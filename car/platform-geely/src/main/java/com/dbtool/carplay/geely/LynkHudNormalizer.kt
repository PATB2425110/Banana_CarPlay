package com.dbtool.carplay.geely

import com.dbtool.carplay.api.navigation.CarHudGuidance
import java.util.Locale

/**
 * Stateless port of box n8.N/v/A/O/P and the semantic part of n8.E.
 *
 * Evidence: BOX-TURN-STATE-APPENDIX.md A1–A6; BOX-SENDER-DETAIL.md §4.4.
 * Expected IDs are from LYNK-MAPPINGS.json / Lynk TransformUtil, NOT the box's
 * expectation table (notably START 23 becomes 200, not 101).
 *
 * null means no new valid direction: callers should retain their last valid hint.
 * No distance, road name, timestamp or index is used to invent maneuver semantics.
 * Trip confirmation, lane data and upcoming-maneuver selection belong to callers.
 */
internal object LynkHudNormalizer {
    fun normalize(guidance: CarHudGuidance): NormalizedHudTurn? {
        val raw = guidance.maneuverType
        val description = guidance.maneuverDescription.orEmpty().lowercase(Locale.ROOT)
        val family = family(raw)
        val geometry = geometry(guidance.junctionAngle, guidance.exitAngle)
        val text = descriptionTurn(description)
        val startExit = hasAny(description, "请前往出口", "前往出口", "出口", "exit")
        val turn = when (family) {
            Family.CONTEXT -> return null
            Family.UNKNOWN -> geometry ?: text ?: return null
            Family.FORWARD -> if (raw == 5 || raw == 51) Turn.FOLLOW_ROAD else Turn.STRAIGHT_AHEAD
            Family.LEFT -> geometry?.takeIf { it in leftGeometry } ?: Turn.LEFT
            Family.RIGHT -> geometry?.takeIf { it in rightGeometry } ?: Turn.RIGHT
            Family.UTURN -> if (guidance.drivingSide == 1) Turn.UTURN_RIGHT else Turn.UTURN_LEFT
            Family.ROUTE_START -> if (startExit) Turn.FOLLOW_ROAD else Turn.ROUTE_START
            Family.ARRIVAL -> Turn.ARRIVAL
            Family.KEEP_LEFT -> Turn.KEEP_LEFT
            Family.KEEP_RIGHT -> Turn.KEEP_RIGHT
            Family.SHARP_LEFT -> Turn.SHARP_LEFT
            Family.SHARP_RIGHT -> Turn.SHARP_RIGHT
            Family.SLIGHT_LEFT -> Turn.SLIGHT_LEFT
            Family.SLIGHT_RIGHT -> Turn.SLIGHT_RIGHT
            Family.ROUNDABOUT_ENTER -> Turn.ENTER_ROUNDABOUT
            Family.ROUNDABOUT_EXIT -> Turn.EXIT_ROUNDABOUT
        }
        // n8.P is independent of geometry. A geometry-only unknown raw remains UNKNOWN.
        val pointRole = when (family) {
            Family.ROUTE_START -> if (startExit) "ACTIONABLE" else "ROUTE_START"
            Family.ARRIVAL -> "ARRIVAL"
            Family.UNKNOWN -> if (text != null) "ACTIONABLE" else "UNKNOWN"
            else -> "ACTIONABLE"
        }
        val straight = turn == Turn.FOLLOW_ROAD
        val expectedId = if (straight) TURN_ALONG_ID else lynkTurnIds.getValue(turn.baiduKind)
        return NormalizedHudTurn(turn.baiduKind, turn.name, pointRole, expectedId, straight)
    }

    private fun family(raw: Int?): Family = when (raw) {
        0 -> Family.CONTEXT
        1, 20, 22, 52 -> Family.LEFT
        2, 21, 23, 53 -> Family.RIGHT
        3, 5, 51 -> Family.FORWARD
        4, 18, 19, 26 -> Family.UTURN
        6 -> Family.ROUNDABOUT_ENTER
        7, in 28..46 -> Family.ROUNDABOUT_EXIT
        10, 12, 24, 25, 27 -> Family.ARRIVAL
        11 -> Family.ROUTE_START
        13 -> Family.KEEP_LEFT
        14 -> Family.KEEP_RIGHT
        47 -> Family.SHARP_LEFT
        48 -> Family.SHARP_RIGHT
        49 -> Family.SLIGHT_LEFT
        50 -> Family.SLIGHT_RIGHT
        else -> Family.UNKNOWN
    }

    private fun geometry(entryRaw: Int?, exitRaw: Int?): Turn? {
        if (entryRaw == null || exitRaw == null) return null
        val entry = entryRaw.toShort().toInt()
        val exit = exitRaw.toShort().toInt()
        if (entry == 0 && exit == 0) return null
        var delta = (exit - entry) % 360
        if (delta > 180) delta -= 360
        if (delta < -180) delta += 360
        return when (delta) {
            in -19..19 -> Turn.STRAIGHT_AHEAD
            in -59..-20 -> Turn.SLIGHT_LEFT
            in -134..-60 -> Turn.LEFT
            in -164..-135 -> Turn.SHARP_LEFT
            in -180..-165 -> Turn.UTURN_LEFT
            in 20..59 -> Turn.SLIGHT_RIGHT
            in 60..134 -> Turn.RIGHT
            in 135..164 -> Turn.SHARP_RIGHT
            else -> Turn.UTURN_RIGHT // 165..180, after signed modulo normalization
        }
    }

    private fun descriptionTurn(text: String): Turn? = when {
        hasAny(text, "驶出环岛", "离开环岛", "exit roundabout") -> Turn.EXIT_ROUNDABOUT
        hasAny(text, "进入环岛", "roundabout", "rotary", "环岛", "環島") -> Turn.ENTER_ROUNDABOUT
        hasAny(text, "u-turn", "uturn", "掉头", "调头", "迴轉", "回转") -> Turn.UTURN_LEFT
        hasAny(text, "slight left", "左前") -> Turn.SLIGHT_LEFT
        hasAny(text, "slight right", "右前") -> Turn.SLIGHT_RIGHT
        hasAny(text, "sharp left", "左后", "左後") -> Turn.SHARP_LEFT
        hasAny(text, "sharp right", "右后", "右後") -> Turn.SHARP_RIGHT
        hasAny(text, "keep left", "靠左") -> Turn.KEEP_LEFT
        hasAny(text, "keep right", "靠右") -> Turn.KEEP_RIGHT
        hasAny(text, "left", "左") -> Turn.LEFT
        hasAny(text, "right", "右") -> Turn.RIGHT
        hasAny(text, "straight", "continue", "直行", "向前", "沿道路") -> Turn.STRAIGHT_AHEAD
        else -> null
    }

    private fun hasAny(text: String, vararg words: String): Boolean = words.any { text.contains(it) }

    private enum class Family {
        CONTEXT, UNKNOWN, LEFT, RIGHT, FORWARD, UTURN, ROUTE_START, ARRIVAL,
        KEEP_LEFT, KEEP_RIGHT, SHARP_LEFT, SHARP_RIGHT, SLIGHT_LEFT, SLIGHT_RIGHT,
        ROUNDABOUT_ENTER, ROUNDABOUT_EXIT,
    }

    private enum class Turn(val baiduKind: Int) {
        STRAIGHT_AHEAD(1), FOLLOW_ROAD(1), SLIGHT_LEFT(8), LEFT(7), SHARP_LEFT(6),
        SLIGHT_RIGHT(2), RIGHT(3), SHARP_RIGHT(4), UTURN_LEFT(5), UTURN_RIGHT(5),
        KEEP_LEFT(11), KEEP_RIGHT(12), ENTER_ROUNDABOUT(9), EXIT_ROUNDABOUT(10),
        ROUTE_START(23), ARRIVAL(24),
    }

    private val leftGeometry = setOf(Turn.SLIGHT_LEFT, Turn.LEFT, Turn.SHARP_LEFT, Turn.UTURN_LEFT)
    private val rightGeometry = setOf(Turn.SLIGHT_RIGHT, Turn.RIGHT, Turn.SHARP_RIGHT, Turn.UTURN_RIGHT)

    // Only the receiver branches emitted by n8.E's semantic mapping are needed here.
    // Lynk TransformUtil.smali:4706–4729, Consts$TurnKind.smali:121–285.
    // Right U-turn intentionally remains kind 5 (108): this is the box approximation,
    // not an invented switch to Lynk's additional kind 100 (179).
    private val lynkTurnIds = mapOf(
        1 to 109, 2 to 105, 3 to 103, 4 to 107, 5 to 108, 6 to 106,
        7 to 102, 8 to 104, 9 to 111, 10 to 112, 11 to 147, 12 to 148,
        23 to 200, 24 to 115,
    )
    // TransformUtil isStraight=true overrides the selected kind with TURN_KIND_TURN_ALONG.
    private const val TURN_ALONG_ID = 120
}

/**
 * [pointRole] is n8.P's stateless slot category, not oa's confirmed trip-event role.
 * In particular ARRIVAL does not assert FINAL_DESTINATION or a confirmed waypoint.
 */
internal data class NormalizedHudTurn(
    val baiduTurnKind: Int,
    val semantic: String,
    val pointRole: String,
    val expectedTurnId: Int,
    val isStraight: Boolean,
)
