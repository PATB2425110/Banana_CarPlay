package com.dbtool.carplay.ecarx.media

import org.junit.Assert.*
import org.junit.Test

/** Models only the equal-status receiver gate, not Android rendering of status 0. */
class FdBookReceiverRefreshTest {
    private class ReceiverGate {
        var status = 0
        var title = ""
        var cover = ""
        fun receive(next: Int, name: String, url: String) {
            if (status == next) return
            status = next
            title = name
            cover = url
        }
    }

    @Test fun changedSongAndCoverReachSameStatusReceiverWithoutQueueChanges() {
        val receiver = ReceiverGate()
        var key: String? = null
        var lastStatus = 0
        fun publish(title: String, cover: String): List<Int> {
            val next = FdBookBroadcastPlan.fingerprint(title, "artist", cover, 3, 180000, "same-item")
            if (key == next) return emptyList()
            val sequence = FdBookBroadcastPlan.statusSequence(true, 3, lastStatus)
            sequence.forEach { receiver.receive(it, title, cover) }
            key = next
            lastStatus = sequence.last()
            return sequence
        }
        assertEquals(listOf(3), publish("歌曲A", "cover-A"))
        assertEquals(listOf(0, 3), publish("歌曲B", "cover-B"))
        assertEquals("歌曲B", receiver.title)
        assertEquals("cover-B", receiver.cover)
        assertEquals(3, receiver.status)
        assertTrue(publish("歌曲B", "cover-B").isEmpty())
    }

    @Test fun lateArtworkUsesBoxRefreshInsteadOfAnIgnoredSinglePlayingState() {
        val receiver = ReceiverGate()
        receiver.receive(3, "新歌", "old-cover")
        receiver.receive(3, "新歌", "new-cover")
        assertEquals("old-cover", receiver.cover) // reproduces the previous bug
        val sequence = FdBookBroadcastPlan.statusSequence(true, 3, 3)
        assertFalse("refresh while playing must not manufacture pause=2", sequence.contains(2))
        sequence.forEach { receiver.receive(it, "新歌", "new-cover") }
        assertEquals("new-cover", receiver.cover)
        assertEquals(3, receiver.status)
    }

    @Test fun lyricUpdatesKeepWireItemIdsAndActualPausedState() {
        val id = "carplay-now-playing-9"
        val book = FdBookBroadcastPlan.bookId(id)
        val fragment = FdBookBroadcastPlan.fragmentId(id)
        assertTrue(book in 0L..0xffff_ffffL)
        assertTrue(fragment in 0L..0xffff_ffffL)
        for (lyric in listOf("歌词一", "歌词二", "歌词三")) {
            assertTrue(FdBookBroadcastPlan.fingerprint(lyric, "artist", "same-cover", 2, 180000, id).isNotEmpty())
            assertEquals(book, FdBookBroadcastPlan.bookId(id))
            assertEquals(fragment, FdBookBroadcastPlan.fragmentId(id))
            assertEquals(listOf(0, 2), FdBookBroadcastPlan.statusSequence(true, 2, 2))
        }
    }

    @Test fun actualPauseResumeAndRepeatedSnapshotsDoNotAddRefreshPulses() {
        assertEquals(listOf(2), FdBookBroadcastPlan.statusSequence(true, 2, 3))
        assertEquals(listOf(3), FdBookBroadcastPlan.statusSequence(true, 3, 2))
        assertEquals(listOf(3), FdBookBroadcastPlan.statusSequence(false, 3, 3))
        assertEquals(listOf(0), FdBookBroadcastPlan.statusSequence(true, 0, 0))
    }

    @Test fun initialSnapshotAlsoRefreshesReceiverWhoseOldSessionWasAlreadyPlaying() {
        val receiver = ReceiverGate()
        receiver.receive(3, "旧会话", "old-cover")
        val initial = FdBookBroadcastPlan.statusSequence(true, 3, 0, firstSnapshot = true)
        assertEquals(listOf(0, 3), initial)
        initial.forEach { receiver.receive(it, "新会话", "new-cover") }
        assertEquals("新会话", receiver.title)
        assertEquals("new-cover", receiver.cover)
        assertEquals(3, receiver.status)
    }
}
