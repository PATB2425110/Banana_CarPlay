package com.dbtool.carplay.geely

import org.junit.Assert.*
import org.junit.Test

class HudBridgeCapabilitiesTest {
    @Test fun legacyLynkBridgeStillProvidesBasicNavigation() {
        assertEquals(HudBridgeCapabilities(true, false),
            HudBridgeCapabilities.fromTags("DBPLAY_LYNK_BRIDGE_V1", null))
    }

    @Test fun missingFailedOrUnrecognizedLaneProbeCannotDisableTurnCard() {
        for (lane in listOf(null, "", "unsupported", "DBPLAY_LYNK_LANES_V0")) {
            val caps = HudBridgeCapabilities.fromTags("DBPLAY_LYNK_BRIDGE_V1", lane)
            assertTrue(caps.navigation)
            assertFalse(caps.lanes)
        }
    }

    @Test fun supportedLaneTagEnablesBothWithoutChangingIdentityContract() {
        assertEquals(HudBridgeCapabilities(true, true),
            HudBridgeCapabilities.fromTags("DBPLAY_LYNK_BRIDGE_V1", "DBPLAY_LYNK_LANES_V1"))
    }

    @Test fun wrongBaseProtocolIsStillRejectedEvenIfItClaimsLaneSupport() {
        assertEquals(HudBridgeCapabilities(false, false),
            HudBridgeCapabilities.fromTags("wrong-base", "DBPLAY_LYNK_LANES_V1"))
    }
}
