package com.dbtool.carplay.bridge.bluetooth

/**
 * 特权 adb shell 通道的**最小只读视图**（2026-10-21）。
 *
 * ## 为什么单独抽接口
 *
 * 蓝牙定向断链需要「用已有 ADB 通道起一个 uid 0/2000 的 `app_process`」，
 * 但它不应该拥有 `AdbShellSession` 的全部语义：
 *
 * - **只 `peek()`、绝不 `ensure()`**：`ensure()` 会跑整套「开通车机 ADB →
 *   起 server → 自连 → 提权探测」，等于在会话中途擅自改系统设置；
 * - 不看 owner / 初始化 / 自愈，只关心「此刻有没有一条能跑命令的通道」。
 *
 * 收窄成接口后，单测可以用假实现，不必拖进 Android `Context`。
 */
interface CarPrivilegedShell {

    /** 已缓存通道的快照。 */
    data class Access(
        /** 提权前缀：`""`（shell uid）或 `"su 0 "`（root uid）。 */
        val prefixOrEmpty: String,
        val rooted: Boolean,
    )

    /** 一条命令的执行结果。 */
    data class Result(
        val exitCode: Int,
        val output: String,
    ) {
        val ok: Boolean get() = exitCode == 0
    }

    /** 当前**已经存在**的通道；null = 没有可用 adb shell（不触发获取）。 */
    fun peek(): Access?

    /**
     * 在已缓存的通道上执行命令（调用方自己拼 `prefix`）。
     *
     * 通道不存在时返回 null；命令超时/失败由 [Result.exitCode] 表达。
     */
    fun shell(command: String, timeoutMillis: Long): Result?
}
