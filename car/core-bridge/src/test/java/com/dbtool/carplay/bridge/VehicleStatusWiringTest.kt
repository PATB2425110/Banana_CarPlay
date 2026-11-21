package com.dbtool.carplay.bridge

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Wiring regression guards, not a claim of iPhone/vehicle integration testing. */
class VehicleStatusWiringTest {
    private fun source(path: String): String {
        val root = generateSequence(File(".").canonicalFile) { it.parentFile }
            .first { File(it, "shared/src/main").isDirectory }
        return File(root, path).readText()
    }

    @Test fun bothControlLoopsHandleSubscriptionAndAlwaysReleaseTheirOwnLease() {
        for (name in listOf("Iap2WiredControlClient", "Iap2WirelessControlClient")) {
            val code = source("shared/src/main/java/com/shilapi/xcertplay/transport/$name.kt")
            assertTrue(code.contains("identification.copy(vehicleStatusUpdatesEnabled = vehicleStatusProvider != null)"))
            assertTrue(code.contains("Iap2VehicleStatusClient(vehicleStatusProvider, ::sendDuringControl, onProgress)"))
            assertTrue(code.contains("vehicleStatus.poll()"))
            assertTrue(code.contains("locationActive || vehicleStatus.active"))
            assertTrue(code.contains("if (vehicleStatus.accept(incoming)) continue"))
            assertTrue(code.contains("finally {\n            vehicleStatus.close()"))
            assertTrue(code.indexOf("mfi.run(") < code.indexOf("pendingVehicleStatus?.take()"))
            assertTrue(code.contains("deferVehicleStatus = pendingVehicleStatus?.let { it::accept }"))
        }
    }

    @Test fun controllerPassesProviderToWiredRfcommAndTunnelAndClosesSessionProvider() {
        val code = source("shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt")
        assertEquals(3, Regex("vehicleStatusProvider = vehicleStatusProvider").findAll(code).count())
        assertTrue(code.contains("closeBestEffort(\"vehicle status provider\") { vehicleStatusProvider?.close() }"))
        val bridge = source("car/core-bridge/src/main/java/com/dbtool/carplay/bridge/CarPlaySessionBridge.kt")
        assertTrue(bridge.contains("vehicleStatusProvider = platform.vehicleStatus?.takeIf { it.enabled }?.let { source ->"))
        assertTrue(bridge.contains("UpstreamVehicleStatusBridge(source)"))
    }

    @Test fun sourceIsOptionalAndCoreHasNoOemOrThemeFallback() {
        assertTrue(source("car/api/src/main/java/com/dbtool/carplay/api/CarPlatform.kt")
            .contains("CarVehicleStatusSource? get() = null"))
        assertTrue(source("car/app/proguard-rules.pro")
            .contains("-keepnames class com.dbtool.carplay.geely.vehicle.**"))
        val code = source("shared/src/main/java/com/shilapi/xcertplay/transport/Iap2VehicleStatusClient.kt")
        for (forbidden in listOf("BatteryManager", "TelephonyManager", "Configuration", "ecarx", "setProperty")) {
            assertFalse(forbidden, code.contains(forbidden))
        }
        assertFalse(code.contains("Thread("))
    }

    @Test fun vehiclePermissionIsOnlyDeclaredAndCarFrameworkIsOptional() {
        val manifest = source("car/app/src/main/AndroidManifest.xml")
        assertTrue(manifest.contains("<uses-permission android:name=\"android.car.permission.CAR_VENDOR_EXTENSION\" />"))
        assertTrue(manifest.contains("<uses-library android:name=\"android.car\" android:required=\"false\" />"))
        assertFalse(manifest.contains("android:sharedUserId"))
        assertFalse(manifest.contains("REQUEST_INSTALL_PACKAGES"))
        assertTrue(manifest.contains("android:icon=\"@mipmap/ic_launcher\""))
    }
}
