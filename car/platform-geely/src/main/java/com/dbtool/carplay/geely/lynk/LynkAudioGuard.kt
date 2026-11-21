package com.dbtool.carplay.geely.lynk

import android.util.Log

/**
 * DBPlay **音频路由守卫**（ROM 兼容降级 + 通道分离 + 焦点）。
 *
 * ## 来源
 *
 * 从 DiPlay(Lynk) 的 `media/AudioRenderer.java`(77KB)、
 * `media/AudioFocusCoordinator.java`、`media/AudioChannelMapper.java` 反编译提取。
 *
 * ## 核心问题
 *
 * 车机 ROM（尤其吉利/比亚迪定制版）**常拒收标准 `AudioAttributes` 的 track 配置**，
 * 导致 CarPlay 音频不出声或被系统抢占。DiPlay 的解法是**两级降级**：
 *
 * ```
 * ① 优先：usage-based track（标准 AudioAttributes 路由）
 *    ↓ 被 ROM 拒收（"rejected by this ROM"）
 * ② 降级：usage-based track 保持原路由（"keeping usage routing"）
 *    ↓ 更老的车机
 * ③ 兜底：legacy audio stream（`legacy audio stream …`）
 * ```
 *
 * ## 通道分离（CarPlay 特需）
 *
 * | 通道 | 用途 | 焦点策略 |
 * |---|---|---|
 * | `mediaChannel` | 媒体音频 | 正常取得焦点，可压低（DUCKED_VOLUME） |
 * | `navigationChannel` | 导航提示 | **overlays without ducking** —— 导航提示不压低媒体 |
 *
 * ## 编解码（从 DiPlay 提取）
 *
 * - AAC：`aacAudioSpecificConfig`、`AAC_OBJECT_TYPE_LC`
 * - Opus：`OpusHead`、`opusCodecDelay`、`opusSeekPreRoll`
 * - 码流：ADTS（`is-adts`）
 *
 * ⚠️ 未实机验证。
 */
class LynkAudioGuard {

    companion object {
        private const val TAG = "DBPlay-Audio"

        /** 路由降级链日志（DiPlay 原文）。 */
        const val LOG_REJECTED_FALLBACK = "rejected by this ROM; falling back to usage-based track"
        const val LOG_REJECTED_KEEP = "rejected; keeping usage routing"
        const val LOG_LEGACY_STREAM = "legacy audio stream "
        const val LOG_QUEUE_FULL = "audio queue full; dropping newest packets to bound latency"
        const val LOG_NAV_NO_DUCK = "audio focus skipped channel=NAVIGATION; overlays without ducking"
        const val LOG_TRACK_UNAVAILABLE = "Audio: track unavailable api="
        const val LOG_RENDERER_FAILED = "Audio: renderer failed api="

        /** 音量。 */
        const val FULL_VOLUME = 1.0f
        const val DUCKED_VOLUME = 0.3f

        /** 统计窗口。 */
        const val STATS_WINDOW_NS = 20_000_000_000L   // 20s
        const val AUDIO_POLL_MILLIS = 10L
        const val MAX_QUEUED_PACKETS = 64
    }

    /** 音频通道。 */
    enum class Channel { MEDIA, NAVIGATION }

    /** 路由模式（降级顺序）。 */
    enum class RouteMode {
        USAGE_BASED,      // 标准 AudioAttributes
        USAGE_KEEP,       // 保持 usage 路由（ROM 拒收后）
        LEGACY_STREAM,    // 老式 stream type
    }

    /** 焦点记录。 */
    data class FocusEntry(
        val channel: Channel,
        val granted: Boolean,
        val volume: Float,
    )

    private val activeTracks = mutableMapOf<Channel, RouteMode>()
    private val focusState = mutableMapOf<Channel, FocusEntry>()

    /**
     * 按 DiPlay 的降级链决定路由模式。
     *
     * @param romRejectsAttributes 是否观察到 ROM 拒收（日志里有 LOG_REJECTED_FALLBACK）
     * @param sdkInt 车机 SDK（越老越可能只有 legacy stream）
     */
    fun resolveRouteMode(romRejectsAttributes: Boolean, sdkInt: Int = android.os.Build.VERSION.SDK_INT): RouteMode {
        val mode = when {
            !romRejectsAttributes -> RouteMode.USAGE_BASED
            sdkInt >= 26 -> RouteMode.USAGE_KEEP
            else -> RouteMode.LEGACY_STREAM
        }
        Log.d(TAG, "音频路由模式: $mode (romRejects=$romRejectsAttributes sdk=$sdkInt)")
        return mode
    }

    /** 记录某通道已建成 track。 */
    fun trackPrepared(channel: Channel, mode: RouteMode) {
        activeTracks[channel] = mode
    }

    /**
     * 焦点策略：导航通道**不压低媒体**（DiPlay 原文：`overlays without ducking`）。
     */
    fun focusVolumeFor(channel: Channel, isDuckRequest: Boolean): Float = when (channel) {
        Channel.NAVIGATION -> FULL_VOLUME                 // 导航提示从不压低别人
        Channel.MEDIA -> if (isDuckRequest) DUCKED_VOLUME else FULL_VOLUME
    }

    /** 从日志行更新降级状态。 */
    fun observe(line: String): RouteMode? = when {
        line.contains(LOG_REJECTED_FALLBACK) -> RouteMode.USAGE_KEEP
        line.contains(LOG_REJECTED_KEEP) -> RouteMode.USAGE_KEEP
        line.contains(LOG_LEGACY_STREAM) -> RouteMode.LEGACY_STREAM
        else -> null
    }

    /** 诊断清单（音频不出声排查用）。 */
    fun checklist(): List<String> = listOf(
        "① 有 '$LOG_REJECTED_FALLBACK' ？→ ROM 拒收标准属性，已降级（正常）",
        "② 有 '$LOG_TRACK_UNAVAILABLE' ？→ track 建不起来，多半被别的 App 占着",
        "③ 有 '$LOG_QUEUE_FULL' ？→ 缓冲满丢包，检查写入速度",
        "④ 有 '$LOG_NAV_NO_DUCK' ？→ 导航通道未压低媒体（设计如此）",
        "⑤ 完全无声？→ 先查 AudioFocus 是否 granted，再查通道映射",
    )
}
