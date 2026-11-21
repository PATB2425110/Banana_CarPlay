package com.dbtool.carplay.ui

/**
 * 仪表转向卡「内容对齐」档位的展示标签（2026-10-11）。
 *
 * ## ⚠️ 顺序是与协议绑定的，不是随便排的
 *
 * `CarSessionSettings.clusterAlignment` 存的是**下标**，解释它的顺序在
 * `shared` 的 `CarPlayClusterDisplay.MANEUVER_LAYOUTS`
 * （`leftAligned / rightAligned / topAligned`）。
 * 那个列表一旦改序，这里必须同步改，否则"用户选左侧、实际发的是顶部"。
 *
 * ## 为什么在本模块复制一份标签
 *
 * `car:ui` 只依赖 `:car:api`（不依赖 `:shared`），拿不到那个列表。
 * 与 `CarMfiTargetLabels` / `CarBluetoothHandoffLabels` 同一处理方式 ——
 * 展示文案留在 UI 层，协议常量留在各自该在的层。
 */
val CAR_CLUSTER_ALIGNMENT_LABELS: List<String> = listOf("左侧", "右侧", "顶部")

/** 取标签；越界值夹到合法范围（设置项可能来自旧版本或手改的 prefs）。 */
fun carClusterAlignmentLabel(index: Int): String =
    CAR_CLUSTER_ALIGNMENT_LABELS[index.coerceIn(0, CAR_CLUSTER_ALIGNMENT_LABELS.lastIndex)]
