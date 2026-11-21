package com.dbtool.carplay.bridge.navigation

import com.shilapi.xcertplay.iap2.message.CarLaneGuidance
import com.shilapi.xcertplay.iap2.message.CarLaneStatus
import com.shilapi.xcertplay.iap2.message.CarNavigationState
import org.junit.Assert.*
import org.junit.Test

class HudBridgeLaneMappingTest {
    @Test fun `重复内容无论经过多久都不向桥接重发`() {
        var now = 0L
        val filter = HudPushDeduper { now }
        assertTrue(filter.accept(1000, 120, "right:130"))
        now = 999L
        assertFalse(filter.accept(1000, 120, "right:130"))
        now = 1000L
        assertFalse(filter.accept(1000, 120, "right:130"))
        now = 1001L
        assertTrue(filter.accept(1000, 120, "right:109"))
        filter.reset()
        assertTrue(filter.accept(1000, 120, "right:109"))
    }
    @Test fun `终点相同时转入路变化也必须放行`() {
        val state = CarNavigationState(maneuverDescription = "前方右转", nextRoad = "八一西路", destination = "车站")
        assertNotEquals(state.hudManeuverSignature(), state.copy(nextRoad = "衡州大道").hudManeuverSignature())
    }
    @Test fun `相同可见内容的新路线代次不能当成重复`() {
        val state = CarNavigationState(sessionId = 1L, revision = 1L)
        assertNotEquals(state.hudManeuverSignature(), state.copy(sessionId = 2L).hudManeuverSignature())
        assertNotEquals(state.hudManeuverSignature(), state.copy(revision = 2L).hudManeuverSignature())
    }

    @Test fun `只有完全相同更新才过滤 每种可见字段独立变化均放行`() {
        val state = CarNavigationState(routeMeters = 1000, routeSeconds = 120, segmentMeters = 300,
            maneuverType = 2, maneuverDescription = "八一西路", destination = "车站")
        val lane = CarLaneGuidance(0, listOf(0, 90), 90, CarLaneStatus.PREFERRED)
        val changed = listOf(state.copy(segmentMeters = 290), state.copy(maneuverType = 1),
            state.copy(maneuverDescription = "衡州大道"), state.copy(destination = "公园"),
            state.copy(lanes = listOf(lane)), state.copy(routeMeters = 900),
            state.copy(routeSeconds = 110))
        changed.forEach { next ->
            val deduper = HudPushDeduper()
            assertTrue(deduper.accept(state.routeMeters, state.routeSeconds, state.hudManeuverSignature()))
            assertFalse(deduper.accept(state.routeMeters, state.routeSeconds, state.hudManeuverSignature()))
            assertTrue(deduper.accept(next.routeMeters, next.routeSeconds, next.hudManeuverSignature()))
        }
    }
    @Test fun `blank description falls back to after road`() {
        assertEquals("衡州大道", CarNavigationState(maneuverDescription = " \u0000", nextRoad = "衡州大道").hudCurrentRoad())
    }

    @Test fun `blank destination does not hide road`() {
        assertEquals("八一西路", CarNavigationState(destination = " ", nextRoad = "八一西路").hudNextRoad())
    }

    @Test fun `destination remains independent from road description`() {
        val state = CarNavigationState(maneuverDescription = "进入衡州大道", destination = "火车站")
        assertEquals("进入衡州大道", state.hudCurrentRoad())
        assertEquals("火车站", state.hudNextRoad())
    }

    @Test fun `lane only and distance only changes are not deduplicated`() {
        val state = CarNavigationState(segmentMeters = 425)
        assertNotEquals(state.hudManeuverSignature(), state.copy(segmentMeters = 400).hudManeuverSignature())
        val lane = CarLaneGuidance(index = 0, status = CarLaneStatus.PREFERRED, angles = listOf(0, 90), highlightAngle = 90)
        assertNotEquals(state.hudManeuverSignature(), state.copy(lanes = listOf(lane)).hudManeuverSignature())
    }
}
