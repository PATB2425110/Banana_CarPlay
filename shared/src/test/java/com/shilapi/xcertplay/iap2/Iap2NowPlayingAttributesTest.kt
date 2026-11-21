package com.shilapi.xcertplay.iap2

import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.message.Iap2NowPlayingAccumulator
import com.shilapi.xcertplay.media.shouldPublishArtwork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Iap2NowPlayingAttributesTest {
    @Test
    fun describeAttributesListsMediaAndPlaybackGroupsWithTruncatedPreview() {
        val longTitle = "歌词标题" + "重复歌词".repeat(12)
        val frame = nowPlayingFrame(title = longTitle, album = "专辑名", artist = "歌手名")

        val dump = Iap2NowPlayingAccumulator().describeAttributes(frame)

        assertTrue(dump, dump.contains("g0[id=1 str("))
        assertTrue(dump, dump.contains("g0[id=6 str("))
        assertTrue(dump, dump.contains("g0[id=12 str("))
        assertTrue(dump, dump.contains("g0[id=26 u8=5]"))
        assertTrue(dump, dump.contains("g1[id=0 u8=1]"))
        assertTrue(dump.length <= 500)

        val titlePreview = previewOf(dump, "g0[id=1 str(")
        assertEquals(48, titlePreview.length)
        assertTrue(titlePreview.endsWith("…"))
        assertEquals("专辑名", previewOf(dump, "g0[id=6 str("))
        assertEquals("歌手名", previewOf(dump, "g0[id=12 str("))
    }

    @Test
    fun describeAttributesCapsTheWholeDumpAt500Characters() {
        val frame = Iap2Messages.buildRaw(0x5001) {
            group(0) {
                repeat(20) { string(1, "歌".repeat(60)) }
            }
        }

        val dump = Iap2NowPlayingAccumulator().describeAttributes(frame)

        assertEquals(500, dump.length)
        assertTrue(dump.endsWith("…"))
    }

    @Test
    fun existingTitleAlbumArtistMappingIsUnchanged() {
        val accumulator = Iap2NowPlayingAccumulator { 0L }
        val title = "音乐设计：王皓@WONDERWALL"
        val album = "Shall We Dance"
        val artist = "王皓"

        val state = accumulator.update(
            nowPlayingFrame(title = title, album = album, artist = artist),
        )

        assertEquals("音乐设计：王皓@WONDERWALL", state.title)
        assertEquals("Shall We Dance", state.album)
        assertEquals("王皓", state.artist)
        assertEquals(180_000L, state.durationMillis)
        assertEquals(5, state.artworkFileTransferId)
    }

    @Test
    fun artworkIsAcceptedWhenItsIdDoesNotMatchTheCurrentTrack() {
        assertTrue(shouldPublishArtwork(incomingId = 129, currentTrackId = 7, hasSameIdCached = false))
        assertTrue(shouldPublishArtwork(incomingId = 129, currentTrackId = 7, hasSameIdCached = true))
        assertTrue(shouldPublishArtwork(incomingId = 129, currentTrackId = null, hasSameIdCached = false))
        assertTrue(shouldPublishArtwork(incomingId = 129, currentTrackId = 129, hasSameIdCached = false))
        assertFalse(shouldPublishArtwork(incomingId = 129, currentTrackId = 129, hasSameIdCached = true))
    }

    private fun previewOf(dump: String, marker: String): String =
        dump.substringAfter(marker).substringBefore("]").substringAfter("=\"").substringBeforeLast("\"")

    private fun nowPlayingFrame(
        title: String,
        album: String,
        artist: String,
    ) = Iap2Messages.buildRaw(0x5001) {
        group(0) {
            string(1, title)
            u32(4, 180_000)
            string(6, album)
            string(12, artist)
            u8(26, 5)
        }
        group(1) {
            u8(0, 1)
            u32(1, 12_000)
            u32(2, 2)
            u32(3, 10)
            string(7, "QQ音乐")
        }
    }
}
