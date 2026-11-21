package com.dbtool.carplay.geely

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Source contract only; does not simulate Android Parcel or real Binder enforcement. */
class HudProtocolIdentityTest {
    private val root = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    private val source = File(root,
        "car/platform-geely/src/main/java/com/dbtool/carplay/geely/GeelyHudBridge.kt").readText()

    @Test fun `binding and token use the synchronized Stewie protocol`() {
        assertTrue(source.contains("private const val ACTION = \"com.stewie.carplay.hudbridge.BIND\""))
        assertTrue(source.contains("private const val DESCRIPTOR = \"com.stewie.carplay.hudbridge.ICarPlayHudBridge\""))
        assertFalse(source.contains("com." + "juyou"))
    }
}
