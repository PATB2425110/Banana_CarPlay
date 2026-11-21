package com.dbtool.carplay.api.projection

import android.content.Context
import android.view.View
import com.dbtool.carplay.api.display.ClusterLayerMode
import com.dbtool.carplay.api.navigation.CarNavigationSnapshot
import com.dbtool.carplay.api.media.CarNowPlaying
import com.dbtool.carplay.api.settings.CarSettingsSafeArea

/** 供仪表主题皮肤使用的车辆读数；没有的项保持 null，绝不以 0 冒充。 */
data class ClusterVitals(
    val speedKmh: Float? = null,
    val rpm: Float? = null,
    val fuel: Float? = null,
    val rangeKm: Int? = null,
    val gear: String? = null,
    val coolant: Int? = null,
    val outsideTempC: Int? = null,
)

/**
 * `:car:api` 不认识 `:car:ui` 的皮肤类，由应用层实现这个最小工厂。
 * 所有方法都在投影窗口的主线程调用。
 */

/**
 * 投影层与应用层之间的运行时配置。
 *
 * 投影实例仍由 `ClusterProjectionController` 创建；该控制器不在本次可写范围内，
 * 所以模式、主题与读数出口通过这里注入。模式在 attach 前先记在这里，
 * 窗口建立时自动取用，满足“尚未 attach 时保留待应用模式”。
 */
object ClusterLayerRuntime {
    @Volatile private var safeAreaPreview: CarSettingsSafeArea? = null
    @Volatile
    private var mode: ClusterLayerMode = ClusterLayerMode.DEFAULT

    /**
     * 导航视频层此刻是否显示（2026-10-22 解耦，P0）。
     *
     * 与 [mode] 正交：mode 决定“主题/导航谁该出现”，本值只表达导航的运行时开关。
     * 默认 `true` ⇒ 未接入新 API 的调用方行为与解耦前一致。
     */
    @Volatile
    private var navigationVisible: Boolean = true


    @Volatile
    private var vitals: () -> ClusterVitals? = { null }

    @Volatile
    private var navigation: () -> CarNavigationSnapshot? = { null }

    @Volatile
    private var nowPlaying: () -> CarNowPlaying? = { null }


    @Volatile
    private var active: CarPlayClusterProjection? = null

    fun mode(): ClusterLayerMode = mode

    /** 导航视频层当前是否显示；`false` 只影响导航层，不影响主题层与窗口生命。 */
    fun navigationVisible(): Boolean = navigationVisible


    fun vitalsProvider(): ClusterVitals? = runCatching { vitals() }.getOrNull()

    /** 当前导航快照；`null` = 没有路线 / 开关关闭。 */
    fun navigationProvider(): CarNavigationSnapshot? =
        runCatching { navigation() }.getOrNull()

    fun nowPlayingProvider(): CarNowPlaying? = runCatching { nowPlaying() }.getOrNull()
    fun safeAreaPreview(): CarSettingsSafeArea? = safeAreaPreview
    fun setSafeAreaPreview(area: CarSettingsSafeArea?) {
        safeAreaPreview = area
        active?.refreshLayerRuntime()
    }


    fun configure(
        vitalsProvider: () -> ClusterVitals?,
        /** 保持默认值可让只关心主题/读数的调用方不改签名。 */
        navigationProvider: () -> CarNavigationSnapshot? = { null },
        nowPlayingProvider: () -> CarNowPlaying? = { null },
    ) {
        vitals = vitalsProvider
        navigation = navigationProvider
        nowPlaying = nowPlayingProvider
        active?.refreshLayerRuntime()
    }

    /**
     * 导航快照变化时立即刷新已挂载的投影。
     *
     * 未挂载 / 非本投影（吉利平台 presenter）不在这里：它们本来就有 200ms
     * 刷新循环，会从 [navigationProvider] 拉到最新值。这里只把「变化瞬间」的
     * 延迟从 200ms 降到一个主线程消息。
     */
    fun notifyNavigationChanged() {
        active?.refreshLayerRuntime()
    }

    fun applyMode(next: ClusterLayerMode) {
        mode = next
        active?.applyLayerMode(next)
    }

    /**
     * 只改导航视频层的可见性：不碰主题层、不拆窗、不解绑 Surface。
     *
     * 未挂载时先记住，[register] 时补应用（与 [applyMode] 同款）。
     */
    fun applyNavigationVisible(visible: Boolean) {
        navigationVisible = visible
        active?.applyNavigationVisible(visible)
    }

    internal fun register(projection: CarPlayClusterProjection) {
        active = projection
        projection.applyLayerMode(mode)
        projection.applyNavigationVisible(navigationVisible)
    }

    internal fun unregister(projection: CarPlayClusterProjection) {
        if (active === projection) active = null
    }
}
