package com.dbtool.carplay.api.display

/**
 * 单个仪表圆表的可调变换（2026-10-21 用户需求：左右表盘各自独立可调）。
 *
 * 语义：以「表盘标称中心」为 pivot，先按 [scale] 绕中心等比缩放，再整体平移 [dx]/[dy]。
 * 默认 `dx=0, dy=0, scale=1, visible=true` ⇒ 与未开放调节时**逐像素一致**。
 *
 * ⚠️ 本参数只作用于 **DBPlay 自绘的圆表**（`ClusterSkinView.drawDial`）。
 * 车机 DIM 固件在左右两侧画的竖排刻度是另一套东西，无法通过本参数移动；
 * 把圆表缩到 `scale < 1` 可以让固件刻度露出来。
 *
 * @property dx 水平偏移（设计画布像素，1920×720 基准；正为右）
 * @property dy 垂直偏移（设计画布像素；正为下）
 * @property scale 绕表盘中心的等比缩放，建议 0.4..2.0
 * @property visible 是否绘制该表盘
 */
data class DialTransform(
    val dx: Float = 0f,
    val dy: Float = 0f,
    val scale: Float = 1f,
    val visible: Boolean = true,
) {
    /** 是否为默认值（用于「恢复默认」判断与诊断输出）。 */
    val isDefault: Boolean
        get() = dx == 0f && dy == 0f && scale == 1f && visible
}

/**
 * 左右两个圆表的完整布局（2026-10-21）。
 *
 * 独立保存，不并入 [com.dbtool.carplay.api.settings.CarSessionSettings] 的握手字段：
 * 它不参与 CarPlay 协商，改动必须立即生效。
 */
data class ClusterDialLayout(
    val left: DialTransform = DialTransform(),
    val right: DialTransform = DialTransform(),
) {
    val isDefault: Boolean
        get() = left.isDefault && right.isDefault

    companion object {
        /**
         * 出厂默认（2026-10-21 车机实测调优值，VIN 381425）。
         *
         * 原为两表恒等变换（0/0/1），但在 1920×720 画布上两表偏小偏内，
         * 仪表两侧原厂刻度暴露过多。车主在车上调到下面这组值后定为默认：
         * 左右表各向外移约 198px、整体放大 1.26 倍。
         */
        val DEFAULT: ClusterDialLayout = ClusterDialLayout(
            left = DialTransform(dx = -196.73277f, dy = 2.1028137f, scale = 1.2597433f),
            right = DialTransform(dx = 200.93352f, dy = 2.1003418f, scale = 1.2583431f),
        )

        /**
         * 参数校验：把越界的值夹回可用范围，避免用户输入导致表盘飞出屏幕。
         *
         * - 缩放夹到 0.2..3.0（再小看不见、再大超出画布）
         * - 偏移夹到 ±1920 / ±720（设计画布尺度）
         */
        fun sanitize(layout: ClusterDialLayout): ClusterDialLayout =
            ClusterDialLayout(
                left = sanitizeOne(layout.left),
                right = sanitizeOne(layout.right),
            )

        private fun sanitizeOne(t: DialTransform): DialTransform =
            t.copy(
                dx = t.dx.coerceIn(-1920f, 1920f),
                dy = t.dy.coerceIn(-720f, 720f),
                scale = t.scale.coerceIn(0.2f, 3f),
            )
    }
}

/**
 * 能接收表盘布局的绘制宿主（2026-10-21）。
 *
 * 由具体皮肤 View 实现（如 `ClusterSkinView`），平台层通过该接口下发参数，
 * 避免 platform 模块直接依赖 ui 模块的 View 实现类。
 */
interface ClusterDialLayoutHost {
    /** 应用布局；实现应只更新绘制参数并请求重绘，不得重建 View。 */
    fun setDialLayout(layout: ClusterDialLayout)
}
