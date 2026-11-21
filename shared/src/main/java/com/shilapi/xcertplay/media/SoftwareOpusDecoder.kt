package com.shilapi.xcertplay.media

import org.concentus.OpusDecoder

/**
 * 平台无关的 Opus 解码器。
 *
 * ## 为什么需要它
 *
 * 部分车机 ROM 的 Android codec 列表**不含 Opus**（或只列出无法按当前采样率
 * 初始化的实现），届时 [AndroidMediaSink] 的 `MediaCodec.createDecoderByType`
 * 直接失败，**CarPlay 音频整段没声**——而画面完全正常，用户只会说"连上了没声音"。
 *
 * 上行（麦克风）侧上游早已有软件兜底（见 `OpusEncoder.SoftwareOpusBackend`），
 * 但**下行（扬声器）侧一直只有系统 codec 一条路**。本类补上这条兜底，
 * 让"车机没 Opus 解码器"从"没声音"降级为"用 CPU 解，正常出声"。
 *
 * ## 与 `OpusEncoder.SoftwareOpusBackend` 的关系
 *
 * 两者都用 Concentus（`org.concentus`，纯 Java，无原生库依赖），但方向相反：
 * 那个是**编码**（PCM → Opus，麦克风上行），本类是**解码**（Opus → PCM，扬声器下行）。
 * 刻意各写各的：编码要 DTX/complexity 调优，解码只要还原，混在一起会让两边的
 * 参数都变得不好读。
 *
 * ## 边界
 *
 * - 只做"Opus 包 → PCM 字节"这一件事，不碰 AudioTrack、不碰路由；
 *   路由仍由 [AndroidMediaSink] 的既有逻辑决定。
 * - 失败即抛异常，由调用方决定是否降级到"静音但画面继续"，**不吞错**。
 */
internal class SoftwareOpusDecoder(
    private val sampleRate: Int,
    private val channels: Int,
) {
    private val decoder: OpusDecoder = try {
        OpusDecoder(sampleRate, channels)
    } catch (error: Exception) {
        throw IllegalStateException(
            "Cannot create software Opus decoder rate=$sampleRate channels=$channels",
            error,
        )
    }

    /** 每帧最大样本数（120 ms @ 48 kHz 上限，够覆盖 CarPlay 的所有帧长）。 */
    private val samples = ShortArray(MAX_FRAME_SAMPLES * channels)

    /**
     * 解一个 Opus 包，返回 PCM 16-bit 小端字节。
     *
     * 返回空数组表示这一包没有产出样本（例如 DTX 静音包），调用方应当跳过、
     * **不要**把它当成错误——这正是 Opus 表达静音的正常方式。
     */
    fun decode(packet: ByteArray): ByteArray {
        if (packet.isEmpty()) return EMPTY
        val decodedSamples = decoder.decode(
            packet,
            0,
            packet.size,
            samples,
            0,
            samples.size,
            false,
        )
        if (decodedSamples <= 0) return EMPTY
        val count = decodedSamples * channels
        val pcm = ByteArray(count * 2)
        for (index in 0 until count) {
            val value = samples[index].toInt()
            pcm[index * 2] = (value and 0xff).toByte()
            pcm[index * 2 + 1] = ((value shr 8) and 0xff).toByte()
        }
        return pcm
    }

    private companion object {
        /** 48 kHz 下 120 ms = 5760 样本，是 Opus 允许的单帧上限。 */
        const val MAX_FRAME_SAMPLES = 5_760
        val EMPTY = ByteArray(0)
    }
}
