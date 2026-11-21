package com.dbtool.carplay.bridge.navigation

import com.shilapi.xcertplay.iap2.message.CarNavigationState
import com.shilapi.xcertplay.iap2.message.CarLaneGuidance
import com.shilapi.xcertplay.iap2.message.CarLaneStatus
import com.shilapi.xcertplay.navigation.CarPlayNavigationBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayNavigationDataSourceTest {

    @Test
    fun `lane only state is visible sorted bounded and retains raw branch recommendations`() {
        val snapshot = requireNotNull(CarNavigationState(lanes = (20 downTo 0).map {
            CarLaneGuidance(it, listOf(270, -90, 32767, 720, 0, 45, 90, 180, 12), 270, CarLaneStatus.GOOD)
        }).toCarNavigationSnapshot(1234L))
        assertTrue(snapshot.hasContent)
        assertEquals((0..15).toList(), snapshot.lanes.map { it.index })
        assertEquals(listOf(270, -90, 32767, 720, 0, 45, 90, 180), snapshot.lanes.first().angles)
        assertEquals(270, snapshot.lanes.first().highlightAngle)
        assertTrue(snapshot.copy(lanes = emptyList()).hasContent.not())
    }

    @Test
    fun `lane replacement and clear are published without retaining old UI lanes`() {
        val source = CarPlayNavigationDataSource()
        val owner = Any()
        val seen = mutableListOf<com.dbtool.carplay.api.navigation.CarNavigationSnapshot?>()
        CarPlayNavigationBridge.attach(owner)
        val cancel = source.observe(seen::add)
        try {
            CarPlayNavigationBridge.publish(owner, CarNavigationState(lanes = listOf(
                CarLaneGuidance(4, listOf(-90, 0), -90, CarLaneStatus.GOOD),
            )))
            assertEquals(listOf(-90, 0), seen.last()?.lanes?.single()?.angles)
            CarPlayNavigationBridge.publish(owner, CarNavigationState(routeMeters = 10))
            assertTrue(requireNotNull(seen.last()).lanes.isEmpty())
            CarPlayNavigationBridge.publish(owner, CarNavigationState())
            assertNull(seen.last())
            CarPlayNavigationBridge.detach(owner)
            assertNull(source.current())
        } finally {
            cancel()
            CarPlayNavigationBridge.detach(owner)
        }
    }

    @Test
    fun `upstream snapshot projects all local-card fields without vehicle HUD mapping`() {
        val snapshot = CarNavigationState(
            routeMeters = 4_200,
            routeSeconds = 780,
            segmentMeters = 250,
            currentRoad = "当前路",
            nextRoad = "下一条路",
            maneuverDescription = "前方左转",
            maneuverType = 1,
            routeActive = true,
            destination = "终点",
            arrivalEpochSeconds = 1_800_000_000L,
            sessionId = 41L,
            revision = 9L,
        ).toCarNavigationSnapshot(receivedAtEpochMillis = 1234L)

        requireNotNull(snapshot)
        assertEquals(4_200, snapshot.routeMeters)
        assertEquals(780, snapshot.routeSeconds)
        assertEquals(250, snapshot.segmentMeters)
        assertEquals("当前路", snapshot.currentRoad)
        assertEquals("下一条路", snapshot.nextRoad)
        assertEquals("前方左转", snapshot.maneuverDescription)
        assertEquals(1, snapshot.maneuverType)
        assertEquals("终点", snapshot.destination)
        assertEquals(1_800_000_000L, snapshot.arrivalEpochSeconds)
        assertTrue(snapshot.routeActive)
        assertEquals(41L, snapshot.sessionId)
        assertEquals(9L, snapshot.revision)
        assertEquals(1234L, snapshot.receivedAtEpochMillis)
    }

    @Test
    fun `empty decoder state is not turned into a zeroed card`() {
        assertNull(CarNavigationState().toCarNavigationSnapshot(receivedAtEpochMillis = 1234L))
    }

    @Test
    fun `source immediately replays current value and follows owner publications`() {
        val source = CarPlayNavigationDataSource()
        val owner = Any()
        val seen = mutableListOf<com.dbtool.carplay.api.navigation.CarNavigationSnapshot?>()
        CarPlayNavigationBridge.attach(owner)
        val cancel = source.observe(seen::add)
        try {
            assertNull(seen.last())
            CarPlayNavigationBridge.publish(owner, CarNavigationState(routeMeters = 900))
            assertEquals(900, seen.last()?.routeMeters)
        } finally {
            cancel()
            CarPlayNavigationBridge.detach(owner)
        }
    }
}
