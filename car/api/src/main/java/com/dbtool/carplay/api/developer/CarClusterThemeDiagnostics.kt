package com.dbtool.carplay.api.developer

/**
 * 诊断包里 `clusterThemeSource` 一行所需的数据。
 *
 * 放在 `:car:api` 是因为 `CarDiagnosticSettingsDump` 在这里，而真正的
 * 云端缓存读取在 `:car:core-bridge`（`ClusterThemeRepository`）。
 * 依赖方向只能是 core-bridge → api，所以这里只放纯数据。
 */
data class CarClusterThemeDiagnostics(
    /** 当前配置的主题 id（`CarSessionSettings.clusterThemeId`）。 */
    val activeThemeId: String = "",
    /** 当前主题是否来自云端缓存。 */
    val activeThemeFromCloud: Boolean = false,
    /** 本地缓存里可用的云端主题数量。 */
    val cloudThemeCount: Int = 0,
    /** 上次成功同步时间（epoch millis）；0 = 从未同步。 */
    val lastSyncAtMillis: Long = 0L,
)
