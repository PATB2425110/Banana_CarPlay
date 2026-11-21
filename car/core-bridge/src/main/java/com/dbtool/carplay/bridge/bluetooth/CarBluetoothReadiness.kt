package com.dbtool.carplay.bridge.bluetooth

/**
 * 无线 CarPlay 起栈前的「蓝牙就绪」等待。
 *
 * ## 为什么需要
 *
 * 上游 wireless bring-up 的第一步是**从已配对设备里找 PHONE_SMART**（iPhone）。
 * 若此刻蓝牙适配器刚被 `enable()`（`STATE_ON` 已到）但**配对缓存还没加载**，
 * native 会直接抛：
 *
 * ```
 * java.io.IOException: No bonded PHONE_SMART devices found; pair an iPhone and retry
 * ```
 *
 * 现场表现就是：**第一次连不上，退出 App 重进就能连上** ——
 * 因为重进时适配器与配对表都已经就绪。2026-10-20 1.1.32 日志实证：
 * `09:00:13 No bonded PHONE_SMART devices found` → 重启后 `09:04:56` 一次成功。
 *
 * ## 语义
 *
 * **只等，不拦**：超时也照常继续起栈（native 会给出真实错误），
 * 目的是把「适配器 on 但配对表为空」这一瞬间的空窗吃掉，而不是新增一个失败分支。
 */
object CarBluetoothReadiness {

    /** 轮询结果，供日志与单测断言。 */
    data class Result(
        val adapterOn: Boolean,
        val bondedCount: Int,
        val waitedMs: Long,
        val timedOut: Boolean,
    ) {
        val ready: Boolean get() = adapterOn && bondedCount > 0
    }

    /**
     * 轮询等待「适配器开 + 至少有 1 个已配对设备」。
     *
     * @param timeoutMs 最长等待；<=0 表示只探一次
     * @param pollMs 轮询间隔（最小 50ms，防止忙等）
     * @param nowMs 时间源（测试注入）
     * @param sleepMs 休眠（测试注入，避免真睡眠）
     * @param adapterOn 适配器是否已开
     * @param bondedCount 当前已配对设备数
     */
    fun awaitBondedPhone(
        timeoutMs: Long = 6_000L,
        pollMs: Long = 300L,
        nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
        sleepMs: (Long) -> Unit = { Thread.sleep(it) },
        adapterOn: () -> Boolean,
        bondedCount: () -> Int,
    ): Result {
        val started = nowMs()
        val interval = pollMs.coerceAtLeast(50L)
        var waited = 0L
        while (true) {
            val on = runCatching { adapterOn() }.getOrDefault(false)
            val bonded = runCatching { bondedCount() }.getOrDefault(0).coerceAtLeast(0)
            if (on && bonded > 0) {
                return Result(on, bonded, waited, timedOut = false)
            }
            if (waited >= timeoutMs || timeoutMs <= 0L) {
                return Result(on, bonded, waited, timedOut = true)
            }
            runCatching { sleepMs(interval) }
            waited = nowMs() - started
        }
    }
}
