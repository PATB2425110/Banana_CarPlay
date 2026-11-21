package com.dbtool.carplay.geely.vehicle

import org.junit.Assert.*
import org.junit.Test

class GeelyOwnedBindingRegistryTest {
    private data class Connection(val label: String)

    @Test fun `SDK state zero no-op disconnect is still cleaned once by owner close`() {
        val unbound = mutableListOf<Connection>()
        val owner = GeelyOwnedBindingRegistry<Connection>({ unbound += it })
        val sdkInnerConnection = Connection("SDK inner listener, not our external callback")
        assertTrue(owner.bind(sdkInnerConnection) { true })
        val sdkState = 0 // Captured Car.onServiceDisconnected: zero BEFORE calling disconnect().
        fun sdkDisconnect() {
            if (sdkState == 0) return
            owner.unbind(sdkInnerConnection)
        }
        sdkDisconnect()
        assertTrue(unbound.isEmpty())
        owner.close()
        owner.close() // Repeated retire must not re-unbind.
        owner.unbind(sdkInnerConnection)
        assertEquals(1, unbound.size)
        assertSame(sdkInnerConnection, unbound.single())
    }

    @Test fun `normal SDK unbind removes ownership and owner close does not double release`() {
        val unbound = mutableListOf<Any>()
        val owner = GeelyOwnedBindingRegistry<Any>({ unbound += it })
        val connection = Any()
        assertTrue(owner.bind(connection) { true })
        owner.unbind(connection)
        owner.unbind(connection)
        owner.close()
        owner.close()
        assertEquals(1, unbound.size)
        assertSame(connection, unbound.single())
    }

    @Test fun `late SDK retry after wrapper close cannot create a backend binding`() {
        var binds = 0
        val owner = GeelyOwnedBindingRegistry<Any>({})
        owner.close()
        repeat(10) {
            assertFalse(owner.bind(Any()) { binds++; true })
        }
        assertEquals(0, binds)
    }

    @Test fun `retired old Car registry never releases a newer Cars binding`() {
        val unbound = mutableListOf<Connection>()
        val oldOwner = GeelyOwnedBindingRegistry<Connection>({ unbound += it })
        val newOwner = GeelyOwnedBindingRegistry<Connection>({ unbound += it })
        val old = Connection("old SDK listener")
        val current = Connection("new SDK listener")
        assertTrue(oldOwner.bind(old) { true })
        assertTrue(newOwner.bind(current) { true })
        oldOwner.close()
        oldOwner.unbind(current) // Unowned: must never delegate to the shared app context.
        assertEquals(1, unbound.size)
        assertSame(old, unbound.single())
        newOwner.close()
        assertEquals(2, unbound.size)
        assertSame(current, unbound.last())
    }

    @Test fun `ownership uses exact connection identity not equals`() {
        val unbound = mutableListOf<Connection>()
        val owner = GeelyOwnedBindingRegistry<Connection>({ unbound += it })
        val owned = Connection("equal")
        val notOwned = Connection("equal")
        assertEquals(owned, notOwned)
        assertNotSame(owned, notOwned)
        assertTrue(owner.bind(owned) { true })
        owner.unbind(notOwned)
        assertTrue(unbound.isEmpty())
        owner.close()
        assertSame(owned, unbound.single())
    }

    @Test fun `bind completing after owner close is released and never accepted`() {
        val unbound = mutableListOf<Any>()
        val owner = GeelyOwnedBindingRegistry<Any>({ unbound += it })
        val connection = Any()
        assertFalse(owner.bind(connection) {
            owner.close() // Deliberately retire during an in-flight public Context bind.
            true
        })
        owner.close()
        owner.unbind(connection)
        assertEquals(1, unbound.size)
        assertSame(connection, unbound.single())
    }

    @Test fun `SDK unbind during pending bind defers cleanup until bind has actually completed`() {
        val unbound = mutableListOf<Any>()
        val owner = GeelyOwnedBindingRegistry<Any>({ unbound += it })
        val connection = Any()
        assertFalse(owner.bind(connection) {
            owner.unbind(connection)
            assertTrue(unbound.isEmpty())
            true
        })
        assertSame(connection, unbound.single())
        owner.close()
        assertEquals(1, unbound.size)
    }

    @Test fun `same listener cannot be rebound while its earlier unbind is in flight`() {
        var backendBinds = 0
        lateinit var owner: GeelyOwnedBindingRegistry<Any>
        val connection = Any()
        owner = GeelyOwnedBindingRegistry({
            assertFalse(owner.bind(connection) { backendBinds++; true })
        })
        assertTrue(owner.bind(connection) { backendBinds++; true })
        owner.unbind(connection)
        assertEquals(1, backendBinds)
    }

    @Test fun `duplicate bind of an already owned listener does not create another binding`() {
        var binds = 0
        val unbound = mutableListOf<Any>()
        val owner = GeelyOwnedBindingRegistry<Any>({ unbound += it })
        val connection = Any()
        assertTrue(owner.bind(connection) { binds++; true })
        assertTrue(owner.bind(connection) { binds++; true })
        assertEquals(1, binds)
        owner.close()
        assertEquals(1, unbound.size)
    }

    @Test fun `cleanup failure does not stop releasing other independently owned listeners`() {
        var unbinds = 0
        var failures = 0
        val owner = GeelyOwnedBindingRegistry<Any>(
            backendUnbind = { unbinds++; throw IllegalArgumentException("binding already gone") },
            cleanupFailure = { failures++ },
        )
        assertTrue(owner.bind(Any()) { true })
        assertTrue(owner.bind(Any()) { true })
        owner.close()
        owner.close()
        assertEquals(2, unbinds)
        assertEquals(2, failures)
    }

    @Test fun `rejected or throwing bind is never reported as an owned successful binding`() {
        var unbinds = 0
        val owner = GeelyOwnedBindingRegistry<Any>({ unbinds++ })
        assertFalse(owner.bind(Any()) { false })
        try {
            owner.bind(Any()) { throw SecurityException("bind denied") }
            fail("Caller must observe the actual bind denial")
        } catch (_: SecurityException) {
            // The standard client's outer connection handler fails closed.
        }
        owner.close()
        assertEquals(2, unbinds) // Each entered backend attempt may own a local dispatcher.
    }

    @Test fun `false or throwing backend attempts clean registered dispatchers exactly once`() {
        val dispatchers = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
        var cleanupCalls = 0
        val owner = GeelyOwnedBindingRegistry<Any>({ connection ->
            cleanupCalls++
            assertTrue(dispatchers.remove(connection))
        })
        val rejected = Any()
        assertFalse(owner.bind(rejected) { dispatchers += rejected; false })
        val throwing = Any()
        val denied = SecurityException("system denied after dispatcher registration")
        try {
            owner.bind(throwing) { dispatchers += throwing; throw denied }
            fail("Original exception must be preserved")
        } catch (error: SecurityException) {
            assertSame(denied, error)
        }
        owner.unbind(rejected)
        owner.unbind(throwing)
        owner.close()
        assertTrue(dispatchers.isEmpty())
        assertEquals(2, cleanupCalls)
    }

    @Test fun `closed request never entering backend must not attempt dispatcher cleanup`() {
        var cleanupCalls = 0
        var backendCalls = 0
        val owner = GeelyOwnedBindingRegistry<Any>({ cleanupCalls++ })
        owner.close()
        assertFalse(owner.bind(Any()) { backendCalls++; false })
        assertEquals(0, backendCalls)
        assertEquals(0, cleanupCalls)
    }
}
