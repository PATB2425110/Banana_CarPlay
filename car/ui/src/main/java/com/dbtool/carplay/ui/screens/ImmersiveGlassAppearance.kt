package com.dbtool.carplay.ui.screens

import kotlin.math.roundToInt

/** Background-only appearance, shared by navigation and music; no protocol settings. */
internal fun glassBackgroundColors(rgb: Int, transparency: Int): IntArray {
    val amount = (100 - transparency.coerceIn(0, 100)) / 85f
    val base = if (rgb in 0..0xffffff) {
        intArrayOf(0xd9000000.toInt() or rgb, 0xd9000000.toInt() or rgb, 0xd9000000.toInt() or rgb)
    } else intArrayOf(0xd93d4851.toInt(), 0xd9514b4b.toInt(), 0xe0524e39.toInt())
    return base.map { color ->
        val alpha = ((color ushr 24) * amount).roundToInt().coerceIn(0, 255)
        (color and 0xffffff) or (alpha shl 24)
    }.toIntArray()
}
