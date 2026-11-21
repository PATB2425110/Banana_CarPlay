package com.dbtool.carplay.geely.lynk

import android.util.Log

/**
 * DBPlay **车辆能力探测 + 输出开关 + 独立进程桥**。
 *
 * ## 来源
 *
 * 从 DiPlay(Lynk) 反编译产物 `hud/BydOutputSettings.java`(22KB)、
 * `hud/BydVehicleCapabilityProbe.java`(23KB)、`hud/BydStarterBridge.java`(16KB) 提取。
 *
 * ## 🔥 最值钱的发现：`app_process` 独立进程探车
 *
 * `BydVehicleCapabilityProbe` **不是靠 bindService 读车机数据**，而是用
 * **`app_process /system/bin` 拉起一个带自定义 CLASSPATH 的独立 Java 进程**去读：
 *
 * ```
 * CLASSPATH=… app_process /system/bin …
 * autoservice probe incomplete: repliedFields=N
 * → ProbeBatch(capabilities, transportReplies)
 * ```
 *
 * 好处：**不用装系统服务、不用系统签名**，靠 shell 权限就能读车机字段。
 * DBPlay 若「读不到电量/轮速」，这条路比申请系统权限现实得多。
 *
 * ## 🔥 第二条通路：ADB 启动器桥（BydStarterBridge）
 *
 * ```
 * MAGIC = 1113146417
 * token 格式：[0-9a-f]{64}（64 位十六进制，一次性）
 * "Connected to ADB starter; navigation writes acknowledged"
 * "Temporary starter credentials received" / "Invalid starter token"
 * "Parked demo requested: left 500m, right 800m, then clear"
 * ```
 *
 * 通过 **ADB 拉起独立进程写导航数据**，带一次性令牌校验。
 * 停车演示模式：左转 500m → 右转 800m → 清空。
 *
 * ## 输出开关（BydOutputSettings）
 *
 * `PREFS = "diplay_byd_outputs"`，键名如下（原文）：
 *
 * | 键 | 含义 |
 * |---|---|
 * | `navigation_enabled` | 导航输出总开关 |
 * | `hud_song` | HUD 显示歌曲 |
 * | `cluster_song` | 仪表显示歌曲 |
 * | `cluster_song_on_change` | 仅换歌时刷新（省性能） |
 * | `cluster_stream_pause` | 仪表暂停时停推流 |
 * | `battery_to_iphone` | 把电量报给 iPhone |
 * | `wheel_speed_to_iphone` | 把轮速报给 iPhone |
 * | `video_while_parked` | 停车时可看视频 |
 * | `oem_cluster_hold` | 借屏保持 |
 * | `legacy_vehicle_probe` | 老式车辆探测 |
 * | `low_charge_percent` | 低电提醒百分比（预设 10/15/20/25/30，默认 20） |
 * | `charging_connectors` | 充电接口 |
 *
 * ⚠️ 未实机验证。`app_process` 通路需要 shell/adb 权限；CS11 上部分字段不存在。
 */
object LynkVehicleProbe {

    private const val TAG = "DBPlay-VehicleProbe"

    /** 输出开关 SharedPreferences 名。 */
    const val PREFS = "diplay_byd_outputs"

    // ---- 开关键名（原文）----
    const val KEY_ENABLED = "navigation_enabled"
    const val KEY_HUD_SONG = "hud_song"
    const val KEY_CLUSTER_SONG = "cluster_song"
    const val KEY_CLUSTER_SONG_ON_CHANGE = "cluster_song_on_change"
    const val KEY_CLUSTER_STREAM_PAUSE = "cluster_stream_pause"
    const val KEY_BATTERY_TO_IPHONE = "battery_to_iphone"
    const val KEY_WHEEL_SPEED_TO_IPHONE = "wheel_speed_to_iphone"
    const val KEY_VIDEO_WHILE_PARKED = "video_while_parked"
    const val KEY_OEM_CLUSTER_HOLD = "oem_cluster_hold"
    const val KEY_LEGACY_VEHICLE_PROBE = "legacy_vehicle_probe"
    const val KEY_LOW_CHARGE_PERCENT = "low_charge_percent"
    const val KEY_CHARGING_CONNECTORS = "charging_connectors"

    /** 低电提醒预设值。 */
    val LOW_CHARGE_PRESETS = listOf(10, 15, 20, 25, 30)

    /** 低电提醒默认值。 */
    const val DEFAULT_LOW_CHARGE_PERCENT = 20

    // ---- 独立进程探车（app_process）----
    const val APP_PROCESS = "/system/bin/app_process"

    /** 探测不完整时的日志。 */
    const val LOG_PROBE_INCOMPLETE = "autoservice probe incomplete: repliedFields="

    // ---- ADB 启动器桥 ----
    /** 桥协议魔数。 */
    const val STARTER_MAGIC = 1113146417

    /** 令牌正则（64 位十六进制）。 */
    const val STARTER_TOKEN_PATTERN = "[0-9a-f]{64}"

    const val LOG_STARTER_CONNECTED = "Connected to ADB starter; navigation writes acknowledged"
    const val LOG_TEMP_CREDENTIALS = "Temporary starter credentials received"
    const val LOG_INVALID_TOKEN = "Invalid starter token"
    const val LOG_PARKED_DEMO = "Parked demo requested: left 500m, right 800m, then clear"
    const val LOG_GUIDANCE_CLEARED = "Guidance cleared"

    /** 探测结果批次。 */
    data class ProbeBatch(
        val capabilities: Map<String, Double>,
        val transportReplies: Map<String, String>,
    )

    /** 停车演示脚本：左 500m → 右 800m → 清空。 */
    val PARKED_DEMO_STEPS = listOf(
        "left 500m",
        "right 800m",
        "clear",
    )

    /**
     * 构建 `app_process` 探车命令。
     *
     * @param classpath 车辆字段读取类所在的 jar/dex 路径
     */
    fun buildProbeCommand(classpath: String, mainClass: String): String =
        "CLASSPATH=$classpath $APP_PROCESS /system/bin $mainClass"

    /**
     * 校验探测是否完整。
     *
     * @param repliedFields 已回复字段数
     * @param expectedFields 期望字段总数
     * @return null 表示完整；否则返回不完整描述
     */
    fun checkProbeComplete(repliedFields: Int, expectedFields: Int): String? =
        if (repliedFields < expectedFields) {
            "$LOG_PROBE_INCOMPLETE$repliedFields + $expectedFields"
        } else null

    /** 校验启动器令牌（64 位 hex）。 */
    fun isValidStarterToken(token: String?): Boolean =
        token != null && Regex("^$STARTER_TOKEN_PATTERN$").matches(token)

    /** 开关读取建议：默认值（对老安装无效，需迁移）。 */
    fun defaultIfMissing(key: String): Any? = when (key) {
        KEY_ENABLED -> true
        KEY_LOW_CHARGE_PERCENT -> DEFAULT_LOW_CHARGE_PERCENT
        else -> false
    }
}
