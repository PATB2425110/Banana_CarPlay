package com.dbtool.carplay.bridge

import com.dbtool.carplay.api.display.CarClusterCruiseGate
import com.dbtool.carplay.api.display.CarClusterModeAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PATCH-0091 契约：仪表「何时显示地图」的判据
 * （`CarClusterCruiseGate`，等价原版 `GeelyClusterModeState`）。
 *
 * 设置页原文是判据的权威来源：
 * > 开启后，无导航时四指左滑可手动开启，方控菜单可关闭。
 * > 开始导航自动开启，明确结束导航自动关闭；**持续的无导航状态不会关闭手动巡航地图**。
 *
 * 放在 `:car:core-bridge`：`car:api` 没配 JUnit 依赖，而本模块已有
 * `ClusterSafeAreaContractTest` 等"测 api 类型"的用例。
 */
class CarClusterCruiseGateTest {

    private fun gate(
        enabled: Boolean = true,
        display: Boolean = true,
        allowed: Boolean = false,
        route: Boolean = false,
    ) = CarClusterCruiseGate(
        enabled = enabled,
        displayAvailable = display,
        manualCruiseAllowed = allowed,
        routeActive = route,
    )

    @Test
    fun `开始导航自动开启`() {
        val g = gate(route = false)
        assertEquals(CarClusterModeAction.Enter, g.onRouteActive(true))
        assertTrue(g.modeActive)
        assertTrue(g.uiEligible())
    }

    @Test
    fun `路线情报变陈旧不关屏`() {
        // "持续的无导航状态不会关闭"：routeActive 变 false 只更新状态，不动屏。
        val g = gate()
        g.onRouteActive(true)
        assertEquals(CarClusterModeAction.None, g.onRouteActive(false))
        assertTrue("屏必须还开着", g.modeActive)
    }

    @Test
    fun `明确结束导航自动关闭`() {
        val g = gate()
        g.onRouteActive(true)
        assertEquals(CarClusterModeAction.Exit, g.onRouteEndedExplicitly())
        assertFalse(g.modeActive)
    }

    @Test
    fun `未开启手动巡航时不能手动开`() {
        val g = gate(allowed = false)
        assertFalse(g.canManualOpen())
        assertEquals(CarClusterModeAction.None, g.onManualOpen())
        assertFalse(g.modeActive)
    }

    @Test
    fun `开启手动巡航后无导航也能手动开`() {
        val g = gate(allowed = true)
        assertTrue(g.canManualOpen())
        assertEquals(CarClusterModeAction.Enter, g.onManualOpen())
        assertTrue(g.uiEligible())
    }

    @Test
    fun `手动开启后路线开始再结束_会正常收屏`() {
        // 手动开过又来了导航：导航结束时应当收屏（manualCruise 被路线开启清掉）。
        val g = gate(allowed = true)
        g.onManualOpen()
        g.onRouteActive(true)
        assertEquals(CarClusterModeAction.Exit, g.onRouteEndedExplicitly())
    }

    @Test
    fun `关掉手动巡航设置_会收起纯手动地图`() {
        val g = gate(allowed = true)
        g.onManualOpen()
        assertEquals(CarClusterModeAction.Exit, g.onManualCruiseAllowed(false))
        assertFalse(g.uiEligible())
    }

    @Test
    fun `关掉手动巡航设置_不影响正在导航的屏`() {
        val g = gate(allowed = true)
        g.onRouteActive(true)
        assertEquals(CarClusterModeAction.None, g.onManualCruiseAllowed(false))
        assertTrue(g.modeActive)
    }

    @Test
    fun `副屏不可用时一律收屏`() {
        val g = gate()
        g.onRouteActive(true)
        assertEquals(CarClusterModeAction.Exit, g.onDisplayAvailable(false))
        assertFalse(g.uiEligible())
    }

    @Test
    fun `投屏总开关关掉一律收屏且清手动标记`() {
        val g = gate(allowed = true)
        g.onManualOpen()
        assertEquals(CarClusterModeAction.Exit, g.onEnabled(false))
        assertFalse(g.uiEligible())
        // 再打开也不能"复活"上一次的手动状态（避免莫名自己亮起来）。
        assertEquals(CarClusterModeAction.None, g.onEnabled(true))
        assertFalse(g.modeActive)
    }

    @Test
    fun `重复事件不产生动作`() {
        val g = gate()
        g.onRouteActive(true)
        assertEquals(CarClusterModeAction.None, g.onRouteActive(true))
        assertEquals(CarClusterModeAction.None, g.onEnabled(true))
        assertEquals(CarClusterModeAction.None, g.onDisplayAvailable(true))
    }
}
