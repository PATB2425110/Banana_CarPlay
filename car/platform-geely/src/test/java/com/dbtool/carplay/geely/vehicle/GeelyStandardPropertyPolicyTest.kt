package com.dbtool.carplay.geely.vehicle

import com.dbtool.carplay.api.vehicle.CarVehicleStatusField
import org.junit.Assert.*
import org.junit.Test

class GeelyStandardPropertyPolicyTest {
    @Test fun `all six HAL mappings are exact captured receive-map pairs`() {
        val pairs = mapOf(
            GeelyVehicleSignal.FUEL_RANGE to 0x21407956,
            GeelyVehicleSignal.FUEL_RANGE_UNIT to 0x21407957,
            GeelyVehicleSignal.OUTSIDE_TEMPERATURE to 0x214077AA,
            GeelyVehicleSignal.OUTSIDE_TEMPERATURE_QUALITY to 0x214077AB,
            GeelyVehicleSignal.LOW_FUEL_WARNING to 0x2140792D,
            GeelyVehicleSignal.NIGHT_MODE to 0x2140779B,
        )
        for ((signal, id) in pairs) {
            assertEquals(id, GeelyStandardPropertyPolicy.halId(signal, signal.expectedId))
            assertNull(GeelyStandardPropertyPolicy.halId(signal, null))
            assertNull(GeelyStandardPropertyPolicy.halId(signal, signal.expectedId + 1))
        }
    }

    @Test fun `runtime night ID without captured HAL pair cannot be guessed`() {
        assertNull(GeelyStandardPropertyPolicy.halId(GeelyVehicleSignal.NIGHT_MODE, 30617))
        assertNull(
            GeelyStandardPropertyPolicy.select(GeelyVehicleSignal.NIGHT_MODE, 30617, config()),
        )
    }

    @Test fun `config requires read access Integer identity and known change mode`() {
        val good = config()
        assertNotNull(select(good))
        assertNotNull(select(good.copy(access = 3)))
        for (access in listOf(0, 2, -1, 5)) assertNull(select(good.copy(access = access)))
        assertNull(select(good.copy(propertyId = 31062)))
        assertNull(select(good.copy(propertyTypeName = "int")))
        assertNull(select(good.copy(propertyTypeName = "java.lang.Float")))
        assertNull(select(good.copy(changeMode = -1)))
        assertNull(select(good.copy(changeMode = 3)))
        assertNull(select(null))
    }

    @Test fun `global config uses area zero not the legacy area one`() {
        assertEquals(0, select(config())!!.areaId)
        assertEquals(0, select(config().copy(areaIds = listOf(0)))!!.areaId)
        assertNull(select(config().copy(areaIds = listOf(1))))
        assertNull(select(config().copy(areaIds = listOf(0, 1))))
        assertNull(select(config().copy(areaIds = listOf(0, 0))))
        assertNull(select(config().copy(isGlobal = false)))
        assertNull(select(config().copy(areaType = 2)))
    }

    @Test fun `zoned config must have exactly one explicit positive supported area`() {
        val zoned = config().copy(areaType = 2, isGlobal = false, areaIds = listOf(16))
        assertEquals(16, select(zoned)!!.areaId)
        assertNull(select(zoned.copy(areaIds = emptyList())))
        assertNull(select(zoned.copy(areaIds = listOf(0))))
        assertNull(select(zoned.copy(areaIds = listOf(-1))))
        assertNull(select(zoned.copy(areaIds = listOf(16, 32))))
        assertNull(select(zoned.copy(areaType = 99)))
    }

    @Test fun `full sample requires exact HAL header AVAILABLE and integer`() {
        val selection = select(config())!!
        val raw = raw()
        assertEquals(500, decode(selection, raw)!!.integer())
        assertNull(decode(selection, raw.copy(propertyId = 31062)))
        assertNull(decode(selection, raw.copy(areaId = 1)))
        for (status in listOf(-1, 1, 2, 3)) assertNull(decode(selection, raw.copy(status = status)))
        for (value in listOf(null, 500L, 500.0, "500", false)) {
            assertNull(decode(selection, raw.copy(value = value)))
        }
        assertNull(decode(selection, null))
    }

    @Test fun `ON_CHANGE hours-old timestamp is valid but preserves real HAL time`() {
        val now = 18_000_000_000_000L // five hours since boot
        val old = 1_000_000_000L
        val selection = select(config())!!
        val reading = GeelyStandardPropertyPolicy.reading(
            selection, raw().copy(timestampNanos = old), now, old,
        )!!
        val property = reading.property!!
        assertEquals(500, reading.integer())
        assertEquals(18_000_000L, property.receiptTimeMillis)
        assertEquals(old, property.standardOrigin!!.actualHalTimestampNanos)
        assertEquals(selection.halPropertyId, property.propertyId)
        assertEquals(0, property.areaId)
    }

    @Test fun `CONTINUOUS rejects old source sample even if just read`() {
        val selection = select(config().copy(changeMode = GeelyStandardPropertyPolicy.CONTINUOUS))!!
        val now = 10_000_000_000L
        assertNotNull(GeelyStandardPropertyPolicy.reading(
            selection, raw().copy(timestampNanos = 7_000_000_000L), now, null,
        ))
        assertNull(GeelyStandardPropertyPolicy.reading(
            selection, raw().copy(timestampNanos = 6_999_999_999L), now, null,
        ))
    }

    @Test fun `all change modes reject zero negative future and regressing HAL timestamps`() {
        for (mode in 0..2) {
            assertFalse(GeelyStandardPropertyPolicy.timestampValid(0, 100, null, mode))
            assertFalse(GeelyStandardPropertyPolicy.timestampValid(-1, 100, null, mode))
            assertFalse(GeelyStandardPropertyPolicy.timestampValid(101, 100, null, mode))
            assertFalse(GeelyStandardPropertyPolicy.timestampValid(99, 100, 100, mode))
            assertTrue(GeelyStandardPropertyPolicy.timestampValid(100, 100, 100, mode))
        }
        assertFalse(GeelyStandardPropertyPolicy.timestampValid(100, 100, null, 3))
    }

    @Test fun `STATIC units require positive timestamp no invented timestamp-zero exception`() {
        val signal = GeelyVehicleSignal.FUEL_RANGE_UNIT
        val cfg = config(signal).copy(changeMode = GeelyStandardPropertyPolicy.STATIC)
        val selection = GeelyStandardPropertyPolicy.select(signal, signal.expectedId, cfg)!!
        val raw = GeelyStandardRawProperty(cfg.propertyId, 0, 0, 0, 0)
        assertNull(GeelyStandardPropertyPolicy.reading(selection, raw, 10_000_000_000L, null))
        assertNotNull(GeelyStandardPropertyPolicy.reading(
            selection, raw.copy(timestampNanos = 1), 10_000_000_000L, null,
        ))
    }

    @Test fun `normalization retains provenance and refuses forged header or receipt time`() {
        val selection = select(config())!!
        val reading = decode(selection, raw())!!
        val property = reading.property!!
        assertEquals(31062, reading.signalId)
        assertEquals(0x21407956, property.propertyId)
        assertEquals(0, property.areaId)
        assertEquals(500, property.integerFor(31062))
        assertNull(property.integerFor(31063))
        assertNull(property.copy(propertyId = 31062).integerFor(31062))
        assertNull(property.copy(areaId = 1).integerFor(31062))
        assertNull(property.copy(receiptTimeMillis = property.receiptTimeMillis + 1).integerFor(31062))
        assertNull(property.copy(hasStatus = false).integerFor(31062))
        assertNull(property.copy(status = 2).integerFor(31062))
    }

    @Test fun `source freshness and local receipt TTL remain independent in mapped result`() {
        val signal = GeelyVehicleSignal.NIGHT_MODE
        val cfg = config(signal)
        val selection = GeelyStandardPropertyPolicy.select(signal, signal.expectedId, cfg)!!
        val oldOnChange = GeelyStandardPropertyPolicy.reading(
            selection, GeelyStandardRawProperty(cfg.propertyId, 0, 0, 1, 0),
            20_000_000_000L, null,
        )!!
        val mapped = GeelyVehicleStatusMapping.map(
            setOf(CarVehicleStatusField.NIGHT_MODE), mapOf(signal to oldOnChange),
        )
        assertEquals(true, mapped.snapshot.nightMode)
        assertEquals(20_000L, mapped.receiptTimeMillis[CarVehicleStatusField.NIGHT_MODE])
        assertFalse(mapped.continuousSampleExpiryMillis.containsKey(CarVehicleStatusField.NIGHT_MODE))
        val continuous = GeelyStandardPropertyPolicy.reading(
            selection.copy(config = cfg.copy(changeMode = GeelyStandardPropertyPolicy.CONTINUOUS)),
            GeelyStandardRawProperty(cfg.propertyId, 0, 0, 18_000_000_000L, 0),
            20_000_000_000L, null,
        )!!
        val mappedContinuous = GeelyVehicleStatusMapping.map(
            setOf(CarVehicleStatusField.NIGHT_MODE), mapOf(signal to continuous),
        )
        assertEquals(21_000L, mappedContinuous.continuousSampleExpiryMillis[CarVehicleStatusField.NIGHT_MODE])
    }

    private fun config(signal: GeelyVehicleSignal = GeelyVehicleSignal.FUEL_RANGE) =
        GeelyStandardPropertyConfig(
            GeelyStandardPropertyPolicy.halId(signal, signal.expectedId)!!,
            1, "java.lang.Integer", 0, true, emptyList(), GeelyStandardPropertyPolicy.ON_CHANGE,
        )

    private fun select(config: GeelyStandardPropertyConfig?) =
        GeelyStandardPropertyPolicy.select(GeelyVehicleSignal.FUEL_RANGE, 31062, config)

    private fun raw() = GeelyStandardRawProperty(0x21407956, 0, 0, 1_000_000_000L, 500)
    private fun decode(selection: GeelyStandardPropertySelection, raw: GeelyStandardRawProperty?) =
        GeelyStandardPropertyPolicy.reading(selection, raw, 2_000_000_000L, null)
}
