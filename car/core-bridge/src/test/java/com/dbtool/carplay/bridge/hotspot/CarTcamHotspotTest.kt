package com.dbtool.carplay.bridge.hotspot

import com.dbtool.carplay.api.hotspot.CarTcamHotspot
import com.dbtool.carplay.api.hotspot.TcamAttachmentState
import com.dbtool.carplay.api.settings.CarHotspotSecurity
import java.net.Inet4Address
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TCAM 热点复用（`backend = TCAM_HOTSPOT`）的纯逻辑测试。
 *
 * ## 为什么这几条必须钉死
 *
 * DiPlay 逆向源码 `TcamHotspotManager.*` 的判据里，最容易"手滑"改坏的是四件事：
 *
 * 1. **接口名写死 `eth0.11`** —— 一旦有人加候选列表 / 多接口回退，就与 DiPlay 口径分叉
 *    （证据包 §D：写死哪张拿到的地址都一样，但口径必须一致）；
 * 2. **恰好一个 IPv4** —— 0 个或多个都不算候选，多个时"挑第一个"会把通告地址变成碰运气；
 * 3. **状态机流转** —— `invalidated` 幂等、`close` 后不再触发、`sameLink` 仍看接口名与地址；
 * 4. **信道 → 频率换算** —— iAP2 0x5703 的信道/频率一旦算错，iPhone 反复重试关联。
 *
 * `NetworkInterface` / `ConnectivityManager` 在 JVM 上读不了，所以这些判据全部抽成
 * `:car:api` 里的纯函数/纯数据（`CarTcamHotspot` / `TcamAttachmentState`），在这里穷举。
 */
class CarTcamHotspotTest {

    private fun inet4(text: String): Inet4Address =
        InetAddress.getByName(text) as Inet4Address

    private fun inet(text: String): InetAddress = InetAddress.getByName(text)

    // ------------------------------------------------------------ ① 接口名

    @Test
    fun `接口名必须恰好是写死的 eth0_11`() {
        assertTrue(CarTcamHotspot.isTcamInterface("eth0.11"))
        assertFalse(CarTcamHotspot.isTcamInterface("eth0.3"))
        assertFalse(CarTcamHotspot.isTcamInterface("eth0"))
        assertFalse(CarTcamHotspot.isTcamInterface("wlan0"))
        assertFalse(CarTcamHotspot.isTcamInterface(null))
    }

    @Test
    fun `sameLink 必须同时要求接口名与选中地址都在`() {
        val address = inet4("198.18.34.15")
        assertTrue(
            CarTcamHotspot.sameLink(
                interfaceName = "eth0.11",
                addresses = listOf(address),
                selectedAddress = address,
            ),
        )
        assertFalse(
            CarTcamHotspot.sameLink(
                interfaceName = "eth0.3",
                addresses = listOf(address),
                selectedAddress = address,
            ),
        )
        assertFalse(
            CarTcamHotspot.sameLink(
                interfaceName = "eth0.11",
                addresses = emptyList(),
                selectedAddress = address,
            ),
        )
        assertFalse(
            CarTcamHotspot.sameLink(
                interfaceName = "eth0.11",
                addresses = listOf(inet4("198.18.34.16")),
                selectedAddress = address,
            ),
        )
        assertFalse(
            CarTcamHotspot.sameLink(
                interfaceName = "eth0.11",
                addresses = listOf(address),
                selectedAddress = null,
            ),
        )
    }

    // ------------------------------------------------------------ ② IPv4 唯一性

    @Test
    fun `恰好一个 IPv4 才算候选`() {
        val address = inet4("198.18.34.15")
        assertEquals(address, CarTcamHotspot.singleInet4Address(listOf(address)))
        // 混入 IPv6 不影响 IPv4 的唯一性
        assertEquals(
            address,
            CarTcamHotspot.singleInet4Address(listOf(address, inet("fe80::2"))),
        )
    }

    @Test
    fun `零个或多个 IPv4 都不算候选`() {
        assertNull(CarTcamHotspot.singleInet4Address(emptyList()))
        assertNull(CarTcamHotspot.singleInet4Address(listOf(inet("fe80::2"))))
        assertNull(
            CarTcamHotspot.singleInet4Address(
                listOf(inet4("198.18.34.15"), inet4("198.18.34.16")),
            ),
        )
    }

    // ------------------------------------------------------------ ③ 状态机

    @Test
    fun `状态机 select 后仍是可用的启动态`() {
        val address = inet4("198.18.34.15")
        val selected = TcamAttachmentState().select(address, "Lynk")
        assertEquals(address, selected.selectedAddress)
        assertEquals("Lynk", selected.confirmedSsid)
        assertTrue(
            selected.isStartupValid(
                interfaceName = "eth0.11",
                addresses = listOf(address),
            ),
        )
        assertFalse(selected.isStartupValid(interfaceName = "eth0.3", addresses = listOf(address)))
    }

    @Test
    fun `invalidate 置位后启动复查失败且幂等`() {
        val address = inet4("198.18.34.15")
        val selected = TcamAttachmentState().select(address, "Lynk")
        val invalidated = selected.invalidate()
        assertTrue(invalidated.invalidated)
        assertFalse(
            invalidated.isStartupValid(
                interfaceName = "eth0.11",
                addresses = listOf(address),
            ),
        )
        // 幂等：再置一次仍是不变的 invalidated 状态（manager 用 CAS 保证只触发一次回调）
        assertEquals(invalidated, invalidated.invalidate())
    }

    @Test
    fun `close 后回调注销且不再算有效`() {
        val address = inet4("198.18.34.15")
        val registered = TcamAttachmentState()
            .select(address, "Lynk")
            .registerCallback()
        assertTrue(registered.callbackRegistered)
        val closed = registered.close()
        assertTrue(closed.closed)
        assertFalse(closed.callbackRegistered)
        assertFalse(
            closed.isStartupValid(
                interfaceName = "eth0.11",
                addresses = listOf(address),
            ),
        )
    }

    @Test
    fun `sameLink 要求选中地址仍在链路里`() {
        val address = inet4("198.18.34.15")
        val selected = TcamAttachmentState().select(address, "Lynk")
        assertTrue(selected.sameLink("eth0.11", listOf(address)))
        assertFalse(selected.sameLink("eth0.11", listOf(inet4("198.18.34.16"))))
        assertFalse(selected.sameLink("eth0.3", listOf(address)))
        assertFalse(selected.sameLink("eth0.11", emptyList()))
    }

    // ------------------------------------------------------------ ④ 频率换算

    @Test
    fun `legacyHotspotBandToSoftApBand 的映射与 DiPlay 一致`() {
        assertEquals(1, CarTcamHotspot.legacyHotspotBandToSoftApBand(0))
        assertEquals(2, CarTcamHotspot.legacyHotspotBandToSoftApBand(1))
        assertEquals(3, CarTcamHotspot.legacyHotspotBandToSoftApBand(-1))
        assertNull(CarTcamHotspot.legacyHotspotBandToSoftApBand(null))
        assertNull(CarTcamHotspot.legacyHotspotBandToSoftApBand(4))
    }

    @Test
    fun `信道到频率的换算与 DiPlay 一致`() {
        // 2.4 GHz（softApBand=1）
        assertEquals(2412, CarTcamHotspot.wifiChannelToFrequencyMhz(1, 1))
        assertEquals(2472, CarTcamHotspot.wifiChannelToFrequencyMhz(13, 1))
        assertEquals(2484, CarTcamHotspot.wifiChannelToFrequencyMhz(14, 1))
        // 5 GHz（softApBand=2）
        assertEquals(5180, CarTcamHotspot.wifiChannelToFrequencyMhz(36, 2))
        assertEquals(5825, CarTcamHotspot.wifiChannelToFrequencyMhz(165, 2))
        // 6 GHz（softApBand=4，DiPlay 独有常量）
        assertEquals(5955, CarTcamHotspot.wifiChannelToFrequencyMhz(1, 4))
        assertEquals(7115, CarTcamHotspot.wifiChannelToFrequencyMhz(233, 4))
        // 越界 / 未知 band / channel=0 全部 null
        assertNull(CarTcamHotspot.wifiChannelToFrequencyMhz(15, 1))
        assertNull(CarTcamHotspot.wifiChannelToFrequencyMhz(36, 3))
        assertNull(CarTcamHotspot.wifiChannelToFrequencyMhz(36, null))
        assertNull(CarTcamHotspot.wifiChannelToFrequencyMhz(0, 1))
    }

    // ------------------------------------------------------------ 安全映射

    @Test
    fun `安全类型按 OPEN 位与密码映射`() {
        assertEquals(
            CarHotspotSecurity.OPEN,
            CarTcamHotspot.security(openKeyManagement = true, passphrase = "12345678"),
        )
        assertEquals(
            CarHotspotSecurity.OPEN,
            CarTcamHotspot.security(openKeyManagement = false, passphrase = ""),
        )
        assertEquals(
            CarHotspotSecurity.WPA2,
            CarTcamHotspot.security(openKeyManagement = false, passphrase = "12345678"),
        )
    }

    @Test
    fun `bandLabel 与 DiPlay 一致`() {
        assertEquals("2.4 GHz", CarTcamHotspot.bandLabel(0))
        assertEquals("5 GHz", CarTcamHotspot.bandLabel(1))
        assertEquals("Auto", CarTcamHotspot.bandLabel(-1))
        assertEquals("Auto", CarTcamHotspot.bandLabel(null))
    }
}
