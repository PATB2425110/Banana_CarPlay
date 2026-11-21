package com.dbtool.carplay.geely

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Source wiring guards complement executable geometry tests; not a Canvas/device test. */
class ClusterLaneWiringTest {
    private fun source(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val file = File(dir, relative)
            if (file.isFile) return file.readText()
            dir = dir.parentFile
        }
        error("Source not found: $relative")
    }

    @Test fun `snapshot projection reaches real Canvas branches and null invalidates view`() {
        val api = source("car/api/src/main/java/com/dbtool/carplay/api/navigation/CarNavigationSnapshot.kt")
        assertTrue(api.contains("val lanes: List<CarHudLane> = emptyList()"))
        assertTrue(api.contains("lanes.isNotEmpty()"))
        val adapter = source("car/core-bridge/src/main/java/com/dbtool/carplay/bridge/navigation/CarPlayNavigationDataSource.kt")
        assertTrue(adapter.contains("listener(state?.toCarNavigationSnapshot())"))
        assertTrue(adapter.contains("lanes = lanes.sortedBy { it.index }.take(16)"))
        assertTrue(adapter.contains("angles = it.angles.take(8).toList()"))
        assertTrue(adapter.contains("highlightAngle = it.highlightAngle"))
        val presentation = source("car/platform-geely/src/main/java/com/dbtool/carplay/geely/GeelyClusterPresentation.kt")
        assertTrue(presentation.contains("navigation = com.dbtool.carplay.api.projection.InstrumentCards.navigation()"))
        val view = source("car/platform-geely/src/main/java/com/dbtool/carplay/geely/ClusterIndependentCardsView.kt")
        assertTrue(view.contains("var navigation: CarNavigationSnapshot? = null\n        set(value) { field = value; invalidate() }"))
        val nav = view.substringAfter("private fun drawNavigation(canvas: Canvas)").substringBefore("private fun drawMusic")
        assertTrue(nav.contains("val n = navigation ?: return"))
        assertTrue(nav.contains("ClusterLaneGeometry.layout(n.lanes)"))
        assertTrue(nav.contains("lines.isEmpty() && cells.isEmpty()"))
        assertTrue(nav.contains("ClusterLaneGeometry.panelHeight(lines.size, cells)"))
        assertTrue(nav.contains("ClusterLaneGeometry.textHeight(lines.size)"))
        assertTrue(nav.contains("drawPath(cell.stem)"))
        assertTrue(nav.contains("drawPath(branch.path)"))
        assertTrue(nav.contains("drawPath(branch.head)"))
        assertTrue(nav.contains("if (branch.recommended)"))
        assertTrue(nav.contains("canvas.drawLine("))
        assertTrue(nav.contains("canvas.drawText(\"?\""))
        assertTrue(view.contains("if (navigationEnabled) drawNavigation(canvas)"))
        assertTrue(view.contains("if (musicEnabled) drawMusic(canvas)"))
        assertTrue(view.contains("val sx = width / 1920f"))
        assertTrue(view.contains("val sy = height / 720f"))
    }
}
