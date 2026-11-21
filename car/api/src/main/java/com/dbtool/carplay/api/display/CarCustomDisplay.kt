package com.dbtool.carplay.api.display

import kotlin.math.roundToInt

/**
 * 自定义分辨率的判据与换算（纯函数，可单测）。
 *
 * ## 它解决什么
 *
 * 默认链路是"UI 视频 View 实测尺寸 × 缩放系数（0.1x 步进）"，用户只能**逼近**
 * 目标分辨率。而车机屏的实际参数五花八门（1024x600 / 1280x720 / 1920x720 /
 * 1920x1080 …），滑杆永远落不到正好一档，结果就是拉伸或黑边。
 * 允许直接输入像素与 dpi，才能按实际屏幕参数精确对齐。
 *
 * ## ⚠️ CarPlay 协议里没有 dpi 字段
 *
 * 上游 `AirPlayDisplayConfig` 只有 `widthPixels / heightPixels /
 * widthPhysicalMm / heightPhysicalMm / fps`。iPhone 是按**像素数 + 物理尺寸**
 * 推算界面密度的，所以 dpi 必须换算成毫米再发出去：
 *
 * ```
 * mm = px / dpi * 25.4
 * ```
 *
 * 这也是"给 dpi 就等于给物理尺寸"的由来——1920 px @160 dpi ⇒ 305 mm，
 * @240 dpi ⇒ 203 mm。同一块屏报的物理尺寸越小，iPhone 越会认为它"密度高"，
 * 界面元素就画得越小。**这正是用户要精确控制的东西。**
 *
 * ## 为什么宽高要偶数对齐
 *
 * H.264 / HEVC 的 4:2:0 色度抽样要求宽高是 2 的倍数。奇数尺寸能不能编过
 * 取决于具体编解码器，属于"在别人机器上才炸"的那类风险。这里统一**向下取偶**
 * （1921 → 1920），而不是判成错误：用户填奇数多半是手误或从别处抄来的，
 * 帮他取整比让他重填更有用；设置页会把这个取整说出来。
 */
object CarCustomDisplay {

    /** 最小边。低于这个值 CarPlay 界面已经无法正常排布。 */
    const val MIN_EDGE = 320

    /** 最大边。4K 之上没有车机屏，且会必然撞解码器上限。 */
    const val MAX_EDGE = 4096

    /** dpi 下限：再低就等于报一块几米宽的屏。 */
    const val MIN_DPI = 60

    /** dpi 上限：再高就是手机密度，车机屏用不到。 */
    const val MAX_DPI = 480

    /**
     * 报给 iPhone 的物理尺寸区间，与上游
     * `AirPlayDisplaySettings.sanitizeReportedPhysicalMm` 的 1..2000 对齐。
     *
     * 在合法 dpi/边长的组合下这个夹取**不会触发**（极值 4096px @60dpi 也才 1734 mm），
     * 留着是防御性的：上游那道夹取是静默的，我们这边先夹一次至少能保证
     * "我们以为发出去的"与"真正发出去的"是同一个数。
     */
    const val MIN_PHYSICAL_MM = 1
    const val MAX_PHYSICAL_MM = 2000

    /** 像素尺寸问题。越界才判错；奇数是**提示**不是错误（见 [alignEven]）。 */
    enum class Problem {
        /** 小于 [MIN_EDGE]。 */
        TOO_SMALL,

        /** 大于 [MAX_EDGE]。 */
        TOO_LARGE,
    }

    /** 换算结果：像素（已偶数对齐）+ 物理尺寸（mm）。 */
    data class Resolved(
        val widthPx: Int,
        val heightPx: Int,
        val widthMm: Int,
        val heightMm: Int,
    )

    /**
     * @return `null` = 合法；否则是命中的问题。**空串不算问题**——
     * 输入框清空是编辑过程的中间态，判错会让用户边删边看到红框。
     */
    fun problemOf(value: Int?): Problem? = when {
        value == null -> null
        value < MIN_EDGE -> Problem.TOO_SMALL
        value > MAX_EDGE -> Problem.TOO_LARGE
        else -> null
    }

    /** dpi 的合法性。与像素分开判，因为区间不同、文案也不同。 */
    fun dpiProblemOf(value: Int?): Problem? = when {
        value == null -> null
        value < MIN_DPI -> Problem.TOO_SMALL
        value > MAX_DPI -> Problem.TOO_LARGE
        else -> null
    }

    /** 向下取偶（正数）。H.264/HEVC 4:2:0 要求宽高为 2 的倍数。 */
    fun alignEven(value: Int): Int {
        if (value <= 0) return 0
        return value / 2 * 2
    }

    /** 文本是否含非数字字符（输入框只接受数字，但粘贴可能带别的）。 */
    fun isNumeric(text: String): Boolean = text.all { it.isDigit() }

    /** 给设置页看的中文说明。**要说清差多少**，不要只说"非法"。 */
    fun describe(problem: Problem, label: String, value: Int?): String = when (problem) {
        Problem.TOO_SMALL ->
            "$label 不能小于 $MIN_EDGE，当前 ${value ?: 0}"
        Problem.TOO_LARGE ->
            "$label 不能大于 $MAX_EDGE，当前 ${value ?: 0}"
    }

    /** dpi 的中文说明。 */
    fun describeDpi(problem: Problem, value: Int?): String = when (problem) {
        Problem.TOO_SMALL ->
            "DPI 不能小于 $MIN_DPI，当前 ${value ?: 0}"
        Problem.TOO_LARGE ->
            "DPI 不能大于 $MAX_DPI，当前 ${value ?: 0}"
    }

    /**
     * 把用户填的三个数解析成真正要发出去的四个数。
     *
     * 越界值**先夹到区间内**再对齐，这样即使设置页没拦住（例如老安装里
     * 持久化了一个越界值）也不会把非法尺寸送给 iPhone。
     */
    fun resolve(widthPx: Int, heightPx: Int, dpi: Int): Resolved {
        val safeDpi = dpi.coerceIn(MIN_DPI, MAX_DPI)
        val width = alignEven(widthPx.coerceIn(MIN_EDGE, MAX_EDGE)).coerceAtLeast(MIN_EDGE)
        val height = alignEven(heightPx.coerceIn(MIN_EDGE, MAX_EDGE)).coerceAtLeast(MIN_EDGE)
        val size = physicalSizeMm(width, height, safeDpi)
        return Resolved(
            widthPx = width,
            heightPx = height,
            widthMm = size.widthMm,
            heightMm = size.heightMm,
        )
    }

    /**
     * dpi + 像素 → 物理尺寸（mm），并按上游区间夹取。
     *
     * 单独暴露出来是为了让单测能直接喂数值断言，不必构造整个 [Resolved]。
     */
    fun physicalSizeMm(widthPx: Int, heightPx: Int, dpi: Int): PhysicalSizeMm {
        val safeDpi = dpi.coerceIn(MIN_DPI, MAX_DPI)
        return PhysicalSizeMm(
            widthMm = (widthPx.toDouble() / safeDpi * MM_PER_INCH)
                .roundToInt()
                .coerceIn(MIN_PHYSICAL_MM, MAX_PHYSICAL_MM),
            heightMm = (heightPx.toDouble() / safeDpi * MM_PER_INCH)
                .roundToInt()
                .coerceIn(MIN_PHYSICAL_MM, MAX_PHYSICAL_MM),
        )
    }

    /** 一英寸 = 25.4 毫米。dpi 的定义就是"每英寸多少像素"。 */
    private const val MM_PER_INCH = 25.4
}

/** 物理尺寸（mm）。与上游 `AirPlayPhysicalSizeMm` 同形，但那是上游类型，
 *  `car:api` 不许引用它——契约层与上游解耦是硬规则。 */
data class PhysicalSizeMm(
    val widthMm: Int,
    val heightMm: Int,
)
