package com.dbtool.carplay.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dbtool.carplay.ui.theme.CarIcons
import com.dbtool.carplay.ui.theme.CarTheme

/**
 * 车机页面骨架。
 *
 * 与 Material `Scaffold` 的区别（也是不用它的原因）：
 * - 顶部栏高度按车机调大（[com.dbtool.carplay.ui.theme.CarDimensions.topBarHeight]），
 *   且**不依赖系统状态栏**——车机可能没有状态栏，也可能把它藏在饰板下；
 * - 内容区强制最大宽度并水平居中。超宽车机（1920x720）上，
 *   如果设置项铺满整宽，一行文字会横跨 1.9 米，眼睛无法定位；
 * - 页面边距/内边距全部来自 Design System，任何页面都不会自己写 padding。
 */
@Composable
fun CarScreen(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    scrollable: Boolean = true,
    /**
     * 允许内容铺满整宽（不套 [CarDimensions.contentMaxWidth]）。
     *
     * 只给**本身就是"工作区"**的页面用（当前：仪表主题的双栏预览）—— 它的右栏
     * 宽度要跟着窗口走，再套 1120dp 上限就永远触发"小屏降级"，双栏等于没做。
     * 普通设置页保持默认，长行文字仍然受最大宽度约束。
     */
    wide: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(
        start = CarTheme.dimens.screenPaddingH,
        end = CarTheme.dimens.screenPaddingH,
        top = CarTheme.dimens.spaceM,
        bottom = CarTheme.dimens.spaceXxl,
    ),
    footer: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val dimens = CarTheme.dimens
    val maxContentWidth: Dp = if (wide) Dp.Unspecified else dimens.contentMaxWidth
    Column(modifier = modifier.fillMaxSize()) {
        CarTopBar(title = title, subtitle = subtitle, onBack = onBack, actions = actions)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = maxContentWidth)
                    .fillMaxWidth()
                    .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                    .padding(contentPadding),
                content = content,
            )
        }
        // 常驻底栏（2026-10-12 加，PATCH-0103）。
        //
        // 与"把状态行塞进滚动内容末尾"的区别：**它不随内容滚走**。
        // 设置页改成自动保存后，用户改完一屏设置往往已经滚到中间，
        // 若状态行在末尾，他要么看不见、要么得专门滚到底去确认 —— 那等于没有提示。
        // 车机上"底部常驻一条状态"也是最省视线的位置。
        if (footer != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(CarTheme.colors.divider),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = dimens.screenPaddingH,
                        end = dimens.screenPaddingH,
                        top = dimens.spaceS,
                        bottom = dimens.spaceS,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .widthIn(max = dimens.contentMaxWidth)
                        .fillMaxWidth(),
                ) {
                    footer()
                }
            }
        }
    }
}

/**
 * 车机顶部栏。
 *
 * [title] 可空：连接页不需要标题（页面中央的大字已经说明了状态），
 * 只留右上角的设置入口。此时用一个 `weight(1f)` 的 Spacer 把 actions
 * 顶到右侧——**必须保持 Row 的两段结构**，否则 actions 会滑到左边。
 *
 * 下沿那条线是全宽的（不缩进）：它划分的是"顶栏"与"内容"两个区域，
 * 而不是列表里的两行，缩进会让它看起来像某个列表项的分隔线。
 *
 * ⚠️ [showDivider] 默认为 true，但**连接页要传 false**。
 * 分隔线的职责是划分两个内容区域；连接页的顶栏没有标题、正文又是**一块居中内容**，
 * 根本不存在要划分的边界。此时一条贯穿全宽的线就只剩"装饰"这一个身份，
 * 而且它横在一片留白上，读起来更像渲染残留而不是设计。
 * 判断标准不是"顶栏该不该有线"，而是**"这条线两边有没有两个区域"**。
 *
 * ⚠️ 顶栏内容的顶部留白是 [com.dbtool.carplay.ui.theme.CarDimensions.topBarInsetTop]
 * 而不是 `spaceM`。别把它当成"躲状态栏"——躲状态栏是根节点
 * `CarPlayApp` 的 `windowInsetsPadding` 干的（车主定案 A：避让）。
 * 这里只是让内容的视觉重心离那条栏的下沿再远一点，理由见 token 的 KDoc。
 *
 * @param showDivider 是否画下沿分隔线。见上面的说明。
 */
@Composable
fun CarTopBar(
    title: String? = null,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    showDivider: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val dimens = CarTheme.dimens
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = dimens.topBarHeight)
                .padding(
                    start = dimens.screenPaddingH,
                    end = dimens.screenPaddingH,
                    top = dimens.topBarInsetTop,
                    bottom = dimens.spaceM,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimens.spaceM),
        ) {
            if (onBack != null) {
                CarIconButton(
                    icon = CarIcons.ArrowBack,
                    contentDescription = "返回",
                    onClick = onBack,
                )
            }
            if (title == null && subtitle == null) {
                Spacer(Modifier.weight(1f))
            } else {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(dimens.spaceXs),
                ) {
                    if (title != null) {
                        Text(
                            text = title,
                            style = CarTheme.type.displayMedium,
                            color = CarTheme.colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = CarTheme.type.bodyMedium,
                            color = CarTheme.colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            actions()
        }
        if (showDivider) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(dimens.strokeThin)
                    .background(CarTheme.colors.divider),
            )
        }
    }
}
