package com.dbtool.carplay.geely.lynk

import android.content.ComponentName
import android.util.Log

/**
 * DBPlay **仪表借屏保持会话 + 旁路 HUD 输出**。
 *
 * ## 来源
 *
 * 从 DiPlay(Lynk) 反编译产物 `hud/OemClusterHoldSession.java`(14KB)、
 * `hud/BydStandaloneHudOutput.java`(17KB)、`hud/BydNavigationOutputs.java`(16KB) 提取。
 *
 * ## 🔥 借屏保持会话（OemClusterHoldSession）
 *
 * 「借屏」（把车机仪表屏临时拿给 App 用）必须成对：
 *
 * ```
 * 借之前：记录宿主原状态 → Journal(target, originalState, lease)
 * 用之中：lease 续约
 * 退出时：还原 originalState
 * ```
 *
 * - `Target.COMPONENT` —— 只借某个组件（如仪表 Activity）
 * - `Target.PACKAGE`   —— 借整包（更彻底，风险更大）
 * - `DISABLED_USER = 3` —— 宿主已被用户禁用，借不到
 *
 * ⚠️ **不还原 originalState = 「借了不还」** → 车机仪表/设置被占死，用户要重启车机。
 * DBPlay 若借屏后异常退出，很可能就是漏了还原。
 *
 * ## 🔥 旁路 HUD 输出（BydStandaloneHudOutput）
 *
 * **不依赖 CarPlay 的独立推屏通道**：通过车机自带调试接收器直接塞帧。
 *
 * ```
 * TARGET = ComponentName("com.byd.clusterdebug",
 *                        "com.byd.clusterdebug.BroadcastReceiverCAN")
 * standaloneHudAvailable= / firmware= / receiver= / version= / system=
 * ```
 *
 * 用处：CarPlay 没连上时也能显示导航（降级体验）。
 *
 * ## 导航输出分发（BydNavigationOutputs）
 *
 * 输入 iAP2 帧 → 转成 `ClusterTurnGuidance` / `BydHudRouteState` 输出。
 * 默认 overlay 导航提示 = **120 秒 / 8 米**（`120000000000L` ns / `8000000000L` ns）。
 * 车速由 `VehicleSpeedSource` 驱动。
 *
 * ⚠️ 未实机验证。`com.byd.clusterdebug` 是比亚迪车机自带组件，吉利 CS11 上不存在。
 */
object LynkClusterHoldSession {

    private const val TAG = "DBPlay-ClusterHold"

    /** 借屏宿主类型。 */
    enum class Target { COMPONENT, PACKAGE }

    /** 宿主不可用原因码。 */
    const val DISABLED_USER = 3

    /** 借屏账本：借什么、原来什么状态、当前租约。 */
    data class Journal(
        val target: Target,
        val originalState: Int,
        val lease: String?,
    )

    /** 旁路 HUD 调试接收器（比亚迪车机自带）。 */
    val STANDALONE_HUD_TARGET = ComponentName(
        "com.byd.clusterdebug",
        "com.byd.clusterdebug.BroadcastReceiverCAN",
    )

    /** 默认 overlay 导航提示：120 秒。 */
    const val DEFAULT_HINT_WINDOW_NS = 120_000_000_000L
    /** 默认 overlay 导航提示：8 米。 */
    const val DEFAULT_HINT_DISTANCE_NS = 8_000_000_000L

    // ---- 日志原文 ----
    const val LOG_STANDALONE_READY = "Standalone navigation ready uid="
    const val LOG_HELPER_NONE = "helper=none"
    const val LOG_STARTUP_CLEAR_RETRY = "Startup clear will retry"
    const val LOG_CANNOT_PERSIST_CLEANUP = "Cannot persist HUD cleanup"

    /**
     * 借屏前置检查。
     *
     * @return null 表示可借；否则返回拒绝原因
     */
    fun checkBeforeAcquire(targetState: Int): String? = when (targetState) {
        DISABLED_USER -> "宿主已被用户禁用（DISABLED_USER=$DISABLED_USER），借不到"
        else -> null
    }

    /**
     * 借屏退出时必须做的事。
     *
     * @return 还原动作说明
     */
    fun restoreOnExit(journal: Journal): String =
        "退出必须还原宿主原状态（target=${journal.target}, originalState=${journal.originalState}），否则车机被占死"

    /**
     * 旁路 HUD 是否可用（需车机自带 clusterdebug 接收器）。
     */
    fun standaloneAvailable(hasReceiver: Boolean, firmware: String?): String =
        "standaloneHudAvailable=$hasReceiver firmware=${firmware ?: "unknown"}"

    /** 生成旁路 HUD 清理提示。 */
    fun cleanupNote(persisted: Boolean): String? =
        if (!persisted) LOG_CANNOT_PERSIST_CLEANUP else null
}
