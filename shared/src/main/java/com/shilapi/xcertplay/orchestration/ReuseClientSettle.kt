package com.shilapi.xcertplay.orchestration

/**
 * 复用原厂热点时，「等客户端网络稳定再进 iAP2」的动态等待（2026-10-24）。
 *
 * ## 为什么要有这个类
 *
 * 2026-10-20 真车实测（VIN 381425，同一台车、同一 SSID）：
 *
 * | 场景 | 客户端出现 → 0x5703 | 结果 |
 * |---|---|---|
 * | 成功 | 09:07:14.280 → 09:07:24.837（**+10.6s**）| AirPlay 正常 |
 * | 失败 | 22:04:02.411 → 22:04:03.269（**+0.86s**）| 40s attach 超时 |
 *
 * 结论：iPhone 刚关联、DHCP 还没走完就进 iAP2，它收下 0x5703 却不发起 AirPlay。
 *
 * ## 旧实现的问题
 *
 * 固定 `Thread.sleep(8_000L)`。四轮冷启动实测（`connect-stream*.log`）：
 * 总耗时 11.6~12.3s，**8s 占 69%**，而 iPhone 实际 0.17s 就连上了 —— 纯白等。
 *
 * 2026-10-21 加过「客户端数 >0 → 2s」的快路径，但三轮都误入 8s 分支
 * （`hotspotClientCountProvider` 返回 0 或 null）。
 *
 * ## 本实现的三条约束（都是坑）
 *
 * 1. **2s 必须从「首次检测到客户端」起算**，不是从开始睡眠起算。
 *    若客户端在第 1.9s 才出现却只给 0.1s，比已知失败样本（0.86s）还短。
 * 2. **硬上限 10s**（8+2），保证最坏情况不比原来差太多。
 * 3. 轮询间隔 500ms 只是「尽快捡到下一份快照」。真正的采样粒度由
 *    `CarHotspotClientWatcher` 决定（约 5s），本类不承诺 500ms 精度。
 *
 * 纯函数式（注入 `poll` / `sleep` / `now`），JVM 可测，不碰 Android。
 */
internal object ReuseClientSettle {

    /** 轮询间隔。 */
    const val POLL_MILLIS: Long = 500L

    /**
     * 首次检测到客户端后，**还必须再等**的时长。
     *
     * 取 2s 与旧的 `REUSE_CLIENT_SETTLE_CLIENT_PRESENT_MILLIS` 一致：
     * 失败样本是 0.86s 就进 iAP2，2s 能覆盖 DHCP 完成。
     */
    const val MIN_AFTER_CLIENT_MILLIS: Long = 2_000L

    /**
     * 总等待硬上限。
     *
     * **找客户端的上限**。客户端一直不出现 → 等满 10s 后进入 iAP2
     * （旧行为是 8s，这里放宽 2s 以提高命中率，仍远小于 40s attach watchdog）。
     * 若客户端在接近 10s 时才出现，总等待会略超 10s（最多约 12s）——
     * 这是刻意的：宁可多等，也不能只给它 0.1s（已知 0.86s 就会失败）。
     */
    const val HARD_CAP_MILLIS: Long = 10_000L

    // Cold clients may need the credentials sent by iAP2 before they can join.
    // Do not spend ten seconds waiting for that association before sending them.
    const val BOOTSTRAP_CLIENT_SEARCH_MILLIS: Long = 2_000L

    fun bootstrapSearchBudget(selfHosted: Boolean): Long =
        if (selfHosted) 0L else BOOTSTRAP_CLIENT_SEARCH_MILLIS

    /**
     * 计算并执行动态等待。
     *
     * @param initialClientCount 进入时的客户端数；`null` = 读不到
     * @param poll 复检客户端数；`null` = 读不到
     * @param sleep 睡眠（测试注入假实现）
     * @param now 单调时钟（毫秒）
     * @return 实际等待的总毫秒数（仅用于日志）
     */
    fun waitForSettle(
        initialClientCount: Int?,
        poll: () -> Int?,
        sleep: (Long) -> Unit,
        now: () -> Long = { System.nanoTime() / 1_000_000 },
        clientSearchMillis: Long = HARD_CAP_MILLIS,
    ): Long {
        require(clientSearchMillis in 0L..HARD_CAP_MILLIS)
        if (clientSearchMillis == 0L) return 0L
        val start = now()

        // 快路径：一进来就已经有客户端在网上 —— 只需等 DHCP 稳定。
        if (initialClientCount != null && initialClientCount > 0) {
            sleep(MIN_AFTER_CLIENT_MILLIS)
            return now() - start
        }

        var firstSeenAt: Long? = null
        while (true) {
            val elapsed = now() - start
            val seen = firstSeenAt

            // 截止时刻：
            //   · 还没见到客户端 → 找客户端的上限 = HARD_CAP_MILLIS
            //   · 已见到 → 从「见到那一刻」再补 MIN_AFTER_CLIENT_MILLIS
            //     （宁可略超总上限，也不能在客户端晚出现时只给 0.1s ——
            //      已知失败样本就是 0.86s 进 iAP2）
            val deadline = if (seen == null) {
                clientSearchMillis
            } else {
                seen + MIN_AFTER_CLIENT_MILLIS
            }
            if (elapsed >= deadline) return elapsed

            val sleepFor = minOf(POLL_MILLIS, deadline - elapsed).coerceAtLeast(1L)
            sleep(sleepFor)

            if (seen == null) {
                val count = runCatching { poll() }.getOrNull()
                if (count != null && count > 0) firstSeenAt = now()
            }
        }
    }
}
