package com.dbtool.carplay.bridge

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class TabletImmersiveRestoreCompatibilityTest {
    private fun source(): String {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "shared/src/main").isDirectory }
        return File(root, "car/core-bridge/src/main/java/com/dbtool/carplay/bridge/CarPlaySessionBridge.kt").readText()
    }
    @Test fun carOwnedWindowExitStaysBeforeTabletOnlyImmersiveGuard() {
        val s = source().substringAfter("private fun scheduleReapplyProjection()")
            .substringBefore("private fun scheduleStopUnrequestedClusterUi()")
        val carExit = s.indexOf("if (platformOwnsClusterWindow) return")
        val immersiveExit = s.indexOf("if (settings.immersiveNavigationEnabled || immersiveModeForConnection)")
        val timer = s.indexOf("val tick = object : Runnable")
        assertTrue(carExit >= 0)
        assertTrue(immersiveExit > carExit)
        assertTrue(timer > immersiveExit)
        assertTrue(s.substring(immersiveExit, timer).contains("return"))
    }
    @Test fun immersiveContentStillUsesWorkerAndNoThreadPolicyBypass() {
        val s = source()
        assertTrue(s.contains("if (active) workExecutor.execute"))
        assertTrue(s.contains("airPlaySession?.takeIf { immersiveModeForConnection }?.let(::ensureClusterContentSent)"))
        assertTrue(s.contains("workExecutor.execute { if (airPlaySessionRef.get() === session) ensureClusterContentSent(session) }"))
        assertFalse(s.contains("permitAll("))
        assertFalse(s.contains("StrictMode.setThreadPolicy"))
    }
}
