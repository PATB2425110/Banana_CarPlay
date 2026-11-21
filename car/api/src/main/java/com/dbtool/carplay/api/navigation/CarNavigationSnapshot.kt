package com.dbtool.carplay.api.navigation

/**
 * UI-safe snapshot of route guidance received from the connected iPhone.
 *
 * This is intentionally independent of iAP2 decoder and vehicle HUD formats:
 * the same phone data can be rendered by DBPlay UI, sent to a vehicle HUD, or
 * projected to a cluster without making those consumers depend on one another.
 * Every field is optional because iAP2 sends partial updates and different
 * navigation apps provide different subsets.
 */
data class CarNavigationSnapshot(
    val routeGeneration: Long? = null,
    val guidanceState: Int? = null,
    val maneuverState: Int? = null,
    val maneuverIndices: List<Int> = emptyList(),
    val maneuverCount: Int? = null,
    val shownInApp: Boolean? = null,
    val laneCount: Int? = null,
    val laneDescription: String? = null,
    val routeSource: String? = null,
    val sourceSupportsGuidance: Boolean? = null,
    val routeDistanceDisplay: String? = null,
    val routeDistanceUnits: Int? = null,
    val maneuverDistanceDisplay: String? = null,
    val maneuverDistanceUnits: Int? = null,
    /** Remaining route distance in metres; null means the phone did not provide it. */
    val routeMeters: Int? = null,
    /** Remaining route duration in seconds; null means the phone did not provide it. */
    val routeSeconds: Int? = null,
    /** Distance to the next manoeuvre in metres. */
    val segmentMeters: Int? = null,
    /** Current road name, when supplied. */
    val currentRoad: String? = null,
    /** Road entered after the next manoeuvre, when supplied. */
    val nextRoad: String? = null,
    /** Human-readable instruction for the next manoeuvre, when supplied. */
    val maneuverDescription: String? = null,
    /** Raw phone manoeuvre code; presentation must not assume every code is mapped. */
    val maneuverType: Int? = null,
    /** Destination label, when supplied. */
    val destination: String? = null,
    /** Estimated arrival time as Unix epoch seconds. */
    val arrivalEpochSeconds: Long? = null,
    /** Whether the decoder currently considers route guidance active. */
    val routeActive: Boolean = false,
    /** Phone route session identifier, when supplied. */
    val sessionId: Long? = null,
    /** Monotonic revision within the phone route session, when supplied. */
    val revision: Long? = null,
    /** Wall-clock time when this snapshot was received, in Unix epoch milliseconds. */
    val receivedAtEpochMillis: Long = 0L,
    /** Left-to-right lane branches, retaining raw phone angles (including unknown values). */
    val lanes: List<CarHudLane> = emptyList(),
) {
    /** True when at least one user-visible route field contains information. */
    val hasContent: Boolean
        get() = routeMeters != null ||
            routeSeconds != null ||
            segmentMeters != null ||
            currentRoad != null ||
            nextRoad != null ||
            maneuverDescription != null ||
            maneuverType != null ||
            destination != null ||
            arrivalEpochSeconds != null ||
            lanes.isNotEmpty()
}

/**
 * Read-only source for the current iPhone route-guidance snapshot.
 *
 * [observe] immediately emits the current value before subsequent changes.
 * A null value means there is no current guidance snapshot. Implementations may
 * invoke listeners on the iAP2 receive thread; UI callers must marshal updates
 * onto their UI thread. The returned cancellation action is idempotent.
 */
interface CarNavigationSource {
    fun current(): CarNavigationSnapshot?

    fun observe(listener: (CarNavigationSnapshot?) -> Unit): () -> Unit
}
