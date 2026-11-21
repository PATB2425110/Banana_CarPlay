package com.dbtool.carplay.api.projection

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
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
import android.widget.TextView
import com.dbtool.carplay.api.display.ClusterLayerComposition
import com.dbtool.carplay.api.display.ClusterLayerLayout
import com.dbtool.carplay.api.display.ClusterLayerMode
import com.dbtool.carplay.api.navigation.CarNavigationSnapshot
import com.dbtool.carplay.api.media.CarNowPlaying
import java.io.Closeable
import kotlin.math.min
import kotlin.math.roundToInt

private const val CLUSTER_TAG = "XCExt.carplay-cluster"

/**
 * 把 CarPlay 的地图**投到仪表副屏**（第二块屏）的渲染容器。
 *
 * ## 它做什么
 *
 * 在仪表屏（displayId != 0）上开一个 [Presentation]（系统官方副屏窗口），
 * 里面放一个 [TextureView]。把这个 TextureView 的 `Surface` 交给 iAP2 的第二路
 * 视频流（stream type 111），iPhone 推过来的地图画面就直接画在仪表上。
 *
 * ## 为什么必须用 Presentation，而不能用悬浮窗
 *
 * 1.0.8 实测（吉利 CS11，2026-10-07 21:19:55）曾经用
 * `TYPE_APPLICATION_OVERLAY` 悬浮窗往仪表屏贴，结果：
 *
 * ```
 * addView() -> "Added window"        ✅ 窗口真的加进去了
 * 1ms 后    -> 建窗被拒（固件/权限）  ❌ 我们自己的判定
 * 之后      -> 再无任何 Relayout      ← 系统一个像素都没渲染
 * ```
 *
 * 真因在 DisplayInfo 里：
 *
 * ```
 * Display id 1: DisplayInfo{"HDMI 屏幕", ... FLAG_SECURE,
 *                            FLAG_SUPPORTS_PROTECTED_BUFFERS, FLAG_PRESENTATION ...}
 * ```
 *
 * 仪表那块 HDMI 屏被固件标成**受保护内容显示器**。普通悬浮窗在这类屏上
 * 「允许 addView 但静默不渲染」—— 窗口对象存在，画面永远不出现。
 *
 * `Presentation` 是 Android 专门给副屏的窗口通道（内部仍是 Dialog，但
 * WindowManager 按 `FLAG_PRESENTATION` 屏分类放行），固件的安全策略对它是开门的。
 * 反编译的原厂 `GeelyClusterPresentation extends Presentation` 与上游 DiPlay 的
 * `ClusterMapPresentation : Presentation` 都是这个做法。
 *
 * ## 移植说明
 *
 * - 窗口通道与生命周期照反编译 `GeelyClusterSurfaceOwner.onDisplayChanged()`：
 *   `new GeelyClusterPresentation(context, display, selection, ...).show()`
 * - `Presentation` 子类照上游 `ClusterMapPresentation`：
 *   `window.setBackgroundDrawable(ColorDrawable(TRANSPARENT))` +
 *   `window.clearFlags(FLAG_DIM_BEHIND)`，TextureView 铺满画布
 * - 上游的比亚迪标定（`DiLink51ClusterLayout`）与比亚迪找屏规则未移植；
 *   找屏用本项目既有的 [ClusterDisplayFinder]。
 *
 * ## 生命周期
 *
 * ```
 * attach(displayId, width, height)   // show() 开窗，拿到 Surface -> 调 onSurface
 * setVisible(true)                   // iPhone 首帧到达后才置 true（避免黑屏闪现）
 * detach()                           // dismiss() 关窗
 * ```
 */
class CarPlayClusterProjection(
    private val context: Context,
    /** Surface 变化回调：交给 iAP2 sink 挂/清 stream 111。null 表示要清。 */
    private val onSurface: (Surface?) -> Unit,
    /** 首帧到达回调（用于把 alpha 拉到 1）。 */
    private val onFirstFrame: () -> Unit = {},
    /** 初始图层模式；默认取运行时的待应用值。 */
    layerMode: ClusterLayerMode = ClusterLayerRuntime.mode(),
    /** 当前主题；null = 主题尚未就绪，主题层暂时隐藏。 */
    /** 车辆读数；null = 当前没有可用数据。 */
    private val vitalsProvider: () -> ClusterVitals? = { ClusterLayerRuntime.vitalsProvider() },
    /** 中央导航卡片快照；null = 没有路线 / 开关关闭。 */
    private val navigationProvider: () -> CarNavigationSnapshot? =
        { ClusterLayerRuntime.navigationProvider() },
    /** 应用层注入的皮肤工厂；null 时回退到运行时配置。 */
) : Closeable {

    @Volatile
    private var currentLayerMode: ClusterLayerMode = layerMode

    /** 导航视频层此刻是否显示；主题层完全不受该值影响（2026-10-22 解耦）。 */
    @Volatile
    private var navigationVisible: Boolean = ClusterLayerRuntime.navigationVisible()

    /** 当前是否已投上第一帧。 */
    @Volatile
    var firstFrame: Boolean = false
        private set

    /** 窗口是否已挂上。 */
    @Volatile
    var attached: Boolean = false
        private set

    /** 当前接管的屏 id；未接管时为 [Display.INVALID_DISPLAY]。 */
    @Volatile
    var displayId: Int = Display.INVALID_DISPLAY
        private set

    private var presentation: ClusterPresentation? = null

    /**
     * 在指定屏上开窗。
     *
     * @param width  流缓冲区宽（应与 [com.dbtool.carplay.api.session.AirPlayDisplayConfig] 一致）
     * @param height 流缓冲区高
     * @return 成功与否。失败通常是车机固件不允许在副屏开窗。
     */
    fun attach(displayId: Int, width: Int, height: Int): Boolean {
        if (attached && this.displayId == displayId) return true
        detach()

        val dm = context.getSystemService(DisplayManager::class.java) ?: return false
        val display = dm.getDisplay(displayId) ?: return false

        val screenW = display.mode?.physicalWidth ?: display.width
        val screenH = display.mode?.physicalHeight ?: display.height
        if (screenW <= 0 || screenH <= 0) return false

        currentLayerMode = ClusterLayerRuntime.mode()

        val cell = arrayOfNulls<ClusterPresentation>(1)
        val p = ClusterPresentation(
            context = context,
            display = display,
            canvasWidth = screenW,
            canvasHeight = screenH,
            bufferWidth = width,
            bufferHeight = height,
            initialMode = currentLayerMode,
            initialNavigationVisible = navigationVisible,
            vitalsProvider = vitalsProvider,
            navigationProvider = navigationProvider,
            onSurface = onSurface,
            onFirstFrame = {
                if (!firstFrame) {
                    firstFrame = true
                    cell[0]?.onFirstFrame()
                    onFirstFrame()
                }
            },
        )
        cell[0] = p

        return try {
            p.show()
            presentation = p
            this.displayId = displayId
            attached = true
            firstFrame = false
            ClusterLayerRuntime.register(this)
            true
        } catch (_: RuntimeException) {
            // 固件拒绝（权限/策略/屏不可写）
            runCatching { p.dismiss() }
            false
        }
    }

    /** 关窗并释放 Surface（会通过 [onSurface] 回调 null 让上层清掉 stream 111）。 */
    fun detach() {
        presentation?.let { ClusterLayerRuntime.unregister(this) }
        presentation?.let { runCatching { it.dismiss() } }
        presentation = null
        attached = false
        firstFrame = false
        displayId = Display.INVALID_DISPLAY
    }

    override fun close() = detach()

    /**
     * 即时切换图层模式。
     *
     * 只改可见性、LayoutParams 与 centerReserved；不 dismiss 窗口、不解绑 Surface，
     * 因此 THEME_ONLY 下视频流保持运行，切回 CARPLAY_ONLY 时下一帧即可恢复。
     */
    fun applyLayerMode(mode: ClusterLayerMode) {
        currentLayerMode = mode
        val current = presentation ?: return
        runOnMain {
            if (presentation === current) current.applyLayerMode(mode)
        }
    }

    /**
     * 只切换导航视频层的显隐：不碰主题层、不 dismiss 窗口、不解绑 Surface。
     *
     * 2026-10-22 解耦（P0）：关闭导航投屏时由应用层调用 `false`；
     * 主题层若仍在显示，窗口必须继续挂着。
     */
    fun applyNavigationVisible(visible: Boolean) {
        navigationVisible = visible
        val current = presentation ?: return
        runOnMain {
            if (presentation === current) current.applyNavigationVisible(visible)
        }
    }

    /** 主题/读数出口在应用层完成配置后重绘一次。 */
    internal fun refreshLayerRuntime() {
        val current = presentation ?: return
        runOnMain {
            if (presentation === current) current.refreshLayers()
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            Handler(Looper.getMainLooper()).post(block)
        }
    }

    /** 供日志/诊断用的一行状态。 */
    fun diagnostics(): String =
        "cluster attached=$attached displayId=$displayId firstFrame=$firstFrame " +
            "layerMode=${currentLayerMode.name}"

    companion object {
        /** 运行时配置入口，供不在本次可写范围内的投影控制器之外的应用层接线。 */
        fun configureLayerRuntime(
            vitalsProvider: () -> ClusterVitals?,
            navigationProvider: () -> CarNavigationSnapshot? = { null },
            nowPlayingProvider: () -> CarNowPlaying? = { null },
        ) {
            ClusterLayerRuntime.configure(
                vitalsProvider = vitalsProvider,
                navigationProvider = navigationProvider,
                nowPlayingProvider = nowPlayingProvider,
            )
        }

        /** 导航快照变化时立即刷新已挂载的投影；未挂载的窗口由 200ms tick 兜底。 */
        fun notifyNavigationChanged() {
            ClusterLayerRuntime.notifyNavigationChanged()
        }

        /** 在窗口尚未 attach 时也可调用；模式会保留到窗口建立。 */
        fun applyLayerMode(mode: ClusterLayerMode) {
            ClusterLayerRuntime.applyMode(mode)
        }

        /**
         * 在窗口尚未 attach 时也可调用；导航层显隐会保留到窗口建立。
         *
         * 只影响导航视频层，不会改变主题层或窗口生命周期。
         */
        fun applyNavigationVisible(visible: Boolean) {
            ClusterLayerRuntime.applyNavigationVisible(visible)
        }
    }
}

/**
 * 仪表副屏的 [Presentation]。
 *
 * 绘制结构在 `GeelyClusterPresentation` 的视频层之外增加主题层：
 * ```
 * FrameLayout(root)
 *   ├─ FrameLayout(skinHost, MATCH_PARENT)
 *   ├─ FrameLayout(videoHost, 按模式内缩)
 *   │    └─ TextureView(video, 等比居中)
 *   └─ TextView(placeholder, 仅首帧前显示)
 * ```
 * 与上游 `ClusterMapPresentation` 的差别：上游是比亚迪专用标定（`DiLink51ClusterLayout`），
 * 这里用「流等比缩放居中」这种不依赖车厂标定的通用做法。
 *
 * 窗口属性照上游 `ClusterMapPresentation`：
 * - 背景全透明（`ColorDrawable(TRANSPARENT)`），否则会盖住原厂仪表底图
 * - `clearFlags(FLAG_DIM_BEHIND)`，否则副屏会被压暗
 */
private class ClusterPresentation(
    context: Context,
    display: Display,
    private val canvasWidth: Int,
    private val canvasHeight: Int,
    private val bufferWidth: Int,
    private val bufferHeight: Int,
    initialMode: ClusterLayerMode,
    initialNavigationVisible: Boolean,
    private val vitalsProvider: () -> ClusterVitals?,
    private val navigationProvider: () -> CarNavigationSnapshot?,
    private val onSurface: (Surface?) -> Unit,
    private val onFirstFrame: () -> Unit,
) : Presentation(context, display) {

    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var skinHost: FrameLayout
    private lateinit var videoHost: FrameLayout
    private var video: TextureView? = null
    private var output: Surface? = null
    private var mode: ClusterLayerMode = initialMode

    /** 导航视频层显隐；与 [mode] 正交，主题层不读它（2026-10-22 解耦）。 */
    private var navigationVisible: Boolean = initialNavigationVisible
    private var firstFrameShown = false
    private var lastVideoWidth = -1
    private var lastVideoHeight = -1
    private var lastVideoLeft = -1
    private var lastVideoTop = -1
    private var lastTextureWidth = -1
    private var lastTextureHeight = -1

    /**
     * 首帧到达前的占位文字。
     *
     * ## 为什么必须有（PATCH-0030 根因）
     *
     * 窗口里若只有一块 `alpha=0` 的 TextureView，下面露出的就是**窗口自己的底**；
     * 而 `Presentation` 在副屏上默认底色是黑的 ⇒ 用户看到「连接成功就黑屏」。
     * 上游 `ClusterMapPresentation` 用 `waitingLabel` 填这个空窗，这里对齐。
     */
    private var placeholder: TextView? = null

    private val refreshTick = object : Runnable {
        override fun run() {
            if (!isShowing) return
            refreshLayers()
            mainHandler.postDelayed(this, VITALS_REFRESH_MILLIS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 透明窗口：不要盖住原厂仪表界面，也不要触发副屏调光。
        window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            // PATCH-0030：整窗默认全透明 —— 首帧到达前不让任何像素出现。
            // 上游同解（`ClusterMapPresentation.setMapVisible`）。缺这行时，
            // 空窗会以窗口默认底色（黑）呈现 = 用户报的"连接成功就黑屏"。
            w.attributes = w.attributes.apply { alpha = 0f }
        }

        val root = FrameLayout(context).apply { setBackgroundColor(Color.TRANSPARENT) }

        skinHost = FrameLayout(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            visibility = View.INVISIBLE
        }
        root.addView(
            skinHost,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        val texture = TextureView(context).apply {
            isOpaque = false
            alpha = 0f // 首帧到达前全透明，避免黑屏闪现
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(t: SurfaceTexture, w: Int, h: Int) {
                    releaseOutput()
                    t.setDefaultBufferSize(bufferWidth, bufferHeight)
                    output = Surface(t).also(onSurface)
                }

                override fun onSurfaceTextureSizeChanged(t: SurfaceTexture, w: Int, h: Int) = Unit

                override fun onSurfaceTextureUpdated(t: SurfaceTexture) = onFirstFrame()

                override fun onSurfaceTextureDestroyed(t: SurfaceTexture): Boolean {
                    releaseOutput()
                    return true
                }
            }
        }
        video = texture

        videoHost = FrameLayout(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            addView(texture, FrameLayout.LayoutParams(1, 1, Gravity.CENTER))
        }
        root.addView(
            videoHost,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START,
            ),
        )

        // PATCH-0030：首帧前的占位。对齐上游 `waitingLabel` 的语义，
        // 但文案用中文（上游是英文资源），且不引入额外资源文件。
        val label = TextView(context).apply {
            text = "正在接收仪表画面…"
            setTextColor(Color.WHITE)
            textSize = 24f
            gravity = Gravity.CENTER
        }
        root.addView(
            label,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START,
            ),
        )
        placeholder = label

        setContentView(root)
        refreshLayers()
        mainHandler.postDelayed(refreshTick, VITALS_REFRESH_MILLIS)
    }

    fun applyLayerMode(next: ClusterLayerMode) {
        mode = next
        refreshLayers(logLayer = true)
    }

    fun applyNavigationVisible(visible: Boolean) {
        navigationVisible = visible
        refreshLayers(logLayer = true)
    }

    fun onFirstFrame() {
        firstFrameShown = true
        refreshLayers()
    }

    fun refreshLayers(logLayer: Boolean = false) {
        if (!::skinHost.isInitialized) return

        // 2026-10-22 解耦（P0）：主题显隐只由 mode 决定；导航层再叠加运行时开关。
        // 窗口可见性由两者并集计算（见 layout.windowVisible），不再由视频单边决定。
        val layout = ClusterLayerComposition.layoutFor(
            themeVisible = false,
            navigationVisible = mode.showsNavigation && navigationVisible,
            panelWidth = canvasWidth,
            panelHeight = canvasHeight,
        )
        val themeVisible = false
        skinHost.visibility = if (themeVisible) View.VISIBLE else View.INVISIBLE
        videoHost.visibility = if (layout.videoVisible) View.VISIBLE else View.INVISIBLE


        updateVideoGeometry(layout)
        video?.alpha = if (firstFrameShown) 1f else 0f
        placeholder?.visibility =
            if (layout.videoVisible && !firstFrameShown) View.VISIBLE else View.INVISIBLE
        setWindowVisible(themeVisible || layout.videoVisible)

        if (logLayer) {
            val inset = layout.videoInset
            Log.i(
                CLUSTER_TAG,
                "仪表图层：mode=${mode.name} themeVisible=$themeVisible " +
                    "videoVisible=${layout.videoVisible} " +
                    "inset=(${inset.left},${inset.top},${inset.right},${inset.bottom})",
            )
        }
    }


    private fun updateVideoGeometry(layout: ClusterLayerLayout) {
        val inset = layout.videoInset
        val width = (canvasWidth - inset.left - inset.right).coerceAtLeast(1)
        val height = (canvasHeight - inset.top - inset.bottom).coerceAtLeast(1)
        if (width != lastVideoWidth || height != lastVideoHeight ||
            inset.left != lastVideoLeft || inset.top != lastVideoTop
        ) {
            lastVideoWidth = width
            lastVideoHeight = height
            lastVideoLeft = inset.left
            lastVideoTop = inset.top
            videoHost.layoutParams = FrameLayout.LayoutParams(
                width,
                height,
                Gravity.TOP or Gravity.START,
            ).apply {
                leftMargin = inset.left
                topMargin = inset.top
            }
            placeholder?.layoutParams = FrameLayout.LayoutParams(
                width,
                height,
                Gravity.TOP or Gravity.START,
            ).apply {
                leftMargin = inset.left
                topMargin = inset.top
            }
        }

        val scale = min(
            width.toDouble() / bufferWidth.coerceAtLeast(1),
            height.toDouble() / bufferHeight.coerceAtLeast(1),
        )
        val textureWidth = (bufferWidth * scale).roundToInt().coerceIn(1, width)
        val textureHeight = (bufferHeight * scale).roundToInt().coerceIn(1, height)
        if (textureWidth != lastTextureWidth || textureHeight != lastTextureHeight) {
            lastTextureWidth = textureWidth
            lastTextureHeight = textureHeight
            video?.layoutParams = FrameLayout.LayoutParams(
                textureWidth,
                textureHeight,
                Gravity.CENTER,
            )
        }
    }

    /**
     * 窗口级整窗透明度。
     *
     * ## 为什么视图层 alpha 不够
     *
     * `TextureView.alpha` 只让**纹理层**透明，窗口底下的东西照样被窗口自己的
     * 背景挡住；副屏 `Presentation` 的默认底是不透明的。必须同时调
     * `window.attributes.alpha`。上游 `setMapVisible` 就是这个作用。
     */
    fun setWindowVisible(visible: Boolean) {
        window?.let { w ->
            val alpha = if (visible) 1f else 0f
            if (w.attributes.alpha != alpha) {
                w.attributes = w.attributes.apply { this.alpha = alpha }
            }
        }
    }

    override fun onStop() {
        // 副屏被系统收回（拔线/切模式）时，通知上层清掉 stream 111。
        mainHandler.removeCallbacks(refreshTick)
        releaseOutput()
        super.onStop()
    }

    private fun releaseOutput() {
        if (output == null) return
        runCatching { onSurface(null) }
        runCatching { output?.release() }
        output = null
    }

    private companion object {
        const val VITALS_REFRESH_MILLIS = 200L
    }
}

/**
 * 仪表屏发现：候选清单 + 「该用哪块」的判据。
 *
 * ## 2026-10-08 第二次修正（选屏 / 投屏失效的直接根因）
 *
 * PATCH-0033 把「自动挑面积最大的演示屏」改成「用户指定优先」，方向对，
 * 但留了两个洞，现场都撞上了：
 *
 * 1. **未选屏时仍会自动挑一块**。那正是"抓错屏 / 白块"的病根 ——
 *    反编译原版 `GeelyDisplaySelector.select` 用的是 `singleOrNull`（**唯一命中**），
 *    从来不会在多块候选里"挑一块"。
 * 2. **主屏（displayId=0）也被当成候选**。原版判据里 `!isDefault` 是**硬条件**；
 *    把主屏列进清单，用户一旦选中，窗口就开在主屏上（1920×720 白块）。
 *
 * 现在与原版逐条对齐：
 *
 * | 情形 | 结果 |
 * | --- | --- |
 * | 用户选定 id，且该屏是非主屏且在场 | 用它 |
 * | 用户选定 id，但该屏不在 / 是主屏 | **-1（不开窗）** |
 * | 未选（-1） | **-1（无论候选几块，都交给用户去选）** |
 *
 * ## 2026-10-10 第三次修正（车主口径：只认人选的）
 *
 * 上一版留了一条"未选 + 恰好一块非主屏 ⇒ 自动用它（首次可用）"。车主明确要求
 * **由用户自行选择、不要自动选**，于是这条兜底删掉：**未选（-1）恒返回 -1**。
 *
 * 理由不是洁癖，是代价不对称：
 * - 自动挑错的后果 —— 画面跑到别的屏上（现场出现"主屏上出现副屏画面 / 白块"），
 *   用户根本不知道发生了什么，只会报"投屏坏了"；
 * - 不自动挑的后果 —— 用户进设置点一下"哪块屏"，多花 10 秒。
 *
 * 与原版 `GeelyDisplaySelector.select`（`singleOrNull`，唯一命中时自动用）**有意不同**：
 * 原版假设车上仪表屏有且只有一块，这个前提在实车上不成立。
 *
 * ⇒ 「未选屏」永远不等于「随便找一块」，也不再需要
 * `DISPLAY_CATEGORY_PRESENTATION` 那套启发式。
 */
object ClusterDisplayFinder {

    /**
     * 主屏（内置屏）的 display id，恒为 0，与 `Display.DEFAULT_DISPLAY` 同值。
     *
     * 写成常量而不是引用 `Display.DEFAULT_DISPLAY`：下面的 [pickDisplayId] 是
     * **纯函数**，要能在 JVM 单测里跑，不该碰 `Display`（android.jar 的 stub）。
     */
    const val DEFAULT_DISPLAY_ID: Int = 0

    /**
     * 一块屏的最小信息（纯数据，无 Android 依赖）。
     *
     * 存在的意义：让「选哪块屏」的判据可以在 JVM 单测里跑（[pickDisplayId]），
     * 不必起 Robolectric / 真机。
     */
    data class ClusterDisplayInfo(
        val displayId: Int,
        val width: Int,
        val height: Int,
    )

    /**
     * 全部有效显示（**含主屏**）。
     *
     * ⚠️ 只用于诊断展示（开发者页导出 display 清单）。**不要**拿它当副屏候选 ——
     * 那会把主屏混进选择器，选中即在主屏开窗。候选一律走 [selectable]。
     */
    fun all(context: Context): List<Display> {
        val dm = context.getSystemService(DisplayManager::class.java) ?: return emptyList()
        return dm.displays
            ?.filter { it.isValid && (it.mode?.physicalWidth ?: it.width) > 0 }
            ?.sortedBy { it.displayId }
            .orEmpty()
    }

    /**
     * **可作仪表副屏**的候选：非主屏 + 有效 + 有尺寸。
     *
     * 这是设置页「选择仪表副屏」列表的数据源，也是 [best] 的取值池。
     * 与原版 `GeelyDisplaySelector.candidates` 的过滤条件一致（`!isDefault`）。
     */
    fun selectable(context: Context): List<Display> =
        all(context).filter { it.displayId != DEFAULT_DISPLAY_ID }

    /**
     * 选出这次该把副屏窗口挂在哪块屏上。
     *
     * 判据**只有一处**：[pickDisplayId]。本函数只是把它套在真实 `DisplayManager`
     * 上 —— 这样单测钉住的判据与线上跑的判据必然同源（不再有两份会走偏的逻辑）。
     *
     * 返回 null ⇒ **不开窗**（用户还没选屏、选定的屏不在、或用户选的是主屏）。
     * 判据里**没有**任何"替他挑一块"的分支——见 [pickDisplayId]。
     */
    fun best(context: Context, requestedId: Int = -1): Display? {
        val displays = all(context)
        val picked = pickDisplayId(
            requestedId = requestedId,
            availableIds = displays.map {
                ClusterDisplayInfo(
                    displayId = it.displayId,
                    width = it.mode?.physicalWidth ?: it.width,
                    height = it.mode?.physicalHeight ?: it.height,
                )
            },
        )
        if (picked < 0) return null
        return displays.firstOrNull { it.displayId == picked && it.isValid }
    }

    /**
     * 选屏判据的**纯函数**版本：不碰任何 Android 类型，便于单测。
     *
     * 参数 [availableIds] 是「当前所有有效显示」的 id + 尺寸（`ClusterDisplayInfo`）。
     * 返回 -1 表示「一块都不该用」（不该开窗）。
     *
     * 规则（[best] 就是把它套在真实 DisplayManager 上）：
     * 1. **未选（[requestedId] < 0）⇒ 恒 -1**：一块候选都不挑，等用户去设置里选。
     * 2. [requestedId] >= 0：先剔除主屏（[DEFAULT_DISPLAY_ID]）—— 主屏永远不是副屏；
     *    剔除后还找得到才返回它，否则 -1。
     */
    fun pickDisplayId(
        requestedId: Int,
        availableIds: List<ClusterDisplayInfo>,
    ): Int {
        // 未选屏 ⇒ 不挑。**无论候选几块**（只有一块也不挑）：车主口径"由用户自行选择"。
        // 曾经这里有一条 `pool.size == 1 ⇒ 自动用它`（原版 singleOrNull 语义），
        // 已按其要求删除，见类注释「2026-10-10 第三次修正」。
        if (requestedId < 0) return -1
        // 主屏永远不是副屏：即便调用方把 0 当有效 id 传进来，也要在这里被剔掉。
        val pool = availableIds.filter { it.displayId != DEFAULT_DISPLAY_ID }
        return if (pool.any { it.displayId == requestedId }) requestedId else -1
    }

    /** 供日志/诊断用的一行描述。 */
    fun describe(display: Display): String {
        val w = display.mode?.physicalWidth ?: display.width
        val h = display.mode?.physicalHeight ?: display.height
        return "displayId=${display.displayId} name=${display.name} ${w}x$h"
    }
}
