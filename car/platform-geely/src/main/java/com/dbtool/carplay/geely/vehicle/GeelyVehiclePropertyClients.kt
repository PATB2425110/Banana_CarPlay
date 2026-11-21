package com.dbtool.carplay.geely.vehicle

import com.dbtool.carplay.api.vehicle.CarVehicleStatusField

/** No mixing a standard temperature with an unrelated legacy quality sample. */
internal class GeelyVehiclePropertyClients(
    private val standard: GeelyVehiclePropertyClient,
    private val legacy: GeelyVehiclePropertyClient,
) : GeelyVehiclePropertyClient {
    override fun read(signals: Set<GeelyVehicleSignal>): Map<GeelyVehicleSignal, GeelyVehicleReading> {
        val result = standard.read(signals).filterKeys { it in signals }.toMutableMap()
        val temperature = CarVehicleStatusField.OUTSIDE_TEMPERATURE
        val temperatureSignals = GeelyVehicleStatusMapping.signalsFor(setOf(temperature))
        if (signals.any { it in temperatureSignals } &&
            temperature !in GeelyVehicleStatusMapping.map(setOf(temperature), result).receiptTimeMillis
        ) {
            temperatureSignals.forEach(result::remove)
            result.putAll(legacy.read(temperatureSignals).filterKeys { it in temperatureSignals && it in signals })
        }
        // Never use the metadata-losing legacy route as a fallback for the other three fields.
        return result
    }

    override fun close() {
        val standardFailure = runCatching { standard.close() }.exceptionOrNull()
        val legacyFailure = runCatching { legacy.close() }.exceptionOrNull()
        if (standardFailure != null) {
            if (legacyFailure != null && legacyFailure !== standardFailure) {
                standardFailure.addSuppressed(legacyFailure)
            }
            throw standardFailure
        }
        if (legacyFailure != null) throw legacyFailure
    }
}
