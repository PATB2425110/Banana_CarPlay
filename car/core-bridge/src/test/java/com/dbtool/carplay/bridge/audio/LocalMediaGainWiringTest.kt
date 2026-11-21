package com.dbtool.carplay.bridge.audio

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Complements gain/guard host behavior tests. Does not execute Android AudioTrack. */
class LocalMediaGainWiringTest {
    private fun source(path: String): String {
        var root = File(requireNotNull(System.getProperty("user.dir")))
        while (!File(root, path).isFile) root = root.parentFile ?: error("missing $path")
        return File(root, path).readText()
    }
    @Test fun gainReachesRealMediaTrackAndIsNotSystemVolume() {
        val slot = source("car/core-bridge/src/main/java/com/dbtool/carplay/bridge/CarPlayMediaSinkSlot.kt")
        assertTrue(slot.contains("delegate.setMediaOutputGain(gain)"))
        val audio = source("shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt")
        assertTrue(audio.contains("created != null && MediaAudioBuffer.isMediaType(format.audioType)"))
        assertTrue(audio.contains("created.setVolume(gain) == AudioTrack.SUCCESS"))
        assertTrue(audio.contains("mediaOutputGain.detach(this)"))
        assertFalse(audio.contains("setStreamVolume("))
    }
    @Test fun callRecoveryCannotRestoreOneOverFocusLoss() {
        val audio = source("shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt")
        val recovery = audio.substringAfter("fun resumeAfterCommunication()").substringBefore("private fun run()")
        assertTrue(recovery.contains("mediaOutputGain.refresh(this)"))
        assertFalse(recovery.contains("setStereoVolume(1f, 1f)"))
    }
}
