package com.dbtool.carplay.bridge.vehicle

import com.dbtool.carplay.api.vehicle.CarVehicleSignals
import com.dbtool.carplay.api.vehicle.CarVehicleSnapshot
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * 按固定间隔在单个后台线程采样车辆数据。
 *
 * 本类只提供采样能力，不负责接线到 UI/投影。`start()` / `stop()` 线程安全且幂等；
 * `stop()` 返回后不会再触发回调。
 */
class CarVehicleSignalPoller(
    private val source: CarVehicleSignals,
    intervalMs: Long = 500L,
    private val onSample: (CarVehicleSnapshot) -> Unit,
) {
    val effectiveIntervalMs: Long = intervalMs.coerceAtLeast(MIN_INTERVAL_MILLIS)

    private val lock = Any()
    private var executor: ScheduledExecutorService? = null

    val isRunning: Boolean
        get() = synchronized(lock) { executor != null }

    fun start() {
        synchronized(lock) {
            if (executor != null) return
            val worker = Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "dbplay-vehicle-signal-poller").apply { isDaemon = true }
            }
            executor = worker
            worker.scheduleWithFixedDelay(
                {
                    val snapshot = runCatching { source.snapshot() }
                        .getOrElse { CarVehicleSnapshot() }
                    // 回调与 stop() 共用同一把锁，保证 stop() 返回后不再有回调。
                    synchronized(lock) {
                        if (executor !== worker) return@synchronized
                        runCatching { onSample(snapshot) }
                    }
                },
                0L,
                effectiveIntervalMs,
                TimeUnit.MILLISECONDS,
            )
        }
    }

    fun stop() {
        synchronized(lock) {
            val worker = executor ?: return
            executor = null
            worker.shutdownNow()
        }
    }

    companion object {
        const val MIN_INTERVAL_MILLIS = 200L
    }
}
