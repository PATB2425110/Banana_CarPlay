package com.dbtool.carplay.bridge.hotspot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EcarxHotspotDiagnosticSummaryTest {
    @Test
    fun `diagnostics distinguish configured gateway local route source and selected endpoint`() {
        val snapshot = EcarxHotspotReader.Snapshot(
            enabled = null,
            clientCount = 1,
            addressCount = 1,
            routeCount = 1,
            routeStatus = EcarxHotspotReader.RouteStatus.VERIFIED,
            routeSources = mapOf("eth0.11" to java.net.InetAddress.getByName("198.18.34.15")),
            clientSubnet = "192.168.15.0/24",
            routeProbe = "routes=[eth0.11] routeStatus=VERIFIED clientCount=1 routeSource=local",
            clientIpv4Addresses = listOf("192.168.15.8"),
        )
        val observation = EcarxHotspotSessionObservation.Observation(
            observedAtEpochMillis = 1234,
            sdkEnabled = null,
            routeStatus = EcarxHotspotReader.RouteStatus.VERIFIED,
            routeCount = 1,
            configuredHost = "192.168.15.1",
            decisionReason = EcarxHotspotAttachmentReason.VERIFIED_CLIENT_ROUTE,
            selectedInterface = "eth0.11",
            routeSourceAddress = "198.18.34.15",
            advertisedEndpoint = "198.18.34.15",
        )

        val text = EcarxHotspotDiagnosticSummary.render(
            snapshot = snapshot,
            observation = observation,
            configuredGateway = "192.168.15.1",
            androidWifiApState = 11,
        )

        assertTrue(text.contains("Configured T-Box/AP gateway (user setting): 192.168.15.1"))
        assertTrue(text.contains("Android WifiManager.getWifiApState: 11 (DISABLED)"))
        assertTrue(text.contains("eth0.11=198.18.34.15 (local route source; not AP gateway)"))
        assertTrue(text.contains("selectedAdvertisedEndpoint=198.18.34.15"))
        assertTrue(text.contains("is an external T-Box/AP address"))
        assertTrue(text.contains("Client side network subnet: 192.168.15.0/24"))
        assertTrue(text.contains("Ecarx current client IPv4 (not DHCP lease history): (withheld; per-package opt-in is off)"))
        assertTrue(text.contains("DHCP lease details (lease time/options): (not collected"))
        assertTrue(text.contains("Local kernel route probe (this car only): routes=[eth0.11] routeStatus=VERIFIED"))
        assertFalse(text.contains("clientMac"))
        assertFalse(text.contains("passphrase"))
        // 隐私闸门：诊断文本里不得出现具体客户端 IP（网段前缀除外）
        assertFalse(text.contains("192.168.15.8"))
    }

    @Test
    fun `explicit per-package opt-in emits SDK client IPv4 but never MAC`() {
        val text = EcarxHotspotDiagnosticSummary.render(
            snapshot = EcarxHotspotReader.Snapshot(
                clientCount = 1,
                clientIpv4Addresses = listOf("192.168.15.8"),
            ),
            observation = null,
            configuredGateway = null,
            androidWifiApState = null,
            includeClientIpv4 = true,
        )
        assertTrue(text.contains("Ecarx current client IPv4 (not DHCP lease history): 192.168.15.8"))
        assertTrue(text.contains("Client MAC: (never collected into diagnostic output)"))
    }

    @Test
    fun `diagnostics explain no client and missing connection decision`() {
        val text = EcarxHotspotDiagnosticSummary.render(
            snapshot = EcarxHotspotReader.Snapshot(
                routeStatus = EcarxHotspotReader.RouteStatus.NO_CURRENT_CLIENT,
            ),
            observation = null,
            configuredGateway = null,
            androidWifiApState = null,
        )
        assertTrue(text.contains("Ecarx route status: NO_CURRENT_CLIENT"))
        assertTrue(text.contains("Last MANUAL connection decision: (not recorded in this process)"))
        assertTrue(text.contains("configured gateway"))
    }
}
