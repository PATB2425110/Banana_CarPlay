package com.dbtool.carplay.bridge.hotspot

import com.dbtool.carplay.api.hotspot.CarHotspotResidue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CarHotspotResidue] 的行为测试。
 *
 * ## 为什么要单独测一个"这么简单"的判据
 *
 * 判错一次就是**40 秒 attach 超时**（真车 `e3de67df72a0`，2026-10-09 09:33）：
 * 自建留下的 `192.168.176.1` 被复用分支认成"车机热点"，通告给 iPhone，
 * 而车机热点其实在 `192.168.175.x`。
 *
 * 所以这里的用例**不是**在测"包含关系"这么 trivial 的事，而是在钉死
 * **判据的边界**：什么时候它必须说"有"，什么时候它**必须闭嘴**。
 * 后者（闭嘴的分支）才是危险的 —— 判据一旦"过于积极"，
 * 上层的模式切换就会去清理一张**不是我们建的**网。
 *
 * ⚠️ 刻意**不**做网段匹配：见 [CarHotspotResidue] 类注释。
 */
class CarHotspotResidueTest {

    private val planned = "192.168.176.1"

    // ------------------------------------------------------------ 命中

    @Test
    fun `网卡上正好躺着规划网关地址时判为残留`() {
        val result = CarHotspotResidue.detect(
            interfaceName = "wlan0",
            addressesOnInterface = listOf("192.168.176.1"),
            plannedGateway = planned,
        )

        assertTrue("规划地址就在网卡上，这正是「自建残留」的定义", result.detected)
        assertEquals(planned, result.leftoverAddress)
        assertEquals("wlan0", result.interfaceName)
        assertTrue("结论必须能直接进日志：要写清是哪个地址、为什么坏事", result.note!!.contains(planned))
        assertTrue("note 里要出现网卡名，排障时第一眼要看到清哪张网", result.note!!.contains("wlan0"))
    }

    @Test
    fun `网卡上有多个地址时命中其中一个即可`() {
        // 真实网卡往往同时挂着 IPv6 与别的 IPv4（车机自己配的）。
        val result = CarHotspotResidue.detect(
            interfaceName = "wlan0",
            addressesOnInterface = listOf("192.168.175.2", planned, "10.0.0.5"),
            plannedGateway = planned,
        )

        assertTrue(result.detected)
        assertEquals(planned, result.leftoverAddress)
    }

    @Test
    fun `地址两侧有空白时仍能命中`() {
        // ifconfig / ip 输出被切分后常带首尾空格；不做 trim 就会漏检，
        // 而漏检的后果就是 40 秒超时那条链。
        val result = CarHotspotResidue.detect(
            interfaceName = " wlan0 ",
            addressesOnInterface = listOf("  $planned  "),
            plannedGateway = " $planned ",
        )

        assertTrue(result.detected)
        assertEquals(planned, result.leftoverAddress)
        assertEquals("wlan0", result.interfaceName)
    }

    // ------------------------------------------------------------ 不该命中（更要紧）

    @Test
    fun `同一个网段的邻居地址不算残留`() {
        // ⚠️ 这条是**契约**：只认精确匹配，不做网段匹配。
        // 车机热点与自建热点**可能**落进同段的网（192.168.176.0/24），
        // 若按网段判，就会把"车机正在用的网"当成我们的残留去清理。
        val result = CarHotspotResidue.detect(
            interfaceName = "wlan0",
            addressesOnInterface = listOf("192.168.176.2", "192.168.176.100"),
            plannedGateway = planned,
        )

        assertFalse(
            "只许精确匹配规划的那一个地址；按网段匹配会把车机自己的网误判成残留",
            result.detected,
        )
        assertNull(result.leftoverAddress)
    }

    @Test
    fun `网卡上没有地址时不算残留`() {
        val result = CarHotspotResidue.detect(
            interfaceName = "wlan0",
            addressesOnInterface = emptyList(),
            plannedGateway = planned,
        )

        assertFalse(result.detected)
    }

    // ------------------------------------------------------------ 读不到就闭嘴

    @Test
    fun `读不到网卡名时不判残留`() {
        val result = CarHotspotResidue.detect(
            interfaceName = null,
            addressesOnInterface = listOf(planned),
            plannedGateway = planned,
        )

        assertFalse("不知道是哪张网卡 ⇒ 不能下结论（宁可多等一次超时，也不误清）", result.detected)
    }

    @Test
    fun `网卡名为空白时不判残留`() {
        val result = CarHotspotResidue.detect(
            interfaceName = "   ",
            addressesOnInterface = listOf(planned),
            plannedGateway = planned,
        )

        assertFalse(result.detected)
    }

    @Test
    fun `没有规划网关时不判残留`() {
        // planner 算不出地址（没权限 / 没网卡）⇒ 失去比较基准 ⇒ 判不了。
        val result = CarHotspotResidue.detect(
            interfaceName = "wlan0",
            addressesOnInterface = listOf("192.168.176.1"),
            plannedGateway = null,
        )

        assertFalse("没有规划值可比 ⇒ 不能凭空说那是残留", result.detected)
    }

    @Test
    fun `没有规划网关但网卡上恰好有个地址时也不判残留`() {
        val result = CarHotspotResidue.detect(
            interfaceName = "wlan0",
            addressesOnInterface = listOf("192.168.176.1"),
            plannedGateway = "",
        )

        assertFalse(result.detected)
    }

    // ------------------------------------------------------------ 诊断行

    @Test
    fun `诊断行始终可读且标明是否命中`() {
        val hit = CarHotspotResidue.debugLine("wlan0", listOf(planned), planned)
        val miss = CarHotspotResidue.debugLine("wlan0", emptyList(), planned)
        val unknown = CarHotspotResidue.debugLine(null, emptyList(), null)

        assertTrue(hit.contains("detected=true"))
        assertTrue(hit.contains(planned))
        assertTrue(miss.contains("detected=false"))
        assertTrue("读不到时也要能打出一行，且如实标注为「无」", unknown.contains("(无)"))
    }

    @Test
    fun `NONE 常量是干净的未检出态`() {
        assertFalse(CarHotspotResidue.Result.NONE.detected)
        assertNull(CarHotspotResidue.Result.NONE.leftoverAddress)
        assertNull(CarHotspotResidue.Result.NONE.interfaceName)
        assertNull(CarHotspotResidue.Result.NONE.note)
    }

    // ---------------------------------------------- 按"我方候选网段"判（2026-10-11 起）

    @Test
    fun `按候选网段判：池子里的地址就是我们的残留`() {
        // 真车 08:57 通告出去的那个地址。
        val result = CarHotspotResidue.detectWithinSubnets(
            interfaceName = "wlan0",
            addressesOnInterface = listOf("192.168.176.1"),
        )
        assertTrue("192.168.176.0/24 是自建候选池之一 ⇒ 必判为残留", result.detected)
        assertEquals("192.168.176.1", result.leftoverAddress)
        assertTrue("结论要能直接进日志", result.note!!.contains("192.168.176.1"))
    }

    /**
     * 🔴 **这条是安全底线**：车机自带热点的地址绝不能被判成我们的残留。
     *
     * 车主口径（2026-10-11）：车机热点固定 `192.168.15.0/24`（网关 `.1`）、固定网口 `wlan0`，
     * 而我们的候选池是 `192.168.175~179` / `172.20` / `10.42` —— **两者不相交**。
     * 若哪天有人把车机网段塞进候选池、或把判据改成"任何 192.168.x 都算我们的"，
     * 复用就会去清车机自己的地址。这条测试就是那个闸。
     */
    @Test
    fun `按候选网段判：车机自己 192_168_15_1 绝不算残留`() {
        val result = CarHotspotResidue.detectWithinSubnets(
            interfaceName = "wlan0",
            addressesOnInterface = listOf("192.168.15.1"),
        )
        assertFalse("车机热点地址被误判 ⇒ 会去清车机的网", result.detected)
        assertNull(result.leftoverAddress)
    }

    @Test
    fun `按候选网段判：读不到网卡或池子为空时不表态`() {
        assertFalse(
            CarHotspotResidue.detectWithinSubnets("wlan0", listOf("192.168.176.1"), emptyList())
                .detected,
        )
        assertFalse(
            CarHotspotResidue.detectWithinSubnets(null, listOf("192.168.176.1")).detected,
        )
    }
}
