package com.dbtool.carplay.bridge

import com.dbtool.carplay.api.audio.CarAudioStreamStatus
import java.util.concurrent.ConcurrentHashMap

/** One registry per media sink/connection. No defaults, retained history or device information. */
internal class ActiveAudioStreams(private val changed: () -> Unit = {}) {
    private val streams = ConcurrentHashMap<Pair<Boolean, Int>, CarAudioStreamStatus>()
    fun start(stream: CarAudioStreamStatus) {
        streams[stream.microphone to stream.type] = stream
        changed()
    }
    fun stop(type: Int, microphone: Boolean = false) {
        streams.remove(microphone to type)
        changed()
    }
    fun snapshot(): List<CarAudioStreamStatus> =
        streams.values.sortedWith(compareBy({ it.microphone }, { it.type }))
}
