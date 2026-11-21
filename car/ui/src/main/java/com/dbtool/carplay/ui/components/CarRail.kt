package com.dbtool.carplay.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import com.dbtool.carplay.ui.theme.CarTheme

/**
 * 设置页双栏**左栏**的导航条目。
 *
 * ## 为什么只有主标题，没有摘要
 *
 * 这是 2026-10-20 定稿的形态（见 `双栏改造方案.md` §0.4）：左栏 320dp、条目高 62dp，
 * 扣掉左右内边距与 24dp 图标后，标题可用宽度只剩约 220dp。原 `SectionEntry` 的摘要
 * （例如「无线 · 本机软件认证 · 热点：…」）在这个宽度里必然截断成两行半，既读不全，
 * 也会把条目撑得高矮不一。摘要统一放到**右栏页头**（[com.dbtool.carplay.ui.screens.SettingsPaneHeader]），
 * 那里有 900dp 以上可用宽度，一行放得下。
 *
 * 因此本组件刻意不复用 [CarSettingAction]：后者把摘要渲染成 description，
 * 而且行高由 [com.dbtool.carplay.ui.theme.CarDimensions.rowMinHeight] 决定，
 * 两个约束都与左栏形态冲突。
 *
 * ## 选中态
 *
 * 底 `primaryContainer`（浅色 `#E6F2FF`）+ 图标/文字取 `primaryText`。
 * 浅色板里 `primaryText == primary == #007AFF`，与定稿图一致；深色板里
 * `primaryText` 是提亮档（`#6AA1FF`），压在 `primaryContainer`（`#172445`）上才读得清——
 * 直接用 `primary` 会在深色下变成深蓝压深蓝。
 *
 * @param style 文字样式。默认 [com.dbtool.carplay.ui.theme.CarTypography.titleSmall]
 *   （基准 16sp Medium ⇒ 目标屏 20sp）；左栏底部「关于 / 恢复默认设置」传 `bodyLarge`
 *   （目标屏约 19sp Regular），与定稿图一致。
 * @param titleColor 覆盖文字色。默认跟随选中态；底部两项显式传主/次文字色。
 */
@Composable
fun CarRailItem(
    title: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: TextStyle? = null,
    titleColor: Color? = null,
    scale: Float = 1f,
) {
    val colors = CarTheme.colors
    val dimens = CarTheme.dimens
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val shape = RoundedCornerShape(dimens.settingsRailItemRadius * scale)
    val baseStyle = style ?: CarTheme.type.titleSmall
    val scaledStyle = baseStyle.copy(fontSize = baseStyle.fontSize * scale, lineHeight = baseStyle.lineHeight * scale)
    val background = when {
        // 2026-10-23 设计系统重构：选中态改用中性的 `selectedBackground`
        // （低对比度灰），不再用蓝色 `primaryContainer`。
        // 车机深色下大面积高饱和色块刺眼，且会把"选中"和"强调"两种语义混在一起。
        selected -> colors.selectedBackground
        pressed -> colors.surfaceVariant
        else -> Color.Transparent
    }
    // 选中态图标与文字同色，取 `onPrimaryContainer`（浅色板 #0056D6 / 深色板 #6AA1FF）。
    //
    // ⚠️ 为什么不用 `primaryText`：日间 `primaryText` 是 #007AFF，压在 `primaryContainer`
    // (#E6F2FF) 上只有 3.54:1，20sp Medium 达不到 WCAG AA 的 4.5:1；
    // `onPrimaryContainer` 正是为"压在 container 上"定义的，日间 #0056D6 → **5.61:1**，
    // 深色 #6AA1FF on #172445 → **5.93:1**，两套主题都达标。（2026-10-20 审查结论）
    val contentColor = titleColor ?: if (selected) colors.onPrimaryContainer else colors.textPrimary
    val iconColor = if (selected) colors.onPrimaryContainer else colors.textSecondary
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(dimens.settingsRailItemHeight * scale)
            // ⚠️ clickable 必须在 padding 之前：否则点击区会被上下内缩裁掉
            // （58dp 条目只剩 53dp；1024×600 下只剩约 41dp，低于 48dp 基线）。
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            // 定稿图的选中底比条目矮约 6dp（58 → 52），上下各让出约 3dp。
            .padding(vertical = dimens.spaceXs * scale / 2f)
            .clip(shape)
            .background(background)
            .padding(horizontal = dimens.spaceL * scale),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimens.spaceM * scale),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(dimens.settingsRailIconSize * scale),
            tint = iconColor,
        )
        Text(
            text = title,
            style = scaledStyle,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 左栏分组标签（日常 / 仪表 / 系统）。
 *
 * 高度取 [com.dbtool.carplay.ui.theme.CarDimensions.spaceXl]（目标屏 30dp），
 * 与定稿图里「标签占 30dp、紧接着排条目」的节奏一致；字号用 `label`
 * （基准 12sp ⇒ 目标屏 15sp），颜色 `textTertiary`（定稿图 `#A1A1A6`）。
 *
 * 标签**不是**可点击项：它只承担分组语义，点它不会有任何动作。
 */
@Composable
fun CarRailGroupLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    val dimens = CarTheme.dimens
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(dimens.spaceXl)
            // 左栏内容整体已有 spaceS 内边距；这里再加 spaceL ⇒ 距栏边约 30dp，
            // 与定稿图分组标签的起点一致。
            .padding(start = dimens.spaceL),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = text,
            style = CarTheme.type.label,
            color = CarTheme.colors.textTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
