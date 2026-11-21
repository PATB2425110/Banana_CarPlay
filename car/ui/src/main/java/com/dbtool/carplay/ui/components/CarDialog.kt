package com.dbtool.carplay.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import com.dbtool.carplay.ui.theme.CarIcons
import com.dbtool.carplay.ui.theme.CarTheme
import kotlinx.coroutines.delay

/**
 * 统一确认对话框。
 *
 * 车机上的对话框有两条硬约束：
 * 1. **按钮必须够大**——用 [CarButton]（≥56dp），不用 Material 默认的 40dp 文字按钮；
 * 2. **必须能明确取消**——单手/颠簸环境下误触率高，破坏性操作永远要有取消路径。
 *
 * ⚠️ **只有破坏性操作才显示图标**（此时固定用警告图标 + 错误色）。
 * 普通确认框上方顶一个大图标，会把一个"问一句"的动作渲染成"出事了"，
 * 而且会让对话框高度不可预期。这是 §8.7 的明确规定。
 *
 * 破坏性操作的确认按钮用错误色，避免用户条件反射式点"确定"。
 */
@Composable
fun CarAlertDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    dismissText: String? = "取消",
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier.widthIn(min = CarTheme.dimens.contentMaxWidth / 2),
        icon = if (destructive) {
            {
                Icon(
                    imageVector = CarIcons.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(CarTheme.dimens.iconL),
                    tint = CarTheme.colors.error,
                )
            }
        } else {
            null
        },
        title = {
            Text(
                text = title,
                style = CarTheme.type.titleLarge,
                color = CarTheme.colors.textPrimary,
            )
        },
        text = {
            Text(
                text = message,
                style = CarTheme.type.bodyMedium,
                color = CarTheme.colors.textSecondary,
            )
        },
        confirmButton = {
            CarButton(
                text = confirmText,
                onClick = onConfirm,
                variant = if (destructive) CarButtonVariant.Danger else CarButtonVariant.Primary,
            )
        },
        dismissButton = if (dismissText != null) {
            {
                CarButton(
                    text = dismissText,
                    onClick = onDismiss,
                    variant = CarButtonVariant.Ghost,
                )
            }
        } else {
            null
        },
        shape = CarTheme.shapes.extraLarge,
        containerColor = CarTheme.colors.surfaceElevated,
        titleContentColor = CarTheme.colors.textPrimary,
        textContentColor = CarTheme.colors.textSecondary,
        iconContentColor = CarTheme.colors.error,
    )
}

/**
 * 单选对话框（选项较多、不适合做成行内分段控件时使用）。
 *
 * 选项本身用 [CarChoiceChip] 的整宽变体，保证每个选项的热区 ≥ 56dp。
 *
 * ## 2026-10-08 新增 [autoDismissMillis]：自动退出
 *
 * 斌哥口径 —— 选屏这类「让用户自己试」的对话框，用户可能**根本不操作**
 * （不确定选哪块、或干脆走掉了）。挂一个倒计时，到点自动 [onDismiss]，
 * 不让一个对话框在仪表前一直杵着挡路。
 *
 * `null`（默认）= 不自动退，保持旧行为；`> 0` = 从出现起计时，到点关。
 * 用户在倒计时内**点了任何选项**，只会 [onSelect]（由调用方决定是否随之关闭）；
 * 倒计时**照常走**（不因点选而重置），符合「试完就该退」的语义。
 */
@Composable
fun <T> CarChoiceDialog(
    title: String,
    options: List<T>,
    selected: T?,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null,
    label: (T) -> String = { it.toString() },
    description: (T) -> String? = { null },
    autoDismissMillis: Long? = null,
) {
    if (autoDismissMillis != null && autoDismissMillis > 0) {
        // key 到 onDismiss：计时只起一次；onDismiss 变了重起（幂等，无副作用）。
        LaunchedEffect(onDismiss) {
            delay(autoDismissMillis)
            onDismiss()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = {
            Text(
                text = title,
                style = CarTheme.type.titleLarge,
                color = CarTheme.colors.textPrimary,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(CarTheme.dimens.spaceS),
            ) {
                if (message != null) {
                    Text(
                        text = message,
                        style = CarTheme.type.bodyMedium,
                        color = CarTheme.colors.textSecondary,
                    )
                }
                for (option in options) {
                    CarChoiceChip(
                        text = buildString {
                            append(label(option))
                            description(option)?.let { append("  ·  $it") }
                        },
                        selected = option == selected,
                        onClick = { onSelect(option) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            CarButton(
                text = "关闭",
                onClick = onDismiss,
                variant = CarButtonVariant.Ghost,
            )
        },
        shape = CarTheme.shapes.extraLarge,
        containerColor = CarTheme.colors.surfaceElevated,
        titleContentColor = CarTheme.colors.textPrimary,
        textContentColor = CarTheme.colors.textSecondary,
    )
}
