package com.dbtool.carplay.bridge.hotspot

import com.dbtool.carplay.api.hotspot.CarHotspotEnabledState
import com.dbtool.carplay.api.hotspot.CarManualHotspotPrecheck
import com.dbtool.carplay.api.settings.CarHotspotMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 吉利 Ecarx（CS11/KX11）原厂热点适配的单测（PATCH-0088）。
 *
 * ## 这一组在防什么
 *
 * 实车日志（2026-10-11 21:35 包）证明：CS11 的原厂热点由厂商 `tcam` 服务管，
 * 地址挂 `eth0=198.18.34.15`，**不进标准 Android SoftAP API** ——
 * `getWifiApState()` 稳定返回 `11`（DISABLED），是**假关闭**。
 * 旧代码据此一票否决，导致「复用车机热点」永远连不上。
 *
 * 三条纪律，每条都对应一种"改坏"：
 *
 * 1. **厂商 SDK 说开着 ⇒ 认 ON**，不被标准 API 的假关闭推翻；
 * 2. **SDK 不存在 / 读不到 ⇒ 行为与改动前逐字相同**（非 Ecarx 车机零影响）；
 * 3. **用户填了原厂网关地址 ⇒ 不被"标准 AP 关着"拦下**，
 *    但**没填地址时照旧拦**（PATCH-0087 的纪律不能被这条新逻辑吃掉）。
 */
class EcarxHotspotAdaptationTest {

    // ------------------------------------------- ① Ecarx 判定（EcarxClientRoute）

    /** CS11 是本车的型号，必须和 KX11 一样被认作 Ecarx 平台。 */
    @Test
    fun `CS11 与 KX11 一样被认作 Ecarx 原厂网关平台`() {
        // eth0 上挂 198.18.x.x ⇒ CS11 也放行 198.18 兜底
        assertTrue(
            "CS11 + eth0 + 198.18 必须放行（本车实测 eth0=198.18.34.15）",
            EcarxClientRoute.kx11Source(28, "CS11", "cs11_high", "eth0", ipv4("198.18.34.15")),
        )
        assertTrue(
            "KX11 原有行为不能被破坏",
            EcarxClientRoute.kx11Source(28, "KX11", "kx11", "eth0", ipv4("198.18.34.15")),
        )
        assertTrue(
            "CX11 + eth0.11 + 198.18.34.15 必须放行",
            EcarxClientRoute.kx11Source(28, "CX11", "cx11_high", "eth0.11", ipv4("198.18.34.15")),
        )
    }

    /** 非 Ecarx 平台 / 非 198.18 段 / 非 eth 接口 —— 三个条件缺一不可。 */
    @Test
    fun `原厂网关兜底不会波及无关平台与地址`() {
        assertFalse(
            "别的车型（MODEL=ABC）不该走这条兜底",
            EcarxClientRoute.kx11Source(28, "ABC", "abc", "eth0", ipv4("198.18.34.15")),
        )
        assertFalse(
            "公网常规地址不该被当成原厂网关",
            EcarxClientRoute.kx11Source(28, "CS11", "cs11_high", "eth0", ipv4("192.168.1.1")),
        )
        assertFalse(
            "wlan0 上的地址不该走 eth 兜底",
            EcarxClientRoute.kx11Source(28, "CS11", "cs11_high", "wlan0", ipv4("198.18.34.15")),
        )
    }

    /** 地址分类：198.18/15 被单列成厂商段，不与普通私有地址混为一谈。 */
    @Test
    fun `198点18段被归为厂商原厂网关地址类`() {
        assertEquals("benchmark_198_18_15", EcarxClientRoute.addressClass(ipv4("198.18.34.15")))
        assertEquals("benchmark_198_18_15", EcarxClientRoute.addressClass(ipv4("198.19.0.1")))
        assertFalse(
            "198.20 已在 /15 之外，属普通地址",
            EcarxClientRoute.addressClass(ipv4("198.20.0.1")) == "benchmark_198_18_15",
        )
        assertEquals("private_ipv4", EcarxClientRoute.addressClass(ipv4("192.168.15.1")))
    }

    // ------------------------------------ ② Precheck：假关闭 + 原厂地址 ⇒ 放行

    /**
     * 🔴 本条是这次修复的**核心**：热点报"关着"（假状态）但用户填了原厂网关地址 ⇒
     * 必须放行，让连接继续，由地址判据决定成败。
     */
    @Test
    fun `填了原厂网关地址时假关闭不再拦截`() {
        for (address in listOf("198.18.34.15", " 198.18.34.15 ", "198.19.1.1")) {
            assertNull(
                "填了原厂网关地址 $address 时不该被 wifiApState=11 的假关闭拦下",
                CarManualHotspotPrecheck.blocker(
                    mode = CarHotspotMode.MANUAL,
                    state = CarHotspotEnabledState.OFF,
                    ssid = "Lynk",
                    hostAddress = address,
                    reuseRequested = true,
                ),
            )
        }
    }

    /**
     * ⚠️ 反向守门：**普通地址 + 热点报关闭 ⇒ 照旧拦** ——
     * 新逻辑不能把 PATCH-0087 之后的拦截能力整体吃掉。
     */
    @Test
    fun `普通地址遇上关闭状态仍然拦截`() {
        val message = CarManualHotspotPrecheck.blocker(
            mode = CarHotspotMode.MANUAL,
            state = CarHotspotEnabledState.OFF,
            ssid = "Lynk",
            hostAddress = "192.168.15.1",
            reuseRequested = true,
        )
        assertNotNull("192.168.15.1 不是原厂网关段，必须照旧拦下", message)
        assertTrue("仍要说清热点关着：$message", message!!.contains("关闭状态"))
    }

    /** 原厂地址**但没填/空白** ⇒ 仍按 PATCH-0087 拦（地址缺失优先）。 */
    @Test
    fun `原厂段判定不会放过没填地址的情况`() {
        for (address in listOf("", "   ")) {
            assertNotNull(
                "地址是空白 '$address' ⇒ 仍要拦",
                CarManualHotspotPrecheck.blocker(
                    mode = CarHotspotMode.MANUAL,
                    state = CarHotspotEnabledState.OFF,
                    hostAddress = address,
                    reuseRequested = true,
                ),
            )
        }
    }

    /** 畸形地址不能被误判成原厂网关（解析失败一律 false）。 */
    @Test
    fun `畸形地址不会被当成原厂网关`() {
        for (bad in listOf("198.18", "198.18.34", "198.18.34.15.16", "198.18.abc.1",
            "256.18.1.1", "198.18.-1.1")) {
            assertNotNull(
                "畸形地址 '$bad' 必须仍被拦下（不能误判为原厂段）",
                CarManualHotspotPrecheck.blocker(
                    mode = CarHotspotMode.MANUAL,
                    state = CarHotspotEnabledState.OFF,
                    hostAddress = bad,
                    reuseRequested = true,
                ),
            )
        }
    }

    // --------------------------- ③ EnabledReader：Ecarx 旁证优先，读不到零影响

    /** 厂商 SDK 明确说开着 ⇒ 直接 ON，**不管**标准 API 报什么。 */
    @Test
    fun `厂商SDK说开着时标准API的假关闭被推翻`() {
        assertEquals(
            "SDK 说开着就该是 ON",
            CarHotspotEnabledState.ON,
            CarHotspotEnabledReader.read(
                state = { 11 }, // 标准 API 的假关闭
                enabled = { false },
                sticky = { null },
                nativeEnabled = { true },
            ),
        )
    }

    /**
     * ⚠️ 反向守门：SDK 读不到（null）⇒ 行为与**改动前逐字相同**。
     * 非 Ecarx 车机、或 SDK 加载失败时，一切照旧。
     */
    @Test
    fun `SDK读不到时行为与改动前完全一致`() {
        assertEquals(
            "SDK 不存在(缺省)时，13 仍为 ON",
            CarHotspotEnabledState.ON,
            CarHotspotEnabledReader.read(state = { 13 }, enabled = { null }, sticky = { null }),
        )
        assertEquals(
            "SDK 不存在(缺省)时，11 仍为 OFF",
            CarHotspotEnabledState.OFF,
            CarHotspotEnabledReader.read(state = { 11 }, enabled = { null }, sticky = { null }),
        )
        assertEquals(
            "显式传 null（SDK 加载失败）也必须与缺省等价",
            CarHotspotEnabledState.OFF,
            CarHotspotEnabledReader.read(
                state = { 11 }, enabled = { null }, sticky = { null }, nativeEnabled = { null },
            ),
        )
    }

    /** SDK 明确说**关着**（false）不足以推翻标准判据 —— 可能真的关着，保持拦截。 */
    @Test
    fun `SDK说关着时不推翻标准判据`() {
        assertEquals(
            CarHotspotEnabledState.OFF,
            CarHotspotEnabledReader.read(
                state = { 11 }, enabled = { null }, sticky = { null }, nativeEnabled = { false },
            ),
        )
    }

    @Test
    fun `Ecarx明确未知时标准SoftAP假关闭降为UNKNOWN而明确OFF保留`() {
        val noClient = EcarxHotspotReader.Snapshot(
            enabled = null,
            routeStatus = EcarxHotspotReader.RouteStatus.NO_CURRENT_CLIENT,
        )
        assertEquals(
            CarHotspotEnabledState.UNKNOWN,
            CarHotspotEnabledReader.applyEcarxUnknownFallback(CarHotspotEnabledState.OFF, noClient, true),
        )
        assertEquals(
            CarHotspotEnabledState.OFF,
            CarHotspotEnabledReader.applyEcarxUnknownFallback(CarHotspotEnabledState.OFF, noClient, false),
        )
        val explicitlyOff = noClient.copy(enabled = false)
        assertEquals(
            CarHotspotEnabledState.OFF,
            CarHotspotEnabledReader.applyEcarxUnknownFallback(CarHotspotEnabledState.OFF, explicitlyOff, true),
        )
    }

    @Test
    fun `唯一验证路由在Ecarx上可作为热点ON旁证但空客户端不行`() {
        val verified = EcarxHotspotReader.Snapshot(
            enabled = null,
            routeStatus = EcarxHotspotReader.RouteStatus.VERIFIED,
            routeSources = mapOf("eth0.11" to ipv4("198.18.34.15")),
        )
        val noClient = EcarxHotspotReader.Snapshot(
            enabled = null,
            routeStatus = EcarxHotspotReader.RouteStatus.NO_CURRENT_CLIENT,
        )
        assertTrue(CarHotspotEnabledReader.hasVerifiedEcarxClientRoute(verified, 28, "CS11", "cs11_high"))
        assertFalse(CarHotspotEnabledReader.hasVerifiedEcarxClientRoute(noClient, 28, "CS11", "cs11_high"))
        assertFalse(CarHotspotEnabledReader.hasVerifiedEcarxClientRoute(verified, 28, "OTHER", "other"))
    }

    /** SDK 抛异常 ⇒ 静默降级，不影响标准判据。 */
    @Test
    fun `SDK抛异常时静默降级不影响原判据`() {
        assertEquals(
            CarHotspotEnabledState.ON,
            CarHotspotEnabledReader.read(
                state = { 13 }, enabled = { null }, sticky = { null },
                nativeEnabled = { throw IllegalStateException("binder dead") },
            ),
        )
    }

    // ------------------------------------------------------------------ helpers

    private fun ipv4(text: String) = java.net.InetAddress.getByName(text) as java.net.Inet4Address
}
