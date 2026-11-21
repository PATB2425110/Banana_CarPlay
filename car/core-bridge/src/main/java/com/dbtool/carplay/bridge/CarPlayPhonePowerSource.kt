package com.dbtool.carplay.bridge

import com.dbtool.carplay.api.session.CarPhonePowerSource
import com.dbtool.carplay.api.session.CarPhonePower
import com.dbtool.carplay.api.session.CarPhoneCommunications
import com.shilapi.xcertplay.power.PhonePowerState
import com.shilapi.xcertplay.power.CarPlayPhonePowerBridge

internal object CarPlayPhonePowerSource : CarPhonePowerSource {
    override fun current(): CarPhonePower? =
        CarPlayPhonePowerBridge.current()?.toPhoneStatus()
    override fun observe(listener: (CarPhonePower?) -> Unit): () -> Unit =
        CarPlayPhonePowerBridge.observe { state ->
            listener(state?.toPhoneStatus())
        }
}

internal fun PhonePowerState.toPhoneStatus() = CarPhonePower(
    level, chargingState, maxCurrentDrawnMa, willChargeIfPowerPresent, externalChargerConnected,
    communications?.let {
        CarPhoneCommunications(it.signalStrength, it.registrationStatus, it.airplaneMode,
            it.carrierName, it.microphoneMuted, it.currentCallCount)
    },
)
