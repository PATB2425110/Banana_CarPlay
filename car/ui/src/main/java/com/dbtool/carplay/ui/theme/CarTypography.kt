package com.dbtool.carplay.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * DBPlay 字号令牌。
 *
 * ## 三条硬规则（见 `DBPlay-UI-DESIGN.md` §6）
 *
 * 1. **只用 400 与 500 两个字重。** 旧版在标题上大量使用 SemiBold(600)，
 *    结果是"处处都重要 = 处处都不重要"；层级改由**字号 + 颜色**承担。
 * 2. **字号必须是整数 sp。** 旧版的 12.5 / 11.5 在缩放后会落到半像素上，
 *    中文在小字号下会出现笔画粘连。
 * 3. **中文不设 `letterSpacing`。** 字距是拉丁字母的排版手段；
 *    对中文它只会把中英混排的节奏撑坏（旧版 `label` 带 1sp 字距就是这个毛病）。
 *
 * ## 尺寸取向
 *
 * 比手机大一到两档。车机观看距离约 50~70cm，且驾驶中只能用余光扫视，
 * 正文小于 14sp 会明显吃力，因此正文基准取 15sp、说明 14sp。
 *
 * 字体族**刻意使用系统默认**（[FontFamily.Default]）而不内置字体文件：
 * 车机 OEM 常通过系统级字体替换统一品牌观感，硬绑字体会与之冲突。
 * 唯一例外是 [mono]，用于日志/诊断这类需要等宽对齐的场景。
 */
@Immutable
data class CarTypography(
    /** 状态页主标题。 */
    val displayLarge: TextStyle,
    /** 页面标题。 */
    val displayMedium: TextStyle,
    /** 区块大标题 / 卡片主标题 / 弹窗标题。 */
    val titleLarge: TextStyle,
    /** 卡片标题。 */
    val titleMedium: TextStyle,
    /** 行标题（设置项名称）、按钮文字。 */
    val titleSmall: TextStyle,
    /** 主要正文。 */
    val bodyLarge: TextStyle,
    /** 次要正文（说明文字）。 */
    val bodyMedium: TextStyle,
    /** 辅助文字（脚注、提示）。 */
    val bodySmall: TextStyle,
    /** 分组标签 / 字段名。 */
    val label: TextStyle,
    /** 数值展示（设置项右侧的值、参数值）。 */
    val valueLarge: TextStyle,
    /** 等宽文字（日志、诊断）。 */
    val mono: TextStyle,
) {
    companion object {
        fun of(scale: Float): CarTypography {
            fun sp(value: Float) = (value * scale).sp

            /** 统一构造：只允许 Regular(400) 与 Medium(500)。 */
            fun style(
                size: Float,
                lineHeight: Float,
                weight: FontWeight,
                family: FontFamily = FontFamily.Default,
            ) = TextStyle(
                fontFamily = family,
                fontWeight = weight,
                fontSize = sp(size),
                lineHeight = sp(lineHeight),
            )

            val regular = FontWeight.Normal
            val medium = FontWeight.Medium

            return CarTypography(
                displayLarge = style(30f, 38f, medium),
                displayMedium = style(24f, 32f, medium),
                titleLarge = style(20f, 28f, medium),
                titleMedium = style(17f, 24f, medium),
                titleSmall = style(16f, 22f, medium),
                bodyLarge = style(15f, 22f, regular),
                bodyMedium = style(14f, 20f, regular),
                bodySmall = style(12f, 18f, regular),
                label = style(12f, 16f, medium),
                valueLarge = style(16f, 22f, medium),
                mono = style(12f, 17f, regular, FontFamily.Monospace),
            )
        }
    }
}
