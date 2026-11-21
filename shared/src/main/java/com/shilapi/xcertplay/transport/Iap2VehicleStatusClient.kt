package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.body.Iap2BodyBuilder
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.iap2.wire.Iap2ParameterList

/** One bounded deferred request, with no source access or output before MFi acceptance. */
internal class PendingVehicleStatusRequest {
    private var pending: Iap2Frame? = null
    fun accept(frame: Iap2Frame): Boolean {
        if (frame.messageId == Iap2VehicleStatusClient.STOP) {
            pending = frame
            return true
        }
        if (frame.messageId != Iap2VehicleStatusClient.START) return false
        if (runCatching {
                Iap2ParameterList.parse(frame.payload).asList().all { it.payload.isEmpty() }
            }.getOrDefault(false)) {
            pending = frame
        }
        return true
    }
    fun take(): Iap2Frame? = pending.also { pending = null }
}

/** Already validated ICE telemetry. Values are nullable; there is no phone/device fallback. */
data class Iap2VehicleStatusSnapshot(
    val rangeKm: Int? = null,
    val outsideTemperatureC: Int? = null,
    val rangeWarning: Boolean? = null,
    val nightMode: Boolean? = null,
)

interface Iap2VehicleStatusSubscription : AutoCloseable {
    /** Memory-only read; vendor IPC and freshness checks belong on the source's worker. */
    fun latest(): Iap2VehicleStatusSnapshot
    override fun close()
}

interface Iap2VehicleStatusProvider : AutoCloseable {
    /** Non-blocking lease allocation; vendor work is asynchronous. */
    fun open(fields: Set<Int>): Iap2VehicleStatusSubscription
    override fun close()
}

/**
 * One control channel owns one subscription. No sender thread or global subscription state:
 * all A101 writes stay on the existing iAP2 control loop, and a retiring RFCOMM channel cannot
 * stop a tunnel subscription. A new channel needs its own iPhone request.
 */
class Iap2VehicleStatusClient(
    private val provider: Iap2VehicleStatusProvider?,
    private val send: (Iap2Frame) -> Unit,
    private val log: (String) -> Unit = {},
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) : AutoCloseable {
    private var subscription: Iap2VehicleStatusSubscription? = null
    private var fields: Set<Int> = emptySet()
    private var lastSent: Map<Int, Int> = emptyMap()
    private var closed = false
    private var readFailureLogged = false
    private var nextOpenAtMillis: Long? = null
    val active: Boolean get() = !closed && provider != null && fields.isNotEmpty()

    /** Returns true for Vehicle Status messages, including rejected/malformed requests. */
    fun accept(frame: Iap2Frame): Boolean {
        if (frame.messageId == STOP) {
            stop()
            log("iap2 rx=0xa102 vehicle-status stopped")
            return true
        }
        if (frame.messageId != START) return false
        if (closed || provider == null) return true
        val requested = try {
            val parameters = Iap2ParameterList.parse(frame.payload).asList()
            require(parameters.all { it.payload.isEmpty() }) { "A100 selectors must be void" }
            parameters.map { it.id }.filter { it in 0..3 }.toSet()
        } catch (error: Exception) {
            log("iap2 rx=0xa100 vehicle-status rejected: ${error.message}")
            return true
        }
        if (requested != fields) {
            stop()
            fields = requested
        }
        openIfDue()
        lastSent = emptyMap()
        log("iap2 rx=0xa100 vehicle-status fields=${requested.sorted()}")
        // Always answer the first request immediately. Unknown fields are omitted, never zero.
        publish(initial = true)
        return true
    }

    fun poll() {
        if (active) {
            openIfDue()
            publish(initial = false)
        }
    }

    private fun openIfDue() {
        if (!active || subscription != null) return
        val now = nowMillis()
        if (nextOpenAtMillis?.let { now - it < 0 } == true) return
        nextOpenAtMillis = now + OPEN_RETRY_MILLIS
        subscription = try {
            provider?.open(fields)
        } catch (error: Exception) {
            log("iap2 vehicle-status source unavailable; retry throttled: ${error.message}")
            null
        }
    }

    private fun publish(initial: Boolean) {
        val snapshot = try {
            subscription?.latest() ?: Iap2VehicleStatusSnapshot()
        } catch (error: Exception) {
            if (!readFailureLogged) log("iap2 vehicle-status read failed: ${error.message}")
            readFailureLogged = true
            Iap2VehicleStatusSnapshot()
        }
        val current = buildMap {
            snapshot.rangeKm?.takeIf { it in 0..0xffff }?.let { put(0, it) }
            snapshot.outsideTemperatureC?.takeIf { it in Short.MIN_VALUE..Short.MAX_VALUE }?.let { put(1, it) }
            snapshot.rangeWarning?.let { put(2, if (it) 1 else 0) }
            snapshot.nightMode?.let { put(3, if (it) 1 else 0) }
        }.filterKeys { it in fields }
        val changed = current.filter { (id, value) -> initial || lastSent[id] != value }
        if (initial || changed.isNotEmpty()) {
            val body = Iap2BodyBuilder.opaque(UPDATE)
            for ((id, value) in changed.toSortedMap()) {
                when (id) {
                    0 -> body.u16(id, value)
                    1 -> body.i16(id, value)
                    else -> body.u8(id, value)
                }
            }
            send(body.build())
            log("iap2 tx=0xa101 vehicle-status fields=${changed.keys.sorted()} initial=$initial")
        }
        // If a field becomes unavailable, forget it so an identical recovered value is sent again.
        lastSent = current
    }

    private fun stop() {
        runCatching { subscription?.close() }
            .onFailure { log("iap2 vehicle-status lease cleanup failed: ${it.javaClass.simpleName}") }
        subscription = null
        fields = emptySet()
        lastSent = emptyMap()
        readFailureLogged = false
        nextOpenAtMillis = null
    }

    override fun close() {
        closed = true
        stop()
    }

    companion object {
        const val START = 0xa100
        const val UPDATE = 0xa101
        const val STOP = 0xa102
        const val POLL_INTERVAL_MILLIS = 1_000L
        private const val OPEN_RETRY_MILLIS = 30_000L
    }
}
