package com.dbtool.carplay.bridge

import com.dbtool.carplay.api.display.CarClusterSpec
import com.dbtool.carplay.api.display.CarDisplay
import com.dbtool.carplay.api.settings.CarSessionSettings
import com.shilapi.xcertplay.airplay.AirPlayDisplaySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 仪表副屏报给 iPhone 的**物理尺寸**（PATCH-0095，2026-10-11）。
 *
 * ## 为什么必须钉住（实车证据）
 *
 * 1.0.51 诊断包里副屏那一格一直是 `widthPhysical=200`，而「参考物理长度」
 * 设置项是 **350mm** —— 两者差 1.75 倍。iPhone 按「像素数 + 物理尺寸」推算
 * 界面密度，所以副屏上的 CarPlay 内容被画得明显偏小。
 *
 * 根因在 [CarPlayConfigBuilder.toAirPlayDisplay]：它的 KDoc 从 2026-10-07 起
 * 就写着"缺省时按面板宽 **350mm**（与上游 `DEFAULT_WIDTH_PHYSICAL_MM` 同源）"，
 * 而**代码写的却是上游那个 200** —— 注释与代码互相矛盾，且**看不出错**：
 * 协议正常、画面正常，只是密度不对。
 *
 * ## 为什么是"行为断言"而不是读源码
 *
 * 这一段是**纯翻译层**（`CarClusterSpec` → 上游 `AirPlayDisplayConfig`），
 * 输入输出全是纯数据，可以在 JVM 里直接跑。所以这里钉的是
 * **"iPhone 到底会收到什么数"**，比读源码强得多。
 */
class ClusterPhysicalSizeContractTest {

    private val mainDisplay = CarDisplay(
        id = 0,
        widthPixels = 1920,
        heightPixels = 1080,
        densityDpi = 160,
    )

    /** 现场那台车的仪表面板：`displayId=3` 的 **1024×274** 细长条。 */
    private val panel = CarClusterSpec(
        displayId = 3,
        widthPixels = 1024,
        heightPixels = 274,
        densityDpi = 160,
    )

    private fun clusterOf(spec: CarClusterSpec = panel) = CarPlayConfigBuilder
        .airPlayConfig(
            settings = CarSessionSettings.default(),
            display = mainDisplay,
            widthPixels = 1920,
            heightPixels = 1080,
            cluster = spec,
        )
        .cluster

    // ── 常量契约 ────────────────────────────────────────────────────────────

    /**
     * 副屏兜底宽度必须与**主屏出厂值同源**（350），而不是上游那个从未按
     * 车机实际屏宽校正过的 200。
     *
     * 写成"引用"而不是"再写一个 350"：两处一旦分叉，副屏与主屏报给 iPhone 的
     * 物理尺寸又会不一致 —— 而界面密度就是按这个算的。
     */
    @Test
    fun `副屏兜底物理宽度与主屏出厂值同源`() {
        assertEquals(350, CarPlayConfigBuilder.CLUSTER_DEFAULT_WIDTH_PHYSICAL_MM)
        assertEquals(
            CarSessionSettings.DEFAULT_WIDTH_PHYSICAL_MM,
            CarPlayConfigBuilder.CLUSTER_DEFAULT_WIDTH_PHYSICAL_MM,
        )
    }

    /** ⚠️ 它**必须不同于**上游的 200 —— 那正是本次要修掉的数。 */
    @Test
    fun `副屏兜底不再走上游的 200`() {
        assertNotEquals(
            AirPlayDisplaySettings.DEFAULT_WIDTH_PHYSICAL_MM,
            CarPlayConfigBuilder.CLUSTER_DEFAULT_WIDTH_PHYSICAL_MM,
        )
        assertEquals(200, AirPlayDisplaySettings.DEFAULT_WIDTH_PHYSICAL_MM)
    }

    // ── 行为契约：面板没报尺寸时 ────────────────────────────────────────────

    /** 面板不报 dpi（本机 CS11 实测 `0.0`）⇒ 用产品出厂值 350 等比推算。 */
    @Test
    fun `面板不报尺寸时按 350 等比推算`() {
        val cluster = clusterOf()
        assertNotNull("有副屏规格就必须声明第二块屏", cluster)
        assertEquals(350, cluster!!.widthPhysicalMm)
        // 高度按面板宽高比等比：350 * 274 / 1024 ≈ 93.65 → 94
        assertEquals(94, cluster.heightPhysicalMm)
    }

    /** ⚠️ 钉的是**差异点**：这一格以前是 200。 */
    @Test
    fun `面板不报尺寸时报的不是 200`() {
        assertNotEquals(
            "副屏宽度退回上游 200 就是本次修复的回归",
            200,
            clusterOf()!!.widthPhysicalMm,
        )
    }

    // ── 行为契约：面板报了尺寸时 ────────────────────────────────────────────

    /**
     * 面板自报尺寸优先 —— 车机固有的一块屏，物理尺寸应当**如实报**，
     * 不该受"主屏参考物理长度"那个用户设置影响（否则用户调主屏大小
     * 会连带改变仪表的界面密度，那是没有道理的行为）。
     */
    @Test
    fun `面板自报尺寸优先于兜底值`() {
        val cluster = clusterOf(panel.copy(widthPhysicalMm = 305, heightPhysicalMm = 171))
        assertEquals(305, cluster!!.widthPhysicalMm)
        assertEquals(171, cluster.heightPhysicalMm)
    }

    /** 只报了宽没报高 ⇒ 高按面板宽高比推算（不能报 0，那会被夹成 1mm）。 */
    @Test
    fun `只给宽度时高度按比例补`() {
        val cluster = clusterOf(panel.copy(widthPhysicalMm = 320))
        assertEquals(320, cluster!!.widthPhysicalMm)
        assertEquals(Math.round(320 * 274.0 / 1024).toInt(), cluster.heightPhysicalMm)
        assertTrue("高度不能是 0（会被 sanitize 夹成 1mm）", cluster.heightPhysicalMm!! > 1)
    }

    // ── 翻译层不许再引用上游兜底（源码级） ──────────────────────────────────

    /**
     * ⚠️ `toAirPlayDisplay` 里**不许**再出现 `AirPlayDisplaySettings.DEFAULT_WIDTH_PHYSICAL_MM`。
     *
     * 行为断言已经覆盖了"当前结果对不对"，这一条防的是**将来有人改回去**
     * （那个上游常量名字看着更"正统"，很容易被误用）。
     */
    @Test
    fun `翻译层不再引用上游兜底常量`() {
        val body = functionBody("private fun CarClusterSpec.toAirPlayDisplay(")
        assertTrue(
            "toAirPlayDisplay 必须用 CLUSTER_DEFAULT_WIDTH_PHYSICAL_MM",
            body.contains("CLUSTER_DEFAULT_WIDTH_PHYSICAL_MM"),
        )
        assertTrue(
            "toAirPlayDisplay 不该再引用上游的 DEFAULT_WIDTH_PHYSICAL_MM",
            !body.contains("AirPlayDisplaySettings.DEFAULT_WIDTH_PHYSICAL_MM"),
        )
    }

    // ── 测试工具 ────────────────────────────────────────────────────────────

    /**
     * 取 `CarPlayConfigBuilder` 里某个函数的函数体。
     *
     * ⚠️ `toAirPlayDisplay` 是**扩展函数**（`private fun CarClusterSpec.toAirPlayDisplay(...)`），
     * 所以查找串必须带上接收者 —— 只写 `fun toAirPlayDisplay(` 是找不到的
     * （这正是这条测试第一次跑挂的原因）。
     */
    private fun functionBody(signature: String): String {
        val source = sourceOf(
            "car/core-bridge/src/main/java/com/dbtool/carplay/bridge/CarPlayConfigBuilder.kt",
        )
            .replace(Regex("(?s)/\\*.*?\\*/"), " ")
            .replace(Regex("(?m)//.*$"), " ")
        val start = source.indexOf(signature)
        assertTrue("找不到函数 $signature", start >= 0)
        val rest = source.substring(start + 1)
        val next = rest.indexOf("\n    private fun ")
        return if (next >= 0) rest.substring(0, next) else rest
    }

    private fun sourceOf(relative: String): String {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile
        }
        throw AssertionError("找不到源码：$relative")
    }
}
