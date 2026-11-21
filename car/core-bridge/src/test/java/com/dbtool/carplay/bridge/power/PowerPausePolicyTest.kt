package com.dbtool.carplay.bridge.power

import com.dbtool.carplay.api.power.CarPowerSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「熄火自动暂停」纯逻辑单测。
 *
 * 这些边界没法靠上车试高效发现（熄火 14 秒 vs 15 秒、连续 OFF 是否刷新起点），
 * 所以在 JVM 上钉死：时间由参数传入，不依赖 Handler / SystemClock。
 */
class PowerPausePolicyTest {

    private fun off(infotainment: Int? = 0) =
        CarPowerSnapshot(entertainmentOn = false, infotainmentMode = infotainment)

    private fun on(infotainment: Int? = 1) =
        CarPowerSnapshot(entertainmentOn = true, infotainmentMode = infotainment)

    /** 熄火（娱乐模式 OFF）必须**立刻**暂停，不能等 15 秒。 */
    @Test
    fun `熄火立即暂停`() {
        val policy = PowerPausePolicy()
        assertEquals(PowerPauseAction.PAUSE_NOW, policy.onSnapshot(off(), 1_000L))
        assertEquals(1_000L, policy.pendingSinceMs)
        assertFalse(policy.stopEmitted)
    }

    /** 15 秒仍熄火 ⇒ 断开会话。 */
    @Test
    fun `十五秒后断开会话`() {
        val policy = PowerPausePolicy()
        policy.onSnapshot(off(), 0L)
        assertEquals(15_000L, policy.deadlineMs())
        assertEquals(PowerPauseAction.PAUSE_AND_STOP_SESSION, policy.onTimeout(15_000L))
        assertTrue(policy.stopEmitted)
    }

    /** 14.999 秒还在宽限期内，一个动作都不发。 */
    @Test
    fun `宽限期内到点不断开`() {
        val policy = PowerPausePolicy()
        policy.onSnapshot(off(), 500L)
        assertEquals(PowerPauseAction.NONE, policy.onTimeout(15_499L))
    }

    /** 时钟边界：恰好 15000ms 算"到点"（`>=` 而不是 `>`）。 */
    @Test
    fun `恰好十五秒算到点`() {
        val policy = PowerPausePolicy()
        policy.onSnapshot(off(), 2_000L)
        assertEquals(PowerPauseAction.NONE, policy.onTimeout(16_999L))
        assertEquals(PowerPauseAction.PAUSE_AND_STOP_SESSION, policy.onTimeout(17_000L))
    }

    /** 15 秒内回到 ON ⇒ 取消断开，且不再需要闹钟。 */
    @Test
    fun `中途恢复取消断开`() {
        val policy = PowerPausePolicy()
        policy.onSnapshot(off(), 0L)
        assertEquals(PowerPauseAction.NONE, policy.onSnapshot(on(), 5_000L))
        assertNull(policy.pendingSinceMs)
        assertNull(policy.deadlineMs())
        assertEquals(PowerPauseAction.NONE, policy.onTimeout(30_000L))
    }

    /** 断开动作只发一次：定时器多跑几拍也不能把会话反复停。 */
    @Test
    fun `断开只发一次`() {
        val policy = PowerPausePolicy()
        policy.onSnapshot(off(), 0L)
        assertEquals(PowerPauseAction.PAUSE_AND_STOP_SESSION, policy.onTimeout(15_000L))
        assertEquals(PowerPauseAction.NONE, policy.onTimeout(20_000L))
        assertEquals(PowerPauseAction.NONE, policy.onTimeout(60_000L))
        assertNull(policy.deadlineMs())
    }

    /** 读不到娱乐模式（null）时既不暂停也不计时。 */
    @Test
    fun `状态读不到不动作`() {
        val policy = PowerPausePolicy()
        assertEquals(
            PowerPauseAction.NONE,
            policy.onSnapshot(CarPowerSnapshot(null, 0), 0L),
        )
        assertEquals(PowerPauseAction.NONE, policy.onSnapshot(null, 1_000L))
        assertNull(policy.pendingSinceMs)
        assertEquals(PowerPauseAction.NONE, policy.onTimeout(60_000L))
    }

    /** 连续 OFF 不重复暂停，也**不刷新**起点（否则持续熄火永远到不了点）。 */
    @Test
    fun `连续熄火不重复暂停也不推迟起点`() {
        val policy = PowerPausePolicy()
        assertEquals(PowerPauseAction.PAUSE_NOW, policy.onSnapshot(off(), 0L))
        assertEquals(PowerPauseAction.NONE, policy.onSnapshot(off(), 6_000L))
        assertEquals(PowerPauseAction.NONE, policy.onSnapshot(off(), 14_000L))
        assertEquals(0L, policy.pendingSinceMs)
        assertEquals(PowerPauseAction.PAUSE_AND_STOP_SESSION, policy.onTimeout(15_000L))
    }

    /** ON 清状态；之后再熄火必须能再次暂停、再次断开。 */
    @Test
    fun `恢复后再次熄火能再次触发`() {
        val policy = PowerPausePolicy()
        assertEquals(PowerPauseAction.PAUSE_NOW, policy.onSnapshot(off(), 0L))
        assertEquals(PowerPauseAction.NONE, policy.onSnapshot(on(), 3_000L))
        assertEquals(PowerPauseAction.PAUSE_NOW, policy.onSnapshot(off(), 10_000L))
        assertEquals(10_000L, policy.pendingSinceMs)
        assertEquals(PowerPauseAction.PAUSE_AND_STOP_SESSION, policy.onTimeout(25_000L))
        assertEquals(PowerPauseAction.NONE, policy.onTimeout(26_000L))
    }

    /** 断开后再 ON 再 OFF：第二轮的断开仍然只能发一次，但停会话会重新被允许。 */
    @Test
    fun `断开后恢复再熄火可以再次断开`() {
        val policy = PowerPausePolicy()
        policy.onSnapshot(off(), 0L)
        assertEquals(PowerPauseAction.PAUSE_AND_STOP_SESSION, policy.onTimeout(15_000L))
        policy.onSnapshot(on(), 20_000L)
        assertFalse(policy.stopEmitted)
        assertEquals(PowerPauseAction.PAUSE_NOW, policy.onSnapshot(off(), 30_000L))
        assertEquals(PowerPauseAction.PAUSE_AND_STOP_SESSION, policy.onTimeout(45_000L))
    }

    /** reset() 用于关开关 / 会话重建：清得干净，下一次熄火重新走完整流程。 */
    @Test
    fun `重置后重新开始`() {
        val policy = PowerPausePolicy()
        policy.onSnapshot(off(), 0L)
        policy.reset()
        assertNull(policy.pendingSinceMs)
        assertFalse(policy.stopEmitted)
        assertEquals(PowerPauseAction.PAUSE_NOW, policy.onSnapshot(off(), 100_000L))
        assertEquals(100_000L, policy.pendingSinceMs)
    }
}
