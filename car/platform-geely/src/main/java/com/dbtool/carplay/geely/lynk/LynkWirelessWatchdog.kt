package com.dbtool.carplay.geely.lynk

import android.util.Log

/**
 * DBPlay **无线连接看门狗与超时预算**。
 *
 * ## 来源
 *
 * 从 DiPlay(Lynk) 反编译产物 `orchestration/CarPlayController.java`(317KB)、
 * `orchestration/FirstTcpWatchdog.java`、`orchestration/WirelessConnectionProof.java` 提取。
 *
 * ## 为什么要这套预算
 *
 * 无线 CarPlay 建链分多段（热点 → iAP2 认证 → CarPlay StartSession → 第一条 AirPlay TCP
 * → 握手 → 首帧渲染），**每一段都必须有超时**，否则会永久卡住、表现为「连上了但一直黑」。
 * DiPlay 每段都设了明确预算，超时就带**失败原因**退出（而不是静默挂死）。
 *
 * ## 超时预算表（DiPlay 原文常量值）
 *
 * | 阶段 | 预算 |
 * |---|---|
 * | 无线交接（handoff） | 45s |
 * | 热点启动 | 60s |
 * | 蓝牙 RFCOMM 连接 | 15s |
 * | WiFi P2P 启动 | 20s |
 * | VPN 隧道连接 | 10s |
 * | 配对 | 300s |
 * | 控制循环 | 300s |
 * | 权限轮询 | 120s |
 * | 线程池关闭 | 2s |
 * | 定位控制循环 | 24h |
 *
 * ## 首条 TCP 看门狗（FirstTcpWatchdog）
 *
 * ```
 * StartSession atNs=…        ← iAP2 发出 CarPlay StartSession
 * first TCP atNs=…           ← 收到第一条 AirPlay TCP（成功）
 * session established atNs=… ← 会话建立
 * first TCP timeout atNs=…   ← 超时（失败）
 * ```
 *
 * 失败原因（原文）：`FIRST_TCP_TIMEOUT = "No AirPlay TCP after CarPlay StartSession"`。
 *
 * ⚠️ 未实机验证。这些是 DiPlay 的取值，DBPlay 可按需调整。
 */
class LynkWirelessWatchdog {

    companion object {
        private const val TAG = "DBPlay-WirelessWD"

        // ---- 超时预算（毫秒，DiPlay 原文值）----
        const val WIRELESS_HANDOFF_TIMEOUT_MILLIS = 45_000L
        const val HOTSPOT_START_TIMEOUT_MILLIS = 60_000L
        const val RFCOMM_CONNECT_TIMEOUT_MILLIS = 15_000L
        const val WIFI_P2P_START_TIMEOUT_MILLIS = 20_000L
        const val VPN_CONNECT_TIMEOUT_MILLIS = 10_000L
        const val PAIR_TIMEOUT_MILLIS = 300_000L
        const val CONTROL_LOOP_TIMEOUT_MILLIS = 300_000L
        const val PERMISSION_POLL_TIMEOUT_MILLIS = 120_000L
        const val EXECUTOR_CLOSE_TIMEOUT_MILLIS = 2_000L
        const val LOCATION_CONTROL_LOOP_TIMEOUT_MILLIS = 86_400_000L

        /** 无线失败原因（枚举名 → 原文说明）。 */
        const val FAIL_FIRST_TCP_TIMEOUT =
            "No AirPlay TCP after CarPlay StartSession"
    }

    /** 无线启动失败类型。 */
    enum class WirelessStartupFailure {
        FIRST_TCP_TIMEOUT,
        HANDOFF_TIMEOUT,
        HOTSPOT_TIMEOUT,
        AUTHENTICATION_FAILED,
        UNKNOWN,
    }

    /** 看门狗事件（对应 DiPlay 的四条日志）。 */
    sealed class Event {
        data class StartSessionSent(val atNs: Long) : Event()
        data class FirstTcp(val atNs: Long) : Event()
        data class SessionEstablished(val atNs: Long) : Event()
        data class FirstTcpTimeout(val atNs: Long) : Event()
    }

    private val events = mutableListOf<Event>()
    @Volatile private var terminated = false

    /** 记录 iAP2 已经发出 StartSession。 */
    fun onStartSessionSent(atNs: Long) {
        events += Event.StartSessionSent(atNs)
        Log.i(TAG, "StartSession atNs=$atNs")
    }

    /** 记录收到第一条 AirPlay TCP。 */
    fun onFirstTcp(atNs: Long) {
        events += Event.FirstTcp(atNs)
        Log.i(TAG, "first TCP atNs=$atNs")
    }

    /** 记录会话建立。 */
    fun onSessionEstablished(atNs: Long) {
        events += Event.SessionEstablished(atNs)
        Log.i(TAG, "session established atNs=$atNs")
    }

    /** 到达超时：若尚未收到首条 TCP，则判失败。 */
    fun onTimeout(atNs: Long): WirelessStartupFailure? {
        if (terminated) return null
        val gotTcp = events.any { it is Event.FirstTcp || it is Event.SessionEstablished }
        if (gotTcp) {
            Log.i(TAG, "timeout fired but TCP already arrived; ignoring")
            return null
        }
        events += Event.FirstTcpTimeout(atNs)
        Log.w(TAG, "first TCP timeout atNs=$atNs")
        return WirelessStartupFailure.FIRST_TCP_TIMEOUT
    }

    /** 终止看门狗（会话结束/清理时）。 */
    fun terminate() {
        terminated = true
        Log.i(TAG, "watchdog terminated; events=${events.size}")
    }

    /** 是否已终止。 */
    fun isTerminated(): Boolean = terminated

    /** 事件轨迹（用于诊断上报）。 */
    fun trace(): List<Event> = events.toList()

    /**
     * 无线连接成功证明（WirelessConnectionProof）。
     *
     * DiPlay 要求 **三个条件同时满足** 才算无线真正成功：
     * `authenticated` + `rendered（首帧已渲染）` + `confirmation`。
     */
    class Proof {
        @Volatile var authenticated = false
            private set
        @Volatile var rendered = false
            private set

        fun markAuthenticated() { authenticated = true }
        fun markRendered() { rendered = true }

        /** 三条件都满足才确认成功。 */
        fun confirmIfReady(): Boolean {
            val ok = authenticated && rendered
            Log.i(TAG, "wireless proof authenticated=$authenticated rendered=$rendered → $ok")
            return ok
        }

        fun clear() {
            authenticated = false
            rendered = false
        }
    }
}
