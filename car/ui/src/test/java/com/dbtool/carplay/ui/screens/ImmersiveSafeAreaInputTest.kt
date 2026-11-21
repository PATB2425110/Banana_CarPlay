package com.dbtool.carplay.ui.screens

import com.dbtool.carplay.api.settings.CarSettingsSafeArea
import org.junit.Assert.*
import org.junit.Test

class ImmersiveSafeAreaInputTest {
    @Test fun exactRectangleUsesFull1080Canvas() {
        assertEquals(CarSettingsSafeArea(100, 120, 1600, 900), immersiveSafeAreaFromText("100", "120", "1600", "900"))
    }
    @Test fun fullScreenIs1920By1080NotInstrument720() {
        assertEquals(CarSettingsSafeArea(0, 0, 1920, 1080), immersiveSafeAreaFromText("0", "0", "1920", "1080"))
    }
    @Test fun oversizedRectangleIsClampedBeforePosition() {
        assertEquals(CarSettingsSafeArea(0, 0, 1920, 1080), immersiveSafeAreaFromText("9999", "9999", "9999", "9999"))
    }
    @Test fun minSizeMatchesExistingRectangleEditorPolicy() {
        assertEquals(CarSettingsSafeArea(1620, 880, 300, 200), immersiveSafeAreaFromText("1900", "1000", "1", "1"))
    }
    @Test fun incompleteInvalidAndOverflowInputNeverChangesSavedArea() {
        for (v in listOf("", "abc", "2147483648")) assertNull(immersiveSafeAreaFromText(v, "0", "1000", "800"))
        assertNull(immersiveSafeAreaFromText("-1", "0", "1000", "800"))
        assertNull(immersiveSafeAreaFromText("0", "0", "0", "800"))
        assertNull(immersiveSafeAreaFromText("0", "0", "1000", "-1"))
    }
}
