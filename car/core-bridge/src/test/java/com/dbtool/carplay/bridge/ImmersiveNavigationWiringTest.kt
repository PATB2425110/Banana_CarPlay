package com.dbtool.carplay.bridge

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Source wiring complements the pure policy/config tests; not phone/Android video execution. */
class ImmersiveNavigationWiringTest {
    @Test fun systemBackReturnsFrom111WithoutPoppingThePageOrForwardingPhoneInput() {
        val s = source("car/ui/src/main/java/com/dbtool/carplay/ui/screens/CarPlayScreen.kt")
        assertTrue(s.contains("BackHandler(enabled = immersive && navigationVisible) { returnToMain?.invoke() }"))
        val back = s.substringAfter("returnToMain = {").substringBefore("main = TextureView")
        assertTrue(back.contains("currentActions.onTouch(emptyList())"))
        assertTrue(back.contains("policy.requestNavigation(false)"))
        assertTrue(back.contains("updateVisible()"))
        assertTrue(back.contains("source=system_back"))
        assertFalse(back.contains("onBack()"))
    }
    @Test fun windowSelectionSurvivesViewDisposalWithoutSavingUnrenderedIntent() {
        val s = source("car/ui/src/main/java/com/dbtool/carplay/ui/screens/CarPlayScreen.kt")
        assertTrue(s.contains("requestNavigation(currentActions.immersiveNavigationWindowSelected)"))
        val change = s.substringAfter("if (target == front) return").substringBefore("main = TextureView")
        assertTrue(change.contains("rememberImmersiveNavigationWindow("))
        val controller = source("car/app/src/main/java/com/dbtool/carplay/app/CarAppController.kt")
        assertTrue(controller.contains("override var immersiveNavigationWindowSelected: Boolean = true"))
    }
    @Test fun projectionHasExplicitReconnectAndImmersiveContentHasSingleChoiceDialog() {
        val s = source("car/ui/src/main/java/com/dbtool/carplay/ui/screens/SettingsScreen.kt")
        assertTrue(s.contains("if (draft.projectionEnabled)"))
        assertTrue(s.contains("CarButton(text = \"重新连接\", onClick = { actions.applySettings(draft, reconnect = true) }"))
        val immersive = s.substringAfter("private fun ImmersiveNavigationSettings(").substringBefore("private fun ClusterFrameGroup(")
        assertTrue(immersive.contains("title = \"沉浸导航显示苹果原生卡片\""))
        assertTrue(immersive.contains("CarChoiceDialog(title = \"沉浸导航内容对齐\""))
        assertTrue(immersive.contains("label = { carClusterAlignmentLabel(it) }"))
        assertFalse(immersive.contains("导航卡片居中"))
        assertTrue(immersive.contains("immersiveNavigationSafeArea = CarSettingsSafeArea(0, 72, 1920, 880)"))
    }
    private fun source(path: String): String {
        var root = File(requireNotNull(System.getProperty("user.dir")))
        while (!File(root, path).isFile) root = root.parentFile ?: error(path)
        return File(root, path).readText()
    }
    @Test fun everyIndependentPreferenceHasBothReadAndWrite() {
        val s = source("car/core-bridge/src/main/java/com/dbtool/carplay/bridge/SharedPreferencesCarSettingsStore.kt")
        for (key in listOf("enabled", "card", "alignment", "map_layout", "draw_outside", "safe_x", "safe_y", "safe_width", "safe_height")) {
            assertTrue(key, Regex("\"immersive_navigation_$key\"").findAll(s).count() == 2)
        }
    }
    @Test fun uiUsesConnectionModeNotUnappliedSavedDraft() {
        val s = source("car/ui/src/main/java/com/dbtool/carplay/ui/screens/CarPlayScreen.kt")
        assertTrue(s.contains("immersive = model.immersiveNavigationActive"))
        assertTrue(s.contains("policy.onFirstFrame(111)"))
        assertTrue(s.contains("policy.requestNavigation(it)"))
    }
    @Test fun altNeverForwardsOneOrTwoFingerInputToPhone() {
        val s = source("car/ui/src/main/java/com/dbtool/carplay/ui/screens/CarPlayScreen.kt")
        assertTrue(s.contains("if (policy.current() == ImmersiveNavigationPolicy.Screen.MAIN_110)"))
        assertTrue(s.contains("currentActions.onTouch(emptyList())"))
        assertTrue(s.contains("container.addView(alt, 0,"))
        assertTrue(s.contains("alt.bringToFront()"))
    }
    @Test fun nativeInstrumentModeEntrypointsHaveImmersiveGate() {
        val s = source("car/app/src/main/java/com/dbtool/carplay/app/CarAppController.kt")
        for (signature in listOf("override fun setClusterModeGuardEnabled(", "override fun setClusterVisible(", "private fun startClusterIfPossible(", "private fun showClusterProjection(")) {
            val body = s.substringAfter(signature).substringAfter("{").take(120)
            assertTrue(signature, body.contains("if (instrumentVideoSuppressed()) return"))
        }
    }
    @Test fun showUiUsesFrozenImmersiveCardConfiguration() {
        val s = source("car/core-bridge/src/main/java/com/dbtool/carplay/bridge/CarPlaySessionBridge.kt")
        val body = s.substringAfter("private fun ensureClusterContentSent(").substringBefore("private fun scheduleReapplyProjection(")
        assertTrue(body.contains("immersiveConfigForConnection.immersiveNavigationCardEnabled"))
        assertTrue(s.contains("immersiveConfigForConnection = current"))
    }
}
