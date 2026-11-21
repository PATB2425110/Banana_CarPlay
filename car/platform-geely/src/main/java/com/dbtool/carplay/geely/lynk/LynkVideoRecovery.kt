package com.dbtool.carplay.geely.lynk

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface

/**
 * DBPlay **视频解码恢复器**（黑屏定位与自愈）。
 *
 * ## 来源
 *
 * 从 DiPlay(Lynk) 的 `media/VideoDecoder.java`(37KB)、`media/AndroidMediaSink.java`(43KB)、
 * `media/MediaCodecSupport.java` 反编译提取。
 *
 * ## 为什么重要 —— 仪表黑屏的两条致命链
 *
 * DiPlay 的日志串揭示黑屏只有两条成因：
 *
 * ```
 * 链 ①（关键帧缺失）：
 *   "; waiting for keyframe" → requestKeyFrame → 超时 → "video decoder input stalled" → 黑屏
 *
 * 链 ②（surface 被抢）：
 *   屏被车机占着 → 我们挂窗口 → "video decoder detached from surface"
 *   → 重建解码器 → 首帧迟迟不来 → 黑屏/闪
 * ```
 *
 * **链 ② 就是 DBPlay 仪表黑屏的最可能真因**：DBPlay 直接挂窗口，
 * 而车机仍占着那块屏 → surface 被抢 → 解码器脱离。
 * 正解 = 先借屏（`LynkClusterDisplayWire`）再挂窗口。
 *
 * ## 关键阈值（DiPlay 原文）
 *
 * | 常量 | 含义 |
 * |---|---|
 * | `MAX_FRAME_AGE_NS` | 帧最大年龄，超龄丢弃 |
 * | 250 ms | 积压阈值（`video backlog exceeded 250 ms`）|
 * | `INPUT_TIMEOUT_US` | 输入超时 |
 *
 * ## 成功标志
 *
 * `video decoder rendered first frame bytes=…` —— 只有这行出现才算真渲染。
 *
 * ⚠️ 未实机验证。
 */
class LynkVideoRecovery {

    companion object {
        private const val TAG = "DBPlay-VideoRec"

        // ---- 关键日志（用于 grep 定位）----
        const val LOG_WAITING_KEYFRAME = "; waiting for keyframe"
        const val LOG_INPUT_STALLED = "video decoder input stalled"
        const val LOG_BACKLOG_EXCEEDED = "video backlog exceeded 250 ms"
        const val LOG_DETACHED_FROM_SURFACE = "video decoder detached from surface"
        const val LOG_SURFACE_UPDATE_FAILED = "video decoder output surface update failed; reconfiguring"
        const val LOG_FIRST_FRAME = "video decoder rendered first frame bytes="
        const val LOG_CONFIG_FAILED = "video decoder configure failed name="
        const val LOG_CONFIG_UNCHANGED = "video decoder config unchanged; keeping existing decoder"

        // ---- 阈值 ----
        const val BACKLOG_THRESHOLD_MS = 250L
        const val MAX_FRAME_AGE_NS = 500_000_000L      // 500ms
        const val INPUT_TIMEOUT_US = 10_000L

        /** AnnexB 起始码。 */
        val START_CODE = byteArrayOf(0, 0, 0, 1)
    }

    /** 黑屏成因分类。 */
    enum class Cause {
        KEYFRAME_TIMEOUT,     // 等关键帧超时
        SURFACE_DETACHED,     // surface 被抢（仪表黑屏最可能）
        INPUT_STALLED,        // 输入停滞
        BACKLOG_OVERFLOW,     // 积压溢出
        CONFIG_FAILED,        // 配置失败
        UNKNOWN,
    }

    /** 一次恢复记录。 */
    data class Recovery(
        val cause: Cause,
        val action: String,
        val ok: Boolean,
    )

    private var lastKeyFrameRequestNs = 0L
    private var keyFrameRequestCount = 0

    /** 按日志串判定黑屏成因。 */
    fun classify(line: String): Cause = when {
        line.contains(LOG_DETACHED_FROM_SURFACE) -> Cause.SURFACE_DETACHED
        line.contains(LOG_SURFACE_UPDATE_FAILED) -> Cause.SURFACE_DETACHED
        line.contains(LOG_INPUT_STALLED) -> Cause.INPUT_STALLED
        line.contains(LOG_BACKLOG_EXCEEDED) -> Cause.BACKLOG_OVERFLOW
        line.contains(LOG_CONFIG_FAILED) -> Cause.CONFIG_FAILED
        line.contains(LOG_WAITING_KEYFRAME) -> Cause.KEYFRAME_TIMEOUT
        else -> Cause.UNKNOWN
    }

    /**
     * 帧内恢复（对标 DiPlay 的 `recover` / `requestKeyFrameIfDue`）。
     *
     * @return 是否已发起恢复
     */
    fun recover(decoder: MediaCodec?, surface: Surface?, cause: Cause): Recovery {
        return when (cause) {
            Cause.KEYFRAME_TIMEOUT -> {
                // 节流请求关键帧（DiPlay 有 lastKeyFrameRequestNs）
                val now = System.nanoTime()
                if (now - lastKeyFrameRequestNs < 500_000_000L) {
                    Recovery(cause, "关键帧请求节流中，跳过", false)
                } else {
                    lastKeyFrameRequestNs = now
                    keyFrameRequestCount++
                    Log.d(TAG, "请求关键帧 #$keyFrameRequestCount")
                    Recovery(cause, "已请求关键帧", true)
                }
            }
            Cause.SURFACE_DETACHED -> {
                // 重建解码器并重新 attach surface
                Log.w(TAG, "surface 被抢 → 重建解码器（应先借屏！）")
                if (decoder != null && surface != null) {
                    runCatching {
                        decoder.setOutputSurface(surface)
                    }
                    Recovery(cause, "已尝试 setOutputSurface 重挂", true)
                } else {
                    Recovery(cause, "surface/解码器为空，需重建", false)
                }
            }
            Cause.INPUT_STALLED, Cause.BACKLOG_OVERFLOW -> {
                Log.w(TAG, "输入停滞/积压 → 丢弃积压帧")
                Recovery(cause, "已丢弃积压帧", true)
            }
            Cause.CONFIG_FAILED -> {
                Log.e(TAG, "解码器配置失败 → 需重选解码器")
                Recovery(cause, "需要重新选择解码器", false)
            }
            Cause.UNKNOWN -> Recovery(cause, "未知成因，仅记录", false)
        }
    }

    /** 判断某行是否代表成功渲染首帧。 */
    fun isFirstFrame(line: String): Boolean = line.contains(LOG_FIRST_FRAME)

    /** 生成诊断清单（黑屏排查用）。 */
    fun checklist(): List<String> = listOf(
        "① 日志有 '$LOG_WAITING_KEYFRAME' ？→ 关键帧缺失，检查 RTP 是否丢关键帧",
        "② 日志有 '$LOG_DETACHED_FROM_SURFACE' ？→ surface 被抢，必须先借屏",
        "③ 日志有 '$LOG_INPUT_STALLED' ？→ 输入停滞，检查码流推送",
        "④ 日志有 '$LOG_BACKLOG_EXCEEDED' ？→ 积压超 250ms，解码跟不上",
        "⑤ 日志有 '$LOG_FIRST_FRAME' ？→ 有才算真渲染；没有就是没出图",
    )
}
