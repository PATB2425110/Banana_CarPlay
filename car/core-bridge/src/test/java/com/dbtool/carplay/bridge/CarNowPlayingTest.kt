package com.dbtool.carplay.bridge

import com.dbtool.carplay.api.media.CarNowPlaying
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CarNowPlaying] 的相等性单测。
 *
 * ## 为什么这条值得单独钉
 *
 * 它是"要不要重发广播 / 要不要重载封面"的判据：上层收到新快照后靠
 * `latest == value` 决定是不是真的变了。而它带着一个 `ByteArray`——
 * Kotlin 的 `data class` 对数组生成的是**引用相等**，两份字节完全相同的封面
 * 会被判成"变了"，于是每帧都重发一次广播、车机端每帧重载一次封面 ⇒ 卡片闪烁。
 *
 * 所以这里手写了 `equals`/`hashCode`，并用下面两条把它钉住。
 */
class CarNowPlayingTest {

    private fun sample(artwork: ByteArray?) = CarNowPlaying(
        title = "歌名",
        artist = "歌手",
        album = "专辑",
        durationMillis = 180_000L,
        positionMillis = 1_000L,
        isPlaying = true,
        artworkBytes = artwork,
    )

    /**
     * 🔴 两份**内容相同但不同实例**的封面字节必须判成相等。
     *
     * 上游每次发布封面都会 new 一个 ByteArray，用引用相等会永远"不等"。
     */
    @Test
    fun `封面字节按内容比较`() {
        val a = sample(byteArrayOf(1, 2, 3))
        val b = sample(byteArrayOf(1, 2, 3))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    /** 反向：内容不同必须判成不等（否则切歌不刷新封面）。 */
    @Test
    fun `封面字节不同则不等`() {
        assertNotEquals(sample(byteArrayOf(1, 2, 3)), sample(byteArrayOf(1, 2, 4)))
    }

    /** 有没有封面也算差异（"先元数据后封面"两帧之间必须被识别成变化）。 */
    @Test
    fun `有封面与无封面不等`() {
        assertNotEquals(sample(null), sample(byteArrayOf(9)))
    }

    /** 播放状态变化必须被识别——这是"卡片上播放/暂停图标要跟着变"的依据。 */
    @Test
    fun `播放状态变化即不等`() {
        val playing = sample(byteArrayOf(1))
        val paused = CarNowPlaying(
            title = playing.title,
            artist = playing.artist,
            album = playing.album,
            durationMillis = playing.durationMillis,
            positionMillis = playing.positionMillis,
            isPlaying = false,
            artworkBytes = playing.artworkBytes,
        )
        assertNotEquals(playing, paused)
    }

    /** 进度变化也算变化（虽然上层对进度另有节流，但数据层不该撒谎）。 */
    @Test
    fun `进度变化即不等`() {
        val atOne = sample(null)
        val atTwo = CarNowPlaying(
            title = atOne.title,
            artist = atOne.artist,
            album = atOne.album,
            durationMillis = atOne.durationMillis,
            positionMillis = 2_000L,
            isPlaying = atOne.isPlaying,
            artworkBytes = null,
        )
        assertNotEquals(atOne, atTwo)
    }

    @Test
    fun `有歌名或歌手就算有内容`() {
        assertTrue(sample(null).hasContent)
        assertTrue(
            CarNowPlaying("", "只有歌手", "", 0, 0, false, null).hasContent,
        )
        // 歌名与歌手都空 ⇒ 不算有内容：上游在会话刚建立时会先发一条占位元数据，
        // 把它当"有一首歌但没有名字"会让车机卡片出现一张空卡片。
        assertFalse(CarNowPlaying("", "", "", 0, 0, false, null).hasContent)
        assertFalse(CarNowPlaying("   ", "", "", 0, 0, false, null).hasContent)
    }
}
