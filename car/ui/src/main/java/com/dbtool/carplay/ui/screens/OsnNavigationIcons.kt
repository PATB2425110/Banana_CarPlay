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

internal data class OsnLaneIcon(val resource: Int, val rotation: Float = 0f, val mirror: Boolean = false)

/** Verified primary lane silhouettes; exact non-cardinal bearings retain their angle via rotation. */
internal fun osnLaneIcon(bearing: Int): OsnLaneIcon? = when (val angle = glassLaneBearing(bearing)) {
    null -> null
    0 -> OsnLaneIcon(R.drawable.osn_lane_0)
    -90 -> OsnLaneIcon(R.drawable.osn_lane_1)
    90 -> OsnLaneIcon(R.drawable.osn_lane_3)
    else -> if (abs(angle) >= 155) OsnLaneIcon(R.drawable.osn_lane_5, mirror = angle > 0)
        else OsnLaneIcon(R.drawable.osn_lane_0, rotation = angle.toFloat())
}
