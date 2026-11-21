package com.dbtool.carplay.geely

import com.dbtool.carplay.api.navigation.CarHudGuidance

/** Bounded mailbox: a stop barrier plus the newest full snapshot, never one task per frame.
 * Source freshness uses monotonic ingress time. Transport heartbeats must not call [offer].
 */
internal class HudNavigationState(private val sourceTimeoutMs: Long = HUD_EXPIRY_MS) {
    data class Batch(val stop: Boolean, val guidance: CarHudGuidance?)

    private var current: CarHudGuidance? = null
    private var pending: CarHudGuidance? = null
    private var stopPending = false
    private var sourceDeadline: Long? = null

    @Synchronized
    fun offer(guidance: CarHudGuidance?, nowMs: Long) {
        if (guidance == null) {
            clear()
            stopPending = true
        } else {
            current = guidance
            pending = guidance
            sourceDeadline = nowMs + sourceTimeoutMs
        }
    }

    @Synchronized
    fun drain(nowMs: Long): Batch = takeForSend(nowMs, replay = false)

    /** Replay and heartbeat must consume the same stop barrier as a content update. */
    @Synchronized
    fun takeForSend(nowMs: Long, replay: Boolean): Batch {
        expire(nowMs)
        return Batch(stopPending, pending ?: if (replay) current else null).also {
            stopPending = false
            pending = null
        }
    }

    @Synchronized
    fun fresh(nowMs: Long): CarHudGuidance? {
        expire(nowMs)
        return current
    }

    @Synchronized
    fun clear() {
        current = null
        pending = null
        sourceDeadline = null
        stopPending = false
    }

    private fun expire(nowMs: Long) {
        if (sourceDeadline?.let { nowMs >= it } == true) {
            clear()
            stopPending = true
        }
    }
}
