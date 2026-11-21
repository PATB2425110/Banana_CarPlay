package com.dbtool.carplay.api.media

import com.dbtool.carplay.api.audio.CarAudioChannel
import com.dbtool.carplay.api.audio.CarAudioFormat
import com.dbtool.carplay.api.display.CarDisplay

/** 视频编码类型。与 upstream AirPlay 协商结果一致。 */
enum class CarVideoCodec {
    H264,
    HEVC,
}

/**
 * 媒体适配：把 CarPlay 的"流生命周期"广播给车机媒体系统。
 *
 * 边界很重要：
 * - **不做** 解码与渲染。视频 `MediaCodec → Surface`、音频 `MediaCodec → AudioTrack`
 *   仍由 Core 的 `AndroidMediaSink` 负责，本适配器只旁路通知。
 * - 这样设计的原因：车机媒体系统真正需要的是"现在有导航在播、媒体在播、该抢谁的焦点、
 *   该显示谁的悬浮控件 / 桌面歌词"，而不是自己去解码。
 * - 若某个车机确实需要接管解码（例如走硬件视频层），那是后续 `modules/media`
 *   替换 `MediaSink` 的工作，届时通过 [CarVideoOutputRequest] 表达，而不是改 Core。
 *
 * 默认实现全部为空操作，保证最小实现成本。
 */
interface CarMediaAdapter {
    /** 视频流开始/结束。车机可据此显示/隐藏视频层或切换输入源。 */
    fun onVideoStreamActive(display: CarDisplay, codec: CarVideoCodec, active: Boolean) {}

    /** 音频流开始/结束。车机可据此驱动桌面歌词、媒体卡片、音源切换。 */
    fun onAudioStreamActive(
        channel: CarAudioChannel,
        format: CarAudioFormat?,
        active: Boolean,
    ) {
    }

    /**
     * 车机媒体系统希望接管视频输出时返回非 null。
     * 返回 null（默认）表示沿用 Core 自带的 Surface 渲染。
     *
     * 当前阶段 Core 尚未开放该接缝，见 PATCHES.md PATCH-0003（PROPOSED）。
     */
    fun requestVideoOutput(display: CarDisplay): CarVideoOutputRequest? = null

    /**
     * 注入「当前正在播放」的数据源。
     *
     * ## 为什么是"注入"而不是让车机自己去读
     *
     * 数据在**上游**（iAP2 → Media3 MediaSession），而只有 `:car:core-bridge`
     * 允许 import `com.shilapi.xcertplay.*`。所以由它实现 [CarNowPlayingSource]、
     * 在产品 App 装配时注入进来；`:car:platform-*` 只消费 [CarNowPlaying]，
     * 不认识 media3，也不认识上游。
     *
     * ## 默认实现是空操作
     *
     * 只有真的要把元数据/封面推给车机系统（如 Ecarx 的仪表盘卡片）的平台才覆写它。
     * 其余平台保持默认，不会因为多了一个空方法而被迫写死代码。
     *
     * 调用方应当在会话建立时调一次、会话结束时用返回的句柄取消。
     *
     * @return 取消订阅；平台不关心时返回一个空操作。
     */
    fun attachNowPlaying(source: CarNowPlayingSource): () -> Unit = {}
}

/**
 * 车机侧请求接管视频输出的描述。
 *
 * 保留为接口而不是 Surface/View 类型，避免 `:car:api` 依赖 Android。
 * 具体实现（Flyme/OSN 的硬件视频层）在各自 adapter 模块里向下转型。
 */
interface CarVideoOutputRequest {
    /** 车机视频层标识，例如 `flyme.hwc.video.0`。 */
    val layerId: String

    /** 期望的视频格式。 */
    val preferredCodec: CarVideoCodec

    /** 期望的分辨率上限，null 表示不限。 */
    val maxWidth: Int?
    val maxHeight: Int?
}
