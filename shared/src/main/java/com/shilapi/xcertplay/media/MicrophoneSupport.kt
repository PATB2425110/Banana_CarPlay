package com.shilapi.xcertplay.media

import android.content.Context
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

internal const val MICROPHONE_CAPTURE_RATE_HZ = 16_000

/** 与 `MicrophoneUplink` 同一个标签，方便两处日志挨着看（PATCH-0007 新增）。 */
private const val TAG = "xcertplay-usb"

internal fun openMicrophoneRecorder(
    requestedBufferBytes: Int,
    source: Int = MediaRecorder.AudioSource.MIC,
): AudioRecord {
    val minBuffer = AudioRecord.getMinBufferSize(
        MICROPHONE_CAPTURE_RATE_HZ,
        AndroidAudioFormat.CHANNEL_IN_MONO,
        AndroidAudioFormat.ENCODING_PCM_16BIT,
    )
    check(minBuffer > 0) { "microphone unavailable at $MICROPHONE_CAPTURE_RATE_HZ Hz" }
    val recorder = AudioRecord.Builder()
        .setAudioSource(source)
        .setAudioFormat(
            AndroidAudioFormat.Builder()
                .setEncoding(AndroidAudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(MICROPHONE_CAPTURE_RATE_HZ)
                .setChannelMask(AndroidAudioFormat.CHANNEL_IN_MONO)
                .build(),
        )
        .setBufferSizeInBytes(maxOf(minBuffer * 2, requestedBufferBytes))
        .build()
    if (recorder.state != AudioRecord.STATE_INITIALIZED) {
        recorder.release()
        error("microphone recorder failed to initialize")
    }
    return recorder
}

/** telephony→VOICE_COMMUNICATION；speechrecognition→VOICE_RECOGNITION；其余（含 null/空/大小写混杂）→MIC。 */
internal fun chooseMicrophoneSource(audioType: String?): Int =
    when (audioType?.trim()?.lowercase(java.util.Locale.ROOT)) {
        "telephony" -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
        "speechrecognition" -> MediaRecorder.AudioSource.VOICE_RECOGNITION
        else -> MediaRecorder.AudioSource.MIC
    }

/** 只有 telephony 才挂 AEC/NS。 */
internal fun shouldAttachVoiceEffects(audioType: String?): Boolean =
    audioType?.trim()?.lowercase(java.util.Locale.ROOT) == "telephony"

object MicrophoneGain {
    const val MIN_PERCENT = 80
    const val MAX_PERCENT = 200
    const val DEFAULT_PERCENT = 100
    const val STEP_PERCENT = 10

    fun sanitize(percent: Int): Int =
        percent.coerceIn(MIN_PERCENT, MAX_PERCENT)

    internal fun applyPcm16InPlace(buffer: ByteArray, count: Int, percent: Int): Int {
        require(count in 0..buffer.size)
        val gain = sanitize(percent)
        var peak = 0
        var offset = 0
        while (offset + 1 < count) {
            val sample = (
                (buffer[offset].toInt() and 0xff) or
                    (buffer[offset + 1].toInt() shl 8)
                ).toShort().toInt()
            val amplified =
                (sample * gain / 100).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            buffer[offset] = amplified.toByte()
            buffer[offset + 1] = (amplified ushr 8).toByte()
            peak = maxOf(peak, kotlin.math.abs(amplified))
            offset += 2
        }
        return peak
    }

    internal fun peakPercent(peak: Int): Int =
        (peak.coerceIn(0, 32_768) * 100 / 32_768).coerceIn(0, 100)
}

/** Keeps communication mode active until every microphone recorder has closed. */
internal class AudioModeLeaseManager(
    private val readMode: () -> Int,
    private val writeMode: (Int) -> Unit,
    private val communicationMode: Int,
    /**
     * 拿不到通信模式时的上报点（DBPlay PATCH-0007 新增）。
     *
     * 做成回调而不是在这里直接 `Log.w`：本类**刻意不依赖任何 Android 运行时**，
     * 单元测试才能用一个假 holder 把"设备接受 mode"和"车机钉死 mode"两侧都跑出来。
     */
    private val onCommunicationModeUnavailable: (Int) -> Unit = {},
) {
    private var users = 0
    private var previousMode = 0
    private var changedMode = false

    @Synchronized
    fun acquire(): Closeable {
        if (users == 0) {
            previousMode = readMode()
            changedMode = previousMode != communicationMode
            if (changedMode) writeMode(communicationMode)
            // DBPlay PATCH-0007 —— 上游原文是 `check(readMode() == communicationMode)`，
            // 读回失败直接抛 IllegalStateException，整个麦克风上行就没了。
            //
            // 但部分车机（实测 Android 9 / Flyme Auto，QUALCOMM CS11）的音频策略把 mode
            // **钉死**：`setMode` 既不报错也不生效，于是这条读回校验必然失败。现场表现是
            // 每次 iPhone 请求语音输入（AirPlay `SETUP type=100 audioType=speechRecognition`）
            // 都打一行 `microphone start failed`，Siri 与语音输入在车上完全不可用。
            //
            // 通信模式只是"更好"（通话路由 + AEC），`AudioRecord(AudioSource.MIC)` 本身
            // **并不要求**它；拿不到就继续开录音，让上层自己去判断有没有声音。
            // 详见 PATCHES.md 的 PATCH-0007。
            val activeMode = readMode()
            if (activeMode != communicationMode) onCommunicationModeUnavailable(activeMode)
        }
        users++
        val closed = AtomicBoolean(false)
        return Closeable {
            if (closed.compareAndSet(false, true)) release()
        }
    }

    @Synchronized
    private fun release() {
        check(users > 0)
        users--
        if (users == 0 && changedMode && readMode() == communicationMode) {
            writeMode(previousMode)
        }
    }
}

internal object MicrophoneAudioMode {
    private var leases: AudioModeLeaseManager? = null

    /**
     * 进程级「已经提示过音频模式被钉死」标记（2026-10-08 新增）。
     *
     * 现场这台车（QUALCOMM CS11 / Android 9）的音频策略把 `AudioManager.mode` 钉死，
     * `setMode(MODE_IN_COMMUNICATION)` 不生效，于是每次麦克风启动都打一行：
     *
     *   W xcertplay-usb: communication audio mode unavailable (mode=0); continuing
     *
     * 该行为自 PATCH-0007 起已被**有意容忍**（拿不到通话模式也继续开录音）。
     * 但一行「已知且已被正确处理」的警告反复出现，会在日志里淹没真正的故障信号
     * —— 本轮排查时就靠人眼过滤了 10 行。这里改为**每个进程只如实提示一次**。
     */
    private val communicationModeWarningIssued = AtomicBoolean(false)

    @Synchronized
    fun acquire(context: Context): Closeable {
        val manager = leases ?: run {
            val audioManager =
                context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            AudioModeLeaseManager(
                readMode = { audioManager.mode },
                writeMode = { audioManager.mode = it },
                communicationMode = AudioManager.MODE_IN_COMMUNICATION,
                onCommunicationModeUnavailable = { actualMode ->
                    // 车机把 mode 钉死时只警告、不阻断（PATCH-0007）。
                    // 这条日志是现场判断"Siri 没声音是不是这个原因"的唯一线索；
                    // 保留首次提示即可（后续重复出现不携带新信息，只增加噪音）。
                    if (communicationModeWarningIssued.compareAndSet(false, true)) {
                        Log.w(
                            TAG,
                            "communication audio mode unavailable (mode=$actualMode); continuing" +
                                "（本机音频策略钉死 mode，仅提示一次；麦克风继续工作）",
                        )
                    }
                },
            ).also { leases = it }
        }
        return manager.acquire()
    }
}

/** Records the same stream used by CarPlay and reports the amplified peak every ~100 ms. */
class MicrophoneLevelMonitor(
    context: Context,
    private val gainPercent: () -> Int,
    private val onPeakPercent: (Int) -> Unit,
    private val onStopped: (Throwable?) -> Unit,
) : Closeable {
    private val appContext = context.applicationContext
    private val running = AtomicBoolean(false)
    @Volatile private var recorder: AudioRecord? = null
    private var thread: Thread? = null

    val isRunning: Boolean get() = running.get()

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true
        return try {
            thread = Thread(::capture, "microphone-level-test").apply {
                isDaemon = true
                start()
            }
            true
        } catch (error: Exception) {
            running.set(false)
            onStopped(error)
            false
        }
    }

    private fun capture() {
        var failure: Throwable? = null
        var audioModeLease: Closeable? = null
        var activeRecorder: AudioRecord? = null
        try {
            audioModeLease = MicrophoneAudioMode.acquire(appContext)
            val frameBytes =
                MICROPHONE_CAPTURE_RATE_HZ * BYTES_PER_SAMPLE * REPORT_MILLIS / 1000
            activeRecorder = openMicrophoneRecorder(frameBytes * 2)
            recorder = activeRecorder
            if (!running.get()) return
            activeRecorder.startRecording()
            val buffer = ByteArray(frameBytes)
            while (running.get()) {
                val count =
                    activeRecorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (count < 0) error("microphone level read failed code=$count")
                if (count == 0) continue
                val peak =
                    MicrophoneGain.applyPcm16InPlace(buffer, count, gainPercent())
                onPeakPercent(MicrophoneGain.peakPercent(peak))
            }
        } catch (error: Exception) {
            if (running.get()) failure = error
        } finally {
            running.set(false)
            recorder = null
            try {
                activeRecorder?.stop()
            } catch (_: Exception) {
                // The recorder may already have been stopped by close().
            }
            try {
                activeRecorder?.release()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                audioModeLease?.close()
            } catch (_: Exception) {
                // Best effort.
            }
            onStopped(failure)
        }
    }

    override fun close() {
        running.set(false)
        try {
            recorder?.stop()
        } catch (_: Exception) {
            // Best effort; the worker releases the recorder.
        }
        thread?.let { worker ->
            try {
                worker.join(CLOSE_JOIN_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (worker.isAlive) worker.interrupt()
        }
    }

    private companion object {
        const val BYTES_PER_SAMPLE = 2
        const val REPORT_MILLIS = 100
        const val CLOSE_JOIN_MILLIS = 500L
    }
}

internal fun expandMonoPcm(input: ByteArray, channels: Int): ByteArray {
    require(channels > 0 && input.size % 2 == 0)
    if (channels == 1) return input
    return ByteArray(input.size * channels).also { output ->
        for (sample in 0 until input.size / 2) {
            for (channel in 0 until channels) {
                val outputOffset = (sample * channels + channel) * 2
                output[outputOffset] = input[sample * 2]
                output[outputOffset + 1] = input[sample * 2 + 1]
            }
        }
    }
}

/** Streaming mono PCM16 resampler used between fixed Android capture and negotiated output. */
internal class PcmMonoResampler(
    private val inputRate: Int,
    private val outputRate: Int,
) {
    private var inputSamples = 0L
    private var nextOutputPosition = 0L
    private var previousSample = 0
    private var pendingLowByte = -1

    init {
        require(inputRate > 0 && outputRate > 0)
    }

    fun convert(input: ByteArray, offset: Int, count: Int): ByteArray {
        require(offset >= 0 && count >= 0 && offset + count <= input.size)
        val output = ByteArrayOutputStream(count * outputRate / inputRate + 4)
        var cursor = offset
        val end = offset + count
        if (pendingLowByte >= 0 && cursor < end) {
            writeSample(pendingLowByte or (input[cursor].toInt() shl 8), output)
            pendingLowByte = -1
            cursor++
        }
        while (cursor + 1 < end) {
            val sample =
                (input[cursor].toInt() and 0xff) or (input[cursor + 1].toInt() shl 8)
            writeSample(sample, output)
            cursor += 2
        }
        if (cursor < end) pendingLowByte = input[cursor].toInt() and 0xff
        return output.toByteArray()
    }

    private fun writeSample(rawSample: Int, output: ByteArrayOutputStream) {
        val sample = rawSample.toShort().toInt()
        val currentPosition = inputSamples * outputRate
        while (nextOutputPosition <= currentPosition) {
            val leftIndex = nextOutputPosition / outputRate
            val fraction = nextOutputPosition % outputRate
            val value = if (leftIndex == inputSamples || inputSamples == 0L) {
                sample
            } else {
                (
                    previousSample.toLong() * (outputRate - fraction) +
                        sample.toLong() * fraction
                ).div(outputRate).toInt()
            }
            output.write(value and 0xff)
            output.write((value ushr 8) and 0xff)
            nextOutputPosition += inputRate
        }
        previousSample = sample
        inputSamples++
    }
}
