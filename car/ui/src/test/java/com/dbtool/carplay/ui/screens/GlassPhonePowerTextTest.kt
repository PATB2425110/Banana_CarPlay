package com.dbtool.carplay.ui.screens

import org.junit.Assert.*
import org.junit.Test

class GlassPhonePowerTextTest {
    @Test fun lowBatteryRedOverridesChargingAnd20IsNotLow() {
        for (state in listOf(null, 0, 1, 2)) {
            assertEquals(0xffff5252.toInt(), glassPhonePowerColor(19, state))
            assertEquals(0xffff5252.toInt(), glassPhonePowerColor(0, state))
        }
        assertEquals(0xff4cdb78.toInt(), glassPhonePowerColor(20, 1))
        assertEquals(0xff4cdb78.toInt(), glassPhonePowerColor(100, 1))
        assertEquals(0xffffffff.toInt(), glassPhonePowerColor(20, 0))
        assertEquals(0xffffffff.toInt(), glassPhonePowerColor(47, null))
        assertEquals(0xffffffff.toInt(), glassPhonePowerColor(100, 2))
    }
    @Test fun batteryTextIsOnlyAPercentageWithoutCaptions() {
        assertEquals("47%", glassPhoneBatteryText(47))
        assertEquals("100%", glassPhoneBatteryText(100))
        assertFalse(glassPhoneBatteryText(47).contains("电量"))
        assertFalse(glassPhoneBatteryText(47).contains("充电中"))
    }
    @Test fun usesCurrentValueInsteadOfFixed47() {
        assertEquals("47%", glassPhoneBatteryText(47))
        assertEquals("48%", glassPhoneBatteryText(48))
        assertEquals("0%", glassPhoneBatteryText(0))
        assertEquals("100%", glassPhoneBatteryText(100))
    }
    @Test fun missingOrInvalidDoesNotShowFakePercentage() {
        assertEquals("—%", glassPhoneBatteryText(null))
        assertEquals("—%", glassPhoneBatteryText(-1))
        assertEquals("—%", glassPhoneBatteryText(101))
    }
}
