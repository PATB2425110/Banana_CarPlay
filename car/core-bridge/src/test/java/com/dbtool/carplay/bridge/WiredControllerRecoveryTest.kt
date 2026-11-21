package com.dbtool.carplay.bridge

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class WiredControllerRecoveryTest {
    private fun allowed(
        bridgeClosed: Boolean = false,
        wireless: Boolean = false,
        expected: Int = 4,
        current: Int = 4,
        same: Boolean = true,
        controllerClosed: Boolean = true,
    ) = shouldRecoverWiredController(bridgeClosed, wireless, expected, current, same, controllerClosed)

    @Test fun closedCurrentWiredControllerCanBeReplaced() {
        assertTrue(allowed())
    }

    @Test fun userStopOrNewReconnectCancelsQueuedRecovery() {
        assertFalse(allowed(current = 5))
        assertFalse(allowed(bridgeClosed = true))
    }

    @Test fun oldOwnerCannotReplaceNewControllerOrWirelessMode() {
        assertFalse(allowed(same = false))
        assertFalse(allowed(wireless = true))
    }

    @Test fun liveControllerIsNeverRetiredByDelayedCallback() {
        assertFalse(allowed(controllerClosed = false))
    }

    private fun source(path: String): String {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "shared/src/main").isDirectory }
        return File(root, path).readText()
    }

    @Test fun wiredTerminationNotifiesOwnerEvenWithoutActiveAirPlay() {
        val controller = source("shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt")
        assertTrue(controller.contains("finishWiredConnection(\"USB detached\")"))
        assertTrue(controller.contains("finishWiredConnection(\"iAP2 control channel closed\")"))
        assertTrue(controller.contains("finishWiredConnection(\"AirPlay session ended\")"))
        assertTrue(controller.contains("return@openIap2UsbSessionAsync"))
        val finish = controller.substringAfter("private fun finishWiredConnection(").substringBefore("private fun startMfi(")
        assertTrue(finish.contains("wiredDisconnectReported.compareAndSet(false, true)"))
        assertTrue(finish.indexOf("close()") < finish.indexOf("onWiredDisconnected(this)"))
    }

    @Test fun bridgeAwaitsCleanupBeforeBuildingReplacementAndDoesNotRebuildAfterTimeout() {
        val bridge = source("car/core-bridge/src/main/java/com/dbtool/carplay/bridge/CarPlaySessionBridge.kt")
        assertTrue(bridge.contains("onWiredDisconnected = ::onWiredControllerDisconnected"))
        val recovery = bridge.substringAfter("private fun onWiredControllerDisconnected(")
            .substringBefore("// ------------------------------------------------------------- core callbacks")
        assertTrue(recovery.contains("sameController = activeHandle?.controller === controller"))
        assertTrue(recovery.indexOf("detachSession()") < recovery.indexOf("closeSession(old)"))
        assertTrue(recovery.indexOf("closeSession(old)") < recovery.indexOf("startInternal()"))
        assertTrue(recovery.contains("if (!clean || closed || generation.get() != observedGeneration || settings.isWireless)"))
        assertTrue(bridge.contains("handle.controller.isClosed() && !settings.isWireless"))
    }
}
