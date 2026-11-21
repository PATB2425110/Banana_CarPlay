package com.dbtool.carplay.api.hotspot

/**
 * `iw dev` 输出的纯解析。
 *
 * ## 为什么需要它：车机"存储的热点配置"与"实际在广播的 AP"可以是两个名字
 *
 * 2026-09-29 实车（领克 / Flyme Auto，Android 9）踩到一次：
 *
 * - `WifiManager.getWifiApConfiguration()`（也就是上游 `ManualHotspotManager`
 *   校验用的那个值）读出 **`Lynk`**；
 * - 而 `iw dev` 说 `wlan0` **实际在广播 `car`**（前一版应用留下的 AP，
 *   `hostapd` 只在启动时读一次配置，改名不会让它重启）。
 *
 * 结果：我们把**存储配置**里的 `Lynk` 发给了 iPhone（`0x5703`），
 * 而车机上根本没有叫 `Lynk` 的网络 ⇒ iPhone 永远关联不上 ⇒
 * iAP2 无线隧道起不来 ⇒ 车机界面一直停在「无线通道就绪」，
 * **而日志里没有任何一处报错**。
 *
 * 所以"实际广播的 SSID"必须是一个**一等事实**：它与存储配置不一致时，
 * 我们发给 iPhone 的那个名字就注定连不上。本文件只负责把它读出来，
 * 怎么用由 `CarNativeHotspotProbe` / `CarHotspotResolver` 决定。
 *
 * ## 为什么要求 `type AP`
 *
 * 同一张网卡在不同时刻可能是 STA（连着别人的 Wi-Fi）也可能是 AP。
 * STA 的 `ssid` 是**上行网络**的名字，把它当成"热点名字"会让 iPhone 去连
 * 一个毫不相干的网络 —— 比读不到更糟。所以只有 `type` 以 `AP` 开头时才算数。
 */
object CarIwDevOutput {

    /** `Interface wlan0` 起、到下一个 `Interface …` 前（或文本结束）为止的一段。 */
    private val IFACE_RE = Regex(
        "Interface[ \\t]+(\\S+)([\\s\\S]*?)(?=\\n[ \\t]*Interface[ \\t]|\\z)",
    )

    private val SSID_RE = Regex("^[ \\t]*ssid[ \\t]+(.+?)[ \\t]*$", RegexOption.MULTILINE)

    private val TYPE_RE = Regex("^[ \\t]*type[ \\t]+(\\S+)", RegexOption.MULTILINE)

    /**
     * [iface] **正在以 AP 模式广播**的 SSID。
     *
     * @param iface AP 网卡名。传 null / 空白时退化为"整段里第一个 `ssid`"
     *        ——仅用于没有网卡名可用的兜底路径，正常调用都应传网卡名。
     * @return 读不到、或该网卡不在 AP 模式时为 null（**不猜、不填默认值**）。
     */
    fun apSsid(iwDev: String, iface: String?): String? {
        if (iwDev.isBlank()) return null
        if (iface.isNullOrBlank()) return firstSsid(iwDev)
        val section = sectionOf(iwDev, iface) ?: return null
        if (!isApMode(section)) return null
        return firstSsid(section)
    }

    /** 该网卡是否处于 AP 模式（`type` 以 `AP` 开头，含 `AP/VLAN`）。 */
    fun isApMode(iwDev: String, iface: String?): Boolean {
        if (iwDev.isBlank()) return false
        val section = if (iface.isNullOrBlank()) iwDev else sectionOf(iwDev, iface) ?: return false
        return isApMode(section)
    }

    private fun isApMode(section: String): Boolean =
        TYPE_RE.find(section)?.groupValues?.get(1)?.startsWith("AP") == true

    private fun firstSsid(section: String): String? =
        SSID_RE.find(section)
            ?.groupValues?.get(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it != "<hidden>" }

    /**
     * 取 [iface] 那一节。
     *
     * ⚠️ 必须**在下一个 `Interface` 处截断**：`wlan0` 没有 `ssid` 行而
     * 紧随其后的 `p2p0` 有时，一路取到文本结尾就会把别人的 SSID 当成自己的。
     */
    private fun sectionOf(iwDev: String, iface: String): String? =
        IFACE_RE.findAll(iwDev)
            .firstOrNull { it.groupValues[1] == iface }
            ?.groupValues?.get(2)
}
