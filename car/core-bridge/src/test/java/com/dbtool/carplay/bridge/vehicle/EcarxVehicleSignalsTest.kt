package com.dbtool.carplay.bridge.vehicle

import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EcarxVehicleSignalsTest {

    @Test
    fun `全部读成功时四个字段都有值`() {
        val source = FakePropertySource(
            values = mutableMapOf(
                EcarxVehicleSignals.SIGNAL_VEH_SPEED to 72,
                EcarxVehicleSignals.SIGNAL_SPEED_UNIT to 0,
                EcarxVehicleSignals.SIGNAL_ENGINE_SPEED_SAFE to 1_850,
                EcarxVehicleSignals.SIGNAL_ENGINE_SPEED to 999,
                EcarxVehicleSignals.SIGNAL_GEAR to 4,
                EcarxVehicleSignals.SIGNAL_OUTSIDE_TEMP to 23,
            ),
        )
        val signals = EcarxVehicleSignals(propertySource = source, clock = { 1_234L })

        val snapshot = signals.snapshot()

        assertEquals(72, snapshot.speedKph)
        assertEquals(1_850, snapshot.rpm)
        assertEquals("D", snapshot.gear)
        assertEquals(23, snapshot.outsideTempC)
        assertEquals(1_234L, snapshot.atElapsedMs)
        assertTrue(snapshot.hasAny)
    }

    @Test
    fun `读车速抛异常只影响车速`() {
        val source = FakePropertySource(
            values = mutableMapOf(
                EcarxVehicleSignals.SIGNAL_VEH_SPEED to 72,
                EcarxVehicleSignals.SIGNAL_SPEED_UNIT to 0,
                EcarxVehicleSignals.SIGNAL_ENGINE_SPEED_SAFE to 1_850,
                EcarxVehicleSignals.SIGNAL_ENGINE_SPEED to 999,
                EcarxVehicleSignals.SIGNAL_GEAR to 3,
                EcarxVehicleSignals.SIGNAL_OUTSIDE_TEMP to 23,
            ),
            failingIds = mutableSetOf(EcarxVehicleSignals.SIGNAL_VEH_SPEED),
        )
        val signals = EcarxVehicleSignals(propertySource = source)

        val snapshot = signals.snapshot()

        assertNull(snapshot.speedKph)
        assertEquals(1_850, snapshot.rpm)
        assertEquals("N", snapshot.gear)
        assertEquals(23, snapshot.outsideTempC)
    }

    @Test
    fun `安全转速为负值时回落显示转速`() {
        val source = FakePropertySource(
            values = mutableMapOf(
                EcarxVehicleSignals.SIGNAL_ENGINE_SPEED_SAFE to -1,
                EcarxVehicleSignals.SIGNAL_ENGINE_SPEED to 1_200,
            ),
        )
        val signals = EcarxVehicleSignals(propertySource = source)

        assertEquals(1_200, signals.snapshot().rpm)
    }

    @Test
    fun `安全转速有效时优先于显示转速`() {
        val source = FakePropertySource(
            values = mutableMapOf(
                EcarxVehicleSignals.SIGNAL_ENGINE_SPEED_SAFE to 2_600,
                EcarxVehicleSignals.SIGNAL_ENGINE_SPEED to 999,
            ),
        )
        val signals = EcarxVehicleSignals(propertySource = source)

        assertEquals(2_600, signals.snapshot().rpm)
    }

    @Test
    fun `全部抛异常时返回空快照`() {
        val source = FakePropertySource(failAll = true)
        val signals = EcarxVehicleSignals(propertySource = source)

        val snapshot = signals.snapshot()

        assertNull(snapshot.speedKph)
        assertNull(snapshot.rpm)
        assertNull(snapshot.gear)
        assertNull(snapshot.outsideTempC)
        assertFalse(snapshot.hasAny)
    }

    @Test
    fun `单位为 mph 时车速换算成公里每小时并四舍五入`() {
        val source = FakePropertySource(
            values = mutableMapOf(
                EcarxVehicleSignals.SIGNAL_VEH_SPEED to 60,
                EcarxVehicleSignals.SIGNAL_SPEED_UNIT to EcarxVehicleSignals.SPEED_UNIT_MPH,
            ),
        )
        val signals = EcarxVehicleSignals(propertySource = source)

        assertEquals(97, signals.snapshot().speedKph)
    }

    @Test
    fun `档位码映射为字母且未知码返回 null`() {
        val known = EcarxVehicleSignals(
            propertySource = FakePropertySource(
                values = mutableMapOf(
                    EcarxVehicleSignals.SIGNAL_GEAR to 5,
                    EcarxVehicleSignals.SIGNAL_OUTSIDE_TEMP to 18,
                ),
            ),
        )
        val unknown = EcarxVehicleSignals(
            propertySource = FakePropertySource(
                values = mutableMapOf(
                    EcarxVehicleSignals.SIGNAL_GEAR to 99,
                    EcarxVehicleSignals.SIGNAL_OUTSIDE_TEMP to 18,
                ),
            ),
        )

        assertEquals("S", known.snapshot().gear)
        assertNull(unknown.snapshot().gear)
    }

    @Test
    fun `连续失败后三十秒内不再访问属性源`() {
        var now = 0L
        val source = FakePropertySource(failAll = true)
        val signals = EcarxVehicleSignals(propertySource = source, clock = { now })

        signals.snapshot()
        val callsAfterFirstFailure = source.calls.size
        // 2026-10-24：转速改为「先读 31612，读不到/负值回落 31534」，
        // 失败路径下会多访问一次属性源，故 5 -> 6。
        assertEquals(6, callsAfterFirstFailure)

        now = EcarxVehicleSignals.FAILURE_BACKOFF_MILLIS - 1
        assertFalse(signals.snapshot().hasAny)
        assertEquals(callsAfterFirstFailure, source.calls.size)

        now = EcarxVehicleSignals.FAILURE_BACKOFF_MILLIS
        signals.snapshot()
        assertEquals(callsAfterFirstFailure + 6, source.calls.size)
    }

    @Test
    fun `成功后失败计数清零`() {
        var now = 0L
        val source = FakePropertySource(failAll = true)
        val signals = EcarxVehicleSignals(propertySource = source, clock = { now })

        signals.snapshot()
        assertEquals(1, signals.failureCount)

        now = EcarxVehicleSignals.FAILURE_BACKOFF_MILLIS
        source.failAll = false
        source.values[EcarxVehicleSignals.SIGNAL_OUTSIDE_TEMP] = 20
        signals.snapshot()
        assertEquals(0, signals.failureCount)

        now += 1
        source.failAll = true
        signals.snapshot()
        assertEquals(1, signals.failureCount)
    }

    @Test
    fun `类不可用时 isAvailable 返回 false 且不抛`() {
        val unavailable = EcarxVehicleSignals(
            propertySource = FakePropertySource(available = false),
            clock = { 0L },
        )
        assertFalse(unavailable.isAvailable())

        // 本机 JVM 没有车机服务：默认反射源也必须走降级而不是抛异常。
        val nonCarMachine = EcarxVehicleSignals(clock = { 0L })
        assertFalse(nonCarMachine.isAvailable())
    }

    private class FakePropertySource(
        val values: MutableMap<Int, Int> = mutableMapOf(),
        private val failingIds: MutableSet<Int> = mutableSetOf(),
        var failAll: Boolean = false,
        private var available: Boolean = true,
    ) : PropertySource {
        val calls = CopyOnWriteArrayList<Int>()

        override fun int(id: Int): Int? {
            calls += id
            if (failAll || id in failingIds) error("fake signal failure")
            return values[id]
        }

        override fun isAvailable(): Boolean = available
    }
}
