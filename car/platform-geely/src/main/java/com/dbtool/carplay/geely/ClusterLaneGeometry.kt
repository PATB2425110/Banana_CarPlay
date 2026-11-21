package com.dbtool.carplay.geely

import com.dbtool.carplay.api.navigation.CarHudLane
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Pure design-coordinate geometry; no Canvas/Android dependencies. */
internal object ClusterLaneGeometry {
    const val PANEL_WIDTH = 430f
    const val ROW_HEIGHT = 84f
    const val PADDING = 18f

    data class Point(val x: Float, val y: Float)
    data class Branch(val bearing: Int, val recommended: Boolean, val path: List<Point>, val head: List<Point>)
    data class Cell(
        val sourceIndex: Int,
        val left: Float,
        val right: Float,
        val scale: Float,
        val stem: List<Point>,
        val branches: List<Branch>,
        val unknown: Boolean,
    )

    // Only the protocol bearing domain is accepted. In particular 720 and 32767
    // are not modulo-normalized into a plausible (but false) straight arrow.
    fun canonical(angle: Int): Int? {
        if (angle !in -360..360) return null
        val wrapped = if (angle < 0) angle + 360 else angle
        return if (wrapped >= 180) wrapped - 360 else wrapped
    }

    fun textHeight(lineCount: Int): Float = if (lineCount == 0) 16f else 34f + lineCount.coerceIn(0, 4) * 30f
    fun panelHeight(lineCount: Int, cells: List<Cell>): Float =
        textHeight(lineCount) + if (cells.isEmpty()) 0f else ROW_HEIGHT

    /** Sorted ordinal slots preserve even unknown/empty lanes; indices need not be contiguous. */
    fun layout(lanes: List<CarHudLane>): List<Cell> {
        val visible = lanes.sortedBy { it.index }.take(16)
        if (visible.isEmpty()) return emptyList()
        val slot = (PANEL_WIDTH - 2 * PADDING) / visible.size
        return visible.mapIndexed { ordinal, lane ->
            val left = PADDING + ordinal * slot
            val right = left + slot
            val scale = min(1f, (slot - 4f) / 66f)
            val cx = (left + right) / 2f
            fun point(x: Float, y: Float) = Point(cx + x * scale, 40f + (y - 36f) * scale)
            val highlight = lane.highlightAngle?.let(::canonical)
            val angles = lane.angles.take(8)
            val bearings = angles.mapNotNull(::canonical).distinct()
            val branches = bearings.map { bearing ->
                val localPath: List<Point>
                val dx: Float
                val dy: Float
                if (abs(bearing) >= 155) {
                    // A returning branch must be offset from the shared incoming stem.
                    val side = if (bearing > 0) 1f else -1f
                    localPath = listOf(Point(0f, 38f), Point(0f, 22f), Point(4f * side, 15f),
                        Point(13f * side, 12f), Point(22f * side, 15f), Point(26f * side, 22f), Point(26f * side, 42f))
                    dx = 0f; dy = 1f
                } else {
                    val radians = Math.toRadians(bearing.toDouble())
                    dx = sin(radians).toFloat(); dy = -cos(radians).toFloat()
                    localPath = listOf(Point(0f, 38f), Point(dx * 25f, 38f + dy * 25f))
                }
                val tip = localPath.last()
                val head = listOf(
                    Point(tip.x - dx * 8f - dy * 5f, tip.y - dy * 8f + dx * 5f),
                    tip,
                    Point(tip.x - dx * 8f + dy * 5f, tip.y - dy * 8f - dx * 5f),
                )
                Branch(bearing, highlight != null && highlight == bearing,
                    localPath.map { point(it.x, it.y) }, head.map { point(it.x, it.y) })
            }
            Cell(lane.index, left, right, scale,
                if (branches.isEmpty()) emptyList() else listOf(point(0f, 66f), point(0f, 38f)),
                branches, angles.isEmpty() || angles.any { canonical(it) == null })
        }
    }
}
