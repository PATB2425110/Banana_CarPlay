package com.dbtool.carplay.bridge.display

import com.dbtool.carplay.api.display.CarVideoCanvas
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁死「只有真实分辨率切换才重建协议栈」这条判定。
 *
 * ## 测的是产品代码，不是抄的实现
 *
 * 直接调 [CarVideoCanvas.isRealResolutionChange]（产品代码）。放在本模块是因为
 * `car/app` 是 application 模块、没配 test source set，单测跑不到那里；
 * `car/core-bridge` 依赖 `car/api`，能直接引用。
 *
 * ## 为什么值得单写一份测试
 *
 * 它的失败模式**只在车上才看得见**，而且会伪装成别的问题：
 * - 判定过松 → 系统栏进出被当成分辨率切换 → 重建整条协议栈 →
 *   音轨销毁重建 → **音乐断 4~5 秒**（用户报的是"音乐卡一下"，
 *   根本联想不到尺寸判定；这就是 0.10.10 的现场）；
 * - 判定过紧 → 真旋转/分屏不重连 → 画面被拉伸。
 *
 * 两个方向都只能靠上车踩，代价极高（一趟车程 + 一份诊断包）。所以把边界
 * 固化成断言，让以后任何人调这个阈值都必须先过这两组数。
 *
 * ## 数据来源
 *
 * 松侧用 0.10.10 车机日志实测的过冲序列（1080 → 1008 → 1032 → 1080）；
 * 紧侧用真实的横竖屏与分屏尺寸。
 */
class CanvasResolutionChangeTest {

    // ------------------------------------------------------------ 松侧：必须挡住

    @Test
    fun `状态栏进出 1080 到 1008 不触发重连`() {
        assertFalse(
            "1920x1080 → 1920x1008 只是状态栏占了 72px，不该重建协议栈（会断音 4~5 秒）",
            CarVideoCanvas(1920, 1080).isRealResolutionChange(CarVideoCanvas(1920, 1008)),
        )
    }

    @Test
    fun `日志实测的 1032 过冲不触发重连`() {
        assertFalse(
            "1920x1032 是 0.10.10 日志里的过冲帧（与 1080 差 48px），旧容差会放行，这是回归点",
            CarVideoCanvas(1920, 1080).isRealResolutionChange(CarVideoCanvas(1920, 1032)),
        )
    }

    @Test
    fun `日志实测的过冲序列全程不触发重连`() {
        // 0.10.10 日志的时间线：1080 → 1008 → 1032 → 1080
        val heights = listOf(1080, 1008, 1032, 1080)
        for (i in 0 until heights.size - 1) {
            val before = CarVideoCanvas(1920, heights[i])
            val after = CarVideoCanvas(1920, heights[i + 1])
            assertFalse(
                "第 $i 步 ${heights[i]} → ${heights[i + 1]} 不该触发重连" +
                    "（旧 roughlyEquals 在此放行，导致 4 次断音循环）",
                before.isRealResolutionChange(after),
            )
        }
    }

    @Test
    fun `导航栏占位导致的 48px 高度变化不触发重连`() {
        assertFalse(
            "底部 48px 导航栏进出：1920x1080 → 1920x1032 不该重连",
            CarVideoCanvas(1920, 1080).isRealResolutionChange(CarVideoCanvas(1920, 1032)),
        )
    }

    @Test
    fun `上下两条栏同时占位 96px 仍不触发重连`() {
        assertFalse(
            "1920x1080 → 1920x984 比例漂移 8.9%，仍在 15% 阈值内",
            CarVideoCanvas(1920, 1080).isRealResolutionChange(CarVideoCanvas(1920, 984)),
        )
    }

    @Test
    fun `反复的栏抖动不会累积成一次重连`() {
        // 真实场景：用户在播放中，栏反复进出十几次。任何相邻两步都不该放行。
        val jitter = listOf(1080, 1008, 1080, 1032, 1080, 1008, 1032, 1080)
        for (i in 0 until jitter.size - 1) {
            assertFalse(
                "抖动序列第 $i 步 ${jitter[i]} → ${jitter[i + 1]} 必须被忽略",
                CarVideoCanvas(1920, jitter[i]).isRealResolutionChange(CarVideoCanvas(1920, jitter[i + 1])),
            )
        }
    }

    // ------------------------------------------------------------ 紧侧：必须放行

    @Test
    fun `横屏转竖屏必须触发重连`() {
        assertTrue(
            "1920x1080 → 1080x1920 是真实的方向切换（比例漂移 68%），必须重连",
            CarVideoCanvas(1920, 1080).isRealResolutionChange(CarVideoCanvas(1080, 1920)),
        )
    }

    @Test
    fun `分屏导致宽度减半必须触发重连`() {
        assertTrue(
            "1920x1080 → 960x1080 是真实的分屏（比例漂移 50%），必须重连",
            CarVideoCanvas(1920, 1080).isRealResolutionChange(CarVideoCanvas(960, 1080)),
        )
    }

    // ------------------------------------------------------------ 退化与边界

    @Test
    fun `同比例的纯分辨率变化判为未变（已知取舍）`() {
        assertFalse(
            "1920x1080 → 1280x720 比例漂移 0，被判为未变。" +
                "这是有意的取舍：车机面板分辨率固定，系统栏进出永远单轴，" +
                "这种同比例输入在车机上不存在；为它放宽会导致栏抖动重新漏进来",
            CarVideoCanvas(1920, 1080).isRealResolutionChange(CarVideoCanvas(1280, 720)),
        )
    }

    @Test
    fun `阈值边界的两侧`() {
        // 构造漂移刚好低于 / 高于 15% 的两个高度（宽度固定 1920）
        // 1080 / (1+0.14) ≈ 947.4 → 947 时漂移 ≈ 14.06%
        // 1080 / (1+0.16) ≈ 931.0 → 931 时漂移 ≈ 16.00%
        assertFalse(
            "漂移约 14%（低于 15%）应忽略",
            CarVideoCanvas(1920, 1080).isRealResolutionChange(CarVideoCanvas(1920, 947)),
        )
        assertTrue(
            "漂移约 16%（高于 15%）应放行",
            CarVideoCanvas(1920, 1080).isRealResolutionChange(CarVideoCanvas(1920, 931)),
        )
    }
}
