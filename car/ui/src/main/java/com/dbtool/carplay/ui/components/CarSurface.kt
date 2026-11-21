package com.dbtool.carplay.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dbtool.carplay.ui.theme.CarTheme

/**
 * 车机 UI 的基础卡片容器。
 *
 * 视觉层级用**表面色阶**表达，不用阴影也不用默认描边：
 * - 车机屏幕常在强光/暗光下观看，阴影在这种环境下几乎不可见，
 *   反而会在廉价面板上出现色带；
 * - 阴影需要离屏合成，是车机 GPU 上不必要的开销；
 * - 描边只在**选中态**才出现——它承担"这是当前选项"的语义，
 *   而每张卡片都描一圈边只会让页面布满线条（Ant/TDesign 的共识：
 *   层级手段底色差异 / 描边 / 阴影三选一，不叠加）。
 *
 * 圆角、内边距、描边色全部来自 Design System，调用方不需要也不能指定。
 *
 * @param onClick 非空表示可点击卡片。按下时底色降到 `surfaceVariant`，
 *   **不使用涟漪**——涟漪在大屏车机上会变成一片模糊的光斑，反而干扰阅读。
 * @param selected 选中态：底 `primaryContainer` + `primary` 1px 描边。
 */
@Composable
fun CarCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    selected: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(CarTheme.dimens.cardPadding),
    shape: RoundedCornerShape = CarTheme.shapes.large,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CarTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val base = when {
        selected -> colors.primaryContainer
        pressed -> colors.surfaceVariant
        else -> colors.surface
    }
    Surface(
        modifier = modifier.then(
            if (onClick != null) {
                Modifier
                    .clip(shape)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onClick,
                    )
            } else {
                Modifier
            },
        ),
        shape = shape,
        color = base,
        border = if (selected) BorderStroke(CarTheme.dimens.strokeThin, colors.primary) else null,
        shadowElevation = 0.dp,
    ) {
        Column(modifier = Modifier.padding(contentPadding), content = content)
    }
}

/**
 * 无内边距的表面容器，用于需要自己控制布局的场景（状态胶囊、提示条、分段选项）。
 *
 * [borderColor] 默认为 [Color.Transparent]，即**不描边**。只有确实需要
 * 与同色底区分时才传色。
 *
 * [onClick] 非空即可点击；此时**不使用涟漪**，按下时底色切到 [pressedColor]
 * （为 null 则不变）。涟漪在大屏车机上会铺开成一片模糊的光斑，
 * 与"扁平、克制"的整体方向冲突；而底色变化是同样清晰、更安静的反馈。
 *
 * ⚠️ [verticalArrangement] / [horizontalAlignment] 不是可有可无的装饰参数：
 * `Surface` 以 `propagateMinConstraints = true` 把**最小高度**透传给内部 `Column`，
 * 而 `Column` 的默认排布是 `Top` + `Start`。于是"调用方用 `defaultMinSize` 指定了
 * 56dp 触控高度、内容却只有一行文字"时，Column 会被撑到 56dp 而内容仍贴在左上角——
 * 表现为**文字偏左上、下方留一大块空**（分段选项曾因此明显不居中）。
 * 需要内容在指定高度里居中时，必须显式传 `Center` / `CenterHorizontally`。
 */
@Composable
fun CarPanel(
    modifier: Modifier = Modifier,
    color: Color = CarTheme.colors.surface,
    borderColor: Color = Color.Transparent,
    shape: RoundedCornerShape = CarTheme.shapes.large,
    onClick: (() -> Unit)? = null,
    pressedColor: Color? = null,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Surface(
        modifier = modifier.then(
            if (onClick != null) {
                Modifier
                    .clip(shape)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onClick,
                    )
            } else {
                Modifier
            },
        ),
        shape = shape,
        color = if (pressed && pressedColor != null) pressedColor else color,
        border = if (borderColor == Color.Transparent) {
            null
        } else {
            BorderStroke(CarTheme.dimens.strokeThin, borderColor)
        },
        shadowElevation = 0.dp,
    ) {
        Column(
            verticalArrangement = verticalArrangement,
            horizontalAlignment = horizontalAlignment,
            content = content,
        )
    }
}

/**
 * 图标徽标：把裸图标放进一个圆角底色块里。
 *
 * 为什么需要它：车机界面如果只是"图标 + 文字"平铺，信息层级会立刻扁平化，
 * 看起来像一个没有设计过的工具页。给图标一个承载块能廉价地建立层级，
 * 同时让不同图标的视觉重量趋于一致（线性图标的实际墨量差异很大）。
 */
@Composable
fun CarIconBadge(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tint: Color = CarTheme.colors.textSecondary,
    container: Color = CarTheme.colors.surfaceVariant,
    size: Dp = CarTheme.dimens.iconXl * 1.4f,
    shape: RoundedCornerShape = CarTheme.shapes.medium,
) {
    Surface(
        modifier = modifier.size(size),
        shape = shape,
        color = container,
        border = null,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(CarTheme.dimens.iconL),
                tint = tint,
            )
        }
    }
}

/**
 * 图标徽标的**位图**版本：用于品牌标志（DBPlay 的 logo 是位图，不是矢量图标）。
 *
 * 两个与 [CarIconBadge] 不同的处理，都是被位图特性逼出来的：
 *
 * 1. **不 tint**。矢量图标写死白色靠 `tint` 上色；logo 自带品牌配色（淡紫），
 *    一旦 tint 就会变成一块纯色。所以这里只有 [container] 可控。
 * 2. **放大后再裁切**。launcher 的 foreground 是按自适应图标规范画的——
 *    图形只占中间约 66% 的"安全区"，四周留白是给系统裁圆角用的。
 *    直接铺进徽标会让 logo 显得很小，所以按 [logoScale] 放大、由
 *    [Surface] 裁回徽标范围。
 */
@Composable
fun CarIconBadge(
    painter: Painter,
    modifier: Modifier = Modifier,
    container: Color = CarTheme.colors.surfaceVariant,
    size: Dp = CarTheme.dimens.iconXl * 1.4f,
    shape: RoundedCornerShape = CarTheme.shapes.medium,
    logoScale: Float = 1.7f,
) {
    Surface(
        modifier = modifier.size(size),
        shape = shape,
        color = container,
        border = null,
        shadowElevation = 0.dp,
    ) {
        Image(
            painter = painter,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .scale(logoScale),
        )
    }
}
