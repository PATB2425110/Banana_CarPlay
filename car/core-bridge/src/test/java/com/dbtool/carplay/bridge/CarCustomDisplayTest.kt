package com.dbtool.carplay.bridge

import com.dbtool.carplay.api.display.CarCustomDisplay
import com.dbtool.carplay.api.display.CarCustomDisplay.Problem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「自定义分辨率」判据与换算的单测（2026-09-30）。
 *
 * ## 为什么这几条值得钉
 *
 * 1. **取偶**：H.264/HEVC 的 4:2:0 要求宽高为 2 的倍数。取偶写错的症状是
 *    "在别人机器上编不过"，我们自己的车机上可能一辈子碰不到。
 * 2. **DPI → 物理尺寸**：CarPlay 协议里**没有 dpi 字段**，密度是靠物理尺寸表达的。
 *    这条换算错了，用户看到的不是"设置没生效"，而是"界面元素大小不对"——
 *    既难归因、又难描述。
 * 3. **空输入不判错**：输入框清空是编辑中间态，判错会让用户边删边看红框。
 */
class CarCustomDisplayTest {

    // ---------------------------------------------------------------- 取偶

    @Test
    fun `alignEven 向下取偶`() {
        assertEquals(1920, CarCustomDisplay.alignEven(1921))
        assertEquals(1920, CarCustomDisplay.alignEven(1920))
        assertEquals(1080, CarCustomDisplay.alignEven(1081))
        assertEquals(2, CarCustomDisplay.alignEven(2))
    }

    @Test
    fun `alignEven 对非正数返回 0（不抛异常，调用方靠夹取兜底）`() {
        assertEquals(0, CarCustomDisplay.alignEven(0))
        assertEquals(0, CarCustomDisplay.alignEven(-5))
    }

    // ---------------------------------------------------------------- 越界判据

    @Test
    fun `像素边界：319 判小、320 合法、4096 合法、4097 判大`() {
        assertEquals(Problem.TOO_SMALL, CarCustomDisplay.problemOf(319))
        assertNull(CarCustomDisplay.problemOf(320))
        assertNull(CarCustomDisplay.problemOf(4096))
        assertEquals(Problem.TOO_LARGE, CarCustomDisplay.problemOf(4097))
    }

    @Test
    fun `dpi 边界：59 判小、60 合法、480 合法、481 判大`() {
        assertEquals(Problem.TOO_SMALL, CarCustomDisplay.dpiProblemOf(59))
        assertNull(CarCustomDisplay.dpiProblemOf(60))
        assertNull(CarCustomDisplay.dpiProblemOf(480))
        assertEquals(Problem.TOO_LARGE, CarCustomDisplay.dpiProblemOf(481))
    }

    @Test
    fun `空输入不判错——那是编辑中间态，不是错误`() {
        assertNull(CarCustomDisplay.problemOf(null))
        assertNull(CarCustomDisplay.dpiProblemOf(null))
    }

    // ------------------------------------------------------------ DPI → 毫米

    /**
     * 出厂值那组数的换算结果。手算：`1920 / 160 * 25.4 = 304.8 → 305`，
     * `1080 / 160 * 25.4 = 171.45 → 171`。
     */
    @Test
    fun `1920x1080 在 160dpi 下是 305x171 毫米`() {
        val size = CarCustomDisplay.physicalSizeMm(1920, 1080, 160)
        assertEquals(305, size.widthMm)
        assertEquals(171, size.heightMm)
    }

    @Test
    fun `dpi 越高物理尺寸越小——这正是界面元素变小的机制`() {
        val low = CarCustomDisplay.physicalSizeMm(1920, 1080, 120)
        val high = CarCustomDisplay.physicalSizeMm(1920, 1080, 240)
        assertTrue("120dpi 应比 240dpi 报出更大的屏", low.widthMm > high.widthMm)
        assertEquals(406, low.widthMm) // 1920 / 120 * 25.4 = 406.4
        assertEquals(203, high.widthMm) // 1920 / 240 * 25.4 = 203.2
    }

    @Test
    fun `物理尺寸被夹到上游区间 1 到 2000 毫米`() {
        // 极端值：5 像素 @480dpi = 0.26 mm ⇒ 取整成 0，必须被抬到下限 1。
        val tiny = CarCustomDisplay.physicalSizeMm(5, 5, 480)
        assertEquals(1, tiny.widthMm)
        assertEquals(1, tiny.heightMm)
        // dpi 越界时按上限夹取，不会算出负值或除零。
        val clamped = CarCustomDisplay.physicalSizeMm(4096, 4096, 1)
        assertEquals(1734, clamped.widthMm) // 4096 / 60 * 25.4 = 1733.97
    }

    // ---------------------------------------------------------------- resolve

    @Test
    fun `resolve 对出厂值原样返回，只做换算`() {
        val resolved = CarCustomDisplay.resolve(1920, 1080, 160)
        assertEquals(1920, resolved.widthPx)
        assertEquals(1080, resolved.heightPx)
        assertEquals(305, resolved.widthMm)
        assertEquals(171, resolved.heightMm)
    }

    @Test
    fun `resolve 把奇数取偶后再换算`() {
        val resolved = CarCustomDisplay.resolve(1921, 1081, 160)
        assertEquals(1920, resolved.widthPx)
        assertEquals(1080, resolved.heightPx)
    }

    @Test
    fun `resolve 把越界值夹回区间——老安装里的脏值不会送到 iPhone`() {
        val small = CarCustomDisplay.resolve(100, 100, 160)
        assertEquals(CarCustomDisplay.MIN_EDGE, small.widthPx)
        assertEquals(CarCustomDisplay.MIN_EDGE, small.heightPx)

        val large = CarCustomDisplay.resolve(9999, 9999, 160)
        assertEquals(CarCustomDisplay.MAX_EDGE, large.widthPx)
        assertEquals(CarCustomDisplay.MAX_EDGE, large.heightPx)

        val badDpi = CarCustomDisplay.resolve(1920, 1080, 5)
        // dpi 被夹到 60 ⇒ 1920 / 60 * 25.4 = 812.8 → 813
        assertEquals(813, badDpi.widthMm)
    }

    @Test
    fun `宽高独立取偶，互不影响`() {
        val resolved = CarCustomDisplay.resolve(1921, 720, 160)
        assertEquals(1920, resolved.widthPx)
        assertEquals(720, resolved.heightPx)
    }

    // ---------------------------------------------------------------- 文案

    @Test
    fun `越界文案要说清差多少`() {
        val text = CarCustomDisplay.describe(Problem.TOO_SMALL, "宽度", 200)
        assertTrue("文案里要有当前值：$text", text.contains("200"))
        assertTrue("文案里要有下限：$text", text.contains("320"))
    }

    @Test
    fun `dpi 文案要说清区间`() {
        val text = CarCustomDisplay.describeDpi(Problem.TOO_LARGE, 999)
        assertTrue("文案里要有当前值：$text", text.contains("999"))
        assertTrue("文案里要有上限：$text", text.contains("480"))
    }

    // ---------------------------------------------------------------- 其它

    @Test
    fun `isNumeric 只认纯数字`() {
        assertTrue(CarCustomDisplay.isNumeric("1920"))
        assertTrue(CarCustomDisplay.isNumeric(""))
        assertTrue(!CarCustomDisplay.isNumeric("19x0"))
        assertTrue(!CarCustomDisplay.isNumeric("-1"))
    }
}
