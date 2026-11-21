package com.dbtool.carplay.api.display

import kotlin.math.abs

/**
 * HUD 配置档。
 *
 * 这里只有可持久化、可比较的纯数据，不创建窗口、不查询 display，也不持有 Context。
 */
data class CarHudDisplayProfile(
    val enabled: Boolean = false,
    val mode: CarHudDisplayMode = CarHudDisplayMode.MIRROR_110,
    val crop: HudCropSpec = HudCropSpec(),
    val scale: HudScaleSpec = HudScaleSpec(),
    val placement: HudPlacementSpec? = null,
    val edgeMask: HudEdgeMaskSpec = HudEdgeMaskSpec(),
    val expectation: HudDisplayExpectation? = null,
)

/**
 * 目标 display 的期望特征。
 *
 * 宽高允许 ±[geometryTolerancePercent]；宽高比允许 ±[aspectTolerancePercent]。
 * [densityDpi] 与 [refreshRateHz] 是可选期望：尚未由 0A 补齐时保持 null。
 * flags 使用 Android `Display` 的位定义数值，但本文件不引用 Android 类型。
 */
data class HudDisplayExpectation(
    val widthPixels: Int,
    val heightPixels: Int,
    val densityDpi: Int? = null,
    val refreshRateHz: Float? = null,
    val requiredFlagsMask: Int = 0,
    val forbiddenFlagsMask: Int = 0,
    val geometryTolerancePercent: Int = 3,
    val aspectTolerancePercent: Int = 2,
) {
    init {
        require(widthPixels > 0) { "widthPixels must be > 0" }
        require(heightPixels > 0) { "heightPixels must be > 0" }
        require(densityDpi == null || densityDpi > 0) { "densityDpi must be > 0" }
        require(refreshRateHz == null || refreshRateHz > 0f) {
            "refreshRateHz must be > 0"
        }
        require(geometryTolerancePercent >= 0) {
            "geometryTolerancePercent must be >= 0"
        }
        require(aspectTolerancePercent >= 0) {
            "aspectTolerancePercent must be >= 0"
        }
    }

    /**
     * 比较真实尺寸、宽高比，以及调用方已知的 dpi/refresh。
     *
     * 未知的真实值不会单独导致失败；未知不能伪装成匹配，最终路由仍要保留
     * 其它判别证据并等待 0A/现场确认。
     */
    fun matchesGeometry(
        actualWidthPixels: Int,
        actualHeightPixels: Int,
        actualDensityDpi: Int? = null,
        actualRefreshRateHz: Float? = null,
    ): Boolean {
        if (actualWidthPixels <= 0 || actualHeightPixels <= 0) return false
        if (
            !withinPercent(
                actualWidthPixels.toDouble(),
                widthPixels.toDouble(),
                geometryTolerancePercent,
            )
        ) {
            return false
        }
        if (
            !withinPercent(
                actualHeightPixels.toDouble(),
                heightPixels.toDouble(),
                geometryTolerancePercent,
            )
        ) {
            return false
        }
        val expectedAspect = widthPixels.toDouble() / heightPixels.toDouble()
        val actualAspect = actualWidthPixels.toDouble() / actualHeightPixels.toDouble()
        if (!withinPercent(actualAspect, expectedAspect, aspectTolerancePercent)) {
            return false
        }
        if (densityDpi != null && actualDensityDpi != null && densityDpi != actualDensityDpi) {
            return false
        }
        if (
            refreshRateHz != null &&
            actualRefreshRateHz != null &&
            !withinPercent(actualRefreshRateHz, refreshRateHz, geometryTolerancePercent)
        ) {
            return false
        }
        return true
    }

    fun matchesDisplayFlags(displayFlags: Int): Boolean {
        val hasRequired = displayFlags and requiredFlagsMask == requiredFlagsMask
        val hasForbidden = displayFlags and forbiddenFlagsMask != 0
        return hasRequired && !hasForbidden
    }

    private fun withinPercent(actual: Double, expected: Double, percent: Int): Boolean {
        if (!actual.isFinite() || !expected.isFinite() || expected == 0.0) return false
        return abs(actual - expected) <= abs(expected) * percent / 100.0
    }

    private fun withinPercent(actual: Float, expected: Float, percent: Int): Boolean =
        withinPercent(actual.toDouble(), expected.toDouble(), percent)
}
