package com.dbtool.carplay.bridge.hotspot

import android.content.Context

/**
 * 「自建热点留下的足迹」——我们**实际配到网卡上的那个地址**的跨进程记录（PATCH-0068，2026-10-11）。
 *
 * ## 为什么必须有它：各热点模式必须互不污染
 *
 * 三种模式共用同一张物理网卡（本车 `wlan0`）：
 *
 * | 模式 | 会不会在网卡上留下东西 |
 * | --- | --- |
 * | `ADB_HOSTAPD`（自建） | **会**：`ifconfig` 上去的网关地址 + hostapd/udhcpd 进程 + 策略路由 |
 * | `MANUAL`（复用） | 不会（只读车机那张 AP） |
 * | `SAME_LAN` | 不会（一张热点都不建） |
 *
 * 于是"自建 → 复用"这条切换路径上，**复用分支会把自建的残留当成车机热点**：
 * 真车日志 2026-10-11 08:57 就是 `advertise SSID="Lynk" ip=192.168.176.1`（176.1 是我们的）
 * ⇒ iPhone 到不了 ⇒ 40 秒 `attach timed out`。
 *
 * 之前的清理只靠 `CarAdbHotspotManager.activeNetwork`（**内存**）：进程被杀 / 被系统回收后
 * 那个字段没了，网卡上的地址却还在，**没有任何人或代码知道"这是我们留的"** ——
 * 这也是"模式互相影响"最难查的一种形态。
 *
 * ⇒ 这里把足迹**落盘**：自建成功时写，清理确认后删。之后任何模式（或下一次冷启动）
 * 都能回答"网上那个地址是不是我们留的"，从而用 **ADB 工具**把它清掉 ——
 * ADB 是本工程的**共享设施**，不属于任何一个热点模式（车主口径 2026-10-11）。
 *
 * ⚠️ 只记"我们实际配过的那一个地址"，**不按网段记**：`CarAdbHotspotDefaults.SUBNET_CANDIDATES`
 * 里含 `192.168.175.0/24`，而车机自带热点可能正好也在 175（见 [CarHotspotResidue]）
 * ⇒ 按网段清会误删车机自己的地址。
 */
internal object CarHotspotFootprintStore {

    /** 一次自建热点在网卡上留下的足迹。 */
    data class Footprint(
        val interfaceName: String,
        val address: String,
        val prefixLength: Int,
    ) {
        /** `192.168.176.1/24` 形态，进日志用。 */
        val cidr: String get() = "$address/$prefixLength"
    }

    private const val PREFS = "dbplay_hotspot_footprint"
    private const val KEY_INTERFACE = "iface"
    private const val KEY_ADDRESS = "address"
    private const val KEY_PREFIX = "prefix_length"

    fun save(context: Context, footprint: Footprint) {
        if (footprint.interfaceName.isBlank() || footprint.address.isBlank()) return
        prefs(context).edit()
            .putString(KEY_INTERFACE, footprint.interfaceName)
            .putString(KEY_ADDRESS, footprint.address)
            .putInt(KEY_PREFIX, footprint.prefixLength)
            .apply()
    }

    fun load(context: Context): Footprint? {
        val prefs = prefs(context)
        val iface = prefs.getString(KEY_INTERFACE, null)?.takeIf { it.isNotBlank() } ?: return null
        val address = prefs.getString(KEY_ADDRESS, null)?.takeIf { it.isNotBlank() } ?: return null
        return Footprint(iface, address, prefs.getInt(KEY_PREFIX, 24))
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
