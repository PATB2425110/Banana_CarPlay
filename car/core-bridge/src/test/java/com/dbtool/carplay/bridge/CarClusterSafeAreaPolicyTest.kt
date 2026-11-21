package com.dbtool.carplay.bridge

import com.dbtool.carplay.api.display.CarClusterSafeAreaInsets
import com.dbtool.carplay.api.display.CarClusterSafeAreaPolicy
import com.dbtool.carplay.api.settings.CarSettingsSafeArea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 副屏「核心安全区」的**默认策略**（PATCH-0093，2026-10-11 车主口径）。
 *
 * ## 为什么要有这个默认值
 *
 * 「核心安全区」是主屏与副屏**共用**的一项设置。用户没画过框时，主屏有既定回退
 * （沉浸模式避让顶部 72px，见 `CarPlayConfigBuilder`），而**副屏什么都没有**
 * ——`CarClusterSpec.safeArea` 恒为 `null` ⇒ 报给 iPhone 的声明里没有安全区 ⇒
 * iPhone 认为整块 1920×1080 面板都可用 ⇒ 导航铺满、压到下方车机自己的油量/车速表。
 *
 * 车主口径：「**仪表核心安全区改成 1920×720，现在的太大了**」。
 *
 * ## 这个文件钉什么
 *
 * 三条，全是"错了看不出来"的类型（画面正常、协议正常、日志正常，只是位置不对）：
 *
 * 1. **默认值的形状**：面板整宽 × 上方 720px ⇒ 1920×1080 面板上 `bottom = 360`；
 * 2. **默认值的边界**：面板本来就 ≤ 1920×720（例如 1024×274 的 HDMI 模式）时
 *    **不声明**（`null`）——硬套会算出负数，或把整块屏判成不可用；
 * 3. **用户的意见优先**：画过框就一律以用户的为准（含"画得比默认还小"），
 *    没画过 / 画框拉满整屏 / 画布未知才走默认。
 *
 * ⚠️ 与主屏**无关**：主屏仍走 `CarPlayConfigBuilder` 的既有回退，本策略只作用于副屏。
 */
class CarClusterSafeAreaPolicyTest {

    /** 本车机仪表 HDMI 面板（实测 `id=3 HDMI 1920×1080 @30Hz`）。 */
    private val panelW = 1920
    private val panelH = 1080

    /** 中控主屏的 CarPlay 画布 —— 用户画框存的坐标空间（见 [CarSettingsSafeArea]）。 */
    private val canvasW = 1920
    private val canvasH = 720

    private fun resolve(
        drawn: CarSettingsSafeArea = CarSettingsSafeArea(),
        canvasWidthPixels: Int = canvasW,
        canvasHeightPixels: Int = canvasH,
        panelWidthPixels: Int = panelW,
        panelHeightPixels: Int = panelH,
    ): CarClusterSafeAreaInsets? = CarClusterSafeAreaPolicy.resolve(
        drawn = drawn,
        mainCanvasWidthPixels = canvasWidthPixels,
        mainCanvasHeightPixels = canvasHeightPixels,
        panelWidthPixels = panelWidthPixels,
        panelHeightPixels = panelHeightPixels,
    )

    // ---------------------------------------------------------------- 默认值的形状

    /**
     * 车主口径就是这两个数。它们一旦被改，副屏上"导航压到仪表"或"上面空一大条"
     * 会立刻回来，所以直接钉住常量本身（不是钉某个调用点的结果）。
     */
    @Test
    fun `车主口径：默认区是 1920 宽乘 720 高`() {
        assertEquals(1920, CarClusterSafeAreaPolicy.DEFAULT_WIDTH_PIXELS)
        assertEquals(720, CarClusterSafeAreaPolicy.DEFAULT_HEIGHT_PIXELS)
    }

    /**
     * 未设安全区 ⇒ 副屏声明「整宽 × 上方 720px」。
     *
     * ⚠️ 这里钉的是 **`bottom = 360`**（= 1080 − 720），不是 `top`。
     * 导航铺满整屏的坏状态正好是 `bottom = 0`；而"上面空一条"的坏状态是 `top > 0`。
     * 只断 `!= null` 是抓不住这两者的。
     */
    @Test
    fun `未设安全区时默认整宽乘上方 720px`() {
        val insets = resolve()
        assertEquals(CarClusterSafeAreaInsets(left = 0, top = 0, right = 0, bottom = 360), insets)
    }

    /** 声明出来的可用高度必须是 720（不是面板高 1080，也不是主屏画布高 720 之外的数）。 */
    @Test
    fun `默认可用高度是 720 而非整块面板`() {
        val insets = requireNotNull(resolve())
        assertEquals(720, panelH - insets.top - insets.bottom)
        assertEquals(1920, panelW - insets.left - insets.right)
    }

    /** 默认区**宽度**夹到面板宽：面板比 1920 窄时左右不留边（`right = 0`）。 */
    @Test
    fun `默认区宽度夹到面板宽`() {
        val insets = requireNotNull(resolve(panelWidthPixels = 1280))
        assertEquals(0, insets.left)
        assertEquals(0, insets.right)
        assertEquals(360, insets.bottom)
    }

    // ---------------------------------------------------------------- 默认值的边界

    /**
     * 面板正好等于默认区（1920×720）⇒ **不声明**。
     *
     * 与主屏同口径（PATCH-0085：四边全 0 ≡ 没声明）——`null` 与"全 0 的 insets"
     * 在 `AirPlayInfoPlist.areaDict` 里等价，但语义要分开：`null` = 这项不存在。
     */
    @Test
    fun `面板正好等于默认区时不声明`() {
        assertNull(resolve(panelHeightPixels = 720))
    }

    /**
     * 面板本来就矮（本车 HDMI 的另一档模式 1024×274）⇒ 不声明，行为与改动前**逐字节一致**。
     *
     * 硬套 1920×720 会算出 `bottom = 274 − 720 = −446`，而报出去的
     * `safeArea.heightPixels` 是负数时 **iPhone 直接拆会话**（真车 CX11 实测）。
     */
    @Test
    fun `面板比默认区小的时候不声明`() {
        assertNull(resolve(panelWidthPixels = 1024, panelHeightPixels = 274))
    }

    /** 面板尺寸非法（0 / 负数）⇒ 不声明，且不抛。 */
    @Test
    fun `面板尺寸非法时不声明`() {
        assertNull(resolve(panelWidthPixels = 0, panelHeightPixels = 1080))
        assertNull(resolve(panelWidthPixels = -5, panelHeightPixels = -5))
    }

    // ---------------------------------------------------------------- 用户的意见优先

    /**
     * **画框拉满整屏 ≡ 没画过**（与主屏同口径，PATCH-0085）⇒ 仍走默认。
     *
     * 真车 1.0.44 实测过这个坑：设置里存的矩形是 `x=0 y=0 w=1920 h=1080`（整屏），
     * "画过框"这条分支**优先于**自动回退 ⇒ 把既定的避让悄悄顶掉了。
     */
    @Test
    fun `画框拉满整屏等同于没画过`() {
        val full = CarSettingsSafeArea(x = 0, y = 0, width = canvasW, height = canvasH)
        assertEquals(CarClusterSafeAreaInsets(left = 0, top = 0, right = 0, bottom = 360), resolve(full))
    }

    /**
     * 画过框 ⇒ 一律以用户的为准，**并换算到副屏面板**（不是把矩形数当内边距用）。
     *
     * 主屏画布 1920×720 上留出上方 72px ⇒ 副屏 1920×1080 面板上是 **108px**。
     * 钉 `108` 而不是 `72` 是刻意的：`72` 正好是"把矩形数直接当 insets 用"的产物，
     * 也就是 PATCH-0079 那次"高度为负 ⇒ iPhone 拆会话"的成因。
     */
    @Test
    fun `画过框就按用户的并换算到副屏面板`() {
        val drawn = CarSettingsSafeArea(x = 0, y = 72, width = 1920, height = 648)
        assertEquals(
            CarClusterSafeAreaInsets(left = 0, top = 108, right = 0, bottom = 0),
            resolve(drawn),
        )
    }

    /** 用户画得**比默认区还小**也照样以他的为准（默认只是兜底，不是下限）。 */
    @Test
    fun `用户画得比默认还小也以用户的为准`() {
        // 画布 1920×720 上高 400 ⇒ 副屏 1920×1080 上高 600 ⇒ bottom = 480（默认是 360）。
        val drawn = CarSettingsSafeArea(x = 0, y = 0, width = 1920, height = 400)
        val insets = requireNotNull(resolve(drawn))
        assertEquals(480, insets.bottom)
        assertTrue("用户的 600 高必须生效，不能被默认的 720 顶掉：$insets", insets.bottom > 360)
    }

    /**
     * 画布未知（还没进过画面页，`CarVideoCanvas` 为 null）⇒ 走默认，且**不能抛**。
     *
     * 这条是判别性的：少了"画布未知"的守卫，`scaledTo(0, 0, …)` / `toInsets(0, 0)`
     * 会撞上 `require(canvas > 0)` 抛 `IllegalArgumentException`。而
     * `CarAppController.resolveClusterSpec()` 每次建栈都会被调、且**必须不抛**
     * （抛出去就是"有副屏的车机上会话建不起来"）。
     */
    @Test
    fun `画布未知时走默认且不抛`() {
        val drawn = CarSettingsSafeArea(x = 0, y = 72, width = 1920, height = 648)
        assertEquals(
            CarClusterSafeAreaInsets(left = 0, top = 0, right = 0, bottom = 360),
            resolve(drawn, canvasWidthPixels = 0, canvasHeightPixels = 0),
        )
    }
}
