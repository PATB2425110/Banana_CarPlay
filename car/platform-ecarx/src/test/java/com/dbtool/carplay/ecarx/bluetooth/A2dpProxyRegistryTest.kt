package com.dbtool.carplay.ecarx.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * [A2dpProxyRegistry] 生命周期 / 并发 / 异常恢复（2026-10-25）。
 *
 * 现场：1.2.53 诊断包里 A2DP 代理 10.5 分钟内被 `getProfileProxy` /
 * `closeProfileProxy` 成对创建销毁 **209 次**。
 *
 * 独立复审（Claude）用并发探针抓出首版两个 P0，本套用例逐条锁定修复：
 * - P0-1 `disconnectA2dp` 绕过 registry（在 guard 侧修，见生产代码注释）；
 * - P0-2 close 后仍能 acquire、迟到 invalidate 误清新代理。
 */
class A2dpProxyRegistryTest {

    private class FakeProxy(val id: Int)

    private fun registry(
        made: AtomicInteger = AtomicInteger(0),
        released: AtomicInteger = AtomicInteger(0),
        failFirst: Int = 0,
    ) = A2dpProxyRegistry<FakeProxy>(
        obtain = {
            if (made.get() < failFirst) {
                made.incrementAndGet()
                null
            } else {
                FakeProxy(made.incrementAndGet())
            }
        },
        release = { released.incrementAndGet() },
    )

    @Test
    fun `正常会话期间只建立一次`() {
        val made = AtomicInteger(0)
        val reg = registry(made)
        reg.open()
        val first = reg.acquire()
        assertNotNull(first)
        repeat(200) { assertSame(first, reg.acquire()) }
        assertEquals("只应建立 1 次，实际 ${made.get()}", 1, made.get())
        assertEquals(1, reg.acquireCount)
        assertTrue(reg.isHeld())
    }

    @Test
    fun `未 open 时 acquire 返回 null`() {
        val reg = registry()
        assertNull("新实例默认处于未开启态", reg.acquire())
        reg.open()
        assertNotNull(reg.acquire())
    }

    @Test
    fun `系统回收后能可靠重建`() {
        val made = AtomicInteger(0)
        val reg = registry(made)
        reg.open()
        val a = reg.acquire()!!
        reg.invalidate(a)
        assertFalse(reg.isHeld())
        val b = reg.acquire()
        assertNotNull(b)
        assertTrue("重建后应是新实例", a !== b)
        assertEquals(2, made.get())
    }

    @Test
    fun `迟到 invalidate 不能误伤新代理`() {
        // P0-2 探针 [2] 复现的行为：旧代理的迟到 onServiceDisconnected
        // 曾把新建立的代理清掉且不释放，造成"缓存空 + 绑定挂着"。
        // N2 之后 invalidate 命中身份时会释放该实例（不再泄漏）。
        val made = AtomicInteger(0)
        val released = AtomicInteger(0)
        val reg = registry(made, released)
        reg.open()
        val old = reg.acquire()!!
        reg.invalidate(old)                 // 旧代理：命中 → 摘下 + 释放
        assertEquals(1, released.get())
        val fresh = reg.acquire()!!
        assertTrue(old !== fresh)
        reg.invalidate(old)                 // 迟到回调：身份不匹配 → 什么都不做
        assertTrue("新代理必须仍在缓存", reg.isHeld())
        assertSame(fresh, reg.acquire())
        assertEquals(2, made.get())
        assertEquals("迟到回调不得释放新代理", 1, released.get())
    }

    @Test
    fun `obtain 阻塞期间 close 再 open - 迟到实例自行释放`() {
        // P0-2 的核心机制（epoch）此前零覆盖：obtain() 卡住时 close()，
        // 迟到建成的实例必须自释、不进缓存，且 close 后 open 能正常重建。
        val made = AtomicInteger(0)
        val released = AtomicInteger(0)
        val gate = CountDownLatch(1)
        val reg = A2dpProxyRegistry<FakeProxy>(
            obtain = {
                made.incrementAndGet()
                gate.await(3, TimeUnit.SECONDS)
                FakeProxy(made.get())
            },
            release = { released.incrementAndGet() },
        )
        reg.open()
        val pool = Executors.newSingleThreadExecutor()
        val fut = pool.submit<FakeProxy?> { reg.acquire() }
        Thread.sleep(100)                   // 确保已进入 obtain
        reg.close()                          // 建立期间 close
        reg.open()                           // 模拟 RECONNECT 立刻重开
        gate.countDown()
        val late = fut.get(5, TimeUnit.SECONDS)
        pool.shutdownNow()
        assertNull("迟到实例不应返回给调用方", late)
        assertFalse("迟到实例不应进缓存", reg.isHeld())
        assertEquals("迟到实例必须被自行释放", 1, released.get())
        assertEquals("不应计入 acquireCount", 0, reg.acquireCount)
        // 重开后仍能正常建立
        assertNotNull("close+open 后应可重建", reg.acquire())
        assertEquals(2, made.get())
    }

    @Test
    fun `invalidate 命中身份时释放代理`() {
        // N2：只清缓存不 close，旧绑定会永久挂着；若 ROM 拒绝重复 connect
        // 还会让 getProfileProxy 一直 false ⇒ 整会话失明。
        val released = AtomicInteger(0)
        val reg = registry(released = released)
        reg.open()
        val p = reg.acquire()!!
        reg.invalidate(p)
        assertEquals("命中身份必须释放", 1, released.get())
        assertFalse(reg.isHeld())
        // 不匹配的实例不触发释放
        reg.acquire()
        val stranger = FakeProxy(999)
        reg.invalidate(stranger)
        assertEquals("身份不匹配不应释放", 1, released.get())
        assertTrue(reg.isHeld())
    }

    @Test
    fun `close 后 acquire 返回 null 且不新建`() {
        // P0-2 探针 [1]：首版 close() 之后 acquire() 仍能建成并缓存，无人释放。
        val made = AtomicInteger(0)
        val reg = registry(made)
        reg.open()
        reg.acquire()
        reg.close()
        assertNull("关闭态不应再建立", reg.acquire())
        assertEquals("不应新增建立", 1, made.get())
        assertFalse(reg.isHeld())
        assertTrue(reg.isClosed())
    }

    @Test
    fun `open 之后可以重新建立`() {
        val made = AtomicInteger(0)
        val released = AtomicInteger(0)
        val reg = registry(made, released)
        reg.open()
        reg.acquire()
        reg.close()
        reg.open()
        assertNotNull(reg.acquire())
        assertEquals(2, made.get())
        assertEquals(1, released.get())
    }

    @Test
    fun `并发巡检不会重复建立`() {
        // obtaining 标志的语义：建立期间其它线程**跳过本轮**（返回 null），
        // 而不是各自 bind 一条。obtain 用 latch 卡住，确保 8 个线程真的都进来过。
        val made = AtomicInteger(0)
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val reg = A2dpProxyRegistry<FakeProxy>(
            obtain = {
                made.incrementAndGet()
                entered.countDown()
                gate.await(3, TimeUnit.SECONDS)
                FakeProxy(1)
            },
            release = { },
        )
        reg.open()
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val done = CountDownLatch(8)
        val got = java.util.Collections.synchronizedList(mutableListOf<FakeProxy>())
        repeat(8) {
            pool.execute {
                start.await()
                reg.acquire()?.let { synchronized(got) { got.add(it) } }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue("obtain 应被进入", entered.await(3, TimeUnit.SECONDS))
        Thread.sleep(150)   // 让其余线程都走到 obtaining 检查并返回
        gate.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        pool.shutdownNow()
        assertEquals("建立期间只允许 1 次 obtain", 1, made.get())
        assertEquals("建成后缓存的实例唯一", 1, reg.acquireCount)
        assertTrue("拿到实例的线程应共用同一对象", got.all { it === got[0] })
        // 建立完成后再来一次应命中缓存
        assertNotNull(reg.acquire())
        assertEquals("后续 acquire 不应再建立", 1, made.get())
    }

    @Test
    fun `建立失败返回 null 且不污染缓存`() {
        val made = AtomicInteger(0)
        val reg = registry(made, failFirst = 1)
        reg.open()
        assertNull(reg.acquire())
        assertEquals(0, reg.acquireCount)
        assertFalse(reg.isHeld())
        assertNotNull("失败后下一轮应能重试成功", reg.acquire())
        assertEquals(1, reg.acquireCount)
    }

    @Test
    fun `obtain 抛异常不污染缓存且可重试`() {
        var boom = true
        val reg = A2dpProxyRegistry<FakeProxy>(
            obtain = { if (boom) throw IllegalStateException("boom-obtain") else FakeProxy(1) },
            release = { },
        )
        reg.open()
        assertNull("obtain 异常应被吞掉并返回 null", reg.acquire())
        assertFalse(reg.isHeld())
        boom = false
        assertNotNull(reg.acquire())
    }

    @Test
    fun `close 幂等且不会双重释放`() {
        val released = AtomicInteger(0)
        val reg = registry(released = released)
        reg.open()
        reg.acquire()
        reg.close()
        assertEquals(1, released.get())
        reg.close()
        reg.close()
        assertEquals("重复 close 不应再释放", 1, released.get())
        assertEquals(1, reg.releaseCount)
    }

    @Test
    fun `release 抛异常不外抛且状态一致`() {
        // 首版把异常穿出 close()，而 close() 跑在 SessionEnded 的主线程上。
        val reg = A2dpProxyRegistry<FakeProxy>(
            obtain = { FakeProxy(1) },
            release = { throw IllegalStateException("boom-release") },
        )
        reg.open()
        reg.acquire()
        reg.close()          // 不应抛
        assertFalse(reg.isHeld())
        assertEquals("即使 release 失败也要记账", 1, reg.releaseCount)
        reg.open()
        assertNotNull("release 失败后仍可重建", reg.acquire())
    }

    @Test
    fun `巡检周期不得退回秒级`() {
        // 引用**生产常量本身**，不是抄一份数字；改小常量这条测试就会红。
        assertTrue(
            "A2DP 巡检周期必须 >= 15000ms，当前=${EcarxBluetoothAudioGuard.A2DP_POLL_INTERVAL_MILLIS}",
            EcarxBluetoothAudioGuard.A2DP_POLL_INTERVAL_MILLIS >= 15_000L,
        )
        assertTrue(
            "首次巡检延迟应 <= 2000ms，保证会话开始即有补刀能力",
            EcarxBluetoothAudioGuard.POLL_INITIAL_DELAY_MILLIS <= 2_000L,
        )
    }
}
