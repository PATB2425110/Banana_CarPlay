package com.shilapi.xcertplay.transport

import org.junit.Assert.*
import org.junit.Test

/** Policy tests adapted from DiPlay's UsbSetupDiagnosticsTest (GPL-3.0). */
class UsbConfigurationRecoveryTest {
    @Test fun successfulSelectionDoesNotReadDetachOrReopen() {
        assertNull(UsbConfigurationSelection.select(6, listOf(0, 1), { true },
            { error("must not read") }, { error("must not detach") }, { error("must not reopen") }))
    }

    @Test fun blockedSwitchDetachesUniqueInterfacesThenReopensBeforeRetry() {
        val events = mutableListOf<String>()
        var active = 2
        var attempts = 0
        val outcome = UsbConfigurationSelection.select(6, listOf(2, 0, 1, 0, 3), {
            events += "select"
            (++attempts == 2).also { if (it) active = 6 }
        }, { active }, { events += "detach:$it"; it != 3 }, { events += "reopen" })
        assertEquals(listOf("select", "detach:0", "detach:1", "detach:2", "detach:3", "reopen", "select"), events)
        assertEquals("USB configuration select target=6 ok=true before=2 detached=0,1,2 after=6",
            outcome!!.diagnostic())
        assertFalse(outcome.wrongConfiguration)
    }

    @Test fun alreadySelectedDoesNotDisturbDrivers() {
        val outcome = UsbConfigurationSelection.select(6, listOf(0, 3), { false }, { 6 },
            { error("must not detach") }, { error("must not reopen") })
        assertFalse(outcome!!.wrongConfiguration)
        assertTrue(outcome.detached.isEmpty())
        assertEquals(6, outcome.after)
    }

    @Test fun knownWrongConfigurationIsReportedAndNoClaimsMeansNoReopen() {
        var attempts = 0
        val outcome = UsbConfigurationSelection.select(6, listOf(0), { attempts++; false }, { 2 },
            { false }, { error("nothing to drop") })
        assertEquals(2, attempts)
        assertTrue(outcome!!.wrongConfiguration)
        assertEquals("USB configuration select target=6 ok=false before=2 detached=none after=2",
            outcome.diagnostic())
    }

    @Test fun unreadableConfigurationRetainsLegacyClaimFallback() {
        var reopens = 0
        val outcome = UsbConfigurationSelection.select(6, listOf(0), { false }, { null },
            { true }, { reopens++ })
        assertEquals(1, reopens)
        assertNull(outcome!!.after)
        assertFalse(outcome.wrongConfiguration)
    }

    @Test fun reopenFailureDoesNotAttemptSelectionOnClosedConnection() {
        var attempts = 0
        val failure = IllegalStateException("device detached")
        try {
            UsbConfigurationSelection.select(6, listOf(0), { attempts++; false }, { 2 },
                { true }, { throw failure })
            fail("expected reopen failure")
        } catch (error: IllegalStateException) {
            assertSame(failure, error)
        }
        assertEquals(1, attempts)
    }

    @Test fun ncmRetryStopsAtSuccessAndDoesNotSleepAfterLastAttempt() {
        val pauses = mutableListOf<Long>()
        var calls = 0
        assertEquals(3, NcmUsbBridge.retryUsbSetup(pause = { pauses += it }) { ++calls == 3 })
        assertEquals(listOf(100L, 100L), pauses)
        pauses.clear()
        calls = 0
        assertNull(NcmUsbBridge.retryUsbSetup(pause = { pauses += it }) { calls++; false })
        assertEquals(5, calls)
        assertEquals(List(4) { 100L }, pauses)
        assertEquals(1, NcmUsbBridge.retryUsbSetup(pause = { error("must not sleep") }) { true })
    }

    @Test fun retryDoesNotSwallowExceptionsOrContinueAfterInterruption() {
        var calls = 0
        try {
            NcmUsbBridge.retryUsbSetup(pause = { throw InterruptedException("cancelled") }) { calls++; false }
            fail("expected interruption")
        } catch (_: InterruptedException) {
            assertEquals(1, calls)
        }
        val failure = SecurityException("permission revoked")
        try {
            NcmUsbBridge.retryUsbSetup(pause = { error("must not sleep") }) { throw failure }
            fail("expected permission failure")
        } catch (error: SecurityException) {
            assertSame(failure, error)
        }
    }
}
