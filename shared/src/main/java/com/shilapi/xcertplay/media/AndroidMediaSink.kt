package com.shilapi.xcertplay.media

import android.content.Context
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.MediaSink
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.airplay.VideoCodec
import com.shilapi.xcertplay.airplay.toHexString
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue

/**
 * Android rendering backend for the CarPlay media engine. Video frames are
 * decoded with MediaCodec onto a Surface; audio streams are decoded to PCM and
 * played through AudioTrack. Call [close] when the session tears down.
 */
class AndroidMediaSink(
    private val context: Context,
    surface: Surface? = null,
    private val videoWidth: Int = 1280,
    private val videoHeight: Int = 720,
    private val preferSoftwareHevcDecoder: Boolean = false,
    private val advancedAudioChannelMapping: Boolean = false,
    /**
     * 上游 1.3.2 起的「主媒体流缓冲时长」参数。
     *
     * ⚠️ **本 fork 建轨时不使用它**（DBPlay PATCH-0014）。我们有自己的
     * [MediaAudioBuffer]：档位 300/500/1000（默认 300）、容量另留 200ms 余量、
     * 并且有一条 `startBytesFor` 起播阈值保护。两者语义与档位都不同，
     * 不能混用（见 PATCHES.md PATCH-0014 的说明）。
     *
     * 保留这个参数**只为让上游 `common/…/CarPlayHostActivity.createMediaSink`
     * 的具名调用继续编译**（它按名字传这个参数）。上游那个 SeekBar 在
     * 它自己的界面里，DBPlay 用的是 `car/ui` 的 Compose 界面，不会渲染它。
     */
    @Suppress("UNUSED_PARAMETER")
    mainMediaAudioBufferDurationMs: Int = MainMediaAudioBuffer.DEFAULT_DURATION_MS,
    private val microphoneGainPercent: Int = MicrophoneGain.DEFAULT_PERCENT,
    /**
     * 媒体通道（音乐/播客）走哪条音频流。**0 = 按 usage 由系统路由**；
     * 1..20 = 直接指定车机 ROM 支持的老式 `streamType`。
     *
     * ## 为什么是「流号」而不是「音量」（DBPlay PATCH-0013）
     *
     * 原先本类带一个 `audioGainPercent` 分通道增益钩子（在写 PCM 前逐样本缩放）。
     * 它在真实车机上解决不了「音乐进了大印（非扬声器）总线」这类问题——
     * 声音根本没到喇叭，缩放多少都一样。改为直接挑流号，才有机会把 PCM
     * 送进正确的输出。
     *
     * 0 是安全的出厂值：此时建轨路径与加这个参数之前**逐字节一致**。
     */
    private val mediaAudioChannel: Int = 0,
    /**
     * 导航提示音走哪条音频流，语义同 [mediaAudioChannel]。
     *
     * 之所以与媒体分开：导航提示在多数车机上落在独立的提示流上，
     * 与音乐不是同一条总线，混用会让「音乐对了、导航还是没声」。
     */
    private val navigationAudioChannel: Int = 0,
    /**
     * 媒体流（音乐/播客）的抖动缓冲时长（毫秒）。
     *
     * 无线 CarPlay 的音频与视频共用 Wi-Fi：链路一紧张，音频包成串到达之前
     * 会先空上几百毫秒。媒体流需要一条够长的缓冲把空档吞掉，通话/Siri/
     * 导航提示则相反（要的是及时），因此这里只作用于媒体流。
     *
     * 合法档位见 [MediaAudioBuffer.presets]（300/500/1000）；其他值会被
     * 夹回默认档。0 或不传等同于默认档——即出厂行为保持可用。
     */
    private val mediaBufferMillis: Int = MediaAudioBuffer.DEFAULT_MILLIS,
    mediaMetricsMonitor: MediaMetricsMonitor? = null,
    onScreenStreamActiveChanged: ((Int, Boolean) -> Unit)? = null,
) : MediaSink {
    private val defaultSurface = surface
    @Volatile private var screenStreamActiveChanged = onScreenStreamActiveChanged
    private val surfaces = ConcurrentHashMap<Int, Surface>()
    private val videoDecoders = ConcurrentHashMap<Int, VideoDecoder>()
    private val audioRenderers = ConcurrentHashMap<Int, AudioRenderer>()
    private val mediaOutputGain = MediaOutputGain()

    /** Local media tracks only; no AudioManager stream-volume changes. */
    fun setMediaOutputGain(gain: Float): Boolean = mediaOutputGain.set(gain)
    private val microphoneUplinks = ConcurrentHashMap<Int, MicrophoneUplink>()
    /**
     * 整个 sink 是否已关闭。
     *
     * 会话收尾时 `CarPlayMediaEngine.onSessionClosed` 会对每条音频流补发
     * `onAudioStopped`，那时媒体音轨正准备销毁——若不拦住，就会在这次"通信结束"
     * 事件里把一条马上要 close 的音轨重新唤醒一遍。加这道闸让收尾路径不白跑。
     */
    @Volatile private var sinkClosed = false
    /**
     * 串行化"通话结束后恢复媒体"的动作。
     *
     * 单线程是刻意的：麦克风关闭与通话下行关闭是两个回调，几乎同时到；它们在
     * 同一队列里排队，就能保证"关一个、看一次、没人再说话才恢复"的判断不会被
     * 另一次回调的中间态骗到。用多线程反而会把判断变成竞态。
     */
    private val communicationReleaseExecutor = java.util.concurrent.Executors.newSingleThreadExecutor {
        Thread(it, "carplay-audio-resume").apply { isDaemon = true }
    }
    private val videoRecoveryHandlers = ConcurrentHashMap<Int, () -> Boolean>()
    private val pendingVideoCodec = ConcurrentHashMap<Int, VideoCodec>()
    @Volatile private var mediaMetricsMonitor = mediaMetricsMonitor

    /**
     * 镜像（mirror）输出：同一条视频流的**第二块 Surface**。
     *
     * ## 为什么不复用主解码器
     *
     * `MediaCodec` 一个实例只能输出到一块 Surface；HUD 要在 111 流之外再挂一块
     * 1024×274 的屏，就必须有独立解码实例。这是已知代价（111 主 + HUD mirror ⇒
     * 三路解码），不是实现疏忽 —— 降级链只有「显式软解」与「关仪表 111」两条
     * 是真的能减少实例数，「降 HUD 分辨率」不减实例、属无效降级（v3 评审）。
     *
     * key 带 owner 是为了同一路流允许多个镜像消费者（本立项只用 `"hud"`），
     * 且 owner 能进日志，排障时能一眼看出是谁挂的。
     */
    private val mirrorSurfaces = ConcurrentHashMap<MirrorKey, Surface>()
    private val mirrorDecoders = ConcurrentHashMap<MirrorKey, VideoDecoder>()

    /**
     * 每个 mirror key 的「Surface / config 谁先到」状态机。
     *
     * P1-A：`setMirrorSurface` 收到非空 Surface 时，若已有 `lastVideoConfig`
     * 就立即 configure + start —— 否则中途挂载的 HUD 永远出不了首帧。
     */
    private val mirrorGates = ConcurrentHashMap<MirrorKey, MirrorSurfaceGate>()

    /**
     * 每条视频流最近一次的 codec config（**流级**，不是 gate 级）。
     *
     * P1-A 的关键：HUD 的 `setMirrorSurface` 可能在 config 之后才**第一次**被调用，
     * 那时镜像 gate 还不存在，单靠 gate 自己记不住「它出生之前」发生过的 config。
     * 所以 sink 必须在这里按流号缓存一份；`setMirrorSurface` 建 gate 时先灌进去。
     */
    private val lastVideoConfigs = ConcurrentHashMap<Int, MirrorSurfaceGate.Config>()

    /** 镜像状态变化回调（type, owner, state）；由上层转发给 HUD 状态机。 */
    @Volatile
    private var mirrorStateListener: ((Int, String, MirrorSurfaceGate.State) -> Unit)? = null

    fun setSurface(type: Int, surface: Surface) {
        val previous = surfaces[type]
        surfaces[type] = surface
        videoDecoders[type]?.setSurface(surface)
        runCatching {
            Log.i(
                "xcertplay-usb",
                "T0|media.set|type=$type surface=$surface " +
                    "prev_overwrote=${previous ?: "null"} thread=${Thread.currentThread().name}",
            )
        }
    }

    fun clearSurface(type: Int, surface: Surface) {
        val matches = surfaces[type] === surface
        // T0 诊断：捕获 remove 的**真实返回值**（不能拿 matches 代替 —— 那是 remove 之前的快照）。
        val removed = surfaces.remove(type, surface)
        if (removed) videoDecoders[type]?.setSurface(null)
        runCatching {
            Log.i(
                "xcertplay-usb",
                "T0|media.clear|type=$type incoming_matches=$matches removed=$removed " +
                    "decoder_unbound=${removed && videoDecoders[type] != null} " +
                    "thread=${Thread.currentThread().name}",
            )
        }
    }

    /**
     * 绑定/解绑**镜像输出** Surface（HUD 专用入口）。
     *
     * 与 [setSurface] 的区别：
     * - [setSurface] 是「这条流唯一的输出」，换 Surface 会改主解码器；
     * - 本方法**不碰主解码器**，在它之外再建一个镜像解码器，消费同一份
     *   已解密帧。仪表副屏（111 主）与 HUD 可以同时收同一路流。
     *
     * `surface == null` 表示窗口已销毁：释放镜像解码器，但保留 gate 的 config，
     * 下次挂载同一会话时还能回放。
     */
    fun setMirrorSurface(type: Int, owner: String, surface: Surface?) {
        val key = MirrorKey(type, owner.ifBlank { DEFAULT_MIRROR_OWNER })
        val gate = mirrorGates.computeIfAbsent(key) { MirrorSurfaceGate() }
        if (surface == null) {
            mirrorSurfaces.remove(key)
            mirrorDecoders.remove(key)?.close()
            gate.detachSurface()
            notifyMirrorState(key, gate)
            Log.i(TAG, "mirror surface detached type=$type owner=${key.owner}")
            return
        }
        if (!surface.isValid) {
            // 不接管生命周期：Surface 由窗口层拥有并释放，这里只拒绝投递。
            Log.w(TAG, "mirror surface rejected (invalid) type=$type owner=${key.owner}")
            notifyMirrorState(key, gate)
            return
        }
        // gate 可能是**刚创建**的（HUD 第一次挂载晚于 config）⇒ 先把流级配置灌进去，
        // 否则 attachSurface 看到的 config 是 null，P1-A 回放失效。
        val knownConfig = lastVideoConfigs[type]
        if (knownConfig != null && gate.lastConfig == null) {
            gate.onConfig(knownConfig.codec, knownConfig.codecData)
        }
        mirrorSurfaces[key] = surface
        val actions = gate.attachSurface()
        val config = gate.lastConfig
        if (actions.isNotEmpty() && config != null) {
            // P1-A：Surface 晚到 ⇒ 立即回放 lastVideoConfig，解码器才能吃帧。
            mirrorDecoder(key)?.configure(config.codec, config.codecData)
            Log.i(
                TAG,
                "mirror surface attached; replayed last config type=$type owner=${key.owner} " +
                    "configBytes=${config.codecData.size}",
            )
        } else {
            Log.i(
                TAG,
                "mirror surface attached type=$type owner=${key.owner} state=${gate.state}",
            )
        }
        notifyMirrorState(key, gate)
    }

    fun setMirrorStateListener(
        listener: ((Int, String, MirrorSurfaceGate.State) -> Unit)?,
    ) {
        mirrorStateListener = listener
    }

    /** 当前镜像状态（诊断用；没有记录时返回 null）。 */
    fun mirrorStateOrNull(type: Int, owner: String): MirrorSurfaceGate.State? =
        mirrorGates[MirrorKey(type, owner)]?.state

    fun setScreenStreamActiveChangedListener(listener: ((Int, Boolean) -> Unit)?) {
        screenStreamActiveChanged = listener
    }

    fun setMediaMetricsMonitor(monitor: MediaMetricsMonitor?) {
        mediaMetricsMonitor = monitor
        videoDecoders.values.forEach { it.setMediaMetricsMonitor(monitor) }
        audioRenderers.values.forEach { it.setMediaMetricsMonitor(monitor) }
    }

    override fun onVideoRecoveryHandler(type: Int, requestKeyFrame: (() -> Boolean)?) {
        if (requestKeyFrame == null) videoRecoveryHandlers.remove(type)
        else videoRecoveryHandlers[type] = requestKeyFrame
    }

    override fun onVideoCodec(type: Int, codec: VideoCodec) {
        pendingVideoCodec[type] = codec
    }

    override fun onVideoConfig(type: Int, codecData: ByteArray) {
        val codec = pendingVideoCodec[type] ?: VideoCodec.H264
        // 流级快照：即使此刻还没有任何 mirror gate，HUD 晚些第一次挂载时也要能回放。
        // 复制字节，避免协议层复用缓冲改写已经记下的参数集。
        lastVideoConfigs[type] = MirrorSurfaceGate.Config(codec, codecData.copyOf())
        videoDecoder(type).configure(codec, codecData)
        // 镜像解码器吃同一份 config。gate 负责判断「Surface 是否已经在了」：
        // 在 ⇒ 返回 configure+start；不在 ⇒ 只记住，等 setMirrorSurface 回放。
        mirrorKeysFor(type).forEach { key ->
            val gate = mirrorGates.computeIfAbsent(key) { MirrorSurfaceGate() }
            val actions = gate.onConfig(codec, codecData)
            if (actions.isNotEmpty()) {
                mirrorDecoder(key)?.configure(codec, codecData)
            }
            notifyMirrorState(key, gate)
        }
    }

    override fun onVideoFrame(type: Int, naluBytes: ByteArray) {
        val arrivalUs = System.nanoTime() / 1_000L
        videoDecoder(type).submit(naluBytes, arrivalUs)
        // 只有已经挂上 Surface 的镜像才提交；没有 gate 的 key 不在这里出现
        // （镜像先于主解码器创建时才会注册 gate）。
        mirrorKeysFor(type).forEach { key ->
            if (mirrorSurfaces.containsKey(key)) {
                mirrorDecoders[key]?.submit(naluBytes, arrivalUs)
            }
        }
    }

    override fun onScreenStreamActive(type: Int, active: Boolean) {
        if (!active) {
            videoDecoders.remove(type)?.close()
            pendingVideoCodec.remove(type)
            videoRecoveryHandlers.remove(type)
            // 镜像是同一条流的消费者：流停了，旧 config 立即作废；Surface 保留，
            // 新流会重新发 SPS/PPS，届时 gate 会再回放一次。
            mirrorKeysFor(type).forEach { key ->
                mirrorDecoders.remove(key)?.close()
                val gate = mirrorGates[key] ?: return@forEach
                gate.invalidateConfig()
                notifyMirrorState(key, gate)
            }
        }
        screenStreamActiveChanged?.invoke(type, active)
    }

    override fun onAudioStarted(type: Int, format: AudioFormat, firstSample: Int) {
        audioRenderer(type, format).start()
    }

    override fun onAudioRtp(type: Int, format: AudioFormat, rtp: ByteArray, sample: Int) {
        audioRenderer(type, format).submit(rtp, sample)
    }

    override fun onAudioStopped(type: Int) {
        val renderer = audioRenderers.remove(type)
        val wasCommunication = isCommunicationAudioType(renderer?.format?.audioType)
        renderer?.close()
        if (wasCommunication) scheduleMediaRenew()
    }

    override fun onMicrophoneStarted(type: Int, config: MicrophoneConfig) {
        val uplink = microphoneUplinks.computeIfAbsent(type) {
            MicrophoneUplink(context, config, microphoneGainPercent)
        }
        if (!uplink.start()) microphoneUplinks.remove(type, uplink)
    }

    override fun onMicrophoneStopped(type: Int) {
        val uplink = microphoneUplinks.remove(type)
        if (uplink != null) {
            uplink.close()
            // close() 内部会释放音频模式租约；等它完成后才谈恢复，避免与 HAL
            // 的模式切换抢跑（这正是 DiPlay 记录的那个竞态：模式还没切回来就去
            // 恢复音乐，结果音乐音轨仍被通信策略压着，白恢复一次）。
            scheduleMediaRenew()
        }
    }

    /**
     * 把"恢复媒体音轨"排到单线程队列，并吞掉已关闭 executor 的拒绝异常。
     *
     * 若 sink 已 close（`communicationReleaseExecutor.shutdownNow()` 之后），
     * `execute()` 会抛 `RejectedExecutionException`——那是收尾路径的正常现象，
     * 不该让一次音频停止回调因为"恢复动作没排上队"而炸掉。
     */
    private fun scheduleMediaRenew() {
        if (sinkClosed) return
        try {
            communicationReleaseExecutor.execute { renewMediaAfterCommunication() }
        } catch (error: java.util.concurrent.RejectedExecutionException) {
            // sink 已收尾，恢复动作没排上队是预期行为，静默跳过即可。
        }
    }

    /** 通话/Siri 类音频流的判定：这些流结束后才需要唤醒媒体音轨。 */
    private fun isCommunicationAudioType(audioType: String?): Boolean =
        audioType == "telephony" || audioType == "speechRecognition"

    /**
     * 通话结束后唤醒媒体音轨（保留缓冲、不重建）。
     *
     * 只在国内流全部静默时执行：同时有通话与 Siri 时，先结束的那个不该把音乐拉回来。
     * 这与 `AudioModeLeaseManager` 的引用计数租约是同一判据——租约只管 `mode`，
     * 这里管音轨，两者都必须在"最后一个通信使用者退出"之后才动手。
     */
    private fun renewMediaAfterCommunication() {
        // 收尾路径不恢复：sink 正在销毁，唤醒的音轨马上就会 close。
        if (sinkClosed) return
        if (microphoneUplinks.isNotEmpty()) return
        val stillTalking = audioRenderers.values.any { isCommunicationAudioType(it.format.audioType) }
        if (stillTalking) return
        audioRenderers.values
            .filter { MediaAudioBuffer.isMediaType(it.format.audioType) }
            .forEach { it.resumeAfterCommunication() }
    }

    fun close() {
        // 先立旗：此后到达的 onAudioStopped/onMicrophoneStopped 不再排队恢复动作。
        sinkClosed = true
        videoDecoders.values.forEach(VideoDecoder::close)
        videoDecoders.clear()
        mirrorDecoders.values.forEach(VideoDecoder::close)
        mirrorDecoders.clear()
        mirrorSurfaces.clear()
        mirrorGates.values.forEach(MirrorSurfaceGate::reset)
        mirrorGates.clear()
        videoRecoveryHandlers.clear()
        pendingVideoCodec.clear()
        audioRenderers.values.forEach(AudioRenderer::close)
        audioRenderers.clear()
        microphoneUplinks.values.forEach(MicrophoneUplink::close)
        microphoneUplinks.clear()
        communicationReleaseExecutor.shutdownNow()
    }

    /** 当前绑定了镜像 Surface 的 key（同一条流可能被多个 owner 消费）。 */
    private fun mirrorKeysFor(type: Int): List<MirrorKey> =
        mirrorGates.keys.filter { it.type == type }

    /**
     * 取/建该 key 的镜像解码器。
     *
     * **只有 Surface 已挂上时才创建**：没有输出目标的 `VideoDecoder` 会白占一个
     * MediaCodec 实例和线程，而 HUD 窗口可能长时间不挂。
     */
    private fun mirrorDecoder(key: MirrorKey): VideoDecoder? {
        val surface = mirrorSurfaces[key] ?: return null
        return mirrorDecoders.computeIfAbsent(key) {
            Log.i(TAG, "mirror decoder created type=${key.type} owner=${key.owner}")
            VideoDecoder(
                surface,
                key.type,
                videoWidth,
                videoHeight,
                preferSoftwareHevcDecoder,
                mediaMetricsMonitor,
                requestKeyFrame = { videoRecoveryHandlers[key.type]?.invoke() ?: false },
            )
        }.also { it.setSurface(surface) }
    }

    private fun notifyMirrorState(key: MirrorKey, gate: MirrorSurfaceGate) {
        val listener = mirrorStateListener ?: return
        runCatching { listener(key.type, key.owner, gate.state) }
    }

    /** 镜像输出标识：同一路流按 (type, owner) 区分消费者。 */
    private data class MirrorKey(val type: Int, val owner: String)

    private fun videoDecoder(type: Int): VideoDecoder =
        videoDecoders.computeIfAbsent(type) {
            VideoDecoder(
                surfaces[type] ?: defaultSurface,
                type,
                videoWidth,
                videoHeight,
                preferSoftwareHevcDecoder,
                mediaMetricsMonitor,
                requestKeyFrame = { videoRecoveryHandlers[type]?.invoke() ?: false },
            )
        }

    @Synchronized
    private fun audioRenderer(type: Int, format: AudioFormat): AudioRenderer {
        val existing = audioRenderers[type]
        if (existing?.format == format) return existing
        existing?.close()
        return AudioRenderer(
            format,
            advancedAudioChannelMapping,
            mediaAudioChannel,
            navigationAudioChannel,
            mediaBufferMillis,
            mediaMetricsMonitor,
            mediaOutputGain,
        ).also { audioRenderers[type] = it }
    }

    private companion object {
        const val TAG = "xcertplay-usb"

        /** 没给 owner 时的镜像标识（上层目前总是显式传 `"hud"`）。 */
        const val DEFAULT_MIRROR_OWNER = "mirror"
    }
}

/**
 * 帧龄超出 [maxAgeNs] 时是否应当丢弃。
 *
 * 2026-10-08（PATCH-0034）修正为**首帧提交前一律不丢**（[submittedFirstFrame] = false）。
 *
 * 理由：解码器还没吃到过任何一帧时，队列里每一帧都可能是**唯一的随机访问点**，
 * 丢掉它解码器就永远等不到 I 帧 —— 1.1.0 现场正是如此：`heldFrame` 的 `enqueuedNs`
 * 被硬编码成 0，`ageNs` 等于开机时长（实测 396680ms），首帧被反复丢弃，
 * 最终 `waiting for random access` 死等、副屏白块。
 *
 * 丢帧只对「已经在正常出画面、只是追不上手指」的滑动场景有意义。
 */
internal fun shouldDropStaleFrame(
    submittedFirstFrame: Boolean,
    ageNs: Long,
    maxAgeNs: Long,
): Boolean = submittedFirstFrame && ageNs > maxAgeNs

private sealed interface VideoJob {
    data class Config(val codec: VideoCodec, val codecData: ByteArray) : VideoJob
    data class Frame(val nalus: ByteArray, val receivedUs: Long) : VideoJob
}

/** Serial MediaCodec video decoder: one worker owns configure and frame feeding. */
private class VideoDecoder(
    surface: Surface?,
    private val streamType: Int,
    private val width: Int,
    private val height: Int,
    private val preferSoftwareHevcDecoder: Boolean,
    mediaMetricsMonitor: MediaMetricsMonitor?,
    private val requestKeyFrame: () -> Boolean,
) : Closeable {
    private val queue = VideoWorkQueue<VideoJob>(8)
    @Volatile private var running = true
    @Volatile private var decoder: MediaCodec? = null
    private var pump: VideoDecodePump? = null
    private var syncGate: VideoSyncGate? = null
    private var pendingSinceNs = 0L
    private var lastPtsUs = 0L
    private var statsStartNs = System.nanoTime()
    private var inputCount = 0
    private var outputCount = 0
    private var renderedCount = 0
    private var lastRenderNs = 0L
    private var inputRetries = 0
    private var syncSkips = 0
    private var maxOutputAgeUs = 0L
    private var droppedOutputFrames = 0L

    /** 队列侧超龄丢弃只打一次日志，避免刷屏（PATCH-0028）。 */
    private var droppedStaleFrameLogged = false
    private var droppedInputFrames = 0L
    private var droppedInputFrameLogged = false
    private var needsKeyFrame = false
    private var lastKeyFrameRequestNs = 0L
    @Volatile private var requestedSurface: Surface? = surface
    private var outputSurface: Surface? = surface
    private var heldFrame: VideoJob.Frame? = null
    private var lastConfig: VideoJob.Config? = null
    private var renderedFrameLogged = false
    private var submittedFrameLogged = false
    private var duplicateConfigLogged = false
    private var awaitingConfigLogged = false
    @Volatile private var mediaMetricsMonitor = mediaMetricsMonitor
    private val thread = Thread(::run, "carplay-video").apply { isDaemon = true; start() }

    fun configure(codec: VideoCodec, codecData: ByteArray) {
        queue.control(VideoJob.Config(codec, codecData.copyOf()))
    }

    fun submit(nalus: ByteArray, arrivalUs: Long) {
        queue.frame(VideoJob.Frame(nalus, arrivalUs))
    }

    fun setSurface(surface: Surface?) {
        runCatching {
            Log.i(
                "xcertplay-usb",
                "T0|decoder.surface|stage=request type=$streamType req=${surface ?: "null"}" +
                    " thread=${Thread.currentThread().name}",
            )
        }
        requestedSurface = surface
    }

    fun setMediaMetricsMonitor(monitor: MediaMetricsMonitor?) {
        mediaMetricsMonitor = monitor
    }

    override fun close() {
        running = false
        queue.close()
        thread.interrupt()
    }

    private fun run() {
        try {
            while (running) {
                try {
                    if (outputSurface !== requestedSurface) changeSurface(requestedSurface)
                    if (needsKeyFrame && outputSurface != null &&
                        System.nanoTime() - lastKeyFrameRequestNs >= KEY_FRAME_REQUEST_INTERVAL_NS
                    ) {
                        lastKeyFrameRequestNs = System.nanoTime()
                        Log.i(TAG, "video recovery key frame requested sent=${requestKeyFrame()}")
                    }
                    // Poll output independently of input arrival, including the final/static frame.
                    pump?.tick()
                    logVideoStats()
                    if (pump?.hasPending == true) {
                        check(System.nanoTime() - pendingSinceNs < INPUT_STALL_NS) {
                            "Video decoder input stalled; waiting for a new random access frame"
                        }
                        Thread.sleep(2)
                        continue
                    }
                    if (heldFrame != null && outputSurface == null) {
                        Thread.sleep(5)
                        continue
                    }
                    // ⚠️ 队列侧帧龄丢弃（2026-10-07，PATCH-0028，修「滑动卡顿」）。
                    //
                    // 现场证据（1.0.9 实测）：`queued=8 maxOutputAgeMs=960` ——
                    // 帧在**队列里**积压近 1 秒，手指划完画面才跟上。
                    //
                    // PATCH-0024 把判定放在 `feed()` 里，还带 `receivedUs < lastPtsUs`
                    // 与 `hasPending` 两个几乎不成立的门槛 ⇒ droppedIn/droppedOut 恒为 0，
                    // 现场卡顿照旧。真正积压的是这条队列，必须在**取出这一刻**判。
                    //
                    // 上游语义（`is VideoJob.Frame ->` 分支）：帧等了超过 MAX_FRAME_AGE_NS
                    // 就整队丢掉并请求关键帧——画面直接跳到最新，而不是把旧帧慢慢放完。
                    // 这里做等价的事：丢掉超龄帧本身，让下一帧重新取；同时 `needsKeyFrame`
                    // 让手机补一个 I 帧（否则丢掉的帧会让参考链断掉、画面长期花屏）。
                    // ⚠️ 2026-10-08（PATCH-0034）：`heldFrame` 的 `enqueuedNs` 原先**硬编码 0**。
                    //
                    // 后果：`ageNs = System.nanoTime() - 0` = **开机至今的时长**
                    // （1.1.0 实测 `waited 396680ms` ≈ 6.6 分钟，正好是车机 uptime），
                    // 必然越过 MAX_FRAME_AGE_NS(250ms) → 攥在手里的**首帧被无限期反复丢弃**
                    // → 解码器一帧都喂不进去 → `waiting for random access` 死等 → 副屏白块。
                    //
                    // Surface 未就绪时攥住的首帧刻意按「刚入队」计时，不参与超龄丢弃：
                    // 它的语义是「保住唯一的随机访问点，等 Surface 一到就喂」。
                    val stamped = heldFrame?.let { VideoWorkQueue.Stamped(it, System.nanoTime(), true) }
                        ?: queue.pollStamped(5)
                    when (val job = stamped?.value) {
                        is VideoJob.Config -> configureDecoder(job)
                        is VideoJob.Frame -> {
                            val ageNs = if (stamped.frame) {
                                System.nanoTime() - stamped.enqueuedNs
                            } else 0L
                            // 2026-10-08（PATCH-0034）：**首帧尚未提交前绝不丢帧**。
                            //
                            // 此时任何一帧都可能是唯一的随机访问点，丢掉它解码器就永远等不到
                            // I 帧（`waiting for random access` 死等、副屏白块）。丢帧只对
                            // 「已在正常出画面、只是追不上手指」的滑动场景有意义，
                            // 因此用 submittedFrameLogged 把首帧阶段排除在外。
                            if (shouldDropStaleFrame(submittedFrameLogged, ageNs, MAX_FRAME_AGE_NS)) {
                                droppedOutputFrames++   // 共用现有计数（输出侧口径一致）
                                if (!droppedStaleFrameLogged) {
                                    droppedStaleFrameLogged = true
                                    Log.i(
                                        TAG,
                                        "video stale queued frame dropped (waited ${ageNs / 1_000_000}ms > " +
                                            "${MAX_FRAME_AGE_NS / 1_000_000}ms); queued=${queue.size}; requesting keyframe",
                                    )
                                }
                                needsKeyFrame = true
                            } else {
                                // Preserve the initial random access picture until a Surface exists.
                                heldFrame = if (outputSurface == null) job else null
                                if (outputSurface != null) feed(job)
                            }
                        }
                        null -> Unit
                    }
                } catch (error: InterruptedException) {
                    throw error
                } catch (error: Exception) {
                    if (running) Log.e(TAG, "video decoder failed; restarting at random access", error)
                    releaseDecoder()
                    needsKeyFrame = true
                }
            }
        } catch (_: InterruptedException) {
            // Worker shut down.
        } finally {
            heldFrame = null
            releaseDecoder()
        }
    }

    private fun configureDecoder(config: VideoJob.Config) {
        val previous = lastConfig
        if (
            decoder != null &&
            previous?.codec == config.codec &&
            previous.codecData.contentEquals(config.codecData)
        ) {
            if (!duplicateConfigLogged) {
                duplicateConfigLogged = true
                Log.i(TAG, "video decoder config unchanged; keeping existing decoder")
            }
            return
        }
        lastConfig = config
        duplicateConfigLogged = false
        awaitingConfigLogged = false
        releaseDecoder()
        val surface = outputSurface ?: return
        val codec = config.codec
        val codecData = config.codecData
        val mime = if (codec == VideoCodec.H265) MediaFormat.MIMETYPE_VIDEO_HEVC
        else MediaFormat.MIMETYPE_VIDEO_AVC
        val csd: ByteArray
        val pps: ByteArray?
        if (codec == VideoCodec.H265) {
            csd = MediaCodecSupport.hevcCodecSpecificData(codecData)
            pps = null
        } else {
            val sets = MediaCodecSupport.avcParameterSets(codecData)
            require(sets.first.isNotEmpty() && sets.second.isNotEmpty()) { "Missing AVC parameter sets" }
            csd = START_CODE + sets.first
            pps = START_CODE + sets.second
        }
        require(csd.isNotEmpty()) { "Missing or invalid video parameter sets" }
        val parameters = VideoParameters.parse(codec, csd)
        val format = MediaFormat.createVideoFormat(mime, parameters.width, parameters.height).apply {
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
            setByteBuffer("csd-0", ByteBuffer.wrap(csd))
            if (pps != null) setByteBuffer("csd-1", ByteBuffer.wrap(pps))
            // Unspecified VUI fields remain unspecified; do not force full range or BT.709.
            if (parameters.colorStandard != -1) setInteger(MediaFormat.KEY_COLOR_STANDARD, parameters.colorStandard)
            if (parameters.colorRange != -1) setInteger(MediaFormat.KEY_COLOR_RANGE, parameters.colorRange)
            if (parameters.colorTransfer != -1) setInteger(MediaFormat.KEY_COLOR_TRANSFER, parameters.colorTransfer)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
        }
        val next = createDecoder(mime)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                next.codecInfo.getCapabilitiesForType(mime)
                    .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
            ) format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            next.configure(format, surface, null, 0)
            next.start()
        } catch (error: Exception) {
            next.release()
            throw error
        }
        decoder = next
        syncGate = VideoSyncGate(codec)
        pump = VideoDecodePump(object : VideoDecodePump.Port {
            override fun drain() = drainOutput(next)
            override fun queue(bytes: ByteArray, presentationTimeUs: Long): Boolean {
                val index = next.dequeueInputBuffer(0)
                if (index < 0) {
                    inputRetries++
                    return false
                }
                val input = checkNotNull(next.getInputBuffer(index)) { "Missing video input buffer" }
                input.clear()
                check(bytes.size <= input.remaining()) { "Video access unit exceeds decoder input capacity" }
                input.put(bytes)
                next.queueInputBuffer(index, 0, bytes.size, presentationTimeUs, 0)
                inputCount++
                return true
            }
        })
        Log.i(TAG, "video SPS parameters=$parameters negotiated=${width}x$height inputFormat=$format")
        renderedFrameLogged = false
        submittedFrameLogged = false
        Log.i(TAG, "video decoder configured name=${next.name} mime=$mime; waiting for random access")
    }

    /**
     * 选一个真的能解当前编码的视频解码器。
     *
     * 旧实现直接 `createDecoderByType(mime)`：它把选择权完全交给 ROM，返回的
     * 往往是 codec 列表里的**第一个**实现，而那个实现未必支持当前编码 —— 现场
     * 表现就是「画面黑屏 / 一帧不出 / 花屏」，但没有任何异常抛出，日志里只剩
     * 一句 `video decoder configured`，看起来一切正常。
     *
     * 车机 ROM 的 codec 列表顺序尤其不可信（同一颗 SoC 上，厂商可能把只能解
     * 1080p 的低端硬解排在只支持 baseline 的软解前面）。这里改为主动按能力筛：
     *
     * 1. preferSoftwareHevcDecoder 打开时，HEVC 优先软解（部分车机 HEVC 硬解有花屏/掉帧）；
     * 2. 否则按 [rank] 排序，**硬解优先、支持该 mime 且非别名**；
     * 3. 逐个 `createByCodecName` 尝试，谁先建成功就用谁；
     * 4. 全失败才退回 `createDecoderByType`，把 ROM 的原行为作为最后兜底，
     *    避免「我们比 ROM 更挑剔」反而把一个本来能用的解码器挡掉。
     */
    private fun createDecoder(mime: String): MediaCodec {
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            mime == MediaFormat.MIMETYPE_VIDEO_HEVC &&
            preferSoftwareHevcDecoder
        ) {
            val software = infos.firstOrNull {
                !it.isEncoder && isSoftwareOnlyCodec(it) && !isAliasCodec(it) && mime in it.supportedTypes
            }
            if (software != null) {
                try {
                    return MediaCodec.createByCodecName(software.name)
                } catch (error: Exception) {
                    Log.w(TAG, "software HEVC decoder unavailable name=${software.name}", error)
                }
            }
        }
        val candidates = infos
            .filter { !it.isEncoder && !isAliasCodec(it) && mime in it.supportedTypes }
            .sortedBy { rank(it, mime) }
        for (info in candidates) {
            try {
                return MediaCodec.createByCodecName(info.name)
            } catch (error: Exception) {
                Log.w(TAG, "video decoder unusable name=${info.name} mime=$mime", error)
            }
        }
        Log.w(TAG, "no probed video decoder for mime=$mime; falling back to platform default")
        return MediaCodec.createDecoderByType(mime)
    }

    /**
     * 解码器排序权重，越小越优先。
     *
     * 硬解优先是刻意的：软解在车机上要吃掉本该留给导航的 CPU，1080p H.264 的
     * 软件解码足以让整机掉帧。所以只在「没有可用硬解」时才轮到软解。
     *
     * 这里**不**按分辨率/帧率二次过滤：`supportedTypes` 只保证编解码类型，
     * 真正的尺寸能力要 `getCapabilitiesForType` 才拿得到，而它在部分 ROM 上对
     * 未初始化格式会抛异常——用一个可能抛异常的探测去做排序，会把「选解码器」
     * 变成新的崩溃点。当前配置格式里已经带了真实宽高，解码器自己在 configure
     * 阶段会拒掉超范围的，那时 [configureVideoDecoder] 的 try 会把它淘汰掉。
     */
    private fun rank(info: MediaCodecInfo, mime: String): Int = when {
        !isSoftwareOnlyCodec(info) -> 0
        else -> 1
    }

    /**
     * 判断 codec 是否纯软件实现。
     *
     * ⚠️ 同 [isAliasCodec] 的理由：`MediaCodecInfo.isSoftwareOnly()` 也是
     * **API 29** 才有的方法，Android 9 车机上直接调用会抛 `NoSuchMethodError`
     * 打死视频线程。低版本退化为按名字判（`OMX.google.` / `c2.android.` / `sw` 前缀
     * 是 AOSP 与各 ROM 对软件解码器的通用命名）。
     */
    private fun isSoftwareOnlyCodec(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return info.isSoftwareOnly
        }
        val name = info.name.lowercase()
        return name.startsWith("omx.google.") ||
            name.startsWith("c2.android.") ||
            name.contains(".sw.") ||
            name.endsWith(".sw")
    }

    /**
     * 判断 codec 是否是别名（alias，指向另一个真实 codec）。
     *
     * ⚠️ 为什么不用 `MediaCodecInfo.isAlias()`：那是 **API 29** 才加的方法，
     * 在 Android 9（API 28）车机上反射不到，直接抛
     * `NoSuchMethodError: No virtual method isAlias()` 把视频线程打死 →
     * 表现为"一连上就闪退"（已于 0.9.1 实车复现，本函数即修复）。
     *
     * API 29+ 直接用它；更低版本退化为按名字判——别名 codec 的名字形如
     * `OMX.xxx.<真实名>`，真名会在别名之后重复出现，所以先按"名字里已出现过"
     * 去重，效果与 isAlias 等价且不会崩。
     */
    private fun isAliasCodec(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return info.isAlias
        }
        // 低版本兜底：名字里含 "alias" 的标记为别名（各 ROM 的常见命名）。
        return info.name.contains(".alias", ignoreCase = true)
    }

    private fun changeSurface(surface: Surface?) {
        runCatching {
            Log.i(
                "xcertplay-usb",
                "T0|decoder.surface|stage=change type=$streamType " +
                    "in=${surface ?: "null"} same=${outputSurface === surface}" +
                    " thread=${Thread.currentThread().name}",
            )
        }
        if (outputSurface === surface) return
        outputSurface = surface
        if (streamType == 111) {
            // The instrument may attach minutes after SETUP. Never replay the held
            // pre-window picture or carry MediaCodec buffers across output generations.
            releaseDecoder()
            heldFrame = null
            queue.discardFrames()
            lastPtsUs = 0L
            pendingSinceNs = 0L
            lastRenderNs = 0L
            renderedFrameLogged = false
            submittedFrameLogged = false
            needsKeyFrame = surface != null
            lastKeyFrameRequestNs = 0L
            if (surface != null) lastConfig?.let(::configureDecoder)
            Log.i(TAG, "instrument video output reset type=$streamType attached=${surface != null}")
            return
        }
        if (surface == null) {
            runCatching {
                Log.i(
                    "xcertplay-usb",
                    "T0|decoder.surface|stage=action type=$streamType action=detach" +
                        " thread=${Thread.currentThread().name}",
                )
            }
            releaseDecoder()
            Log.i(TAG, "video decoder detached from surface")
            return
        }
        val codec = decoder
        if (codec != null) {
            try {
                runCatching {
                    Log.i(
                        "xcertplay-usb",
                        "T0|decoder.surface|stage=action type=$streamType action=setOutputSurface" +
                            " thread=${Thread.currentThread().name}",
                    )
                }
                codec.setOutputSurface(surface)
                Log.i(TAG, "video decoder output surface updated")
                return
            } catch (error: Exception) {
                runCatching {
                    Log.i(
                        "xcertplay-usb",
                        "T0|decoder.surface|stage=action type=$streamType action=reconfigure" +
                            " thread=${Thread.currentThread().name}",
                    )
                }
                Log.w(TAG, "video decoder output surface update failed; reconfiguring", error)
            }
        }
        releaseDecoder()
        lastConfig?.let(::configureDecoder)
    }

    private fun feed(frame: VideoJob.Frame) {
        if (decoder == null) {
            if (outputSurface == null) return
            val config = lastConfig
            if (config == null) {
                // The phone emits its codec config (opcode=1) before the first
                // frame; frames without one mean it skipped that step (seen with
                // HEVC). Nudge it instead of dropping frames forever.
                if (!awaitingConfigLogged) {
                    awaitingConfigLogged = true
                    Log.i(TAG, "video frames without codec config; requesting keyframe")
                }
                needsKeyFrame = true
                return
            }
            configureDecoder(config)
        }
        val activePump = pump ?: return
        val annexB = MediaCodecSupport.toAnnexB(frame.nalus)
        require(annexB.isNotEmpty()) { "Malformed video access unit" }
        if (syncGate?.accept(annexB) != true) {
            // RASL intentionally skipped after CRA does not mean sync was lost.
            if (syncGate?.waitingForRandomAccess == true) {
                needsKeyFrame = true
                syncSkips++
            }
            return
        }
        needsKeyFrame = false
        if (!submittedFrameLogged) {
            submittedFrameLogged = true
            Log.i(TAG, "video decoder first random access input bytes=${annexB.size}")
        }
        // ⚠️ 输入侧帧龄检查（2026-10-07，修「左右划动卡顿」）。
        //
        // 帧已经在队列里等到「解码器都跟不上」了，再喂进去只会让积压更深。
        // 照上游思路：丢掉当前帧、请求关键帧，让链路直接跳到最新画面。
        // 刻意**不**重启整条解码链（上游 recover 的做法）——车机上重建解码器
        // 代价高（上一版踩过能力表查询阻塞 10.7 秒），这里只用既有的
        // needsKeyFrame 机制让手机重新发一个 I 帧。
        val waitingFrames = if (activePump.hasPending) 1 else 0
        if (frame.receivedUs < lastPtsUs && waitingFrames > 0) {
            val ageUs = System.nanoTime() / 1000 - frame.receivedUs
            if (ageUs > MAX_FRAME_AGE_NS / 1_000L) {
                droppedInputFrames++
                if (!droppedInputFrameLogged) {
                    droppedInputFrameLogged = true
                    Log.i(
                        TAG,
                        "video frame dropped (decoder behind by ${ageUs / 1000}ms, " +
                            "pending=$waitingFrames); requesting keyframe",
                    )
                }
                needsKeyFrame = true
                return
            }
        }
        lastPtsUs = maxOf(lastPtsUs + 1, frame.receivedUs)
        activePump.submit(annexB, lastPtsUs)
        pendingSinceNs = System.nanoTime()
        activePump.tick()
    }

    private fun drainOutput(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val index = codec.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> logOutputFormat(codec.outputFormat)
                index >= 0 -> {
                    // ⚠️ 帧龄丢帧（2026-10-07，修「左右划动卡顿」）。
                    //
                    // 旧实现 render 恒为 true：输出缓冲积压多少都照画，实测
                    // maxOutputAgeMs=960 —— 手指划完近 1 秒画面才跟上。
                    //
                    // 照上游 AndroidMediaSink：只渲染 250ms 以内的帧。超龄帧丢弃
                    // 不影响解码器的参考帧链（下游 H.264/HEVC 都能从后续
                    // 非参考帧继续），代价只是画面少一帧，换来的是不积压。
                    val presentationAgeNs = System.nanoTime() - info.presentationTimeUs * 1_000L
                    val fresh = presentationAgeNs <= MAX_FRAME_AGE_NS
                    if (!fresh) {
                        droppedOutputFrames++
                    }
                    // Under dual-stream load, dropping every late output can starve
                    // the instrument indefinitely. Allow a bounded visual heartbeat.
                    val nowNs = System.nanoTime()
                    val heartbeatFrame = streamType == 111 && nowNs - lastRenderNs >= 500_000_000L
                    val render = outputSurface != null &&
                        info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 &&
                        (fresh || heartbeatFrame)
                    outputCount++
                    maxOutputAgeUs = maxOf(maxOutputAgeUs, presentationAgeNs / 1000)
                    codec.releaseOutputBuffer(index, render)
                    if (render) {
                        renderedCount++
                        lastRenderNs = nowNs
                        // feed() assigns the System.nanoTime-based arrival as input PTS. This
                        // measurement assumes MediaCodec preserves that PTS in BufferInfo.
                        mediaMetricsMonitor?.recordVideoFrameRendered(
                            arrivalNs = info.presentationTimeUs * 1_000L,
                            renderedNs = System.nanoTime(),
                        )
                    }
                    if (render && !renderedFrameLogged) {
                        renderedFrameLogged = true
                        Log.i(TAG, "video decoder rendered first frame bytes=${info.size}")
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> return
            }
        }
    }

    private fun logVideoStats() {
        val now = System.nanoTime()
        if (now - statsStartNs < 5_000_000_000L) return
        if (inputCount + outputCount + inputRetries + syncSkips > 0) {
            val seconds = (now - statsStartNs) / 1e9
            Log.i(TAG, "video stats type=$streamType inputFps=${inputCount / seconds} decodedFps=${outputCount / seconds} renderedFps=${renderedCount / seconds} " +
                "inputRetries=$inputRetries syncSkips=$syncSkips queued=${queue.size} " +
                "maxOutputAgeMs=${maxOutputAgeUs / 1000} waitingForSync=${syncGate?.waitingForRandomAccess} " +
                    "droppedOut=$droppedOutputFrames droppedIn=$droppedInputFrames")
        }
        statsStartNs = now
        inputCount = 0
        outputCount = 0
        renderedCount = 0
        inputRetries = 0
        syncSkips = 0
        maxOutputAgeUs = 0
    }

    private fun logOutputFormat(format: MediaFormat) {
        Log.i(
            TAG,
            "video decoder output format " +
                "size=${format.intOrNull(MediaFormat.KEY_WIDTH)}x" +
                "${format.intOrNull(MediaFormat.KEY_HEIGHT)} " +
                "stride=${format.intOrNull(MediaFormat.KEY_STRIDE)} " +
                "slice=${format.intOrNull(MediaFormat.KEY_SLICE_HEIGHT)} " +
                "standard=${format.intOrNull(MediaFormat.KEY_COLOR_STANDARD)} " +
                "range=${format.intOrNull(MediaFormat.KEY_COLOR_RANGE)} " +
                "transfer=${format.intOrNull(MediaFormat.KEY_COLOR_TRANSFER)}",
        )
    }

    @Synchronized
    private fun releaseDecoder() {
        val codec = decoder
        decoder = null
        pump = null
        syncGate = null
        if (codec != null) {
            needsKeyFrame = true
            try {
                codec.stop()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                codec.release()
            } catch (_: Exception) {
                // Best effort.
            }
        }
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        /**
         * 帧龄上限（2026-10-07，修「左右划动卡顿」）。
         *
         * 上游 `AndroidMediaSink` 用同一个值（250ms）做**输入与输出两侧**的丢帧判定。
         * 超过这个年龄的帧已经"过期"：画出来只会让画面更滞后于手指，
         * 丢掉反而更跟手。
         */
        const val MAX_FRAME_AGE_NS = 250_000_000L

        const val MAX_INPUT_SIZE = 8 * 1024 * 1024
        const val INPUT_STALL_NS = 2_000_000_000L
        const val KEY_FRAME_REQUEST_INTERVAL_NS = 2_000_000_000L
        val START_CODE = byteArrayOf(0x00, 0x00, 0x00, 0x01)
    }
}

private fun MediaFormat.intOrNull(key: String): Int? =
    if (!containsKey(key)) {
        null
    } else {
        try {
            getInteger(key)
        } catch (_: Exception) {
            null
        }
    }

/** Decodes AAC-LC/Opus to PCM and plays it, or plays wired LPCM directly. */
private class AudioRenderer(
    val format: AudioFormat,
    private val advancedAudioChannelMapping: Boolean,
    private val mediaAudioChannel: Int,
    private val navigationAudioChannel: Int,
    private val mediaBufferMillis: Int,
    mediaMetricsMonitor: MediaMetricsMonitor?,
    private val mediaOutputGain: MediaOutputGain,
) : Closeable {
    private data class AudioPacket(val rtp: ByteArray, val sample: Int)

    private val queue = LinkedBlockingQueue<AudioPacket>(MAX_QUEUED_PACKETS)
    @Volatile private var running = true
    @Volatile private var started = false
    private var codec: MediaCodec? = null
    /** 系统 Opus 解码器缺失时的软件兜底；非 null 时音频走它，codec 保持 null。 */
    // @Volatile：在渲染线程写入（configureCodec 失败时启用），在另一侧读取并长跑；不加会有可见性空窗。
    @Volatile private var opusFallback: SoftwareOpusDecoder? = null
    private var track: AudioTrack? = null
    private var pcm = ByteArray(64 * 1024)
    private var playbackStarted = false
    private var prebufferBytes = 0
    private var startThresholdBytes = 0
    private var fadeApplied = false
    private var droppedPacketsLogged = false
    private var firstAacPayloadLogged = false
    private var firstOpusShortPacketLogged = false
    private var firstInputQueuedLogged = false
    private var inputQueued = 0
    private var inputDropped = 0
    private var outputBuffers = 0
    private var firstPcmLogged = false
    private var trackBytesPerFrame = 0
    private var totalBytesWritten = 0L
    private var playbackHeadWraps = 0L
    private var lastPlaybackHeadRaw = 0L
    private var lastPlaybackHeadFrames = 0L
    private val audioTimestamp = AudioTimestamp()
    private var lastAudioTimestampQueryNs = 0L
    private var timestampHeadWraps = 0L
    private var lastTimestampHeadRaw = 0L
    private var lastTimestampHeadFrames = 0L
    private var latestOutputLatencyMs: Float? = null
    @Volatile private var mediaMetricsMonitor = mediaMetricsMonitor
    private val thread = Thread(::run, "carplay-audio").apply { isDaemon = true }

    fun start() {
        if (started) return
        started = true
        thread.start()
    }

    fun submit(rtp: ByteArray, sample: Int) {
        if (!started || !queue.offer(AudioPacket(rtp, sample))) {
            if (started && !droppedPacketsLogged) {
                droppedPacketsLogged = true
                Log.w(TAG, "audio queue full; dropping newest packets to bound latency")
            }
        }
    }

    fun setMediaMetricsMonitor(monitor: MediaMetricsMonitor?) {
        mediaMetricsMonitor = monitor
    }

    override fun close() {
        running = false
        thread.interrupt()
    }

    /**
     * 通话（Siri / 电话）结束后把媒体音轨唤醒。
     *
     * ## 为什么需要它
     *
     * 部分车机的音频策略在进入通话时会**把媒体音轨单独静音**（而不是只靠音频焦点
     * 让上层退让），通话退出后它**不会自己恢复媒体音轨的音量**。现场表现就是
     * DiPlay 记录的那条用户报告：USB 连接下挂断电话后要手动拨一下 iPhone 音量，
     * 声音才回来——因为那一下才触发重设音量。
     *
     * 我们的音频模式恢复（`AudioModeLeaseManager` 的引用计数租约）只保证 `mode`
     * 回到通话前，管不到媒体音轨的音量；这里补的就是这一环。
     *
     * ## 边界（刻意保守）
     *
     * - **只对 media 音轨生效**：导航/Siri/通话音轨各自的生命周期由流本身管理，
     *   在这里统一唤醒会打乱它们的启停。
     * - **不重建音轨、不丢已缓冲 PCM**：只是把音量设回满值并确保在播放态。
     *   重建音轨会让用户听到一次明显的断音，比"要拨一下音量"更糟。
     * - 全程 `runCatching`：音轨可能已被并发 close，这里失败不该影响通话收尾。
     */
    fun resumeAfterCommunication() {
        if (!MediaAudioBuffer.isMediaType(format.audioType)) return
        val track = track ?: return
        runCatching {
            // A call ending must never override a concurrent media focus loss.
            mediaOutputGain.refresh(this)
            if (playbackStarted) {
                // 车机把音轨暂停（而非仅静音）时，这里把它重新拉起来。
                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
            }
            Log.i(TAG, "audio media playback renewed after communication type=${format.payloadType}")
        }.onFailure {
            Log.w(TAG, "audio media playback renew failed type=${format.payloadType}: ${it.message}")
        }
    }

    private fun run() {
        try {
            when (format.codec) {
                AudioCodecKind.AAC_LC -> configureCodec(MediaFormat.MIMETYPE_AUDIO_AAC)
                AudioCodecKind.OPUS -> configureCodec(MediaFormat.MIMETYPE_AUDIO_OPUS)
                AudioCodecKind.LPCM -> Unit
            }
            createTrack()
            while (running) handle(queue.take())
        } catch (_: InterruptedException) {
            // Worker shut down.
        } catch (error: Exception) {
            if (running) Log.e(TAG, "audio renderer worker failed", error)
        } finally {
            release()
        }
    }

    private fun configureCodec(mime: String) {
        val mediaFormat = MediaFormat().apply {
            setString(MediaFormat.KEY_MIME, mime)
            setInteger(MediaFormat.KEY_SAMPLE_RATE, format.sampleRate)
            setInteger(MediaFormat.KEY_CHANNEL_COUNT, format.channels)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                setInteger(MediaFormat.KEY_IS_ADTS, 1)
                setByteBuffer("csd-0", ByteBuffer.wrap(aacAudioSpecificConfig()))
            } else {
                setByteBuffer("csd-0", ByteBuffer.wrap(opusHead()))
                setByteBuffer("csd-1", ByteBuffer.wrap(opusCodecDelay()))
                setByteBuffer("csd-2", ByteBuffer.wrap(opusSeekPreRoll()))
            }
        }
        if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
            Log.i(
                TAG,
                "audio AAC config rate=${format.sampleRate} channels=${format.channels} " +
                    "csd0=${aacAudioSpecificConfig().toHexString()}",
            )
        }
        codec = try {
            MediaCodec.createDecoderByType(mime).also {
                it.configure(mediaFormat, null, null, 0)
                it.start()
                Log.i(TAG, "audio decoder configured mime=$mime name=${it.name}")
            }
        } catch (error: Exception) {
            Log.e(TAG, "audio decoder configuration failed mime=$mime", error)
            null
        }
        // 系统 codec 不可用时，Opus 走软件解，保证「没解码器」不等于「没声音」。
        // 只对 Opus 生效：AAC 的软解要么体积大要么授权受限，且 AAC 在车机 ROM
        // 上几乎不会缺，硬塞兜底反而增加未经验证的路径。
        if (codec == null && mime == MediaFormat.MIMETYPE_AUDIO_OPUS) {
            opusFallback = try {
                SoftwareOpusDecoder(format.sampleRate, format.channels).also {
                    Log.w(
                        TAG,
                        "audio Opus falling back to software decoder " +
                            "rate=${format.sampleRate} channels=${format.channels}",
                    )
                }
            } catch (error: Exception) {
                Log.e(TAG, "audio Opus software fallback unavailable", error)
                null
            }
        }
    }

    private fun createTrack() {
        val encoding = AndroidAudioFormat.ENCODING_PCM_16BIT
        val trackChannelCount = if (format.channels >= 2) 2 else 1
        val channelMask = if (trackChannelCount == 2) AndroidAudioFormat.CHANNEL_OUT_STEREO
        else AndroidAudioFormat.CHANNEL_OUT_MONO
        val minBuffer = AudioTrack.getMinBufferSize(format.sampleRate, channelMask, encoding)
        if (minBuffer <= 0) {
            Log.e(TAG, "AudioTrack buffer size unavailable rate=${format.sampleRate} channels=${format.channels}")
            return
        }
        // 缓冲按音频种类区分：媒体流（音乐/播客）走抖动缓冲，能把无线链路的
        // 几百毫秒空档吞掉；通话/Siri/导航提示保持小缓冲，保低延迟。
        // 改动前这里是「两类同值」，等于不区分——网络一抖音乐就断。
        //
        // ⚠️ 上游 1.3.2 也加了一套缓冲控制（`MainMediaAudioBuffer`，档位
        // 100~1000/步进100、默认 100）。**本 fork 刻意保留自己这一套**
        // （见 PATCHES.md PATCH-0014）：我们的档位是 300/500/1000、容量另留
        // 200ms 余量，且多一条 `startBytesFor` 起播阈值保护。两套语义不同，
        // 合并时**不要**把 `bufferBytes` 换成上游的算法。
        // 上游那个参数在本类里仍然收下（只为让上游 common/ 的具名调用编译），
        // 但不参与建轨。
        val isMedia = MediaAudioBuffer.isMediaType(format.audioType)
        val plan = MediaAudioBuffer.plan(
            isMedia = isMedia,
            sampleRate = format.sampleRate,
            channels = format.channels,
            minBufferBytes = minBuffer,
            mediaMillis = mediaBufferMillis,
        )
        val bufferBytes = plan.trackBufferBytes
        val selection = mappedSelection()
        val streamOverride = channelOverride(selection.channel)
        var routeLabel: String
        val created: AudioTrack? = when (streamOverride) {
            0 -> {
                routeLabel = "usage"
                usageBasedTrack(encoding, channelMask, bufferBytes, selection)
            }
            AudioChannelOverride.LYNK01_CHANNEL -> {
                routeLabel = "lynk01 usage=12 contentType=17"
                usageBasedTrack(
                    encoding,
                    channelMask,
                    bufferBytes,
                    selection,
                    lynk01 = true,
                )
            }
            else -> {
            routeLabel = "streamType=$streamOverride"
            // 老式 `AudioTrack(streamType, ...)` 在部分车机 ROM 上会被拒（返回未初始化
            // 的轨或直接抛异常）。那种情况下必须退回按 usage 建轨——「声音走错通道」
            // 还能排查，「整条音频没了」不能。
            val streamType = streamOverride
            val legacy = LegacyAudioFallback.build(
                createLegacy = {
                    AudioTrack(
                        streamType,
                        format.sampleRate,
                        channelMask,
                        encoding,
                        bufferBytes,
                        AudioTrack.MODE_STREAM,
                    )
                },
                isInitialized = { it.state == AudioTrack.STATE_INITIALIZED },
                release = { it.release() },
                createFallback = {
                    routeLabel = "streamType=$streamType(fallback=usage)"
                    Log.w(
                        TAG,
                        "streamType=$streamType rejected by this ROM; falling back to usage-based track",
                    )
                    usageBasedTrack(encoding, channelMask, bufferBytes, selection)
                },
            )
            legacy
            }
        }
        track = created
        created?.let { ActiveAudioTrackProbe.add(it, "type=${format.payloadType}/${format.audioType}") }
        if (created != null && MediaAudioBuffer.isMediaType(format.audioType)) {
            val applied = mediaOutputGain.attach(this) { gain ->
                check(created.setVolume(gain) == AudioTrack.SUCCESS) { "AudioTrack gain rejected" }
            }
            Log.i(TAG, "audio media gain attached type=${format.payloadType} gain=${mediaOutputGain.current()} applied=$applied")
        }
        // 帧字节数与建轨一致（16bit PCM）：单声道 2、双声道 4。
        //
        // ⚠️ 这个字段是**上游指标代码**要用的（`writePcm` 里用
        // `totalBytesWritten / trackBytesPerFrame` 算已写帧数），
        // 合并时不能因为"我们用不到"就删掉——删了上游的 `recordAudioBuffer` 会除零。
        // 顺便也让下面的容量换算与它同源，不再各写一个 4 / 2。
        trackBytesPerFrame = trackChannelCount * BYTES_PER_PCM_16_SAMPLE
        // AudioTrack 申请到的真实容量可能小于请求值。写满且未 play() 时写入会阻塞，
        // 起点若高于真实容量，play() 永远不会执行——现场表现是「整条音频哑掉」。
        //
        // 用 getBufferSizeInFrames() 而不是某个「InBytes」方法：框架只公开了帧数版
        // （按字节的那个名字属于 Builder 的 setter）。换算用的帧字节数与建轨一致。
        val frameBytes = trackBytesPerFrame
        val actualCapacityBytes = created?.let { it.bufferSizeInFrames * frameBytes } ?: 0
        startThresholdBytes = MediaAudioBuffer.startBytesFor(
            plannedStartBytes = plan.startBytes,
            actualCapacityBytes = actualCapacityBytes,
            writeChunkBytes = PREBUFFER_WRITE_CHUNK_BYTES,
        )
        Log.i(
            TAG,
            "audio track prepared type=${format.payloadType} audioType=${format.audioType} " +
                "codec=${format.codec} " +
                "rate=${format.sampleRate} channels=${format.channels} " +
                "route=$routeLabel mode=${
                    if (advancedAudioChannelMapping) {
                        AudioChannelMappingMode.AUTOMOTIVE_BUS
                    } else {
                        AudioChannelMappingMode.MOBILE_COMPATIBLE
                    }
                } channel=${selection.channel} streamOverride=$streamOverride " +
                "isMedia=$isMedia bufferMillis=$mediaBufferMillis " +
                "trackBytes=$bufferBytes startBytes=$startThresholdBytes " +
                // 上游 1.3.2 加的字段：帧字节数，指标代码用它把已写字节换算成帧。
                "bytesPerFrame=$trackBytesPerFrame",
        )
    }

    /** 按 usage 建轨，即加 PATCH-0013 之前的行为，也是 streamOverride=0 与降级时的路径。 */
    private fun usageBasedTrack(
        encoding: Int,
        channelMask: Int,
        bufferBytes: Int,
        selection: AudioChannelSelection,
        lynk01: Boolean = false,
    ): AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(if (lynk01) lynk01Attributes() else audioAttributes(selection))
        .setAudioFormat(
            AndroidAudioFormat.Builder()
                .setEncoding(encoding)
                .setSampleRate(format.sampleRate)
                .setChannelMask(channelMask)
                .build(),
        )
        .setBufferSizeInBytes(bufferBytes)
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()

    /**
     * 0 = 按 usage 路由；1..20 = 尝试车机 ROM 支持的老式 streamType。
     *
     * 为什么通话与 Siri 不开放覆盖、为什么越界值按 0 处理，都写在
     * [AudioChannelOverride] 里，并由它的单测钉住——那几种失败模式的现场表现
     * 是「没声音」而不是报错，必须靠机器盯着。
     */
    private fun channelOverride(channel: AudioChannel): Int = AudioChannelOverride.forChannel(
        channel = channel,
        media = mediaAudioChannel,
        navigation = navigationAudioChannel,
    )

    private fun aacAudioSpecificConfig(): ByteArray {
        val frequencyIndex = MediaCodecSupport.aacFrequencyIndex(format.sampleRate)
        val value = (AAC_OBJECT_TYPE_LC shl 11) or
            (frequencyIndex shl 7) or
            (format.channels.coerceIn(1, 7) shl 3)
        return byteArrayOf((value ushr 8).toByte(), value.toByte())
    }

    private fun mappedSelection(): AudioChannelSelection {
        val mode = if (advancedAudioChannelMapping) {
            AudioChannelMappingMode.AUTOMOTIVE_BUS
        } else {
            AudioChannelMappingMode.MOBILE_COMPATIBLE
        }
        return AudioChannelMapper.map(
            audioType = format.audioType,
            payloadType = format.payloadType,
            mode = mode,
        )
    }

    /**
     * 领克 01 导航提示：usage 12（导航引导）+ content type 17（厂商私有，公开 SDK 没有这个常量）。
     * 公开的 [AudioAttributes.Builder.setContentType] 会把 17 收成 0，所以写完再核对，
     * 对不上就把 Builder 里的字段改成 17。
     */
    private fun lynk01Attributes(): AudioAttributes {
        val builder = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(LYNK01_CONTENT_TYPE)
        if (builder.build().contentType == LYNK01_CONTENT_TYPE) return builder.build()
        runCatching {
            val field = AudioAttributes.Builder::class.java.getDeclaredField("mContentType")
            field.isAccessible = true
            field.setInt(builder, LYNK01_CONTENT_TYPE)
        }
        val forced = builder.build()
        if (forced.contentType != LYNK01_CONTENT_TYPE) {
            runCatching {
                val field = AudioAttributes::class.java.getDeclaredField("mContentType")
                field.isAccessible = true
                field.setInt(forced, LYNK01_CONTENT_TYPE)
            }
        }
        Log.i(TAG, "lynk01 audio attributes usage=${forced.usage} contentType=${forced.contentType}")
        return forced
    }

    private fun audioAttributes(selection: AudioChannelSelection): AudioAttributes {
        val usage = usageFor(selection.channel)
        val contentType = contentTypeFor(selection.contentType)
        return AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(contentType)
            .build()
            .also {
                Log.i(
                    TAG,
                    "audio route type=${format.payloadType} audioType=${format.audioType} " +
                        "channel=${selection.channel} " +
                        "usage=$usage contentType=$contentType",
                )
            }
    }

    private fun usageFor(channel: AudioChannel): Int = when (channel) {
        AudioChannel.MEDIA -> AudioAttributes.USAGE_MEDIA
        AudioChannel.PHONE -> AudioAttributes.USAGE_VOICE_COMMUNICATION
        AudioChannel.ASSISTANT -> AudioAttributes.USAGE_ASSISTANT
        AudioChannel.NAVIGATION -> AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
        // 来电铃声走系统铃声属性（DBPlay PATCH-0015，思路来自 DiPlay）：
        // 铃声若不单独成类，会落进导航提示那一路，现场表现是「来电只振动/不响」。
        AudioChannel.RINGTONE -> AudioAttributes.USAGE_NOTIFICATION_RINGTONE
    }

    private fun contentTypeFor(contentType: AudioContentType): Int = when (contentType) {
        AudioContentType.MUSIC -> AudioAttributes.CONTENT_TYPE_MUSIC
        AudioContentType.SPEECH -> AudioAttributes.CONTENT_TYPE_SPEECH
    }

    /** Minimal OpusHead CSD for the mono 48 kHz stream CarPlay negotiates. */
    private fun opusHead(): ByteArray {
        val head = ByteArray(19)
        "OpusHead".toByteArray(Charsets.US_ASCII).copyInto(head, 0)
        head[8] = 1
        head[9] = format.channels.toByte()
        head[10] = 0x38
        head[11] = 0x01
        head[12] = format.sampleRate.toByte()
        head[13] = (format.sampleRate ushr 8).toByte()
        head[14] = (format.sampleRate ushr 16).toByte()
        head[15] = (format.sampleRate ushr 24).toByte()
        return head
    }

    private fun opusCodecDelay(): ByteArray =
        java.nio.ByteBuffer.allocate(8)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putLong(OPUS_CODEC_DELAY_NANOS)
            .array()

    private fun opusSeekPreRoll(): ByteArray =
        java.nio.ByteBuffer.allocate(8)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putLong(OPUS_SEEK_PRE_ROLL_NANOS)
            .array()

    private fun handle(packet: AudioPacket) {
        val rtp = packet.rtp
        val timestampUs = sampleTimestampUs(packet.sample)
        when (format.codec) {
            AudioCodecKind.LPCM -> writePcm(byteSwapS16(rtp.copyOfRange(12, rtp.size)))
            AudioCodecKind.AAC_LC -> {
                val accessUnit = rtp.copyOfRange(12, rtp.size)
                if (accessUnit.isNotEmpty()) {
                    if (!firstAacPayloadLogged) {
                        firstAacPayloadLogged = true
                        Log.i(
                            TAG,
                            "audio AAC access unit bytes=${accessUnit.size} " +
                                "head=${accessUnit.copyOf(minOf(accessUnit.size, 16)).toHexString()}",
                        )
                    }
                    feedCodec(
                        MediaCodecSupport.adtsFrame(accessUnit, format.sampleRate, format.channels),
                        timestampUs,
                    )
                }
            }
            AudioCodecKind.OPUS -> {
                val accessUnit = rtp.copyOfRange(12, rtp.size)
                if (accessUnit.size < MIN_OPUS_PACKET_BYTES) {
                    if (!firstOpusShortPacketLogged) {
                        firstOpusShortPacketLogged = true
                        Log.i(
                            TAG,
                            "audio Opus skipping short packet bytes=${accessUnit.size} " +
                                "head=${accessUnit.toHexString()}",
                        )
                    }
                    return
                }
                // 先取局部变量：opusFallback 是可变的，直接判空会被智能转换拒绝。
                val fallback = opusFallback
                if (fallback != null) {
                    writePcm(fallback.decode(accessUnit))
                } else {
                    feedCodec(accessUnit, timestampUs)
                }
            }
        }
    }

    private fun sampleTimestampUs(sample: Int): Long =
        (sample.toLong() and 0xffff_ffffL) * 1_000_000L / format.sampleRate

    private fun feedCodec(payload: ByteArray, presentationTimeUs: Long) {
        val codec = codec ?: return
        val index = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
        if (index < 0) {
            inputDropped++
            if (inputDropped == 1) {
                Log.w(
                    TAG,
                    "audio decoder input unavailable codec=${format.codec} " +
                        "queued=$inputQueued dropped=$inputDropped",
                )
            }
            return
        }
        val input = codec.getInputBuffer(index) ?: return
        input.clear()
        if (payload.size <= input.remaining()) {
            input.put(payload)
            codec.queueInputBuffer(index, 0, payload.size, presentationTimeUs, 0)
            inputQueued++
            if (!firstInputQueuedLogged) {
                firstInputQueuedLogged = true
                Log.i(
                    TAG,
                    "audio decoder first input codec=${format.codec} bytes=${payload.size} " +
                        "head=${payload.copyOf(minOf(payload.size, 16)).toHexString()}",
                )
            }
        } else {
            codec.queueInputBuffer(index, 0, 0, 0, 0)
            inputDropped++
        }
        drainCodec(codec)
    }

    private fun drainCodec(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val index = codec.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                index >= 0 -> {
                    val size = info.size
                    if (size > 0) {
                        outputBuffers++
                        if (outputBuffers == 1 || outputBuffers % DECODED_BUFFER_LOG_INTERVAL == 0) {
                            Log.i(
                                TAG,
                                "audio decoder output codec=${format.codec} " +
                                    "buffers=$outputBuffers bytes=$size " +
                                    "queued=$inputQueued dropped=$inputDropped",
                            )
                        }
                    }
                    if (size > 0) {
                        val output = codec.getOutputBuffer(index)
                        if (output != null) {
                            if (size > pcm.size) pcm = ByteArray(size)
                            output.position(info.offset)
                            output.limit(info.offset + size)
                            output.get(pcm, 0, size)
                            writePcm(pcm, 0, size)
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> return
            }
        }
    }

    private fun writePcm(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        val track = track ?: return
        if (!firstPcmLogged && length > 0) {
            firstPcmLogged = true
            val end = minOf(data.size, offset + minOf(length, 16))
            Log.i(
                TAG,
                "audio first PCM type=${format.payloadType} bytes=$length " +
                    "head=${data.copyOfRange(offset, end).toHexString()}",
            )
        }
        if (!fadeApplied) {
            applyFadeIn(data, offset, length)
            fadeApplied = true
        }
        var written = 0
        while (written < length && running) {
            val writeLength = if (playbackStarted) {
                length - written
            } else {
                minOf(length - written, PREBUFFER_WRITE_CHUNK_BYTES)
            }
            val count = track.write(data, offset + written, writeLength, AudioTrack.WRITE_BLOCKING)
            if (count <= 0) break
            written += count
            totalBytesWritten += count
            val monitor = mediaMetricsMonitor
            if (monitor != null) {
                val nowNs = System.nanoTime()
                val totalFramesWritten = totalBytesWritten / trackBytesPerFrame
                monitor.recordAudioBuffer(
                    totalFramesWritten = totalFramesWritten,
                    playbackHeadFrames = playbackHeadFrames(track),
                    sampleRate = format.sampleRate,
                    nowNs = nowNs,
                    outputLatencyMs = outputLatencyMs(track, totalFramesWritten, nowNs),
                )
            }
            if (!playbackStarted) {
                prebufferBytes += count
                if (prebufferBytes >= startThresholdBytes) {
                    track.play()
                    playbackStarted = true
                    Log.i(TAG, "audio playback started type=${format.payloadType}")
                }
            }
        }
    }

    private fun playbackHeadFrames(track: AudioTrack): Long {
        val raw = track.playbackHeadPosition.toLong() and UINT32_MASK
        if (raw < lastPlaybackHeadRaw && lastPlaybackHeadRaw - raw > UINT32_HALF_RANGE) {
            playbackHeadWraps++
        }
        lastPlaybackHeadRaw = raw
        val unwrapped = playbackHeadWraps * UINT32_MODULUS + raw
        lastPlaybackHeadFrames = maxOf(lastPlaybackHeadFrames, unwrapped)
        return lastPlaybackHeadFrames
    }

    private fun outputLatencyMs(
        track: AudioTrack,
        totalFramesWritten: Long,
        nowNs: Long,
    ): Float? {
        if (!playbackStarted) return null
        if (nowNs - lastAudioTimestampQueryNs < AUDIO_TIMESTAMP_INTERVAL_NS) {
            return latestOutputLatencyMs
        }
        lastAudioTimestampQueryNs = nowNs
        if (!track.getTimestamp(audioTimestamp)) {
            latestOutputLatencyMs = null
            return null
        }
        val raw = audioTimestamp.framePosition and UINT32_MASK
        if (raw < lastTimestampHeadRaw && lastTimestampHeadRaw - raw > UINT32_HALF_RANGE) {
            timestampHeadWraps++
        }
        lastTimestampHeadRaw = raw
        val unwrapped = timestampHeadWraps * UINT32_MODULUS + raw
        lastTimestampHeadFrames = maxOf(lastTimestampHeadFrames, unwrapped)
        val elapsedNs = (nowNs - audioTimestamp.nanoTime).coerceAtLeast(0L)
        val elapsedFrames = (elapsedNs.toDouble() * format.sampleRate / NANOS_PER_SECOND).toLong()
        val presentedFrames = minOf(totalFramesWritten, lastTimestampHeadFrames + elapsedFrames)
        return MediaMetricsMonitor.audioLatencyMs(
            totalFramesWritten,
            presentedFrames,
            format.sampleRate,
        ).also { latestOutputLatencyMs = it }
    }

    private fun applyFadeIn(data: ByteArray, offset: Int, length: Int) {
        val samples = (length - length % 2) / 2
        val fadeSamples = minOf(samples, maxOf(1, format.sampleRate / 100))
        for (index in 0 until fadeSamples) {
            val position = offset + index * 2
            val sample = (data[position].toInt() and 0xff) or (data[position + 1].toInt() shl 8)
            val scaled = (sample.toLong() * (index + 1) / fadeSamples).toInt()
            data[position] = scaled.toByte()
            data[position + 1] = (scaled shr 8).toByte()
        }
    }

    private fun byteSwapS16(source: ByteArray): ByteArray {
        for (index in 0 until source.size - 1 step 2) {
            val tmp = source[index]
            source[index] = source[index + 1]
            source[index + 1] = tmp
        }
        return source
    }

    @Synchronized
    private fun release() {
        val codec = codec
        this.codec = null
        // 软解无原生资源可回收，断开引用即可（Concentus 是纯 Java）。
        opusFallback = null
        if (codec != null) {
            try {
                codec.stop()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                codec.release()
            } catch (_: Exception) {
                // Best effort.
            }
        }
        val track = track
        this.track = null
        track?.let(ActiveAudioTrackProbe::remove)
        mediaOutputGain.detach(this)
        if (track != null) {
            try {
                track.pause()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                track.flush()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                track.release()
            } catch (_: Exception) {
                // Best effort.
            }
        }
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        /** 领克 01 导航提示的厂商 content type。公开 SDK 没有这个常量。 */
        const val LYNK01_CONTENT_TYPE = 17
        const val AAC_OBJECT_TYPE_LC = 2
        const val MIN_OPUS_PACKET_BYTES = 4
        const val OPUS_CODEC_DELAY_NANOS = 6_500_000L
        const val OPUS_SEEK_PRE_ROLL_NANOS = 80_000_000L
        const val INPUT_TIMEOUT_US = 10_000L
        const val MAX_QUEUED_PACKETS = 64
        const val MIN_TRACK_BUFFER_BYTES = 16 * 1024
        const val MIN_START_BUFFER_BYTES = 4 * 1024
        const val PREBUFFER_WRITE_CHUNK_BYTES = 2 * 1024
        const val DECODED_BUFFER_LOG_INTERVAL = 50
        const val AUDIO_TIMESTAMP_INTERVAL_NS = 200_000_000L
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val BYTES_PER_PCM_16_SAMPLE = 2
        const val UINT32_MASK = 0xffff_ffffL
        const val UINT32_HALF_RANGE = 0x8000_0000L
        const val UINT32_MODULUS = 0x1_0000_0000L
    }
}
