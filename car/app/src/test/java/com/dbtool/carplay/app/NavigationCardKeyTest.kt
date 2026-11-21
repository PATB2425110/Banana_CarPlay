package com.dbtool.carplay.app

import com.dbtool.carplay.api.navigation.CarHudLane
import com.dbtool.carplay.api.navigation.CarNavigationSnapshot
import org.junit.Assert.*
import org.junit.Test

class NavigationCardKeyTest {
    private val base = CarNavigationSnapshot(routeActive = true, nextRoad = "测试路", sessionId = 1)

    @Test fun maneuverOnlyAndLaneOnlyChangesRefreshTheLocalCard() {
        assertNotEquals(base.navigationCardKey(), base.copy(maneuverType = 3).navigationCardKey())
        val lane = base.copy(lanes = listOf(CarHudLane(0, listOf(0, 90), 0)))
        assertNotEquals(base.navigationCardKey(), lane.navigationCardKey())
        assertNotEquals(lane.navigationCardKey(),
            lane.copy(lanes = listOf(CarHudLane(0, listOf(0, 90), 90))).navigationCardKey())
    }

    @Test fun repeatedTripAndClearAreNotSuppressedByEqualDistances() {
        assertNotEquals(base.navigationCardKey(), base.copy(sessionId = 2).navigationCardKey())
        assertNotEquals(base.navigationCardKey(), base.copy(routeActive = false).navigationCardKey())
        assertNotEquals(base.navigationCardKey(), (null as CarNavigationSnapshot?).navigationCardKey())
    }

    @Test fun revisionAndReceiveTimeAloneDoNotRepaint() {
        assertEquals(base.navigationCardKey(),
            base.copy(revision = 42, receivedAtEpochMillis = 1234).navigationCardKey())
    }
}
