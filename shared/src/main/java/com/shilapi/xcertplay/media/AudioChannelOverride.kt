package com.shilapi.xcertplay.media

/**
 * 「媒体/导航走哪条音频流」的取值裁决（DBPlay PATCH-0013，思路来自 DiPlay）。
 *
 * ## 为什么单独抽出来
 *
 * 真实建轨代码在 `AndroidMediaSink.AudioRenderer` 里，那里到处都是 Android 类型，
 * 本地单测跑不起来。但「用户选的数字 → 到底要不要覆盖」这一段没有 Android 依赖，
 * 抽出来就能用纯 Kotlin 单测钉住每个分支——而这几种失败模式**不报错、只是没声音**，
 * 正是最需要机器盯着的那类。
 *
 * ⚠️ 本文件是**重写实现**，不是 DiPlay（GPL-3.0）源码的复制。
 */
internal object AudioChannelOverride {

    /** 车机 ROM 的私有流号取值范围（与设置页滑杆一致）。 */
    const val MIN_CHANNEL = 0
    const val MAX_CHANNEL = 20

    /**
     * 导航提示音的第 21 档。不是 streamType，而是
     * `setUsage(12)` + `setContentType(17)`（领克 01）。
     * 媒体声道不接受这个值。
     */
    const val LYNK01_CHANNEL = 21

    /**
     * 只有媒体与导航两条通道开放覆盖：
     * - 通话/Siri 若被乱指定流号，现场表现是「打电话没声」，比音乐没声更难让用户自己回退；
     * - 越界值一律按 0（自动）处理，不让一个手滑的数字把整条音频带走。
     *
     * @return 0 = 按 usage 路由；1..20 = 该老式 streamType 号；
     *   [LYNK01_CHANNEL] = 导航专用的 usage 12 + content type 17。
     */
    fun forChannel(channel: AudioChannel, media: Int, navigation: Int): Int = when (channel) {
        AudioChannel.MEDIA -> sanitize(media, MAX_CHANNEL)
        AudioChannel.NAVIGATION -> sanitize(navigation, LYNK01_CHANNEL)
        else -> 0
    }

    private fun sanitize(value: Int, max: Int): Int =
        if (value in MIN_CHANNEL..max) value else 0
}
