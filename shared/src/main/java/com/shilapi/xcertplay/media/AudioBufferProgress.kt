package com.shilapi.xcertplay.media

/**
 * 跟踪 [android.media.AudioTrack] 的无符号播放头，统计「还有多少字节没播出去」。
 *
 * 播放头是 32 位无符号且会绕回，直接相减会在绕回瞬间算出巨大数值。
 * 这里按位丢弃高位、只累加增量，因此绕回前后都连续。
 *
 * 第一次采样只用来立基准（见 [lastHead]），不计入已播——本对象可能在播放
 * 中途才被创建，此时播放头的绝对值与「已播里程」毫无关系。
 *
 * 用途：判断媒体流是否已经「排空」——排空且底层编码队列也空，说明确实
 * 断流了，此时才值得重建缓冲；否则一有波动就重建，反而每次重建都制造
 * 一次可听见的停顿。
 */
internal class AudioBufferProgress(private val frameBytes: Int) {

    private var writtenBytes = 0L
    private var playedFrames = 0L

    /**
     * 上一次采到的播放头；`-1` 表示「还没有基准」。
     *
     * 不能用 0 当初始值：那等于假定播放头真的从 0 开始。会话中途才创建本对象时
     * （例如媒体流在播放中才被识别为媒体），第一次采样的绝对里程会被整段计入
     * 「已播」，于是 [queuedBytes] 恒为 0、[shouldRebuffer] 误判成「已排空」——
     * 表现就是好端端一首歌被当成断流而重建缓冲。
     */
    private var lastHead = -1L

    /** 记录已写入 AudioTrack 的字节数。 */
    fun written(bytes: Int) {
        writtenBytes += bytes
    }

    /** 已排队但尚未播放的字节数；不会因播放头绕回而跳变，也不小于 0。 */
    fun queuedBytes(rawHead: Int): Long {
        val head = rawHead.toLong() and 0xffff_ffffL
        // 首次采样只立基准：此刻并不知道之前播了多少，贸然相减会得到一个
        // 凭空的「已播」大数。基准立好之后，增量按位相减即可跨绕回连续。
        if (lastHead >= 0L) {
            playedFrames += (head - lastHead) and 0xffff_ffffL
        }
        lastHead = head
        return (writtenBytes - playedFrames * frameBytes).coerceAtLeast(0)
    }

    /**
     * 是否需要重建缓冲。
     *
     * 五个条件同时成立才算「真的断流」：媒体流、已在播放、播放期间出现过
     * 欠载、底层压缩队列已空、AudioTrack 也已排空。任何一个不成立都说明
     * 数据还在路上，重建只会打断已经正常的播放。
     */
    fun shouldRebuffer(
        isMedia: Boolean,
        playing: Boolean,
        underrunSinceStart: Boolean,
        compressedQueueEmpty: Boolean,
        rawHead: Int,
    ): Boolean =
        isMedia && playing && underrunSinceStart && compressedQueueEmpty &&
            queuedBytes(rawHead) == 0L

    /** 复位（重建缓冲或切换音频种类时调用）。 */
    fun reset() {
        writtenBytes = 0L
        playedFrames = 0L
        lastHead = -1L
    }
}
