package com.dbtool.carplay.bridge.hotspot

import com.dbtool.carplay.api.hotspot.CarHotspotApInterface.Iface
import com.dbtool.carplay.api.hotspot.CarHotspotEndpointCandidates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「本机可填的 CarPlay 地址候选」的行为测试（PATCH-0102，2026-10-12）。
 *
 * ## 为什么这几条必须钉死
 *
 * 真车 `日志/3`（设备 `e3de67df72a0`）里，「复用车机热点」**10 次尝试全部在
 * `buildSession` 阶段被拦**，一次都没走到 iPhone 关联。而拦下它的那条判据是
 * "你填的地址必须在某块网卡上" —— 用户填的 `192.168.15.1` 是**外置 T-Box 网关**，
 * 永远不在本机网卡上。更糟的是：那个值是 **App 自己提示用户去填的**
 * （16:58:41 说"本车是 192.168.15.1"，16:59:48 又说"它不在任何网卡上"）。
 *
 * 这个类就是用来把"本机现在到底有哪些地址"如实列给用户点选的，所以它的判据
 * **判错一次就等于又把用户引到一个填了必然失败的地址上**。这里穷举的是边界：
 * 什么必须留下（本车唯一可用的 `eth0 198.18.34.15`）、什么必须筛掉
 * （回环 / down / 链路本地 / 自建残留）。
 */
class CarHotspotEndpointCandidatesTest {

    /** 我方自建热点上一轮配上去的地址（`CarAdbHotspotState.hostAddress`）。 */
    private val ourSelfBuilt = "192.168.176.1"

    /** 我方候选池里的另一个地址（`CarAdbHotspotDefaults.SUBNET_CANDIDATES` 之一）。 */
    private val ourPool = "192.168.175.1"

    /**
     * 本车实测的全量网卡快照（`日志/3` → `system/wifi_direct.txt`，2026-10-12）。
     *
     * 要点：**只有 `eth0` 系持有 `198.18.34.15`**（VLAN 子接口与基础网卡挂同一个地址），
     * `wlan0` 是 `NO-CARRIER … DOWN` 且无地址，`lo` 是 `127.0.0.1`。
     */
    private val lynkSnapshot = listOf(
        Iface(name = "eth0.11", addresses = listOf("198.18.34.15"), isUplink = true),
        Iface(name = "dummy0", addresses = emptyList(), isUp = false),
        Iface(name = "lo", addresses = listOf("127.0.0.1")),
        Iface(name = "sit0", addresses = emptyList(), isUp = false),
        Iface(name = "eth0.3", addresses = listOf("198.18.34.15")),
        Iface(name = "wlan0", addresses = emptyList(), isUp = false),
        Iface(name = "eth0.4", addresses = listOf("198.18.34.15")),
        Iface(name = "p2p0", addresses = emptyList(), isUp = false),
        Iface(name = "eth0.5", addresses = listOf("198.18.34.15")),
        Iface(name = "eth0.6", addresses = listOf("198.18.34.15")),
        Iface(name = "bond0", addresses = emptyList(), isUp = false),
        Iface(name = "eth0.2", addresses = listOf("198.18.34.15")),
        Iface(name = "eth0", addresses = listOf("198.18.34.15")),
    )

    @Test
    fun `本车快照只给出一条 198_18_34_15，且认的是基础网卡 eth0`() {
        val result = CarHotspotEndpointCandidates.of(lynkSnapshot)

        assertEquals("七张网卡挂同一个地址，去重后必须只剩一条", 1, result.size)
        assertEquals("198.18.34.15", result.single().address)
        // 基础网卡优先于 `eth0.11` / `eth0.2` 这类子接口 —— 否则去重留下谁全看枚举顺序，
        // 同一台机两次读可能给出不同的网卡名。
        assertEquals("eth0", result.single().interfaceName)
    }

    @Test
    fun `回环地址永远不进候选`() {
        val result = CarHotspotEndpointCandidates.of(
            listOf(Iface(name = "lo", addresses = listOf("127.0.0.1"))),
        )

        assertTrue("127.0.0.1 对 iPhone 毫无意义，不能列给用户", result.isEmpty())
    }

    @Test
    fun `没 up 的网卡不进候选`() {
        val result = CarHotspotEndpointCandidates.of(
            listOf(Iface(name = "wlan0", addresses = listOf("192.168.43.1"), isUp = false)),
        )

        assertTrue("down 的网卡上的地址此刻没有承载者", result.isEmpty())
    }

    @Test
    fun `链路本地 169_254 不进候选`() {
        val result = CarHotspotEndpointCandidates.of(
            listOf(Iface(name = "eth0", addresses = listOf("169.254.13.7"))),
        )

        assertTrue("169.254/16 是自动私有地址，iPhone 到不了", result.isEmpty())
    }

    @Test
    fun `我方自建残留地址不进候选`() {
        val result = CarHotspotEndpointCandidates.of(
            listOf(
                // 自建热点留下的（真车历史上就是它被误当成车机热点通告出去）
                Iface(name = "wlan0", addresses = listOf(ourPool)),
                // 车机网卡上真正可用的那个
                Iface(name = "eth0", addresses = listOf("198.18.34.15")),
            ),
        )

        assertEquals(1, result.size)
        assertEquals("198.18.34.15", result.single().address)
    }

    @Test
    fun `显式给出的自建地址不进候选`() {
        val result = CarHotspotEndpointCandidates.of(
            candidates = listOf(
                Iface(name = "wlan0", addresses = listOf("192.168.43.1", "198.18.34.15")),
            ),
            // 网段判据认不出 `192.168.43.1`（不在我方池里），但足迹记得它 —— 两条都要生效。
            selfAddresses = listOf("192.168.43.1"),
        )

        assertEquals(1, result.size)
        assertEquals("198.18.34.15", result.single().address)
    }

    @Test
    fun `落在车机固定网段里的地址不算自建残留，必须保留`() {
        // 别的车型上车机热点**就在本机网卡上**（本车不是，本车是外置 T-Box）。
        // 这条判据不能把车机自己的地址当成"我们的残留"筛掉。
        val result = CarHotspotEndpointCandidates.of(
            listOf(Iface(name = "wlan0", addresses = listOf("192.168.15.1"))),
        )

        assertEquals(1, result.size)
        assertEquals("192.168.15.1", result.single().address)
    }

    @Test
    fun `上行网卡上的地址也要保留`() {
        // 本车默认路由那张卡就是 `eth0.11`，而它挂的 198.18.34.15 恰恰是唯一可用
        // 的 endpoint。把"上行"当排除项会把正确答案筛掉 —— 这条就是防这个回归的。
        val result = CarHotspotEndpointCandidates.of(
            listOf(Iface(name = "eth0.11", addresses = listOf("198.18.34.15"), isUplink = true)),
        )

        assertEquals(1, result.size)
        assertEquals("198.18.34.15", result.single().address)
        assertEquals("eth0.11", result.single().interfaceName)
    }

    @Test
    fun `读不到任何网卡时给空表，而不是编一个地址`() {
        assertTrue(CarHotspotEndpointCandidates.of(emptyList()).isEmpty())
        assertTrue(
            "全是 down / 无地址时同样给空表",
            CarHotspotEndpointCandidates.of(
                listOf(
                    Iface(name = "wlan0", addresses = emptyList(), isUp = false),
                    Iface(name = "lo", addresses = listOf("127.0.0.1")),
                ),
            ).isEmpty(),
        )
    }

    @Test
    fun `label 把网卡名一起带出来`() {
        val candidate = CarHotspotEndpointCandidates.of(lynkSnapshot).single()

        // 同一个地址可能挂在多张卡上，报出名字才知道是从哪张读到的。
        assertEquals("198.18.34.15（eth0）", candidate.label)
    }
}
