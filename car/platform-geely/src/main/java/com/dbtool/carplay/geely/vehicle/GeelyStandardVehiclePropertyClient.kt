package com.dbtool.carplay.geely.vehicle

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicReference

/**
 * Optional, existing-permission-only Android CarPropertyManager route.
 *
 * Verified android.car.jar APIs: Car.createCar(Context, ServiceConnection, Handler), connect(),
 * getCarManager("property"), disconnect(); CarPropertyManager.getPropertyList() and
 * getProperty(Class, int, int). No vendor-extension callback (it registers EVERY property).
 *
 * CarService PropertyHalService preserves HAL status/timestamp and passes vendor properties only
 * from the actual HAL config list. CarPropertyService filters it and checks CAR_VENDOR_EXTENSION
 * on read; that permission is system|signature and shared by vendor reads AND writes in this ROM,
 * not a dedicated read-only grant. A manifest declaration is not a grant. This feature only reads;
 * it makes no runtime permission request, grants nothing, and never changes the caller's identity.
 */
internal class GeelyStandardVehiclePropertyClient(
    private val context: Context,
    private val callbacks: GeelyVehicleClientCallbacks,
    private val nowMillis: () -> Long,
    private val nowNanos: () -> Long,
    private val log: (String) -> Unit,
) : GeelyVehiclePropertyClient {
    private enum class Connection { WAITING, CONNECTED, DISCONNECTED }
    private data class ConnectionState(val generation: Long = 0, val state: Connection = Connection.WAITING)
    private data class OwnedCar(
        val instance: Any,
        val type: Class<*>,
        val handlerThread: HandlerThread,
        val bindingContext: GeelyStandardCarContext,
    )

    private val connection = AtomicReference(ConnectionState())
    private val permission = GeelyStandardPermissionGate(
        granted = {
            context.checkSelfPermission(VENDOR_PERMISSION) == PackageManager.PERMISSION_GRANTED
        },
        onDenied = {
            runCatching {
                log("Geely vehicle-status: missing legally granted $VENDOR_PERMISSION; " +
                    "standard Car will not be created/bound; only legacy temperature-quality fallback")
            }
        },
    )
    @Volatile private var closed = false
    private var car: OwnedCar? = null
    private var manager: Any? = null
    private var managerType: Class<*>? = null
    private var valueType: Class<*>? = null
    private var configType: Class<*>? = null
    private var signalType: Class<*>? = null
    private var getProperty: Method? = null
    private var connectStartedAt = 0L
    private var nextAttemptAt = 0L
    private val runtimeIds = mutableMapOf<GeelyVehicleSignal, Int?>()
    private val previousTimestamps = mutableMapOf<Pair<Int, Int>, Long>()
    private val diagnosticAt = mutableMapOf<String, Long>()

    override fun read(signals: Set<GeelyVehicleSignal>): Map<GeelyVehicleSignal, GeelyVehicleReading> {
        if (signals.isEmpty() || isClosed()) return emptyMap()
        // Gate BEFORE class loading, factory invocation or connection. Re-check for revocation.
        return permission.withPermission {
            try {
                if (!ensureManager()) return@withPermission emptyMap()
                readAvailable(signals)
            } catch (error: Throwable) {
                GeelyVehicleReadFailures.rethrowFatal(error)
                diagnose("connection", "standard public property API unavailable: ${rootCause(error).javaClass.simpleName}; " +
                    "three status fields unknown, legacy temperature-quality fallback only")
                failConnection()
                emptyMap()
            }
        } ?: run {
            if (car != null) {
                callbacks.invalidate()
                retireCar()
            }
            emptyMap()
        }
    }

    private fun readAvailable(
        signals: Set<GeelyVehicleSignal>,
    ): Map<GeelyVehicleSignal, GeelyVehicleReading> {
        val reader = checkNotNull(manager)
        val type = checkNotNull(managerType)
        val configurations = type.getMethod("getPropertyList").invoke(reader) as? List<*>
            ?: error("public property config list absent")
        val expectedConfigType = checkNotNull(configType)
        val expectedValueType = checkNotNull(valueType)
        val ids = signals.associateWith(::runtimeId)
        val wantedHalIds = signals.mapNotNull { GeelyStandardPropertyPolicy.halId(it, ids[it]) }.toSet()
        // Inspect the real permission-filtered config list; parse only requested known properties.
        val configs = mutableMapOf<Int, MutableList<GeelyStandardPropertyConfig?>>()
        for (raw in configurations) {
            if (raw == null || !expectedConfigType.isInstance(raw)) continue
            val id = expectedConfigType.getMethod("getPropertyId").invoke(raw) as? Int ?: continue
            if (id in wantedHalIds) {
                configs.getOrPut(id) { mutableListOf() }.add(GeelyStandardPropertyDecoder.config(raw))
            }
        }
        val result = mutableMapOf<GeelyVehicleSignal, GeelyVehicleReading>()
        for (signal in signals) {
            if (isClosed()) break
            val runtimeId = ids[signal]
            val halId = GeelyStandardPropertyPolicy.halId(signal, runtimeId)
            val config = halId?.let { configs[it]?.singleOrNull() }
            val selected = GeelyStandardPropertyPolicy.select(signal, runtimeId, config)
            if (selected == null) {
                callbacks.invalidateField(GeelyVehicleStatusMapping.fieldFor(signal))
                diagnose(
                    "config-${signal.name}",
                    "${signal.name}: no verified HAL mapping or readable integer/area/changeMode config; unknown",
                )
                continue
            }
            // Actual CarPropertyValue header remains HAL-ID/real area; no Manager-ID forgery.
            val rawObject = when (
                val attempt = GeelyStandardPropertyGetter.read(checkNotNull(getProperty), reader, selected)
            ) {
                is GeelyStandardPropertyGetResult.Value -> attempt.raw
                is GeelyStandardPropertyGetResult.InvalidSample -> {
                    callbacks.invalidateField(GeelyVehicleStatusMapping.fieldFor(signal))
                    diagnose(
                        "getter-${signal.name}",
                        "${signal.name}: public getter rejected sample/type/parameter; this field unknown, continuing others",
                    )
                    continue
                }
            }
            val raw = rawObject?.takeIf { expectedValueType.isInstance(it) }
                ?.let(GeelyStandardPropertyDecoder::property)
            val key = selected.halPropertyId to selected.areaId
            val validated = GeelyStandardPropertyPolicy.reading(
                selected, raw, nowNanos(), previousTimestamps[key],
            )
            if (validated == null) {
                callbacks.invalidateField(GeelyVehicleStatusMapping.fieldFor(signal))
                diagnose(
                    "value-${signal.name}",
                    "${signal.name}: full property unavailable, invalid header/value or timestamp; unknown",
                )
            } else {
                previousTimestamps[key] = checkNotNull(raw).timestampNanos
                result[signal] = validated
                val field = GeelyVehicleStatusMapping.fieldFor(signal)
                val dependencies = GeelyVehicleStatusMapping.signalsFor(setOf(field))
                if (dependencies.all { it in result } &&
                    field !in GeelyVehicleStatusMapping.map(setOf(field), result).receiptTimeMillis
                ) {
                    callbacks.invalidateField(field)
                    diagnose("mapping-${field.name}", "${field.name}: invalid raw value/unit/quality; unknown")
                }
            }
        }
        return result
    }

    private fun runtimeId(signal: GeelyVehicleSignal): Int? {
        if (runtimeIds.containsKey(signal)) return runtimeIds[signal]
        val id = GeelyVehicleReadFailures.orUnknown {
            val field = checkNotNull(signalType).getField(signal.constantName)
            if (Modifier.isStatic(field.modifiers) && field.type == Int::class.javaPrimitiveType) {
                signal.resolveRuntimeId(field.get(null))
            } else null
        }
        runtimeIds[signal] = id
        if (id != signal.expectedId) {
            diagnose(
                "id-${signal.name}",
                "${signal.name}: runtime Manager ID=$id has no verified standard HAL mapping; unknown",
            )
        }
        return id
    }

    private fun ensureManager(): Boolean {
        val now = nowMillis()
        if (car == null) {
            if (now < nextAttemptAt) return false
            nextAttemptAt = now + RETRY_MILLIS
            val loader = context.classLoader
            // Keep the official eCarx/native Authority barrier even for the Android route.
            checkNotNull(
                Class.forName("com.ecarx.xui.adaptapi.car.Car", true, loader)
                    .getMethod("create", Context::class.java).invoke(null, context),
            ) { "official eCarx Car.create denied/absent" }
            if (isClosed()) return false
            val carType = Class.forName("android.car.Car", true, loader)
            val bindingContext = GeelyStandardCarContext(context) { error ->
                diagnose("cleanup", "owned standard Car unbind failed: ${error.javaClass.simpleName}")
            }
            val thread = HandlerThread("geely-standard-car-sdk").apply { start() }
            val generation = advanceConnection()
            try {
                val created = checkNotNull(
                    carType.getMethod(
                        "createCar", Context::class.java, ServiceConnection::class.java, Handler::class.java,
                    ).invoke(null, bindingContext, callback(generation), Handler(thread.looper)),
                ) { "official Android Car factory unavailable" }
                car = OwnedCar(created, carType, thread, bindingContext)
                if (isClosed()) {
                    retireCar()
                    return false
                }
                connectStartedAt = nowMillis()
                carType.getMethod("connect").invoke(created)
            } catch (error: Throwable) {
                if (car == null) {
                    try {
                        bindingContext.close()
                    } finally {
                        thread.quitSafely()
                    }
                }
                throw error
            }
        }
        val state = connection.get()
        if (state.state == Connection.DISCONNECTED) {
            failConnection()
            return false
        }
        val owned = checkNotNull(car)
        if (state.state != Connection.CONNECTED ||
            owned.type.getMethod("isConnected").invoke(owned.instance) != true
        ) {
            if (nowMillis() - connectStartedAt >= CONNECT_TIMEOUT_MILLIS) {
                diagnose("timeout", "standard Car connection timed out; unknown")
                failConnection()
            }
            return false
        }
        if (manager != null) return true
        val loader = context.classLoader
        managerType = Class.forName("android.car.hardware.property.CarPropertyManager", false, loader)
        valueType = Class.forName("android.car.hardware.CarPropertyValue", false, loader)
        configType = Class.forName("android.car.hardware.CarPropertyConfig", false, loader)
        signalType = Class.forName("ecarx.car.hardware.signal.CarSignalManager", false, loader)
        manager = checkNotNull(
            owned.type.getMethod("getCarManager", String::class.java).invoke(owned.instance, "property"),
        ) { "official standard property manager absent" }
        check(checkNotNull(managerType).isInstance(manager)) { "unexpected public property manager" }
        getProperty = checkNotNull(managerType).getMethod(
            "getProperty", Class::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        )
        return true
    }

    private fun callback(generation: Long) = object : ServiceConnection {
        private fun publish(value: Connection) {
            while (true) {
                val before = connection.get()
                if (isClosed() || before.generation != generation) return
                if (connection.compareAndSet(before, before.copy(state = value))) {
                    callbacks.invalidate()
                    return
                }
            }
        }
        // No app IPC/reflection/parsing in Android's ServiceConnection callback.
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) =
            publish(if (service != null) Connection.CONNECTED else Connection.DISCONNECTED)
        override fun onServiceDisconnected(name: ComponentName?) = publish(Connection.DISCONNECTED)
        override fun onBindingDied(name: ComponentName?) = publish(Connection.DISCONNECTED)
        override fun onNullBinding(name: ComponentName?) = publish(Connection.DISCONNECTED)
    }

    private fun advanceConnection(): Long {
        while (true) {
            val before = connection.get()
            val next = ConnectionState(generation = before.generation + 1)
            if (connection.compareAndSet(before, next)) return next.generation
        }
    }

    private fun failConnection() {
        callbacks.invalidate()
        retireCar()
        nextAttemptAt = nowMillis() + RETRY_MILLIS
    }

    private fun retireCar() {
        advanceConnection()
        val retired = car
        car = null
        manager = null
        managerType = null
        valueType = null
        configType = null
        signalType = null
        getProperty = null
        runtimeIds.clear()
        previousTimestamps.clear()
        if (retired != null) {
            // This is the independent Android Car factory instance, NEVER global eCarx Car.
            try {
                retired.type.getMethod("disconnect").invoke(retired.instance)
            } finally {
                try {
                    // Also handles this ROM's state=0/no-op disconnect leak. SDK unbind already
                    // removed normal-case entries; this only releases remaining OWNED connections.
                    retired.bindingContext.close()
                } finally {
                    retired.handlerThread.quitSafely()
                }
            }
        }
    }

    private fun isClosed(): Boolean = closed || callbacks.isClosed()

    @Synchronized // Owned-context cleanup can also finish on this Car's SDK handler thread.
    private fun diagnose(key: String, message: String) {
        val now = nowMillis()
        val previous = diagnosticAt[key]
        if (previous != null && now - previous < LOG_INTERVAL_MILLIS) return
        diagnosticAt[key] = now
        runCatching { log("Geely vehicle-status: $message") }
    }

    private fun rootCause(error: Throwable): Throwable {
        var result = error
        repeat(8) { result = result.cause?.takeIf { it !== result } ?: return result }
        return result
    }

    override fun close() {
        if (closed) return
        closed = true
        retireCar()
    }

    private companion object {
        const val VENDOR_PERMISSION = "android.car.permission.CAR_VENDOR_EXTENSION"
        const val RETRY_MILLIS = 30_000L
        const val CONNECT_TIMEOUT_MILLIS = 5_000L
        const val LOG_INTERVAL_MILLIS = 60_000L
    }
}
