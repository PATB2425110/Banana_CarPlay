package com.dbtool.carplay.geely.vehicle

import com.dbtool.carplay.api.vehicle.CarVehicleStatusField
import org.junit.Assert.*
import org.junit.Test

class GeelyVehicleStatusMappingTest {
    @Test fun `fuel distance requires known unit and normalizes miles to km`() {
        assertEquals(500, GeelyVehicleStatusMapping.fuelRangeKm(500, 0))
        assertEquals(161, GeelyVehicleStatusMapping.fuelRangeKm(100, 1))
        assertEquals(0, GeelyVehicleStatusMapping.fuelRangeKm(0, 0))
        assertNull(GeelyVehicleStatusMapping.fuelRangeKm(200, null))
        assertNull(GeelyVehicleStatusMapping.fuelRangeKm(200, 2))
        assertNull(GeelyVehicleStatusMapping.fuelRangeKm(null, 0))
    }

    @Test fun `negative extreme and implausible fuel distance are unknown not clamped`() {
        for (raw in listOf(-1, Int.MIN_VALUE, 65535, Int.MAX_VALUE)) {
            assertNull(GeelyVehicleStatusMapping.fuelRangeKm(raw, 0))
        }
        assertEquals(10000, GeelyVehicleStatusMapping.fuelRangeKm(10000, 0))
        assertNull(GeelyVehicleStatusMapping.fuelRangeKm(10000, 1))
    }

    @Test fun `temperature uses exact SensorNew quality three not active by default`() {
        assertEquals(0, GeelyVehicleStatusMapping.outsideTemperatureC(700, 3))
        assertEquals(25, GeelyVehicleStatusMapping.outsideTemperatureC(950, 3))
        assertEquals(-50, GeelyVehicleStatusMapping.outsideTemperatureC(200, 3))
        assertEquals(-1, GeelyVehicleStatusMapping.outsideTemperatureC(694, 3))
        for (quality in listOf(null, -1, 0, 1, 2, 4, Int.MAX_VALUE)) {
            assertNull(GeelyVehicleStatusMapping.outsideTemperatureC(950, quality))
        }
        assertNull(GeelyVehicleStatusMapping.outsideTemperatureC(null, 3))
    }

    @Test fun `temperature boundaries reject negative and extreme raw data`() {
        assertEquals(-70, GeelyVehicleStatusMapping.outsideTemperatureC(0, 3))
        assertEquals(85, GeelyVehicleStatusMapping.outsideTemperatureC(1550, 3))
        for (raw in listOf(-1, Int.MIN_VALUE, 1551, 65535, Int.MAX_VALUE)) {
            assertNull(GeelyVehicleStatusMapping.outsideTemperatureC(raw, 3))
        }
    }

    @Test fun `invalid night and fuel warning never default to false or day`() {
        assertEquals(true, GeelyVehicleStatusMapping.nightMode(0))
        assertEquals(false, GeelyVehicleStatusMapping.nightMode(1))
        assertEquals(false, GeelyVehicleStatusMapping.lowFuelWarning(0))
        assertEquals(true, GeelyVehicleStatusMapping.lowFuelWarning(1))
        for (raw in listOf(null, -1, 2, 3, Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertNull(GeelyVehicleStatusMapping.nightMode(raw))
            assertNull(GeelyVehicleStatusMapping.lowFuelWarning(raw))
        }
    }

    @Test fun `field selection reads only dependencies and never electric range`() {
        assertEquals(emptySet<GeelyVehicleSignal>(), GeelyVehicleStatusMapping.signalsFor(emptySet()))
        assertEquals(
            setOf(GeelyVehicleSignal.FUEL_RANGE, GeelyVehicleSignal.FUEL_RANGE_UNIT),
            GeelyVehicleStatusMapping.signalsFor(setOf(CarVehicleStatusField.FUEL_RANGE)),
        )
        assertEquals(
            setOf(GeelyVehicleSignal.OUTSIDE_TEMPERATURE, GeelyVehicleSignal.OUTSIDE_TEMPERATURE_QUALITY),
            GeelyVehicleStatusMapping.signalsFor(setOf(CarVehicleStatusField.OUTSIDE_TEMPERATURE)),
        )
        assertEquals(
            setOf(GeelyVehicleSignal.NIGHT_MODE),
            GeelyVehicleStatusMapping.signalsFor(setOf(CarVehicleStatusField.NIGHT_MODE)),
        )
        assertEquals(
            setOf(GeelyVehicleSignal.LOW_FUEL_WARNING),
            GeelyVehicleStatusMapping.signalsFor(setOf(CarVehicleStatusField.LOW_FUEL_WARNING)),
        )
        assertEquals(6, GeelyVehicleStatusMapping.signalsFor(CarVehicleStatusField.entries.toSet()).size)
    }

    @Test fun `runtime ID must exist and conflicting non-night IDs are refused`() {
        assertEquals(30619, GeelyVehicleSignal.NIGHT_MODE.resolveRuntimeId(30619))
        // Another firmware's PUBLIC runtime constant may be honored; no fallback hardcode.
        assertEquals(30617, GeelyVehicleSignal.NIGHT_MODE.resolveRuntimeId(30617))
        assertNull(GeelyVehicleSignal.NIGHT_MODE.resolveRuntimeId(null))
        assertNull(GeelyVehicleSignal.NIGHT_MODE.resolveRuntimeId(0))
        assertNull(GeelyVehicleSignal.NIGHT_MODE.resolveRuntimeId(30619L))
        assertEquals(31062, GeelyVehicleSignal.FUEL_RANGE.resolveRuntimeId(31062))
        assertNull(GeelyVehicleSignal.FUEL_RANGE.resolveRuntimeId(31064))
        assertEquals(30635, GeelyVehicleSignal.OUTSIDE_TEMPERATURE_QUALITY.resolveRuntimeId(30635))
    }

    @Test fun `complete property checks identity area exact integer and optional status`() {
        val good = GeelyVehicleProperty(31062, 1, 0, 20)
        assertEquals(0, good.integerFor(31062))
        assertNull(good.copy(propertyId = 999).integerFor(31062))
        assertNull(good.copy(areaId = 0).integerFor(31062))
        assertNull(good.copy(value = null).integerFor(31062))
        assertNull(good.copy(value = 0L).integerFor(31062))
        assertNull(good.copy(value = 0.0).integerFor(31062))
        assertNull(good.copy(hasStatus = true, status = null).integerFor(31062))
        assertNull(good.copy(hasStatus = true, status = 1).integerFor(31062))
        assertNull(good.copy(hasStatus = true, status = 2).integerFor(31062))
        assertEquals(0, good.copy(hasStatus = true, status = 0).integerFor(31062))
    }

    @Test fun `missing property and missing temperature quality cannot synthesize zero`() {
        val fields = CarVehicleStatusField.entries.toSet()
        val readings = mapOf(
            GeelyVehicleSignal.FUEL_RANGE to GeelyVehicleReading(31062, null),
            GeelyVehicleSignal.FUEL_RANGE_UNIT to reading(GeelyVehicleSignal.FUEL_RANGE_UNIT, 0),
            GeelyVehicleSignal.OUTSIDE_TEMPERATURE to reading(GeelyVehicleSignal.OUTSIDE_TEMPERATURE, 700),
            GeelyVehicleSignal.NIGHT_MODE to GeelyVehicleReading(30619, null),
        )
        val result = GeelyVehicleStatusMapping.map(fields, readings)
        assertNull(result.snapshot.fuelRangeKm)
        assertNull(result.snapshot.outsideTemperatureC)
        assertNull(result.snapshot.lowFuelWarning)
        assertNull(result.snapshot.nightMode)
        assertTrue(result.receiptTimeMillis.isEmpty())
    }

    @Test fun `unrequested values cannot escape and freshness uses oldest dependency`() {
        val readings = mapOf(
            GeelyVehicleSignal.FUEL_RANGE to reading(GeelyVehicleSignal.FUEL_RANGE, 500, 200),
            GeelyVehicleSignal.FUEL_RANGE_UNIT to reading(GeelyVehicleSignal.FUEL_RANGE_UNIT, 0, 100),
            GeelyVehicleSignal.NIGHT_MODE to reading(GeelyVehicleSignal.NIGHT_MODE, 0, 250),
        )
        val result = GeelyVehicleStatusMapping.map(setOf(CarVehicleStatusField.FUEL_RANGE), readings)
        assertEquals(500, result.snapshot.fuelRangeKm)
        assertEquals(100L, result.receiptTimeMillis[CarVehicleStatusField.FUEL_RANGE])
        assertNull(result.snapshot.nightMode)
        assertFalse(result.receiptTimeMillis.containsKey(CarVehicleStatusField.NIGHT_MODE))
    }

    @Test fun `old ROM presence without availability cannot publish fuel warning or night`() {
        val readings = GeelyVehicleSignal.entries.associateWith { signal ->
            val value = when (signal) {
                GeelyVehicleSignal.FUEL_RANGE -> 500
                GeelyVehicleSignal.OUTSIDE_TEMPERATURE -> 950
                GeelyVehicleSignal.OUTSIDE_TEMPERATURE_QUALITY -> 3
                else -> 0
            }
            GeelyVehicleReading(
                signal.expectedId,
                GeelyVehicleProperty(signal.expectedId, 1, value, 0),
            )
        }
        val result = GeelyVehicleStatusMapping.map(CarVehicleStatusField.entries.toSet(), readings)
        assertNull(result.snapshot.fuelRangeKm)
        assertNull(result.snapshot.lowFuelWarning)
        assertNull(result.snapshot.nightMode)
        assertEquals(25, result.snapshot.outsideTemperatureC)
    }

    private fun reading(signal: GeelyVehicleSignal, value: Int, at: Long = 0) =
        GeelyVehicleReading(
            signal.expectedId,
            GeelyVehicleProperty(signal.expectedId, 1, value, at, hasStatus = true, status = 0),
        )
}
