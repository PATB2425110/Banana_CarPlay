package com.dbtool.carplay.geely

import com.dbtool.carplay.api.navigation.CarHudGuidance
import org.junit.Assert.*
import org.junit.Test

class LynkHudWireCodecTest {
    private class RecordingParcel : HudParcelWriter {
        val values = mutableListOf<Any>()
        val types = mutableListOf<String>()
        override fun writeInt(value: Int) { types += "Int"; values += value }
        override fun writeLong(value: Long) { types += "Long"; values += value }
        override fun writeString(value: String) { types += "String"; values += value }
    }
    private val guidance = CarHudGuidance(
        updatedAtMs = 123456789L, routeMeters = 1200, routeSeconds = 300,
        segmentMeters = 88, maneuverType = 2, currentRoad = "甲路",
        nextRoad = "目的地", sessionId = 7, revision = 9,
        nextManeuverRoad = "乙路", maneuverIndex = 12,
    )
    private val turn = NormalizedHudTurn(3, "RIGHT", "NORMAL", 103, false)

    @Test fun `legacy exactly matches original reader sixteen typed slots`() {
        val parcel = RecordingParcel()
        LynkHudWireCodec.writeLegacy(parcel, guidance, 3)
        assertEquals(listOf("Int", "Long", "Int", "Int", "Int", "Int", "Int", "Int",
            "String", "String", "String", "Int", "Int", "Int", "Int", "Int"), parcel.types)
        assertEquals(listOf(1, 123456789L, 1200, 300, 88, 0, 3, 12,
            "甲路", "乙路", "", 0, 0, 0, 0, 0), parcel.values)
    }

    @Test fun `modern embeds exact legacy and complete extension order`() {
        val legacy = RecordingParcel()
        val modern = RecordingParcel()
        LynkHudWireCodec.writeLegacy(legacy, guidance, 3)
        LynkHudWireCodec.writeModern(modern, guidance, turn, 42, 8)
        assertEquals(listOf<Any>(0x48445532, 2, 42L, 7L, 8L, 1, 0), modern.values.take(7))
        assertEquals(legacy.values, modern.values.subList(7, 23))
        assertEquals(listOf(9L, 0, 12, "NORMAL", "RIGHT", "UNKNOWN", "LYNK_NORMALIZED_V1",
            103, 0, 0), modern.values.drop(23))
        assertEquals(33, modern.values.size)
    }

    @Test fun `null source fields never fabricate route distance time or upcoming`() {
        val parcel = RecordingParcel()
        LynkHudWireCodec.writeModern(parcel, guidance.copy(routeMeters = null,
            routeSeconds = null, segmentMeters = null, currentRoad = null,
            nextManeuverRoad = null, nextRoad = null, maneuverIndex = null), turn, 1, 1)
        assertEquals(listOf(0, 0, 0, 0), parcel.values.subList(9, 13))
        assertEquals(listOf("", "", ""), parcel.values.subList(15, 18))
        assertEquals(-1, parcel.values[25])
        assertEquals(0, parcel.values.last())
    }

    @Test fun `negative distances and seconds are clamped at serialization boundary`() {
        val parcel = RecordingParcel()
        LynkHudWireCodec.writeLegacy(parcel,
            guidance.copy(routeMeters = -1, routeSeconds = -2, segmentMeters = -3), 3)
        assertEquals(listOf(0, 0, 0, 0), parcel.values.subList(2, 6))
    }

    @Test fun `heartbeat changes timestamp only in serialized complete state`() {
        val first = RecordingParcel()
        val heartbeat = RecordingParcel()
        LynkHudWireCodec.writeLegacy(first, guidance, 3)
        LynkHudWireCodec.writeLegacy(heartbeat, guidance.copy(updatedAtMs = 987654321L), 3)
        assertNotEquals(first.values[1], heartbeat.values[1])
        assertEquals(first.values.filterIndexed { i, _ -> i != 1 },
            heartbeat.values.filterIndexed { i, _ -> i != 1 })
    }
}
