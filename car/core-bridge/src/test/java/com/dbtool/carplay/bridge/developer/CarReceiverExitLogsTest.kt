package com.dbtool.carplay.bridge.developer

import org.junit.Assert.*
import org.junit.Test

class CarReceiverExitLogsTest {
    @Test fun unavailableShellIsExplicit() {
        val files = CarReceiverExitLogs.collect(null) { 10000L }
        assertEquals(setOf("receiver_exit_capture.txt"), files.keys)
        assertTrue(files.getValue("receiver_exit_capture.txt").contains("unavailable"))
    }

    @Test fun crashIsCollectedSeparatelyBeforeEventsWithoutClearingBuffers() {
        val commands = mutableListOf<String>()
        val files = CarReceiverExitLogs.collect(CarSystemShell { command, timeout ->
            commands += command
            assertTrue(timeout <= 5000L)
            if (command.endsWith("-b crash")) "FATAL EXCEPTION: receiver" else "am_proc_died"
        }) { 12000L }
        assertEquals("logcat -d -v threadtime -b crash", commands.first())
        assertEquals(2, commands.size)
        assertFalse(commands.any { it.contains(" -c") })
        assertEquals("FATAL EXCEPTION: receiver", files["logcat_crash.txt"])
        assertEquals("am_proc_died", files["logcat_process_events.txt"])
    }

    @Test fun emptyOrFailedReadsAreNotReportedAsEvidence() {
        val files = CarReceiverExitLogs.collect(CarSystemShell { command, _ ->
            if (command.endsWith("-b crash")) "" else null
        }) { 10000L }
        assertFalse(files.containsKey("logcat_crash.txt"))
        assertFalse(files.containsKey("logcat_process_events.txt"))
        assertTrue(files.getValue("receiver_exit_capture.txt").contains("not proof of no crash"))
        assertTrue(files.getValue("receiver_exit_capture.txt").contains("failed or unavailable"))
    }

    @Test fun expiredDeadlineDoesNotRunCommands() {
        val files = CarReceiverExitLogs.collect(CarSystemShell { _, _ -> error("must not run") }) { 0L }
        assertTrue(files.getValue("receiver_exit_capture.txt").contains("deadline exhausted"))
    }
}
