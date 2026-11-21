package com.dbtool.carplay.ui.screens

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class GlassNavigationEmptyStateTest {
    private fun source(): String {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "shared/src/main").isDirectory }
        return File(root, "car/ui/src/main/java/com/dbtool/carplay/ui/screens/ImmersiveGlassCards.kt").readText()
    }

    @Test fun inactiveCardOnlyDrawsCenteredWaitingMessage() {
        val s = source()
        val inactive = s.substringAfter("if (!active) {").substringBefore("} else {")
        assertEquals("drawNavigationWaiting(c)", inactive.trim())
        val waiting = s.substringAfter("private fun drawNavigationWaiting(").substringBefore("/** Fixed row")
        assertTrue(waiting.contains("Paint.Align.CENTER"))
        assertTrue(waiting.contains("150f - (metrics.ascent + metrics.descent) / 2f"))
        assertTrue(waiting.contains("c.drawText(\"等待导航信息\", 275f, baseline, p)"))
        assertFalse(s.contains("\"未导航\""))
        assertFalse(s.contains("\"导航中\""))
        assertFalse(waiting.contains("navigation.arrival"))
        assertFalse(waiting.contains("navigation.remaining"))
    }
}
