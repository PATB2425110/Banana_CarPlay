package com.dbtool.carplay.geely.lynk

import android.util.Log

/**
 * DBPlay **比亚迪 HUD / 仪表桥**（DiPlay 的 BYD 系适配）。
 *
 * ## 来源
 *
 * 从 DiPlay(Lynk) 反编译产物 `hud/BydHudBridge.java`(24KB)、
 * `hud/BydClusterBridge.java`(20KB)、`hud/BydHudRouteState.java`(18KB) 提取。
 *
 * ## 🔥 两条完全不同的输出路径
 *
 * ### 1) HUD → SOME/IP 服务（比亚迪私有框架 `com.ts.car.someip.service`）
 *
 * ```
 * SOMEIP_PACKAGE = "com.ts.car.someip.service"
 * SOMEIP_CLASS   = "com.ts.car.someip.service.manager.SomeIpServerService"
 * SOMEIP_ACTION  = "com.ts.car.someip.SomeIpServerService"
 * SOMEIP_TOKEN   = "ts.car.someip.sdk.ISomeIpServerInterface"
 * CALLBACK_TOKEN = "ts.car.someip.sdk.ISomeIpCallback"
 * SERVICE_ID     = 3097367205183488L   ← 硬编码服务 ID
 * HUD_TOPIC      = 1127042368241665L   ← 硬编码话题 ID
 * TX_REGISTER_CALLBACK=1  TX_START_SERVICE=4  TX_FIRE_EVENT=6
 * ```
 *
 * 发送节奏：**300ms 重复一帧，field2 固定为 2**。
 * ⚠️ DiPlay 原话：`gateway accepted guidance; rendering unconfirmed`
 * —— SOME/IP 网关**只收不回执**，发出去了不代表 HUD 真显示。
 *
 * ### 2) 仪表 → 高德广播（不走 Presentation！）
 *
 * ```
 * AMAP_ACTION     = "AUTONAVI_STANDARD_BROADCAST_SEND"   ← 高德车机版广播
 * DILINK3_DISPLAY = "fission_bg_xdjaVirtualSurface"      ← DiLink3 虚拟屏名
 * KEY_GUIDANCE = 10001   KEY_STATE = 10019   STATE_ENDED = 9
 * KEEPALIVE_TICKS = 5
 * ```
 *
 * 🔥 **`KEEPALIVE_TICKS = 5`**：仪表内容必须**周期性续约**，否则车机判定超时清屏。
 * DBPlay 若「投上去一会儿没画面」，除了 surface 被抢，也可能是**没续约**。
 *
 * ## 虚拟屏命名约定
 *
 * `fission_bg_xdjaVirtualSurface` 说明车机系（BYD/吉利）用**固定虚拟屏名**暴露仪表屏。
 * 可先在 `dumpsys display` 里找同类名字（`*VirtualSurface*` / `*virtual*`），
 * 而不是靠分辨率猜。
 *
 * ⚠️ 未实机验证。SOME/IP 需要车厂组件的 binder 权限；CS11（吉利）上无此服务。
 */
object LynkBydHudBridge {

    private const val TAG = "DBPlay-BYD-HUD"

    // ---- SOME/IP 服务标识（硬编码，原文值）----
    const val SOMEIP_PACKAGE = "com.ts.car.someip.service"
    const val SOMEIP_CLASS = "com.ts.car.someip.service.manager.SomeIpServerService"
    const val SOMEIP_ACTION = "com.ts.car.someip.SomeIpServerService"
    const val SOMEIP_TOKEN = "ts.car.someip.sdk.ISomeIpServerInterface"
    const val CALLBACK_TOKEN = "ts.car.someip.sdk.ISomeIpCallback"

    /** 硬编码服务 ID。 */
    const val SERVICE_ID = 3097367205183488L

    /** 硬编码 HUD 话题 ID。 */
    const val HUD_TOPIC = 1127042368241665L

    // ---- SOME/IP 事务码 ----
    const val TX_REGISTER_CALLBACK = 1
    const val TX_START_SERVICE = 4
    const val TX_FIRE_EVENT = 6

    /** HUD 重发周期（原文：300ms repeat，field2 固定 2）。 */
    const val HUD_REPEAT_MILLIS = 300L
    const val HUD_FIELD2_FIXED = 2

    /** HUD 图标资源目录。 */
    const val ICON_ASSET_DIR = "byd-hud-icons"

    // ---- 仪表高德广播 ----
    const val AMAP_ACTION = "AUTONAVI_STANDARD_BROADCAST_SEND"
    const val DILINK3_DISPLAY = "fission_bg_xdjaVirtualSurface"
    const val KEY_GUIDANCE = 10001
    const val KEY_STATE = 10019
    const val STATE_ENDED = 9

    /** 🔥 仪表保活 tick 数（不发就清屏）。 */
    const val KEEPALIVE_TICKS = 5

    const val FLAG_RECEIVER_INCLUDE_BACKGROUND = 16777216

    // ---- 日志原文 ----
    const val LOG_SOMEIP_READY = "SOME/IP HAL ready="
    const val LOG_CANNOT_BIND = "cannot bind SOME/IP service"
    const val LOG_GATEWAY_ACCEPTED =
        "HUD profile (300ms repeat, fixed field2=2): gateway accepted guidance; rendering unconfirmed"
    const val LOG_ICONS_UNAVAILABLE = "HUD icons unavailable"
    const val LOG_CLUSTER_GUIDANCE_SENT = "cluster guidance sent "
    const val LOG_CLUSTER_GUIDANCE_ENDED = "cluster guidance ended"
    const val LOG_CLUSTER_BROADCAST_FAILED = "cluster broadcast failed"

    /** 导航机动指令数据（对应 iAP2 的 RouteGuidanceUpdates）。 */
    data class Maneuver(
        val type: String?,
        val drivingSide: String?,
        val afterRoad: String?,
    )

    /** 路由状态（HUD 与仪表共用同一个 route 实例）。 */
    data class RouteState(
        val maneuver: Maneuver?,
        val distanceMeters: Int,
        val remainingSeconds: Int,
        val state: Int,
    )

    /**
     * 判定虚拟屏名是否像仪表屏。
     *
     * 车机系（BYD/吉利）用固定命名暴露仪表虚拟屏，例如
     * `fission_bg_xdjaVirtualSurface`。
     */
    fun looksLikeClusterVirtualSurface(displayName: String?): Boolean {
        if (displayName.isNullOrEmpty()) return false
        val n = displayName.lowercase()
        return n.contains("virtualsurface") || n.contains("virtual_surface") ||
            n.contains("cluster") || n.contains("fission")
    }

    /** HUD 发送提示：网关无回执，只能靠用户肉眼确认。 */
    fun hudSendNote(): String =
        "SOME/IP 网关只收不回执 —— 日志显示 accepted 不代表 HUD 真显示"

    /** 仪表保活提示。 */
    fun keepAliveNote(): String =
        "仪表内容需每 $KEEPALIVE_TICKS tick 续约，否则车机判定超时清屏"
}
