package com.dbtool.carplay.bridge

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Cross-module source wiring only, not a real Android/Binder/vehicle test. */
class NativeCardDiagnosticsContractTest {
    private val root = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    private fun code(path: String) = File(root, path).readText()
        .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
        .replace(Regex("//[^\\n]*"), "")

    @Test fun `native card setting has a distinct visible name and preserves its storage key`() {
        val ui = code("car/ui/src/main/java/com/dbtool/carplay/ui/screens/SettingsScreen.kt")
        assertTrue(ui.contains("title = \"原车仪表卡片\""))
        val group = ui.substringAfter("private fun ClusterHudGroup(").substringBefore("private fun hudConnectionHint(")
        assertTrue(group.contains("checked = draft.hudGuidanceEnabled"))
        assertFalse(group.contains("checked = draft.localNavigationCardEnabled"))
        assertTrue(group.contains("受理不代表原车已经绘制"))
        assertFalse("用户已删除主页导航入口开关，原车开关仍需独立保留", ui.contains("title = \"显示主页导航卡片\""))
    }

    @Test fun `input rejection and lane transport attempts are observable without inventing directions`() {
        val bridge = code("car/platform-geely/src/main/java/com/dbtool/carplay/geely/GeelyHudBridge.kt")
        assertTrue(bridge.contains("logInput(guidance, \"drop_no_valid_turn\")"))
        assertTrue(bridge.contains("if (guidance != null && LynkHudNormalizer.normalize(guidance) == null)"))
        assertTrue(bridge.contains("lane.send code=11 sequence="))
        assertTrue(bridge.contains("lane.clear code=12 reason="))
        assertTrue(bridge.contains("transportAccepted=true accepted="))
        assertTrue(bridge.contains("guidance.lanes, ++requestId, generation, guidance.updatedAtMs"))
        assertTrue(bridge.contains("if (sendLanes(wireGuidance)) lastSentLanes = guidance.lanes"))
    }

    @Test fun `power diagnostics do not introduce a door event assumption`() {
        val app = code("car/app/src/main/java/com/dbtool/carplay/app/CarAppController.kt")
        for (action in listOf("ACTION_SCREEN_OFF", "ACTION_SCREEN_ON", "ACTION_USER_PRESENT", "ACTION_SHUTDOWN")) {
            assertTrue(app.contains("filter.addAction(Intent.$action)"))
        }
        assertTrue(app.contains("不等同熄火或开门"))
        val power = code("car/core-bridge/src/main/java/com/dbtool/carplay/bridge/power/CarPowerPauseCoordinator.kt")
        assertTrue(power.contains("source=vehicle entertainmentOn="))
        assertTrue(power.contains("policy.onSnapshot(snapshot, clock())"))
    }

    @Test fun `audio diagnostic observers do not mute or add a ringtone player`() {
        val audio = code("car/core-bridge/src/main/java/com/dbtool/carplay/bridge/UpstreamMediaSinkBridge.kt")
        assertTrue(audio.contains("audio.lifecycle event=start"))
        assertTrue(audio.contains("audio.lifecycle event=stop"))
        assertTrue(audio.contains("delegate.onAudioStarted(type, format, firstSample)"))
        assertTrue(audio.contains("delegate.onAudioStopped(type)"))
        assertFalse(audio.contains("RingtoneManager"))
        assertFalse(audio.contains("setVolume("))
    }
}
