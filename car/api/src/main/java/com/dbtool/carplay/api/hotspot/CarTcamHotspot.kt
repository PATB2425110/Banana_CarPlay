package com.dbtool.carplay.api.hotspot

import com.dbtool.carplay.api.settings.CarHotspotSecurity
import java.net.Inet4Address
import java.net.InetAddress

/**
 * 车机 TCAM 热点复用（`backend = TCAM_HOTSPOT`）的纯逻辑层。
 *
 * ## 来源
 *
 * 这一份是 DiPlay（V3.2，基于 shilapi/xcertplay）逆向源码
 * `TcamHotspotManager.*` 的**原样移植**，只保留**不依赖 Android 框架**、能在 JVM
 * 上被单测穷举的那部分：
 *
 * - 接口名判定：写死 [TCAM_ETH_IFACE]，与 DiPlay 同一套判定，**不提供候选列表 /
 *   多接口回退**（证据包 §D：写死哪张拿到的地址都一样，但口径必须与 DiPlay 一致）；
 * - `Inet4Address` 唯一性：同一张卡上恰好一个 IPv4 才算候选（0 个或多个都不算）；
 * - 附着状态机（[TcamAttachmentState]）：`select` / `registerCallback` / `invalidate` /
 *   `close` / `sameLink`，供 `TcamHotspotManager` 拿真实 `ConnectivityManager` 数据驱动；
 * - 信道 → 频率换算（DiPlay `wifiChannelToFrequencyMhz` + `legacyHotspotBandToSoftApBand`）。
 *
 * 需要 Android 框架（`ConnectivityManager` / `WifiManager` / `LinkProperties` / 反射读
 * `WifiConfiguration`）的部分放在 `:car:core-bridge` 的 `TcamHotspotManager`。
 *
 * ## 为什么放在 `:car:api`
 *
 * `:car:api` 没有 Robolectric，`NetworkInterface` / `ConnectivityManager` 在 JVM 上读不了；
 * 而这些判据判错一次的代价是"60 秒超时 + 全链路无报错"，必须能在 JVM 上被单测穷举。
 * 与既有 [CarHotspotApInterface] / [CarHotspotEndpointCandidates] 的拆分是同一手法。
 */
object CarTcamHotspot {

    /**
     * **车机 TCAM 热点接入网卡名**（DiPlay 写死，照搬）。
     *
     * 本车实测 `eth0.11` 存在且为 `198.18.34.15/16`（证据包 §A.1）。
     * 判据口径见证据包 §D：接口名写死、集中为单一常量，不另建候选列表 / 多接口回退。
     */
    const val TCAM_ETH_IFACE: String = "eth0.11"

    /** 默认等待超时（ms），本仓库约定 60 秒。 */
    const val DEFAULT_TIMEOUT_MILLIS: Long = 60_000L

    /** 轮询间隔（ms），DiPlay 原文 `Thread.sleep(200)`。 */
    const val POLL_INTERVAL_MILLIS: Long = 200L

    /** 接口名是不是 TCAM 接入卡。单一常量判定，不在这里做任何"像不像"的猜测。 */
    fun isTcamInterface(interfaceName: String?): Boolean =
        interfaceName == TCAM_ETH_IFACE

    /**
     * **唯一性判据**：同一张卡上的地址集合里，恰好一个 IPv4 才接受。
     *
     * DiPlay 语义：`linkAddresses` 过滤 `Inet4Address` 后 `singleOrNull()`，
     * 0 个或多个都不算候选（继续轮询）。
     */
    fun singleInet4Address(addresses: List<InetAddress>): Inet4Address? =
        addresses.filterIsInstance<Inet4Address>().singleOrNull()

    /**
     * DiPlay `sameLink`：接口名必须仍是 [TCAM_ETH_IFACE]，且地址集合里还包含选中地址。
     *
     * 与 [TcamAttachmentState.sameLink] 共用这一条判定（后者多一层"是否已选"的状态）。
     */
    fun sameLink(
        interfaceName: String?,
        addresses: List<InetAddress>,
        selectedAddress: InetAddress?,
    ): Boolean {
        if (!isTcamInterface(interfaceName)) return false
        if (addresses.isEmpty()) return false
        val selected = selectedAddress ?: return false
        return addresses.any { it == selected }
    }

    /**
     * DiPlay `legacyHotspotBandToSoftApBand`：旧版 `WifiConfiguration.apBand`
     * （0=2.4G / 1=5G / -1=自动）映射成 `SoftApConfiguration` 的 band
     * （1=2.4G / 2=5G / 3=自动）。其余值无法映射 → null。
     */
    fun legacyHotspotBandToSoftApBand(band: Int?): Int? = when (band) {
        0 -> 1
        1 -> 2
        -1 -> 3
        else -> null
    }

    /**
     * DiPlay `wifiChannelToFrequencyMhz`：信道 → 频率（MHz）。
     *
     * 与上游 `:shared` 里那个 `wifiChannelToFrequencyMhz` 的差别：DiPlay 的
     * 6 GHz 分支用的 band 常量是 **4**（而不是 3），这里**照搬 DiPlay**，
     * 不合并进上游语义。
     */
    fun wifiChannelToFrequencyMhz(channel: Int, softApBand: Int?): Int? = when {
        channel == 0 -> null
        softApBand == 1 && channel in 1..13 -> 2407 + channel * 5
        softApBand == 1 && channel == 14 -> 2484
        softApBand == 2 && channel in 32..177 -> 5000 + channel * 5
        softApBand == 4 && channel in 1..233 -> 5950 + channel * 5
        else -> null
    }

    /**
     * DiPlay 安全类型映射：`allowedKeyManagement` 的 OPEN 位（bit 0）为真，或密码为空
     * → [CarHotspotSecurity.OPEN]；否则 [CarHotspotSecurity.WPA2]。
     *
     * DiPlay 只有 OPEN→NONE / 其它→WPA_WPA2 两种，不保留 WPA3。
     */
    fun security(openKeyManagement: Boolean, passphrase: String): CarHotspotSecurity =
        if (openKeyManagement || passphrase.isEmpty()) CarHotspotSecurity.OPEN
        else CarHotspotSecurity.WPA2

    /** DiPlay 的 band 标签：旧版 `apBand` 0=2.4 GHz、1=5 GHz，其余 "Auto"。 */
    fun bandLabel(apBand: Int?): String = when (apBand) {
        0 -> "2.4 GHz"
        1 -> "5 GHz"
        else -> "Auto"
    }
}

/**
 * TCAM 附着成功后的结论（DiPlay `WirelessHotspotInfo` 的 DBPlay 等价物）。
 *
 * @property passphrase 已按 DiPlay 语义收敛：OPEN 网络必为空串，加密网络为 8..63 位。
 * @property frequencyMHz 由 `apChannel` + `apBand` 换算（纯函数，见 [CarTcamHotspot]）。
 * @property interfaceName 恒为 [CarTcamHotspot.TCAM_ETH_IFACE]。
 */
data class CarTcamHotspotInfo(
    val ssid: String,
    val passphrase: String,
    val security: CarHotspotSecurity,
    val channel: Int,
    val frequencyMHz: Int?,
    val interfaceName: String?,
    val hostAddress: Inet4Address?,
    val bandLabel: String,
)

/**
 * TCAM 附着状态机（纯数据，与 Android 无关）。
 *
 * `TcamHotspotManager` 拿真实 `Network` / `LinkProperties` 驱动它；这里只保留
 * "状态怎么流转"与"链路是否仍是同一条"的纯判据，供 JVM 单测穷举。
 *
 * 与 DiPlay 的字段一一对应：`selectedAddress` / `confirmedSsid` / `invalidated` /
 * `closed` / `callbackRegistered`（`selectedNetwork` 是 `android.net.Network`，
 * 不放进纯状态机，由 manager 自己持有）。
 */
data class TcamAttachmentState(
    val selectedAddress: Inet4Address? = null,
    val confirmedSsid: String? = null,
    val invalidated: Boolean = false,
    val closed: Boolean = false,
    val callbackRegistered: Boolean = false,
) {
    /** 选中一条链路（DiPlay：记 `selectedAddress` + `confirmedSsid`）。 */
    fun select(address: Inet4Address, ssid: String?): TcamAttachmentState =
        copy(selectedAddress = address, confirmedSsid = ssid)

    /** 已注册 NetworkCallback（DiPlay：`callbackRegistered = true`）。 */
    fun registerCallback(): TcamAttachmentState = copy(callbackRegistered = true)

    /** 置 `invalidated`（幂等；DiPlay 用 `compareAndSet(false, true)` 保证只触发一次）。 */
    fun invalidate(): TcamAttachmentState = copy(invalidated = true)

    /** 关闭：置 `closed` 并注销 callback。 */
    fun close(): TcamAttachmentState = copy(closed = true, callbackRegistered = false)

    /**
     * DiPlay `sameLink`：接口名仍为 [CarTcamHotspot.TCAM_ETH_IFACE]，
     * 且地址集合里还包含选中地址。
     */
    fun sameLink(interfaceName: String?, addresses: List<InetAddress>): Boolean =
        CarTcamHotspot.sameLink(interfaceName, addresses, selectedAddress)

    /** 启动复查：已选、未失效、未关闭、链路仍是同一条（DiPlay 的启动期复查口径）。 */
    fun isStartupValid(interfaceName: String?, addresses: List<InetAddress>): Boolean =
        selectedAddress != null && !invalidated && !closed && sameLink(interfaceName, addresses)
}
