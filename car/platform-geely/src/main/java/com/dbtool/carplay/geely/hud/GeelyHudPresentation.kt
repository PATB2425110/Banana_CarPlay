package com.dbtool.carplay.geely.hud

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import com.dbtool.carplay.api.display.HudCropSpec
import com.dbtool.carplay.api.display.HudScaleMode

/**
 * HUD 画面窗口（2026-10-22，0B 探针路线落地）。
 *
 * ## 路线已被实车确认
 *
 * 2026-10-21 的 0B 探针在 display 3 上用 `Presentation` 得到：
 *
 * ```json
 * {"mode":"presentation","displayId":3,"bounds":"0,0 1024x274",
 *  "windowAttached":true,"rendering":true,
 *  "sfLayer":"DBPlay-HUD-PROBE-PRESENTATION","errors":[]}
 * ```
 *
 * 同一轮 `TYPE_APPLICATION_OVERLAY` 虽然 `addView()` 返回成功，但建窗被系统拒绝、
 * 一个像素都没渲染。**因此这里只用 `Presentation`，不提供 overlay 回退。**
 *
 * ## 与仪表副屏窗口（`GeelyClusterPresentation`）的刻意差异
 *
 * | | 仪表副屏 | HUD |
 * | --- | --- | --- |
 * | 尺寸 | 1920×1080（可见 720） | 1024×274 |
 * | 背景 | 透明（要透出原厂仪表） | 不透明黑（HUD 没有底层内容） |
 * | 焦点 | 不抢 | **明确 `NOT_FOCUSABLE / NOT_TOUCHABLE`** |
 * | 画面 | 原样铺满 | `TextureView.setTransform` 裁剪 + FIT/FILL |
 *
 * ## 首帧看门狗
 *
 * `windowAttached=true` 与 `rendering=true` 只证明「窗口挂上 / Surface 收帧」，
 * **不证明 HUD 玻璃上真有像素**（`FLAG_SECURE` 让 `screencap` 截不到，只能肉眼）。
 * 看门狗只负责把「5 秒还没有一帧」这件事写进状态/日志，绝不把
 * [com.dbtool.carplay.api.display.HudVisibleOnHud] 置为 CONFIRMED。
 */
class GeelyHudPresentation(
    displayContext: Context,
    private val hudDisplay: Display,
    private val widthPixels: Int,
    private val heightPixels: Int,
    crop: HudCropSpec,
    scaleMode: HudScaleMode,
    private val onSurface: (Surface?) -> Unit,
    private val onFirstFrame: () -> Unit,
    private val onFirstFrameTimeout: () -> Unit,
    private val onSurfaceInvalidated: () -> Unit,
    private val log: (String) -> Unit,
) : Presentation(displayContext, hudDisplay) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val surfaceHost = HudSurfaceHost(onSurface)

    private var texture: TextureView? = null
    private var currentCrop: HudCropSpec = crop
    private var currentScaleMode: HudScaleMode = scaleMode

    private var released = false
    private var firstFrameShown = false
    private var frameUpdateCount = 0
    private var watchdogFired = false
    private var surfacePublished = false

    private val firstFrameWatchdog = Runnable {
        if (released || firstFrameShown) return@Runnable
        watchdogFired = true
        log(
            "HUD 首帧看门狗超时：${FIRST_FRAME_TIMEOUT_MS}ms 内 TextureView 未收到画面 " +
                "（${diagnosticSnapshot()}）",
        )
        onFirstFrameTimeout()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        log(
            "HUD 窗口 onCreate：displayId=${hudDisplay.displayId} " +
                "面板=${widthPixels}x${heightPixels} name=${hudDisplay.name}",
        )
        window?.apply {
            @Suppress("DEPRECATION")
            setFormat(PixelFormat.OPAQUE)
            @Suppress("DEPRECATION")
            setBackgroundDrawable(ColorDrawable(Color.BLACK))
            // HUD 是纯输出面：不抢焦点、不接触摸。否则会把主屏的输入焦点吸走。
            addFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            )
            // 诊断：把实际生效的窗口属性回读出来（设了不等于生效）。
            log(
                "HUD 窗口属性：format=${attributes.format} flags=0x" +
                    Integer.toHexString(attributes.flags),
            )
        }

        val root = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
        val view = TextureView(context).apply {
            // HUD 没有底层内容，不透明即可；透明会多一次混合，且可能露出未初始化的绿/黑块。
            isOpaque = true
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(
                    surfaceTexture: android.graphics.SurfaceTexture,
                    width: Int,
                    height: Int,
                ) {
                    if (released) return
                    log("HUD SurfaceTexture available ${width}x$height")
                    // 必须在交付 Surface 之前把矩阵设好，否则首帧会按未裁剪画面闪一下。
                    applyCrop()
                    surfacePublished = true
                    surfaceHost.publish(Surface(surfaceTexture))
                    mainHandler.removeCallbacks(firstFrameWatchdog)
                    mainHandler.postDelayed(firstFrameWatchdog, FIRST_FRAME_TIMEOUT_MS)
                }

                override fun onSurfaceTextureSizeChanged(
                    surfaceTexture: android.graphics.SurfaceTexture,
                    width: Int,
                    height: Int,
                ) {
                    log("HUD SurfaceTexture sizeChanged ${width}x$height")
                    applyCrop()
                }

                override fun onSurfaceTextureDestroyed(
                    surfaceTexture: android.graphics.SurfaceTexture,
                ): Boolean {
                    log(
                        "HUD SurfaceTexture destroyed surface=${surfaceHost.currentSurface} " +
                            "firstFrameShown=$firstFrameShown released=$released",
                    )
                    mainHandler.removeCallbacks(firstFrameWatchdog)
                    surfacePublished = false
                    surfaceHost.publish(null)
                    // 返回 true：由框架释放 SurfaceTexture；我们包装的 Surface 已由
                    // HudSurfaceHost 释放（两者是不同对象，不能混为一谈）。
                    if (!released) onSurfaceInvalidated()
                    return true
                }

                override fun onSurfaceTextureUpdated(
                    surfaceTexture: android.graphics.SurfaceTexture,
                ) {
                    frameUpdateCount += 1
                    if (!firstFrameShown) {
                        firstFrameShown = true
                        mainHandler.removeCallbacks(firstFrameWatchdog)
                        log("HUD 首帧到达：帧更新 #$frameUpdateCount")
                        onFirstFrame()
                    } else if (frameUpdateCount % FRAME_LOG_INTERVAL == 0) {
                        log("HUD 帧更新 #$frameUpdateCount")
                    }
                }
            }
        }
        view.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            if (right - left != 0 && bottom - top != 0) applyCrop()
        }
        root.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        setContentView(root)
        texture = view
        // 视图尺寸要等第一次 layout 才有值；这里先排一次，layout 回调会再补。
        view.post { applyCrop() }
    }

    override fun onStart() {
        super.onStart()
        window?.apply {
            setTitle(LAYER_TITLE)
            setLayout(widthPixels, heightPixels)
            setGravity(Gravity.TOP or Gravity.START)
        }
        log("HUD 窗口 onStart：isShowing=$isShowing surfacePublished=$surfacePublished")
    }

    /**
     * 窗口停止时**不解绑 Surface**（与仪表副屏同一口径）。
     *
     * 车机把 HUD 收回再交还时，`TextureView` 不会重新触发
     * `onSurfaceTextureAvailable`；在这里解绑会让 HUD 永久空窗。
     */
    override fun onStop() {
        super.onStop()
        log("HUD 窗口 onStop：Surface 保持绑定（firstFrameShown=$firstFrameShown）")
    }

    /** 就地更新裁剪（下一帧生效，不重建窗口、不解绑 Surface）。 */
    fun setCrop(crop: HudCropSpec) {
        currentCrop = crop
        applyCrop()
    }

    /** 就地更新缩放方式（FIT/FILL/STRETCH）。 */
    fun setScaleMode(mode: HudScaleMode) {
        currentScaleMode = mode
        applyCrop()
    }

    /** 当前是否已经收到过至少一帧（只代表 Surface 收帧，不代表 HUD 玻璃可见）。 */
    fun hasFirstFrame(): Boolean = firstFrameShown

    fun isReleased(): Boolean = released

    fun diagnosticSnapshot(): String =
        "displayId=${hudDisplay.displayId} 屏=${widthPixels}x$heightPixels " +
            "视图=${texture?.width}x${texture?.height} " +
            "surface=${surfaceHost.currentSurface} " +
            "surfaceValid=${surfaceHost.currentSurface?.isValid} " +
            "isShowing=$isShowing firstFrameShown=$firstFrameShown " +
            "frameUpdates=$frameUpdateCount watchdogFired=$watchdogFired " +
            "crop=${currentCrop.leftFraction},${currentCrop.topFraction}," +
            "${currentCrop.rightFraction},${currentCrop.bottomFraction} " +
            "scaleMode=${currentScaleMode.name} released=$released"

    /** 释放：先解绑 + 释放 Surface，再 dismiss（顺序与会话解绑契约一致）。 */
    fun release() {
        if (released) return
        released = true
        mainHandler.removeCallbacks(firstFrameWatchdog)
        log("HUD 窗口释放 release() ${diagnosticSnapshot()}")
        surfaceHost.release()
        runCatching { dismiss() }
    }

    private fun applyCrop() {
        val view = texture ?: return
        val applied = applyHudCropTransform(view, currentCrop, currentScaleMode)
        if (applied) {
            log(
                "HUD 裁剪：crop=(${currentCrop.leftFraction},${currentCrop.topFraction}," +
                    "${currentCrop.rightFraction},${currentCrop.bottomFraction}) " +
                    "mode=${currentScaleMode.name} 视图=${view.width}x${view.height}",
            )
        }
    }

    companion object {
        private const val TAG = "XCExt.hud-projection"

        /** Surface 挂上后多久没帧算超时。与仪表守护链路的 5s 同口径。 */
        const val FIRST_FRAME_TIMEOUT_MS = 5_000L

        /** 帧更新日志间隔（60Hz 下每 5 秒一条，避免刷屏）。 */
        private const val FRAME_LOG_INTERVAL = 300

        /** SurfaceFlinger 里能认出来的层名（诊断用）。 */
        const val LAYER_TITLE = "DBPlay-HUD-PROJECTION"

        /**
         * 在 logcat 里统一前缀：HUD 与仪表（`XCExt.carplay-cluster`）分开过滤。
         */
        fun logTag(): String = TAG
    }
}
