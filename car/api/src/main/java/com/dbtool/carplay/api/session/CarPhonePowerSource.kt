package com.dbtool.carplay.api.session

data class CarPhoneCommunications(
    val signalStrength: Int? = null, val registrationStatus: Int? = null,
    val airplaneMode: Boolean? = null, val carrierName: String? = null,
    val microphoneMuted: Boolean? = null, val currentCallCount: Int? = null,
)

data class CarPhonePower(
    val level: Int? = null, val chargingState: Int? = null,
    val maxCurrentDrawnMa: Int? = null, val willChargeIfPowerPresent: Boolean? = null,
    val externalChargerConnected: Boolean? = null, val communications: CarPhoneCommunications? = null,
)

/** Phone power/communications state; immediate snapshot followed by incremental changes.
 * No Android host battery/radio fallback. Charging 0=disabled, 1=charging, 2=charged.
 */
interface CarPhonePowerSource {
    fun current(): CarPhonePower?
    fun observe(listener: (CarPhonePower?) -> Unit): () -> Unit
}
