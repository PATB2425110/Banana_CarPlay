package com.dbtool.carplay.api.display

/**
 * 仪表窗口在**导航层单独关闭**时的去留判据（2026-10-22 解耦，P0）。
 *
 * ## 为什么值得单独存在
 *
 * “关导航”以前等于“拆整窗”（`CarAppController.hideClusterProjection` →
 * `stopCluster`），于是主题层跟着消失。解耦后窗口是两个模块的**共用容器**：
 * 只要还有一个模块要求显示，窗口就必须保留。
 *
 * 本对象是纯函数、无 Android 依赖，直接钉住三条规则：
 * 1. 导航关闭、主题仍要显示 ⇒ **保留窗口**（不拆窗、不解绑 Surface）；
 * 2. 导航关闭、主题也不需要 ⇒ 可以拆窗（把仪表还给车机）；
 * 3. 导航仍要显示 ⇒ 永远不因主题关闭而拆窗。
 */
object ClusterLayerVisibilityPolicy {

    /**
     * 导航层关闭时，窗口是否必须留给主题层。
     *
     * @param mode 当前三态显示模式（只取 [ClusterLayerMode.showsTheme]）
     * @param themeAvailable 主题是否真的解析成功（主题文件缺失时保留空窗没有意义）
     * @param windowAttached 窗口当前是否挂在屏上
     */
    fun shouldRetainWindowForTheme(
        mode: ClusterLayerMode,
        themeAvailable: Boolean,
        windowAttached: Boolean,
    ): Boolean = windowAttached && mode.showsTheme && themeAvailable

    /**
     * 两个图层都不需要时，是否应该主动拆窗。
     *
     * @param navigationVisible 导航层此刻是否可见（运行时开关，不是模式）
     * @param windowAttached 窗口当前是否挂在屏上
     */
    fun shouldTearDownWindow(
        mode: ClusterLayerMode,
        navigationVisible: Boolean,
        windowAttached: Boolean,
    ): Boolean = windowAttached && !mode.showsTheme && !navigationVisible
}
