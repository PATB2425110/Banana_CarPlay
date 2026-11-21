package com.dbtool.carplay.api.hotspot

/**
 * 「本机可用的 CarPlay endpoint 候选」里的一条（PATCH-0102，2026-10-12）。
 *
 * @property interfaceName 承载该地址的网卡名（`eth0` / `wlan0` / `eth0.11` …）。
 *        进界面与日志用 —— 同一个地址可能同时挂在基础网卡与它的 VLAN 子接口上，
 *        报出名字才知道"这个地址是从哪张卡上读到的"。
 * @property address 本机网卡上**当前真实存在**的 IPv4 字面量。
 */
data class CarHotspotEndpointCandidate(
    val interfaceName: String,
    val address: String,
) {
    /** 界面上一行显示成 `198.18.34.15（eth0）`。 */
    val label: String get() = "$address（$interfaceName）"
}

/**
 * 从「当前可见的网卡与地址」里挑出**用户可以直接填进「本机 CarPlay 地址」**的那些
 * （PATCH-0102，2026-10-12）。
 *
 * ## 它解决的是什么（真车实证）
 *
 * 「复用车机热点」要求用户**手填**一个"iPhone 能访问到的本机 AirPlay 地址"
 * （PATCH-0087 车主口径：不替用户猜网卡）。但设置页过去**只给一个空输入框**，
 * 用户没有任何途径知道该填什么 —— 于是出现闭环自相矛盾：
 *
 * ```
 * 16:58:41  地址留空 → App：「请填上车机热点网卡的地址 —— 本车是 192.168.15.1」
 * 16:59:48  用户照填 192.168.15.1 → App：「你填的地址现在不在任何网卡上」
 * ```
 *
 * 而 `192.168.15.1` 是**外置 T-Box 热点网关**，永远不会出现在 Android 本机网卡上
 * ⇒ **照提示填必然失败**。本机真正的地址是 `eth0 = 198.18.34.15`。
 *
 * 这个类就是把"本机现在到底有哪些地址可以填"算出来，让界面**列给用户点选**，
 * 而不是让用户去猜一个提示文案里写死的错值。
 *
 * ## 判据（只做"能填"的筛，不做"该填哪个"的猜）
 *
 * 保留：
 * - 网卡 `isUp` 且**不是回环**（`lo` 上的 `127.0.0.1` 对 iPhone 毫无意义）；
 * - 地址是 IPv4 字面量、且不是链路本地（`169.254.*`）。
 *
 * 排除：
 * - [selfAddresses]：**我们自建热点实际配上去**的地址（调用方给，最准）；
 * - 落在我方候选池里、且不在车机固定网段里的地址（[isSelfPoolOnly]）——
 *   那是自建残留，把它推荐给"复用车机热点"正是历史上认错地址的老毛病。
 *
 * ⚠️ **刻意不排除"上行网卡"**：本车实测默认路由那张卡就是 `eth0.11`，
 * 而它挂的 `198.18.34.15` 恰恰是唯一可用的 endpoint —— 排除上行会把正确答案筛掉。
 *
 * ## 排序与去重
 *
 * 同一个地址常同时挂在 `eth0` 与 `eth0.11` / `eth0.2` 等子接口上。排序让
 * **基础网卡（名字里不带 `.`）排在前面**，再按网卡名、地址字典序；随后按地址去重
 * ⇒ 本车得到唯一一条 `198.18.34.15（eth0）`，而不是七条重复项。
 *
 * ## 为什么是纯函数
 *
 * `:car:api` 没有 Robolectric，`NetworkInterface` 读不了；而这条判据判错的代价
 * 就是"又把用户引到一个填了必然失败的地址上"。所以它必须在 JVM 上能被单测穷举
 * （测试落在 `:car:core-bridge` 的 test source set，见 `REF-pitfalls.md` §127）。
 */
object CarHotspotEndpointCandidates {

    /**
     * @param candidates 当前可见的网卡快照（含 down 的与上行，由本函数过滤）。
     * @param selfAddresses **我们自建热点实际配上去**的地址（`CarAdbHotspotState.hostAddress`，
     *        没有则空表）。这些不算"可推荐的本机地址"。
     * @param carApSubnet 车机热点固定网段；落在里面的地址**不会**被当成自建残留。
     * @param selfSubnets 我方自建候选网段池。
     */
    fun of(
        candidates: List<CarHotspotApInterface.Iface>,
        selfAddresses: List<String> = emptyList(),
        carApSubnet: String = CarHotspotApInterface.CAR_AP_SUBNET,
        selfSubnets: List<String> = CarAdbHotspotDefaults.SUBNET_CANDIDATES,
    ): List<CarHotspotEndpointCandidate> {
        val excluded = selfAddresses.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        return candidates
            .asSequence()
            .filter { it.isUp && it.name != "lo" }
            .flatMap { iface ->
                iface.addresses.asSequence()
                    .map { it.trim() }
                    .filter { address ->
                        CarHotspotApInterface.isUsableApAddress(address) &&
                            address !in excluded &&
                            !CarHotspotApInterface.isSelfPoolOnly(address, carApSubnet, selfSubnets)
                    }
                    .map { address -> CarHotspotEndpointCandidate(iface.name, address) }
            }
            .sortedWith(
                // ① 基础网卡优先（`eth0` 排在 `eth0.11` / `eth0.2` 前面）；
                // ② 再按网卡名、地址字典序，让同一台机的输出**稳定**（去重取谁不再随机）。
                compareBy<CarHotspotEndpointCandidate> { if (it.interfaceName.contains('.')) 1 else 0 }
                    .thenBy { it.interfaceName }
                    .thenBy { it.address },
            )
            .distinctBy { it.address }
            .toList()
    }
}
