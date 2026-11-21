package com.dbtool.carplay.ui.screens

import com.dbtool.carplay.api.navigation.CarNavigationSnapshot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Physical pixel geometry, independent of display density and font scaling. */
internal object ImmersiveGlassCardLayout {
    const val WIDTH = 550
    const val HEIGHT = 300
    const val LEFT = 60
    const val GAP = 35
    const val TOP = 75
    const val INSTRUCTION_OFFSET_Y = -15f
    const val ARROW_OFFSET_X = -15f
    const val ARROW_OFFSET_Y = -20f
    const val COVER_LEFT = 32f
    const val COVER_TOP = 30f
    const val COVER_SIZE = 102f
    const val COVER_PROGRESS_GAP = 24f
    const val PROGRESS_TOP = COVER_TOP + COVER_SIZE + COVER_PROGRESS_GAP
    const val PROGRESS_BOTTOM = PROGRESS_TOP + 6f
    const val MEDIA_ICON_TOP = PROGRESS_BOTTOM + 30f
    const val MEDIA_ICON_BOTTOM = MEDIA_ICON_TOP + 30f
    const val MUSIC_HEIGHT = 252
    const val PHONE_STATUS_HEIGHT = 100
    const val MUSIC_TEXT_LEFT = 158f
    const val TOTAL_HEIGHT = HEIGHT + PHONE_STATUS_HEIGHT + MUSIC_HEIGHT + GAP * 2
    // 64px × 1.15, rounded to whole physical pixels; preserve the original hit-area centre.
    const val MEDIA_HIT_SIZE = 74
    const val MEDIA_HIT_CENTER_Y = 209
    fun mediaHitLeft(centerX: Int): Int = centerX - MEDIA_HIT_SIZE / 2
    fun mediaHitTop(): Int = MEDIA_HIT_CENTER_Y - MEDIA_HIT_SIZE / 2
    fun top(): Int = TOP
    fun phoneStatusTop(): Int = TOP + HEIGHT + GAP
    fun musicTop(): Int = phoneStatusTop() + PHONE_STATUS_HEIGHT + GAP
    fun canFitNavigation(width: Int, height: Int) = width >= LEFT + WIDTH && height >= TOP + HEIGHT
    fun canFitPhoneStatus(width: Int, height: Int) =
        width >= LEFT + WIDTH && height >= phoneStatusTop() + PHONE_STATUS_HEIGHT
    fun canFit(width: Int, height: Int) = width >= LEFT + WIDTH && height >= TOP + TOTAL_HEIGHT
}

/** One centered row in the compact phone card; all coordinates are physical pixels. */
internal object GlassPhoneStatusLayout {
    const val CENTER_Y = ImmersiveGlassCardLayout.PHONE_STATUS_HEIGHT / 2f
    const val CELL_WIDTH = ImmersiveGlassCardLayout.WIDTH / 3f
    const val SIGNAL_CENTER_X = CELL_WIDTH / 2f
    const val BATTERY_CENTER_X = CELL_WIDTH * 1.5f
    const val CURRENT_CENTER_X = CELL_WIDTH * 2.5f
    const val SIGNAL_STEP = 22f
    const val SIGNAL_BAR_WIDTH = 14f
    const val SIGNAL_WIDTH = SIGNAL_STEP * 4f + SIGNAL_BAR_WIDTH
    const val SIGNAL_LEFT = SIGNAL_CENTER_X - SIGNAL_WIDTH / 2f
    const val SIGNAL_BOTTOM = CENTER_Y + 21f
    const val BATTERY_WIDTH = 56f
    const val BATTERY_HEIGHT = 30f
    const val BATTERY_TERMINAL_EXTENT = 6f
    const val BATTERY_VALUE_GAP = 14f
    const val VALUE_TEXT_SIZE = 32f

    // Center the complete battery + percentage group, not just the battery body.
    fun batteryLeft(valueTextWidth: Float): Float =
        BATTERY_CENTER_X - (BATTERY_WIDTH + BATTERY_TERMINAL_EXTENT +
            BATTERY_VALUE_GAP + valueTextWidth) / 2f

    fun batteryValueLeft(valueTextWidth: Float): Float =
        batteryLeft(valueTextWidth) + BATTERY_WIDTH + BATTERY_TERMINAL_EXTENT + BATTERY_VALUE_GAP
}

/** Convert the phone's mA value exactly to A, without locale commas or rounding. */
internal fun glassPhoneCurrentText(currentMa: Int?): String =
    currentMa?.takeIf { it in 0..65535 }?.let {
        java.math.BigDecimal.valueOf(it.toLong(), 3).stripTrailingZeros().toPlainString() + "A"
    } ?: "—A"

internal fun glassPhoneSignalBars(strength: Int?): List<Boolean> =
    List(5) { index -> strength != null && strength in 0..5 && index < strength }

internal data class GlassLane(val bearings: List<Int>, val highlight: Int?, val unknown: Boolean)

internal fun glassPhoneBatteryText(level: Int?): String =
    level?.takeIf { it in 0..100 }?.let { "$it%" } ?: "—%"

internal fun glassPhoneBatteryFill(level: Int?): Float? =
    level?.takeIf { it in 0..100 }?.let { it / 100f }

/** Low battery takes priority even while charging; exactly 20 is not below 20. */
internal fun glassPhonePowerColor(level: Int?, chargingState: Int?): Int = when {
    level != null && level in 0..19 -> 0xffff5252.toInt()
    chargingState == 1 -> 0xff4cdb78.toInt()
    else -> 0xffffffff.toInt()
}

/** Presentation only; accepts the same bounded bearing domain as the existing cluster lane view. */
internal fun glassLaneBearing(angle: Int): Int? {
    if (angle !in -360..360) return null
    val wrapped = if (angle < 0) angle + 360 else angle
    return if (wrapped >= 180) wrapped - 360 else wrapped
}

internal fun glassLanes(snapshot: CarNavigationSnapshot?): List<GlassLane> =
    snapshot?.takeIf { it.routeActive && it.guidanceState !in 3..5 }?.lanes.orEmpty().sortedBy { it.index }.take(16).map { lane ->
        val angles = lane.angles.take(8)
        GlassLane(
            bearings = angles.mapNotNull(::glassLaneBearing).distinct(),
            highlight = lane.highlightAngle?.let(::glassLaneBearing),
            unknown = angles.isEmpty() || angles.any { glassLaneBearing(it) == null },
        )
    }

internal data class GlassNavigationText(
    val distance: String, val road: String, val remaining: String, val arrival: String,
)

internal fun glassNavigationText(snapshot: CarNavigationSnapshot?): GlassNavigationText {
    val s = snapshot?.takeIf { it.routeActive && it.hasContent }
    fun distance(m: Int?): String = when {
        m == null || m < 0 -> "—"
        m < 1000 -> "${m}米"
        else -> String.format(Locale.ROOT, "%.1f公里", m / 1000.0)
    }
    fun suppliedDistance(value: String?, units: Int?, fallback: Int?): String {
        val suffix = when (units) { 0 -> "公里"; 1 -> "英里"; 2 -> "米"; 3 -> "码"; 4 -> "英尺"; else -> null }
        return if (!value.isNullOrBlank() && suffix != null) value.trim() + suffix else distance(fallback)
    }
    val road = listOf(s?.nextRoad, s?.currentRoad, s?.maneuverDescription)
        .firstOrNull { !it.isNullOrBlank() }?.trim() ?: "等待导航信息"
    val duration = s?.routeSeconds?.takeIf { it >= 0 }?.let {
        val minutes = (it.toLong() + 59) / 60
        if (minutes >= 60) "${minutes / 60}小时${minutes % 60}分钟" else "${minutes}分钟"
    } ?: "—"
    val arrival = s?.arrivalEpochSeconds?.takeIf { it > 0 && it <= Long.MAX_VALUE / 1000 }?.let {
        SimpleDateFormat("HH:mm", Locale.ROOT).format(Date(it * 1000)) + "到"
    } ?: "— 到"
    val instruction = when (s?.guidanceState) {
        3 -> "正在加载路线"
        4 -> "正在定位"
        5 -> "正在重新规划路线"
        else -> road
    }
    return GlassNavigationText(
        suppliedDistance(s?.maneuverDistanceDisplay, s?.maneuverDistanceUnits, s?.segmentMeters),
        instruction,
        "${suppliedDistance(s?.routeDistanceDisplay, s?.routeDistanceUnits, s?.routeMeters)} · $duration",
        arrival,
    )
}
