package com.dbtool.carplay.bridge

import com.dbtool.carplay.api.audio.CarAudioStreamStatus
import org.junit.Assert.*
import org.junit.Test

class ActiveAudioStreamTest {
    private fun observer(changed: () -> Unit = {}) = ActiveAudioStreams(changed)

    @Test fun noStreamMeansEmptyNotAdvertisedDefault() {
        assertTrue(observer().snapshot().isEmpty())
    }
    @Test fun actualFormatsFollowStartsReplacementsAndStopsIndependently() {
        var changes = 0
        val sink = observer { changes++ }
        sink.start(CarAudioStreamStatus(102, "media", "AAC_LC", 48000, 2))
        sink.start(CarAudioStreamStatus(101, "default", "OPUS", 48000, 1))
        assertEquals(2, sink.snapshot().size)
        sink.start(CarAudioStreamStatus(102, "media", "LPCM", 44100, 2))
        assertEquals("LPCM", sink.snapshot().single { it.type == 102 }.codec)
        assertEquals(44100, sink.snapshot().single { it.type == 102 }.sampleRate)
        sink.stop(101)
        assertEquals(1, sink.snapshot().size)
        sink.stop(102)
        assertTrue(sink.snapshot().isEmpty())
        assertEquals(5, changes)
        assertTrue(observer().snapshot().isEmpty()) // new connection has no stale state
    }
    @Test fun microphoneAndOutputOfSameTypeHaveSeparateLifecycle() {
        val sink = observer()
        sink.start(CarAudioStreamStatus(100, "speechrecognition", "OPUS", 48000, 1))
        sink.start(CarAudioStreamStatus(100, "speechrecognition", "OPUS", 16000, 1, microphone = true))
        assertEquals(2, sink.snapshot().size)
        sink.stop(100)
        assertTrue(sink.snapshot().single().microphone)
        sink.stop(100, microphone = true)
        assertTrue(sink.snapshot().isEmpty())
    }
    @Test fun compressedAudioNeverClaimsSourceBitDepth() {
        assertEquals("LPCM／44.1kHz／16位／双声道 · type=102",
            CarAudioStreamStatus(102, "media", "LPCM", 44100, 2).description())
        assertEquals("AAC_LC／48kHz／双声道 · type=102",
            CarAudioStreamStatus(102, "media", "AAC_LC", 48000, 2).description())
    }
}
