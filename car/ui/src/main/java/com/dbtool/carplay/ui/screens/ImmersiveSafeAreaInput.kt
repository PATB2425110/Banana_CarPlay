package com.dbtool.carplay.ui.screens

import com.dbtool.carplay.api.settings.CarSettingsSafeArea

/** Same rectangle/clamping semantics as the instrument editor, independent 1080px canvas. */
internal fun immersiveSafeAreaFromText(x: String, y: String, width: String, height: String): CarSettingsSafeArea? {
    val left = x.toIntOrNull() ?: return null
    val top = y.toIntOrNull() ?: return null
    val w = width.toIntOrNull() ?: return null
    val h = height.toIntOrNull() ?: return null
    if (left < 0 || top < 0 || w <= 0 || h <= 0) return null
    return CarSettingsSafeArea(left, top, w, h).normalized(1920, 1080)
}
