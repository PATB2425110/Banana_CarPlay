package com.shilapi.xcertplay.media

import android.media.AudioTrack
import java.util.concurrent.ConcurrentHashMap

/** Read-only view of this process's live tracks, never a claim about HAL/DAC format. */
object ActiveAudioTrackProbe {
    private val tracks = ConcurrentHashMap<AudioTrack, String>()

    internal fun add(track: AudioTrack, label: String) { tracks[track] = label }
    internal fun remove(track: AudioTrack) { tracks.remove(track) }

    fun snapshot(): List<String> = tracks.entries.map { (track, label) ->
        runCatching {
            val device = track.routedDevice
            "$label：AudioTrack ${track.sampleRate}Hz／encoding=${track.audioFormat}" +
                "／${track.channelCount}声道／session=${track.audioSessionId}" +
                "／播放状态=${track.playState}／输出设备=${device?.type ?: "未路由"}" +
                "（id=${device?.id ?: "未知"}）"
        }.getOrElse { "$label：音轨已退出或无法读取（${it.javaClass.simpleName}）" }
    }.sorted()
}
