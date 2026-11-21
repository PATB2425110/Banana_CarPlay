package com.dbtool.carplay.geely.lynk

import android.content.Context
import android.util.Log
import java.util.UUID

/**
 * Lynk 车机 **ADB 投屏路由**：用本地 ADB 把仪表 Activity 直接起在指定副屏上。
 *
 * ## 与借屏方案的关系（两条独立路线）
 *
 * | 路线 | 依赖 | 通用性 |
 * |---|---|---|
 * | [LynkClusterDisplayWire] 借屏 | 车机私有服务 `DimNaviService` | 只在有该服务的车机 |
 * | **本类** ADB 起 Activity | **只要有 ADB** | 更通用 |
 *
 * 上游 DiPlay(Lynk) **两条都有**，说明车机上 ADB 是通的（与 DBPlay 的 ADB 路子同源）。
 *
 * ## 协议（从反编译提取，逐字）
 *
 * 启动命令：
 * ```
 * am start-activity --display <displayId> -f 0x18000000 -n <pkg>/<clusterActivity> \
 *    --es cluster_launch_token <UUID>
 * ```
 * - `--display N`：把 Activity 起在指定副屏
 * - `-f 0x18000000`：`FLAG_ACTIVITY_NEW_TASK(0x10000000) | FLAG_ACTIVITY_MULTIPLE_TASK(0x08000000)`
 * - `cluster_launch_token`：UUID，**防重复拉起**（Activity 侧校验）
 *
 * 找屏（解析 `dumpsys display`）：
 * ```
 * ^Display #(\d+) \(activities from top to bottom\):   ← 块头，记住当前 display
 * ^\s{4,}\* Hist #\d+: ActivityRecord\{               ← Hist 条目
 * ```
 * → **不问「哪块是仪表屏」，只查「哪个 display 上已有 Activity」**，避开 displayId 猜谜。
 *
 * 验证（解析 `dumpsys activity activities`）：
 * ```
 * \bu\d+\s+<pkg>/<clusterActivity>(?=\s|,)   ← 确认 Activity 真在该 display
 * \bt<taskId>(?=\s|\})                        ← 确认 task id
 * ```
 *
 * 接受判定：输出含 `Starting: Intent {` 或 `^Status: ok$`，且不含
 * `error|exception|permission denied`。
 *
 * ⚠️ 需要本地 ADB 可用（`LocalAdb`，DBPlay 侧对应 [com.dbtool.carplay...] 的 adb 桥）。
 * 未实机验证。
 */
object LynkAdbClusterRouter {

    private const val TAG = "DBPlay-LynkAdb"

    /** 本应用里承载仪表画面的 Activity 全名（DBPlay 侧自定）。 */
    const val CLUSTER_ACTIVITY = "io.dushu.car.AdbClusterActivity"

    /** 拉起时的 token extra key（原样保留）。 */
    const val EXTRA_TOKEN = "cluster_launch_token"

    /** 起 Activity 用的 flags：NEW_TASK | MULTIPLE_TASK。 */
    private const val FLAGS = 0x18000000

    private val PKG_RE = Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+")

    /** 解析结果。 */
    data class Result(val success: Boolean, val report: String)

    /**
     * 拼启动命令。**纯函数，可单测**。
     *
     * @param pkg 目标包名（须是合法包名）
     * @param display 目标 displayId（必须 > 0）
     * @param token UUID 字符串
     */
    fun launchCommand(pkg: String, display: Int, token: String, activity: String = CLUSTER_ACTIVITY): String {
        require(display > 0) { "display 必须 > 0" }
        require(PKG_RE.matches(pkg)) { "非法包名: $pkg" }
        require(UUID.fromString(token).toString() == token) { "token 不是规范 UUID: $token" }
        return "am start-activity --display $display -f $FLAGS -n $pkg/$activity " +
            "--es $EXTRA_TOKEN $token"
    }

    /**
     * 解析 `dumpsys display`，找出「哪个 display 上有 Activity」。
     *
     * @return displayId；解析不到或 <=0 返回 null
     */
    fun displayIdFromDump(dump: String): Int? {
        var current: Int? = null
        val hits = mutableListOf<Int>()
        for (line in dump.lineSequence()) {
            val head = Regex("^Display #(\\d+) \\(activities from top to bottom\\):\\s*$").find(line)
            if (head != null) {
                current = head.groupValues[1].toIntOrNull()
                continue
            }
            // 遇到新的非缩进文本块 → 离开当前 display 段
            if (line.isNotEmpty() && !line.first().isWhitespace()) current = null
            val d = current ?: continue
            if (Regex("^\\s{4,}\\* Hist #\\d+: ActivityRecord\\{").containsMatchIn(line)) {
                hits += d
            }
        }
        return hits.singleOrNull()?.takeIf { it > 0 }
    }

    /**
     * 解析 `dumpsys activity activities`，确认我们的 Activity 真的落在某 display 上。
     *
     * @return 该 Activity 所在 displayId；找不到返回 null
     */
    fun activityDisplayFromDump(dump: String, pkg: String, task: Int,
                                activity: String = CLUSTER_ACTIVITY): Int? {
        var current: Int? = null
        val hits = mutableListOf<Int>()
        val activityPattern = "\\bu\\d+\\s+" + Regex.escape("$pkg/$activity") + "(?=\\s|,)"
        val taskPattern = "\\bt$task(?=\\s|\\})"
        for (line in dump.lineSequence()) {
            val head = Regex("^Display #(\\d+) \\(activities from top to bottom\\):\\s*$").find(line)
            if (head != null) {
                current = head.groupValues[1].toIntOrNull()
                continue
            }
            if (line.isNotEmpty() && !line.first().isWhitespace()) current = null
            val d = current ?: continue
            if (Regex("^\\s{4,}\\* Hist #\\d+: ActivityRecord\\{").containsMatchIn(line) &&
                Regex(activityPattern).containsMatchIn(line) &&
                Regex(taskPattern).containsMatchIn(line)
            ) {
                hits += d
            }
        }
        return hits.singleOrNull()?.takeIf { it > 0 }
    }

    /** am 命令输出是否被认为「已接受」。 */
    fun accepted(output: String): Boolean {
        val started = output.contains("Starting: Intent {") || Regex("(?m)^Status: ok\\s*$").containsMatchIn(output)
        val errored = Regex("(?i)error|exception|permission\\s*deni(?:al|ed)").containsMatchIn(output)
        return started && !errored
    }

    /**
     * 端到端拉起到指定屏。
     *
     * @param shell 执行 adb shell 命令的桥（返回 stdout；失败返回 null）
     * @return [Result]
     */
    fun launch(
        context: Context,
        token: String,
        shell: (String) -> String?,
        displayIdProvider: () -> Int?,
    ): Result {
        val pkg = context.packageName
        val report = StringBuilder()
        try {
            val display = displayIdProvider() ?: run {
                report.append("找不到可用副屏（dumpsys display 无 activity）\n")
                return Result(false, report.toString())
            }
            report.append("目标 displayId=$display\n")
            val cmd = launchCommand(pkg, display, token)
            report.append("命令: $cmd\n")
            val out = shell(cmd) ?: run {
                report.append("adb shell 无输出\n")
                return Result(false, report.toString())
            }
            report.append("输出: ${out.take(300)}\n")
            if (!accepted(out)) {
                report.append("输出未被接受\n")
                return Result(false, report.toString())
            }
            return Result(true, report.toString())
        } catch (e: Exception) {
            Log.w(TAG, "launch 失败", e)
            report.append("异常: ${e.message}\n")
            return Result(false, report.toString())
        }
    }
}
