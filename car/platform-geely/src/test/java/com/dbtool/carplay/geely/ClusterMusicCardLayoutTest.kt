package com.dbtool.carplay.geely

import org.junit.Assert.*
import org.junit.Test

class ClusterMusicCardLayoutTest {
    private val measure: (String) -> Float = { it.codePointCount(0, it.length).toFloat() }

    @Test fun `music card length is exactly eighty percent without changing height`() {
        assertEquals(496f, ClusterMusicCardLayout.WIDTH, 0f)
        assertEquals(72f, ClusterMusicCardLayout.HEIGHT, 0f)
        assertEquals(460f, ClusterMusicCardLayout.TEXT_WIDTH, 0f)
    }

    @Test fun `short text stays unchanged and long text fits reduced width`() {
        assertEquals("歌词", musicCardEllipsize("歌词", 4f, measure))
        assertEquals("这是一…", musicCardEllipsize("这是一句很长的歌词", 4f, measure))
        assertEquals("", musicCardEllipsize("长歌词", 0.5f, measure))
        assertEquals("", musicCardEllipsize("长歌词", 0f, measure))
    }

    @Test fun `ellipsis never splits an emoji surrogate pair`() {
        val result = musicCardEllipsize("🎵🎶播放音乐", 3f, measure)
        assertEquals("🎵🎶…", result)
        assertEquals(3, result.codePointCount(0, result.length))
    }
}
