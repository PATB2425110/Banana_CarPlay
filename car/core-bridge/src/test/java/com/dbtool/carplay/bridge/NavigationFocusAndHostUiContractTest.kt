package com.dbtool.carplay.bridge

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wiring assertions complement the Android-free state-machine tests. Comments cannot satisfy them. */
class NavigationFocusAndHostUiContractTest {
    private val main = "car/core-bridge/src/main/java/com/dbtool/carplay/bridge/"
    private val api = "car/api/src/main/java/com/dbtool/carplay/api/"
    private fun code(path: String): String {
        val file = listOf("", "../", "../../", "../../../").map { File(it + path) }
            .firstOrNull { it.isFile } ?: error("Missing source: $path")
        return file.readText().replace(Regex("""/\*[\s\S]*?\*/"""), "")
            .replace(Regex("""//[^\n]*"""), "")
    }

    private fun body(source: String, signature: String): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "Missing function: $signature" }
        val open = source.indexOf('{', start)
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(open, index + 1)
            }
        }
        error("Unclosed function: $signature")
    }

    @Test fun hostUiUsesSystemHomeOnMainWithTwoSessionGuardsAndNoTeardown() {
        val callback = body(code(main + "CarPlaySessionBridge.kt"), "override fun onHostUiRequested(")
        assertTrue(callback.contains("mainHandler.post"))
        assertEquals(2, Regex(Regex.escape("airPlaySessionRef.get() !== session")).findAll(callback).count())
        assertTrue(callback.contains("appContext.startActivity("))
        for (value in listOf("Intent.ACTION_MAIN", "Intent.CATEGORY_HOME", "Intent.FLAG_ACTIVITY_NEW_TASK", "runCatching", ".onFailure", "diagnostics.warn")) {
            assertTrue(value, callback.contains(value))
        }
        for (value in listOf("stop(", "reconnect(", "close(", "SessionEnded", "onDockHomeClick", "requestUi(")) {
            assertFalse(value, callback.contains(value))
        }
    }

    @Test fun navigationSettingIsDefaultEnabledLoadedSavedDumpedAndLive() {
        val field = "navigationAudioFocusEnabled"
        assertTrue(code(api + "settings/CarSessionSettings.kt").contains("val $field: Boolean = true"))
        val store = code(main + "SharedPreferencesCarSettingsStore.kt")
        assertTrue(store.contains("$field = prefs.getBoolean(KEY_NAVIGATION_AUDIO_FOCUS_ENABLED, d.$field)"))
        assertTrue(store.contains(".putBoolean(KEY_NAVIGATION_AUDIO_FOCUS_ENABLED, settings.$field)"))
        assertTrue(store.contains("KEY_NAVIGATION_AUDIO_FOCUS_ENABLED = \"navigation_audio_focus_enabled\""))
        assertTrue(code(api + "developer/CarDiagnosticSettingsDump.kt").contains("$field: \${settings.$field}"))
        val policy = code(api + "settings/CarSettingsReconnectPolicy.kt")
        assertTrue(policy.contains("\"$field\""))
        assertTrue(policy.contains("$field = previous.$field"))
    }

    @Test fun sessionUsesSpeechStateAndAppliesSavedSettingOffMainThread() {
        val bridge = code(main + "CarPlaySessionBridge.kt")
        val update = body(bridge, "override fun updateSettings(")
        assertTrue(update.contains("workExecutor.execute"))
        assertTrue(update.contains("activeHandle?.focusGuard?.setNavigationAudioFocusEnabled(settings.navigationAudioFocusEnabled)"))
        assertTrue(bridge.contains("navigationAudioFocusEnabled = settings.navigationAudioFocusEnabled"))
        assertTrue(bridge.contains("handle.focusGuard?.setNavigationAudioFocusEnabled(settings.navigationAudioFocusEnabled)"))
        assertTrue(bridge.contains("focusGuard.setNavigationSpeechActive(active)"))
        assertTrue(bridge.contains("focusGuard.acquire()")) // Media focus remains independent.
        val guard = code(main + "audio/CarAudioFocusGuard.kt")
        assertTrue(guard.contains("navigationState.close()"))
        assertTrue(guard.contains("navigationState.setSpeechActive(active)"))
        val controller = body(code("car/app/src/main/java/com/dbtool/carplay/app/CarAppController.kt"), "override fun applySettings(")
        assertTrue(controller.indexOf("services.settingsStore.save(settings)") < controller.indexOf("session.updateSettings(settings)"))
    }

    @Test fun settingsSwitchUsesRequestedNavigationVolumeHint() {
        val ui = code("car/ui/src/main/java/com/dbtool/carplay/ui/screens/SettingsScreen.kt")
        assertTrue(ui.contains("checked = draft.navigationAudioFocusEnabled"))
        assertTrue(ui.contains("update(draft.copy(navigationAudioFocusEnabled = it))"))
        assertTrue(ui.contains("导航播报时通过按键或旋钮调节导航音量"))
    }
}
