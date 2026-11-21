package com.dbtool.carplay.geely

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeelyClusterPresentationCallbackTest {

    @Test
    fun currentPresentationCallbacksAreAccepted() {
        assertTrue(shouldAcceptClusterPresentationCallback(7L, 7L, stopped = false))
    }

    @Test
    fun callbacksFromPreviousPresentationAreRejected() {
        assertFalse(shouldAcceptClusterPresentationCallback(6L, 7L, stopped = false))
    }

    @Test
    fun callbacksAfterStopAreRejected() {
        assertFalse(shouldAcceptClusterPresentationCallback(7L, 7L, stopped = true))
    }
}
