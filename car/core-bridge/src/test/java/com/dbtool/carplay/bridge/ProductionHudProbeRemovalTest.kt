package com.dbtool.carplay.bridge

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ProductionHudProbeRemovalTest {
    @Test fun productionHasNoProbeClassOrStartupCall() {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "shared/src/main").isDirectory }
        val activity = File(root, "car/app/src/main/java/com/dbtool/carplay/app/MainActivity.kt").readText()
        assertFalse(activity.contains("HudOverlayProbe"))
        assertFalse(File(root, "car/platform-geely/src/main/java/com/dbtool/carplay/geely/hud/HudOverlayProbe.kt").exists())
    }
    @Test fun allThreeCardBackgroundsUseOneSettingAndNoLiteralOldProgressGeometry() {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "shared/src/main").isDirectory }
        val cards = File(root, "car/ui/src/main/java/com/dbtool/carplay/ui/screens/ImmersiveGlassCards.kt").readText()
        assertEquals(3, Regex("glass\\(c, model.settings.immersiveGlassColor, model.settings.immersiveGlassTransparency")
            .findAll(cards).count())
        assertFalse(cards.contains("RectF(32f, 208f"))
    }
}
