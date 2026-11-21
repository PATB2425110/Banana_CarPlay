package com.dbtool.carplay.api.display

/** 视频相对整块仪表面板的内缩，单位 px。 */
data class VideoInset(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

/** 某个显示模式下，各图层是否出现以及视频层的几何位置。 */
data class ClusterLayerLayout(
    val themeVisible: Boolean,
    val videoVisible: Boolean,
    /** 交给 ClusterSkinView.centerReserved。 */
    val themeCenterReserved: Boolean,
    val videoInset: VideoInset,
) {
    /**
     * 仪表窗口是否需要显示（整窗 alpha）。
     *
     * 2026-10-22 解耦：窗口可见性是**两个图层可见性的并集**，不再由视频层
     * 单边决定。主题层存在时，即使导航视频层关闭，窗口也必须保留。
     */
    val windowVisible: Boolean
        get() = themeVisible || videoVisible
}

/**
 * 仪表主题 × CarPlay 的纯布局合成。
 *
 * 坐标以仪表面板左上角为原点，基准安全区为 1920×720：
 * 左右各 384px、上下各 200px。面板尺寸变化时按比例换算，并保证
 * 内缩后至少留下 1×1 px，避免极小或异常尺寸产生负宽高。
 */
object ClusterLayerComposition {
    const val BASE_WIDTH: Int = 1920
    const val BASE_HEIGHT: Int = 720
        // 2026-10-22 斌哥口径：CarPlay 地图要落在仪表中央那块红框区
    //   照片实测（1500x700）红框 = [525,140]-[1035,510]
    //   换算 1920x720：左 672、上 144、右 595、下 195
    // 四边独立，不再用对称常量。
    // 2026-10-22 第二版：车主反馈「地图太小」。原来按照片红框算成 653x381，
    // 但红框画得偏小，实际仪表中央可用区比那更大。
    // 现在放大到 860x490（严格 16:9，和 CarPlay 1280x720 源比例一致，不留黑边）。
    const val BASE_INSET_LEFT: Int = 530
    const val BASE_INSET_TOP: Int = 115
    const val BASE_INSET_RIGHT: Int = 530
    const val BASE_INSET_BOTTOM: Int = 115

    /**
     * 兼容入口：把三态模式翻译成两个独立图层开关。
     *
     * 三态没有“都关”的取值，因此本入口永远不会返回两个 false；
     * 需要“主题关 + 导航关”的运行时组合时，走
     * [layoutFor] 的 `(themeVisible, navigationVisible)` 重载，或先把
     * `navigationVisible=false` 叠加上去。
     */
    fun layoutFor(
        mode: ClusterLayerMode,
        panelWidth: Int,
        panelHeight: Int,
    ): ClusterLayerLayout = layoutFor(
        themeVisible = mode.showsTheme,
        navigationVisible = mode.showsNavigation,
        panelWidth = panelWidth,
        panelHeight = panelHeight,
    )

    /**
     * 2026-10-22 解耦后的纯布局入口：主题与导航**各自**决定是否出现。
     *
     * - [themeVisible] 只来自主题域（模式是否要求主题 + 主题是否可用由调用方再叠）；
     * - [navigationVisible] 只来自导航域（模式是否要求视频 + 运行时导航开关）。
     *
     * 三态模式经 [layoutFor] 的兼容重载映射后，与解耦前逐项一致：
     * `BOTH → (true,true)`、`CARPLAY_ONLY → (false,true)`、`THEME_ONLY → (true,false)`。
     */
    fun layoutFor(
        themeVisible: Boolean,
        navigationVisible: Boolean,
        panelWidth: Int,
        panelHeight: Int,
    ): ClusterLayerLayout {
        // 2026-10-22 车主最终口径：CarPlay 视频层**不要固定尺寸，按 1920x720 全屏铺**。
        // 原厂左右表是 DIM 固件层，本身就盖在 App 之上，两侧自然被它吃掉，
        // 中间空出来的地方就是地图 —— 不需要我们算内缩。
        val inset = VideoInset(0, 0, 0, 0)
        return ClusterLayerLayout(
            themeVisible = themeVisible,
            videoVisible = navigationVisible,
            // 只有“主题 + 导航同时出现”时才需要给视频预留中央区；
            // 与解耦前 BOTH=true、其余=false 完全一致。
            themeCenterReserved = themeVisible && navigationVisible,
            videoInset = inset,
        )
    }

    private fun safeInset(panelWidth: Int, panelHeight: Int): VideoInset {
        var left = scaledInset(panelWidth, BASE_INSET_LEFT, BASE_WIDTH)
        var right = scaledInset(panelWidth, BASE_INSET_RIGHT, BASE_WIDTH)
        if (left + right >= panelWidth) {
            left = 0
            right = 0
        }

        var top = scaledInset(panelHeight, BASE_INSET_TOP, BASE_HEIGHT)
        var bottom = scaledInset(panelHeight, BASE_INSET_BOTTOM, BASE_HEIGHT)
        if (top + bottom >= panelHeight) {
            top = 0
            bottom = 0
        }

        return VideoInset(left = left, top = top, right = right, bottom = bottom)
    }


    private fun scaledInset(length: Int, baseInset: Int, baseLength: Int): Int =
        (length.toLong() * baseInset / baseLength).toInt().coerceAtLeast(0)
}
