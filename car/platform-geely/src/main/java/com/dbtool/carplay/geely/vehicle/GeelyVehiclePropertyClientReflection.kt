package com.dbtool.carplay.geely.vehicle

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicReference

/**
 * Public APIs only:
 * Car.create(Context) -> Authority/native authorization (never skipped)
 * normal explicit service binding -> official IECarXCar.Stub.asInterface
 * getCarService("car_signal") -> official IECarXCarProperty.Stub.asInterface -> getProperty(id, 1).
 *
 * CarSignalManager has no public full-property getter in this ROM. The official public property
 * interface does. Do not replace this with a bare int getter, private mMgr, ServiceManager lookup,
 * hand-written Binder transactions, signature bypass or a setter.
 *
 * Evidence: separately decoded ECarXCarService manifest; IECarXCar / IECarXCarProperty public
 * interfaces; ECarXCarPropertyManagerBase.getProperty/getIntProperty (OSN2.0, 2026-11-20).
 */
internal class GeelyVehiclePropertyClientReflection(
    private val context: Context,
    private val callbacks: GeelyVehicleClientCallbacks,
    private val nowMillis: () -> Long,
    private val log: (String) -> Unit,
) : GeelyVehiclePropertyClient {
    private data class Binding(val generation: Long = 0, val binder: IBinder? = null)
    private val binding = AtomicReference(Binding())
    @Volatile private var closed = false
    private var bound = false
    private var bindStartedAt = 0L
    private var nextAttemptAt = 0L
    private var endpointBinder: IBinder? = null
    private var endpoint: Any? = null
    private var propertyMethod: Method? = null
    private var propertyClass: Class<*>? = null
    private var sdk: Sdk? = null
    private var connection: ServiceConnection? = null
    private val runtimeIds = mutableMapOf<GeelyVehicleSignal, Int?>()
    private val diagnosticAt = mutableMapOf<String, Long>()

    // Callbacks only change memory; all reflection, parsing and IPC stay on the lease's worker.
    private fun newConnection(generation: Long) = object : ServiceConnection {
        private fun publish(service: IBinder?) {
            while (true) {
                val before = binding.get()
                if (isClosed() || before.generation != generation) return
                if (binding.compareAndSet(before, before.copy(binder = service))) {
                    callbacks.invalidate()
                    return
                }
            }
        }

        override fun onServiceConnected(name: ComponentName?, service: IBinder?) = publish(service)
        override fun onServiceDisconnected(name: ComponentName?) = publish(null)
        override fun onBindingDied(name: ComponentName?) = onServiceDisconnected(name)
        override fun onNullBinding(name: ComponentName?) = onServiceDisconnected(name)
    }

    private data class Sdk(
        val signalClass: Class<*>,
        val carStub: Class<*>,
        val carInterface: Class<*>,
        val propertyStub: Class<*>,
        val propertyInterface: Class<*>,
        val propertyValue: Class<*>,
    )

    override fun read(signals: Set<GeelyVehicleSignal>): Map<GeelyVehicleSignal, GeelyVehicleReading> {
        if (isClosed() || signals.isEmpty()) return emptyMap()
        if (!ensureEndpoint()) return emptyMap()
        val api = sdk ?: return emptyMap()
        val reader = endpoint ?: return emptyMap()
        val getProperty = propertyMethod ?: return emptyMap()
        val type = propertyClass ?: return emptyMap()
        val result = mutableMapOf<GeelyVehicleSignal, GeelyVehicleReading>()
        for (signal in signals) {
            if (isClosed()) break
            val field = GeelyVehicleStatusMapping.fieldFor(signal)
            val id = runtimeId(api.signalClass, signal)
            if (id == null) {
                callbacks.invalidateField(field)
                continue
            }
            try {
                val raw = getProperty.invoke(reader, id, 1)
                val property = if (raw != null && type.isInstance(raw)) {
                    GeelyVehiclePropertyDecoder.decode(raw, nowMillis())
                } else null
                result[signal] = GeelyVehicleReading(id, property)
                if (property?.integerFor(id) == null) {
                    callbacks.invalidateField(field)
                    diagnose("sample-${signal.name}", "${signal.name}: missing/invalid full property; unknown")
                } else if (!property.hasStatus &&
                    signal != GeelyVehicleSignal.OUTSIDE_TEMPERATURE &&
                    signal != GeelyVehicleSignal.OUTSIDE_TEMPERATURE_QUALITY
                ) {
                    callbacks.invalidateField(field)
                    diagnose(
                        "availability-${signal.name}",
                        "${signal.name}: wrapper omits availability and no verified quality exists; unknown",
                    )
                }
                val dependencies = GeelyVehicleStatusMapping.signalsFor(setOf(field))
                if (dependencies.all { it in result } &&
                    field !in GeelyVehicleStatusMapping.map(setOf(field), result).receiptTimeMillis
                ) {
                    // Clear this field now, even if a later, unrelated OEM call blocks.
                    callbacks.invalidateField(field)
                    diagnose("mapping-${field.name}", "${field.name}: invalid value/unit/quality or unavailable metadata; unknown")
                }
            } catch (error: Throwable) {
                GeelyVehicleReadFailures.rethrowFatal(error)
                // A read failure must not leave any stale last-good values. Do not hammer a dead
                // binder or denied endpoint once per field; invalidate and back off the connection.
                diagnose("read-failed", "property read unavailable: ${rootCause(error).javaClass.simpleName}")
                callbacks.invalidate()
                releaseBinding()
                nextAttemptAt = nowMillis() + RETRY_MILLIS
                return emptyMap()
            }
        }
        return result
    }

    private fun runtimeId(type: Class<*>, signal: GeelyVehicleSignal): Int? {
        if (runtimeIds.containsKey(signal)) return runtimeIds[signal]
        val id = GeelyVehicleReadFailures.orUnknown {
            val field = type.getField(signal.constantName)
            if (!Modifier.isStatic(field.modifiers) || field.type != Int::class.javaPrimitiveType) null
            else signal.resolveRuntimeId(field.get(null))
        }
        runtimeIds[signal] = id
        if (id == null) {
            diagnose("id-${signal.name}", "${signal.name}: public runtime signal ID missing/conflicting; unknown")
        } else if (id != signal.expectedId) {
            diagnose(
                "id-${signal.name}",
                "${signal.name}: runtime ID=$id differs from OSN2.0 ${signal.expectedId}; using runtime public constant",
            )
        }
        return id
    }

    private fun ensureEndpoint(): Boolean {
        val now = nowMillis()
        if (!bound) {
            if (now < nextAttemptAt) return false
            nextAttemptAt = now + RETRY_MILLIS
            try {
                val loader = context.classLoader
                // This official call executes Authority.verifyAuthority and native verifyPackage.
                // A denial/linkage failure stops this attempt before our service binding/reading.
                val officialCar = Class.forName("com.ecarx.xui.adaptapi.car.Car", true, loader)
                    .getMethod("create", Context::class.java).invoke(null, context)
                checkNotNull(officialCar) { "official Car.create returned null" }
                sdk = Sdk(
                    Class.forName("ecarx.car.hardware.signal.CarSignalManager", false, loader),
                    Class.forName("ecarx.car.IECarXCar\$Stub", false, loader),
                    Class.forName("ecarx.car.IECarXCar", false, loader),
                    Class.forName("ecarx.car.hardware.property.IECarXCarProperty\$Stub", false, loader),
                    Class.forName("ecarx.car.hardware.property.IECarXCarProperty", false, loader),
                    Class.forName("ecarx.car.hardware.ECarXCarPropertyValue", false, loader),
                )
                if (isClosed()) return false
                val attemptConnection = newConnection(advanceBinding())
                connection = attemptConnection
                bound = context.bindService(
                    Intent("ecarx.car.IECarXCar").setComponent(
                        ComponentName("com.ecarx.car", "com.ecarx.car.ECarXCarService"),
                    ),
                    attemptConnection,
                    Context.BIND_AUTO_CREATE,
                )
                bindStartedAt = nowMillis()
                if (!bound || isClosed()) {
                    if (!bound) diagnose("bind", "official car service binding unavailable; retry throttled")
                    releaseBinding()
                    return false
                }
            } catch (error: Throwable) {
                GeelyVehicleReadFailures.rethrowFatal(error)
                diagnose("connect", "official car authorization/connection unavailable: ${rootCause(error).javaClass.simpleName}")
                releaseBinding()
                return false
            }
        }
        val currentBinder = binding.get().binder
        if (currentBinder == null) {
            if (bound && now - bindStartedAt >= CONNECT_TIMEOUT_MILLIS) {
                diagnose("bind-timeout", "official car service connection timed out; unknown")
                releaseBinding()
                nextAttemptAt = now + RETRY_MILLIS
            }
            return false
        }
        if (!currentBinder.isBinderAlive) {
            callbacks.invalidate()
            releaseBinding()
            nextAttemptAt = now + RETRY_MILLIS
            return false
        }
        if (endpointBinder === currentBinder && endpoint != null) return true
        return try {
            val api = checkNotNull(sdk)
            val carService = checkNotNull(
                api.carStub.getMethod("asInterface", IBinder::class.java).invoke(null, currentBinder),
            ) { "official car interface absent" }
            val propertyBinder = checkNotNull(
                api.carInterface.getMethod("getCarService", String::class.java)
                    .invoke(carService, "car_signal") as? IBinder,
            ) { "official property service absent" }
            endpoint = checkNotNull(
                api.propertyStub.getMethod("asInterface", IBinder::class.java).invoke(null, propertyBinder),
            ) { "official property interface absent" }
            // Method on PUBLIC interface, not a possibly-private generated Proxy class.
            propertyMethod = api.propertyInterface.getMethod(
                "getProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            )
            propertyClass = api.propertyValue
            endpointBinder = currentBinder
            true
        } catch (error: Throwable) {
            GeelyVehicleReadFailures.rethrowFatal(error)
            diagnose("endpoint", "public property API unavailable: ${rootCause(error).javaClass.simpleName}")
            callbacks.invalidate()
            releaseBinding()
            nextAttemptAt = now + RETRY_MILLIS
            false
        }
    }

    private fun isClosed(): Boolean = closed || callbacks.isClosed()

    private fun advanceBinding(): Long {
        while (true) {
            val before = binding.get()
            val next = Binding(generation = before.generation + 1)
            if (binding.compareAndSet(before, next)) return next.generation
        }
    }

    private fun releaseBinding() {
        advanceBinding() // Atomically discard delayed callbacks from a retired binding.
        endpoint = null
        endpointBinder = null
        propertyMethod = null
        propertyClass = null
        val retiredConnection = connection
        connection = null
        if (bound) {
            bound = false
            if (retiredConnection != null) {
                runCatching { context.unbindService(retiredConnection) }
                    .onFailure(GeelyVehicleReadFailures::rethrowFatal) // this lease's binding only
            }
        }
    }

    private fun diagnose(key: String, message: String) {
        val now = nowMillis()
        val previous = diagnosticAt[key]
        if (previous != null && now - previous < LOG_INTERVAL_MILLIS) return
        diagnosticAt[key] = now
        runCatching { log("Geely vehicle-status: $message") }
    }

    override fun close() {
        if (closed) return
        closed = true
        releaseBinding()
        sdk = null
        runtimeIds.clear()
        // Never call disconnect/cleanup on the SDK's process-global Car or its proxy.
    }

    private fun rootCause(error: Throwable): Throwable {
        var current = error
        repeat(8) {
            current = current.cause?.takeIf { it !== current } ?: return current
        }
        return current
    }

    private companion object {
        const val RETRY_MILLIS = 30_000L
        const val CONNECT_TIMEOUT_MILLIS = 5_000L
        const val LOG_INTERVAL_MILLIS = 60_000L
    }
}

/** Missing identity/value metadata is unknown; only public methods are inspected. */
internal object GeelyVehiclePropertyDecoder {
    fun decode(raw: Any, receiptTimeMillis: Long): GeelyVehicleProperty? = GeelyVehicleReadFailures.orUnknown {
        val type = raw.javaClass
        val id = type.getMethod("getPropertyId").invoke(raw) as? Int ?: return null
        val area = type.getMethod("getAreaId").invoke(raw) as? Int ?: return null
        val value = type.getMethod("getValue").invoke(raw) as? Int ?: return null
        val statusMethod = try {
            type.getMethod("getStatus")
        } catch (_: NoSuchMethodException) {
            null
        }
        val status = statusMethod?.invoke(raw) as? Int
        GeelyVehicleProperty(id, area, value, receiptTimeMillis, statusMethod != null, status)
    }
}
