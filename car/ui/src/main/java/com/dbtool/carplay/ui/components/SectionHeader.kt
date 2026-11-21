package com.dbtool.carplay.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.dbtool.carplay.ui.theme.CarTheme

/**
 * 设置页/列表页的分组标题。
 *
 * ⚠️ 旧版做的是 `title.uppercase()` + 字距。那套是英文排版惯例，
 * **用在中文上一无所获**：汉字没有大小写，`uppercase()` 是空操作；
 * 而加字距会把"编码与解码"拉成"编 码 与 解 码"，读起来像打字机故障。
 * 现在标题就是普通文案 + `label` 字号 + `textSecondary`。
 *
 * 层级由**字号 + 颜色**承担，不加粗、不加字距——这是三套体系的共识。
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    val dimens = CarTheme.dimens
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = dimens.spaceM,
                end = dimens.spaceM,
                top = dimens.spaceL,
                bottom = dimens.spaceS,
            ),
        verticalArrangement = Arrangement.spacedBy(dimens.spaceXs),
    ) {
        Text(
            text = title,
            style = CarTheme.type.label,
            color = CarTheme.colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = CarTheme.type.bodySmall,
                color = CarTheme.colors.textTertiary,
            )
        }
    }
}
