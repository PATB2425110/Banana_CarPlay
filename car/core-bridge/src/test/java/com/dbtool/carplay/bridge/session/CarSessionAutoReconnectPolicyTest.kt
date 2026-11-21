package com.dbtool.carplay.bridge.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「会话意外结束 ⇒ 自动重连」判据的单测（PATCH-0095，2026-10-11）。
 *
 * ## 为什么这些数值得钉
 *
 * 起因是 1.0.51 诊断包：会话在 `17:06:45` 被 iPhone 关掉之后**再没回来**，
 * 一行动作都没有。修复就是"排一次有界重连"，而这里钉的是它的**三个边界**：
 *
 * 1. **第一次要快**（2 秒）—— 断连成因大多是瞬时抖动，等太久用户就以为坏了；
 * 2. **必须封顶**（最多 3 次、整轮 14 秒）—— 无界重连会让"手机开走了"
 *    变成 App 在后台一直折腾，用户观感极差；
 * 3. **用户点了停止就一次都不连** —— 那是"App 不听使唤"，比不自动连更糟。
 *
 * ## 为什么单独抽一个模块来测
 *
 * 接线点在 `car/app` 的 `CarAppController`，而那个模块是 application 模块、
 * **没有 test source set**（见 `CarAppController` 里对
 * `CarVideoCanvas.CANVAS_ASPECT_TOLERANCE` 的同款说明）。
 * 所以判据放在这里，接线只靠 `CarAppControllerAutoReconnectWiringTest` 的源码级断言。
 */
class CarSessionAutoReconnectPolicyTest {

    // ── 退避节奏 ────────────────────────────────────────────────────────────

    /** 第一次必须快：瞬时抖动（Wi-Fi 重连、iPhone 短暂锁屏）要能自动接上。 */
    @Test
    fun `第一次退避是 2 秒`() {
        assertEquals(2_000L, autoReconnectDelayMillis(1))
    }

    /** 之后每次翻倍，别在"手机真的不在了"时白试太多次。 */
    @Test
    fun `退避按指数增长`() {
        assertEquals(4_000L, autoReconnectDelayMillis(2))
        assertEquals(8_000L, autoReconnectDelayMillis(3))
    }

    /**
     * ⚠️ 退避**必须有上限**。
     *
     * 没有上限时 `attempt = 20` 会算出 `2000 shl 19` ≈ 12 天 —— 一个永远不会触发的
     * 闹钟，等于"悄悄把重连关了"，而且没有任何日志能看出来。
     */
    @Test
    fun `退避封顶在 16 秒`() {
        assertEquals(16_000L, autoReconnectDelayMillis(4))
        assertEquals(16_000L, autoReconnectDelayMillis(5))
        assertEquals(16_000L, autoReconnectDelayMillis(100))
    }

    /**
     * 越界的次数按"第一次"处理，**不抛**。
     *
     * 这是"排闹钟"路径上的数：宁可多等 2 秒，也不能因为一个越界值把整条重连链
     * 断在异常里（那正是"断了不自动连"这个 bug 的复现形态）。
     */
    @Test
    fun `越界次数不抛异常`() {
        assertEquals(2_000L, autoReconnectDelayMillis(0))
        assertEquals(2_000L, autoReconnectDelayMillis(-5))
        assertEquals(2_000L, autoReconnectDelayMillis(Int.MIN_VALUE))
    }

    // ── 该不该重连 ──────────────────────────────────────────────────────────

    /** 上限之内都该重连（0/1/2 = 已经用过 0/1/2 次）。 */
    @Test
    fun `上限之内都该重连`() {
        assertTrue(shouldAutoReconnect(attemptsSoFar = 0, endedByUser = false))
        assertTrue(shouldAutoReconnect(attemptsSoFar = 1, endedByUser = false))
        assertTrue(shouldAutoReconnect(attemptsSoFar = 2, endedByUser = false))
    }

    /** 用满 [AUTO_RECONNECT_MAX_ATTEMPTS] 次就停手，把决定权交回用户。 */
    @Test
    fun `用满次数后停手`() {
        assertFalse(shouldAutoReconnect(attemptsSoFar = 3, endedByUser = false))
        assertFalse(shouldAutoReconnect(attemptsSoFar = 99, endedByUser = false))
    }

    /**
     * ⚠️ **用户明确要求停止 ⇒ 一次都不重连**。
     *
     * `CarPlaySessionBridge.stop()`（用户点的）与 `onSessionEnded()`（远端断的）
     * 走的是**同一个** `SessionEnded` 事件，只能靠发起方留标记来区分。
     * 这条钉的是"标记一旦置位就绝对不连"—— 包括"一次都还没试过"的情况。
     */
    @Test
    fun `用户主动停止后一次都不重连`() {
        assertFalse(shouldAutoReconnect(attemptsSoFar = 0, endedByUser = true))
        assertFalse(shouldAutoReconnect(attemptsSoFar = 2, endedByUser = true))
    }

    // ── 常量本身的量级 ──────────────────────────────────────────────────────

    /**
     * 一轮退避的总耗时必须**短于**"会话算稳定"的时长。
     *
     * 否则会出现荒诞的语义：上一次断线的重连还没排完，新会话已经算"稳定"了。
     * 现在整轮 14 秒 < 稳定阈值 30 秒。
     */
    @Test
    fun `整轮退避短于会话稳定阈值`() {
        val total = (1..AUTO_RECONNECT_MAX_ATTEMPTS).sumOf { autoReconnectDelayMillis(it) }
        assertEquals("2s + 4s + 8s", 14_000L, total)
        assertTrue(
            "整轮 ${total}ms 不该超过稳定阈值 ${AUTO_RECONNECT_STABLE_RESET_MILLIS}ms",
            total < AUTO_RECONNECT_STABLE_RESET_MILLIS,
        )
    }

    /** 次数上限保持在"能救回瞬时抖动、又不会没完没了"的窄区间里。 */
    @Test
    fun `次数上限是一个小数字`() {
        assertEquals(3, AUTO_RECONNECT_MAX_ATTEMPTS)
        assertTrue(
            "上限超过 5 次就不再是「有界」了",
            AUTO_RECONNECT_MAX_ATTEMPTS <= 5,
        )
    }
}
