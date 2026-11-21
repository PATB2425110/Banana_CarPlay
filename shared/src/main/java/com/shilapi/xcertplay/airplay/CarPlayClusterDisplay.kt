package com.shilapi.xcertplay.airplay

/**
 * CarPlay 仪表副屏（instrument cluster）—— 流类型 111（`STREAM_TYPE_ALT_SCREEN`）。
 *
 * ## 它解决什么
 *
 * iPhone 在 `/info` 握手里通过 `altScreenURLs` 列出它愿意向「第二块屏」投的内容。
 * 不给 `initialUrl` 时，iPhone 会推**黑帧**（屏是黑的，但流是活的）。
 * 因此想看到地图，必须把 URL 告诉手机。
 *
 * ## 与 DBPlay 既有 HUD 桥的区别（重要）
 *
 * DBPlay 原本的 `GeelyHudBridge` 是**发转向文字给第三方服务**（高德/百度的
 * `CarPlayHudBridgeService`），依赖车机装了那个服务 —— 实测 `HUD 桥可用性: false`。
 * 本类走的是**另一条路**：让 iPhone 直接把地图**当第二路画面**推过来，车机只负责
 * 开一块窗口接住它。不依赖任何第三方服务。
 *
 * ## 移植说明
 *
 * 骨架与 URL 取自上游 DiPlay（`carlito12345/DiPlay`，GPL-3.0，与本项目同类许可），
 * 但**上游的标定值全部来自比亚迪 DiLink**，对吉利不成立，故此处：
 * - 保留 URL 常量、config 计算、安全区算法（通用）；
 * - `SAFE_AREA_PERCENT` 改为 `null` 语义由调用方（吉利平台）显式传入；
 *   未标定时用整屏安全区（`ZERO`），宁可先占满也别用错标定把地图挤偏。
 *
 * ⚠️ 吉利 CS11 实测后应把真实标定写进 `car/platform-geely`，不要回填到这里。
 */
object CarPlayClusterDisplay {

    /** 地图。车机侧实测这条最稳。 */
    const val MAP_URL = "maps:/car/instrumentcluster/map"

    /** iPhone 愿意投的仪表内容之一。 */
    enum class Content(val url: String) {
        /** 地图（整幅）。默认。 */
        MAP(MAP_URL),

        /** 官方转向卡（玻璃质感，数据量极小 0–5 kbit/s）。 */
        TURN_CARD("maps:/car/instrumentcluster/instructioncard"),

        /** 整个仪表盘。 */
        INSTRUMENTS("maps:/car/instrumentcluster"),

        /** 取地图流，由车机自己在其上叠一层可移动卡片。 */
        MAP_WITH_CUSTOM_CARD(MAP_URL),
    }

    fun usesCustomTurnCard(content: Content): Boolean = content == Content.MAP_WITH_CUSTOM_CARD

    /**
     * 转向卡三档对齐（`maneuverLayout` 取值）。
     *
     * 顺序即原版 `GeelyClusterContent` 的 `alignment` 语义：0=左侧 / 1=右侧 / 2=顶部。
     * **别改顺序** —— 用户已保存的 `clusterAlignment` 是按**下标**存的。
     */
    val MANEUVER_LAYOUTS = listOf("leftAligned", "rightAligned", "topAligned")

    /** 与 [MANEUVER_LAYOUTS] 同序的中文标签，供设置页展示。 */
    val MANEUVER_LAYOUT_LABELS = listOf("左侧", "右侧", "顶部")

    /**
     * 副屏内容 URL —— 原版 `GeelyClusterContent.url()` 的等价实现（2026-10-11 同步）。
     *
     * | 开关 | URL |
     * | --- | --- |
     * | 显示 iPhone 导航卡片 | `maps:/car/instrumentcluster?showCompass=user&showETA=yes&showSpeedLimit=user&maneuverLayout=<layout>` |
     * | 仅地图布局控制（测试） | `maps:/car/instrumentcluster/map?maneuverLayout=<layout>` |
     * | 都不开 | `maps:/car/instrumentcluster/map` |
     *
     * `alignment` 经 `coerceIn(0, 2)`，越界不会抛（设置项可能来自旧版本）。
     *
     * ⚠️ 语义边界：这两个开关**只改 URL 参数**，由 iPhone 侧的导航应用决定效果；
     * 拿不到预期效果时先核对 URL，不要让车机侧去"补偿"。
     */
    fun contentUrl(
        showNavigationCard: Boolean,
        alignment: Int,
        mapLayoutTrial: Boolean,
    ): String {
        val layout = MANEUVER_LAYOUTS[alignment.coerceIn(0, MANEUVER_LAYOUTS.lastIndex)]
        return when {
            showNavigationCard ->
                "${Content.INSTRUMENTS.url}?showCompass=user&showETA=yes" +
                    "&showSpeedLimit=user&maneuverLayout=$layout"
            mapLayoutTrial -> "$MAP_URL?maneuverLayout=$layout"
            else -> MAP_URL
        }
    }

    fun usesOfficialTurnCard(content: Content): Boolean =
        content == Content.TURN_CARD || content == Content.INSTRUMENTS

    /**
     * 未标定时的安全区：整屏。
     *
     * ⚠️ **不要拿上游的 (16/25/35/36) 当默认值** —— 那是比亚迪 DiLink 5.0 的实测结果，
     * 用在吉利上会把车标挤到一个错误的位置。吉利要么由平台层传入实数，要么先用整屏。
     */
    val UNSET_SAFE_AREA = AirPlayInsets(top = 0, bottom = 0, left = 0, right = 0)

    /** 车标可偏移的步进（百分比）。与安全区标定配合使用。 */
    const val MARKER_STEP_PERCENT = 10
    val horizontalSteps = -4..4 // 负 = 左
    val verticalSteps = -3..3 // 负 = 上

    /**
     * 车标落点（占整屏百分比，x 自左、y 自上）。
     *
     * 算法与上游一致：以安全区中心为基准，按步进偏移，并夹在安全区内。
     */
    fun markerPercent(
        horizontalStep: Int,
        verticalStep: Int,
        area: AirPlayInsets = UNSET_SAFE_AREA,
    ): Pair<Double, Double> {
        val x = (area.left + 100 - area.right) / 2.0 +
            horizontalStep.coerceIn(horizontalSteps) * MARKER_STEP_PERCENT
        val y = (area.top + 100 - area.bottom) / 2.0 +
            verticalStep.coerceIn(verticalSteps) * MARKER_STEP_PERCENT
        return x.coerceIn(MARKER_MARGIN, 100 - MARKER_MARGIN) to
            y.coerceIn(MARKER_MARGIN, 100 - MARKER_MARGIN)
    }

    private const val MARKER_MARGIN = 5.0

    /**
     * 流尺寸（占面板百分比）。
     *
     * 仪表会把流**放大**到面板，所以流越小、地图看起来越大（但更糊）。
     * 上游在比亚迪上实测 83% 是清晰度与可读性的平衡点；吉利用默认 100（不缩放）起步，
     * 实测后再调。
     */
    const val STREAM_SCALE_PERCENT = 100
    val scalePresets = listOf(100, 83, 67, 125)

    private const val FPS = 30

    /**
     * 构造给 iPhone 的仪表屏配置。
     *
     * 高度取 8 的倍数、宽度跟随高度以保持面板宽高比（如 83% 的 1920x720 → 1600x600）。
     *
     * @param baseSafeArea 平台实测的安全区；不传则用整屏（未标定）。
     */
    fun config(
        widthPixels: Int,
        heightPixels: Int,
        scalePercent: Int = STREAM_SCALE_PERCENT,
        horizontalStep: Int = 0,
        verticalStep: Int = 0,
        content: Content = Content.MAP,
        baseSafeArea: AirPlayInsets = UNSET_SAFE_AREA,
    ): AirPlayDisplayConfig {
        val scale = scalePercent.coerceIn(25, 150)
        val height = roundTo8(heightPixels * scale / 100.0)
        val width = roundTo8(height * widthPixels.toDouble() / heightPixels)
        return AirPlayDisplayConfig(
            widthPixels = width,
            heightPixels = height,
            fps = FPS,
            primaryInputDevice = 0,
            initialUrl = content.url,
            safeArea = safeArea(width, height, horizontalStep, verticalStep, baseSafeArea),
            safeAreaDrawOutside = true,
        )
    }

    /**
     * 安全区保持以车标为中心；只有车标靠近面板边缘时才收缩，保证车标始终居中。
     */
    private fun safeArea(
        width: Int,
        height: Int,
        horizontalStep: Int,
        verticalStep: Int,
        baseSafeArea: AirPlayInsets = UNSET_SAFE_AREA,
    ): AirPlayInsets {
        val area = baseSafeArea
        val (x, y) = markerPercent(horizontalStep, verticalStep, area)
        val halfWidth = minOf((100 - area.left - area.right) / 2.0, x, 100 - x)
        val halfHeight = minOf((100 - area.top - area.bottom) / 2.0, y, 100 - y)
        return AirPlayInsets(
            top = (height * (y - halfHeight) / 100).toInt(),
            bottom = (height * (100 - y - halfHeight) / 100).toInt(),
            left = (width * (x - halfWidth) / 100).toInt(),
            right = (width * (100 - x - halfWidth) / 100).toInt(),
        )
    }

    private fun roundTo8(value: Double): Int =
        (Math.round(value / 8) * 8).toInt().coerceAtLeast(8)
}
