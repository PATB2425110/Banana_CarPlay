package com.dbtool.carplay.bridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「顶栏黑底 + 黑字」配色回归契约（2026-10-05 实车修复）。
 *
 * ## 故障现场
 *
 * 斌哥原话：「顶栏的颜色也优化下，都是黑底跟文字黑冲突了」。
 *
 * ## 成因
 *
 * 视频页底衬恒为 `videoBackground`（浅/深主题都是 `#0E0F12` 近黑，
 * 因为视频区天然要暗）——但顶栏文字用的是 `textPrimary`：
 *
 * | 主题 | 底 | 字 | 效果 |
 * |---|---|---|---|
 * | 浅色 | `#0E0F12` | `#1D1D1F` | **黑字压黑底，看不见** |
 * | 深色 | `#0E0F12` | `#F2F3F5` | 正常 |
 *
 * 车机默认跟随系统主题，多半正好落在浅色 —— 于是撞车。
 *
 * ## 修法
 *
 * 「底色恒定暗 ⇒ 文字恒定亮」：新增 `onVideoSurface` / `onVideoSurfaceVariant`
 * 两个**不随主题变**的浅色，两套主题都给亮色值；视频页改用它们。
 *
 * 这个测试**静态读源码**，把「暗底不许配主题化文字色」这条钉死。
 */
class VideoSurfaceContrastContractTest {

    private fun sourceOf(relative: String): String {
        val candidates = listOf(
            File(relative),
            File("../$relative"),
            File("../../$relative"),
            File("../../../$relative"),
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("找不到源码文件 $relative（从 ${File(".").absolutePath} 起找过 ${candidates.size} 个位置）")
        return file.readText()
    }

    private val colorsSrc =
        "car/ui/src/main/java/com/dbtool/carplay/ui/theme/CarColors.kt"

    private val screenSrc =
        "car/ui/src/main/java/com/dbtool/carplay/ui/screens/CarPlayScreen.kt"

    /** 色板必须提供「视频区专用、不随主题变」的文字色。 */
    @Test
    fun `色板必须定义 onVideoSurface 系列`() {
        val src = sourceOf(colorsSrc)
        assertTrue("缺少 `val onVideoSurface: Color` 字段声明。", src.contains("val onVideoSurface: Color"))
        assertTrue(
            "缺少 `val onVideoSurfaceVariant: Color` 字段声明。",
            src.contains("val onVideoSurfaceVariant: Color"),
        )
        // 两套主题各给一次值（浅色板 + 深色板）。
        val defs = Regex("""onVideoSurface\s*=\s*Color\(0x""").findAll(src).count()
        assertTrue(
            "onVideoSurface 应有 2 处赋值（浅色板 + 深色板），实测 $defs 处。",
            defs >= 2,
        )
        val vdefs = Regex("""onVideoSurfaceVariant\s*=\s*Color\(0x""").findAll(src).count()
        assertTrue(
            "onVideoSurfaceVariant 应有 2 处赋值（浅色板 + 深色板），实测 $vdefs 处。",
            vdefs >= 2,
        )
    }

    /**
     * 两套主题下 `onVideoSurface` 都必须**足够亮**。
     *
     * `videoBackground` 两套主题都是 `#0E0F12`（近黑），文字的亮度必须能与它拉开，
     * 否则又回到「黑字压黑底」。这里按相对亮度做个粗判：RGB 均值须 ≥ 0x80。
     */
    @Test
    fun `onVideoSurface 在两套主题下都必须够亮`() {
        val src = sourceOf(colorsSrc)
        val hexes = Regex("""onVideoSurface\s*=\s*Color\(0xFF([0-9A-Fa-f]{6})\)""")
            .findAll(src).map { it.groupValues[1] }.toList()

        assertTrue("没解析到 onVideoSurface 的浅色值。", hexes.size >= 2)
        for (h in hexes) {
            val r = h.substring(0, 2).toInt(16)
            val g = h.substring(2, 4).toInt(16)
            val b = h.substring(4, 6).toInt(16)
            val avg = (r + g + b) / 3
            assertTrue(
                "onVideoSurface=#${h} 的 RGB 均值只有 $avg（<128）——压近黑底 #0E0F12 会看不清。",
                avg >= 0x80,
            )
        }
    }

    /**
     * 视频页**主区域（videoBackground 打底）**不许再用主题化的文字色。
     *
     * 「底恒定暗、字随主题变」正是本 bug 的形态。注意：调试日志浮层自带
     * `overlaySurface` 底衬（浅色主题下是白的），它用 `textSecondary` 是**对的**，
     * 所以这里只检查 `videoBackground` 打底的那一段，不搞一刀切。
     */
    @Test
    fun `视频页暗底上的文字不许用 textPrimary 或 textSecondary`() {
        val src = sourceOf(screenSrc)

        assertTrue(
            "视频页应改用 onVideoSurface。",
            src.contains("CarTheme.colors.onVideoSurface"),
        )
        assertTrue(
            "视频页副标题应改用 onVideoSurfaceVariant。",
            src.contains("CarTheme.colors.onVideoSurfaceVariant"),
        )

        // 只取「videoBackground 打底」的主区域段落（到日志浮层函数注释为止）。
        val main = src.substringAfter(".background(CarTheme.colors.videoBackground)")
            .substringBefore("调试日志浮层")
        assertTrue("没切出主区域段落，测试本身失效了。", main.isNotEmpty())
        assertFalse(
            "视频页主区域仍用 textPrimary 做正文色——浅色主题下 = 黑字压黑底。",
            main.contains("color = CarTheme.colors.textPrimary"),
        )
        assertFalse(
            "视频页主区域仍用 textSecondary 做正文色——浅色主题下对比度不足。",
            main.contains("color = CarTheme.colors.textSecondary"),
        )
    }
}
