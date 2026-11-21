package com.dbtool.carplay.geely.vehicle

import org.junit.Assert.*
import org.junit.Test

class GeelyVehiclePropertyDecoderTest {
    class Complete(val propertyId: Int, val areaId: Int, val value: Any?)
    class WithStatus(val propertyId: Int, val areaId: Int, val value: Any?, val status: Any?)
    class BareInt(val value: Int)
    class Throwing {
        fun getPropertyId(): Int = throw SecurityException("denied")
        fun getAreaId(): Int = 1
        fun getValue(): Int = 0
    }

    @Test fun `full original-ROM wrapper preserves zero but presence is not availability`() {
        val value = GeelyVehiclePropertyDecoder.decode(Complete(31062, 1, 0), 42)
        assertNotNull(value)
        assertEquals(0, value!!.integerFor(31062))
        assertEquals(42L, value.receiptTimeMillis)
        assertNull(GeelyVehicleReading(31062, value).integer())
    }

    @Test fun `bare getter null value and reflected denial are unknown`() {
        assertNull(GeelyVehiclePropertyDecoder.decode(BareInt(0), 0))
        assertNull(GeelyVehiclePropertyDecoder.decode(Complete(31062, 1, null), 0))
        assertNull(GeelyVehiclePropertyDecoder.decode(Complete(31062, 1, 1L), 0))
        assertNull(GeelyVehiclePropertyDecoder.decode(Throwing(), 0))
    }

    @Test fun `newer public status is required to be available and exactly typed`() {
        assertEquals(
            500,
            GeelyVehiclePropertyDecoder.decode(WithStatus(31062, 1, 500, 0), 0)?.integerFor(31062),
        )
        for (status in listOf(null, 1, 2, -1, "0", 0L)) {
            assertNull(
                GeelyVehiclePropertyDecoder.decode(WithStatus(31062, 1, 500, status), 0)
                    ?.integerFor(31062),
            )
        }
    }
}
