package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioBufferProgressTest {

    private val frameBytes = 4 // 16bit 双声道

    @Test
    fun `已写入减去已播即排队字节`() {
        val p = AudioBufferProgress(frameBytes)
        p.written(1000)
        p.queuedBytes(0) // 立基准：从这一刻起才谈得上「此后播了多少」
        // 播放头前进 100 帧 * 4B = 400B
        assertEquals(600L, p.queuedBytes(100))
    }

    @Test
    fun `播放头绕回不产生跳变`() {
        val p = AudioBufferProgress(frameBytes)
        p.written(1_000_000)
        p.queuedBytes(0xffff_ff00.toInt()) // 接近顶端
        // 播放头从 0xffffff00 前进 0x200 帧到 0x100（绕回）
        val queued = p.queuedBytes(0x100)
        assertEquals(1_000_000L - 0x200L * frameBytes, queued)
    }

    @Test
    fun `首次采样不计已播`() {
        // 这个对象可能在播放中途才被创建，那时播放头的绝对值（例如 0xffffff00）
        // 与「已经播了多少」毫无关系。首次采样若把它当成里程，queuedBytes 会
        // 直接算成 0，shouldRebuffer 便会把正常播放误判为断流。
        val p = AudioBufferProgress(frameBytes)
        p.written(1_000_000)
        assertEquals(1_000_000L, p.queuedBytes(0xffff_ff00.toInt()))
    }

    @Test
    fun `首次采样后仍能正常累计`() {
        val p = AudioBufferProgress(frameBytes)
        p.written(1_000_000)
        p.queuedBytes(0)          // 立基准
        assertEquals(1_000_000L - 100L * frameBytes, p.queuedBytes(100))
    }

    @Test
    fun `排队字节不为负`() {
        val p = AudioBufferProgress(frameBytes)
        p.written(10)
        p.queuedBytes(0) // 立基准
        // 播放头跳到接近顶端的 0xffffffff：已播里程远超写入量，结果必须钳到 0
        assertEquals(0L, p.queuedBytes(0xffff_ffff.toInt()))
    }

    @Test
    fun `五个条件全成立才需重建缓冲`() {
        val p = AudioBufferProgress(frameBytes)
        p.written(800)
        p.queuedBytes(0) // 立基准，令「已播」从 0 起算
        assertTrue(
            p.shouldRebuffer(
                isMedia = true, playing = true, underrunSinceStart = true,
                compressedQueueEmpty = true, rawHead = 200, // 800 - 200*4 = 0
            ),
        )
    }

    @Test
    fun `非媒体流永不重建缓冲`() {
        val p = AudioBufferProgress(frameBytes)
        p.written(800)
        p.queuedBytes(0)
        assertFalse(
            p.shouldRebuffer(false, true, true, true, 200),
        )
    }

    @Test
    fun `还没开播不重建`() {
        val p = AudioBufferProgress(frameBytes)
        p.written(800)
        p.queuedBytes(0)
        assertFalse(p.shouldRebuffer(true, playing = false, underrunSinceStart = true,
            compressedQueueEmpty = true, rawHead = 200))
    }

    @Test
    fun `底层队列非空说明数据在路上 不重建`() {
        val p = AudioBufferProgress(frameBytes)
        p.written(800)
        p.queuedBytes(0)
        assertFalse(p.shouldRebuffer(true, true, true, compressedQueueEmpty = false, rawHead = 200))
    }

    @Test
    fun `还有排队字节说明没排空 不重建`() {
        val p = AudioBufferProgress(frameBytes)
        p.written(800)
        p.queuedBytes(0)
        assertFalse(p.shouldRebuffer(true, true, true, true, rawHead = 100)) // 还剩 400B
    }

    @Test
    fun `复位后再起一轮不残留旧计数`() {
        val p = AudioBufferProgress(frameBytes)
        p.written(5000)
        p.queuedBytes(1000)
        p.reset()
        p.written(400)
        assertEquals(400L, p.queuedBytes(0))
    }
}
