package com.dbtool.carplay.bridge.navigation

import com.dbtool.carplay.api.navigation.CarHudBridge
import com.dbtool.carplay.api.navigation.CarHudGuidance
import com.shilapi.xcertplay.iap2.message.CarNavigationState
import com.shilapi.xcertplay.navigation.CarPlayNavigationBridge
import org.junit.Assert.*
import org.junit.Test

class JointRouteRecoveryTest {
    @Test fun stopRerouteAndSameContentNextTripReachTheNativeBridge() {
        val frames = mutableListOf<CarHudGuidance?>()
        val bridge = object : CarHudBridge {
            override fun isAvailable() = true
            override fun start(onAvailabilityChanged: (Boolean) -> Unit) = onAvailabilityChanged(true)
            override fun stop() {}
            override fun update(guidance: CarHudGuidance?) { frames += guidance }
        }
        val owner = Any()
        val client = CarPlayNavigationHudClient(bridge, log = {})
        CarPlayNavigationBridge.attach(owner)
        client.connect()
        try {
            val initial = CarNavigationState(routeActive = true, guidanceState = 1,
                routeGeneration = 101, maneuverIndex = 0, maneuverType = 2, segmentMeters = 300)
            CarPlayNavigationBridge.publish(owner, initial)
            assertEquals(101L, frames.last()!!.sessionId)
            CarPlayNavigationBridge.publish(owner, initial.copy(guidanceState = 5))
            assertNull("reroute must clear native old turn", frames.last())
            CarPlayNavigationBridge.publish(owner, initial.copy(maneuverType = null))
            assertNull("unresolved selection must never repeat old native turn", frames.last())
            CarPlayNavigationBridge.publish(owner, initial.copy(routeGeneration = 102))
            assertEquals(102L, frames.last()!!.sessionId)
            CarPlayNavigationBridge.publish(owner, null)
            assertNull(frames.last())
            CarPlayNavigationBridge.publish(owner, initial.copy(routeGeneration = 103))
            assertEquals(103L, frames.last()!!.sessionId)
            assertEquals(2, frames.last()!!.maneuverType)
        } finally {
            client.disconnect()
            CarPlayNavigationBridge.detach(owner)
        }
    }

    @Test fun onlyTurnContentSurvivesUiAdapterAndMetadataRemainsAvailable() {
        val snapshot = CarNavigationState(maneuverType = 2, routeActive = true,
            guidanceState = 1, maneuverState = 3, shownInApp = false, routeGeneration = 99,
            laneCount = 0, laneDescription = "keep left", routeSource = "百度地图").toCarNavigationSnapshot()
        assertNotNull(snapshot)
        assertTrue(snapshot!!.hasContent)
        assertEquals(3, snapshot.maneuverState)
        assertEquals(false, snapshot.shownInApp)
        assertEquals(99L, snapshot.routeGeneration)
        assertEquals(0, snapshot.laneCount)
        assertEquals("keep left", snapshot.laneDescription)
    }
}
