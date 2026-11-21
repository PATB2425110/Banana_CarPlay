package com.dbtool.carplay.api.hotspot

/**
 * 生成 `hostapd` / `udhcpd` 的两份配置文件内容。
 *
 * ## 为什么单独抽出来
 *
 * 这两份文件是**错一个字节就立刻失败**的地方：`hostapd` 解析不了配置会直接退出，
 * 而它退出的现象是"热点没起来"，与"网卡不对""没有 root"完全一样，非常难区分。
 * 原先这段内联在 `CarAdbHotspotManager.writeLocalConfig` 里（要 `Context`），
 * **只能靠上车试**。抽成纯函数后，每一行的顺序与内容都能被单测钉住。
 *
 * ## 行的顺序与内容是怎么定下来的
 *
 * 逐字对照参考实现（`FUCK-Lynk&Co-ADB.apk`）`OSNwifi.saveSoftAPConf` 的**字节码**
 * （`dexdump -d` 读出的 `StringBuilder.append` 序列，见 `ADB-HOTSPOT.md` §3.8.3）。
 * 参考的拼接序列是：
 *
 * ```
 * "interface=wlan0\ndriver=nl80211 \nieee80211n=1\nieee80211ac=1\nauth_algs=1\n"
 *   + "wpa=2\nwpa_key_mgmt=WPA-PSK\nrsn_pairwise=CCMP\n"     ← 一条 116 字节常量
 *   + "ssid=" + <值> + '\n'
 *   + "wpa_passphrase=" + <值> + "\n"
 *   + "hw_mode=a\n"                                          ← 含换行，10 字节
 *   + "channel=36\n\n"                                       ← 两个换行，12 字节
 * ```
 *
 * ⚠️ **`driver=nl80211 ` 的尾随空格是照抄的**：dex 里那条字符串就带一个空格。
 * `hostapd` 会 trim 它，功能上无差别，但保持逐字一致能让"到底哪一处与参考不同"
 * 永远只有一处可疑。
 *
 * ⚠️ **`channel=` 后面是**两个**换行**，不是笔误——参考实现就是这样，照抄。
 *
 * ## 与参考实现有意不同的地方
 *
 * | 项 | 参考 | 这里 | 理由 |
 * | --- | --- | --- | --- |
 * | `interface=` | 写死 `wlan0` | 跟随 [CarHotspotNetwork.interfaceName] | 写死正是参考换车就废的原因 |
 * | `ieee80211ac=1` | 恒有（它恒 5 GHz） | **仅 5 GHz 时加** | VHT 是 5 GHz 专属；2.4 GHz 下 hostapd 会因"网卡不具备该能力"退出 |
 * | `hw_mode` | 恒 `a` | 由信道推导（[CarAdbHotspotDefaults.hwModeOfChannel]） | `hw_mode=g` + `channel=36` 会让 hostapd 直接拒绝启动 |
 * | `ssid` / `wpa_passphrase` | 写死 `car` / `12345678` | 取设置页 | 产品要求 SSID `DBPlay`；且凭据必须与通告给 iPhone 的完全一致 |
 * | 网段 / 网关 | 写死 `192.168.175.x` | 动态选出的不冲突网段 | 同上 |
 * | `max_leases` | `234`（无来源的魔数） | `254-2+1 = 253`（池的真实大小） | 见 [udhcpd] |
 * | `lease_file` | **没有** | 显式指到 `/data/udhcpd.leases` | 见 [udhcpd] |
 */
object CarAdbHotspotConfig {

    /**
     * `hostapd` 的配置。
     *
     * @param ssid 热点名。**必须与通告给 iPhone 的完全一致**，否则 iPhone 会拿着
     *        A 密码去连 B 密码的 AP。
     * @param passphrase WPA2-PSK 口令。`open = true` 时忽略。
     * @param open true = 开放网络（不写 `wpa=` 那几行，也不写口令）。
     */
    fun softap(
        network: CarHotspotNetwork,
        ssid: String,
        passphrase: String,
        open: Boolean,
    ): String {
        // 频段 / `hw_mode` / `ieee80211ac` 三者必须自洽，所以只从信道推导一次。
        val fiveGigahertz = CarAdbHotspotDefaults.isFiveGigahertz(network.channel)
        return buildString {
            append("interface=").append(network.interfaceName).append('\n')
            // ⚠️ 尾随空格照抄参考实现（见类文档）。
            append("driver=nl80211 ").append('\n')
            // 802.11n（HT）两个频段都有效，保留能提高吞吐。
            append("ieee80211n=1").append('\n')
            if (fiveGigahertz) {
                // VHT：**5 GHz 专属**，与 `hw_mode=a` 配套。
                append("ieee80211ac=1").append('\n')
            }
            append("auth_algs=1").append('\n')
            if (!open) {
                append("wpa=2").append('\n')
                append("wpa_key_mgmt=WPA-PSK").append('\n')
                append("rsn_pairwise=CCMP").append('\n')
            }
            append("ssid=").append(ssid).append('\n')
            if (!open) {
                append("wpa_passphrase=").append(passphrase).append('\n')
            }
            append("hw_mode=").append(CarAdbHotspotDefaults.hwModeOfChannel(network.channel))
                .append('\n')
            // ⚠️ 两个换行，照抄参考实现。
            append("channel=").append(network.channel).append("\n\n")
        }
    }

    /**
     * `busybox udhcpd` 的配置。
     *
     * ## `max_leases` 为什么不是参考实现那个 234
     *
     * 参考写 `max_leases 234`，而它的池是 `.2~.254`（**253** 个地址）——对不上，
     * 是个**没有来源的魔数**。这里用池的真实大小（`254-2+1 = 253`）：
     * 两者都只是"同时存在的租约数上限"，实际只有 1~2 个客户端 ⇒ 功能上无差别，
     * 但我们的值随池推导，池变了它跟着变。**抄一个无法解释的魔数只会让以后的人
     * 以为它有意义。**
     *
     * ## `lease_file` 是**必要加固**，不是多余的一行
     *
     * 参考实现**不写**这一行。但 busybox `udhcpd` 在**打不开租约文件时会直接退出**，
     * 而它编译期的默认值是 `/var/lib/misc/udhcpd.leases` —— Android 上这个目录
     * 根本不存在。参考能跑通说明它那份 busybox 的默认值恰好可用（或编译时去掉了
     * 该特性），**那是它的运气，不是可复制的做法**。
     *
     * 这里显式给一个 root 可写的路径。`lease_file` 是合法的 udhcpd 配置键，
     * 且 udhcpd 逐行解析 ⇒ 放在 `opt router` 之后不影响解析。
     */
    fun udhcpd(network: CarHotspotNetwork): String = buildString {
        append("start ").append(network.dhcpStart).append('\n')
        append("end ").append(network.dhcpEnd).append('\n')
        append("interface ").append(network.interfaceName).append('\n')
        // DHCP 池大小 = 池里地址的个数（`.2 ~ .254` → 253）。
        append("max_leases ").append(
            CarAdbHotspotDefaults.DHCP_HOST_END - CarAdbHotspotDefaults.DHCP_HOST_START + 1,
        ).append('\n')
        append("opt router ").append(network.hostAddress).append('\n')
        append("lease_file ${CarAdbHotspotDefaults.REMOTE_LEASE_FILE}").append('\n')
        // 结尾空行，与参考实现一致。
        append('\n')
    }
}
