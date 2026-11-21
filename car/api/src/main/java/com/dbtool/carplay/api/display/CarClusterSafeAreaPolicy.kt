package com.dbtool.carplay.api.display

import com.dbtool.carplay.api.settings.CarSettingsSafeArea

/**
 * 副屏「核心安全区」的**默认策略**（PATCH-0093，2026-10-11 车主口径）。
 *
 * ## 为什么需要它
 *
 * 「核心安全区」此前是**主屏与副屏共用**的一项（PATCH-0094 起已**只作用于副屏**，
 * 见 [CarSettingsSafeArea] 的类注释）。共用带来的问题是：
 * 用户没画过框时，主屏有既定回退（沉浸模式避让顶部 72px，见
 * `CarPlayConfigBuilder`），而**副屏什么都没有** —— `CarClusterSpec.safeArea`
 * 恒为 `null` ⇒ 报给 iPhone 的声明里没有安全区 ⇒ iPhone 认为整块面板都可用
 * ⇒ 导航铺满 1920×1080 面板、压到下方车机自己的油量/车速表。
 *
 * 车主口径（2026-10-11）：「**仪表核心安全区改成 1920×720，现在的太大了**」。
 *
 * ## 定案：默认只给副屏，**不动**共用设置项本身
 *
 * 三条被考虑过的路，选了第 ①：
 *
 * ① **副屏单独一条默认回退**（本文件）—— 主屏行为逐字节不变；
 * ② 把 `CarSettingsSafeArea` 的出厂默认改成 1920×720 —— 会连带把**主屏**可用区
 *    收到上方 720px、底部空出 360px（改之前 `CarPlayConfigBuilder.mainSafeArea`
 *    读同一份矩形），属"改一处坏另一屏"；
 * ③ 只改我这台车上的值 —— 解决不了"下次换车/重装又变回去"。
 *
 * ## 默认值的含义
 *
 * [DEFAULT_WIDTH_PIXELS] × [DEFAULT_HEIGHT_PIXELS] = **1920×720**，坐标空间是
 * **副屏面板**（不是主屏画布）：
 *
 * - 宽取**整面板宽**（不左右留边）；
 * - 高取 720px ⇒ 在 1920×1080 的仪表面板上正好是**上方 2/3**，
 *   下方 360px 留给车机自己的油量/车速表。
 *
 * ⚠️ **面板本身不比默认值大时不声明**（返回 `null`）—— 例如 1024×274 的 HDMI 模式，
 * 硬套一个 1920×720 会算出负数或把整块屏判成不可用。此时行为与改动前**完全一致**
 * （= 不声明，iPhone 按整块面板排）。
 *
 * ⚠️ **用户画过框就一律以用户的为准**（哪怕画得比默认值更小/更大），
 * 本策略只在"用户没表达意见"时兜底。
 */
object CarClusterSafeAreaPolicy {

    /** 默认核心区宽度（px，副屏面板空间）。1920 = 本车机仪表 HDMI 面板整宽。 */
    const val DEFAULT_WIDTH_PIXELS = 1920

    /** 默认核心区高度（px，副屏面板空间）。720 / 1080 = 上方 2/3。 */
    const val DEFAULT_HEIGHT_PIXELS = 720

    /**
     * 没画过框时副屏该声明什么。返回 `null` = **不声明**（与改动前一致）。
     *
     * 只在面板**真的比默认区大**时才收紧：宽或高已经 ≤ 默认值的面板原样放过，
     * 免得把"本来就矮的屏"整块判成不可用。
     */
    fun defaultInsetsFor(panelWidthPixels: Int, panelHeightPixels: Int): CarClusterSafeAreaInsets? {
        if (panelWidthPixels <= 0 || panelHeightPixels <= 0) return null
        val width = minOf(DEFAULT_WIDTH_PIXELS, panelWidthPixels)
        val height = minOf(DEFAULT_HEIGHT_PIXELS, panelHeightPixels)
        if (width >= panelWidthPixels && height >= panelHeightPixels) return null
        return CarClusterSafeAreaInsets(
            left = 0,
            top = 0,
            right = panelWidthPixels - width,
            bottom = panelHeightPixels - height,
        )
    }

    /**
     * 副屏最终该报的四边（px，副屏面板空间）。`null` = 不声明。
     *
     * @param drawn 用户画的核心区矩形（**主屏画布空间**，见 [CarSettingsSafeArea]）
     * @param mainCanvasWidthPixels 主屏画布宽（`<= 0` = 还没进过画面页，画布未知）
     * @param mainCanvasHeightPixels 主屏画布高
     * @param panelWidthPixels 副屏面板宽
     * @param panelHeightPixels 副屏面板高
     *
     * 两条分支：
     * 1. **用户表达过意见**（画了框，且框没盖满整屏）⇒ 把矩形从主屏画布换算到副屏面板
     *    再转 insets。换算必须走 `scaledTo`：两块屏尺寸不同，直接把矩形数当内边距用
     *    正是 2026-10-11「safeArea 高度为负 ⇒ iPhone 拆会话」那次事故的成因。
     * 2. **用户没表达意见**（未设置 / 画框拉满整屏 / 画布未知）⇒ [defaultInsetsFor]。
     *
     * ⚠️ 第 2 条里"画框拉满整屏"算**没画过**是沿用了 PATCH-0085 的既有定案
     * （拉满整屏 ≡ 没画过）。副作用是"想回到整块面板"没法靠拉满画框实现
     * —— 这是刻意的：副屏的整块面板本来就包含车机自己的仪表区，铺满会压到它。
     */
    fun resolve(
        drawn: CarSettingsSafeArea,
        mainCanvasWidthPixels: Int,
        mainCanvasHeightPixels: Int,
        panelWidthPixels: Int,
        panelHeightPixels: Int,
    ): CarClusterSafeAreaInsets? {
        val hasCanvas = mainCanvasWidthPixels > 0 && mainCanvasHeightPixels > 0
        val drawnInsets = if (!drawn.isEmpty && hasCanvas) {
            drawn.toInsets(mainCanvasWidthPixels, mainCanvasHeightPixels)
        } else {
            null
        }
        val coversEverything = drawnInsets == null ||
            (
                drawnInsets.top == 0 && drawnInsets.bottom == 0 &&
                    drawnInsets.left == 0 && drawnInsets.right == 0
                )
        if (coversEverything) {
            return defaultInsetsFor(panelWidthPixels, panelHeightPixels)
        }
        return drawn
            .scaledTo(mainCanvasWidthPixels, mainCanvasHeightPixels, panelWidthPixels, panelHeightPixels)
            .toInsets(panelWidthPixels, panelHeightPixels)
    }
}
