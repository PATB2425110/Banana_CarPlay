package com.dbtool.carplay.api.display

/**
 * 视频画布尺寸（px）——即 UI 里承载 CarPlay 画面的那个 View 的**实测**尺寸。
 *
 * 为什么需要它而不是直接用面板分辨率：
 * CarPlay 的握手分辨率必须与实际呈现区域一致，否则画面会被拉伸或留黑边。
 * 车机上视频区域常常不是全屏（下方有控制栏、上方有状态条、或副驾屏只占一半），
 * 因此必须由 UI 提供真实尺寸。
 *
 * upstream 用的也是同一口径（`CarPlayHostActivity` 取 `TextureView` 的
 * `view.width/height`），不是取 DisplayMetrics。
 */
data class CarVideoCanvas(
    val widthPixels: Int,
    val heightPixels: Int,
) {
    init {
        require(widthPixels > 0 && heightPixels > 0) {
            "Video canvas must be positive, got ${widthPixels}x$heightPixels"
        }
    }

    /**
     * 宽容比较：两条边各自差不超过 [CANVAS_TOLERANCE_PIXELS] 就当作同一尺寸。
     *
     * 专治实车上的"状态条抖动"：承载画面的 TextureView 高度会在 1080 与 1008
     * 之间反复跳（差的 72px 是顶部状态条），而调用方（
     * `CarAppController.onVideoCanvasSizeChanged`）一旦看到尺寸变化就会重建
     * 整条协议栈。用严格相等比较挡不住 72px 的变化，于是画面每隔几十秒被
     * 自己重连一次。
     *
     * 容差取 96px：足以吃掉一条状态条（72px），又远小于任何真实布局变化
     * （横竖屏旋转是 1080↔1920，差 840px；分屏也在数百 px 量级）。
     */
    fun roughlyEquals(other: CarVideoCanvas): Boolean =
        kotlin.math.abs(widthPixels - other.widthPixels) <= CANVAS_TOLERANCE_PIXELS &&
            kotlin.math.abs(heightPixels - other.heightPixels) <= CANVAS_TOLERANCE_PIXELS

    /**
     * 这次变化是不是**真实的分辨率切换**（而不是系统栏进出造成的几像素抖动）。
     *
     * ## ⚠️ 为什么需要它 —— `roughlyEquals` 不够
     *
     * `roughlyEquals` 挡的是**单次跳变**（这一步跨了多大）。但 0.10.10 车机
     * 日志（20:43~20:45）实证了另一种走法：高度**在容差内来回蹭**——
     *
     *     1080 → 1008（差 72，被挡）→ 1032（与上次只差 24）→ 1080（差 48）
     *
     * 每一步都在 96px 容差内，却**越过了"上一次已接受值"**，于是旧实现放行、
     * 重建整条协议栈，`AndroidMediaSink` 销毁重建音轨，用户听到音乐断 4~5 秒。
     * 日志里那 4 次 `AudioTrack pause()+stop() → start()` 就是这么来的。
     *
     * ## 判据：宽高比
     *
     * 系统栏进出的影响恒为**单轴**（只改高度、不改宽度），比例漂移极小：
     * - `1920x1080 → 1920x1008`：漂移 7.1%
     * - `1920x1080 → 1920x1032`：漂移 4.7%
     * - `1920x1080 → 1920x984`（上下两条栏）：漂移 8.9%
     *
     * 真实切换则是**量级差异**：
     * - 横转竖 `1920x1080 → 1080x1920`：漂移 68%
     * - 分屏 `1920x1080 → 960x1080`：漂移 50%
     *
     * 用**比例**而非像素距离：像素距离对"大屏小抖动"太敏感（1920 宽时 72px
     * 只占 3.75%），比例判据与屏幕绝对尺寸无关，换台车也不会失准。
     *
     * ## 已知取舍
     *
     * **同比例的纯分辨率变化**（如 1920x1080 → 1280x720）会被判为"没变"。
     * 这是有意的：车机面板分辨率固定，系统栏进出永远单轴，所以这种输入不存在；
     * 若为它放宽到像素判据，系统栏抖动就会重新漏进来。
     *
     * @param tolerance 相对漂移阈值，默认 [CANVAS_ASPECT_TOLERANCE]。
     */
    fun isRealResolutionChange(
        other: CarVideoCanvas,
        tolerance: Double = CANVAS_ASPECT_TOLERANCE,
    ): Boolean {
        val before = widthPixels.toDouble() / heightPixels.toDouble()
        val after = other.widthPixels.toDouble() / other.heightPixels.toDouble()
        val drift = kotlin.math.abs(after - before) / before
        return drift > tolerance
    }

    companion object {
        /**
         * 画布尺寸变化的容差（px）。见 [roughlyEquals] 的说明。
         *
         * 取 96 是有意的：车机常见状态条/导航条高度集中在 48~96 之间
         * （实车实测 72），而真实的分辨率切换至少是数百 px。
         */
        const val CANVAS_TOLERANCE_PIXELS = 96

        /**
         * 判定"真实分辨率切换"的宽高比漂移阈值（相对值，0.15 = 15%）。
         * 见 [isRealResolutionChange] 的说明与实测数据。
         *
         * 余量充足（系统栏 7% vs 真实切换 50%，中间隔 7 倍），
         * 换台屏幕比例不同的车机也不会误判。
         */
        const val CANVAS_ASPECT_TOLERANCE = 0.15
    }
}
