package com.dbtool.carplay.geely

import org.junit.Assert.*
import org.junit.Test

class HudLifecycleEpochTest {
    @Test fun `captured batch remains invalid after stop and immediate new start`() {
        val epochs = HudLifecycleEpoch()
        val firstStart = epochs.next()
        val capturedBatchEpoch = firstStart
        epochs.next() // stop
        val nextStart = epochs.next()
        assertFalse(epochs.isCurrent(capturedBatchEpoch))
        assertTrue(epochs.isCurrent(nextStart))
    }

    @Test fun `old queued start cannot reactivate after a newer stop`() {
        val epochs = HudLifecycleEpoch()
        val queuedStart = epochs.next()
        val stop = epochs.next()
        assertFalse(epochs.isCurrent(queuedStart))
        assertEquals(stop, epochs.current())
    }
}
