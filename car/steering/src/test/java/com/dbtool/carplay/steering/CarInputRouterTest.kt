package com.dbtool.carplay.steering

import com.dbtool.carplay.api.input.CarInputEvent
import com.dbtool.carplay.api.input.CarInputListener
import com.dbtool.carplay.api.input.CarInputSource
import com.dbtool.carplay.api.input.CarKey
import com.dbtool.carplay.api.input.CarKeyAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CarInputRouter] 的去抖行为。
 *
 * 背景（2026-10-03 实车日志 `devlog_2149`）：一次物理按压会被车机推**两条** DOWN
 * （`onKeyCodeEvent` 与 `onShortClick` 两条回调都照发），间隔 123~330ms。
 * 去抖窗口若小于该间隔，一次按压就会转发两次 HID：
 * - `PLAY_PAUSE` × 2 → 暂停又播放 → 用户看到「按暂停后音乐自己又播」
 * - `NEXT` × 2 → 一次跳两首
 *
 * 本测试钉死：默认窗口必须能挡住实测的最长重复间隔（330ms），
 * 同时不误伤实测的最短真实按键间隔（2.8s）。
 */
class CarInputRouterTest {

    /** 记录被放行的事件。 */
    private class Recorder : CarInputListener {
        val accepted = mutableListOf<CarInputEvent>()
        override fun onInputEvent(event: CarInputEvent): Boolean {
            accepted.add(event)
            return true
        }
    }

    private fun routerWithClock(vararg ticks: Long): Pair<CarInputRouter, Recorder> {
        var index = 0
        val router = CarInputRouter(clock = { ticks[index.coerceAtMost(ticks.size - 1)].also { index++ } })
        val recorder = Recorder()
        router.attach(recorder)
        return router to recorder
    }

    private fun CarInputRouter.down(key: CarKey = CarKey.PLAY_PAUSE, repeat: Int = 0): Boolean =
        route(key, CarKeyAction.DOWN, CarInputSource.STEERING_WHEEL, rawKeyCode = 200085, repeatCount = repeat)

    // ------------------------------------------------------------ 默认窗口

    @Test
    fun `默认去抖窗口是 350ms`() {
        assertEquals(350L, CarInputRouter.DEFAULT_DEBOUNCE_MILLIS)
    }

    /**
     * 实测最长重复间隔 330ms —— 必须被挡掉。
     * （devlog_2149: 21:47:37.595 与 21:47:37.925，间隔 330ms）
     */
    @Test
    fun `挡掉实车 330ms 的第二条 DOWN`() {
        val (router, recorder) = routerWithClock(1_000L, 1_330L)
        assertTrue(router.down())
        assertFalse("330ms 内的第二条 DOWN 必须被挡", router.down())
        assertEquals(1, recorder.accepted.size)
    }

    /** 实测最短重复间隔 123ms —— 必须被挡掉。 */
    @Test
    fun `挡掉实车 123ms 的第二条 DOWN`() {
        val (router, recorder) = routerWithClock(1_000L, 1_123L)
        assertTrue(router.down())
        assertFalse(router.down())
        assertEquals(1, recorder.accepted.size)
    }

    /** 窗口边界：正好 350ms 视为已过窗口（`now - previous < debounce` 为 false）。 */
    @Test
    fun `窗口边界 350ms 放行`() {
        val (router, recorder) = routerWithClock(1_000L, 1_350L)
        assertTrue(router.down())
        assertTrue("恰好 350ms 不算抖动", router.down())
        assertEquals(2, recorder.accepted.size)
    }

    /** 实测最短真实按键间隔 2.8s —— 必须放行，不能误伤。 */
    @Test
    fun `不误伤 2_8 秒后的真实按键`() {
        val (router, recorder) = routerWithClock(1_000L, 1_000L + 2_800L)
        assertTrue(router.down())
        assertTrue(router.down())
        assertEquals(2, recorder.accepted.size)
    }

    // ------------------------------------------------------------ repeatCount

    /**
     * 长按连发（repeatCount > 0）不是独立按压，必须丢弃。
     * 与上游 DiPlay `CarPlayMediaKeys` 的 `event.repeatCount == 0` 判据一致。
     */
    @Test
    fun `repeatCount 大于 0 的 DOWN 被丢弃且不占用去抖窗口`() {
        val (router, recorder) = routerWithClock(1_000L, 1_100L, 1_200L)
        assertFalse("长按连发不算一次按压", router.down(repeat = 1))
        // 连发被丢弃后，紧接着的真实 DOWN 不应被去抖挡住
        assertTrue(router.down(repeat = 0))
        assertFalse("刚放行过，第二条仍被挡", router.down(repeat = 0))
        assertEquals(1, recorder.accepted.size)
    }

    // ------------------------------------------------------------ 分键独立

    /** 去抖按 CarKey 分别计时：PLAY_PAUSE 不该挡住紧随其后的 NEXT。 */
    @Test
    fun `不同按键各自独立计时`() {
        val (router, recorder) = routerWithClock(1_000L, 1_100L)
        assertTrue(router.down(CarKey.PLAY_PAUSE))
        assertTrue("NEXT 有自己的计时，不应被 PLAY_PAUSE 挡住", router.down(CarKey.NEXT))
        assertEquals(2, recorder.accepted.size)
    }

    // ------------------------------------------------------------ 可配置

    @Test
    fun `setDebounceMillis 0 关闭抑制`() {
        val (router, recorder) = routerWithClock(1_000L, 1_001L)
        router.setDebounceMillis(0)
        assertTrue(router.down())
        assertTrue("关闭抑制后连发都放行", router.down())
        assertEquals(2, recorder.accepted.size)
    }

    @Test
    fun `reset 清空抖动状态`() {
        val (router, recorder) = routerWithClock(1_000L, 1_100L)
        assertTrue(router.down())
        assertFalse(router.down())
        router.reset()
        assertTrue("reset 后旧状态不再压制新按键", router.down())
        assertEquals(2, recorder.accepted.size)
    }

    // ------------------------------------------------------------ UP 不占窗口

    /**
     * UP 事件不去抖 —— 「按下→抬起」间隔通常 100~300ms，若 UP 也走窗口，
     * 抬起会被吞掉，那后面紧跟的车机短按回调（同一次按压的第二条 DOWN）
     * 就会变成一次孤立的「按下」，反而更容易被 CarPlay 当成一次新按键。
     */
    @Test
    fun `UP 事件不被去抖挡住`() {
        val (router, recorder) = routerWithClock(1_000L, 1_200L)
        assertTrue(router.down())
        // 抬起距按下仅 200ms，仍在 350ms 窗口内，但 UP 不受窗口约束
        assertTrue(
            "UP 必须放行",
            router.route(CarKey.PLAY_PAUSE, CarKeyAction.UP, CarInputSource.STEERING_WHEEL),
        )
        // 代码只把 DOWN 写入窗口，UP 不刷新，故窗口仍从 1000ms 起算
        assertEquals(2, recorder.accepted.size)
    }

    /**
     * 短按回调（同一次按压的第三条）仍被窗口挡住：
     * 按下(1000) → 抬起(1200) → 短按回调(1300)。
     * 抬起会放行（UP 不去抖），但短按回调作为 DOWN 必须挡掉 ——
     * 判据是「距上次**放行的 DOWN**」，UP 不刷新窗口，故仍以 1000ms 起算。
     */
    @Test
    fun `短按回调仍被 DOWN 的窗口挡住`() {
        val (router, recorder) = routerWithClock(1_000L, 1_200L, 1_300L)
        assertTrue(router.down())
        router.route(CarKey.PLAY_PAUSE, CarKeyAction.UP, CarInputSource.STEERING_WHEEL)
        assertFalse("距放行的 DOWN 仅 300ms，第二条 DOWN 必须挡", router.down())
        val acceptedDowns = recorder.accepted.count { it.action == CarKeyAction.DOWN }
        assertEquals("只应有一次 DOWN 被转发", 1, acceptedDowns)
        assertEquals("UP 照样放行，故共两条事件", 2, recorder.accepted.size)
    }
}
