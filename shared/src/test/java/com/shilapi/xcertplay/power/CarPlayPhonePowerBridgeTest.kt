package com.shilapi.xcertplay.power

import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import org.junit.Assert.*
import org.junit.Test

class CarPlayPhonePowerBridgeTest {
    @Test fun powerSubscriptionRequestsCurrentChargePolicyChargerChargingAndLevel() {
        val frame = com.shilapi.xcertplay.iap2.message.Iap2ControlMessages.subscriptions()
            .single { it.messageId == 0xae00 }
        assertArrayEquals(byteArrayOf(0, 4, 0, 0, 0, 4, 0, 1,
            0, 4, 0, 4, 0, 4, 0, 5, 0, 4, 0, 6), frame.payload)
    }

    @Test fun chargingStateAndBatteryMergeInEitherOrderAndResetTogether() {
        val owner = Any()
        fun charging(n: Int) = Iap2Frame(0xae01, byteArrayOf(0, 5, 0, 5, n.toByte()))
        CarPlayPhonePowerBridge.attach(owner)
        try {
            CarPlayPhonePowerBridge.accept(owner, charging(1))
            assertEquals(PhonePowerState(null, 1), CarPlayPhonePowerBridge.current())
            CarPlayPhonePowerBridge.accept(owner, Iap2Frame(0xae01, body(47)))
            assertEquals(PhonePowerState(47, 1), CarPlayPhonePowerBridge.current())
            CarPlayPhonePowerBridge.accept(owner, charging(0))
            assertEquals(PhonePowerState(47, 0), CarPlayPhonePowerBridge.current())
            CarPlayPhonePowerBridge.accept(owner, charging(2))
            assertEquals(PhonePowerState(47, 2), CarPlayPhonePowerBridge.current())
            CarPlayPhonePowerBridge.accept(owner, charging(3))
            assertEquals(PhonePowerState(47, 2), CarPlayPhonePowerBridge.current())
            CarPlayPhonePowerBridge.attach(owner)
            assertNull(CarPlayPhonePowerBridge.current())
            CarPlayPhonePowerBridge.accept(owner, Iap2Frame(0xae01, body(48)))
            CarPlayPhonePowerBridge.accept(owner, charging(1))
            assertEquals(PhonePowerState(48, 1), CarPlayPhonePowerBridge.current())
        } finally { CarPlayPhonePowerBridge.detach(owner) }
    }

    @Test fun combinedFrameDecodesAndRejectsMalformedOrDuplicateChargingFields() {
        val charging = byteArrayOf(0, 5, 0, 5, 1)
        assertEquals(PhonePowerState(47, 1), decodePhonePowerUpdate(body(47) + charging))
        assertNull(decodePhonePowerUpdate(charging + charging))
        assertNull(decodePhonePowerUpdate(byteArrayOf(0, 6, 0, 5, 0, 1)))
    }
    private fun body(value: Int) = byteArrayOf(0, 6, 0, 6, (value shr 8).toByte(), value.toByte())
    @Test fun decodesLogged49Then48Then47AndValidBounds() {
        for (value in listOf(49, 48, 47, 0, 100)) {
            assertEquals(value, decodePhonePowerParameter6(body(value)))
        }
    }
    @Test fun doesNotUseOtherFlagsOrMalformedReadings() {
        assertNull(decodePhonePowerParameter6(byteArrayOf(0, 5, 0, 4, 0)))
        assertNull(decodePhonePowerParameter6(body(101)))
        assertNull(decodePhonePowerParameter6(body(65535)))
        assertNull(decodePhonePowerParameter6(body(47).dropLast(1).toByteArray()))
        assertNull(decodePhonePowerParameter6(body(47) + body(48)))
        assertNull(decodePhonePowerParameter6(body(47) + byteArrayOf(0)))
    }
    @Test fun newSessionAndDisconnectClearAndOldOwnerCannotOverwrite() {
        val old = Any()
        val live = Any()
        val events = mutableListOf<PhonePowerState?>()
        CarPlayPhonePowerBridge.attach(old)
        val detach = CarPlayPhonePowerBridge.observe { events += it }
        try {
            CarPlayPhonePowerBridge.accept(old, Iap2Frame(0xae01, body(49)))
            assertEquals(49, CarPlayPhonePowerBridge.current()?.level)
            CarPlayPhonePowerBridge.attach(live)
            assertNull(CarPlayPhonePowerBridge.current())
            CarPlayPhonePowerBridge.accept(old, Iap2Frame(0xae01, body(48)))
            assertNull(CarPlayPhonePowerBridge.current())
            CarPlayPhonePowerBridge.accept(live, Iap2Frame(0xae01, body(47)))
            CarPlayPhonePowerBridge.detach(old)
            assertEquals(47, CarPlayPhonePowerBridge.current()?.level)
            CarPlayPhonePowerBridge.accept(live, Iap2Frame(0x5201, body(99)))
            CarPlayPhonePowerBridge.accept(live, Iap2Frame(0xae01, byteArrayOf(0, 5, 0, 5, 0)))
            assertEquals(47, CarPlayPhonePowerBridge.current()?.level)
            CarPlayPhonePowerBridge.detach(live)
            assertNull(CarPlayPhonePowerBridge.current())
            assertEquals(listOf(null, PhonePowerState(49), null, PhonePowerState(47),
                PhonePowerState(47, 0), null), events)
        } finally {
            detach()
            CarPlayPhonePowerBridge.detach(live)
            CarPlayPhonePowerBridge.detach(old)
        }
    }
}
