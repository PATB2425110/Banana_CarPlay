package com.dbtool.carplay.geely.vehicle

import org.junit.Assert.*
import org.junit.Test

class GeelyStandardPropertyDecoderTest {
    class Config {
        fun getPropertyId(): Int = 0x21407956
        fun getAccess(): Int = 1
        fun getPropertyType(): Class<*> = Integer::class.java
        fun getAreaType(): Int = 0
        fun isGlobalProperty(): Boolean = true
        fun getAreaIds(): IntArray = intArrayOf()
        fun getChangeMode(): Int = 1
    }
    class Value(
        val propertyId: Any?, val areaId: Any?, val status: Any?,
        val timestamp: Any?, val value: Any?,
    )
    class Legacy(val propertyId: Int, val areaId: Int, val value: Int)
    class MissingConfigIdentity(val propertyId: Int)

    @Test fun `public config getters include actual change mode and area metadata`() {
        val decoded = GeelyStandardPropertyDecoder.config(Config())!!
        assertEquals(0x21407956, decoded.propertyId)
        assertEquals(1, decoded.access)
        assertEquals("java.lang.Integer", decoded.propertyTypeName)
        assertEquals(0, decoded.areaType)
        assertTrue(decoded.isGlobal)
        assertTrue(decoded.areaIds.isEmpty())
        assertEquals(GeelyStandardPropertyPolicy.ON_CHANGE, decoded.changeMode)
        assertNull(GeelyStandardPropertyDecoder.config(MissingConfigIdentity(0x21407956)))
    }

    @Test fun `raw decoder preserves actual HAL header status and long nanosecond timestamp`() {
        val raw = GeelyStandardPropertyDecoder.property(Value(0x21407956, 0, 0, 123_456_789L, 0))!!
        assertEquals(0x21407956, raw.propertyId)
        assertEquals(0, raw.areaId)
        assertEquals(0, raw.status)
        assertEquals(123_456_789L, raw.timestampNanos)
        assertEquals(0, raw.value)
    }

    @Test fun `missing metadata or nonexact timestamp types cannot fabricate availability`() {
        assertNull(GeelyStandardPropertyDecoder.property(Legacy(31062, 1, 0)))
        for (timestamp in listOf(null, 100, 100.0, "100")) {
            assertNull(GeelyStandardPropertyDecoder.property(Value(0x21407956, 0, 0, timestamp, 0)))
        }
        assertNull(GeelyStandardPropertyDecoder.property(Value(0x21407956L, 0, 0, 100L, 0)))
        assertNull(GeelyStandardPropertyDecoder.property(Value(0x21407956, 0, null, 100L, 0)))
    }
}
