package com.dbtool.carplay.api.microphone

/** 麦克风采集源偏好。对应车机上可选的几种录音源。 */
enum class CarMicrophoneSource {
    /** 通话链路麦克风（CarPlay 电话场景首选，带回声消除）。 */
    VOICE_COMMUNICATION,

    /** 语音识别优化（Siri / 语音助手场景）。 */
    VOICE_RECOGNITION,

    /** 车机默认麦克风。 */
    DEFAULT,

    /** 车机专有采集源，由 Adapter 自行解释。 */
    PLATFORM_SPECIFIC,
}

/**
 * 麦克风适配。
 *
 * 边界：**不负责采集与编码**。`AudioRecord` 采集与 Opus 编码仍在 Core
 * （upstream `MicrophoneUplink` / `OpusEncoder`）；本适配器只回答
 * "该用哪个采集源、车机是否允许采集、采集期间车机需要做什么"。
 *
 * 之所以这样切：不同车机对麦克风的要求差异集中在"选哪路 mic、要不要先申请
 * 音频模式、要不要抢占 ANC 通道"，而采集/编码是通用的。
 */
interface CarMicrophoneAdapter {
    /** 车机是否具备可用麦克风。false 时 UI 应禁用麦克风开关。 */
    val available: Boolean

    /** 为给定逻辑通道选择采集源。 */
    fun preferredSource(channelName: String): CarMicrophoneSource = CarMicrophoneSource.DEFAULT

    /** 采集开始前调用，返回 false 表示车机拒绝（例如通话占用）。 */
    fun onCaptureStarting(channelName: String): Boolean = true

    fun onCaptureStarted(channelName: String) {}

    fun onCaptureStopped(channelName: String) {}
}
