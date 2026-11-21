package com.dbtool.carplay.ui.theme

import android.provider.Settings
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

internal val LocalCarColors = staticCompositionLocalOf { CarLightColors }
internal val LocalCarTypography = staticCompositionLocalOf { CarTypography.of(1f) }
internal val LocalCarDimensions = staticCompositionLocalOf { CarDimensions.of(1f) }
internal val LocalCarShapes = staticCompositionLocalOf { CarShapes.of(CarDimensions.of(1f)) }
internal val LocalCarScale = staticCompositionLocalOf { 1f }
internal val LocalCarIsDark = staticCompositionLocalOf { false }

/**
 * 「减少动效」。
 *
 * 默认 false。由 [CarPlayTheme] 读系统的**动画时长缩放**自动置位：
 * 部分车机（以及"开发者选项里把动画关掉"的设备）会把它设成 `0`。
 * 此时任何循环动画都必须停掉——否则用户看到的是一个**卡住的转圈**，
 * 比完全不动更糟：他会以为应用死了。
 *
 * ⚠️ 只在主题建立时读一次。这个值在实际使用中不会变（要改得进开发者选项），
 * 为它挂一个 `ContentObserver` 是过度设计。
 */
val LocalReduceMotion = staticCompositionLocalOf { false }

/**
 * 访问 Design System 的唯一入口。
 *
 * 界面代码只允许通过 `CarTheme.colors` / `CarTheme.type` / `CarTheme.dimens` /
 * `CarTheme.shapes` 取值，禁止内联字面量。这样"整体改 UI 只改 Design System"
 * 才成立——否则一年后颜色和字号又会散落到上百个文件里。
 */
object CarTheme {
    val colors: CarColors
        @Composable @ReadOnlyComposable get() = LocalCarColors.current

    val type: CarTypography
        @Composable @ReadOnlyComposable get() = LocalCarTypography.current

    val dimens: CarDimensions
        @Composable @ReadOnlyComposable get() = LocalCarDimensions.current

    val shapes: CarShapes
        @Composable @ReadOnlyComposable get() = LocalCarShapes.current

    /** 整体缩放系数（随屏幕短边自动计算，也可由调用方显式指定）。 */
    val scale: Float
        @Composable @ReadOnlyComposable get() = LocalCarScale.current

    val isDark: Boolean
        @Composable @ReadOnlyComposable get() = LocalCarIsDark.current
}

/**
 * DBPlay 车机 UI 主题。
 *
 * 与常见 Android App 主题的三点关键差异：
 *
 * 1. **浅色与深色同等支持**。浅色是默认（现代车机系统级 HMI 的通行做法，
 *    白天强光下可读性最好）；深色完整实现，用于夜间/隧道。
 *    默认跟随系统 `isSystemInDarkTheme()`，由宿主决定。
 * 2. **不使用动态取色（Material You）**。动态取色会让同一款车机的观感
 *    随壁纸变化，与"这是一个车机系统 UI"的品牌一致性目标冲突。
 *    上游 `common` 的 Compose 主题用了 `dynamicColor = true`，
 *    那是模板默认值，不适用于产品 UI。
 * 3. **整体尺寸随屏幕短边缩放**。Android 的 dp 只保证物理尺寸一致，
 *    1024x600 与 1920x1080 的车机若用同一套 dp，后者的控件会显得只有一半大。
 *    见 [CarDimensions.scaleFor]。
 *
 * @param darkTheme 默认 false（浅色）。宿主可传 `isSystemInDarkTheme()` 跟随系统。
 * @param scale 显式指定缩放；传 null 时按实际窗口短边自动计算。
 */
@Composable
fun CarPlayTheme(
    darkTheme: Boolean = false,
    scale: Float? = null,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val resolvedScale = scale
            ?: CarDimensions.scaleFor(minOf(maxWidth.value, maxHeight.value))
        val dimens = CarDimensions.of(resolvedScale)
        val colors = if (darkTheme) CarDarkColors else CarLightColors
        val typography = CarTypography.of(resolvedScale)

        // 系统把动画时长缩放设成 0 时，一切循环动画都要停。
        val context = LocalContext.current
        val reduceMotion = remember(context) {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        }

        CompositionLocalProvider(
            LocalCarColors provides colors,
            LocalCarTypography provides typography,
            LocalCarDimensions provides dimens,
            LocalCarShapes provides CarShapes.of(dimens),
            LocalCarScale provides resolvedScale,
            LocalCarIsDark provides darkTheme,
            LocalReduceMotion provides reduceMotion,
        ) {
            MaterialTheme(
                colorScheme = if (darkTheme) materialDarkScheme(colors) else materialLightScheme(colors),
                typography = materialTypography(typography),
                content = content,
            )
        }
    }
}

/**
 * 把我们的色板映射到 Material3 的 `ColorScheme`。
 *
 * 目的不是"用 Material3 的主题"，而是让我们**确实用到的**几个 Material3 组件
 * （Switch / Slider / TextField / AlertDialog）拿到一致的底色，
 * 避免它们带着 Material 默认色出现在车机界面上。
 *
 * 只映射从 Material3 1.0 就存在的槽位，规避版本差异。
 */
private fun materialDarkScheme(c: CarColors) = darkColorScheme(
    primary = c.primary,
    onPrimary = c.onPrimary,
    primaryContainer = c.primaryContainer,
    onPrimaryContainer = c.onPrimaryContainer,
    secondary = c.secondary,
    onSecondary = c.onSecondary,
    secondaryContainer = c.secondaryContainer,
    onSecondaryContainer = c.onSecondaryContainer,
    tertiary = c.info,
    onTertiary = c.onInfo,
    background = c.background,
    onBackground = c.onBackground,
    surface = c.surface,
    onSurface = c.onSurface,
    surfaceVariant = c.surfaceVariant,
    onSurfaceVariant = c.onSurfaceVariant,
    error = c.error,
    onError = c.onError,
    outline = c.outline,
    scrim = c.scrim,
)

private fun materialLightScheme(c: CarColors) = lightColorScheme(
    primary = c.primary,
    onPrimary = c.onPrimary,
    primaryContainer = c.primaryContainer,
    onPrimaryContainer = c.onPrimaryContainer,
    secondary = c.secondary,
    onSecondary = c.onSecondary,
    secondaryContainer = c.secondaryContainer,
    onSecondaryContainer = c.onSecondaryContainer,
    tertiary = c.info,
    onTertiary = c.onInfo,
    background = c.background,
    onBackground = c.onBackground,
    surface = c.surface,
    onSurface = c.onSurface,
    surfaceVariant = c.surfaceVariant,
    onSurfaceVariant = c.onSurfaceVariant,
    error = c.error,
    onError = c.onError,
    outline = c.outline,
    scrim = c.scrim,
)

/** 把我们的大字号映射给 Material3，避免 Material 组件用默认的偏小字号。 */
private fun materialTypography(t: CarTypography) = Typography(
    displayLarge = t.displayLarge,
    displayMedium = t.displayMedium,
    headlineSmall = t.titleLarge,
    titleLarge = t.titleLarge,
    titleMedium = t.titleMedium,
    titleSmall = t.titleSmall,
    bodyLarge = t.bodyLarge,
    bodyMedium = t.bodyMedium,
    bodySmall = t.bodySmall,
    labelLarge = t.valueLarge,
    labelMedium = t.label,
    labelSmall = t.label,
)
