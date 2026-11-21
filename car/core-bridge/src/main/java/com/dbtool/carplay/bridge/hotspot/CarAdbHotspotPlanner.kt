package com.dbtool.carplay.bridge.hotspot

import android.content.Context
import android.net.ConnectivityManager
import com.dbtool.carplay.api.hotspot.CarAdbHotspotDefaults
import com.dbtool.carplay.api.hotspot.CarHotspotNetwork
import com.dbtool.carplay.api.hotspot.CarHotspotSubnet
import com.dbtool.carplay.api.hotspot.CarNativeHotspotInfo
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 给"DBPlay 自建热点"选一组**与车机现状不冲突**的网络参数。
 *
 * ## 立场：参考实现的值优先，探测只做兜底（2026-09-29 修正）
 *
 * 参考实现（`FUCK-Lynk&Co-ADB.apk`）把 `wlan0` / `192.168.175.1` / 信道 36
 * 全部写死。**2026-10-07 起这三项全部照抄**——车主口径已明确只走 5 GHz，
 * 此前"车机射频只有 2.4 GHz"的判定被推翻（参考实现在目标车机上确实以此成功
 * 起过热点）。所以逐项对待：
 *
 * - 网卡：`wlan0` 存在且不是上行 ⇒ 直接用它（见 [autoInterface]）。**照抄**；
 * - 网段：`192.168.175.0/24` 是候选表第一项（见 `SUBNET_CANDIDATES`）。**照抄**；
 * - 信道：走 5 GHz 的 `36/40/44/48`（见 `CHANNEL_CANDIDATES`）。**照抄**，
 *   只是不写死单值——按顺序试，起不来就换下一个。
 *
 * 早期版本反过来做——"探测优先、参考值只当最后的兜底"——结果是：每次都要
 * 靠真机日志去验证探测猜得对不对，而参考实现那些**已知可用**的值反而
 * 从来不是第一选择。现在把可用的部分改回"先试被验证过的值"。
 *
 * ## 但"冲突检测"必须保留
 *
 * 网段仍要挑"第一个不冲突的"：车机常有 `10.x` 蜂窝、`192.168.43.x` 自带热点，
 * 撞上之后 `ip rule` / 路由互相打架，现象是"热点起来了，但 iPhone 连不上或
 * 连上不通"——极难查。信道同理（同频干扰会直接反映成画面卡顿）。
 *
 * ## 与探测的关系
 *
 * 输入是 [CarNativeHotspotInfo]（由 `CarNativeHotspotProbe` 产出）。
 * 没探测过（null）时仍然能工作，只是判据退化成"只按本进程能看到的网卡"，
 * 覆盖面小很多——所以调用方应当尽量先探测。
 *
 * ⚠️ **只在"车机没有现成热点、我们要自建"这条分支上使用。**
 * 探测到车机热点时是 attach（复用它），不选参数。
 */
internal object CarAdbHotspotPlanner {

    /**
     * @param interfaceOverride 设置页里用户手动填的网卡名，空串 = 自动。
     */
    fun plan(
        context: Context,
        info: CarNativeHotspotInfo?,
        interfaceOverride: String,
    ): CarHotspotNetwork {
        val appContext = context.applicationContext
        val interfaces = enumerateInterfaces()
        val uplink = uplinkInterface(appContext)
        val notes = mutableListOf<String>()

        // ---- 网卡
        val override = interfaceOverride.trim()
        val interfaceName: String = when {
            override.isNotEmpty() -> {
                val exists = interfaces.any { it.name == override }
                if (exists) {
                    notes += "网卡取设置里手动指定的 $override"
                    override
                } else {
                    notes += "设置里指定的网卡 $override 不存在，改为自动选择"
                    autoInterface(interfaces, uplink, info, notes)
                }
            }
            else -> autoInterface(interfaces, uplink, info, notes)
        } ?: run {
            // 一个 Wi-Fi 网卡都找不到。仍然要给出一个名字，否则整条链没法继续——
            // 但把这件事写进理由，让现场知道这是"猜的"，不是探测结果。
            notes += "本机没有可识别的 Wi-Fi 网卡（wlan*/ap*/softap*），回落到参考实现的 wlan0"
            CarAdbHotspotDefaults.DEFAULT_INTERFACE
        }

        // ---- 网段：接管残留 AP 时**沿用**它已有的地址，否则挑第一个没被占用的 /24
        //
        // ⚠️ 为什么要"沿用"而不是"避开"（2026-09-30 实车）：
        //
        // 网卡被别人占着时，它上面**已经**有一个地址（实车 `wlan0` = `192.168.175.1`，
        // 是第三方转换类 App 留下的残留热点）。如果我们按"避开冲突"另选
        // `192.168.176.0/24`，网卡上就会**同时**挂着两个地址；而下游
        // `ManualHotspotManager` 取的是 `NetworkInterface.inetAddresses` 里的**第一个**
        // （顺序不保证）⇒ 给 iPhone 通告的 AirPlay 地址可能是那个残留地址，
        // iPhone 连上我们的 AP 之后**根本到不了它**。
        //
        // 沿用之后网卡上只有一个地址，通告的地址必然正确，而且**不需要删任何东西**
        // （删别人的地址是在动车机的网络栈，能不碰就不碰）。
        //
        // 判据刻意收得很窄：只有"探测到网卡上确实有 AP、但它的广播名与车机存储配置
        // 不一致"（⇒ 那张 AP 是残留，我们即将接管它）才沿用。其余情况一律走冲突检测。
        val adoptable = info?.takeIf { it.ssidMismatch }
        val adopted = adoptable?.hostAddress
            ?.let { CarHotspotSubnet.networkOf(it, CarAdbHotspotDefaults.PREFIX_LENGTH) }
            ?.takeIf { candidate ->
                // 沿用不等于不查冲突：它仍不能与**别的**网卡（尤其上行）撞段位。
                // "上行网段被撞"才是冲突检测真正要防的事（`ip rule` / 路由会互相打架）。
                val others = interfaces
                    .filter { iface -> iface.name != adoptable.interfaceName }
                    .flatMap { iface -> iface.addresses }
                    .map { address -> address.substringBefore('/') }
                others.none { other -> CarHotspotSubnet.sameSlash24(other, candidate) }
            }

        val subnet: String
        if (adopted != null) {
            subnet = adopted
            notes += "网段 $adopted（接管 ${adoptable?.interfaceName} 上残留的 AP：" +
                "沿用网卡已有的地址，避免网卡上出现第二个地址让上游读到错的网关）"
        } else {
            val occupied = buildSet {
                addAll(info?.occupiedAddresses.orEmpty())
                interfaces.forEach { iface -> addAll(iface.addresses.map { it.substringBefore('/') }) }
            }
            val free = CarAdbHotspotDefaults.SUBNET_CANDIDATES.firstOrNull { candidate ->
                val host = CarHotspotSubnet.hostAddress(candidate) ?: return@firstOrNull false
                occupied.none { CarHotspotSubnet.sameSlash24(it, host) }
            }
            subnet = free ?: CarAdbHotspotDefaults.SUBNET_CANDIDATES.first()
            if (free == null) {
                notes += "候选网段全部与本机已有地址冲突，只能用 ${CarAdbHotspotDefaults.SUBNET_CANDIDATES.first()}（可能不通）"
            } else {
                val collisions = CarAdbHotspotDefaults.SUBNET_CANDIDATES
                    .takeWhile { it != free }
                    .joinToString("、")
                if (collisions.isEmpty()) {
                    notes += "网段 $free（首选候选即无冲突）"
                } else {
                    notes += "网段 $free（避开了与已有地址冲突的 $collisions）"
                }
            }
        }

        val host = CarHotspotSubnet.hostAddress(subnet) ?: "${subnet.substringBeforeLast('.')}.1"
        val dhcpStart = CarHotspotSubnet.dhcpStart(subnet) ?: "$host"
        val dhcpEnd = CarHotspotSubnet.dhcpEnd(subnet) ?: "$host"

        // ---- 信道：5 GHz 的 36/40/44/48（2026-10-07 起只走 5 GHz，见 CHANNEL_CANDIDATES）。
        //      这里只挑"第一个尝试项"；真正起不来时由 `CarAdbHotspotManager`
        //      按同一张候选表逐个再试，所以这里避开同频只是**省一次失败尝试**。
        val avoidChannel = info?.channel?.takeIf { it > 0 }
        val channel = CarAdbHotspotDefaults.CHANNEL_CANDIDATES
            .mapNotNull { it.toIntOrNull() }
            .firstOrNull { it != avoidChannel }
            ?: CarAdbHotspotDefaults.DEFAULT_CHANNEL
        notes += if (avoidChannel != null && avoidChannel != channel) {
            "信道 $channel（${bandLabelOf(channel)}；避开本机热点正在用的 $avoidChannel）"
        } else {
            "信道 $channel（${bandLabelOf(channel)}）"
        }

        if (info == null) {
            notes += "未做 ADB 探测，冲突判断只基于本进程可见的网卡"
        }
        if (uplink != null) notes += "上行网卡 $uplink 已排除"

        return CarHotspotNetwork(
            interfaceName = interfaceName,
            subnet = subnet,
            hostAddress = host,
            prefixLength = CarAdbHotspotDefaults.PREFIX_LENGTH,
            dhcpStart = dhcpStart,
            dhcpEnd = dhcpEnd,
            channel = channel,
            rationale = notes.joinToString("；"),
        )
    }

    // ------------------------------------------------------------ 网卡选择

    private class Iface(val name: String, val addresses: List<String>)

    /**
     * 自动挑一张 AP 网卡。
     *
     * 判据：Wi-Fi 系网卡名 + 不是上行。优先级：
     * 1. **`wlan0`**（参考实现写死的值，见下）——只要它存在且不是当前上行；
     * 2. 探测结果里那张空闲的 Wi-Fi 网卡（如果探测到了但它没在跑热点）；
     * 3. 有 IPv4 且不是上行的那张；
     * 4. 任意 Wi-Fi 系网卡。
     *
     * ## 为什么 `wlan0` 排第一（2026-09-29 修正）
     *
     * 参考实现（`FUCK-Lynk&Co-ADB.apk`）无条件用 `wlan0`，且它是为 Flyme Auto /
     * ECARX 这一代车机写的（本工程实车就是 `com.flyme.auto.*`）。也就是说
     * `wlan0` 在这类车机上是**被验证过**的名字。
     *
     * 而"自动挑一张"只在"车机没有热点"这条分支上用，此时 `wlan0` 通常空闲
     * ——拿它当 AP 是安全的。把验证过的值排在前面，探测只作为**它不存在时**的
     * 兜底，比反过来（先猜再兜底）少一次真机往返。
     *
     * ⚠️ 上行网卡**永远排除**：`wlan0` 正连着手机热点 / 车机 Wi-Fi 时，
     * 把它改成 AP 会直接掐断车机自己的网络。
     */
    private fun autoInterface(
        interfaces: List<Iface>,
        uplink: String?,
        info: CarNativeHotspotInfo?,
        notes: MutableList<String>,
    ): String? {
        val wifi = interfaces.filter { looksLikeWifi(it.name) }
        if (wifi.isEmpty()) return null

        val reference = CarAdbHotspotDefaults.DEFAULT_INTERFACE
        if (wifi.any { it.name == reference } && reference != uplink) {
            notes += "网卡 $reference（参考实现验证过的名字；本机 Wi-Fi 网卡：" +
                wifi.joinToString { it.name } + "）"
            return reference
        }

        // 探测到车机热点时 info.interfaceName 就是**车机正在用的**那张，
        // 拿它自建会直接撞车。所以只在"没探测到热点"时才敢用。
        val probed = info?.interfaceName?.takeIf { !info.detected && it != uplink }
        if (probed != null && wifi.any { it.name == probed }) {
            notes += "网卡 $probed（来自 ADB 探测）"
            return probed
        }

        val notUplink = wifi.filter { it.name != uplink }
        val withAddress = notUplink.filter { it.addresses.isNotEmpty() }
        val chosen = withAddress.firstOrNull() ?: notUplink.firstOrNull() ?: wifi.first()
        notes += "网卡 ${chosen.name}（本机 Wi-Fi 网卡：${wifi.joinToString { it.name }}）"
        return chosen.name
    }

    private fun bandLabelOf(channel: Int): String =
        if (CarAdbHotspotDefaults.isFiveGigahertz(channel)) "5 GHz" else "2.4 GHz"

    /**
     * 这个名字像不像一块**可做 AP 的 Wi-Fi 网卡**？
     *
     * ⚠️ 2026-10-05（为"每台车都能开"）：**原来只认 `wlan/ap/softap`，太窄**。
     * 不同方案/ROM 的 AP 网卡命名差异很大，漏认就会误判"本机没有 Wi-Fi 网卡"
     * （见 [plan] 的回落分支）⇒ 换一台车直接开不了热点。补上的常见形态：
     *
     * | 前缀 | 来源 |
     * |---|---|
     * | `wlan` / `wlan0` | 绝大多数方案（高通/MTK/展锐） |
     * | `ap` / `softap` | 厂商自定义软 AP |
     * | `p2p` / `p2p0` | Wi-Fi Direct 网卡，部分车机 AP 就跑在它上 |
     * | `swlan` | 高通部分固件的软 AP |
     * | `wifi0` / `wlan` | MTK 老方案（`wifi0`/`wifi1` 多网卡） |
     * | `wl` | 少数方案（broadcom 风格 `wl0.x`） |
     * | `ra` / `rai` | MTK `ralink` 无线 |
     *
     * 判据仍然**只放宽前缀、不加任何"必须存在"的假设**：多认几个名字的代价
     * 只是候选集里多一个选项（后面还会用有没有地址、是不是上行来筛），
     * 而漏认的代价是整台车开不了热点。
     */
    private fun looksLikeWifi(name: String): Boolean {
        val lower = name.lowercase()
        return WIFI_PREFIXES.any { lower.startsWith(it) }
    }

    /** [looksLikeWifi] 认的网卡名前缀，按常见度排序（仅作可读性，匹配无顺序语义）。 */
    private val WIFI_PREFIXES = listOf(
        "wlan",
        "ap",
        "softap",
        "p2p",
        "swlan",
        "wifi",
        "wl",
        "ra",
    )

    private fun enumerateInterfaces(): List<Iface> = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
    }.getOrDefault(emptyList()).mapNotNull { network ->
        val name = runCatching { network.name }.getOrNull() ?: return@mapNotNull null
        val addresses = runCatching {
            network.inetAddresses.toList()
                .filterIsInstance<Inet4Address>()
                .map { it.hostAddress.orEmpty() }
        }.getOrDefault(emptyList())
        Iface(name, addresses)
    }

    private fun uplinkInterface(context: Context): String? = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return@runCatching null
        val active = manager.activeNetwork ?: return@runCatching null
        manager.getLinkProperties(active)?.interfaceName
    }.getOrNull()
}
