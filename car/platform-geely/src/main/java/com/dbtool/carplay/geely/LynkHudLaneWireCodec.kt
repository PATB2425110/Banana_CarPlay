package com.dbtool.carplay.geely

import com.dbtool.carplay.api.navigation.CarHudLane

/** Exact code11 call order; separate from the navigation state machine. */
internal object LynkHudLaneWireCodec {
    fun write(
        lanes: List<CarHudLane>, request: Long, generation: Long, timestamp: Long,
        int: (Int) -> Unit, long: (Long) -> Unit, string: (String) -> Unit,
    ) {
        long(request)
        long(generation)
        int(0) // lane group
        long(timestamp)
        int(if (lanes.isEmpty()) 0 else 1)
        val bounded = lanes.take(16)
        int(bounded.size)
        bounded.forEach { lane ->
            int(lane.index)
            val angles = lane.angles.take(8)
            int(angles.size)
            angles.forEach(int)
            string(lane.highlightAngle?.let { "highlight=$it" }.orEmpty())
        }
    }
}
