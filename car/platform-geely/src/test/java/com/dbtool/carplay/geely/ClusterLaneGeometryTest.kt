package com.dbtool.carplay.geely

import com.dbtool.carplay.api.navigation.CarHudLane
import org.junit.Assert.*
import org.junit.Test

class ClusterLaneGeometryTest {
    @Test fun `bearing aliases are accepted but unknown shorts are not wrapped`() {
        mapOf(0 to 0, 360 to 0, -360 to 0, 270 to -90, -90 to -90,
            180 to -180, -180 to -180, 315 to -45, -270 to 90).forEach { (raw, expected) ->
            assertEquals(expected, ClusterLaneGeometry.canonical(raw))
        }
        listOf(720, -720, 32767, -32768, 361, -361).forEach {
            assertNull(ClusterLaneGeometry.canonical(it))
        }
    }

    @Test fun `recommendation matches exact canonical branch not nearby bearing or whole cell`() {
        val cell = ClusterLaneGeometry.layout(listOf(CarHudLane(0, listOf(270, -90, -89, 0, 45), -90))).single()
        assertEquals(listOf(-90), cell.branches.filter { it.recommended }.map { it.bearing })
        assertEquals(4, cell.branches.size)
        assertEquals(2, cell.stem.size)
        assertTrue(ClusterLaneGeometry.layout(listOf(CarHudLane(0, listOf(0), 720)))
            .single().branches.none { it.recommended })
        assertTrue(ClusterLaneGeometry.layout(listOf(CarHudLane(0, listOf(0), null)))
            .single().branches.none { it.recommended })
    }

    @Test fun `unknown and empty lanes retain sorted relative slots without fake straight arrows`() {
        val cells = ClusterLaneGeometry.layout(listOf(
            CarHudLane(9, listOf(32767, 720)), CarHudLane(2, listOf(-90)), CarHudLane(5, emptyList()),
        ))
        assertEquals(listOf(2, 5, 9), cells.map { it.sourceIndex })
        assertTrue(cells[0].right <= cells[1].left)
        assertTrue(cells[1].right <= cells[2].left)
        cells.drop(1).forEach {
            assertTrue(it.unknown)
            assertTrue(it.branches.isEmpty())
            assertTrue(it.stem.isEmpty())
        }
    }

    @Test fun `two through sixteen lanes stay inside panel and their own slots for eight branches`() {
        for (count in 1..20) {
            val cells = ClusterLaneGeometry.layout((0 until count).map {
                CarHudLane(it, listOf(-180, -135, -90, -45, 0, 45, 90, 135, 180), 0)
            })
            assertEquals(count.coerceAtMost(16), cells.size)
            if (count <= 6) assertTrue(cells.all { it.scale > 0.9f })
            cells.forEach { cell ->
                assertTrue(cell.left >= 18f && cell.right <= 412.001f)
                val points = cell.stem + cell.branches.flatMap { it.path + it.head }
                points.forEach {
                    assertTrue("x=${it.x} slot=${cell.left}..${cell.right}", it.x >= cell.left && it.x <= cell.right)
                    assertTrue("y=${it.y}", it.y >= 0f && it.y < ClusterLaneGeometry.ROW_HEIGHT)
                }
                assertTrue(cell.branches.size <= 8)
            }
        }
    }

    @Test fun `all accepted signed bearings have finite bounded geometry even at sixteen lanes`() {
        for (angle in -360..360) {
            val cells = ClusterLaneGeometry.layout((0 until 16).map { CarHudLane(it, listOf(angle), angle) })
            cells.forEach { cell ->
                val branch = cell.branches.single()
                assertTrue(branch.recommended)
                (cell.stem + branch.path + branch.head).forEach {
                    assertTrue(it.x.isFinite() && it.y.isFinite())
                    assertTrue("angle=$angle x=${it.x}", it.x >= cell.left && it.x <= cell.right)
                    assertTrue("angle=$angle y=${it.y}", it.y >= 0f && it.y < 84f)
                }
            }
        }
    }

    @Test fun `real bearing directions and offset return arrow do not rotate into incoming stem`() {
        val cell = ClusterLaneGeometry.layout(listOf(CarHudLane(0, listOf(0, -45, 45, -90, 90, 180)))).single()
        val paths = cell.branches.associate { it.bearing to it.path }
        val start = paths.getValue(0).first()
        assertEquals(start.x, paths.getValue(0).last().x, 0f)
        assertTrue(paths.getValue(0).last().y < start.y)
        assertTrue(paths.getValue(-45).last().x < start.x)
        assertTrue(paths.getValue(45).last().x > start.x)
        assertEquals(start.y, paths.getValue(-90).last().y, 0.001f)
        assertEquals(start.y, paths.getValue(90).last().y, 0.001f)
        val u = paths.getValue(-180)
        assertTrue(u.size > 3)
        assertTrue(u.last().x < start.x)
        assertTrue(u.last().y > u[u.lastIndex - 1].y)
        assertNotEquals(cell.stem.first().x, u.last().x)
    }

    @Test fun `lane row is below all text baselines and clear removes row height`() {
        val cells = ClusterLaneGeometry.layout(listOf(CarHudLane(0, listOf(0))))
        for (lines in 0..4) {
            val top = ClusterLaneGeometry.textHeight(lines)
            if (lines > 0) assertTrue(top > 35f + (lines - 1) * 30f + 5f)
            assertEquals(top + 84f, ClusterLaneGeometry.panelHeight(lines, cells), 0f)
            assertEquals(top, ClusterLaneGeometry.panelHeight(lines, emptyList()), 0f)
        }
        assertTrue(ClusterLaneGeometry.layout(emptyList()).isEmpty())
        assertEquals(238f, ClusterLaneGeometry.panelHeight(4, cells), 0f)
    }
}
