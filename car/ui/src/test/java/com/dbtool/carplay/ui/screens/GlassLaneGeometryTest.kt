package com.dbtool.carplay.ui.screens

import org.junit.Assert.*
import org.junit.Test

class GlassLaneGeometryTest {
    @Test fun everyBearingHasExactlyTheSameStemAnchor() {
        for (angle in listOf(-180, -170, -135, -90, -45, 0, 45, 90, 135, 170)) {
            assertEquals(LanePoint(0f, 4f), glassLaneBranch(angle)!!.start)
            assertEquals(0f, glassLaneBranch(angle)!!.control1.x, 0f)
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
}
