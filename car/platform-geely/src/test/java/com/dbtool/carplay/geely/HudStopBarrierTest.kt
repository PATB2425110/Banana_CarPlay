package com.dbtool.carplay.geely

import com.dbtool.carplay.api.navigation.CarHudGuidance
import org.junit.Assert.*
import org.junit.Test

class HudStopBarrierTest {
    @Test fun `unavailable rejected and failed stop remain pending until accepted`() {
        val barrier = HudStopBarrier()
        barrier.request()
        assertTrue(barrier.isPending) // no binder
        barrier.request()
        assertTrue(barrier.isPending) // rejected or exception: no acknowledge
        val accepted = barrier.request()
        barrier.acknowledge(accepted)
        assertFalse(barrier.isPending)
    }

    @Test fun `old acknowledgement cannot erase a newer stop`() {
        val barrier = HudStopBarrier()
        val old = barrier.request()
        val current = barrier.request()
        barrier.acknowledge(old)
        assertTrue(barrier.isPending)
        barrier.acknowledge(current)
        assertFalse(barrier.isPending)
    }

    @Test fun `dequeued stop failure still precedes replayed new route on reconnect`() {
        val mailbox = HudNavigationState()
        val barrier = HudStopBarrier()
        val route = CarHudGuidance(10, 100, 50, 20, 1, "甲", "乙", 1, 1)
        mailbox.offer(null, 0)
        mailbox.offer(route, 1)
        val disconnected = mailbox.takeForSend(2, replay = false)
        assertTrue(disconnected.stop)
        barrier.request() // stop transmit fails: receiver has not acknowledged
        val reconnect = mailbox.takeForSend(3, replay = true)
        assertFalse(reconnect.stop) // mailbox event was consumed, delivery barrier was not
        assertEquals(route, reconnect.guidance)
        assertTrue("must not publish new route yet", barrier.isPending)
        val stopOnNewConnection = barrier.request()
        barrier.acknowledge(stopOnNewConnection)
        assertFalse("new route can now pass", barrier.isPending)
    }
}
