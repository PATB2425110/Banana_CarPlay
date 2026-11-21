package com.dbtool.carplay.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import com.dbtool.carplay.ui.theme.CarTheme

/** 按钮语义变体。 */
enum class CarButtonVariant {
    /** 主操作（每屏最多一个）。 */
    Primary,

    /** 次操作（白色实底 + 极淡描边）。 */
    Secondary,

    /** 轻量操作（纯文字）。 */
    Ghost,

    /** 破坏性操作（退出、清除配对）。 */
    Danger,
}

/** 按钮尺寸档。车机上不存在"小按钮"。 */
enum class CarButtonSize {
    /** 标准操作，高度 = touchTargetMin（56dp 基准）。 */
    Regular,

    /** 主操作 / 底部固定操作，高度 = touchTargetLarge（68dp 基准）。 */
    Large,
}

/**
 * 统一按钮。
 *
 * **为什么不用裸 Material3 `Button`：**
 * 车机上按钮高度必须显著大于手机（56/68dp vs 48dp），且四种语义变体
 * 必须共用同一套圆角/字号/内边距。集中在这里，任何一处调色或调高都是全局生效。
 *
 * 圆角**随尺寸档走**（见函数内的 `shape`）：56dp → 12dp、68dp → 16dp。
 * 两种尺寸共用一个圆角会让大的那个显得方。
 *
 * 禁用态用**实底色 + 实底字**（`surfaceVariant` / `textDisabled`），
 * 不用半透明：半透明在车机的强光环境下会与底图混成一片，读不出"这是禁用"。
 */
@Composable
fun CarButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: CarButtonVariant = CarButtonVariant.Primary,
    size: CarButtonSize = CarButtonSize.Regular,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val colors = CarTheme.colors
    val dimens = CarTheme.dimens
    val minHeight = when (size) {
        CarButtonSize.Regular -> dimens.touchTargetMin
        CarButtonSize.Large -> dimens.touchTargetLarge
    }
    val contentPadding = PaddingValues(
        horizontal = dimens.spaceL,
        vertical = dimens.spaceS,
    )
    val content: @Composable () -> Unit = {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimens.spaceS),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(dimens.iconM),
                )
            }
            Text(
                text = text,
                style = if (size == CarButtonSize.Large) {
                    CarTheme.type.titleSmall
                } else {
                    CarTheme.type.bodyLarge
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    val shaped = modifier.defaultMinSize(minHeight = minHeight)
    // 圆角**随高度走**（约 20%）：56dp 用 12dp、68dp 用 16dp。
    //
    // 旧版两种尺寸共用 `medium`(8dp)。8dp 落在 68dp 高的按钮上只占 12%，
    // 在车机的横屏上看起来就是**直角方块**——这是"按钮丑"最直接的原因。
    // 这不是"柔软 SaaS 风"回潮：8dp → 16dp 仍在"结构感"区间内，
    // 只是把圆角与控件高度重新绑上了比例关系。
    val shape = when (size) {
        CarButtonSize.Regular -> CarTheme.shapes.large
        CarButtonSize.Large -> CarTheme.shapes.extraLarge
    }
    when (variant) {
        CarButtonVariant.Primary -> Button(
            onClick = onClick,
            modifier = shaped,
            enabled = enabled,
            shape = shape,
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.primary,
                contentColor = colors.onPrimary,
                disabledContainerColor = colors.surfaceVariant,
                disabledContentColor = colors.textDisabled,
            ),
            contentPadding = contentPadding,
            content = { content() },
        )

        CarButtonVariant.Secondary -> OutlinedButton(
            onClick = onClick,
            modifier = shaped,
            enabled = enabled,
            shape = shape,
            colors = ButtonDefaults.outlinedButtonColors(
                // 用**实底**而不是 `Color.Transparent`。
                //
                // 透明底 + 细描边在页底色（`background`）上只剩一圈线，
                // 读起来是"一个空的输入框"，而不是一个按钮——
                // 现场反馈就是"这两个按钮太丑了"。给它一块白色实底之后，
                // 它在页底色与白色卡片上都成立（卡片上靠那条极淡的描边兜住）。
                containerColor = colors.surface,
                contentColor = colors.textPrimary,
                disabledContentColor = colors.textDisabled,
            ),
            border = BorderStroke(
                width = dimens.strokeThin,
                color = if (enabled) colors.outline else colors.divider,
            ),
            contentPadding = contentPadding,
            content = { content() },
        )

        CarButtonVariant.Ghost -> TextButton(
            onClick = onClick,
            modifier = shaped,
            enabled = enabled,
            shape = shape,
            colors = ButtonDefaults.textButtonColors(
                contentColor = colors.primaryText,
                disabledContentColor = colors.textDisabled,
            ),
            contentPadding = contentPadding,
            content = { content() },
        )

        CarButtonVariant.Danger -> Button(
            onClick = onClick,
            modifier = shaped,
            enabled = enabled,
            shape = shape,
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.error,
                contentColor = colors.onError,
                disabledContainerColor = colors.surfaceVariant,
                disabledContentColor = colors.textDisabled,
            ),
            contentPadding = contentPadding,
            content = { content() },
        )
    }
}

/**
 * 图标按钮。触控区域固定为 [CarButtonSize.Regular] 高度，
 * 即使图标本身很小——这是车机上的硬性要求，不能靠视觉大小决定热区。
 *
 * 底色默认 `surfaceVariant` 的浅块：车机顶栏上如果只放一个裸图标，
 * 用户看不出它可点。给一块底是成本最低的"可点击"提示。
 */
@Composable
fun CarIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = CarTheme.colors.textPrimary,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(CarTheme.dimens.touchTargetMin),
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = tint,
            disabledContentColor = CarTheme.colors.textDisabled,
            containerColor = CarTheme.colors.surfaceVariant,
        ),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(CarTheme.dimens.iconM),
        )
    }
}
