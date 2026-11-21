package com.dbtool.carplay.ui.screens

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Source contracts only; actual Compose interaction/rendering still needs Android acceptance. */
class VehicleReadSafetySectionTest {
    private fun code(relative: String): String {
        val root = generateSequence(File(".").canonicalFile) { it.parentFile }
            .first { File(it, "shared/src/main").isDirectory }
        return File(root, relative).readText()
    }
    private fun section() = code("car/ui/src/main/java/com/dbtool/carplay/ui/screens/VehicleReadSafetySection.kt")

    @Test fun vehicleSettingsIncludesIndependentReaderSwitchAndExplicitReconnectAction() {
        val settings = code("car/ui/src/main/java/com/dbtool/carplay/ui/screens/SettingsScreen.kt")
        assertTrue(settings.contains("CarSettingsSection.VEHICLE -> VehicleSection(draft, update, model, actions)"))
        assertTrue(settings.contains("VehicleReadSafetySection(onReconnect = { actions.applySettings(draft, reconnect = true) })"))
        val ui = section()
        assertTrue(ui.contains("checked = state.enabled"))
        assertTrue(ui.contains("enabled = supported"))
        assertTrue(ui.contains("onClick = onReconnect"))
        assertFalse(ui.contains("actions.stop"))
        assertFalse(ui.contains("setProperty"))
    }

    @Test fun enableRequiresConfirmationAndNoClaimOfNativeCrashFreeOperation() {
        val ui = section()
        assertTrue(ui.contains("if (enabled) confirmEnable = true"))
        assertTrue(ui.indexOf("if (confirmEnable)") < ui.indexOf("safety.setEnabled(true)"))
        assertTrue(ui.contains("默认关闭"))
        assertTrue(ui.contains("不能保证绝不闪退"))
        assertTrue(ui.contains("系统杀进程或断电"))
        assertTrue(ui.contains("state.disabledReason"))
        assertTrue(ui.contains("observer.close()"))
        assertTrue(ui.contains("LaunchedEffect(safety)"))
        assertTrue(ui.contains("collect { state = safety.state() }"))
    }
}
