package com.dbtool.carplay.bridge

import com.dbtool.carplay.api.session.CarSessionPhase
import com.dbtool.carplay.bridge.update.SILENT_INSTALL_IDLE_MILLIS
import com.dbtool.carplay.bridge.update.SILENT_MAX_ATTEMPTS
import com.dbtool.carplay.bridge.update.advanceIdleClock
import com.dbtool.carplay.bridge.update.allowsSilentInstall
import com.dbtool.carplay.bridge.update.idleWindowSettled
import com.dbtool.carplay.bridge.update.nextSilentAttempt
import com.dbtool.carplay.bridge.update.silentAttemptLimitReached
import com.dbtool.carplay.bridge.update.silentAttemptsFor
import com.dbtool.carplay.bridge.update.silentBlocked
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 后台静默更新的判据（`CarSilentUpdatePolicy`）。
 *
 * ## 为什么这些判据值得单独测
 *
 * 静默更新是这个工程里**唯一一个"能让 App 把自己装死"**的功能：
 * 装的是它自己，装失败 → 进程被杀 → 重启 → 又检测到新版 → 又装。
 * 三组判据各自守一条命门：
 *
 * | 组 | 守什么 | 判错一次的后果 |
 * | --- | --- | --- |
 * | 空闲 | 用户没在用车 | 连手机时重启 ⇒ 投屏被打断 |
 * | 稳定窗口 | 空闲**连续**成立 | 踩在"刚拔又插"的缝里 ⇒ 同样打断 |
 * | 熔断 | 坏包不会无限重试 | 车队反复自杀重启，用户进不去设置页 |
 *
 * ⚠️ 这三组都是**纯函数**（时间由参数传入），所以边界（29s / 30s、
 * 第 2 次 / 第 3 次）可以在 JVM 里精确钉死 —— 真机上根本没法复现
 * "恰好 29.9 秒"这种时序。
 */
class CarSilentUpdatePolicyTest {

    // ------------------------------------------------------------ 空闲判据

    @Test
    fun idlePhasesAllowSilentInstall() {
        // 没有任何会话在跑 ⇒ 装自己不会打断任何东西。
        assertTrue(CarSessionPhase.IDLE.allowsSilentInstall())
        assertTrue(CarSessionPhase.STOPPED.allowsSilentInstall())
        assertTrue(CarSessionPhase.FAILED.allowsSilentInstall())
    }

    @Test
    fun busyPhasesRejectSilentInstall() {
        // 每一个都意味着"用户正在用车"：重启 App 会打断投屏/握手/重连。
        val busy = listOf(
            CarSessionPhase.PREPARING_MFI,
            CarSessionPhase.WAITING_FOR_MFI,
            CarSessionPhase.WAITING_FOR_DEVICE,
            CarSessionPhase.CONNECTING,
            CarSessionPhase.ACTIVE,
            CarSessionPhase.RECONNECTING,
        )
        for (phase in busy) {
            assertFalse("$phase 阶段不得允许静默安装", phase.allowsSilentInstall())
        }
    }

    @Test
    fun everyPhaseIsCoveredExactlyOnce() {
        // 穷尽性守门：枚举新增取值时 `when` 会编译失败，但**这里**要防止
        // 有人图省事给 `when` 加 `else` —— 那样新增阶段会静默落到某个分支。
        val allowed = CarSessionPhase.entries.filter { it.allowsSilentInstall() }
        val rejected = CarSessionPhase.entries.filterNot { it.allowsSilentInstall() }
        assertEquals("允许/拒绝必须覆盖全部阶段", CarSessionPhase.entries.size, allowed.size + rejected.size)
        assertEquals("允许静默的阶段只有 IDLE/STOPPED/FAILED", 3, allowed.size)
    }

    // -------------------------------------------------------- 稳定窗口计时

    @Test
    fun idleClockStartsWhenEnteringIdle() {
        // 刚转入空闲：从现在开始计时。
        assertEquals(1_000L, advanceIdleClock(CarSessionPhase.IDLE, 0L, 1_000L))
    }

    @Test
    fun idleClockKeepsOriginalStart() {
        // 已经在计时：必须保留原起点，不能刷新成 now（否则永远凑不满窗口）。
        assertEquals(1_000L, advanceIdleClock(CarSessionPhase.IDLE, 1_000L, 9_000L))
    }

    @Test
    fun idleClockResetsOnBusyPhase() {
        assertEquals(0L, advanceIdleClock(CarSessionPhase.ACTIVE, 1_000L, 2_000L))
        assertEquals(0L, advanceIdleClock(CarSessionPhase.CONNECTING, 5_000L, 6_000L))
    }

    @Test
    fun windowNotSettledBeforeThirtySeconds() {
        // 29 秒：还差一点，不许动手。
        assertFalse(idleWindowSettled(1_000L, 1_000L + SILENT_INSTALL_IDLE_MILLIS - 1))
    }

    @Test
    fun windowSettledAtExactlyThirtySeconds() {
        // 恰好 30 秒：边界取 `>=`，应当允许。
        assertTrue(idleWindowSettled(1_000L, 1_000L + SILENT_INSTALL_IDLE_MILLIS))
    }

    @Test
    fun windowNotSettledWhenNeverIdle() {
        // 起点为 0 = 当前不空闲，无论时间怎么走都不能算"稳定"。
        assertFalse(idleWindowSettled(0L, 999_999_999L))
    }

    @Test
    fun windowRestartsAfterInterruption() {
        // "刚拔线又插线"：空闲 25 秒后转 ACTIVE ⇒ 起点清零 ⇒ 重新计时。
        val started = advanceIdleClock(CarSessionPhase.IDLE, 0L, 1_000L)
        val after25s = advanceIdleClock(CarSessionPhase.IDLE, started, 26_000L)
        assertFalse("25 秒时还不该动手", idleWindowSettled(after25s, 26_000L))

        val interrupted = advanceIdleClock(CarSessionPhase.ACTIVE, after25s, 27_000L)
        assertEquals("转 ACTIVE 必须清零", 0L, interrupted)

        val restarted = advanceIdleClock(CarSessionPhase.IDLE, interrupted, 30_000L)
        assertFalse(
            "重新计时后 29 秒仍不该动手（不能沿用上一轮的累计）",
            idleWindowSettled(restarted, 30_000L + SILENT_INSTALL_IDLE_MILLIS - 1),
        )
        assertTrue(idleWindowSettled(restarted, 30_000L + SILENT_INSTALL_IDLE_MILLIS))
    }

    // ------------------------------------------------------------ 熔断计数

    @Test
    fun attemptCountAccumulatesForSameVersion() {
        val first = nextSilentAttempt(previousVersionCode = 0L, previousCount = 0, versionCode = 200L)
        assertEquals(200L, first.versionCode)
        assertEquals(1, first.count)

        val second = nextSilentAttempt(
            previousVersionCode = first.versionCode,
            previousCount = first.count,
            versionCode = 200L,
        )
        assertEquals(2, second.count)
    }

    @Test
    fun attemptCountResetsForNewVersion() {
        // 服务端发来更高的 versionCode ⇒ 天然是新的熔断键 ⇒ 从 1 重新开始。
        val fresh = nextSilentAttempt(previousVersionCode = 200L, previousCount = 2, versionCode = 201L)
        assertEquals(201L, fresh.versionCode)
        assertEquals(1, fresh.count)
    }

    @Test
    fun attemptsForOtherVersionReadAsZero() {
        assertEquals(0, silentAttemptsFor(201L, attemptVersionCode = 200L, attemptCount = 2))
        assertEquals(2, silentAttemptsFor(200L, attemptVersionCode = 200L, attemptCount = 2))
    }

    @Test
    fun limitReachedFollowsTheTimingTable() {
        // ⚠️ 这是本文件最要紧的一条断言：`SILENT_MAX_ATTEMPTS` 是"**允许**几次"，
        // 判据是 `已试次数 >= 允许次数`。写成 `>` 就会多装一次（坏包多自杀一轮）。
        assertEquals(2, SILENT_MAX_ATTEMPTS)

        // 第 1 次发起前：已试 0 次 ⇒ 允许。
        assertFalse(silentAttemptLimitReached(200L, attemptVersionCode = 0L, attemptCount = 0))
        // 第 2 次发起前：已试 1 次 ⇒ 仍允许（允许 2 次）。
        assertFalse(silentAttemptLimitReached(200L, attemptVersionCode = 200L, attemptCount = 1))
        // 第 3 次发起前：已试 2 次 ⇒ 用完 ⇒ 熔断。
        assertTrue(silentAttemptLimitReached(200L, attemptVersionCode = 200L, attemptCount = 2))
        assertTrue(silentAttemptLimitReached(200L, attemptVersionCode = 200L, attemptCount = 9))
    }

    @Test
    fun limitNotReachedForDifferentVersion() {
        // 计数是**按版本**的：换版本号后，上一版的失败次数不算在新版头上。
        assertFalse(silentAttemptLimitReached(201L, attemptVersionCode = 200L, attemptCount = 5))
    }

    // ------------------------------------------------------------ 熔断标记

    @Test
    fun blockedOnlyMatchesSameVersion() {
        assertTrue(silentBlocked(200L, blockedVersionCode = 200L))
        assertFalse(silentBlocked(201L, blockedVersionCode = 200L))
    }

    @Test
    fun nothingBlockedWhenMarkerIsZero() {
        // 0 = 没有熔断。不能让"版本号恰好是 0"的情况被误判成熔断。
        assertFalse(silentBlocked(200L, blockedVersionCode = 0L))
        assertFalse(silentBlocked(0L, blockedVersionCode = 0L))
    }
}
