package com.dbtool.carplay.api.display

/**
 * 仪表投屏视频层的**本机画面变换**（2026-10-22）。
 *
 * ## 它解决什么
 *
 * 仪表上那条 CarPlay 视频流（ALT / 111）原本 1:1 铺满可见区，车机侧没有任何
 * 缩放/平移入口。本类描述的变换由平台层落到 `TextureView.setTransform(Matrix)`
 * （GPU 纹理矩阵，零额外拷贝），只影响**仪表这块屏**：
 *
 * - 主屏画面不受影响（另一条 TextureView / Surface）；
 * - 不向 iPhone 发任何命令，不改会话、不解绑 Surface。
 *
 * ## 与「设置 → 仪表导航 → 地图缩放」按钮的区别
 *
 * 那两个按钮走 `changeMapZoomLevel` 命令让 **iPhone 地图自己缩放**（Apple Maps
 * 才会响应，且要求仪表流已建立）。本类是本机对已解码画面的缩放，任何内容都有效，
 * 两者可以叠加使用。
 *
 * ## 语义
 *
 * 先绕视频视图中心等比缩放 [scale]，再平移 [dx]/[dy]（单位 = 视图坐标 px，
 * 与 `TextureView` 的 `setTransform` 语义一致，见 `HudCropGeometry` 的推导）。
 * 默认 `1.0 / 0 / 0` ⇒ 与未开放时逐像素一致。
 *
 * @property scale 等比缩放，[MIN_SCALE]..[MAX_SCALE]
 * @property dx 水平平移（视图 px，正为右）
 * @property dy 垂直平移（视图 px，正为下）
 */
data class ClusterVideoTransform(
    val scale: Float = 1f,
    val dx: Float = 0f,
    val dy: Float = 0f,
) {
    val isDefault: Boolean
        get() = scale == 1f && dx == 0f && dy == 0f

    companion object {
        const val MIN_SCALE: Float = 0.5f
        const val MAX_SCALE: Float = 3f
        const val MAX_OFFSET_X: Float = 1920f
        const val MAX_OFFSET_Y: Float = 720f

        val DEFAULT: ClusterVideoTransform = ClusterVideoTransform()

        /**
         * 参数校验：缩放夹到 0.5..3.0，平移夹到设计画布尺度。
         *
         * 小于 0.5 时仪表上一小块画面已经没有可读性；大于 3.0 时放大后的
         * 锯齿与裁切都很明显，且没有实际收益。
         */
        fun sanitize(transform: ClusterVideoTransform): ClusterVideoTransform =
            transform.copy(
                scale = transform.scale.coerceIn(MIN_SCALE, MAX_SCALE),
                dx = transform.dx.coerceIn(-MAX_OFFSET_X, MAX_OFFSET_X),
                dy = transform.dy.coerceIn(-MAX_OFFSET_Y, MAX_OFFSET_Y),
            )
    }
}
