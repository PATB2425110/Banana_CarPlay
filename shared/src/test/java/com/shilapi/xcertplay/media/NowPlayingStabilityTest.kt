package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class NowPlayingStabilityTest {
    @Test fun identityDoesNotChangeWhenQueueIsFirstDiscoveredOrTemporarilyMissing() {
        val continuity = NowPlayingIdentityContinuity()
        val initial = continuity.observe(null, null)
        assertEquals(initial, continuity.observe(null, "Music"))
        assertEquals(initial, continuity.observe(1, "Music"))
        assertEquals(initial, continuity.observe(null, null))
        assertEquals(initial, continuity.observe(1, "Music"))
        assertNotEquals(initial, continuity.observe(2, "Music"))
    }
    @Test fun unknownQueueMetadataCannotCreateMediaItemIdentity() {
        val continuity = NowPlayingIdentityContinuity()
        val original = continuity.observe(null, "Music")
        repeat(5) { assertEquals(original, continuity.observe(null, "Music")) }
        assertEquals(original, continuity.observe(1, "Music"))
    }
    @Test fun queueContinuityResetsWithSession() {
        val continuity = NowPlayingIdentityContinuity()
        val initial = continuity.observe(1, "Music")
        assertNotEquals(initial, continuity.observe(2, "Music"))
        continuity.reset()
        assertEquals(initial, continuity.observe(7, "Music"))
    }
    @Test fun explicitPauseAndResumeAreNeverLatched() {
        assertEquals(2, resolvePlaybackStatus(2, 1))
        assertEquals(1, resolvePlaybackStatus(1, 2))
        assertEquals(0, resolvePlaybackStatus(0, 1))
        assertEquals(3, resolvePlaybackStatus(3, 1))
        assertEquals(4, resolvePlaybackStatus(4, 1))
    }
    @Test fun unknownDoesNotInventPause() {
        assertEquals(1, resolvePlaybackStatus(-1, 1))
        assertEquals(2, resolvePlaybackStatus(-1, 2))
        assertEquals(-1, resolvePlaybackStatus(-1, null))
    }
    @Test fun missingTextKeepsDisplayButNewLyricStillUpdates() {
        assertEquals("歌词一", retainDisplayText(null, "歌词一"))
        assertEquals("歌词一", retainDisplayText("  ", "歌词一"))
        assertEquals("歌词二", retainDisplayText("歌词二", "歌词一"))
    }
    @Test fun repeatedCoverHasSameObjectEvenWithAnotherTransferId() {
        val cache = NowPlayingArtworkCache()
        val cover = cache.accept(1, byteArrayOf(1, 2))
        assertSame(cover, cache.accept(1, byteArrayOf(1, 2)))
        assertSame(cover, cache.accept(2, byteArrayOf(1, 2)))
        assertTrue(cache.contains(1)); assertTrue(cache.contains(2))
    }
    @Test fun changedCoverWithSameIdRefreshes() {
        val cache = NowPlayingArtworkCache()
        assertNotSame(cache.accept(1, byteArrayOf(1)), cache.accept(1, byteArrayOf(2)))
    }
    @Test fun emptyTransferDoesNotEvictCover() {
        val cache = NowPlayingArtworkCache()
        val cover = cache.accept(1, byteArrayOf(1))
        assertNull(cache.accept(2, byteArrayOf()))
        assertTrue(cache.contains(1))
        assertSame(cover, cache.accept(1, byteArrayOf(1)))
    }
    @Test fun transfersAreBoundedAndClearedAtSessionEnd() {
        val cache = NowPlayingArtworkCache()
        for (id in 1..5) cache.accept(id, byteArrayOf(id.toByte()))
        assertFalse(cache.contains(1)); assertTrue(cache.contains(5))
        cache.clear(); assertFalse(cache.contains(5))
    }
    @Test fun callerBufferCannotMutateCachedCover() {
        val cache = NowPlayingArtworkCache()
        val bytes = byteArrayOf(1)
        val cover = cache.accept(1, bytes)!!
        bytes[0] = 2
        assertArrayEquals(byteArrayOf(1), cover.data)
    }

    private val root get() = generateSequence(File(System.getProperty("dbplay.source.root", ".")).absoluteFile) {
        it.parentFile
    }.first { File(it, "shared/src/main").isDirectory }
    private fun source(path: String) = File(root, path).readText()
    @Test fun serviceKeepsMediaItemInsideIdentityGateAndDisplayTitleLive() {
        val s = source("shared/src/main/java/com/shilapi/xcertplay/media/CarPlayMediaSessionService.kt")
        val gate = s.indexOf("if (trackIdentity != identity || mediaItem == null)")
        val item = s.indexOf("mediaItem = MediaItem.Builder()", gate)
        val metadata = s.indexOf("val metadata = nowPlaying.toMediaMetadata(artwork)", gate)
        assertTrue(gate >= 0 && item > gate && metadata > item)
        assertTrue(s.contains(".setTitle(title ?: appName)"))
        assertTrue(s.contains("playlist?.takeIf { metadataKey == nextKey }"))
        assertTrue(s.contains("identityContinuity.observe(nowPlaying.queueIndex, nowPlaying.appName)"))
        assertFalse(s.contains("nowPlayingIdentity("))
        assertFalse(s.contains("cachedArtwork = null"))
    }
    @Test fun clientUsesRemoteIntentAndIgnoresOnlyEmptyTransientEvents() {
        val s = source("car/core-bridge/src/main/java/com/dbtool/carplay/bridge/media/CarPlayNowPlayingClient.kt")
        assertTrue(s.contains("readSnapshot(player)?.let(::publish)"))
        assertTrue(s.contains("readSnapshot(connected)?.let(::publish)"))
        assertTrue(s.contains("player.playWhenReady"))
        assertTrue(s.contains("publish(null)")) // explicit teardown remains
    }
    @Test fun ecarxRefreshesActualByteChangesNotEveryLyric() {
        val s = source("car/platform-ecarx/src/main/java/com/dbtool/carplay/ecarx/media/EcarxCarMediaAdapter.kt")
        assertTrue(s.contains("!artwork.contentEquals(lastArtwork)"))
        assertTrue(s.contains("refreshCover = artworkChanged"))
        assertTrue(s.contains("trackChanged = queueContinuity.observe(nowPlaying)"))
        assertFalse(s.contains("coverServer.updateCover(null"))
        assertTrue(s.contains("if (broadcaster === fd) onNowPlaying(fd, nowPlaying)"))
        assertTrue(s.contains("trackChanged = trackChanged"))
        val broadcaster = source("car/platform-ecarx/src/main/java/com/dbtool/carplay/ecarx/media/FdBookBroadcaster.kt")
        assertTrue(broadcaster.contains("snapshotChanged = key != lastSentKey || trackRefreshPending"))
        assertTrue(broadcaster.contains(".putExtra(KEY_BOOK_ID, FdBookBroadcastPlan.bookId(itemId))"))
        assertTrue(broadcaster.contains(".putExtra(KEY_FRAGMENT_ID, FdBookBroadcastPlan.fragmentId(itemId))"))
        val coverUpdate = broadcaster.substringAfter("fun onCoverUpdated(coverUrl: String)").substringBefore("private fun scheduleDataBroadcast")
        assertTrue(coverUpdate.contains("scheduleDataBroadcast()"))
    }
    @Test fun releaseCancelsAndRejectsLateObserverOrCoverCallbacks() {
        val s = source("car/platform-ecarx/src/main/java/com/dbtool/carplay/ecarx/media/FdBookBroadcaster.kt")
        val release = s.substringAfter("fun release() {").substringBefore("\n    }")
        for (statement in listOf("released = true", "handler.removeCallbacks(debouncedSend)",
            "trackRefreshPending = false", "coverRefreshPending = false", "metadataRefreshPending = false",
            "dataPendingSince = 0L", "lastSentKey = \"\"", "lastSentCoverUrl = null", "lastSentAt = 0L")) {
            assertTrue(statement, release.contains(statement))
        }
        assertTrue(s.substringAfter("fun onCoverUpdated(coverUrl: String) {").startsWith("\n        if (released) return"))
        assertTrue(s.contains("if (active || released) return"))
        assertTrue(s.contains("if (released) return@Runnable"))
        assertTrue(s.substringAfter("trackChanged: Boolean = false,").substringBefore("if (title.isBlank())").contains("if (released) return"))
    }
}
