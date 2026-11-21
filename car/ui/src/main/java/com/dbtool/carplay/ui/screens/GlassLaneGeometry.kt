package com.dbtool.carplay.ui.screens

import kotlin.math.cos
import kotlin.math.sin

internal data class LanePoint(val x: Float, val y: Float)

/** The lane row keeps its existing region; every visible arrow has the same top and baseline. */
internal object GlassLaneLayout {
    const val LEFT = 188f
    const val WIDTH = 326f
    const val TOP = 164f
    const val BOTTOM = 212f
    const val HEIGHT = BOTTOM - TOP
    const val SLOT_GAP = 2f

    fun slotWidth(laneCount: Int): Float = WIDTH / laneCount.coerceAtLeast(1)
    fun centerX(index: Int, laneCount: Int): Float = LEFT + (index + 0.5f) * slotWidth(laneCount)

    /** One scale for the ENTIRE row: a left/straight/right group must not get shorter alone. */
    fun rowScale(laneCount: Int, maximumHalfWidth: Float): Float =
        if (maximumHalfWidth <= 0f) 1f else
            ((slotWidth(laneCount) - SLOT_GAP) / (2f * maximumHalfWidth)).coerceIn(0f, 1f)
}

/** Pixel bounds measured from a cached copy of the very same OSN large-direction vector. */
internal data class GlassLaneInk(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val stemCenterX: Float,
) {
    val scaleToLaneHeight: Float get() = GlassLaneLayout.HEIGHT / (bottom - top).coerceAtLeast(1)
    val halfWidth: Float get() =
        maxOf(stemCenterX - left, right - stemCenterX) * scaleToLaneHeight

    fun leftAt(centerX: Float, rowScale: Float): Float = centerX - stemCenterX * scaleToLaneHeight * rowScale
    fun topAt(rowScale: Float): Float = GlassLaneLayout.BOTTOM - bottom * scaleToLaneHeight * rowScale
}

/**
 * Exact-angle fallback only for bearings without an existing large OSN resource. Uses the large
 * straight arrow's filled 64-unit head profile and an angular, upright shaft, never the old cubic
 * lane curve. Cardinal/diagonal/U-turn directions use the actual large-arrow vector resources.
 */
internal data class GlassLaneBranch(
    val start: LanePoint,
    val bend: LanePoint,
    val neck: LanePoint,
    val tip: LanePoint,
    val heading: LanePoint,
    val head: List<LanePoint>,
)

internal fun glassLaneBranch(bearing: Int): GlassLaneBranch? {
    val angle = glassLaneBearing(bearing) ?: return null
    val radians = Math.toRadians(angle.toDouble())
    val direction = LanePoint(sin(radians).toFloat(), -cos(radians).toFloat())
    val side = LanePoint(cos(radians).toFloat(), sin(radians).toFloat())
    val bend = LanePoint(0f, -36f)
    val tip = LanePoint(bend.x + direction.x * 26f, bend.y + direction.y * 26f)
    fun point(across: Float, back: Float) =
        LanePoint(tip.x + side.x * across - direction.x * back,
            tip.y + side.y * across - direction.y * back)
    return GlassLaneBranch(
        start = LanePoint(0f, 0f),
        bend = bend,
        neck = point(0f, 15.3144f),
        tip = tip,
        heading = direction,
        head = listOf(
            point(0f, 0f), point(-17.2284f, 17.2284f), point(-11.5716f, 22.8853f),
            point(-4f, 15.3144f), point(4f, 15.3144f), point(11.5716f, 22.8853f),
            point(17.2284f, 17.2284f),
        ),
    )
}
