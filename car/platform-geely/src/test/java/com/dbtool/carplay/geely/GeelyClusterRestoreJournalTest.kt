package com.dbtool.carplay.geely

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `GeelyClusterRestoreJournal` 纯 JVM 单测：账本的写入 / 读取 / 清除与顺序。
 */
class GeelyClusterRestoreJournalTest {

    @Test
    fun `mark read clear round trip`() {
        val store = MemoryJournalStore()
        val journal = GeelyClusterRestoreJournal(store)

        journal.markAcquireIntent(displayId = 3, originalMode = 1, nowElapsedMs = 1_234L)

        val pending = journal.pending()
        assertNotNull(pending)
        assertEquals(3, pending!!.displayId)
        assertEquals(1, pending.originalMode)
        assertEquals(1_234L, pending.sinceElapsedMs)

        journal.clear()
        assertNull(journal.pending())
        assertFalse(journal.isActive())
    }

    @Test
    fun `unreadable original mode is stored as null`() {
        val journal = GeelyClusterRestoreJournal(MemoryJournalStore())

        journal.markAcquireIntent(displayId = 5, originalMode = null, nowElapsedMs = 10L)

        assertNull(journal.pending()!!.originalMode)
    }

    @Test
    fun `journal uses documented preference keys`() {
        val store = MemoryJournalStore()
        val journal = GeelyClusterRestoreJournal(store)

        journal.markAcquireIntent(displayId = 3, originalMode = 1, nowElapsedMs = 1L)

        assertTrue(store.values.containsKey("cluster_fullmap_active"))
        assertTrue(store.values.containsKey("cluster_fullmap_display_id"))
        assertTrue(store.values.containsKey("cluster_fullmap_original_mode"))
        assertTrue(store.values.containsKey("cluster_fullmap_since_elapsed"))
    }

    @Test
    fun `markReleased false keeps journal while true releases it`() {
        val journal = GeelyClusterRestoreJournal(MemoryJournalStore())
        journal.markAcquireIntent(displayId = 3, originalMode = 1, nowElapsedMs = 1L)

        journal.markReleased(verified = false)
        assertNotNull(journal.pending())

        journal.markReleased(verified = true)
        assertNull(journal.pending())
        assertFalse(journal.isActive())
    }

    @Test
    fun `journal is committed before switchNaviMode is called`() {
        val scheduler = FakeClusterScheduler()
        val store = MemoryJournalStore()
        val journal = GeelyClusterRestoreJournal(store)
        val dim = FakeNaviModeController()
        val events = mutableListOf<String>()
        dim.modes.add(1)
        dim.onEnter = {
            // 关键断言：切模式的那一刻，账本必须已经 active（commit 已完成）。
            assertTrue(journal.isActive())
            events += "switch"
        }
        val session = GeelyClusterModeSession(
            dim = dim,
            journal = journal,
            scheduler = scheduler,
            diagnostics = {},
            now = { scheduler.currentTime },
        )

        session.acquire("attach")

        assertTrue(store.writes.isNotEmpty())
        assertEquals(listOf("switch"), events)
        assertNotNull(journal.pending())
    }
}
