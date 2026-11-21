package com.dbtool.carplay.bridge

import com.dbtool.carplay.api.settings.CarSettingsSafeArea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「核心安全区」的**矩形模型**（PATCH-0080，2026-10-11；口径照原版 `GeelyClusterSafeArea`）。
 *
 * ## 为什么钉它
 *
 * 改成矩形模型就是为了让"报出去的 safeArea 高度为负"这种事**从结构上不可能发生**：
 * 矩形先 `normalized()` 收进画布、再 `toInsets()` ⇒ 四边恒 ≥0 且 `top+bottom+h == H`。
 * 这条不变式一旦破了，真车上就是 **iPhone 直接拆会话**（CX11 实测：`heightPixels=-1560`）。
 */
class SafeAreaRectModelTest {

    private val canvasW = 1920
    private val canvasH = 1080

    @Test
    fun `未设置的判据是宽或高非正`() {
        assertTrue(CarSettingsSafeArea().isEmpty)
        assertTrue(CarSettingsSafeArea(x = 10, y = 10, width = 0, height = 500).isEmpty)
        assertFalse(CarSettingsSafeArea(x = 0, y = 0, width = 800, height = 400).isEmpty)
    }

    @Test
    fun `矩形转内边距：四边恒非负且自洽`() {
        val rect = CarSettingsSafeArea(x = 100, y = 60, width = 1200, height = 800)
        val insets = rect.toInsets(canvasW, canvasH)
        assertEquals(100, insets.left)
        assertEquals(60, insets.top)
        assertEquals(1920 - 100 - 1200, insets.right)
        assertEquals(1080 - 60 - 800, insets.bottom)
        // 报给 iPhone 的那两个数（上游按 insets 现算）恒为正。
        assertTrue(1920 - insets.left - insets.right > 0)
        assertTrue(1080 - insets.top - insets.bottom > 0)
    }

    @Test
    fun `坐标越界会被收进画布，不会产出负宽高`() {
        // 正是出事故那组数的形状：坐标远大于画布。
        val wild = CarSettingsSafeArea(x = 1900, y = 1920, width = 5000, height = 720)
        val insets = wild.toInsets(canvasW, canvasH)
        assertTrue("top 必须被收进画布：$insets", insets.top < canvasH)
        assertTrue("高度必须为正：$insets", canvasH - insets.top - insets.bottom > 0)
        assertTrue("宽度必须为正：$insets", canvasW - insets.left - insets.right > 0)
    }

    @Test
    fun `缩放到另一块画布保持比例，且结果仍在目标画布内`() {
        val rect = CarSettingsSafeArea(x = 480, y = 270, width = 960, height = 540) // 主画布 1/4 处
        val onCluster = rect.scaledTo(canvasW, canvasH, 1024, 600)
        assertTrue(onCluster.x in 0..1024)
        assertTrue(onCluster.y in 0..600)
        assertTrue(onCluster.width in 1..1024)
        assertTrue(onCluster.height in 1..600)
        assertTrue(onCluster.x + onCluster.width <= 1024)
        assertTrue(onCluster.y + onCluster.height <= 600)
    }

    @Test
    fun `最小尺寸与画布更小时取画布本身`() {
        // 原版口径：w 下界是 min(300, canvasW)、h 下界是 min(200, canvasH)。
        val tiny = CarSettingsSafeArea(x = 0, y = 0, width = 1, height = 1).normalized(100, 50)
        assertEquals(100, tiny.width)
        assertEquals(50, tiny.height)
    }
}
