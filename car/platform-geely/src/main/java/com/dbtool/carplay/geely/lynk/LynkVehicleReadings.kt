package com.dbtool.carplay.geely.lynk

import android.util.Log

/**
 * DBPlay **hud 域残余：车速/挡位读法 + 官方箭头换算表 + 高德适配 + 字段缓存**。
 *
 * ## 来源
 *
 * 从 DiPlay(Lynk) 反编译产物 `hud/BydWheelSpeed.java`(4KB)、`hud/BydParkedState.java`(2KB)、
 * `hud/BydFactoryTurnCode.java`(2KB)、`hud/BydAmapAdapter.java`(5KB)、
 * `hud/BydClusterSongTool.java`(6KB)、`hud/BydVehicleFieldStore.java`(11KB)、
 * `hud/BydFieldSource.java`(2KB)、`hud/BydDiLink3GuidanceText.java`(3KB)、
 * `hud/BydHudProtocol.java`(1KB)、`hud/BydOemClusterHold.java`(4KB)、
 * `hud/BydHudRouteChange.java`(2KB)、`hud/BydVehicleProbeOutcome.java`(6KB) 提取。
 *
 * ## 🔥 车速 / 挡位怎么读（原文命令，adb 可直接跑）
 *
 * ```
 * 车速  service call autoservice 7 i32 1013 i32 -1807745016   ← 事务码 7 = 读 float
 * 挡位  service call autoservice 5 i32 1011 i32 555745336    ← 事务码 5 = 读 int
 * ```
 *
 * 挡位 `PARK = 1` → **判断「驻车才允许看视频」就靠它**（对应开关 `video_while_parked`）。
 *
 * ## 🔥 第三张表：官方箭头换算表（BydFactoryTurnCode）
 *
 * ```
 * codes[] = {0,0,1,2,3,5,7,8,9,11,45,13,24,46,47,48,49,14,23,10,12,15,18,20,22,16,17,19,21}
 *            索引 = 车机帧内码，值 = iAP2 标准码
 * ```
 *
 * **这是唯一一张「帧内码 → 标准码」的官方换算表**，有它就不用再猜前两张表怎么对应。
 *
 * ## 高德适配（BydAmapAdapter）
 *
 * | 适配器 | 包名 | 兼容模式 |
 * |---|---|---|
 * | `BYD` | `com.byd.amapservice` | false |
 * | `DILINK3` | `com.example.amapservice` | true |
 *
 * ## 歌曲写入字段（BydClusterSongTool）
 *
 * `DEVICE=1007`、`SOURCE=871366704`、`STATE=1138753546`、`TEXT=1140527112`。
 *
 * ## 字段缓存（BydVehicleFieldStore）
 *
 * `PREFS="diplay_byd_vehicle_fields"`、`SCHEMA=1`；
 * 键：`catalog` / `detected_at` / `firmware` / `schema`。
 * **固件变了就作废重探**：`probe belongs to another firmware`。
 *
 * ## 字段来源三级（BydFieldSource）
 *
 * `FIRMWARE`（固件表）→ `KNOWN_13`（已知 13 款车型）→ `DEFAULT`（兜底）。
 *
 * ⚠️ 未实机验证。命令里的服务名/设备号是比亚迪体系；CS11 需换成吉利对应服务。
 */
object LynkVehicleReadings {

    private const val TAG = "DBPlay-Readings"

    // ---- 🔥 车速 / 挡位命令（原文）----
    /** 车速读取命令（float）。 */
    const val CMD_SPEED = "service call autoservice 7 i32 1013 i32 -1807745016"

    /** 挡位读取命令（int）。 */
    const val CMD_GEAR = "service call autoservice 5 i32 1011 i32 555745336"

    /** 挡位 = 驻车。 */
    const val GEAR_PARK = 1

    // ---- 🔥 官方箭头换算表 ----
    /**
     * 车机帧内码 → iAP2 标准码。
     *
     * 索引即帧内码，值即标准码。
     */
    val FACTORY_TURN_CODES = intArrayOf(
        0, 0, 1, 2, 3, 5, 7, 8, 9, 11, 45, 13, 24, 46, 47, 48, 49,
        14, 23, 10, 12, 15, 18, 20, 22, 16, 17, 19, 21,
    )

    /**
     * 用官方表把车机帧内码换成 iAP2 标准码。
     *
     * @return 标准码；越界返回 null
     */
    fun toStandardCode(factoryCode: Int): Int? =
        FACTORY_TURN_CODES.getOrNull(factoryCode)

    // ---- 高德适配 ----
    /** 高德适配器定义。 */
    data class AmapAdapter(
        val label: String,
        val id: Int,
        val amapServicePackage: String,
        val compat: Boolean,
    )

    val AMAP_BYD = AmapAdapter("BYD", 0, "com.byd.amapservice", false)
    val AMAP_DILINK3 = AmapAdapter("DILINK3", 1, "com.example.amapservice", true)

    // ---- 歌曲写入字段 ----
    const val SONG_DEVICE = 1007
    const val SONG_FID_SOURCE = 871366704
    const val SONG_FID_STATE = 1138753546
    const val SONG_FID_TEXT = 1140527112

    // ---- 字段缓存 ----
    const val FIELDS_PREFS = "diplay_byd_vehicle_fields"
    const val FIELDS_SCHEMA = 1
    const val KEY_CATALOG = "catalog"
    const val KEY_DETECTED_AT = "detected_at"
    const val KEY_FIRMWARE = "firmware"
    const val KEY_SCHEMA = "schema"

    const val LOG_WRONG_FIRMWARE = "probe belongs to another firmware"
    const val LOG_CANNOT_PERSIST = "Could not persist BYD vehicle field probe"

    /** 字段来源。 */
    enum class FieldSource { FIRMWARE, KNOWN_13, DEFAULT }

    /** 借屏持有范围。 */
    enum class OemClusterHold { OFF, COMPONENT, PACKAGE }

    /** 路由变化类型。 */
    enum class RouteChange { NONE, GUIDANCE, CLEAR }

    // ---- HUD 协议常量（复述）----
    /** SOME/IP 服务话题。 */
    const val SERVICE_TOPIC = 3097367205183488L
    /** 导航话题。 */
    const val NAVIGATION_TOPIC = 1127042368241665L
    /** 重发周期（毫秒）。 */
    const val HUD_REPEAT_MILLIS = 300L

    /** 驻车判定（用于 video_while_parked）。 */
    fun isParked(gear: Int): Boolean = gear == GEAR_PARK

    /** 固件变了字段表作废。 */
    fun validateCachedFirmware(cachedFirmware: String?, currentFirmware: String?): String? =
        if (cachedFirmware != null && currentFirmware != null && cachedFirmware != currentFirmware) {
            LOG_WRONG_FIRMWARE
        } else null

    /** 距离文案（DiLink3 风格）。 */
    fun formatDistance(meters: Int): String {
        val km = meters / 1000.0
        return if (km >= 1.0) String.format("%.1f km", km) else "$meters m"
    }
}
