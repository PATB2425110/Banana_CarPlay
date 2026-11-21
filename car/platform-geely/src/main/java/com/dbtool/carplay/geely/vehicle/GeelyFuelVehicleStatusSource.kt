package com.dbtool.carplay.geely.vehicle

import android.content.Context
import android.os.SystemClock
import com.dbtool.carplay.api.vehicle.CarVehicleStatusField
import com.dbtool.carplay.api.vehicle.CarVehicleStatusSource
import com.dbtool.carplay.api.vehicle.CarVehicleStatusSubscription
import com.dbtool.carplay.api.vehicle.CarVehicleReadSafety
import com.dbtool.carplay.api.vehicle.GuardedVehicleStatusSource

/**
 * Geely ICE-only, read-only vehicle inputs. Constructing this source performs no OEM lookup/IPC.
 * Each open owns its worker, normal service binding and cache, not the SDK's process-global Car.
 */
class GeelyFuelVehicleStatusSource(
    context: Context,
    private val log: (String) -> Unit = {},
) : CarVehicleStatusSource {
    private val context = context.applicationContext ?: context

    private val protection = CarVehicleReadSafety.forProcess(this.context)
    private val guarded = GuardedVehicleStatusSource(object : CarVehicleStatusSource {
        override fun open(fields: Set<CarVehicleStatusField>): CarVehicleStatusSubscription =
            GeelyVehicleStatusLease(
                fields = fields,
                clientFactory = { callbacks ->
                    GeelyProtectedVehicleClient(protection) {
                        GeelyVehiclePropertyClients(
                            standard = GeelyStandardVehiclePropertyClient(
                                context, callbacks, SystemClock::elapsedRealtime,
                                SystemClock::elapsedRealtimeNanos, log,
                            ),
                            legacy = GeelyVehiclePropertyClientReflection(
                                context, callbacks, SystemClock::elapsedRealtime, log,
                            ),
                        )
                    }
                },
                worker = GeelyVehicleExecutor(),
                nowMillis = SystemClock::elapsedRealtime,
                log = log,
            )
    }, protection)

    override val enabled: Boolean get() = guarded.enabled
    override fun forSession(): CarVehicleStatusSource = guarded.forSession()
    override fun open(fields: Set<CarVehicleStatusField>): CarVehicleStatusSubscription = guarded.open(fields)
}
