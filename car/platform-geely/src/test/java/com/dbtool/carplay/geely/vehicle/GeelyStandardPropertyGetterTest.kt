package com.dbtool.carplay.geely.vehicle

import com.dbtool.carplay.api.vehicle.CarVehicleStatusField
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import org.junit.Assert.*
import org.junit.Test

class GeelyStandardPropertyGetterTest {
    class Value(val propertyId: Int, val areaId: Int, val value: Int) {
        fun getStatus(): Int = 0
        fun getTimestamp(): Long = 1_000_000_000L
    }

    /** It really THROWS from the reflected public getter, like the SDK's type check; no decoder shortcut. */
    class Manager(val failures: Map<Int, Throwable> = emptyMap()) {
        val calls = mutableListOf<Int>()
        fun getProperty(type: Class<*>, propertyId: Int, area: Int): Value {
            calls += propertyId
            failures[propertyId]?.let { throw it }
            require(type == Integer::class.java)
            val value = when (propertyId) {
                0x21407956 -> 500
                0x21407957 -> 0
                0x2140792D -> 1
                0x2140779B -> 0
                else -> error("Unexpected test property")
            }
            return Value(propertyId, area, value)
        }
    }

    private val method = Manager::class.java.getMethod(
        "getProperty", Class::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
    )

    @Test fun `real reflected getter IllegalArgumentException only loses its field and later getters still run`() {
        val manager = Manager(mapOf(0x21407956 to IllegalArgumentException("Expected Integer, received Float")))
        val readings = mutableMapOf<GeelyVehicleSignal, GeelyVehicleReading>()
        val invalidated = mutableListOf<CarVehicleStatusField>()
        val signals = listOf(
            GeelyVehicleSignal.FUEL_RANGE, GeelyVehicleSignal.FUEL_RANGE_UNIT,
            GeelyVehicleSignal.LOW_FUEL_WARNING, GeelyVehicleSignal.NIGHT_MODE,
        )
        for (signal in signals) {
            val selection = selection(signal)
            when (val attempt = GeelyStandardPropertyGetter.read(method, manager, selection)) {
                is GeelyStandardPropertyGetResult.InvalidSample -> {
                    invalidated += GeelyVehicleStatusMapping.fieldFor(signal)
                    continue
                }
                is GeelyStandardPropertyGetResult.Value -> {
                    val raw = GeelyStandardPropertyDecoder.property(attempt.raw!!)
                    readings[signal] = GeelyStandardPropertyPolicy.reading(
                        selection, raw, 2_000_000_000L, null,
                    )!!
                }
            }
        }
        assertEquals(listOf(0x21407956, 0x21407957, 0x2140792D, 0x2140779B), manager.calls)
        assertEquals(listOf(CarVehicleStatusField.FUEL_RANGE), invalidated)
        val mapped = GeelyVehicleStatusMapping.map(
            setOf(CarVehicleStatusField.FUEL_RANGE, CarVehicleStatusField.LOW_FUEL_WARNING, CarVehicleStatusField.NIGHT_MODE),
            readings,
        )
        assertNull(mapped.snapshot.fuelRangeKm)
        assertEquals(true, mapped.snapshot.lowFuelWarning)
        assertEquals(true, mapped.snapshot.nightMode)
    }

    @Test fun `nested reflection envelopes unwrap known sample error without arbitrary cause traversal`() {
        val error = IllegalArgumentException("sample invalid")
        val manager = Manager(mapOf(0x21407956 to InvocationTargetException(error)))
        val outcome = GeelyStandardPropertyGetter.read(method, manager, selection(GeelyVehicleSignal.FUEL_RANGE))
        assertSame(error, (outcome as GeelyStandardPropertyGetResult.InvalidSample).error)
    }

    @Test fun `permission denial with argument-error cause remains fatal to connection`() {
        val denial = SecurityException("requires CAR_VENDOR_EXTENSION", IllegalArgumentException("nested detail"))
        val manager = Manager(mapOf(0x21407956 to denial))
        try {
            GeelyStandardPropertyGetter.read(method, manager, selection(GeelyVehicleSignal.FUEL_RANGE))
            fail("Permission denial must propagate to the client's connection failure handler")
        } catch (actual: SecurityException) {
            assertSame(denial, actual)
        }
    }

    @Test fun `not-connected remote-style and unexpected getter failures are not swallowed as bad samples`() {
        val failures = listOf(
            IllegalStateException("CarNotConnected"),
            IOException("remote binder died"),
            UnsupportedOperationException("unexpected public API failure"),
        )
        for (failure in failures) {
            val manager = Manager(mapOf(0x21407956 to failure))
            try {
                GeelyStandardPropertyGetter.read(method, manager, selection(GeelyVehicleSignal.FUEL_RANGE))
                fail("Non-sample failure must propagate")
            } catch (actual: Exception) {
                assertSame(failure, actual)
            }
        }
    }

    @Test fun `getter Error is not converted to a field-level success or invalid integer`() {
        val error = AssertionError("unexpected fatal failure")
        val manager = Manager(mapOf(0x21407956 to error))
        try {
            GeelyStandardPropertyGetter.read(method, manager, selection(GeelyVehicleSignal.FUEL_RANGE))
            fail("Error must propagate")
        } catch (actual: AssertionError) {
            assertSame(error, actual)
        }
    }

    private fun selection(signal: GeelyVehicleSignal): GeelyStandardPropertySelection {
        val hal = GeelyStandardPropertyPolicy.halId(signal, signal.expectedId)!!
        return GeelyStandardPropertyPolicy.select(
            signal, signal.expectedId,
            GeelyStandardPropertyConfig(hal, 1, "java.lang.Integer", 0, true, emptyList(), 1),
        )!!
    }
}
