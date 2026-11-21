package com.dbtool.carplay.api.hotspot

/**
 * 「复用车机热点」的在 attach 之前的**秒级闸门**（2026-10-12，日志 4 排查）。
 *
 * ## 它挡的是什么
 *
 * 日志 4（CS11，2026-10-12）实测：用户在设置里填好了车机热点地址 `198.18.34.15`，
 * 二开层自己的 Java 预检也打 `manual hotspot precheck: state=ON effective=ON`
 * 并且 `endpoint: source=user configuration … interface=eth0.11` —— **说通过**。
 *
 * 但同一时刻上游 `ManualHotspotManager` 的"按地址找网卡"把带这个地址的网卡全排掉了
 * （当时 `eth` 在排除表里），于是：
 *
 * ```text
 * manual hotspot host address 198.18.34.15 was not found on any interface; falling back to interface scoring
 * java.io.IOException: Timed out after 60000ms waiting for the manual hotspot: local hotspot interface was not found
 * ```
 *
 * 4 分钟日志里这样白等了 **60 秒**，而且每 2.27 秒刷一行 warning。
 *
 * ## 为什么闸门要放在这里（而不是等上游超时）
 *
 * 两条判据本来就是**同一时刻读的同一批网卡**，我们这边知道结论、上游那边不知道。
 * 与其让流程滑到上游空转一分钟再报一句对我们毫无信息量的英文，不如在这里把它
 * 变成一句给用户的中文提示。这不改变"什么算可用"的定义，只改变**什么时候说出来**。
 *
 * ## 判据（刻意保守）
 *
 * - Ecarx 已验证过一条客户端 route（[verifiedRouteInterface] 非空）⇒ **永不拦**。
 *   那意味着车机 SDK 亲眼看到 iPhone 挂在车机热点上，是这里能拿到的最强证据。
 * - 地址根本不在任何网卡上 ⇒ 拦（[CarManualEndpointVerdict.NOWHERE]）——
 *   与改动前的行为一致。
 * - 地址**只落在我方自建候选网段**里 ⇒ 拦（[CarManualEndpointVerdict.SELF_POOL]）——
 *   2026-10-13 新增。那是「自建热点」的残留地址（真车 08:57 的 `192.168.176.1`），
 *   车机热点固定 `192.168.15.0/24`、与池不相交（见 [CarHotspotApInterface.isSelfPoolOnly]），
 *   把它通告给 iPhone 必然 40 秒 attach 超时。判据与识别侧（[CarHotspotApInterface.identify]、
 *   [CarHotspotEndpointCandidates.of]）同源 ⇒ 设置页候选列表不会推荐出一个这里又拦下的值。
 * - 地址**只在有线口**上 ⇒ 拦（[WiredOnlyVerdict][com.dbtool.carplay.api.hotspot.CarManualEndpointVerdict.WIRED_ONLY]）——
 *   本次新增。这是日志 4 那种"预检说通过、上游必然失败"的情形。
 * - 地址在**非有线口**（`wlan*` / `ap*` / `p2p*` …）上 ⇒ **不拦**，照旧交给后面。
 *   那种情况真有可能成功，宁可放过去。
 */
object CarManualEndpointGate {

    /**
     * 有线口前缀 —— iPhone 不可能通过 Wi-Fi 关联到它们。
     *
     * ⚠️ 与 `ManualHotspotManager.GUESS_EXCLUDED_INTERFACE_PREFIXES` 是**同一份语义的两处**
     * （一个在 `:shared`、一个在 `:car:api`，跨模块引不动）。改一处必须改另一处，
     * 并有 `SettingsAutosaveContractTest` 之外的 `ManualHotspotExclusionSplitTest` 钉住两边一致。
     */
    val WIRED_INTERFACE_PREFIXES: List<String> = listOf("eth", "usb")

    /**
     * @param address 用户在设置里填的车机热点地址（`manualHotspotHostAddress`）。
     * @param interfaces 当前网卡快照（`CarNetworkInterfaces.snapshot()`）。
     * @param verifiedRouteInterface Ecarx **已验证**的客户端 route 所在网卡名；
     *        非空即"车机 SDK 亲眼看到客户端"。上游对应 `hostInterfaceOverride`。
     * @param carApSubnet 车机热点固定网段（本车 `192.168.15.0/24`）；落在里面的地址
     *        **不会**被当成自建残留（直判优先权，与识别侧一致）。
     * @param selfSubnets 我方自建热点候选网段池（`CarAdbHotspotDefaults.SUBNET_CANDIDATES`）。
     * @return 放行（[CarManualEndpointVerdict.OK]）或拦截（带原因与涉事网卡名）。
     */
    fun evaluate(
        address: String,
        interfaces: List<CarHotspotApInterface.Iface>,
        verifiedRouteInterface: String? = null,
        carApSubnet: String = CarHotspotApInterface.CAR_AP_SUBNET,
        selfSubnets: List<String> = CarAdbHotspotDefaults.SUBNET_CANDIDATES,
    ): CarManualEndpointVerdict {
        val target = address.trim()
        if (target.isEmpty()) {
            // 没填地址 ⇒ 这里无从判断，交给上游的打分路径（它自己会按名字猜）。
            return CarManualEndpointVerdict.OK
        }
        if (!verifiedRouteInterface.isNullOrBlank()) {
            // 最强证据在手：车机 SDK 看到 iPhone 挂在车机热点上 ⇒ 什么都不拦。
            return CarManualEndpointVerdict.OK
        }
        val carriers = interfaces.filter { iface ->
            iface.isUp && iface.addresses.any { it.trim() == target }
        }
        if (carriers.isEmpty()) return CarManualEndpointVerdict.NOWHERE
        // 落在我方自建候选池里的地址通告给 iPhone 必然连不上（那是我们上次自建
        // 留下的网段，车机热点不在那里）。放在有线判定**之前**：自建残留挂在哪张
        // 卡上都不改变"它不是车机热点"这件事，这条文案更直指下一步动作。
        if (CarHotspotApInterface.isSelfPoolOnly(target, carApSubnet, selfSubnets)) {
            return CarManualEndpointVerdict.SELF_POOL
        }
        val wired = carriers.filter { iface -> isWired(iface.name) }
        // 只在有线口上 ⇒ 拦；只要有一张非有线口也带着它，就放行（不误杀）。
        if (wired.size == carriers.size) {
            return CarManualEndpointVerdict.WIRED_ONLY(wired.map { it.name }.sorted())
        }
        return CarManualEndpointVerdict.OK
    }

    /** 名字是否像有线口（大小写不敏感，与上游 `startsWith` 语义对齐）。 */
    fun isWired(name: String): Boolean =
        WIRED_INTERFACE_PREFIXES.any { prefix -> name.startsWith(prefix, ignoreCase = true) }
}

/**
 * 闸门结论。
 *
 * ⚠️ [OK] 与 `null` 的区别：这里是"明确判定可以放过去"，`null` 是"没判"。
 * 调用方按 `verdict != OK` 拦截即可。
 */
sealed interface CarManualEndpointVerdict {

    /** 放行：要么有 Ecarx 已验证 route，要么地址在非有线口上，要么用户没填地址。 */
    data object OK : CarManualEndpointVerdict

    /** 地址不在任何一张 UP 的网卡上（= 改动前 `addressPresentAnywhere() == null` 的情形）。 */
    data object NOWHERE : CarManualEndpointVerdict

    /**
     * 地址**只落在我方自建热点候选网段**里（`CarAdbHotspotDefaults.SUBNET_CANDIDATES`，
     * 且不落在车机固定网段里）—— 即「自建热点」的残留地址。
     *
     * 把它通告给 iPhone 必然连不上（车机热点不在那个网段），真车 2026-10-11 08:57
     * 的 `192.168.176.1` 就是这么白等 40 秒的。判据与识别侧
     * （`CarHotspotApInterface.isSelfPoolOnly`）同源。
     */
    data object SELF_POOL : CarManualEndpointVerdict

    /**
     * 地址在，但**只在**有线口上，且没有 Ecarx 已验证 route。
     *
     * @param interfaces 涉事网卡名（已排序），进诊断用。
     */
    data class WIRED_ONLY(val interfaces: List<String>) : CarManualEndpointVerdict
}
