package com.dbtool.carplay.bridge

import com.dbtool.carplay.api.vehicle.*
import com.shilapi.xcertplay.transport.Iap2VehicleStatusSnapshot
import org.junit.Assert.*
import org.junit.Test

class UpstreamVehicleStatusBridgeTest {
    private class Source : CarVehicleStatusSource {
        val fields = mutableListOf<Set<CarVehicleStatusField>>()
        var closes = 0
        override fun open(fields: Set<CarVehicleStatusField>): CarVehicleStatusSubscription {
            this.fields += fields
            return object : CarVehicleStatusSubscription {
                override fun latest() = CarVehicleStatusSnapshot(400, -1, true, false)
                override fun close() { closes++ }
            }
        }
    }

    @Test fun mapsOnlySelectedFieldsAndKeepsFuelSemantics() {
        val source = Source()
        val bridge = UpstreamVehicleStatusBridge(source)
        val lease = bridge.open(setOf(0, 2, 99))
        assertEquals(setOf(CarVehicleStatusField.FUEL_RANGE, CarVehicleStatusField.LOW_FUEL_WARNING), source.fields.single())
        assertEquals(Iap2VehicleStatusSnapshot(400, -1, true, false), lease.latest())
        bridge.close()
    }

    @Test fun oldLeaseCloseCannotStopNewLeaseAndControllerCloseReleasesAll() {
        val source = Source()
        val bridge = UpstreamVehicleStatusBridge(source)
        val old = bridge.open(setOf(0))
        val next = bridge.open(setOf(3))
        old.close()
        old.close()
        assertEquals(1, source.closes)
        assertEquals(Iap2VehicleStatusSnapshot(), old.latest())
        assertEquals(400, next.latest().rangeKm)
        bridge.close()
        bridge.close()
        assertEquals(2, source.closes)
        assertEquals(Iap2VehicleStatusSnapshot(), next.latest())
    }

    @Test(expected = IllegalStateException::class)
    fun closedBridgeRefusesToReopen() {
        val bridge = UpstreamVehicleStatusBridge(Source())
        bridge.close()
        bridge.open(setOf(0))
    }

    @Test fun togglingSafetyOffOnCannotReuseOldControllerForNewSubscriptions() {
        val policy = VehicleReadSafetyPolicy(object : VehicleReadSafetyStore {
            override fun load() = VehicleReadSafetyState()
            override fun save(state: VehicleReadSafetyState) = true
        })
        policy.setEnabled(true)
        val raw = Source()
        val source = GuardedVehicleStatusSource(raw, policy)
        val old = UpstreamVehicleStatusBridge(source)
        val beforeOff = old.open(setOf(0))
        policy.setEnabled(false)
        policy.setEnabled(true)
        assertEquals(Iap2VehicleStatusSnapshot(), beforeOff.latest())
        assertEquals(Iap2VehicleStatusSnapshot(), old.open(setOf(3)).latest())
        assertEquals(1, raw.fields.size)
        val reconnected = UpstreamVehicleStatusBridge(source)
        assertEquals(400, reconnected.open(setOf(0)).latest().rangeKm)
        old.close()
        reconnected.close()
        assertEquals(2, raw.closes)
    }
}
