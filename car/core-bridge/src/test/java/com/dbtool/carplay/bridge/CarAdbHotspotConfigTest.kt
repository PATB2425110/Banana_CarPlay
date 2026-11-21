package com.dbtool.carplay.bridge

import com.dbtool.carplay.api.hotspot.CarAdbHotspotConfig
import com.dbtool.carplay.api.hotspot.CarAdbHotspotDefaults
import com.dbtool.carplay.api.hotspot.CarHotspotNetwork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 两份热点配置文件的单测。
 *
 * ## 为什么这两份文件值得逐行钉死
 *
 * 它们是**错一个字节就立刻失败**的地方：`hostapd` 解析不了配置会直接退出，
 * 而"hostapd 退出"的现象与"网卡不对""没有 root"完全一样 —— 现场只有一句
 * "热点没起来"，根本分不出是哪一类。这类问题必须**在上车之前**排除掉。
 *
 * ## 期望值是从哪来的（不是我自己编的）
 *
 * 全部来自参考实现（`FUCK-Lynk&Co-ADB.apk`）`OSNwifi.saveSoftAPConf` 的**字节码**：
 *
 * ```
 * "interface=wlan0\ndriver=nl80211 \nieee80211n=1\nieee80211ac=1\nauth_algs=1\n"
 *   + "wpa=2\nwpa_key_mgmt=WPA-PSK\nrsn_pairwise=CCMP\n"     ← 一条 116 字节常量
 *   + "ssid=" + "car" + '\n'
 *   + "wpa_passphrase=" + "12345678" + "\n"
 *   + "hw_mode=a\n"
 *   + "channel=36\n\n"
 * ```
 *
 * 以及 `udhcpd.conf` 那条 97 字节常量。见 `ADB-HOTSPOT.md` §3.8.3。
 */
class CarAdbHotspotConfigTest {

    private fun network(
        channel: Int = 1,
        interfaceName: String = "wlan0",
    ) = CarHotspotNetwork(
        interfaceName = interfaceName,
        subnet = "192.168.175.0/24",
        hostAddress = "192.168.175.1",
        prefixLength = 24,
        dhcpStart = "192.168.175.2",
        dhcpEnd = "192.168.175.254",
        channel = channel,
        rationale = "测试用",
    )

    private fun softap(channel: Int = 1, open: Boolean = false) = CarAdbHotspotConfig.softap(
        network = network(channel = channel),
        ssid = "DBPlay",
        passphrase = "12345678",
        open = open,
    )

    private fun udhcpd() = CarAdbHotspotConfig.udhcpd(network())

    // ------------------------------------------------------------ hostapd

    /**
     * 行的顺序必须与参考实现一致。
     *
     * `hostapd` 本身对顺序不敏感，但**顺序一致是排查手段**：一旦现场出问题，
     * "与参考不同之处"越少越好定位。参考的 9 条前缀行是一条常量，不能拆错。
     */
    @Test
    fun `2_4 GHz 配置行的顺序与参考实现一致`() {
        assertEquals(
            "interface=wlan0\n" +
                "driver=nl80211 \n" +
                "ieee80211n=1\n" +
                "auth_algs=1\n" +
                "wpa=2\n" +
                "wpa_key_mgmt=WPA-PSK\n" +
                "rsn_pairwise=CCMP\n" +
                "ssid=DBPlay\n" +
                "wpa_passphrase=12345678\n" +
                "hw_mode=g\n" +
                "channel=1\n\n",
            softap(channel = 1),
        )
    }

    /** `driver=nl80211 ` 的**尾随空格**是照抄 dex 里的字节，不是笔误。 */
    @Test
    fun `driver 行保留参考实现的尾随空格`() {
        assertTrue(softap().contains("driver=nl80211 \n"))
        // 反过来钉住：不能被人"顺手清理"成不带空格。
        assertFalse(softap().contains("driver=nl80211\n"))
    }

    /** `channel=` 后面是**两个**换行（参考实现如此）。 */
    @Test
    fun `channel 行以两个换行结尾`() {
        assertTrue(softap(channel = 6).endsWith("channel=6\n\n"))
    }

    /**
     * VHT（`ieee80211ac`）是 5 GHz 专属。
     *
     * 2.4 GHz 下带上它，`hostapd` 会以"网卡在该频段不具备 VHT 能力"**直接退出**——
     * 这是配置生成里最容易踩的一个坑（本产品只走 5 GHz，此断言防的是
     * 「信道推导与 VHT 开关脱钩」这类回归）。
     */
    @Test
    fun `2_4 GHz 不带 ieee80211ac 且 hw_mode 为 g`() {
        val text = softap(channel = 11)
        assertFalse(text.contains("ieee80211ac"))
        assertTrue(text.contains("hw_mode=g\n"))
        assertTrue(text.contains("channel=11\n\n"))
    }

    /** 5 GHz 时 VHT 与 `hw_mode=a` 必须配套出现（换到支持 5 GHz 的车机时才用）。 */
    @Test
    fun `5 GHz 带 ieee80211ac 且 hw_mode 为 a`() {
        val text = softap(channel = 36)
        assertTrue(text.contains("ieee80211ac=1\n"))
        assertTrue(text.contains("hw_mode=a\n"))
        assertTrue(text.contains("channel=36\n\n"))
    }

    /** 开放网络：不写 `wpa=` 那几行，也不写口令。 */
    @Test
    fun `开放网络省略 WPA 与口令`() {
        val text = softap(open = true)
        assertFalse(text.contains("wpa=2"))
        assertFalse(text.contains("wpa_key_mgmt"))
        assertFalse(text.contains("rsn_pairwise"))
        assertFalse(text.contains("wpa_passphrase"))
        // `auth_algs=1` 与 `ssid=` 仍必须在。
        assertTrue(text.contains("auth_algs=1\n"))
        assertTrue(text.contains("ssid=DBPlay\n"))
    }

    /** 网卡名跟随选择结果，不写死 `wlan0`。 */
    @Test
    fun `网卡名跟随选择结果`() {
        val text = CarAdbHotspotConfig.softap(
            network = network(interfaceName = "wlan2"),
            ssid = "DBPlay",
            passphrase = "12345678",
            open = false,
        )
        assertTrue(text.startsWith("interface=wlan2\n"))
    }

    /** 凭据取自参数而不是常量（必须与通告给 iPhone 的完全一致）。 */
    @Test
    fun `凭据取自参数`() {
        val text = CarAdbHotspotConfig.softap(
            network = network(),
            ssid = "MyCar",
            passphrase = "abcdefgh",
            open = false,
        )
        assertTrue(text.contains("ssid=MyCar\n"))
        assertTrue(text.contains("wpa_passphrase=abcdefgh\n"))
    }

    // ------------------------------------------------------------ udhcpd

    @Test
    fun `udhcpd 配置内容与顺序`() {
        assertEquals(
            "start 192.168.175.2\n" +
                "end 192.168.175.254\n" +
                "interface wlan0\n" +
                "max_leases 253\n" +
                "opt router 192.168.175.1\n" +
                "lease_file ${CarAdbHotspotDefaults.REMOTE_LEASE_FILE}\n" +
                "\n",
            udhcpd(),
        )
    }

    /**
     * `max_leases` 必须是**池的真实大小**，不是参考实现那个没有来源的 234。
     *
     * 池是 `.2 ~ .254` = 253 个地址。两者功能上无差别（实际只有 1~2 个客户端），
     * 但我们的值随池推导，池变了它跟着变。
     */
    @Test
    fun `max_leases 等于池的真实大小`() {
        val expected = CarAdbHotspotDefaults.DHCP_HOST_END -
            CarAdbHotspotDefaults.DHCP_HOST_START + 1
        assertEquals(253, expected)
        assertTrue(udhcpd().contains("max_leases $expected\n"))
        assertFalse(udhcpd().contains("max_leases 234"))
    }

    /**
     * `lease_file` **必须**有：busybox `udhcpd` 打不开租约文件会**直接退出**，
     * 而它的编译期默认值 `/var/lib/misc/udhcpd.leases` 在 Android 上不存在。
     * 参考实现不写这一行还能跑通是它的运气，不是可复制的做法。
     */
    @Test
    fun `lease_file 必须显式指定且不在 var 下`() {
        val text = udhcpd()
        assertTrue(text.contains("lease_file /data/udhcpd.leases\n"))
        assertFalse(text.contains("/var/lib/misc"))
    }

    /** `opt router` 必须是网关地址，否则客户端拿到 IP 也出不去。 */
    @Test
    fun `opt router 指向网关`() {
        assertTrue(udhcpd().contains("opt router 192.168.175.1\n"))
    }
}
