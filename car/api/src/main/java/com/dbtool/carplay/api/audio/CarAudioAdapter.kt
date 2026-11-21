package com.dbtool.carplay.api.audio

/**
 * CarPlay 侧的逻辑音频通道。
 *
 * 对应 upstream AirPlay 的 audioType：`telephony` / `speechrecognition` / `media` /
 * `alert` / `default` / `compatibility`。这里归一化成车机语义，
 * 具体映射由各车机 Adapter 决定。
 *
 * ⚠️ **2026-09-30 修正了两处枚举名**：原来 `speechrecognition` 叫 `NAVIGATION`、
 * `alert` 叫 `ALERT`，而 [AndroidCarAudioAdapter] 又把它们分别路由到
 * `CarAudioUsage.ASSISTANT` 与 `CarAudioUsage.NAVIGATION`——名字和它实际去的总线
 * 正好互换。改名后 `routeFor` 变成一一同名（`ASSISTANT -> ASSISTANT`、
 * `NAVIGATION -> NAVIGATION`），也与 upstream `AudioChannelMapper` 的口径一致
 * （upstream 把 `alert` 当导航提示、把 `speechrecognition` 当语音助理）。
 *
 * 起因：分通道音量的界面要**逐条显示通道名**，沿用旧名会把"Siri 音量"
 * 标成"导航音量"，用户调错还不知道。改名只动符号名、不动任何行为，
 * 且全仓只有 `routeFor` 一处穷尽式 `when`，编译器可保证没有漏改。
 */
enum class CarAudioChannel {
    /** CarPlay 电话通话声（AirPlay `telephony`）。 */
    VOICE,

    /** Siri / 语音识别（AirPlay `speechrecognition`）。 */
    ASSISTANT,

    /** 音乐与其它媒体（AirPlay `media`）。 */
    MEDIA,

    /** 导航播报与系统提示音（AirPlay `alert`）。 */
    NAVIGATION,

    /** 兼容性通道，AirPlay 的 `default` / `compatibility` 都归到这里。 */
    COMPATIBILITY,
    ;

    companion object {
        /**
         * 把 upstream AirPlay audioType 字符串归一化。
         *
         * 取值口径与 upstream `AudioChannelMapper.mapMobileCompatible` 对齐
         * （`telephony`→电话、`speechrecognition`→语音助理、`media`→媒体、
         * `alert`→导航提示），未识别取值回落到 [COMPATIBILITY]，
         * 与 upstream 的 `else` 分支行为一致。
         */
        fun fromAirPlayAudioType(audioType: String?): CarAudioChannel =
            when (audioType?.lowercase()) {
                "telephony" -> VOICE
                "speechrecognition" -> ASSISTANT
                "media" -> MEDIA
                "alert" -> NAVIGATION
                else -> COMPATIBILITY
            }
    }
}

/**
 * 车机音频 usage，语义与 Android `AudioAttributes.USAGE_*` 对齐但保持平台无关，
 * 由各 Adapter 翻译成目标系统自己的枚举。
 */
enum class CarAudioUsage {
    PHONE,
    ASSISTANT,
    MEDIA,
    NAVIGATION,
    ALARM,
    NOTIFICATION,
    UNKNOWN,
}

/** 车机音频 contentType。 */
enum class CarAudioContentType {
    SPEECH,
    MUSIC,
    SONIFICATION,
    UNKNOWN,
}

/**
 * 一个通道最终要落到车机的哪个音频路由。
 */
data class CarAudioRoute(
    val usage: CarAudioUsage,
    val contentType: CarAudioContentType,
    /**
     * 车机自己的音频总线 / zone / device id。为 null 表示走系统默认。
     * Flyme / OSN / Ecarx 常需要指定具体总线，Android 默认实现返回 null。
     */
    val busId: String? = null,
)

/** 音频流格式（下行）。 */
data class CarAudioFormat(
    val sampleRate: Int,
    val channelCount: Int,
    val codec: String,
)

/**
 * 音频适配：只负责"这条声音该走车机哪条路、车机音频系统是否已经就绪"。
 *
 * 注意边界：**PCM 解码与 AudioTrack 播放不在这里**。
 * 那部分属于 CarPlay Core (upstream `AndroidMediaSink`) 与 media 扩展模块，
 * 本适配器只提供路由决策与状态通知，避免把播放实现散落进各车机 Adapter。
 */
interface CarAudioAdapter {
    /** 为逻辑通道选择车机音频路由。实现必须是无副作用的纯查询。 */
    fun routeFor(channel: CarAudioChannel): CarAudioRoute

    /**
     * 是否启用"高级通道映射"。
     *
     * upstream 有两个映射档位（mobile 兼容档 / automotive 总线档）。
     * 返回 true 表示当前车机走总线档（例如 AAOS、Flyme Auto）。
     */
    fun supportsAdvancedChannelMapping(): Boolean = false

    /**
     * 请求音频焦点。返回 false 表示车机拒绝（例如被系统导航占用），
     * 调用方可据此降级。默认实现不干预。
     */
    fun requestFocus(channel: CarAudioChannel): Boolean = true

    fun abandonFocus(channel: CarAudioChannel) {}

    /** 某个逻辑通道开始有音频流。 */
    fun onStreamStarted(channel: CarAudioChannel, format: CarAudioFormat) {}

    /** 某个逻辑通道音频流结束。 */
    fun onStreamStopped(channel: CarAudioChannel) {}
}
