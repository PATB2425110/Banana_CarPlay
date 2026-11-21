package com.dbtool.carplay.geely

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「用户选屏」判据的单测（2026-10-08）。
 *
 * ## 为什么这几条值得钉
 *
 * 这是一次**现场事故的根治点**：仪表副屏原来是"代码自动挑"，隐含假设
 * 「车上那块仪表屏有且只有一块」。现场推翻该假设 —— 车上有多块非主屏，
 * `firstOrNull()` 抓错了块，用户看到「主屏上出现 1920×720 白块」。
 *
 * 新语义是「**只认用户明确选中的 displayId**，绝不替用户猜」。下面几条
 * 把这条语义钉死，防止以后有人"贴心"地把自动挑加回来。
 */
class GeelyDisplaySelectorTest {

    private fun d(
        id: Int,
        w: Int = 1920,
        h: Int = 1080,
        isDefault: Boolean = false,
        refreshHz: Float = 0f,
        xdpi: Float = 0f,
        ydpi: Float = 0f,
    ) = GeelyDisplayDescriptor(
        id = id,
        widthPixels = w,
        heightPixels = h,
        isDefault = isDefault,
        refreshRate = refreshHz,
        xdpi = xdpi,
        ydpi = ydpi,
    )

    // ---------------------------------------------------------- candidates：列出全部非主屏

    @Test
    fun `candidates 列出所有非主屏，不再按 1920x1080 过滤`() {
        val displays = listOf(
            d(0, isDefault = true),          // 主屏 → 排除
            d(1, 1920, 1080),                // 仪表 1080
            d(2, 1280, 720),                 // 别的副屏（旧实现会漏掉它）
            d(3, 800, 480),                  // 再一块
        )
        val got = GeelyDisplaySelector.candidates(isEcarx = true, displays = displays)
        // 关键：1280x720 与 800x480 都要在列表里 —— 旧实现只留 1920x1080，
        // 用户想要的那块可能根本不出现在下拉里。
        assertEquals(setOf(1, 2, 3), got.map { it.id }.toSet())
        // 2026-10-08 起列表**按「像仪表面板」的程度排序**：800×480（矮屏）排最前。
        assertEquals(3, got.first().id)
    }

    @Test
    fun `candidates 非 eCarx 车机返回空表`() {
        val displays = listOf(d(0, isDefault = true), d(1))
        assertTrue(GeelyDisplaySelector.candidates(isEcarx = false, displays = displays).isEmpty())
    }

    @Test
    fun `candidates 只有主屏时返回空表`() {
        assertTrue(GeelyDisplaySelector.candidates(true, listOf(d(0, isDefault = true))).isEmpty())
    }

    // ---------------------------------------------------------- select：只认用户选的 id

    @Test
    fun `select 命中用户选中的那块副屏`() {
        val displays = listOf(d(0, isDefault = true), d(1), d(2, 1280, 720))
        val got = GeelyDisplaySelector.select(isEcarx = true, displays = displays, requestedId = 2)
        assertEquals(2, got?.id)
    }

    @Test
    fun `select 未选（-1）返回 null，绝不自动挑第一块`() {
        val displays = listOf(d(0, isDefault = true), d(1), d(2))
        // 这是事故的根治点：旧实现在此处会 firstOrNull() 抓到 display 1。
        assertNull(GeelyDisplaySelector.select(true, displays, requestedId = -1))
    }

    @Test
    fun `select 用户选的 id 不在场返回 null，不退化到别的屏`() {
        val displays = listOf(d(0, isDefault = true), d(1), d(2))
        assertNull(GeelyDisplaySelector.select(true, displays, requestedId = 9))
    }

    @Test
    fun `select 不允许选中主屏`() {
        val displays = listOf(d(0, isDefault = true), d(1))
        assertNull(GeelyDisplaySelector.select(true, displays, requestedId = 0))
    }

    @Test
    fun `select 非 eCarx 车机返回 null`() {
        val displays = listOf(d(0, isDefault = true), d(1))
        assertNull(GeelyDisplaySelector.select(false, displays, requestedId = 1))
    }

    @Test
    fun `select 多块 1920x1080 副屏时按 id 精确命中（事故场景）`() {
        // 现场场景：不止一块 1080 的非主屏。旧实现 singleOrNull ⇒ null ⇒ 上层抓错。
        val displays = listOf(d(0, isDefault = true), d(1), d(2), d(3))
        assertEquals(3, GeelyDisplaySelector.select(true, displays, requestedId = 3)?.id)
        assertEquals(1, GeelyDisplaySelector.select(true, displays, requestedId = 1)?.id)
    }

    // ---------------------------------------------------------- selectFailureReason（PATCH-0118）

    @Test
    fun `selectFailureReason 与 select 同判据：命中时无原因，未命中时必有原因`() {
        val displays = listOf(d(0, isDefault = true), d(1), d(2, 1280, 720), d(3, 1024, 274))
        val cases = listOf(
            true to -1, true to 0, true to 1, true to 2, true to 3, true to 9, false to 3,
        )
        for ((ecarx, id) in cases) {
            val selected = GeelyDisplaySelector.select(ecarx, displays, id)
            val reason = GeelyDisplaySelector.selectFailureReason(ecarx, displays, id)
            assertEquals("isEcarx=$ecarx requestedId=$id", selected == null, reason != null)
        }
    }

    @Test
    fun `selectFailureReason 区分三种未命中成因`() {
        val displays = listOf(d(0, isDefault = true), d(1))
        assertTrue(
            "非 eCarx 必须单独报（平台根本不提供副屏）",
            GeelyDisplaySelector.selectFailureReason(false, displays, 1)!!.contains("非 eCarx"),
        )
        assertTrue(
            "未选（-1）必须单独报（去设置页选屏即可）",
            GeelyDisplaySelector.selectFailureReason(true, displays, -1)!!.contains("未选副屏"),
        )
        assertTrue(
            "选中 id 不在场必须单独报（查屏是否上电/被重排）",
            GeelyDisplaySelector.selectFailureReason(true, displays, 9)!!.contains("不在场"),
        )
        assertTrue(
            "选中的是主屏必须单独报（主屏从不允许作仪表副屏）",
            GeelyDisplaySelector.selectFailureReason(true, displays, 0)!!.contains("主屏"),
        )
    }

    @Test
    fun `selectFailureReason 对 id 重复的异常形态报唯一命中失败`() {
        // displayId 在 DisplayManager 里本应唯一；真出现重复时 select 走
        // singleOrNull 返回 null，原因也必须照实说"唯一命中失败"，而不是"不在场"。
        val displays = listOf(d(0, isDefault = true), d(5), d(5, 1280, 720))
        assertNull(GeelyDisplaySelector.select(true, displays, requestedId = 5))
        assertTrue(
            GeelyDisplaySelector.selectFailureReason(true, displays, 5)!!.contains("唯一命中失败"),
        )
    }

    // ---------------------------------------------------------- labelOf

    @Test
    fun `labelOf 区分主副屏并带 id 与分辨率`() {
        assertEquals(
            "主屏 · 宽屏 · 像中控或外接 HDMI · displayId=0 · 1920×1080",
            GeelyDisplaySelector.labelOf(d(0, isDefault = true)),
        )
        assertEquals(
            "副屏 · 宽屏 · 像中控或外接 HDMI · displayId=1 · 1280×720",
            GeelyDisplaySelector.labelOf(d(1, 1280, 720)),
        )
    }

    @Test
    fun `labelOf 把细长矮屏标成像仪表面板（现场 1024x274 场景）`() {
        // 现场这台车的仪表面板：1024×274（宽高比 ≈3.7）。旧标签只吐
        // "副屏 · displayId=3 · 1024×274"，与 HDMI 屏的标签肉眼难分。
        val label = GeelyDisplaySelector.labelOf(d(3, 1024, 274))
        assertTrue(label, label.contains("像仪表面板"))
        assertTrue(label, label.contains("displayId=3"))
        assertTrue(label, label.contains("1024×274"))
    }

    @Test
    fun `labelOf 带 dpi 时补出物理尺寸`() {
        val withDpi = GeelyDisplayDescriptor(
            id = 3, widthPixels = 1024, heightPixels = 274, isDefault = false,
            xdpi = 160f, ydpi = 160f,
        )
        // 1024/160*25.4 ≈ 163mm，274/160*25.4 ≈ 43mm
        assertTrue(GeelyDisplaySelector.labelOf(withDpi), GeelyDisplaySelector.labelOf(withDpi).contains("163×43mm"))
    }

    @Test
    fun `panelHint 按宽高比与绝对高度区分面板类型`() {
        assertEquals("细长 · 像仪表面板", GeelyDisplaySelector.panelHint(d(3, 1024, 274)))
        assertEquals("矮屏 · 像仪表面板", GeelyDisplaySelector.panelHint(d(3, 1024, 400)))
        assertEquals("宽屏 · 像中控或外接 HDMI", GeelyDisplaySelector.panelHint(d(1, 1920, 1080)))
        assertEquals("分辨率未知", GeelyDisplaySelector.panelHint(d(9, 0, 0)))
    }

    @Test
    fun `clusterLikeness 细长矮屏分最高，排序不参与自动选择`() {
        val slim = d(3, 1024, 274)
        val hdmi = d(1, 1920, 1080)
        assertTrue(GeelyDisplaySelector.clusterLikeness(slim) > GeelyDisplaySelector.clusterLikeness(hdmi))
        // 仍然必须：不选就返回 null（排序不等于自动挑）
        assertNull(GeelyDisplaySelector.select(true, listOf(d(0, isDefault = true), slim), requestedId = -1))
    }

    // ---------------------------------------------------------- specOf

    @Test
    fun `specOf 可见高固定 720 面板高跟屏走`() {
        val spec = GeelyDisplaySelector.specOf(d(1, 1920, 1080), densityDpi = 160)
        assertEquals(1, spec.displayId)
        assertEquals(1920, spec.widthPixels)
        assertEquals(1080, spec.heightPixels)
        assertEquals(720, spec.visibleHeightPixels)
    }

    @Test
    fun `specOf 矮屏时可见高不超过面板本身（1024x274 场景）`() {
        // 现场 bug：面板只有 274 高，旧实现仍按 720 申报 ⇒ 等于告诉 iPhone
        // 这块屏有 720 高，比屏本身还高。现在按面板实际高度报。
        val spec = GeelyDisplaySelector.specOf(d(3, 1024, 274), densityDpi = 160)
        assertEquals(274, spec.visibleHeightPixels)
    }

    // ---------------------------------------------------------- 帧率跟面板走

    /**
     * 车主口径：「**仪表盘只支持 30 帧**」。真车那块仪表面板就是
     * `id=2 HDMI 屏幕 1920x1080 refresh=30.0Hz`，而 `/info` 一直按 60 声明 ——
     * 手机多推、解码器多解，面板根本画不出来。
     */
    @Test
    fun `specOf 声明帧率跟面板刷新率走（30Hz 面板就是 30）`() {
        val spec = GeelyDisplaySelector.specOf(
            d(2, 1920, 1080, refreshHz = 30f),
            densityDpi = 160,
        )
        assertEquals(30, spec.fps)

        val sixty = GeelyDisplaySelector.specOf(
            d(1, 1920, 1080, refreshHz = 60f),
            densityDpi = 160,
        )
        assertEquals(60, sixty.fps)
    }

    @Test
    fun `clusterFpsFor 取不到刷新率时用缺省值，越界夹到 30 到 60`() {
        assertEquals(
            com.dbtool.carplay.api.display.CarResolutionPolicy.DEFAULT_FPS,
            GeelyDisplaySelector.clusterFpsFor(0f),
        )
        assertEquals(
            com.dbtool.carplay.api.display.CarResolutionPolicy.DEFAULT_FPS,
            GeelyDisplaySelector.clusterFpsFor(-1f),
        )
        // 24Hz 面板：夹到 30（再低不是"能显示"，是卡顿）
        assertEquals(30, GeelyDisplaySelector.clusterFpsFor(24f))
        // 90Hz 面板：声明得比面板高没有意义，夹到 60
        assertEquals(60, GeelyDisplaySelector.clusterFpsFor(90f))
        // 小数刷新率按四舍五入
        assertEquals(30, GeelyDisplaySelector.clusterFpsFor(29.97f))
        assertEquals(50, GeelyDisplaySelector.clusterFpsFor(50f))
    }

    // ---------------------------------------------------------- 物理尺寸（PATCH-0095）

    /**
     * 面板报得出 dpi ⇒ 如实折算成 mm，并且**真的进了 `CarClusterSpec`**。
     *
     * ## 为什么这条值得钉
     *
     * 此前 `specOf()` **完全不传** `widthPhysicalMm/heightPhysicalMm` ⇒ 副屏声明
     * 一路落到上游兜底值 200mm（而主屏出厂值是 350mm），iPhone 按
     * 「像素 + 物理尺寸」推算出来的仪表界面密度比主屏小 1.75 倍。
     *
     * 换算口径：`mm = px / dpi * 25.4`。160dpi 下 1920px ≈ 305mm、1080px ≈ 171mm。
     */
    @Test
    fun `specOf 把面板自报的 dpi 折成物理尺寸带给声明`() {
        val spec = GeelyDisplaySelector.specOf(
            d(1, 1920, 1080, xdpi = 160f, ydpi = 160f),
            densityDpi = 160,
        )
        assertEquals(305, spec.widthPhysicalMm)
        assertEquals(171, spec.heightPhysicalMm)
    }

    /** 面板不报 dpi（本机 CS11 实测 0.0）⇒ 留 null，交给下游用产品出厂值。 */
    @Test
    fun `specOf 面板不报 dpi 时物理尺寸留空`() {
        val spec = GeelyDisplaySelector.specOf(d(3, 1024, 274), densityDpi = 160)
        assertNull(
            "面板不说自己多大，就不该编一个数出来（下游会用 350mm 兜底）",
            spec.widthPhysicalMm,
        )
        assertNull(spec.heightPhysicalMm)
    }

    /**
     * ⚠️ `physicalSizeMmOrNull` 必须返回 **null** 而不是 `(0, 0)`。
     *
     * 返回 0 会让下游算出 `heightPhysical = 0`，再被
     * `sanitizeReportedPhysicalMm` 夹到 1mm —— 那是在告诉 iPhone
     * 「这块屏 1 毫米高」。
     */
    @Test
    fun `取不到 dpi 时返回 null 而不是零`() {
        assertNull(GeelyDisplaySelector.physicalSizeMmOrNull(d(1, 1920, 1080)))
        // 只有一半能读（x 有 y 没有）也当作读不到：混着算出来的比例是错的。
        assertNull(
            GeelyDisplaySelector.physicalSizeMmOrNull(d(1, 1920, 1080, xdpi = 160f, ydpi = 0f)),
        )
        // 分辨率未知同理（0 像素算出来必然是 0）。
        assertNull(GeelyDisplaySelector.physicalSizeMmOrNull(d(1, 0, 0, xdpi = 160f, ydpi = 160f)))
    }

    /** 标签与声明必须用**同一份**换算 —— 否则设置页说"约 305×171mm"、报给 iPhone 的却是别的数。 */
    @Test
    fun `标签与声明的物理尺寸同源`() {
        val descriptor = d(1, 1920, 1080, xdpi = 160f, ydpi = 160f)
        val label = GeelyDisplaySelector.physicalSizeLabel(descriptor)
        val mm = GeelyDisplaySelector.physicalSizeMmOrNull(descriptor)
        assertEquals(" · 约 ${mm!!.first}×${mm.second}mm", label)
        assertEquals(305, mm.first)
    }
}
