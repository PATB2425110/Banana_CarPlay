package com.dbtool.carplay.bridge.navigation

import com.dbtool.carplay.api.navigation.CarNavigationSnapshot
import com.dbtool.carplay.api.navigation.CarHudLane
import com.dbtool.carplay.api.navigation.CarNavigationSource
import com.shilapi.xcertplay.iap2.message.CarNavigationState
import com.shilapi.xcertplay.navigation.CarPlayNavigationBridge

/**
 * Adapts the existing iAP2 navigation publish point to the app-facing contract.
 *
 * This class is deliberately a thin read-only adapter: route decoding and
 * lifecycle ownership remain in the existing Core path, while UI and vehicle
 * consumers receive independent API snapshots.
 */
class CarPlayNavigationDataSource : CarNavigationSource {
    override fun current(): CarNavigationSnapshot? =
        CarPlayNavigationBridge.current()?.toCarNavigationSnapshot()

    override fun observe(listener: (CarNavigationSnapshot?) -> Unit): () -> Unit =
        CarPlayNavigationBridge.observe { state ->
            listener(state?.toCarNavigationSnapshot())
        }
}

/** Pure field projection kept in core-bridge, the only extension module allowed to see upstream types. */
internal fun CarNavigationState.toCarNavigationSnapshot(
    receivedAtEpochMillis: Long = System.currentTimeMillis(),
): CarNavigationSnapshot? {
    if (!hasContent) return null
    return CarNavigationSnapshot(
        routeGeneration = routeGeneration, guidanceState = guidanceState,
        maneuverState = maneuverState, maneuverIndices = maneuverIndices,
        maneuverCount = maneuverCount, shownInApp = shownInApp,
        laneCount = laneCount, laneDescription = laneDescription,
        routeSource = routeSource, sourceSupportsGuidance = sourceSupportsGuidance,
        routeDistanceDisplay = routeDistanceDisplay, routeDistanceUnits = routeDistanceUnits,
        maneuverDistanceDisplay = maneuverDistanceDisplay, maneuverDistanceUnits = maneuverDistanceUnits,
        routeMeters = routeMeters,
        routeSeconds = routeSeconds,
        segmentMeters = segmentMeters,
        currentRoad = currentRoad,
        nextRoad = nextRoad,
        maneuverDescription = maneuverDescription,
        maneuverType = maneuverType,
        destination = destination,
        arrivalEpochSeconds = arrivalEpochSeconds,
        routeActive = routeActive,
        sessionId = sessionId,
        revision = revision,
        receivedAtEpochMillis = receivedAtEpochMillis,
        lanes = lanes.sortedBy { it.index }.take(16).map {
            CarHudLane(index = it.index, angles = it.angles.take(8).toList(), highlightAngle = it.highlightAngle)
        },
    )
}
