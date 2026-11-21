package com.dbtool.carplay.bridge

import com.dbtool.carplay.api.settings.*
import com.dbtool.carplay.api.display.theme.ClusterAppearanceMode
import com.dbtool.carplay.bridge.update.DisabledProductUpdates
import org.junit.Assert.*
import org.junit.Test

class AppleCarPlayProductTest {
    @Test fun musicUsesFixedBothRateCapabilitiesWithoutManualCeilingSettings() {
        val base = CarSessionSettings.default().forAppleCarPlay()
        val display = com.dbtool.carplay.api.display.CarDisplay(id = 0, widthPixels = 1920, heightPixels = 1080, densityDpi = 160)
        val config = CarPlayConfigBuilder.airPlayConfig(base, display, 1920, 1080)
        assertEquals(48000, config.audioQuality.musicRate)
        assertFalse(CarSessionSettings::class.java.declaredFields.any { it.name.endsWith("MaxSampleRate") })
    }
    @Test fun experimentalMediaFlagDefaultsOffSurvivesPolicyAndRequiresReconnectBothWays() {
        val original = CarSessionSettings.default().forAppleCarPlay()
        assertFalse(original.higherQualityMedia)
        val enabled = original.copy(higherQualityMedia = true).forAppleCarPlay()
        assertTrue(enabled.higherQualityMedia)
        assertTrue(CarSettingsReconnectPolicy.requiresReconnect(original, enabled))
        assertTrue(CarSettingsReconnectPolicy.requiresReconnect(enabled, original))
        val display = com.dbtool.carplay.api.display.CarDisplay(id = 0, widthPixels = 1920, heightPixels = 1080, densityDpi = 160)
        assertTrue(CarPlayConfigBuilder.airPlayConfig(enabled, display, 1920, 1080).experimentalBufferedLpcm)
        assertFalse(CarPlayConfigBuilder.airPlayConfig(original, display, 1920, 1080).experimentalBufferedLpcm)
    }
    @Test fun hiddenMediaReportingRemainsEnabledForOldPreferences() {
        assertTrue(CarSessionSettings.default().copy(reportMediaCardToCar = false).forAppleCarPlay().reportMediaCardToCar)
    }
    @Test fun mediaBufferPresetsUseSevenFiftyMillisecondSteps() {
        val expected = listOf(200, 250, 300, 350, 400, 450, 500)
        assertEquals(expected, CarSessionSettings.AUDIO_BUFFER_PRESETS)
        for (value in expected) assertEquals(value, CarSessionSettings.sanitizeAudioBufferMillis(value))
        assertEquals(300, CarSessionSettings.sanitizeAudioBufferMillis(1000))
        assertEquals(300, CarSessionSettings.sanitizeAudioBufferMillis(225))
    }
    @Test fun hiddenAudioRoutesCannotBeOverriddenByOldPreferences() {
        val s = CarSessionSettings.default().copy(mediaAudioChannel = 9, navigationAudioChannel = 4,
            advancedAudioChannelMapping = true, startupPerfCompile = true).forAppleCarPlay()
        assertEquals(3, s.mediaAudioChannel); assertEquals(15, s.navigationAudioChannel)
        assertFalse(s.advancedAudioChannelMapping); assertFalse(s.startupPerfCompile)
    }
    @Test fun factoryHotspotDefaultsMatchProduct() {
        val s = CarSessionSettings.default()
        assertEquals(CarHotspotMode.GEELY_ROUTED, s.hotspotMode)
        assertEquals("Lynk&Co", s.manualHotspotSsid)
        assertEquals("12345678", s.manualHotspotPassphrase)
    }
    @Test fun oldImportsCannotRestoreRemovedModesOrAdb() {
        for (mode in CarHotspotMode.entries) {
            val s = CarSessionSettings.default().copy(hotspotMode = mode, debugTunnelEnabled = true,
                enableBuiltInAdb = true, startupPerfCompile = true).forAppleCarPlay()
            assertEquals(CarHotspotMode.GEELY_ROUTED, s.hotspotMode)
            assertFalse(s.debugTunnelEnabled); assertFalse(s.enableBuiltInAdb); assertFalse(s.startupPerfCompile)
        }
    }
    @Test fun identityIsFixedAcrossOldPreferences() {
        val s = CarSessionSettings.default().copy(deviceName = "old", manufacturer = "old", model = "old", oemLabel = "old").forAppleCarPlay()
        assertEquals(listOf("Lynk&Co", "Lynk&Co", "Lynk&Co", "Lynk&Co"), listOf(s.deviceName, s.manufacturer, s.model, s.oemLabel))
    }
    @Test fun routedCredentialAutofillDoesNotSelectTcamReuse() {
        assertTrue(CarHotspotMode.GEELY_ROUTED.readsAppleCarPlayHotspotCredentials)
        assertFalse(CarHotspotMode.GEELY_ROUTED.reusesCarNativeHotspot)
        assertFalse(CarHotspotMode.ADB_HOSTAPD.readsAppleCarPlayHotspotCredentials)
        val next = CarSessionSettings.default().withReadCarHotspot("Factory AP", "factory-pass").forAppleCarPlay()
        assertEquals(CarHotspotMode.GEELY_ROUTED, next.hotspotMode)
        assertEquals("Factory AP", next.manualHotspotSsid)
        assertEquals("factory-pass", next.manualHotspotPassphrase)
    }
    @Test fun handoffIsLinkDisconnectNotAdapterPowerOff() {
        val s = CarSessionSettings.default().forAppleCarPlay()
        assertEquals(CarBluetoothHandoffPolicy.DISCONNECT_LINK, s.bluetoothHandoffPolicy)
        assertEquals(ClusterAppearanceMode.SYSTEM, s.uiAppearanceMode)
    }
    @Test fun missingNativePasswordCannotEraseSavedPassword() {
        val s = CarSessionSettings.default().copy(manualHotspotPassphrase = "saved-pass")
        val next = s.withReadCarHotspot("Factory AP", null)
        assertEquals("Factory AP", next.manualHotspotSsid); assertEquals("saved-pass", next.manualHotspotPassphrase)
    }
    @Test fun knownEmptyPasswordIsOpenAndKnownPasswordIsWpa2() {
        val s = CarSessionSettings.default()
        assertEquals(CarHotspotSecurity.OPEN, s.withReadCarHotspot("Factory AP", "").manualHotspotSecurity)
        assertEquals(CarHotspotSecurity.WPA2, s.withReadCarHotspot("Factory AP", "factory-pass").manualHotspotSecurity)
        assertEquals("factory-pass", s.withReadCarHotspot("Factory AP", "factory-pass").manualHotspotPassphrase)
    }
    @Test fun blankSsidOrInvalidPasswordDoesNotClobberGoodSettings() {
        val s = CarSessionSettings.default().copy(manualHotspotPassphrase = "saved-pass")
        assertEquals(s, s.withReadCarHotspot("", "bad"))
        assertEquals("saved-pass", s.withReadCarHotspot("Factory AP", "bad").manualHotspotPassphrase)
    }
    @Test fun removedUpdatesCannotBeReenabledOrCallNetworkCallback() {
        var called = false
        DisabledProductUpdates.checkAsync(true) { called = true }
        DisabledProductUpdates.maybeAutoCheck { called = true }
        assertFalse(DisabledProductUpdates.download({ called = true }, false)); assertFalse(called)
        assertFalse(DisabledProductUpdates.setSilentUpdateEnabled(true).silentUpdateEnabled)
    }
}
