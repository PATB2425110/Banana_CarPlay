package com.dbtool.carplay.ui.screens

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class PhoneStatusCardTest {
    @Test fun allThreeCardsUseRequestedSizesAndTwo35PixelGaps() {
        assertEquals(100, ImmersiveGlassCardLayout.PHONE_STATUS_HEIGHT)
        assertEquals(550, ImmersiveGlassCardLayout.WIDTH)
        assertEquals(410, ImmersiveGlassCardLayout.phoneStatusTop())
        assertEquals(545, ImmersiveGlassCardLayout.musicTop())
        assertEquals(797, ImmersiveGlassCardLayout.TOP + ImmersiveGlassCardLayout.TOTAL_HEIGHT)
        assertTrue(ImmersiveGlassCardLayout.canFit(1920, 1080))
    }
    @Test fun smallerViewportKeepsUpperCardsWithoutScalingOrOverflow() {
        assertTrue(ImmersiveGlassCardLayout.canFitNavigation(1920, 720))
        assertTrue(ImmersiveGlassCardLayout.canFitPhoneStatus(1920, 720))
        assertFalse(ImmersiveGlassCardLayout.canFit(1920, 720))
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
    @Test fun batteryMovedOffNavigationAndPhoneCardIsReadOnly() {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "shared/src/main").isDirectory }
        val s = File(root, "car/ui/src/main/java/com/dbtool/carplay/ui/screens/ImmersiveGlassCards.kt").readText()
        val navigation = s.substringAfter("val navigation =").substringBefore("if (ImmersiveGlassCardLayout.canFitPhoneStatus")
        assertFalse(navigation.contains("glassPhoneBatteryText("))
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
        val settings = File(root, "car/ui/src/main/java/com/dbtool/carplay/ui/screens/ImmersiveGlassAppearanceSettings.kt").readText()
        assertTrue(settings.contains("三卡片颜色与透明度"))
        assertTrue(settings.contains("settings.copy(immersiveGlassColor ="))
        assertTrue(settings.contains("settings.copy(immersiveGlassTransparency ="))
    }
    @Test fun singleRowHasThreeEqualCenteredGroupsInside100PixelCard() {
        val row = GlassPhoneStatusLayout
        assertEquals(50f, row.CENTER_Y, 0f)
        assertEquals(550f / 3f, row.CELL_WIDTH, 0.0001f)
        assertEquals(550f / 6f, row.SIGNAL_CENTER_X, 0.0001f)
        assertEquals(275f, row.BATTERY_CENTER_X, 0.0001f)
        assertEquals(550f * 5f / 6f, row.CURRENT_CENTER_X, 0.0001f)
        assertEquals(row.CELL_WIDTH, row.BATTERY_CENTER_X - row.SIGNAL_CENTER_X, 0.0001f)
        assertEquals(row.CELL_WIDTH, row.CURRENT_CENTER_X - row.BATTERY_CENTER_X, 0.0001f)
        assertEquals(row.SIGNAL_CENTER_X, row.SIGNAL_LEFT + row.SIGNAL_WIDTH / 2f, 0.0001f)
        assertTrue(row.SIGNAL_LEFT > 0f)
        assertTrue(row.SIGNAL_LEFT + row.SIGNAL_WIDTH < row.CELL_WIDTH)
        assertTrue(row.SIGNAL_BOTTOM - 42f >= 0f)
        assertTrue(row.SIGNAL_BOTTOM < ImmersiveGlassCardLayout.PHONE_STATUS_HEIGHT)
        assertTrue(row.CENTER_Y - row.BATTERY_HEIGHT / 2f > 0f)
        assertTrue(row.CENTER_Y + row.BATTERY_HEIGHT / 2f < ImmersiveGlassCardLayout.PHONE_STATUS_HEIGHT)
    }
    @Test fun variablePercentageWidthKeepsTheWholeBatteryGroupCentered() {
        val row = GlassPhoneStatusLayout
        for (textWidth in listOf(40f, 55f, 70f, 88f)) {
            val left = row.batteryLeft(textWidth)
            val textLeft = row.batteryValueLeft(textWidth)
            val right = textLeft + textWidth
            assertEquals(row.BATTERY_CENTER_X, (left + right) / 2f, 0.0001f)
            assertTrue(left >= row.CELL_WIDTH)
            assertTrue(right <= row.CELL_WIDTH * 2f)
            assertEquals(row.BATTERY_VALUE_GAP, textLeft -
                (left + row.BATTERY_WIDTH + row.BATTERY_TERMINAL_EXTENT), 0.0001f)
        }
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
