package com.dbtool.carplay.api.display

/**
 * HUD 输出模式。
 *
 * 本立项只开放镜像主屏 110 与复用仪表 111 两种模式；`HudOnly` 是槽位策略中的
 * 未来产品分支，不是可在这里选择的运行时输出模式。
 */
enum class CarHudDisplayMode {
    MIRROR_110,
    SHARED_111,
}

/** 纯像素矩形，坐标原点为目标 display 的左上角。 */
data class HudBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    init {
        require(right >= left) { "right must be >= left" }
        require(bottom >= top) { "bottom must be >= top" }
    }

    val widthPixels: Int get() = right - left
    val heightPixels: Int get() = bottom - top
}

/**
 * 源画面裁剪，四边使用 0..1 的归一化坐标。
 *
 * 契约层只描述裁剪范围，不负责生成纹理矩阵。
 */
data class HudCropSpec(
    val leftFraction: Float = 0f,
    val topFraction: Float = 0f,
    val rightFraction: Float = 1f,
    val bottomFraction: Float = 1f,
) {
    init {
        require(leftFraction in 0f..1f) { "leftFraction must be in [0, 1]" }
        require(topFraction in 0f..1f) { "topFraction must be in [0, 1]" }
        require(rightFraction in 0f..1f) { "rightFraction must be in [0, 1]" }
        require(bottomFraction in 0f..1f) { "bottomFraction must be in [0, 1]" }
        require(rightFraction > leftFraction) { "crop width must be > 0" }
        require(bottomFraction > topFraction) { "crop height must be > 0" }
    }
}

/** 缩放方式。实际矩阵在输出层计算，本契约只存用户配置。 */
enum class HudScaleMode {
    STRETCH,
    FIT,
    FILL,
}

data class HudScaleSpec(
    val mode: HudScaleMode = HudScaleMode.FIT,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
) {
    init {
        require(scaleX > 0f) { "scaleX must be > 0" }
        require(scaleY > 0f) { "scaleY must be > 0" }
    }
}

/** 视频在目标 display 内的摆放边界。 */
data class HudPlacementSpec(
    val bounds: HudBounds,
)

/** 四边遮罩宽度，单位为目标 display 像素。 */
data class HudEdgeMaskSpec(
    val leftPixels: Int = 0,
    val topPixels: Int = 0,
    val rightPixels: Int = 0,
    val bottomPixels: Int = 0,
) {
    init {
        require(leftPixels >= 0) { "leftPixels must be >= 0" }
        require(topPixels >= 0) { "topPixels must be >= 0" }
        require(rightPixels >= 0) { "rightPixels must be >= 0" }
        require(bottomPixels >= 0) { "bottomPixels must be >= 0" }
    }
}

/** 目标屏判别证据。一个目标可以同时命中多项。 */
enum class HudDisplayDiscriminator {
    HUD_COMPONENT,
    ZERO_WINDOW,
    GEOMETRY_MATCH,
    FLAGS_MATCH,
    USER_CONFIRMED,
}

/**
 * HUD 目标屏的数据契约。
 *
 * [component] 使用标准 `package/class` 字符串，避免契约层依赖 Android
 * `ComponentName`。display 没有 Android 窗口时 [component] 可以为 null。
 */
data class CarHudTarget(
    val displayId: Int,
    val bounds: HudBounds,
    val component: String? = null,
    val hasAndroidWindow: Boolean = component != null,
    val densityDpi: Int? = null,
    val refreshRateHz: Float? = null,
    val displayFlags: Int = 0,
    val discriminators: Set<HudDisplayDiscriminator> = emptySet(),
)

/**
 * 物理可见性只能由现场肉眼确认或专项检查置为 [CONFIRMED]。
 * `addView` 成功与收到首帧都不能替代它。
 */
enum class HudVisibleOnHud {
    UNCONFIRMED,
    CONFIRMED,
}

/** HUD 各阶段状态；相邻布尔量不能互相替代。 */
data class CarHudDisplayStatus(
    val streamActive: Boolean = false,
    val configReady: Boolean = false,
    val windowAttached: Boolean = false,
    val rendering: Boolean = false,
    val visibleOnHud: HudVisibleOnHud = HudVisibleOnHud.UNCONFIRMED,
    val lastFailure: String? = null,
)
