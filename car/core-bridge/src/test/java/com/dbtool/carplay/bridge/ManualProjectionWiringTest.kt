package com.dbtool.carplay.bridge

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ManualProjectionWiringTest {
    private fun source(path: String): String {
        var dir = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (!File(dir, path).isFile) dir = dir.parentFile ?: error("missing $path")
        return File(dir, path).readText()
    }
    @Test fun productionDoesNotCreateOrEnableRouteProjectionSubscriber() {
        val bridge = source("car/core-bridge/src/main/java/com/dbtool/carplay/bridge/CarPlaySessionBridge.kt")
        assertFalse(bridge.contains("CarPlayClusterRouteClient("))
        assertFalse(bridge.contains(".clusterAutoShowOnRoute"))
        assertFalse(bridge.contains("clusterRouteClient.connect()"))
        assertTrue(bridge.contains("ClusterProjectionIntent.resetSession()"))
    }
    @Test fun retiredSwitchIsHiddenAndCannotChangeSetting() {
        val screen = source("car/ui/src/main/java/com/dbtool/carplay/ui/screens/SettingsScreen.kt")
        assertFalse(screen.contains("title = \"导航时自动投屏\""))
        assertFalse(screen.contains("draft.clusterAutoShowOnRoute"))
        assertTrue(screen.contains("打开立即投屏"))
    }
    @Test fun savedEnabledSwitchProjectsOnConnectionWithoutRouteCondition() {
        val controller = source("car/app/src/main/java/com/dbtool/carplay/app/CarAppController.kt")
        val started = controller.substringAfter("CarSessionEvent.SessionStarted ->").substringBefore("is CarSessionEvent.SessionEnded ->")
        assertTrue(started.contains("sessionState.phase == CarSessionPhase.ACTIVE && settings.projectionEnabled"))
        assertTrue(started.contains("showClusterProjection(source = \"投屏开关已开启，会话就绪\", manualOpen = true)"))
    }
}
