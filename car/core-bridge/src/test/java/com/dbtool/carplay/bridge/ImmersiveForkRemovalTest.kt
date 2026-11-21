package com.dbtool.carplay.bridge

import java.io.File
import com.dbtool.carplay.api.display.ClusterLayerMode
import com.dbtool.carplay.api.settings.ClusterLayerModeSetting
import org.junit.Assert.*
import org.junit.Test

/** Deleted-feature assertions replace tests for code intentionally absent from this fork. */
class ImmersiveForkRemovalTest {
    private fun root(): File {
        var root = File(requireNotNull(System.getProperty("user.dir")))
        while (!File(root, "settings.gradle.kts").isFile) root = root.parentFile ?: error("root")
        return root
    }
    @Test fun themeNetworkAndSkinImplementationAreNotInProductionSources() {
        val root = root()
        for (path in listOf(
            "car/core-bridge/src/main/java/com/dbtool/carplay/bridge/theme/ClusterThemeRepository.kt",
            "car/core-bridge/src/main/java/com/dbtool/carplay/bridge/theme/ThemeAuthorizationClient.kt",
            "car/ui/src/main/java/com/dbtool/carplay/ui/screens/ClusterThemeScreen.kt",
            "car/ui/src/main/java/com/dbtool/carplay/ui/cluster/ClusterSkinView.kt",
            "car/app/src/main/assets/cluster-themes/ultra_dual.json",
        )) assertFalse(path, File(root, path).exists())
    }
    @Test fun themePageIsRemovedRatherThanRuntimeHidden() {
        val source = File(root(), "car/ui/src/main/java/com/dbtool/carplay/ui/navigation/CarDestination.kt").readText()
        assertFalse(source.contains("CLUSTER_THEME("))
        val app = File(root(), "car/app/src/main/java/com/dbtool/carplay/app/MainActivity.kt").readText()
        assertFalse(app.contains("fun startThemeQrScan("))
        assertFalse(app.contains("ThemeAuthorizationClient("))
    }
    @Test fun legacyThemeOnlyModeCannotHideNavigationOrReopenThemeWindow() {
        ClusterLayerModeSetting.restore("THEME_ONLY")
        assertEquals(ClusterLayerMode.CARPLAY_ONLY, ClusterLayerModeSetting.current())
        ClusterLayerModeSetting.set(ClusterLayerMode.BOTH)
        assertEquals(ClusterLayerMode.CARPLAY_ONLY, ClusterLayerModeSetting.current())
    }
}
