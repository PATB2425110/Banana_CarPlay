package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `VideoWorkQueue` 的**入队时刻**契约（PATCH-0028）。
 *
 * ## 为什么要有这组测试
 *
 * 卡顿的现场证据是 `queued=8 maxOutputAgeMs=960` —— 帧在队列里积压近 1 秒。
 * 修法是"取出时按入队时刻判超龄"，而**这个能力完全依赖队列记下的时间戳**：
 * 一旦有人把 `enqueuedNs` 拿掉或改成取时刻，丢帧判定就会静默失效
 * （表现跟修复前一样：卡，但不报错）。所以这里把契约钉死。
 */
class VideoWorkQueueStampTest {

    private sealed interface Job {
        data class Frame(val id: Int) : Job
        data class Control(val id: Int) : Job
    }

    @Test
    fun frameIsStampedWithEnqueueTime() {
        val queue = VideoWorkQueue<Job>(capacity = 4)
        val before = System.nanoTime()
        queue.frame(Job.Frame(1))
        val after = System.nanoTime()

        val stamped = queue.pollStamped(timeoutMs = 100)
        assertNotNull("队列应有帧可取出", stamped)
        val frame = stamped!!.value as Job.Frame
        assertEquals(1, frame.id)
        assertTrue("帧项必须标记为 frame", stamped.frame)
        assertTrue(
            "入队时刻应落在入队前后的测量窗口内：${stamped.enqueuedNs} 不在 [$before, $after]",
            stamped.enqueuedNs in before..after,
        )
    }

    @Test
    fun controlIsNotStampedAsFrame() {
        val queue = VideoWorkQueue<Job>(capacity = 4)
        queue.control(Job.Control(7))

        val stamped = queue.pollStamped(timeoutMs = 100)
        assertNotNull(stamped)
        assertEquals(7, (stamped!!.value as Job.Control).id)
        assertFalse("控制项不得被当作帧项（否则会误判帧龄）", stamped.frame)
    }

    /** 旧 `poll()` 必须保持兼容：返回 value，不因新增 `pollStamped` 而改变行为。 */
    @Test
    fun legacyPollStillReturnsValue() {
        val queue = VideoWorkQueue<Job>(capacity = 4)
        queue.frame(Job.Frame(3))
        val value = queue.poll(timeoutMs = 100)
        assertEquals(3, (value as Job.Frame).id)
    }

    @Test
    fun pollReturnsNullWhenEmpty() {
        val queue = VideoWorkQueue<Job>(capacity = 4)
        assertNull(queue.pollStamped(timeoutMs = 10))
    }

    /**
     * 容量耗尽时 `frame()` 阻塞（TCP 背压）——这正是"队列积压"的物理成因。
     * 用一个满队列 + 短超时验证：取不到东西，而不是静默丢弃。
     */
    @Test
    fun capacityAppliesBackpressureInsteadOfDropping() {
        val queue = VideoWorkQueue<Job>(capacity = 2)
        repeat(2) { queue.frame(Job.Frame(it)) }
        // 第 3 帧会阻塞在 slots 上；这里不调用它（会挂住测试），
        // 只验证容量语义：取出 2 帧后 size 归零。
        assertEquals(2, queue.size)
        assertNotNull(queue.poll(timeoutMs = 50))
        assertNotNull(queue.poll(timeoutMs = 50))
        assertEquals(0, queue.size)
        assertNull(queue.poll(timeoutMs = 10))
    }
}
