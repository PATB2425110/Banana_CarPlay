package com.dbtool.carplay.bridge.hotspot

import com.dbtool.carplay.api.hotspot.CarAdbHotspotDefaults
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * ADB 专用口看门狗（2026-10-15）。
 *
 * ## 只做一件事
 *
 * 在"用户已经选了要自建热点、且此刻没人占用共享 ADB 通道"时，周期性地确认
 * adbd 的瞬态口是不是 [CarAdbHotspotDefaults.DEVICE_ADB_PORT]；不是就把修复动作交给
 * [AdbShellSession.repairDedicatedPort]（它自带冷却窗口、写瞬态/持久/厂商持久、重启
 * adbd、重连验活）。这样"车机重启 / 厂商把 adbd 打回厂商口"这两件事，在用户**点连接
 * 之前**就被悄悄摆正了，不再有"第一次连失败、还要手点修复"。
 *
 * ## 三道保险（为什么它是窄职责而不是乱动）
 *
 * 1. **enable 门**：由注入的 [enabled] 决定（当前热点方案是否依赖 DBPlay 专用口），
 *    每个周期现读——设置一改立刻生效；
 * 2. **占用门**：`ownersActive()` 为真（探测 / 自检 / 更新 / 热点正用通道）时**不碰**，
 *    绝不在别人用 adbd 时把它重启；
 * 3. **限速门**：修复本身由 [CarAdbHotspotDefaults.ADB_REPAIR_COOLDOWN_MILLIS] 限速，
 *    看门狗不重复造轮子。
 *
 * ## 不是什么
 *
 * 不是全量 liveness 探测：每个周期**不**做 `adb connect + shell`（那要几百毫秒、还会在
 * adb server 里注册设备）。它只读一条瞬态属性（毫秒级、无副作用）；端到端验活仍由
 * 会话启动时的 `acquire()` 负责。也不是开机自启：App 起来、且确认无人占用之后才开始看。
 */
internal class CarAdbWatchdog(
    private val enabled: () -> Boolean,
    private val ownersActive: () -> Boolean,
    private val livePort: () -> String,
    private val repair: () -> AdbShellSession.RepairOutcome,
    private val periodMillis: Long = CarAdbHotspotDefaults.ADB_WATCHDOG_PERIOD_MILLIS,
    private val log: (String) -> Unit = {},
    private val startupGraceMillis: Long = 30_000L,
) {
    @Volatile
    private var executor: ScheduledExecutorService? = null

    /** 决策：这一周期该不该修专用口。纯函数，便于单测。 */
    fun shouldRepairNow(): Boolean =
        enabled() &&
            !ownersActive() &&
            livePort() != CarAdbHotspotDefaults.DEVICE_ADB_PORT.toString()

    /**
     * 一个巡检周期：该修就修，不该修保持安静（健康态不刷日志）。
     * 线程安全由调度器的单线程保证；本方法自身不吞异常，调用方负责 `runCatching`。
     */
    fun tick() {
        if (!shouldRepairNow()) return
        val outcome = repair()
        log(
            if (outcome.attempted) {
                "ADB 看门狗：专用口不在 5556，已发起修复 —— ${outcome.reason}"
            } else {
                "ADB 看门狗：需要修但未执行 —— ${outcome.reason}"
            },
        )
    }

    /** 幂等启动；重复调用不会起第二个循环。 */
    fun start() {
        synchronized(this) {
            if (executor != null) return
            val e = Executors.newSingleThreadScheduledExecutor { task ->
                Thread(task, "dbplay-adb-watchdog")
            }
            executor = e
            e.scheduleWithFixedDelay(
                { runCatching { tick() } },
                startupGraceMillis,
                periodMillis,
                TimeUnit.MILLISECONDS,
            )
        }
    }

    /** 停止循环（停止后仍可再次 [start]）。 */
    fun stop() {
        synchronized(this) {
            executor?.shutdownNow()
            executor = null
        }
    }
}
