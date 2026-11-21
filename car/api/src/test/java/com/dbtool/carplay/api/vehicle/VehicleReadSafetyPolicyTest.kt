package com.dbtool.carplay.api.vehicle

import org.junit.Assert.*
import org.junit.Test

class VehicleReadSafetyPolicyTest {
    private class Store(var value: VehicleReadSafetyState = VehicleReadSafetyState()) : VehicleReadSafetyStore {
        var fail = false
        var writes = 0
        override fun load() = value
        override fun save(state: VehicleReadSafetyState): Boolean {
            writes++
            if (fail) return false
            value = state
            return true
        }
    }

    @Test fun defaultOffCannotStartAReadingPeriod() {
        val store = Store()
        val policy = VehicleReadSafetyPolicy(store)
        assertFalse(policy.state().enabled)
        assertNull(policy.beginSession())
        assertEquals(0, store.writes)
    }

    @Test fun markerIsDurableBeforeFirstAccessAndOnlyLastLeaseClearsIt() {
        val store = Store()
        val policy = VehicleReadSafetyPolicy(store)
        assertTrue(policy.setEnabled(true))
        val a = policy.beginSession()!!
        assertTrue(store.value.interruptedSession)
        val b = policy.beginSession()!!
        assertEquals(2, store.writes) // Enable + arm. Not one write per sample/channel.
        policy.endSession(a)
        assertTrue(store.value.interruptedSession)
        policy.endSession(b)
        assertFalse(store.value.interruptedSession)
        assertTrue(store.value.enabled)
        assertEquals(3, store.writes)
        policy.endSession(b)
        assertEquals(3, store.writes)
    }

    @Test fun simulatedNativeDeathOrProcessKillLeavesMarkerAndNextStartupDisablesBeforeAccess() {
        val store = Store()
        val previous = VehicleReadSafetyPolicy(store)
        previous.setEnabled(true)
        previous.beginSession() // Deliberately no normal cleanup, not an actual process crash.
        val next = VehicleReadSafetyPolicy(store)
        assertFalse(next.state().enabled)
        assertFalse(store.value.enabled)
        assertNull(next.beginSession())
        assertTrue(next.state().disabledReason!!.contains("未正常结束"))
        assertTrue(next.state().disabledReason!!.contains("系统杀进程或断电"))
    }

    @Test fun cleanlyStoppedReaderDoesNotDisableOnNextLaunch() {
        val store = Store()
        val previous = VehicleReadSafetyPolicy(store)
        previous.setEnabled(true)
        previous.endSession(previous.beginSession()!!)
        assertTrue(VehicleReadSafetyPolicy(store).state().enabled)
    }

    @Test fun manuallyEnabledButNeverReadIsNotCalledAnInterruptedVehicleRead() {
        val store = Store()
        val previous = VehicleReadSafetyPolicy(store)
        previous.setEnabled(true)
        val next = VehicleReadSafetyPolicy(store)
        assertTrue(next.state().enabled)
        assertNull(next.state().disabledReason)
    }

    @Test fun caughtFaultDisablesAndCannotBeUndoneByClosingOldLease() {
        val store = Store()
        val policy = VehicleReadSafetyPolicy(store)
        policy.setEnabled(true)
        val token = policy.beginSession()!!
        policy.trip("车辆读取异常")
        assertFalse(policy.state().enabled)
        assertNull(policy.beginSession())
        policy.endSession(token)
        assertFalse(store.value.enabled)
        assertEquals("车辆读取异常", store.value.disabledReason)
        assertFalse(VehicleReadSafetyPolicy(store).state().enabled)
    }

    @Test fun reenablingRequiresExplicitUserActionAfterOldReadsHaveRetired() {
        val policy = VehicleReadSafetyPolicy(Store())
        policy.setEnabled(true)
        val token = policy.beginSession()!!
        policy.setEnabled(false)
        assertFalse(policy.setEnabled(true))
        policy.endSession(token)
        assertFalse(policy.state().enabled)
        assertTrue(policy.setEnabled(true))
        assertTrue(policy.state().enabled)
        assertNull(policy.state().disabledReason)
    }

    @Test fun persistenceFailurePreventsVendorSessionFromStarting() {
        val store = Store()
        val policy = VehicleReadSafetyPolicy(store)
        policy.setEnabled(true)
        store.fail = true
        assertNull(policy.beginSession())
        assertFalse(policy.state().enabled)
        assertFalse(store.value.interruptedSession)
    }

    @Test fun failedEnableIsNotAnEnabledRuntimeGate() {
        val store = Store().apply { fail = true }
        val policy = VehicleReadSafetyPolicy(store)
        assertFalse(policy.setEnabled(true))
        assertFalse(policy.state().enabled)
        assertNull(policy.beginSession())
    }

    @Test fun failedMarkerCleanupRemainsConservativelyDisabledAcrossRestart() {
        val store = Store()
        val policy = VehicleReadSafetyPolicy(store)
        policy.setEnabled(true)
        val token = policy.beginSession()!!
        store.fail = true
        policy.endSession(token)
        assertFalse(policy.state().enabled)
        assertTrue(store.value.interruptedSession)
        store.fail = false
        assertFalse(VehicleReadSafetyPolicy(store).state().enabled)
    }

    @Test fun observersSeeDisableButAnObserverFailureCannotStopOtherSafetyCallbacks() {
        val policy = VehicleReadSafetyPolicy(Store())
        var stopped = 0
        val good = policy.observe { if (!it.enabled) stopped++ }
        policy.observe { if (it.disabledReason != null) error("observer") }
        policy.setEnabled(true)
        policy.trip("fault")
        assertEquals(2, stopped)
        good.close()
        policy.setEnabled(false)
        assertEquals(2, stopped)
    }

    @Test fun recoveryOfManualOffPendingSessionDoesNotAccuseItOfCrashing() {
        val store = Store(VehicleReadSafetyState(enabled = false, interruptedSession = true))
        val policy = VehicleReadSafetyPolicy(store)
        assertFalse(policy.state().enabled)
        assertFalse(policy.state().interruptedSession)
        assertNull(policy.state().disabledReason)
    }

    @Test fun classifierOnlyAttributesOwnReaderThreadOrStackNotUnrelatedNavigationAudio() {
        assertTrue(VehicleReadCrashClassifier.isVehicleReadFailure("geely-vehicle-status", Error()))
        assertTrue(VehicleReadCrashClassifier.isVehicleReadFailure("geely-standard-car-sdk", Error()))
        val related = Error().apply {
            stackTrace = arrayOf(StackTraceElement("com.dbtool.carplay.geely.vehicle.Reader", "read", "Reader.kt", 1))
        }
        assertTrue(VehicleReadCrashClassifier.isVehicleReadFailure("main", RuntimeException(related)))
        val unrelated = Error().apply {
            stackTrace = arrayOf(StackTraceElement("com.shilapi.xcertplay.airplay.Audio", "play", "Audio.kt", 1))
        }
        assertFalse(VehicleReadCrashClassifier.isVehicleReadFailure("main", unrelated))
    }
}
