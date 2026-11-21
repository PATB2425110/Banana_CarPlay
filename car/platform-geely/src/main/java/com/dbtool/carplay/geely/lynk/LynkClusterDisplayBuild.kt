package com.dbtool.carplay.geely.lynk

import android.util.Log

/**
 * DBPlay **仪表建屏命令 + 电池读取命令集 + 能力目录扫描**。
 *
 * ## 来源
 *
 * 从 DiPlay(Lynk) 反编译产物 `hud/BydDiLink3ClusterMode.java`(5KB)、
 * `hud/BydBattery.java`(12KB)、`hud/BydBatteryStatus.java`(11KB)、
 * `hud/Byd13CatalogProbeMain.java`(8KB)、`hud/BydFieldProbeResult.java`(7KB)、
 * `hud/BydFactoryNavigationOutput.java`(7KB)、`hud/BydVehicleCapabilities.java`(8KB)、
 * `hud/BydStandaloneNavigationBridge.java`(7KB)、`hud/BydHudGuidance.java`(6KB)、
 * `hud/NavigationOutputWorker.java`(6KB) 提取。
 *
 * ## 🔥 仪表屏不是白来的：要叫车机建
 *
 * ```
 * service call AutoContainer 2 i32 1000 i32 <mode>
 * ```
 *
 * | 模式 | 值 | 含义 |
 * |---|---|---|
 * | `PROJECTION` | 17 | 投屏用屏（CarPlay 投仪表用这个） |
 * | `SIMPLE_NAVIGATION` | 39 | 简易导航 |
 * | `STOCK` | 18 | 原厂 |
 *
 * **仪表黑屏的正解顺序**：先 `service call AutoContainer` **建屏** → 拿到屏 → 挂窗口。
 * DBPlay 若直接挂窗口，屏根本不存在 → surface 被抢 → `detached from surface` → 黑屏。
 *
 * ## 🔥 事务码含义（从 `BydBattery` 读出）
 *
 * | 事务码 | 含义 |
 * |---|---|
 * | `5` | 读 **int** |
 * | `7` | 读 **float** |
 *
 * 读电量几个实际命令（原文）：
 * ```
 * service call autoservice 7 i32 1005 i32 882901008   ← 剩余电量
 * service call autoservice 7 i32 1014 i32 …           ← 读 float 字段
 * service call autoservice 5 i32 1014 i32 …           ← 读 int 字段
 * ```
 *
 * 车辆协议（决定 CAN / CANFD）：`getprop ro.car.protocol`
 *
 * ## 电池轮询节奏（BydBatteryStatus）
 *
 * `READ_MILLIS=30000`（30 秒一读）、`IDLE_MILLIS=120000`（空闲 2 分钟停读省电）。
 *
 * ## 能力目录扫描（Byd13CatalogProbeMain）
 *
 * 独立进程主类，`HEADER = "DIPLAY_BYD13_CATALOG_V1"`，
 * 反射扫描 `android.hardware.bydauto.BYDAutoFeatureIds` 取出**本车全部可用字段**。
 * → **换车机时先跑它，拿到本车真实字段表，再套 [LynkVehicleFields] 的读法。**
 *
 * ⚠️ 未实机验证。`AutoContainer` / `autoservice` 是比亚迪车机服务名，吉利 CS11 上是别的名字，
 * 但**「先建屏再挂窗口」这个顺序是通用的**。
 */
object LynkClusterDisplayBuild {

    private const val TAG = "DBPlay-ClusterBuild"

    // ---- 🔥 建屏命令 ----
    /** 车机容器服务名（由它负责创建虚拟屏）。 */
    const val AUTO_CONTAINER_SERVICE = "AutoContainer"

    /** 建屏命令的事务码。 */
    const val CREATE_DISPLAY_TRANSACTION = 2

    /** 建屏命令的设备号。 */
    const val CREATE_DISPLAY_DEVICE = 1000

    /** 车机显示模式。 */
    enum class DisplayMode(val code: Int, val label: String) {
        PROJECTION(17, "投屏（CarPlay 投仪表用）"),
        STOCK(18, "原厂"),
        SIMPLE_NAVIGATION(39, "简易导航"),
    }

    /**
     * 生成建屏命令（adb shell 可直接跑）。
     *
     * @param mode 要建的模式（投仪表用 PROJECTION）
     */
    fun buildCreateDisplayCommand(mode: DisplayMode): String =
        "service call $AUTO_CONTAINER_SERVICE $CREATE_DISPLAY_TRANSACTION i32 $CREATE_DISPLAY_DEVICE i32 ${mode.code}"

    // ---- 车辆字段读取事务码 ----
    /** 读 int 的事务码。 */
    const val READ_INT = 5
    /** 读 float 的事务码。 */
    const val READ_FLOAT = 7

    /** 车辆协议属性名（决定 CAN / CANFD）。 */
    const val PROP_CAR_PROTOCOL = "ro.car.protocol"

    /** 车辆服务名。 */
    const val AUTOSERVICE = "autoservice"

    /** 电量相关设备号。 */
    const val DEVICE_BATTERY = 1005
    const val DEVICE_STATISTIC = 1014
    const val DEVICE_AUX = 1009

    /** 剩余电量字段（原文）。 */
    const val FID_REMAINING = 882901008

    /** 电池 BMS 充电状态码。 */
    const val BMS_CHARGING = 1

    /** 生成读字段命令。 */
    fun buildReadCommand(device: Int, fid: Int, asFloat: Boolean): String =
        "service call $AUTOSERVICE ${if (asFloat) READ_FLOAT else READ_INT} " +
            "i32 $device i32 $fid"

    /** 读电量命令（原文）。 */
    fun readRemainingCommand(): String =
        "service call $AUTOSERVICE $READ_FLOAT i32 $DEVICE_BATTERY i32 $FID_REMAINING"

    /** 读车辆协议。 */
    fun readProtocolCommand(): String = "getprop $PROP_CAR_PROTOCOL"

    // ---- 电池轮询节奏 ----
    /** 轮询周期（毫秒）。 */
    const val BATTERY_READ_MILLIS = 30000L
    /** 空闲多久停读（毫秒）。 */
    const val BATTERY_IDLE_MILLIS = 120000L

    // ---- 能力目录扫描 ----
    /** 扫描输出头。 */
    const val CATALOG_HEADER = "DIPLAY_BYD13_CATALOG_V1"

    /** 待反射扫描的字段根类（原文前两个）。 */
    val CATALOG_ROOTS = listOf(
        "android.hardware.bydauto.BYDAutoFeatureIds",
        "android.hardware.bydauto.BYDAutoConstants",
    )

    /** HUD 更新/清理失败态。 */
    const val LOG_HUD_RETRY = "HUD update/cleanup will retry"

    /** 能力快照。 */
    data class Capabilities(
        val fields: Map<String, Double>,
        val catalogAvailable: Boolean,
        val firmwareKey: String?,
        val detectedAtMillis: Long,
    )

    /** 单字段探测结果。 */
    data class FieldProbeResult(
        val field: String,
        val supported: Boolean,
        val value: Double?,
        val failure: String?,
    )

    /**
     * 仪表投屏的正确顺序（DBPlay 应照此实现）。
     *
     * @return 步骤说明
     */
    fun correctOrder(): List<String> = listOf(
        "1. service call $AUTO_CONTAINER_SERVICE $CREATE_DISPLAY_TRANSACTION i32 $CREATE_DISPLAY_DEVICE i32 ${DisplayMode.PROJECTION.code}  ← 先建屏",
        "2. dumpsys display 找到刚建的 virtual surface（别猜 resolution）",
        "3. 借原厂仪表页（pm disable --user 0 <厂商 automap 的 MeterActivity>）",
        "4. 挂视频窗口到该 surface",
        "5. 每 KEEPALIVE_TICKS 续约 + 保活",
        "6. 退出：按 Journal 还原原厂页与模式",
    )

    /** 建屏失败提示。 */
    fun buildFailureNote(): String =
        "屏是叫车机建的，不是自动有的；直接挂窗口 → surface 被抢 → detached from surface → 黑屏"
}
