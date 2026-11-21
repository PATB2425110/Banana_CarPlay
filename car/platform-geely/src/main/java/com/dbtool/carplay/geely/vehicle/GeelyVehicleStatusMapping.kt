package com.dbtool.carplay.geely.vehicle

import com.dbtool.carplay.api.vehicle.CarVehicleStatusField
import com.dbtool.carplay.api.vehicle.CarVehicleStatusSnapshot
import kotlin.math.roundToInt

/** Names/IDs are from the separately decoded OSN2.0 ecarx.car.jar, not APK-inlined old IDs. */
internal enum class GeelyVehicleSignal(val constantName: String, val expectedId: Int) {
    FUEL_RANGE("SignalId_DstToEmptyIndcdDstToEmpty", 31062),
    FUEL_RANGE_UNIT("SignalId_DstToEmptyIndcdDstUnit", 31063),
    OUTSIDE_TEMPERATURE("SignalId_AmbTRawAmbTVal", 30634),
    OUTSIDE_TEMPERATURE_QUALITY("SignalId_AmbTRawQly", 30635),
    LOW_FUEL_WARNING("SignalId_FuLvlLoWarn", 31021),
    NIGHT_MODE("SignalId_TwliBriSts", 30619);

    fun resolveRuntimeId(value: Any?): Int? {
        val id = value as? Int ?: return null
        // Night IDs differ between generations. Trust only the public constant on the loaded
        // CarSignalManager class; never fall back to an APK's inlined old number.
        return id.takeIf {
            if (this == NIGHT_MODE) it in 0x7000..0xffff else it == expectedId
        }
    }
}

/**
 * A non-null FULL property object is mandatory presence evidence. Bare OEM int getters lose
 * that evidence (their null-property fallback is zero), so they are deliberately not supported.
 * The supplied ROM's wrapper has id/area/value only. Presence is NOT availability: fields without
 * an independently verified quality signal require public AVAILABLE (0) status metadata, otherwise
 * they remain unknown. We do not invent HAL availability/timestamps for the old wrapper.
 */
internal data class GeelyVehicleProperty(
    val propertyId: Int,
    val areaId: Int,
    val value: Any?,
    /** Local successful read/validation time, NOT the HAL's last-event/sample timestamp. */
    val receiptTimeMillis: Long,
    val hasStatus: Boolean = false,
    val status: Int? = null,
    val standardOrigin: GeelyStandardPropertyOrigin? = null,
) {
    fun integerFor(expectedId: Int): Int? {
        val identityValid = standardOrigin?.let {
            hasStatus && status == 0 && receiptTimeMillis == it.validatedAtNanos / 1_000_000L &&
                GeelyStandardPropertyPolicy.originMatches(it, expectedId, propertyId, areaId)
        } ?: (propertyId == expectedId && areaId == 1)
        return (value as? Int)?.takeIf {
            identityValid && (!hasStatus || status == 0)
        }
    }
}

internal data class GeelyVehicleReading(val signalId: Int, val property: GeelyVehicleProperty?) {
    fun integer(requireAvailability: Boolean = true): Int? =
        property?.takeIf { !requireAvailability || it.hasStatus }?.integerFor(signalId)
}

internal data class GeelyMappedVehicleStatus(
    val snapshot: CarVehicleStatusSnapshot = CarVehicleStatusSnapshot(),
    val receiptTimeMillis: Map<CarVehicleStatusField, Long> = emptyMap(),
    val continuousSampleExpiryMillis: Map<CarVehicleStatusField, Long> = emptyMap(),
)

internal object GeelyVehicleStatusMapping {
    // Conservative sanity limits, not a claim about the vendor protocol's reserved codes.
    private const val MAX_FUEL_RANGE_KM = 10_000
    private const val MILES_TO_KM = 1.609344

    fun fieldFor(signal: GeelyVehicleSignal): CarVehicleStatusField = when (signal) {
        GeelyVehicleSignal.FUEL_RANGE, GeelyVehicleSignal.FUEL_RANGE_UNIT -> CarVehicleStatusField.FUEL_RANGE
        GeelyVehicleSignal.OUTSIDE_TEMPERATURE, GeelyVehicleSignal.OUTSIDE_TEMPERATURE_QUALITY ->
            CarVehicleStatusField.OUTSIDE_TEMPERATURE
        GeelyVehicleSignal.LOW_FUEL_WARNING -> CarVehicleStatusField.LOW_FUEL_WARNING
        GeelyVehicleSignal.NIGHT_MODE -> CarVehicleStatusField.NIGHT_MODE
    }

    fun signalsFor(fields: Set<CarVehicleStatusField>): Set<GeelyVehicleSignal> = buildSet {
        if (CarVehicleStatusField.FUEL_RANGE in fields) {
            add(GeelyVehicleSignal.FUEL_RANGE)
            add(GeelyVehicleSignal.FUEL_RANGE_UNIT)
        }
        if (CarVehicleStatusField.OUTSIDE_TEMPERATURE in fields) {
            add(GeelyVehicleSignal.OUTSIDE_TEMPERATURE)
            add(GeelyVehicleSignal.OUTSIDE_TEMPERATURE_QUALITY)
        }
        if (CarVehicleStatusField.LOW_FUEL_WARNING in fields) add(GeelyVehicleSignal.LOW_FUEL_WARNING)
        if (CarVehicleStatusField.NIGHT_MODE in fields) add(GeelyVehicleSignal.NIGHT_MODE)
    }

    fun fuelRangeKm(raw: Int?, unit: Int?): Int? {
        if (raw == null || raw !in 0..MAX_FUEL_RANGE_KM) return null
        val km = when (unit) {
            0 -> raw.toDouble() // DstUnit.km
            1 -> raw * MILES_TO_KM // DstUnit.miles
            else -> return null
        }
        return km.takeIf { it in 0.0..MAX_FUEL_RANGE_KM.toDouble() }?.roundToInt()
    }

    fun outsideTemperatureC(raw: Int?, quality: Int?): Int? {
        // SensorNew.lambda$buildSensorFunction$7: only AmbTRawQly == 3 is active.
        if (quality != 3 || raw == null || raw !in 0..1_550) return null
        return ((raw * 0.1) - 70.0).roundToInt()
    }

    fun lowFuelWarning(raw: Int?): Boolean? = when (raw) {
        0 -> false
        1 -> true
        else -> null
    }

    fun nightMode(raw: Int?): Boolean? = when (raw) {
        0 -> true // TwliBriSts1.Night, NOT physical low beam or realTheme/uiMode.
        1 -> false
        else -> null // Unlike SensorNew's invalid-to-DAY fallback, preserve unknown.
    }

    fun map(
        fields: Set<CarVehicleStatusField>,
        readings: Map<GeelyVehicleSignal, GeelyVehicleReading>,
    ): GeelyMappedVehicleStatus {
        val times = mutableMapOf<CarVehicleStatusField, Long>()
        val expiries = mutableMapOf<CarVehicleStatusField, Long>()
        fun value(signal: GeelyVehicleSignal, requireAvailability: Boolean = true): Int? =
            readings[signal]?.integer(requireAvailability)
        fun <T : Any> keep(
            field: CarVehicleStatusField,
            signals: List<GeelyVehicleSignal>,
            convert: () -> T?,
        ): T? {
            if (field !in fields) return null
            val result = convert() ?: return null
            // Temperature has its own verified quality=3. Other fields cannot rely on
            // old-ROM full-property presence alone: HAL availability was stripped from it.
            val requireAvailability = field != CarVehicleStatusField.OUTSIDE_TEMPERATURE
            val properties = signals.map {
                readings[it]?.takeIf { r -> r.integer(requireAvailability) != null }?.property
            }
            if (properties.any { it == null }) return null
            times[field] = properties.minOf { it!!.receiptTimeMillis }
            properties.mapNotNull { property ->
                property!!.standardOrigin?.takeIf {
                    it.selection.config.changeMode == GeelyStandardPropertyPolicy.CONTINUOUS
                }?.let {
                    // Preserve remaining physical-sample age across local caching.
                    it.actualHalTimestampNanos / 1_000_000L +
                        GeelyStandardPropertyPolicy.MAX_CONTINUOUS_SAMPLE_AGE_NANOS / 1_000_000L
                }
            }.minOrNull()?.let { expiries[field] = it }
            return result
        }
        val range = keep(
            CarVehicleStatusField.FUEL_RANGE,
            listOf(GeelyVehicleSignal.FUEL_RANGE, GeelyVehicleSignal.FUEL_RANGE_UNIT),
        ) { fuelRangeKm(value(GeelyVehicleSignal.FUEL_RANGE), value(GeelyVehicleSignal.FUEL_RANGE_UNIT)) }
        val temperature = keep(
            CarVehicleStatusField.OUTSIDE_TEMPERATURE,
            listOf(GeelyVehicleSignal.OUTSIDE_TEMPERATURE, GeelyVehicleSignal.OUTSIDE_TEMPERATURE_QUALITY),
        ) {
            outsideTemperatureC(
                value(GeelyVehicleSignal.OUTSIDE_TEMPERATURE, requireAvailability = false),
                value(GeelyVehicleSignal.OUTSIDE_TEMPERATURE_QUALITY, requireAvailability = false),
            )
        }
        val warning = keep(
            CarVehicleStatusField.LOW_FUEL_WARNING, listOf(GeelyVehicleSignal.LOW_FUEL_WARNING),
        ) { lowFuelWarning(value(GeelyVehicleSignal.LOW_FUEL_WARNING)) }
        val night = keep(
            CarVehicleStatusField.NIGHT_MODE, listOf(GeelyVehicleSignal.NIGHT_MODE),
        ) { nightMode(value(GeelyVehicleSignal.NIGHT_MODE)) }
        return GeelyMappedVehicleStatus(
            CarVehicleStatusSnapshot(range, temperature, warning, night), times.toMap(), expiries.toMap(),
        )
    }
}
