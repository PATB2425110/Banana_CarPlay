package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class MediaOutputGainTest {
    @Test fun activeOutputIsMutedSynchronously() {
        val gate = MediaOutputGain(); var volume = 1f
        gate.attach("media") { volume = it }
        assertTrue(gate.set(0f)); assertEquals(0f, volume)
    }
    @Test fun tracksCreatedDuringLossStartMuted() {
        val gate = MediaOutputGain(); gate.set(0f); var volume = 1f
        gate.attach("new") { volume = it }; assertEquals(0f, volume)
    }
    @Test fun communicationRecoveryCannotBypassLoss() {
        val gate = MediaOutputGain(); var volume = 1f
        gate.attach("media") { volume = it }; gate.set(0f)
        gate.refresh("media"); assertEquals(0f, volume)
    }
    @Test fun gainAndDuckApplyOnlyRegisteredMediaOutputs() {
        val gate = MediaOutputGain(); var a = 0f; var b = 0f; val call = 1f
        gate.attach("a") { a = it }; gate.attach("b") { b = it }
        gate.set(0.5f); assertEquals(0.5f, a); assertEquals(0.5f, b); assertEquals(1f, call)
        gate.set(1f); assertEquals(1f, a)
    }
    @Test fun detachedOutputIsNeverRestored() {
        val gate = MediaOutputGain(); var volume = 1f
        gate.attach("old") { volume = it }; gate.set(0f); gate.detach("old")
        gate.set(1f); assertEquals(0f, volume)
    }
    @Test fun failingOutputDoesNotBlockOtherOutputsOrLatch() {
        val gate = MediaOutputGain(); gate.attach("broken") { error("released") }; var volume = 1f
        gate.attach("ok") { volume = it }; assertFalse(gate.set(0f)); assertEquals(0f, volume)
        assertEquals(0f, gate.current())
    }
    @Test fun repeatedMuteAndExplicitGainRemainIdempotent() {
        val gate = MediaOutputGain(); gate.set(0f); gate.set(0f)
        assertEquals(0f, gate.current()); gate.set(1f); assertEquals(1f, gate.current())
    }
    @Test fun invalidGainCannotChangeOutput() {
        val gate = MediaOutputGain()
        for (v in listOf(-1f, 2f, Float.NaN)) { try { gate.set(v); fail() } catch (_: IllegalArgumentException) {} }
        assertEquals(1f, gate.current())
    }
}
