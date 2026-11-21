package com.dbtool.carplay.geely.vehicle

import org.junit.Assert.*
import org.junit.Test

class GeelyVehiclePropertyClientsTest {
    private class Client : GeelyVehiclePropertyClient {
        var reads = mutableListOf<Set<GeelyVehicleSignal>>()
        var closes = 0
        var result = emptyMap<GeelyVehicleSignal, GeelyVehicleReading>()
        var closeFailure: Throwable? = null
        override fun read(signals: Set<GeelyVehicleSignal>): Map<GeelyVehicleSignal, GeelyVehicleReading> {
            reads += signals
            return result
        }
        override fun close() { closes++; closeFailure?.let { throw it } }
    }

    @Test fun `denied unavailable standard route never falls back legacy for three fields`() {
        val standard = Client()
        val legacy = Client()
        val client = GeelyVehiclePropertyClients(standard, legacy)
        val signals = setOf(
            GeelyVehicleSignal.FUEL_RANGE, GeelyVehicleSignal.FUEL_RANGE_UNIT,
            GeelyVehicleSignal.LOW_FUEL_WARNING, GeelyVehicleSignal.NIGHT_MODE,
        )
        assertTrue(client.read(signals).isEmpty())
        assertEquals(listOf(signals), standard.reads)
        assertTrue(legacy.reads.isEmpty())
    }

    @Test fun `legacy temperature fallback is dependency complete without standard quality mixing`() {
        val standard = Client()
        val legacy = Client()
        val signal = GeelyVehicleSignal.OUTSIDE_TEMPERATURE
        val quality = GeelyVehicleSignal.OUTSIDE_TEMPERATURE_QUALITY
        standard.result = mapOf(signal to reading(signal, 900))
        legacy.result = mapOf(quality to reading(quality, 3))
        val client = GeelyVehiclePropertyClients(standard, legacy)
        val readings = client.read(setOf(signal, quality))
        assertNull(readings[signal]) // Never combine standard temperature with legacy quality.
        assertNotNull(readings[quality])
        assertEquals(listOf(setOf(signal, quality)), legacy.reads)
    }

    @Test fun `valid standard temperature avoids legacy connection and close touches both own clients`() {
        val standard = Client()
        val legacy = Client()
        val signal = GeelyVehicleSignal.OUTSIDE_TEMPERATURE
        val quality = GeelyVehicleSignal.OUTSIDE_TEMPERATURE_QUALITY
        standard.result = mapOf(signal to reading(signal, 900), quality to reading(quality, 3))
        val client = GeelyVehiclePropertyClients(standard, legacy)
        assertEquals(standard.result, client.read(setOf(signal, quality)))
        assertTrue(legacy.reads.isEmpty())
        client.close()
        assertEquals(1, standard.closes)
        assertEquals(1, legacy.closes)
    }

    private fun reading(signal: GeelyVehicleSignal, raw: Int) =
        GeelyVehicleReading(signal.expectedId, GeelyVehicleProperty(signal.expectedId, 1, raw, 1))

    @Test fun cleanupFailureStillClosesBothClientsAndReachesOuterProtection() {
        val first = IllegalStateException("standard failed")
        val second = IllegalStateException("legacy failed")
        val standard = Client().apply { closeFailure = first }
        val legacy = Client().apply { closeFailure = second }
        try {
            GeelyVehiclePropertyClients(standard, legacy).close()
            fail("Outer protection must see cleanup failure")
        } catch (actual: IllegalStateException) {
            assertSame(first, actual)
            assertArrayEquals(arrayOf(second), actual.suppressed)
        }
        assertEquals(1, standard.closes)
        assertEquals(1, legacy.closes)
    }
}
