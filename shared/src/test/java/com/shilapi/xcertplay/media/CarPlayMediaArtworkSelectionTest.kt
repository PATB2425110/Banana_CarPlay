package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.iap2.message.Iap2NowPlayingState
import com.shilapi.xcertplay.iap2.message.Iap2PlaybackStatus
import com.shilapi.xcertplay.iap2.session.Iap2FileTransferHandler
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Field

/** Exercises the production bridge without Context/startService or Android Binder.
 * Only owner setup is reflected; all metadata, transfer caching and observer calls are real.
 */
class CarPlayMediaArtworkSelectionTest {
    private val owner = Any()
    private lateinit var cache: NowPlayingArtworkCache
    private val saved = mutableMapOf<Field, Any?>()
    private var observed = CarPlayMediaSnapshot()

    @Before fun prepareOwner() {
        val type = CarPlayMediaSessionBridge.javaClass
        for (name in listOf("owner", "snapshot", "observer")) {
            val field = type.getDeclaredField(name).apply { isAccessible = true }
            saved[field] = field.get(CarPlayMediaSessionBridge)
            field.set(CarPlayMediaSessionBridge, when (name) {
                "owner" -> owner
                "snapshot" -> CarPlayMediaSnapshot()
                else -> null
            })
        }
        cache = type.getDeclaredField("artworkCache").apply { isAccessible = true }
            .get(CarPlayMediaSessionBridge) as NowPlayingArtworkCache
        cache.clear()
        CarPlayMediaSessionBridge.observe { observed = it }
    }

    @After fun restoreOwner() {
        cache.clear()
        for ((field, value) in saved) field.set(CarPlayMediaSessionBridge, value)
    }

    private fun metadata(id: Int?, queue: Long) = Iap2NowPlayingState(
        title = "歌词$queue", artist = "歌手", album = "专辑", queueIndex = queue,
        artworkFileTransferId = id, status = Iap2PlaybackStatus.PLAYING,
    )

    @Test fun cachedSetupOnReturningToASelectsAWithoutAnotherTransfer() {
        CarPlayMediaSessionBridge.publish(owner, metadata(1, 1))
        CarPlayMediaSessionBridge.publishArtwork(owner, 1, byteArrayOf(1, 1))
        val coverA = observed.artwork
        CarPlayMediaSessionBridge.publish(owner, metadata(2, 2))
        CarPlayMediaSessionBridge.publishArtwork(owner, 2, byteArrayOf(2, 2))
        assertArrayEquals(byteArrayOf(2, 2), observed.artwork?.data)
        assertTrue("cached metadata can select A before any repeated payload", CarPlayMediaSessionBridge.hasArtwork(owner, 1))
        CarPlayMediaSessionBridge.publish(owner, metadata(1, 1))
        assertSame(coverA, observed.artwork)
        assertArrayEquals(byteArrayOf(1, 1), observed.artwork?.data)
    }

    @Test fun uncachedMetadataIdKeepsVisibleImageUntilPayloadArrives() {
        CarPlayMediaSessionBridge.publish(owner, metadata(1, 1))
        CarPlayMediaSessionBridge.publishArtwork(owner, 1, byteArrayOf(1))
        val cover = observed.artwork
        CarPlayMediaSessionBridge.publish(owner, metadata(99, 2))
        assertSame(cover, observed.artwork)
        assertFalse(CarPlayMediaSessionBridge.hasArtwork(owner, 99))
        CarPlayMediaSessionBridge.publishArtwork(owner, 99, byteArrayOf(99))
        assertArrayEquals(byteArrayOf(99), observed.artwork?.data)
    }

    @Test fun repeatedLyricsAndMissingIdDoNotRollBackPrefetchedArtwork() {
        val state = NowPlayingArtworkCache()
        state.accept(1, byteArrayOf(1))
        state.selectForMetadata(1)
        val prefetched = state.accept(2, byteArrayOf(2))
        assertNull(state.selectForMetadata(null))
        assertNull(state.selectForMetadata(1))
        assertSame(prefetched, state.selectForMetadata(2))
        state.clear()
        assertNull(state.selectForMetadata(1))
    }

    @Test fun reusedTransferIdAcceptsNewPictureAndIdenticalBytesDoNotRepublish() {
        CarPlayMediaSessionBridge.publish(owner, metadata(7, 1))
        CarPlayMediaSessionBridge.publishArtwork(owner, 7, byteArrayOf(1))
        assertTrue(CarPlayMediaSessionBridge.hasArtwork(owner, 7))
        val receiver = Iap2FileTransferHandler()
        val setup = byteArrayOf(7, 4, 0, 0, 0, 0, 0, 0, 0, 1, 0, 2)
        assertArrayEquals(byteArrayOf(7, 1), receiver.handle(setup).response)
        val received = requireNotNull(receiver.handle(byteArrayOf(7, 0xc0.toByte(), 2)).artwork)
        CarPlayMediaSessionBridge.publishArtwork(owner, received.fileTransferId, received.bytes)
        assertArrayEquals(byteArrayOf(2), observed.artwork?.data)
        val displayed = observed.artwork
        CarPlayMediaSessionBridge.publishArtwork(owner, 7, byteArrayOf(2))
        assertSame(displayed, observed.artwork)
    }
}
