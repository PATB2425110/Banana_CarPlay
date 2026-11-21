package com.dbtool.carplay.geely.lynk

/**
 * DBPlay **车辆字段地址表 + 转向码表 + HUD 载荷限制**。
 *
 * ## 来源
 *
 * 从 DiPlay(Lynk) 反编译产物 `hud/BydVehicleField.java`(6KB)、
 * `hud/BydManeuverCodes.java`(6KB)、`hud/BydFirmwareCatalog.java`(10KB)、
 * `hud/BydWheelSpeedSource.java`(9KB)、`hud/BydHudPayload.java`(6KB) 提取。
 *
 * ## 🔥 车辆字段地址表（怎么读到电量/车速的答案）
 *
 * 每条字段 = `(名, 序, 符号, 常量列表, 设备, ?, hash, ?)`：
 *
 * | 字段 | 符号 | 设备 | id |
 * |---|---|---|---|
 * | `SPEED` | `Speed.SPEED_AUTO_SPEED` | SPEED | 1013 |
 * | `GEAR` | `Gearbox.GEARBOX_AUTO_MODE_TYPE` | GEARBOX | 1011 |
 * | `SOC` | `Statistic.STATISTIC_ELEC_PERCENTAGE` | STATISTIC | 1014 |
 * | `RANGE` | `Statistic.STATISTIC_ELEC_DRIVING_RANGE` | — | — |
 * | `REMAINING_KWH` | `Power.POWER_BATTERY_REMAIN_ELECTRICITY` | — | — |
 * | `BMS_STATE` | `Charging.CHARGING_BATTERRY_DEVICE_STATE` | — | — |
 *
 * 读法：用 `app_process` 起独立进程（见 [LynkVehicleProbe]），按这些**符号名**
 * 向车机 HAL 取值。**不依赖系统签名**。
 *
 * ⚠️ 这些符号名/设备名是比亚迪 `BYDAutoConstants` 体系的命名，吉利 CS11 上不一定存在，
 * 但**该通路本身通用**（换成本车机对应的符号名即可）。
 *
 * ## 转向箭头码表（BydManeuverCodes）
 *
 * 车机仪表的箭头图标用整数编码，**与 iAP2 的 maneuverType 不完全一致**，需要映射。
 *
 * ## 轮速采样参数（BydWheelSpeedSource）
 *
 * `READ_MILLIS=250`（每 250ms 一读）、`MAX_SAMPLES=40`（环形缓冲）、
 * `GEAR_EVERY_READS=4`（每 4 次读一次挡位）。
 *
 * ## HUD 载荷限制（BydHudPayload）
 *
 * `MAX_TEXT_BYTES=200`（文本上限）、`MIN_DISTANCE_METERS=11`（小于 11 米不显示）。
 */
object LynkVehicleFields {

    /** 车辆字段定义。 */
    data class Field(
        val name: String,
        val ordinal: Int,
        val symbol: String,
        val constants: List<String>,
        val device: String?,
        val deviceId: Int?,
    )

    // ---- 车辆字段（原文）----
    val SPEED = Field("SPEED", 0, "Speed.SPEED_AUTO_SPEED", emptyList(), "SPEED", 1013)
    val GEAR = Field("GEAR", 1, "Gearbox.GEARBOX_AUTO_MODE_TYPE", emptyList(), "GEARBOX", 1011)
    val SOC = Field("SOC", 2, "Statistic.STATISTIC_ELEC_PERCENTAGE", emptyList(), "STATISTIC", 1014)
    val RANGE = Field("RANGE", 3, "Statistic.STATISTIC_ELEC_DRIVING_RANGE", emptyList(), "STATISTIC", null)
    val REMAINING_KWH = Field("REMAINING_KWH", 4, "Power.POWER_BATTERY_REMAIN_ELECTRICITY", emptyList(), null, null)
    val BMS_STATE = Field("BMS_STATE", 5, "Charging.CHARGING_BATTERRY_DEVICE_STATE", emptyList(), "CHARGING", null)

    /** 全部字段。 */
    val ALL = listOf(SPEED, GEAR, SOC, RANGE, REMAINING_KWH, BMS_STATE)

    /** 固件常量前缀（BydFirmwareCatalog 用）。 */
    const val FIRMWARE_DEVICE_PREFIX = "BYDAutoConstants.BYDAUTO_DEVICE_"

    // ---- 转向箭头码表（BydManeuverCodes）----
    object ManeuverCodes {
        const val NONE = 0
        const val LEFT = 1
        const val RIGHT = 2
        const val SLIGHT_LEFT = 3
        const val SLIGHT_RIGHT = 4
        const val SHARP_LEFT = 7
        const val SHARP_RIGHT = 8
        const val U_TURN_LEFT = 9
        const val U_TURN_RIGHT = 10
        const val STRAIGHT = 11
        const val ROUNDABOUT_ENTER = 13
        const val ROUNDABOUT_EXIT = 24
        /** 左行国家（日本/英国）环岛出口基准码。 */
        const val ROUNDABOUT_EXIT_BASE_LEFT_HAND = 34
        const val DESTINATION = 48

        /** 码 → 可读名。 */
        fun name(code: Int): String = when (code) {
            NONE -> "无"
            LEFT -> "左转"
            RIGHT -> "右转"
            SLIGHT_LEFT -> "微左"
            SLIGHT_RIGHT -> "微右"
            SHARP_LEFT -> "急左"
            SHARP_RIGHT -> "急右"
            U_TURN_LEFT -> "左掉头"
            U_TURN_RIGHT -> "右掉头"
            STRAIGHT -> "直行"
            ROUNDABOUT_ENTER -> "进入环岛"
            ROUNDABOUT_EXIT -> "驶出环岛"
            DESTINATION -> "到达终点"
            else ->
                if (code >= ROUNDABOUT_EXIT_BASE_LEFT_HAND) {
                    "驶出环岛（左行，第 ${code - ROUNDABOUT_EXIT_BASE_LEFT_HAND} 出口）"
                } else "未知($code)"
        }
    }

    // ---- 轮速采样 ----
    /** 采样周期（毫秒）。 */
    const val WHEEL_SPEED_READ_MILLIS = 250L
    /** 环形缓冲上限。 */
    const val WHEEL_SPEED_MAX_SAMPLES = 40
    /** 每 N 次读顺带读一次挡位。 */
    const val GEAR_EVERY_READS = 4

    // ---- HUD 载荷限制 ----
    /** HUD 文本字节上限。 */
    const val HUD_MAX_TEXT_BYTES = 200
    /** 距离小于此值不显示（避免闪动）。 */
    const val HUD_MIN_DISTANCE_METERS = 11

    /** 判定是否显示距离提示。 */
    fun shouldShowDistance(meters: Int): Boolean = meters >= HUD_MIN_DISTANCE_METERS

    /** 按符号名查字段。 */
    fun bySymbol(symbol: String): Field? = ALL.firstOrNull { it.symbol == symbol }
}
