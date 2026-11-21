package com.dbtool.carplay.ecarx.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 帆书广播通道里那两处纯逻辑的单测。
 *
 * 这两条都**没法靠"上车试"高效发现**：症状与原因完全脱节
 * （"歌名变了卡片不切"看起来像广播没发出去，实际是服务端把整条 return 了）。
 */
class FdBookBroadcastPlanTest {

    @Test fun `连续更新也必须在最长等待内发送`() {
        assertEquals(700L, FdBookBroadcastPlan.debounceDelay(0, 700, 1500))
        assertEquals(700L, FdBookBroadcastPlan.debounceDelay(400, 700, 1500))
        assertEquals(300L, FdBookBroadcastPlan.debounceDelay(1200, 700, 1500))
        assertEquals(0L, FdBookBroadcastPlan.debounceDelay(1500, 700, 1500))
        assertEquals(0L, FdBookBroadcastPlan.debounceDelay(2000, 700, 1500))
    }

    @Test fun `同曲歌词变化不能伪造暂停且封面URL保持不变`() {
        val cover = "http://127.0.0.1/cover.jpg?v=4"
        assertNotEquals(
            FdBookBroadcastPlan.fingerprint("歌词一", "歌手", cover, 3, 180000),
            FdBookBroadcastPlan.fingerprint("歌词二", "歌手", cover, 3, 180000),
        )
        assertEquals(listOf(3), FdBookBroadcastPlan.statusSequence(false, 3, 3))
        assertEquals(false, FdBookBroadcastPlan.shouldRebroadcastForCover(cover, cover, 2000))
    }

    // ------------------------------------------------------------ 去重指纹

    @Test
    fun `指纹包含封面URL`() {
        val a = FdBookBroadcastPlan.fingerprint("歌", "手", "http://a/1.jpg", 3, 1000)
        val b = FdBookBroadcastPlan.fingerprint("歌", "手", "http://a/2.jpg", 3, 1000)
        // 歌名/歌手相同但封面换了（同专辑同名曲、翻唱）必须被判成"变了"。
        assertNotEquals(a, b)
    }

    @Test
    fun `指纹包含播放状态`() {
        val playing = FdBookBroadcastPlan.fingerprint("歌", "手", "u", 3, 1000)
        val paused = FdBookBroadcastPlan.fingerprint("歌", "手", "u", 2, 1000)
        assertNotEquals(playing, paused)
    }

    @Test
    fun `指纹包含歌名与歌手`() {
        assertNotEquals(
            FdBookBroadcastPlan.fingerprint("甲", "手", "u", 3, 1000),
            FdBookBroadcastPlan.fingerprint("乙", "手", "u", 3, 1000),
        )
        assertNotEquals(
            FdBookBroadcastPlan.fingerprint("歌", "甲", "u", 3, 1000),
            FdBookBroadcastPlan.fingerprint("歌", "乙", "u", 3, 1000),
        )
    }

    /** 同一份内容必须得到同一个指纹，否则每次都会重发（卡片闪）。 */
    @Test
    fun `相同内容指纹相同`() {
        assertEquals(
            FdBookBroadcastPlan.fingerprint("歌", "手", "u", 3, 1000),
            FdBookBroadcastPlan.fingerprint("歌", "手", "u", 3, 1000),
        )
    }

    /**
     * ⚠️ 反向断言：**进度不进指纹**。
     *
     * 进度每几百毫秒就变一次，放进去等于广播风暴 + 封面闪烁。
     */
    @Test
    fun `进度不参与指纹`() {
        // fingerprint 的签名里根本没有 position 参数——这条断言靠"签名里没有它"成立，
        // 这里用一个显式的调用把它钉在文档层面。
        val fingerprint = FdBookBroadcastPlan.fingerprint(
            name = "歌",
            author = "手",
            coverUrl = "u",
            status = 3,
            durationMillis = 1000,
        )
        assertEquals("歌|手|u|3|1000", fingerprint)
    }

    // -------------------------------------------------------- 切歌状态跳变

    /**
     * 播放中内容变化必须让同状态去重的接收端受理，但不能伪造暂停。
     *
     * 服务端 `if (playStatus == 上次playStatus) return;` 会把整条广播丢掉，
     * 连歌名都不读 ⇒ 卡片不跟着切歌。这是"歌名变了但卡片不更新"的唯一原因。
     */
    @Test
    fun `播放中快照变化先发盒子刷新值而非暂停`() {
        val sequence = FdBookBroadcastPlan.statusSequence(
            snapshotChanged = true,
            status = FdBookBroadcaster.PLAY_STATUS_PLAYING,
            lastStatus = FdBookBroadcaster.PLAY_STATUS_PLAYING,
        )
        assertEquals(
            listOf(FdBookBroadcastPlan.REFRESH_STATUS, FdBookBroadcaster.PLAY_STATUS_PLAYING),
            sequence,
        )
    }

    /** 没切歌（只是进度/封面刷新）时不该制造跳变——否则卡片会闪。 */
    @Test
    fun `没切歌只发一条`() {
        val sequence = FdBookBroadcastPlan.statusSequence(
            snapshotChanged = false,
            status = FdBookBroadcaster.PLAY_STATUS_PLAYING,
            lastStatus = FdBookBroadcaster.PLAY_STATUS_PLAYING,
        )
        assertEquals(listOf(FdBookBroadcaster.PLAY_STATUS_PLAYING), sequence)
    }

    /** 状态本来就在变（3→2 或 2→3）时服务端不会去重，不需要额外跳变。 */
    @Test
    fun `状态已变化时不补跳变`() {
        assertEquals(
            listOf(FdBookBroadcaster.PLAY_STATUS_PAUSED),
            FdBookBroadcastPlan.statusSequence(
                snapshotChanged = true,
                status = FdBookBroadcaster.PLAY_STATUS_PAUSED,
                lastStatus = FdBookBroadcaster.PLAY_STATUS_PLAYING,
            ),
        )
        assertEquals(
            listOf(FdBookBroadcaster.PLAY_STATUS_PLAYING),
            FdBookBroadcastPlan.statusSequence(
                snapshotChanged = true,
                status = FdBookBroadcaster.PLAY_STATUS_PLAYING,
                lastStatus = FdBookBroadcaster.PLAY_STATUS_PAUSED,
            ),
        )
    }

    /**
     * 暂停中内容变化也必须刷新，但不允许发送PLAYING来假装恢复播放。
     */
    @Test
    fun `暂停中刷新后仍保持暂停不假播放`() {
        assertEquals(
            listOf(FdBookBroadcastPlan.REFRESH_STATUS, FdBookBroadcaster.PLAY_STATUS_PAUSED),
            FdBookBroadcastPlan.statusSequence(
                snapshotChanged = true,
                status = FdBookBroadcaster.PLAY_STATUS_PAUSED,
                lastStatus = FdBookBroadcaster.PLAY_STATUS_PAUSED,
            ),
        )
    }

    // -------------------------------------------------------- 封面到货补发

    /**
     * 🔴 本次曲目一直没带过封面（`lastSentCoverUrl` 为空）⇒ 必须补发。
     * 这就是"广播先发出去、封面 50ms 后到"那一路。
     */
    @Test
    fun `首次带封面要补发`() {
        assertTrue(
            FdBookBroadcastPlan.shouldRebroadcastForCover(
                lastSentCoverUrl = null,
                newCoverUrl = "http://127.0.0.1:42031/cover.jpg?v=2",
                sinceLastSentMillis = 10_000L,
            ),
        )
        assertTrue(
            FdBookBroadcastPlan.shouldRebroadcastForCover(
                lastSentCoverUrl = "",
                newCoverUrl = "http://127.0.0.1:42031/cover.jpg?v=2",
                sinceLastSentMillis = 10_000L,
            ),
        )
    }

    /** 同一个 URL 重复回调不补发——否则车机会反复重取同一张图。 */
    @Test
    fun `同一个URL重复回调不补发`() {
        assertEquals(
            false,
            FdBookBroadcastPlan.shouldRebroadcastForCover(
                lastSentCoverUrl = "http://127.0.0.1:42031/cover.jpg?v=2",
                newCoverUrl = "http://127.0.0.1:42031/cover.jpg?v=2",
                sinceLastSentMillis = 10_000L,
            ),
        )
    }

    /** URL 版本变了（v=2 → v=3）⇒ 必须补发，否则车机永远停在旧封面。 */
    @Test
    fun `封面版本变了要补发`() {
        assertTrue(
            FdBookBroadcastPlan.shouldRebroadcastForCover(
                lastSentCoverUrl = "http://127.0.0.1:42031/cover.jpg?v=2",
                newCoverUrl = "http://127.0.0.1:42031/cover.jpg?v=3",
                sinceLastSentMillis = 10_000L,
            ),
        )
    }

    /** 间隔不足 400ms ⇒ 不补发，把同一批封面分片合并掉。 */
    @Test
    fun `间隔不足最小间隔不补发`() {
        assertEquals(
            false,
            FdBookBroadcastPlan.shouldRebroadcastForCover(
                lastSentCoverUrl = "http://127.0.0.1:42031/cover.jpg?v=2",
                newCoverUrl = "http://127.0.0.1:42031/cover.jpg?v=3",
                sinceLastSentMillis = 399L,
            ),
        )
        // 恰好到 400ms 就不再拦。
        assertTrue(
            FdBookBroadcastPlan.shouldRebroadcastForCover(
                lastSentCoverUrl = "http://127.0.0.1:42031/cover.jpg?v=2",
                newCoverUrl = "http://127.0.0.1:42031/cover.jpg?v=3",
                sinceLastSentMillis = 400L,
            ),
        )
    }

    /** 新 URL 为空 ⇒ 一律不补发（没有可用的封面地址）。 */
    @Test
    fun `新URL为空不补发`() {
        assertEquals(
            false,
            FdBookBroadcastPlan.shouldRebroadcastForCover(
                lastSentCoverUrl = null,
                newCoverUrl = "",
                sinceLastSentMillis = 10_000L,
            ),
        )
    }

    /** 最小间隔传 0 表示关掉限流（纯判定，供以后按需调整）。 */
    @Test
    fun `最小间隔为0时不限流`() {
        assertTrue(
            FdBookBroadcastPlan.shouldRebroadcastForCover(
                lastSentCoverUrl = "http://127.0.0.1:42031/cover.jpg?v=2",
                newCoverUrl = "http://127.0.0.1:42031/cover.jpg?v=3",
                sinceLastSentMillis = 1L,
                minIntervalMillis = 0L,
            ),
        )
    }

    // ------------------------------------------------------------ 常量不变量

    /**
     * 🔴 心跳周期**必须小于服务端 3s 超时**，否则卡片会被清空又重建，肉眼可见地闪。
     */
    @Test
    fun `心跳周期小于服务端超时`() {
        assertTrue(
            "心跳 ${FdBookBroadcaster.HEARTBEAT_MILLIS}ms 必须 < 3000ms",
            FdBookBroadcaster.HEARTBEAT_MILLIS < 3_000L,
        )
    }

    /**
     * 广播的 action 与组件是**服务端写死的契约**，改一个字符整条通道就哑了。
     * 这里逐字钉住，防止有人"顺手整理常量"。
     */
    @Test
    fun `广播契约逐字不变`() {
        assertEquals(
            "ecarx.intent.broadcast.action.MEDIA_CONTROL_RECEIVER",
            FdBookBroadcaster.RECEIVER_ACTION,
        )
        assertEquals("com.geely.online.service", FdBookBroadcaster.RECEIVER_PACKAGE)
        assertEquals("com.media.control.MediaControlReceiver", FdBookBroadcaster.RECEIVER_CLASS)
        assertEquals("RECEIVER_MEDIA_BOOK_COVERURL", FdBookBroadcaster.KEY_COVER_URL)
        assertEquals("RECEIVER_MEDIA_BOOK_NAME", FdBookBroadcaster.KEY_NAME)
        assertEquals("RECEIVER_MEDIA_BOOK_AUTHOR_NAME", FdBookBroadcaster.KEY_AUTHOR)
        assertEquals("RECEIVER_MEDIA_PLAY_STATUS", FdBookBroadcaster.KEY_PLAY_STATUS)
        assertEquals("RECEIVER_MEDIA_APP_STATUS", FdBookBroadcaster.KEY_APP_STATUS)
    }

    /** 播放状态码也是契约：3 播放 / 2 暂停 / 0 停止。 */
    @Test
    fun `播放状态码不变`() {
        assertEquals(3, FdBookBroadcaster.PLAY_STATUS_PLAYING)
        assertEquals(2, FdBookBroadcaster.PLAY_STATUS_PAUSED)
        assertEquals(0, FdBookBroadcaster.PLAY_STATUS_STOPPED)
    }
}
