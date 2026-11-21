package com.dbtool.carplay.ui.cluster

import com.dbtool.carplay.api.display.DialTransform
import com.dbtool.carplay.api.navigation.CarNavigationSnapshot
import com.dbtool.carplay.ui.screens.navigationArrivalText
import com.dbtool.carplay.ui.screens.navigationDistanceText
import java.time.ZoneId
import kotlin.math.min

/**
 * 仪表中央导航卡片的纯计算层。
 *
 * 这里只做三件事：转向码 → 箭头语义、快照 → 显示文本、左右表变换 → 中央可用矩形。
 * 全部不依赖 Android 类型，因此可以直接在 JVM 单测里钉住几何边界与空数据行为。
 */
internal enum class ClusterManeuverKind {
    STRAIGHT,
    LEFT,
    RIGHT,
    UTURN,
    ROUNDABOUT,
    KEEP_LEFT,
    KEEP_RIGHT,
    ARRIVAL,

    /** 未识别的转向码：画通用直行箭头，不猜方向。 */
    GENERIC,
}

/**
 * iPhone 原始转向码 → 箭头语义。
 *
 * 数字表与 `GeelyHudTurnMapping.semantic` 同源（实车验证过的码表）；`:car:ui`
 * 不能依赖平台模块，所以在这里保留一份只读映射。码表新增前，未知值一律
 * [ClusterManeuverKind.GENERIC]。
 */
internal fun clusterManeuverKindOf(maneuverType: Int?): ClusterManeuverKind = when (maneuverType) {
    1, 20, 22, 52 -> ClusterManeuverKind.LEFT
    2, 21, 23, 53 -> ClusterManeuverKind.RIGHT
    3, 5, 11, 51 -> ClusterManeuverKind.STRAIGHT
    4, 18, 26 -> ClusterManeuverKind.UTURN
    6, 19, 7, in 28..46 -> ClusterManeuverKind.ROUNDABOUT
    10, 12, 24, 25, 27 -> ClusterManeuverKind.ARRIVAL
    13 -> ClusterManeuverKind.KEEP_LEFT
    14 -> ClusterManeuverKind.KEEP_RIGHT
    else -> ClusterManeuverKind.GENERIC
}

/** 卡片上要画的文本；字段为 null 表示该行不画，绝不以 `0` / `--` 冒充数据。 */
internal data class ClusterNavigationContent(
    val arrow: ClusterManeuverKind,
    val distanceText: String?,
    val roadText: String?,
    val detailText: String?,
)

/**
 * 快照 → 卡片内容。
 *
 * - 大字：到下一转向的距离（`segmentMeters`），没有就不画；
 * - 路名：转向描述 → 当前路 → 下一段路 → 目的地，取第一个非空；
 * - 小字：剩余路程 + 预计到达，用 `·` 连接。
 *
 * `null`、`hasContent == false`、或所有可见字段都为空时返回 null ——
 * 调用方据此清空卡片，回到纯黑中央区。
 */
internal fun clusterNavigationContentOf(
    snapshot: CarNavigationSnapshot?,
    zoneId: ZoneId = ZoneId.systemDefault(),
): ClusterNavigationContent? {
    val source = snapshot?.takeIf { it.hasContent } ?: return null
    val distance = navigationDistanceText(source.segmentMeters)
    val road = cleanNavigationLine(source.maneuverDescription)
        ?: cleanNavigationLine(source.currentRoad)
        ?: cleanNavigationLine(source.nextRoad)
        ?: cleanNavigationLine(source.destination)
    val remaining = navigationDistanceText(source.routeMeters)?.let { "剩余 $it" }
    val arrival = navigationArrivalText(source.arrivalEpochSeconds, zoneId)?.let { "预计 $it" }
    val detail = listOfNotNull(remaining, arrival)
        .joinToString(" · ")
        .takeIf { it.isNotEmpty() }
    if (distance == null && road == null && detail == null) return null
    return ClusterNavigationContent(
        arrow = clusterManeuverKindOf(source.maneuverType),
        distanceText = distance,
        roadText = road,
        detailText = detail,
    )
}

private fun cleanNavigationLine(value: String?): String? =
    value?.trim()?.takeIf { it.isNotEmpty() }

/**
 * 中央卡片矩形（设计画布坐标，1920×720）。
 *
 * `left/right` 已经把左右表盘的**当前**外接圆算进去（含用户调过的
 * `DialTransform`），并在表盘与卡片之间留出 [ClusterNavigationLayout.DIAL_CLEARANCE]。
 */
internal data class ClusterNavigationRegion(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
    val centerX: Float get() = (left + right) / 2f
    val isEmpty: Boolean get() = width <= 0f || height <= 0f
}

/**
 * 中央可用区计算（纯函数）。
 *
 * ## 为什么不能写死 `x ∈ [780, 1140]`
 *
 * 标称（恒等变换）时左右表外接圆确实把中间挤成
 * `[786.8421, 1133.1579]`；但 2026-10-21 起的出厂默认是两表向外平移约 198px、
 * 放大 1.26 倍，实际空隙会宽很多；用户还可以把表盘往里调。写死会把卡片压到表盘上。
 *
 * 这里按当前变换取每张表**最靠中间的外缘**：
 *
 * ```text
 * 左表右缘 = LEFT_DIAL_CX  + dx + OUTER_VISIBLE_R × scale
 * 右表左缘 = RIGHT_DIAL_CX + dx - OUTER_VISIBLE_R × scale
 * ```
 *
 * 表盘隐藏（`visible=false`）时退到安全边距。中央区再向两侧各让
 * [DIAL_CLEARANCE]，宽度上限 [MAX_WIDTH]（避免默认大空隙时卡片铺得过宽）。
 * 空隙小于 [MIN_WIDTH] 时返回空矩形 —— 宁可不画，也不压表盘。
 */
internal object ClusterNavigationLayout {
    /** 卡片纵向范围（设计画布像素）：在安全边距 150 内侧，避开上下边缘。 */
    const val TOP: Float = 200f
    const val BOTTOM: Float = 520f

    /** 卡片边缘与表盘外接圆之间至少留出的空白。 */
    const val DIAL_CLEARANCE: Float = 24f

    /** 中央空隙再大，卡片也不超过这个宽度（默认布局下空隙约 635px）。 */
    const val MAX_WIDTH: Float = 560f

    /** 低于该宽度不绘制：文字已经放不下，画出来只会压到表盘。 */
    const val MIN_WIDTH: Float = 220f

    fun region(
        leftDialCx: Float,
        rightDialCx: Float,
        outerVisibleRadius: Float,
        left: DialTransform,
        right: DialTransform,
        safeLeft: Float,
        safeRight: Float,
    ): ClusterNavigationRegion {
        val leftEdge = if (left.visible) {
            leftDialCx + left.dx + outerVisibleRadius * left.scale + DIAL_CLEARANCE
        } else {
            safeLeft + DIAL_CLEARANCE
        }
        val rightEdge = if (right.visible) {
            rightDialCx + right.dx - outerVisibleRadius * right.scale - DIAL_CLEARANCE
        } else {
            safeRight - DIAL_CLEARANCE
        }
        val freeWidth = rightEdge - leftEdge
        if (freeWidth < MIN_WIDTH) {
            // 保留一个空矩形：调用方据此完全不画，而不是画到表盘上。
            return ClusterNavigationRegion(leftEdge, TOP, leftEdge, BOTTOM)
        }
        val width = min(freeWidth, MAX_WIDTH)
        val centerX = (leftEdge + rightEdge) / 2f
        return ClusterNavigationRegion(
            left = centerX - width / 2f,
            top = TOP,
            right = centerX + width / 2f,
            bottom = BOTTOM,
        )
    }
}

/**
 * 按像素宽度截断并追加省略号（纯函数，`measure` 由调用方注入）。
 *
 * 用于路名/小字：宁可显示“…”，也不能让文字越过中央区压到表盘。
 */
internal fun ellipsizeToWidth(
    text: String,
    maxWidth: Float,
    measure: (String) -> Float,
): String {
    if (text.isEmpty() || maxWidth <= 0f) return ""
    if (measure(text) <= maxWidth) return text
    val ellipsis = "…"
    if (measure(ellipsis) > maxWidth) return ""
    var low = 0
    var high = text.length
    while (low < high) {
        val middle = (low + high + 1) / 2
        if (measure(text.substring(0, middle) + ellipsis) <= maxWidth) {
            low = middle
        } else {
            high = middle - 1
        }
    }
    return if (low == 0) "" else text.substring(0, low) + ellipsis
}
