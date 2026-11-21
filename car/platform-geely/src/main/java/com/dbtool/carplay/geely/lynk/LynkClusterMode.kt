package com.dbtool.carplay.geely.lynk

import android.util.Log

/**
 * DBPlay **车机仪表模式切换 + 电池协议 + 旁路 HUD 报文**。
 *
 * ## 来源
 *
 * 从 DiPlay(Lynk) 反编译产物 `hud/BydClusterNaviMode.java`(6KB)、
 * `hud/BydDiLink3ClusterOutput.java`(14KB)、`hud/DiLink3ClusterRecoveryJournal.java`(2KB)、
 * `hud/BydBatteryProtocol.java`(3KB)、`hud/BydBatteryReading.java`(7KB)、
 * `hud/BydStandalonePackets.java`(7KB)、`hud/BydClusterMapPause.java`(7KB) 提取。
 *
 * ## 🔥 仪表模式怎么查/怎么切（`service call` 直通）
 *
 * ```
 * READ_COMMAND = "service call autoservice 5 i32 1007 i32 1086337074"
 *                GET_INT = 5              ← 事务码 5 = 读 int
 *                INSTRUMENT_DEVICE = 1007 ← 仪表设备号
 *                NAVI_TYPE_GET = 1086337074 ← 导航模式查询字段
 * ```
 *
 * 仪表导航模式：
 * | 模式 | 值 | 含义 |
 * |---|---|---|
 * | `OFF` | 1 | 关 |
 * | `TURN_ON_BY_NAVI` | 2 | 由导航自动点亮 |
 * | `SMALL` | 3 | 小屏导航 |
 * | `FULL` | 4 | 全屏导航 |
 *
 * **仪表投不上画面时，先跑这条命令看模式是不是 `OFF`** —— 比猜 displayId 靠谱。
 *
 * ## 借屏恢复账本（DiLink3ClusterRecoveryJournal）
 *
 * `JOURNAL = "restore_stock_mode"` —— 借屏前把「原厂模式」写进账本，
 * 退出/Crash 后靠它还原。**与 [LynkClusterHoldSession] 是同一套思路的第二处实现。**
 *
 * ## 电池协议魔数（BydBatteryProtocol）
 *
 * | 协议 | 三个魔数 |
 * |---|---|
 * | `CAN` | 1033543720, 1033203771, 876611608 |
 * | `CANFD` | 1246777400, 1246765118, 876609560 |
 *
 * 读数结果：`BydBatteryReading(percent, rangeKm, remainingKwh, charging, protocol)`
 *
 * ## 旁路 HUD 报文（BydStandalonePackets）
 *
 * - `MAX_DISTANCE_METERS = 16777214`（0xFFFFFE，24 位上限）
 * - 报文头原文 hex：`43,E0,00,3A,01,02` / `43,E0,00,3A,01,01`
 *
 * ⚠️ 未实机验证。`autoservice` 与设备号 1007 是比亚迪体系；CS11（吉利）上服务名不同，
 * 但**用 `service call <服务> <事务码> i32 <设备> i32 <字段>` 这条思路通用**。
 */
object LynkClusterMode {

    private const val TAG = "DBPlay-ClusterMode"

    // ---- 仪表模式查询命令（原文）----
    /** 读 int 事务码。 */
    const val GET_INT = 5
    /** 仪表设备号。 */
    const val INSTRUMENT_DEVICE = 1007
    /** 导航模式查询字段。 */
    const val NAVI_TYPE_GET = 1086337074

    /** 完整查询命令（adb shell 可直接跑）。 */
    const val READ_COMMAND = "service call autoservice $GET_INT i32 $INSTRUMENT_DEVICE i32 $NAVI_TYPE_GET"

    /** 借屏恢复账本键名。 */
    const val JOURNAL_KEY = "restore_stock_mode"

    /** 仪表导航模式。 */
    enum class NaviMode(val code: Int, val label: String) {
        OFF(1, "Off"),
        TURN_ON_BY_NAVI(2, "Turn on by navi"),
        SMALL(3, "Small screen navi"),
        FULL(4, "Full screen navi");

        companion object {
            fun of(code: Int): NaviMode? = entries.firstOrNull { it.code == code }
        }
    }

    // ---- 电池协议魔数 ----
    /** 电池协议定义。 */
    data class BatteryProtocol(
        val name: String,
        val id: Int,
        val magic1: Int,
        val magic2: Int,
        val magic3: Int,
    )

    val BATTERY_CAN = BatteryProtocol("CAN", 0, 1033543720, 1033203771, 876611608)
    val BATTERY_CANFD = BatteryProtocol("CANFD", 1, 1246777400, 1246765118, 876609560)

    /** 电池读数。 */
    data class BatteryReading(
        val percent: Double,
        val rangeKm: Double?,
        val remainingKwh: Double?,
        val charging: Boolean,
        val protocol: BatteryProtocol?,
    )

    // ---- 旁路 HUD 报文 ----
    /** 距离上限（24 位）。 */
    const val MAX_DISTANCE_METERS = 16777214

    /** 报文头（原文 hex）。 */
    val PACKET_HEADER_ACTIVE = byteArrayOf(0x43, 0xE0.toByte(), 0x00, 0x3A, 0x01, 0x02)
    val PACKET_HEADER_INACTIVE = byteArrayOf(0x43, 0xE0.toByte(), 0x00, 0x3A, 0x01, 0x01)

    /** 仪表导航模式轮询周期（毫秒）。 */
    const val MAP_PAUSE_READ_MILLIS = 1000L

    // ---- 日志原文 ----
    const val LOG_PREPARED = "DiLink 3 cluster display prepared="
    const val LOG_PREPARATION_WILL_RECOVER = "Cluster preparation will recover"
    const val LOG_MODE_WILL_RETRY = "Cluster mode will retry"
    const val LOG_MODE_PENDING = "Cluster mode pending recovery/retry"

    /** 生成 adb 查询命令。 */
    fun readCommand(): String = READ_COMMAND

    /**
     * 判定仪表是否可用。
     *
     * @return null 表示可用（非 OFF）；否则返回问题描述
     */
    fun checkMode(code: Int): String? {
        val m = NaviMode.of(code)
        return when (m) {
            NaviMode.OFF -> "仪表导航模式为 OFF —— 投不上画面先切模式，别急着猜 displayId"
            null -> "未知仪表模式码：$code"
            else -> null
        }
    }

    /** 借屏恢复提示。 */
    fun recoveryNote(): String =
        "借屏前把原厂模式写入账本（键 $JOURNAL_KEY），退出/Crash 后据此还原"

    /** 距离裁剪到 24 位上限。 */
    fun clampDistance(meters: Int): Int = meters.coerceAtMost(MAX_DISTANCE_METERS)
}
