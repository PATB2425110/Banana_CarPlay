package io.dushu.car.hudprobe

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

internal data class ProbeReport(
    val mode: String,
    val displayId: Int?,
    val bounds: String?,
    val canDrawOverlays: Boolean,
    val appops: String,
    val host: String,
    val windowAttached: Boolean,
    val rendering: Boolean,
    val visibleOnHud: String = "UNCONFIRMED",
    val sfLayer: String?,
    val decoderNames: List<String> = emptyList(),
    val decodedFps: List<Double> = emptyList(),
    val droppedOut: List<Int> = emptyList(),
    val displaySummary: String? = null,
    val surfaceFlingerSnapshot: String = "",
    val mediaCodecSnapshot: String = "",
    val mediaCodecInstances: Int = 0,
    val errors: List<String> = emptyList(),
)

internal data class ProbeWriteResult(
    val resultPath: String?,
    val jsonPath: String,
    val errors: List<String>,
)

internal object ProbeReportWriter {
    const val LIMITATION =
        "0B_PROBE_DOES_NOT_MEASURE_REAL_CARPLAY_FPS_UNDERRUN_OR_SAME_PACKAGE_POLICY"

    fun write(context: Context, report: ProbeReport): ProbeWriteResult {
        val errors = report.errors.toMutableList()
        val jsonText = toJson(report).toString(2)
        val appDir = context.getExternalFilesDir(null) ?: context.filesDir
        if (!appDir.exists() && !appDir.mkdirs()) {
            errors += "无法创建报告目录: ${appDir.absolutePath}"
        }
        val jsonFile = File(appDir, "probe_report.json")
        runCatching { jsonFile.writeText(jsonText) }
            .onFailure { errors += "写 JSON 失败: ${it.message}" }

        val publicResult = File("/sdcard/hud_probe_result.txt")
        val resultPath = runCatching {
            publicResult.writeText(toText(report, jsonFile.absolutePath))
            publicResult.absolutePath
        }.onFailure {
            errors += "写 ${publicResult.absolutePath} 失败: ${it.message}"
        }.getOrNull()

        return ProbeWriteResult(
            resultPath = resultPath,
            jsonPath = jsonFile.absolutePath,
            errors = errors,
        )
    }

    fun captureSnapshots(context: Context): CommandSnapshot {
        val surfaceFlinger = runCommand("/system/bin/dumpsys", "SurfaceFlinger", "--list")
        val mediaCodec = runCommand("/system/bin/dumpsys", "media.codec")
        val appops = runCommand(
            "/system/bin/appops",
            "get",
            "--user",
            "0",
            context.packageName,
            "SYSTEM_ALERT_WINDOW",
        )
        return CommandSnapshot(
            surfaceFlingerDump = renderCommand(surfaceFlinger),
            mediaCodecDump = renderCommand(mediaCodec),
            appops = renderCommand(appops),
            mediaCodecInstances = countCodecInstances(mediaCodec.stdout),
        )
    }

    private fun toJson(report: ProbeReport): JSONObject = JSONObject().apply {
        put("probePackage", "io.dushu.car.hudprobe")
        put("mode", report.mode)
        put("displayId", report.displayId ?: JSONObject.NULL)
        put("bounds", report.bounds ?: JSONObject.NULL)
        put("displaySize", report.displaySummary ?: JSONObject.NULL)
        put("canDrawOverlays", report.canDrawOverlays)
        put("appops", report.appops)
        put("host", report.host)
        put("windowAttached", report.windowAttached)
        put("rendering", report.rendering)
        put("visibleOnHud", report.visibleOnHud)
        put("sfLayer", report.sfLayer ?: JSONObject.NULL)
        put("decoderNames", JSONArray(report.decoderNames))
        put("decodedFps", JSONArray(report.decodedFps))
        put("droppedOut", JSONArray(report.droppedOut))
        put("mediaCodecInstances", report.mediaCodecInstances)
        put("errors", JSONArray(report.errors))
        put("limitation", LIMITATION)
        put("surfaceFlingerSnapshot", report.surfaceFlingerSnapshot)
        put("mediaCodecSnapshot", report.mediaCodecSnapshot)
    }

    private fun toText(report: ProbeReport, jsonPath: String): String = buildString {
        appendLine("DBPlay HUD probe result")
        appendLine("mode=${report.mode}")
        appendLine("displayId=${report.displayId}")
        appendLine("bounds=${report.bounds}")
        appendLine("displaySize=${report.displaySummary}")
        appendLine("canDrawOverlays=${report.canDrawOverlays}")
        appendLine("appops=${report.appops}")
        appendLine("host=${report.host}")
        appendLine("windowAttached=${report.windowAttached}")
        appendLine("rendering=${report.rendering}")
        appendLine("visibleOnHud=${report.visibleOnHud}")
        appendLine("sfLayer=${report.sfLayer}")
        appendLine("decoderNames=${report.decoderNames}")
        appendLine("decodedFps=${report.decodedFps}")
        appendLine("droppedOut=${report.droppedOut}")
        appendLine("mediaCodecInstances=${report.mediaCodecInstances}")
        appendLine("json=$jsonPath")
        appendLine("limitation=$LIMITATION")
        if (report.errors.isNotEmpty()) {
            appendLine("errors=${report.errors.joinToString(" | ")}")
        }
        appendLine()
        appendLine("===== dumpsys SurfaceFlinger --list =====")
        appendLine(report.surfaceFlingerSnapshot)
        appendLine()
        appendLine("===== dumpsys media.codec =====")
        appendLine(report.mediaCodecSnapshot)
    }

    private fun countCodecInstances(dump: String): Int =
        Regex("""(?i)\bMediaCodec\[[^\]]+]""").findAll(dump).count()

    private fun runCommand(vararg command: String): CommandResult {
        return runCatching {
            val process = ProcessBuilder(*command)
                .redirectErrorStream(false)
                .start()
            val stdout = process.inputStream.bufferedReader().use { it.readText() }
            val stderr = process.errorStream.bufferedReader().use { it.readText() }
            val finished = process.waitFor(8, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                CommandResult(
                    command.joinToString(" "),
                    -1,
                    stdout,
                    "$stderr\ncommand timeout",
                )
            } else {
                CommandResult(command.joinToString(" "), process.exitValue(), stdout, stderr)
            }
        }.getOrElse {
            CommandResult(command.joinToString(" "), -1, "", it.message ?: it.javaClass.name)
        }
    }

    private fun renderCommand(result: CommandResult): String = buildString {
        appendLine("$ ${result.command}")
        appendLine("exit=${result.exitCode}")
        if (result.stderr.isNotBlank()) {
            appendLine("stderr=${result.stderr.trim()}")
        }
        append(result.stdout)
    }

    private data class CommandResult(
        val command: String,
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )
}

internal data class CommandSnapshot(
    val surfaceFlingerDump: String,
    val mediaCodecDump: String,
    val appops: String,
    val mediaCodecInstances: Int,
)
