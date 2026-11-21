package com.dbtool.carplay.bridge

import com.dbtool.carplay.api.hotspot.CarAdbHotspotReadiness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「热点起没起来」判据的单测。
 *
 * ## 为什么这条判据必须钉死
 *
 * 它是整条自建热点链的**唯一放行开关**。判宽了，会出现
 * "hostapd 已退出、地址还在、udhcpd 还活着 ⇒ 判成就绪 ⇒ 会话继续
 * ⇒ iPhone 根本搜不到 SSID，而日志里只有一句'热点已就绪'"——
 * 这是最难查的一类现象（现象与日志完全脱节）。
 *
 * 2026-09-30 实车又补上了第四项：前四项里前三项全真、而网卡上广播的是**别人的**
 * SSID（实车 `car`，我们配的是 `Lynk`），判据照样放行 ⇒ 我们把 `Lynk` 发给 iPhone，
 * 而它**没有被广播** ⇒ iPhone 永远关联不上 ⇒ 界面停在「无线通道就绪」且全链路无报错。
 *
 * 判严了，又会因为一个"其实不影响 AP 的步骤"失败而整条链放弃，
 * 回落这台车上必然超时的 `LocalOnlyHotspot`。所以两个方向都要有断言：
 * 下面"四项全真才算就绪"钉住前者，"任一为假都不算就绪"钉住后者。
 */
class CarAdbHotspotReadinessTest {

    @Test
    fun `四项全真才算就绪`() {
        assertTrue(
            CarAdbHotspotReadiness.isReady(
                hostapdAlive = true,
                dhcpAlive = true,
                addressConfigured = true,
                noForeignApOnInterface = true,
            ),
        )
    }

    /**
     * `hostapd` 就是那张 AP 本身。它退出了 ⇒ iPhone 搜不到 SSID，
     * 但网卡地址与 udhcpd 可能都还在 ⇒ 只看后两项会误判成就绪。
     */
    @Test
    fun `hostapd 已退出不算就绪`() {
        assertFalse(
            CarAdbHotspotReadiness.isReady(
                hostapdAlive = false,
                dhcpAlive = true,
                addressConfigured = true,
                noForeignApOnInterface = true,
            ),
        )
    }

    /** 有 AP 但没 DHCP ⇒ iPhone 关联后停在"获取地址中"。 */
    @Test
    fun `DHCP 已退出不算就绪`() {
        assertFalse(
            CarAdbHotspotReadiness.isReady(
                hostapdAlive = true,
                dhcpAlive = false,
                addressConfigured = true,
                noForeignApOnInterface = true,
            ),
        )
    }

    /** 有 AP 有 DHCP 但没有网关地址 ⇒ 拿到 IP 也 ping 不通。 */
    @Test
    fun `网卡地址没配上不算就绪`() {
        assertFalse(
            CarAdbHotspotReadiness.isReady(
                hostapdAlive = true,
                dhcpAlive = true,
                addressConfigured = false,
                noForeignApOnInterface = true,
            ),
        )
    }

    /**
     * 2026-09-30 实车新增的那一项：前三项全真，但网卡上广播的是**别人的** SSID。
     *
     * 这一条**必须**为假才放行——否则我们就把设置页那个 SSID 发给 iPhone 了，
     * 而它没有被广播，iPhone 必然关联不上（现象：卡在「无线通道就绪」、
     * 封面能投但音视频上不来、全链路无报错）。
     */
    @Test
    fun `网卡上广播的是别人的 SSID 不算就绪`() {
        assertFalse(
            CarAdbHotspotReadiness.isReady(
                hostapdAlive = true,
                dhcpAlive = true,
                addressConfigured = true,
                noForeignApOnInterface = false,
            ),
        )
    }

    @Test
    fun `四项全假不算就绪`() {
        assertFalse(
            CarAdbHotspotReadiness.isReady(
                hostapdAlive = false,
                dhcpAlive = false,
                addressConfigured = false,
                noForeignApOnInterface = false,
            ),
        )
    }

    /** 原因文案要把四项逐项点明，不能只说"没起来"。 */
    @Test
    fun `原因文案逐项点明四项状态`() {
        val text = CarAdbHotspotReadiness.explain(
            hostapdAlive = true,
            dhcpAlive = false,
            addressConfigured = false,
            noForeignApOnInterface = false,
        )
        assertTrue(text.contains("hostapd=在跑"))
        assertTrue(text.contains("DHCP=已退出"))
        assertTrue(text.contains("地址=没配上"))
        assertTrue(text.contains("网卡=被别人的 AP 占着"))
    }

    /** 文案与判据必须同源：就绪时四项都应写成"好"的那一侧。 */
    @Test
    fun `就绪时文案四项都是正面表述`() {
        val text = CarAdbHotspotReadiness.explain(
            hostapdAlive = true,
            dhcpAlive = true,
            addressConfigured = true,
            noForeignApOnInterface = true,
        )
        assertTrue(text.contains("hostapd=在跑"))
        assertTrue(text.contains("DHCP=在跑"))
        assertTrue(text.contains("地址=已配上"))
        assertTrue(text.contains("网卡=广播的是我们的 SSID"))
        assertEquals(false, text.contains("已退出") || text.contains("没配上"))
        assertEquals(false, text.contains("被别人的 AP 占着"))
    }
}
