package com.dbtool.carplay.ecarx.media

import com.dbtool.carplay.api.media.CarNowPlaying
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class MediaArtworkTrackKeyTest {
    @Test fun metadataEnrichmentAndArtistVariantCannotManufacturePause() {
        val continuity = MediaQueueContinuity()
        val snapshots = listOf(
            CarNowPlaying("歌词一", "歌手", "", 0, 0, true, byteArrayOf(1)),
            CarNowPlaying("歌词一", "歌手 feat. 合作者", "", 0, 0, true, byteArrayOf(1)),
            CarNowPlaying("歌词一", "歌手 feat. 合作者", "专辑", 0, 0, true, byteArrayOf(1)),
            CarNowPlaying("歌词二", "歌手 feat. 合作者", "专辑", 180000, 1000, true, byteArrayOf(1)),
            CarNowPlaying("歌词三", "歌手", "专辑", 180000, 2000, true, byteArrayOf(1), 1),
        )
        snapshots.forEach { now ->
            assertEquals(listOf(3), FdBookBroadcastPlan.statusSequence(continuity.observe(now), 3, 3))
        }
    }
    @Test fun knownQueueMissingThenSameQueueDoesNotFlip() {
        val continuity = MediaQueueContinuity()
        assertFalse(continuity.observe(playing("歌", track = 1)))
        assertFalse(continuity.observe(playing("歌词", track = null)))
        assertFalse(continuity.observe(playing("歌词二", track = 1)))
    }
    @Test fun onlyKnownQueueTransitionFlipsExactlyOnce() {
        val continuity = MediaQueueContinuity()
        assertFalse(continuity.observe(playing("歌", track = 1)))
        assertEquals(listOf(0, 3), FdBookBroadcastPlan.statusSequence(continuity.observe(playing("歌", track = 2)), 3, 3))
        assertEquals(listOf(3), FdBookBroadcastPlan.statusSequence(continuity.observe(playing("歌词", track = 2)), 3, 3))
    }
    @Test fun pauseStopAndResetRemainAuthoritative() {
        val continuity = MediaQueueContinuity()
        continuity.observe(playing("歌", track = 1))
        assertEquals(listOf(2), FdBookBroadcastPlan.statusSequence(continuity.observe(playing("歌词", track = null)), 2, 3))
        assertEquals(listOf(0), FdBookBroadcastPlan.statusSequence(false, 0, 2))
        continuity.reset()
        assertFalse(continuity.observe(playing("新会话", track = 2)))
    }
    @Test fun lyricAndCoverUpdatesSendOnlyRealStatusButTrueTrackMayUseLegacyRefresh() {
        assertEquals(listOf(3), FdBookBroadcastPlan.statusSequence(false, 3, 3))
        assertEquals(listOf(2), FdBookBroadcastPlan.statusSequence(false, 2, 3))
        assertEquals(listOf(3), FdBookBroadcastPlan.statusSequence(false, 3, 2))
        assertEquals(listOf(0, 3), FdBookBroadcastPlan.statusSequence(true, 3, 3))
        assertEquals(listOf(0, 2), FdBookBroadcastPlan.statusSequence(true, 2, 2))
    }
    private fun playing(title: String, position: Long = 0, track: Int? = 1) =
        CarNowPlaying(title, "artist", "album", 180000, position, true, null, track)

    @Test fun lyricAndProgressDoNotChangeQueueIdentity() {
        val continuity = MediaQueueContinuity()
        assertFalse(continuity.observe(playing("歌词一", 1000)))
        assertFalse(continuity.observe(playing("歌词二", 2000)))
    }
    @Test fun nextTrackChangesIdentityEvenWithSameTitle() {
        val continuity = MediaQueueContinuity()
        assertFalse(continuity.observe(playing("同名歌", track = 1)))
        assertTrue(continuity.observe(playing("同名歌", track = 2)))
    }
    @Test fun noQueueIndexRemainsAmbiguousEvenWhenDurationChanges() {
        val continuity = MediaQueueContinuity()
        assertFalse(continuity.observe(playing("歌词一", track = null)))
        assertFalse(continuity.observe(CarNowPlaying("歌二", "artist", "album", 190000, 0, true, null)))
    }

    @Test fun oldAttachmentCleanupCannotStopTheCurrentArtworkServer() {
        val root = generateSequence(File(".").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val code = File(root, "car/platform-ecarx/src/main/java/com/dbtool/carplay/ecarx/media/EcarxCarMediaAdapter.kt")
            .readText().replace("\r\n", "\n")
            .replace(Regex("""/\*[\s\S]*?\*/|//[^\n]*"""), "")
        val cleanup = code.substringAfter("return {\n            unsubscribe()")
            .substringBefore("private fun onNowPlaying")
        val ownerCheck = cleanup.indexOf("if (broadcaster === fd)")
        assertTrue(ownerCheck >= 0)
        assertTrue(cleanup.indexOf("coverServer.stop()") > ownerCheck)
        assertTrue(cleanup.indexOf("queueContinuity.reset()") > ownerCheck)
    }
}
