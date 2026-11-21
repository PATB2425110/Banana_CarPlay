package com.dbtool.carplay.bridge

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ClusterPhoneStopContractTest {
    private fun source(): String {
        var directory: File? = File(".").absoluteFile
        while (directory != null) {
            val file = File(directory, "car/core-bridge/src/main/java/com/dbtool/carplay/bridge/CarPlaySessionBridge.kt")
            if (file.isFile) return file.readText()
            directory = directory.parentFile
        }
        error("missing source")
    }

    @Test fun `关闭副屏不能依赖曾经发过showUI的本地记录`() {
        val stop = source().substringAfter("override fun setProjectionEnabled(enabled: Boolean)")
            .substringBefore("override fun setProjectionContent")
        assertTrue(stop.contains("val phoneStreamReady = (airPlaySession?.clusterStream ?: 0) > 0"))
        assertTrue(stop.contains("phoneStreamReady && !settings.hudProjectionEnabled"))
        assertTrue(stop.contains("setClusterUiShown(false)"))
        assertFalse(stop.contains("val stopSent = if (shownBefore)"))
    }

    @Test fun `开关关闭仍等待延迟到达的副屏流但保护独立HUD与新会话`() {
        val code = source().substringAfter("private fun scheduleStopUnrequestedClusterUi()")
            .substringBefore("// ------------------------------------------------------------------ internals")
        assertTrue(code.contains("airPlaySession !== expected"))
        assertTrue(code.contains("settings.hudProjectionEnabled"))
        assertTrue(code.contains("workExecutor.execute"))
        assertTrue(code.contains("expected.setClusterUiShown(false)"))
        assertTrue(code.contains("20_000L"))
    }
}
