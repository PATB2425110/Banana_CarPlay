package com.shilapi.xcertplay.media

import android.media.MediaRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicrophoneAudioSourceTest {
    @Test
    fun telephonyUsesVoiceCommunication() {
        assertEquals(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            chooseMicrophoneSource("telephony"),
        )
    }

    @Test
    fun speechRecognitionUsesVoiceRecognition() {
        assertEquals(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            chooseMicrophoneSource("speechrecognition"),
        )
    }

    @Test
    fun defaultEmptyAndNullUseMic() {
        assertEquals(MediaRecorder.AudioSource.MIC, chooseMicrophoneSource("default"))
        assertEquals(MediaRecorder.AudioSource.MIC, chooseMicrophoneSource(""))
        assertEquals(MediaRecorder.AudioSource.MIC, chooseMicrophoneSource(null))
    }

    @Test
    fun mixedCaseTelephonyUsesVoiceCommunication() {
        assertEquals(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            chooseMicrophoneSource("Telephony"),
        )
    }

    @Test
    fun paddedMixedCaseTelephonyUsesVoiceCommunication() {
        assertEquals(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            chooseMicrophoneSource(" teLEphony "),
        )
    }

    @Test
    fun onlyTelephonyAttachesVoiceEffects() {
        assertTrue(shouldAttachVoiceEffects("telephony"))
        assertTrue(shouldAttachVoiceEffects(" Telephony "))
        assertFalse(shouldAttachVoiceEffects("speechrecognition"))
        assertFalse(shouldAttachVoiceEffects("default"))
        assertFalse(shouldAttachVoiceEffects(null))
    }
}
