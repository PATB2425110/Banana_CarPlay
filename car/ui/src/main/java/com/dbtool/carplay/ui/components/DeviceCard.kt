package com.dbtool.carplay.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.dbtool.carplay.ui.CarStatusIndicator
import com.dbtool.carplay.ui.theme.CarIcons
import com.dbtool.carplay.ui.theme.CarTheme

/**
 * 设备卡片：一台与本机发生过关系的 iPhone。
 *
 * 与主页状态页的区别：状态页回答"现在怎么样"，
 * 设备卡回答"有哪些设备、哪个是你"。
 * 因此设备卡的信息密度更高（名称 + 型号 + 链路 + 最近状态），
 * 但不放大字号，避免主次不分。
 *
 * 结构固定为 `[图标徽标] [名称 / 型号·链路] [状态胶囊]` 三段，
 * 与设置行同高同距——同一屏里两类"行"长得一样，是"整体视觉统一"的具体落点。
 */
@Composable
fun CarDeviceCard(
    name: String,
    modifier: Modifier = Modifier,
    model: String? = null,
    transportLabel: String? = null,
    indicator: CarStatusIndicator = CarStatusIndicator.IDLE,
    connected: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val dimens = CarTheme.dimens
    CarCard(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
        selected = connected,
        contentPadding = PaddingValues(dimens.spaceL),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimens.spaceL),
        ) {
            CarIconBadge(
                icon = CarIcons.Device,
                tint = indicatorColor(indicator),
                container = indicatorContainerColor(indicator),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(dimens.spaceXs),
            ) {
                Text(
                    text = name,
                    style = CarTheme.type.titleMedium,
                    color = CarTheme.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val secondary = listOfNotNull(model, transportLabel)
                    .filter { it.isNotBlank() }
                    .joinToString("  ·  ")
                if (secondary.isNotEmpty()) {
                    Text(
                        text = secondary,
                        style = CarTheme.type.bodyMedium,
                        color = CarTheme.colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            when {
                trailing != null -> trailing()
                connected -> CarStatusPill(text = "已连接", indicator = CarStatusIndicator.READY)
                else -> CarStatusPill(text = "未连接", indicator = indicator)
            }
        }
    }
}
