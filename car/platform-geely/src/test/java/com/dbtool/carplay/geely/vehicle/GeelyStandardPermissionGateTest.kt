package com.dbtool.carplay.geely.vehicle

import org.junit.Assert.*
import org.junit.Test

class GeelyStandardPermissionGateTest {
    @Test fun `denied permission never executes factory or binding action and logs only once`() {
        var creations = 0
        var deniedLogs = 0
        val gate = GeelyStandardPermissionGate({ false }, { deniedLogs++ })
        repeat(10) { assertNull(gate.withPermission { creations++; Any() }) }
        assertEquals(0, creations)
        assertEquals(1, deniedLogs)
    }

    @Test fun `permission check failure is closed without executing connection action`() {
        var creations = 0
        val gate = GeelyStandardPermissionGate({ throw SecurityException("denied") }, {})
        assertNull(gate.withPermission { creations++; true })
        assertEquals(0, creations)
    }

    @Test fun `legal grant may enable later and revocation blocks further work`() {
        var granted = false
        var actions = 0
        val gate = GeelyStandardPermissionGate({ granted }, {})
        assertNull(gate.withPermission { actions++; 1 })
        granted = true
        assertEquals(1, gate.withPermission { actions++; 1 })
        granted = false
        assertNull(gate.withPermission { actions++; 1 })
        assertEquals(1, actions)
    }

    @Test fun `gate does not mask authorized endpoint error as a successful reading`() {
        val gate = GeelyStandardPermissionGate({ true }, {})
        try {
            gate.withPermission<Unit> { throw SecurityException("service rejected actual caller") }
            fail("Service permission error must remain an error for the client's fallback policy")
        } catch (_: SecurityException) {
            // The production client catches this and keeps the reading unknown.
        }
    }
}
