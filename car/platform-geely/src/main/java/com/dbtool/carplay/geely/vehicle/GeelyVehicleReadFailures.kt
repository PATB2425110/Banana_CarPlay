package com.dbtool.carplay.geely.vehicle

import java.lang.reflect.InvocationTargetException

/** Unsupported/missing metadata stays unknown, but a caught JVM/native-link Error must trip. */
internal object GeelyVehicleReadFailures {
    fun rethrowFatal(error: Throwable) {
        var actual = error
        for (depth in 0 until 8) {
            val envelope = actual as? InvocationTargetException ?: break
            actual = envelope.targetException ?: break
        }
        if (actual is Error) throw actual
    }

    inline fun <T> orUnknown(action: () -> T): T? = try {
        action()
    } catch (error: Throwable) {
        rethrowFatal(error)
        null
    }
}
