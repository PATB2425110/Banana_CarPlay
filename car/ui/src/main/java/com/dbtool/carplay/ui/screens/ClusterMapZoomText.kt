package com.dbtool.carplay.ui.screens

/**
 * 仪表地图缩放入口的文案与可用性判断。
 *
 * 链路（`CarUiActions.zoomProjectionMap` → `CarAppController` → core-bridge）
 * 早已存在，这里只服务于设置页的入口：抽成纯函数，既能被单测钉住文案，
 * 也能让可用性判断与脚注文案共用同一处定义。
 */

/** 缩放按钮是否可用：投屏功能关着时无意义，置灰。 */
fun clusterMapZoomEnabled(projectionEnabled: Boolean): Boolean = projectionEnabled

/** 索引页/详情页的一行说明。 */
fun clusterMapZoomHint(projectionEnabled: Boolean): String =
    if (projectionEnabled) "使用下方放大 / 缩小按钮调整仪表地图"
    else "需先开启仪表投屏"

/** 手势成功后的短提示（用于设置页/日志对照）。 */
fun clusterMapZoomStepText(zoomIn: Boolean): String =
    if (zoomIn) "已放大一步" else "已缩小一步"
