package com.dbtool.carplay.geely

import com.dbtool.carplay.api.navigation.CarHudGuidance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LynkHudNormalizerTest {
    private fun guidance(
        raw: Int? = null,
        text: String? = null,
        entry: Int? = null,
        exit: Int? = null,
        side: Int? = null,
    ) = CarHudGuidance(
        updatedAtMs = 1L,
        routeMeters = null,
        routeSeconds = null,
        segmentMeters = null,
        maneuverType = raw,
        currentRoad = null,
        nextRoad = null,
        sessionId = null,
        revision = null,
        maneuverDescription = text,
        drivingSide = side,
        junctionAngle = entry,
        exitAngle = exit,
    )

    private fun normalized(input: CarHudGuidance): NormalizedHudTurn =
        requireNotNull(LynkHudNormalizer.normalize(input)) { "No normalized turn for $input" }

    private fun assertSemantic(expected: String, input: CarHudGuidance) {
        assertEquals(input.toString(), expected, normalized(input).semantic)
    }

    @Test
    fun `all raw families and aliases match the box rather than an external protocol table`() {
        val families = mapOf(
            "LEFT" to listOf(1, 20, 22, 52),
            "RIGHT" to listOf(2, 21, 23, 53),
            "STRAIGHT_AHEAD" to listOf(3),
            "FOLLOW_ROAD" to listOf(5, 51),
            "UTURN_LEFT" to listOf(4, 18, 19, 26),
            "ENTER_ROUNDABOUT" to listOf(6),
            "EXIT_ROUNDABOUT" to (listOf(7) + (28..46)),
            "ARRIVAL" to listOf(10, 12, 24, 25, 27),
            "ROUTE_START" to listOf(11),
            "KEEP_LEFT" to listOf(13),
            "KEEP_RIGHT" to listOf(14),
            "SHARP_LEFT" to listOf(47),
            "SHARP_RIGHT" to listOf(48),
            "SLIGHT_LEFT" to listOf(49),
            "SLIGHT_RIGHT" to listOf(50),
        )
        families.forEach { (semantic, values) ->
            values.forEach { assertSemantic(semantic, guidance(raw = it)) }
        }
    }

    @Test
    fun `unlisted and missing raw types do not fabricate a direction`() {
        listOf(null, Int.MIN_VALUE, -1, 8, 9, 15, 16, 17, 54, 156, Int.MAX_VALUE).forEach {
            assertNull("raw=$it", LynkHudNormalizer.normalize(guidance(raw = it)))
        }
    }

    @Test
    fun `context does not use geometry or description to fabricate a direction`() {
        assertNull(LynkHudNormalizer.normalize(guidance(0, "left", 0, 90)))
        assertNull(LynkHudNormalizer.normalize(guidance(0, "straight")))
    }

    @Test
    fun `every signed geometry threshold includes both boundary endpoints`() {
        val ranges = mapOf(
            "UTURN_LEFT" to -180..-165,
            "SHARP_LEFT" to -164..-135,
            "LEFT" to -134..-60,
            "SLIGHT_LEFT" to -59..-20,
            "STRAIGHT_AHEAD" to -19..19,
            "SLIGHT_RIGHT" to 20..59,
            "RIGHT" to 60..134,
            "SHARP_RIGHT" to 135..164,
            "UTURN_RIGHT" to 165..180,
        )
        ranges.forEach { (semantic, range) ->
            // Nonzero entry makes even delta=0 valid. Check every integer, not just endpoints.
            range.forEach { delta -> assertSemantic(semantic, guidance(entry = 10, exit = 10 + delta)) }
        }
    }

    @Test
    fun `geometry sign extends only the lower sixteen bits`() {
        assertSemantic("LEFT", guidance(entry = 0, exit = 65446)) // unsigned -90
        assertSemantic("LEFT", guidance(entry = 65536, exit = 130982))
        assertSemantic("RIGHT", guidance(entry = 65536, exit = 65626))
        assertSemantic("STRAIGHT_AHEAD", guidance(entry = 32767, exit = 32768)) // -65535 % 360 = -15
    }

    @Test
    fun `geometry wraps signed java remainders without changing the 180 sign`() {
        listOf(-540, -180, 180, 540).forEach { delta ->
            assertSemantic(if (delta < 0) "UTURN_LEFT" else "UTURN_RIGHT", guidance(entry = 0, exit = delta))
        }
        assertSemantic("LEFT", guidance(entry = 0, exit = 270))
        assertSemantic("RIGHT", guidance(entry = 0, exit = -270))
        assertSemantic("STRAIGHT_AHEAD", guidance(entry = 20, exit = 380))
    }

    @Test
    fun `two zero angles disable geometry but a single zero does not`() {
        assertNull(LynkHudNormalizer.normalize(guidance(entry = 0, exit = 0)))
        assertNull(LynkHudNormalizer.normalize(guidance(entry = 65536, exit = -65536)))
        assertSemantic("RIGHT", guidance(entry = 0, exit = 90))
        assertSemantic("LEFT", guidance(entry = 90, exit = 0))
        assertSemantic("LEFT", guidance(text = "left", entry = 0, exit = 0))
    }

    @Test
    fun `either missing angle disables geometry and permits text fallback`() {
        listOf(null to null, null to 90, 90 to null).forEach { (entry, exit) ->
            assertNull(LynkHudNormalizer.normalize(guidance(entry = entry, exit = exit)))
            assertSemantic("KEEP_RIGHT", guidance(text = "keep right", entry = entry, exit = exit))
        }
    }

    @Test
    fun `unknown raw uses geometry before text`() {
        assertSemantic("RIGHT", guidance(999, "left", 0, 90))
        assertSemantic("STRAIGHT_AHEAD", guidance(null, "left", 10, 10))
        assertSemantic("LEFT", guidance(8, "left"))
    }

    @Test
    fun `left and right families accept only same side geometry including uturn`() {
        val left = listOf(-30 to "SLIGHT_LEFT", -90 to "LEFT", -150 to "SHARP_LEFT", -180 to "UTURN_LEFT")
        val right = listOf(30 to "SLIGHT_RIGHT", 90 to "RIGHT", 150 to "SHARP_RIGHT", 180 to "UTURN_RIGHT")
        listOf(1, 20, 22, 52).forEach { raw ->
            left.forEach { (angle, semantic) -> assertSemantic(semantic, guidance(raw, entry = 0, exit = angle)) }
            right.forEach { (angle, _) -> assertSemantic("LEFT", guidance(raw, entry = 0, exit = angle)) }
            assertSemantic("LEFT", guidance(raw, "right", 10, 10))
        }
        listOf(2, 21, 23, 53).forEach { raw ->
            right.forEach { (angle, semantic) -> assertSemantic(semantic, guidance(raw, entry = 0, exit = angle)) }
            left.forEach { (angle, _) -> assertSemantic("RIGHT", guidance(raw, entry = 0, exit = angle)) }
            assertSemantic("RIGHT", guidance(raw, "left", 10, 10))
        }
    }

    @Test
    fun `explicit families ignore conflicting geometry and ordinary description`() {
        listOf(3, 5, 6, 7, 10, 11, 12, 13, 14, 24, 25, 27, 28, 46, 47, 48, 49, 50, 51).forEach { raw ->
            val baseline = normalized(guidance(raw)).semantic
            assertSemantic(baseline, guidance(raw, "keep left and sharp right", 0, -180))
            assertSemantic(baseline, guidance(raw, "left", 0, 180))
        }
    }

    @Test
    fun `uturn driving side equals one only and never uses geometry or text`() {
        listOf(4, 18, 19, 26).forEach { raw ->
            listOf(null, -1, 0, 2, Int.MAX_VALUE).forEach { side ->
                assertSemantic("UTURN_LEFT", guidance(raw, "right", 0, 180, side))
            }
            val right = normalized(guidance(raw, "left", 0, -180, 1))
            assertEquals("UTURN_RIGHT", right.semantic)
            assertEquals(5, right.baiduTurnKind)
            assertEquals(108, right.expectedTurnId)
        }
    }

    @Test
    fun `route start exit substring is follow road even for exit roundabout`() {
        listOf("请前往出口", "前往出口", "出口", "EXIT", "exit roundabout", "preexisting exit").forEach {
            val result = normalized(guidance(11, it))
            assertEquals("FOLLOW_ROAD", result.semantic)
            assertEquals("ACTIONABLE", result.pointRole)
            assertTrue(result.isStraight)
            assertEquals(120, result.expectedTurnId)
        }
        listOf(null, "", "start", "straight", "right").forEach {
            val result = normalized(guidance(11, it))
            assertEquals("ROUTE_START", result.semantic)
            assertEquals("ROUTE_START", result.pointRole)
            assertEquals(23, result.baiduTurnKind)
            assertEquals(200, result.expectedTurnId)
            assertFalse(result.isStraight)
        }
    }

    @Test
    fun `every box description token is retained`() {
        val tokens = mapOf(
            "EXIT_ROUNDABOUT" to listOf("驶出环岛", "离开环岛", "exit roundabout"),
            "ENTER_ROUNDABOUT" to listOf("进入环岛", "roundabout", "rotary", "环岛", "環島"),
            "UTURN_LEFT" to listOf("u-turn", "uturn", "掉头", "调头", "迴轉", "回转"),
            "SLIGHT_LEFT" to listOf("slight left", "左前"),
            "SLIGHT_RIGHT" to listOf("slight right", "右前"),
            "SHARP_LEFT" to listOf("sharp left", "左后", "左後"),
            "SHARP_RIGHT" to listOf("sharp right", "右后", "右後"),
            "KEEP_LEFT" to listOf("keep left", "靠左"),
            "KEEP_RIGHT" to listOf("keep right", "靠右"),
            "LEFT" to listOf("left", "左"),
            "RIGHT" to listOf("right", "右"),
            "STRAIGHT_AHEAD" to listOf("straight", "continue", "直行", "向前", "沿道路"),
        )
        tokens.forEach { (semantic, words) ->
            words.forEach { assertSemantic(semantic, guidance(text = "prefix $it suffix", side = 1)) }
        }
        assertSemantic("KEEP_RIGHT", guidance(text = "KEEP RIGHT"))
    }

    @Test
    fun `description first match priority is the box order not word position`() {
        val phrases = listOf(
            "exit roundabout", "roundabout", "u-turn", "slight left", "slight right",
            "sharp left", "sharp right", "keep left", "keep right", "left", "right", "straight",
        )
        phrases.forEachIndexed { index, phrase ->
            val expected = normalized(guidance(text = phrase)).semantic
            phrases.drop(index + 1).forEach { later ->
                assertSemantic(expected, guidance(text = "$later then $phrase"))
            }
        }
        assertSemantic("LEFT", guidance(text = "cleft")) // Substring, not word-boundary matching.
        listOf(null, "", "  \t\n", "start", "destination", "出口").forEach {
            assertNull(LynkHudNormalizer.normalize(guidance(text = it)))
        }
    }

    @Test
    fun `slot category for unknown raw is based on text independently of geometry`() {
        val geometryOnly = normalized(guidance(entry = 0, exit = 90))
        assertEquals("UNKNOWN", geometryOnly.pointRole)
        val withText = normalized(guidance(text = "left", entry = 0, exit = 90))
        assertEquals("RIGHT", withText.semantic)
        assertEquals("ACTIONABLE", withText.pointRole)
        assertEquals("ACTIONABLE", normalized(guidance(1)).pointRole)
        assertEquals("ACTIONABLE", normalized(guidance(text = "left")).pointRole)
    }

    @Test
    fun `arrival uses kind 24 without pretending to confirm destination or waypoint`() {
        listOf(10, 12, 24, 25, 27).forEach {
            assertEquals(NormalizedHudTurn(24, "ARRIVAL", "ARRIVAL", 115, false), normalized(guidance(it)))
        }
    }

    @Test
    fun `all emitted kinds exist in the Lynk receiver table and expected ids agree`() {
        // Independent fixture: Lynk TransformUtil + Consts TurnKind, not the sender's START=101.
        val receiver = mapOf(
            1 to 109, 2 to 105, 3 to 103, 4 to 107, 5 to 108, 6 to 106, 7 to 102,
            8 to 104, 9 to 111, 10 to 112, 11 to 147, 12 to 148, 23 to 200, 24 to 115,
        )
        val inputs = (0..54).map { guidance(it) } +
            listOf(-180, -150, -90, -30, 0, 30, 90, 150, 180).map { guidance(entry = 10, exit = 10 + it) } +
            listOf(guidance(4, side = 1), guidance(11, "exit"))
        val outputs = inputs.mapNotNull { LynkHudNormalizer.normalize(it) }
        assertEquals(receiver.keys, outputs.map { it.baiduTurnKind }.toSet())
        outputs.forEach {
            assertNotNull(receiver[it.baiduTurnKind])
            assertTrue(it.baiduTurnKind > 0 && it.baiduTurnKind != 156)
            assertEquals(if (it.isStraight) 120 else receiver.getValue(it.baiduTurnKind), it.expectedTurnId)
        }
    }

    @Test
    fun `isStraight is follow road only and overrides receiver front id`() {
        listOf(guidance(5), guidance(51), guidance(11, "exit")).forEach {
            assertEquals(NormalizedHudTurn(1, "FOLLOW_ROAD", "ACTIONABLE", 120, true), normalized(it))
        }
        listOf(guidance(3), guidance(text = "continue"), guidance(entry = 10, exit = 10)).forEach {
            val result = normalized(it)
            assertEquals("STRAIGHT_AHEAD", result.semantic)
            assertEquals(1, result.baiduTurnKind)
            assertEquals(109, result.expectedTurnId)
            assertFalse(result.isStraight)
        }
    }

    @Test
    fun `roads distances clocks revisions and indices never invent or change a maneuver`() {
        val original = guidance(1)
        val changed = original.copy(
            currentRoad = "right", nextRoad = "roundabout", nextManeuverRoad = "exit",
            routeMeters = 0, routeSeconds = 0, segmentMeters = 0,
            sessionId = 100L, revision = 200L, maneuverIndex = 999, updatedAtMs = Long.MAX_VALUE,
        )
        assertEquals(normalized(original), normalized(changed))
        assertNull(LynkHudNormalizer.normalize(changed.copy(maneuverType = null)))
        assertEquals("LEFT", normalized(original).semantic) // No retained state inside the mapper.
    }
}
