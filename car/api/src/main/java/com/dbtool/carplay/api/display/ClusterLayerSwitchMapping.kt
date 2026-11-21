package com.dbtool.carplay.api.display

/**
 * 仪表上两个图层的**用户开关状态**。
 *
 * 2026-10-23 分区重划：UI 不再让用户选一个同时决定两层的三态枚举，而是
 * 主题页一个开关、导航页一个开关。本类型就是这两个开关的纯数据形态。
 */
data class ClusterLayerSwitches(
    /** 主题层是否要求显示（对应 `ClusterLayerMode.showsTheme`）。 */
    val themeVisible: Boolean,
    /** 导航层是否要求显示（模式允许导航 + 用户打开了导航投屏）。 */
    val navigationVisible: Boolean,
)

/**
 * 两个开关落到**现有持久化模型**上的目标值。
 *
 * 刻意不新增字段：主题开关落在 [ClusterLayerMode]，导航开关落在既有的
 * `CarSessionSettings.projectionEnabled`。老配置因此可以原样读取。
 */
data class ClusterLayerSwitchTarget(
    val mode: ClusterLayerMode,
    val projectionEnabled: Boolean,
)

/**
 * 两个独立开关 ↔ 现有三态模式 + 投屏总开关的双向映射。
 *
 * ## 读取（老配置 → 两个开关）
 *
 * `themeOn = mode.showsTheme`；
 * `navOn = mode.showsNavigation && projectionEnabled`。
 *
 * 后一半必须叠加 `projectionEnabled`：P0 解耦后「双层显示 + 关闭投屏」
 * 的运行时状态就是“主题开、导航收起”，只有这样才能原样映射，不会在
 * 用户下次拨开关时把导航意外打开。
 *
 * ## 写入（两个开关 → 现有模型）
 *
 * | 主题 | 导航 | mode | projectionEnabled |
 * |---|---|---|---|
 * | 关 | 关 | `CARPLAY_ONLY` | false |
 * | 关 | 开 | `CARPLAY_ONLY` | true |
 * | 开 | 关 | `THEME_ONLY` | false |
 * | 开 | 开 | `BOTH` | true |
 *
 * 三态模式本身无法表达“两层都关”，所以“都关”必须由
 * `CARPLAY_ONLY + projectionEnabled=false` 共同表达；调用方在关闭时还要
 * 走既有的 `setClusterVisible(false)`，由 P0 的拆窗判据收掉窗口。
 */
object ClusterLayerSwitchMapping {

    /** 把现有模式 + 投屏总开关读成两个独立开关。 */
    fun read(mode: ClusterLayerMode, projectionEnabled: Boolean): ClusterLayerSwitches =
        ClusterLayerSwitches(
            themeVisible = mode.showsTheme,
            navigationVisible = mode.showsNavigation && projectionEnabled,
        )

    /** 把两个独立开关写成现有模式 + 投屏总开关。 */
    fun write(themeVisible: Boolean, navigationVisible: Boolean): ClusterLayerSwitchTarget =
        ClusterLayerSwitchTarget(
            mode = when {
                themeVisible && navigationVisible -> ClusterLayerMode.BOTH
                themeVisible -> ClusterLayerMode.THEME_ONLY
                else -> ClusterLayerMode.CARPLAY_ONLY
            },
            projectionEnabled = navigationVisible,
        )
}
