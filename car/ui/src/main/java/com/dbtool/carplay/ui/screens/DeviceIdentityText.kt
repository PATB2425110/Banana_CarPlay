package com.dbtool.carplay.ui.screens

/** Used by the unrelated block page; retained without any theme/activation UI. */
fun maskVinForDisplay(vin: String?): String {
    val text = vin?.trim().orEmpty()
    if (text.isEmpty()) return "未识别"
    if (text.length <= 9) return text.take(3) + "***"
    return text.substring(0, 3) + "***" + text.substring(text.length - 6)
}
