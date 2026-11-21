package com.dbtool.carplay.bridge

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Source contracts complement the pure policy tests; they do not emulate vehicle firmware. */
class ClusterProjectionIntentWiringTest {
    private fun source(relative: String): String {
        var root = File(requireNotNull(System.getProperty("user.dir")))
        while (!File(root, "car/app").isDirectory) {
            root = root.parentFile ?: error("repository root not found")
        }
        return File(root, relative).readText()
    }

    @Test fun manualAndAutomaticAppEntrypointsStayDistinct() {
        val app = source("car/app/src/main/java/com/dbtool/carplay/app/CarAppController.kt")
        assertTrue(app.contains("manualOpen = true"))
        assertTrue(app.contains("ClusterProjectionIntent.onManualRequest(false)"))
        assertTrue(app.contains("visible && !com.dbtool.carplay.api.display.ClusterProjectionIntent.allowsAutomaticOpen()"))
        assertTrue(app.indexOf("clusterPresenter?.onManualProjectionOpen()") <
            app.indexOf("if (clusterPresenter?.isWindowAttached() == true) {", app.indexOf("private fun showClusterProjection")))
    }

    @Test fun logsNeverDirectlyReopenAndPollUsesEligibility() {
        val presenter = source("car/platform-geely/src/main/java/com/dbtool/carplay/geely/GeelyClusterPresenter.kt")
        val logHandler = presenter.substringAfter("private fun onClusterTakenBack(source: String)")
            .substringBefore("private fun pollNaviModeOnce()")
        assertFalse(logHandler.contains("seizeCluster("))
        assertFalse(logHandler.contains("modeSession.reopen("))
        assertTrue(presenter.contains("rendering && navigationLayerVisible"))
        assertTrue(presenter.contains("!modeObserver.isOwnModeOffQuiet()"))
        assertTrue(presenter.contains("projectionIntent.observeMode("))
        assertTrue(presenter.contains("modeSession.acceptExternalModeOff()"))
    }

    @Test fun lifecycleAndRecoveryAreWired() {
        val route = source("car/core-bridge/src/main/java/com/dbtool/carplay/bridge/navigation/CarPlayClusterRouteClient.kt")
        assertEquals(2, Regex("ClusterProjectionIntent\\.resetSession\\(\\)").findAll(route).count())
        assertTrue(route.contains("if (wanted && !com.dbtool.carplay.api.display.ClusterProjectionIntent.allowsAutomaticOpen()) return"))
        val presenter = source("car/platform-geely/src/main/java/com/dbtool/carplay/geely/GeelyClusterPresenter.kt")
        assertTrue(presenter.substringAfter("override fun start(").substringBefore("stopped = false")
            .contains("if (!projectionIntent.allowsAutomaticOpen()) return"))
        val mode = source("car/platform-geely/src/main/java/com/dbtool/carplay/geely/GeelyClusterModeSession.kt")
            .substringAfter("fun acceptExternalModeOff()").substringBefore("/**")
        assertTrue(mode.contains("invalidate()"))
        assertFalse(mode.contains("safeEnter()"))
        assertFalse(mode.contains("safeExit()"))
    }
}
