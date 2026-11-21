package com.shilapi.xcertplay.media

import android.media.AudioManager

internal enum class AudioChannelMappingMode {
    MOBILE_COMPATIBLE,
    AUTOMOTIVE_BUS,
}

internal enum class AudioChannel {
    MEDIA,
    PHONE,
    ASSISTANT,
    NAVIGATION,
    RINGTONE,
}

internal enum class AudioContentType {
    MUSIC,
    SPEECH,
}

internal data class AudioChannelSelection(
    val channel: AudioChannel,
    val contentType: AudioContentType,
    /**
     * 走老式 streamType 建轨时要用的系统流号。
     *
     * 当前 PATCH-0013 里**没有任何通道**通过它传流号：媒体/导航的流号都由用户在
     * 设置页选定，经 `mediaAudioChannel` / `navigationAudioChannel` 直接传进渲染器
     * （见 [AudioChannelOverride]）。本字段保留默认值，仅作为 [AudioChannelMapper]
     * 在自动路由下给出的「建议流号」，供将来若要让导航默认落到提示流时使用。
     */
    val streamType: Int = AudioManager.STREAM_MUSIC,
)

/**
 * Maps CarPlay stream metadata to Android AudioAttributes values.
 *
 * AAOS routes AudioTrack instances by usage, so the automotive mode keeps generic media streams
 * on the media bus instead of reusing mobile's navigation fallback for default/compatibility.
 */
internal object AudioChannelMapper {
    const val STREAM_TYPE_MAIN_HIGH_AUDIO = 102

    /**
     * 导航提示音默认使用的老式系统流号。
     *
     * 与上游 `AudioManager.STREAM_MUSIC`(3) 不同：导航提示在多数车机上走的是
     * 独立的提示流（14 = `STREAM_SYSTEM_ENFORCED` 一类的车机提示通道），
     * 这样才不会被媒体流一起路由走。DBPlay PATCH-0013 起可从设置页覆盖。
     */
    const val DEFAULT_NAVIGATION_STREAM_TYPE = AudioManager.STREAM_MUSIC

    fun map(
        audioType: String,
        payloadType: Int,
        mode: AudioChannelMappingMode,
        navigationStreamType: Int = DEFAULT_NAVIGATION_STREAM_TYPE,
    ): AudioChannelSelection {
        val normalized = audioType.lowercase()
        return when (mode) {
            AudioChannelMappingMode.MOBILE_COMPATIBLE ->
                mapMobileCompatible(normalized, payloadType, navigationStreamType)
            AudioChannelMappingMode.AUTOMOTIVE_BUS ->
                mapAutomotiveBus(normalized, payloadType, navigationStreamType)
        }
    }

    private fun mapMobileCompatible(
        audioType: String,
        payloadType: Int,
        navigationStreamType: Int,
    ): AudioChannelSelection = when (audioType) {
        "telephony" -> AudioChannelSelection(AudioChannel.PHONE, AudioContentType.SPEECH)
        "speechrecognition" ->
            AudioChannelSelection(AudioChannel.ASSISTANT, AudioContentType.SPEECH)
        "media" -> AudioChannelSelection(AudioChannel.MEDIA, AudioContentType.MUSIC)
        "ringtone", "ring" ->
            AudioChannelSelection(AudioChannel.RINGTONE, AudioContentType.SPEECH)
        "default", "alert", "compatibility" ->
            AudioChannelSelection(AudioChannel.NAVIGATION, AudioContentType.SPEECH, navigationStreamType)
        else -> mainHighAudioOrNavigation(payloadType, navigationStreamType)
    }

    private fun mapAutomotiveBus(
        audioType: String,
        payloadType: Int,
        navigationStreamType: Int,
    ): AudioChannelSelection = when (audioType) {
        "telephony" -> AudioChannelSelection(AudioChannel.PHONE, AudioContentType.SPEECH)
        "speechrecognition" ->
            AudioChannelSelection(AudioChannel.ASSISTANT, AudioContentType.SPEECH)
        "media", "default", "compatibility" ->
            AudioChannelSelection(AudioChannel.MEDIA, AudioContentType.MUSIC)
        "ringtone", "ring" ->
            AudioChannelSelection(AudioChannel.RINGTONE, AudioContentType.SPEECH)
        "alert" -> AudioChannelSelection(AudioChannel.NAVIGATION, AudioContentType.SPEECH, navigationStreamType)
        else -> mainHighAudioOrNavigation(payloadType, navigationStreamType)
    }

    private fun mainHighAudioOrNavigation(
        payloadType: Int,
        navigationStreamType: Int,
    ): AudioChannelSelection =
        if (payloadType == STREAM_TYPE_MAIN_HIGH_AUDIO) {
            AudioChannelSelection(AudioChannel.MEDIA, AudioContentType.MUSIC)
        } else {
            AudioChannelSelection(AudioChannel.NAVIGATION, AudioContentType.SPEECH, navigationStreamType)
        }
}
