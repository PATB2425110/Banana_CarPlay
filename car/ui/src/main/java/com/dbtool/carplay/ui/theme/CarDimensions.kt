package com.dbtool.carplay.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * DBPlay 尺寸令牌。
 *
 * 全部尺寸**只在本文件定义**，界面里不允许出现裸 `16.dp`。
 * 这样"整体调大调小 / 适配不同分辨率"就只改这一处。
 *
 * ## 间距：4px 栅格
 *
 * 刻度是 4 / 8 / 12 / 16 / 24 / 32 / 48 —— 只有 4 的倍数。
 * 旧版的 14 / 20 / 28 / 40 是"手感微调"累积出来的，不在栅格上，
 * 也是界面看起来对不齐的根因之一（见 `DBPlay-UI-DESIGN.md` §4）。
 *
 * ## 圆角：收敛到 8 与 12
 *
 * 主力值是 [radiusM]（按钮/输入框）与 [radiusL]（卡片）。
 * 旧版的 8 / 14 / 20 / 26 属于 TDesign 明确告诫的"柔软 SaaS 风"——
 * 圆角是组件层级的一部分，不是品牌装饰。
 *
 * ## 缩放
 *
 * 设计基准 **1280x720 @ 160dpi** → `scale = 1.0`（车机最常见的一档）。
 * 最小屏 1024x600 → `scale ≈ 0.85`；大屏 1920x1080 → `scale = 1.5`。
 * 缩放由 [CarPlayTheme] 依据实际窗口短边自动计算，见 [scaleFor]。
 *
 * **触控目标**：车机在颠簸路面 + 手指远距离伸出的场景下操作，
 * 48dp（Material 手机标准）明显不够。这里基线取 **56dp**，
 * 主操作按钮 68dp，行高 60dp。
 */
@Immutable
data class CarDimensions(
    // ---------------------------------------------------------------- 间距
    val spaceXs: Dp,
    val spaceS: Dp,
    val spaceM: Dp,
    val spaceL: Dp,
    val spaceXl: Dp,
    val spaceXxl: Dp,
    /** 页面级留白。**不用于卡片内边距**。 */
    val spaceHuge: Dp,

    // ---------------------------------------------------------------- 圆角
    /** 状态点容器、进度条、极小标记。 */
    val radiusXs: Dp,
    /** 紧凑控件、Tag。 */
    val radiusS: Dp,
    /** 按钮、输入框、分段选择（主力）。 */
    val radiusM: Dp,
    /** 卡片、面板（主力）。 */
    val radiusL: Dp,
    /** 弹窗、大容器。 */
    val radiusXl: Dp,
    val radiusPill: Dp,

    // ------------------------------------------------------------ 页面边距
    val screenPaddingH: Dp,
    val screenPaddingV: Dp,
    val contentMaxWidth: Dp,

    // ------------------------------------------------------------ 组件尺寸
    val cardPadding: Dp,
    val cardPaddingCompact: Dp,
    val rowMinHeight: Dp,
    val touchTargetMin: Dp,
    val touchTargetLarge: Dp,
    val navRailWidth: Dp,

    // ------------------------------------------------------- 设置页双栏（2026-10-20）
    /**
     * 设置页双栏**左栏**宽度。
     *
     * 定稿口径是「1920×1080@160dpi 下 320dp」。该屏短边 1080 ⇒ `scale = 1.25`
     * （`CarDimensions.scaleFor`），所以基准值存 `320 / 1.25 = 256dp`。
     * 内容宽 1400 − 页边距 60 = 1340dp；左栏 320 + 间距 30 + 右栏 990，与定稿图一致。
     */
    val settingsRailWidth: Dp,
    /**
     * 设置页左栏条目高度。定稿 58dp @scale=1.25 ⇒ 基准 46.4dp。
     *
     * 从 62dp 收到 58dp 的原因（2026-10-20）：底部出现「重连生效」按钮时，
     * 62dp 会让左栏 10 项 + 2 个底部项溢出约 31dp；58dp 后留出余量。
     *
     * 刻意**不用 [touchTargetMin]**（56dp 基准 / 70dp 实机）：左栏要在 1080p 的
     * 一屏内放下 10 个分区 + 3 个分组标签 + 底部两项，70dp 会多出约 80dp 而溢出。
     * 62dp 仍大于左栏条目实际需要的点击高度（图标 24 + 上下留白）。
     */
    val settingsRailItemHeight: Dp,
    /** 左栏条目图标边长。定稿 24dp @scale=1.25 ⇒ 基准 19.2dp。 */
    val settingsRailIconSize: Dp,
    /** 左栏选中态圆角。定稿 12dp @scale=1.25 ⇒ 基准 9.6dp。 */
    val settingsRailItemRadius: Dp,
    /**
     * 双栏断点（**不随 scale 缩放**）。
     *
     * 它是布局阈值，不是视觉尺寸：如果跟着 scale 走，屏幕越大断点反而越高，
     * 同一台 1024×600 与 1920×1080 的判定会失去可比性。
     *
     * 定稿：可用内容宽 ≥ 900dp 走双栏。1024×600（可用 964.8dp）仍双栏；
     * 800×480（可用 756.8dp）回退「索引页 + 详情页」两段式。
     */
    val settingsDualPaneMinWidth: Dp,

    val topBarHeight: Dp,
    /**
     * 顶栏**内容**（标题 / 返回键 / 右侧操作）距屏幕顶端的额外留白。
     *
     * ## 为什么需要它（2026-10-07 车主反馈）
     *
     * 车主原话：
     *
     * > 左滑进蓝牙设备页，设置按钮被顶栏遮挡
     * > 顶栏是 72px，你把设置按钮再往下移，应该能解决
     *
     * 那 72px 是**车机 SystemUI 的独立窗口**（`StatusBarWindowView{... 0,0-1920,72}`），
     * 不在我们进程里、`insets.hide(statusBars())` 也被 ROM 拒绝 ⇒ 抢不掉。
     * 本应用走的是"**避让**"路线（车主 2026-10-06 定案 A，见
     * `FullscreenInsetContractTest`）：顶部让出那条栏，画面协商 1920x1008。
     *
     * 避让由 `CarPlayApp` 根节点的 `windowInsetsPadding` 完成，`CarTopBar` 只是
     * **被动**落在 inset 之内——单看 `CarTopBar` 里那个 `top = spaceM`（12dp），
     * 会以为它顶到屏幕边上了，其实并没有。
     *
     * 所以本值**不是**用来躲状态栏的（躲它是根节点的事）。它解决的是另一件事：
     * 顶栏内容的视觉重心贴着那条栏的下沿，读起来像"被压住了"。往下再让一档，
     * 给顶栏一条自己的呼吸。
     *
     * ⚠️ 不要把它和"躲状态栏"混为一谈，更**不要**为某个页面单独加避开
     * 状态栏的分支（`FullscreenInsetContractTest` 反向钉死）。
     */
    val topBarInsetTop: Dp,

    // ------------------------------------------------------------ 图标尺寸
    val iconS: Dp,
    val iconM: Dp,
    val iconL: Dp,
    val iconXl: Dp,

    // ------------------------------------------------------------ 描边宽度
    val strokeThin: Dp,
    val strokeThick: Dp,

    // ---------------------------------------------------------------- 底栏

    /**
     * 底栏（Dock）相关尺寸。
     *
     * ## 为什么单独一组
     *
     * 底栏是**一条横贯全屏的独立条**，它的内部节奏与页面内容不同：
     * 页面用 [spaceM] 这类"内容间距"，而底栏里装的是图标槽 + 温度步进器 +
     * 媒体卡三种**并列的胶囊**，间距、图标边长、分隔线高度都要自成一套。
     * 混用页面刻度会让这条栏看起来像"页面里塞了一行控件"，
     * 而不是"系统底栏"。
     *
     * 参考形态来自车主给的截图（home + 主驾温度 + 应用行 + 媒体卡 + 副驾温度），
     * 见 `DBPlay-底栏Dock-设计方案-20261011.md`。
     */
    /**
     * 底栏高度。
     *
     * 取值来源：参考图里**应用图标只占面板高度的不到一半**
     * （实测 图标 : 面板 ≈ 1 : 2.1，元素在面板内垂直居中）。
     * 应用图标定 32dp ⇒ 面板 72dp。
     * 这条比例是"底栏看起来厚不厚"的唯一决定因素，别按页面行高（60dp）去压。
     */
    val dockBarHeight: Dp,
    /** 底栏距屏幕左右的外边距——它是**浮起**的，不贴屏幕两边。 */
    val dockBarMarginH: Dp,
    /** 底栏内部左右内边距（面板边 → 第一个条目）。 */
    val dockBarPaddingH: Dp,
    /** 底栏主行间距（home │ 温度 │ 应用行 │ 媒体卡 │ 温度 之间）。 */
    val dockBarGap: Dp,
    /** 竖分隔线高度（比条目矮，才读得出"分段"而不是"分栏"）。 */
    val dockDividerHeight: Dp,
    /** 主页图标的**图形**尺寸（比应用图标小一号，与参考图一致）。 */
    val dockHomeIcon: Dp,
    /** 底栏里箭头 / 播放 / 暂停 / 下一曲的图形尺寸（比主图标小）。 */
    val dockGlyphIcon: Dp,
    /** 应用图标边长。图里是自适应的圆角方形应用图标。 */
    val dockAppIcon: Dp,
    /**
     * 底栏自己的触摸目标尺寸（= 48dp）。
     *
     * ⚠️ **刻意小于 [touchTargetMin]（56dp）**，两条理由缺一不可：
     *
     * 1. **宽度**：底栏是一排密排条目，全用 56dp 时固定部分合计会超过屏幕
     *    （本机 1920×1080 @1.25 密度、`scale=1.2`，折合 1536dp 宽），
     *    带 `weight(1f)` 的应用行会被挤成 0 并整体溢出；
     * 2. **图形本来就小**：参考图里箭头/播放只有 ≈0.45 个图标宽，
     *    配 48dp 触摸区时视觉间距正好落在参考图的 ≈1 个图标宽上。
     *
     * 48dp 仍是 Material 的触摸下限，底栏自身高 72dp ⇒ 相邻目标不会重叠。
     */
    val dockTouchTarget: Dp,
    /**
     * 应用槽的边长（= [dockAppIcon] + 两侧各 4dp）。
     *
     * 为什么**不**用 [dockTouchTarget]（48dp）：系统那条 dock 的应用图标只有
     * 24dp 左右、间距约半个图标宽，48dp 的槽会把图标推得比参考图稀一倍。
     * 这里用 32dp，视觉间距正好落回"半个图标宽"。
     */
    val dockAppSlot: Dp,
    /**
     * 应用槽之间的间距。
     *
     * ⚠️ 不是"图标之间的视觉间距"：触摸目标是 [touchTargetMin] (56dp)，
     * 图标只有 [dockAppIcon] (40dp)，所以视觉间距 = 本值 + 两侧各 8dp。
     * 参考图实测视觉间距 ≈ 1 个图标宽（40dp）⇒ 本值 24dp。
     */
    val dockAppGap: Dp,
    /** 媒体卡专辑图边长。 */
    val dockMediaArt: Dp,
    /**
     * 媒体卡歌名/进度线宽度。
     *
     * 固定宽度而不是自适应：媒体卡是底栏里**唯一会变长**的元素，
     * 自适应会让左边的应用行跟着一起抖动（歌名一长，整条栏都在挪）。
     * 截图里歌名也是被截断的固定宽度。
     */
    val dockMediaTitleWidth: Dp,
    /** 媒体进度线高度。 */
    val dockProgressHeight: Dp,

    // ---------------------------------------------------------------- 缩放
    /** 当前生效的缩放系数，供少量需要手动换算的场景使用。 */
    val scale: Float,
) {
    companion object {
        /** 设计基准短边（dp）：1280x720 屏的短边。 */
        const val BASE_MIN_DIMENSION = 720f

        /**
         * 缩放上下限。
         *
         * 上限刻意压到 1.25 而不是更高：车机虽然屏幕大，但**观看距离**也远，
         * 尺寸需要的是"够大"而不是"按比例同步放大"。
         * 放太大会导致单屏信息量骤降、留白失控，反而更难扫视。
         */
        const val MIN_SCALE = 0.9f
        const val MAX_SCALE = 1.25f

        /**
         * 依据窗口短边推算缩放系数。
         *
         * 为什么需要：Android 的 `dp` 只保证物理尺寸一致，不保证**相对**尺寸一致。
         * 一台 1024x600 的车机和一台 1920x1080 的车机，若都用同一套 dp 值，
         * 后者上的文字和按钮会显得只有前者的一半大。
         * 车机环境的屏幕尺寸差异极大，因此必须按短边做整体缩放。
         */
        fun scaleFor(minDimensionDp: Float): Float {
            if (minDimensionDp <= 0f) return 1f
            return (minDimensionDp / BASE_MIN_DIMENSION).coerceIn(MIN_SCALE, MAX_SCALE)
        }

        /** 按缩放系数生成一套尺寸。 */
        fun of(scale: Float): CarDimensions {
            fun d(value: Float): Dp = (value * scale).dp
            return CarDimensions(
                spaceXs = d(4f),
                spaceS = d(8f),
                spaceM = d(12f),
                spaceL = d(16f),
                spaceXl = d(24f),
                spaceXxl = d(32f),
                spaceHuge = d(48f),

                radiusXs = d(2f),
                radiusS = d(4f),
                radiusM = d(8f),
                radiusL = d(12f),
                radiusXl = d(16f),
                radiusPill = d(999f),

                screenPaddingH = d(24f),
                screenPaddingV = d(20f),
                contentMaxWidth = d(1120f),

                cardPadding = d(20f),
                cardPaddingCompact = d(12f),
                rowMinHeight = d(60f),
                touchTargetMin = d(56f),
                touchTargetLarge = d(68f),
                navRailWidth = d(96f),

                settingsRailWidth = d(256f),        // 320dp @scale=1.25
                settingsRailItemHeight = d(46.4f),  // 58dp @scale=1.25
                settingsRailIconSize = d(19.2f),    // 24dp @scale=1.25
                settingsRailItemRadius = d(9.6f),   // 12dp @scale=1.25
                settingsDualPaneMinWidth = 900.dp,  // 布局断点，不缩放

                topBarHeight = d(64f),
                // 一档 4px 栅格 → 12dp（基准 1280x720）；1080p 车机上 scale=1.25 ⇒ 15dp。
                // 为什么取 spaceM 这一档而不是更大：顶栏已经由根节点避让了两条系统栏，
                // 这里只是让内容离那条栏的下沿再远一点，**不是**第二次避让。
                topBarInsetTop = d(12f),

                iconS = d(16f),
                iconM = d(22f),
                iconL = d(28f),
                iconXl = d(40f),

                strokeThin = d(1f),
                strokeThick = d(2f),

                // 真值来自系统 dock 反编译产物（`aapt2 dump resources oneOS_Dock.apk`）：
                // dock_bar_height=50dp、dock_pop_window_margin_horizontal=48dp。
                dockBarHeight = d(50f),
                dockBarMarginH = d(48f),
                dockBarPaddingH = d(24f),
                dockBarGap = d(20f),
                dockDividerHeight = d(24f),
                dockHomeIcon = d(18f),
                dockGlyphIcon = d(12f),
                dockAppIcon = d(24f),
                dockTouchTarget = d(48f),
                dockAppSlot = d(32f),
                dockAppGap = d(4f),
                dockMediaArt = d(24f),
                dockMediaTitleWidth = d(48f),
                dockProgressHeight = d(2f),

                scale = scale,
            )
        }
    }
}
