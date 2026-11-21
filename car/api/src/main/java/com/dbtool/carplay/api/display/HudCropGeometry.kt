package com.dbtool.carplay.api.display

import kotlin.math.abs

/**
 * HUD 裁剪几何（2026-10-22）。
 *
 * 三部分：
 * 1. [HudCropPreset] —— 预设裁剪矩形（配置默认值用）
 * 2. [sanitizeHudCrop] —— 坏值兜底（SharedPreferences 读出来可能非法）
 * 3. [plan] —— 由裁剪矩形 + 视图尺寸算 `TextureView.setTransform` 需要的矩阵参数
 *
 * ⚠️ 关于第 3 部分：HUD 是 1024×274、源 1920×1080、60Hz。CPU 逐帧 Bitmap 裁剪
 * 每帧要拷约 8 MB，必然掉帧；`TextureView.setTransform` 是 GPU 纹理矩阵，由
 * SurfaceFlinger/HWUI 在合成时应用，零额外拷贝。所以这里只算矩阵，不做像素操作。
 *
 * ⚠️ **整个 HUD 投屏方向的前提（display 3 能出画）在 2026-10-22 实车被否定**：
 * 该屏 `mHasContent` 恒为 false，Presentation `rendering=true` 但玻璃零变化。
 * 本文件保留是因为 `CarSessionSettings.hudCrop` 等已接线；是否继续用待车主定。
 */
object HudCropGeometry {

    /** CarPlay 源画面宽度（像素）。 */
    const val SOURCE_WIDTH_PIXELS: Int = 1920

    /** CarPlay 源画面高度（像素）。 */
    const val SOURCE_HEIGHT_PIXELS: Int = 1080

    /** HUD 目标屏宽度（像素）。 */
    const val HUD_WIDTH_PIXELS: Int = 1024

    /** HUD 目标屏高度（像素）。 */
    const val HUD_HEIGHT_PIXELS: Int = 274

    /** HUD 目标屏宽高比（≈3.74:1）。设置页预览与说明文字都用它。 */
    val HUD_ASPECT: Float = HUD_WIDTH_PIXELS.toFloat() / HUD_HEIGHT_PIXELS

    /**
     * 矩阵计划：先按 [scaleX]/[scaleY] 绕 ([pivotX],[pivotY]) 缩放，再平移。
     *
     * 与 `TextureView.setTransform` 的语义一致（矩阵作用于**视图坐标**）。
     */
    data class Plan(
        val scaleX: Float,
        val scaleY: Float,
        val pivotX: Float,
        val pivotY: Float,
        val translateX: Float,
        val translateY: Float,
        /** 裁剪内容缩放后占据的宽度（视图坐标）。STRETCH 时等于 viewWidth。 */
        val destinationWidth: Float,
        /** 裁剪内容缩放后占据的高度（视图坐标）。STRETCH 时等于 viewHeight。 */
        val destinationHeight: Float,
    )

    /**
     * 由裁剪矩形与视图尺寸推导矩阵参数。
     *
     * 推导：
     * - 裁剪矩形在源图里占 `cropW × cropH`（像素）
     * - 目标是让这块区域铺满 `viewWidth × viewHeight`
     * - `TextureView` 默认把整幅源图铺满视图 ⇒ 需要放大 `sourceW / cropW` 倍
     *   才能让裁剪区占满视图宽度，纵向同理
     * - 放大后裁剪区左上角会跑出视图，用平移把它拉回 (0,0)
     *
     * [HudScaleMode]:
     * - `STRETCH` —— 两轴独立缩放（会变形，但铺满）
     * - `FIT` —— 等比取小（保比例，可能留黑边）
     * - `FILL` —— 等比取大（保比例，边缘被裁）
     */
    fun plan(
        sourceWidth: Int,
        sourceHeight: Int,
        viewWidth: Int,
        viewHeight: Int,
        crop: HudCropSpec,
        mode: HudScaleMode,
    ): Plan {
        val safeSourceW = sourceWidth.coerceAtLeast(1)
        val safeSourceH = sourceHeight.coerceAtLeast(1)
        val safeViewW = viewWidth.coerceAtLeast(1).toFloat()
        val safeViewH = viewHeight.coerceAtLeast(1).toFloat()

        val cropLeftPx = crop.leftFraction * safeSourceW
        val cropTopPx = crop.topFraction * safeSourceH
        val cropWPx = ((crop.rightFraction - crop.leftFraction) * safeSourceW).coerceAtLeast(1f)
        val cropHPx = ((crop.bottomFraction - crop.topFraction) * safeSourceH).coerceAtLeast(1f)

        // ⚠️ 2026-10-22 修正：TextureView.setTransform 作用于**视图坐标**，
        // 而纹理默认已把整幅源图铺满视图。所以要把裁剪区放大到铺满视图，
        // 需要的倍数是「源宽 / 裁剪宽」（视图坐标下），不是「视图宽 / 裁剪宽」。
        // 反例：CENTERED 裁剪宽 1267px、源 1920、视图 1024
        //   正确 1920/1267 ≈ 1.515 ；错误 1024/1267 ≈ 0.808（画面反而缩小）
        val stretchX = safeSourceW / cropWPx
        val stretchY = safeSourceH / cropHPx

        val (sx, sy) = when (mode) {
            HudScaleMode.STRETCH -> stretchX to stretchY
            HudScaleMode.FIT -> {
                val s = minOf(stretchX, stretchY)
                s to s
            }
            HudScaleMode.FILL -> {
                val s = maxOf(stretchX, stretchY)
                s to s
            }
        }

        // 缩放后裁剪区左上角的视图坐标：
        // 源图 cropLeft 在默认渲染下映射到视图 cropLeft*(viewW/sourceW)，
        // 再乘以缩放 sx(=sourceW/cropW) ⇒ cropLeft*viewW/cropW。
        val scaledLeft = cropLeftPx * safeViewW / cropWPx
        val scaledTop = cropTopPx * safeViewH / cropHPx

        // 等比模式下画面可能小于视图，居中摆放
        // 等比模式下裁剪区缩放后的视图尺寸：源裁剪宽 × (viewW/sourceW) × s
        val scaledCropW = cropWPx * (safeViewW / safeSourceW) * sx
        val scaledCropH = cropHPx * (safeViewH / safeSourceH) * sy
        val centerDx = (safeViewW - scaledCropW) / 2f
        val centerDy = (safeViewH - scaledCropH) / 2f

        return if (mode == HudScaleMode.STRETCH) {
            Plan(
                scaleX = sx,
                scaleY = sy,
                pivotX = 0f,
                pivotY = 0f,
                translateX = -scaledLeft,
                translateY = -scaledTop,
                destinationWidth = scaledCropW,
                destinationHeight = scaledCropH,
            )
        } else {
            Plan(
                scaleX = sx,
                scaleY = sy,
                pivotX = 0f,
                pivotY = 0f,
                translateX = centerDx - scaledLeft,
                translateY = centerDy - scaledTop,
                destinationWidth = scaledCropW,
                destinationHeight = scaledCropH,
            )
        }
    }
}

/**
 * 裁剪四边兜底。
 *
 * SharedPreferences 里的值可能是旧版本写的、被手工改坏的、或 NaN。
 * 规则：
 * - 非有限值 → 回落默认
 * - 各自夹到 `0..1`
 * - 若 `right <= left` 或 `bottom <= top` → 整个回落默认矩形
 */
fun sanitizeHudCrop(
    leftFraction: Float,
    topFraction: Float,
    rightFraction: Float,
    bottomFraction: Float,
): HudCropSpec {
    val d = HudCropSpec()
    fun fix(v: Float, fallback: Float): Float =
        if (v.isNaN() || v.isInfinite()) fallback else v.coerceIn(0f, 1f)

    val l = fix(leftFraction, d.leftFraction)
    val t = fix(topFraction, d.topFraction)
    val r = fix(rightFraction, d.rightFraction)
    val b = fix(bottomFraction, d.bottomFraction)

    return if (r > l && b > t) HudCropSpec(l, t, r, b) else d
}

/**
 * HUD 源画面裁剪预设。
 *
 * HUD 目标屏 1024×274（≈3.74:1），CarPlay 源 1920×1080（≈1.78:1），
 * 直接缩放会严重压扁，必须裁掉上下非导航区域。
 *
 * ⚠️ 这两个预设是设计建议值，**尚未实车验证**。
 */
enum class HudCropPreset(val crop: HudCropSpec) {
    /** 全宽 + 纵向中下段（覆盖导航提示条与前方路况）。 */
    FULL_WIDTH(
        HudCropSpec(
            leftFraction = 0f,
            topFraction = 0.55f,
            rightFraction = 1f,
            bottomFraction = 0.803f,
        ),
    ),

    /** 严格 3.74:1、水平居中。 */
    CENTERED(
        HudCropSpec(
            leftFraction = 0.17f,
            topFraction = 0.343f,
            rightFraction = 0.83f,
            bottomFraction = 0.657f,
        ),
    ),
}

/**
 * 浮点比较容差。
 *
 * 滑杆是连续量，SharedPreferences 也按 Float 存取；预设匹配不能要求逐位相等，
 * 否则「归位到预设」的 chip 永远选不中。
 */
private const val HUD_CROP_EPSILON: Float = 1e-3f

/**
 * 裁剪框单边下限（归一化）。
 *
 * [HudCropSpec] 的 `init` 要求 `right > left`、`bottom > top`；Float 舍入可能让
 * 极小的宽度在平移后塌成 0，直接抛 `IllegalArgumentException`。所有移动/改尺寸的
 * 入口都夹到这个下限。
 */
private const val MIN_HUD_CROP_EXTENT: Float = 0.01f

/** 四边都在 [epsilon] 容差内即视为同一个裁剪框（设置页预设 chip 的选中判定）。 */
fun HudCropSpec.approximatelyEquals(
    other: HudCropSpec,
    epsilon: Float = HUD_CROP_EPSILON,
): Boolean =
    abs(leftFraction - other.leftFraction) <= epsilon &&
        abs(topFraction - other.topFraction) <= epsilon &&
        abs(rightFraction - other.rightFraction) <= epsilon &&
        abs(bottomFraction - other.bottomFraction) <= epsilon

/** 裁剪框水平中心（归一化 0..1）。 */
val HudCropSpec.centerX: Float
    get() = (leftFraction + rightFraction) / 2f

/** 裁剪框垂直中心（归一化 0..1）。 */
val HudCropSpec.centerY: Float
    get() = (topFraction + bottomFraction) / 2f

/** 裁剪框宽度（归一化 0..1，恒 > 0）。 */
val HudCropSpec.widthFraction: Float
    get() = rightFraction - leftFraction

/**
 * 保持宽高不变，把裁剪框中心移到 ([xFraction], [yFraction])。
 *
 * 中心靠近边缘时不会把框推出源画面，而是贴着边停下（夹在 `0..1`）。
 * 非有限值（NaN/Inf）会退回当前中心，避免 [HudCropSpec] 的 `init` 抛异常。
 */
fun HudCropSpec.centerAt(xFraction: Float, yFraction: Float): HudCropSpec {
    val width = widthFraction.coerceAtLeast(MIN_HUD_CROP_EXTENT)
    val height = (bottomFraction - topFraction).coerceAtLeast(MIN_HUD_CROP_EXTENT)
    val x = if (xFraction.isFinite()) xFraction else centerX
    val y = if (yFraction.isFinite()) yFraction else centerY
    val left = (x - width / 2f).coerceIn(0f, 1f - width)
    val top = (y - height / 2f).coerceIn(0f, 1f - height)
    return HudCropSpec(
        leftFraction = left,
        topFraction = top,
        rightFraction = left + width,
        bottomFraction = top + height,
    )
}

/**
 * 保持中心与高度不变，把裁剪框宽度改成 [newWidthFraction]（归一化）。
 *
 * 宽度夹在 `MIN_HUD_CROP_EXTENT..1`；中心贴边时整体平移到画面内，仍保持原中心
 * 尽量不变。设置页「大小」滑杆走这里：数值越小 = 裁得越窄 = 画面放得越大。
 */
fun HudCropSpec.scaledToWidth(newWidthFraction: Float): HudCropSpec {
    val requested = if (newWidthFraction.isFinite()) newWidthFraction else widthFraction
    val width = requested.coerceIn(MIN_HUD_CROP_EXTENT, 1f)
    val left = (centerX - width / 2f).coerceIn(0f, 1f - width)
    return HudCropSpec(
        leftFraction = left,
        topFraction = topFraction,
        rightFraction = left + width,
        bottomFraction = bottomFraction,
    )
}
