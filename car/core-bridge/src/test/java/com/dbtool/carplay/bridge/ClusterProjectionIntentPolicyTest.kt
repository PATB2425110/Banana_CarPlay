package com.dbtool.carplay.bridge

import com.dbtool.carplay.api.display.ClusterProjectionIntentPolicy
import org.junit.Assert.*
import org.junit.Test

class ClusterProjectionIntentPolicyTest {
    private val policy = ClusterProjectionIntentPolicy()

    @Test fun manualCloseBlocksAutoAndRecoveryAcrossRoutes() {
        policy.onManualRequest(false)
        repeat(4) {
            policy.resetObservation() // stop/window recovery, NOT connection reset
            policy.observeMode(3, true, it * 4000L)
            assertFalse(policy.allowsAutomaticOpen()) // a new route is not a user open
        }
    }

    @Test fun explicitManualOpenRevokesClose() {
        policy.onManualRequest(false)
        policy.onManualRequest(true)
        assertTrue(policy.allowsAutomaticOpen())
    }

    @Test fun disconnectReconnectClearsIntentAndObservation() {
        policy.onManualRequest(false)
        policy.resetSession()
        assertTrue(policy.allowsAutomaticOpen())
        policy.observeMode(1, true, 0)
        assertFalse(policy.observeMode(1, true, 4000))
    }

    @Test fun modeOneAloneIsNotView() {
        repeat(10) { assertFalse(policy.observeMode(1, true, it * 1500L)) }
        assertTrue(policy.allowsAutomaticOpen())
    }

    @Test fun stableExternalCloseRequiresPreviouslyVerifiedThree() {
        policy.observeMode(3, true, 0)
        assertFalse(policy.observeMode(1, true, 1500))
        assertFalse(policy.observeMode(1, true, 2999))
        assertTrue(policy.observeMode(1, true, 3000))
        assertFalse(policy.allowsAutomaticOpen())
        assertFalse(policy.observeMode(1, true, 4500))
    }

    @Test fun transientOffDoesNotSuppress() {
        policy.observeMode(3, true, 0)
        policy.observeMode(1, true, 1500)
        policy.observeMode(3, true, 2000)
        assertFalse(policy.observeMode(1, true, 3000))
        assertTrue(policy.allowsAutomaticOpen())
    }

    @Test fun hiddenNavigationOrOwnExitIsNotExternalClose() {
        policy.observeMode(3, true, 0)
        assertFalse(policy.observeMode(1, false, 1500))
        assertFalse(policy.observeMode(1, true, 3000))
        assertFalse(policy.observeMode(1, true, 4500))
        assertTrue(policy.allowsAutomaticOpen())
    }

    @Test fun unreadableOrOtherModeBreaksStableSequence() {
        for (interruption in listOf(null, 2)) {
            policy.resetSession()
            policy.observeMode(3, true, 0)
            policy.observeMode(1, true, 1500)
            policy.observeMode(interruption, true, 2000)
            assertFalse(policy.observeMode(1, true, 3000))
            assertTrue(policy.allowsAutomaticOpen())
        }
    }
}
