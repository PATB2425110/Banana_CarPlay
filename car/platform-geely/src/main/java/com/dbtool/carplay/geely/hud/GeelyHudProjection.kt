package com.dbtool.carplay.geely.hud

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.dbtool.carplay.api.display.CarHudDisplayMode
import com.dbtool.carplay.api.display.CarHudDisplayProfile
import com.dbtool.carplay.api.display.CarHudDisplayStatus
import com.dbtool.carplay.api.display.CarHudProjection
import com.dbtool.carplay.api.display.CarHudStartResult
import com.dbtool.carplay.api.display.HudVisibleOnHud
import java.util.concurrent.Executors

/**
 * 吉利 HUD 画面投屏协调器（2026-10-22）。
 *
 * 职责链：
 *
 * ```
 * CarAppController.applyHudProjection()
 *        ↓  start(profile, clusterWindowActive, onSurface)
 * GeelyHudProjection
 *        ├── HudDisplayRouter       按 1024×274 + FLAG_PRESENTATION 选屏（纯逻辑）
 *        ├── GeelyHudPresentation   Presentation + TextureView + GPU 裁剪
 *        └── HudSurfaceHost         Surface create/release 成对
 *        ↓  onSurface
 * CarPlaySessionBridge.attachHudVideoSurface()
 *        ↓
 * AndroidMediaSink.setMirrorSurface(111, "hud", surface)
 * ```
 *
 * ## 关键纪律
 *
 * 1. 一律用 `applicationContext.createDisplayContext(display)`，**不持 Activity**
 *    —— 避免 HUD 窗口把已销毁的 Activity 钉在内存里；
 * 2. 找不到 1024×274 的屏就报 [CarHudProjection.HUD_NOT_FOUND]，
 *    **绝不回退 display 2**（那是仪表，不是 HUD 玻璃）；
 * 3. 共享 111 时若仪表窗口也挂着，会多一个镜像解码实例（三路解码）。这是
 *    已知代价，只在日志里留痕，不阻断；
 * 4. `visibleOnHud` 永远保持 UNCONFIRMED —— 首帧只证明 Surface 收帧，
 *    `FLAG_SECURE` 让截图不可用，只能实车肉眼确认。
 */
class GeelyHudProjection(
    context: Context,
    private val log: (String) -> Unit,
) : CarHudProjection {

    private val appContext: Context = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val router = HudDisplayRouter()

    /** 首帧超时后请求 111 关键帧——网络写必须在后台线程（PATCH-0106 同一教训）。 */
    private val keyFrameExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "dbplay-hud-keyframe").apply { isDaemon = true }
    }

    private var presentation: GeelyHudPresentation? = null
    private var displayId: Int = -1
    private var profile: CarHudDisplayProfile? = null
    private var onSurface: ((Surface?) -> Unit)? = null
    private var clusterWindowActive: Boolean = false

    @Volatile
    private var status: CarHudDisplayStatus = CarHudDisplayStatus()

    private var streamActiveListener: ((Boolean) -> Unit)? = null
    private var keyFrameRequester: (() -> Boolean)? = null
    private var keyFrameRequestedForTimeout = false
    private var rebuildScheduled = false

    // ------------------------------------------------------------ CarHudProjection

    override fun isAvailable(): Boolean =
        router.route(HudDisplays.query(appContext)) is HudDisplayRoute.Found

    override fun start(
        profile: CarHudDisplayProfile,
        clusterWindowActive: Boolean,
        onSurface: (Surface?) -> Unit,
    ): CarHudStartResult {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            // Presentation.show() 必须在主线程；不在主线程时**不假装成功**。
            return CarHudStartResult.Unavailable(CarHudProjection.REASON_MAIN_THREAD_REQUIRED)
        }
        this.profile = profile
        this.onSurface = onSurface
        this.clusterWindowActive = clusterWindowActive

        val route = router.route(HudDisplays.query(appContext))
        if (route is HudDisplayRoute.Unavailable) {
            status = status.copy(
                windowAttached = false,
                rendering = false,
                configReady = false,
                lastFailure = route.reason,
            )
            log("HUD 投屏：未找到可用 HUD 屏（${route.reason}）—— 不回退 display 2")
            return CarHudStartResult.Unavailable(route.reason)
        }
        val descriptor = (route as HudDisplayRoute.Found).display

        val existing = presentation
        if (existing != null && displayId == descriptor.displayId && existing.isShowing) {
            existing.setCrop(profile.crop)
            existing.setScaleMode(profile.scale.mode)
            return CarHudStartResult.Started
        }

        stopInternal(reason = "重建窗口")
        val display = HudDisplays.display(appContext, descriptor.displayId)
        if (display == null) {
            status = status.copy(lastFailure = CarHudProjection.HUD_NOT_FOUND)
            log("HUD 投屏：display ${descriptor.displayId} 在枚举后消失")
            return CarHudStartResult.Unavailable(CarHudProjection.HUD_NOT_FOUND)
        }

        // ⚠️ 一律 applicationContext：controller/Activity 不持有 HUD 窗口。
        val displayContext = appContext.createDisplayContext(display)
        val created = runCatching {
            GeelyHudPresentation(
                displayContext = displayContext,
                hudDisplay = display,
                widthPixels = descriptor.widthPixels,
                heightPixels = descriptor.heightPixels,
                crop = profile.crop,
                scaleMode = profile.scale.mode,
                onSurface = { surface -> this.onSurface?.invoke(surface) },
                onFirstFrame = {
                    status = status.copy(rendering = true)
                    log("HUD 投屏：Surface 已收到首帧（HUD 玻璃是否可见仍待肉眼确认）")
                },
                onFirstFrameTimeout = { onFirstFrameTimedOut() },
                onSurfaceInvalidated = { scheduleRebuild() },
                log = log,
            )
        }.getOrElse { error ->
            status = status.copy(lastFailure = "PRESENTATION_FAILED: ${error.message}")
            log("HUD 投屏：创建 Presentation 失败 ${error.javaClass.simpleName}: ${error.message}")
            return CarHudStartResult.Unavailable("PRESENTATION_FAILED")
        }

        return runCatching {
            created.show()
            presentation = created
            displayId = descriptor.displayId
            keyFrameRequestedForTimeout = false
            status = CarHudDisplayStatus(
                streamActive = status.streamActive,
                configReady = false,
                windowAttached = true,
                rendering = false,
                visibleOnHud = HudVisibleOnHud.UNCONFIRMED,
                lastFailure = null,
            )
            log(
                "HUD 投屏：窗口已挂载 displayId=${descriptor.displayId} " +
                    "面板=${descriptor.widthPixels}x${descriptor.heightPixels} " +
                    "name=${descriptor.name} flags=0x${Integer.toHexString(descriptor.flags)}",
            )
            if (clusterWindowActive && profile.mode == CarHudDisplayMode.SHARED_111) {
                // 允许，但必须留痕：这是 v3 评审确认的「三路解码」代价。
                log(
                    "HUD 投屏：仪表窗口已挂载 ⇒ ${CarHudProjection.REASON_SHARED_111_THREE_DECODER}" +
                        "（110 主 + 111 主 + 111 mirror；降 HUD 分辨率不减实例，属无效降级）",
                )
            }
            CarHudStartResult.Started
        }.getOrElse { error ->
            runCatching { created.release() }
            status = status.copy(
                windowAttached = false,
                rendering = false,
                lastFailure = "SHOW_FAILED: ${error.message}",
            )
            log("HUD 投屏：Presentation.show() 失败 ${error.javaClass.simpleName}: ${error.message}")
            CarHudStartResult.Unavailable("SHOW_FAILED")
        }
    }

    override fun updateProfile(profile: CarHudDisplayProfile) {
        this.profile = profile
        val current = presentation ?: return
        current.setCrop(profile.crop)
        current.setScaleMode(profile.scale.mode)
    }

    override fun stop() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { stopInternal(reason = "stop()（跨线程投递）") }
            return
        }
        stopInternal(reason = "stop()")
    }

    override fun status(): CarHudDisplayStatus = status

    override fun onScreenStreamActive(active: Boolean) {
        mainHandler.post {
            status = status.copy(streamActive = active)
            log("HUD 投屏：111 流 ${if (active) "已建立" else "已断开"}")
        }
        // 回调可能来自协议读线程；监听者（controller）自己负责切后台再发网络命令。
        runCatching { streamActiveListener?.invoke(active) }
    }

    override fun onConfigReady(ready: Boolean) {
        mainHandler.post {
            status = status.copy(configReady = ready)
            log("HUD 投屏：镜像解码器 config ${if (ready) "已就绪" else "已失效"}")
        }
    }

    override fun setStreamActiveListener(listener: ((Boolean) -> Unit)?) {
        streamActiveListener = listener
    }

    override fun setKeyFrameRequester(requester: (() -> Boolean)?) {
        keyFrameRequester = requester
    }

    override fun release() {
        val sink = onSurface
        stop()
        // 跨线程 stop 是投递执行的：这里同步补一次解绑，保证调用返回后
        // 上层记录里不会再留着一条指向已关闭窗口的 Surface。
        sink?.invoke(null)
        keyFrameExecutor.shutdownNow()
        streamActiveListener = null
        keyFrameRequester = null
        onSurface = null
    }

    // ------------------------------------------------------------ 内部

    private fun onFirstFrameTimedOut() {
        if (keyFrameRequestedForTimeout) return
        keyFrameRequestedForTimeout = true
        status = status.copy(lastFailure = "FIRST_FRAME_TIMEOUT")
        log("HUD 投屏：首帧超时，请求一次 111 关键帧")
        val requester = keyFrameRequester ?: return
        if (keyFrameExecutor.isShutdown) return
        runCatching {
            keyFrameExecutor.execute {
                val sent = runCatching { requester() }.getOrDefault(false)
                log("HUD 投屏：关键帧请求 sent=$sent")
            }
        }
    }

    /**
     * SurfaceTexture 被系统回收：5 秒后重建一次窗口。
     *
     * 不立即重建：`onSurfaceTextureDestroyed` 之后框架通常会紧接着交付新的
     * `onSurfaceTextureAvailable`；立刻 dismiss 会吞掉那次回调（PATCH-0061 的
     * 实测教训）。延迟一次重建既避开这个窗口，又保证真的丢了通道时能自愈。
     */
    private fun scheduleRebuild() {
        if (rebuildScheduled) return
        rebuildScheduled = true
        mainHandler.postDelayed({
            rebuildScheduled = false
            val savedProfile = profile ?: return@postDelayed
            val savedSink = onSurface ?: return@postDelayed
            if (!savedProfile.enabled) return@postDelayed
            log("HUD 投屏：Surface 通道被回收，重建窗口")
            stopInternal(reason = "Surface 失效重建")
            start(savedProfile, clusterWindowActive, savedSink)
        }, REBUILD_DELAY_MS)
    }

    private fun stopInternal(reason: String) {
        val current = presentation
        presentation = null
        displayId = -1
        current?.release()
        // 窗口层会在 release 里补 onSurface(null)；这里再兜一次，防止窗口
        // 根本没建起来（presentation==null）时上层还留着上一轮的 Surface。
        onSurface?.invoke(null)
        status = status.copy(
            windowAttached = false,
            rendering = false,
            configReady = false,
        )
        log("HUD 投屏：窗口已关闭（$reason）")
    }

    private companion object {
        /**
         * Surface 被回收后的重建延迟。
         *
         * 500ms 是经验值：足够让框架把紧随其后的 `onSurfaceTextureAvailable`
         * 走完，又不至于让用户长时间看到空 HUD。
         */
        const val REBUILD_DELAY_MS = 500L
    }
}
