package com.dbtool.carplay.ui.screens

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class PhoneStatusCardTest {
    private fun source(relative: String): String {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "shared/src/main").isDirectory }
        return File(root, relative).readText()
    }

    @Test fun phoneCardRemovedAndMusicStartsAtItsOldPositionWithOne35PixelGap() {
        assertEquals(550, ImmersiveGlassCardLayout.WIDTH)
        assertEquals(300, ImmersiveGlassCardLayout.HEIGHT)
        assertEquals(252, ImmersiveGlassCardLayout.MUSIC_HEIGHT)
        assertEquals(410, ImmersiveGlassCardLayout.musicTop())
        assertEquals(35, ImmersiveGlassCardLayout.musicTop() -
            (ImmersiveGlassCardLayout.TOP + ImmersiveGlassCardLayout.HEIGHT))
        assertEquals(662, ImmersiveGlassCardLayout.TOP + ImmersiveGlassCardLayout.TOTAL_HEIGHT)
        assertTrue(ImmersiveGlassCardLayout.canFit(1920, 1080))
    }
    @Test fun smallerViewportKeepsNavigationInsetAndCanNowFitBothCardsAt720() {
        assertTrue(ImmersiveGlassCardLayout.canFitNavigation(1920, 720))
        assertTrue(ImmersiveGlassCardLayout.canFit(1920, 720))
        assertTrue(ImmersiveGlassCardLayout.canFitNavigation(610, 375))
        assertFalse(ImmersiveGlassCardLayout.canFit(610, 661))
        assertTrue(ImmersiveGlassCardLayout.canFit(610, 662))
    }
    @Test fun fiveBarsMatchActualStrengthIncludingZeroAndUnknown() {
        for (n in 0..5) {
            assertEquals(n, glassPhoneSignalBars(n).count { it })
        }
        for (n in listOf(null, -1, 6)) {
            assertTrue(glassPhoneSignalBars(n).none { it })
        }
    }
    @Test fun ampsUseExactConversionAndBatteryColorRemains() {
        assertEquals("0.5A", glassPhoneCurrentText(500))
        assertEquals("0A", glassPhoneCurrentText(0))
        assertEquals("0.001A", glassPhoneCurrentText(1))
        assertEquals("1A", glassPhoneCurrentText(1000))
        assertEquals("2.4A", glassPhoneCurrentText(2400))
        assertEquals("65.535A", glassPhoneCurrentText(65535))
        assertEquals("—A", glassPhoneCurrentText(null))
        assertEquals("—A", glassPhoneCurrentText(-1))
        assertEquals("—A", glassPhoneCurrentText(65536))
        assertEquals("47%", glassPhoneBatteryText(47))
        assertEquals(0xff4cdb78.toInt(), glassPhonePowerColor(47, 1))
        assertEquals(0xffff5252.toInt(), glassPhonePowerColor(19, 1))
    }
    @Test fun statusIsInsideNavigationReadOnlyAndNoLongerDrawsCurrentOrThirdBackground() {
        val s = source("car/ui/src/main/java/com/dbtool/carplay/ui/screens/ImmersiveGlassCards.kt")
        val navigation = s.substringAfter("val navigation =").substringBefore("val musicTop =")
        assertTrue(navigation.contains("drawPhoneStatus(c, model)"))
        assertTrue(navigation.contains("glassPhoneBatteryText("))
        assertFalse(s.contains("canFitPhoneStatus"))
        assertFalse(s.contains("PHONE_STATUS_HEIGHT"))
        assertEquals(2, Regex("""glass\(c, model\.settings\.immersiveGlassColor""").findAll(s).count())
        val status = s.substringAfter("private fun drawPhoneStatus(").substringBefore("/** Empty state")
        assertTrue(status.contains("glassPhoneBatteryText("))
        assertTrue(status.contains("drawPhoneBattery("))
        for (removed in listOf("\"手机状态\"", "\"手机信号\"", "\"最大取电电流\"", "/5格",
            "glassPhoneSignalText(", "c.drawLine(")) {
            assertFalse(removed, status.contains(removed))
        }
        assertFalse(status.contains("clickable"))
        assertFalse(status.contains("BatteryManager"))
        assertFalse(status.contains("TelephonyManager"))
        assertFalse(status.contains("glassPhoneCurrentText("))
        assertFalse(status.contains("phoneMaxCurrentDrawnMa"))
        assertTrue(status.contains("c.scale(GlassPhoneStatusLayout.SIGNAL_SCALE"))
        assertTrue(status.contains("c.scale(GlassPhoneStatusLayout.BATTERY_SCALE"))
        val settings = source("car/ui/src/main/java/com/dbtool/carplay/ui/screens/ImmersiveGlassAppearanceSettings.kt")
        assertTrue(settings.contains("卡片颜色与透明度"))
        assertFalse(settings.contains("三卡片"))
        assertTrue(settings.contains("settings.copy(immersiveGlassColor ="))
        assertTrue(settings.contains("settings.copy(immersiveGlassTransparency ="))
    }
    @Test fun twoRightAlignedRowsUseExactRequestedScalesInsideNavigation() {
        val row = GlassPhoneStatusLayout
        assertEquals(0.7f, row.SIGNAL_SCALE, 0f)
        assertEquals(0.8f, row.BATTERY_SCALE, 0f)
        assertEquals(71.4f, row.SIGNAL_WIDTH * row.SIGNAL_SCALE, 0.0001f)
        assertEquals(29.4f, row.SIGNAL_HEIGHT * row.SIGNAL_SCALE, 0.0001f)
        assertEquals(44.8f, row.BATTERY_WIDTH * row.BATTERY_SCALE, 0.0001f)
        assertEquals(24f, row.BATTERY_HEIGHT * row.BATTERY_SCALE, 0.0001f)
        assertEquals(25.6f, row.VALUE_TEXT_SIZE * row.BATTERY_SCALE, 0.0001f)
        assertEquals(row.RIGHT, row.SIGNAL_LEFT + row.SIGNAL_WIDTH * row.SIGNAL_SCALE, 0.0001f)
        val signalBottom = row.SIGNAL_TOP + row.SIGNAL_HEIGHT * row.SIGNAL_SCALE
        val batteryTop = row.BATTERY_CENTER_Y - row.BATTERY_HEIGHT * row.BATTERY_SCALE / 2f
        assertTrue(row.SIGNAL_TOP > 0f)
        assertTrue(signalBottom < batteryTop)
        assertTrue(row.BATTERY_CENTER_Y + row.BATTERY_HEIGHT * row.BATTERY_SCALE / 2f < 105f)
        assertTrue(row.RIGHT < ImmersiveGlassCardLayout.WIDTH)
    }
    @Test fun variablePercentageWidthKeepsWholeScaledBatteryGroupRightAlignedAndClearOfDistance() {
        val row = GlassPhoneStatusLayout
        for (textWidth in listOf(40f, 55f, 70f, 88f)) {
            val left = row.batteryLeft(textWidth)
            val textLeft = row.batteryValueLeft(textWidth)
            val right = textLeft + textWidth * row.BATTERY_SCALE
            assertEquals(row.RIGHT, right, 0.0001f)
            assertTrue(left > 370f)
            assertEquals(row.BATTERY_VALUE_GAP * row.BATTERY_SCALE, textLeft -
                (left + (row.BATTERY_WIDTH + row.BATTERY_TERMINAL_EXTENT) * row.BATTERY_SCALE), 0.0001f)
            val distanceRight = ImmersiveGlassCardLayout.INSTRUCTION_LEFT + row.distanceMaxWidth(textWidth)
            assertEquals(row.DISTANCE_GAP, minOf(left, row.SIGNAL_LEFT) - distanceRight, 0.0001f)
        }
    }
    @Test fun arrowAlignmentMeasuresRasterInkAndRoadTextNotJustDrawableBounds() {
        val s = source("car/ui/src/main/java/com/dbtool/carplay/ui/screens/ImmersiveGlassCards.kt")
        assertTrue(s.contains("val roadBottom = text(c, navigation.road"))
        assertTrue(s.contains("p.getTextBounds(result, 0, result.length, ink)"))
        assertTrue(s.contains("return y + ink.bottom"))
        val painter = s.substringAfter("private class OsnIconPainter")
        assertTrue(painter.contains("icons.getOrPut(resource)"))
        assertTrue(painter.contains("bitmap.getPixels("))
        assertTrue(painter.contains("ImmersiveGlassCardLayout.arrowTop(roadInkBottom, icon.inkBottom)"))
        val distance = s.substringAfter("private fun distanceText(").substringBefore("private fun centered(")
        assertTrue(distance.contains("ImmersiveGlassCardLayout.distanceTextSize("))
        assertTrue(distance.contains("c.drawText(value,"))
        assertFalse(distance.contains("breakText"))
    }
    @Test fun settingsDescribeTwoCardsAndNoLongerPromiseAVisibleCurrentCard() {
        val settings = source("car/ui/src/main/java/com/dbtool/carplay/ui/screens/SettingsScreen.kt")
        assertTrue(settings.contains("title = \"液态玻璃导航与音乐卡片\""))
        assertTrue(settings.contains("完整双卡需要至少662像素高度"))
        assertTrue(settings.contains("信号0.7倍"))
        assertTrue(settings.contains("百分比0.8倍"))
        assertFalse(settings.contains("手机卡550×100"))
        assertFalse(settings.contains("全部卡片需要至少797"))
    }
    @Test fun batteryFillNeverUsesZeroForAnUnknownReading() {
        assertNull(glassPhoneBatteryFill(null))
        assertNull(glassPhoneBatteryFill(-1))
        assertNull(glassPhoneBatteryFill(101))
        assertEquals(0f, glassPhoneBatteryFill(0)!!, 0f)
        assertEquals(0.47f, glassPhoneBatteryFill(47)!!, 0.0001f)
        assertEquals(1f, glassPhoneBatteryFill(100)!!, 0f)
    }
}
