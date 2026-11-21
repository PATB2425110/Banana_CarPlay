package com.dbtool.carplay.ui

/**
 * 仪表「原厂全屏地图模式」在设置页的只读快照。
 *
 * ## 为什么需要它
 *
 * 仪表只显示原厂界面时，用户看不出原因是「守护没开」「本机不支持」
 * 「还在等首帧」还是「已经接管」。设置页要把这四种情况分开显示，
 * 但 `:car:ui` 不依赖平台实现，因此这里只放**纯数据**：
 *
 * - [guardEnabled] 来自设置里的灰度开关（`clusterModeGuardEnabled`）；
 * - [supported] / [lastError] / [sinceElapsedMs] 来自平台状态机快照；
 * - [phase] 直接存平台枚举的 `name`，只用于文案映射 —— 这样界面模块
 *   不必引用 `CarClusterFullMapPhase`，也就不必依赖平台实现。
 *
 * 本类型**不含任何动作**：阶段 3 只加展示（含事件历史）与开关回调，
 * 不改接管/还原的判定逻辑。
 */
data class CarClusterFullMapUi(
    /** 灰度开关是否打开。 */
    val guardEnabled: Boolean = false,
    /** 厂商接口是否可用。 */
    val supported: Boolean = false,
    /** 平台状态机的阶段名（`CarClusterFullMapPhase.name`），仅用于映射。 */
    val phase: String = "",
    /** 最近一次失败原因；null = 没有（或已清空）。 */
    val lastError: String? = null,
    /** 当前阶段已持续多久（毫秒）。 */
    val sinceElapsedMs: Long = 0L,
    /** 最近的状态变化记录（旧→新，最多 30 条）。 */
    val events: List<CarClusterFullMapEventUi> = emptyList(),
)

/** 一次状态变化在设置页展示所需的纯数据。 */
data class CarClusterFullMapEventUi(
    val kind: String,
    val atElapsedMs: Long,
    val detail: String?,
)
