package com.dbtool.carplay.api.display

/**
 * 仪表副屏的图层显示模式。
 *
 * 持久化值就是枚举名；读取时必须经过 [fromStorage]，非法值回落 [BOTH]。
 */
enum class ClusterLayerMode {
    /** CarPlay 画面与仪表主题同时显示，视频内缩到中间安全区。 */
    BOTH,

    /** 只显示 CarPlay 画面，铺满整块仪表面板。 */
    CARPLAY_ONLY,

    /** 只显示仪表主题；视频视图不可见，但流与窗口保持不动。 */
    THEME_ONLY,
    ;

    /**
     * 该模式是否要求**主题层**可见。
     *
     * 2026-10-22 解耦：主题与导航的可见性不再共用一个“三选一”结论。
     * 本属性只回答主题这一半；导航那一半见 [showsNavigation]。
     * 两者叠加运行时开关（导航投屏是否开启）才是最终图层计划。
     */
    val showsTheme: Boolean
        get() = this == BOTH || this == THEME_ONLY

    /**
     * 该模式是否要求**导航视频层**可见。
     *
     * 注意：这里只表达“模式允许导航层出现”，实际是否显示还要叠加
     * `navigationVisible`（用户此刻有没有开启导航投屏）。见
     * `ClusterLayerComposition.layoutFor(themeVisible, navigationVisible, …)`。
     */
    val showsNavigation: Boolean
        get() = this == BOTH || this == CARPLAY_ONLY

    companion object {
        /** 非法或缺省值统一回落的模式。 */
        // 2026-10-21 斌哥口径：显示模式默认「仅 CarPlay」（不抢仪表主题层）
        val DEFAULT: ClusterLayerMode = CARPLAY_ONLY

        /** 把 SharedPreferences 中的原始字符串解析成模式，永不抛异常。 */
        fun fromStorage(raw: String?): ClusterLayerMode =
            entries.firstOrNull { it.name == raw } ?: DEFAULT
    }
}
