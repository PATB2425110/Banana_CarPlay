package com.dbtool.carplay.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.dbtool.carplay.ui.theme.CarIcons
import com.dbtool.carplay.ui.theme.CarTheme

/**
 * 底栏「选应用」面板（多选）。
 *
 * ## 为什么不用现成的 `CarChoiceDialog`
 *
 * 那个是**单选**（`selected: T?` + `onSelect: (T) -> Unit`），而底栏要占好几个槽。
 * 硬把多选塞进单选组件会让调用方拼两次状态，不如这里自己写一个。
 *
 * ## 交互
 *
 * - 点行 = 勾选/取消；已选的行显示一个对勾；
 * - 已选数量上限 = [maxSelection]（底栏的槽位数）；到上限后**再点不会静默失败** ——
 *   提示行会变成"最多 N 个"，用户知道自己为什么点不动；
 * - 确定 → 回调**已选的 key 列表**（顺序 = 用户勾选的先后顺序，也就是底栏里的先后）。
 *
 * 只依赖 `AlertDialog` / `LazyColumn` / `CarButton` 这几个既有组件与 Token，
 * 不引入新视觉体系。
 */
@Composable
fun CarDockAppPickerDialog(
    choices: List<CarDockAppChoice>,
    selectedKeys: List<String>,
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    maxSelection: Int = 5,
) {
    val dimens = CarTheme.dimens
    // ⚠️ key 必须同时带上 `choices`：候选列表是**异步**查出来的，第一次组合时它还是空的
    // ⇒ `selectedKeys ∩ choices` 会被算成空、预选全丢；而 `selectedKeys` 之后结构没变，
    // `remember` 不会重算，于是面板会一直显示"已选 0 个"（实拍确认过）。
    // 带上 `choices` 后，候选一到就会按新交集重算一次。
    var picked by remember(selectedKeys, choices) {
        mutableStateOf(selectedKeys.filter { key -> choices.any { it.key == key } })
    }
    val atLimit = picked.size >= maxSelection

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier.widthIn(min = dimens.contentMaxWidth / 2),
        title = {
            Text(
                text = "选择底栏应用",
                style = CarTheme.type.titleLarge,
                color = CarTheme.colors.textPrimary,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(dimens.spaceS)) {
                Text(
                    text = if (atLimit) {
                        "已选 ${picked.size} 个 · 最多 $maxSelection 个（再点要取消一个）"
                    } else {
                        "已选 ${picked.size} 个 · 最多 $maxSelection 个"
                    },
                    style = CarTheme.type.bodyMedium,
                    color = if (atLimit) CarTheme.colors.warning else CarTheme.colors.textSecondary,
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = dimens.rowMinHeight * 5),
                ) {
                    items(choices, key = { it.key }) { choice ->
                        val selected = picked.contains(choice.key)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(dimens.rowMinHeight)
                                .clickable {
                                    picked = when {
                                        selected -> picked - choice.key
                                        atLimit -> picked
                                        else -> picked + choice.key
                                    }
                                }
                                .padding(horizontal = dimens.spaceS),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(dimens.spaceM),
                        ) {
                            // 标签 + **包名副行**：去重是按包名做的（同包多个
                            // launcher activity 只留一个），但**不同包可以同名** ——
                            // 实机上面板里出现过两条 "DiPlay"，只显示标签就分不清。
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = choice.label,
                                    style = CarTheme.type.bodyLarge,
                                    color = if (!selected && atLimit) {
                                        CarTheme.colors.textDisabled
                                    } else {
                                        CarTheme.colors.textPrimary
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = choice.key,
                                    style = CarTheme.type.bodySmall,
                                    color = CarTheme.colors.textTertiary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Box(
                                modifier = Modifier.size(dimens.iconM),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (selected) {
                                    Icon(
                                        imageVector = CarIcons.Check,
                                        contentDescription = "已选",
                                        tint = CarTheme.colors.primary,
                                        modifier = Modifier.size(dimens.iconM),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            CarButton(text = "确定", onClick = { onConfirm(picked) })
        },
        dismissButton = {
            CarButton(
                text = "取消",
                onClick = onDismiss,
                variant = CarButtonVariant.Secondary,
            )
        },
    )
}
