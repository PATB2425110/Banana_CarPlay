package com.dbtool.carplay.api.media

/**
 * 「当前正在播放」的一份快照 —— 车机侧媒体卡片 / 仪表盘要显示的全部内容。
 *
 * ## 数据从哪来
 *
 * iPhone 通过 iAP2 的 `NowPlayingUpdate` 把歌名 / 歌手 / 专辑 / 时长 / 播放状态推给
 * 我们，封面则走 iAP2 的**文件传输**（`artworkFileTransferId` + 字节流）。
 * 上游 `CarPlayMediaSessionService` 已经把这些组装成一个 Media3 `MediaSession`，
 * 本接口的实现（`CarPlayNowPlayingClient`）**用标准 `MediaController` 去读它**，
 * 不碰上游任何 `internal` 符号。
 *
 * ## 为什么 `artworkBytes` 是原始字节而不是 Bitmap / URI
 *
 * 车机仪表盘不认 `file://`（跨进程读不到我们的私有目录），只认 `http(s)://`
 * 或 `content://`。所以最终必须由一个**本地 HTTP 服务**把字节喂出去
 * （见 `LocalCoverHttpServer` 的说明）。在这一层保留原始字节最省事：
 * 想转 Bitmap 随时能转，反过来（Bitmap → 原始编码）会有二次压缩损失。
 *
 * ## 相等性
 *
 * 这是 data 语义的类，但**不能**用 `data class`：Kotlin 为数组生成的是
 * **引用相等**，两份字节完全相同的快照会被判成"变了"，于是每帧都触发一次
 * 广播 / 封面重载。这里手写 `equals` / `hashCode`，用 `contentEquals`。
 */
class CarNowPlaying(
    /** 歌名。空串表示"没有媒体信息"，调用方应当整条忽略。 */
    val title: String,
    val artist: String,
    val album: String,
    val durationMillis: Long,
    val positionMillis: Long,
    val isPlaying: Boolean,
    /** 封面原始字节（iPhone 给的通常就是 JPEG）；null = 还没拿到 / 这首歌没有封面。 */
    val artworkBytes: ByteArray?,
    /** iAP2 queue index projected by Media3; stable during lyric/progress updates. */
    val trackNumber: Int? = null,
    /** Stable Media3 item identity, independent of a title that may be live lyrics. */
    val mediaId: String? = null,
) {
    /** 是否有可用的媒体信息（至少有个歌名或歌手）。 */
    val hasContent: Boolean get() = title.isNotBlank() || artist.isNotBlank()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CarNowPlaying) return false
        return title == other.title &&
            artist == other.artist &&
            album == other.album &&
            durationMillis == other.durationMillis &&
            positionMillis == other.positionMillis &&
            isPlaying == other.isPlaying &&
            trackNumber == other.trackNumber &&
            mediaId == other.mediaId &&
            artworkBytes.contentEquals(other.artworkBytes)
    }

    override fun hashCode(): Int {
        var result = title.hashCode()
        result = 31 * result + artist.hashCode()
        result = 31 * result + album.hashCode()
        result = 31 * result + durationMillis.hashCode()
        result = 31 * result + positionMillis.hashCode()
        result = 31 * result + isPlaying.hashCode()
        result = 31 * result + (artworkBytes?.contentHashCode() ?: 0)
        result = 31 * result + (trackNumber ?: 0)
        result = 31 * result + (mediaId?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "CarNowPlaying(title=$title, artist=$artist, album=$album, " +
            "playing=$isPlaying, pos=$positionMillis/$durationMillis, " +
            "artwork=${artworkBytes?.size ?: 0}B)"
}

/**
 * 「当前正在播放」的订阅点。
 *
 * 由 `:car:core-bridge` 实现（唯一能碰上游的模块），通过
 * [CarMediaAdapter.attachNowPlaying] 注入给车机平台适配器。
 * 这样 `:car:platform-*` 不需要知道上游、也不需要知道 media3。
 */
interface CarNowPlayingSource {

    /**
     * 订阅变化。**会立刻回调一次当前值**（可能为 null），
     * 这样订阅方不必自己再问一遍 [current]（那是竞态的来源）。
     *
     * @return 取消订阅；重复调用应当无害。
     */
    fun observe(listener: (CarNowPlaying?) -> Unit): () -> Unit

    /**
     * 当前值；还没连上会话或 iPhone 没在放媒体时为 null。
     *
     * ⚠️ 拿它做"播放还是暂停"的判断时要注意它可能是 **null**：
     * 那时应当退回"不知道"的分支，而不是假定某一个方向。
     */
    fun current(): CarNowPlaying?

    // ---------------------------------------------------------------- 发令
    //
    // ⚠️ 2026-10-11 新增：这个接口原本**只读**，所以底栏上的 ⏸ / ⏭ 是死按钮。
    // 命令与状态本来就是同一条通道的两面（都要握着一个 MediaController），
    // 拆成第二个接口只会让调用方同时持有两个引用、还要各自处理生命周期，
    // 所以直接加在这里。
    //
    // **默认实现是空操作**：不接管播放的平台（或还没接的平台）不必写一行代码。
    // 实现方要保证"还没有会话/还没连上"时是安全的空调用，不要抛。

    /**
     * 播放。
     *
     * ⚠️ 不要实现成"toggle"：方控与底栏都只在**明确知道当前状态**时才发对应命令
     * （见 `CarKeyHidMap.playPauseIndex` 的说明 —— 实车上只发 toggle 是不生效的）。
     */
    fun play() {}

    /** 暂停。语义同 [play]：显式命令，不是 toggle。 */
    fun pause() {}

    /** 下一曲。 */
    fun next() {}

    /**
     * 上一曲。
     *
     * 【2026-10-19 新增】补齐与 [next] 对称的那一半。原先只有 [next]，
     * 于是「车机媒体卡片的上一个」这条路上没有任何可调用的出口 ——
     * 方控走 HID 那条不受影响（那边直接发 HID 报文，不经本接口）。
     */
    fun previous() {}
}
