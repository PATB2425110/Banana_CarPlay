package com.dbtool.carplay.bridge.vehicle

import com.dbtool.carplay.api.vehicle.CarVehicleSignals
import com.dbtool.carplay.api.vehicle.CarVehicleSnapshot
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarVehicleSignalPollerTest {

    @Test
    fun `start 和 stop 幂等且重复 start 不会启动第二条采样流`() {
        val calls = AtomicInteger()
        val first = CountDownLatch(1)
        val poller = CarVehicleSignalPoller(
            source = source {
                calls.incrementAndGet()
                first.countDown()
            },
            onSample = {},
        )

        poller.start()
        poller.start()
        assertTrue(first.await(2, TimeUnit.SECONDS))
        Thread.sleep(260)
        assertTrue("重复 start 不应叠加采样任务", calls.get() <= 2)

        poller.stop()
        poller.stop()
        assertFalse(poller.isRunning)
    }

    @Test
    fun `stop 返回后不再回调`() {
        val calls = AtomicInteger()
        val first = CountDownLatch(1)
        val poller = CarVehicleSignalPoller(
            source = source {
                calls.incrementAndGet()
                first.countDown()
            },
            onSample = {},
        )

        poller.start()
        assertTrue(first.await(2, TimeUnit.SECONDS))
        poller.stop()
        val callsAtStop = calls.get()

        Thread.sleep(500)

        assertEquals(callsAtStop, calls.get())
    }

    @Test
    fun `采样间隔下限为二百毫秒`() {
        val samples = CountDownLatch(3)
        val poller = CarVehicleSignalPoller(
            source = source {},
            intervalMs = 1L,
            onSample = { samples.countDown() },
        )

        assertEquals(CarVehicleSignalPoller.MIN_INTERVAL_MILLIS, poller.effectiveIntervalMs)
        val startedAt = System.nanoTime()
        poller.start()
        assertTrue(samples.await(2, TimeUnit.SECONDS))
        poller.stop()
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

        // 三次回调之间至少有两个 200ms 周期。
        assertTrue("实际采样过快：${elapsedMs}ms", elapsedMs >= 350L)
    }

    private fun source(onSample: () -> Unit): CarVehicleSignals =
        object : CarVehicleSignals {
            override fun isAvailable(): Boolean = true

            override fun snapshot(): CarVehicleSnapshot {
                onSample()
                return CarVehicleSnapshot(atElapsedMs = System.nanoTime())
            }
        }
}
