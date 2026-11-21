package com.dbtool.carplay.bridge

import android.util.Log
import com.dbtool.carplay.api.audio.CarAudioChannel
import com.dbtool.carplay.api.audio.CarAudioFormat
import com.dbtool.carplay.api.display.CarDisplay
import com.dbtool.carplay.api.media.CarMediaAdapter
import com.dbtool.carplay.api.media.CarVideoCodec
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.MediaSink
import com.shilapi.xcertplay.airplay.VideoCodec
import java.util.concurrent.ConcurrentHashMap

/**
 * 把 upstream 的 [MediaSink] 装饰一层，旁路广播给 [CarMediaAdapter]。
 *
 * 设计要点（这是"不改 Core 也能挂车机媒体能力"的关键）：
 * - 用 Kotlin 接口委托 `by delegate`：**所有**解码/渲染调用原样透传给 upstream 实现，
 *   我们只**额外**观测流生命周期，不拦截、不缓存、不改变时序
 *   → 不会影响已经正常工作的视频/音频链路。
 * - 只覆写 4 个"生命周期"方法，RTP/帧数据路径完全不碰
 *   → 即使车机侧适配器抛异常也不该影响播放（见下面的 try/catch）。
 *
 * 注意：`MediaSink.onAudio*` 不直接给 CarPlay 的 audioType，
 * 但 [AudioFormat] 自带 `audioType` 字段（upstream `AudioStream.AudioFormat`），
 * 因此这里可以准确还原 [CarAudioChannel]，无需包装 `AirPlayMediaHandler`。
 */
class UpstreamMediaSinkBridge(
    private val delegate: MediaSink,
    private val media: CarMediaAdapter,
    private val displayProvider: () -> CarDisplay,
    private val navigationSpeechFocus: (Boolean) -> Unit = {},
    private val advancedAudioChannelMapping: Boolean = false,
    private val audioStreamsChanged: () -> Unit = {},
) : MediaSink by delegate {

    private val codecByType = ConcurrentHashMap<Int, VideoCodec>()
    private val channelByType = ConcurrentHashMap<Int, CarAudioChannel>()
    private val audioGeneration = java.util.concurrent.atomic.AtomicLong()
    private val audioGenerationByType = ConcurrentHashMap<Int, Long>()
    private val navigationTypes = HashSet<Int>()
    private val activeAudio = ActiveAudioStreams(audioStreamsChanged)
    fun activeAudioStreams(): List<com.dbtool.carplay.api.audio.CarAudioStreamStatus> =
        activeAudio.snapshot()

    override fun onMicrophoneStarted(type: Int, config: com.shilapi.xcertplay.airplay.MicrophoneConfig) {
        delegate.onMicrophoneStarted(type, config)
        activeAudio.start(com.dbtool.carplay.api.audio.CarAudioStreamStatus(
            type, config.audioType, config.codec.name, config.sampleRate, config.channels, microphone = true))
    }

    override fun onMicrophoneStopped(type: Int) {
        delegate.onMicrophoneStopped(type)
        activeAudio.stop(type, microphone = true)
    }

    private fun updateNavigationFocus(type: Int, active: Boolean) = synchronized(navigationTypes) {
        val wasActive = navigationTypes.isNotEmpty()
        if (active) navigationTypes.add(type) else navigationTypes.remove(type)
        val isActive = navigationTypes.isNotEmpty()
        if (isActive != wasActive) runCatching { navigationSpeechFocus(isActive) }
    }

    override fun onVideoCodec(type: Int, codec: VideoCodec) {
        delegate.onVideoCodec(type, codec)
        codecByType[type] = codec
    }

    override fun onVideoConfig(type: Int, codecData: ByteArray) {
        // 纯观测：先把配置的关键字段打出来，再原样透传。
        // 真机上 HEVC 解码后色度全为 0（整屏偏绿）而 AVC 正常，
        // hvcC 的 chroma_format_idc 与位深是判断"流本身是单色/高位深"
        // 还是"本机合成路径丢色度"的唯一依据（见 VideoCodecConfigDiagnostics）。
        // 顺序保证：upstream 在 onVideoConfig 之前必定先调过 onVideoCodec，
        // 所以这里读 codecByType 一定拿得到正确编码类型。
        Log.i(
            TAG,
            VideoCodecConfigDiagnostics.describe(
                hevc = codecByType[type] == VideoCodec.H265,
                codecData = codecData,
            ),
        )
        delegate.onVideoConfig(type, codecData)
    }

    override fun onScreenStreamActive(type: Int, active: Boolean) {
        delegate.onScreenStreamActive(type, active)
        // 车机适配器属于"锦上添花"，异常绝不允许打断视频链路。
        runCatching {
            media.onVideoStreamActive(
                display = displayProvider(),
                codec = (codecByType[type] ?: VideoCodec.H264).toCarVideoCodec(),
                active = active,
            )
        }
    }

    override fun onAudioStarted(type: Int, format: AudioFormat, firstSample: Int) {
        val generation = audioGeneration.incrementAndGet()
        val previous = audioGenerationByType.put(type, generation)
        val knownType = format.audioType.lowercase().takeIf {
            it in setOf("telephony", "speechrecognition", "media", "ringtone", "ring", "alert", "default", "compatibility")
        } ?: "other"
        Log.i(TAG, "audio.lifecycle event=start generation=$generation previousGeneration=$previous " +
            "type=$type audioType=$knownType sampleRate=${format.sampleRate} channels=${format.channels} " +
            "elapsedMs=${android.os.SystemClock.elapsedRealtime()} owner=carplay externalRingOwner=unknown")
        updateNavigationFocus(type, isNavigationSpeech(format.audioType, type, advancedAudioChannelMapping))
        delegate.onAudioStarted(type, format, firstSample)
        activeAudio.start(com.dbtool.carplay.api.audio.CarAudioStreamStatus(
            type, format.audioType, format.codec.name, format.sampleRate, format.channels))
        val channel = CarAudioChannel.fromAirPlayAudioType(format.audioType)
        channelByType[type] = channel
        runCatching {
            media.onAudioStreamActive(channel, format.toCarAudioFormat(), true)
        }
    }

    override fun onAudioStopped(type: Int) {
        val generation = audioGenerationByType.remove(type)
        Log.i(TAG, "audio.lifecycle event=stop generation=$generation type=$type " +
            "elapsedMs=${android.os.SystemClock.elapsedRealtime()}")
        val channel = channelByType.remove(type)
        delegate.onAudioStopped(type)
        activeAudio.stop(type)
        runCatching {
            media.onAudioStreamActive(channel ?: CarAudioChannel.COMPATIBILITY, null, false)
        }
        updateNavigationFocus(type, false)
    }

    private companion object {
        /**
         * 与 upstream 媒体层的日志标签保持一致 —— 本类的诊断要和
         * `video size clamped` / `video decoder configured` 那几行挨着看。
         */
        const val TAG = "xcertplay-usb"
    }
}

/** Match the audio renderer: default/compatibility are navigation only in mobile mode. */
internal fun isNavigationSpeech(audioType: String, payloadType: Int, automotive: Boolean): Boolean =
    when (audioType.lowercase()) {
        "telephony", "speechrecognition", "media", "ringtone", "ring" -> false
        "alert" -> true
        "default", "compatibility" -> !automotive
        else -> payloadType != 102
    }

internal fun VideoCodec.toCarVideoCodec(): CarVideoCodec = when (this) {
    VideoCodec.H264 -> CarVideoCodec.H264
    VideoCodec.H265 -> CarVideoCodec.HEVC
}

internal fun AudioFormat.toCarAudioFormat(): CarAudioFormat = CarAudioFormat(
    sampleRate = sampleRate,
    channelCount = channels,
    codec = codec.name,
)
