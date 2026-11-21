package com.dbtool.carplay.api.audio

/** Observed active stream, not advertised capabilities or proof of speaker/HAL output. */
data class CarAudioStreamStatus(
    val type: Int,
    val audioType: String,
    val codec: String,
    val sampleRate: Int,
    val channels: Int,
    val microphone: Boolean = false,
) {
    fun description(): String {
        val rate = if (sampleRate % 1000 == 0) "${sampleRate / 1000}" else "${sampleRate / 1000.0}"
        val channel = when (channels) { 1 -> "单声道"; 2 -> "双声道"; else -> "${channels}声道" }
        val bits = if (codec == "LPCM") "／16位" else ""
        return "$codec／${rate}kHz$bits／$channel · type=$type"
    }
}
