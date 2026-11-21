package com.dbtool.carplay.api.hotspot

/**
 * **识别「车机自带热点」那张网口与它的地址**（PATCH-0068，2026-10-11）。
 *
 * ## 为什么必须单独识别（真车日志 2026-10-11 08:57，设备 `67b3c24af6b5`）
 *
 * 「复用车机热点」要求把 **车机那张 AP 的地址**通告给 iPhone（iAP2 `wirelessIP`）。
 * 而这条链上我们一直是"把网卡上碰到的地址当成车机热点"：
 *
 * ```
 * 08:57:37.090  manual hotspot precheck: state=ON effective=ON ssid=Lynk   ← 车机热点确实在跑
 * 08:57:37.134  wireless hotspot backend=Manual hotspot iface=wlan0 host=192.168.176.1
 *                ↑ 这是**我们自己** 08:55 自建热点配上去的地址
 * 08:57:37.617  wireless advertise SSID="Lynk" … ip=192.168.176.1
 * 08:58:17.617  wireless attach timed out: iPhone did not open the AirPlay connection（40000ms）
 * ```
 *
 * iPhone 关联的是车机广播的 SSID，却按 `192.168.176.1` 去找 7000 端口 —— 到不了。
 *
 * ## 判据（车主口径：车机热点是**固定网口 + 固定网段**）
 *
 * ### 🔴 本车实测（2026-10-11）：车机热点就在 `wlan0`
 *
 * 诊断包 `1791680364941` 的 `system/wifi_direct.txt` 里有**全量接口清单**
 * （`ip -o link show`）：本机只有两张 Wi-Fi 网卡 —— `wlan0 <UP>` 与 `p2p0 <DOWN>`，
 * 没有 `softap0` / `ap0` / `wifi_br0`。而同一包的复用那行写着
 * `wireless hotspot backend=Manual hotspot iface=wlan0 host=192.168.176.1`。
 *
 * ⇒ **车机的 Lynk 热点与我们的自建热点共用同一张 `wlan0`**。这条事实有两个直接后果：
 *
 * 1. **分辨靠"地址"，不靠"网卡名"**：`wlan0` 上可能同时挂着"车机 AP 的地址"与
 *    "我方自建残留地址"，靠名字永远分不开 —— 只能靠"哪个地址是我们实际配过的"
 *    （[selfAddresses]，来自 `CarAdbHotspotState.hostAddress` / 落盘足迹）；
 * 2. **两种模式在同一张网卡上是互斥的**：谁在 `wlan0` 上起了 AP，谁就占着这张卡
 *    （我们自建时会 `killall hostapd` —— 那会把车机自己的 AP 一起干掉）。
 *    这正是"各模式必须互不影响"要在**启动前**处理的事，而不是等它互相踩。
 *
 * 从"当前可见的网卡与地址"里挑出**最像车机热点**的那一个：
 *
 * 1. 排除：网卡没 up、是上行网卡（默认路由那张）、回环、链路本地（`169.254.*`）；
 * 2. 排除：**我们自己自建热点实际配上去的地址**（[selfAddresses]，由
 *    `CarAdbHotspotState.hostAddress` 给出）—— 那是残留，不是车机热点；
 * 3. 排除：**只落在我方候选池里**的地址（[selfSubnets] = `CarAdbHotspotDefaults.SUBNET_CANDIDATES`）
 *    —— 见下面 PATCH-0086 那段；
 * 4. 在剩下的里按名字排序：`softap*` / `ap*` / `swlan*` / `wifi_br*` 优先于 `wlan*`，
 *    再优先网关形状的地址（末位 `.1`）。
 *
 * ⚠️ **2026-10-11（PATCH-0086）：也按"我方候选网段"排除，但留了直判的优先权。**
 *
 * 旧版本**刻意不按候选网段排除**（那时车机网段还没确定，池子里的 `192.168.175.0/24`
 * 有可能正好是车机自己的地址）。现在车主给了固定网段（本车 `192.168.15.0/24`），
 * 而它与我们的候选池（`192.168.175~179` / `172.20` / `10.42`）**不相交** ⇒
 * 规则可以收紧成：
 *
 * ```
 * 落在「我方候选池」里、**且不落在**[carApSubnet] 里的地址 ⇒ 一律不算车机热点
 * ```
 *
 * ② 与 ③ 是两道并列的排除：② 是"我们实际配过的那一个地址"（最准，哪怕它在别的网段），
 * ③ 是"落在我方候选池里的地址"（足迹丢失后仍有效）。**直判（落在 [carApSubnet] 里）永远
 * 压过 ③** —— 万一哪天车机真的用了池子里的网段，它照样被认出来，不会被 ③ 误杀。
 *
 * ## 为什么必须补 ③（真车 1.0.44，2026-10-11 15:11）
 *
 * 那一版诊断包 `system/wifi_direct.txt` 里写着
 * `车机热点网口: wlan0 192.168.176.1 192.168.176.0/24（车机固定网段 192.168.15.0/24）` ——
 * **把我方自建的 `192.168.176.1` 冒充成了车机热点**。当时 `selfAddresses` 恰好在
 * 进程重启后为空（拿不到 `CarAdbHotspotState.hostAddress`），② 就失效了。
 * 有了 ③，这种"足迹丢了"的场景也不会再冒认自己的地址。
 *
 * 挑不出来就返回 null —— **宁可不猜**：上层据此把"识别不到车机热点网口"直接说出来，
 * 而不是把一个可能连不上的地址通告给 iPhone（那要等 40 秒才失败，且看不出原因）。
 *
 * ## 为什么是纯函数
 *
 * `:car:api` 没有 Robolectric，`NetworkInterface` 测不了；而这条判据判错一次的代价
 * 就是上面那 40 秒超时，所以它必须能在 JVM 上被单测穷举。
 */
object CarHotspotApInterface {

    /**
     * **本车「车机自带热点」的固定网段**（车主口径 2026-10-11：网关 `192.168.15.1`）。
     *
     * ## 为什么可以把它当常量
     *
     * - 车主明确：车机热点是"**固定网口 + 固定网段**"，且**随车自启**；
     * - 它与我们的自建候选池（[CarAdbHotspotDefaults.SUBNET_CANDIDATES]：`192.168.175~179`、
     *   `172.20`、`10.42`）**天然不相交** ⇒ 只看地址落在哪个网段就能分辨
     *   "这是车机热点"还是"这是我们自建留下的"。
     *
     * ⇒ 于是"认车机热点地址"从"排除法 + 猜"变成**直判**：
     * 网卡上落在本网段里的那个 IPv4（本车 `192.168.15.1`）就是它。
     * 候选里一个都不落在这个网段时（别的车型/别的端口），才退回按网卡名与排除法挑。
     */
    const val CAR_AP_SUBNET: String = "192.168.15.0/24"

    /** 一张网卡的快照（纯数据，调用方从 `NetworkInterface` 里读好）。 */
    data class Iface(
        val name: String,
        /** 该网卡上当前的 IPv4 字符串（IPv6 由调用方过滤掉，本判据只认 IPv4）。 */
        val addresses: List<String>,
        val isUp: Boolean = true,
        /** 是不是默认路由那张网卡（上行）。上行永远不是车机热点。 */
        val isUplink: Boolean = false,
    )

    /** 识别结论。 */
    data class Found(
        val interfaceName: String,
        val address: String,
    ) {
        /** `/24` 网段字面量，进日志用。 */
        val subnet: String get() = subnetOf(address)
    }

    /** 网卡名"更像一张 AP"的排序（数值大的优先）。 */
    private fun nameRank(name: String): Int {
        val lower = name.lowercase()
        return when {
            lower.contains("softap") || lower.startsWith("softap") -> 100
            lower.startsWith("ap") -> 90
            lower.startsWith("swlan") -> 80
            lower.startsWith("wifi_br") -> 75
            lower.startsWith("wlan") -> 70
            else -> 0
        }
    }

    /**
     * @param candidates 当前可见的网卡快照（含上行与不 up 的，由本函数过滤）
     * @param selfAddresses **我们自建热点实际配上去的地址**（`CarAdbHotspotState.hostAddress`，
     *        没有则空表）。这些地址一律不算车机热点。
     * @param carApSubnet 车机热点固定网段；落在里面的地址**优先**认定为车机热点
     *        （车主口径：本车 `192.168.15.0/24`）。
     */
    fun identify(
        candidates: List<CarHotspotApInterface.Iface>,
        selfAddresses: List<String> = emptyList(),
        carApSubnet: String = CAR_AP_SUBNET,
        selfSubnets: List<String> = CarAdbHotspotDefaults.SUBNET_CANDIDATES,
    ): Found? {
        val excluded = selfAddresses.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val pool = selfSubnets.map { it.trim() }.filter { it.isNotEmpty() }
        return candidates
            .asSequence()
            .filter { it.isUp && !it.isUplink && it.name != "lo" }
            .flatMap { iface ->
                iface.addresses.asSequence()
                    .map { it.trim() }
                    .filter {
                        isUsableApAddress(it) &&
                            it !in excluded &&
                            !isSelfPoolOnly(it, carApSubnet, pool)
                    }
                    .map { addr -> iface to addr }
            }
            .sortedWith(
                // ① 先看是不是落在**车机固定网段**里（本车 192.168.15.0/24）—— 直判，不猜；
                // ② 再看网卡名像不像 AP（别的车型 / 别的端口时用）；
                // ③ 再看是不是网关形状（末位 .1）。
                compareByDescending<Pair<Iface, String>> { if (inSubnet(it.second, carApSubnet)) 200 else 0 }
                    .thenByDescending { nameRank(it.first.name) }
                    .thenByDescending { it.second.endsWith(".1") }
                    .thenBy { it.first.name },
            )
            .map { (iface, addr) -> Found(interfaceName = iface.name, address = addr) }
            .firstOrNull()
    }

    /**
     * 一行诊断：**识别结论 + 全部候选 + 我方自建地址**。
     *
     * 三样都要有：结论是"我们打算通告哪个地址"，候选与自建地址是"这个结论凭什么成立"。
     * 真车 2026-10-11 08:57 那次排查之所以难，就是日志里只有前者、没有后者 ——
     * 人工看日志时无法判断"认的是哪张网口"。
     */
    fun debugLine(
        candidates: List<CarHotspotApInterface.Iface>,
        selfAddresses: List<String> = emptyList(),
        carApSubnet: String = CAR_AP_SUBNET,
        selfSubnets: List<String> = CarAdbHotspotDefaults.SUBNET_CANDIDATES,
    ): String {
        val found = identify(candidates, selfAddresses, carApSubnet, selfSubnets)
        val all = candidates.joinToString(" | ") { iface ->
            "${iface.name}${if (iface.isUp) "" else "(down)"}" +
                "${if (iface.isUplink) "(上行)" else ""}=[${iface.addresses.joinToString(",")}]"
        }
        val self = if (selfAddresses.isEmpty()) "(无)" else selfAddresses.joinToString(",")
        val conclusion = found?.let { "${it.interfaceName} ${it.address} ${it.subnet}" }
            ?: run {
                // 结论为空时要说**为什么**：只读到"我方自建/候选网段"的地址（真车 1.0.44
                // 就是只读到 192.168.176.1，然后被冒充成车机热点）。不说清这句，
                // 下次看日志的人还是会以为"这就是车机热点"。
                val pool = selfSubnets.map { it.trim() }.filter { it.isNotEmpty() }
                val selfSet = selfAddresses.map { it.trim() }.toSet()
                val ours = candidates.asSequence()
                    .flatMap { iface -> iface.addresses.asSequence().map { iface.name to it.trim() } }
                    .filter { (_, address) ->
                        isUsableApAddress(address) &&
                            (address in selfSet || isSelfPoolOnly(address, carApSubnet, pool))
                    }
                    .map { (iface, address) -> "$iface $address" }
                    .toList()
                if (ours.isEmpty()) {
                    "识别不到"
                } else {
                    "识别不到（读到的只有我方自建/候选网段地址：${ours.joinToString("、")}）"
                }
            }
        return "车机热点网口: $conclusion（车机固定网段 $carApSubnet）" +
            "｜候选：$all｜我方自建地址=$self"
    }

    /**
     * 这个地址是不是"**只属于我方候选池**"（⇒ 不是车机热点）。
     *
     * 判据：**不落在** [carApSubnet] 里 **且** 落在 [selfSubnets] 的某个网段里。
     * 那个"不落在车机网段"的前置条件是直判的优先权所在 —— 车机真的用了池子里的网段时，
     * 它落在 [carApSubnet] 里，本函数返回 false，地址照样被认出来。
     *
     * 公开出来是为了让诊断/测试与识别用的是**同一条**判据（同一台机不能读出两个结论）。
     */
    fun isSelfPoolOnly(
        address: String,
        carApSubnet: String = CAR_AP_SUBNET,
        selfSubnets: List<String> = CarAdbHotspotDefaults.SUBNET_CANDIDATES,
    ): Boolean {
        val trimmed = address.trim()
        if (trimmed.isEmpty()) return false
        if (inSubnet(trimmed, carApSubnet)) return false
        return selfSubnets.any { it.isNotBlank() && inSubnet(trimmed, it) }
    }

    /** 地址可用作"车机热点地址"：是 IPv4、不是链路本地。 */
    fun isUsableApAddress(address: String): Boolean {
        val trimmed = address.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.startsWith("169.254.")) return false
        return isIpv4Literal(trimmed)
    }

    /** IPv4 字面量（四段 0..255）。 */
    fun isIpv4Literal(address: String): Boolean = parseIpv4(address) != null

    /** `a.b.c.d` 是否落在 `a.b.c.d/nn` 里（纯 IPv4，前缀 0..32）。 */
    fun inSubnet(address: String, subnet: String): Boolean {
        val value = parseIpv4(address) ?: return false
        val slash = subnet.indexOf('/')
        if (slash <= 0) return false
        val network = parseIpv4(subnet.substring(0, slash)) ?: return false
        val prefix = subnet.substring(slash + 1).trim().toIntOrNull() ?: return false
        if (prefix !in 0..32) return false
        if (prefix == 0) return true
        val mask = (-1 shl (32 - prefix))
        return (value and mask) == (network and mask)
    }

    /** `/24` 字面量（按地址的前三段拼）。 */
    fun subnetOf(address: String): String {
        val value = parseIpv4(address) ?: return address
        return "${(value ushr 24) and 0xFF}.${(value ushr 16) and 0xFF}.${(value ushr 8) and 0xFF}.0/24"
    }

    private fun parseIpv4(address: String): Int? {
        val parts = address.trim().split('.')
        if (parts.size != 4) return null
        var value = 0
        for (part in parts) {
            val octet = part.toIntOrNull() ?: return null
            if (octet !in 0..255) return null
            value = (value shl 8) or octet
        }
        return value
    }
}
