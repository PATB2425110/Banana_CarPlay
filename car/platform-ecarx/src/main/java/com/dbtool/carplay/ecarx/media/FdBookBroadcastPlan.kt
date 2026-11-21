package com.dbtool.carplay.ecarx.media

/**
 * 帆书广播通道里**可以被单测钉住**的那部分判断。
 *
 * ## 为什么值得单独抽出来
 *
 * [FdBookBroadcaster] 本体要 `Context`、要 `Handler`、要真机才能跑；
 * 但它最容易出错的两处判断其实是**纯逻辑**：
 *
 * 1. **去重指纹** —— 指纹算错（比如漏了封面）会让封面永远不刷新，
 *    或者反过来每帧都重发把卡片刷闪；
 * 2. **切歌时的状态跳变** —— 这是"为什么歌名变了、卡片却不跟着切"的**唯一**原因，
 *    而它极不直观：服务端只按播放状态去重，同状态直接 `return`，连歌名都不读。
 *
 * 抽出来之后这两条不必上车试，跑单测就能证明。
 */
internal object FdBookBroadcastPlan {
    const val PLAY_STATUS_PLAYING = 3
    const val PLAY_STATUS_PAUSED = 2
    /** Exact compatibility refresh value emitted by box va.a:298–307; NOT fake pause=2. */
    const val REFRESH_STATUS = 0

    /** Trailing debounce with a fixed deadline from the first pending update. */
    fun debounceDelay(elapsedMillis: Long, quietMillis: Long, maxWaitMillis: Long): Long =
        minOf(quietMillis, (maxWaitMillis - elapsedMillis.coerceAtLeast(0L)).coerceAtLeast(0L))

    /**
     * 封面补发的最小间隔。
     *
     * 封面分片通常几十毫秒内到齐，用一个 400ms 的窗口把它们合并成一次补发，
     * 避免每片都广播一次把卡片刷闪。
     */
    const val COVER_REBROADCAST_MIN_INTERVAL_MILLIS = 400L

    /**
     * 数据广播的指纹：任一项变化才重发。
     *
     * ⚠️ **封面 URL 必须在指纹里**。少了它，切歌时若歌名/歌手恰好相同
     * （同专辑同名曲、翻唱），封面换了却不重发。
     * ⚠️ **进度不在指纹里**：进度每几百毫秒就变，放进去等于广播风暴 + 封面闪烁。
     * 进度只作为载荷发出去，不参与"要不要发"的判断。
     */
    fun fingerprint(
        name: String,
        author: String,
        coverUrl: String,
        status: Int,
        durationMillis: Int,
        itemId: String? = null,
    ): String = "$name|$author|$coverUrl|$status|$durationMillis" + (itemId?.let { "|$it" } ?: "")

    /** Same long extra type as box va.b BOOK_ID/FRAGMENT_ID; lyrics never change these IDs. */
    fun bookId(itemId: String?): Long = wireId("book", itemId)
    fun fragmentId(itemId: String?): Long = wireId("fragment", itemId)
    private fun wireId(kind: String, itemId: String?): Long =
        ("$kind\u0000${itemId?.takeIf(String::isNotBlank) ?: "dbplay-current-item"}").hashCode().toLong() and 0xffff_ffffL

    /**
     * A changed complete snapshot must pass ROMs which deduplicate equal playback states.
     * Box va.a:298–307 writes 0 then the actual status; the previous DBPlay pause=2
     * workaround was not that protocol. Never manufacture PLAYING for a paused source.
     * Progress-only updates do not reach this method. This proves wire behavior, not
     * that every external ROM renders refresh=0 invisibly.
     */
    fun statusSequence(snapshotChanged: Boolean, status: Int, lastStatus: Int, firstSnapshot: Boolean = false): List<Int> {
        val needsRefresh = (firstSnapshot || snapshotChanged && status == lastStatus) && status != REFRESH_STATUS
        return if (needsRefresh) {
            listOf(REFRESH_STATUS, status)
        } else {
            listOf(status)
        }
    }

    /**
     * 封面到货后要不要补发一次数据广播。
     *
     * 三种情况必须补发：本次曲目还没成功带过封面（lastSentCoverUrl 为空）、
     * 或者封面 URL 的版本变了（?v= 变了）。同一个 URL 重复回调不补。
     * 另加最小间隔，防止封面分片到达时连发。
     *
     * 判定顺序是定死的：
     * 1. 新 URL 为空 → 不补（没有可用的封面）；
     * 2. 距上次广播不足最小间隔 → 不补（合并同一批分片）；
     * 3. 上次没带过封面 → **补**（这是首要目标：本次曲目一直没封面）；
     * 4. 与上次 URL 相同 → 不补（重复回调）；
     * 5. 其余（版本变了）→ 补。
     */
    fun shouldRebroadcastForCover(
        lastSentCoverUrl: String?,
        newCoverUrl: String,
        sinceLastSentMillis: Long,
        minIntervalMillis: Long = COVER_REBROADCAST_MIN_INTERVAL_MILLIS,
    ): Boolean {
        if (newCoverUrl.isEmpty()) return false
        if (minIntervalMillis > 0 && sinceLastSentMillis < minIntervalMillis) return false
        if (lastSentCoverUrl.isNullOrEmpty()) return true
        if (lastSentCoverUrl == newCoverUrl) return false
        return true
    }
}
