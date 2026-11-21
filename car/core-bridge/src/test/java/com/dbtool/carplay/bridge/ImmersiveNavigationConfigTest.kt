package com.dbtool.carplay.bridge

import com.dbtool.carplay.api.display.CarDisplay
import com.dbtool.carplay.api.settings.CarSessionSettings
import com.dbtool.carplay.api.settings.CarSettingsSafeArea
import com.dbtool.carplay.api.settings.CarSettingsReconnectPolicy
import org.junit.Assert.*
import org.junit.Test

class ImmersiveNavigationConfigTest {
    private fun config(s: CarSessionSettings) = CarPlayConfigBuilder.airPlayConfig(s,
        CarDisplay(id = 0, widthPixels = 1920, heightPixels = 1080, densityDpi = 160), 1920, 1080)
    @Test fun noInstrumentDisplayIsRequiredForImmersiveDeclaration() {
        val c = config(CarSessionSettings.default().copy(immersiveNavigationEnabled = true)).cluster!!
        assertEquals(1920, c.widthPixels); assertEquals(1080, c.heightPixels); assertEquals(60, c.fps)
        assertTrue(c.initialUrl!!.contains("maps:"))
    }
    @Test fun instrumentCardAndCropDoNotChangeImmersiveConfiguration() {
        val s = CarSessionSettings.default().copy(immersiveNavigationEnabled = true,
            immersiveNavigationCardEnabled = true, immersiveNavigationSafeArea = CarSettingsSafeArea(10, 20, 1800, 1000))
        assertEquals(config(s).cluster, config(s.copy(clusterNavigationCard = false, safeArea = CarSettingsSafeArea(100, 100, 400, 200))).cluster)
        val c = config(s).cluster!!
        assertEquals(20, c.safeArea!!.top); assertEquals(60, c.safeArea!!.bottom)
    }
    @Test fun immersiveCardChangesOnlyItsOwnUrl() {
        val s = CarSessionSettings.default().copy(immersiveNavigationEnabled = true)
        assertNotEquals(config(s).cluster!!.initialUrl, config(s.copy(immersiveNavigationCardEnabled = true)).cluster!!.initialUrl)
        assertFalse(s.clusterNavigationCard)
    }
    @Test fun invalidSafeAreaIsClampedAndDefaultUsesRequestedRectangle() {
        val s = CarSessionSettings.default().copy(immersiveNavigationEnabled = true)
        assertEquals(CarSettingsSafeArea(0, 72, 1920, 880), s.immersiveNavigationSafeArea)
        assertEquals(72, config(s).cluster!!.safeArea!!.top)
        assertEquals(128, config(s).cluster!!.safeArea!!.bottom)
        val c = config(s.copy(immersiveNavigationSafeArea = CarSettingsSafeArea(9999, -10, 99999, 99999))).cluster!!
        assertTrue(c.safeArea!!.top >= 0); assertTrue(c.safeArea!!.bottom >= 0)
    }
    @Test fun immersiveAlignmentUsesSameProtocolAsInstrument() {
        val s = CarSessionSettings.default().copy(immersiveNavigationEnabled = true, immersiveNavigationCardEnabled = true)
        for ((index, layout) in listOf("leftAligned", "rightAligned", "topAligned").withIndex()) {
            assertTrue(config(s.copy(immersiveNavigationAlignment = index)).cluster!!.initialUrl!!.contains("maneuverLayout=$layout"))
        }
    }
    @Test fun displayModeCardAndSafeAreaRequireReconnect() {
        val s = CarSessionSettings.default()
        for (next in listOf(s.copy(immersiveNavigationEnabled = true), s.copy(immersiveNavigationCardEnabled = true),
            s.copy(immersiveNavigationSafeArea = CarSettingsSafeArea(0, 0, 1600, 900)))) {
            assertTrue(CarSettingsReconnectPolicy.requiresReconnect(s, next))
        }
    }
}
