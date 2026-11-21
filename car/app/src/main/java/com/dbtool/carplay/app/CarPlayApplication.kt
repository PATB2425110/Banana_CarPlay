package com.dbtool.carplay.app

import android.app.Application
import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.dbtool.carplay.api.CarPlatform
import com.dbtool.carplay.api.CarPlatformRegistry
import com.dbtool.carplay.android.AndroidCarPlatform
import com.dbtool.carplay.bridge.CarAppServices
import com.dbtool.carplay.ecarx.EcarxCarPlatform
import com.dbtool.carplay.geely.GeelyCarPlatform
import com.dbtool.carplay.geely.StartupRestoreTasks
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 应用入口。
 *
 * 做两件事，顺序不能颠倒：
 * 1. **安装车机平台适配**（[CarPlatformRegistry]）。必须在任何会话创建之前完成，
 *    否则 `CarPlatformRegistry.current()` 会抛异常。
 * 2. 创建 [CarAppController] —— 进程级单例。
 *
 * 为什么会话控制器挂在 Application 而不是 Activity：
 * CarPlay 会话必须在 Activity 重建、切页、临时离开时继续保持。
 * 这与上游用 `CarPlayBackgroundSession` 保住 controller 的思路一致，
 * 只是这里直接放到进程级，逻辑更简单、没有"谁负责回收"的歧义。
 */
class CarPlayApplication : Application() {

    lateinit var services: CarAppServices
        private set

    lateinit var controller: CarAppController
        private set

    /** PATCH-0137：`attachBaseContext` 进入/返回的时刻（elapsedRealtime）。 */
    private var attachEnteredAt = 0L
    private var attachReturnedAt = 0L

    /**
     * 启动期「崩溃恢复」动作的后台执行器（2026-10-20）。
     *
     * 单线程 + daemon：两个动作按提交顺序串行跑（蓝牙补恢复在前、仪表补还原在后），
     * 且不会阻止进程退出。主线程只负责提交，不等结果。
     */
    private val startupRestoreExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "dbplay-startup-restore").apply { isDaemon = true }
    }

    /** 只负责"提交 + 计时 + 失败日志"；动作本身仍由本类传进去。 */
    private val startupRestoreTasks = StartupRestoreTasks(
        executor = startupRestoreExecutor,
        clock = { SystemClock.uptimeMillis() },
        log = { message -> Log.i(TAG, message) },
    )

    /**
     * PATCH-0137（2026-10-17）：**进程启动 → onCreate 入口**这一段以前没人量。
     *
     * 现场（VIN …368562 / 1.1.14）报的是
     * `MainThreadMonitor: Slow dispatch took 9122ms main ... BIND_APPLICATION`，
     * 而本类里 `t0..t3` 只覆盖了 181ms ⇒ 9 秒里有 8.9 秒落在**进 onCreate 之前**
     * （zygote fork / Application 类加载 / multidex / ContentProvider 安装）。
     * 不把这层分开，下次仍只能看到"启动慢"四个字。
     */
    override fun attachBaseContext(base: Context) {
        attachEnteredAt = SystemClock.elapsedRealtime()
        super.attachBaseContext(base)
        attachReturnedAt = SystemClock.elapsedRealtime()
    }

    override fun onCreate() {
        super.onCreate()
        runCatching { com.dbtool.carplay.api.vehicle.CarVehicleReadSafety.installCrashProtection(this) }
            .onFailure { Log.w(TAG, "车辆读取保护初始化失败；不应影响CarPlay启动", it) }
        val onCreateEnteredAt = SystemClock.elapsedRealtime()

        // 量一遍"进了本方法之后"的启动耗时。
        //
        // 为什么需要这一行：实车日志里 `MainThreadMonitor: Slow dispatch took
        // 1120~3822ms` 报的都是 `m=110`，也就是
        // `ActivityThread$H.BIND_APPLICATION` —— **进程冷启动那一条消息**，
        // 不是某一帧卡住。而那条消息的绝大部分花在**进本方法之前**
        // （zygote fork、Application 类加载、ContentProvider 安装），
        // 应用侧看不到、也管不了。
        // 剩下能管的就是下面三段。不分开量，下次再看到 3822ms 仍然只能猜
        // 是哪一段变大了；分开量，下一个诊断包就能直接给答案。
        val t0 = SystemClock.uptimeMillis()
        CarPlatformRegistry.install(selectPlatform(this))
        val t1 = SystemClock.uptimeMillis()

        services = CarAppServices(this)
        // 应用日志（`--pid=本进程`，滚动落盘 `logs/xcertplay.log`）**随进程常驻**
        // （PATCH-0095，2026-10-11）。
        //
        // 为什么在这里、而不是等会话起来再开：这一份只有我们自己的行（稀疏、
        // 便宜），而**空闲期发生的事只能靠它** —— 后台静默更新恰好只在"没连手机"
        // 时动手，开机自启那一串（`BootReceiver` → 热点恢复 → 首次心跳）也在会话之前。
        // 全系统那一份（约 100KB/s，写 eMMC）更贵，由会话启停控制，
        // 见 `CarAppServices.startSessionLogging()` 的说明。
        runCatching { services.startAppLogging() }
            .onFailure { Log.w(TAG, "启动应用日志记录失败", it) }
        // 蓝牙补恢复 / 仪表补还原都是崩溃恢复动作，与首帧无关 ⇒ 只提交到后台，
        // 不在 `BIND_APPLICATION` 这条消息里读盘、走厂商 Binder。动作一个都没删。
        val restoresSubmitted = submitStartupRestores()
        val t2 = SystemClock.uptimeMillis()

        controller = CarAppController(this, services)
        val t3 = SystemClock.uptimeMillis()

        // ⚠️ 2026-10-05（斌哥口径）：**不再在 App 启动时自动预热热点 / ADB**。
        //
        // 旧做法（2026-10-04）是开机/冷启动就把 9 步热点拉起 + adb 通道建好，
        // 目的是让"用户点连接"那一刻秒回。实测代价太大：预热点会打大量
        // `XCExt.hotspot.prewarm` 日志、并在主线程造成 37 秒级卡顿
        // （实车日志 `Skipped 2086 frames`），用户观感是"进首页非常卡"。
        //
        // 新口径：**热点与 ADB 都由用户手动点一次建立，且永久生效**——
        // 用户进设置页点「开启热点常驻」/「允许内置 ADB」后落盘，
        // 之后跟随车机自启由 BootReceiver 接管；App 启动这一层不再自动动手，
        // 把冷启动的主线程完整留给首帧渲染。
        //
        // 连接 CarPlay 时若热点/ADB 尚未就绪，会话路径的 `ensureAdbHotspot`
        // 会照常拉起并给出中文进度，只是不再由启动阶段抢占。

        Log.i(
            TAG,
            "启动分段：装平台=${t1 - t0}ms 建服务=${t2 - t1}ms 建控制器=${t3 - t2}ms" +
                " 后台恢复已提交=$restoresSubmitted" +
                "（本方法合计 ${t3 - t0}ms）",
        )
        Log.i(TAG, "启动分段：控制器构造=${t3 - t2}ms")
        // PATCH-0137：把"进本方法之前"那段也量出来（只读系统时钟，无副作用）。
        val processStartAt = runCatching { Process.getStartElapsedRealtime() }.getOrDefault(0L)
        val sinceProcess = if (processStartAt > 0L) onCreateEnteredAt - processStartAt else -1L
        Log.i(
            TAG,
            "启动分段：进程启动→onCreate 入口=${sinceProcess}ms" +
                "（其中 attachBaseContext 之前=${attachEnteredAt - processStartAt}ms" +
                "、attachBaseContext 本身=${attachReturnedAt - attachEnteredAt}ms" +
                "、attach→onCreate=${onCreateEnteredAt - attachReturnedAt}ms）" +
                "｜本类 onCreate 内=${t3 - onCreateEnteredAt}ms",
        )
    }

    /**
     * 把两个崩溃恢复动作提交到后台（2026-10-20）。
     *
     * ## 为什么必须还在（只改时机）
     *
     * - 蓝牙：默认档 `DISABLE_ADAPTER` 关的是整个适配器；进程被杀这条路径不经过
     *   任何会话事件 ⇒ 不补恢复就会留下"蓝牙一直关着"，连带车钥匙/胎压/耳机全断；
     * - 仪表：进程被杀不会走 `stop()` / `SessionEnded`，仪表可能停在原厂全屏地图页。
     *
     * 两个动作都保留原来的 `runCatching + onFailure 打日志`，失败只影响自己。
     *
     * @return 两个动作是否都已提交给执行器（不代表执行成功）。
     */
    private fun submitStartupRestores(): Boolean {
        val bluetooth = startupRestoreTasks.submit("蓝牙补恢复") {
            runCatching { services.restoreBluetoothAdapterIfLeftDisabled() }
                .onFailure { Log.w(TAG, "补恢复蓝牙适配器失败", it) }
        }
        val cluster = startupRestoreTasks.submit("仪表补还原") {
            runCatching { services.restoreClusterModeIfLeftFullMap() }
                .onFailure { Log.w(TAG, "补还原仪表全屏模式失败", it) }
        }
        return bluetooth && cluster
    }

    /**
     * 选车机平台。
     *
     * 判据是"车机上有没有这个厂商的服务"（这里是 OneOS 的服务总线 / 仪表 HDMI2 那块屏），
     * 而不是读 `Build.MANUFACTURER`——厂商名会被刷机包、白牌车机改掉，
     * 而系统组件在就在、不在就不在。
     *
     * ## ⚠️ 优先级：Geely **必须**排在 Ecarx 前面（2026-10-07）
     *
     * 吉利车机同时满足两个判据（Geely 的判据本身就是"eCarx + 有 1920x1080 非主屏"），
     * 是 Ecarx 的**真超集**。若先判 Ecarx，本机的仪表副屏永远不会被启用 ——
     * 用户看到的现象就是"装了新版，仪表盘还是黑的"。
     *
     * 顺序写死在这里而不是靠"哪个判据更严"：这是一条**产品语义**，
     * 大声写在调用点比藏在两个 `isAvailable()` 的实现细节里更不容易被后人改错。
     *
     * 探测不到一律退回 [AndroidCarPlatform] 基线：在非目标车机上仍然能跑，
     * 只是没有厂商专有能力。**不要**让"选平台"失败变成启动崩溃。
     */
    private fun selectPlatform(context: Context): CarPlatform = when {
        GeelyCarPlatform.isAvailable(context) -> GeelyCarPlatform(context)
        EcarxCarPlatform.isAvailable(context) -> EcarxCarPlatform(context)
        else -> AndroidCarPlatform(context)
    }

    companion object {
        /** logcat 标签。与 `BootReceiver` 的 `carplay-boot` 同一命名风格。 */
        private const val TAG = "carplay-startup"

        /**
         * 进程内已建好的服务实例（可能尚未初始化）。
         *
         * ⚠️ 存在的理由只有一个：`BootReceiver` 收到 BOOT_COMPLETED 时要**顺手
         * 预热热点**，但它拿不到 `Application` 实例。`Application.onCreate` 时序
         * 上一定早于任何广播，所以这里读到的一般就是 `onCreate` 建好的那份；
         * 真读到 null（极罕见，例如进程刚被拉起就开始分发广播）就**什么都不做**——
         * 那时的 `onCreate` 自己会预热，不需要这里兜。
         */
        @Volatile
        private var instance: CarPlayApplication? = null

        /** 见 [instance]。**可能为 null**，调用方必须做空判断。 */
        fun servicesOrNull(): CarAppServices? = instance?.services

        /**
         * 见 [instance]。**可能为 null**。
         *
         * 【2026-10-19 新增】给 `io.dushu.car.receiver.MediaControlReceiver` 用：
         * 广播接收者由系统实例化，拿不到 `Application` 实例，只能走这条静态口子。
         *
         * ⚠️ 必须用 `runCatching` 包住：`controller` 是 `lateinit`，而
         * `isInitialized` 只能在本类里对 `this` 用，从外部检查不了。进程刚被拉起、
         * `onCreate` 还没跑完就收到广播时，直接读会抛 `UninitializedPropertyAccessException`。
         * 那种情况下**丢弃这条命令**是正确的 —— 会话都还没建，没有可控制的播放器。
         */
        fun controllerOrNull(): CarAppController? = runCatching { instance?.controller }.getOrNull()

        init { /* 保持 companion 非空，便于 Kotlin 生成静态访问。 */ }
    }

    init {
        instance = this
    }
}
