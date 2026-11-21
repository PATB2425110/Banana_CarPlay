package com.dbtool.carplay.geely.vehicle

import com.dbtool.carplay.api.vehicle.CarVehicleReadProtection

/** Durable session marker precedes even construction/class loading of an OEM client. Worker-only. */
internal class GeelyProtectedVehicleClient(
    private val protection: CarVehicleReadProtection,
    private val factory: () -> GeelyVehiclePropertyClient,
) : GeelyVehiclePropertyClient {
    private var token: Long? = null
    private var delegate: GeelyVehiclePropertyClient? = null
    private var closed = false

    override fun read(signals: Set<GeelyVehicleSignal>): Map<GeelyVehicleSignal, GeelyVehicleReading> {
        if (closed || signals.isEmpty() || !protection.state().enabled) return emptyMap()
        if (token == null) token = protection.beginSession() ?: return emptyMap()
        if (!protection.state().enabled) return emptyMap()
        return try {
            val client = delegate ?: factory().also { delegate = it }
            client.read(signals)
        } catch (error: Throwable) {
            protection.trip("车辆读取异常，已自动关闭：${error.javaClass.simpleName}")
            emptyMap()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        var cleaned = false
        try {
            delegate?.close()
            cleaned = true
        } catch (error: Throwable) {
            protection.trip("车辆读取清理异常，已自动关闭：${error.javaClass.simpleName}")
        } finally {
            delegate = null
            // Failed cleanup may still own OEM work: forbid re-enable until process restart.
            if (cleaned) token?.let(protection::endSession)
            token = null
        }
    }
}
