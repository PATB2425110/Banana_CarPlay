package com.shilapi.xcertplay.network

import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class CarPlayTransportCleanupTest {
    @Test fun tunnelClosesBeforeOtherResources() {
        val steps = mutableListOf<String>()
        closeCarPlayTransportResources(
            { steps += "tun" }, { steps += "server" }, { steps += "sessions" }, { steps += "bridge" },
            { _, error -> throw AssertionError(error) },
        )
        assertEquals(listOf("tun", "server", "sessions", "bridge"), steps)
    }

    @Test fun allResourcesAreAttemptedEvenIfEveryCloseFails() {
        val failures = mutableListOf<String>()
        var calls = 0
        val close = { calls++; throw IOException("close failure") }
        closeCarPlayTransportResources(close, close, close, close, { name, _ -> failures += name })
        assertEquals(4, calls)
        assertEquals(listOf("VPN tunnel", "AirPlay listener", "AirPlay sessions", "NCM bridge"), failures)
    }

    @Test fun bridgeFailureCannotPreventVpnRelease() {
        var tunnelClosed = false
        var failure: String? = null
        closeCarPlayTransportResources(
            { tunnelClosed = true }, {}, {}, { check(tunnelClosed); throw IllegalStateException("USB gone") },
            { name, _ -> failure = name },
        )
        assertTrue(tunnelClosed)
        assertEquals("NCM bridge", failure)
    }

    private fun source(relative: String): String {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "shared/src/main").isDirectory }
        return File(root, "shared/src/main/java/com/shilapi/xcertplay/$relative").readText()
    }

    @Test fun wirelessAttachCleansStaleTunAndNeverEstablishesVpn() {
        val service = source("network/CarPlayVpnService.kt")
        val wireless = service.substringAfter("fun attachWireless(").substringBefore("fun detach()")
        assertTrue(wireless.contains("active.get() || tun != null || bridge != null"))
        assertTrue(wireless.indexOf("releaseLocked()") < wireless.indexOf("startAirPlayServer("))
        assertFalse(wireless.contains(".establish()"))
        val controller = source("orchestration/CarPlayController.kt")
        val start = controller.substringAfter("private fun runWireless(generation:")
        assertTrue(start.indexOf("detachWired()") >= 0)
        assertTrue(start.indexOf("detachWired()") < start.indexOf("startWirelessHotspot("))
    }

    @Test fun detachAndSessionEndCleanupAreWiredAndGenerationScoped() {
        val controller = source("orchestration/CarPlayController.kt")
        assertTrue(controller.contains("registerDetachReceiver"))
        assertTrue(controller.contains("device.deviceName == wiredDeviceName"))
        val service = source("network/CarPlayVpnService.kt")
        assertTrue(service.contains("generation != attachGeneration || tun == null"))
        assertTrue(service.contains("if (sessionWasActive) releaseEndedWiredSession(generation)"))
        assertTrue(service.contains("override fun onRevoke()"))
    }

    @Test fun usbVpnDoesNotBlockUnderlyingIpv4DnsOrAddAnInternetRoute() {
        val service = source("network/CarPlayVpnService.kt")
        val builder = service.substringAfter("val tunFd = Builder()").substringBefore("tun = tunFd")
        assertTrue(builder.contains(".allowFamily(OsConstants.AF_INET)"))
        assertTrue(builder.contains(".addRoute(LINK_LOCAL_ROUTE, LINK_PREFIX)"))
        assertEquals(1, Regex("\\.addRoute\\(").findAll(builder).count())
        assertFalse(builder.contains(".addDnsServer("))
        assertFalse(builder.contains("\"0.0.0.0\""))
        assertFalse(builder.contains("\"::\""))
        assertTrue(builder.indexOf(".allowFamily(") < builder.indexOf(".establish()"))
    }
}
