package com.dbtool.carplay.ui.screens

import com.dbtool.carplay.api.navigation.CarNavigationSnapshot
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Text projection for the local navigation card; kept pure so edge cases are unit-testable. */
fun navigationHeadline(snapshot: CarNavigationSnapshot): String =
    snapshot.maneuverDescription.cleanNavigationText()
        ?: snapshot.currentRoad.cleanNavigationText()
        ?: "导航进行中"

fun navigationDistanceText(meters: Int?): String? {
    val distance = meters?.takeIf { it >= 0 } ?: return null
    return if (distance >= 1_000) {
        String.format(Locale.CHINA, "%.1f 公里", distance / 1_000.0)
    } else {
        "$distance 米"
    }
}

fun navigationDurationText(seconds: Int?): String? {
    val duration = seconds?.takeIf { it >= 0 } ?: return null
    if (duration == 0) return "即将到达"
    if (duration < 60) return "不到 1 分钟"
    val totalMinutes = duration / 60
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours == 0 -> "$totalMinutes 分钟"
        minutes == 0 -> "$hours 小时"
        else -> "$hours 小时 $minutes 分钟"
    }
}

fun navigationArrivalText(
    epochSeconds: Long?,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String? {
    val seconds = epochSeconds?.takeIf { it > 0L } ?: return null
    return runCatching {
        ARRIVAL_FORMAT.format(Instant.ofEpochSecond(seconds).atZone(zoneId))
    }.getOrNull()
}

fun navigationDestinationText(snapshot: CarNavigationSnapshot): String? =
    snapshot.destination.cleanNavigationText()
        ?: snapshot.nextRoad.cleanNavigationText()

private fun String?.cleanNavigationText(): String? =
    this?.trim()?.takeIf(String::isNotEmpty)

private val ARRIVAL_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA)
