package com.dbtool.carplay.api.settings

import com.dbtool.carplay.api.display.ClusterLayerMode

/**
 * 图层模式的进程内当前值与持久化出口。
 *
 * 模式刻意**不进入** `CarSessionSettings` 的构造字段：它不参与 CarPlay 握手，
 * 改动必须立即生效；同时 `CarSessionSettings` 的诊断白名单由另一个模块维护，
 * 把“显示模式”混进握手快照会让诊断输出与重连语义都被动多出一项。
 */
object ClusterLayerModeSetting {
    @Volatile
    private var current: ClusterLayerMode = ClusterLayerMode.CARPLAY_ONLY

    @Volatile
    private var persist: ((ClusterLayerMode) -> Unit)? = null

    @Volatile
    private var listener: ((ClusterLayerMode) -> Unit)? = null

    fun current(): ClusterLayerMode = current

    /** 读取时调用：缺省或非法值由 [ClusterLayerMode.fromStorage] 回落 BOTH。 */
    fun restore(raw: String?) {
        // Theme layers do not exist in this fork. Legacy stored modes cannot
        // hide the video or trigger a theme-only instrument window.
        current = ClusterLayerMode.CARPLAY_ONLY
        listener?.invoke(current)
    }

    /** 用户选择时调用：先更新进程内值，再落盘并通知投影层。 */
    fun set(mode: ClusterLayerMode) {
        current = ClusterLayerMode.CARPLAY_ONLY
        runCatching { persist?.invoke(current) }
        listener?.invoke(current)
    }

    /** SharedPreferences 存储实现注册落盘出口。 */
    fun bindPersistence(writer: (ClusterLayerMode) -> Unit) {
        persist = writer
    }

    /** 应用层注册即时生效出口；只有一个投影层监听者。 */
    fun bindListener(consumer: ((ClusterLayerMode) -> Unit)?) {
        listener = consumer
    }
}
