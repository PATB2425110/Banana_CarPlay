package com.dbtool.carplay.bridge

import com.shilapi.xcertplay.power.PhonePowerState
import com.shilapi.xcertplay.power.PhoneCommunicationsState
import org.junit.Assert.*
import org.junit.Test

class PhoneStatusProjectionTest {
    @Test fun adapterRetainsRequestedFieldsAndKeepsPowerSeparateFromRadio() {
        val projected = PhonePowerState(level = 47, chargingState = 1,
            maxCurrentDrawnMa = 500, willChargeIfPowerPresent = true,
            externalChargerConnected = false, communications = PhoneCommunicationsState(
                signalStrength = 3, registrationStatus = 4, airplaneMode = false,
                carrierName = "中国移动", microphoneMuted = true, currentCallCount = 1,
            )).toPhoneStatus()
        assertEquals(47, projected.level)
        assertEquals(1, projected.chargingState)
        assertEquals(500, projected.maxCurrentDrawnMa)
        assertEquals(true, projected.willChargeIfPowerPresent)
        assertEquals(false, projected.externalChargerConnected)
        val radio = requireNotNull(projected.communications)
        assertEquals(3, radio.signalStrength)
        assertEquals(4, radio.registrationStatus)
        assertEquals("中国移动", radio.carrierName)
        assertEquals(true, radio.microphoneMuted)
        assertEquals(1, radio.currentCallCount)
    }
}
