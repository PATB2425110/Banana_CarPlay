package com.shilapi.xcertplay.navigation

import android.util.Log
import com.shilapi.xcertplay.iap2.message.CarNavigationState

/**
 * Process-wide publish point for the navigation state decoded from iAP2.
 *
 * ## Why this exists (DBPlay PATCH-0016)
 *
 * `CarPlayController` decodes the iPhone's route-guidance messages, but the
 * `:car` extension layer cannot read them: the accumulator and the controller's
 * callback are both private to the Core, and — unlike "now playing" — there is
 * no public `MediaSessionService` to observe navigation through.
 *
 * This object is that missing public door. It deliberately mirrors
 * [com.shilapi.xcertplay.media.CarPlayMediaSessionBridge], except that it is
 * `object`-visible to other modules rather than `internal`, because a seam here
 * is the whole point of the patch.
 *
 * ## Contract for subscribers
 *
 * - [observe] delivers the current value immediately, then on every change.
 *   Callers must not have to query separately; doing so would introduce a race.
 * - A subscriber that throws is logged and skipped. A HUD adapter is a
 *   nice-to-have and must never be able to break media playback or the session.
 * - Values are published on whatever thread sent the iAP2 frame. Subscribers
 *   that need a specific thread must marshal themselves; the Geely HUD bridge
 *   posts to its own handler.
 * - `null` means "no navigation guidance is active", which is different from
 *   "a route with no data yet" — callers should hide their UI on `null`.
 */
object CarPlayNavigationBridge {
    private val lock = Any()
    private var owner: Any? = null
    private var snapshot: CarNavigationState? = null
    private val observers = LinkedHashSet<(CarNavigationState?) -> Unit>()

    /**
     * Registers [observer] and immediately reports the current value.
     *
     * Returns a function that removes the observer; calling it more than once is
     * safe. Observers are keyed by identity, so the same lambda cannot be
     * registered twice.
     */
    fun observe(observer: (CarNavigationState?) -> Unit): () -> Unit {
        val current = synchronized(lock) {
            observers += observer
            snapshot
        }
        notifyObserver(observer, current)
        return {
            synchronized(lock) { observers -= observer }
            Unit
        }
    }

    /** The most recently published state, or `null` when guidance is not active. */
    fun current(): CarNavigationState? = synchronized(lock) { snapshot }

    /**
     * Publishes [state] as the current navigation snapshot.
     *
     * Only [owner] may publish; a stale controller from a previous session cannot
     * overwrite the live one, matching [com.shilapi.xcertplay.media.CarPlayMediaSessionBridge].
     * Pass `null` to report that guidance has stopped.
     */
    fun publish(owner: Any, state: CarNavigationState?) {
        val targets = synchronized(lock) {
            if (this.owner !== owner) return
            if (snapshot == state) return
            snapshot = state
            observers.toList()
        }
        targets.forEach { notifyObserver(it, state) }
    }

    /**
     * Claims the publish channel for [owner] and clears any stale state.
     *
     * Called when a session starts so observers never see the previous car's
     * route during the gap before the first frame arrives.
     */
    fun attach(owner: Any) {
        val targets = synchronized(lock) {
            this.owner = owner
            snapshot = null
            observers.toList()
        }
        targets.forEach { notifyObserver(it, null) }
    }

    /** Releases the channel when [owner] goes away, if it still holds it. */
    fun detach(owner: Any) {
        val targets = synchronized(lock) {
            if (this.owner !== owner) return
            this.owner = null
            snapshot = null
            observers.toList()
        }
        targets.forEach { notifyObserver(it, null) }
    }

    private fun notifyObserver(observer: (CarNavigationState?) -> Unit, state: CarNavigationState?) {
        // One misbehaving subscriber must not stop the others or surface into
        // the iAP2 control loop that triggered the publish.
        runCatching { observer(state) }
            .onFailure { Log.w(TAG, "导航订阅方回调异常", it) }
    }

    private const val TAG = "CarPlay-Navigation"
}
