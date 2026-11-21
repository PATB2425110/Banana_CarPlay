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
    @Test fun verifiedLaneSilhouettesMatchCardinalBearings() {
        assertEquals(R.drawable.osn_lane_0, osnLaneIcon(0)?.resource)
        assertEquals(R.drawable.osn_lane_1, osnLaneIcon(-90)?.resource)
        assertEquals(R.drawable.osn_lane_1, osnLaneIcon(270)?.resource)
        assertEquals(R.drawable.osn_lane_3, osnLaneIcon(90)?.resource)
        assertEquals(R.drawable.osn_lane_5, osnLaneIcon(-180)?.resource)
        assertEquals(45f, osnLaneIcon(45)!!.rotation, 0f)
        assertTrue(osnLaneIcon(170)!!.mirror)
    }
    @Test fun invalidLaneAnglesAreNotConvertedToStraight() {
        assertNull(osnLaneIcon(720))
        assertNull(osnLaneIcon(32767))
    }
}
