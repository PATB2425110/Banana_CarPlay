package com.dbtool.carplay.ui.screens

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.Text
import androidx.compose.material3.Slider
import com.dbtool.carplay.api.projection.InstrumentCards
import com.dbtool.carplay.api.projection.InstrumentCardSettings
import com.dbtool.carplay.ui.components.*
import kotlin.math.roundToInt

/** Separate settings group: positions use instrument 1920x720 pixels, never theme coordinates. */
@Composable
internal fun IndependentInstrumentCardsGroup() {
    val context = LocalContext.current
    var value by remember { mutableStateOf(InstrumentCards.load(context)) }
    fun save(next: InstrumentCardSettings) { value = next.bounded(); InstrumentCards.save(context, value) }
    SectionHeader(title = "独立仪表卡片", subtitle = "叠加在地图上方；坐标基于1920×720，保存后立即生效。")
    CarCard {
        CarSettingSwitch(title = "歌曲信息与歌词卡", checked = value.musicEnabled,
            onCheckedChange = { save(value.copy(musicEnabled = it)) })
        Text("歌曲卡横向位置：${value.musicX.roundToInt()} px")
        Slider(value.musicX, { save(value.copy(musicX = it)) }, valueRange = 0f..1300f)
        Text("歌曲卡纵向位置：${value.musicY.roundToInt()} px")
        Slider(value.musicY, { save(value.copy(musicY = it)) }, valueRange = 0f..620f)
    }
}
