package com.dbtool.carplay.ui.navigation

import androidx.compose.ui.graphics.vector.ImageVector
import com.dbtool.carplay.ui.theme.CarIcons

/**
 * 应用内可到达的页面。
 *
 * 设置页刻意接受一个"聚焦分区"参数：首页的"音频"快捷卡直接跳到
 * 音频分区，而不是把音频设置再复制一份到首页——设置项只有一处定义，
 * 避免同一开关在两个页面出现、改一处漏一处。
 */
sealed interface CarDestination {
    val label: String

    val icon: ImageVector

    /**
     * 稳定路由标识。用于导航栏选中态匹配——
     * 不能直接用对象相等，因为 `Settings(focus = AUDIO)` 与 `Settings()`
     * 是同一个页面，但 `equals` 不成立。
     */
    val routeKey: String

    data object Home : CarDestination {
        override val label = "主页"
        override val icon = CarIcons.Home
        override val routeKey = "home"
    }

    data object CarPlay : CarDestination {
        override val label = "CarPlay"
        override val icon = CarIcons.Display
        override val routeKey = "carplay"
    }

    /** Local iPhone route guidance; intentionally not a main navigation entry. */
    data object Navigation : CarDestination {
        override val label = "导航"
        override val icon = CarIcons.Location
        override val routeKey = "navigation"
    }

    /** 设置页。[focus] 指定进入后直接打开的分区。 */
    data class Settings(val focus: CarSettingsSection? = null) : CarDestination {
        override val label = "设置"
        override val icon = CarIcons.Settings
        override val routeKey = "settings"
    }

    data object About : CarDestination {
        override val label = "关于"
        override val icon = CarIcons.Info
        override val routeKey = "about"
    }

    /**
     * 开发者模式（隐藏页）。
     *
     * **刻意不进 [mainEntries]**：它的唯一入口是「关于」页的版本号连点 7 次。
     * 一旦进了主导航，普通用户就会看见一个满是设备号与日志按钮的页面。
     */
    data object Developer : CarDestination {
        override val label = "开发者模式"
        override val icon = CarIcons.Log
        override val routeKey = "developer"
    }

    companion object {
        /**
         * 主导航条目。
         *
         * 2026-10-11：`Devices` 已删除（设备内容并入「设置 → 诊断」），
         * 设备不再是一个可直达的顶层页面。
         */
        val mainEntries: List<CarDestination> = listOf(Home, CarPlay, Settings(), About)
    }
}

/**
 * 设置页分区。顺序即页面呈现顺序。
 *
 * ## 2026-10-11 合并：11 → 7
 *
 * 二开过程中分区是**按上游菜单逐条搬**的（上游 8 个：Connection / Location /
 * Startup / Audio / Identity & appearance / Display & video / Window /
 * Diagnostics），搬完又补了「方向盘与按键」「媒体卡片」「仪表导航」三个
 * 单能力分区 ⇒ 索引页涨到 11 条，其中四个各自只有一个开关，用户要滚一屏
 * 才看得全；而且「显示与视频」与「窗口」都在管系统栏、两处都能改安全区。
 *
 * 现在按"一页管一件事"重新归并（合并理由见
 * `DBPlay-UI精简与分区合并方案-20261011.md` §2）：
 *
 * | 分区 | 来源 |
 * | --- | --- |
 * | 连接 | Connection（瘦身） |
 * | 显示 | Display & video + Window |
 * | 仪表导航 | 原单能力分区「仪表导航」+ 副屏投屏 + HUD 桥（2026-10-11 曾改名「仪表与HUD」，2026-10-19 恢复原名） |
 * | 车机配置 | Audio + 方向盘与按键 + 媒体卡片 + Location |
 * | 启动 | Startup |
 * | 身份与外观 | Identity & appearance |
 * | 诊断 | Diagnostics + 设备页并入 |
 *
 * ⚠️ 安全区有两块互不影响的矩形：主屏在「显示 → 主屏安全区」
 * （`mainSafeArea`），仪表在「仪表导航 → 导航安全区」（`safeArea`）。
 *
 * 刻意**不包含"关于"**：关于是一个独立页面（导航栏可直达），
 * 设置索引页里只放一个跳转入口。这样关于内容只有一处定义，
 * 不会出现"设置里的版本号和关于页不一致"这种低级问题。
 */
enum class CarSettingsSection(val title: String) {
    CONNECTION("连接"),
    DISPLAY("显示"),
    /**
     * 仪表导航（副屏投屏 + 安全区）。
     *
     * 2026-10-19：显示名由「仪表与HUD」恢复为「仪表导航」。名称里不再带 HUD ——
     * HUD 导航推送已于 2026-10-18 独立成 [HUD] 分区，两者本来就互不依赖。
     *
     * 2026-10-10 补回：这一分区在上游/原版是独立页面（原版是
     * `GeelyClusterSettingsActivity`「仪表地图与安全区域」），本工程迁移时
     * 没有把入口做出来 ⇒ `CarSessionSettings.projectionEnabled` /
     * `projectionEnabled` 在界面上无处可改，用户只能吃默认值（默认 false
     * ⇒ 仪表永远不投）。2026-10-20 起投屏目标固定优先使用 displayId=2，
     * 历史 `clusterDisplayId` 仅做诊断留痕。
     */
    CLUSTER("投屏仪表"),
    IMMERSIVE_NAVIGATION("沉浸式导航（与仪表投屏互斥）"),
    /**
     * HUD 导航提示（2026-10-18）。
     *
     * 与「仪表导航」**并列**的独立版块：后者管副屏窗口/安全区/投屏，这一项只管
     * iPhone 的转向提示推给车机自带地图服务。两者互不依赖，原先挤在仪表组最后
     * 一块，用户要翻过整个投屏页才能拨到它。
     */
    HUD("原车仪表卡片 / HUD"),
    /**
     * 仪表主题（P1）。
     *
     * 与「仪表导航」**并列**而不是塞进它：后者管的是副屏窗口 / 安全区 / 投屏触发，
     * 这一项只管"皮肤长什么样"。两件事的生命周期也不同（主题是纯数据，改完下次绘制生效）。
     */
    /**
     * 仪表「全屏模式」详情页（开关 + 当前状态 + 事件历史）。
     *
     * ⚠️ **刻意不在索引页单独列一行**：它的入口是「仪表导航」下方那行
     * 「全屏模式」，避免索引页多出一条同义入口。
     */
    /** 音频 / 方向盘按键 / 媒体卡片 / 位置共享 —— 四个车机侧功能开关收成一页。 */
    VEHICLE("车机"),
    IDENTITY("设置"),
    DIAGNOSTICS("诊断"),
    /** 2026-10-22 车主：设置里加赞助入口。 */
}
