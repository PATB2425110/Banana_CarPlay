package com.dbtool.carplay.geely.vehicle

import com.dbtool.carplay.api.vehicle.*
import org.junit.Assert.*
import org.junit.Test

class GeelyProtectedVehicleClientTest {
    private class Store : VehicleReadSafetyStore {
        var value = VehicleReadSafetyState()
        var fail = false
        override fun load() = value
        override fun save(state: VehicleReadSafetyState): Boolean {
            if (fail) return false
            value = state
            return true
        }
    }
    private class Client : GeelyVehiclePropertyClient {
        var reads = 0
        var closes = 0
        var failure: Throwable? = null
        override fun read(signals: Set<GeelyVehicleSignal>): Map<GeelyVehicleSignal, GeelyVehicleReading> {
            reads++
            failure?.let { throw it }
            return emptyMap()
        }
        override fun close() { closes++ }
    }
    private val signals = setOf(GeelyVehicleSignal.FUEL_RANGE, GeelyVehicleSignal.FUEL_RANGE_UNIT)

    @Test fun disabledReaderNeverEvenConstructsVendorClient() {
        val policy = VehicleReadSafetyPolicy(Store())
        var constructed = 0
        val reader = GeelyProtectedVehicleClient(policy) { constructed++; Client() }
        reader.read(signals)
        reader.close()
        assertEquals(0, constructed)
    }

    @Test fun durableMarkerPrecedesVendorConstructionAndStaysForWholeActivePeriod() {
        val store = Store()
        val policy = VehicleReadSafetyPolicy(store)
        policy.setEnabled(true)
        val delegate = Client()
        val reader = GeelyProtectedVehicleClient(policy) {
            assertTrue(store.value.interruptedSession)
            delegate
        }
        repeat(10) { reader.read(signals) }
        assertTrue(store.value.interruptedSession)
        assertEquals(10, delegate.reads)
        reader.close()
        reader.close()
        assertFalse(store.value.interruptedSession)
        assertEquals(1, delegate.closes)
    }

    @Test fun factoryOrReadFaultTripsAndFuturePollDoesNotRetryOEM() {
        for (factoryFault in listOf(false, true)) {
            val policy = VehicleReadSafetyPolicy(Store())
            policy.setEnabled(true)
            var constructions = 0
            val delegate = Client().apply { failure = UnsatisfiedLinkError("test only, no native load") }
            val reader = GeelyProtectedVehicleClient(policy) {
                constructions++
                if (factoryFault) throw IllegalStateException("factory fault")
                delegate
            }
            assertTrue(reader.read(signals).isEmpty())
            assertFalse(policy.state().enabled)
            repeat(3) { reader.read(signals) }
            assertEquals(1, constructions)
            assertTrue(policy.state().disabledReason!!.contains("自动关闭"))
            reader.close()
        }
    }

    @Test fun failedMarkerWriteBlocksAllVendorConstruction() {
        val store = Store()
        val policy = VehicleReadSafetyPolicy(store)
        policy.setEnabled(true)
        store.fail = true
        var constructed = 0
        val reader = GeelyProtectedVehicleClient(policy) { constructed++; Client() }
        assertTrue(reader.read(signals).isEmpty())
        assertEquals(0, constructed)
        assertFalse(policy.state().enabled)
    }

    @Test fun ordinaryUnavailableDataDoesNotGetMislabelledAsCrash() {
        val policy = VehicleReadSafetyPolicy(Store())
        policy.setEnabled(true)
        val reader = GeelyProtectedVehicleClient(policy) { Client() }
        assertTrue(reader.read(signals).isEmpty())
        assertTrue(policy.state().enabled)
        reader.close()
    }

    @Test fun failedCleanupDisablesPersistentlyAndBlocksSameProcessReenable() {
        val store = Store()
        val policy = VehicleReadSafetyPolicy(store)
        policy.setEnabled(true)
        val reader = GeelyProtectedVehicleClient(policy) {
            object : GeelyVehiclePropertyClient {
                override fun read(signals: Set<GeelyVehicleSignal>) = emptyMap<GeelyVehicleSignal, GeelyVehicleReading>()
                override fun close() = error("cleanup failed")
            }
        }
        reader.read(signals)
        reader.close()
        assertFalse(policy.state().enabled)
        assertFalse(policy.setEnabled(true))
        assertTrue(store.value.interruptedSession)
        val restarted = VehicleReadSafetyPolicy(store)
        assertFalse(restarted.state().enabled)
        assertTrue(restarted.setEnabled(true))
    }
}
