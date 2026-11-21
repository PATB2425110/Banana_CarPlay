package com.dbtool.carplay.bridge.developer

import com.dbtool.carplay.api.developer.CarDeveloperEndpoints
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 周期性心跳：让后台「DBPlay 车辆」页的**在线状态是实时的**。
 *
 * ## 为什么需要它（而不是复用档案上报）
 *
 * 后台的「在线」列读的是 `vehicle_profiles.last_seen_at`，而它此前**只在档案上报时**
 * 被刷新 —— 那个上报按 1 小时节流（见 `CarVehicleProfileReporter`）。
 * 于是"在线"实际上退化成了"最近一小时报过到"，一台已经关掉两小时的车
 * 在页面上照样是绿点。要做到实时，只能让车机**更频繁地**说一声"我还在"。
 *
 * 为什么不直接把档案上报的间隔从 1 小时改成 1 分钟：档案上报写的是**整行画像**
 * （VIN / 系统版本 / App 版本 / 客户端 IP），每分钟覆盖一次既是 60 倍的写入，
 * 也会把"画像何时真的变过"这个语义冲掉。心跳只写一列 `last_seen_at`，
 * 代价与语义都对得上（服务端见 `touch_vehicle_profile`）。
 *
 * ## 为什么顺手带上拉黑判定
 *
 * 心跳打的是 `POST /api/device/heartbeat`，那个接口**同时**返回"本机是否被拉黑"。
 * 这不是硬塞：两者都是周期性事实，而且必须同时成功才有意义。合成一次请求之后，
 * 后台看到的在线时间与拉黑判定天然同一时刻，客户端也少一条失败路径。
 *
 * ## 生命周期
 *
 * **不提供 `stop()`**：没有哪个 Android 回调能可靠地代表"应用要关了"，
 * 而这个线程是 daemon 线程，进程结束就跟着没了 —— 加一个永远不会被正确调用的
 * `stop()` 只会让人以为"停过了"。真要停，`CarAppServices` 的生命周期就是它的边界。
 *
 * [start] 幂等（`AtomicBoolean` 挡住）：`CarAppServices` 是进程内单例，
 * 但它的持有者（Activity / Controller）可能被重建，重复 start 不该起第二个循环。
 */
class CarPresenceHeartbeat(
    private val sync: CarDeviceStatusSync,
    /**
     * 日志出口。由调用方接到 `platform.diagnostics`（写文件、进诊断包），
     * 而不是 `android.util.Log` —— 这条链只在现场排障时才需要看。
     */
    private val log: (String) -> Unit = {},
    private val intervalMillis: Long = CarDeveloperEndpoints.HEARTBEAT_INTERVAL_MILLIS,
) {

    private val worker: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "dbplay-heartbeat").apply { isDaemon = true }
        }

    private val started = AtomicBoolean(false)

    /** 开始心跳。重复调用是空操作。 */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        worker.scheduleWithFixedDelay(
            {
                // 心跳**绝不能抛出去**：这个线程一旦因为异常死掉，
                // 后台就永远显示这台车离线，而现场完全看不出原因。
                runCatching { sync.sync() }.onFailure { error ->
                    log("心跳异常：${error.javaClass.simpleName} ${error.message.orEmpty()}")
                }
            },
            // 首次延迟 0：进程一起来就先报一次，后台立刻能看到这台车在线，
            // 不必等满一个间隔（用户很可能开机十几秒内就点连接）。
            0L,
            intervalMillis,
            TimeUnit.MILLISECONDS,
        )
        log("心跳已启动（间隔 ${intervalMillis / 1000} 秒）")
    }
}
