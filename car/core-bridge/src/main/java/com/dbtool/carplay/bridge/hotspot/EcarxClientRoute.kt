// 移植自 carlito12345/DiPlay（GPL-3.0）network/EcarxClientRoute.kt
// DBPlay 适配：把厂商标识判定从 KX11 扩展到 CS11/cs11_high（吉利 Ecarx 平台同族）。
// 只做判定，不写任何系统状态。
package com.dbtool.carplay.bridge.hotspot

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** 一条解析出来的 IPv4 路由。字段名与 `ip route` 输出一一对应，便于排障比对。 */
internal data class EcarxIpv4Route(
    val destination: Inet4Address,
    val prefix: Int,
    val interfaceName: String,
    val source: Inet4Address?,
    val gateway: Inet4Address?,
    val table: String,
)

/**
 * 原厂（Ecarx / tcam）热点路径的地址与路由判定。
 *
 * ## 为什么需要这个
 *
 * 吉利 Ecarx 平台的车机原厂热点不由标准 Android SoftAP 提供，它的地址挂在
 * `eth*` 上（实测 `eth0 = 198.18.34.15`），车机自报网段是 `192.168.15.0/24`。
 * 因此 `NetworkInterface` 里找不到 `192.168.15.1`，`getWifiApState()` 也会返回
 * 一个"关闭"的假值。这里集中放"什么样的地址/路由才算原厂热点"的判定。
 *
 * ## 安全边界
 *
 * 只读：不写系统状态、不动网卡、不开进程（`ip` 由调用方以受限方式运行）。
 * 判不出来就返回拒绝原因字符串，绝不猜——猜错会让排障更慢。
 */
internal object EcarxClientRoute {

    /** 可用作热点源的 IPv4：排除环回、组播、链路本地、以及 `usable` 判定外的地址。 */
    fun usableIpv4(address: InetAddress): Boolean =
        address is Inet4Address && HotspotAddressPolicy.select(listOf(address)) != null

    /** 地址分类，仅用于日志与诊断，不参与放行判定。 */
    fun addressClass(address: InetAddress?): String = when {
        address == null -> "unknown"
        address.isAnyLocalAddress -> "unspecified"
        address.isLoopbackAddress -> "loopback"
        address.isMulticastAddress -> "multicast"
        address is Inet4Address && benchmarkSource(address) -> "benchmark_198_18_15"
        address is Inet4Address && address.isLinkLocalAddress -> "ipv4_link_local"
        address is Inet4Address && address.isSiteLocalAddress -> "private_ipv4"
        address is Inet4Address -> "other_ipv4"
        address is Inet6Address && address.isLinkLocalAddress -> "ipv6_link_local"
        else -> "other_ipv6"
    }

    /**
     * 明确排除的接口名：蜂窝、VPN、虚拟隧道、dummy。
     * 这些接口上就算有 `198.18/15` 也不是原厂热点。
     */
    fun unsafeInterface(name: String): Boolean =
        Regex("^(rmnet|ccmni|pdp|wwan|tun|tap|dummy|veth|sit).*", RegexOption.IGNORE_CASE)
            .containsMatchIn(name)

    /**
     * 是否是"本平台"的原厂热点源。
     *
     * carlito 原版只认 `MODEL=KX11`；DBPlay 在此扩展到 **`CS11`**（斌哥车机，
     * 同属吉利 Ecarx 平台，SDK28 / 高通 msmnile / 原厂源同为 `eth*` + `198.18/15`）。
     *
     * 其余条件保持严格：`eth*`（含 vlan 子接口）、源地址落在 `198.18/15`。
     */
    fun isEcarxPlatform(sdk: Int, model: String, device: String): Boolean = sdk == 28 && (
        model.equals("KX11", true) && device.startsWith("kx11", true) ||
            model.equals("CS11", true) && device.startsWith("cs11", true) ||
            model.equals("CX11", true) && device.startsWith("cx11", true)
        )

    fun kx11Source(sdk: Int, model: String, device: String, name: String, source: InetAddress): Boolean =
        isEcarxPlatform(sdk, model, device) &&
            Regex("eth[0-9]+(\\.[0-9]+)?", RegexOption.IGNORE_CASE).matches(name) &&
            benchmarkSource(source)

    /** 路由表数字别名必须与表名视为同一张表，否则会漏判/误判。 */
    private fun table(value: String): String = when (value) {
        "254" -> "main"
        "255" -> "local"
        "253" -> "default"
        else -> value
    }

    private fun destination(text: String, allowDefault: Boolean = false): Pair<Inet4Address, Int>? {
        if (text == "default") return if (allowDefault)
            (EcarxHotspotReader.numericAddress("0.0.0.0") as Inet4Address) to 0 else null
        val parts = text.split('/')
        if (parts.size !in 1..2) return null
        val address = EcarxHotspotReader.numericAddress(parts[0]) as? Inet4Address ?: return null
        val prefix = if (parts.size == 1) 32 else parts[1].toIntOrNull() ?: return null
        return if (prefix in 1..32 || allowDefault && prefix == 0 && address.isAnyLocalAddress)
            address to prefix else null
    }

    /**
     * `ip route show` 输出的同一前缀多 nexthop（多路径）判定。
     * 只有命中当前客户端、且同表的最长前缀才参与；无关策略表不阻断。
     */
    fun matchingMultipathRoute(lines: List<String>, peer: Inet4Address, resolvedTable: String,
                               allowDefault: Boolean = false): Boolean {
        val matches = mutableListOf<Pair<Int, Boolean>>()
        var matchingPrefix: Int? = null
        var multipath = false
        fun finishHeader() {
            matchingPrefix?.let { matches += it to multipath }
        }
        for (line in lines) {
            val fields = line.trim().split(Regex("\\s+"))
            if (fields.firstOrNull() == "nexthop") {
                if (matchingPrefix != null) multipath = true
                continue
            }
            finishHeader()
            val target = fields.firstOrNull()?.let { destination(it, allowDefault) }
            val tableIndex = fields.indexOf("table")
            val rowTable = if (tableIndex < 0) "main" else fields.getOrNull(tableIndex + 1)
            matchingPrefix = target?.takeIf { (network, prefix) ->
                rowTable != null && table(rowTable) == table(resolvedTable) &&
                    (prefix == 0 || EcarxHotspotReader.sameSubnet(network.address, peer.address, prefix))
            }?.second
            multipath = "nexthop" in fields
        }
        finishHeader()
        val longest = matches.maxOfOrNull { it.first } ?: return false
        return matches.any { it.first == longest && it.second }
    }

    /** 解析单行 `ip route` 输出；字段重复或有歧义时返回 null（不猜）。 */
    fun parseIpv4Route(line: String, allowDefault: Boolean = false): EcarxIpv4Route? {
        val fields = line.trim().split(Regex("\\s+"))
        if (fields.isEmpty() || "nexthop" in fields) return null
        val (destination, prefix) = destination(fields.first(), allowDefault) ?: return null
        fun field(key: String): String? {
            val positions = fields.indices.filter { fields[it] == key }
            return if (positions.size == 1) fields.getOrNull(positions.single() + 1) else null
        }
        if (listOf("dev", "src", "via", "table").any { key -> fields.count { it == key } > 1 }) return null
        val name = field("dev")?.takeIf { it.matches(Regex("[a-zA-Z0-9_.-]+")) } ?: return null
        val source = field("src")?.let { EcarxHotspotReader.numericAddress(it) as? Inet4Address }
        val gateway = field("via")?.let { EcarxHotspotReader.numericAddress(it) as? Inet4Address }
        if ("src" in fields && source == null || "via" in fields && gateway == null) return null
        val routeTable = if ("table" in fields) {
            field("table")?.takeIf { it.matches(Regex("[a-zA-Z0-9_.-]+")) } ?: return null
        } else "main"
        return EcarxIpv4Route(destination, prefix, name, source, gateway, table(routeTable))
    }

    /**
     * 跨网段（客户端与源不同网段）情形下的放行判定。
     *
     * 返回 `verified_factory_gateway` / `verified_factory_default_gateway` 才允许使用，
     * 其余返回的都是拒绝原因（进日志，不进放行）。
     */
    fun routedDecision(peer: Inet4Address, source: Inet4Address, name: String, sourcePrefix: Int,
                       route: EcarxIpv4Route?, routes: List<EcarxIpv4Route>,
                       allowDefault: Boolean = false): String {
        if (route == null) return "route_get_unreadable"
        // 移植自 DiPlay v0.2.16：route get 必须恰好解析出“到该客户端”的 /32，
        // 否则一条同名但目的地址不同的路由会冒充客户端证据。
        if (route.prefix != 32 || route.destination != peer) return "route_destination_mismatch"
        if (route.interfaceName != name || route.source != source) return "route_source_or_interface_mismatch"
        val gateway = route.gateway ?: return "cross_subnet_gateway_missing"
        if (gateway == source || gateway.isAnyLocalAddress || gateway.isLoopbackAddress || gateway.isMulticastAddress ||
            !EcarxHotspotReader.sameSubnet(source.address, gateway.address, sourcePrefix))
            return "gateway_not_on_source_link"
        val matches = routes.filter {
            it.table == route.table && (allowDefault && it.prefix == 0 && it.destination.isAnyLocalAddress ||
                EcarxHotspotReader.sameSubnet(it.destination.address, peer.address, it.prefix))
        }
        val longest = matches.maxOfOrNull { it.prefix } ?: return "no_specific_client_route"
        val selected = matches.filter { it.prefix == longest }.distinct()
        if (selected.size != 1) return "ambiguous_specific_client_routes"
        val specific = selected.single()
        if (specific.interfaceName != name || specific.gateway != gateway ||
            specific.source != null && specific.source != source) return "specific_route_mismatch"
        return if (specific.prefix == 0) "verified_factory_default_gateway" else "verified_factory_gateway"
    }

    /** `198.18.0.0/15` —— 车机原厂热点实际使用的源网段。 */
    private fun benchmarkSource(address: InetAddress): Boolean = address is Inet4Address &&
        (address.address[0].toInt() and 255) == 198 && (address.address[1].toInt() and 254) == 18
}
