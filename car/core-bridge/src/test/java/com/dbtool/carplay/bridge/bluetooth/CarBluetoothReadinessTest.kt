package com.dbtool.carplay.bridge.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CarBluetoothReadiness] 的轮询语义。
 *
 * 现场（2026-10-20 / 1.1.32）：无线 CarPlay 第一次起栈报
 * `No bonded PHONE_SMART devices found; pair an iPhone and retry`，
 * 退出 App 重进则一次成功 —— 适配器 `STATE_ON` 已到但配对缓存还没加载。
 */
class CarBluetoothReadinessTest {

    @Test
    fun `适配器开且有配对设备立即就绪不等待`() {
        var now = 0L
        val r = CarBluetoothReadiness.awaitBondedPhone(
            timeoutMs = 6_000,
            pollMs = 300,
            nowMs = { now },
            sleepMs = { now += it },
            adapterOn = { true },
            bondedCount = { 2 },
        )
        assertTrue(r.ready)
        assertEquals(0L, r.waitedMs)
        assertFalse(r.timedOut)
    }

    @Test
    fun `配对表晚到 600ms 时等到就绪`() {
        var now = 0L
        var bonded = 0
        val r = CarBluetoothReadiness.awaitBondedPhone(
            timeoutMs = 6_000,
            pollMs = 300,
            nowMs = { now },
            sleepMs = { now += it; if (now >= 600) bonded = 1 },
            adapterOn = { true },
            bondedCount = { bonded },
        )
        assertTrue("应在 600ms 后判定就绪", r.ready)
        assertTrue("等待时长应 >= 600ms", r.waitedMs >= 600)
        assertFalse(r.timedOut)
    }

    @Test
    fun `适配器一直不开则超时返回且不抛`() {
        var now = 0L
        val r = CarBluetoothReadiness.awaitBondedPhone(
            timeoutMs = 1_000,
            pollMs = 250,
            nowMs = { now },
            sleepMs = { now += it },
            adapterOn = { false },
            bondedCount = { 0 },
        )
        assertFalse(r.ready)
        assertTrue(r.timedOut)
        assertFalse(r.adapterOn)
    }

    @Test
    fun `配对表一直为空则超时但不拦`() {
        var now = 0L
        val r = CarBluetoothReadiness.awaitBondedPhone(
            timeoutMs = 900,
            pollMs = 300,
            nowMs = { now },
            sleepMs = { now += it },
            adapterOn = { true },
            bondedCount = { 0 },
        )
        assertFalse(r.ready)
        assertTrue(r.timedOut)
        assertTrue(r.adapterOn)
        assertEquals(0, r.bondedCount)
    }

    @Test
    fun `timeout 为 0 时只探一次`() {
        var polls = 0
        val r = CarBluetoothReadiness.awaitBondedPhone(
            timeoutMs = 0,
            pollMs = 300,
            nowMs = { 0L },
            sleepMs = { error("不应休眠") },
            adapterOn = { polls++; true },
            bondedCount = { 0 },
        )
        assertEquals(1, polls)
        assertTrue(r.timedOut)
    }

    @Test
    fun `适配器读取抛异常时按不可用处理不崩`() {
        var now = 0L
        val r = CarBluetoothReadiness.awaitBondedPhone(
            timeoutMs = 300,
            pollMs = 150,
            nowMs = { now },
            sleepMs = { now += it },
            adapterOn = { error("SecurityException") },
            bondedCount = { error("SecurityException") },
        )
        assertFalse(r.ready)
        assertTrue(r.timedOut)
    }

    @Test
    fun `pollMs 小于 50 时被夹到 50ms 不忙等`() {
        var now = 0L
        val r = CarBluetoothReadiness.awaitBondedPhone(
            timeoutMs = 200,
            pollMs = 1,
            nowMs = { now },
            sleepMs = { now += it },
            adapterOn = { true },
            bondedCount = { 0 },
        )
        assertTrue(r.timedOut)
        assertTrue("等待时长应按 50ms 步进，实际 ${r.waitedMs}", r.waitedMs >= 150)
    }
}
