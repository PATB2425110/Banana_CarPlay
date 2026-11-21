package com.dbtool.carplay.android

import com.dbtool.carplay.api.audio.CarAudioChannel
import com.dbtool.carplay.api.audio.CarAudioFormat
import com.dbtool.carplay.api.display.CarDisplay
import com.dbtool.carplay.api.media.CarMediaAdapter
import com.dbtool.carplay.api.media.CarVideoCodec

/**
 * 默认媒体适配：只承接"流开始/结束"通知，不做任何车机侧动作。
 *
 * 默认空实现是刻意的：
 * 标准 Android 车机不需要额外做媒体集成，CarPlay 画面/声音由 Core 直接输出。
 * 车机需要驱动桌面歌词、媒体卡片、音源切换时，继承本类覆写对应方法即可。
 */
open class AndroidCarMediaAdapter : CarMediaAdapter {

    override fun onVideoStreamActive(display: CarDisplay, codec: CarVideoCodec, active: Boolean) {}

    override fun onAudioStreamActive(
        channel: CarAudioChannel,
        format: CarAudioFormat?,
        active: Boolean,
    ) {
    }
}
