package com.dbtool.carplay.android

import com.dbtool.carplay.api.audio.CarAudioAdapter
import com.dbtool.carplay.api.audio.CarAudioChannel
import com.dbtool.carplay.api.audio.CarAudioContentType
import com.dbtool.carplay.api.audio.CarAudioFormat
import com.dbtool.carplay.api.audio.CarAudioRoute
import com.dbtool.carplay.api.audio.CarAudioUsage

/**
 * Android 默认音频路由。
 *
 * 与 upstream `AudioChannelMapping.AudioChannelMapper` 的 `MOBILE_COMPATIBLE` 档位
 * 保持同一套语义，保证默认行为与上游一致：
 *
 * | CarPlay 通道 | usage | contentType |
 * | --- | --- | --- |
 * | VOICE (telephony) | PHONE | SPEECH |
 * | ASSISTANT (speechrecognition) | ASSISTANT | SPEECH |
 * | MEDIA | MEDIA | MUSIC |
 * | NAVIGATION (alert) / COMPATIBILITY | NAVIGATION | SPEECH |
 *
 * 车机适配点：需要走独立音频总线（Flyme Auto / OSN 的导航总线、媒体总线）时，
 * 覆写 [routeFor] 并填 [CarAudioRoute.busId]。
 */
open class AndroidCarAudioAdapter : CarAudioAdapter {

    override fun routeFor(channel: CarAudioChannel): CarAudioRoute = when (channel) {
        CarAudioChannel.VOICE -> CarAudioRoute(CarAudioUsage.PHONE, CarAudioContentType.SPEECH)
        CarAudioChannel.ASSISTANT -> CarAudioRoute(CarAudioUsage.ASSISTANT, CarAudioContentType.SPEECH)
        CarAudioChannel.MEDIA -> CarAudioRoute(CarAudioUsage.MEDIA, CarAudioContentType.MUSIC)
        CarAudioChannel.NAVIGATION -> CarAudioRoute(CarAudioUsage.NAVIGATION, CarAudioContentType.SPEECH)
        CarAudioChannel.COMPATIBILITY -> CarAudioRoute(CarAudioUsage.NAVIGATION, CarAudioContentType.SPEECH)
    }

    /**
     * 默认 false：走 upstream 的 mobile 兼容档，行为与上游原版一致。
     * AAOS / 车机需要"alert 不占用导航总线"的分档行为时覆写为 true。
     */
    override fun supportsAdvancedChannelMapping(): Boolean = false

    override fun onStreamStarted(channel: CarAudioChannel, format: CarAudioFormat) {}

    override fun onStreamStopped(channel: CarAudioChannel) {}
}
