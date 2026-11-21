package com.dbtool.carplay.android

import com.dbtool.carplay.api.microphone.CarMicrophoneAdapter
import com.dbtool.carplay.api.microphone.CarMicrophoneSource

/**
 * 默认麦克风适配：按 CarPlay 通道选采集源。
 *
 * 与 upstream `MicrophoneUplink` 的选择口径一致：
 * - `telephony`（通话）→ 通话链路，带回声消除
 * - `speechrecognition`（语音助手）→ 语音识别优化
 * - 其它 → 默认源
 *
 * 采集与 Opus 编码仍由 Core 完成，本适配器只影响"选哪路 mic"。
 */
open class AndroidCarMicrophoneAdapter : CarMicrophoneAdapter {

    override val available: Boolean = true

    override fun preferredSource(channelName: String): CarMicrophoneSource =
        when (channelName.lowercase()) {
            "telephony" -> CarMicrophoneSource.VOICE_COMMUNICATION
            "speechrecognition" -> CarMicrophoneSource.VOICE_RECOGNITION
            else -> CarMicrophoneSource.DEFAULT
        }

    override fun onCaptureStarting(channelName: String): Boolean = true

    override fun onCaptureStarted(channelName: String) {}

    override fun onCaptureStopped(channelName: String) {}
}
