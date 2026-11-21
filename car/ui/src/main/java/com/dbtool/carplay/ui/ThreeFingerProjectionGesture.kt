package com.dbtool.carplay.ui

import kotlin.math.abs

/** Local gesture only. Never expands the two-contact CarPlay HID descriptor. */
class ThreeFingerProjectionGesture(private val distancePx: Float) {
    enum class Action { DOWN, POINTER_DOWN, MOVE, POINTER_UP, UP, CANCEL }
    data class Point(val id: Int, val x: Float, val y: Float)
    data class Result(val consume: Boolean, val releasePhone: Boolean = false, val showProjection: Boolean? = null, val reason: String? = null)
    private var consuming = false
    private var cancelled = false
    private var fired = false
    private var startedAt = 0L
    private var origin = emptyMap<Int, Point>()
    private var rejectedReason = "short_or_vertical"

    fun onEvent(action: Action, points: List<Point>, timeMs: Long): Result {
        if (action == Action.DOWN) reset()
        if (action == Action.CANCEL || action == Action.UP) {
            val result = Result(consuming, reason = if (consuming && !fired) {
                if (action == Action.CANCEL) "cancelled_by_dispatch" else rejectedReason
            } else null)
            reset()
            return result
        }
        var release = false
        if (!consuming && points.size > 2) {
            consuming = true
            release = true // Release any first two contacts already forwarded.
            startedAt = timeMs
            if (points.size == 3 && action == Action.POINTER_DOWN) origin = points.associateBy { it.id }
            else { cancelled = true; rejectedReason = "missing_three_pointer_down" }
        }
        if (!consuming) return Result(false)
        if (points.size != 3) { cancelled = true; rejectedReason = "pointer_count_changed" }
        if (timeMs - startedAt !in 0..1500) { cancelled = true; rejectedReason = "timeout" }
        // POINTER_UP still carries all three coordinates before removing the
        // lifted pointer. Evaluate that last sample before cancelling the rest.
        if (cancelled || fired || (action != Action.MOVE && action != Action.POINTER_UP)) return Result(true, release)
        val deltas = points.map { point ->
            val start = origin[point.id] ?: return Result(true, release).also { cancelled = true; rejectedReason = "pointer_identity_changed" }
            (point.x - start.x) to (point.y - start.y)
        }
        val left = deltas.all { (dx, dy) -> dx <= -distancePx && abs(dy) <= abs(dx) * 0.65f }
        val right = deltas.all { (dx, dy) -> dx >= distancePx && abs(dy) <= abs(dx) * 0.65f }
        if (!left && !right) {
            if (action == Action.POINTER_UP) { cancelled = true; rejectedReason = "lift_before_threshold" }
            return Result(true, release)
        }
        fired = true
        return Result(true, release, showProjection = left, reason = if (left) "left" else "right")
    }

    private fun reset() {
        consuming = false; cancelled = false; fired = false; origin = emptyMap(); startedAt = 0L; rejectedReason = "short_or_vertical"
    }
}
