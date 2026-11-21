package com.dbtool.carplay.ui.screens

import com.dbtool.carplay.ui.CarClusterLayerStatus

/**
 * 仪表图层状态文案（2026-10-23，B2）。
 *
 * 抽成纯函数是为了能单测“导航层已收起时不能报警”这条规则（B4）：
 * 以前界面只看 `attached && !firstFrame`，P0 解耦后「双层 + 关投屏」和
 * 「仅主题」都会命中，主题明明在显示却被报成“窗上挂着，但没有画面”。
 */

/** 主题层状态：只有“用户要求 + 主题解析成功 + 窗口已挂”才算显示中。 */
internal fun clusterThemeLayerText(status: CarClusterLayerStatus): String = when {
    status.themeVisible -> "显示中"
    status.themeRequested && !status.themeAvailable -> "未显示（主题不可用）"
    status.themeRequested -> "未显示（窗口未挂）"
    else -> "已关闭"
}

/**
 * 导航层状态。
 *
 * 顺序很重要：先看“用户要不要导航”（不要就是收起，窗口可能正在为主题保留），
 * 再看窗口/首帧，最后才是运行时导航层开关。
 */
internal fun clusterNavigationLayerText(status: CarClusterLayerStatus): String = when {
    !status.navigationRequested -> "已收起"
    // 运行时已被收起（导航结束 / 手动收起）时，即使窗口还挂着、没有首帧，
    // 也不能报「无画面」——窗口可能正为主题层保留。
    !status.navigationVisible -> "已收起"
    !status.windowAttached -> "无画面（窗口未挂）"
    !status.firstFrameShown -> "无画面（等待首帧）"
    else -> "显示中"
}

/** 仪表窗口状态（只读）。 */
internal fun clusterWindowText(status: CarClusterLayerStatus): String =
    if (status.windowAttached) "已挂" else "未挂"

/** 两个开关组合派生出的可读模式，用来替代原来那个可点的三态控件。 */
internal fun clusterLayerCombinationText(status: CarClusterLayerStatus): String = when {
    status.themeRequested && status.navigationRequested -> "双层显示（主题 + 导航）"
    status.themeRequested -> "仅仪表主题"
    status.navigationRequested -> "仅导航画面（CarPlay）"
    else -> "两层都关闭"
}

/**
 * 「窗上挂着，但没有画面」只在**导航层确实要求显示**时才允许出现（B4）。
 *
 * 主题保留、导航收起时窗口仍然挂着且没有首帧，这是 P0 后的正常状态，
 * 绝不能报成黑屏故障。
 */
internal fun clusterNavigationWarningVisible(
    status: CarClusterLayerStatus,
    windowAttached: Boolean,
    firstFrameShown: Boolean,
): Boolean = status.navigationVisible && windowAttached && !firstFrameShown
