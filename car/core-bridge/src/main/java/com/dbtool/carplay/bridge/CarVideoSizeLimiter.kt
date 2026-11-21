package com.dbtool.carplay.bridge

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import com.dbtool.carplay.api.display.CarVideoCanvas
import com.shilapi.xcertplay.airplay.AirPlayDisplaySettings

/**
 * 把 CarPlay 画布尺寸夹到"本机解码器真的解得动"的范围里。
 *
 * ## 为什么必须有这一步
 *
 * CarPlay 的握手分辨率来自车机在 `/info` 里声明的显示能力，而这个尺寸
 * 直接决定 iPhone 编码出来的码流分辨率——**同时也直接写进
 * `MediaCodec.configure()` 的 `MediaFormat`**。一旦超过本机解码器声明的
 * `size` 上限，configure 会立刻抛 `BAD_VALUE`。
 *
 * 真机实测（vivo Y78+，骁龙 6 Gen 1 / Holi）：
 * `/vendor/etc/media_codecs_holi.xml` 把 `c2.qti.hevc.decoder` 与
 * `c2.qti.avc.decoder` 的上限都声明成 `1920x1088`；而本机屏幕是 1080x2400，
 * 横屏去掉刘海 inset 后承载画面的 View 实测 2314x1080。
 *
 * 现象非常具有误导性：iPhone 侧一切正常（弹窗、配对、会话 active、
 * 屏幕流 active、H265 帧源源不断到达），只有车机端永远黑屏，日志里是
 *
 * ```
 * W CCodec  : failed to configure c2 params
 * E MediaCodec: Codec reported err 0xffffffea/BAD_VALUE, while in state 3/CONFIGURING
 * E xcertplay-usb: video decoder configure failed mime=video/hevc size=2314x1080
 * ```
 *
 * ## 策略
 *
 * 等比缩小到解码器上限之内，并按解码器要求的 `widthAlignment/heightAlignment`
 * 对齐（QTI 是 2x2）。画面由 SurfaceView 放大回全屏，代价只是轻微软化。
 *
 * 为什么不干脆换软解：2314x1080@60 是 2.5M 像素/秒·帧，中端 SoC 上
 * `c2.android.hevc.decoder` 实测只有个位数帧率（见设备
 * `media_codecs_performance.xml` 的 `measured-frame-rate`），软解不可用。
 *
 * 为什么不放在 upstream：这是"按本机硬件能力协商"的策略，属于平台适配。
 * upstream 的 `AndroidMediaSink` 只负责照单执行，不该由它去猜设备能力。
 */
object CarVideoSizeLimiter {

    /**
     * 按 [hevc] 选定的编码类型查询本机解码器能力，必要时缩小 [canvas]、**并降帧率**。
     *
     * @param fps 握手时声明的帧率，用于 `areSizeAndRateSupported` 校验。
     *        若解码器在这个尺寸下跑不满该帧率，返回值里的 [ClampResult.fps]
     *        会给出**它真能跑的帧率**（见下）。
     * @param softwareDecoder 为 true 时按 upstream 的软解选择逻辑取
     *        `c2.android.*` 软解能力（软解上限通常远大于硬解）。
     *
     * ## 为什么返回值要带上 fps（2026-10-06，PATCH 前哨）
     *
     * [fit] 在请求尺寸+帧率不被支持时会**逐次折半帧率**（60→30→15…）去找一个能跑的
     * 组合，并把选中的帧率放在 [FitResult.fps] 里。但旧版 `clamp` 把它**丢掉了**：
     *
     * ```kotlin
     * return CarVideoCanvas(fitted.widthPixels, fitted.heightPixels)  // ← fps 没了
     * ```
     *
     * 后果是：解码器明明只跑得动 30fps，我们仍然按用户选的 60fps 去建
     * `AirPlayConfig` 交给 iPhone ⇒ iPhone 按 60 编码 ⇒ 车机侧当场掉帧/卡顿。
     * 换回正确帧率属于"治未病"：**协商期就说出真话，而不是等播放期卡顿**。
     */
    fun clamp(
        canvas: CarVideoCanvas,
        fps: Int,
        hevc: Boolean,
        softwareDecoder: Boolean,
    ): ClampResult {
        val mime = if (hevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        val decoder = decoderChoice(mime, softwareDecoder)
        if (decoder == null) {
            Log.w(TAG, "video size not checked; no usable decoder declared for $mime")
            return ClampResult(canvas, fps)
        }
        val capabilities = decoder.capabilities

        val requestedWidth = canvas.widthPixels
        val requestedHeight = canvas.heightPixels
        val requestedFps = if (fps > 0) fps else DEFAULT_FPS
        val maximumWidth = capabilities.supportedWidths.upper
        val maximumHeight = capabilities.supportedHeights.upper
        val widthAlignment = capabilities.widthAlignment.coerceAtLeast(2)
        val heightAlignment = capabilities.heightAlignment.coerceAtLeast(2)

        if (capabilities.areSizeAndRateSupported(
                requestedWidth,
                requestedHeight,
                requestedFps.toDouble(),
            )
        ) {
            Log.i(
                TAG,
                "video size accepted codec=$mime size=${requestedWidth}x$requestedHeight@$requestedFps",
            )
            return ClampResult(canvas, requestedFps)
        }

        val fitted = fit(
            requestedWidth = requestedWidth,
            requestedHeight = requestedHeight,
            requestedFps = requestedFps,
            maximumWidth = maximumWidth,
            maximumHeight = maximumHeight,
            widthAlignment = widthAlignment,
            heightAlignment = heightAlignment,
            isSupported = { width, height, rate ->
                capabilities.areSizeAndRateSupported(width, height, rate.toDouble())
            },
        )
        if (fitted == null) {
            // ⚠️ 2026-10-05：这条日志以前是 `video size clamp exhausted … decoderMax=256x256`，
            // 极易被读成「设备太弱、解不动」。实车（CS11）证明那是**能力表撒谎**——
            // 同一个解码器几秒后照样把 1920x1080 渲染出来了。措辞改清楚，并明确
            // 结果是「保持原尺寸」而不是「缩成更小」。
            Log.e(
                TAG,
                "video size clamp gave up (decoder capability table not credible) " +
                    "codec=$mime decoder=${decoder.name} " +
                    "requested=${requestedWidth}x$requestedHeight@$requestedFps " +
                    "declaredMax=${maximumWidth}x$maximumHeight " +
                    "→ keeping requested size (NOT shrinking to declared max)",
            )
            return ClampResult(canvas, requestedFps)
        }
        Log.w(
            TAG,
            "video size clamped codec=$mime requested=${requestedWidth}x$requestedHeight@$requestedFps " +
                "chosen=${fitted.widthPixels}x${fitted.heightPixels}@${fitted.fps} " +
                "decoderMax=${maximumWidth}x$maximumHeight alignment=${widthAlignment}x$heightAlignment",
        )
        return ClampResult(CarVideoCanvas(fitted.widthPixels, fitted.heightPixels), fitted.fps)
    }

    /**
     * 纯计算部分：把请求尺寸等比压到解码器上限内。
     *
     * 刻意不碰任何 Android 类型，好让 JVM 单测能直接喂 [isSupported] 断言。
     * 先取上限框内最大的等比尺寸，再逐个对齐步长往下退——因为"块数/秒"
     * 这类限制会让上限框算出来的尺寸仍然不被接受，必须靠实际能力回调确认。
     */
    internal fun fit(
        requestedWidth: Int,
        requestedHeight: Int,
        requestedFps: Int,
        maximumWidth: Int,
        maximumHeight: Int,
        widthAlignment: Int,
        heightAlignment: Int,
        isSupported: (width: Int, height: Int, fps: Int) -> Boolean,
    ): FitResult? {
        if (requestedWidth <= 0 || requestedHeight <= 0) return null
        if (maximumWidth <= 0 || maximumHeight <= 0) return null
        // ⚠️ 2026-10-05：上限小到荒谬时**直接放弃缩小**，交回调用方原尺寸。
        //
        // 这是 CS11 假上限事故的第二道防线。车机 ROM 曾把某个解码器的上限声明成
        // 256x256（连握手分辨率都装不下），而请求尺寸一旦落在它之内（用户把画布
        // 调到 256 宽以下），原逻辑会照着假上限把画面缩成邮票——比不缩还糟。
        //
        // 判据：**上限的短边低于 720p 短边（[CREDIBLE_MIN_EDGE]）**就说明这张能力表
        // 不可能是真的在描述"这台设备能解多大"。此时返回 null ⇒ 调用方保持原尺寸，
        // 让 `MediaCodec.configure` 去如实报错，而不是悄悄交出一张邮票。
        //
        // 刻意用「绝对荒谬阈值」而不是「相对请求的缩比」：2314x1080 → 上限 1920x1088
        // 是**正常**的车机硬解上限，那种该缩就得缩；而 1920 → 256 这种量级的落差
        // 只可能是假表。绝对阈值能干净地把两者分开。
        if (maximumWidth < CREDIBLE_MIN_EDGE || maximumHeight < CREDIBLE_MIN_EDGE) {
            return null
        }
        val widthStep = widthAlignment.coerceAtLeast(2)
        val heightStep = heightAlignment.coerceAtLeast(2)
        // 先求"上限框内最大的等比尺寸"，全程整数运算。
        // 刻意不用浮点缩放：2314→1920 这种刚好压线的比值会在末位抖成 1919，
        // 再对齐一次就白掉一个步长（1918x894），画质和观感都无谓地差一档。
        var startWidth = minOf(requestedWidth, maximumWidth)
        var startHeight = startWidth.toLong() * requestedHeight / requestedWidth
        if (startHeight > maximumHeight) {
            startHeight = maximumHeight.toLong()
            startWidth = (startHeight * requestedWidth / requestedHeight).toInt()
        }
        var candidateFps = requestedFps.coerceAtLeast(MINIMUM_FPS)
        while (candidateFps >= MINIMUM_FPS) {
            var candidateWidth = alignDown(startWidth, widthStep)
            while (candidateWidth >= MINIMUM_EDGE) {
                val candidateHeight = alignDown(
                    (candidateWidth.toLong() * requestedHeight / requestedWidth).toInt(),
                    heightStep,
                )
                if (candidateHeight < MINIMUM_EDGE) break
                if (isSupported(candidateWidth, candidateHeight, candidateFps)) {
                    return FitResult(candidateWidth, candidateHeight, candidateFps)
                }
                candidateWidth = alignDown(candidateWidth - widthStep, widthStep)
            }
            candidateFps /= 2
        }
        return null
    }

    private fun alignDown(value: Int, alignment: Int): Int {
        val aligned = value / alignment * alignment
        return if (aligned >= alignment) aligned else alignment
    }

    /**
     * 复刻 `MediaCodec.createDecoderByType` 的选择结果，取它的视频能力。
     *
     * 不用 `MediaCodecList.findDecoderForFormat`：它需要一个已经合法的
     * `MediaFormat`，而我们要解决的恰恰是"尺寸非法"——喂进去只会拿到 null，
     * 拿不到任何上限信息。
     *
     * ⚠️ 连**编解码器名字**一起返回：不返回的话，日志里只剩一句
     * `decoderMax=256x256`，没法判断是"设备真的这么弱"还是"我们挑错了编解码器"。
     * 实车（Android 9 / QUALCOMM CS11）就踩过这个坑，见
     * [isHardwareAcceleratedDecoder]。
     */
    private fun decoderChoice(mime: String, preferSoftware: Boolean): DecoderChoice? {
        val infos = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos }
            .getOrNull() ?: return null
        val usable = infos.filter { info ->
            !info.isEncoder && mime in info.supportedTypes && !info.isSecureDecoder(mime)
        }
        if (usable.isEmpty()) return null
        // ⚠️ 2026-10-05：先剔掉**能力表明显不可信**的解码器，再按硬解/软解偏好挑。
        //
        // 为什么必须先剔：车机（Android 9 / QUALCOMM CS11）的列表里，排在前面的那个
        // 「硬件」解码器声明上限只有 256x256——连 1920x1080 都装不下，而真正被
        // `MediaCodec.configure` 选中、且实车跑通 1920x1080@30 的
        // `OMX.qcom.video.decoder.avc`「能解」列表里排在它后面。
        //
        // 原实现 `usable.firstOrNull { it.isHardwareAcceleratedDecoder() }` 会挑中
        // 这个假条目，后果有二：
        //   1. 日志打出误导性极强的 `decoderMax=256x256`（以为是设备弱，其实是挑错了）；
        //   2. 更糟——**请求尺寸落在假上限之内时**（用户把画布调到 256 宽以下），
        //      限流器会照着假上限把画面缩成邮票。
        // 剔掉不可信的，挑到的就是能力表正常、能覆盖真实握手尺寸的那个。
        // 用 `MediaCodecInfo` 上**便宜**的信息先筛，只对幸存者做昂贵的
        // `getCapabilitiesForType()`。2026-10-05 实车（CS11）实测：一个 `clamp` 里的
        // 真探把连接链堵了 10.7 秒——`getCapabilitiesForType` 每次都要在 ROM 里
        // 重建整张 VideoCapabilities（logcat 里成片的 `Unsupported mime` /
        // `Unrecognized profile` 就是它），而 usable 列表在 CS11 上有七八个。
        //
        // 顺序因此改成：
        //   ① 先按硬/软解偏好挑出**一个**候选（便宜，只读 MediaCodecInfo 的字段）；
        //   ② 只有它被判"能力表不可信"时，才回头去问下一个——而不是把所有
        //      候选的能力都问一遍。
        val ordered = when {
            preferSoftware -> usable.sortedBy { !it.isSoftwareOnlyDecoder() }
            else -> usable.sortedBy { !it.isHardwareAcceleratedDecoder() }
        }
        val preferred = ordered.firstOrNull { it.hasCredibleCapabilities(mime) }
            ?: ordered.first()
        val capabilities = cachedVideoCapabilities(preferred, mime) ?: return null
        return DecoderChoice(preferred.name, capabilities)
    }

    /**
     * 解码器声明的能力是否**可信**（2026-10-05，CS11 假上限事故）。
     *
     * 判据：声明的宽度/高度上限必须能覆盖该 mime 的实际常见码流——即上限不低于
     * [CREDIBLE_MIN_EDGE]（720p 短边 720）。低于这个数的「视频解码器」在 2020 年后
     * 的量产车机/手机上已不存在，声明成 256x256 只可能是 ROM 的能力表 bug 或
     * 一个没接上真实硬件的占位条目。
     *
     * 刻意**只看上限、不看是否支持某个具体尺寸**：我们要的是"这张能力表能不能拿来
     * 做缩小决策"，而不是"它能不能解某一个尺寸"。上限荒谬 ⇒ 整张表都别信 ⇒ 不拿它
     * 去缩画面，也不拿它的名字去误导排障。
     *
     * 全都被判不可信时（[decoderChoice] 里 `credible` 为空）回退到原列表——宁可
     * 用可疑的能力表，也不要一个解码器都报不出来。
     */
    private fun MediaCodecInfo.hasCredibleCapabilities(mime: String): Boolean {
        val caps = cachedVideoCapabilities(this, mime) ?: return false
        val maxWidth = runCatching { caps.supportedWidths.upper }.getOrDefault(0)
        val maxHeight = runCatching { caps.supportedHeights.upper }.getOrDefault(0)
        return maxWidth >= CREDIBLE_MIN_EDGE && maxHeight >= CREDIBLE_MIN_EDGE
    }

    /**
     * 带**进程内缓存**的 `getCapabilitiesForType(mime).videoCapabilities`（2026-10-05）。
     *
     * ## 为什么必须缓存
     *
     * 实车（CS11）实测：`clamp()` 触发的这一串能力查询把整条连接链堵了 **10.7 秒**
     * ——`getCapabilitiesForType` 每次都要在 ROM 侧重建一整张 `VideoCapabilities`，
     * 而它的构造过程会逐个 mime 逐条 profile 地试（logcat 里成片的
     * `VideoCapabilities: Unsupported mime …` / `Unrecognized profile …` 就是它）。
     * 车机上 AVC 的解码器条目有七八个，一次 `clamp` 挨个问一遍就是十几秒。
     *
     * ## 为什么可以缓存
     *
     * 解码器能力是**只读的硬件/ROM 属性**：同一个 `(解码器, mime)` 在同一台机器上
     * 只有一个答案，进程生命周期内不会变。跨进程重启会重新建，不存在过期问题。
     *
     * key 用「解码器名 + mime」而不是 `MediaCodecInfo` 实例——`MediaCodecList`
     * 每次 `codecInfos` 都会新建实例，用实例当 key 等于没缓存。
     */
    private fun cachedVideoCapabilities(
        info: MediaCodecInfo,
        mime: String,
    ): MediaCodecInfo.VideoCapabilities? {
        val key = info.name + '\u0000' + mime
        synchronized(capabilitiesCache) {
            capabilitiesCache[key]?.let { return it.getOrNull() }
        }
        val value = runCatching { info.getCapabilitiesForType(mime).videoCapabilities }
        synchronized(capabilitiesCache) {
            capabilitiesCache[key] = value
        }
        return value.getOrNull()
    }

    private fun MediaCodecInfo.isSecureDecoder(mime: String): Boolean {
        val requiredByFeature = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            runCatching { getCapabilitiesForType(mime).isFeatureRequired(FEATURE_SECURE_PLAYBACK) }
                .getOrDefault(false)
        // `isFeatureRequired` 是 API 29，低版本回退到命名约定：
        // QTI 的 secure 解码器一律叫 `*.decoder.secure`。
        return requiredByFeature || name.contains(SECURE_NAME_MARKER, ignoreCase = true)
    }

    /**
     * 取**软件解码器**。
     *
     * API 29 起有 `isSoftwareOnly`；更低的版本没有这个属性，按命名约定判断
     * （见 [isSoftwareDecoderName]）。低版本上原来直接返回 false，等于
     * "软解开关点了没用"，取到的其实是列表里第一个条目。
     */
    private fun MediaCodecInfo.isSoftwareOnlyDecoder(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            isSoftwareOnly
        } else {
            isSoftwareDecoderName(name)
        }

    /**
     * 取**硬件解码器**。
     *
     * ⚠️ API 29 以下没有 `isHardwareAccelerated`，只能按命名约定反着判断
     * （不是已知的软解前缀就当硬件）。**这一步不能省**：省掉就会落到
     * `usable.first()`——列表里**任意**一个条目。
     *
     * 实车（Android 9 / QUALCOMM CS11）取到的那个声明上限只有 `256x256`，
     * 于是 1920x1080 被判成"超限"、一路退到 96 宽仍不被接受，最后打出一句
     * 误导性极强的
     * ```
     * video size clamp exhausted codec=video/avc requested=1920x1080@60 decoderMax=256x256
     * ```
     * 而真正被 `MediaCodec.configure` 用的是 `OMX.qcom.video.decoder.avc`
     * （上限 1920x1088，实车 1920x1080@30 跑得好好的，`video decoder rendered
     * first frame` 就在同一份日志里）。
     *
     * 后果不只是日志难看：如果请求尺寸落在那个假上限**之内**（例如用户把画布
     * 调到 256 宽以下），限流器会**照着假上限把画面缩成邮票**。
     */
    private fun MediaCodecInfo.isHardwareAcceleratedDecoder(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            isHardwareAccelerated
        } else {
            !isSoftwareDecoderName(name)
        }

    /**
     * 按**命名约定**判断软解。纯函数，所以能直接单测（`MediaCodecInfo` 在 JVM 上造不出来）。
     *
     * 前缀取自 AOSP 与各厂商的既有约定：`OMX.google.*` / `c2.android.*` 是 AOSP 软解，
     * `*ffmpeg*` 是常见的第三方软解。QTI 硬解是 `OMX.qcom.*` / `c2.qti.*`，
     * 不在这个列表里。
     */
    internal fun isSoftwareDecoderName(name: String): Boolean =
        SOFTWARE_DECODER_PREFIXES.any { name.startsWith(it, ignoreCase = true) }

    /** 选中的解码器：名字 + 能力。名字只为日志与排障。 */
    private data class DecoderChoice(
        val name: String,
        val capabilities: MediaCodecInfo.VideoCapabilities,
    )

    internal data class FitResult(
        val widthPixels: Int,
        val heightPixels: Int,
        val fps: Int,
    )

    /**
     * [clamp] 的结果：生效画布 + **生效帧率**。
     *
     * 把帧率从"只用来查询、查完丢掉"改成"查完带回来"——
     * 调用方（`CarPlaySessionBridge`）据此声明 `AirPlayConfig`，
     * 让 iPhone 按车机**真跑得动**的帧率来编码。
     */
    data class ClampResult(
        val canvas: CarVideoCanvas,
        val fps: Int,
    )

    private const val TAG = "xcertplay-usb"

    /**
     * `(解码器名 + mime) -> videoCapabilities` 的进程内缓存（2026-10-05）。
     *
     * 实车 CS11 上，缺了它一个 `clamp()` 就要在能力表上花掉 10.7 秒，把整条
     * 连接链堵住。见 [cachedVideoCapabilities]。
     */
    private val capabilitiesCache =
        java.util.concurrent.ConcurrentHashMap<String, Result<MediaCodecInfo.VideoCapabilities?>>()

    private const val DEFAULT_FPS = 60
    private const val MINIMUM_FPS = AirPlayDisplaySettings.MIN_FPS
    private const val MINIMUM_EDGE = 96
    private const val FEATURE_SECURE_PLAYBACK = "secure-playback"
    private const val SECURE_NAME_MARKER = ".secure"

    /**
     * 解码器声明的上限低于「720p 短边」时视为**不可信的能力表**（2026-10-05，CS11 假上限）。
     *
     * 2020 年后的量产车机/手机不可能只有 720p 以下硬解；声明成 256x256 必是 ROM bug
     * 或未接真实硬件的占位条目。用于 [decoderChoice] 剔除假条目。
     */
    private const val CREDIBLE_MIN_EDGE = 720

    /**
     * 已知**软解**的命名前缀。用来在 API 29 以下替代 `isSoftwareOnly` /
     * `isHardwareAccelerated`（见 [isSoftwareDecoderName]）。
     *
     * `OMX.google.*` / `c2.android.*` 是 AOSP 软解；`*ffmpeg*` 是常见第三方软解。
     * QTI 的硬解是 `OMX.qcom.*` / `c2.qti.*`，刻意不在这里。
     */
    private val SOFTWARE_DECODER_PREFIXES =
        listOf("OMX.google.", "c2.android.", "OMX.ffmpeg.", "c2.ffmpeg.")
}
