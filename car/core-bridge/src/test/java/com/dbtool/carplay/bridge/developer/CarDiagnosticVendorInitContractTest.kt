package com.dbtool.carplay.bridge.developer

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 契约测试：诊断包必须把**厂商 init 脚本**带回来（PATCH-0075，2026-10-11）。
 *
 * ## 为什么钉它
 *
 * 车主实测（车机上 `ls -l /vendor/etc/init/`）里有这三份，而它们决定"车机自带热点
 * 由谁拉起、谁负责拆、init 会不会把它重新拉起来" —— 也就是我们 P2P 那条路
 * "关热点"能不能确定性地做：
 *
 * - `hostapd.android.rc`（框架 SoftAP 那份）
 * - `hostapdwlan.rc`（厂商在 wlan 上自己那张 AP）
 * - `udhcpdwlan.rc`（厂商 AP 的 DHCP 服务）
 *
 * 这三份**打一次包就永久有**，但很容易在后续"精简诊断包"时被顺手删掉；删掉之后现场
 * 又只能靠人肉 `cat`（车主那次只贴到了 `ls -l`，内容仍然缺）。所以这里把它钉住。
 *
 * ## 为什么静态读源码
 *
 * `CarDiagnosticPackager` 的 companion 是 **private**（探针清单不对外），
 * 所以拿不到实例来断言；静态读法与 `CarHotspotModeInitContractTest` 等一致。
 */
class CarDiagnosticVendorInitContractTest {

    private val packagerSrc: String = run {
        val candidates = listOf(
            File("src/main/java/com/dbtool/carplay/bridge/developer/CarDiagnosticPackager.kt"),
            File("../src/main/java/com/dbtool/carplay/bridge/developer/CarDiagnosticPackager.kt"),
            File("../../src/main/java/com/dbtool/carplay/bridge/developer/CarDiagnosticPackager.kt"),
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("找不到 CarDiagnosticPackager.kt（从 ${File(".").absolutePath} 起找过 ${candidates.size} 个位置）")
        file.readText()
    }

    /**
     * 只看 `VENDOR_INIT_PROBES` 那一段。
     *
     * ⚠️ **必须切出边界**：文件里紧跟其后的 `ROOT_P2P_PROBES` 会 `grep` 厂商的
     * `wpa_supplicant.conf`（那是它**该**做的），把整段都算进来会让"不 cat 配置文件"
     * 那条测试永远红 —— 那是测试取错了范围，不是命令写错了。
     */
    private val probesSrc: String = run {
        val start = packagerSrc.indexOf("val VENDOR_INIT_PROBES = listOf(")
        if (start < 0) {
            ""
        } else {
            val rest = packagerSrc.substring(start)
            // 下一个同缩进的 `val ` 声明（即别的探针清单）就是边界。
            val end = rest.indexOf("\n        val ", startIndex = 1)
            if (end < 0) rest else rest.substring(0, end)
        }
    }

    @Test
    fun `车主点名的三份脚本必须按名字逐个 cat`() {
        assertTrue("探针清单不见了", probesSrc.isNotEmpty())
        // ⚠️ 刻意**按名字**而不是通配符：车主那次 `wlan*.rc` 就没展开成文件名
        //（目录里根本没有叫 wlan*.rc 的文件，wlan 相关的是 hostapdwlan.rc / udhcpdwlan.rc）。
        for (name in listOf("hostapd.android.rc", "hostapdwlan.rc", "udhcpdwlan.rc")) {
            assertTrue("$name 必须被显式 cat（别只 ls）", probesSrc.contains("/vendor/etc/init/$name"))
        }
    }

    @Test
    fun `厂商侧另外几份相关脚本也要带上`() {
        // 亿咖通的连通性服务 —— 很可能就是拉起/收掉 Wi-Fi 与 AP 的那一层。
        assertTrue(
            "vendor.ecarx.xma.connectivity 必须带",
            probesSrc.contains("vendor.ecarx.xma.connectivity@1.0-service.rc"),
        )
        // 用的是哪套 Wi-Fi HAL —— 决定"P2P 到底走框架还是走厂商"。
        assertTrue(
            "android.hardware.wifi@1.0-service.rc 必须带",
            probesSrc.contains("android.hardware.wifi@1.0-service.rc"),
        )
        // WFD HAL 与 P2P/组网同源。
        assertTrue(
            "com.qualcomm.qti.wifidisplayhal 必须带",
            probesSrc.contains("com.qualcomm.qti.wifidisplayhal@1.0-service.rc"),
        )
        // SKU 差异：不同车型启用哪些服务不一样。
        assertTrue("manifest_sku.rc 必须带", probesSrc.contains("manifest_sku.rc"))
    }

    @Test
    fun `必须同时给出目录清单与进程状态`() {
        // 光有脚本内容判不出"现在跑没跑"（`init.svc.*` 只在状态变化时才写）。
        assertTrue("要有目录全量清单", probesSrc.contains("ls -l /vendor/etc/init/"))
        assertTrue("要有 hostapd/udhcpd 进程", probesSrc.contains("hostapd|udhcpd|dnsmasq"))
        assertTrue("要有 init.svc 状态", probesSrc.contains("init.svc"))
        assertTrue(
            "要有框架侧 AP/tethering 状态（框架那份与厂商那份要一起看）",
            probesSrc.contains("dumpsys tethering"),
        )
    }

    @Test
    fun `不 cat 热点配置文件 —— 里面有 SSID 与口令，而包要上传`() {
        // 配置层只允许"列名字"（`ls -l`），不允许把内容打进包里。
        //
        // ⚠️ 只看**命令所在的行**：清单里那些注释本来就会讨论这些文件名（"不 cat 它"），
        //    把它们也算进去会让这条测试永远红 —— 那是测试写错了，不是命令写错了。
        val commandLines = probesSrc.lines()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")
        for (forbidden in listOf("softap.conf", "wpa_supplicant.conf", "hostapd.conf")) {
            assertFalse(
                "探针里不许出现 $forbidden（包要上传服务器）",
                commandLines.contains(forbidden),
            )
        }
    }
}
