package com.dbtool.carplay.api.display

import kotlin.math.min

/**
 * DBPlay 自绘层的「绘制区域」（2026-10-22 车主需求：自绘区域要可自定义）。
 *
 * ## 语义
 *
 * 坐标系与 [ClusterSkinView] 的画布一致：**1920×720 设计基准**，左上角为原点。
 * [x]/[y]/[width]/[height] 描述的是**目标矩形**（这块区域落在画布的哪里、多大），
 * 而不是「从内容里裁一块」：
 *
 * - 内容整体**等比缩放**后放进这个矩形并居中 —— 用 [contentScale] 等比取小，
 *   保证圆表在任何区域比例下都还是正圆（这是双环皮肤最不能被破坏的几何约束）；
 * - 矩形之外一律不画 ⇒ 保持透明，下层 CarPlay 地图可以透出来。
 *
 * 默认 [DEFAULT] = 整幅 1920×720、缩放 1.0 ⇒ 与未开放该参数时**逐像素一致**。
 *
 * ## 与 [ClusterDialLayout] 的关系
 *
 * 两者叠加、互不替代：
 *
 * ```
 * 画布 → 自绘区域（本类，外层裁剪 + 等比缩放）
 *      → 单个表盘的 DialTransform（内层 dx/dy/scale）
 * ```
 *
 * 因此调整区域不会改变左右表的相对布局，用户之前调好的表盘位置仍然有效。
 */
data class ClusterDrawRegion(
    /** 区域左边缘（设计画布 px，0..1920）。 */
    val x: Float = 0f,
    /** 区域上边缘（设计画布 px，0..720）。 */
    val y: Float = 0f,
    /** 区域宽度（设计画布 px，[MIN_WIDTH]..1920）。 */
    val width: Float = BASE_WIDTH,
    /** 区域高度（设计画布 px，[MIN_HEIGHT]..720）。 */
    val height: Float = BASE_HEIGHT,
) {
    val right: Float get() = x + width

    val bottom: Float get() = y + height

    /** 是否为出厂默认（整幅画布、不缩放）。 */
    val isDefault: Boolean
        get() = x == 0f && y == 0f && width == BASE_WIDTH && height == BASE_HEIGHT

    /**
     * 内容整体缩放系数。
     *
     * 取宽、高两个方向的较小值（等比），而不是分别拉伸：表盘一旦被拉成椭圆，
     * 读数与刻度都会失真，所以宁可在一个方向上留透明边。
     */
    val contentScale: Float
        get() = min(width / BASE_WIDTH, height / BASE_HEIGHT).coerceAtLeast(0.0001f)

    /** 缩放后内容的左边缘（区域大于内容时居中留边）。 */
    val contentLeft: Float
        get() = x + (width - BASE_WIDTH * contentScale) / 2f

    /** 缩放后内容的上边缘（区域大于内容时居中留边）。 */
    val contentTop: Float
        get() = y + (height - BASE_HEIGHT * contentScale) / 2f

    companion object {
        const val BASE_WIDTH: Float = 1920f
        const val BASE_HEIGHT: Float = 720f

        /** 区域最小宽度：再小表盘已经看不清，且滑块会退化成一点。 */
        const val MIN_WIDTH: Float = 480f

        /** 区域最小高度。 */
        const val MIN_HEIGHT: Float = 180f

        /** 出厂默认：整幅画布（与未开放区域参数时完全一致）。 */
        val DEFAULT: ClusterDrawRegion = ClusterDrawRegion()

        /**
         * 把越界值夹回可用范围。
         *
         * 顺序很关键：先夹宽高，再用「画布 - 宽高」夹 x/y，
         * 保证 `x + width <= 1920`、`y + height <= 720` 恒成立。
         */
        fun sanitize(region: ClusterDrawRegion): ClusterDrawRegion {
            val w = region.width.coerceIn(MIN_WIDTH, BASE_WIDTH)
            val h = region.height.coerceIn(MIN_HEIGHT, BASE_HEIGHT)
            return ClusterDrawRegion(
                x = region.x.coerceIn(0f, BASE_WIDTH - w),
                y = region.y.coerceIn(0f, BASE_HEIGHT - h),
                width = w,
                height = h,
            )
        }
    }
}

/**
 * 能接收「自绘区域」的绘制宿主（与 `ClusterDialLayoutHost` 同款可选能力接口）。
 *
 * 由具体皮肤 View 实现（如 `ClusterSkinView`），平台层通过该接口下发参数，
 * 避免 platform 模块直接依赖 ui 模块的 View 实现类。
 */
interface ClusterDrawRegionHost {
    /** 应用区域；实现应只更新绘制参数并请求重绘，不得重建 View。 */
    fun applyDrawRegion(region: ClusterDrawRegion)
}
