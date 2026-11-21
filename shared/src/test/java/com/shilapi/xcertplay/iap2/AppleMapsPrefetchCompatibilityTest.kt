package com.shilapi.xcertplay.iap2

import com.shilapi.xcertplay.iap2.message.Iap2NavigationAccumulator
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import org.junit.Assert.*
import org.junit.Test

/** Host regression for the logged Apple order, not an ART/vehicle test.
 * The preceding source is an explicit test precondition, not inferred from missing log frames.
 */
class AppleMapsPrefetchCompatibilityTest {
    private var now = 0L
    private val acc = Iap2NavigationAccumulator { now }
    private fun u16(n: Int) = byteArrayOf((n shr 8).toByte(), n.toByte())
    private fun u32(n: Int) = byteArrayOf((n shr 24).toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte())
    private fun tlv(id: Int, value: ByteArray) = u16(value.size + 4) + u16(id) + value
    private fun text(s: String) = s.toByteArray(Charsets.UTF_8) + byteArrayOf(0)
    private fun frame(id: Int, vararg fields: ByteArray) = Iap2Frame(id, fields.fold(byteArrayOf()) { a, b -> a + b })
    private fun route(vararg fields: ByteArray) = acc.update(frame(0x5201, tlv(0, u16(42)), *fields))
    private fun source(s: String) = tlv(19, text(s))
    private fun state(n: Int) = tlv(1, byteArrayOf(n.toByte()))
    private fun maneuver(index: Int, kind: Int = 11) = acc.update(frame(0x5202,
        tlv(0, u16(42)), tlv(1, u16(index)), tlv(2, text("从仁北路出发")),
        tlv(3, byteArrayOf(kind.toByte())), tlv(4, text("仁北路"))))
    private fun lanes(event: Int, count: Int = 2) = acc.update(frame(0x5204,
        tlv(0, u16(42)), tlv(1, u16(event)),
        *(0 until count).map { i -> tlv(2,
            tlv(0, u16(i)) + tlv(1, byteArrayOf(2)) + tlv(3, u16(0))) }.toTypedArray()))
    private fun apple(emptyIndices: Boolean = false) = route(
        state(6), source("Apple Maps"), tlv(20, byteArrayOf(1)),
        tlv(4, text("柳州柳南万达广场")), tlv(7, u32(556534)),
        tlv(13, if (emptyIndices) byteArrayOf() else u16(0) + u16(1)), tlv(16, u16(0)))
    private fun oldRoute() {
        route(source("百度地图"), state(1), tlv(7, u32(9000)), tlv(13, u16(99)))
        maneuver(99, 2)
        lanes(99)
        route(tlv(16, u16(99)))
    }
    private fun prefetch() { maneuver(0); maneuver(1, 1); lanes(0); lanes(5, 3) }
    private fun assertAppleReady() {
        val result = apple()
        assertTrue(result.routeActive)
        assertEquals(556534, result.routeMeters)
        assertEquals(0, result.maneuverIndex)
        assertEquals(11, result.maneuverType)
        assertEquals(0, result.laneEventId)
        assertEquals(2, result.lanes.size)
    }

    @Test fun inactivePreviousSourceDoesNotEraseNewPrefetch() {
        route(source("百度地图"), state(0))
        now = 1_000_000_000L
        route(state(0))
        prefetch()
        now = 2_400_000_000L
        apple(emptyIndices = true)
        assertAppleReady()
    }

    @Test fun zeroBoundaryThenLateAppleSourceRetainsOnlyNewRecords() {
        oldRoute()
        route(state(0))
        prefetch()
        now = 1_500_000_000L
        val empty = apple(emptyIndices = true)
        assertEquals("new frame context survives an empty maneuver list", 556534, empty.routeMeters)
        assertNull(empty.maneuverType)
        assertAppleReady()
        val missingOld = route(tlv(13, u16(99)), tlv(16, u16(99)))
        assertTrue("old lane group must be discarded", missingOld.lanes.isEmpty())
    }

    @Test fun lateSourceCanFollowASourceLessPositiveFrame() {
        oldRoute(); route(state(0)); prefetch()
        route(state(6), tlv(13, u16(0)))
        now = 1_500_000_000L
        assertAppleReady()
    }

    @Test fun switchWithoutZeroBoundaryDoesNotReuseOldCaches() {
        oldRoute()
        val result = apple()
        assertNull(result.maneuverType)
        assertTrue(result.lanes.isEmpty())
    }

    @Test fun explicitClearDiscardsPrefetch() {
        oldRoute(); route(state(0)); prefetch()
        route(tlv(20, byteArrayOf(0)))
        val result = apple()
        assertNull(result.maneuverType)
        assertTrue(result.lanes.isEmpty())
    }

    @Test fun immediateEndDiscardsPrefetch() {
        oldRoute(); route(state(0)); prefetch(); route(state(2))
        assertNull(apple().maneuverType)
        assertTrue(apple().lanes.isEmpty())
    }

    @Test fun sessionResetDiscardsPrefetch() {
        route(source("百度地图")); route(state(0)); prefetch(); acc.reset()
        assertNull(apple().maneuverType)
        assertTrue(apple().lanes.isEmpty())
    }

    @Test fun expiredBoundaryDoesNotResurrectNewRecordsOnSourceSwitch() {
        route(source("百度地图")); route(state(0)); prefetch()
        now = 3_100_000_000L
        val result = apple()
        assertNull(result.maneuverType)
        assertTrue(result.lanes.isEmpty())
    }

    @Test fun activeRouteStillEndsAtTheExistingGraceDeadline() {
        oldRoute(); route(state(0)); prefetch()
        now = 3_100_000_000L
        assertFalse(acc.expirePendingEnd().hasContent)
        assertNull(apple().maneuverType)
    }

    @Test fun sameMapResumeKeepsExistingValidManeuver() {
        oldRoute(); route(state(0))
        now = 1_000_000_000L
        val result = route(source("百度地图"), state(1), tlv(13, u16(99)))
        assertEquals(2, result.maneuverType)
        assertEquals(9000, result.routeMeters)
    }

    @Test fun appleWithoutPreviousSourceAlreadySupportsPrefetch() {
        route(state(0)); prefetch(); assertAppleReady()
    }

    @Test fun otherSourceNamesUseTheSameBoundaryRule() {
        route(source("高德地图")); route(state(0)); prefetch()
        now = 1_400_000_000L
        assertAppleReady()
    }

    @Test fun preBoundaryRecordsAreNeverTransferredToTheNewSource() {
        oldRoute(); route(state(0)); prefetch()
        val result = route(source("Apple Maps"), state(6), tlv(13, u16(99)), tlv(16, u16(99)))
        assertNull(result.maneuverType)
        assertTrue(result.lanes.isEmpty())
        assertNull(result.routeMeters)
    }

    @Test fun prefetchIsConsumedAndCannotLeakIntoAThirdSource() {
        oldRoute(); route(state(0)); prefetch(); assertAppleReady()
        val result = route(source("高德地图"), state(1), tlv(13, u16(0)), tlv(16, u16(0)))
        assertNull(result.maneuverType)
        assertTrue(result.lanes.isEmpty())
    }

    @Test fun boundaryWindowIsNotRenewedByRepeatedZeroFrames() {
        route(source("百度地图")); route(state(0)); prefetch()
        now = 2_900_000_000L
        route(state(0))
        now = 3_100_000_000L
        assertNull(apple().maneuverType)
        assertTrue(apple().lanes.isEmpty())
    }

    @Test fun stagedManeuversRemainBounded() {
        route(source("百度地图")); route(state(0))
        repeat(40) { maneuver(it, 1) }
        val evicted = route(source("Apple Maps"), state(6), tlv(13, u16(0)))
        assertNull(evicted.maneuverType)
        val retained = route(tlv(13, u16(39)))
        assertEquals(1, retained.maneuverType)
    }

    @Test fun stagedLaneGroupsRemainBoundedAndDoNotInventASelection() {
        route(source("百度地图")); route(state(0))
        repeat(12) { lanes(it) }
        val evicted = route(source("Apple Maps"), state(6), tlv(16, u16(0)))
        assertTrue(evicted.lanes.isEmpty())
        assertEquals(2, route(tlv(16, u16(11))).lanes.size)
    }

    @Test fun laneOnlyPrefetchDoesNotInventAManeuverOrRouteStart() {
        route(source("百度地图")); route(state(0)); lanes(0)
        val result = apple(emptyIndices = true)
        assertFalse(result.routeActive)
        assertNull(result.maneuverType)
        assertEquals("new route context is retained without inventing an arrow", 556534, result.routeMeters)
        assertEquals(2, result.lanes.size)
    }

    @Test fun inactiveStartupResetAfterPrefetchDoesNotLoseTheNewRecords() {
        route(state(0)); prefetch()
        now = 450_000_000L
        val reset = route(state(2))
        assertFalse(reset.routeActive)
        assertFalse("reset must still publish no navigation", reset.hasContent)
        now = 1_080_000_000L
        assertAppleReady()
    }

    @Test fun inactiveStartupClearAfterPrefetchRetainsOnlyAStagedCandidate() {
        route(state(0)); prefetch()
        now = 450_000_000L
        val reset = route(tlv(20, byteArrayOf(0)))
        assertFalse(reset.hasContent)
        assertFalse(reset.routeActive)
        now = 1_080_000_000L
        assertAppleReady()
    }

    @Test fun stagedCandidateAfterStartupResetCannotReviveAnExpiredRoute() {
        route(state(0)); prefetch()
        now = 450_000_000L
        route(state(2))
        now = 3_100_000_000L
        assertNull(apple().maneuverType)
        assertTrue(apple().lanes.isEmpty())
    }

    @Test fun startupResetDoesNotSelectARecordWithoutNewPositiveSelection() {
        route(state(0)); prefetch(); route(state(2))
        now = 1_000_000_000L
        val metadata = route(source("Apple Maps"))
        assertFalse(metadata.hasContent)
        assertFalse(metadata.routeActive)
        now = 1_080_000_000L
        assertAppleReady()
    }

    @Test fun diagnosticSinkFailureCannotChangeNavigationParsing() {
        acc.setDiagnostics { throw IllegalStateException("host diagnostic failure") }
        route(state(0)); prefetch(); route(state(2)); assertAppleReady()
    }

    @Test fun positiveSourceWithoutSelectionMustNotConsumeStartupCandidateWindow() {
        // The actual state/source/index ordering is logged. The initial source
        // spelling is deliberately a fixture: that frame's text was rate-limited.
        route(state(0))
        route(state(3), source("startup-source-fixture"))
        prefetch()
        route(state(3), source("startup-source-fixture"))
        now = 1_838_000_000L
        apple(emptyIndices = true)
        now = 1_875_000_000L
        assertAppleReady()
    }

    @Test fun positiveSourceWithoutSelectionDoesNotExtendOriginalCandidateDeadline() {
        route(state(0)); route(state(3), source("startup-source-fixture")); prefetch()
        now = 2_900_000_000L
        route(state(3), source("startup-source-fixture"))
        now = 3_100_000_000L
        assertNull(apple().maneuverType)
        assertTrue(apple().lanes.isEmpty())
    }

    @Test fun resolvedSourceSelectionConsumesCandidateBeforeThirdSource() {
        route(state(0)); route(state(3), source("startup-source-fixture")); prefetch()
        assertAppleReady()
        val switched = route(state(6), source("third-source-fixture"), tlv(13, u16(0)))
        assertNull(switched.maneuverType)
        assertTrue(switched.lanes.isEmpty())
    }

    @Test fun parserTraceIdentifiesControlsWithoutLeakingRoadsOrDestination() {
        val events = mutableListOf<String>()
        acc.setDiagnostics { events += it }
        route(state(0)); prefetch(); route(state(2)); assertAppleReady()
        assertTrue(events.any { it.contains("reason=state_2") })
        assertTrue(events.any { it.contains("control state=6") })
        assertFalse(events.any { it.contains("柳州") || it.contains("仁北路") })
    }
}
