package com.dbtool.carplay.bridge

import com.dbtool.carplay.api.vehicle.CarVehicleStatusField
import com.dbtool.carplay.api.vehicle.CarVehicleStatusSource
import com.dbtool.carplay.api.vehicle.CarVehicleStatusSubscription
import com.shilapi.xcertplay.transport.Iap2VehicleStatusProvider
import com.shilapi.xcertplay.transport.Iap2VehicleStatusSnapshot
import com.shilapi.xcertplay.transport.Iap2VehicleStatusSubscription

/** Session-owned read-only adapter. Each iAP2 channel gets a separately owned OEM lease. */
class UpstreamVehicleStatusBridge(
    source: CarVehicleStatusSource,
    private val log: (String) -> Unit = {},
) : Iap2VehicleStatusProvider {
    private val source = source.forSession()
    private var closed = false
    private val leases = mutableSetOf<Lease>()

    @Synchronized
    override fun open(fields: Set<Int>): Iap2VehicleStatusSubscription {
        check(!closed) { "Vehicle status session closed" }
        val mapped = fields.mapNotNull {
            when (it) {
                0 -> CarVehicleStatusField.FUEL_RANGE
                1 -> CarVehicleStatusField.OUTSIDE_TEMPERATURE
                2 -> CarVehicleStatusField.LOW_FUEL_WARNING
                3 -> CarVehicleStatusField.NIGHT_MODE
                else -> null
            }
        }.toSet()
        return Lease(source.open(mapped)).also { leases += it }
    }

    @Synchronized
    override fun close() {
        closed = true
        leases.toList().forEach { it.close() }
        leases.clear()
    }

    private inner class Lease(private val delegate: CarVehicleStatusSubscription) : Iap2VehicleStatusSubscription {
        private var released = false

        override fun latest(): Iap2VehicleStatusSnapshot = synchronized(this@UpstreamVehicleStatusBridge) {
            if (closed || released) return Iap2VehicleStatusSnapshot()
            val value = delegate.latest()
            Iap2VehicleStatusSnapshot(
                rangeKm = value.fuelRangeKm,
                outsideTemperatureC = value.outsideTemperatureC,
                rangeWarning = value.lowFuelWarning,
                nightMode = value.nightMode,
            )
        }

        override fun close() = synchronized(this@UpstreamVehicleStatusBridge) {
            if (!released) {
                released = true
                leases.remove(this)
                runCatching { delegate.close() }.onFailure {
                    log("vehicle-status OEM lease cleanup failed: ${it.javaClass.simpleName}")
                }
            }
            Unit
        }
    }
}
