package com.dbtool.carplay.bridge.session

import com.dbtool.carplay.api.session.CarReconnectState
import com.dbtool.carplay.api.session.CarReconnectStatus
import com.dbtool.carplay.api.session.CarSessionEndReason

/**
 * 「CarPlay 会话意外结束 ⇒ 自动重连」的判据与退避节奏（PATCH-0095，2026-10-11）。
 *
 * ## 为什么要有它（现场证据）
 *
 * 1.0.51 那份诊断包里，会话在 `17:06:45` 被 iPhone 侧关掉之后**就再也没回来**：
 * `CarPlay 会话已结束` 之后一行动作都没有，直到用户自己发现"投屏断了"、
 * 手动点一次连接。而断连的成因（蓝牙释放未生效、Wi-Fi 抖动、iPhone 切走）
 * 大多**是瞬时的** —— 隔两秒重连就能接上。
 *
 * ## 为什么要"有界"
 *
 * 无界重连在车机上会变成更糟的东西：手机开走了、用户关了 Wi-Fi，
 * 车机会**一直**在后台拉起热点、扫设备、建协议栈，既耗电又刷日志，
 * 用户还以为"这 App 一直在后台偷偷连我的手机"。
 * 所以：**一次断线事件内最多试 [AUTO_RECONNECT_MAX_ATTEMPTS] 次**，
 * 用完就停手，把决定权交回用户（手动点「连接」）。
 *
 * ## 为什么退避要**指数**
 *
 * 断连的成因分两类：瞬时抖动（毫秒级就恢复）与"手机真的不在了"。
 * 固定间隔会在第二种情况下白试很多次；纯固定长间隔又会让第一种情况恢复得太慢。
 * 指数退避（2s → 4s → 8s）两头都照顾：第一次很快，之后不再浪费。
 *
 * ## 为什么要"稳定 N 秒才把计数归零"
 *
 * 只在 `SessionStarted` 时归零是不够的：如果会话"连上 3 秒就掉"，
 * 每次 `SessionStarted` 都把计数清零 ⇒ 退避变成无限循环（永远停在第一次的 2 秒）。
 * 所以归零挂在**稳定性**上：会话连续存活满 [AUTO_RECONNECT_STABLE_RESET_MILLIS]
 * 才认为"这次真的好了"，把计数清零。中途掉线则计数继续累积，很快用满上限停手。
 *
 * ## 为什么是纯函数 + 纯常量
 *
 * 接线点在 `car/app` 的 `CarAppController`，而**那个模块没有 test source set**
 * （它是 application 模块，见 `CarAppController` 里对
 * `CarVideoCanvas.CANVAS_ASPECT_TOLERANCE` 的同款说明）。把判据抽到这里，
 * 就能在 `:car:core-bridge` 里用 JVM 单测钉住"第几次等多久""什么时候该放弃"，
 * 而 `car/app` 那一侧只留"照着算出来的值 `postDelayed`"这一句。
 */

/**
 * 一次断线事件内，自动重连的次数上限。
 *
 * 取 3：配合 [autoReconnectDelayMillis] 的 2s/4s/8s，整轮耗时约 14 秒。
 * 这个量级覆盖得住"Wi-Fi 重连/蓝牙重连"这类瞬时抖动，又短到
 * "手机真的开走了"时不会让用户觉得 App 一直在后台折腾。
 */
const val AUTO_RECONNECT_MAX_ATTEMPTS: Int = 3

/** 第一次重连前等多久；之后每次翻倍（见 [autoReconnectDelayMillis]）。 */
const val AUTO_RECONNECT_BASE_DELAY_MILLIS: Long = 2_000L

/**
 * 会话连续存活满这么久，就认为"这次真的连上了"，把重连计数归零。
 *
 * 取 30 秒：与后台静默更新的"空闲 30 秒"（`SILENT_INSTALL_IDLE_MILLIS`）同一个量级，
 * 都是"人已经不去看它了"的时间尺度；而"连上就掉"的坏会话基本活不过 30 秒。
 */
const val AUTO_RECONNECT_STABLE_RESET_MILLIS: Long = 30_000L

/** 退避的翻倍次数上限（`2s shl 3 = 16s`）。防止调用方传进来一个很大的 attempt 把间隔算爆。 */
private const val AUTO_RECONNECT_MAX_SHIFT = 3

/**
 * 第 [attempt] 次重连（**1 起**）应该等多久（毫秒）。
 *
 * - `attempt = 1` → 2s，`2` → 4s，`3` → 8s，`4+` → 16s（封顶）。
 * - `attempt <= 0` 按 1 处理（不抛：这是"排闹钟"路径上的数，宁可多等 2 秒
 *   也不能因为一个越界值把重连整条链断掉）。
 */
fun autoReconnectDelayMillis(attempt: Int): Long {
    val normalized = attempt.coerceAtLeast(1)
    val shift = (normalized - 1).coerceAtMost(AUTO_RECONNECT_MAX_SHIFT)
    return AUTO_RECONNECT_BASE_DELAY_MILLIS shl shift
}

/**
 * 这次 `SessionEnded` 该不该排自动重连。
 *
 * @param attemptsSoFar 本次断线事件内**已经**用掉的次数。
 * @param endedByUser 这次结束是不是用户明确要求的（`CarUiActions.stop()`）。
 *   ⚠️ 用户点了停止就**绝不自作主张连回去** —— 那是"App 不听使唤"，
 *   比"断了不自动连"更让人恼火。
 */
fun shouldAutoReconnect(attemptsSoFar: Int, endedByUser: Boolean): Boolean =
    !endedByUser && attemptsSoFar < AUTO_RECONNECT_MAX_ATTEMPTS

/**
 * 这次 `SessionEnded` 该不该排自动重连（按**来源**判断）。
 *
 * 只有 [CarSessionEndReason.REMOTE] 是真正的意外断开：
 * - `USER_STOP` / `POWER_PAUSE`：用户或车机明确要求停，绝不能自作主张连回去；
 * - `RECONNECT`：这次结束本身就是某次重连拆旧会话产生的，再排一次会无限套娃。
 *
 * 这是 [shouldAutoReconnect] 的来源版重载；上限仍复用
 * [AUTO_RECONNECT_MAX_ATTEMPTS]，调用方不得另写一套判断。
 */
fun shouldAutoReconnect(
    attemptsSoFar: Int,
    reason: CarSessionEndReason,
): Boolean = reason == CarSessionEndReason.REMOTE &&
    attemptsSoFar < AUTO_RECONNECT_MAX_ATTEMPTS

/**
 * 本次断线事件是否已经**用完**自动重连额度、应当落 `EXHAUSTED`。
 *
 * ⚠️ 用户主动停止（[endedByUser]）**绝不算耗尽**：否则用户点一次停止，
 * 首页会显示「自动重连已停止」，把一次正常停止说成故障。
 * 非 `REMOTE` 来源同理 —— 它们本来就不进入重连链。
 *
 * 判据用 `>=`：读的是“**已经**用掉几次”，上限是“**允许**几次”；
 * 已用 3 次、允许 3 次 ⇒ 停手（见 [AUTO_RECONNECT_MAX_ATTEMPTS]）。
 */
fun shouldExhaustAutoReconnect(
    attemptsSoFar: Int,
    reason: CarSessionEndReason,
    endedByUser: Boolean,
): Boolean = !endedByUser &&
    reason == CarSessionEndReason.REMOTE &&
    attemptsSoFar >= AUTO_RECONNECT_MAX_ATTEMPTS

/**
 * 自动重连耗尽时的正交状态。
 *
 * `state = EXHAUSTED` 会由实现方映射成 `phase = FAILED`（见
 * `CarReconnectState.appliedPhase()`），这样 `CarSilentUpdatePolicy` 的
 * `allowsSilentInstall()` 才会重新返回 `true`。
 *
 * @param lastError 最后一次失败原因；为空时由桥接层兜底成“自动重连失败”。
 */
fun exhaustedReconnectStatus(lastError: String?): CarReconnectStatus =
    CarReconnectStatus(
        state = CarReconnectState.EXHAUSTED,
        attempt = AUTO_RECONNECT_MAX_ATTEMPTS,
        maxAttempts = AUTO_RECONNECT_MAX_ATTEMPTS,
        delayMillis = 0L,
        lastError = lastError,
    )
