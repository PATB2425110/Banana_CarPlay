package com.dbtool.carplay.bridge

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class UsbVpnInternetIsolationTest {
    private fun source(path: String): String {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "shared/src/main").isDirectory }
        return File(root, path).readText()
    }
    @Test fun vpnIsAppOnlyAndPermitsExplicitInternetConnections() {
        val s = source("shared/src/main/java/com/shilapi/xcertplay/network/CarPlayVpnService.kt")
        assertTrue(s.contains(".addAllowedApplication(packageName)"))
        assertTrue(s.contains(".allowBypass()"))
        assertTrue(s.contains(".allowFamily(OsConstants.AF_INET)"))
        assertFalse(s.contains(".addDisallowedApplication("))
        assertFalse(s.contains(".addDnsServer("))
        assertTrue(s.contains("CarPlayInternetConnection.usbVpnActive = true"))
        assertTrue(s.contains("if (oldTun != null) CarPlayInternetConnection.usbVpnActive = false"))
    }
    @Test fun onlyOurActiveUsbVpnUsesPerConnectionNotVpnInternetNetwork() {
        val s = source("shared/src/main/java/com/shilapi/xcertplay/network/CarPlayInternetConnection.kt")
        assertTrue(s.contains("if (!usbVpnActive) return url.openConnection()"))
        assertTrue(s.contains("NET_CAPABILITY_NOT_VPN"))
        assertTrue(s.contains("NET_CAPABILITY_INTERNET"))
        assertTrue(s.contains("NET_CAPABILITY_VALIDATED"))
        assertTrue(s.contains("underlying?.openConnection(url)"))
        assertFalse(s.contains("bindProcessToNetwork("))
        assertFalse(s.contains("requestNetwork("))
    }
    @Test fun diagnosticAndExistingHttpClientsUseExplicitInternetOpener() {
        for (path in listOf(
            "developer/CarDeviceLogUploader.kt", "developer/CarVehicleProfileReporter.kt",
            "developer/CarDeviceStatusSync.kt", "update/CarUpdateApi.kt", "update/CarApkDownloader.kt",
        )) {
            val s = source("car/core-bridge/src/main/java/com/dbtool/carplay/bridge/$path")
            assertTrue(path, s.contains("CarPlayInternetConnection.open("))
            assertFalse(path, s.contains(".openConnection()"))
        }
    }
}
