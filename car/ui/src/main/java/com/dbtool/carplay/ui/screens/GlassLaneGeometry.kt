package com.dbtool.carplay.ui.screens

import kotlin.math.cos
import kotlin.math.sin

internal data class LanePoint(val x: Float, val y: Float)
internal data class GlassLaneBranch(
    val start: LanePoint,
    val control1: LanePoint,
    val control2: LanePoint,
    val tip: LanePoint,
    val heading: LanePoint,
)

/** One coordinate system for every branch, independent of recommendation/color.
 * The vertical stem is drawn once; only the branch bends, never the entire silhouette.
 */
internal fun glassLaneBranch(bearing: Int): GlassLaneBranch? {
    val angle = glassLaneBearing(bearing) ?: return null
    val start = LanePoint(0f, 4f)
    if (kotlin.math.abs(angle) >= 155) {
        val side = if (angle > 0) 1f else -1f
        return GlassLaneBranch(start, LanePoint(0f, -28f),
            LanePoint(18f * side, -28f), LanePoint(18f * side, 1f), LanePoint(0f, 1f))
    }
    val radians = Math.toRadians(angle.toDouble())
    val direction = LanePoint(sin(radians).toFloat(), -cos(radians).toFloat())
    val tip = LanePoint(direction.x * 19f, -9f + direction.y * 13f)
    return GlassLaneBranch(start, LanePoint(0f, -8f),
        LanePoint(tip.x - direction.x * 9f, tip.y - direction.y * 9f), tip, direction)
}
