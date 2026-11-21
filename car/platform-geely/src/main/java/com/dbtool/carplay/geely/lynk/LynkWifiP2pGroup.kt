package com.dbtool.carplay.geely.lynk

import android.net.wifi.p2p.WifiP2pGroup
import android.util.Log
import java.util.concurrent.TimeUnit

/**
 * DBPlay **WiFi P2P 群组管理**（无线 CarPlay 的直连主路，替代热点）。
 *
 * ## 来源
 *
 * 从 DiPlay(Lynk) 反编译产物 `network/WifiP2pGroupManager.java`(86KB，全包最大) 提取。
 *
 * ## 为什么比热点更值得用
 *
 * 无线 CarPlay 有两条路：**① 车机开热点让 iPhone 连**（DBPlay 现在用的）、
 * **② WiFi Direct（P2P）直连**（DiPlay 主路）。P2P 直连不依赖车机热点实现，
 * 车机只要支持 WiFi Direct 即可 —— **对「复用车机热点」失败的车是更好的兜底**。
 *
 * ## 三个必须处理的坑（DiPlay 原文）
 *
 * 1. **owner 被车机抢**：`Wi-Fi P2P device became a group client instead of owner`
 *    → 必须 `reclaiming retained owned group` 抢回 owner，否则 iPhone 连不上。
 * 2. **群组归属冲突**：`Wi-Fi P2P cleanup skipped=another_app_owns_group`
 *    → 别的 App 占着群组时不能强行 remove。
 * 3. **成功判据不含糊**：`proof=authenticated_first_frame`
 *    → 建了群组 ≠ 成功，必须 **认证通过 + 首帧渲染** 才算。
 *
 * ## 前置条件（preflight 五项）
 *
 * `wifiEnabled` / `locationEnabled` / `permissionGranted` / `locationAccessMode` / `fiveGhzSupported`
 *
 * 三条原文提示：
 * - `Allow Nearby devices for DiPlay in the head unit's app permissions`
 * - `Allow precise Location for DiPlay in the head unit's app permissions`
 * - `Turn on Wi-Fi in the head unit's settings, then reconnect`
 *
 * ## 常量（DiPlay 原文）
 *
 * | 常量 | 值 |
 * |---|---|
 * | `REMOVE_GROUP_TIMEOUT_MILLIS` | 2000 |
 * | `REQUEST_POLL_NANOS` | 500ms |
 * | SSID 前缀 | `carplay-` |
 * | token 字母表 | `ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789`（去掉易混字符 I/l/O/0/1）|
 *
 * ⚠️ 未实机验证。P2P 凭据 API 需 **Android 10 (API 29)+**；本车机是 Android 9 (SDK 28)，
 * 故 DiPlay 在 CS11 上走的是热点路而非 P2P 路 —— **这条留给 Android 10+ 的车机**。
 */
class LynkWifiP2pGroup {

    companion object {
        private const val TAG = "DBPlay-WifiP2p"

        const val REMOVE_GROUP_TIMEOUT_MILLIS = 2000L
        const val REQUEST_POLL_NANOS = 500L * 1_000_000
        const val SSID_PREFIX = "carplay-"

        /** 去掉了 I/l/O/0/1 等易混字符，便于车机屏上人工核对。 */
        const val TOKEN_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"

        /** 成功证明（DiPlay 原文值）。 */
        const val PROOF_AUTHENTICATED_FIRST_FRAME = "authenticated_first_frame"

        /** P2P 凭据最低 API 级别。 */
        const val MIN_API_FOR_CREDENTIALS = 29 // Android 10
    }

    /** 启动前置检查快照（DiPlay 的 preflight 字段）。 */
    data class Preflight(
        val wifiEnabled: Boolean,
        val locationEnabled: Boolean,
        val permissionGranted: Boolean,
        val locationAccessMode: String,
        val stationMHz: Int,
        val stationState: String,
        val reportedStationMHz: Int,
        val fiveGhzSupported: Boolean,
    ) {
        /** 返回人类可读的失败原因，null = 通过。 */
        fun failureReason(): String? = when {
            !wifiEnabled -> "Turn on Wi-Fi in the head unit's settings, then reconnect"
            !permissionGranted ->
                "Allow Nearby devices for DiPlay in the head unit's app permissions"
            !locationEnabled ->
                "Allow precise Location for DiPlay in the head unit's app permissions"
            else -> null
        }
    }

    /** 群组状态。 */
    enum class GroupState { UNAVAILABLE, ABSENT, PRESENT, CALLBACK_TIMEOUT, INTERRUPTED }

    /** 群组归属。 */
    enum class Ownership { OWNER, CLIENT, UNKNOWN }

    /**
     * 判定群组归属（DiPlay: `became a group client instead of owner`）。
     *
     * @param isGroupOwner 系统报告的 isGroupOwner
     */
    fun ownership(isGroupOwner: Boolean): Ownership =
        if (isGroupOwner) Ownership.OWNER else Ownership.CLIENT

    /**
     * 是否需要抢回 owner。
     *
     * DiPlay: `Wi-Fi P2P reclaiming retained owned group`
     * —— 车机把自己变成 owner 后，DiPlay 会把 owner 抢回来。
     */
    fun shouldReclaimOwnership(state: GroupState, ownership: Ownership): Boolean {
        if (state == GroupState.PRESENT && ownership == Ownership.CLIENT) {
            Log.w(TAG, "Wi-Fi P2P device became a group client instead of owner; reclaiming retained owned group")
            return true
        }
        return false
    }

    /**
     * 是否可清理群组。
     *
     * DiPlay: `Wi-Fi P2P cleanup skipped=another_app_owns_group`
     * —— 别的 App 占着群组时不能 remove。
     */
    fun canRemoveGroup(ownedByUs: Boolean): Boolean {
        if (!ownedByUs) {
            Log.i(TAG, "Wi-Fi P2P cleanup skipped=another_app_owns_group")
        }
        return ownedByUs
    }

    /**
     * 信道协商结果判定。
     *
     * DiPlay: `Wi-Fi P2P channel requestedMHz=… actualMHz=… matched=…`
     * 及 `The car selected channel … instead.` —— 车机自选信道时记录但继续。
     */
    fun channelNegotiation(requestedMHz: Int, actualMHz: Int): String {
        val matched = requestedMHz == actualMHz
        val msg = "Wi-Fi P2P channel requestedMHz=$requestedMHz actualMHz=$actualMHz matched=$matched"
        if (!matched) {
            Log.w(TAG, "$msg; The car selected channel $actualMHz instead.")
        } else {
            Log.i(TAG, msg)
        }
        return msg
    }

    /** 频段名（DiPlay 原文："5 GHz" / "2.4 GHz"）。 */
    fun bandName(frequencyMHz: Int): String = when {
        frequencyMHz >= 5000 -> "5 GHz"
        else -> "2.4 GHz"
    }

    /**
     * 无线连接是否确认成功（DiPlay 成功判据）。
     *
     * 原文：`remembered saved=… proof=authenticated_first_frame`
     *      `remembered cleared=session_unconfirmed`
     *
     * @return true = 保留记忆（成功）；false = 清掉重来
     */
    fun confirmSession(authenticated: Boolean, firstFrameRendered: Boolean): Boolean {
        val confirmed = authenticated && firstFrameRendered
        if (confirmed) {
            Log.i(TAG, "Wi-Fi P2P remembered saved=… proof=$PROOF_AUTHENTICATED_FIRST_FRAME")
        } else {
            Log.w(TAG, "Wi-Fi P2P remembered cleared=session_unconfirmed")
        }
        return confirmed
    }

    /** 生成随机 SSID/口令 token。 */
    fun randomToken(length: Int): String {
        val sb = StringBuilder(length)
        repeat(length) {
            sb.append(TOKEN_ALPHABET[(Math.random() * TOKEN_ALPHABET.length).toInt()])
        }
        return sb.toString()
    }

    /** 生成 SSID（前缀 carplay- + token）。 */
    fun buildSsid(): String = SSID_PREFIX + randomToken(6)

    /** 移除群组的超时时间。 */
    fun removeGroupTimeoutMillis(): Long = REMOVE_GROUP_TIMEOUT_MILLIS

    /** 请求轮询间隔（纳秒）。 */
    fun requestPollNanos(): Long = REQUEST_POLL_NANOS

    /** 清理超时提示（DiPlay 原文）。 */
    fun removeTimeoutMessage(ms: Long): String = "Timed out after $ms ms waiting for Wi-Fi P2P group creation"
}
