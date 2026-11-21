package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaAudioBufferTest {

    private val rate = 48_000
    private val stereo = 2
    private val minBuffer = 4_096

    private fun plan(isMedia: Boolean, millis: Int = MediaAudioBuffer.DEFAULT_MILLIS) =
        MediaAudioBuffer.plan(isMedia, rate, stereo, minBuffer, millis)

    @Test
    fun `非媒体流使用低延迟小缓冲`() {
        val low = plan(isMedia = false)
        assertEquals(maxOf(minBuffer * 4, 16 * 1024), low.trackBufferBytes)
        assertEquals(maxOf(minBuffer, 4 * 1024), low.startBytes)
    }

    @Test
    fun `媒体流按毫秒折算缓冲 48k 双声道`() {
        // 48000 * 2ch * 2B = 192000 B/s
        val p = plan(isMedia = true, millis = 500)
        assertEquals((192_000L * 500 / 1000).toInt(), p.startBytes)
        assertEquals((192_000L * 700 / 1000).toInt(), p.trackBufferBytes)
    }

    @Test
    fun `媒体缓冲始终不小于低延迟基线`() {
        val p = plan(isMedia = true, millis = 300)
        assertTrue(p.trackBufferBytes >= maxOf(minBuffer * 4, 16 * 1024))
        assertTrue(p.startBytes >= maxOf(minBuffer, 4 * 1024))
    }

    @Test
    fun `非法档位回落到默认 300 毫秒`() {
        assertEquals(300, MediaAudioBuffer.sanitize(0))
        assertEquals(300, MediaAudioBuffer.sanitize(123))
        assertEquals(300, MediaAudioBuffer.sanitize(-5))
        assertEquals(500, MediaAudioBuffer.sanitize(500))
        assertEquals(1000, MediaAudioBuffer.sanitize(1000))
    }

    @Test
    fun `多声道按双声道计 不申请过大缓冲`() {
        val six = MediaAudioBuffer.plan(true, rate, 6, minBuffer, 500)
        val two = MediaAudioBuffer.plan(true, rate, 2, minBuffer, 500)
        assertEquals(two, six)
    }

    @Test
    fun `车机只批小容量时 起点被压到容量之下`() {
        // 计划要 96000B 起点，真实只给 32768B，写块 4096B
        val start = MediaAudioBuffer.startBytesFor(96_000, 32_768, 4_096)
        assertEquals(32_768 - 4_096, start)
        assertTrue(start < 32_768)
    }

    @Test
    fun `容量未知时保留计划起点`() {
        assertEquals(96_000, MediaAudioBuffer.startBytesFor(96_000, 0, 4_096))
        assertEquals(96_000, MediaAudioBuffer.startBytesFor(96_000, -1, 4_096))
    }

    @Test
    fun `起点不会低于一个写入块`() {
        // 真实容量仅 2048B，写块 4096B → 至少给一个写块
        assertEquals(4_096, MediaAudioBuffer.startBytesFor(96_000, 2_048, 4_096))
    }

    @Test
    fun `只有 media 算媒体流`() {
        assertTrue(MediaAudioBuffer.isMediaType("media"))
        assertTrue(MediaAudioBuffer.isMediaType("Media"))
        assertFalse(MediaAudioBuffer.isMediaType("telephony"))
        assertFalse(MediaAudioBuffer.isMediaType("speechRecognition"))
        assertFalse(MediaAudioBuffer.isMediaType("navigation"))
        assertFalse(MediaAudioBuffer.isMediaType(""))
    }
}
