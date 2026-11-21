package com.dbtool.carplay.geely.lynk

import android.content.Context
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Lynk 车机**显示链路分段诊断**：把投屏整条链按阶段记下来，一眼看出断在哪段。
 *
 * ## 为什么需要它
 *
 * 仪表黑屏/无画面时，「解码器 rendered first frame 出了、窗口挂上了、Surface 有效」
 * 全绿 —— 但屏上没图。此时**逐段记录**才能定位到底是：
 * 选屏错 / 手机没请求该分辨率 / 车机能力不匹配 / 解码器失败 / 输出格式不对。
 *
 * ## 协议（从 DiPlay(Lynk) 反编译提取）
 *
 * SharedPreferences 名：`diplay_display_diagnostics`，字段：
 *
 * | 字段 | 含义 |
 * |---|---|
 * | `selection` | 选屏（Size setting previous=X% selected=Y% reconnect=Z）|
 * | `request` | 手机请求的分辨率 |
 * | `capability` | 车机能力 |
 * | `effective` | 最终生效参数 |
 * | `phone` | 手机侧消息 |
 * | `decoder` | 解码器（`Video: decoder=...` / `Video: decoder configuration failed`）|
 * | `output` | 输出（`Video: output format ...`）|
 *
 * 另有 `attempt` 字段存**本次尝试的 UUID**，用来把同一轮的各段串起来。
 *
 * ## 按消息前缀自动归位（原样保留）
 *
 * ```java
 * if (msg.startsWith("Phone display peer "))        → phone
 * else if (msg.startsWith("Video: decoder=") ||
 *          msg.startsWith("Video: decoder configuration failed")) → decoder
 * else if (msg.startsWith("Video: output format ")) → output
 * ```
 *
 * ## 用法
 *
 * ```kotlin
 * val snap = LynkDisplayDiagnostics(context)
 * val attempt = snap.begin(request = "1920x1080@60", capability = "1080p", effective = "1920x1080")
 * snap.record(attempt, "Video: decoder=OMX.xxx")
 * snap.record(attempt, "Phone display peer 192.168.1.5")
 * Log.i(TAG, snap.report())
 * ```
 *
 * ⚠️ 键名与阶段划分照上游保留；写入按「不改动他人数据」原则，仅本应用自己的 SP。
 */
class LynkDisplayDiagnostics(
    private val context: Context,
    private val prefsName: String = "dbplay_display_diagnostics",
) {

    private companion object {
        const val TAG = "DBPlay-LynkDiag"

        /** 记录字段（顺序即展示顺序）。 */
        val FIELDS = listOf("selection", "request", "capability", "effective", "phone", "decoder", "output")

        /** 本次尝试的 token 键。 */
        const val KEY_ATTEMPT = "attempt"

        const val PREFIX_PHONE = "Phone display peer "
        const val PREFIX_DECODER = "Video: decoder="
        const val PREFIX_DECODER_FAIL = "Video: decoder configuration failed"
        const val PREFIX_OUTPUT = "Video: output format "
    }

    private fun prefs() = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    private fun stamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

    /** 记一次选屏动作。 */
    fun recordSelection(previous: Int, selected: Int, reconnect: Boolean) {
        prefs().edit().putString(
            "selection",
            "${stamp()} Size setting previous=$previous% selected=$selected% reconnect=$reconnect"
        ).apply()
    }

    /**
     * 开始一轮协商，返回本次尝试的 token。
     *
     * @param request 手机请求的参数
     * @param capability 车机能力
     * @param effective 最终生效参数
     */
    fun begin(request: String, capability: String, effective: String): String {
        val attempt = UUID.randomUUID().toString()
        val edit = prefs().edit()
        edit.putString(KEY_ATTEMPT, attempt)
        for ((k, v) in listOf("request" to request, "capability" to capability, "effective" to effective)) {
            edit.putString(k, "${stamp()} $v")
        }
        edit.apply()
        Log.i(TAG, "begin attempt=$attempt req=$request cap=$capability eff=$effective")
        return attempt
    }

    /**
     * 记一条链路消息。仅当 [attemptToken] 与当前记录的一致时写入（防过期写入）。
     */
    fun record(attemptToken: String, message: String) {
        val p = prefs()
        if (p.getString(KEY_ATTEMPT, null) != attemptToken) return
        val field = when {
            message.startsWith(PREFIX_PHONE) -> "phone"
            message.startsWith(PREFIX_DECODER) || message.startsWith(PREFIX_DECODER_FAIL) -> "decoder"
            message.startsWith(PREFIX_OUTPUT) -> "output"
            else -> return
        }
        p.edit().putString(field, "${stamp()} $message").apply()
    }

    /** 生成可读报告。 */
    fun report(): String {
        val p = prefs()
        val lines = ArrayList<String>()
        for (f in FIELDS) {
            val v = p.getString(f, null)
            if (v != null) lines += "$f: $v"
        }
        if (lines.isEmpty()) return "No display negotiation recorded yet."
        return lines.joinToString("\n")
    }

    /** 清空。 */
    fun clear() {
        prefs().edit().clear().apply()
    }
}
