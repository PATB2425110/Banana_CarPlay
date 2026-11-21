package com.dbtool.carplay.ui.screens

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlin.math.PI
import kotlin.math.sin

/**
 * 「试听当前媒体通道」用的探针。
 *
 * ## 为什么值得单独写一段代码
 *
 * 排查「连接正常但没声音」时，现场真正的时间都花在
 * **改设置 → 保存 → 重连 → 放歌 → 还是没声** 这个循环上，一轮以分钟计。
 * 而问题的答案其实只有一个：*这条流号有没有通到喇叭*。
 *
 * 本对象把这件事压缩成「点一下」，直接在选定流号上放一段提示音，
 * 不需要 iPhone、不需要 CarPlay 会话、不需要重连。
 * 于是 21 个候选流号可以在一分钟内全试完。
 *
 * ## 为什么不走协议、不碰会话
 *
 * 它刻意**不依赖任何 CarPlay 状态**：正是要在「会话有问题」的时候
 * 还能验证车机自己的音频通道通不通。所以直接 `AudioTrack` 起轨、
 * 写正弦波、同步写完释放。
 *
 * ## 与 `AndroidMediaSink` 的关系
 *
 * 复制了几行建轨逻辑（老式 streamType 轨 + 初始化失败退回 usage）。
 * 刻意**不抽公共函数**：那会要求 sink 暴露一个「只放提示音」的入口，
 * 把排查用的东西塞进生产播放路径。这里各写各的，代价是十来行重复，
 * 换来的是**试听出问题绝不影响真正的播放链路**。
 */
internal object AudioChannelProbe {

    private const val TAG = "DBPlay-audio-probe"

    /** 提示音频率（A4），够明显又不刺耳。 */
    private const val TONE_HZ = 440.0

    /** 总时长与淡入淡出，避免"啪"的爆音被误当成故障。 */
    private const val DURATION_MS = 600
    private const val FADE_MS = 30

    private const val SAMPLE_RATE = 48_000

    /**
     * 在 [channel] 上放一段提示音。
     *
     * [channel] == 0 表示按 usage 路由（media usage），非 0 表示老式 streamType。
     * 整个过程在后台线程同步完成：调用方（Compose 的 onClick）不能阻塞主线程。
     */
    fun play(context: Context, channel: Int) {
        @Suppress("UNUSED_PARAMETER")
        val ignored = context
        Thread({
            runCatching { playBlocking(channel, DURATION_MS) }.onFailure {
                Log.w(TAG, "probe playback failed channel=$channel", it)
            }
        }, "dbplay-audio-probe").apply { isDaemon = true }.start()
    }

    /**
     * 在 [channel] 上放一段提示音、**同步执行并返回建轨结果**。
     *
     * 返回值：`"ok(usage)"` / `"ok(streamType=$channel)"` / `"fallback-usage(...)"`。
     * 这个返回值只用于日志/排障（见 `play`），不影响实际播放。
     */
    private fun playBlocking(channel: Int, durationMs: Int): String {
        val samples = SAMPLE_RATE * durationMs / 1000
        val pcm = ShortArray(samples)
        val fade = (SAMPLE_RATE * FADE_MS / 1000).coerceAtMost(samples / 2)
        for (i in 0 until samples) {
            // 两处淡入淡出：起播与停播的瞬断本身听着就像"故障"，
            // 会被误判成通道有问题。
            val gain = when {
                i < fade -> i.toDouble() / fade
                i >= samples - fade -> (samples - i).toDouble() / fade
                else -> 1.0
            }
            pcm[i] = (sin(2 * PI * TONE_HZ * i / SAMPLE_RATE) * gain * Short.MAX_VALUE * 0.5)
                .toInt()
                .toShort()
        }

        val bytes = pcm.size * 2
        val outcome: String
        val track: AudioTrack
        if (channel == 0) {
            outcome = "ok(usage)"
            track = usageTrack(bytes)
        } else if (channel == 21) {
            outcome = "ok(usage=12,contentType=17)"
            track = lynk01Track(bytes)
        } else {
            val legacy = tryBuildLegacyTrack(channel, bytes)
            if (legacy != null) {
                outcome = "ok(streamType=$channel)"
                track = legacy
            } else {
                // ROM 拒绝该流号 —— 退回 usage 轨，但仍要在日志里标明是 fallback，
                // 否则会把"退回后的声音"误记成"该流号有声音"。
                outcome = "fallback-usage(streamType=$channel rejected)"
                track = usageTrack(bytes)
            }
        }

        try {
            track.write(pcm, 0, pcm.size)
            track.play()
            Thread.sleep(durationMs.toLong() + 80)
        } finally {
            runCatching { track.stop() }
            runCatching { track.release() }
        }
        return outcome
    }

    private fun tryBuildLegacyTrack(channel: Int, bytes: Int): AudioTrack? {
        val size = maxOf(bytes, minBuffer())
        val legacy = runCatching {
            AudioTrack(
                channel,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
                size,
                AudioTrack.MODE_STREAM,
            )
        }.getOrNull()
        if (legacy != null && legacy.state == AudioTrack.STATE_INITIALIZED) return legacy
        runCatching { legacy?.release() }
        return null
    }

    private fun lynk01Track(bytes: Int): AudioTrack {
        val builder = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(17)
        if (builder.build().contentType != 17) {
            runCatching {
                val field = AudioAttributes.Builder::class.java.getDeclaredField("mContentType")
                field.isAccessible = true
                field.setInt(builder, 17)
            }
        }
        val attributes = builder.build()
        if (attributes.contentType != 17) {
            runCatching {
                val field = AudioAttributes::class.java.getDeclaredField("mContentType")
                field.isAccessible = true
                field.setInt(attributes, 17)
            }
        }
        return AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(pcmFormat())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(bytes, minBuffer()))
            .build()
    }

    private fun usageTrack(bytes: Int): AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
        )
        .setAudioFormat(pcmFormat())
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setBufferSizeInBytes(maxOf(bytes, minBuffer()))
        .build()

    private fun pcmFormat() = AudioFormat.Builder()
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setSampleRate(SAMPLE_RATE)
        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
        .build()

    private fun minBuffer(): Int =
        AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(4096)

    private fun sleepQuietly(millis: Long) {
        runCatching { Thread.sleep(millis) }
    }
}
