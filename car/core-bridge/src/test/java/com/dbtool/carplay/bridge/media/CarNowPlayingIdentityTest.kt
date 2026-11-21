package com.dbtool.carplay.bridge.media

import com.dbtool.carplay.api.media.CarNowPlaying
import org.junit.Assert.*
import org.junit.Test

class CarNowPlayingIdentityTest {
    private fun frame(id: String) = CarNowPlaying("歌词", "歌手", "专辑", 180000,
        1000, true, byteArrayOf(1, 2), trackNumber = 1, mediaId = id)

    @Test fun metadataEqualButDifferentRealItemMustStillPublish() {
        assertNotEquals(frame("item-A"), frame("item-B"))
    }

    @Test fun sameItemAndEqualArtworkBytesDoNotInventAChange() {
        assertEquals(frame("item-A"), frame("item-A"))
        assertEquals(frame("item-A").hashCode(), frame("item-A").hashCode())
    }
}
