package com.dbtool.carplay.ui.screens

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dbtool.carplay.ui.components.CarPanel
import com.dbtool.carplay.ui.components.CarRailItem
import com.dbtool.carplay.ui.components.CarRowDivider
import com.dbtool.carplay.ui.components.CarScreen
import com.dbtool.carplay.ui.navigation.CarSettingsSection
import com.dbtool.carplay.ui.theme.CarIcons
import com.dbtool.carplay.ui.theme.CarTheme

/**
 * 设置页双栏布局策略（纯函数，可直接单测）。
 *
 * 断点判定只依赖**可用内容宽**，与窗口物理尺寸无关：
 * 内容上限与页边距都已经乘过 `scale`，所以这里算出来的值就是内容真正能用的宽度。
 * 这样 1024×600（scale=0.9）与 1920×1080（scale=1.25）可以用同一个 900dp 阈值。
 */
internal object SettingsLayoutPolicy {
    const val NAV_RAIL_SCALE = 1.1f
    /**
     * 可用内容宽 = min(窗口宽, 内容上限) − 两侧页边距。
     *
     * 为什么取 `min`：`CarScreen` 用 `widthIn(max = contentMaxWidth)` 居中，
     * 窗口比上限宽时内容不会继续变宽；窗口比上限窄时按窗口算。
     */
    fun availableContentWidth(
        screenWidth: Dp,
        contentMaxWidth: Dp,
        screenPaddingH: Dp,
    ): Dp = (minOf(screenWidth, contentMaxWidth) - screenPaddingH * 2).coerceAtLeast(0.dp)

    /** 可用内容宽达到 [minWidth] 时走双栏，否则回退「索引页 + 详情页」。 */
    fun isDualPane(availableWidth: Dp, minWidth: Dp): Boolean = availableWidth >= minWidth
}

/** 左栏的一个分组。`sections` 的顺序即左栏的呈现顺序。 */
internal data class SettingsNavGroup(
    val sections: List<CarSettingsSection>,
)

/**
 * 左栏分组（2026-10-20 定稿）。
 *
 * **这是左栏与窄屏索引页共用的唯一分区清单**：新增/删除分区时只改这里
 * （以及 `CarSettingsSection` 枚举），不要在页面里再手写第二份。
 */
internal val SETTINGS_NAV_GROUPS: List<SettingsNavGroup> = listOf(
    SettingsNavGroup(
        sections = listOf(
            CarSettingsSection.CONNECTION,
            CarSettingsSection.DISPLAY,
            CarSettingsSection.VEHICLE,
        ),
    ),
    SettingsNavGroup(
        sections = listOf(
            CarSettingsSection.CLUSTER,
            CarSettingsSection.IMMERSIVE_NAVIGATION,
            CarSettingsSection.HUD,
        ),
    ),
    SettingsNavGroup(
        sections = listOf(
            CarSettingsSection.IDENTITY,
            CarSettingsSection.DIAGNOSTICS,
        ),
    ),
)

/**
 * 窄屏索引页的顺序。
 *
 * 刻意与 [SETTINGS_NAV_GROUPS] 分开：窄屏是**回退路径**，要求行为不变，
 * 所以保留改造前的行序（HUD 在仪表导航之后、全屏模式之前等）。
 * 两份清单共享同一个 `CarSettingsSection` 枚举与 [settingsSectionIcon] 映射，
 * 不会出现"图标/标题各写一份"的漂移；单测同时校验两者覆盖全部 10 个分区。
 */
internal val SETTINGS_INDEX_ORDER: List<CarSettingsSection> = listOf(
    CarSettingsSection.CONNECTION,
    CarSettingsSection.DISPLAY,
    CarSettingsSection.CLUSTER,
    CarSettingsSection.IMMERSIVE_NAVIGATION,
    CarSettingsSection.HUD,
    CarSettingsSection.VEHICLE,
    CarSettingsSection.IDENTITY,
    CarSettingsSection.DIAGNOSTICS,
)

/** 分区 → 图标。索引页与左栏共用，避免同一分区在两处显示不同图标。 */
internal fun settingsSectionIcon(section: CarSettingsSection): ImageVector = when (section) {
    CarSettingsSection.CONNECTION -> CarIcons.Link
    CarSettingsSection.DISPLAY -> CarIcons.Display
    // 2026-10-22：仪表导航用「定位」图标，与仪表主题的「图片」图标拉开；
    // 原来导航与全屏模式共用 Display，左栏里两块长得一样，3 秒内分不出来。
    CarSettingsSection.CLUSTER -> CarIcons.Location
    CarSettingsSection.IMMERSIVE_NAVIGATION -> CarIcons.Display
    CarSettingsSection.HUD -> CarIcons.Link
    CarSettingsSection.VEHICLE -> CarIcons.System
    CarSettingsSection.IDENTITY -> CarIcons.Device
    CarSettingsSection.DIAGNOSTICS -> CarIcons.Log
}

/**
 * 设置页主从双栏：左栏常驻导航 + 右栏当前分区内容。
 *
 * ## 为什么是"主从"而不是"内容左右对切"
 *
 * 只把**外层容器**改成两栏，15 个 `*Section` 函数仍按原顺序、原单列渲染。
 * 项目在 PATCH-0096 试过把仪表导航的内容拆成左右两列，真车反馈是
 * 「列高不等、阅读顺序断裂、同族被拆开」（见 `SettingsScreen.kt` 的相应注释），
 * 因此本方案不再拆内容。
 *
 * ## 滚动
 *
 * - 页面级 [CarScreen] 传 `scrollable = false`，不产生整页滚动；
 * - 左栏内部可滚动（目标 1920×1080 一屏放得下，1024×600 等小屏才需要滚）；
 * - 右栏独立 `verticalScroll`，且滚动位置以当前分区为 key —— 切分区自动回顶部。
 *
 * @param content 当前分区内容。由调用方原样调用 `SettingsSectionContent`，
 *   保证状态所有权、回调签名与持久化路径都不经过本容器。
 */
@Composable
internal fun SettingsTwoPane(
    selected: CarSettingsSection,
    summary: String,
    onSelectSection: (CarSettingsSection) -> Unit,
    onOpenAbout: () -> Unit,
    onBack: () -> Unit,
    footer: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val dimens = CarTheme.dimens
    CarScreen(
        title = "设置",
        onBack = onBack,
        footer = footer,
        actions = actions,
        // 两个滚动区各自管理，页面本身不能再滚（否则内层滚动会被外层吃掉）。
        scrollable = false,
        modifier = modifier,
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            SettingsNavRail(
                selected = selected,
                onSelectSection = onSelectSection,
                onOpenAbout = onOpenAbout,
                modifier = Modifier
                    .width(dimens.settingsRailWidth * SettingsLayoutPolicy.NAV_RAIL_SCALE)
                    .fillMaxHeight()
                    // 2026-10-23 特斯拉风格：左栏做成一块**略带圆角的独立面板**，
                    // 底色比页面亮一档（`navBackground`），靠明度分层而不是竖直分割线。
                    .padding(vertical = 8.dp)
                    .clip(RoundedCornerShape(dimens.radiusL))
                    .background(CarTheme.colors.navBackground)
                    .padding(horizontal = 4.dp),
            )
            Spacer(Modifier.width(dimens.spaceXl))
            // 以 selected 为 key：切分区时旧滚动状态被丢弃，新分区从顶部开始。
            val rightScrollState = remember(selected) { ScrollState(0) }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rightScrollState),
            ) {
                SettingsPaneHeader(title = selected.title, summary = summary)
                content()
                Spacer(Modifier.height(dimens.spaceXl))
            }
        }
    }
}

/**
 * 右栏页头：分区标题 + 一行摘要。
 *
 * 摘要只在这里出现一次；左栏条目只有标题（见 [CarRailItem] 的 KDoc）。
 * 标题用 `titleLarge`（基准 20sp ⇒ 目标屏 25sp），摘要用 `bodyMedium`
 * （基准 14sp ⇒ 17.5sp），与定稿图 26 / 17 的层级一致。
 */
@Composable
internal fun SettingsPaneHeader(title: String, summary: String) {
    val dimens = CarTheme.dimens
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimens.spaceL),
    ) {
        Text(
            text = title,
            style = CarTheme.type.titleLarge,
            color = CarTheme.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = summary,
            style = CarTheme.type.bodyMedium,
            color = CarTheme.colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
    Spacer(Modifier.height(dimens.spaceM))
}

/**
 * 左栏导航卡片。
 *
 * 使用 [CarPanel] 而不是 [com.dbtool.carplay.ui.components.CarCard]：定稿图的左栏
 * 是一张 `surface` 底、`outline` 1px 描边、圆角 15dp 的面板（`CarCard` 只在选中态
 * 才画描边）。内部结构分两段：上方分组条目区（`weight(1f)`，必要时独立滚动），
 * 下方「关于 / 恢复默认设置」固定区（上方一条分隔线）。
 */
@Composable
private fun SettingsNavRail(
    selected: CarSettingsSection,
    onSelectSection: (CarSettingsSection) -> Unit,
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CarTheme.colors
    val dimens = CarTheme.dimens
    CarPanel(
        modifier = modifier.fillMaxHeight(),
        color = colors.surface,
        borderColor = colors.outline,
        shape = CarTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = dimens.spaceS),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(top = dimens.spaceM),
            ) {
                for (group in SETTINGS_NAV_GROUPS) {
                    for (section in group.sections) {
                        CarRailItem(
                            title = section.title,
                            icon = settingsSectionIcon(section),
                            selected = section == selected,
                            onClick = { onSelectSection(section) },
                            scale = SettingsLayoutPolicy.NAV_RAIL_SCALE,
                        )
                    }
                }
            }

            // 底部固定区：分割线以上是 10 个分区，以下只放「关于 / 恢复默认设置」。
            // 两者不会随分区滚动 —— 定稿要求它们在左栏底部常驻。
            CarRowDivider(inset = dimens.spaceXs)
            Spacer(Modifier.height(dimens.spaceXs))
            CarRailItem(
                title = "关于",
                icon = CarIcons.Info,
                selected = false,
                onClick = onOpenAbout,
                scale = SettingsLayoutPolicy.NAV_RAIL_SCALE,
                style = CarTheme.type.bodyLarge,
                titleColor = colors.textPrimary,
            )
            Spacer(Modifier.height(dimens.spaceS))
        }
    }
}
