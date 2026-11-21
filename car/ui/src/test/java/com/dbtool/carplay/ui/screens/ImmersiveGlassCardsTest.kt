package com.dbtool.carplay.ui.screens

import com.dbtool.carplay.api.navigation.CarNavigationSnapshot
import com.dbtool.carplay.api.navigation.CarHudLane
import com.dbtool.carplay.api.settings.CarSessionSettings
import com.dbtool.carplay.api.settings.CarSettingsReconnectPolicy
import org.junit.Assert.*
import org.junit.Test

class ImmersiveGlassCardsTest {
    @Test fun bottomAlignedArrowKeepsItsSizeHorizontalPositionAndMusicControls() {
        assertEquals(-15f, ImmersiveGlassCardLayout.ARROW_OFFSET_X, 0f)
        assertEquals(50f, ImmersiveGlassCardLayout.ARROW_LEFT, 0f)
        assertEquals(110, ImmersiveGlassCardLayout.ARROW_SIZE)
        assertEquals(136f, ImmersiveGlassCardLayout.ROAD_BASELINE, 0f)
        assertEquals(-15f, ImmersiveGlassCardLayout.INSTRUCTION_OFFSET_Y, 0f)
        assertEquals(32f, ImmersiveGlassCardLayout.COVER_LEFT, 0f)
        assertEquals(300f - 270f, ImmersiveGlassCardLayout.COVER_TOP, 0f)
        assertEquals(102f, ImmersiveGlassCardLayout.COVER_SIZE, 0f)
        assertTrue(ImmersiveGlassCardLayout.MUSIC_TEXT_LEFT >
            ImmersiveGlassCardLayout.COVER_LEFT + ImmersiveGlassCardLayout.COVER_SIZE)
        assertEquals(24f, ImmersiveGlassCardLayout.PROGRESS_TOP -
            (ImmersiveGlassCardLayout.COVER_TOP + ImmersiveGlassCardLayout.COVER_SIZE), 0f)
        assertEquals(75, ImmersiveGlassCardLayout.top())
        assertEquals(410, ImmersiveGlassCardLayout.musicTop())
        assertEquals(74, ImmersiveGlassCardLayout.MEDIA_HIT_SIZE)
    }
    @Test fun mediaHitAreasKeep115PercentSizeAndFollowMovedButtonsWithoutOverlappingProgress() {
        val size = ImmersiveGlassCardLayout.MEDIA_HIT_SIZE
        assertEquals(kotlin.math.round(64 * 1.15).toInt(), size)
        val top = ImmersiveGlassCardLayout.mediaHitTop()
        assertEquals(209, top + size / 2)
        assertTrue(top > ImmersiveGlassCardLayout.PROGRESS_BOTTOM)
        assertEquals(6, ImmersiveGlassCardLayout.MUSIC_HEIGHT - (top + size))
        val centres = listOf(136, 275, 414)
        centres.forEach { x ->
            val left = ImmersiveGlassCardLayout.mediaHitLeft(x)
            assertEquals(x, left + size / 2)
            assertTrue(left >= 0 && left + size <= ImmersiveGlassCardLayout.WIDTH)
        }
        assertTrue(centres.zipWithNext().all { (a, b) ->
            ImmersiveGlassCardLayout.mediaHitLeft(a) + size <= ImmersiveGlassCardLayout.mediaHitLeft(b)
        })
    }
    @Test fun physicalGeometryMatchesRequestedDimensionsAndGap() {
        assertEquals(550, ImmersiveGlassCardLayout.WIDTH)
        assertEquals(300, ImmersiveGlassCardLayout.HEIGHT)
        assertEquals(60, ImmersiveGlassCardLayout.LEFT)
        assertEquals(35, ImmersiveGlassCardLayout.GAP)
        assertEquals(75, ImmersiveGlassCardLayout.top())
        assertEquals(410, ImmersiveGlassCardLayout.musicTop())
        assertEquals(35, ImmersiveGlassCardLayout.musicTop() -
            (ImmersiveGlassCardLayout.top() + ImmersiveGlassCardLayout.HEIGHT))
        assertEquals(-15f, ImmersiveGlassCardLayout.INSTRUCTION_OFFSET_Y, 0f)
    }
    @Test fun undersizedWindowDoesNotScaleOrOverflowCards() {
        assertFalse(ImmersiveGlassCardLayout.canFit(600, 1080))
        assertFalse(ImmersiveGlassCardLayout.canFit(1920, 600))
        assertFalse(ImmersiveGlassCardLayout.canFit(1920, 661))
        assertTrue(ImmersiveGlassCardLayout.canFit(610, 662))
        assertTrue(ImmersiveGlassCardLayout.canFitNavigation(610, 375))
        assertTrue(ImmersiveGlassCardLayout.canFit(1920, 1080))
    }
    @Test fun visibleArrowBottomTracksRoadGlyphBottomInsteadOfViewportPadding() {
        for (fontInkBottom in listOf(-1f, 0f, 3f, 7f)) {
            val roadBottom = ImmersiveGlassCardLayout.ROAD_BASELINE + fontInkBottom
            for (iconInkBottom in listOf(96, 107, 108, 110)) {
                val top = ImmersiveGlassCardLayout.arrowTop(roadBottom, iconInkBottom)
                assertEquals(roadBottom, top + iconInkBottom, 0f)
                assertTrue(top >= 0f)
                assertTrue(top + ImmersiveGlassCardLayout.ARROW_SIZE < 180f)
            }
        }
    }
    @Test fun distanceFitsReservedInsetWithoutEnlargingShortText() {
        assertEquals(55f, ImmersiveGlassCardLayout.distanceTextSize(120f, 190f), 0f)
        assertEquals(49.5f, ImmersiveGlassCardLayout.distanceTextSize(200f, 180f), 0.0001f)
        for (width in listOf(100f, 185f, 240f, 360f)) {
            val available = GlassPhoneStatusLayout.distanceMaxWidth(88f)
            val size = ImmersiveGlassCardLayout.distanceTextSize(width, available)
            assertTrue(size > 0f && size <= 55f)
            assertTrue(width * size / 55f <= available + 0.001f)
        }
    }
    @Test fun compactMusicCardPreservesVisualGapsAndNavigationDimensions() {
        assertEquals(156f, ImmersiveGlassCardLayout.PROGRESS_TOP, 0f)
        assertEquals(162f, ImmersiveGlassCardLayout.PROGRESS_BOTTOM, 0f)
        assertEquals(30f, ImmersiveGlassCardLayout.MEDIA_ICON_TOP -
            ImmersiveGlassCardLayout.PROGRESS_BOTTOM, 0f)
        assertEquals(30f, ImmersiveGlassCardLayout.MUSIC_HEIGHT -
            ImmersiveGlassCardLayout.MEDIA_ICON_BOTTOM, 0f)
        assertEquals(300, ImmersiveGlassCardLayout.HEIGHT)
        assertEquals(252, ImmersiveGlassCardLayout.MUSIC_HEIGHT)
    }
    @Test fun absentArtworkMusicGlyphIsCenteredByVisibleInkNotAdvanceOrFixedX() {
        for ((left, right) in listOf(0 to 28, 3 to 31, -2 to 25)) {
            val x = ImmersiveGlassCardLayout.coverPlaceholderLeft(left, right)
            assertEquals(83f, x + (left + right) / 2f, 0f)
        }
        val root = generateSequence(java.io.File(".").canonicalFile) { it.parentFile }
            .first { java.io.File(it, "shared/src/main").isDirectory }
        val code = java.io.File(root, "car/ui/src/main/java/com/dbtool/carplay/ui/screens/ImmersiveGlassCards.kt").readText()
        assertTrue(code.contains("} else drawMusicPlaceholder(c)"))
        assertTrue(code.contains("coverPlaceholderLeft(ink.left, ink.right)"))
        assertFalse(code.contains("\"♪\", 68f"))
    }
    @Test fun newSwitchIsDefaultOffAndDoesNotRequireProtocolReconnect() {
        val old = CarSessionSettings.default()
        assertFalse(old.immersiveGlassCardsEnabled)
        assertFalse(CarSettingsReconnectPolicy.requiresReconnect(old, old.copy(immersiveGlassCardsEnabled = true)))
    }
    @Test fun navigationDisplaysAllSuppliedInformation() {
        val result = glassNavigationText(CarNavigationSnapshot(
            routeActive = true, segmentMeters = 3300, routeMeters = 8300,
            routeSeconds = 1440, nextRoad = "芙蓉南路", arrivalEpochSeconds = 1800000000,
        ))
        assertEquals("3.3公里", result.distance)
        assertEquals("芙蓉南路", result.road)
        assertEquals("8.3公里 · 24分钟", result.remaining)
        assertTrue(result.arrival.matches(Regex("\\d{2}:\\d{2}到")))
    }
    @Test fun inactiveRouteDoesNotRetainPreviousInstruction() {
        assertEquals("等待导航信息", glassNavigationText(CarNavigationSnapshot(
            routeActive = false, nextRoad = "旧路", segmentMeters = 3300)).road)
        assertEquals("—", glassNavigationText(null).distance)
    }
    @Test fun partialAndInvalidFieldsAreNotFabricated() {
        val result = glassNavigationText(CarNavigationSnapshot(
            routeActive = true, currentRoad = " 当前路 ", routeMeters = -1,
            routeSeconds = -2, arrivalEpochSeconds = Long.MAX_VALUE,
        ))
        assertEquals("当前路", result.road)
        assertEquals("— · —", result.remaining)
        assertEquals("— 到", result.arrival)
    }
    @Test fun laneOrderBranchesAndRecommendationFollowPhoneData() {
        val lanes = glassLanes(CarNavigationSnapshot(routeActive = true, lanes = listOf(
            CarHudLane(8, listOf(0, 90), 90),
            CarHudLane(2, listOf(270, -90, 0), -90),
        )))
        assertEquals(listOf(-90, 0), lanes[0].bearings)
        assertEquals(-90, lanes[0].highlight)
        assertEquals(listOf(0, 90), lanes[1].bearings)
        assertEquals(90, lanes[1].highlight)
    }
    @Test fun unknownAndEmptyLanesKeepSlotsAndNeverInventRecommendation() {
        val lanes = glassLanes(CarNavigationSnapshot(routeActive = true, lanes = listOf(
            CarHudLane(0, listOf(720, 32767, 0), null),
            CarHudLane(1, emptyList(), 720),
        )))
        assertEquals(2, lanes.size)
        assertEquals(listOf(0), lanes[0].bearings)
        assertNull(lanes[0].highlight)
        assertTrue(lanes.all { it.unknown })
        assertTrue(lanes[1].bearings.isEmpty())
        assertNull(lanes[1].highlight)
    }
    @Test fun endedRouteClearsLaneRowAndBranchRenderingIsBounded() {
        val source = CarNavigationSnapshot(routeActive = false, lanes = listOf(CarHudLane(0, listOf(0), 0)))
        assertTrue(glassLanes(source).isEmpty())
        assertTrue(glassLanes(null).isEmpty())
        assertEquals(16, glassLanes(source.copy(routeActive = true,
            lanes = List(30) { CarHudLane(it, List(20) { n -> n }, null) })).size)
        assertEquals(8, glassLanes(source.copy(routeActive = true,
            lanes = listOf(CarHudLane(0, (0..20).toList(), null)))).single().bearings.size)
    }
}
