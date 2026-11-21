package com.shilapi.xcertplay.media

import android.media.MediaCodec
import android.media.MediaFormat
import java.io.Closeable
import org.concentus.OpusApplication
import org.concentus.OpusEncoder as ConcentusEncoder

internal interface OpusBackend : Closeable {
    val name: String
    fun encode(pcm: ByteArray): List<ByteArray>
}

/** Uses Android's Opus encoder when available, with a software fallback for older devices. */
internal class OpusEncoder(
    private val sampleRate: Int,
    private val bitrate: Int,
    systemFactory: (Int, Int) -> OpusBackend = ::MediaCodecOpusBackend,
    private val softwareFactory: (Int, Int) -> OpusBackend = ::SoftwareOpusBackend,
    private val onFallback: (String) -> Unit = {},
) : Closeable {
    private val pending = ArrayDeque<ByteArray>()
    private var closed = false
    private var backend: OpusBackend = run {
        // ---------------------------------------------------------------- 2026-10-08 改
        //
        // 现场（VIN L6TTEST1111111112 / QUALCOMM CS11 / Android 9）日志：
        //
        //   W xcertplay-usb: Opus encoder fallback: system encoder unavailable:
        //                    Failed to initialize audio/opus, error 0xfffffffe
        //
        // 该车机的 `MediaFormat.MIMETYPE_AUDIO_OPUS` 系统编码器根本不存在
        // （errno -2 / 0xfffffffe = ENOENT），所以**每一次**建链都要：
        //   ① 走一遍 MediaCodec.createEncoderByType → configure → start（必然失败）；
        //   ② 打一行 W；③ 才拿到软件（Concentus）后端。
        //
        // 结论：这不是缺陷，是设计内的兜底（软件 Opus 照常出声）。但「同一个进程里
        // 反复试一个已知不存在的系统编码器」既浪费一次 MediaCodec 创建（现场约百毫秒级），
        // 又每次刷一行 W 掩盖真信号。这里在**进程级**记住结论，后续实例直接走软件后端。
        //
        // ⚠️ 只在「系统编码器不存在/配置失败」时记忆（见 systemBackendKnownUnavailable）。
        // 它一旦为 true 就**不再重试**，因此不能用于「暂时性失败」——后者仍应每次重试。
        if (systemBackendKnownUnavailable) {
            softwareFactory(sampleRate, bitrate)
        } else {
            try {
                systemFactory(sampleRate, bitrate)
            } catch (error: Exception) {
                systemBackendKnownUnavailable = true
                onFallback("system encoder unavailable: ${error.message ?: error.javaClass.simpleName}")
                softwareFactory(sampleRate, bitrate)
            }
        }
    }

    val available: Boolean get() = !closed
    val backendName: String get() = backend.name

    @Synchronized
    fun encode(pcm: ByteArray): List<ByteArray> {
        if (closed) return emptyList()
        if (backend.name == SOFTWARE_BACKEND) return backend.encode(pcm)

        pending.addLast(pcm.copyOf())
        val packets = try {
            backend.encode(pcm)
        } catch (error: Exception) {
            return useSoftware("system encoder failed: ${error.message ?: error.javaClass.simpleName}")
        }
        repeat(packets.size) {
            if (pending.isNotEmpty()) pending.removeFirst()
        }
        if (pending.size >= MAX_PENDING_FRAMES) {
            return packets + useSoftware("system encoder produced no timely output")
        }
        return packets
    }

    private fun useSoftware(reason: String): List<ByteArray> {
        try {
            backend.close()
        } catch (_: Exception) {
            // A failed system codec may also fail while closing.
        }
        backend = softwareFactory(sampleRate, bitrate)
        onFallback(reason)
        return pending.flatMap(backend::encode).also { pending.clear() }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        pending.clear()
        backend.close()
    }

    internal companion object {
        const val SOFTWARE_BACKEND = "software"
        const val MAX_PENDING_FRAMES = 4

        /**
         * 进程级记忆：本机的系统 Opus 编码器是否**确定不可用**（2026-10-08 新增）。
         *
         * 只在 `systemFactory` 抛异常时置 true —— 现场车机 `MIMETYPE_AUDIO_OPUS`
         * 是 ENOENT（`error 0xfffffffe`），属于「这台机器就没有」，重试一万次也一样。
         * 置 true 后本进程内所有 `OpusEncoder` 实例直接走软件后端。
         *
         * 首次仍会真实尝试一次并如实记一行 W（保留「这台车没有系统 Opus」的可观测性），
         * 之后不再刷屏、不再白花一次 MediaCodec 创建。
         */
        @Volatile
        private var systemBackendKnownUnavailable = false

        /** 仅给单测用：把进程级记忆复位，避免用例互相污染。 */
        internal fun resetSystemBackendAvailabilityForTest() {
            systemBackendKnownUnavailable = false
        }
    }
}

internal class MediaCodecOpusBackend(sampleRate: Int, bitrate: Int) : OpusBackend {
    private val codec = createCodec(sampleRate, bitrate)
    private val bufferInfo = MediaCodec.BufferInfo()
    private var presentationTimeUs = 0L
    private var closed = false

    override val name: String = "system"

    override fun encode(pcm: ByteArray): List<ByteArray> {
        check(!closed) { "Opus system encoder is closed" }
        val inputIndex = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
        check(inputIndex >= 0) { "Opus system encoder has no input buffer" }
        val input = codec.getInputBuffer(inputIndex)
            ?: error("Opus system encoder returned a null input buffer")
        input.clear()
        check(pcm.size <= input.remaining()) { "Opus system encoder input buffer is too small" }
        input.put(pcm)
        codec.queueInputBuffer(inputIndex, 0, pcm.size, presentationTimeUs, 0)
        presentationTimeUs += FRAME_DURATION_US

        val packets = ArrayList<ByteArray>()
        while (true) {
            val index = codec.dequeueOutputBuffer(bufferInfo, 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return packets
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> continue
                index >= 0 -> {
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 &&
                        bufferInfo.size > 0
                    ) {
                        val output = codec.getOutputBuffer(index)
                            ?: error("Opus system encoder returned a null output buffer")
                        output.position(bufferInfo.offset)
                        output.limit(bufferInfo.offset + bufferInfo.size)
                        packets += ByteArray(bufferInfo.size).also(output::get)
                    }
                    codec.releaseOutputBuffer(index, false)
                }
                else -> return packets
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            codec.stop()
        } catch (_: Exception) {
            // A failed codec may already be stopped.
        }
        try {
            codec.release()
        } catch (_: Exception) {
            // Best effort.
        }
    }

    private companion object {
        const val INPUT_TIMEOUT_US = 10_000L
        const val FRAME_DURATION_US = 20_000L
        const val MAX_INPUT_BYTES = 4_096

        fun createCodec(sampleRate: Int, bitrate: Int): MediaCodec {
            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_OPUS,
                sampleRate,
                1,
            ).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_BYTES)
            }
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
            try {
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
                return codec
            } catch (error: Exception) {
                try {
                    codec.release()
                } catch (_: Exception) {
                    // Preserve the configuration failure that triggered the fallback.
                }
                throw error
            }
        }
    }
}

/** Encodes one 20 ms mono PCM frame into a raw Opus packet. */
internal class SoftwareOpusBackend(private val sampleRate: Int, bitrate: Int) : OpusBackend {
    private val encoder = ConcentusEncoder(
        sampleRate,
        1,
        OpusApplication.OPUS_APPLICATION_VOIP,
    ).apply {
        setBitrate(bitrate)
        setComplexity(2)
        setUseDTX(false)
    }

    override val name: String = "software"

    override fun encode(pcm: ByteArray): List<ByteArray> {
        val samples = sampleRate / 50
        require(pcm.size == samples * 2) {
            "Opus microphone frame must contain 20 ms of mono PCM"
        }
        val input = ShortArray(samples)
        for (index in input.indices) {
            input[index] = (
                (pcm[index * 2].toInt() and 0xff) or
                    (pcm[index * 2 + 1].toInt() shl 8)
            ).toShort()
        }
        val output = ByteArray(MAX_PACKET_BYTES)
        val count = encoder.encode(input, 0, samples, output, 0, output.size)
        check(count > 0) { "Opus microphone encoder produced no packet" }
        return listOf(output.copyOf(count))
    }

    override fun close() = Unit

    private companion object {
        const val MAX_PACKET_BYTES = 1275
    }
}
