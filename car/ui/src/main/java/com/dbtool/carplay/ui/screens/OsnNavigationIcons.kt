package com.dbtool.carplay.ui.screens

import com.dbtool.carplay.ui.R
import com.dbtool.carplay.ui.cluster.ClusterManeuverKind
import kotlin.math.abs

/** Semantic selection only. APK array indices are NOT iAP2 manoeuvre/bearing values. */
internal fun osnManeuverIcon(kind: ClusterManeuverKind?): Int? = when (kind) {
    ClusterManeuverKind.STRAIGHT -> R.drawable.osn_navi_turn_front
    ClusterManeuverKind.LEFT -> R.drawable.osn_navi_turn_left
    ClusterManeuverKind.RIGHT -> R.drawable.osn_navi_turn_right
    ClusterManeuverKind.UTURN -> R.drawable.osn_navi_turn_back
    ClusterManeuverKind.ROUNDABOUT -> R.drawable.osn_navi_turn_ring
    ClusterManeuverKind.KEEP_LEFT -> R.drawable.osn_navi_left_front
    ClusterManeuverKind.KEEP_RIGHT -> R.drawable.osn_navi_right_front
    ClusterManeuverKind.ARRIVAL -> R.drawable.osn_navi_dest
    else -> null
}

internal data class OsnLaneIcon(
    val resource: Int,
    val mirror: Boolean = false,
    val exactBearing: Int? = null,
)

/** Reuse the LARGE maneuver resources, not the old rounded lane silhouettes. */
internal fun osnLaneIcon(bearing: Int): OsnLaneIcon? = when (val angle = glassLaneBearing(bearing)) {
    null -> null
    0 -> OsnLaneIcon(requireNotNull(osnManeuverIcon(ClusterManeuverKind.STRAIGHT)))
    -90 -> OsnLaneIcon(requireNotNull(osnManeuverIcon(ClusterManeuverKind.LEFT)))
    90 -> OsnLaneIcon(requireNotNull(osnManeuverIcon(ClusterManeuverKind.RIGHT)))
    -45 -> OsnLaneIcon(requireNotNull(osnManeuverIcon(ClusterManeuverKind.KEEP_LEFT)))
    45 -> OsnLaneIcon(requireNotNull(osnManeuverIcon(ClusterManeuverKind.KEEP_RIGHT)))
    else -> if (abs(angle) >= 155) {
        OsnLaneIcon(requireNotNull(osnManeuverIcon(ClusterManeuverKind.UTURN)), mirror = angle > 0)
    } else {
        // Do not round an unusual phone bearing to left/right/straight or rotate its whole stem.
        OsnLaneIcon(requireNotNull(osnManeuverIcon(ClusterManeuverKind.STRAIGHT)), exactBearing = angle)
    }
}
