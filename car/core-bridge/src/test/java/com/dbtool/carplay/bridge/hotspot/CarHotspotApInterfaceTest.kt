package com.dbtool.carplay.bridge.hotspot

import com.dbtool.carplay.api.hotspot.CarHotspotApInterface
import com.dbtool.carplay.api.hotspot.CarHotspotApInterface.Iface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「识别车机热点网口」的行为测试（PATCH-0068，2026-10-11）。
 *
 * ## 为什么这几条必须钉死
 *
 * 真车日志（设备 `67b3c24af6b5`，08:57）：
 *
 * ```
 * manual hotspot precheck: state=ON effective=ON ssid=Lynk     ← 车机热点确实在跑
 * wireless hotspot backend=Manual hotspot iface=wlan0 host=192.168.176.1
 *   ↑ 那是**我们自己**上一轮自建配上去的地址（不是车机热点）
 * wireless advertise SSID="Lynk" … ip=192.168.176.1
 * wireless attach timed out: iPhone did not open the AirPlay connection（40000ms）
 * ```
 *
 * 判错一次就是 40 秒超时 + "屏对但没内容"这种表象。所以这里穷举的是**边界**：
 * 什么时候必须认出车机那张网口，什么时候必须说"识别不到"（而不是随便挑一个）。
 *
 * ## PATCH-0086（2026-10-11 15:11，1.0.44 诊断包）
 *
 * 那一版 `system/wifi_direct.txt` 里写着
 * `车机热点网口: wlan0 192.168.176.1 192.168.176.0/24（车机固定网段 192.168.15.0/24）` ——
 * **把我方自建残留冒充成了车机热点**（当时 `selfAddresses` 为空，只靠老判据就失效了）。
 * 于是补上"落在我方候选池里、且不落在车机网段里的地址一律不算车机热点"，
 * 并把"直判（车机网段）优先于候选池排除"这条钉死。
 */
class CarHotspotApInterfaceTest {

    /** 我方自建热点上一轮配上去的地址（`CarAdbHotspotState.hostAddress`）。 */
    private val ourSelfBuilt = listOf("192.168.176.1")

    /** 我方候选池里的另一个地址（`CarAdbHotspotDefaults.SUBNET_CANDIDATES` 之一）。 */
    private val ourPool = "192.168.175.1"

    @Test
    fun `车机 AP 在另一张网卡时，必须认出它而不是我们自己的 wlan0`() {
        val found = CarHotspotApInterface.identify(
            candidates = listOf(
                // 我们自己的自建热点残留在 wlan0 上（真车就是这样）
                Iface(name = "wlan0", addresses = ourSelfBuilt),
                // 车机自带热点在 softap0 上（车主口径：固定网口 + 固定网段）
                Iface(name = "softap0", addresses = listOf("192.168.43.1")),
                Iface(name = "eth0.11", addresses = listOf("198.18.32.17"), isUplink = true),
            ),
            selfAddresses = ourSelfBuilt,
        )
        assertEquals("softap0", found?.interfaceName)
        assertEquals("192.168.43.1", found?.address)
        assertEquals("192.168.43.0/24", found?.subnet)
    }

    /**
     * 车主口径（2026-10-11）：车机热点固定 `192.168.15.0/24`（网关 `.1`）、固定网口 `wlan0`。
     * ⇒ 落在该网段里的地址**直判**为车机热点，哪怕同一张卡上还挂着我方自建残留。
     */
    @Test
    fun `落在车机固定网段里的地址直判为车机热点`() {
        val found = CarHotspotApInterface.identify(
            candidates = listOf(
                // 我方 08:55 自建留下的地址（候选池之一）
                Iface(name = "wlan0", addresses = ourSelfBuilt + listOf("192.168.15.1")),
            ),
            selfAddresses = ourSelfBuilt,
        )
        assertEquals("wlan0", found?.interfaceName)
        assertEquals("192.168.15.1", found?.address)
        assertEquals("192.168.15.0/24", found?.subnet)
    }

    /** 同一个网段里若既有车机地址又有我方地址，优先车机网段那个。 */
    @Test
    fun `车机网段优先于网卡名与网关形状`() {
        val found = CarHotspotApInterface.identify(
            candidates = listOf(
                Iface(name = "wlan0", addresses = listOf("192.168.15.1")),
                // 名字更"像 AP"、也是 .1，但不在车机网段里
                Iface(name = "softap0", addresses = listOf("192.168.43.1")),
            ),
        )
        assertEquals("192.168.15.1", found?.address)
    }

    /**
     * ① 只有我方候选网段里的地址时，必须**识别不到**。
     *
     * 这条正是 1.0.44 那次冒认（`车机热点网口: wlan0 192.168.176.1`）的回归测试，
     * 而且刻意**不传** `selfAddresses` —— 进程重启后拿不到足迹，判据也不能塌。
     */
    @Test
    fun `只有我方候选网段里的地址时必须识别不到`() {
        val candidates = listOf(Iface(name = "wlan0", addresses = ourSelfBuilt + listOf(ourPool)))
        assertNull(
            "我方候选池（175~179 / 172.20 / 10.42）里的地址一律不是车机热点",
            CarHotspotApInterface.identify(candidates = candidates),
        )

        // 同一份输入，诊断行必须**说清为什么**（否则看日志的人又会以为这就是车机热点）。
        val line = CarHotspotApInterface.debugLine(candidates = candidates)
        assertTrue("要说识别不到：$line", line.contains("识别不到"))
        assertTrue("要列出读到的我方地址：$line", line.contains("192.168.176.1"))
        assertTrue("要标明那是我方候选网段：$line", line.contains("我方自建/候选网段"))
    }

    /** ③ 车机网段与我方候选池同时在时，取车机网段那个。 */
    @Test
    fun `车机网段与我方候选池同时在时选车机网段`() {
        val candidates = listOf(
            Iface(name = "wlan0", addresses = listOf(ourPool, "192.168.15.1", ourSelfBuilt.first())),
        )
        assertEquals(
            "192.168.15.1",
            CarHotspotApInterface.identify(candidates = candidates)?.address,
        )
        assertEquals(
            "192.168.15.1",
            CarHotspotApInterface.identify(candidates = candidates, selfAddresses = ourSelfBuilt)?.address,
        )
    }

    /**
     * ④ 两条排除**互为双保险**：足迹（`selfAddresses`）与候选池判据各能独立挡住我们自己的地址，
     * 而"直判"永远压过候选池排除。
     */
    @Test
    fun `两条排除互为双保险且直判优先`() {
        // 足迹挡得住"不在池子里"的我方地址（② 独立生效）
        assertEquals(
            "10.55.0.1",
            CarHotspotApInterface.identify(
                candidates = listOf(Iface(name = "wlan0", addresses = ourSelfBuilt + listOf("10.55.0.1"))),
                selfAddresses = ourSelfBuilt,
            )?.address,
        )
        // 池子判据挡得住"足迹丢了"的我方地址（③ 独立生效）
        assertNull(
            CarHotspotApInterface.identify(
                candidates = listOf(Iface(name = "wlan0", addresses = listOf(ourPool))),
                selfAddresses = emptyList(),
            ),
        )
        // 直判优先：哪怕把车机网段与候选池**故意设成同一个**，落在车机网段里的地址照样认出
        assertEquals(
            "192.168.15.1",
            CarHotspotApInterface.identify(
                candidates = listOf(Iface(name = "wlan0", addresses = listOf("192.168.15.1"))),
                selfSubnets = listOf("192.168.15.0/24"),
            )?.address,
        )
    }

    @Test
    fun `车机 AP 与我们共用一张网卡时，排除掉我们自己那个地址`() {
        // 边界：车机的 AP 也在 wlan0（同卡多地址）。此时**唯一**能把两者分开的信息，
        // 就是"我们实际配过的那一个地址"（车机那个地址刻意选在候选池之外，
        // 这样本用例只考 ② 这条判据，不与 ③ 混在一起）。
        val found = CarHotspotApInterface.identify(
            candidates = listOf(
                Iface(name = "wlan0", addresses = ourSelfBuilt + listOf("10.55.0.1")),
            ),
            selfAddresses = ourSelfBuilt,
        )
        assertEquals("wlan0", found?.interfaceName)
        assertEquals("10.55.0.1", found?.address)
    }

    @Test
    fun `链路本地、上行、未 up 的地址都不算车机热点`() {
        assertNull(
            CarHotspotApInterface.identify(
                candidates = listOf(
                    Iface(name = "wlan0", addresses = listOf("169.254.1.1")),
                    Iface(name = "eth0", addresses = listOf("10.0.0.2"), isUplink = true),
                    Iface(name = "p2p0", addresses = listOf("192.168.49.1"), isUp = false),
                ),
            ),
        )
    }

    @Test
    fun `一张候选都没有时返回 null 并写明识别不到`() {
        val line = CarHotspotApInterface.debugLine(emptyList())
        assertNull(CarHotspotApInterface.identify(emptyList()))
        assertTrue("诊断行必须说清'识别不到'：$line", line.contains("识别不到"))
    }

    @Test
    fun `名字像 AP 的网卡优先于普通 wlan`() {
        val found = CarHotspotApInterface.identify(
            candidates = listOf(
                Iface(name = "wlan1", addresses = listOf("192.168.9.1")),
                Iface(name = "ap0", addresses = listOf("192.168.8.1")),
            ),
        )
        assertEquals("ap0", found?.interfaceName)
    }

    @Test
    fun `诊断行同时给出结论、候选与我们的自建地址`() {
        val line = CarHotspotApInterface.debugLine(
            candidates = listOf(
                Iface(name = "wlan0", addresses = ourSelfBuilt),
                Iface(name = "softap0", addresses = listOf("192.168.43.1")),
            ),
            selfAddresses = ourSelfBuilt,
        )
        assertTrue("要有结论：$line", line.contains("softap0 192.168.43.1"))
        assertTrue("要列候选：$line", line.contains("wlan0"))
        assertTrue("要标明我方自建地址：$line", line.contains("192.168.176.1"))
    }
}
