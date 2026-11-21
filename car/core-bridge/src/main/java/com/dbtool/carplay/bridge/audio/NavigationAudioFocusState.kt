package com.dbtool.carplay.bridge.audio

/** Serializes speech/setting changes without dispatching synchronously to the Android main thread. */
internal class NavigationAudioFocusState(
    enabled: Boolean = true,
    private val acquire: () -> Boolean,
    private val release: () -> Unit,
) {
    private var enabled = enabled
    private var speechActive = false
    private var held = false
    private var closed = false

    @Synchronized
    fun setEnabled(value: Boolean) {
        enabled = value
        reconcile()
    }

    @Synchronized
    fun setSpeechActive(value: Boolean) {
        speechActive = value
        reconcile()
    }

    @Synchronized
    fun close() {
        closed = true
        speechActive = false
        reconcile()
    }

    private fun reconcile() {
        if (!closed && enabled && speechActive) {
            if (!held) held = acquire()
        } else if (held) {
            release()
            held = false
        }
    }
}
