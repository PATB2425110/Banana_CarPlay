package com.dbtool.carplay.ui.screens

import com.dbtool.carplay.ui.R
import com.dbtool.carplay.ui.cluster.ClusterManeuverKind
import com.dbtool.carplay.ui.cluster.clusterManeuverKindOf
import org.junit.Assert.*
import org.junit.Test

class OsnNavigationIconsTest {
    @Test fun routeStartRemainsStraightAndDoesNotUseApkArrayIndex() {
        assertEquals(R.drawable.osn_navi_turn_front, osnManeuverIcon(clusterManeuverKindOf(11)))
        assertEquals(R.drawable.osn_navi_turn_back, osnManeuverIcon(ClusterManeuverKind.UTURN))
        assertNotEquals(osnManeuverIcon(clusterManeuverKindOf(11)),
            osnManeuverIcon(ClusterManeuverKind.UTURN))
    }
    @Test fun allKnownUiSemanticsHaveResourcesAndUnknownRemainsBlank() {
        ClusterManeuverKind.entries.filter { it != ClusterManeuverKind.GENERIC }.forEach {
            assertNotNull(osnManeuverIcon(it))
        }
        assertNull(osnManeuverIcon(null))
        assertNull(osnManeuverIcon(ClusterManeuverKind.GENERIC))
    }
    @Test fun laneDirectionsUseExactlyTheSameLargeManeuverResources() {
        for ((bearing, kind) in listOf(
            0 to ClusterManeuverKind.STRAIGHT,
            -90 to ClusterManeuverKind.LEFT,
            90 to ClusterManeuverKind.RIGHT,
            -45 to ClusterManeuverKind.KEEP_LEFT,
            45 to ClusterManeuverKind.KEEP_RIGHT,
            -180 to ClusterManeuverKind.UTURN,
        )) {
            assertEquals(osnManeuverIcon(kind), osnLaneIcon(bearing)?.resource)
            assertNull(osnLaneIcon(bearing)?.exactBearing)
        }
        assertEquals(osnLaneIcon(-90), osnLaneIcon(270))
        assertEquals(osnLaneIcon(-45), osnLaneIcon(315))
        assertTrue(osnLaneIcon(170)!!.mirror)
    }
    @Test fun unusualValidLaneBearingKeepsExactAngleRatherThanUsingOldRoundedAsset() {
        for (angle in listOf(-135, -30, 1, 30, 75, 135)) {
            val icon = requireNotNull(osnLaneIcon(angle))
            assertEquals(R.drawable.osn_navi_turn_front, icon.resource)
            assertEquals(angle, icon.exactBearing)
        }
    }
    @Test fun invalidLaneAnglesAreNotConvertedToStraight() {
        assertNull(osnLaneIcon(720))
        assertNull(osnLaneIcon(32767))
    }
}
