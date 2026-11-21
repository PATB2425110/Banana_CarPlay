package com.dbtool.carplay.bridge

import com.dbtool.carplay.api.hotspot.CarAdbHotspotDefaults
import com.dbtool.carplay.api.hotspot.CarHotspotSubnet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CarHotspotSubnet] 纯计算部分的单测。
 *
 * ## 为什么这段必须钉死
 *
 * 这四件事——**网关地址、DHCP 池、网段归属、冲突判定**——全部由它推导，
 * 而推导错的后果是**静默的**：
 *
 * - 网关算错 ⇒ `ip addr add` 配上一个网段外的地址，`hostapd` 照样起得来、
 *   `udhcpd` 照样发租约，但 iPhone 拿到地址后**回不到网关**，
 *   表现为"热点连上了、AirPlay 永远 7000 超时"；
 * - 冲突判定算错 ⇒ 选中的网段与车机已有网段重合，`ip rule` / 路由打架，
 *   同样只在真机上、只在某些车机上才暴露。
 *
 * 这两类故障都没法在开发机上复现，所以把边界在这里穷举掉。
 *
 * ## 为什么用"真机实测值"当夹具
 *
 * 车机上实际会遇到的地址形态比教科书脏：`ip addr` 带 `/24` 后缀、
 * 尾部有空白、`10.x` 蜂窝段、`192.168.43.x`（Android 自带热点池）、
 * 厂商自定义段。夹具按这些真实形态写，而不是只测 `192.168.175.0/24`。
 */
class CarHotspotSubnetTest {

    // ------------------------------------------------------------ 网关与 DHCP 池

    @Test
    fun `网关取网段的第一个可用地址`() {
        assertEquals("192.168.175.1", CarHotspotSubnet.hostAddress("192.168.175.0/24"))
        assertEquals("10.42.0.1", CarHotspotSubnet.hostAddress("10.42.0.0/24"))
    }

    @Test
    fun `DHCP 池是 2 到 254`() {
        assertEquals("192.168.176.2", CarHotspotSubnet.dhcpStart("192.168.176.0/24"))
        assertEquals("192.168.176.254", CarHotspotSubnet.dhcpEnd("192.168.176.0/24"))
    }

    /**
     * 池的两端与常量必须一致。
     *
     * 这个断言看着啰嗦，但它守的是一个**很容易在重构里改坏**的耦合：
     * `udhcpd.conf` 的 `max_leases` 是按这两个常量算的
     * （`END - START + 1`），三者一旦不同步，udhcpd 会拒绝启动或提前耗尽租约。
     */
    @Test
    fun `DHCP 池端点与常量同步`() {
        val subnet = "192.168.177.0/24"
        assertEquals(
            "192.168.177.${CarAdbHotspotDefaults.DHCP_HOST_START}",
            CarHotspotSubnet.dhcpStart(subnet),
        )
        assertEquals(
            "192.168.177.${CarAdbHotspotDefaults.DHCP_HOST_END}",
            CarHotspotSubnet.dhcpEnd(subnet),
        )
        // max_leases 的口径：.2~.254 共 253 个。
        assertEquals(
            253,
            CarAdbHotspotDefaults.DHCP_HOST_END - CarAdbHotspotDefaults.DHCP_HOST_START + 1,
        )
    }

    /**
     * 主机名允许写成"非 `.0` 结尾"的网段写法。
     *
     * 因为探测层拿到的可能是 `192.168.43.1/24` 这种**接口地址**形态，
     * 而不是规范网段。此时前三段才是有效信息，第四段直接丢掉。
     */
    @Test
    fun `非零结尾的写法也能取到前三段`() {
        assertEquals("192.168.43.1", CarHotspotSubnet.hostAddress("192.168.43.7/24"))
        assertEquals("192.168.43.2", CarHotspotSubnet.dhcpStart("192.168.43.7/24"))
    }

    // ------------------------------------------------------------ 前缀长度

    @Test
    fun `前缀长度解析`() {
        assertEquals(24, CarHotspotSubnet.prefixLength("192.168.175.0/24"))
        assertEquals(16, CarHotspotSubnet.prefixLength("172.20.0.0/16"))
        assertEquals(0, CarHotspotSubnet.prefixLength("0.0.0.0/0"))
        assertEquals(32, CarHotspotSubnet.prefixLength("10.0.0.1/32"))
    }

    @Test
    fun `非法前缀返回 null`() {
        assertNull(CarHotspotSubnet.prefixLength("192.168.175.0"))
        assertNull(CarHotspotSubnet.prefixLength("192.168.175.0/"))
        assertNull(CarHotspotSubnet.prefixLength("192.168.175.0/abc"))
        assertNull(CarHotspotSubnet.prefixLength("192.168.175.0/33"))
        assertNull(CarHotspotSubnet.prefixLength("192.168.175.0/-1"))
    }

    // ------------------------------------------------------------ 网段归属

    @Test
    fun `地址归到所属的 24 位网段`() {
        assertEquals("192.168.43.0/24", CarHotspotSubnet.networkOf("192.168.43.7", 24))
        assertEquals("192.168.43.0/24", CarHotspotSubnet.networkOf("192.168.43.1/24", 24))
        assertEquals("10.42.0.0/24", CarHotspotSubnet.networkOf("10.42.0.254", 24))
    }

    /**
     * 非 /24 前缀**刻意不支持**，返回 null。
     *
     * 因为整套参数推导（DHCP 池、网关、`ip rule`）都是按 /24 设计的。
     * 如果这里"尽力而为"地按 /24 处理一个 /16 地址，会把 `172.20.5.7`
     * 错判成 `172.20.5.0/24`，而真实网段是 `172.20.0.0/16`——
     * 于是冲突检测会漏判。宁可返回 null（调用方退化为"不做冲突判断"），
     * 也不要给一个看起来对、实际错的值。
     */
    @Test
    fun `非 24 位前缀返回 null`() {
        assertNull(CarHotspotSubnet.networkOf("172.20.5.7", 16))
        assertNull(CarHotspotSubnet.networkOf("10.0.0.1", 8))
        assertNull(CarHotspotSubnet.networkOf("192.168.1.1", 32))
    }

    // ------------------------------------------------------------ 冲突判定

    @Test
    fun `同网段判定`() {
        assertTrue(CarHotspotSubnet.sameSlash24("192.168.175.1", "192.168.175.99"))
        assertTrue(CarHotspotSubnet.sameSlash24("192.168.175.1/24", "192.168.175.0/24"))
        assertFalse(CarHotspotSubnet.sameSlash24("192.168.175.1", "192.168.176.1"))
        // 前两段相同、第三段不同 ⇒ 不同 /24，必须判为不冲突。
        assertFalse(CarHotspotSubnet.sameSlash24("192.168.1.1", "192.168.2.1"))
    }

    /**
     * 非法输入一律判"不冲突"，而不是抛异常。
     *
     * 调用方是"选网段"的循环：如果这里抛异常，一条脏数据就会让整次
     * 热点拉起失败；而返回 false 的代价只是"可能漏判一次冲突"，
     * 后面还有 `ip rule` 的运行时校验兜底。**可用性优先于严格性。**
     */
    @Test
    fun `非法输入判为不冲突`() {
        assertFalse(CarHotspotSubnet.sameSlash24("", "192.168.175.1"))
        assertFalse(CarHotspotSubnet.sameSlash24("192.168.175.1", "abc"))
        assertFalse(CarHotspotSubnet.sameSlash24("192.168.175", "192.168.175.1"))
        assertFalse(CarHotspotSubnet.sameSlash24("999.168.175.1", "999.168.175.2"))
    }

    // ------------------------------------------------------------ 脏输入

    @Test
    fun `带空白与斜杠后缀的地址可正常解析`() {
        // `ip addr` 的输出常带首尾空白（shell 里逐行 trim 过一次，但可能漏）。
        assertEquals("192.168.43", CarHotspotSubnet.firstThreeOctets("  192.168.43.7  "))
        assertEquals("192.168.43", CarHotspotSubnet.firstThreeOctets("192.168.43.7/24"))
    }

    @Test
    fun `八位组越界返回 null`() {
        assertNull(CarHotspotSubnet.firstThreeOctets("192.168.256.7"))
        assertNull(CarHotspotSubnet.firstThreeOctets("192.168.-1.7"))
        assertNull(CarHotspotSubnet.firstThreeOctets("192.168.43"))
        assertNull(CarHotspotSubnet.firstThreeOctets("192.168.43.7.8"))
        assertNull(CarHotspotSubnet.firstThreeOctets("a.b.c.d"))
    }

    @Test
    fun `非法网段推导网关返回 null`() {
        assertNull(CarHotspotSubnet.hostAddress("not-a-subnet"))
        assertNull(CarHotspotSubnet.hostAddress(""))
        assertNull(CarHotspotSubnet.hostAddress("192.168.43"))
    }

    // ------------------------------------------------------------ 候选池自身

    /**
     * 候选池的**不变量**：必须覆盖到 175，且必须不含 Android 自带热点的默认池。
     *
     * 后半条是硬要求（见 `CarAdbHotspotDefaults.SUBNET_CANDIDATES` 的注释）：
     * 撞上 `192.168.43.0/24` 会留下 `ip rule` 残留。这条断言把它钉住，
     * 防止以后有人"顺手"把 43 加回候选里。
     */
    @Test
    fun `候选网段不含 Android 默认热点池`() {
        val candidates = CarAdbHotspotDefaults.SUBNET_CANDIDATES
        assertTrue(candidates.contains("192.168.175.0/24"))
        assertFalse(candidates.any { CarHotspotSubnet.sameSlash24(it, "192.168.43.1") })
        assertFalse(candidates.any { CarHotspotSubnet.sameSlash24(it, "192.168.14.1") })
    }

    /** 候选网段本身必须都是合法 /24，否则 `hostAddress` 会返回 null 而静默失败。 */
    @Test
    fun `候选网段都能推导出完整参数`() {
        for (subnet in CarAdbHotspotDefaults.SUBNET_CANDIDATES) {
            assertEquals("候选 $subnet 前缀不是 24", 24, CarHotspotSubnet.prefixLength(subnet))
            assertTrue("候选 $subnet 推导不出网关", CarHotspotSubnet.hostAddress(subnet) != null)
            assertTrue("候选 $subnet 推导不出 DHCP 起址", CarHotspotSubnet.dhcpStart(subnet) != null)
            assertTrue("候选 $subnet 推导不出 DHCP 止址", CarHotspotSubnet.dhcpEnd(subnet) != null)
        }
    }
}
