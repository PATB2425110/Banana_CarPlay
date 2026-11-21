package com.dbtool.carplay.bridge.hotspot

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import com.dbtool.carplay.api.hotspot.CarSameLanInfo
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

/**
 * 读"车机**作为客户端**所在局域网"的信息（PATCH-0010，`CarHotspotMode.SAME_LAN`）。
 *
 * ## 与 `LocalSoftApReader` 的分工
 *
 * 两者都用 `ConnectivityManager`，但读的是**相反的两侧**：
 * - `LocalSoftApReader` / `ManualHotspotManager`：找**本机在当 AP 的那张网卡**
 *   （ap / wlan / p2p 开头的网卡且有地址），那是 iPhone 要关联的目标；
 * - 本类：找**本机连着别人 AP 的那张网卡**（`activeNetwork` 的 `LinkProperties`），
 *   那是 iPhone 已经/将要去的同一张网。
 *
 * ## 判据（按可信度排序）
 *
 * 1. **`ConnectivityManager.activeNetwork` + `LinkProperties`**（首选，最权威）：
 *    系统自己告诉我们在用哪张网、拿到什么地址、网关是谁。
 *    只接受**非 link-local 的 IPv4**——AirPlay 的通告与 iPhone 的反向回连都按 IPv4 走，
 *    给一个 IPv6 或 169.254.x.x 只会让连接更绕甚至不可达。
 * 2. **网卡枚举回落**：`activeNetwork` 拿不到时（ROM 裁剪、权限不足），
 *    逐张网卡找"有全局 IPv4 且不是回环"的那张。
 *
 * ## 刻意不做的事
 *
 * - **不判断"iPhone 是否在同一张网"**：看不见，也不该猜。所以 [CarSameLanInfo.connected]
 *   只表达"车机侧拿到了可用 IPv4"。iPhone 那一侧要用户自己确认，见设置页文案。
 * - **不建任何热点**：本模式的全部意义就是不建。
 * - **不做 mDNS 转发/NAT**：同一张 LAN 里 mDNS 本来就通。
 */
internal object CarSameLanReader {

    private const val TAG = "xcertplay-samelan"

    @SuppressLint("MissingPermission")
    fun read(context: Context): CarSameLanInfo {
        val appContext = context.applicationContext
        val connectivityManager = runCatching {
            appContext.getSystemService(ConnectivityManager::class.java)
        }.getOrNull()
            ?: return CarSameLanInfo.unavailable(
                "本机读不到网络状态服务，无法确定车机所在的局域网地址",
            )
        // Android 9（28）没有 `NetworkCapabilities.getTransportInfo()`（API 29），
        // 读 SSID 要走 `WifiManager.getConnectionInfo()`——见 [ssidOf] 的说明。
        val wifiManager = runCatching {
            appContext.getSystemService(WifiManager::class.java)
        }.getOrNull()

        readFromActiveNetwork(connectivityManager, wifiManager)?.let { return it }
        readFromInterfaces()?.let { return it }

        return CarSameLanInfo.unavailable(
            "车机当前没有连上任何一张可用的局域网（需要先连上 Wi-Fi 并拿到 IPv4 地址）",
        )
    }

    /**
     * 首选路径：系统报告的在用网络。
     *
     * ⚠️ `activeNetwork` 可能是**蜂窝网**——4G 车机上那就是 rmnet 口，地址
     * 对 iPhone 毫无意义。所以这里必须用 `activeNetwork.networkCapabilities`
     * 过滤出 **`TRANSPORT_WIFI`** 的网络，而不是无脑采信 `activeNetwork`。
     * 否则会出现"车机用 4G 上网、我们却把蜂窝地址通告给 iPhone"这种
     * 必然连不上的情况，而且日志里看起来一切正常。
     */
    @SuppressLint("MissingPermission")
    private fun readFromActiveNetwork(
        connectivityManager: ConnectivityManager,
        wifiManager: WifiManager?,
    ): CarSameLanInfo? {
        val wifiNetwork = findWifiNetwork(connectivityManager) ?: return null
        val properties = runCatching {
            connectivityManager.getLinkProperties(wifiNetwork)
        }.getOrNull() ?: return null
        val address = pickIpv4(properties.linkAddresses.map { it.address })
            ?.takeUnless { isBenchmarkReserved(it) }
            ?: return null
        val prefix = properties.linkAddresses
            .firstOrNull { it.address == address }
            ?.prefixLength
            ?: 0
        val gateway = pickIpv4(properties.routes.mapNotNull { it.gateway })
        // ⚠️ 2026-10-07：**必须把真实 SSID 带出去**。iAP2 `0x5703` / `0x4301` 用它
        // 告诉 iPhone "去连哪张网"。早先只带地址、SSID 沿用热点名 `DBPlay`，
        // 导致 iPhone 去关联一张不存在的网、永远不切 Wi-Fi（真机实测卡在「无线通道就绪」）。
        val ssid = sanitizeSsid(ssidOf(connectivityManager, wifiNetwork, wifiManager))
        return CarSameLanInfo(
            connected = true,
            hostAddress = address.hostAddress?.substringBefore('%'),
            interfaceName = properties.interfaceName,
            gateway = gateway?.hostAddress?.substringBefore('%'),
            prefixLength = prefix,
            ssid = ssid,
            targetLabel = "局域网连接：${address.hostAddress?.substringBefore('%')}",
            summary = buildString {
                append("车机已连上 Wi-Fi「")
                append(ssid ?: "未知网络").append("」")
                append("，地址 ").append(address.hostAddress?.substringBefore('%'))
                if (prefix > 0) append("/").append(prefix)
                gateway?.let { append("，网关 ").append(it.hostAddress?.substringBefore('%')) }
                properties.interfaceName?.let { append("（").append(it).append("）") }
                append("；不建热点，iPhone 连同一张网即可直连")
            },
        )
    }

    /**
     * 找出系统里**类型为 Wi-Fi** 且在用的那张网络。
     *
     * Android 6 起有 `activeNetwork`；但 `activeNetwork` 只保证"有默认出口"，
     * 4G 车机上通常给的是蜂窝。所以要遍历 `allNetworks` 挑 Wi-Fi 那张。
     *
     * ⚠️ `allNetworks` 在 API 31 起被标记 deprecated（官方推荐改用
     * `registerDefaultNetworkCallback`）。这里**故意仍用它**：
     * 本方法是一次性查询（设置页刷新/会话启动各一次），而 callback 是**订阅式**的——
     * 用订阅做一次查询要多管一个生命周期、还得处理"回调还没来"的竞态，
     * 复杂度远高于收益。且该 API 到 API 35 仍可用，只是打了 deprecation 标记。
     */
    @Suppress("DEPRECATION", "MissingPermission")
    private fun findWifiNetwork(connectivityManager: ConnectivityManager): Network? {
        val wifiNetworks = runCatching {
            connectivityManager.allNetworks.filter { network ->
                connectivityManager.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
        }.getOrNull().orEmpty()
        if (wifiNetworks.isEmpty()) return null
        // 有默认出口的那张优先——它才是 iPhone 也会去的那个网络。
        val active = connectivityManager.activeNetwork
        return wifiNetworks.firstOrNull { it == active } ?: wifiNetworks.first()
    }

    @SuppressLint("MissingPermission")
    private fun ssidOf(
        connectivityManager: ConnectivityManager,
        network: Network,
        wifiManager: WifiManager?,
    ): String? {
        // ------------------------------------------------------------------
        // ⚠️ 2026-10-10（真车事故同族）：`NetworkCapabilities.getTransportInfo()`
        // 是 **API 29** 才有的方法，而本产品运行在 **Android 9（SDK 28）** 车机上。
        // 不分支就是"编译一路绿灯、上车 `NoSuchMethodError`"——与仪表窗口那次
        // `ViewTreeObserver` 用了 API 34 监听器完全是同一类错误。
        //
        // 28/29 的分支：`WifiManager.getConnectionInfo()` 与 `getTransportInfo()`
        // 是**同一份 Wi-Fi 状态**（后者本身就是把 `WifiInfo` 包装出来），只是取法更老。
        // 两条路都拿不到 ⇒ 返回 null，调用方回落到设置页手填的 SSID（本来就是这么设计的，
        // 见 `CarSameLanInfo` 的 ssid 说明）。
        // ------------------------------------------------------------------
        val transportInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val capabilities = runCatching {
                connectivityManager.getNetworkCapabilities(network)
            }.getOrNull() ?: return null
            capabilities.transportInfo ?: return null
        } else {
            @Suppress("DEPRECATION")
            wifiManager?.connectionInfo ?: return null
        }
        return runCatching {
            val method = transportInfo.javaClass.getMethod("getSSID")
            method.invoke(transportInfo) as? String
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /**
     * 把 `WifiInfo.getSSID()` 的返回值洗成"可直接填进 iAP2 的 SSID"。
     *
     * Android 的这个 API 有三种坑，全部要处理，否则会把脏值发给 iPhone：
     * ① 返回值**带双引号**（`"MyWiFi"`）——iAP2 的 SSID 字段不能带引号；
     * ② 拿不到时返回字符串 `<unknown ssid>` —— 这是**哨兵值，不是网名**，
     *    直接填给 iPhone 会让它去连一张叫 `<unknown ssid>` 的网；
     * ③ 拿不到时还可能返回 `0x` —— 同上。
     *
     * ⇒ 命中后两种哨兵一律返回 null（调用方回落到设置页手填值）。
     */
    private fun sanitizeSsid(raw: String?): String? {
        val trimmed = raw?.trim()?.removeSurrounding("\"")?.trim() ?: return null
        if (trimmed.isEmpty()) return null
        if (trimmed.equals("<unknown ssid>", ignoreCase = true)) return null
        if (trimmed.equals("0x", ignoreCase = true)) return null
        if (trimmed.equals("unknown", ignoreCase = true)) return null
        return trimmed
    }

    /**
     * 次选路径：直接枚举网卡。
     *
     * 只在 `activeNetwork` 那条路走不通时用。判据是"有**全局** IPv4 且不是回环"——
     * 全局即排除 `169.254.x.x`（link-local，DHCP 失败时的自动地址）与 `127.x`。
     */
    private fun readFromInterfaces(): CarSameLanInfo? {
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull()
            ?: return null
        // 诊断账本：把**每一张**网卡的判定都记下来。实车日志里只有"选了谁"时，
        // 局域网路失败就只能猜（2026-10-08 那轮就是：通告了 eth0.11/198.18.34.15，
        // 但日志看不出当时 wlan0 到底有没有地址、长什么样）。现在一目了然。
        val ledger = mutableListOf<String>()
        val candidates = Collections.list(interfaces)
            .asSequence()
            .filter { !it.isLoopback && it.isUp }
            .filter { networkInterface ->
                EXCLUDED_PREFIXES.none { prefix ->
                    networkInterface.name.startsWith(prefix, ignoreCase = true)
                }
            }
            .mapNotNull { networkInterface ->
                val address = pickIpv4(Collections.list(networkInterface.inetAddresses))
                if (address == null) {
                    ledger += "${networkInterface.name}=无IPv4"
                    return@mapNotNull null
                }
                val addr = address.hostAddress?.substringBefore('%') ?: "?"
                when {
                    looksLikeApInterface(networkInterface.name) -> {
                        ledger += "${networkInterface.name}/$addr=AP侧跳过"
                        null
                    }
                    isBenchmarkReserved(address) -> {
                        ledger += "${networkInterface.name}/$addr=RFC2544保留跳过"
                        null
                    }
                    else -> {
                        ledger += "${networkInterface.name}/$addr=候选"
                        networkInterface.name to address
                    }
                }
            }
            .toList()
        // ② Wi-Fi STA 侧（`wlan*`）优先于其余网卡。原来直接 `.firstOrNull()`
        //    吃枚举顺序，`eth0.11` 排在 `wlan0` 前面就会被选中。
        val candidate = pickSameLanCandidate(candidates) ?: return null
        val (name, address) = candidate
        return CarSameLanInfo(
            connected = true,
            hostAddress = address.hostAddress?.substringBefore('%'),
            interfaceName = name,
            targetLabel = "局域网连接：${address.hostAddress?.substringBefore('%')}",
            summary = "车机在 $name 上拿到地址 ${address.hostAddress?.substringBefore('%')}" +
                "（读不到系统在用网络，按网卡枚举推断；网卡账本：${ledger.joinToString(", ")}）" +
                "；不建热点，iPhone 连同一张网即可直连",
        )
    }

    /** 从一组地址里挑一个可用的全局 IPv4。 */
    private fun pickIpv4(addresses: List<InetAddress>): Inet4Address? =
        addresses.filterIsInstance<Inet4Address>().firstOrNull { address ->
            !address.isLoopbackAddress &&
                !address.isLinkLocalAddress &&
                !address.isAnyLocalAddress
        }

    /**
     * 地址是否落在 **RFC 2544** 网络互连设备基准测试保留网段 `198.18.0.0/15`。
     *
     * ## 为什么必须排除（2026-10-08 实车日志实锤，设备 `67b3c24af6b5`）
     *
     * 车机内部常用这个网段（VLAN 子接口 / 内部网桥）。现场：
     *
     * 「hotspot plan: 车机在 eth0.11 上拿到地址 198.18.34.15」
     * → 通告给 iPhone → iPhone 走 Wi-Fi **物理上到不了**
     * → 「wireless attach timed out: iPhone did not open the AirPlay
     *    connection within 40000ms (attempt=1)」反复 40 秒超时。
     *
     * 蓝牙 / iAP2 / MFi 认证全过，唯独 AirPlay 数据通道永远不开——
     * 全链路除这一条超时外**没有任何报错**，极难排查。宁可如实报
     * 「读不到车机所在局域网地址」（由设置页引导手填），也不能把
     * 一个到不了的地址发给 iPhone。
     *
     * ⚠️ 这是 2026-10-03 那次（`eth0.3` = 同一个地址）的**同一类坑**，
     * 只是这次从「网卡枚举回落」这条路漏出来。
     */
    internal fun isBenchmarkReserved(address: Inet4Address): Boolean {
        val octets = address.address
        val first = octets[0].toInt() and 0xFF
        val second = octets[1].toInt() and 0xFF
        return first == 198 && (second == 18 || second == 19)
    }

    /**
     * 从候选网卡里挑一张：**Wi-Fi STA 侧（`wlan*`）优先**，其次按原顺序。
     *
     * 原实现是 `.firstOrNull()`（纯枚举顺序）⇒ 实车 `eth0.11`（`198.18.34.15`）
     * 排在 `wlan0` 前面就被选中，而那张网卡 iPhone 到不了。
     *
     * 保留「没有 `wlan*` 就退回原顺序」这一档：个别 ROM 的 Wi-Fi 侧网卡不叫
     * `wlan*`（如某些平台的 `eth0.x`），**不能一刀切排除**，否则会把本来
     * 可用的配置判死。
     */
    internal fun pickSameLanCandidate(
        candidates: List<Pair<String, Inet4Address>>,
    ): Pair<String, Inet4Address>? =
        candidates.firstOrNull { it.first.startsWith("wlan", ignoreCase = true) }
            ?: candidates.firstOrNull()

    /**
     * 网卡名是否**像一张 AP**（与 `ManualHotspotManager.looksLikeApInterface` 同口径）。
     *
     * SAME_LAN 要的是"连着别人"的那张网卡，AP 网卡语义相反 ⇒ 必须排除。
     */
    private fun looksLikeApInterface(name: String): Boolean =
        name.startsWith("ap", ignoreCase = true) ||
            name.startsWith("p2p", ignoreCase = true) ||
            name.contains("softap", ignoreCase = true)

    /** 与 `ManualHotspotManager.EXCLUDED_INTERFACE_PREFIXES` 同口径 + 蜂窝口。 */
    private val EXCLUDED_PREFIXES = listOf(
        "lo",
        "dummy",
        "rmnet",
        "r_rmnet",
        "tun",
        "ppp",
        "sit",
        "ip6",
        "bond",
        "usb",
    )
}
