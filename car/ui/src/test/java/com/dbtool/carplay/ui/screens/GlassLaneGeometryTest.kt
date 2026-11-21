package com.dbtool.carplay.ui.screens

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class GlassLaneGeometryTest {
    @Test fun uncommonAnglesRetainUprightSharedStemAndAngularFilledHeads() {
        for (angle in listOf(-135, -90, -30, 0, 30, 90, 135)) {
            val branch = requireNotNull(glassLaneBranch(angle))
            assertEquals(LanePoint(0f, 0f), branch.start)
            assertEquals(0f, branch.bend.x, 0f)
            assertTrue(branch.bend.y < branch.start.y)
            assertEquals(7, branch.head.size)
            assertEquals(branch.tip, branch.head.first())
        }
    }

    @Test fun wrappedPhoneAnglesUseIdenticalGeometry() {
        assertEquals(glassLaneBranch(-90), glassLaneBranch(270))
        assertEquals(glassLaneBranch(-45), glassLaneBranch(315))
        assertNull(glassLaneBranch(32767))
    }

    @Test fun recommendationChangesColorNotBranchPosition() {
        val bearings = listOf(-90, 0, 45)
        val gray = bearings.associateWith(::glassLaneBranch)
        val highlighted = bearings.sortedBy { it == 0 }.associateWith(::glassLaneBranch)
        assertEquals(gray, highlighted)
        assertEquals(-glassLaneBranch(-90)!!.tip.x, glassLaneBranch(90)!!.tip.x, 0.001f)
    }

    @Test fun leftAndStraightInkHaveEqualHeightAndBaselineDespiteDifferentVectorPadding() {
        val straight = GlassLaneInk(25, 4, 85, 108, 55f)
        val left = GlassLaneInk(15, 3, 90, 108, 80f)
        for (ink in listOf(straight, left, GlassLaneInk(5, 8, 95, 100, 80f))) {
            assertEquals(GlassLaneLayout.TOP, ink.topAt(1f) + ink.top * ink.scaleToLaneHeight, 0.0001f)
            assertEquals(GlassLaneLayout.BOTTOM, ink.topAt(1f) + ink.bottom * ink.scaleToLaneHeight, 0.0001f)
            assertEquals(48f, (ink.bottom - ink.top) * ink.scaleToLaneHeight, 0.0001f)
            assertEquals(300f, ink.leftAt(300f, 1f) + ink.stemCenterX * ink.scaleToLaneHeight, 0.0001f)
        }
        assertEquals(13f, 225f - GlassLaneLayout.BOTTOM, 0f)
    }

    @Test fun allLanesUseOneScaleSoMultiBranchAndLeftDoNotGetShorterIndependently() {
        for (count in 1..16) {
            val maximumHalfWidth = 34f
            val scale = GlassLaneLayout.rowScale(count, maximumHalfWidth)
            assertTrue(scale > 0f && scale <= 1f)
            for (index in 0 until count) {
                val center = GlassLaneLayout.centerX(index, count)
                val half = maximumHalfWidth * scale
                val cellLeft = GlassLaneLayout.LEFT + index * GlassLaneLayout.slotWidth(count)
                assertTrue(center - half >= cellLeft + 0.99f)
                assertTrue(center + half <= cellLeft + GlassLaneLayout.slotWidth(count) - 0.99f)
            }
            assertTrue(GlassLaneLayout.BOTTOM - GlassLaneLayout.HEIGHT * scale >= GlassLaneLayout.TOP)
        }
    }

    @Test fun rendererUsesSharedLargeIconsAndNoCubicRoundLaneRendererOrMainTurnSubstitution() {
        val root = generateSequence(File(".").canonicalFile) { it.parentFile }
            .first { File(it, "shared/src/main").isDirectory }
        val code = File(root, "car/ui/src/main/java/com/dbtool/carplay/ui/screens/ImmersiveGlassCards.kt").readText()
        val row = code.substringAfter("private fun drawLanes(").substringBefore("private fun glass(")
        assertTrue(row.contains("lane.bearings"))
        assertTrue(row.contains("osnLaneIcon(bearing)"))
        assertTrue(row.contains("icons.drawLane("))
        assertTrue(row.contains("if (bearing == lane.highlight)"))
        assertTrue(row.contains("GlassLaneLayout.rowScale("))
        assertFalse(row.contains("cubicTo"))
        assertFalse(row.contains("maneuverType"))
        val resources = File(root, "car/ui/src/main/java/com/dbtool/carplay/ui/screens/OsnNavigationIcons.kt").readText()
        assertFalse(resources.contains("R.drawable.osn_lane_"))
        assertTrue(code.contains("vectorIcon(glyph.resource)"))
        assertTrue(code.contains("strokeJoin = Paint.Join.MITER"))
        assertTrue(code.contains("size > 24"))
    }
}
