package com.dbtool.carplay.geely.vehicle

import com.dbtool.carplay.api.vehicle.CarVehicleStatusField
import com.dbtool.carplay.api.vehicle.CarVehicleStatusSnapshot
import com.dbtool.carplay.api.vehicle.CarVehicleStatusSubscription
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal interface GeelyVehiclePropertyClient : AutoCloseable {
    fun read(signals: Set<GeelyVehicleSignal>): Map<GeelyVehicleSignal, GeelyVehicleReading>
    override fun close()
}

internal data class GeelyVehicleClientCallbacks(
    val invalidate: () -> Unit,
    val isClosed: () -> Boolean,
    val invalidateField: (CarVehicleStatusField) -> Unit,
)

internal interface GeelyVehicleWorker {
    fun schedule(delayMillis: Long, task: () -> Unit)
    /** Enqueue cleanup after any in-flight read; never wait for OEM IPC on the caller. */
    fun close(cleanup: () -> Unit)
}

internal class GeelyVehicleExecutor : GeelyVehicleWorker {
    private val executor = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "geely-vehicle-status").apply { isDaemon = true }
    }.apply {
        setContinueExistingPeriodicTasksAfterShutdownPolicy(false)
        removeOnCancelPolicy = true
    }
    private var pending: ScheduledFuture<*>? = null

    @Synchronized
    override fun schedule(delayMillis: Long, task: () -> Unit) {
        pending = executor.schedule(Runnable { task() }, delayMillis, TimeUnit.MILLISECONDS)
    }

    @Synchronized
    override fun close(cleanup: () -> Unit) {
        pending?.cancel(false)
        executor.execute { cleanup() }
        executor.shutdown()
    }
}

/** Android-free lifecycle/cache policy so races, TTL and field selection can be tested on JVM. */
internal class GeelyVehicleStatusLease(
    fields: Set<CarVehicleStatusField>,
    private val clientFactory: (GeelyVehicleClientCallbacks) -> GeelyVehiclePropertyClient,
    private val worker: GeelyVehicleWorker,
    private val nowMillis: () -> Long,
    private val log: (String) -> Unit,
) : CarVehicleStatusSubscription {
    private data class State(
        val generation: Long = 0,
        val closed: Boolean = false,
        val values: GeelyMappedVehicleStatus = GeelyMappedVehicleStatus(),
    )

    private val fields = fields.toSet()
    private val signals = GeelyVehicleStatusMapping.signalsFor(this.fields)
    private val state = AtomicReference(State())
    // These are only touched by this lease's worker (including cleanup).
    private var client: GeelyVehiclePropertyClient? = null
    private var lastFailureLogAt: Long? = null
    private val schedulingLock = Any()

    init {
        if (signals.isNotEmpty()) worker.schedule(0, ::poll)
    }

    override fun latest(): CarVehicleStatusSnapshot {
        val cached = state.get()
        if (cached.closed) return CarVehicleStatusSnapshot()
        val now = nowMillis()
        fun fresh(field: CarVehicleStatusField): Boolean {
            val received = cached.values.receiptTimeMillis[field] ?: return false
            val age = now - received
            val sampleExpiry = cached.values.continuousSampleExpiryMillis[field]
            return field in fields && age in 0..SAMPLE_TTL_MILLIS &&
                (sampleExpiry == null || now <= sampleExpiry)
        }
        val value = cached.values.snapshot
        return CarVehicleStatusSnapshot(
            value.fuelRangeKm.takeIf { fresh(CarVehicleStatusField.FUEL_RANGE) },
            value.outsideTemperatureC.takeIf { fresh(CarVehicleStatusField.OUTSIDE_TEMPERATURE) },
            value.lowFuelWarning.takeIf { fresh(CarVehicleStatusField.LOW_FUEL_WARNING) },
            value.nightMode.takeIf { fresh(CarVehicleStatusField.NIGHT_MODE) },
        )
    }

    private fun invalidate() {
        while (true) {
            val before = state.get()
            if (before.closed) return
            if (state.compareAndSet(before, State(generation = before.generation + 1))) return
        }
    }

    private fun invalidateField(field: CarVehicleStatusField) {
        while (true) {
            val before = state.get()
            if (before.closed) return
            val old = before.values.snapshot
            val cleared = when (field) {
                CarVehicleStatusField.FUEL_RANGE -> old.copy(fuelRangeKm = null)
                CarVehicleStatusField.OUTSIDE_TEMPERATURE -> old.copy(outsideTemperatureC = null)
                CarVehicleStatusField.LOW_FUEL_WARNING -> old.copy(lowFuelWarning = null)
                CarVehicleStatusField.NIGHT_MODE -> old.copy(nightMode = null)
            }
            val values = GeelyMappedVehicleStatus(
                cleared, before.values.receiptTimeMillis - field,
                before.values.continuousSampleExpiryMillis - field,
            )
            if (state.compareAndSet(before, before.copy(values = values))) return
        }
    }

    private fun poll() {
        val captured = state.get()
        if (captured.closed) return
        val values = try {
            val reader = client ?: clientFactory(
                GeelyVehicleClientCallbacks(::invalidate, { state.get().closed }, ::invalidateField),
            ).also { client = it }
            GeelyVehicleStatusMapping.map(fields, reader.read(signals))
        } catch (error: Throwable) {
            val now = nowMillis()
            if (lastFailureLogAt == null || now - lastFailureLogAt!! >= FAILURE_LOG_INTERVAL_MILLIS) {
                lastFailureLogAt = now
                runCatching { log("vehicle-status worker unavailable: ${error.javaClass.simpleName}") }
            }
            GeelyMappedVehicleStatus()
        }
        while (true) {
            val current = state.get()
            if (current.closed || current.generation != captured.generation) break
            if (state.compareAndSet(current, current.copy(values = values))) break
        }
        synchronized(schedulingLock) {
            if (!state.get().closed) worker.schedule(POLL_INTERVAL_MILLIS, ::poll)
        }
    }

    override fun close() {
        synchronized(schedulingLock) {
            while (true) {
                val before = state.get()
                if (before.closed) return
                if (state.compareAndSet(
                        before,
                        State(generation = before.generation + 1, closed = true),
                    )
                ) break
            }
            // Clear immediately above; a blocked or late OEM read can never repopulate this lease.
            worker.close {
                runCatching { client?.close() }
                client = null
            }
        }
    }

    internal companion object {
        const val POLL_INTERVAL_MILLIS = 1_000L
        const val SAMPLE_TTL_MILLIS = 3_000L
        private const val FAILURE_LOG_INTERVAL_MILLIS = 60_000L
    }
}
