package com.dbtool.carplay.bridge.developer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlayTcpCaptureTest {
    @Test
    fun `capture command is bounded and limits output to TCP 7000 handshake flags`() {
        val command = AirPlayTcpCapture.buildCaptureCommand(
            tcpdumpPath = "/system/bin/tcpdump",
            remoteLogPath = "/data/local/tmp/dbplay_airplay_tcp_1792112537035.txt",
        )

        assertTrue(command.contains("-i any"))
        assertTrue(command.contains("tcp port 7000"))
        assertTrue(command.contains("tcp[13] & 0x06 != 0"))
        assertTrue(command.contains("-c ${AirPlayTcpCapture.MAX_PACKETS}"))
        assertTrue(command.contains("-tttt -l"))
        assertTrue(command.contains("${AirPlayTcpCapture.WINDOW_SECONDS}"))
        assertFalse(command.contains("do;"))
        assertTrue(command.contains("rm -f \"/data/local/tmp/dbplay_airplay_tcp_1792112537035.txt\""))
        assertFalse(command.contains("-X"))
        assertFalse(command.contains("-A"))
    }

    @Test
    fun `capture refuses to initialize adb or root when no root shell is ready`() {
        val result = AirPlayTcpCapture(rootShellProvider = { null }).capture()
        assertTrue(result.status == AirPlayTcpCaptureResult.Status.ROOT_SHELL_UNAVAILABLE)
        assertTrue(result.detail.contains("不会自动开启 ADB 或提权"))
    }

    @Test
    fun `capture probes tool then requests a bounded capture`() {
        val commands = mutableListOf<String>()
        val shell = CarSystemShell { command, timeout ->
            commands += "$timeout:$command"
            when (commands.size) {
                1 -> "/system/bin/tcpdump"
                else -> "listening on any\\n__CAPTURE_WINDOW_SECONDS__=90\\n__TCPDUMP_EXIT__=0\\n"
            }
        }
        val result = AirPlayTcpCapture(rootShellProvider = { shell }).capture()
        assertTrue(result.status == AirPlayTcpCaptureResult.Status.CAPTURED)
        assertTrue(commands.first().contains("tcpdump"))
        assertTrue(commands.last().contains("tcp port 7000"))
        assertTrue(commands.last().startsWith("${AirPlayTcpCapture.CAPTURE_TIMEOUT_MILLIS}:"))
    }

    @Test
    fun `capture output distinguishes SYN from SYN ACK without claiming absent traffic`() {
        val result = AirPlayTcpCaptureResult(
            status = AirPlayTcpCaptureResult.Status.CAPTURED,
            startedAt = "2026-10-16 10:00:00",
            detail = "采集窗口已结束",
            output = """
                listening on any
                __CAPTURE_WINDOW_SECONDS__=90
                __TCPDUMP_EXIT__=0
                2026-10-16 10:00:01 IP 192.168.15.8.50100 > 198.18.34.15.7000: Flags [S], seq 1
                2026-10-16 10:00:01 IP 198.18.34.15.7000 > 192.168.15.8.50100: Flags [S.], seq 2
            """.trimIndent(),
        )
        val text = AirPlayTcpCaptureDiagnosticSummary.render(result)
        assertTrue(text.contains("SYN observed: true"))
        assertTrue(text.contains("SYN-ACK observed: true"))
        assertFalse(text.contains("192.168.15.8"))
        assertTrue(text.contains("source/destination redacted"))

        val optedIn = AirPlayTcpCaptureDiagnosticSummary.render(result, includeClientAddresses = true)
        assertTrue(optedIn.contains("192.168.15.8"))
    }

    @Test
    fun `missing capture is explicitly unknown not no SYN`() {
        val text = AirPlayTcpCaptureDiagnosticSummary.render(null)
        assertTrue(text.contains("Capture status: NOT_REQUESTED"))
        assertTrue(text.contains("SYN/SYN-ACK were not observed because no packet capture was requested"))
        assertFalse(text.contains("SYN observed: false"))
    }

    @Test
    fun `capture path validation blocks arbitrary shell text`() {
        val error = runCatching {
            AirPlayTcpCapture.buildCaptureCommand(
                tcpdumpPath = "/system/bin/tcpdump; reboot",
                remoteLogPath = "/data/local/tmp/dbplay_airplay_tcp_1.txt",
            )
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }
}
