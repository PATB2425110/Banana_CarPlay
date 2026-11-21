package com.dbtool.carplay.geely

/** Only the independent music card is shortened; position, height and 1920x720 canvas stay intact. */
internal object ClusterMusicCardLayout {
    const val ORIGINAL_WIDTH = 620f
    const val LENGTH_SCALE = 0.8f
    const val WIDTH = ORIGINAL_WIDTH * LENGTH_SCALE
    const val HEIGHT = 72f
    const val TEXT_PADDING = 18f
    const val TEXT_WIDTH = WIDTH - TEXT_PADDING * 2f
}

/** Keep Unicode code points intact when the reduced card needs an ellipsis. */
internal fun musicCardEllipsize(text: String, maxWidth: Float, measure: (String) -> Float): String {
    if (maxWidth <= 0f) return ""
    if (measure(text) <= maxWidth) return text
    val ellipsis = "…"
    if (measure(ellipsis) > maxWidth) return ""
    var low = 0
    var high = text.codePointCount(0, text.length)
    while (low < high) {
        val mid = (low + high + 1) / 2
        val end = text.offsetByCodePoints(0, mid)
        if (measure(text.substring(0, end) + ellipsis) <= maxWidth) low = mid else high = mid - 1
    }
    return text.substring(0, text.offsetByCodePoints(0, low)) + ellipsis
}
