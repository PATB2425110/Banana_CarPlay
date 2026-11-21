package com.shilapi.xcertplay.orchestration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ReuseClientSettle] 的 JVM 单测（注入假时钟/假睡眠，不真 sleep）。
 */
class ReuseClientSettleTest {

    @Test
    fun `自建热点不等待原厂客户端读数`() {
        val c = FakeClock()
        val waited = ReuseClientSettle.waitForSettle(
            initialClientCount = null,
            poll = { error("自建热点不得查询原厂 AP 的客户端") },
            sleep = { error("自建 AP 已就绪不得再睡眠") },
            now = c::now,
            clientSearchMillis = ReuseClientSettle.bootstrapSearchBudget(selfHosted = true),
        )
        assertEquals(0L, waited)
    }

    @Test
    fun `复用首次无客户端 2秒后先发送入网凭据`() {
        val c = FakeClock()
        val waited = ReuseClientSettle.waitForSettle(
            initialClientCount = 0,
            poll = { 0 },
            sleep = c::sleep,
            now = c::now,
            clientSearchMillis = ReuseClientSettle.bootstrapSearchBudget(selfHosted = false),
        )
        assertEquals(2_000L, waited)
    }

    @Test
    fun `快速复用也从客户端出现时保留完整2秒稳定时间`() {
        val c = FakeClock()
        val waited = ReuseClientSettle.waitForSettle(
            initialClientCount = 0,
            poll = { if (c.t >= 1_500L) 1 else 0 },
            sleep = c::sleep,
            now = c::now,
            clientSearchMillis = ReuseClientSettle.bootstrapSearchBudget(selfHosted = false),
        )
        assertEquals(3_500L, waited)
    }

    @Test
    fun `快速复用读数不可用不冒充已入网也不白等10秒`() {
        val c = FakeClock()
        val waited = ReuseClientSettle.waitForSettle(
            initialClientCount = null,
            poll = { null },
            sleep = c::sleep,
            now = c::now,
            clientSearchMillis = ReuseClientSettle.bootstrapSearchBudget(selfHosted = false),
        )
        assertEquals(2_000L, waited)
    }

    /** 假时钟 + 假睡眠：sleep 只推进虚拟时间。 */
    private class FakeClock {
        var t = 0L
        val sleeps = mutableListOf<Long>()
        fun now() = t
        fun sleep(ms: Long) {
            sleeps += ms
            t += ms
        }
    }

    @Test
    fun `一进来就有客户端 只等 2s`() {
        val c = FakeClock()
        val waited = ReuseClientSettle.waitForSettle(
            initialClientCount = 1,
            poll = { error("不应轮询") },
            sleep = c::sleep,
            now = c::now,
        )
        assertEquals(2_000L, waited)
        assertEquals(listOf(2_000L), c.sleeps)
    }

    @Test
    fun `客户端在轮询中出现 从发现时刻起补 2s`() {
        val c = FakeClock()
        var calls = 0
        val waited = ReuseClientSettle.waitForSettle(
            initialClientCount = 0,
            poll = { calls++; if (c.t >= 1_500L) 1 else 0 },
            sleep = c::sleep,
            now = c::now,
        )
        // 1.5s 左右发现（500/1000/1500 三次轮询），再补 2s → 约 3.5s
        assertTrue("实际等待 $waited 应 >= 3500", waited >= 3_500L)
        assertTrue("实际等待 $waited 应 <= 4000", waited <= 4_000L)
        assertTrue(calls >= 3)
    }

    @Test
    fun `客户端一直不出现 等满硬上限 10s`() {
        val c = FakeClock()
        val waited = ReuseClientSettle.waitForSettle(
            initialClientCount = 0,
            poll = { 0 },
            sleep = c::sleep,
            now = c::now,
        )
        assertEquals(10_000L, waited)
    }

    @Test
    fun `poll 抛异常按读不到处理 不崩`() {
        val c = FakeClock()
        val waited = ReuseClientSettle.waitForSettle(
            initialClientCount = null,
            poll = { error("boom") },
            sleep = c::sleep,
            now = c::now,
        )
        assertEquals(10_000L, waited)
    }

    @Test
    fun `initial 为 null 时不当成有客户端`() {
        val c = FakeClock()
        val waited = ReuseClientSettle.waitForSettle(
            initialClientCount = null,
            poll = { 0 },
            sleep = c::sleep,
            now = c::now,
        )
        assertEquals("null 不能走 2s 快路径", 10_000L, waited)
    }

    @Test
    fun `任何路径都不超过硬上限`() {
        listOf(0, 1, -1).forEach { init ->
            val c = FakeClock()
            val w = ReuseClientSettle.waitForSettle(
                initialClientCount = init,
                poll = { if (c.t > 50_000L) 1 else 0 },
                sleep = c::sleep,
                now = c::now,
            )
            assertTrue("initial=$init 等待 $w 超上限", w <= ReuseClientSettle.HARD_CAP_MILLIS)
        }
    }
}
