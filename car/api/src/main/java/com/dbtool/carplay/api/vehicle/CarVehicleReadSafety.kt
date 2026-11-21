package com.dbtool.carplay.api.vehicle

import android.content.Context

/** Dedicated preferences: an old settings draft/import cannot silently re-enable a tripped reader. */
class CarVehicleReadSafety private constructor(context: Context) : CarVehicleReadProtection {
    private val prefs = runCatching {
        (context.applicationContext ?: context)
            .getSharedPreferences("car_vehicle_read_safety", Context.MODE_PRIVATE)
    }.getOrNull()
    private val policy = VehicleReadSafetyPolicy(object : VehicleReadSafetyStore {
        override fun load(): VehicleReadSafetyState {
            val values = prefs ?: return VehicleReadSafetyState(disabledReason = "保护存储不可用，车辆读取已关闭")
            return VehicleReadSafetyState(
                enabled = values.getBoolean("enabled", false),
                interruptedSession = values.getBoolean("active_reading", false),
                disabledReason = values.getString("disabled_reason", null),
            )
        }
        override fun save(state: VehicleReadSafetyState): Boolean {
            val editor = prefs?.edit() ?: return false
            return editor.putBoolean("enabled", state.enabled)
                .putBoolean("active_reading", state.interruptedSession)
                .putString("disabled_reason", state.disabledReason).commit()
        }
    })

    override fun state() = policy.state()
    fun setEnabled(enabled: Boolean) = policy.setEnabled(enabled)
    override fun beginSession() = policy.beginSession()
    override fun endSession(token: Long) = policy.endSession(token)
    override fun trip(reason: String) = policy.trip(reason)
    override fun observe(listener: (VehicleReadSafetyState) -> Unit) = policy.observe(listener)

    companion object {
        @Volatile private var instance: CarVehicleReadSafety? = null
        @Synchronized fun forProcess(context: Context): CarVehicleReadSafety =
            instance ?: CarVehicleReadSafety(context).also { instance = it }

        /** No exception is swallowed: preserve the existing crash handler/Android termination. */
        @Synchronized fun installCrashProtection(context: Context) {
            val safety = forProcess(context) // Recover before platform/controller can create a source.
            if (crashProtectionInstalled) return
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            try {
                Thread.setDefaultUncaughtExceptionHandler(VehicleReadCrashHandler(safety, previous))
                crashProtectionInstalled = true
            } catch (error: Throwable) {
                safety.trip("无法安装车辆读取崩溃保护，读取保持关闭")
                throw error
            }
        }
        private var crashProtectionInstalled = false
    }
}

/** Chaining is in finally: even protection/storage/classification failure must not mask a crash. */
internal class VehicleReadCrashHandler(
    private val safety: CarVehicleReadProtection,
    private val previous: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, error: Throwable) {
        try {
            if (VehicleReadCrashClassifier.isVehicleReadFailure(thread.name, error)) {
                safety.trip("车辆读取相关异常，已自动关闭：${error.javaClass.simpleName}")
            }
        } catch (_: Throwable) {
            // The active-reading marker is the fallback if persisting an explicit trip fails.
        } finally {
            if (previous != null) previous.uncaughtException(thread, error) else throw error
        }
    }
}

object VehicleReadCrashClassifier {
    fun isVehicleReadFailure(threadName: String, error: Throwable): Boolean {
        if (threadName == "geely-vehicle-status" || threadName == "geely-standard-car-sdk") return true
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        var current: Throwable? = error
        repeat(16) {
            val value = current ?: return false
            if (!seen.add(value)) return false
            if (value.stackTrace.any { it.className.startsWith("com.dbtool.carplay.geely.vehicle.") }) return true
            current = value.cause
        }
        return false
    }
}
