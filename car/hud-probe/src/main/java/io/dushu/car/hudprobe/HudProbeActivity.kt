package io.dushu.car.hudprobe

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 0B 独立探针入口。
 *
 * 只由 adb 显式拉起，不包含 launcher，也不连接 DBPlay 的生产 session。
 */
class HudProbeActivity : Activity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val finalizing = AtomicBoolean(false)
    private lateinit var statusView: TextView
    private var overlayHandle: OverlayProbe.Handle? = null
    private var presentationHandle: PresentationProbe.Handle? = null
    private var windowAttached = false
    private var rendering = false
    private var mode: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        statusView = TextView(this).apply {
            textSize = 22f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.BLACK)
            gravity = Gravity.CENTER
            text = "DBPlay HUD probe\nwaiting for storage permission"
        }
        setContentView(
            statusView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        mode = intent.getStringExtra(EXTRA_MODE).orEmpty()
        if (mode !in SUPPORTED_MODES) {
            failUnknownMode()
            return
        }
        ensureStoragePermissionThenRun()
    }

    private fun ensureStoragePermissionThenRun() {
        val needsLegacyStorageWrite =
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                PackageManager.PERMISSION_GRANTED
        if (needsLegacyStorageWrite) {
            statusView.text = "DBPlay HUD probe\nrequesting legacy storage permission"
            requestPermissions(
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                REQUEST_STORAGE,
            )
        } else {
            startRequestedMode()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_STORAGE) {
            if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
                statusView.text =
                    "DBPlay HUD probe\nlegacy storage denied; app-private JSON will be used"
            }
            startRequestedMode()
        }
    }

    private fun startRequestedMode() {
        when (mode) {
            MODE_OVERLAY -> startOverlay()
            MODE_PRESENTATION -> startPresentation()
            MODE_LOCAL_THREE_LANE -> startCodecProbe(laneCount = 3)
            MODE_CARPLAY_PLUS_ONE -> startCodecProbe(laneCount = 1)
        }
    }

    private fun startOverlay() {
        val displayId = intent.getIntExtra(EXTRA_DISPLAY_ID, DEFAULT_DISPLAY_ID)
        val x = intent.getIntExtra(EXTRA_X, 0)
        val y = intent.getIntExtra(EXTRA_Y, 0)
        val width = intent.getIntExtra(EXTRA_WIDTH, DEFAULT_WIDTH)
        val height = intent.getIntExtra(EXTRA_HEIGHT, DEFAULT_HEIGHT)
        statusView.text = "DBPlay HUD probe\nmode=overlay display=$displayId bounds=$x,$y ${width}x$height"
        runCatching {
            overlayHandle = OverlayProbe(this).show(
                displayId = displayId,
                x = x,
                y = y,
                width = width,
                height = height,
                onFirstDraw = { markRendering() },
            )
            windowAttached = true
            statusView.text = statusView.text.toString() + "\nwindowAttached=true"
            scheduleUiProbeReport(
                host = "overlay",
                displayId = displayId,
                x = x,
                y = y,
                width = width,
                height = height,
                layerTitle = OverlayProbe.LAYER_TITLE,
            )
        }.onFailure { error ->
            writeImmediateFailure("overlay 启动失败: ${error.message}", "overlay", displayId)
        }
    }

    private fun startPresentation() {
        val displayId = intent.getIntExtra(EXTRA_DISPLAY_ID, DEFAULT_DISPLAY_ID)
        val x = intent.getIntExtra(EXTRA_X, 0)
        val y = intent.getIntExtra(EXTRA_Y, 0)
        val width = intent.getIntExtra(EXTRA_WIDTH, DEFAULT_WIDTH)
        val height = intent.getIntExtra(EXTRA_HEIGHT, DEFAULT_HEIGHT)
        statusView.text =
            "DBPlay HUD probe\nmode=presentation display=$displayId bounds=$x,$y ${width}x$height"
        runCatching {
            presentationHandle = PresentationProbe(this).show(
                displayId = displayId,
                x = x,
                y = y,
                width = width,
                height = height,
                onFirstDraw = { markRendering() },
            )
            windowAttached = true
            statusView.text = statusView.text.toString() + "\nwindowAttached=true"
            scheduleUiProbeReport(
                host = "presentation",
                displayId = displayId,
                x = x,
                y = y,
                width = width,
                height = height,
                layerTitle = PresentationProbe.LAYER_TITLE,
            )
        }.onFailure { error ->
            writeImmediateFailure("presentation 启动失败: ${error.message}", "presentation", displayId)
        }
    }

    private fun markRendering() {
        mainHandler.post {
            rendering = true
            statusView.text = statusView.text.toString() + "\nrendering=true"
        }
    }

    private fun scheduleUiProbeReport(
        host: String,
        displayId: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        layerTitle: String,
    ) {
        val durationSeconds = intent.getIntExtra(EXTRA_DURATION_SECONDS, DEFAULT_DURATION_SECONDS)
        mainHandler.postDelayed(
            {
                if (!finalizing.compareAndSet(false, true)) return@postDelayed
                statusView.text = statusView.text.toString() + "\ncapturing 60s report"
                val attached = windowAttached
                val drewFrame = rendering
                worker.execute {
                    val snapshots = ProbeReportWriter.captureSnapshots(this)
                    val report = ProbeReport(
                        mode = mode,
                        displayId = displayId,
                        bounds = "$x,$y ${width}x$height",
                        canDrawOverlays = Settings.canDrawOverlays(this),
                        appops = snapshots.appops,
                        host = host,
                        windowAttached = attached,
                        rendering = drewFrame,
                        sfLayer = layerTitle,
                        displaySummary = displaySummary(displayId),
                        surfaceFlingerSnapshot = snapshots.surfaceFlingerDump,
                        mediaCodecSnapshot = snapshots.mediaCodecDump,
                        mediaCodecInstances = snapshots.mediaCodecInstances,
                    )
                    val writeResult = ProbeReportWriter.write(this, report)
                    runOnUiThread {
                        showWriteResult(writeResult)
                        mainHandler.postDelayed({ closeProbeWindows() }, 2_000L)
                    }
                }
            },
            durationSeconds * 1_000L,
        )
    }

    private fun startCodecProbe(laneCount: Int) {
        if (!finalizing.compareAndSet(false, true)) return
        val asset = intent.getStringExtra(EXTRA_ASSET) ?: DEFAULT_ASSET
        val durationSeconds = intent.getIntExtra(EXTRA_DURATION_SECONDS, DEFAULT_DURATION_SECONDS)
        statusView.text =
            "DBPlay HUD probe\nmode=$mode lanes=$laneCount duration=${durationSeconds}s asset=$asset"
        worker.execute {
            val result = runCatching {
                CodecLaneProbe(this, asset).run(laneCount, durationSeconds)
            }.getOrElse { error ->
                CodecLaneProbe.Result(
                    decoderNames = emptyList(),
                    decodedFps = emptyList(),
                    droppedOut = emptyList(),
                    snapshots = ProbeReportWriter.captureSnapshots(this),
                    errors = listOf(error.message ?: error.javaClass.name),
                )
            }
            val displayId = intent.getIntExtra(EXTRA_DISPLAY_ID, DEFAULT_DISPLAY_ID)
            val report = ProbeReport(
                mode = mode,
                displayId = displayId.takeIf { mode == MODE_CARPLAY_PLUS_ONE },
                bounds = null,
                canDrawOverlays = Settings.canDrawOverlays(this),
                appops = result.snapshots.appops,
                host = "offscreen-image-reader",
                windowAttached = false,
                rendering = result.decodedFps.any { it > 0.0 },
                sfLayer = null,
                decoderNames = result.decoderNames,
                decodedFps = result.decodedFps,
                droppedOut = result.droppedOut,
                displaySummary = displayId.takeIf {
                    mode == MODE_CARPLAY_PLUS_ONE
                }?.let(::displaySummary),
                surfaceFlingerSnapshot = result.snapshots.surfaceFlingerDump,
                mediaCodecSnapshot = result.snapshots.mediaCodecDump,
                mediaCodecInstances = result.snapshots.mediaCodecInstances,
                errors = result.errors,
            )
            val writeResult = ProbeReportWriter.write(this, report)
            runOnUiThread { showWriteResult(writeResult) }
        }
    }

    private fun failUnknownMode() {
        if (!finalizing.compareAndSet(false, true)) return
        val received = mode.ifBlank { "<missing>" }
        statusView.text = "DBPlay HUD probe\nunknown mode=$received"
        worker.execute {
            val snapshots = ProbeReportWriter.captureSnapshots(this)
            val report = ProbeReport(
                mode = received,
                displayId = null,
                bounds = null,
                canDrawOverlays = Settings.canDrawOverlays(this),
                appops = snapshots.appops,
                host = "none",
                windowAttached = false,
                rendering = false,
                sfLayer = null,
                surfaceFlingerSnapshot = snapshots.surfaceFlingerDump,
                mediaCodecSnapshot = snapshots.mediaCodecDump,
                mediaCodecInstances = snapshots.mediaCodecInstances,
                errors = listOf("UNKNOWN_MODE: $received"),
            )
            val writeResult = ProbeReportWriter.write(this, report)
            runOnUiThread { showWriteResult(writeResult) }
        }
    }

    private fun writeImmediateFailure(message: String, host: String, displayId: Int) {
        if (!finalizing.compareAndSet(false, true)) return
        worker.execute {
            val snapshots = ProbeReportWriter.captureSnapshots(this)
            val report = ProbeReport(
                mode = mode,
                displayId = displayId,
                bounds = null,
                canDrawOverlays = Settings.canDrawOverlays(this),
                appops = snapshots.appops,
                host = host,
                windowAttached = false,
                rendering = false,
                sfLayer = null,
                displaySummary = displaySummary(displayId),
                surfaceFlingerSnapshot = snapshots.surfaceFlingerDump,
                mediaCodecSnapshot = snapshots.mediaCodecDump,
                mediaCodecInstances = snapshots.mediaCodecInstances,
                errors = listOf(message),
            )
            val writeResult = ProbeReportWriter.write(this, report)
            runOnUiThread { showWriteResult(writeResult) }
        }
    }

    private fun displaySummary(displayId: Int): String? {
        val display = getSystemService(DisplayManager::class.java)?.getDisplay(displayId)
            ?: return null
        val point = android.graphics.Point()
        @Suppress("DEPRECATION")
        display.getRealSize(point)
        val context = createDisplayContext(display)
        return buildString {
            append("id=${display.displayId}")
            append(" name=${display.name}")
            append(" size=${point.x}x${point.y}")
            append(" dpi=${context.resources.displayMetrics.densityDpi}")
            append(" refresh=${display.refreshRate}")
            append(" flags=0x${Integer.toHexString(display.flags)}")
            append(" state=${display.state}")
        }
    }

    private fun showWriteResult(writeResult: ProbeWriteResult) {
        val text = buildString {
            appendLine("DBPlay HUD probe complete")
            appendLine("mode=$mode")
            appendLine("result=${writeResult.resultPath ?: "<failed>"}")
            appendLine("json=${writeResult.jsonPath}")
            if (writeResult.errors.isNotEmpty()) {
                appendLine("errors=${writeResult.errors.joinToString(" | ")}")
            }
            appendLine("limitation=${ProbeReportWriter.LIMITATION}")
        }
        statusView.text = text
    }

    private fun closeProbeWindows() {
        overlayHandle?.close()
        overlayHandle = null
        presentationHandle?.close()
        presentationHandle = null
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        closeProbeWindows()
        worker.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_DISPLAY_ID = "displayId"
        private const val EXTRA_X = "x"
        private const val EXTRA_Y = "y"
        private const val EXTRA_WIDTH = "width"
        private const val EXTRA_HEIGHT = "height"
        private const val EXTRA_ASSET = "asset"
        private const val EXTRA_DURATION_SECONDS = "durationSeconds"
        private const val MODE_OVERLAY = "overlay"
        private const val MODE_PRESENTATION = "presentation"
        private const val MODE_LOCAL_THREE_LANE = "local_three_lane"
        private const val MODE_CARPLAY_PLUS_ONE = "carplay_plus_one"
        private val SUPPORTED_MODES = setOf(
            MODE_OVERLAY,
            MODE_PRESENTATION,
            MODE_LOCAL_THREE_LANE,
            MODE_CARPLAY_PLUS_ONE,
        )
        private const val DEFAULT_DISPLAY_ID = 3
        private const val DEFAULT_WIDTH = 1024
        private const val DEFAULT_HEIGHT = 274
        private const val DEFAULT_ASSET = "probe_720p.h264"
        private const val DEFAULT_DURATION_SECONDS = 60
        private const val REQUEST_STORAGE = 1001
    }
}
