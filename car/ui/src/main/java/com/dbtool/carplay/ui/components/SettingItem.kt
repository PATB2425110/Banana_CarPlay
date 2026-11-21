package com.dbtool.carplay.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.dbtool.carplay.ui.theme.CarIcons
import com.dbtool.carplay.ui.theme.CarTheme

/**
 * 设置页的**唯一**行基础设施。
 *
 * 所有具体形态（开关/分段/滑杆/输入/跳转/只读）都建立在它之上，
 * 因此行高、内边距、图标尺寸、标题字号在任何一页都一致——
 * 这正是"不要把设置做成传统 Android Preference 默认样式"的落点：
 * 我们不用 Preference，而是用一套统一的、为车机调过尺寸的自有行组件。
 *
 * 触控规则：只要 [onClick] 非空，**整行**都是热区（≥ [com.dbtool.carplay.ui.theme.CarDimensions.rowMinHeight]），
 * 而不是只有右侧控件可点。车机上"点不中"是最高频的体验问题。
 *
 * 按下反馈用**底色降到 `surfaceVariant`**，不用涟漪——与卡片保持一致。
 */
@Composable
fun CarSettingRow(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val colors = CarTheme.colors
    val dimens = CarTheme.dimens
    val titleColor = if (enabled) colors.textPrimary else colors.textDisabled
    val descColor = if (enabled) colors.textSecondary else colors.textDisabled
    val clickable = onClick != null && enabled
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = dimens.rowMinHeight)
            .background(if (clickable && pressed) colors.surfaceVariant else Color.Transparent)
            .then(
                if (clickable) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onClick!!,
                    )
                } else {
                    Modifier
                },
            )
            .padding(horizontal = dimens.spaceM, vertical = dimens.spaceS),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimens.spaceM),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(dimens.iconM),
                tint = if (enabled) colors.textSecondary else colors.textDisabled,
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(dimens.spaceXs),
        ) {
            Text(
                text = title,
                style = CarTheme.type.titleSmall,
                color = titleColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (description != null) {
                Text(
                    text = description,
                    style = CarTheme.type.bodySmall,
                    color = descColor,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            trailing()
        }
    }
}

/**
 * 开关行。整行可点即切换，右侧 Switch 只作为状态指示（不接受独立点击）。
 *
 * 轨道关态用 `outline`（`#E5E6EB`）而不是深灰：深灰轨道在浅色卡片上
 * 会读成"这个开关是坏的"。滑块两态都用白色，颜色对比靠轨道承担。
 */
@Composable
fun CarSettingSwitch(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    CarSettingRow(
        title = title,
        modifier = modifier,
        description = description,
        icon = icon,
        enabled = enabled,
        onClick = { onCheckedChange(!checked) },
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = null,
                enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = CarTheme.colors.primary,
                    checkedBorderColor = Color.Transparent,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = CarTheme.colors.outline,
                    uncheckedBorderColor = Color.Transparent,
                ),
            )
        },
    )
}

/** 跳转行：右侧显示当前值 + 箭头。 */
@Composable
fun CarSettingAction(
    title: String,
    value: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    CarSettingRow(
        title = title,
        modifier = modifier,
        description = description,
        icon = icon,
        enabled = enabled,
        onClick = onClick,
        trailing = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(CarTheme.dimens.spaceS),
            ) {
                if (!value.isNullOrEmpty()) {
                    Text(
                        text = value,
                        style = CarTheme.type.valueLarge,
                        color = CarTheme.colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    imageVector = CarIcons.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(CarTheme.dimens.iconS),
                    tint = CarTheme.colors.textTertiary,
                )
            }
        },
    )
}

/**
 * 只读行：仅展示值，默认不可点。
 *
 * [onClick] 非空时整行变为可点（底色反馈同其它行），用于「关于」页的
 * **版本号连点**这类隐藏入口——它必须看起来仍是只读信息，
 * 不能加箭头（那等于把隐藏入口写在脸上）。
 *
 * ⚠️ **只读 ≠ 禁用**，所以这里永远传 `enabled = true`：
 * `enabled` 在 [CarSettingRow] 里同时控制"能不能点"和"文字是不是禁用灰"，
 * 而一行"设备编号 / 系统版本"只是没有交互，它的内容必须是正常可读的。
 * 曾经写成 `enabled = onClick != null`，结果是「关于」页与开发者页里
 * **每一行只读信息的标题都变成 25% 的灰**——整页看起来像坏掉了。
 * 是否可点由 [onClick] 单独决定（`CarSettingRow` 里 `clickable = onClick != null && enabled`）。
 */
@Composable
fun CarSettingInfo(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
) {
    CarSettingRow(
        title = title,
        modifier = modifier,
        description = description,
        icon = icon,
        enabled = true,
        onClick = onClick,
        trailing = {
            Text(
                text = value,
                style = CarTheme.type.valueLarge,
                color = CarTheme.colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

/**
 * 分段选择行（iOS 风格 segmented control）。
 *
 * 为什么不用 `RadioGroup` + `RadioButton`（上游的做法）：
 * 车机上单选按钮的小圆点热区远小于可用手指精确命中的尺寸，
 * 而分段控件把**每个选项本身**变成大热区，并且一眼就能看出"有哪些选项"，
 * 不需要先展开再选。
 *
 * 选项**等宽均分整行**（`weight(1f)`）。宽度跟着文字走会让一排按钮参差不齐、
 * 整排只占卡片左边一小段，是"比例不协调"的直接来源。
 */
@Composable
fun <T> CarSettingChoice(
    title: String,
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    label: (T) -> String = { it.toString() },
) {
    val dimens = CarTheme.dimens
    Column(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = dimens.rowMinHeight)
            .padding(horizontal = dimens.spaceM, vertical = dimens.spaceS),
        verticalArrangement = Arrangement.spacedBy(dimens.spaceS),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimens.spaceM),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(dimens.iconM),
                    tint = CarTheme.colors.textSecondary,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(dimens.spaceXs)) {
                Text(text = title, style = CarTheme.type.titleSmall, color = CarTheme.colors.textPrimary)
                if (description != null) {
                    Text(
                        text = description,
                        style = CarTheme.type.bodySmall,
                        color = CarTheme.colors.textSecondary,
                    )
                }
            }
        }
        // 等宽分段控件：所有选项均分整行宽度。
        //
        // ⚠️ 旧版是 `horizontalScroll` + 按内容自适应宽度，结果同一行里
        // 「USB 有线」114px、「无线 CarPlay」142px，宽窄不一；整排还只占卡片左侧一小段，
        // 右侧空出一大片——这正是"选项比例不协调"的来源。
        // 选项最多 5 个（CarTransportMode 2 / CarMfiTarget 4 / CarHotspotMode 5 /
        // CarHotspotBand 3 / CarHotspotSecurity 4 / CarPhysicalSizeBasis 2 / CarDrivingSide 2），
        // 等分后每格仍远宽于文字，因此不再需要横向滚动。
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(dimens.spaceS),
        ) {
            for (option in options) {
                CarChoiceChip(
                    text = label(option),
                    selected = option == selected,
                    enabled = enabled,
                    onClick = { onSelect(option) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * 单个选项胶囊。
 *
 * **两态都不描边**：未选中靠 `surfaceVariant` 的色块与卡片底区分，
 * 选中靠主色填充。描边在这里是多余的——它既不能表达"未选中"，
 * 又会让一排选项看起来像一排输入框。
 *
 * 不加涟漪也不加按下底色：点下去选中态**立刻**变化，
 * 状态变化本身就是反馈，再叠一层是重复。
 */
@Composable
fun CarChoiceChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = CarTheme.colors
    val dimens = CarTheme.dimens
    CarPanel(
        modifier = modifier.defaultMinSize(minHeight = dimens.touchTargetMin),
        // Match the checked switch track in both themes; rail selection is independent.
        color = if (selected) colors.primary else colors.surfaceVariant,
        shape = CarTheme.shapes.large,
        onClick = if (enabled) onClick else null,
        // 框高由 defaultMinSize 给到 56dp，而内容只有一行文字 ⇒ 必须显式居中。
        // CarPanel 内部的 Column 默认是 Top + Start，不传这两个参数时文字会贴在左上角。
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = text,
            style = CarTheme.type.bodyLarge,
            color = when {
                !enabled -> colors.textDisabled
                selected -> colors.onPrimary
                else -> colors.textPrimary
            },
            // 等宽分段控件里每格宽度相同，文字必须自身居中才不会偏向一侧。
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(
                horizontal = dimens.spaceL,
                vertical = dimens.spaceS,
            ),
        )
    }
}

/**
 * 滑杆行。数值用大字号显示在标题右侧——车机上调节时必须有明确反馈，
 * 光靠滑杆位置看不出当前值。
 */
@Composable
fun CarSettingSlider(
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    valueLabel: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    steps: Int = 0,
    enabled: Boolean = true,
) {
    val dimens = CarTheme.dimens
    val colors = CarTheme.colors
    // 2026-10-23 对齐规范：滑动行与普通设置行用同一套
    // 　· 最小行高 = `rowMinHeight`（60dp），不再由内容撑到 56/68 忽高忽低
    // 　· 左右内边距 = `spaceM`（与 CarSettingRow 一致，边缘才齐）
    // 　· 标题/数值同一行、垂直居中；轨道在下一行、与标题左边缘对齐
    Column(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = dimens.rowMinHeight)
            .padding(horizontal = dimens.spaceM, vertical = dimens.spaceS),
        verticalArrangement = Arrangement.spacedBy(dimens.spaceS),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(dimens.spaceXs),
            ) {
                Text(
                    text = title,
                    style = CarTheme.type.titleSmall,
                    color = if (enabled) colors.textPrimary else colors.textDisabled,
                )
                if (description != null) {
                    Text(
                        text = description,
                        style = CarTheme.type.bodySmall,
                        color = colors.textSecondary,
                    )
                }
            }
            Spacer(Modifier.width(dimens.spaceM))
            // 数值右对齐 + 等宽位（textAlign End），不同位数不会把文字推来推去。
            Text(
                text = valueLabel,
                style = CarTheme.type.titleLarge,
                color = if (enabled) colors.primaryText else colors.textDisabled,
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                modifier = Modifier.widthIn(min = 96.dp),
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = colors.primary,
                activeTrackColor = colors.primary,
                inactiveTrackColor = colors.surfaceVariant,
            ),
        )
    }
}

/**
 * 文本输入行。
 *
 * 底 `surfaceVariant`，**默认不描边**，只在聚焦时出现一条 `primary` 细线。
 * 输入框平时挂着一圈灰边是"表单感"最重的元素之一，而设置页里
 * 绝大多数输入框大部分时间都不在被编辑——常态描边纯属噪音。
 */
@Composable
fun CarSettingText(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    placeholder: String? = null,
    enabled: Boolean = true,
    isPassword: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    isError: Boolean = false,
) {
    val dimens = CarTheme.dimens
    val colors = CarTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = dimens.rowMinHeight)
            .padding(horizontal = dimens.spaceM, vertical = dimens.spaceS),
        verticalArrangement = Arrangement.spacedBy(dimens.spaceS),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(dimens.spaceXs)) {
            Text(
                text = title,
                style = CarTheme.type.titleSmall,
                color = if (enabled) colors.textPrimary else colors.textDisabled,
            )
            if (description != null) {
                Text(
                    text = description,
                    style = CarTheme.type.bodySmall,
                    color = colors.textSecondary,
                )
            }
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = dimens.touchTargetMin),
            enabled = enabled,
            singleLine = true,
            isError = isError,
            placeholder = placeholder?.let {
                {
                    Text(
                        text = it,
                        style = CarTheme.type.bodyLarge,
                        color = colors.textTertiary,
                    )
                }
            },
            textStyle = CarTheme.type.bodyLarge,
            visualTransformation = if (isPassword) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            shape = CarTheme.shapes.large,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = colors.surfaceVariant,
                unfocusedContainerColor = colors.surfaceVariant,
                disabledContainerColor = colors.surfaceVariant,
                focusedBorderColor = colors.primary,
                unfocusedBorderColor = Color.Transparent,
                disabledBorderColor = Color.Transparent,
                errorBorderColor = colors.error,
                focusedTextColor = colors.textPrimary,
                unfocusedTextColor = colors.textPrimary,
                disabledTextColor = colors.textDisabled,
                cursorColor = colors.primary,
            ),
        )
    }
}

/**
 * 数字参数组：一行内并排若干**等宽**的数字输入框。
 *
 * ## 为什么是"一行多列"而不是"一列多行"
 *
 * 宽 / 高 / DPI 是**一组**参数，共同描述同一块屏。并排放在一起才看得出从属关系；
 * 各占一行会吃掉大半个屏幕，而且三行之间看起来像三个互不相关的设置。
 *
 * ## 为什么不用 [CarSettingSlider]
 *
 * 滑杆的步进是固定的，而分辨率要**精确**落到某个值（1920x720 这类非标准比例
 * 恰恰是车机里最常见的）。滑杆只能逼近，永远差一档——这正是这个组件存在的理由。
 *
 * 用法：组内用 [CarNumberField]，它会自动等分宽度。
 */
@Composable
fun CarSettingNumberGroup(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val dimens = CarTheme.dimens
    val colors = CarTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = dimens.rowMinHeight)
            .padding(horizontal = dimens.spaceM, vertical = dimens.spaceS),
        verticalArrangement = Arrangement.spacedBy(dimens.spaceS),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(dimens.spaceXs)) {
            Text(
                text = title,
                style = CarTheme.type.titleSmall,
                color = if (enabled) colors.textPrimary else colors.textDisabled,
            )
            if (description != null) {
                Text(
                    text = description,
                    style = CarTheme.type.bodySmall,
                    color = colors.textSecondary,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(dimens.spaceS),
            // 顶对齐：只有某一个框出现校验提示时，其余框不会跟着上下跳。
            verticalAlignment = Alignment.Top,
        ) {
            content()
        }
    }
}

/**
 * [CarSettingNumberGroup] 里的单个数字输入框。**只能在那个 Row 里用**（需要 [RowScope]）。
 *
 * 数字用 [com.dbtool.carplay.ui.theme.CarTypography.mono]（等宽）：
 * 比例字体下 `1` 比 `8` 窄，光标停在 4 位数中间时整行会左右晃。
 *
 * 键盘固定 `KeyboardType.Number`——车机上弹字母键盘再让用户找数字键是最没必要的折磨。
 * 但**不在这里过滤非数字**：`onValueChange` 原样上抛，由调用方决定
 * （清空输入框是编辑中间态，直接吞掉会让用户删不动）。
 */
@Composable
fun RowScope.CarNumberField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isError: Boolean = false,
) {
    val dimens = CarTheme.dimens
    val colors = CarTheme.colors
    Column(
        modifier = modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(dimens.spaceXs),
    ) {
        Text(
            text = label,
            style = CarTheme.type.bodySmall,
            color = if (enabled) colors.textSecondary else colors.textDisabled,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = dimens.touchTargetMin),
            enabled = enabled,
            singleLine = true,
            isError = isError,
            textStyle = CarTheme.type.mono,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            shape = CarTheme.shapes.large,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = colors.surfaceVariant,
                unfocusedContainerColor = colors.surfaceVariant,
                disabledContainerColor = colors.surfaceVariant,
                focusedBorderColor = colors.primary,
                unfocusedBorderColor = Color.Transparent,
                disabledBorderColor = Color.Transparent,
                errorBorderColor = colors.error,
                focusedTextColor = colors.textPrimary,
                unfocusedTextColor = colors.textPrimary,
                disabledTextColor = colors.textDisabled,
                cursorColor = colors.primary,
            ),
        )
    }
}
