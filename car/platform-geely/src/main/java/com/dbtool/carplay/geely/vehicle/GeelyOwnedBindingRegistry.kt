package com.dbtool.carplay.geely.vehicle

import java.util.IdentityHashMap

/**
 * Android-free ownership of the EXACT ServiceConnection objects passed by one Car factory instance.
 * Never unbinds an unowned connection. Ownership includes an entered backend attempt: Android can
 * register a local dispatcher before bind returns false or throws. Those attempts also need cleanup.
 * Pending binds retired concurrently are unbound on completion.
 * The lock guards bookkeeping only: no Android bind/unbind IPC runs under it.
 */
internal class GeelyOwnedBindingRegistry<C : Any>(
    private val backendUnbind: (C) -> Unit,
    private val cleanupFailure: (RuntimeException) -> Unit = {},
) : AutoCloseable {
    private enum class Phase { PENDING, BOUND, RETIRED }
    private class Entry(var phase: Phase = Phase.PENDING)
    private val lock = Any()
    private val entries = IdentityHashMap<C, Entry>()
    private var closed = false

    fun bind(connection: C, action: () -> Boolean): Boolean {
        val entry: Entry
        synchronized(lock) {
            if (closed) return false
            entries[connection]?.let { return it.phase == Phase.BOUND }
            entry = Entry()
            entries[connection] = entry
        }
        var returned = false
        val bound = try {
            action().also { returned = true }
        } finally {
            if (!returned) {
                synchronized(lock) {
                    entry.phase = Phase.RETIRED
                }
                releaseOwned(connection, entry) // Keep the original failure; release may find no binding.
            }
        }
        var shouldRelease = false
        val accepted = synchronized(lock) {
            if (!bound) {
                entry.phase = Phase.RETIRED
                shouldRelease = true // A false result may still own a Context ServiceDispatcher.
                false
            } else if (closed || entry.phase == Phase.RETIRED) {
                entry.phase = Phase.RETIRED
                shouldRelease = true
                false
            } else {
                entry.phase = Phase.BOUND
                true
            }
        }
        if (shouldRelease) releaseOwned(connection, entry)
        return accepted
    }

    fun unbind(connection: C) {
        val entry = synchronized(lock) {
            val entry = entries[connection] ?: return
            when (entry.phase) {
                Phase.PENDING -> {
                    entry.phase = Phase.RETIRED
                    null // Do not race an unbind ahead of an in-flight bind; its completion owns cleanup.
                }
                Phase.BOUND -> {
                    entry.phase = Phase.RETIRED
                    entry
                }
                Phase.RETIRED -> null
            }
        }
        if (entry != null) releaseOwned(connection, entry)
    }

    override fun close() {
        val toRelease = mutableListOf<Pair<C, Entry>>()
        synchronized(lock) {
            if (closed) return
            closed = true
            val iterator = entries.entries.iterator()
            while (iterator.hasNext()) {
                val (connection, entry) = iterator.next()
                if (entry.phase == Phase.BOUND) {
                    entry.phase = Phase.RETIRED
                    toRelease += connection to entry
                } else {
                    entry.phase = Phase.RETIRED
                }
            }
        }
        toRelease.forEach { (connection, entry) -> releaseOwned(connection, entry) }
    }

    private fun releaseOwned(connection: C, entry: Entry) {
        try {
            backendUnbind(connection)
        } catch (error: RuntimeException) {
            // Android may have already removed a dead binding. Cleanup of our other owned binds
            // must still run; never retry a possibly-completed unbind against unrelated ownership.
            runCatching { cleanupFailure(error) }
        } finally {
            synchronized(lock) {
                if (entries[connection] === entry) entries.remove(connection)
            }
        }
    }
}
