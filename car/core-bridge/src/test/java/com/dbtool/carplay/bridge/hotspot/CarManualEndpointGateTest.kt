package com.dbtool.carplay.bridge.hotspot

import com.dbtool.carplay.api.hotspot.CarHotspotApInterface
import com.dbtool.carplay.api.hotspot.CarManualEndpointGate
import com.dbtool.carplay.api.hotspot.CarManualEndpointVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「复用车机热点」attach 前闸门的判据（2026-10-12，日志 4 排查）。
 *
 * ## 被测的两条结论
 *
 * 1. **地址只在有线口上 ⇒ 拦**。这是日志 4 里那种"预检说通过、上游必然失败"的情形：
 *    `198.18.34.15` 挂在 `eth0.11` 上，二开层预检放行，上游 `ManualHotspotManager`
 *    却把 `eth*` 全排除 ⇒ 每 2.27 秒一行 warning、60 秒后 `local hotspot interface was not found`。
 * 2. **Ecarx 已验证 route 在手 ⇒ 永不拦**。车机 SDK 亲眼看到 iPhone 挂在车机热点上，
 *    这是这里能拿到的最强证据，任何网卡形状都不该否定它。
 *
 * ## 刻意保留的"不拦"
 *
 * 地址同时出现在**非有线口**（`wlan*` / `ap*` / `p2p*`）上时放行 —— 那种情况真有可能成功，
 * 宁可放过去，不误杀。
 */
class CarManualEndpointGateTest {

    private val target = "198.18.34.15"

    private fun iface(
        name: String,
        vararg addresses: String,
        isUp: Boolean = true,
        isUplink: Boolean = false,
    ) = CarHotspotApInterface.Iface(
        name = name,
        addresses = addresses.toList(),
        isUp = isUp,
        isUplink = isUplink,
    )

    /** 日志 4 现场的网卡快照：承载目标地址的全是 eth*，wlan0/p2p0 都 DOWN。 */
    private val fieldSnapshots = listOf(
        iface("eth0.11", target, isUplink = true),
        iface("eth0", target),
        iface("eth0.2", target),
        iface("dummy0"),
        iface("lo", "127.0.0.1"),
        iface("p2p0", isUp = false),
        iface("wlan0", isUp = false),
    )

    // ------------------------------------------------------------ 放行

    @Test
    fun `空地址不拦_交给上游按名字猜`() {
        assertEquals(
            CarManualEndpointVerdict.OK,
            CarManualEndpointGate.evaluate("   ", fieldSnapshots),
        )
    }

    @Test
    fun `有 Ecarx 已验证 route 就永不拦`() {
        assertEquals(
            CarManualEndpointVerdict.OK,
            CarManualEndpointGate.evaluate(
                address = target,
                interfaces = fieldSnapshots,
                verifiedRouteInterface = "eth0.11",
            ),
        )
    }

    @Test
    fun `已验证 route 是空白串时不当成已验证`() {
        // 上游 `hostInterfaceOverride` 也是"trim 后非空才算"；这里保持一致，
        // 否则一个全空白串就能绕过整道闸门。
        assertTrue(
            CarManualEndpointGate.evaluate(
                address = target,
                interfaces = fieldSnapshots,
                verifiedRouteInterface = "   ",
            ) is CarManualEndpointVerdict.WIRED_ONLY,
        )
    }

    @Test
    fun `地址同时在非有线口上就不拦`() {
        val interfaces = fieldSnapshots + iface("wlan0", target, isUp = true)
        assertEquals(
            CarManualEndpointVerdict.OK,
            CarManualEndpointGate.evaluate(target, interfaces),
        )
    }

    @Test
    fun `无线热点的自有地址不受影响`() {
        val interfaces = listOf(iface("ap0", "192.168.43.1"), iface("wlan0", "192.168.43.1"))
        assertEquals(
            CarManualEndpointVerdict.OK,
            CarManualEndpointGate.evaluate("192.168.43.1", interfaces),
        )
    }

    // ------------------------------------------------------------ 拦截

    @Test
    fun `日志4现场_地址只在有线口上_拦`() {
        val verdict = CarManualEndpointGate.evaluate(target, fieldSnapshots)
        assertTrue(verdict is CarManualEndpointVerdict.WIRED_ONLY)
        assertEquals(
            listOf("eth0", "eth0.11", "eth0.2"),
            (verdict as CarManualEndpointVerdict.WIRED_ONLY).interfaces,
        )
    }

    @Test
    fun `地址不在任何网卡上_判 NOWHERE`() {
        assertEquals(
            CarManualEndpointVerdict.NOWHERE,
            CarManualEndpointGate.evaluate("192.168.15.99", fieldSnapshots),
        )
    }

    // ------------------------------------------------------------ 自建池地址（SELF_POOL）

    @Test
    fun `自建残留地址挂在无线口上也拦_真车08点57现场`() {
        // 真车 2026-10-11 08:57：自建的 192.168.176.1 残留在 wlan0 上，
        // 复用模式把它通告给 iPhone ⇒ 40 秒 attach 超时。
        // 修复前：wlan0 非有线 ⇒ 放行；现在必须在闸门口就拦下。
        val interfaces = fieldSnapshots + iface("wlan0", "192.168.176.1", isUp = true)
        assertEquals(
            CarManualEndpointVerdict.SELF_POOL,
            CarManualEndpointGate.evaluate("192.168.176.1", interfaces),
        )
    }

    @Test
    fun `候选池每个网段都拦`() {
        for (address in listOf(
            "192.168.175.1", "192.168.176.1", "192.168.177.1",
            "192.168.178.1", "192.168.179.1", "172.20.0.1", "10.42.0.1",
        )) {
            val interfaces = listOf(iface("wlan0", address))
            assertEquals(
                "$address 应判 SELF_POOL",
                CarManualEndpointVerdict.SELF_POOL,
                CarManualEndpointGate.evaluate(address, interfaces),
            )
        }
    }

    @Test
    fun `自建池判定优先于有线判定`() {
        // 池地址挂在有线口上时，SELF_POOL 的文案更直指下一步（清残留），
        // 比"只在有线口"更有用。
        val interfaces = listOf(iface("eth0", "192.168.176.1"))
        assertEquals(
            CarManualEndpointVerdict.SELF_POOL,
            CarManualEndpointGate.evaluate("192.168.176.1", interfaces),
        )
    }

    @Test
    fun `自建池地址有已验证 route 时仍不拦`() {
        // Ecarx 已验证 route 是最强证据，永不拦（与 WIRED_ONLY 同一条纪律）。
        val interfaces = listOf(iface("wlan0", "192.168.176.1"))
        assertEquals(
            CarManualEndpointVerdict.OK,
            CarManualEndpointGate.evaluate(
                address = "192.168.176.1",
                interfaces = interfaces,
                verifiedRouteInterface = "wlan0",
            ),
        )
    }

    @Test
    fun `车机固定网段的地址不算自建池`() {
        // 直判优先权：192.168.15.x 是车机热点网段，哪怕它恰好在池边上也
        // 不能被 SELF_POOL 误杀（与识别侧 isSelfPoolOnly 同源）。
        val interfaces = listOf(iface("wlan0", "192.168.15.1"))
        assertEquals(
            CarManualEndpointVerdict.OK,
            CarManualEndpointGate.evaluate("192.168.15.1", interfaces),
        )
    }

    @Test
    fun `池外地址不受影响`() {
        // 192.168.43.x 是 Android 系统热点默认池，刻意不在我们的候选池里
        // （见 CarAdbHotspotContract.SUBNET_CANDIDATES 的注释）。
        val interfaces = listOf(iface("ap0", "192.168.43.1"))
        assertEquals(
            CarManualEndpointVerdict.OK,
            CarManualEndpointGate.evaluate("192.168.43.1", interfaces),
        )
    }

    @Test
    fun `网卡 DOWN 视作不存在`() {
        // 快照里 `wlan0` 带着目标地址但 DOWN —— 与上游"isUp 才算"对齐。
        val interfaces = fieldSnapshots + iface("wlan0", target, isUp = false)
        assertTrue(CarManualEndpointGate.evaluate(target, interfaces) is CarManualEndpointVerdict.WIRED_ONLY)
    }

    @Test
    fun `usb 前缀也算有线`() {
        val interfaces = listOf(iface("usb0", target))
        assertTrue(CarManualEndpointGate.evaluate(target, interfaces) is CarManualEndpointVerdict.WIRED_ONLY)
    }

    @Test
    fun `网卡名大小写不敏感`() {
        val interfaces = listOf(iface("ETH0", target))
        assertTrue(CarManualEndpointGate.evaluate(target, interfaces) is CarManualEndpointVerdict.WIRED_ONLY)
    }

    @Test
    fun `地址前后空格被 trim`() {
        assertTrue(
            CarManualEndpointGate.evaluate("  $target ", fieldSnapshots)
                is CarManualEndpointVerdict.WIRED_ONLY,
        )
    }

    // ------------------------------------------------------ 与上游判据一致

    @Test
    fun `有线前缀与上游 ManualHotspotManager 的猜测排除表同源`() {
        // 两处必须一致，否则会出现"我们放行、上游否掉"或反之的自相矛盾。
        // `:shared` 的那个表在 `ManualHotspotExclusionSplitTest` 里按源码钉住。
        assertEquals(listOf("eth", "usb"), CarManualEndpointGate.WIRED_INTERFACE_PREFIXES)
    }

    @Test
    fun `WIRED_ONLY 带上涉事网卡名且已排序`() {
        val verdict = CarManualEndpointGate.evaluate(
            target,
            listOf(iface("eth0.6", target), iface("eth0.2", target), iface("eth0", target)),
        ) as CarManualEndpointVerdict.WIRED_ONLY
        assertEquals(listOf("eth0", "eth0.2", "eth0.6"), verdict.interfaces)
        assertEquals(verdict.interfaces.sorted(), verdict.interfaces)
    }

    @Test
    fun `OK 与其余判定互不相等_拦截条件只写一个不等于 OK 就够`() {
        // 防止后来者把"没判"和"明确放行"混成一个语义。
        // 调用方只写 `verdict != CarManualEndpointVerdict.OK`，所以三个判定必须两两不相等，
        // 否则某天加个新成员却复用了 equals，拦截就会漏。
        val verdicts = listOf(
            CarManualEndpointVerdict.OK,
            CarManualEndpointVerdict.NOWHERE,
            CarManualEndpointVerdict.SELF_POOL,
            CarManualEndpointVerdict.WIRED_ONLY(listOf("eth0")),
        )
        verdicts.forEach { a ->
            verdicts.filter { it !== a }.forEach { b ->
                assertFalse("$a 与 $b 相等了，!= OK 的拦截会漏判", a == b)
            }
        }
    }

    @Test
    fun `isWired 对非网卡名安全`() {
        assertFalse(CarManualEndpointGate.isWired(""))
        assertFalse(CarManualEndpointGate.isWired("wlan0"))
        assertFalse(CarManualEndpointGate.isWired("rmnet_data0"))
        assertTrue(CarManualEndpointGate.isWired("eth0"))
        assertTrue(CarManualEndpointGate.isWired("Eth0.11"))
        assertTrue(CarManualEndpointGate.isWired("usb0"))
    }
}
