package com.dbtool.carplay.android

import android.content.Context
import android.view.WindowManager
import com.dbtool.carplay.api.display.CarDisplay
import com.dbtool.carplay.api.display.CarDisplayAdapter
import com.dbtool.carplay.api.display.CarDisplayProbe
import com.dbtool.carplay.api.display.CarResolutionPolicy
import com.dbtool.carplay.api.display.CarSafeArea

/**
 * 默认显示适配：读取系统默认屏的尺寸/密度/刷新率。
 *
 * 与 upstream 一致，默认不声明物理尺寸（由 UI 的设置项覆盖）；
 * [DEFAULT_WIDTH_PHYSICAL_MM] 与 `CarSessionSettings.DEFAULT_WIDTH_PHYSICAL_MM` 同源
 * （**改一处必须同时改另一处**，见 companion object 里的说明）。
 */
open class AndroidCarDisplayAdapter(
    protected val context: Context,
) : CarDisplayAdapter {

    @Suppress("DEPRECATION")
    override fun primaryDisplay(): CarDisplay {
        val windowManager = context.getSystemService(WindowManager::class.java)
        val metrics = context.resources.displayMetrics
        val densityDpi = if (metrics.densityDpi > 0) metrics.densityDpi else DEFAULT_DENSITY_DPI
        // minSdk 28，`Context.getDisplay()` 需要 API 30，因此这里仍走已废弃的 defaultDisplay。
        val refreshRate = runCatching {
            windowManager?.defaultDisplay?.refreshRate?.toDouble()
        }.getOrNull()
        return CarDisplay(
            id = 0,
            widthPixels = metrics.widthPixels,
            heightPixels = metrics.heightPixels,
            densityDpi = densityDpi,
            widthPhysicalMm = DEFAULT_WIDTH_PHYSICAL_MM,
            heightPhysicalMm = resolveHeightPhysicalMm(metrics.widthPixels, metrics.heightPixels),
            refreshRateHz = refreshRate,
            primary = true,
        )
    }

    /** 安全区默认全 0：upstream 默认也是全屏，需要时由车机 Adapter 覆写。 */
    override fun safeArea(display: CarDisplay): CarSafeArea = CarSafeArea()

    override fun resolutionPolicy(display: CarDisplay): CarResolutionPolicy =
        CarResolutionPolicy(
            scaleTenths = CarResolutionPolicy.DEFAULT_SCALE_TENTHS,
            fps = display.refreshRateHz?.let { if (it >= 60) 60 else 30 }
                ?: CarResolutionPolicy.DEFAULT_FPS,
        )

    override fun hideSystemBars(): Boolean = true

    /**
     * 画布尺寸对账快照（2026-10-07）。
     *
     * ## 取数口径
     *
     * - **面板**：`WindowManager.currentWindowMetrics`（API 30+）优先，回落
     *   `defaultDisplay.getRealSize()`。注意必须用**真实/最大**尺寸，不能用
     *   `resources.displayMetrics` —— 后者在应用窗口小于屏幕时会被裁成窗口尺寸，
     *   就分不清"面板 1080 / 窗口 1008"了。
     * - **两条栏 inset**：从当前 `WindowInsets` 取。**这里是本探测的核心**：
     *   如果车机在窗口层就把 StatusBar 切掉了（厂商 overlay 常见），
     *   `topInset` 会是 0 —— 而面板高仍报 1080。这种"窗口小了、inset 却没有"
     *   的组合，正是判断"该按面板报还是按窗口报"的唯一线索。
     * - **忽略可见性**的底部 inset：区分"栏真的存在但被隐藏"与"根本没这栏"。
     *
     * ## 为什么不放在 UI 层
     *
     * UI 层（MainActivity）拿到的 `View.rootWindowInsets` 已经是 Compose 处理过的，
     * 可能被 `WindowInsetsCompat` 消费掉一部分；而本探测要的是**原始系统值**。
     * 放在这里也顺便让非 Android 平台自然跳过（基类默认返回零快照）。
     */
    @Suppress("DEPRECATION")
    override fun probeCanvasMetrics(): CarDisplayProbe {
        val wm = context.getSystemService(WindowManager::class.java)

        // ① 面板：真实/最大尺寸。
        var panelW = 0
        var panelH = 0
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val b = wm?.currentWindowMetrics?.bounds
                if (b != null) {
                    panelW = b.width()
                    panelH = b.height()
                }
            }
            if (panelW == 0 || panelH == 0) {
                val p = android.graphics.Point()
                wm?.defaultDisplay?.getRealSize(p)
                panelW = p.x
                panelH = p.y
            }
        }
        if (panelW == 0 || panelH == 0) {
            // 极端回落：至少给出一个可比的数，别让对账行全是 0。
            val m = context.resources.displayMetrics
            panelW = m.widthPixels
            panelH = m.heightPixels
        }

        // ② 两条栏 inset：无窗口时取不到，由 UI 层（MainActivity.applySystemBars）
        // 通过 [CarWindowInsetsHolder] 回填。这样探测在"有窗口/无窗口"两种时机都
        // 拿得到同一份数据，不必让 bridge 层去依赖 Activity。
        val insets = CarWindowInsetsHolder.snapshot()
        return CarDisplayProbe(
            panelWidthPx = panelW,
            panelHeightPx = panelH,
            topInsetPx = insets.topPx,
            bottomInsetPx = insets.bottomPx,
            bottomIgnoringVisibilityPx = insets.bottomIgnoringVisibilityPx,
            statusBarWindowHeightPx = insets.statusBarWindowHeightPx,
        )
    }

    private fun resolveHeightPhysicalMm(widthPixels: Int, heightPixels: Int): Int? {
        if (widthPixels <= 0) return null
        return (DEFAULT_WIDTH_PHYSICAL_MM.toLong() * heightPixels / widthPixels).toInt()
    }

    companion object {
        /**
         * 与 `CarSessionSettings.DEFAULT_WIDTH_PHYSICAL_MM` **同源**。
         *
         * ⚠️ 动手改 `CarSessionSettings` 里那个值时，**这里必须一起改**——
         * 这是同一语义的第二份拷贝（legacy，早于 `:car:api` 抽出来之前就在这）。
         * 两处不一致 ⇒ 首帧用 200 声明、进设置后变 350，
         * iPhone 侧会看到物理尺寸跳变（界面密度重算、控件尺寸突变）。
         */
        const val DEFAULT_WIDTH_PHYSICAL_MM = 350
        const val DEFAULT_DENSITY_DPI = 160

        // 与 upstream AirPlayDisplaySettings 的合法区间一致，避免协商出越界值。
        const val MIN_WIDTH_PHYSICAL_MM = 100
        const val MAX_WIDTH_PHYSICAL_MM = 400
        const val WIDTH_PHYSICAL_MM_STEP = 50
    }
}
