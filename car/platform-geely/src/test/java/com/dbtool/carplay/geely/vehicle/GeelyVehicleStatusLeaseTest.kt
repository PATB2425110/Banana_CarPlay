package com.dbtool.carplay.geely.vehicle

import com.dbtool.carplay.api.vehicle.CarVehicleStatusField
import com.dbtool.carplay.api.vehicle.CarVehicleStatusSnapshot
import org.junit.Assert.*
import org.junit.Test

class GeelyVehicleStatusLeaseTest {
    private class Worker : GeelyVehicleWorker {
        data class Work(val delay: Long, val run: () -> Unit)
        val tasks = mutableListOf<Work>()
        var closes = 0
        var cleanup: (() -> Unit)? = null
        override fun schedule(delayMillis: Long, task: () -> Unit) {
            tasks += Work(delayMillis, task)
        }
        override fun close(cleanup: () -> Unit) {
            closes++
            this.cleanup = cleanup
        }
        fun next() = tasks.removeAt(0).run()
    }

    private class Client : GeelyVehiclePropertyClient {
        lateinit var callbacks: GeelyVehicleClientCallbacks
        var values = emptyMap<GeelyVehicleSignal, GeelyVehicleReading>()
        var beforeReturn: () -> Unit = {}
        var failure: Throwable? = null
        var reads = 0
        var closes = 0
        var lastSignals = emptySet<GeelyVehicleSignal>()
        override fun read(signals: Set<GeelyVehicleSignal>): Map<GeelyVehicleSignal, GeelyVehicleReading> {
            reads++
            lastSignals = signals.toSet()
            beforeReturn()
            failure?.let { throw it }
            return values
        }
        override fun close() { closes++ }
    }

    private class Fixture(fields: Set<CarVehicleStatusField>) {
        val worker = Worker()
        val client = Client()
        var now = 0L
        var factoryCalls = 0
        val logs = mutableListOf<String>()
        val lease = GeelyVehicleStatusLease(
            fields,
            { callbacks -> factoryCalls++; client.callbacks = callbacks; client },
            worker, { now }, { logs += it },
        )
    }

    @Test fun `open immediately queues work and latest never constructs or invokes OEM client`() {
        val f = Fixture(setOf(CarVehicleStatusField.NIGHT_MODE))
        assertEquals(0L, f.worker.tasks.single().delay)
        repeat(10) { assertEquals(CarVehicleStatusSnapshot(), f.lease.latest()) }
        assertEquals(0, f.factoryCalls)
        assertEquals(0, f.client.reads)
        f.client.values = mapOf(GeelyVehicleSignal.NIGHT_MODE to reading(GeelyVehicleSignal.NIGHT_MODE, 0))
        f.worker.next()
        assertEquals(true, f.lease.latest().nightMode)
        assertEquals(setOf(GeelyVehicleSignal.NIGHT_MODE), f.client.lastSignals)
        assertEquals(1000L, f.worker.tasks.single().delay)
        repeat(10) { f.lease.latest() }
        assertEquals(1, f.client.reads)
    }

    @Test fun `empty selection does not create client or schedule polling`() {
        val f = Fixture(emptySet())
        assertTrue(f.worker.tasks.isEmpty())
        assertEquals(0, f.factoryCalls)
        assertEquals(CarVehicleStatusSnapshot(), f.lease.latest())
        f.lease.close()
        f.worker.cleanup!!.invoke()
        assertEquals(0, f.client.closes)
    }

    @Test fun `close clears cache immediately idempotently and cleanup is worker-owned`() {
        val f = Fixture(setOf(CarVehicleStatusField.NIGHT_MODE))
        f.client.values = mapOf(GeelyVehicleSignal.NIGHT_MODE to reading(GeelyVehicleSignal.NIGHT_MODE, 0))
        f.worker.next()
        assertEquals(true, f.lease.latest().nightMode)
        f.lease.close()
        f.lease.close()
        assertEquals(CarVehicleStatusSnapshot(), f.lease.latest())
        assertEquals(1, f.worker.closes)
        assertEquals(0, f.client.closes)
        f.worker.cleanup!!.invoke()
        assertEquals(1, f.client.closes)
    }

    @Test fun `late running read cannot publish after close and does not schedule again`() {
        val f = Fixture(setOf(CarVehicleStatusField.NIGHT_MODE))
        f.client.values = mapOf(GeelyVehicleSignal.NIGHT_MODE to reading(GeelyVehicleSignal.NIGHT_MODE, 0))
        f.client.beforeReturn = { f.lease.close() }
        f.worker.next()
        assertEquals(CarVehicleStatusSnapshot(), f.lease.latest())
        assertTrue(f.worker.tasks.isEmpty())
    }

    @Test fun `queued read retired before start never touches OEM APIs`() {
        val f = Fixture(setOf(CarVehicleStatusField.NIGHT_MODE))
        f.lease.close()
        f.worker.next() // Deliberately run an already-cancelled task.
        assertEquals(0, f.factoryCalls)
        assertEquals(0, f.client.reads)
        assertEquals(CarVehicleStatusSnapshot(), f.lease.latest())
    }

    @Test fun `disconnect generation rejects late sample and next generation may recover`() {
        val f = Fixture(setOf(CarVehicleStatusField.NIGHT_MODE))
        f.client.values = mapOf(GeelyVehicleSignal.NIGHT_MODE to reading(GeelyVehicleSignal.NIGHT_MODE, 0))
        f.worker.next()
        f.client.beforeReturn = { f.client.callbacks.invalidate() }
        f.worker.next()
        assertNull(f.lease.latest().nightMode)
        f.client.beforeReturn = {}
        f.worker.next()
        assertEquals(true, f.lease.latest().nightMode)
    }

    @Test fun `TTL expires a successful snapshot even if OEM poll is stuck`() {
        val f = Fixture(setOf(CarVehicleStatusField.NIGHT_MODE))
        f.client.values = mapOf(GeelyVehicleSignal.NIGHT_MODE to reading(GeelyVehicleSignal.NIGHT_MODE, 0))
        f.worker.next()
        f.now = GeelyVehicleStatusLease.SAMPLE_TTL_MILLIS
        assertEquals(true, f.lease.latest().nightMode)
        f.now++
        assertNull(f.lease.latest().nightMode)
        assertEquals(1, f.client.reads)
        f.now = -1 // A non-monotonic test clock must not resurrect a future sample.
        assertNull(f.lease.latest().nightMode)
    }

    @Test fun `missing field clears it immediately while other fields stay independent`() {
        val f = Fixture(setOf(CarVehicleStatusField.NIGHT_MODE, CarVehicleStatusField.LOW_FUEL_WARNING))
        f.client.values = mapOf(
            GeelyVehicleSignal.NIGHT_MODE to reading(GeelyVehicleSignal.NIGHT_MODE, 0),
            GeelyVehicleSignal.LOW_FUEL_WARNING to reading(GeelyVehicleSignal.LOW_FUEL_WARNING, 1),
        )
        f.worker.next()
        assertEquals(true, f.lease.latest().lowFuelWarning)
        f.client.values = mapOf(GeelyVehicleSignal.NIGHT_MODE to reading(GeelyVehicleSignal.NIGHT_MODE, 1))
        f.worker.next()
        assertNull(f.lease.latest().lowFuelWarning)
        assertEquals(false, f.lease.latest().nightMode)
    }

    @Test fun `observed field failure clears it before a later IPC returns`() {
        val f = Fixture(setOf(CarVehicleStatusField.NIGHT_MODE, CarVehicleStatusField.LOW_FUEL_WARNING))
        f.client.values = mapOf(
            GeelyVehicleSignal.NIGHT_MODE to reading(GeelyVehicleSignal.NIGHT_MODE, 0),
            GeelyVehicleSignal.LOW_FUEL_WARNING to reading(GeelyVehicleSignal.LOW_FUEL_WARNING, 1),
        )
        f.worker.next()
        f.client.values = mapOf(GeelyVehicleSignal.NIGHT_MODE to reading(GeelyVehicleSignal.NIGHT_MODE, 0))
        f.client.beforeReturn = {
            f.client.callbacks.invalidateField(CarVehicleStatusField.LOW_FUEL_WARNING)
            assertNull(f.lease.latest().lowFuelWarning)
            assertEquals(true, f.lease.latest().nightMode)
        }
        f.worker.next()
        assertNull(f.lease.latest().lowFuelWarning)
    }

    @Test fun `read exceptions do not escape or retain last good status or spam logs`() {
        val f = Fixture(setOf(CarVehicleStatusField.NIGHT_MODE))
        f.client.values = mapOf(GeelyVehicleSignal.NIGHT_MODE to reading(GeelyVehicleSignal.NIGHT_MODE, 0))
        f.worker.next()
        f.client.failure = SecurityException("denied")
        repeat(10) { f.worker.next() }
        assertNull(f.lease.latest().nightMode)
        assertEquals(1, f.logs.size)
    }

    @Test fun `older lease close never stops newer lease`() {
        val old = Fixture(setOf(CarVehicleStatusField.NIGHT_MODE))
        val current = Fixture(setOf(CarVehicleStatusField.NIGHT_MODE))
        old.client.values = mapOf(GeelyVehicleSignal.NIGHT_MODE to reading(GeelyVehicleSignal.NIGHT_MODE, 0))
        current.client.values = old.client.values
        old.worker.next()
        current.worker.next()
        old.lease.close()
        old.worker.cleanup!!.invoke()
        assertEquals(1, old.client.closes)
        assertEquals(0, current.client.closes)
        assertEquals(true, current.lease.latest().nightMode)
        current.worker.next()
        assertEquals(2, current.client.reads)
    }

    @Test fun `selection is copied and temperature polling includes only its quality dependency`() {
        val fields = mutableSetOf(CarVehicleStatusField.OUTSIDE_TEMPERATURE)
        val f = Fixture(fields)
        fields.clear()
        f.client.values = mapOf(
            GeelyVehicleSignal.OUTSIDE_TEMPERATURE to reading(GeelyVehicleSignal.OUTSIDE_TEMPERATURE, 950),
            GeelyVehicleSignal.OUTSIDE_TEMPERATURE_QUALITY to reading(GeelyVehicleSignal.OUTSIDE_TEMPERATURE_QUALITY, 3),
        )
        f.worker.next()
        assertEquals(25, f.lease.latest().outsideTemperatureC)
        assertEquals(
            setOf(GeelyVehicleSignal.OUTSIDE_TEMPERATURE, GeelyVehicleSignal.OUTSIDE_TEMPERATURE_QUALITY),
            f.client.lastSignals,
        )
        assertNull(f.lease.latest().fuelRangeKm)
    }

    @Test fun `old ON_CHANGE last event stays usable only while local read cache is current`() {
        val f = Fixture(setOf(CarVehicleStatusField.NIGHT_MODE))
        f.now = 20_000
        f.client.values = mapOf(
            GeelyVehicleSignal.NIGHT_MODE to standardNight(
                GeelyStandardPropertyPolicy.ON_CHANGE, halTimestampMillis = 1, receiptMillis = 20_000,
            ),
        )
        f.worker.next()
        assertEquals(true, f.lease.latest().nightMode)
        f.now = 23_000
        assertEquals(true, f.lease.latest().nightMode)
        f.now = 23_001
        assertNull(f.lease.latest().nightMode)
        // A later successful reread of the SAME last event refreshes only local receipt time.
        f.client.values = mapOf(
            GeelyVehicleSignal.NIGHT_MODE to standardNight(
                GeelyStandardPropertyPolicy.ON_CHANGE, halTimestampMillis = 1, receiptMillis = 23_001,
            ),
        )
        f.worker.next()
        assertEquals(true, f.lease.latest().nightMode)
        val origin = f.client.values[GeelyVehicleSignal.NIGHT_MODE]!!.property!!.standardOrigin!!
        assertEquals(1_000_000L, origin.actualHalTimestampNanos)
    }

    @Test fun `continuous source age can expire before local read cache TTL`() {
        val f = Fixture(setOf(CarVehicleStatusField.NIGHT_MODE))
        f.now = 20_000
        f.client.values = mapOf(
            GeelyVehicleSignal.NIGHT_MODE to standardNight(
                GeelyStandardPropertyPolicy.CONTINUOUS, halTimestampMillis = 18_000, receiptMillis = 20_000,
            ),
        )
        f.worker.next()
        assertEquals(true, f.lease.latest().nightMode)
        f.now = 21_000
        assertEquals(true, f.lease.latest().nightMode)
        f.now = 21_001 // Local receipt is only 1001ms old, physical source is already >3s old.
        assertNull(f.lease.latest().nightMode)
    }

    @Test fun `disconnect invalidates old ON_CHANGE immediately regardless of last local receipt`() {
        val f = Fixture(setOf(CarVehicleStatusField.NIGHT_MODE))
        f.now = 20_000
        f.client.values = mapOf(
            GeelyVehicleSignal.NIGHT_MODE to standardNight(
                GeelyStandardPropertyPolicy.ON_CHANGE, halTimestampMillis = 1, receiptMillis = 20_000,
            ),
        )
        f.worker.next()
        assertEquals(true, f.lease.latest().nightMode)
        f.client.callbacks.invalidate()
        assertNull(f.lease.latest().nightMode)
    }

    private fun standardNight(mode: Int, halTimestampMillis: Long, receiptMillis: Long): GeelyVehicleReading {
        val signal = GeelyVehicleSignal.NIGHT_MODE
        val halId = GeelyStandardPropertyPolicy.halId(signal, signal.expectedId)!!
        val config = GeelyStandardPropertyConfig(
            halId, 1, "java.lang.Integer", 0, true, emptyList(), mode,
        )
        val selection = GeelyStandardPropertyPolicy.select(signal, signal.expectedId, config)!!
        return GeelyStandardPropertyPolicy.reading(
            selection, GeelyStandardRawProperty(halId, 0, 0, halTimestampMillis * 1_000_000L, 0),
            receiptMillis * 1_000_000L, null,
        )!!
    }

    private fun reading(signal: GeelyVehicleSignal, value: Int) =
        GeelyVehicleReading(
            signal.expectedId,
            GeelyVehicleProperty(signal.expectedId, 1, value, 0, hasStatus = true, status = 0),
        )
}
