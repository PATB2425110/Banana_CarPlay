package com.dbtool.carplay.api.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AltScreenSlotPolicyTest {
    private val clusterSpec = CarClusterSpec(
        displayId = 2,
        widthPixels = 1920,
        heightPixels = 1080,
        densityDpi = 240,
    )

    @Test
    fun `no HUD request and no cluster spec is None`() {
        val decision = policy().decide(
            clusterProjectionRequested = false,
            hudIndependentRequested = false,
            hudSpecKnown = false,
            clusterSpec = null,
        )

        assertEquals(AltScreenSlotDecision.None, decision)
    }

    @Test
    fun `cluster projection request does not invent an ALT slot`() {
        val decision = policy().decide(
            clusterProjectionRequested = true,
            hudIndependentRequested = false,
            hudSpecKnown = false,
            clusterSpec = null,
        )

        assertEquals(AltScreenSlotDecision.None, decision)
    }

    @Test
    fun `no HUD request with cluster spec is ClusterOnly`() {
        val decision = policy().decide(
            clusterProjectionRequested = true,
            hudIndependentRequested = false,
            hudSpecKnown = true,
            clusterSpec = clusterSpec,
        )

        assertEquals(AltScreenSlotDecision.ClusterOnly(clusterSpec), decision)
    }

    @Test
    fun `default product mode shares the existing 111 spec`() {
        val decision = policy().decide(
            clusterProjectionRequested = true,
            hudIndependentRequested = true,
            hudSpecKnown = true,
            clusterSpec = clusterSpec,
        )

        assertEquals(AltScreenSlotDecision.Shared111(clusterSpec), decision)
    }

    @Test
    fun `shared mode reports missing cluster spec`() {
        val decision = policy().decide(
            clusterProjectionRequested = true,
            hudIndependentRequested = true,
            hudSpecKnown = true,
            clusterSpec = null,
        )

        assertEquals(
            AltScreenSlotDecision.Conflict("CLUSTER_SPEC_UNAVAILABLE"),
            decision,
        )
    }

    @Test
    fun `HUD only mode reports unknown HUD spec`() {
        val decision = policy(AltScreenProductMode.HUD_ONLY_ALLOWED).decide(
            clusterProjectionRequested = false,
            hudIndependentRequested = true,
            hudSpecKnown = false,
            clusterSpec = clusterSpec,
        )

        assertEquals(
            AltScreenSlotDecision.Conflict("HUD_SPEC_NOT_READY"),
            decision,
        )
    }

    @Test
    fun `HUD only mode returns the known cached spec`() {
        val decision = policy(AltScreenProductMode.HUD_ONLY_ALLOWED).decide(
            clusterProjectionRequested = false,
            hudIndependentRequested = true,
            hudSpecKnown = true,
            clusterSpec = clusterSpec,
        )

        assertEquals(AltScreenSlotDecision.HudOnly(clusterSpec), decision)
    }

    @Test
    fun `known HUD flag without a spec is still a conflict`() {
        val decision = policy(AltScreenProductMode.HUD_ONLY_ALLOWED).decide(
            clusterProjectionRequested = false,
            hudIndependentRequested = true,
            hudSpecKnown = true,
            clusterSpec = null,
        )

        assertTrue(
            decision is AltScreenSlotDecision.Conflict &&
                decision.reason == "CLUSTER_SPEC_UNAVAILABLE",
        )
    }

    private fun policy(
        mode: AltScreenProductMode = AltScreenProductMode.SHARED_111_ONLY,
    ) = AltScreenSlotPolicy(mode)
}
