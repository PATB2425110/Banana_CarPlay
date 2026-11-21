package com.dbtool.carplay.bridge.developer

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class CarDiagnosticEcarxContractTest {
    @Test
    fun `wifi direct diagnostic includes runtime Ecarx decision and role separated snapshot`() {
        val candidates = listOf(
            File("src/main/java/com/dbtool/carplay/bridge/developer/CarDiagnosticPackager.kt"),
            File("../src/main/java/com/dbtool/carplay/bridge/developer/CarDiagnosticPackager.kt"),
            File("../../src/main/java/com/dbtool/carplay/bridge/developer/CarDiagnosticPackager.kt"),
        )
        val source = candidates.firstOrNull(File::exists)?.readText()
            ?: error("CarDiagnosticPackager.kt not found from ${File(".").absolutePath}")

        assertTrue(source.contains("EcarxHotspotDiagnosticSummary.render("))
        // 只钉方法名：源码里 receiver 在上一行（`…CarHotspotEnabledReader` 换行后 `.readStandardWifiApState`），
        // 写全限定名会因为换行而匹配不到 —— 那是格式，不是契约。
        assertTrue(source.contains("readStandardWifiApState(appContext)"))
        assertTrue(source.contains("EcarxHotspotSessionObservation.latest"))
        assertTrue(source.contains("Current local interface inventory:"))
        assertTrue(source.contains("Read-only OEM route table"))
        assertTrue(source.contains("configuredGateway = configuredGateway"))
        assertTrue(source.contains("observation = observation"))
        assertTrue(source.contains("includeClientIpv4 = includeClientIpv4"))
        assertTrue(source.contains("airplay_tcp_evidence.txt"))
        assertTrue(source.contains("AirPlayTcpCaptureDiagnosticSummary.render"))
    }
}
