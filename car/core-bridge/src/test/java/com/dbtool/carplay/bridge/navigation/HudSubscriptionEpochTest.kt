package com.dbtool.carplay.bridge.navigation

import org.junit.Assert.*
import org.junit.Test

class HudSubscriptionEpochTest {
    @Test fun `copied callback from old subscription cannot enter reconnected source`() {
        val epochs = HudSubscriptionEpoch()
        val old = epochs.next()
        val copiedObserver = { epochs.isCurrent(old) }
        epochs.next() // disconnect revokes already copied observers
        val current = epochs.next() // reconnect
        assertFalse(copiedObserver())
        assertTrue(epochs.isCurrent(current))
    }

    @Test fun `old null callback cannot stop a new source either`() {
        val epochs = HudSubscriptionEpoch()
        val first = epochs.next()
        val second = epochs.next()
        assertFalse(epochs.isCurrent(first))
        assertTrue(epochs.isCurrent(second))
    }
}
