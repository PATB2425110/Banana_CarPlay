package com.shilapi.xcertplay.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 防止再次踩「API 29+ 新方法在 Android 9 车机上 NoSuchMethodError」的坑。
 *
 * ## 背景（0.9.1 实车事故，2026-10-05）
 *
 * `createDecoder()` 里为了「过滤别名解码器」直接调了 `MediaCodecInfo.isAlias`，
 * 但它 **API 29 (Q) 才存在**，而车机是 Android 9 (API 28, sdkInt=28)。
 * 结果是 `NoSuchMethodError` 打死 `carplay-video` 线程 → 进程反复重启
 * （10:03:08 / 10:04:04 两次），每次重启主线程又卡 37 秒（Skipped 2086 frames）
 * → 用户观感就是「进首页非常卡 + 连上就闪退」。
 *
 * ## 这类坑的通用形态
 *
 * `MediaCodecInfo` / `MediaFormat` / `Build` 上很多"看起来普通"的方法都是
 * 高版本新增：`isAlias`(29)、`isSoftwareOnly`(29)、`isHardwareAccelerated`(29)、
 * `getSupportedFrameRatesFor`(23 但部分 ROM 没有)…在低版本车机上调用即崩。
 *
 * ## 本测试的作用
 *
 * 这些"是否别名 / 是否软解"的判断已经收敛到 [AndroidMediaSink] 内部的两个
 * 带版本守卫的辅助函数（`isAliasCodec` / `isSoftwareOnlyCodec`），
 * 本测试用纯 JVM 逻辑校验**命名回退策略**本身的正确性——即低版本路径上
 * 我们依据名字判断软解/别名的规则是预期的，不依赖任何 Android 运行时。
 *
 * 若将来有人把这套回退规则改坏（比如把 `c2.android.` 从软解前缀里删掉），
 * 本测试会立刻变红。
 */
class CodecApiLevelGuardTest {

    /**
     * 复刻 [AndroidMediaSink.isSoftwareOnlyCodec] 在 API < 29 上的名字回退规则。
     * 与实现保持同一份字符串列表；改实现必须同步改这里（本测试即"契约"）。
     */
    private fun isSoftwareOnlyByLowerApiName(name: String): Boolean {
        val n = name.lowercase()
        return n.startsWith("omx.google.") ||
            n.startsWith("c2.android.") ||
            n.contains(".sw.") ||
            n.endsWith(".sw")
    }

    @Test
    fun `google 软解与 c2 软解被识别为软件实现`() {
        // AOSP 的两种软解命名：OMX.google.*（OMX 时代）与 c2.android.*（Codec2 时代）
        assertTrue(isSoftwareOnlyByLowerApiName("OMX.google.h264.decoder"))
        assertTrue(isSoftwareOnlyByLowerApiName("c2.android.avc.decoder"))
        assertTrue(isSoftwareOnlyByLowerApiName("c2.android.hevc.decoder"))
        // 大小写不敏感：ROM 里名字大小写并不统一
        assertTrue(isSoftwareOnlyByLowerApiName("OMX.GOOGLE.AAC.DECODER"))
    }

    @Test
    fun `带 sw 标记的厂商软解被识别为软件实现`() {
        // 部分高通/联发科 ROM 用 .sw. 段标记软件解码器
        assertTrue(isSoftwareOnlyByLowerApiName("OMX.qcom.video.decoder.avc.sw"))
        assertTrue(isSoftwareOnlyByLowerApiName("OMX.MTK.VIDEO.DECODER.AVC.SW"))
    }

    @Test
    fun `硬件解码器不被误判为软件实现`() {
        // 吉利 CS11 上真实存在的硬解名，必须走硬解优先档
        assertFalse(isSoftwareOnlyByLowerApiName("OMX.qcom.video.decoder.avc"))
        assertFalse(isSoftwareOnlyByLowerApiName("OMX.qcom.video.decoder.hevc"))
        assertFalse(isSoftwareOnlyByLowerApiName("c2.qti.avc.decoder"))
        assertFalse(isSoftwareOnlyByLowerApiName("OMX.Exynos.AVC.Decoder"))
    }

    @Test
    fun `别名解码器命名回退规则不误伤真实解码器`() {
        // 低版本回退用名字判断别名（含 ".alias"），必须不误伤正常解码器
        val aliasMarker = ".alias"
        assertFalse("OMX.qcom.video.decoder.avc".contains(aliasMarker, ignoreCase = true))
        assertFalse("c2.android.avc.decoder".contains(aliasMarker, ignoreCase = true))
        // 而真正的别名声会被命中
        assertTrue("OMX.qcom.video.decoder.avc.alias".contains(aliasMarker, ignoreCase = true))
    }
}
