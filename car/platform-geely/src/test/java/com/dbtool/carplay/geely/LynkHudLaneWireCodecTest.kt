package com.dbtool.carplay.geely

import com.dbtool.carplay.api.navigation.CarHudLane
import org.junit.Assert.*
import org.junit.Test

class LynkHudLaneWireCodecTest {
    private fun wire(lanes: List<CarHudLane>): List<Any> = mutableListOf<Any>().also { out ->
        LynkHudLaneWireCodec.write(lanes, 9L, 7L, 123L, { out.add(it) }, { out.add(it) }, { out.add(it) })
    }

    @Test fun `every lane position direction and highlight reaches code11`() {
        val out = wire(listOf(CarHudLane(0, listOf(-90, 0), -90), CarHudLane(1, listOf(0), 0), CarHudLane(2, listOf(90))))
        assertEquals(listOf(9L, 7L, 0, 123L, 1, 3, 0, 2, -90, 0, "highlight=-90", 1, 1, 0, "highlight=0", 2, 1, 90, ""), out)
    }

    @Test fun `unknown bearing is preserved without invented straight or highlight`() {
        assertEquals(listOf(9L, 7L, 0, 123L, 1, 1, 5, 1, 17, ""), wire(listOf(CarHudLane(5, listOf(17)))))
    }

    @Test fun `empty lanes explicitly encode inactive even on code11`() {
        assertEquals(listOf<Any>(9L, 7L, 0, 123L, 0, 0), wire(emptyList()))
    }

    @Test fun `wire count bounds agree with bounded body`() {
        val out = wire((0..20).map { CarHudLane(it, (-4..4).toList()) })
        assertEquals(16, out[5])
        assertEquals(6 + 16 * 11, out.size)
        assertEquals(8, out[7])
    }
}
