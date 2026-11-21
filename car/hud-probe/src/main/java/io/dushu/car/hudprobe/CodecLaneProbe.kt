package io.dushu.car.hudprobe

import android.content.Context
import android.graphics.PixelFormat
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 独立本地 H.264 解码压力探针。
 *
 * 三路各自持有独立的 [MediaCodec] 与独立 [ImageReader] Surface。当前独立探针
 * 包无法取得 DBPlay 进程私有 Surface，因此这里证明的是实例容量与并发稳定性，
 * 不冒充真实 CarPlay mirror 验收。
 */
internal class CodecLaneProbe(
    private val context: Context,
    private val assetName: String,
) {
    data class Result(
        val decoderNames: List<String>,
        val decodedFps: List<Double>,
        val droppedOut: List<Int>,
        val snapshots: CommandSnapshot,
        val errors: List<String>,
    )

    fun run(laneCount: Int, durationSeconds: Int): Result {
        require(laneCount > 0) { "laneCount must be > 0" }
        require(durationSeconds > 0) { "durationSeconds must be > 0" }
        val nals = runCatching { readAnnexBNals() }
            .getOrElse { return failed("读取 asset 失败: ${it.message}") }
        if (nals.isEmpty()) return failed("asset 中没有 Annex-B NAL")

        val errors = mutableListOf<String>()
        val lanes = (1..laneCount).mapNotNull { index ->
            runCatching { DecodeLane(index, nals) }
                .onSuccess {
                    runCatching {
                        it.prepare()
                        it.start()
                    }.onFailure { error -> errors += "lane $index 启动失败: ${error.message}" }
                    if (it.started.get()) it else null
                }
                .onFailure { error ->
                    errors += "lane $index 创建失败: ${error.message}"
                    null
                }
                .getOrNull()
        }

        if (lanes.isEmpty()) {
            return failed(
                errors.joinToString(" | ").ifBlank { "没有成功启动的 decoder" },
            )
        }

        val executor = Executors.newFixedThreadPool(lanes.size)
        val startedAt = SystemClock.elapsedRealtime()
        val deadlineAt = startedAt + durationSeconds * 1_000L
        val futures = lanes.map { lane ->
            executor.submit {
                runCatching { lane.decodeLoop(deadlineAt) }
                    .onFailure { error -> lane.errors += error.message ?: error.javaClass.name }
            }
        }

        val snapshotAt = (deadlineAt - 1_500L).coerceAtLeast(SystemClock.elapsedRealtime())
        val waitMs = (snapshotAt - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        if (waitMs > 0) Thread.sleep(waitMs)
        val snapshots = ProbeReportWriter.captureSnapshots(context)

        futures.forEach { future ->
            runCatching { future.get(8, TimeUnit.SECONDS) }
                .onFailure { errors += "等待 decoder 结束失败: ${it.message}" }
        }
        executor.shutdownNow()

        lanes.forEach { lane ->
            errors += lane.errors
            runCatching { lane.release() }
                .onFailure { errors += "lane ${lane.index} 释放失败: ${it.message}" }
        }

        val elapsedSeconds = (
            (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(1L)
            ) / 1000.0
        return Result(
            decoderNames = lanes.map { it.codecName },
            decodedFps = lanes.map { it.decodedFrames.get() / elapsedSeconds },
            droppedOut = lanes.map { it.droppedOut.get() },
            snapshots = snapshots,
            errors = errors,
        )
    }

    private fun failed(message: String) = Result(
        decoderNames = emptyList(),
        decodedFps = emptyList(),
        droppedOut = emptyList(),
        snapshots = ProbeReportWriter.captureSnapshots(context),
        errors = listOf(message),
    )

    private fun readAnnexBNals(): List<ByteArray> {
        val bytes = context.assets.open(assetName).use { input ->
            ByteArrayOutputStream().use { output ->
                input.copyTo(output)
                output.toByteArray()
            }
        }
        val starts = buildList {
            var index = 0
            while (index + 3 < bytes.size) {
                when {
                    bytes[index] == 0.toByte() &&
                        bytes[index + 1] == 0.toByte() &&
                        bytes[index + 2] == 1.toByte() -> {
                        add(index to 3)
                        index += 3
                    }

                    index + 4 < bytes.size &&
                        bytes[index] == 0.toByte() &&
                        bytes[index + 1] == 0.toByte() &&
                        bytes[index + 2] == 0.toByte() &&
                        bytes[index + 3] == 1.toByte() -> {
                        add(index to 4)
                        index += 4
                    }

                    else -> index++
                }
            }
        }
        return starts.mapIndexedNotNull { position, (start, prefixLength) ->
            val payloadStart = start + prefixLength
            val payloadEnd = starts.getOrNull(position + 1)?.first ?: bytes.size
            if (payloadStart >= payloadEnd) {
                null
            } else {
                bytes.copyOfRange(payloadStart, payloadEnd)
            }
        }
    }

    private class DecodeLane(
        val index: Int,
        private val nals: List<ByteArray>,
    ) {
        private val width = 1280
        private val height = 720
        private val imageReader = ImageReader.newInstance(
            width,
            height,
            PixelFormat.RGBA_8888,
            3,
        )
        private val mediaCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val codecName: String = mediaCodec.name
        val started = AtomicBoolean(false)
        val decodedFrames = AtomicInteger(0)
        val droppedOut = AtomicInteger(0)
        val errors = mutableListOf<String>()
        private var frameIndex = 0L

        init {
            imageReader.setOnImageAvailableListener(
                { reader ->
                    runCatching { reader.acquireLatestImage()?.close() }
                },
                null,
            )
        }

        fun prepare() {
            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                width,
                height,
            ).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
                )
            }
            mediaCodec.configure(format, imageReader.surface, null, 0)
        }

        fun start() {
            mediaCodec.start()
            started.set(true)
        }

        fun decodeLoop(deadlineAt: Long) {
            var nextFrameNanos = System.nanoTime()
            while (SystemClock.elapsedRealtime() < deadlineAt) {
                var vclInLoop = false
                for (nal in nals) {
                    val type = nal.firstOrNull()?.toInt()?.and(0x1f) ?: continue
                    val isVcl = type == 1 || type == 5
                    val flags = when (type) {
                        7, 8 -> MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                        5 -> MediaCodec.BUFFER_FLAG_KEY_FRAME
                        else -> 0
                    }
                    queueNal(nal, type, flags)
                    drainOutput()
                    if (isVcl) {
                        vclInLoop = true
                        nextFrameNanos += FRAME_INTERVAL_NANOS
                        val sleepNanos = nextFrameNanos - System.nanoTime()
                        if (sleepNanos > 0L) {
                            TimeUnit.NANOSECONDS.sleep(sleepNanos)
                        } else {
                            nextFrameNanos = System.nanoTime()
                        }
                    }
                }
                if (!vclInLoop) {
                    errors += "asset 不含 VCL NAL"
                    return
                }
                drainOutput()
            }
            drainOutput()
        }

        private fun queueNal(nal: ByteArray, nalType: Int, flags: Int) {
            var attempts = 0
            while (attempts++ < 50) {
                val inputIndex = mediaCodec.dequeueInputBuffer(INPUT_TIMEOUT_US)
                if (inputIndex >= 0) {
                    val buffer = mediaCodec.getInputBuffer(inputIndex) ?: return
                    buffer.clear()
                    buffer.put(nal)
                    val ptsUs = if (nalType == 1 || nalType == 5) {
                        frameIndex++ * FRAME_INTERVAL_US
                    } else {
                        frameIndex * FRAME_INTERVAL_US
                    }
                    mediaCodec.queueInputBuffer(inputIndex, 0, nal.size, ptsUs, flags)
                    return
                }
                Thread.sleep(2)
            }
            droppedOut.incrementAndGet()
        }

        private fun drainOutput() {
            val info = MediaCodec.BufferInfo()
            while (true) {
                when (val outputIndex = mediaCodec.dequeueOutputBuffer(info, 0)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> return
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> if (outputIndex >= 0) {
                        val render = info.size > 0
                        mediaCodec.releaseOutputBuffer(outputIndex, render)
                        if (render) decodedFrames.incrementAndGet()
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            return
                        }
                    } else {
                        return
                    }
                }
            }
        }

        fun release() {
            runCatching { mediaCodec.stop() }
            runCatching { mediaCodec.release() }
            runCatching { imageReader.close() }
            started.set(false)
        }

        companion object {
            const val INPUT_TIMEOUT_US = 10_000L
            const val FRAME_INTERVAL_US = 33_333L
            const val FRAME_INTERVAL_NANOS = 33_333_333L
        }
    }

    private companion object {
        const val INPUT_TIMEOUT_US = 10_000L
        const val FRAME_INTERVAL_US = 33_333L
        const val FRAME_INTERVAL_NANOS = 33_333_333L
    }
}
