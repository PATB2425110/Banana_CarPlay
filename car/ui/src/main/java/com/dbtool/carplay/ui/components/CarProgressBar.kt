package com.dbtool.carplay.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.dbtool.carplay.ui.theme.CarTheme

/**
 * 线性进度条。
 *
 * **刻意不用 Material 的 `LinearProgressIndicator`**：它的高度与内边距
 * 不可控，还会引入一个和本项目其它元素不同源的尺寸（4dp 轨道在 56dp 的行高
 * 旁边看着像一条缝）。这里轨道高度就是 `strokeThick × 2`，圆角走 `shapes.pill`，
 * 与卡片/胶囊同一个圆角体系。
 *
 * [progress] 为 0 时**只画轨道、不画已完成段**，这是有意的：
 * 采集/打包阶段拿不到可靠进度，显示一个"看起来在走"的假进度是骗人——
 * 此时由旁边的阶段胶囊说明"在推进"。
 *
 * @param progress 0f..1f，超出范围会被夹住。
 * @param label 轨道右侧的说明文字（如 `45%` 或 `12.3 MB / 27.4 MB`）。
 *        null = 不显示，轨道独占整行。
 */
@Composable
fun CarProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    val colors = CarTheme.colors
    val dimens = CarTheme.dimens
    val fraction = progress.coerceIn(0f, 1f)
    val track = @Composable {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(dimens.strokeThick * 2f)
                .clip(CarTheme.shapes.pill)
                .background(colors.surfaceVariant),
        ) {
            if (fraction > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .clip(CarTheme.shapes.pill)
                        .background(colors.primary),
                )
            }
        }
    }
    if (label == null) {
        track()
    } else {
        Row(
            modifier = modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimens.spaceS),
        ) {
            Box(modifier = Modifier.weight(1f)) { track() }
            Text(
                text = label,
                style = CarTheme.type.valueLarge,
                color = colors.textSecondary,
                maxLines = 1,
            )
        }
    }
}
