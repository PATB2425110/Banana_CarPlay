package com.dbtool.carplay.geely.vehicle

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

internal sealed interface GeelyStandardPropertyGetResult {
    data class Value(val raw: Any?) : GeelyStandardPropertyGetResult
    data class InvalidSample(val error: IllegalArgumentException) : GeelyStandardPropertyGetResult
}

/**
 * The real public CarPropertyManager.getProperty(Class,int,int) can throw IllegalArgumentException
 * before returning a wrapper (e.g. actual sample type disagrees with requested Integer). That is a
 * field failure, not evidence the connection died. Only unwrap reflection envelopes, not arbitrary
 * causes: a SecurityException with an IllegalArgumentException cause must remain a denial.
 */
internal object GeelyStandardPropertyGetter {
    fun read(
        method: Method,
        receiver: Any,
        selection: GeelyStandardPropertySelection,
    ): GeelyStandardPropertyGetResult = try {
        GeelyStandardPropertyGetResult.Value(
            method.invoke(receiver, Integer::class.java, selection.halPropertyId, selection.areaId),
        )
    } catch (error: Exception) {
        val actual = unwrapInvocation(error)
        if (actual is IllegalArgumentException) GeelyStandardPropertyGetResult.InvalidSample(actual)
        else throw actual // Permission, remote/death, not-connected and unexpected failures stay fatal to this connection.
    }

    private fun unwrapInvocation(error: Throwable): Throwable {
        var result = error
        repeat(8) {
            if (result !is InvocationTargetException) return result
            result = (result as InvocationTargetException).targetException ?: return result
        }
        return result
    }
}
