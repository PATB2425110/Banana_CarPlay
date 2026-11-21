package com.dbtool.carplay.app

import com.dbtool.carplay.api.navigation.CarNavigationSnapshot

/** All locally visible guidance, plus route generation. Revisions/timestamps are not paint changes. */
internal fun CarNavigationSnapshot?.navigationCardKey(): List<Any?>? = this?.let {
    listOf(
        it.routeMeters, it.routeSeconds, it.segmentMeters, it.currentRoad, it.nextRoad,
        it.maneuverDescription, it.destination, it.arrivalEpochSeconds, it.routeActive,
        it.maneuverType, it.lanes, it.sessionId,
        it.routeGeneration, it.guidanceState, it.maneuverState, it.maneuverIndices,
        it.maneuverDistanceDisplay, it.maneuverDistanceUnits, it.routeDistanceDisplay,
        it.routeDistanceUnits, it.laneDescription,
    )
}
