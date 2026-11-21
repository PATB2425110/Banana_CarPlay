package com.dbtool.carplay.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.dbtool.carplay.api.settings.CarSessionSettings
import com.dbtool.carplay.ui.components.CarButton
import com.dbtool.carplay.ui.components.CarCard
import com.dbtool.carplay.ui.components.CarRowDivider
import com.dbtool.carplay.ui.components.CarSettingAction
import com.dbtool.carplay.ui.components.CarSettingChoice
import com.dbtool.carplay.ui.components.CarSettingSlider
import kotlin.math.roundToInt

/** One expandable entry controls both cards, live and persistent. */
@Composable
internal fun ImmersiveGlassAppearanceSettings(
    settings: CarSessionSettings,
    update: (CarSessionSettings) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val presets = listOf(-1, 0x20242b, 0x000000, 0x334d66, 0x655348, 0xffffff)
    fun label(rgb: Int): String = when (rgb) {
        -1 -> "原始渐变"
        0x20242b -> "深灰"
        0x000000 -> "黑色"
        0x334d66 -> "灰蓝"
        0x655348 -> "暖棕"
        0xffffff -> "白色"
        else -> "自定义"
    }
    val rgb = settings.immersiveGlassColor.takeIf { it in 0..0xffffff } ?: 0x514b4b
    CarCard(modifier = Modifier.fillMaxWidth()) {
        CarSettingAction(
            title = "三卡片颜色与透明度",
            value = "${label(settings.immersiveGlassColor)} · 底色透明度${settings.immersiveGlassTransparency.coerceIn(0, 100)}%",
            onClick = { expanded = !expanded },
        )
        if (expanded) {
            CarRowDivider()
            CarSettingChoice(
                title = "三张卡片共用底色",
                options = if (settings.immersiveGlassColor in presets) presets else presets + settings.immersiveGlassColor,
                selected = settings.immersiveGlassColor,
                onSelect = { update(settings.copy(immersiveGlassColor = it)) },
                label = { label(it) },
            )
            listOf("红色 R" to 16, "绿色 G" to 8, "蓝色 B" to 0).forEach { (title, shift) ->
                CarSettingSlider(
                    title = title,
                    value = ((rgb ushr shift) and 255).toFloat(),
                    valueRange = 0f..255f,
                    steps = 254,
                    valueLabel = ((rgb ushr shift) and 255).toString(),
                    onValueChange = {
                        val channel = it.roundToInt().coerceIn(0, 255)
                        val changed = (rgb and (255 shl shift).inv()) or (channel shl shift)
                        update(settings.copy(immersiveGlassColor = changed))
                    },
                )
            }
            CarSettingSlider(
                title = "底色透明度（0%不透明，100%透明）",
                value = settings.immersiveGlassTransparency.coerceIn(0, 100).toFloat(),
                valueRange = 0f..100f,
                steps = 99,
                valueLabel = "${settings.immersiveGlassTransparency.coerceIn(0, 100)}%",
                onValueChange = { update(settings.copy(immersiveGlassTransparency = it.roundToInt().coerceIn(0, 100))) },
            )
            CarButton(text = "恢复三卡片默认外观",
                onClick = { update(settings.copy(immersiveGlassColor = -1, immersiveGlassTransparency = 15)) })
        }
    }
}
