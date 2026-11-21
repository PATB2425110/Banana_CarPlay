package com.dbtool.carplay.geely

import com.dbtool.carplay.api.navigation.CarHudGuidance
import org.junit.Assert.*
import org.junit.Test

class HudNavigationStateTest {
    private fun frame(index: Int = 1) = CarHudGuidance(
        updatedAtMs = 900L, routeMeters = 1000, routeSeconds = 60,
        segmentMeters = index, maneuverType = 1, currentRoad = "甲路",
        nextRoad = "目的地", sessionId = 1, revision = 1,
    )

    @Test fun `empty queue is not a stop`() {
        assertEquals(HudNavigationState.Batch(false, null), HudNavigationState().drain(0))
    }

    @Test fun `burst retains newest full state in bounded slot`() {
        val state = HudNavigationState()
        repeat(10000) { state.offer(frame(it), it.toLong()) }
        assertEquals(9999, state.drain(10000).guidance?.segmentMeters)
        assertNull(state.drain(10001).guidance)
    }

    @Test fun `stop is not erased by empty queue or following route`() {
        val state = HudNavigationState()
        state.offer(frame(1), 0)
        state.offer(null, 1)
        state.offer(frame(2), 2)
        val batch = state.drain(3)
        assertTrue(batch.stop)
        assertEquals(frame(2), batch.guidance)
        assertFalse(state.drain(4).stop)
        assertEquals(frame(2), state.fresh(4))
    }

    @Test fun `explicit stop discards all older content`() {
        val state = HudNavigationState()
        state.offer(frame(), 0)
        state.offer(null, 1)
        assertEquals(HudNavigationState.Batch(true, null), state.drain(2))
        assertNull(state.fresh(3))
    }

    @Test fun `transport reads never renew source freshness`() {
        val state = HudNavigationState(100)
        state.offer(frame(), 0)
        for (now in 1L..99L) assertNotNull(state.fresh(now))
        assertNull(state.fresh(100))
        assertTrue(state.drain(100).stop)
        assertFalse(state.drain(101).stop)
    }

    @Test fun `identical source input renews freshness without replacing content semantics`() {
        val state = HudNavigationState(100)
        state.offer(frame(), 0)
        state.drain(0)
        state.offer(frame(), 99)
        assertEquals(frame(), state.fresh(150))
        assertNull(state.fresh(199))
    }

    @Test fun `expired content cannot replay after reconnect`() {
        val state = HudNavigationState(100)
        state.offer(frame(), 0)
        assertNull(state.fresh(1000))
        assertEquals(HudNavigationState.Batch(true, null), state.drain(1001))
    }

    @Test fun `clear disconnect removes pending stop snapshot and deadline`() {
        val state = HudNavigationState(100)
        state.offer(frame(), 0)
        state.offer(null, 1)
        state.clear()
        assertEquals(HudNavigationState.Batch(false, null), state.drain(1000))
        assertNull(state.fresh(1000))
    }

    @Test fun `source lifetime does not depend on payload wall clock`() {
        val state = HudNavigationState(100)
        state.offer(frame().copy(updatedAtMs = Long.MAX_VALUE), 10)
        assertNotNull(state.fresh(109))
        assertNull(state.fresh(110))
    }

    @Test fun `connection replay atomically consumes stop before latest route`() {
        val state = HudNavigationState()
        state.offer(frame(1), 0)
        state.offer(null, 1)
        state.offer(frame(2), 2)
        assertEquals(HudNavigationState.Batch(true, frame(2)), state.takeForSend(3, replay = true))
        assertEquals(HudNavigationState.Batch(false, null), state.drain(4))
    }

    @Test fun `heartbeat replay sees stop and never revives earlier current`() {
        val state = HudNavigationState()
        state.offer(frame(), 0)
        state.drain(0)
        state.offer(null, 1)
        assertEquals(HudNavigationState.Batch(true, null), state.takeForSend(2, replay = true))
    }
}
