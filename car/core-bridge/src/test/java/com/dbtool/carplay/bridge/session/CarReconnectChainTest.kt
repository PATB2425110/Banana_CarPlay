package com.dbtool.carplay.bridge.session

import com.dbtool.carplay.api.session.CarReconnectState
import com.dbtool.carplay.api.session.CarSessionEndReason
import com.dbtool.carplay.api.session.CarSessionPhase
import com.dbtool.carplay.api.session.appliedPhase
import com.dbtool.carplay.bridge.update.allowsSilentInstall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「重连链修复」的纯 JVM 判据（2026-10-20）。
 *
 * 这里钉的是**正确性**，不是接线（接线由 `CarReconnectChainWiringTest` 读源码）：
 *
 * 1. 只有 `REMOTE` 能排自动重连 —— 用户停止 / 熄火暂停 / 故意重建都不行；
 * 2. 退避仍是 2s / 4s / 8s，一轮最多 3 次；
 * 3. 用满 3 次后必须落 `EXHAUSTED`，且映射到 `phase = FAILED`；
 * 4. 用户主动停止**绝不**进入 `EXHAUSTED`；
 * 5. 耗尽后 `allowsSilentInstall()` 必须恢复 `true`（静默更新不被永久阻断）。
 *
 * 全部是纯函数，不依赖 Android 运行时。
 */
class CarReconnectChainTest {

    // ── 1. 来源决定是否排期 ────────────────────────────────────────────────

    @Test
    fun `只有 REMOTE 会排自动重连`() {
        CarSessionEndReason.entries.forEach { reason ->
            assertEquals(
                "reason=$reason 的排期判断错了",
                reason == CarSessionEndReason.REMOTE,
                shouldAutoReconnect(attemptsSoFar = 0, reason = reason),
            )
        }
    }

    /**
     * 给一串 `SessionEnded(reason)` 序列，逐条断言“排不排”。
     *
     * 这是控制器事件分支的输入侧：真实事件流里四种来源会交错出现，
     * 只有远端断开那几条能触发重连。
     */
    @Test
    fun `SessionEnded 序列按来源决定是否排期`() {
        val sequence = listOf(
            CarSessionEndReason.REMOTE, // iPhone 拔线
            CarSessionEndReason.USER_STOP, // 用户点停止
            CarSessionEndReason.REMOTE, // 又一次远端断开
            CarSessionEndReason.POWER_PAUSE, // 熄火自动暂停
            CarSessionEndReason.RECONNECT, // 自动重连拆旧会话
            CarSessionEndReason.REMOTE,
        )
        val scheduled = sequence.map { shouldAutoReconnect(0, it) }
        assertEquals(
            listOf(true, false, true, false, false, true),
            scheduled,
        )
    }

    @Test
    fun `停止与重建即使一次都没试过也不排期`() {
        for (reason in listOf(
            CarSessionEndReason.USER_STOP,
            CarSessionEndReason.POWER_PAUSE,
            CarSessionEndReason.RECONNECT,
        )) {
            for (attempts in 0..AUTO_RECONNECT_MAX_ATTEMPTS) {
                assertFalse(
                    "attempts=$attempts reason=$reason 不该排自动重连",
                    shouldAutoReconnect(attempts, reason),
                )
            }
        }
    }

    // ── 2. 退避与上限 ──────────────────────────────────────────────────────

    @Test
    fun `退避序列是 2 4 8 秒且上限 3 次`() {
        assertEquals(3, AUTO_RECONNECT_MAX_ATTEMPTS)
        assertEquals(2_000L, autoReconnectDelayMillis(1))
        assertEquals(4_000L, autoReconnectDelayMillis(2))
        assertEquals(8_000L, autoReconnectDelayMillis(3))

        // 模拟一轮断线事件：只要策略说“还能试”，就消耗一次并按退避排期。
        var attempts = 0
        val delays = mutableListOf<Long>()
        while (shouldAutoReconnect(attempts, CarSessionEndReason.REMOTE)) {
            attempts++
            delays += autoReconnectDelayMillis(attempts)
        }
        assertEquals(listOf(2_000L, 4_000L, 8_000L), delays)
        assertEquals(AUTO_RECONNECT_MAX_ATTEMPTS, attempts)
    }

    // ── 3. 耗尽必须落 FAILED ────────────────────────────────────────────────

    @Test
    fun `耗尽落 EXHAUSTED 且 phase 是 FAILED`() {
        val status = exhaustedReconnectStatus(lastError = "蓝牙适配器正在恢复")
        assertEquals(CarReconnectState.EXHAUSTED, status.state)
        assertEquals(AUTO_RECONNECT_MAX_ATTEMPTS, status.attempt)
        assertEquals(AUTO_RECONNECT_MAX_ATTEMPTS, status.maxAttempts)
        assertEquals(0L, status.delayMillis)
        assertEquals("蓝牙适配器正在恢复", status.lastError)

        assertEquals(
            "EXHAUSTED 必须落 FAILED（RECONNECTING 会永久阻断静默更新）",
            CarSessionPhase.FAILED,
            status.state.appliedPhase(),
        )
    }

    @Test
    fun `排期与进行中仍映射到 RECONNECTING`() {
        assertEquals(
            CarSessionPhase.RECONNECTING,
            CarReconnectState.SCHEDULED.appliedPhase(),
        )
        assertEquals(
            CarSessionPhase.RECONNECTING,
            CarReconnectState.IN_PROGRESS.appliedPhase(),
        )
        assertNull(
            "NONE 不允许改动连接阶段",
            CarReconnectState.NONE.appliedPhase(),
        )
    }

    // ── 4. 用户停止不算耗尽 ────────────────────────────────────────────────

    @Test
    fun `用户主动停止不会进入 EXHAUSTED`() {
        // 用户点停止（endedByUser=true）—— 哪怕次数早已用满，也不算耗尽。
        assertFalse(
            shouldExhaustAutoReconnect(
                attemptsSoFar = AUTO_RECONNECT_MAX_ATTEMPTS,
                reason = CarSessionEndReason.REMOTE,
                endedByUser = true,
            ),
        )
        // 非 REMOTE 来源本来就不在重连链上。
        for (reason in listOf(
            CarSessionEndReason.USER_STOP,
            CarSessionEndReason.POWER_PAUSE,
            CarSessionEndReason.RECONNECT,
        )) {
            assertFalse(
                "$reason 不该被判成耗尽",
                shouldExhaustAutoReconnect(
                    attemptsSoFar = AUTO_RECONNECT_MAX_ATTEMPTS,
                    reason = reason,
                    endedByUser = false,
                ),
            )
        }
        // 远程断开但次数还没用满：也不能提前耗尽。
        assertFalse(
            shouldExhaustAutoReconnect(
                attemptsSoFar = AUTO_RECONNECT_MAX_ATTEMPTS - 1,
                reason = CarSessionEndReason.REMOTE,
                endedByUser = false,
            ),
        )
    }

    @Test
    fun `远端断线用满次数才算耗尽`() {
        assertTrue(
            shouldExhaustAutoReconnect(
                attemptsSoFar = AUTO_RECONNECT_MAX_ATTEMPTS,
                reason = CarSessionEndReason.REMOTE,
                endedByUser = false,
            ),
        )
        assertTrue(
            "比上限还多（异常累积）同样要停手",
            shouldExhaustAutoReconnect(
                attemptsSoFar = AUTO_RECONNECT_MAX_ATTEMPTS + 5,
                reason = CarSessionEndReason.REMOTE,
                endedByUser = false,
            ),
        )
    }

    // ── 5. 静默更新恢复（R1 的核心证明）─────────────────────────────────────

    @Test
    fun `耗尽后静默安装恢复`() {
        val exhaustedPhase = exhaustedReconnectStatus(null).state.appliedPhase()
        assertEquals(CarSessionPhase.FAILED, exhaustedPhase)
        assertTrue(
            "FAILED 必须允许静默安装，否则后台更新会被永久阻断",
            exhaustedPhase!!.allowsSilentInstall(),
        )
        assertFalse(
            "对照：停在 RECONNECTING 会永久阻断静默更新",
            CarSessionPhase.RECONNECTING.allowsSilentInstall(),
        )
    }

    @Test
    fun `默认重连状态不改变任何阶段`() {
        val default = com.dbtool.carplay.api.session.CarReconnectStatus()
        assertEquals(CarReconnectState.NONE, default.state)
        assertEquals(0, default.attempt)
        assertEquals(0, default.maxAttempts)
        assertEquals(0L, default.delayMillis)
        assertNull(default.lastError)
        assertNull(default.state.appliedPhase())
    }
}
