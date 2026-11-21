package com.dbtool.carplay.bridge

import com.dbtool.carplay.api.display.CarClusterSafeAreaInsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 安全区**必须夹进面板**才能报给 iPhone（PATCH-0079，2026-10-11）。
 *
 * ## 真车依据（CX11，app 1.0.37，车主给的诊断包）
 *
 * 两块屏都报了这样一组 `safeArea`（面板 1920×1080）：
 *
 * ```
 * safeArea={widthPixels=1912, heightPixels=-1560, originXPixels=1, originYPixels=1920}
 * ```
 *
 * 上游 `AirPlayInfoPlist.areaDict` 是按 insets 现算的
 * （`height = 面板高 - top - bottom`、`originY = top`），反解这组值得到
 * **`top=1920`（比面板还高）、`bottom=720`、`left=1`、`right=7`** ⇒ 高度为负。
 * 而 iPhone 拿到自相矛盾的 safeArea 会**直接拆会话**：
 * 同一份日志里 `AirPlay iAP tunnel peer EOF` → 空 `TEARDOWN`（`activeBefore=[130]`，视频流一条都没建）。
 *
 * 原版 `GeelyClusterSafeArea` 用"矩形 → `normalized()` → `toInsets()`"保证四边恒自洽；
 * 我们这边少了那一步 ⇒ 这里补上兜底夹取，并把它钉在测试里。
 */
class ClusterSafeAreaClampTest {

    @Test
    fun `真车那组脏值必须被夹成合法区域`() {
        // 正是日志反解出来的那四个数。
        val dirty = CarClusterSafeAreaInsets(left = 1, top = 1920, right = 7, bottom = 720)
        val safe = dirty.clampedTo(panelWidthPixels = 1920, panelHeightPixels = 1080)

        assertTrue("top 不能超过面板高", safe.top <= 1079)
        assertTrue("bottom 不能超过剩下的空间", safe.bottom <= 1079 - safe.top)
        // 上游按 insets 现算 height ⇒ 必须保证算出来是正的（这是会话不被拆的底线）。
        assertTrue(
            "报出去的 safeArea 高度必须 > 0（top+bottom 不能超过面板高）：$safe",
            1080 - safe.top - safe.bottom > 0,
        )
        assertTrue("宽度也必须 > 0", 1920 - safe.left - safe.right > 0)
    }

    @Test
    fun `正常值原样保留`() {
        val normal = CarClusterSafeAreaInsets(left = 0, top = 0, right = 55, bottom = 420)
        assertEquals(normal, normal.clampedTo(1920, 1080))
    }

    @Test
    fun `任意乱填都不产出负宽高`() {
        val wild = listOf(-100, 0, 1, 500, 1080, 1920, 99999)
        for (top in wild) {
            for (bottom in wild) {
                for (left in wild) {
                    for (right in wild) {
                        val safe = CarClusterSafeAreaInsets(left, top, right, bottom)
                            .clampedTo(1920, 1080)
                        assertTrue(
                            "top=$top bottom=$bottom left=$left right=$right ⇒ $safe",
                            1080 - safe.top - safe.bottom > 0 && 1920 - safe.left - safe.right > 0,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `面板尺寸为 0 或负数时也不崩、不产负值`() {
        val safe = CarClusterSafeAreaInsets(1, 2, 3, 4).clampedTo(0, -5)
        assertEquals(0, safe.top)
        assertEquals(0, safe.left)
        assertEquals(0, safe.bottom)
        assertEquals(0, safe.right)
    }
}
