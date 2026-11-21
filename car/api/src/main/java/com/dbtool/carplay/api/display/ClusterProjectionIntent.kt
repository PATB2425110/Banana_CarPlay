package com.dbtool.carplay.api.display

/**
 * Projection-only intent. Mode 1 is NOT a VIEW key signal.
 * Respect a stable external close only after a verified, visible full-map session.
 * No route identity is available: suppression survives new routes until an explicit
 * manual open or connection reset. Window stop/recovery alone must not revoke it.
 */
open class ClusterProjectionIntentPolicy {
    private var closed = false
    private var fullModeSeen = false
    private var offSince: Long? = null

    @Synchronized fun allowsAutomaticOpen(): Boolean = !closed

    @Synchronized fun onManualRequest(visible: Boolean) {
        closed = !visible
        resetObservation()
    }

    /** Returns true only on the transition into stable-external-close suppression. */
    @Synchronized fun observeMode(mode: Int?, eligible: Boolean, nowMs: Long): Boolean {
        if (closed) return false
        if (!eligible || mode == null) {
            resetObservation()
            return false
        }
        if (mode == 3) {
            fullModeSeen = true
            offSince = null
            return false
        }
        if (mode != 1 || !fullModeSeen) {
            offSince = null
            return false
        }
        val since = offSince
        if (since == null) {
            offSince = nowMs
            return false
        }
        if (nowMs - since < STABLE_OFF_MS) return false
        closed = true
        resetObservation()
        return true
    }

    @Synchronized fun resetObservation() {
        fullModeSeen = false
        offSince = null
    }

    @Synchronized fun resetSession() {
        closed = false
        resetObservation()
    }

    companion object {
        const val STABLE_OFF_MS = 1500L
    }
}

/** The app has one active CarPlay connection; shared by its intent and presenter paths. */
object ClusterProjectionIntent : ClusterProjectionIntentPolicy()
