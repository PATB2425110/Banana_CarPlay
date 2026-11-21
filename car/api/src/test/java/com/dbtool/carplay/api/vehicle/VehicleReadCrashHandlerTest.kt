package com.dbtool.carplay.api.vehicle

import org.junit.Assert.*
import org.junit.Test

class VehicleReadCrashHandlerTest {
    private class Protection : CarVehicleReadProtection {
        var trips = 0
        var fail = false
        override fun state() = VehicleReadSafetyState()
        override fun beginSession(): Long? = null
        override fun endSession(token: Long) = Unit
        override fun observe(listener: (VehicleReadSafetyState) -> Unit) = AutoCloseable {}
        override fun trip(reason: String) { trips++; if (fail) error("storage failed") }
    }

    @Test fun readerCrashTripsBeforeExactlyOneDelegationOfOriginalThreadAndError() {
        val safety = Protection()
        val thread = Thread("geely-vehicle-status")
        val error = Error("test only; not an actual crash")
        var calls = 0
        val handler = VehicleReadCrashHandler(safety, Thread.UncaughtExceptionHandler { actualThread, actualError ->
            assertEquals(1, safety.trips)
            assertSame(thread, actualThread)
            assertSame(error, actualError)
            calls++
        })
        handler.uncaughtException(thread, error)
        assertEquals(1, calls)
    }

    @Test fun protectionFailureCannotSkipPriorCrashHandler() {
        val safety = Protection().apply { fail = true }
        var calls = 0
        VehicleReadCrashHandler(safety, Thread.UncaughtExceptionHandler { _, _ -> calls++ })
            .uncaughtException(Thread("geely-standard-car-sdk"), Error())
        assertEquals(1, safety.trips)
        assertEquals(1, calls)
    }

    @Test fun unrelatedCrashStillDelegatesWithoutAccusingReader() {
        val safety = Protection()
        var calls = 0
        val error = Error().apply {
            stackTrace = arrayOf(StackTraceElement("com.example.Audio", "play", "Audio.kt", 1))
        }
        VehicleReadCrashHandler(safety, Thread.UncaughtExceptionHandler { _, _ -> calls++ })
            .uncaughtException(Thread("main"), error)
        assertEquals(0, safety.trips)
        assertEquals(1, calls)
    }

    @Test fun absentPriorHandlerRethrowsOriginalErrorInsteadOfSwallowingIt() {
        val original = Error("test only")
        try {
            VehicleReadCrashHandler(Protection(), null).uncaughtException(Thread("geely-vehicle-status"), original)
            fail("Must preserve termination")
        } catch (actual: Error) {
            assertSame(original, actual)
        }
    }
}
