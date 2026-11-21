package com.dbtool.carplay.bridge.hotspot

import com.dbtool.carplay.api.hotspot.CarHotspotApInterface
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EcarxHotspotAttachmentPolicyTest {
    private val address = ipv4("198.18.34.15")
    private val carIface = CarHotspotApInterface.Iface(
        name = "eth0.11",
        addresses = listOf("198.18.34.15"),
        isUp = true,
        isUplink = true,
    )

    private fun snapshot(
        status: EcarxHotspotReader.RouteStatus = EcarxHotspotReader.RouteStatus.VERIFIED,
        enabled: Boolean? = null,
        sources: Map<String, java.net.InetAddress> = mapOf("eth0.11" to address),
    ) = EcarxHotspotReader.Snapshot(
        enabled = enabled,
        clientCount = if (status == EcarxHotspotReader.RouteStatus.NO_CURRENT_CLIENT) 0 else 1,
        addressCount = if (status == EcarxHotspotReader.RouteStatus.NO_CURRENT_CLIENT) 0 else 1,
        routeCount = sources.size,
        routeStatus = status,
        routeSources = sources,
    )

    @Test
    fun `verified Ecarx route selects local endpoint not TBox gateway`() {
        val decision = resolve(snapshot(), listOf(carIface))

        assertEquals(EcarxHotspotAttachmentReason.VERIFIED_CLIENT_ROUTE, decision.reason)
        assertEquals(EcarxHotspotEndpoint("eth0.11", "198.18.34.15"), decision.endpoint)
        assertFalse("the external AP gateway must not become the local endpoint", decision.endpoint?.address == "192.168.15.1")
    }

    @Test
    fun `enabled null is acceptable only with one verified route`() {
        val decision = resolve(snapshot(enabled = null), listOf(carIface))
        assertNotNull(decision.endpoint)
        assertTrue(CarHotspotEnabledReader.hasVerifiedEcarxClientRoute(snapshot(), 28, "CS11", "cs11_high"))
        assertFalse(
            CarHotspotEnabledReader.hasVerifiedEcarxClientRoute(
                snapshot(status = EcarxHotspotReader.RouteStatus.NO_CURRENT_CLIENT, sources = emptyMap()),
                28,
                "CS11",
                "cs11_high",
            ),
        )
    }

    @Test
    fun `explicit SDK disabled conflicts with route and fails closed`() {
        val decision = resolve(snapshot(enabled = false), listOf(carIface))
        assertNull(decision.endpoint)
        assertEquals(EcarxHotspotAttachmentReason.SDK_STATE_CONFLICT, decision.reason)
    }

    @Test
    fun `no client and ambiguous route do not guess endpoint`() {
        val noClient = resolve(
            snapshot(status = EcarxHotspotReader.RouteStatus.NO_CURRENT_CLIENT, sources = emptyMap()),
            listOf(carIface),
        )
        val ambiguous = resolve(
            snapshot(status = EcarxHotspotReader.RouteStatus.AMBIGUOUS, sources = emptyMap()),
            listOf(carIface),
        )
        assertNull(noClient.endpoint)
        assertEquals(EcarxHotspotAttachmentReason.NO_CURRENT_CLIENT, noClient.reason)
        assertNull(ambiguous.endpoint)
        assertEquals(EcarxHotspotAttachmentReason.AMBIGUOUS_ROUTE, ambiguous.reason)
    }

    @Test
    fun `endpoint requires exact live Ecarx interface and source address`() {
        val down = resolve(snapshot(), listOf(carIface.copy(isUp = false)))
        val wrongAddress = resolve(snapshot(), listOf(carIface.copy(addresses = listOf("198.18.34.16"))))
        val wrongInterface = resolve(snapshot(), listOf(carIface.copy(name = "eth0.3")))
        assertEquals(EcarxHotspotAttachmentReason.INTERFACE_DOWN, down.reason)
        assertEquals(EcarxHotspotAttachmentReason.SOURCE_ADDRESS_NOT_ON_INTERFACE, wrongAddress.reason)
        assertEquals(EcarxHotspotAttachmentReason.INTERFACE_MISSING, wrongInterface.reason)
        assertNull(down.endpoint)
        assertNull(wrongAddress.endpoint)
        assertNull(wrongInterface.endpoint)
    }

    @Test
    fun `route policy is isolated from other platforms and non Ecarx address classes`() {
        val wrongPlatform = EcarxHotspotAttachmentPolicy.resolve(
            snapshot(), 28, "OTHER", "other", listOf(carIface),
        )
        val wrongSource = resolve(
            snapshot(sources = mapOf("eth0.11" to ipv4("192.168.15.1"))),
            listOf(carIface.copy(addresses = listOf("192.168.15.1"))),
        )
        assertEquals(EcarxHotspotAttachmentReason.UNSUPPORTED_PLATFORM, wrongPlatform.reason)
        assertEquals(EcarxHotspotAttachmentReason.ROUTE_NOT_ECARX_SOURCE, wrongSource.reason)
        assertNull(wrongPlatform.endpoint)
        assertNull(wrongSource.endpoint)
    }

    // ── 日志 7：iPhone 还没入网时（NO_CURRENT_CLIENT）本机 endpoint 仍可通告 ─────────────

    private val noClientDecision = EcarxHotspotAttachmentDecision(
        reason = EcarxHotspotAttachmentReason.NO_CURRENT_CLIENT,
    )

    /** 日志 7 现场：198.18.34.15 挂在 eth0 / eth0.11 等有线口上，wlan0 / p2p0 都 DOWN。 */
    private val field = listOf(
        carIface,
        carIface.copy(name = "eth0", isUplink = false),
        CarHotspotApInterface.Iface("wlan0", emptyList(), isUp = false),
    )

    private fun awaiting(
        decision: EcarxHotspotAttachmentDecision? = noClientDecision,
        address: String = "198.18.34.15",
        interfaces: List<CarHotspotApInterface.Iface> = field,
        model: String = "CS11",
        device: String = "cs11_high",
    ) = EcarxHotspotAttachmentPolicy.endpointAwaitingClient(
        decision, address, interfaces, 28, model, device,
    )

    @Test
    fun `日志7_没有客户端时_本机有线口上的 Ecarx endpoint 可以先通告`() {
        assertTrue(awaiting())
        assertTrue("地址前后空格要 trim", awaiting(address = " 198.18.34.15 "))
    }

    @Test
    fun `只在 NO_CURRENT_CLIENT 时放宽_其它决策原因照旧不放`() {
        EcarxHotspotAttachmentReason.entries
            .filter { it != EcarxHotspotAttachmentReason.NO_CURRENT_CLIENT }
            .forEach { reason ->
                assertFalse(
                    "$reason 不该被放宽",
                    awaiting(decision = EcarxHotspotAttachmentDecision(reason = reason)),
                )
            }
        assertFalse("没有决策（非复用 / 没读到）不放", awaiting(decision = null))
    }

    @Test
    fun `地址必须真在本机 UP 的 Ecarx 有线口上_网关与不存在的地址不放`() {
        // T-Box 网关 192.168.15.1 不在本机网卡上
        assertFalse(awaiting(address = "192.168.15.1"))
        // 地址不在任何网卡
        assertFalse(awaiting(address = "198.18.99.99"))
        // 网卡 DOWN
        assertFalse(awaiting(interfaces = field.map { it.copy(isUp = false) }))
        // 198.18/15 之外的地址即使挂在 eth 上也不是 Ecarx 源地址
        val other = listOf(CarHotspotApInterface.Iface("eth0", listOf("10.0.0.5"), isUp = true))
        assertFalse(awaiting(address = "10.0.0.5", interfaces = other))
    }

    @Test
    fun `非 Ecarx 平台与非 IPv4 字面量不放`() {
        assertFalse(awaiting(model = "OTHER", device = "other"))
        assertFalse(awaiting(address = "car.local"))
        assertFalse(awaiting(address = ""))
    }

    @Test
    fun `桥接层只在闸门判成 WIRED_ONLY 时才调用放宽_且只在复用分支里`() {
        val rel = "src/main/java/com/dbtool/carplay/bridge/CarPlaySessionBridge.kt"
        val code = listOf(java.io.File(rel), java.io.File("../$rel"), java.io.File("../../$rel"))
            .firstOrNull { it.exists() }?.readText() ?: error("找不到 $rel")
        val stripped = code
            .replace(Regex("(?s)/\\*.*?\\*/"), " ")
            .replace(Regex("(?m)//.*$"), " ")
        assertEquals(
            "endpointAwaitingClient 只能有桥接层这一个调用点",
            1,
            Regex("""endpointAwaitingClient\(""").findAll(stripped).count(),
        )
        assertTrue(
            "放宽必须以 gateVerdict is WIRED_ONLY 为前提，其余拦截（NOWHERE / SELF_POOL）不得被放宽",
            Regex(
                """gateVerdict is CarManualEndpointVerdict\.WIRED_ONLY\s*&&\s*EcarxHotspotAttachmentPolicy\.endpointAwaitingClient\(""",
            ).containsMatchIn(stripped),
        )
        val reuseGuard = stripped.indexOf("if (effectivePlan.reused && manualAddress != null)")
        val call = stripped.indexOf("endpointAwaitingClient(")
        assertTrue("放宽必须位于 effectivePlan.reused 分支内", reuseGuard in 0 until call)
    }

    private fun resolve(
        sample: EcarxHotspotReader.Snapshot,
        interfaces: List<CarHotspotApInterface.Iface>,
    ) = EcarxHotspotAttachmentPolicy.resolve(sample, 28, "CS11", "cs11_high", interfaces)

    private fun ipv4(value: String) = InetAddress.getByName(value)
}
