package com.dbtool.carplay.bridge

import android.content.Context
import android.util.Log
import android.view.Surface
import com.dbtool.carplay.api.display.CarDisplay
import com.shilapi.xcertplay.airplay.MediaSink
import com.shilapi.xcertplay.media.AndroidMediaSink

/**
 * 视频/音频渲染槽位。
 *
 * 这是"以后能不能不碰 Core 换掉媒体实现"的关键接缝：
 * - 默认实现 [AndroidCarPlayMediaSink] 直接复用 upstream `AndroidMediaSink`
 *   （MediaCodec → Surface / AudioTrack），行为与上游原版**完全一致**。
 * - 将来要做车机硬件视频层、外接音频 DSP、或替换解码策略时，
 *   实现本接口并在构造 bridge 时换掉 factory 即可，Core 一行不改。
 *
 * 之所以把"Surface 绑定"也放进本接口：Surface 生命周期归渲染实现所有，
 * 让 UI 直接拿 upstream `AndroidMediaSink` 会立刻破坏解耦。
 */
interface CarPlayMediaSink {
    /** 交给 upstream `CarPlayMediaEngine` 的协议侧接收器。 */
    val mediaSink: MediaSink
    /** Synchronous local gain for media tracks; false means unsupported/failed. */
    fun setMediaOutputGain(gain: Float): Boolean = false

    /** 绑定/解绑视频输出 Surface。 */
    fun attachSurface(surface: Surface?)

    /**
     * 绑定/解绑**仪表副屏**的视频输出 Surface（2026-10-07，吉利 HDMI2 移植）。
     *
     * 上游协议层把副屏当作独立视频流（`STREAM_TYPE_ALT_SCREEN = 111`）。
     * 默认实现把副屏 Surface 也绑到 ALT 轨——见 [AndroidCarPlayMediaSink]。
     * 传 null 表示副屏 Surface 已销毁（窗口关闭 / 屏拔出）。
     *
     * 默认空实现：不关心副屏的渲染实现（如第三方替换的 sink）可以完全忽略它，
     * 行为与合并前一致。
     */
    fun attachClusterSurface(surface: Surface?) {}

    /**
     * 绑定/解绑 **HUD 镜像** Surface（2026-10-22）。
     *
     * 与 [attachClusterSurface] 的区别：
     * - 副屏（111）是这条流**唯一**的输出，换 Surface 会改主解码器；
     * - HUD 是同一路 111 流的**第二个消费者**，必须走镜像入口，
     *   否则会把仪表正在用的 Surface 抢走。
     *
     * 默认空实现：不关心 HUD 的渲染实现可以完全忽略它。
     */
    fun attachHudSurface(surface: Surface?) {}

    /**
     * HUD 镜像解码器状态（true = 已拿到 codec config，可以吃帧）。
     *
     * 只用于状态/诊断；默认空实现。回调线程由渲染实现决定（当前是
     * 协议读线程 / 主线程），实现方负责切线程。
     */
    fun setHudMirrorStateListener(listener: ((Boolean) -> Unit)?) {}

    /** 某块屏开始/结束收流。UI 用它显示/隐藏视频层。 */
    fun setScreenStreamActiveListener(listener: (type: Int, active: Boolean) -> Unit)

    fun close()
}

/** 创建渲染槽位所需的上下文。 */
data class CarPlaySinkRequest(
    val context: Context,
    val display: CarDisplay,
    val widthPixels: Int,
    val heightPixels: Int,
    val preferSoftwareHevcDecoder: Boolean,
    val advancedAudioChannelMapping: Boolean,
    /**
     * 媒体通道走哪条音频流（0 = 按 usage 由系统路由；1..20 = 车机老式 streamType）。
     *
     * 出厂 0 等价于「不加这个参数」的行为。用户可在设置页逐个数试，
     * 试出能把音乐送进喇叭的那条。
     */
    val mediaAudioChannel: Int = 0,
    /** 导航提示音走哪条音频流，语义同 [mediaAudioChannel]。 */
    val navigationAudioChannel: Int = 0,
    /**
     * 媒体流（音乐/播客）抖动缓冲时长（毫秒）。
     *
     * 与音频流号解决的是两件事：流号决定「声音送进哪条总线」，
     * 缓冲决定「链路抖动时声音会不会断」。真车上这两个问题会同时出现。
     */
    val mediaAudioBufferMillis: Int = 300,
)

fun interface CarPlayMediaSinkFactory {
    fun create(request: CarPlaySinkRequest): CarPlayMediaSink
}

/**
 * 默认渲染实现：原样使用 upstream `AndroidMediaSink`。
 *
 * 除 Surface 转发外不做任何加工，保证"默认行为 = 上游行为"。
 */
class AndroidCarPlayMediaSink(
    request: CarPlaySinkRequest,
) : CarPlayMediaSink {

    private val delegate = AndroidMediaSink(
        // 上游 1.3.0 起 `AndroidMediaSink` 需要 Context：它新增的
        // `CarPlayMediaSessionService`（media3 媒体会话）与麦克风增益都要靠它拿系统服务。
        context = request.context,
        surface = null,
        videoWidth = request.widthPixels,
        videoHeight = request.heightPixels,
        preferSoftwareHevcDecoder = request.preferSoftwareHevcDecoder,
        advancedAudioChannelMapping = request.advancedAudioChannelMapping,
        mediaAudioChannel = request.mediaAudioChannel,
        navigationAudioChannel = request.navigationAudioChannel,
        mediaBufferMillis = request.mediaAudioBufferMillis,
    )

    @Volatile
    private var currentSurface: Surface? = null

    @Volatile
    private var currentClusterSurface: Surface? = null

    override val mediaSink: MediaSink get() = delegate
    override fun setMediaOutputGain(gain: Float): Boolean = delegate.setMediaOutputGain(gain)

    override fun attachSurface(surface: Surface?) {
        // upstream 的 setSurface(type, surface) 不接受 null，解绑必须走 clearSurface(type, old)。
        // 因此这里自己记住当前 Surface，切换/清空时先解绑旧的，再绑定新的。
        val previous = currentSurface
        if (previous != null && previous !== surface) {
            delegate.clearSurface(SCREEN_TYPE_MAIN, previous)
        }
        currentSurface = surface
        if (surface != null) {
            delegate.setSurface(SCREEN_TYPE_MAIN, surface)
        }
    }

    /**
     * 仪表副屏 Surface → `STREAM_TYPE_ALT_SCREEN = 111`。
     *
     * ## 2026-10-07 的行为修正（吉利 HDMI2 移植）
     *
     * 合并前本方法并不存在：`attachSurface()` 把 110 与 111 **绑到同一块 Surface**，
     * 那是照抄上游 `CarPlayHostActivity` 的做法（上游不区分两块屏，谁都不声明
     * `config.cluster`，iPhone 根本不开 111 流，绑了也无害）。
     *
     * 一旦我们开始声明副屏（`AirPlayConfig.cluster` 非空），111 就**真的会有流**；
     * 此时若还绑在主屏 Surface 上，仪表副屏内容与主屏相同、且分辨率不匹配，
     * 画面必然错乱。所以必须分开绑。
     *
     * 主屏仍在 [attachSurface] 里绑 110，两条轨各自独立、互不干扰。
     */
    override fun attachClusterSurface(surface: Surface?) {
        val previous = currentClusterSurface
        runCatching {
            Log.i(
                "carplay-sink",
                "T0|sink.attach|stage=enter in=${surface ?: "null"} prev=${previous ?: "null"} " +
                    "same=${previous === surface} " +
                    "in_valid=${surface?.isValid} prev_valid=${previous?.isValid} " +
                    "thread=${Thread.currentThread().name}",
            )
        }
        if (previous != null && previous !== surface) {
            runCatching {
                Log.i(
                    "carplay-sink",
                    "T0|sink.attach|stage=clear cleared=true prev=${previous} " +
                        "thread=${Thread.currentThread().name}",
                )
            }
            delegate.clearSurface(SCREEN_TYPE_ALT, previous)
        }
        currentClusterSurface = surface
        if (surface != null) {
            runCatching {
                Log.i(
                    "carplay-sink",
                    "T0|sink.attach|stage=set set=true in=$surface " +
                        "thread=${Thread.currentThread().name}",
                )
            }
            delegate.setSurface(SCREEN_TYPE_ALT, surface)
        }
    }

    /**
     * HUD 镜像 Surface → 111 流的**第二消费者**（2026-10-22）。
     *
     * 走 upstream `AndroidMediaSink.setMirrorSurface`：它会在主解码器之外再建
     * 一个镜像解码器，并把 `lastVideoConfig` 在 Surface 晚到时回放（P1-A）。
     *
     * owner 固定 `"hud"`：同一路流未来若还有别的镜像消费者，用不同 owner 区分。
     */
    override fun attachHudSurface(surface: Surface?) {
        delegate.setMirrorSurface(HUD_STREAM_TYPE, HUD_MIRROR_OWNER, surface)
    }

    override fun setHudMirrorStateListener(listener: ((Boolean) -> Unit)?) {
        if (listener == null) {
            delegate.setMirrorStateListener(null)
            return
        }
        delegate.setMirrorStateListener { type, owner, state ->
            if (type == HUD_STREAM_TYPE && owner == HUD_MIRROR_OWNER) {
                listener(state == com.shilapi.xcertplay.media.MirrorSurfaceGate.State.CONFIG_READY)
            }
        }
    }

    override fun setScreenStreamActiveListener(listener: (type: Int, active: Boolean) -> Unit) {
        delegate.setScreenStreamActiveChangedListener(listener)
    }

    override fun close() {
        delegate.close()
    }

    companion object {
        /** 与 upstream `CarPlayHostActivity` 的 SCREEN_TYPE_MAIN / SCREEN_TYPE_ALT 一致。 */
        const val SCREEN_TYPE_MAIN = 110
        const val SCREEN_TYPE_ALT = 111

        /**
         * HUD 镜像消费的流号：**111（ALT）**，与仪表副屏共享。
         *
         * 不另开协议流：CarPlay 只给了两条屏流（110 主 / 111 副），HUD 是 111
         * 的第二个消费者。代价见 [attachHudSurface] 与 HUD 协调器的三路解码说明。
         */
        const val HUD_STREAM_TYPE = SCREEN_TYPE_ALT

        /** HUD 镜像 owner（进日志/状态 key，避免与未来其它消费者混淆）。 */
        const val HUD_MIRROR_OWNER = "hud"
    }
}
