package com.dbtool.carplay.ui.connection

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dbtool.carplay.api.session.CarSessionPhase
import com.dbtool.carplay.ui.theme.CarTheme

/**
 * 动画层宿主：告诉组件树当前是否启用「减少动效」系统设置。
 */
val LocalReduceMotion = staticCompositionLocalOf { false }

/**
 * CarPlay 连接页 · 极简 Apple logo。
 *
 * 设计：
 * - 浅色主题：纯白背景 + 黑色 Apple logo
 * - 深色主题：纯黑背景 + 白色 Apple logo
 * - 无动画、无设备图、无装饰线
 * - 底部保留一行连接状态文案
 */
@Composable
fun CarPlayConnectionAnimation(
    phase: CarSessionPhase,
    modifier: Modifier = Modifier,
) {
    val isDark = CarTheme.isDark
    val background = if (isDark) Color.Black else Color.White
    val logoColor = if (isDark) Color.White else Color.Black
    val (title, _) = remember(phase) { phaseToText(phase) }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        // 背景
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(color = background)
        }

        // Apple logo居中
        val logoSizeDp = 96.dp
        Canvas(
            modifier = Modifier.padding(bottom = 32.dp)
        ) {
            drawAppleLogoGlyph(
                center = Offset(size.width / 2f, size.height / 2f - 16.dp.toPx()),
                size = logoSizeDp.toPx(),
                color = logoColor,
            )
        }

        // 底部状态文案
        Text(
            text = title,
            style = CarTheme.type.bodyLarge,
            color = logoColor.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 80.dp),
        )
    }
}

private fun phaseToText(phase: CarSessionPhase): Pair<String, String> = when (phase) {
    CarSessionPhase.PREPARING_MFI, CarSessionPhase.WAITING_FOR_MFI ->
        "正在连接 CarPlay" to ""
    CarSessionPhase.WAITING_FOR_DEVICE ->
        "正在连接 iPhone" to ""
    CarSessionPhase.CONNECTING ->
        "正在连接车机" to ""
    CarSessionPhase.RECONNECTING ->
        "正在重新连接" to ""
    CarSessionPhase.ACTIVE ->
        "CarPlay 已连接" to ""
    else ->
        "CarPlay" to ""
}

private fun DrawScope.drawAppleLogoGlyph(
    center: Offset,
    size: Float,
    color: Color,
) {
    val s = size / 2f
    val cx = center.x
    val cy = center.y

    // 苹果主体：经典比例，顶部咬口、两侧内收、底部圆润
    val body = Path().apply {
        // 咬口底部中心
        moveTo(cx, cy - 0.55f * s)
        // 咬口右侧 → 右肩
        cubicTo(
            cx + 0.16f * s, cy - 0.53f * s,
            cx + 0.40f * s, cy - 0.74f * s,
            cx + 0.55f * s, cy - 0.72f * s,
        )
        // 右肩 → 右腰
        cubicTo(
            cx + 0.78f * s, cy - 0.68f * s,
            cx + 0.82f * s, cy - 0.22f * s,
            cx + 0.74f * s, cy - 0.08f * s,
        )
        // 右腰 → 右底
        cubicTo(
            cx + 0.66f * s, cy + 0.35f * s,
            cx + 0.48f * s, cy + 0.72f * s,
            cx + 0.30f * s, cy + 0.80f * s,
        )
        // 底部
        cubicTo(
            cx + 0.15f * s, cy + 0.86f * s,
            cx - 0.15f * s, cy + 0.86f * s,
            cx - 0.30f * s, cy + 0.80f * s,
        )
        // 左底 → 左腰
        cubicTo(
            cx - 0.48f * s, cy + 0.72f * s,
            cx - 0.66f * s, cy + 0.35f * s,
            cx - 0.74f * s, cy - 0.08f * s,
        )
        // 左腰 → 左肩
        cubicTo(
            cx - 0.82f * s, cy - 0.22f * s,
            cx - 0.78f * s, cy - 0.68f * s,
            cx - 0.55f * s, cy - 0.72f * s,
        )
        // 左肩 → 咬口左侧 → 咬口底部
        cubicTo(
            cx - 0.40f * s, cy - 0.74f * s,
            cx - 0.16f * s, cy - 0.53f * s,
            cx, cy - 0.55f * s,
        )
        close()
    }
    drawPath(path = body, color = color)

    // 叶子：水滴形，位于右肩上方
    val leaf = Path().apply {
        moveTo(cx + 0.38f * s, cy - 0.72f * s)
        cubicTo(
            cx + 0.58f * s, cy - 1.04f * s,
            cx + 0.98f * s, cy - 0.90f * s,
            cx + 0.84f * s, cy - 0.58f * s,
        )
        cubicTo(
            cx + 0.72f * s, cy - 0.38f * s,
            cx + 0.50f * s, cy - 0.48f * s,
            cx + 0.38f * s, cy - 0.72f * s,
        )
        close()
    }
    drawPath(path = leaf, color = color)
}
