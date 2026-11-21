package com.dbtool.carplay.bridge.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationAudioFocusStateTest {
    private val events = mutableListOf<String>()
    private fun state(enabled: Boolean = true, granted: () -> Boolean = { true }) =
        NavigationAudioFocusState(enabled, acquire = {
            events += "acquire"
            granted()
        }, release = { events += "release" })

    @Test fun defaultEnabledMatchesSpeechLifecycleAndIsIdempotent() {
        val state = state()
        state.setSpeechActive(true)
        state.setSpeechActive(true)
        state.setEnabled(true)
        state.setSpeechActive(false)
        state.setSpeechActive(false)
        assertEquals(listOf("acquire", "release"), events)
    }

    @Test fun disablingReleasesAndReenablingActiveSpeechRestores() {
        val state = state()
        state.setSpeechActive(true)
        state.setEnabled(false)
        state.setEnabled(false)
        state.setEnabled(true)
        state.setSpeechActive(false)
        assertEquals(listOf("acquire", "release", "acquire", "release"), events)
    }

    @Test fun disabledStillTracksSpeechButDoesNotRequestFocus() {
        val state = state(enabled = false)
        state.setSpeechActive(true)
        assertEquals(emptyList<String>(), events)
        state.setEnabled(true)
        assertEquals(listOf("acquire"), events)
    }

    @Test fun completedSpeechWhileDisabledIsNotRestored() {
        val state = state()
        state.setSpeechActive(true)
        state.setEnabled(false)
        state.setSpeechActive(false)
        state.setEnabled(true)
        assertEquals(listOf("acquire", "release"), events)
    }

    @Test fun enablingWhileIdleDoesNotAcquire() {
        val state = state(enabled = false)
        state.setEnabled(true)
        assertEquals(emptyList<String>(), events)
    }

    @Test fun failedRequestIsNotHeldAndCanRetry() {
        var granted = false
        val state = state(granted = { granted })
        state.setSpeechActive(true)
        state.setEnabled(false)
        granted = true
        state.setEnabled(true)
        state.close()
        assertEquals(listOf("acquire", "acquire", "release"), events)
    }

    @Test fun closeReleasesAndRejectsLateSpeechAndSettings() {
        val state = state()
        state.setSpeechActive(true)
        state.close()
        state.close()
        state.setSpeechActive(true)
        state.setEnabled(false)
        state.setEnabled(true)
        assertEquals(listOf("acquire", "release"), events)
    }

    @Test fun concurrentSettingAndSpeechChangesCannotLeakFocus() {
        val state = state()
        val workers = listOf(
            Thread { repeat(200) { state.setSpeechActive(it % 2 == 0) } },
            Thread { repeat(200) { state.setEnabled(it % 2 == 0) } },
        )
        workers.forEach { it.start() }
        workers.forEach { it.join(5_000); check(!it.isAlive) }
        state.close()
        assertEquals(events.count { it == "acquire" }, events.count { it == "release" })
        events.forEachIndexed { index, event ->
            assertEquals(if (index % 2 == 0) "acquire" else "release", event)
        }
    }
}
