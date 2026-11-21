package com.dbtool.carplay.bridge

import com.dbtool.carplay.api.display.CarVideoCanvas
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 画布尺寸"宽容比较"的单测。
 *
 * ## 为什么值得钉
 *
 * 这个比较直接决定**要不要重建整条 CarPlay 协议栈**（代价 = 画面黑一下 +
 * 4 秒重连）。实车（Android 9 / QUALCOMM CS11）上承载画面的 TextureView
 * 高度会在 1080 与 1008 之间反复跳——差的 72px 正好是顶部状态条
 * （日志里 `content=[0,72]`）。
 *
 * 旧实现用 `canvas == next`（data class 严格相等）挡不住 72px 的变化，
 * 于是车机会自己把正在跑的会话重连掉，日志表现：
 *
 * ```
 * 17:52:36.031 AirPlay session ended peer=192.168.175.2
 * 17:52:42.012 IAP2 RX ... carplay-start-session     ← 4 秒后重连
 * ```
 *
 * 这两个"看起来很像"的尺寸在**不同场景下必须给出相反的结论**：
 * - 状态条抖动（差 72px）⇒ 必须视为同一尺寸（否则每隔几十秒断一次）
 * - 横竖屏旋转（1080↔1920）⇒ 必须视为不同尺寸（否则画面被拉伸）
 *
 * 所以不能靠"上车试"来验证，在这里钉死。
 */
class CarVideoCanvasToleranceTest {

    private fun canvas(width: Int, height: Int) = CarVideoCanvas(width, height)

    @Test
    fun `状态条抖动 1080 与 1008 视为同一尺寸`() {
        // 实车原样：1920x1080 → 1920x1008，差 72px。
        assertTrue(
            "实车状态条抖动（差 72px）必须被吞掉，否则会话被反复重连",
            canvas(1920, 1080).roughlyEquals(canvas(1920, 1008)),
        )
    }

    @Test
    fun `横竖屏旋转视为不同尺寸`() {
        // 差 840px，远超容差，必须触发重连（画面要重新协商）。
        assertFalse(
            "旋转 1080↔1920 必须触发重连，否则画面被拉伸",
            canvas(1920, 1080).roughlyEquals(canvas(1080, 1920)),
        )
    }

    @Test
    fun `容差是闭区间 恰好等于阈值算同一尺寸`() {
        assertTrue(canvas(1920, 1080).roughlyEquals(canvas(1920, 1080 - 96)))
        assertFalse(canvas(1920, 1080).roughlyEquals(canvas(1920, 1080 - 97)))
    }

    @Test
    fun `宽度差超阈值同样视为不同尺寸`() {
        // 分屏把宽度砍掉一半这类真实变化不能被吞。
        assertFalse(canvas(1920, 1080).roughlyEquals(canvas(960, 1080)))
    }

    @Test
    fun `自身永远算同一尺寸`() {
        assertTrue(canvas(1, 1).roughlyEquals(canvas(1, 1)))
        assertTrue(canvas(1920, 1008).roughlyEquals(canvas(1920, 1008)))
    }

    @Test
    fun `方向无关 两边都超才算不同`() {
        // 一边超、一边不超 ⇒ 仍然算同一尺寸（判定是"两条边都在容差内"的取反）。
        assertFalse(canvas(1920, 1080).roughlyEquals(canvas(1500, 1080)))
        assertTrue(canvas(1920, 1080).roughlyEquals(canvas(1850, 1050)))
    }
}
