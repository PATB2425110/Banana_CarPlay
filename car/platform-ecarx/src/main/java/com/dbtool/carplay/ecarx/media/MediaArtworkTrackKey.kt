package com.dbtool.carplay.ecarx.media

import com.dbtool.carplay.api.media.CarNowPlaying

/** Only two known, different queue numbers prove a legacy refresh is needed. */
internal class MediaQueueContinuity {
    private var knownQueue: Int? = null

    fun observe(now: CarNowPlaying): Boolean {
        val queue = now.trackNumber ?: return false
        val changed = knownQueue != null && knownQueue != queue
        knownQueue = queue
        return changed
    }

    fun reset() { knownQueue = null }
}
