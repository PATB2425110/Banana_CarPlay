package com.dbtool.carplay.bridge.developer

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Result of an explicitly requested, bounded capture of TCP handshake headers for AirPlay port 7000. */
internal data class AirPlayTcpCaptureResult(
    val status: Status,
    val startedAt: String,
    val detail: String,
    val output: String = "",
) {
    enum class Status {
        NOT_REQUESTED,
        RUNNING,
        CAPTURED,
        ROOT_SHELL_UNAVAILABLE,
        TCPDUMP_UNAVAILABLE,
        FAILED,
    }
}

/** Executes a short packet-header capture only through an already-ready rooted ADB shell. */
internal class AirPlayTcpCapture(
    private val rootShellProvider: () -> CarSystemShell?,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    fun capture(): AirPlayTcpCaptureResult {
        val startedAt = TIME_FORMAT.format(Date(nowMillis()))
        val shell = runCatching(rootShellProvider).getOrNull()
            ?: return result(
                AirPlayTcpCaptureResult.Status.ROOT_SHELL_UNAVAILABLE,
                startedAt,
                "未启动抓包：没有已就绪的 root ADB shell；不会自动开启 ADB 或提权。",
            )

        val probe = runCatching {
            shell.run(TCPDUMP_PROBE_COMMAND, PROBE_TIMEOUT_MILLIS)
        }.getOrNull()
        val tcpdump = probe?.lineSequence()?.map(String::trim)
            ?.firstOrNull { it.startsWith("/") && TCPDUMP_PATH.matches(it) }
            ?: return result(
                AirPlayTcpCaptureResult.Status.TCPDUMP_UNAVAILABLE,
                startedAt,
                "未启动抓包：root shell 可用，但未找到 tcpdump。",
                probe.orEmpty(),
            )

        val remoteLog = "/data/local/tmp/dbplay_airplay_tcp_${nowMillis()}.txt"
        val output = runCatching {
            shell.run(buildCaptureCommand(tcpdump, remoteLog), CAPTURE_TIMEOUT_MILLIS)
        }.getOrNull()
        if (output == null) {
            return result(
                AirPlayTcpCaptureResult.Status.FAILED,
                startedAt,
                "抓包未能完成：ADB shell 命令失败或超时。",
            )
        }

        val clipped = output.take(MAX_OUTPUT_CHARS)
        val denied = listOf("permission denied", "operation not permitted", "not permitted")
            .any { it in output.lowercase() }
        val listening = "listening on" in output.lowercase()
        val exitCode = Regex("__TCPDUMP_EXIT__=(\\d+)").find(output)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val windowSeconds = Regex("__CAPTURE_WINDOW_SECONDS__=(\\d+)")
            .find(output)?.groupValues?.getOrNull(1)?.toIntOrNull()
        return when {
            denied -> result(
                AirPlayTcpCaptureResult.Status.FAILED,
                startedAt,
                "抓包失败：tcpdump 被系统权限拒绝。",
                clipped,
            )
            listening && exitCode == 0 && windowSeconds != null -> result(
                AirPlayTcpCaptureResult.Status.CAPTURED,
                startedAt,
                "采集进程正常结束（${windowSeconds} 秒）。内容仅含 TCP 7000 端口的 SYN/RST 包头，不含应用数据负载。",
                clipped,
            )
            else -> result(
                AirPlayTcpCaptureResult.Status.FAILED,
                startedAt,
                if (!listening) {
                    "抓包失败：tcpdump 未确认开始监听。"
                } else {
                    "抓包未完整结束：tcpdump exit=${exitCode ?: "unknown"} window=${windowSeconds ?: "unknown"} 秒。"
                },
                clipped,
            )
        }
    }

    companion object {
        const val WINDOW_SECONDS = 90
        const val MAX_PACKETS = 128
        const val PROBE_TIMEOUT_MILLIS = 5_000L
        const val CAPTURE_TIMEOUT_MILLIS = 105_000L
        const val MAX_OUTPUT_CHARS = 64 * 1024

        private val TIME_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        private val TCPDUMP_PATH = Regex("/[A-Za-z0-9_./-]+")

        private const val TCPDUMP_PROBE_COMMAND =
            "sh -c 'for p in /system/bin/tcpdump /system/xbin/tcpdump /vendor/bin/tcpdump " +
                "/vendor/bin/tcpdump_static; do if [ -x \"\$p\" ]; then echo \"\$p\"; " +
                "exit 0; fi; done; command -v tcpdump || true'"

        /** Exposed as a pure builder so shell quoting and capture bounds can be contract-tested. */
        fun buildCaptureCommand(tcpdumpPath: String, remoteLogPath: String): String {
            require(TCPDUMP_PATH.matches(tcpdumpPath)) { "tcpdump path must be an absolute plain path" }
            require(remoteLogPath.matches(Regex("/data/local/tmp/dbplay_airplay_tcp_[0-9]+\\.txt"))) {
                "capture output must use the DBPlay temporary path"
            }
            val script = listOf(
                "\"$tcpdumpPath\" -i any -nn -tttt -l -s 96 -c $MAX_PACKETS " +
                    "\"tcp port 7000 and (tcp[13] & 0x06 != 0)\" > \"$remoteLogPath\" 2>&1 &",
                "pid=\$!",
                "trap 'kill -INT \"\$pid\" 2>/dev/null; wait \"\$pid\" 2>/dev/null; " +
                    "rm -f \"$remoteLogPath\"' EXIT HUP INT TERM",
                "i=0",
                "while [ \"\$i\" -lt $WINDOW_SECONDS ]; do kill -0 \"\$pid\" 2>/dev/null || break; " +
                    "sleep 1; i=\$((i+1)); done",
                "kill -INT \"\$pid\" 2>/dev/null",
                "wait \"\$pid\" 2>/dev/null",
                "rc=\$?",
                "echo __CAPTURE_WINDOW_SECONDS__=\$i",
                "echo __TCPDUMP_EXIT__=\$rc",
                "cat \"$remoteLogPath\" 2>/dev/null",
                "rm -f \"$remoteLogPath\"",
                "trap - EXIT HUP INT TERM",
                "true",
            ).joinToString("; ")
            return "sh -c '${script.replace("'", "'\\''")}'"
        }

        private fun result(
            status: AirPlayTcpCaptureResult.Status,
            startedAt: String,
            detail: String,
            output: String = "",
        ) = AirPlayTcpCaptureResult(status, startedAt, detail, output)
    }
}

/** Truthful package rendering: an absent capture is not evidence that no SYN was sent. */
internal object AirPlayTcpCaptureDiagnosticSummary {
    fun render(result: AirPlayTcpCaptureResult?, includeClientAddresses: Boolean = false): String = buildString {
        appendLine("AirPlay endpoint port: 7000")
        appendLine("Capture filter: TCP port 7000, SYN/RST flags only; payload is not printed")
        if (result == null) {
            appendLine("Capture status: NOT_REQUESTED")
            appendLine("Interpretation: SYN/SYN-ACK were not observed because no packet capture was requested.")
            return@buildString
        }
        appendLine("Capture status: ${result.status}")
        appendLine("Capture started at: ${result.startedAt}")
        appendLine("Capture detail: ${result.detail}")
        if (result.status == AirPlayTcpCaptureResult.Status.CAPTURED) {
            val packets = result.output.lineSequence().filter { "Flags [" in it }.toList()
            val tcpFlags = packets.map { it.substringAfter("Flags [").substringBefore(']') }
            val syn = tcpFlags.any { 'S' in it && '.' !in it }
            val synAck = tcpFlags.any { 'S' in it && '.' in it }
            val rst = tcpFlags.any { 'R' in it }
            val windowSeconds = Regex("__CAPTURE_WINDOW_SECONDS__=(\\d+)")
                .find(result.output)?.groupValues?.getOrNull(1)
            val exitCode = Regex("__TCPDUMP_EXIT__=(\\d+)")
                .find(result.output)?.groupValues?.getOrNull(1)
            appendLine("Capture window seconds: ${windowSeconds ?: "(unknown)"}")
            appendLine("tcpdump exit code: ${exitCode ?: "(unknown)"}")
            appendLine("Matching TCP packets: ${packets.size}")
            appendLine("SYN observed: $syn")
            appendLine("SYN-ACK observed: $synAck")
            if (packets.isEmpty()) {
                appendLine("Interpretation: no matching SYN/RST packet was seen at the capture point during this bounded window; this does not prove the phone sent none elsewhere.")
            }
            appendLine(if (includeClientAddresses) "Packet headers (addresses included by per-package opt-in):" else "Packet headers (IP endpoints redacted):")
            packets.forEach { packet ->
                appendLine(if (includeClientAddresses) packet else redactPacketEndpoints(packet))
            }
            val diagnostics = result.output.lineSequence()
                .filter { "listening on" in it.lowercase() || "packets captured" in it.lowercase() || "packets received by filter" in it.lowercase() }
                .toList()
            if (diagnostics.isNotEmpty()) {
                appendLine("tcpdump summary:")
                diagnostics.forEach(::appendLine)
            }
        } else if (result.output.isNotBlank()) {
            appendLine("Capture tool output:")
            val output = result.output.take(MAX_OUTPUT_CHARS)
            appendLine(if (includeClientAddresses) output else redactCaptureOutput(output))
        }
    }.trimEnd()

    private fun redactPacketEndpoints(line: String): String {
        val marker = line.indexOf(": Flags [")
        if (marker < 0) return "(packet endpoint redacted)"
        val ipMarkers = listOf(" IP ", " IP6 ").map { line.indexOf(it) }.filter { it >= 0 }
        val ipIndex = ipMarkers.minOrNull()
            ?: return "(timestamp unavailable; packet endpoint redacted) " + line.substring(marker)
        val prefix = line.substring(0, ipIndex)
        val flagsAndTail = line.substring(marker)
        return "$prefix IP (source/destination redacted)$flagsAndTail"
    }

    private fun redactCaptureOutput(text: String): String = text.lineSequence().joinToString("\n") { line ->
        if ("Flags [" in line) redactPacketEndpoints(line) else redactIpv4(line)
    }

    private fun redactIpv4(text: String): String = IPV4.replace(text, "<IPv4 redacted>")

    private val IPV4 = Regex("(?<![0-9.])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?![0-9.])")
    private const val MAX_OUTPUT_CHARS = 8 * 1024
}
