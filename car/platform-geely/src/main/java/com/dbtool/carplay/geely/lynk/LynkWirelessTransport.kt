package com.dbtool.carplay.geely.lynk

import android.util.Log

/**
 * DBPlay **无线建链地基**（Bonjour 发现 + VPN 隧道 + IPv6 NCM 桥）。
 *
 * ## 来源
 *
 * 从 DiPlay(Lynk) 反编译产物 `network/CarPlayBonjour.java`(61KB)、
 * `network/CarPlayVpnService.java`(55KB)、`network/Ipv6NcmBridge.java`(16KB)、
 * `network/AirPlayPortSelector.java` 提取。
 *
 * ## 为什么掉线要从这里查
 *
 * 无线 CarPlay 的建链地基层是：**Bonjour 广播/发现 → VPN 隧道建立 → AirPlay TCP 接入**。
 * 任何一环没拆干净，重连就会「连上又掉」。DiPlay 明确处理了三件事：
 *
 * 1. **服务广播**：`_airplay._tcp`（音视频）与 `_carplay-ctrl._tcp`（控制通道）两个服务类型；
 * 2. **旧隧道替换**：`replacing stale NCM/VPN attachment` —— 每次重连先拆旧隧道；
 * 3. **端口占用回退**：`AirPlay port N is in use; listening on M instead`。
 *
 * ## Bonjour 超时/重试常量（DiPlay 原文值）
 *
 * | 常量 | 值 |
 * |---|---|
 * | `CONNECT_TIMEOUT_MILLIS` | 3000 |
 * | `READ_TIMEOUT_MILLIS` | 3000 |
 * | `RESOLVE_TIMEOUT_MILLIS` | 10000 |
 * | `RESOLVE_ATTEMPTS` | 3 |
 * | `RESOLVE_RETRY_DELAY_MILLIS` | 250 |
 * | `MAX_PROBE_ATTEMPTS` | 7 |
 * | `PROBE_RETRY_DELAY_MILLIS` | 1500 |
 * | `JOIN_TIMEOUT_MILLIS` | 2000 |
 * | `WORKER_POLL_MILLIS` | 500 |
 *
 * ⚠️ 未实机验证。VPN 隧道需要 `BIND_VPN_SERVICE` 权限且车机 ROM 可能限制。
 */
class LynkWirelessTransport {

    companion object {
        private const val TAG = "DBPlay-Wireless"

        // ---- Bonjour 服务类型 ----
        const val SERVICE_AIRPLAY = "_airplay._tcp"
        const val SERVICE_CARPLAY_CTRL = "_carplay-ctrl._tcp"
        const val SERVICE_AIRPLAY_LOCAL = "_airplay._tcp.local."
        const val SERVICE_CARPLAY_CTRL_LOCAL = "_carplay-ctrl._tcp.local."

        // ---- Bonjour 超时/重试（DiPlay 原文）----
        const val CONNECT_TIMEOUT_MILLIS = 3000
        const val READ_TIMEOUT_MILLIS = 3000
        const val RESOLVE_TIMEOUT_MILLIS = 10000L
        const val RESOLVE_ATTEMPTS = 3
        const val RESOLVE_RETRY_DELAY_MILLIS = 250L
        const val MAX_PROBE_ATTEMPTS = 7
        const val PROBE_RETRY_DELAY_MILLIS = 1500L
        const val JOIN_TIMEOUT_MILLIS = 2000L
        const val WORKER_POLL_MILLIS = 500L

        // ---- VPN / NCM ----
        const val LINK_LOCAL_PREFIX = "fe80::"
        const val HOST_MAC_BYTES = 6
        const val NCM_IFACE_IN = "ncm-ipv6-in"
        const val NCM_IFACE_OUT = "ncm-ipv6-out"
    }

    /** Bonjour 诊断快照（对应 DiPlay 的 diagnosticSnapshot 字段）。 */
    data class BonjourDiagnostics(
        val added: Int,
        val resolved: Int,
        val addressMismatch: Int,
        val probeCount: Int,
        val successfulProbe: Int,
    ) {
        override fun toString(): String =
            "bonjourAdded=$added bonjourResolved=$resolved " +
                "bonjourAddressMismatch=$addressMismatch " +
                "probeCount=$probeCount successfulProbe=$successfulProbe"
    }

    /** Bonjour 事件。 */
    sealed class BonjourEvent {
        data class Registered(val serviceType: String) : BonjourEvent()
        data class Resolved(val name: String, val address: String, val port: Int) : BonjourEvent()
        data class Probed(val address: String, val port: Int, val ok: Boolean) : BonjourEvent()
        data class Failed(val reason: String) : BonjourEvent()
    }

    /** VPN 隧道结果。 */
    sealed class TunnelResult {
        data class Established(val tunnelName: String) : TunnelResult()
        data class Failed(val error: String) : TunnelResult()
    }

    /**
     * 校验 link-local 地址（IPv6 fe80::  或 IPv4）。
     *
     * DiPlay 原文要求：`advertisedHost must be link-local IPv6 or IPv4`，
     * 且拒绝 loopback。
     */
    fun validateAdvertisedHost(host: String): Boolean {
        val ok = (host.startsWith(LINK_LOCAL_PREFIX) || host.matches(Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$"))) &&
            !host.startsWith("127.") && host != "::1"
        if (!ok) Log.w(TAG, "Invalid advertised host: $host")
        return ok
    }

    /** 校验 host MAC 必须是 6 字节。 */
    fun validateHostMac(mac: ByteArray): Boolean {
        if (mac.size != HOST_MAC_BYTES) {
            Log.w(TAG, "hostMac must be 6 bytes (got ${mac.size})")
            return false
        }
        return true
    }

    /** 校验 link-local 必须是 IPv6 字面量。 */
    fun validateLinkLocal(linkLocal: String): Boolean {
        if (!linkLocal.startsWith(LINK_LOCAL_PREFIX)) {
            Log.w(TAG, "linkLocal must be a link-local IPv6 literal (got $linkLocal)")
            return false
        }
        return true
    }

    /**
     * 旧隧道替换判定（重连关键）。
     *
     * DiPlay: `replacing stale NCM/VPN attachment` / `replacing stale local-only Wi-Fi attachment`
     * —— 若已有活动隧道，先拆再建，否则新隧道挂在旧状态上。
     */
    fun shouldReplaceStaleAttachment(activeTunnel: Boolean): Boolean {
        if (activeTunnel) {
            Log.i(TAG, "replacing stale NCM/VPN attachment")
        }
        return activeTunnel
    }

    /**
     * AirPlay 端口回退。
     *
     * DiPlay: `AirPlay port N is in use; listening on M instead`
     *
     * @param preferred 首选端口
     * @param taken     已被占用的端口集合
     * @return 实际可用端口，无则 null
     */
    fun selectAirPlayPort(preferred: Int, taken: Set<Int>): Int? {
        if (preferred !in taken) return preferred
        for (candidate in preferred + 1..65535) {
            if (candidate !in taken) {
                Log.w(TAG, "AirPlay port $preferred is in use; listening on $candidate instead")
                return candidate
            }
        }
        Log.e(TAG, "No common AirPlay port available for the selected interface addresses")
        return null
    }

    /** Bonjour 注册失败原因（DiPlay 原文）。 */
    fun registrationFailedReason(code: Int): String =
        "AirPlay NSD registration failed code=$code"

    /** Bonjour 发现失败原因（DiPlay 原文）。 */
    fun discoveryFailedReason(code: Int): String =
        "CarPlay control discovery failed code=$code"
}
