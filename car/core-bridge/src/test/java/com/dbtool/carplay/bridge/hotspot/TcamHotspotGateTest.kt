package com.dbtool.carplay.bridge.hotspot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TcamHotspotGateTest {

    private fun snapshot(
        state: Int? = null,
        enabled: Boolean? = null,
        clients: Int = 0,
    ) = EcarxWifiApSnapshot(
        state = state,
        enabled = enabled,
        clients = List(clients) { index ->
            EcarxWifiApClient(ip = "192.168.15.${index + 10}", mac = null, name = null)
        },
    )

    @Test
    fun `vendor state 13 with legacy false is not blocked`() {
        assertNull(TcamHotspotGate.blockReason(snapshot(state = 13), false))
    }

    @Test
    fun `vendor state 13 with legacy true is not blocked`() {
        assertNull(TcamHotspotGate.blockReason(snapshot(state = 13), true))
    }

    @Test
    fun `vendor state 11 without clients is blocked with state in reason`() {
        val reason = TcamHotspotGate.blockReason(snapshot(state = 11), false)

        assertTrue("expected a blocking reason, got $reason", reason != null)
        assertTrue("reason should carry vendor state 11, got $reason", reason!!.contains("11"))
    }

    @Test
    fun `vendor state 11 with one client is not blocked`() {
        assertNull(TcamHotspotGate.blockReason(snapshot(state = 11, clients = 1), false))
    }

    @Test
    fun `vendor state 12 enabling is not blocked`() {
        assertNull(TcamHotspotGate.blockReason(snapshot(state = 12), false))
    }

    @Test
    fun `vendor state 14 without clients is blocked`() {
        val reason = TcamHotspotGate.blockReason(snapshot(state = 14), true)

        assertTrue("expected a blocking reason, got $reason", reason != null)
        assertTrue("reason should carry vendor state 14, got $reason", reason!!.contains("14"))
    }

    @Test
    fun `unavailable vendor snapshot degrades open with legacy false`() {
        assertNull(TcamHotspotGate.blockReason(null, false))
    }

    @Test
    fun `unavailable vendor snapshot degrades open with legacy true`() {
        assertNull(TcamHotspotGate.blockReason(null, true))
    }

    @Test
    fun `vendor state 10 disabling without clients is blocked`() {
        val reason = TcamHotspotGate.blockReason(snapshot(state = 10), false)

        assertEquals("TCAM hotspot is off (vendor state=10)", reason)
    }

    @Test
    fun `vendor enabled true overrides an off state code`() {
        assertNull(TcamHotspotGate.blockReason(snapshot(state = 11, enabled = true), false))
    }
}
