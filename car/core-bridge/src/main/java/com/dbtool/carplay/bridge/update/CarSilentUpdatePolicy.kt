package com.dbtool.carplay.bridge.update

import com.dbtool.carplay.api.session.CarSessionPhase

/**
 * 后台静默更新的**全部判据**（纯函数，无 Android 依赖）。
 *
 * ## 为什么单独立一个文件
 *
 * 静默更新是这个工程里**唯一一个"能让 App 把自己装死"**的功能：
 * 装的是它自己，装失败 → 进程被杀 → 重启 → 又检测到有新版 → 又装。
 * 因此"什么时候可以动手""什么时候必须停手"这两件事必须**可穷举、可单测**，
 * 而不是散在 `CarUpdateManager` 的 `if` 里靠读代码确认。
 *
 * 本文件里的每个函数都是**纯函数**：给定输入必然得到同一输出，不读时钟、
 * 不碰磁盘。时间由调用方传 `nowMillis` 进来 —— 这样"空闲 29 秒不触发、
 * 30 秒触发"这类边界才测得动。
 *
 * 判据一共三组：
 *
 * | 组 | 函数 | 防的是什么 |
 * | --- | --- | --- |
 * | 空闲 | [allowsSilentInstall] / [advanceIdleClock] / [idleWindowSettled] | 用户在用车时被重启（打断投屏） |
 * | 熔断 | [nextSilentAttempt] / [silentBlocked] | 坏包导致无限"装→重启→再装" |
 * | —— | [SilentAttempt] | 上面两者的中间结果 |
 */

/**
 * 空闲必须**连续**保持多久，才允许动手安装。
 *
 * 取 30 秒：`WAITING_FOR_DEVICE → ACTIVE` 之间可能只差几百毫秒
 * （用户刚拔线又插线），只判"当前空闲"会踩在阶段切换的缝里 ——
 * 那一刻装自己就会打断刚起来的会话。
 *
 * 没连手机时杀掉 App 重启，用户几乎无感；这是收益/代价最平衡的点。
 */
const val SILENT_INSTALL_IDLE_MILLIS: Long = 30_000L

/**
 * 同一个版本连续尝试几次仍未成功，就熔断。
 *
 * 取 2：第一次失败可能是偶发（adb 通道没起来、包还没落完盘），
 * 值得再试一次；第二次还失败就说明"这个包在**这台机器上**装不了"，
 * 再试一万次也一样，而每试一次都会把 App 重启一遍。
 *
 * ⚠️ 语义是"**允许**尝试的次数"，不是"熔断阈值"：计数达到本值后**不再发起**
 * （见 [silentAttemptLimitReached]）。差一个 `>=` 就会变成"少装一次"。
 */
const val SILENT_MAX_ATTEMPTS: Int = 2

/**
 * 这个会话阶段下，允许后台静默安装吗？
 *
 * 判据是"装自己会不会打断用户"。装自己必然杀进程重建，
 * 所以只有**没有任何会话在跑**时才允许。
 *
 * ⚠️ 穷尽式 `when` 不写 `else`：`CarSessionPhase` 新增取值时编译失败，
 * 强制在这里显式决策（新增阶段默认必须被视为"用户在用"）。
 */
fun CarSessionPhase.allowsSilentInstall(): Boolean = when (this) {
    // 没有会话在跑 ⇒ 装自己不会打断任何东西。
    CarSessionPhase.IDLE,
    CarSessionPhase.STOPPED,
    CarSessionPhase.FAILED,
    -> true

    // 下面这些阶段都意味着"用户正在用车"：
    // 重启 App 会打断投屏、握手，或让刚插上的 iPhone 掉线重来。
    CarSessionPhase.PREPARING_MFI,
    CarSessionPhase.WAITING_FOR_MFI,
    CarSessionPhase.WAITING_FOR_DEVICE,
    CarSessionPhase.CONNECTING,
    CarSessionPhase.ACTIVE,
    CarSessionPhase.RECONNECTING,
    -> false
}

/**
 * 推进"空闲起点"时钟，返回新的起点（0 = 当前不空闲）。
 *
 * 语义是"**连续**空闲"：一旦转入非空闲阶段就清零，
 * 下次再空闲要**重新**计满 [SILENT_INSTALL_IDLE_MILLIS]。
 *
 * @param idleSinceMillis 上一次算出的起点；0 表示上一刻并不空闲。
 * @param nowMillis 当前时间（由调用方给，便于测试）。
 */
fun advanceIdleClock(
    phase: CarSessionPhase,
    idleSinceMillis: Long,
    nowMillis: Long,
): Long = when {
    // 不空闲：清零，下次从新起点重算。
    !phase.allowsSilentInstall() -> 0L
    // 已经在计时：保留原起点，别把它刷新成 now（否则永远凑不满窗口）。
    idleSinceMillis > 0L -> idleSinceMillis
    // 刚转入空闲：从现在开始计时。
    else -> nowMillis
}

/**
 * 空闲窗口是否已经稳定（连续空闲 ≥ [SILENT_INSTALL_IDLE_MILLIS]）。
 *
 * @param idleSinceMillis [advanceIdleClock] 算出的起点；0 = 不空闲。
 */
fun idleWindowSettled(idleSinceMillis: Long, nowMillis: Long): Boolean =
    idleSinceMillis > 0L && nowMillis - idleSinceMillis >= SILENT_INSTALL_IDLE_MILLIS

/**
 * 一次静默尝试的计数推进结果。
 *
 * @param versionCode 这次尝试的是哪个版本。
 * @param count 该版本**连续**尝试了几次（含本次）。
 */
data class SilentAttempt(val versionCode: Long, val count: Int)

/**
 * 推进"同一版本连续尝试"的计数。
 *
 * - 还是同一个版本 ⇒ 累加；
 * - **换版本号 ⇒ 从 1 重新开始**（新版本天然是新的熔断键，
 *   服务端发个更高的 `versionCode` 就等于自动解除熔断）。
 *
 * ⚠️ 这个函数的结果**必须在发起安装之前落盘**（见 `CarUpdatePrefs.recordSilentAttempt`）：
 * 装自己会把进程杀掉，写在"成功之后"的代码可能永远执行不到。
 */
fun nextSilentAttempt(
    previousVersionCode: Long,
    previousCount: Int,
    versionCode: Long,
): SilentAttempt = if (previousVersionCode == versionCode && previousCount > 0) {
    SilentAttempt(versionCode = versionCode, count = previousCount + 1)
} else {
    SilentAttempt(versionCode = versionCode, count = 1)
}

/**
 * 该版本**已经**自动尝试过几次（不是同一个版本则为 0）。
 *
 * 单独抽出来是因为"读计数"与"推进计数"发生在**两个不同的时刻**：
 * 判断"还能不能再试"发生在**发起之前**（读），落盘发生在**发起之时**（写）。
 */
fun silentAttemptsFor(
    versionCode: Long,
    attemptVersionCode: Long,
    attemptCount: Int,
): Int = if (attemptVersionCode == versionCode) attemptCount else 0

/**
 * 该版本是否**已经用完**允许的自动尝试次数（调用方据此拒绝再发起）。
 *
 * ⚠️ 判据是 `>=` 而不是 `>`：读的是"**已经**尝试过几次"，
 * 而 [SILENT_MAX_ATTEMPTS] 是"**允许**几次"。已试 2 次、允许 2 次 ⇒ 停手。
 * 完整时序（MAX = 2）：
 *
 * | 时刻 | 已试次数 | 判定 | 动作 |
 * | --- | --- | --- | --- |
 * | 第 1 次发起前 | 0 | 0 >= 2 假 | 落盘为 1，发起 |
 * | 第 2 次发起前 | 1 | 1 >= 2 假 | 落盘为 2，发起 |
 * | 第 3 次发起前 | 2 | 2 >= 2 **真** | **熔断**，不发起 |
 *
 * 即"连续失败 2 次 ⇒ 停手"，与需求一致。
 */
fun silentAttemptLimitReached(
    versionCode: Long,
    attemptVersionCode: Long,
    attemptCount: Int,
): Boolean = silentAttemptsFor(versionCode, attemptVersionCode, attemptCount) >= SILENT_MAX_ATTEMPTS

/**
 * 该版本的静默是否已被**熔断**（永久关闭）。
 *
 * 熔断只由三件事解除：① 用户手动点「立即安装」（手动路径不受熔断限制）；
 * ② 服务端发来更高的 `versionCode`；③ 用户在「关于」页关掉静默开关后再打开。
 */
fun silentBlocked(versionCode: Long, blockedVersionCode: Long): Boolean =
    versionCode > 0L && versionCode == blockedVersionCode
