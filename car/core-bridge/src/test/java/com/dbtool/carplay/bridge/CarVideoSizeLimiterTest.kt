package com.dbtool.carplay.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [CarVideoSizeLimiter] 纯计算部分的单测。
 *
 * 为什么这段必须钉死：它的输出直接决定 iPhone 按多大分辨率编码，而
 * **尺寸一旦超过本机硬解上限，症状是全链路看起来正常、只有车机黑屏**
 * （`MediaCodec.configure()` 抛 `BAD_VALUE`，iPhone 侧会话却是 active）。
 * 这类故障在现场极难归因，所以把边界在能自动化的一侧穷举掉。
 *
 * 夹具用真机实测值：vivo Y78+（骁龙 6 Gen 1 / Holi）的
 * `c2.qti.hevc.decoder` 上限 `1920x1088`、对齐 `2x2`、块 16x16、
 * `blocks-per-second` 上限 `489600`（即 1920x1080@60 刚好压线）。
 */
class CarVideoSizeLimiterTest {

    /**
     * 静态读源码用（不反射、不启动 Android 运行时）。
     *
     * 单测 cwd 是模块目录（car/core-bridge），故从几层父目录里找。
     */
    private fun sourceOf(relative: String): String {
        val candidates = listOf(
            File(relative),
            File("../$relative"),
            File("../../$relative"),
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("找不到源码文件 $relative（从 ${File(".").absolutePath} 起找过 ${candidates.size} 个位置）")
        return file.readText()
    }

    private val limiterSrc = "src/main/java/com/dbtool/carplay/bridge/CarVideoSizeLimiter.kt"

    /** 复刻 Holi 上 `c2.qti.hevc.decoder` 的真实约束。 */
    private val holiSupported: (Int, Int, Int) -> Boolean = { width, height, fps ->
        width <= 1920 && height <= 1088 && (width / 16) * (height / 16) * fps <= 489_600
    }

    private fun fit(
        width: Int,
        height: Int,
        fps: Int = 60,
        maximumWidth: Int = 1920,
        maximumHeight: Int = 1088,
        alignment: Int = 2,
        isSupported: (Int, Int, Int) -> Boolean = holiSupported,
    ): CarVideoSizeLimiter.FitResult? = CarVideoSizeLimiter.fit(
        requestedWidth = width,
        requestedHeight = height,
        requestedFps = fps,
        maximumWidth = maximumWidth,
        maximumHeight = maximumHeight,
        widthAlignment = alignment,
        heightAlignment = alignment,
        isSupported = isSupported,
    )

    @Test
    fun `真机画布 2314x1080 被夹到硬解上限内且保持宽高比`() {
        val fitted = fit(width = 2314, height = 1080)

        // 上限宽度 1920，等比高度 1920*1080/2314 = 896（整数除）。
        assertEquals(1920, fitted?.widthPixels)
        assertEquals(896, fitted?.heightPixels)
        assertEquals(60, fitted?.fps)
    }

    @Test
    fun `压线比值不会因为浮点误差掉一个对齐步长`() {
        // 2314→1920 的比值恰好压线：浮点缩放会算成 1919，再对齐变成 1918。
        // 这里断言拿到的是整数运算给出的 1920。
        val fitted = fit(width = 2314, height = 1080)

        assertEquals(0, fitted!!.widthPixels % 2)
        assertEquals(1920, fitted.widthPixels)
    }

    @Test
    fun `尺寸本来就在上限内时不做任何缩放`() {
        val fitted = fit(width = 1920, height = 1080)

        assertEquals(1920, fitted?.widthPixels)
        assertEquals(1080, fitted?.heightPixels)
        assertEquals(60, fitted?.fps)
    }

    @Test
    fun `窄画布如 1280x720 原样通过`() {
        val fitted = fit(width = 1280, height = 720)

        assertEquals(1280, fitted?.widthPixels)
        assertEquals(720, fitted?.heightPixels)
    }

    @Test
    fun `高度超限时以高度为准反推宽度`() {
        val fitted = fit(width = 1920, height = 1200)

        // 1920*1200 超 1088 → 高度压到 1088，宽度反推 1088*1920/1200 = 1740，
        // 再由宽度回推高度 1740*1200/1920 = 1087 → 对齐到 1086。
        assertEquals(1740, fitted?.widthPixels)
        assertEquals(1086, fitted?.heightPixels)
    }

    @Test
    fun `结果始终落在解码器声明的对齐网格上`() {
        val fitted = fit(width = 2313, height = 1081, alignment = 2)

        assertEquals(0, fitted!!.widthPixels % 2)
        assertEquals(0, fitted.heightPixels % 2)
    }

    @Test
    fun `块数超限时继续按步长往下退直到被接受`() {
        // 只允许 1280x720 这一档，模拟"上限框算出来的尺寸仍不被接受"。
        val fitted = fit(
            width = 2314,
            height = 1080,
            isSupported = { width, height, fps ->
                width <= 1280 && height <= 720 && fps <= 60
            },
        )

        assertEquals(1280, fitted?.widthPixels)
        // 宽度退到 1280 时高度由 1280*1080/2314 = 597 对齐到 596。
        assertEquals(596, fitted?.heightPixels)
    }

    @Test
    fun `帧率不被接受时降一档帧率重试`() {
        val fitted = fit(
            width = 2314,
            height = 1080,
            isSupported = { _, _, fps -> fps <= 30 },
        )

        assertEquals(1920, fitted?.widthPixels)
        assertEquals(896, fitted?.heightPixels)
        assertEquals(30, fitted?.fps)
    }

    /**
     * 2026-10-06：帧率折半的**下限必须与 CarPlay 协议的下限一致**。
     *
     * `fit()` 靠 `candidateFps /= 2` 找能跑的组合。若下限低于
     * `AirPlayDisplaySettings.MIN_FPS`（30），它会返回一个协议不接受的帧率
     * （例如 24），而 `CarPlayConfigBuilder` 那边的 `sanitizeFps` 会把它
     * **顶回 30** —— 于是"解码器只跑得动 24，我们却说 30"，
     * 掉帧问题原样复现。两边下限对齐才能真正闭环。
     */
    @Test
    fun `帧率折半不会低于协议下限 30`() {
        // 只接受 20fps 的解码器：任何合法帧率都不被支持，应当返回 null。
        val fitted = fit(
            width = 2314,
            height = 1080,
            isSupported = { _, _, fps -> fps <= 20 },
        )

        assertNull("帧率下限必须 ≥30，否则会返回协议不接受的值", fitted)
    }

    /**
     * 2026-10-06：**`clamp()` 必须把降下来的帧率带回去**。
     *
     * 旧版 `clamp` 只 `return CarVideoCanvas(fitted.widthPixels, fitted.heightPixels)`，
     * 把 `FitResult.fps` **丢掉**了。而 `fit()` 恰恰是靠折半帧率才找到可行组合的
     * ⇒ 解码器只跑得动 30fps 时，我们仍按用户选的 60fps 建 `AirPlayConfig`
     * 交给 iPhone，车机侧当场掉帧。
     *
     * 纯函数部分（`fit`）单测覆盖不到这个"丢弃"动作，故用源码级断言钉死。
     */
    @Test
    fun `clamp 把生效帧率带回去而不是丢掉`() {
        val src = sourceOf(limiterSrc)
        val fn = src.substringAfter("    fun clamp(", "")
        assertTrue("clamp() 不见了——本契约需同步更新", fn.isNotEmpty())
        val body = fn.substringBefore("\n    /**")

        assertTrue(
            "clamp() 必须返回带帧率的 `ClampResult`，而不是只回 `CarVideoCanvas`。",
            body.contains("): ClampResult {"),
        )
        assertTrue(
            "失效路径也要带上 requestedFps，不能让调用方拿到 0 帧率。",
            body.contains("ClampResult(canvas, requestedFps)"),
        )
        assertTrue(
            "成功路径必须用 `fitted.fps`（可能已被折半），不能用 requestedFps。",
            body.contains("fitted.fps)"),
        )
        assertFalse(
            "旧写法 `return CarVideoCanvas(fitted.widthPixels, fitted.heightPixels)` " +
                "已回归——它把降下来的帧率丢掉了。",
            body.contains("return CarVideoCanvas(fitted.widthPixels"),
        )
    }

    @Test
    fun `任何尺寸都不被接受时返回 null 而不是瞎猜`() {
        val fitted = fit(width = 2314, height = 1080, isSupported = { _, _, _ -> false })

        assertNull(fitted)
    }

    @Test
    fun `非法输入直接返回 null`() {
        assertNull(fit(width = 0, height = 1080))
        assertNull(fit(width = 2314, height = 0))
        assertNull(fit(width = 2314, height = 1080, maximumWidth = 0))
        assertNull(fit(width = 2314, height = 1080, maximumHeight = 0))
    }

    // ------------------------------------------------ 假上限防护（2026-10-05 CS11）

    /**
     * 车机 ROM 把某个解码器的上限谎报成 `256x256`（实车 CS11，日志
     * `video size clamp exhausted … decoderMax=256x256`，而同一解码器几秒后
     * 照样渲染出 1920x1080）。
     *
     * 此时**必须拒绝缩小**：如果请求尺寸碰巧落在假上限之内（用户把画布调到
     * 256 宽以下），照着假上限缩会把画面变成一张邮票——比不缩还糟。
     */
    @Test
    fun `假上限 256x256 时拒绝缩小并返回 null`() {
        // 请求 1920x1080（正常握手尺寸），上限虚报 256x256。
        assertNull(
            fit(width = 1920, height = 1080, maximumWidth = 256, maximumHeight = 256),
        )
    }

    @Test
    fun `请求尺寸小于假上限时也绝不照着假上限缩`() {
        // 用户把画布调到 200x120，落在假上限 256x256 之内。
        // 原逻辑会「接受」并原样通过；关键是它**绝不能被拿去当缩小依据**。
        // 这里断言：上限荒谬时直接放弃，而不是产出一个 ≤256 的缩小结果。
        val fitted = fit(width = 200, height = 120, maximumWidth = 256, maximumHeight = 256)
        // 返回 null（放弃）或原尺寸都行，但绝不允许返回"更小"的尺寸。
        if (fitted != null) {
            assertEquals(200, fitted.widthPixels)
            assertEquals(120, fitted.heightPixels)
        }
    }

    @Test
    fun `上限只比请求小一档时仍正常缩小`() {
        // 2314x1080 → 上限 1920x1088 是**正常**的车机硬解上限，必须照常缩。
        val fitted = fit(width = 2314, height = 1080, maximumWidth = 1920, maximumHeight = 1088)

        assertEquals(1920, fitted?.widthPixels)
        assertEquals(896, fitted?.heightPixels)
    }

    @Test
    fun `上限装不下请求的四分之一时视为假表`() {
        // 请求 1920x1080，上限 719x719：低于 720p 短边阈值 ⇒ 判为假表、拒绝缩小。
        assertNull(fit(width = 1920, height = 1080, maximumWidth = 719, maximumHeight = 719))
    }

    @Test
    fun `上限正好 720p 短边时仍走正常缩小路径`() {
        // 720 是可信下限，不该被"假表"分支拦下；是否真能缩由 isSupported 决定。
        // 用一个只接受 1280x720 的夹具确认它走的是正常缩小，而不是直接放弃。
        val fitted = fit(
            width = 1920,
            height = 1080,
            maximumWidth = 1280,
            maximumHeight = 720,
            isSupported = { w, h, fps -> w <= 1280 && h <= 720 && fps <= 60 },
        )

        assertEquals(1280, fitted?.widthPixels)
    }

    // ------------------------------------------------------ 软解 / 硬解判定

    /**
     * 这一段钉的是 **API 29 以下唯一能用的判据**。
     *
     * `isSoftwareOnly` / `isHardwareAccelerated` 都是 API 29 才有的属性，
     * 而车机（Android 9 / API 28）恰恰没有。少了这个回退，选解码器就会落到
     * `usable.first()`——列表里任意一个条目。实车取到的那个上限只有 256x256，
     * 于是 1920x1080 被误判成超限（见 `CarVideoSizeLimiter` 的
     * `isHardwareAcceleratedDecoder`）。
     *
     * 所以这里**既钉软解要认出来，也钉 QTI 硬解绝不能被当成软解**：
     * 后者判错会让"优先硬解"退化成软解，车机上直接卡成个位数帧率。
     */
    @Test
    fun `AOSP 软解前缀被认成软解`() {
        assertTrue(CarVideoSizeLimiter.isSoftwareDecoderName("OMX.google.h264.decoder"))
        assertTrue(CarVideoSizeLimiter.isSoftwareDecoderName("OMX.google.hevc.decoder"))
        assertTrue(CarVideoSizeLimiter.isSoftwareDecoderName("c2.android.avc.decoder"))
        assertTrue(CarVideoSizeLimiter.isSoftwareDecoderName("c2.android.hevc.decoder"))
    }

    @Test
    fun `第三方 ffmpeg 软解也被认成软解`() {
        assertTrue(CarVideoSizeLimiter.isSoftwareDecoderName("OMX.ffmpeg.h264.decoder"))
        assertTrue(CarVideoSizeLimiter.isSoftwareDecoderName("c2.ffmpeg.avc.decoder"))
    }

    @Test
    fun `前缀大小写不敏感`() {
        assertTrue(CarVideoSizeLimiter.isSoftwareDecoderName("omx.google.h264.decoder"))
        assertTrue(CarVideoSizeLimiter.isSoftwareDecoderName("C2.ANDROID.AVC.DECODER"))
    }

    /**
     * 车机上真正被用到的那个解码器（实车日志
     * `video decoder configured name=OMX.qcom.video.decoder.avc`）绝不能被当成软解。
     */
    @Test
    fun `厂商硬解不被认成软解`() {
        val hardware = listOf(
            "OMX.qcom.video.decoder.avc",
            "OMX.qcom.video.decoder.hevc",
            "c2.qti.avc.decoder",
            "c2.qti.hevc.decoder",
            "OMX.MTK.VIDEO.DECODER.AVC",
            "OMX.Exynos.AVC.Decoder",
        )
        for (name in hardware) {
            assertFalse(name, CarVideoSizeLimiter.isSoftwareDecoderName(name))
        }
    }

    /** 前缀必须在**开头**，名字中间出现 `google` 不算软解。 */
    @Test
    fun `名字中间出现前缀不算软解`() {
        assertFalse(CarVideoSizeLimiter.isSoftwareDecoderName("OMX.qcom.google.h264.decoder"))
        assertFalse(CarVideoSizeLimiter.isSoftwareDecoderName("OMX.vendor.c2.android.decoder"))
    }

    /**
     * 2026-10-05：**能力表查询必须带进程内缓存**。
     *
     * 实车 CS11（Android 9）实测：`clamp()` 里的能力查询把整条连接链堵了 **10.7 秒**
     * ——`getCapabilitiesForType` 每次都要在 ROM 侧重整张 `VideoCapabilities`
     * （logcat 里成片的 `VideoCapabilities: Unsupported mime …` 就是它），而车机上
     * AVC 的解码器条目有七八个。缓存把「同一个 (解码器, mime) 只问一次」钉死。
     */
    @Test
    fun `能力表查询走进程内缓存`() {
        val src = sourceOf(limiterSrc)

        assertTrue(
            "缺少能力表缓存：`getCapabilitiesForType` 每次都要在 ROM 侧重建整张" +
                "VideoCapabilities，实车一次 clamp 会因此花掉 10.7 秒。",
            src.contains("capabilitiesCache"),
        )
        assertTrue(
            "缓存 key 必须含 **解码器名**（`MediaCodecList` 每次 codecInfos 都会新建" +
                "实例，拿实例当 key 等于没缓存）。",
            src.contains("info.name + "),
        )
        assertTrue(
            "必须有 `cachedVideoCapabilities(` 这个统一入口，让两条路径（可信度检查、" +
                "最终取值）走同一份缓存。",
            src.contains("cachedVideoCapabilities("),
        )
    }

    /**
     * 2026-10-05：**廉价信息先筛，昂贵查询只对幸存者做**。
     *
     * 排查前的实现是 `usable.filter { it.hasCredibleCapabilities(mime) }` ——
     * 对**每一个**候选解码器都做一次昂贵的能力查询。改成按硬/软解偏好排序后
     * `firstOrNull`，命中即止。
     */
    @Test
    fun `先排序再逐个问只对幸存者做昂贵查询`() {
        val src = sourceOf(limiterSrc)
        val fn = src.substringAfter("private fun decoderChoice(", "")
        assertTrue("decoderChoice 不见了——本契约需同步更新", fn.isNotEmpty())
        val body = fn.substringBefore("\n    /**")

        assertTrue(
            "必须先用便宜的硬/软解判定排序（`ordered`），再逐个问可信度。",
            body.contains("val ordered ="),
        )
        assertTrue(
            "必须用 `firstOrNull { it.hasCredibleCapabilities(` 命中即止，" +
                "不能再用 `filter { … }` 把每个候选的能力都问一遍。",
            body.contains("firstOrNull { it.hasCredibleCapabilities("),
        )
        assertFalse(
            "旧的 `usable.filter { it.hasCredibleCapabilities(mime) }` 已回归——" +
                "那会把所有候选的能力都问一遍，实车一次就是十几秒。",
            body.contains("filter { it.hasCredibleCapabilities("),
        )
    }
}
