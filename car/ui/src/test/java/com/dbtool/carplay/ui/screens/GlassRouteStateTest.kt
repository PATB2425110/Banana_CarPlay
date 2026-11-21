package com.dbtool.carplay.ui.screens

import com.dbtool.carplay.api.navigation.CarNavigationSnapshot
import com.dbtool.carplay.api.navigation.CarHudLane
import org.junit.Assert.*
import org.junit.Test

class GlassRouteStateTest {
    @Test fun rerouteSuppressesOldLaneAndShowsRealState() {
        val state = CarNavigationSnapshot(routeActive = true, guidanceState = 5,
            routeMeters = 1000, maneuverType = 2, lanes = listOf(CarHudLane(0, listOf(0), 0)))
        assertEquals("正在重新规划路线", glassNavigationText(state).road)
        assertTrue(glassLanes(state).isEmpty())
        assertEquals("正在加载路线", glassNavigationText(state.copy(guidanceState = 3)).road)
        assertEquals("正在定位", glassNavigationText(state.copy(guidanceState = 4)).road)
    }
    @Test fun phoneDistanceUnitsUsedWithoutDiscardingRawMeters() {
        val state = CarNavigationSnapshot(routeActive = true, routeMeters = 20000,
            segmentMeters = 300, routeDistanceDisplay = "12.5", routeDistanceUnits = 1,
            maneuverDistanceDisplay = "900", maneuverDistanceUnits = 4)
        assertEquals("900英尺", glassNavigationText(state).distance)
        assertTrue(glassNavigationText(state).remaining.startsWith("12.5英里"))
        assertEquals(300, state.segmentMeters)
        assertEquals("300米", glassNavigationText(state.copy(maneuverDistanceUnits = null)).distance)
    }
}
