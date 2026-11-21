package com.dbtool.carplay.api.vehicle

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class GuardedVehicleStatusSourceTest {
    private fun protection(): VehicleReadSafetyPolicy = VehicleReadSafetyPolicy(object : VehicleReadSafetyStore {
        var value = VehicleReadSafetyState()
        override fun load() = value
        override fun save(state: VehicleReadSafetyState): Boolean { value = state; return true }
    })
    private class Source : CarVehicleStatusSource {
        var opens = 0
        var closes = 0
        var onOpen: () -> Unit = {}
        var onLatest: () -> Unit = {}
        var closeFailure: Throwable? = null
        override fun open(fields: Set<CarVehicleStatusField>): CarVehicleStatusSubscription {
            opens++
            onOpen()
            return object : CarVehicleStatusSubscription {
                override fun latest(): CarVehicleStatusSnapshot {
                    onLatest()
                    return CarVehicleStatusSnapshot(fuelRangeKm = 500)
                }
                override fun close() { closes++; closeFailure?.let { throw it } }
            }
        }
    }

    @Test fun switchOffDoesNotConstructDelegateLeaseOrPublishData() {
        val raw = Source()
        val source = GuardedVehicleStatusSource(raw, protection())
        assertFalse(source.enabled)
        assertEquals(CarVehicleStatusSnapshot(), source.open(setOf(CarVehicleStatusField.FUEL_RANGE)).latest())
        assertEquals(0, raw.opens)
    }

    @Test fun manualOffImmediatelyStopsAllReaderLeasesWithoutTouchingOtherFeatures() {
        val raw = Source()
        val protection = protection()
        val source = GuardedVehicleStatusSource(raw, protection)
        protection.setEnabled(true)
        val a = source.open(setOf(CarVehicleStatusField.FUEL_RANGE))
        val b = source.open(setOf(CarVehicleStatusField.NIGHT_MODE))
        assertEquals(500, a.latest().fuelRangeKm)
        protection.setEnabled(false)
        assertEquals(2, raw.closes)
        assertEquals(CarVehicleStatusSnapshot(), a.latest())
        assertEquals(CarVehicleStatusSnapshot(), b.latest())
        a.close()
        b.close()
        assertEquals(2, raw.closes)
    }

    @Test fun tripDuringOpenRetiresTheLateDelegateInsteadOfPublishingIt() {
        val raw = Source()
        val protection = protection()
        val source = GuardedVehicleStatusSource(raw, protection)
        protection.setEnabled(true)
        raw.onOpen = { protection.trip("fault") }
        val lease = source.open(setOf(CarVehicleStatusField.FUEL_RANGE))
        assertFalse(source.enabled)
        assertEquals(1, raw.closes)
        assertEquals(CarVehicleStatusSnapshot(), lease.latest())
    }

    @Test fun normalLeaseCloseCannotStopAnotherActiveChannel() {
        val raw = Source()
        val protection = protection()
        val source = GuardedVehicleStatusSource(raw, protection)
        protection.setEnabled(true)
        val a = source.open(setOf(CarVehicleStatusField.FUEL_RANGE))
        val b = source.open(setOf(CarVehicleStatusField.FUEL_RANGE))
        a.close()
        assertEquals(1, raw.closes)
        assertEquals(500, b.latest().fuelRangeKm)
        b.close()
        assertEquals(2, raw.closes)
        assertTrue(source.enabled)
    }

    @Test fun offOnDoesNotReopenOldCarPlaySessionButNewControllerCanRead() {
        val raw = Source()
        val protection = protection()
        protection.setEnabled(true)
        val source = GuardedVehicleStatusSource(raw, protection)
        val oldSession = source.forSession()
        val oldLease = oldSession.open(setOf(CarVehicleStatusField.FUEL_RANGE))
        protection.setEnabled(false)
        protection.setEnabled(true)
        assertFalse(oldSession.enabled)
        assertEquals(CarVehicleStatusSnapshot(), oldLease.latest())
        assertEquals(CarVehicleStatusSnapshot(), oldSession.open(setOf(CarVehicleStatusField.NIGHT_MODE)).latest())
        assertEquals(1, raw.opens)
        val newSession = source.forSession()
        assertTrue(newSession.enabled)
        val newLease = newSession.open(setOf(CarVehicleStatusField.FUEL_RANGE))
        assertEquals(500, newLease.latest().fuelRangeKm)
        assertEquals(2, raw.opens)
        newLease.close()
    }

    @Test fun snapshotFailureTripsAndNeverLeaksAnExceptionIntoControlLoop() {
        val raw = Source().apply { onLatest = { error("snapshot failed") } }
        val protection = protection()
        protection.setEnabled(true)
        val source = GuardedVehicleStatusSource(raw, protection)
        val lease = source.open(setOf(CarVehicleStatusField.FUEL_RANGE))
        assertEquals(CarVehicleStatusSnapshot(), lease.latest())
        assertFalse(source.enabled)
        assertEquals(1, raw.closes)
    }

    @Test fun delayedOldOffNotificationCannotCloseNewlyEnabledSession() {
        val policy = protection()
        policy.setEnabled(true)
        val notifications = mutableListOf<() -> Unit>()
        val delayed = object : CarVehicleReadProtection by policy {
            override fun observe(listener: (VehicleReadSafetyState) -> Unit): AutoCloseable =
                policy.observe { captured -> notifications += { listener(captured) } }
        }
        val raw = Source()
        val source = GuardedVehicleStatusSource(raw, delayed)
        val old = source.forSession().open(setOf(CarVehicleStatusField.FUEL_RANGE))
        policy.setEnabled(false)
        policy.setEnabled(true)
        val current = source.forSession().open(setOf(CarVehicleStatusField.FUEL_RANGE))
        notifications.reversed().forEach { it() } // Deliberately deliver stale off/initial events last.
        assertEquals(1, raw.closes)
        assertEquals(CarVehicleStatusSnapshot(), old.latest())
        assertEquals(500, current.latest().fuelRangeKm)
        current.close()
    }

    @Test fun oneCleanupFailureTripsButDoesNotStopOtherLeasesFromClosing() {
        val raw = Source().apply { closeFailure = IllegalStateException("cleanup failed") }
        val protection = protection()
        protection.setEnabled(true)
        val source = GuardedVehicleStatusSource(raw, protection)
        source.open(setOf(CarVehicleStatusField.FUEL_RANGE))
        source.open(setOf(CarVehicleStatusField.NIGHT_MODE))
        protection.setEnabled(false)
        assertEquals(2, raw.closes)
        assertFalse(source.enabled)
        assertTrue(protection.state().disabledReason!!.contains("清理异常"))
    }

    @Test(timeout = 5000) fun stopDoesNotWaitForDelegateSnapshotAndLateSnapshotCannotEscape() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val raw = Source().apply {
            onLatest = { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
        }
        val protection = protection()
        protection.setEnabled(true)
        val source = GuardedVehicleStatusSource(raw, protection)
        val lease = source.open(setOf(CarVehicleStatusField.FUEL_RANGE))
        val result = AtomicReference<CarVehicleStatusSnapshot>()
        val reading = Thread { result.set(lease.latest()) }
        reading.start()
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            protection.setEnabled(false)
            assertEquals(1, raw.closes) // Must finish while delegate.latest is still held.
        } finally {
            release.countDown()
            reading.join(2000)
        }
        assertFalse(reading.isAlive)
        assertEquals(CarVehicleStatusSnapshot(), result.get())
    }
}
