package com.dbtool.carplay.bridge

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ImmersiveGlassCardsIsolationTest {
    private fun source(path: String): String {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "shared/src/main").isDirectory }
        return File(root, path).readText()
    }
    @Test fun overlayRequiresActual111AndDoesNotRebuildVideoForToggle() {
        val s = source("car/ui/src/main/java/com/dbtool/carplay/ui/screens/CarPlayScreen.kt")
        assertTrue(s.contains("immersive && navigationVisible && model.settings.immersiveGlassCardsEnabled"))
        assertTrue(s.contains("androidx.compose.runtime.key(immersive)"))
        assertFalse(s.contains("key(model.settings.immersiveGlassCardsEnabled)"))
        assertTrue(s.contains("if (policy.current() == ImmersiveNavigationPolicy.Screen.MAIN_110)"))
    }
    @Test fun cardUsesExistingMediaSourceAndPersistentDefaultOffSwitch() {
        val app = source("car/app/src/main/java/com/dbtool/carplay/app/CarAppController.kt")
        assertTrue(app.contains("immersiveNowPlaying = dockNowPlaying"))
        assertTrue(app.contains("session.nowPlayingSource()?.previous()"))
        assertTrue(app.contains("!settings.dockBar && !(settings.immersiveNavigationEnabled && settings.immersiveGlassCardsEnabled)"))
        val prefs = source("car/core-bridge/src/main/java/com/dbtool/carplay/bridge/SharedPreferencesCarSettingsStore.kt")
        assertTrue(prefs.contains("prefs.getBoolean(\"immersive_glass_cards\", false)"))
        assertTrue(prefs.contains(".putBoolean(\"immersive_glass_cards\", settings.immersiveGlassCardsEnabled)"))
        val cards = source("car/ui/src/main/java/com/dbtool/carplay/ui/screens/ImmersiveGlassCards.kt")
        assertFalse(cards.contains("sendBroadcast("))
        assertFalse(cards.contains("onTouch("))
        assertFalse(cards.contains("TextureView"))
        assertFalse(cards.contains("PixelCopy"))
    }
}
