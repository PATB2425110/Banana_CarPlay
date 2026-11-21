package com.shilapi.xcertplay.iap2

import com.shilapi.xcertplay.iap2.message.Iap2NavigationAccumulator
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import org.junit.Assert.*
import org.junit.Test

/** Regression for the source half of the Lynk lane bridge; real decoder, no UI/Binder. */
class Iap2LaneSourceIntegrityTest {
    private fun short(value: Int) = byteArrayOf((value ushr 8).toByte(), value.toByte())
    private fun tlv(id: Int, value: ByteArray) = short(value.size + 4) + short(id) + value
    private fun group(index: Int, angles: List<Int>, highlight: Int? = null): ByteArray {
        var body = tlv(0, short(index)) + tlv(1, byteArrayOf(0))
        body += tlv(2, angles.flatMap { short(it).asIterable() }.toByteArray())
        if (highlight != null) body += tlv(3, short(highlight))
        return tlv(2, body)
    }
    private fun lanes(vararg groups: ByteArray) = Iap2Frame(0x5204,
        tlv(0, short(42)) + tlv(1, short(7)) + groups.fold(byteArrayOf()) { all, next -> all + next })

    @Test fun `decoder retains whole ordered group and every recommended direction`() {
        val accumulator = Iap2NavigationAccumulator()
        val state = accumulator.update(lanes(group(2, listOf(90)), group(0, listOf(0, -90), -90), group(1, listOf(0), 0)))
        assertEquals(listOf(0, 1, 2), state.lanes.map { it.index })
        assertEquals(listOf(-90, 0), state.lanes[0].angles)
        assertEquals(listOf(0), state.lanes[1].angles)
        assertEquals(listOf(90), state.lanes[2].angles)
        assertEquals(listOf(-90, 0, null), state.lanes.map { it.highlightAngle })
        assertEquals(7, state.laneEventId)
    }

    @Test fun `unknown angle remains unknown and duplicate position cannot partially replace group`() {
        val accumulator = Iap2NavigationAccumulator()
        val before = accumulator.update(lanes(group(5, listOf(17))))
        assertEquals(listOf(17), before.lanes.single().angles)
        assertNull(before.lanes.single().highlightAngle)
        val after = accumulator.update(lanes(group(0, listOf(0)), group(0, listOf(90))))
        assertEquals(before.lanes, after.lanes)
    }

    @Test fun `no selected lane sentinel clears visible whole group`() {
        val accumulator = Iap2NavigationAccumulator()
        accumulator.update(lanes(group(0, listOf(-90)), group(1, listOf(0))))
        assertEquals(2, accumulator.update(Iap2Frame(0x5201, tlv(0x10, short(7)))).lanes.size)
        assertTrue(accumulator.update(Iap2Frame(0x5201, tlv(0x10, short(0xffff)))).lanes.isEmpty())
    }

    @Test fun `prefetched group cannot resurrect lanes after explicit no selection`() {
        val accumulator = Iap2NavigationAccumulator()
        accumulator.update(lanes(group(0, listOf(0))))
        accumulator.update(Iap2Frame(0x5201, tlv(0x10, short(0xffff))))
        assertTrue(accumulator.update(lanes(group(0, listOf(90)))).lanes.isEmpty())
        assertEquals(1, accumulator.update(Iap2Frame(0x5201, tlv(0x10, short(7)))).lanes.size)
    }

    @Test fun `valid empty replacement clears selected lane event`() {
        val accumulator = Iap2NavigationAccumulator()
        accumulator.update(lanes(group(0, listOf(0))))
        val cleared = accumulator.update(lanes())
        assertTrue(cleared.lanes.isEmpty())
        assertNull(cleared.laneEventId)
    }

    @Test fun `new maneuver without lane selection does not inherit old arrows`() {
        val accumulator = Iap2NavigationAccumulator()
        accumulator.update(Iap2Frame(0x5201, tlv(13, short(0))))
        accumulator.update(lanes(group(0, listOf(-90))))
        assertTrue(accumulator.update(Iap2Frame(0x5201, tlv(13, short(1)))).lanes.isEmpty())
    }

    @Test fun `delta metadata for same maneuver retains current lanes`() {
        val accumulator = Iap2NavigationAccumulator()
        accumulator.update(Iap2Frame(0x5201, tlv(13, short(0))))
        accumulator.update(lanes(group(0, listOf(-90))))
        assertEquals(1, accumulator.update(Iap2Frame(0x5201, tlv(13, short(0)))).lanes.size)
    }
}
