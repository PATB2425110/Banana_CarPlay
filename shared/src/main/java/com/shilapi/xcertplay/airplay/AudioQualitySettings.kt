package com.shilapi.xcertplay.airplay

/** Per-route advertised ceilings, adapted from shilapi/xcertplay 1.4.1 (GPL-3.0).
 * Opus/AAC-ELD switching is deliberately not exposed: this fork retains its tested voice codecs.
 */
data class AudioQualitySettings(
    val musicRate: Int = 48000,
    val assistantRate: Int = 48000,
    val navigationRate: Int = 48000,
    val phoneRate: Int = 48000,
    val microphoneRate: Int = 16000,
    val navigationChannels: Int = 2,
)
