package com.dbtool.carplay.ui.screens

import com.dbtool.carplay.api.settings.CarSessionSettings
import com.dbtool.carplay.api.settings.CarSettingsReconnectPolicy
import org.junit.Assert.*
import org.junit.Test

class ImmersiveGlassAppearanceTest {
    @Test fun defaultsKeepExistingBackgroundExactly() {
        assertArrayEquals(intArrayOf(0xd93d4851.toInt(), 0xd9514b4b.toInt(), 0xe0524e39.toInt()),
            glassBackgroundColors(-1, 15))
    }
    @Test fun selectedRgbAndTransparencyAreSharedForBothCards() {
        val colors = glassBackgroundColors(0x123456, 50)
        assertTrue(colors.all { (it and 0xffffff) == 0x123456 })
        assertTrue(colors.all { (it ushr 24) in 127..128 })
        assertTrue(glassBackgroundColors(0, 100).all { it ushr 24 == 0 })
        assertTrue(glassBackgroundColors(0xffffff, 0).all { it ushr 24 == 255 })
    }
    @Test fun invalidStoredValuesRemainBoundedAndFallbackToDefaultPalette() {
        assertTrue(glassBackgroundColors(-2, -9).all { it ushr 24 == 255 })
        assertTrue(glassBackgroundColors(0, 109).all { it ushr 24 == 0 })
        assertArrayEquals(glassBackgroundColors(-1, 15), glassBackgroundColors(Int.MAX_VALUE, 15))
    }
    @Test fun appearanceIsLiveOnlyAndLeavesProtocolFieldsUntouched() {
        val old = CarSessionSettings.default()
        val changed = old.copy(immersiveGlassColor = 0x123456, immersiveGlassTransparency = 70)
        assertFalse(CarSettingsReconnectPolicy.requiresReconnect(old, changed))
        assertEquals(old, changed.copy(immersiveGlassColor = old.immersiveGlassColor,
            immersiveGlassTransparency = old.immersiveGlassTransparency))
    }
}
