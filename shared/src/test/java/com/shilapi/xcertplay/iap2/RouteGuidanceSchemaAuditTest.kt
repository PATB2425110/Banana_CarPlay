package com.shilapi.xcertplay.iap2

import com.shilapi.xcertplay.iap2.message.Iap2NavigationAccumulator
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import org.junit.Assert.*
import org.junit.Test

/** Regressions for gaps originally reproduced by the schema audit. */
class RouteGuidanceSchemaAuditTest {
    private val acc = Iap2NavigationAccumulator { 0L }
    private fun u16(n: Int) = byteArrayOf((n shr 8).toByte(), n.toByte())
    private fun tlv(id: Int, value: ByteArray) = u16(value.size + 4) + u16(id) + value
    private fun route(vararg fields: ByteArray) =
        acc.update(Iap2Frame(0x5201, fields.fold(tlv(0, u16(42))) { a, b -> a + b }))
    private fun state(n: Int) = tlv(1, byteArrayOf(n.toByte()))
    private fun index(n: Int) = tlv(13, u16(n))
    private fun maneuver(n: Int, kind: Int) =
        acc.update(Iap2Frame(0x5202, tlv(0, u16(42)) + tlv(1, u16(n)) +
            tlv(2, "instruction$n".toByteArray()) + tlv(3, byteArrayOf(kind.toByte()))))
    private fun active() {
        route(state(1), index(0))
        maneuver(0, 1)
    }

    @Test fun preservesLoadingLocatingReroutingAndHeadingStates() {
        active()
        for (n in 3..6) {
            val s = route(state(n))
            assertTrue(s.routeActive)
            assertEquals(n, s.guidanceState)
            if (n >= 5) assertNull(s.maneuverType)
        }
    }

    @Test fun missingNewManeuverNeverRetainsPreviousTurn() {
        active()
        val s = route(index(7))
        assertEquals(7, s.maneuverIndex)
        assertNull(s.maneuverType)
        assertNull(s.maneuverDescription)
    }

    @Test fun repeatedIndexFieldsKeepOrderedListAndSelectFirst() {
        maneuver(0, 1)
        maneuver(1, 2)
        val s = route(state(1), index(0), index(1))
        assertEquals(listOf(0, 1), s.maneuverIndices)
        assertEquals(0, s.maneuverIndex)
        assertEquals(1, s.maneuverType)
    }

    @Test fun auditPackedIndexListSelectsFirstOnly() {
        maneuver(0, 1)
        maneuver(1, 2)
        val s = route(state(1), tlv(13, u16(0) + u16(1)))
        assertEquals(0, s.maneuverIndex)
        assertEquals(1, s.maneuverType)
        assertEquals(listOf(0, 1), s.maneuverIndices)
    }

    @Test fun emptyIndexListKeepsOtherContextAndClearsArrow() {
        active()
        val s = route(tlv(13, byteArrayOf()), tlv(4, "new destination".toByteArray()))
        assertEquals("new destination", s.destination)
        assertNull(s.maneuverType)
    }

    @Test fun componentIsFilteredAndIsNotSessionIdentity() {
        val s = acc.update(Iap2Frame(0x5201, tlv(0, u16(99)) + state(1) +
            tlv(3, "other component road".toByteArray())))
        assertNull(s.currentRoad)
        assertNull(s.sessionId)
        assertNull(s.revision)
    }

    @Test fun foregroundAndManeuverStageAreRetainedAsDeltas() {
        active()
        val delta = route(tlv(15, byteArrayOf(0)), tlv(2, byteArrayOf(3)))
        assertEquals(false, delta.shownInApp)
        assertEquals(3, delta.maneuverState)
        assertTrue(delta.routeActive)
        assertEquals(delta, route())
    }

    @Test fun laneTotalZeroClearsPreviouslyVisibleLanes() {
        active()
        val lane = tlv(0, u16(0)) + tlv(1, byteArrayOf(2)) + tlv(3, u16(0))
        acc.update(Iap2Frame(0x5204, tlv(0, u16(42)) + tlv(1, u16(0)) + tlv(2, lane)))
        assertEquals(1, route(tlv(16, u16(0))).lanes.size)
        assertEquals(0, route(tlv(17, u16(0))).lanes.size)
    }

    @Test fun rerouteGeneratesNewLocalScopeAndResolvesOnlyNewDetails() {
        active()
        val first = route()
        val pending = route(state(5))
        assertNotEquals(first.routeGeneration, pending.routeGeneration)
        assertNull(pending.maneuverType)
        val waiting = route(state(1), index(0))
        assertNull(waiting.maneuverType)
        val resolved = maneuver(0, 2)
        assertEquals(2, resolved.maneuverType)
        assertEquals(pending.routeGeneration, resolved.routeGeneration)
    }

    @Test fun optionalMetadataAndTerminatedStringsSurviveDeltaUpdates() {
        val s = route(state(1), tlv(14, u16(9)), tlv(17, u16(2)),
            tlv(18, "keep left\u0000".toByteArray()), tlv(19, "百度地图\u0000".toByteArray()),
            tlv(20, byteArrayOf(1)), tlv(8, "12.5\u0000".toByteArray()), tlv(9, byteArrayOf(1)),
            tlv(11, "300".toByteArray()), tlv(12, byteArrayOf(4)))
        assertEquals(9, s.maneuverCount)
        assertEquals(2, s.laneCount)
        assertEquals("keep left", s.laneDescription)
        assertEquals("百度地图", s.routeSource)
        assertEquals(true, s.sourceSupportsGuidance)
        assertEquals("12.5", s.routeDistanceDisplay)
        assertEquals(1, s.routeDistanceUnits)
        assertEquals("300", s.maneuverDistanceDisplay)
        assertEquals(4, s.maneuverDistanceUnits)
        assertEquals(s, route(tlv(15, byteArrayOf(1))).copy(shownInApp = null))
    }

    @Test fun malformedKnownFieldCannotPartiallyOverwriteRoute() {
        active()
        val before = route()
        assertEquals(before, route(tlv(3, "bad".toByteArray()), tlv(1, byteArrayOf(1, 0))))
        assertEquals(before, route(tlv(13, byteArrayOf(0))))
        assertEquals(before, route(state(7)))
    }

    @Test fun distanceFromPreviousTurnDoesNotLeakIntoNewSelection() {
        active()
        route(tlv(10, byteArrayOf(0, 0, 1, 44)), tlv(11, "300".toByteArray()), tlv(12, byteArrayOf(2)))
        assertEquals(300, route().segmentMeters)
        assertNull(route(index(7)).segmentMeters)
        assertNull(route().maneuverDistanceDisplay)
        assertEquals(2, maneuver(7, 2).maneuverType)
        assertNull(route().segmentMeters)
    }
}
