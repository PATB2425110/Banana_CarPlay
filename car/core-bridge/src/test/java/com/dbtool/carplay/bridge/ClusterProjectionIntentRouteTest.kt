package com.dbtool.carplay.bridge

import com.dbtool.carplay.api.display.ClusterProjectionIntent
import com.dbtool.carplay.bridge.navigation.CarPlayClusterRouteClient
import com.shilapi.xcertplay.iap2.message.CarNavigationState
import com.shilapi.xcertplay.navigation.CarPlayNavigationBridge
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class ClusterProjectionIntentRouteTest {
    private val requests = mutableListOf<Boolean>()
    private val timers = mutableListOf<Runnable>()
    private val owner = Any()
    private var now = 0L
    private var revision = 0L
    private val client = CarPlayClusterRouteClient(
        onVisibilityRequest = { requests += it },
        clock = { now },
        postTimeout = { _, task -> timers += task },
        cancelTimeout = { task -> timers.removeAll { it === task } },
    )

    private fun start() {
        CarPlayNavigationBridge.attach(owner)
        client.connect()
    }

    private fun route(active: Boolean) = CarPlayNavigationBridge.publish(
        owner, CarNavigationState(routeActive = active, revision = ++revision),
    )

    @After fun cleanup() {
        client.disconnect()
        CarPlayNavigationBridge.detach(owner)
    }

    @Test fun manualCloseSurvivesRouteEndNewRouteAndAutoToggle() {
        start()
        route(true)
        ClusterProjectionIntent.onManualRequest(false)
        route(false)
        now += CarPlayClusterRouteClient.NAV_STOP_GRACE_MS
        val due = timers.toList()
        timers.clear()
        due.forEach { it.run() }
        requests.clear()
        route(true)
        client.setEnabled(false)
        client.setEnabled(true)
        route(true)
        assertFalse(requests.contains(true))
        assertFalse(ClusterProjectionIntent.allowsAutomaticOpen())
    }

    @Test fun manualOpenAllowsRouteRequestsAgain() {
        start()
        ClusterProjectionIntent.onManualRequest(false)
        route(true)
        assertTrue(requests.isEmpty())
        ClusterProjectionIntent.onManualRequest(true)
        route(true)
        assertEquals(listOf(true), requests)
    }

    @Test fun disconnectClearsIntentAndPendingTimeouts() {
        start()
        ClusterProjectionIntent.onManualRequest(false)
        route(false)
        assertTrue(timers.isNotEmpty())
        client.disconnect()
        assertTrue(timers.isEmpty())
        assertTrue(ClusterProjectionIntent.allowsAutomaticOpen())
        client.connect()
        route(true)
        assertEquals(listOf(true), requests)
    }
}
