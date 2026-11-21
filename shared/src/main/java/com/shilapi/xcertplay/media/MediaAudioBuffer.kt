package com.shilapi.xcertplay.media

/**
 * 媒体音频流的抖动缓冲规划（DBPlay PATCH-0014）。
 *
 * ## 为什么单独给「媒体」一套缓冲
 *
 * 无线 CarPlay 的音频与视频走同一条 Wi-Fi 链路：链路占满或空中干扰时，
 * 音频包成串到达之前会先空上一段时间（几百毫秒很常见）。音乐不能跟着一起
 * 饿死——它需要一条「够长」的缓冲把空档吞掉。
 *
 * 而通话、Siri、导航提示音恰好相反：这类声音的价值在于**及时**，
 * 缓冲越大越迟钝，所以它们保持小缓冲、低延迟。
 *
 * 所以这里按音频种类分成两条路，而不是全类一个常数。
 *
 * ## 为什么还要 `startBytesFor`
 *
 * `AudioTrack` 申请到的真实容量可能小于请求值。写满且未 `play()` 时
 * 写入会阻塞，于是「先攒够再播」的起点若高于真实容量，`play()` 就永远不会
 * 被调用——现场表现是**整条音频哑掉**，而不是报错。
 * 因此起点必须压在真实容量之下，且不能小于一个写入块，否则仍然攒不满。
 */
object MediaAudioBuffer {

    /** 媒体流默认缓冲时长（毫秒）。三档可选，与车机上手感一致。 */
    const val DEFAULT_MILLIS = 300

    /** 媒体缓冲可选档位。 */
    val presets = listOf(300, 500, 1000)

    /** 容量在上方留出的余量：断流后成串到达的包需要空间落座。 */
    private const val HEADROOM_MILLIS = 200

    private const val MIN_TRACK_BUFFER_BYTES = 16 * 1024
    private const val MIN_START_BUFFER_BYTES = 4 * 1024

    /** 把任意输入夹到合法档位；非法值一律回落到 [DEFAULT_MILLIS]。 */
    fun sanitize(millis: Int): Int = millis.takeIf { it in presets } ?: DEFAULT_MILLIS

    /** 一条输出流的建轨计划：AudioTrack 容量 + 开播前需攒到的字节数。 */
    data class Plan(val trackBufferBytes: Int, val startBytes: Int)

    /**
     * 为一条输出流算建轨计划。
     *
     * @param isMedia 是否媒体流（音乐/播客）。false 时返回低延迟小缓冲。
     * @param sampleRate 采样率（Hz）
     * @param channels 声道数（夹到 1..2，多声道按双声道算，避免申请过大）
     * @param minBufferBytes 系统给出的 `getMinBufferSize`
     * @param mediaMillis 媒体缓冲档位（非预设值会被 [sanitize] 修正）
     */
    fun plan(
        isMedia: Boolean,
        sampleRate: Int,
        channels: Int,
        minBufferBytes: Int,
        mediaMillis: Int,
    ): Plan {
        val lowLatency = Plan(
            trackBufferBytes = maxOf(minBufferBytes * 4, MIN_TRACK_BUFFER_BYTES),
            startBytes = maxOf(minBufferBytes, MIN_START_BUFFER_BYTES),
        )
        if (!isMedia) return lowLatency
        val millis = sanitize(mediaMillis)
        val bytesPerSecond = sampleRate.toLong() * channels.coerceIn(1, 2) * 2
        val start = (bytesPerSecond * millis / 1000).toInt()
        val capacity = (bytesPerSecond * (millis + HEADROOM_MILLIS) / 1000).toInt()
        return Plan(
            trackBufferBytes = maxOf(capacity, lowLatency.trackBufferBytes),
            startBytes = maxOf(start, lowLatency.startBytes),
        )
    }

    /**
     * 把计划起点压到真实容量之下。
     *
     * 设备可能只批一个比请求更小的 AudioTrack；写满且暂停时写入会阻塞，
     * 起点必须留在真实容量之内，`play()` 才有机会执行。
     */
    fun startBytesFor(plannedStartBytes: Int, actualCapacityBytes: Int, writeChunkBytes: Int): Int {
        if (actualCapacityBytes <= 0) return plannedStartBytes
        return minOf(plannedStartBytes, actualCapacityBytes - writeChunkBytes)
            .coerceAtLeast(writeChunkBytes)
    }

    /**
     * 判定音频种类是否为「媒体」。
     *
     * CarPlay 的 `audioType` 字面量来自对端协商：音乐/播客为 `media`，
     * 通话为 `telephony`，Siri 为 `speechRecognition`，导航提示为
     * `navigation`/`alert`/`system`。除 `media` 之外一律按低延迟处理。
     */
    fun isMediaType(audioType: String): Boolean =
        audioType.equals("media", ignoreCase = true)
}
