package com.dbtool.carplay.geely

import com.dbtool.carplay.api.navigation.CarHudGuidance

/** Parcel call order, not byte offsets. Tested without an Android runtime. */
internal interface HudParcelWriter {
    fun writeInt(value: Int)
    fun writeLong(value: Long)
    fun writeString(value: String)
}

internal object LynkHudWireCodec {
    fun writeLegacy(out: HudParcelWriter, guidance: CarHudGuidance, turnKind: Int) {
        out.writeInt(1) // active
        out.writeLong(guidance.updatedAtMs)
        out.writeInt(guidance.routeMeters?.coerceAtLeast(0) ?: 0)
        out.writeInt(guidance.routeSeconds?.coerceAtLeast(0) ?: 0)
        out.writeInt(guidance.segmentMeters?.coerceAtLeast(0) ?: 0)
        out.writeInt(0) // no segment-time measurement; never use the full-route time
        out.writeInt(turnKind)
        out.writeInt(12) // paired bridge maps 12 to Baidu roadLevel 8
        out.writeString(guidance.currentRoad.orEmpty())
        out.writeString((guidance.nextManeuverRoad ?: guidance.currentRoad).orEmpty())
        out.writeString("") // next-next road is not supplied; destination is not a road name
        repeat(5) { out.writeInt(0) } // lights/camera fields not supplied
    }

    fun writeModern(
        out: HudParcelWriter,
        guidance: CarHudGuidance,
        turn: NormalizedHudTurn,
        requestId: Long,
        generation: Long,
    ) {
        out.writeInt(0x48445532)
        out.writeInt(2)
        out.writeLong(requestId)
        out.writeLong(guidance.sessionId ?: 0L)
        out.writeLong(generation)
        out.writeInt(1)
        out.writeInt(0)
        writeLegacy(out, guidance, turn.baiduTurnKind)
        out.writeLong(guidance.revision ?: 0L)
        out.writeInt(0) // leg index is not supplied
        out.writeInt(guidance.maneuverIndex ?: -1)
        out.writeString(turn.pointRole)
        out.writeString(turn.semantic)
        out.writeString("UNKNOWN")
        out.writeString("LYNK_NORMALIZED_V1")
        out.writeInt(turn.expectedTurnId)
        out.writeInt(if (turn.isStraight) 1 else 0)
        out.writeInt(0) // no fabricated upcoming elements
    }
}
