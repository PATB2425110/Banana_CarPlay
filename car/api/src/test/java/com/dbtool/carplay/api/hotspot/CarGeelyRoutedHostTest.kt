package com.dbtool.carplay.api.hotspot

import com.dbtool.carplay.api.settings.CarHotspotSecurity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

/**
 * [CarGeelyRoutedHost] 纯逻辑回归（2026-10-25）。
 *
 * 判据来源：参考 APK `GeelyCarPlay-Settings-Refined-debug.apk` 的
 * `GeelyRoutedHotspotManager` / `GeelyRoutedHostReadiness` 反编译结果。
 *
 * 这些用例锁定的都是「判错一次就静默失败 + 超时」的边界：
 * 网卡名、地址两字节、eth0 优先、机型门禁、凭据长度、两条链路的类型隔离。
 */
class CarGeelyRoutedHostTest {

    private fun addr(s: String): InetAddress = InetAddress.getByName(s)

    // ------------------------------------------------------------ 地址判据

    @Test
    fun `198_18 地址被接受`() {
        assertTrue(CarGeelyRoutedHost.isRoutedAddress(addr("198.18.34.15")))
        assertTrue(CarGeelyRoutedHost.isRoutedAddress(addr("198.18.0.1")))
        assertTrue(CarGeelyRoutedHost.isRoutedAddress(addr("198.18.255.254")))
    }

    @Test
    fun `198_19 与相邻网段被拒绝`() {
        // 参考包逐字节判 198.18，不是 /15。放宽会把 198.19.x.x 吃进来。
        assertFalse(CarGeelyRoutedHost.isRoutedAddress(addr("198.19.0.1")))
        assertFalse(CarGeelyRoutedHost.isRoutedAddress(addr("198.17.0.1")))
        assertFalse(CarGeelyRoutedHost.isRoutedAddress(addr("192.168.15.2")))
        assertFalse(CarGeelyRoutedHost.isRoutedAddress(addr("10.0.0.1")))
    }

    @Test
    fun `IPv6 与 null 被拒绝`() {
        assertFalse(CarGeelyRoutedHost.isRoutedAddress(addr("::1")))
        assertFalse(CarGeelyRoutedHost.isRoutedAddress(null))
    }

    @Test
    fun `只有 eth 开头算候选`() {
        assertTrue(CarGeelyRoutedHost.isRoutedInterface("eth0"))
        assertTrue(CarGeelyRoutedHost.isRoutedInterface("eth0.11"))
        assertFalse("大小写敏感", CarGeelyRoutedHost.isRoutedInterface("ETH0"))
        assertFalse(CarGeelyRoutedHost.isRoutedInterface("wlan0"))
        assertFalse(CarGeelyRoutedHost.isRoutedInterface("p2p0"))
        assertFalse(CarGeelyRoutedHost.isRoutedInterface("dummy0"))
        assertFalse(CarGeelyRoutedHost.isRoutedInterface(null))
    }

    // ------------------------------------------------------------ 选择与排序

    @Test
    fun `eth0 优先于其它 eth 接口`() {
        val picked = CarGeelyRoutedHost.selectHost(
            listOf(
                "eth1" to addr("198.18.1.2"),
                "eth0" to addr("198.18.34.15"),
            ),
        )
        assertNotNull(picked)
        assertEquals("eth0", picked!!.first)
        assertEquals("198.18.34.15", picked.second.hostAddress)
    }

    @Test
    fun `没有 eth0 时按接口名字典序`() {
        val picked = CarGeelyRoutedHost.selectHost(
            listOf(
                "eth2" to addr("198.18.2.2"),
                "eth1" to addr("198.18.1.2"),
            ),
        )
        assertEquals("eth1", picked!!.first)
    }

    @Test
    fun `同接口多地址保留枚举顺序`() {
        // 2026-10-25 车主口径「完全按照反编译执行」：参考包用 `sortedWith`
        // （Kotlin 稳定排序），同一接口多个命中地址时**保留 NetworkInterface 枚举顺序**，
        // 不做地址级 tie-break。我先前加的字典序已删。
        val a = CarGeelyRoutedHost.selectHost(
            listOf("eth0" to addr("198.18.34.20"), "eth0" to addr("198.18.34.15")),
        )
        assertEquals("先出现的 198.18.34.20 应被选中", "198.18.34.20", a!!.second.hostAddress)

        val b = CarGeelyRoutedHost.selectHost(
            listOf("eth0" to addr("198.18.34.15"), "eth0" to addr("198.18.34.20")),
        )
        assertEquals("反过来应先选 198.18.34.15", "198.18.34.15", b!!.second.hostAddress)
    }

    @Test
    fun `非 eth 接口即使地址匹配也被忽略`() {
        val picked = CarGeelyRoutedHost.selectHost(listOf("wlan0" to addr("198.18.34.15")))
        assertNull("wlan0 上的 198.18 不算车机热点", picked)
    }

    @Test
    fun `空候选返回 null`() {
        assertNull(CarGeelyRoutedHost.selectHost(emptyList()))
    }

    @Test
    fun `候选计数只统计命中项`() {
        val list = listOf(
            "eth0" to addr("198.18.34.15"),
            "eth0.11" to addr("198.18.34.15"),
            "wlan0" to addr("198.18.34.15"),
            "eth1" to addr("192.168.1.2"),
        )
        assertEquals(2, CarGeelyRoutedHost.candidateCount(list))
    }

    // ------------------------------------------------------------ 设备门禁

    @Test
    fun `CS11 与 KX11 在 SDK28 上通过`() {
        assertTrue(CarGeelyRoutedHost.usesRoutedHotspot("CS11", "cs11", 28))
        assertTrue(CarGeelyRoutedHost.usesRoutedHotspot("KX11", "kx11", 28))
        assertTrue(CarGeelyRoutedHost.usesRoutedHotspot("CS11", "cs11a5", 28))
        // 参考包原文：CS11 两支**完全不看 device**
        assertTrue(CarGeelyRoutedHost.usesRoutedHotspot("CS11", "", 28))
        assertTrue(CarGeelyRoutedHost.usesRoutedHotspot("CS11", null, 28))
        // trim 后比较
        assertTrue(CarGeelyRoutedHost.usesRoutedHotspot(" CS11 ", "whatever", 28))
        // 第三支机型：QUALCOMM CS11
        assertTrue(CarGeelyRoutedHost.usesRoutedHotspot("QUALCOMM CS11", "whatever", 28))
        assertTrue(CarGeelyRoutedHost.usesRoutedHotspot("CX11", "cx11_high", 28))
        assertTrue(CarGeelyRoutedHost.usesRoutedHotspot("QUALCOMM CX11", "cx11_high", 28))
    }

    @Test
    fun `不存在 device 兜底 - model 不认识就拒绝`() {
        // 2026-10-25 二轮核正：参考包**没有**"device 以 cs11 开头即为真"。
        // 上一版把这条不存在的兜底写进了代码与测试，这里是回归锁。
        assertFalse(CarGeelyRoutedHost.usesRoutedHotspot("UNKNOWN", "cs11xyz", 28))
        assertFalse(CarGeelyRoutedHost.usesRoutedHotspot(null, "cs11_high", 28))
        assertFalse(CarGeelyRoutedHost.usesRoutedHotspot("Pixel", "cs11", 28))
    }

    @Test
    fun `非 SDK28 一律拒绝`() {
        assertFalse(CarGeelyRoutedHost.usesRoutedHotspot("CS11", "cs11", 27))
        assertFalse(CarGeelyRoutedHost.usesRoutedHotspot("CS11", "cs11", 29))
        assertFalse(CarGeelyRoutedHost.usesRoutedHotspot("CS11", "cs11", 33))
    }

    @Test
    fun `机型不匹配拒绝`() {
        assertFalse(CarGeelyRoutedHost.usesRoutedHotspot("Pixel", "sailfish", 28))
        assertFalse(CarGeelyRoutedHost.usesRoutedHotspot(null, null, 28))
        // KX11 那支**要看** device：model 对但 device 不是 kx11* 就不算
        assertFalse(CarGeelyRoutedHost.usesRoutedHotspot("KX11", "cs11", 28))
        // CS11 那支**不看** device，所以 "CS11"+任意 device 都为 true
        assertTrue(CarGeelyRoutedHost.usesRoutedHotspot("CS11", "kx11", 28))
    }

    // ------------------------------------------------------------ 凭据

    @Test
    fun `合法凭据通过`() {
        assertNull(CarGeelyRoutedHost.validateCredentials("DBPlay", "12345678", CarHotspotSecurity.WPA2))
        assertNull(CarGeelyRoutedHost.validateCredentials("Car Hotspot", "", CarHotspotSecurity.OPEN))
        // 63 位边界
        assertNull(
            CarGeelyRoutedHost.validateCredentials(
                "DBPlay", "1".repeat(63), CarHotspotSecurity.WPA2,
            ),
        )
    }

    @Test
    fun `SSID 长度按字节算 - 11 个汉字超 32 字节被拒`() {
        // IEEE 802.11 SSID element 上限 32 octets（UTF-8 字节），不是 32 字符。
        // 11 个汉字 = 33 字节 ⇒ 必须拒；10 个汉字 = 30 字节 ⇒ 放行。
        // 车机热点名称长度测试用 = 11 个汉字 = 33 字节
        val eleven = "车机热点名称长度测试用"
        assertEquals(11, eleven.length)
        assertEquals(33, eleven.encodeToByteArray().size)
        assertNotNull(
            "33 字节必须拒，否则会一路发到 0x5703 让 iPhone 关联失败",
            CarGeelyRoutedHost.validateCredentials(eleven, "12345678", CarHotspotSecurity.WPA2),
        )
        // 车机热点名称长度测试 = 10 个汉字 = 30 字节
        val ten = "车机热点名称长度测试"
        assertEquals(10, ten.length)
        assertEquals(30, ten.encodeToByteArray().size)
        assertNull(
            "30 字节应放行",
            CarGeelyRoutedHost.validateCredentials(ten, "12345678", CarHotspotSecurity.WPA2),
        )
    }

    @Test
    fun `空 SSID 与超长 SSID 被拒`() {
        assertNotNull(CarGeelyRoutedHost.validateCredentials("", "12345678", CarHotspotSecurity.WPA2))
        assertNotNull(CarGeelyRoutedHost.validateCredentials("   ", "12345678", CarHotspotSecurity.WPA2))
        assertNotNull(
            CarGeelyRoutedHost.validateCredentials("A".repeat(33), "12345678", CarHotspotSecurity.WPA2),
        )
    }

    @Test
    fun `换行与参考包一致不放行也不额外拦截`() {
        // 2026-10-25 二轮核正：参考包 ManualHotspotValidation **不禁换行**
        // （它的 UI 是单行 EditText，正常输入产生不了），所以这里不再拦。
        // 保留用例是为了锁住"我们不会因为换行而误拒存量配置"。
        assertNull(
            "参考包不拦换行，我们也不拦（避免误拒导入的存量值）",
            CarGeelyRoutedHost.validateCredentials("DB\nPlay", "12345678", CarHotspotSecurity.WPA2),
        )
        assertNull(
            CarGeelyRoutedHost.validateCredentials("DBPlay", "1234\n5678", CarHotspotSecurity.WPA2),
        )
    }

    @Test
    fun `加密网络密码长度边界`() {
        // 7 位 / 64 位都不合法
        assertNotNull(CarGeelyRoutedHost.validateCredentials("DBPlay", "1234567", CarHotspotSecurity.WPA2))
        assertNotNull(
            CarGeelyRoutedHost.validateCredentials("DBPlay", "1".repeat(64), CarHotspotSecurity.WPA2),
        )
        // 空密码在加密网络下也不合法
        assertNotNull(CarGeelyRoutedHost.validateCredentials("DBPlay", "", CarHotspotSecurity.WPA2))
    }

    @Test
    fun `开放网络必须空密码`() {
        assertNotNull(CarGeelyRoutedHost.validateCredentials("DBPlay", "12345678", CarHotspotSecurity.OPEN))
        assertNull(CarGeelyRoutedHost.validateCredentials("DBPlay", "", CarHotspotSecurity.OPEN))
    }

    @Test
    fun `normalizeSsid 去首尾空白`() {
        assertEquals("DBPlay", CarGeelyRoutedHost.normalizeSsid("  DBPlay  "))
    }

    // ------------------------------------------------------------ 类型隔离

    @Test
    fun `新档 info 类型与 TCAM 类型不互通`() {
        // 两条链路一旦共用一个类型，下游 is CarTcamHotspotInfo 会把新档当 TCAM。
        val routed = CarGeelyRoutedInfo(
            ssid = "DBPlay",
            passphrase = "",
            security = CarHotspotSecurity.WPA2,
            interfaceName = "eth0",
            hostAddress = addr("198.18.34.15") as Inet4Address,
        )
        assertFalse("必须是独立类型", routed is CarTcamHotspotInfo)
        assertFalse("TCAM info 也不应被当成新档", (null as Any?) is CarGeelyRoutedInfo)
        assertEquals("geely-routed", routed.source)
        assertEquals("geely-routed", CarGeelyRoutedInfo.SOURCE_TAG)
    }

    // ------------------------------------------------------------ 常量契约

    @Test
    fun `预算与轮询常量符合口径`() {
        assertEquals(
            "扫描预算 60 秒，与参考包 controller 对 MANUAL 传的 60000 一致" +
                "（2026-10-25 车主口径：完全按照反编译执行）",
            60_000L, CarGeelyRoutedHost.SCAN_TIMEOUT_MILLIS,
        )
        assertEquals("轮询沿用 200ms", CarTcamHotspot.POLL_INTERVAL_MILLIS, CarGeelyRoutedHost.POLL_INTERVAL_MILLIS)
        assertEquals("eth", CarGeelyRoutedHost.INTERFACE_PREFIX)
        assertEquals("eth0", CarGeelyRoutedHost.PREFERRED_INTERFACE)
        assertEquals(198, CarGeelyRoutedHost.ADDRESS_FIRST_OCTET)
        assertEquals(18, CarGeelyRoutedHost.ADDRESS_SECOND_OCTET)
    }
}
