package com.dbtool.carplay.bridge.hotspot

import com.dbtool.carplay.api.hotspot.CarConfigFileDigest
import com.dbtool.carplay.api.hotspot.CarHotspotProbeEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「配置文件 dump 别让样板注释吃掉诊断预算」这条修正。
 *
 * ## 为什么专门钉它
 *
 * 2026-09-29 实车诊断包，探测步骤 `[15] 运行中的热点配置（hostapd.conf，含明文密码）`
 * 的**标题承诺了明文密码，输出里却没有密码**：
 *
 * ```
 * [15/1] === /data/vendor/wifi/hostapd/hostapd.conf ===
 * interface=wlan1
 * driver=nl80211
 * …
 * ssid2=416e64726f696453686172655f34363832
 * …（本条已裁剪到 4000 字符，完整内容见原始日志）
 * ```
 *
 * `/data/vendor/wifi/hostapd/hostapd.conf` 是 **AOSP 模板**，前面两百多行全是注释；
 * `clipForDisplay` 按**字符数**裁 ⇒ 4000 字符全被注释吃掉 ⇒
 * `wpa_passphrase=` / `channel=` / `hw_mode=` / `wpa=` 一个都没留下。
 *
 * 而这几个值正是我们发给 iPhone 的 `0x5703` 里的 SSID 与密码——
 * 任一个与网卡实际在用的不一致，iPhone 都关联不上，**且链路上不报任何错**。
 * 也就是说：这一步的裁剪，恰好裁掉了它存在的理由。
 *
 * 下面第 1 条用的是那次日志的原文；第 2 条把"旧行为会丢密码"也钉住，
 * 免得以后有人把压缩又改回纯字符裁剪。
 */
class CarConfigFileDigestTest {

    /** 实车日志 `[15]` 那一步的原文（含 `catMany` 的分隔行与结尾的裁剪标记）。 */
    private val realWorld = """
        === /data/vendor/wifi/hostapd/hostapd.conf ===
        ##### hostapd configuration file ##############################################
        # Empty lines and lines starting with # are ignored

        # AP netdevice name (without 'ap' postfix, i.e., wlan0 uses wlan0ap for
        # management frames with the Host AP driver); wlan0 with many nl80211 drivers
        # Note: This attribute can be overridden by the values supplied with the '-i'
        # command line parameter.
        interface=wlan1

        # Driver interface type (hostap/wired/none/nl80211/bsd);
        # default: hostap). nl80211 is used with all Linux mac80211 drivers.
        driver=nl80211

        # hostapd event logger configuration
        logger_syslog=-1
        logger_syslog_level=2
        logger_stdout=-1
        logger_stdout_level=2

        # Dump file for state information (on SIGUSR1)
        dump_file=/tmp/hostapd.dump

        # Interface for separate control program.
        ctrl_interface=/data/vendor/wifi/hostapd/ctrl

        ##### IEEE 802.11 related configuration #######################################

        # SSID to be used in IEEE 802.11 management frames
        #ssid=QualcommSoftAP
        # Alternative formats for configuring SSID
        # (double quoted string, hexdump, printf-escaped string)
        ssid2=416e64726f696453686172655f34363832
        …（本条已裁剪到 4000 字符，完整内容见原始日志）
    """.trimIndent()

    @Test
    fun `实车原文：压缩后有效设置全在，注释与空行被略去`() {
        val compacted = CarConfigFileDigest.compact(realWorld)

        // 有效设置一行不少
        assertTrue(compacted.contains("interface=wlan1"))
        assertTrue(compacted.contains("driver=nl80211"))
        assertTrue(compacted.contains("ctrl_interface=/data/vendor/wifi/hostapd/ctrl"))
        assertTrue(compacted.contains("ssid2=416e64726f696453686172655f34363832"))

        // 十六进制 SSID 顺手解码出来（这次是靠手工才认出是 AndroidShare_4682）
        assertTrue(compacted.contains("AndroidShare_4682"))

        // catMany 的分隔行必须留：一次 cat 多个路径，丢了就分不清来源
        assertTrue(compacted.contains("=== /data/vendor/wifi/hostapd/hostapd.conf ==="))

        // 模板注释不再占位
        assertFalse(compacted.contains("SSID to be used in IEEE 802.11 management frames"))
        assertFalse(compacted.contains("Empty lines and lines starting with # are ignored"))

        // 略去了多少行要写在明面上，免得"少了几行"被当成"文件本来就这么短"
        assertTrue(compacted.contains("已略去"))

        // 「被裁过」这件事本身也是证据，不能压掉
        assertTrue(compacted.contains("本条已裁剪到 4000 字符"))

        assertTrue(compacted.length < realWorld.length)
    }

    @Test
    fun `压缩之后有效设置才进得了展示预算`() {
        val template = buildString {
            repeat(200) { appendLine("# 这是 AOSP hostapd.conf 模板里的样板注释，用来占掉裁剪预算。") }
            appendLine("interface=wlan0")
            appendLine("ssid=Lynk")
            appendLine("wpa_passphrase=12345678")
            appendLine("channel=6")
            appendLine("hw_mode=g")
        }

        // 旧行为（直接按字符裁）：预算被注释吃光，密码根本不在结果里。
        val oldBehavior = template.take(CarHotspotProbeEntry.DISPLAY_LIMIT)
        assertFalse(oldBehavior.contains("wpa_passphrase="))

        // 新行为（先压再裁）：有效设置一条不少，且远小于预算。
        val compacted = CarConfigFileDigest.compact(template)
        assertTrue(compacted.contains("interface=wlan0"))
        assertTrue(compacted.contains("wpa_passphrase=12345678"))
        assertTrue(compacted.contains("channel=6"))
        assertTrue(compacted.contains("hw_mode=g"))
        assertTrue(compacted.length <= CarHotspotProbeEntry.DISPLAY_LIMIT)
    }

    @Test
    fun `多个文件的 dump：每个分隔行都在`() {
        val multi = """
            === /data/vendor/wifi/hostapd/hostapd.conf ===
            # 注释
            interface=wlan1
            === /data/misc/wifi/hostapd.conf ===
            # 注释
            interface=wlan0
            ssid2=636172
        """.trimIndent()

        val compacted = CarConfigFileDigest.compact(multi)
        assertTrue(compacted.contains("=== /data/vendor/wifi/hostapd/hostapd.conf ==="))
        assertTrue(compacted.contains("=== /data/misc/wifi/hostapd.conf ==="))
        assertTrue(compacted.contains("interface=wlan0"))
        // 636172 = "car"（这次实车网卡实际广播的那个名字）
        assertTrue(compacted.contains("car"))
    }

    @Test
    fun `值里的井号是值的一部分，不能当注释切掉`() {
        val compacted = CarConfigFileDigest.compact(
            """
            wpa_passphrase=abc#def
            # 整行注释
            ssid=My;Net
            """.trimIndent(),
        )

        assertTrue(compacted.contains("wpa_passphrase=abc#def"))
        assertTrue(compacted.contains("ssid=My;Net"))
        assertFalse(compacted.contains("整行注释"))
    }

    @Test
    fun `全是注释或空行时返回原文，不把这一步显示成空白`() {
        val onlyComments = "# 只有注释\n\n# 还是注释\n"

        assertEquals(onlyComments, CarConfigFileDigest.compact(onlyComments))
    }

    @Test
    fun `空输出原样返回`() {
        assertEquals("", CarConfigFileDigest.compact(""))
        assertEquals("   \n  ", CarConfigFileDigest.compact("   \n  "))
    }

    @Test
    fun `没有注释与空行时不做任何改动`() {
        val clean = "interface=wlan0\nssid=Lynk\nwpa_passphrase=12345678"

        assertEquals(clean, CarConfigFileDigest.compact(clean))
    }

    @Test
    fun `ssid2 的十六进制解码`() {
        assertEquals("AndroidShare_4682", CarConfigFileDigest.decodeSsid2("416e64726f696453686172655f34363832"))
        assertEquals("car", CarConfigFileDigest.decodeSsid2("636172"))
        assertEquals("Lynk", CarConfigFileDigest.decodeSsid2("4C796E6B"))
        // 大写十六进制也认
        assertEquals("car", CarConfigFileDigest.decodeSsid2("636172".uppercase()))
    }

    @Test
    fun `解不出来就不猜`() {
        assertNull(CarConfigFileDigest.decodeSsid2(""))
        assertNull(CarConfigFileDigest.decodeSsid2("416"))          // 奇数长度
        assertNull(CarConfigFileDigest.decodeSsid2("zz"))           // 非十六进制
        assertNull(CarConfigFileDigest.decodeSsid2("ff"))           // 不是合法 UTF-8
        assertNull(CarConfigFileDigest.decodeSsid2("2020"))         // 全是空格 ⇒ 不算名字
    }

    @Test
    fun `解不出来的 ssid2 只留原行，不追加半截注释`() {
        val compacted = CarConfigFileDigest.compact("ssid2=ff")

        assertEquals("ssid2=ff", compacted)
    }

    @Test
    fun `粘在上一行末尾的分隔行被拆回行首（实车 softap 配置结尾没有换行）`() {
        // 实车 `[17]` 原文：`/data/misc/wifi/softap.conf` 结尾没有换行，
        // 于是下一个候选路径的标题与"密码"挤在同一行，归属当场串掉。
        val realWorld = "������LYNK&CO������������12345678=== /data/vendor/wifi/softap.conf ===\n" +
            "cat: /data/vendor/wifi/softap.conf: No such file or directory\n"

        val lines = CarConfigFileDigest.compact(realWorld).split('\n')

        // 分隔行必须独占一行，否则"这行来自哪个文件"就无从判断
        assertTrue(lines.any { it.trim() == "=== /data/vendor/wifi/softap.conf ===" })
        // 密码那行不再拖着别人的标题
        assertTrue(lines.any { it.contains("12345678") && !it.contains("===") })
    }

    @Test
    fun `自家标题（=== pid N ===）不会被误拆`() {
        val out = "=== pid 2724 ===\n" +
            "cmdline: /vendor/bin/hw/hostapd -B /data/vendor/wifi/hostapd/hostapd.conf\n"

        val compacted = CarConfigFileDigest.compact(out)

        assertTrue(compacted.contains("=== pid 2724 ==="))
        assertTrue(compacted.contains("cmdline: /vendor/bin/hw/hostapd"))
    }
}
