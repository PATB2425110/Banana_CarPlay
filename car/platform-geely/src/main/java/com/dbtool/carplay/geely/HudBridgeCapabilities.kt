package com.dbtool.carplay.geely

/** An optional lane extension must never revoke an otherwise compatible navigation channel. */
internal data class HudBridgeCapabilities(val navigation: Boolean, val lanes: Boolean) {
    companion object {
        const val IDENTITY_TAG = "DBPLAY_LYNK_BRIDGE_V1"
        const val LANE_TAG = "DBPLAY_LYNK_LANES_V1"

        fun fromTags(identity: String?, laneTag: String?): HudBridgeCapabilities {
            val navigation = identity == IDENTITY_TAG
            return HudBridgeCapabilities(navigation, navigation && laneTag == LANE_TAG)
        }
    }
}
