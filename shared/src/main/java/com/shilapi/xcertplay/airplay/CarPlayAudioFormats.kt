package com.shilapi.xcertplay.airplay

/**
 * Adapted from xcertplay 1.4.1 CarPlayAudioFormats/AudioQualityProfiles (GPL-3.0).
 * Music type 100 retains legacy PCM rates/channels alongside stereo 44.1/48k.
 * Type 102 offers stereo AAC-LC 44.1/48k, or opt-in experimental stereo PCM.
 * Voice stays on LPCM for wired and Opus for wireless; no unimplemented AAC-ELD offer.
 */
internal object CarPlayAudioFormats {
    private val pcm = listOf(
        Triple(2, 8000, 1), Triple(3, 8000, 2),
        Triple(4, 16000, 1), Triple(5, 16000, 2),
        Triple(6, 24000, 1), Triple(7, 24000, 2),
        Triple(8, 32000, 1), Triple(9, 32000, 2),
        Triple(10, 44100, 1), Triple(11, 44100, 2),
        Triple(14, 48000, 1), Triple(15, 48000, 2),
    )
    fun build(q: AudioQualitySettings, microphone: Boolean, wireless: Boolean, lpcm102: Boolean): List<Map<String, Any?>> {
        fun mask(formats: List<Triple<Int, Int, Int>>, rates: Set<Int>, limit: Int, channels: Int) =
            formats.filter { it.second in rates && it.second <= limit && it.third <= channels }
                .fold(0) { bits, f -> bits or (1 shl f.first) }
        val musicRate = q.musicRate.takeIf { it in setOf(44100, 48000) } ?: 48000
        val musicPcm = mask(pcm.filter { it.third == 2 }, setOf(44100, 48000), musicRate, 2)
        val musicAac = mask(listOf(Triple(22, 44100, 2), Triple(23, 48000, 2)), setOf(44100, 48000), musicRate, 2)
        val voiceFormats = if (wireless) listOf(Triple(28, 16000, 1), Triple(29, 24000, 1), Triple(30, 48000, 1)) else pcm
        // As upstream, unsupported transport/rate combinations fall back to a usable ceiling,
        // rather than emitting an empty route (e.g. 44.1k nav ceiling with 48k-only wireless Opus).
        fun voice(rates: Set<Int>, ceiling: Int, channels: Int): Int {
            val allowed = voiceFormats.filter { it.second in rates && it.third <= channels }
            return mask(allowed, rates, ceiling, channels).takeIf { it != 0 }
                ?: allowed.fold(0) { bits, f -> bits or (1 shl f.first) }
        }
        val assistant = voice(if (wireless) setOf(24000, 48000) else setOf(24000, 32000, 44100, 48000), q.assistantRate, 1)
        val navigation = voice(if (wireless) setOf(48000) else setOf(44100, 48000), q.navigationRate, q.navigationChannels.coerceIn(1, 2))
        val phone = voice(if (wireless) setOf(16000, 24000, 48000) else setOf(8000, 16000, 24000, 32000), q.phoneRate, 1)
        val input = if (microphone) voice(if (wireless) setOf(16000) else setOf(8000, 16000), q.microphoneRate.coerceAtMost(16000), 1) else null
        fun entry(type: Int, audioType: String, output: Int, mic: Int? = null): Map<String, Any?> =
            linkedMapOf<String, Any?>("type" to type, "audioType" to audioType, "audioOutputFormats" to output)
                .apply { if (mic != null && mic != 0) put("audioInputFormats", mic) }
        val current = listOf(
            entry(100, "compatibility", assistant, input), entry(101, "compatibility", navigation),
            entry(100, "default", assistant, input), entry(100, "alert", navigation),
            entry(100, "media", musicPcm), entry(100, "telephony", phone, input),
            entry(100, "speechRecognition", assistant, input), entry(101, "default", navigation),
            // This fork's opt-in experiment, not an upstream-guaranteed type-102 format.
            entry(102, "media", if (lpcm102) musicPcm else musicAac),
        )
        // Additive compatibility with this fork's previously working /info.
        // Never replace current capabilities. Only experimental type 102 is excluded
        // from the legacy union: its codec is selected by the user's switch.
        val legacyPcm = 0x3fc or (if (musicRate == 48000) 0xc000 else 0xc00)
        val legacyMono = 0x154 or (if (musicRate == 48000) 0x4000 else 0x400)
        val legacyOpus = if (wireless) 0x70000000 else 0
        return current.map { item ->
            val type = item["type"] as Int
            val audioType = item["audioType"] as String
            if (audioType == "media") {
                return@map if (type == 100) LinkedHashMap(item).apply {
                    put("audioOutputFormats", (item["audioOutputFormats"] as Int) or legacyPcm)
                } else item
            }
            val compatibility = audioType == "compatibility"
            val mono = audioType == "telephony" || audioType == "speechRecognition"
            val oldOutput = (if (mono) legacyMono else legacyPcm) or
                (if (compatibility) 0 else legacyOpus)
            val merged = LinkedHashMap(item)
            merged["audioOutputFormats"] = (item["audioOutputFormats"] as Int) or oldOutput
            if (microphone && type == 100 &&
                audioType in setOf("compatibility", "default", "telephony", "speechRecognition")) {
                val oldInput = legacyMono or (if (compatibility) 0 else legacyOpus)
                merged["audioInputFormats"] = ((item["audioInputFormats"] as? Int) ?: 0) or oldInput
            }
            merged
        }
    }
}
