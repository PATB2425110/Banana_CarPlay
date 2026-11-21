package com.dbtool.carplay.api.vehicle

/** Read-only ICE vehicle inputs. Null is unavailable, never a fabricated zero/default. */
data class CarVehicleStatusSnapshot(
    val fuelRangeKm: Int? = null,
    val outsideTemperatureC: Int? = null,
    val lowFuelWarning: Boolean? = null,
    val nightMode: Boolean? = null,
)

enum class CarVehicleStatusField {
    FUEL_RANGE, OUTSIDE_TEMPERATURE, LOW_FUEL_WARNING, NIGHT_MODE,
}

/** Each open owns an independent lease; retiring Bluetooth must not stop the tunnel's lease. */
interface CarVehicleStatusSource {
    /** Current local opt-in gate. Off must never load/connect/read a vendor client. */
    val enabled: Boolean get() = true
    /** Captured at CarPlay controller creation. An off/on cycle requires a new controller. */
    fun forSession(): CarVehicleStatusSource = this
    /** Non-blocking: allocate a lease/worker only; no vendor IPC or waiting on this caller. */
    fun open(fields: Set<CarVehicleStatusField>): CarVehicleStatusSubscription
}

interface CarVehicleStatusSubscription : AutoCloseable {
    /** Non-blocking memory snapshot. All OEM IPC must run on the implementation's worker. */
    fun latest(): CarVehicleStatusSnapshot
    /** Idempotent, non-blocking: clear exposed state first, then release only this lease's IPC. */
    override fun close()
}
