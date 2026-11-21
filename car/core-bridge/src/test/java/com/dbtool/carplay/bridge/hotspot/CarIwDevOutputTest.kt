package com.dbtool.carplay.bridge.hotspot

import com.dbtool.carplay.api.hotspot.CarIwDevOutput
import com.dbtool.carplay.api.hotspot.CarNativeHotspotInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「车机热点实际在广播什么名字」这条事实的解析与判据。
 *
 * ## 为什么要专门钉它
 *
 * 2026-09-29 实车（领克 / Flyme Auto，Android 9）：`WifiManager.getWifiApConfiguration()`
 * 读出 **`Lynk`**，而 `iw dev` 说 `wlan0` 实际在广播 **`car`**（`hostapd` 只在启动时
 * 读一次配置，改名不会让它重启）。我们把存储配置里的 `Lynk` 发进了 `0x5703`，
 * iPhone 去连一个不存在的网络 ⇒ iAP2 无线隧道起不来 ⇒ 界面永远停在
 * 「无线通道就绪」，**而整条链路上没有任何一句报错**。
 *
 * 所以这条判据的价值不在于"算得对"，而在于**把这种必然失败变成可见的**。
 * 下面第 1 条用的就是那次日志的原文。
 */
class CarIwDevOutputTest {

    /** 实车日志 `[13] 无线网卡详情（iw dev）` 的原文（缩进保留）。 */
    private val realWorld = """
        phy#0
        	Interface p2p0
        		ifindex 13
        		wdev 0x2
        		addr a6:04:50:3c:b9:9e
        		type P2P-device
        	Interface wlan0
        		ifindex 12
        		wdev 0x1
        		addr a4:04:50:b9:b9:9e
        		ssid car
        		type AP
    """.trimIndent()

    @Test
    fun `实车原文：wlan0 实际广播的是 car`() {
        assertEquals("car", CarIwDevOutput.apSsid(realWorld, "wlan0"))
    }

    @Test
    fun `P2P 网卡不被当成热点`() {
        // p2p0 在 AP 之前出现，且没有 ssid；若按"整段第一个 ssid"去取，
        // 会把 wlan0 的名字安到 p2p0 头上。
        assertNull(CarIwDevOutput.apSsid(realWorld, "p2p0"))
        assertFalse(CarIwDevOutput.isApMode(realWorld, "p2p0"))
    }

    @Test
    fun `STA 网卡的 ssid 不算热点名`() {
        // STA 的 ssid 是**上行网络**的名字。把它当热点名会让 iPhone 去连一个
        // 毫不相干的网络 —— 比读不到更糟，所以这里必须返回 null。
        val sta = """
            phy#0
            	Interface wlan0
            		ifindex 12
            		addr a4:04:50:b9:b9:9e
            		ssid 家里的路由器
            		type managed
        """.trimIndent()
        assertNull(CarIwDevOutput.apSsid(sta, "wlan0"))
    }

    @Test
    fun `AP VLAN 也算 AP 模式`() {
        val vlan = """
            	Interface wlan0
            		ssid car
            		type AP/VLAN
        """.trimIndent()
        assertEquals("car", CarIwDevOutput.apSsid(vlan, "wlan0"))
    }

    @Test
    fun `没有 ssid 行时不在下一张网卡里捡`() {
        // 必须在下一条 `Interface` 处截断，否则 wlan0 会"继承" p2p0 的 ssid。
        val tricky = """
            	Interface wlan0
            		type AP
            	Interface p2p0
            		ssid 别人的名字
            		type P2P-device
        """.trimIndent()
        assertNull(CarIwDevOutput.apSsid(tricky, "wlan0"))
    }

    @Test
    fun `网卡名找不到时返回 null 而不是猜一个`() {
        assertNull(CarIwDevOutput.apSsid(realWorld, "wlan1"))
        assertNull(CarIwDevOutput.apSsid("", "wlan0"))
    }

    @Test
    fun `隐藏 SSID 不算读到了名字`() {
        val hidden = """
            	Interface wlan0
            		ssid <hidden>
            		type AP
        """.trimIndent()
        assertNull(CarIwDevOutput.apSsid(hidden, "wlan0"))
    }

    @Test
    fun `带空格的 SSID 整行取走`() {
        val spaced = """
            	Interface wlan0
            		ssid My Car Hotspot
            		type AP
        """.trimIndent()
        assertEquals("My Car Hotspot", CarIwDevOutput.apSsid(spaced, "wlan0"))
    }

    // ------------------------------------------------------------ 不一致判据

    @Test
    fun `存储配置与实播不一致时判为不一致，且提示里两个值都在`() {
        val info = CarNativeHotspotInfo(
            detected = true,
            enabled = true,
            ssid = "Lynk",
            runningSsid = "car",
        )
        assertTrue(info.ssidMismatch)
        val note = requireNotNull(info.ssidMismatchNote)
        // 只说"不一致"没用：现场要据此决定改设置还是重启车机热点，
        // 所以两个值都必须出现。
        assertTrue(note, note.contains("car"))
        assertTrue(note, note.contains("Lynk"))
    }

    @Test
    fun `两边一致时不算不一致`() {
        val info = CarNativeHotspotInfo(detected = true, enabled = true, ssid = "Lynk", runningSsid = "Lynk")
        assertFalse(info.ssidMismatch)
        assertNull(info.ssidMismatchNote)
    }

    @Test
    fun `只读到一个值时不下结论`() {
        // 读不全就说"不一致"会让每一次读不到 iw 的探测都变成告警，
        // 很快就会被无视 —— 所以缺一个值一律不算不一致。
        assertFalse(CarNativeHotspotInfo(detected = true, ssid = "Lynk").ssidMismatch)
        assertFalse(CarNativeHotspotInfo(detected = true, runningSsid = "car").ssidMismatch)
        assertFalse(CarNativeHotspotInfo(detected = true).ssidMismatch)
        assertNull(CarNativeHotspotInfo(detected = true, ssid = "Lynk").ssidMismatchNote)
    }

    @Test
    fun `空串按缺失处理`() {
        val info = CarNativeHotspotInfo(detected = true, ssid = "", runningSsid = "car")
        assertFalse(info.ssidMismatch)
    }
}
