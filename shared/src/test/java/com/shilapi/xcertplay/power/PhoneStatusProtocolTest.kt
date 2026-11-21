package com.shilapi.xcertplay.power

import com.shilapi.xcertplay.iap2.message.Iap2ControlMessages
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import org.junit.Assert.*
import org.junit.Test

class PhoneStatusProtocolTest {
    private fun u16(n: Int) = byteArrayOf((n shr 8).toByte(), n.toByte())
    private fun tlv(id: Int, bytes: ByteArray) = u16(bytes.size + 4) + u16(id) + bytes
    private fun byte(id: Int, n: Int) = tlv(id, byteArrayOf(n.toByte()))

    @Test fun communicationsSubscriptionIncludesDefinedFieldsAndPreservesLegacyRequests() {
        val body = Iap2ControlMessages.subscriptions().single { it.messageId == 0x4157 }.payload
        val expected = listOf(0, 1, 2, 3, 4, 5, 8, 9).fold(byteArrayOf()) { bytes, id ->
            bytes + tlv(id, byteArrayOf())
        }
        assertArrayEquals(expected, body)
    }

    @Test fun powerDecodesFullSchemaAndDoesNotCallMaximumAnInstantMeasurement() {
        val state = decodePhonePowerUpdate(tlv(0, u16(500)) + byte(1, 1) +
            byte(4, 1) + byte(5, 1) + tlv(6, u16(47)))!!
        assertEquals(500, state.maxCurrentDrawnMa)
        assertEquals(true, state.willChargeIfPowerPresent)
        assertEquals(true, state.externalChargerConnected)
        assertEquals(1, state.chargingState)
        assertEquals(47, state.level)
        assertEquals(0, decodePhonePowerUpdate(tlv(0, u16(0)))!!.maxCurrentDrawnMa)
        assertEquals(65535, decodePhonePowerUpdate(tlv(0, u16(65535)))!!.maxCurrentDrawnMa)
    }

    @Test fun communicationsKeepsSignalRegistrationAirplaneCarrierMuteAndCalls() {
        val state = decodePhoneCommunicationsUpdate(byte(0, 4) + byte(1, 5) + byte(2, 0) +
            tlv(3, "中国移动\u0000".toByteArray()) + byte(8, 1) + byte(9, 2))!!
        assertEquals(4, state.signalStrength)
        assertEquals(5, state.registrationStatus)
        assertEquals(false, state.airplaneMode)
        assertEquals("中国移动", state.carrierName)
        assertEquals(true, state.microphoneMuted)
        assertEquals(2, state.currentCallCount)
    }

    @Test fun legacyNoSimTextIsNotCarrierAndDoesNotInvalidateActualSignal() {
        val state = decodePhoneCommunicationsUpdate(byte(0, 1) +
            tlv(4, "无SIM卡\u0000".toByteArray()) + byte(5, 1))!!
        assertEquals(1, state.signalStrength)
        assertNull(state.carrierName)
        assertNull(state.registrationStatus)
    }

    @Test fun bothFamiliesMergeZerosFalseAndSplitUpdatesWithoutCrossingIds() {
        val owner = Any()
        CarPlayPhonePowerBridge.attach(owner)
        try {
            fun accept(id: Int, bytes: ByteArray) = CarPlayPhonePowerBridge.accept(owner, Iap2Frame(id, bytes))
            accept(0xae01, tlv(0, u16(500)) + byte(5, 1) + tlv(6, u16(47)))
            accept(0x4158, byte(0, 5) + byte(2, 1))
            accept(0x4158, byte(0, 0) + byte(2, 0))
            accept(0xae01, tlv(0, u16(0)) + byte(1, 0) + byte(4, 0))
            val state = CarPlayPhonePowerBridge.current()!!
            assertEquals(47, state.level)
            assertEquals(1, state.chargingState)
            assertEquals(0, state.maxCurrentDrawnMa)
            assertEquals(false, state.willChargeIfPowerPresent)
            assertEquals(false, state.externalChargerConnected)
            assertEquals(0, state.communications!!.signalStrength)
            assertEquals(false, state.communications.airplaneMode)
            val next = Any()
            CarPlayPhonePowerBridge.attach(next)
            assertNull(CarPlayPhonePowerBridge.current())
            accept(0x4158, byte(0, 5))
            assertNull(CarPlayPhonePowerBridge.current())
            CarPlayPhonePowerBridge.detach(owner)
            assertNull(CarPlayPhonePowerBridge.current())
            CarPlayPhonePowerBridge.detach(next)
        } finally { CarPlayPhonePowerBridge.detach(owner) }
    }

    @Test fun invalidKnownWidthsEnumsDuplicatesOrTruncationRejectWholeDelta() {
        assertNull(decodePhonePowerUpdate(byte(0, 5)))
        assertNull(decodePhonePowerUpdate(byte(1, 2)))
        assertNull(decodePhonePowerUpdate(byte(4, 2)))
        assertNull(decodePhonePowerUpdate(tlv(0, u16(500)) + tlv(0, u16(1000))))
        assertNull(decodePhoneCommunicationsUpdate(byte(0, 6)))
        assertNull(decodePhoneCommunicationsUpdate(byte(1, 7)))
        assertNull(decodePhoneCommunicationsUpdate(byte(2, 2)))
        assertNull(decodePhoneCommunicationsUpdate(byte(8, 2)))
        assertNull(decodePhoneCommunicationsUpdate(tlv(9, u16(1))))
        assertNull(decodePhoneCommunicationsUpdate(byte(0, 1) + byte(0, 2)))
        assertNull(decodePhoneCommunicationsUpdate(byte(0, 1) + byteArrayOf(0)))
    }
}
