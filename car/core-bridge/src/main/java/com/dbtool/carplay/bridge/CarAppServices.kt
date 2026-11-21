package com.dbtool.carplay.bridge

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.dbtool.carplay.api.CarPlatform
import com.dbtool.carplay.api.CarPlatformRegistry
import com.dbtool.carplay.api.airplay.CarAirPlayIconManager
import com.dbtool.carplay.api.developer.CarDeveloperHost
import com.dbtool.carplay.api.display.CarClusterFullMapControl
import com.dbtool.carplay.api.display.CarClusterSpec
import com.dbtool.carplay.api.display.CarVideoCanvas
import com.dbtool.carplay.api.hotspot.AdbPortVerdict
import com.dbtool.carplay.api.hotspot.CarAdbHotspotDefaults
import com.dbtool.carplay.api.hotspot.CarAdbInitializationState
import com.dbtool.carplay.api.hotspot.CarAdbChannelStatus
import com.dbtool.carplay.api.hotspot.CarAdbChannelStatusPolicy
import com.dbtool.carplay.api.hotspot.CarAdbHotspotHost
import com.dbtool.carplay.api.hotspot.CarHotspotEnabledState
import com.dbtool.carplay.api.hotspot.CarHotspotEndpointCandidate
import com.dbtool.carplay.api.hotspot.CarHotspotEndpointCandidates
import com.dbtool.carplay.api.hotspot.CarNativeHotspotHost
import com.dbtool.carplay.api.mfi.CarMfiIdentityManager
import com.dbtool.carplay.api.session.CarSessionHost
import com.dbtool.carplay.api.settings.CarHotspotSecurity
import com.dbtool.carplay.api.settings.CarHotspotMode
import com.dbtool.carplay.api.settings.reusesCarNativeHotspot
import com.dbtool.carplay.api.settings.readsAppleCarPlayHotspotCredentials
import com.dbtool.carplay.api.settings.CarSessionSettings
import com.dbtool.carplay.api.settings.CarSettingsStore
import com.dbtool.carplay.api.update.CarUpdateHost
import com.dbtool.carplay.api.hotspot.CarManualHotspotMatch
import com.dbtool.carplay.api.hotspot.CarNativeHotspotCredentials
import com.dbtool.carplay.api.hotspot.CarNativeHotspotInfo
import com.dbtool.carplay.bridge.airplay.CarAirPlayIconStore
import com.dbtool.carplay.bridge.bluetooth.CarBluetoothHandoffReleaser
import com.dbtool.carplay.bridge.bluetooth.CarPrivilegedShell
import com.dbtool.carplay.bridge.developer.CarDeveloperMode
import com.dbtool.carplay.bridge.developer.CarDeviceStatusSync
import com.dbtool.carplay.bridge.developer.CarLogcatRecorder
import com.dbtool.carplay.bridge.developer.CarMfiBlockPrefs
import com.dbtool.carplay.bridge.developer.CarPresenceHeartbeat
import com.dbtool.carplay.bridge.developer.CarSystemShell
import com.dbtool.carplay.bridge.developer.CarVehicleProfileReporter
import com.dbtool.carplay.bridge.hotspot.AdbShellRunner
import com.dbtool.carplay.bridge.hotspot.AdbShellSession
import com.dbtool.carplay.bridge.hotspot.CarAdbHotspotManager
import com.dbtool.carplay.bridge.hotspot.CarAdbWatchdog
import com.dbtool.carplay.bridge.hotspot.CarHotspotEnabledReader
import com.dbtool.carplay.bridge.hotspot.CarHotspotModeInit
import com.dbtool.carplay.bridge.hotspot.CarNetworkInterfaces
import com.dbtool.carplay.bridge.hotspot.CarNativeHotspotProbe
import com.dbtool.carplay.bridge.hotspot.DeviceAdbEnabler
import com.dbtool.carplay.bridge.hotspot.LocalSoftApReader
import com.dbtool.carplay.bridge.mfi.CarMfiIdentityStore
import com.dbtool.carplay.bridge.network.CarHotspotPlan
import com.dbtool.carplay.bridge.network.CarHotspotResolver
import com.dbtool.carplay.bridge.update.CarUpdateManager
import java.io.File

/**
 * 设置页「本次将使用」的两行文案 + 一行"手机怎么连"。
 *
 * - [target]：**结论**——到底用哪张网（"复用车机热点「X」"/"自建热点「DBPlay」"）；
 * - [detail]：**理由**——探测到什么、为什么回落、加密方式与频段；
 * - [phoneHint]：**iPhone 该连哪张网、密码多少**。
 *
 * 刻意不把 `CarHotspotPlan` 直接抛给 UI：`:car:ui` 不依赖 `:car:core-bridge`，
 * 而且 UI 只需要这三句话，不需要 `passphrase` 以外的内部字段。
 */
data class CarHotspotBriefing(
    val target: String,
    val detail: String,
    /**
     * 只在**复用车机热点**时非 null。
     *
     * 为什么自建那一支不给：那张网的 SSID / 密码就是设置页上方两个输入框里的值，
     * 再说一遍是冗余。而复用车机热点时，凭据来自**车机侧**、用户在本 App 里
     * 根本看不到——无线连不上时最缺的就是这一句。
     */
    val phoneHint: String?,
    /**
     * 「复用车机热点」下用户手填的名字与车机实际热点名不一致时的警告；否则 null。
     *
     * 2026-10-06 加。这条必须**显式、结构化**地传出来，而不是拼进 [detail]：
     * 它是设置页要**标红**的那种提示（见 `SettingsScreen`），
     * 靠字符串里找 `⚠️` 来决定标不标红，是把展示逻辑建在文案上 —— 早晚失灵。
     */
    val warning: String? = null,
    /**
     * 车机自带热点开关的**当前**读数（三态）。
     *
     * 2026-10-08 加（车主口径：「设置页要能常驻看到车机热点状态」，而不是等连接
     * 失败才知道）。与连接前那道拦截（`CarPlaySessionBridge.ensureManualHotspotReady`）
     * 读的是**同一个** [CarHotspotEnabledReader] ⇒ 设置页显示什么，连接时就是什么，
     * 两处不会得出矛盾结论。
     *
     * 只在无线链路下有意义（[hotspotBriefing] 本身只在无线时被调用）。
     */
    val enabledState: CarHotspotEnabledState = CarHotspotEnabledState.UNKNOWN,
    /**
     * 本机网卡上**当前真实存在**、可直接填进「本机 CarPlay 地址」的候选（PATCH-0102，2026-10-12）。
     *
     * ## 为什么要把它算出来给界面
     *
     * 「复用车机热点」要求用户手填一个"iPhone 能访问到的本机地址"，但设置页过去只有
     * 一个**空输入框** —— 用户只能去猜，而提示文案里写死的那个 `192.168.15.1`
     * 是**外置 T-Box 网关、根本不在本机网卡上**（填了必然被连接前那道闸门拦下）。
     * 真车日志 `日志/3` 里 10 次尝试全灭就是这么来的（`REF-pitfalls.md` §125）。
     *
     * 这里把"本机现在到底有哪些地址"如实读出来，界面据此**列出来让用户点选**，
     * 把"填一个值"从猜谜变成选择。
     *
     * ## 口径
     *
     * 判据全在纯函数 [CarHotspotEndpointCandidates.of]（可脱机单测）。空表 = 读不到
     * （没有 up 的非回环网卡，或枚举失败）—— **不是**"没有可用地址"，界面必须区别对待。
     */
    val endpointCandidates: List<CarHotspotEndpointCandidate> = emptyList(),
    /**
     * 车机自带热点的**真实凭据**（设置页「一键填入」用，2026-10-13）。
     *
     * 只在**复用车机热点**（`MANUAL`）且探测到了车机热点时非 null：
     * 自建模式下那两个输入框命名的是**我们自己的网**，把车机的凭据填进去
     * 反而错。密码读不到时 [CarNativeHotspotCredentials.passphrase] 为 null，
     * 界面据此只填名称、不动密码框。
     */
    val nativeCredentials: CarNativeHotspotCredentials? = null,
)

/**
 * 应用层的**唯一入口**。
 *
 * 存在理由：`CarPlaySessionBridge` 的构造函数里出现了 upstream 的
 * `AirPlayIdentity` / `PairingStore` / `LockdownPairRecord` 等类型，
 * 而 `:car:app` 的编译类路径上**没有** `:shared`（core-bridge 用
 * `implementation` 而不是 `api` 依赖它）。因此应用层根本无法直接
 * new 出这个 Bridge，也不应该知道这些类型的存在。
 *
 * 本类把这些细节全部包住，只向应用层暴露 `:car:api` 里的类型：
 *
 * ```
 * :car:app  →  CarAppServices  →  :car:core-bridge  →  :shared (upstream)
 *                    ↑ 只出现 api 类型
 * ```
 *
 * 结果是：**只要本文件编译通过，应用层就不可能意外引入 upstream 依赖。**
 */
class CarAppServices(context: Context) {

    private val appContext = context.applicationContext
    private val identityStore = CarIdentityStore(appContext)
    private val settingsStoreImpl = SharedPreferencesCarSettingsStore(appContext)
    private val adbInitializationPrefs = appContext.getSharedPreferences(ADB_INITIALIZATION_PREFS, Context.MODE_PRIVATE)

    /**
     * **共享的 adb shell 通道**。
     *
     * ⚠️ 必须是单例，而且**必须同时交给探测 / 自建热点 / 在线更新**：
     * 它内部持有一个自建的 adb server（端口 5038）与一组长驻的 hostapd /
     * udhcpd 客户端进程。两个实例会互相 `kill-server`，表现是
     * "探测好好的，紧接着拉起热点就报连不上"。
     *
     * ⚠️ **声明位置有讲究（2026-10-11）**：必须排在 [updateManager] **之前**。
     * Kotlin 的类属性按**文本顺序**初始化，而 [updateManager] 的构造是
     * **直接引用**（不是 lambda）这个实例 —— 声明在后会取到未初始化值
     * （即 `null`，于是"本机支持 ADB 通道"被静默判成 false）。
     * 其它消费者（[developerMode] 等）用的是 lambda，位置无所谓。
     */
    private val adbSession = AdbShellSession(appContext)

    /**
     * 2026-10-21：把手里的 [adbSession] 收窄成蓝牙定向断链要用的**只读视图**。
     *
     * - 只 `peek()`，**绝不 `ensure()`**：会话中途不初始化 ADB、不改系统设置；
     * - `shell()` 用的就是既有 `runner`，不再起第二个 adb server；
     * - 没有通道时 releaser 自己会记日志并保持连接，不会自动关适配器。
     */
    private val carPrivilegedShell = object : CarPrivilegedShell {
        override fun peek(): CarPrivilegedShell.Access? =
            adbSession.peek()
                ?.takeIf { it.shellOk }
                ?.let {
                    CarPrivilegedShell.Access(
                        prefixOrEmpty = it.prefixOrEmpty,
                        rooted = it.rooted,
                    )
                }

        override fun shell(command: String, timeoutMillis: Long): CarPrivilegedShell.Result? =
            runCatching { adbSession.runner.shell(command, timeoutMillis) }
                .getOrNull()
                ?.let { CarPrivilegedShell.Result(exitCode = it.exitCode, output = it.output) }
    }

    init {
        // 用户初始化成功的历史记录只负责维持 owner 关系，不代表当前 adbd 在线。
        // 当前状态始终由 checkAdbChannel() 实连 127.0.0.1:5556 决定。
        //
        // ⚠️ 外层**必须是这一行字面量**：`CarAdbHotspotTearDownContractTest` 的
        // 「全局ADB owner由初始化成功历史维护而非旧开关」把
        // `if (adbInitializationPrefs.getBoolean(KEY_ADB_INIT_SUCCEEDED, false))`
        // 整串钉住了（它的意图是"登记由初始化历史决定，不再受 enableBuiltInAdb 控制"）。
        // 所以「关闭 ADB 后不登记」这层新门**写在里面**而不是重排外层条件 ——
        // 意图不变（仍是初始化历史驱动），只是多一条用户口径的否决。
    }

    /**
     * ADB 专用口看门狗：把"点连接才修"提前成"巡检时发现口不对就修"。
     *
     * 只在**当前热点方案确实依赖 DBPlay 专用口**（自建或 AUTO）时启用；
     * 复用/局域网/有线这些不走专用口的模式，看门狗整个不动作。
     * 巡检周期与冷却窗口都在 [CarAdbHotspotDefaults]；占用门见 [CarAdbWatchdog]。
     */
    private val adbWatchdog = CarAdbWatchdog(
        enabled = {
            // 用户主动「关闭 ADB」后整个巡检停摆（2026-10-15）：这一门不堵，
            // 关掉之后最多 30 秒（ADB_WATCHDOG_PERIOD_MILLIS）看门狗就会把 5556 修回来，
            // 用户看到的是"关了又自己开了"。看门狗的定义域是"该用 5556 时把口摆正"，
            // 用户已经说了不用 ⇒ 它就没有动作的正当性。
            if (adbInitializationPrefs.getBoolean(KEY_ADB_USER_DISABLED, false)) {
                false
            } else {
                val mode = settingsStoreImpl.load().hotspotMode
                mode == CarHotspotMode.ADB_HOSTAPD || mode == CarHotspotMode.AUTO
            }
        },
        ownersActive = { adbSession.hasTransientOwner },
        livePort = { DeviceAdbEnabler.currentPort() },
        repair = { adbSession.repairDedicatedPort() },
        log = { message -> Log.i("XCExt.adb.watchdog", message) },
    )


    /**
     * 本地 MFi 配件身份。
     *
     * **必须是进程内唯一实例**：设置页导入身份后会调 [CarMfiIdentityStore.invalidate]，
     * 而会话启动时读的是同一份缓存。若两边各持一个实例，
     * 就会出现"设置页显示已导入，但会话仍用旧身份"的鬼故事。
     * 所以这里只建一次，同时交给设置页与 [createSession]。
     */
    private val mfiIdentityStore = CarMfiIdentityStore(appContext)

    /**
     * AirPlay OEM 图标。
     *
     * 与 [mfiIdentityStore] 同理必须是**进程内唯一实例**：设置页导入图标后
     * 立刻用同一个实例读回来展示，会话启动时读的也是同一份。
     * 若两边各持一个实例，就会出现"设置页显示换了、CarPlay 侧还是旧图"。
     */
    private val airPlayIconStore = CarAirPlayIconStore(appContext)

    /**
     * 开发者模式（隐藏页）。
     *
     * 与 [mfiIdentityStore] / [airPlayIconStore] 同理必须是**进程内唯一实例**：
     * 解锁标记、设备短码、最近一次上传结果都存在它自己的 prefs 与内存快照里，
     * 多建一个实例就会出现"解锁了但页面说没解锁"。
     *
     * 系统适配器取 [CarPlatformRegistry.current]——`CarPlayApplication` 在
     * 构造本类之前已经 `install` 过平台，这里拿到的一定是最终生效的那个。
     */
    /**
     * 滚动日志的落盘目录。
     *
     * ⚠️ **必须与 `CarDiagnosticPackager` 采集时找的位置一致**
     * （那边也是 `getExternalFilesDir(null) ?: filesDir` 下的 `logs/`）。
     * 两处各写各的路径正是"采集永远找不到会话日志"的经典成因，
     * 所以这里只在**这一处**定义，采集侧读同一个常量目录名。
     */
    private val logDir: File =
        File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, LOG_DIR_NAME)

    /**
     * **应用日志**：只装本 App 自己的行（`--pid=<本进程>`），滚动落盘到 `logs/xcertplay.log`。
     *
     * ## 为什么必须有它（PATCH-0095，2026-10-11）
     *
     * 旧版这份文件是上游宿主 `CarPlayHostActivity.initializeSessionLog()` 写的，
     * 而**那个 Activity 在当前产品里从未被实例化**（launcher 是 `car/app` 的
     * `MainActivity`）⇒ 文件从不存在，诊断包里的
     * "rollingLog: 未找到 logs/xcertplay.log" 是一条**误导性**提示。
     * 现在由我们自己写。
     *
     * 只装自己的行而不是全系统：`VehicleHal` 刷屏会把全系统日志在几秒内冲光，
     * 而本 App 的行很稀疏 ⇒ 同一个文件大小能覆盖**很久**的时间跨度。
     */
    private val appLogRecorder = CarLogcatRecorder(
        logDir = logDir,
        baseName = CarLogcatRecorder.APP_LOG_NAME,
        extraArgs = listOf("--pid=${android.os.Process.myPid()}"),
        maxFileBytes = CarLogcatRecorder.APP_MAX_FILE_BYTES,
        log = { message -> Log.i(LOGCAT_TAG, message) },
    )

    /**
     * **全系统滚动日志**：不过滤，滚动落盘到 `logs/logcat_system_rolling.txt`。
     *
     * 补的是 `logcat -d` 只覆盖几秒的缺口（实车：1.0.51 那份包里
     * `logcat_system.txt` 只覆盖 17:08:15~17:08:20，而断连发生在 17:06:45）。
     * 本车 `ro.debuggable=1`，所以这里读得到 `system_server` / `VehicleHal`；
     * 非 debuggable 的车机上只剩我们自己的行 —— 那是如实的，不是 bug。
     */
    private val systemLogRecorder = CarLogcatRecorder(
        logDir = logDir,
        baseName = CarLogcatRecorder.SYSTEM_LOG_NAME,
        maxFileBytes = CarLogcatRecorder.SYSTEM_MAX_FILE_BYTES,
        log = { message -> Log.i(LOGCAT_TAG, message) },
    )

    private val developerMode = CarDeveloperMode(
        context = appContext,
        system = CarPlatformRegistry.current().system,
        appVersionName = readAppVersionName(),
        // 诊断包里要落一份"生效设置"（`system/effective_settings.txt`）——
        // 排查时最常缺的就是"当时是什么配置"。读持久化值即可：
        // `applySettings` 是"先 save 再 updateSettings"，两者一致。
        settingsProvider = settingsStoreImpl::load,
        // 诊断包里的"系统视角"日志（含 system_server / SurfaceFlinger）必须走 adb 通道：
        // 应用自己的 uid 没有 `READ_LOGS` / `DUMP`，`logcat -b system` 只会回本进程的行、
        // `dumpsys` 直接被拒（2026-09-29 的包里 `logcat.txt` 只有本应用的 5 个 pid）。
        //
        // ⚠️ 用 `peek()` 而**不是** `ensure()`：`ensure()` 会跑整套"开通车机 ADB → 起 server
        // → 自连 → 提权探测"，等于顺手改了一项用户没同意的系统设置。拿不到通道就回落，
        // 由清单如实写明"本次没有系统视角"（见 `CarSystemShell` 的契约）。
        //
        // ⚠️ 这里**必须**是 lambda：`adbSession` 在本文件中声明得比本构造更晚，
        // 直接引用会取到未初始化的值；lambda 到采集那一刻才求值，没有这个问题。
        // 顺带也正好是想要的语义——会话可能在这之后才起来，那时通道才可用。
        systemShell = {
            adbSession.peek()
                ?.takeIf { it.shellOk }
                ?.let {
                    CarSystemShell { command, timeoutMillis ->
                        adbSession.runner.shell(command, timeoutMillis)
                            .takeIf { result -> result.ok }
                            ?.output
                    }
                }
        },
        // 提权段：只在上面的通道**已经拿到 root**（`su 0`）时给内容。
        // 用途见 `CarDiagnosticPackager.rootShell`：读厂商 `/vendor/etc/init/` 下的
        // init 脚本（车机自带热点由谁拉起、谁负责拆）—— 这些文件只有 root 读得到（2026-10-10）。
        rootShell = {
            adbSession.peek()
                ?.takeIf { it.rooted }
                ?.let { access ->
                    CarSystemShell { command, timeoutMillis ->
                        adbSession.runner.shell(access.prefixOrEmpty + command, timeoutMillis)
                            .takeIf { result -> result.ok }
                            ?.output
                    }
                }
        },
        // 后台静默更新的状态（PATCH-0095）：写进 `system/update_state.txt`。
        // 用 lambda 而不是直接取值 —— `updateManager` 声明在本构造之后，
        // 直接引用会取到未初始化的值（与本文件里 `systemShell` 同一个坑）。
        updateStateProvider = { "Apple CarPlay: updater removed" },
        // 滚动日志记录器的运行时快照（PATCH-0095）：采集那一刻"记录器在不在跑"。
        // 同样用 lambda —— 两个 recorder 声明在本构造之后。
        recorderStatusProvider = { logcatRecorderStatus() },
    )

    /**
     * 在线更新。
     *
     * 与 [developerMode] 同理必须是**进程内唯一实例**：`downloadedApkPath` /
     * `pendingInstallVersionCode` 这些"升级链路的状态"存在它自己的 prefs 与
     * 内存快照里，多建一个实例就会出现"下好了但界面说没下"。
     *
     * ⚠️ 构造时会做一次"上次交出去的安装到底成没成"的判定（比对版本号），
     * 因此它必须在**应用启动时**就被创建，不能等到用户点「检查更新」才 new。
     *
     * ⚠️ 2026-10-11：ADB 通道改为**复用共享的 [adbSession]**（此前它自己
     * new 了一个 runner，与热点/探测各持一个 adb server 却共用 5038 端口，
     * `kill-server` 不幂等 ⇒ 后台静默安装会在"会话刚结束"的瞬间与热点互踢）。
     */
    private val updateManager = com.dbtool.carplay.bridge.update.DisabledProductUpdates

    /**
     * 车辆档案上报（启动时自动把 VIN / 系统版本 / App 版本告诉后台）。
     *
     * 与 [developerMode] / [updateManager] 同理必须是**进程内唯一实例**：
     * 它自己持有一份"上次上报时间"的节流状态，多建一个实例会让节流失效
     * （每次重建都补发一次）。
     */
    private val profileReporter = CarVehicleProfileReporter(
        context = appContext,
        system = CarPlatformRegistry.current().system,
        appVersionName = readAppVersionName(),
        appVersionCode = readAppVersionCode(),
    )

    /**
     * 心跳：报在线（后台「在线」列的实时来源）+ 取回本机是否被拉黑。
     *
     * 必须是**进程内唯一实例**，且必须由本类持有：它有两个消费者——
     * [createSession] 把它注入给会话桥（连接前的拦截），应用启动时又由
     * [presenceHeartbeat] 拉起周期循环。两份实例会各起一个循环，
     * 车机侧白打一倍心跳。
     */
    private val deviceStatusSync = CarDeviceStatusSync(
        context = appContext,
        store = CarMfiBlockPrefs(appContext),
        system = CarPlatformRegistry.current().system,
        log = { message -> CarPlatformRegistry.current().diagnostics.log(HEARTBEAT_TAG, message) },
    )

    /** 周期心跳（`CarAppController` 启动时 `start()`，之后每 60 秒一次）。 */
    private val presenceHeartbeat = CarPresenceHeartbeat(
        sync = deviceStatusSync,
        log = { message -> CarPlatformRegistry.current().diagnostics.log(HEARTBEAT_TAG, message) },
    )

    /** `pm compile -m speed -f` 的超时（2026-10-07）。
     * 首次 AOT 完整编译实测几十秒，给 120 秒余量；超时即判失败，
     * 不会把设置页卡死（调用方在后台线程）。 */
    private val PERF_COMPILE_TIMEOUT_MILLIS = 120_000L

    init {
        // `car:core-bridge` 是纯 JVM 模块，不直接依赖 android.util.Log；这里把它的
        // 日志出口接到本模块可用的日志上（TAG `XCExt.adb`），便于实机排障时
        // 看到「检测到 offline → 重连 → 重试」这条自愈链。
        AdbShellRunner.logger = { level, message ->
            when (level) {
                "W" -> Log.w("XCExt.adb", message)
                else -> Log.i("XCExt.adb", message)
            }
        }
    }

    /**
     * 自建热点（兜底方案）。
     *
     * ⚠️ 与 [adbSession] 同样的理由必须是**单例**：会话桥在 start() 里拉起热点、
     * 在 stop()/close() 里收掉它，而开发者页的自检要读同一份 `snapshot`。
     * 两份实例 = 自检永远看到 IDLE。
     */
    private val adbHotspotManager = CarAdbHotspotManager(
        appContext,
        adbSession,
        // ADB_HOSTAPD 会话按需启动，不受旧版本 keepHotspotAlive 偏好门控。
        autoStartGate = { true },
    )

    /**
     * 车机原生热点探测（**首选方案**）。
     *
     * 与 [adbHotspotManager] 共用 [adbSession]，所以"探测"与"自建"不会互相
     * 踢掉对方的 adb server，也不会重复跑一遍开通 ADB / 提权探测。
     */
    private val nativeHotspotProbe = CarNativeHotspotProbe(appContext, adbSession)

    /** 设置持久化。返回接口类型，应用层与 UI 都不依赖具体实现。 */
    val settingsStore: CarSettingsStore get() = settingsStoreImpl

    /** 本地 MFi 身份管理（设置页导入/清除自定义身份用）。 */
    val mfiIdentity: CarMfiIdentityManager get() = mfiIdentityStore

    /** AirPlay OEM 图标管理（设置页预览/导入/恢复默认用）。 */
    val airPlayIcon: CarAirPlayIconManager get() = airPlayIconStore

    /** 开发者模式（解锁 / 设备号 / 采集车机日志并上传）。 */
    val developer: CarDeveloperHost get() = developerMode

    /** 在线更新（查询 / 下载 / 安装）。 */
    val update: CarUpdateHost get() = updateManager

    /**
     * 车辆档案上报（启动时自动告诉后台"这台车是谁、装的哪个版本"）。
     *
     * 与 [update] / [developer] 分开暴露：更新是"这台车要不要升级"，
     * 档案是"这台车是什么"。两者都会在启动路径上被调，但互不依赖。
     */
    val profile: CarVehicleProfileReporter get() = profileReporter

    /**
     * 心跳（报在线 + 取回拉黑判定）。
     *
     * 与 [profile] 分开暴露：档案是"这台车是什么"（每小时一次、写整行），
     * 心跳是"这台车现在还在不在"（每分钟一次、只写一列）。两者都在启动路径上
     * 被调，但节流口径与失败处置都不同，合并成一个入口只会让调用方分不清。
     */
    val presence: CarPresenceHeartbeat get() = presenceHeartbeat

    /**
     * 封禁判定变化回调；由 `CarAppController` 注册，负责把 `true` 切回主线程
     * 并拉起封禁页。实现层不保证回调线程，注册方必须自己 `post` 到主线程。
     */
    var onRemoteBlockChanged: ((Boolean) -> Unit)?
        get() = deviceStatusSync.onRemoteBlockChanged
        set(value) {
            deviceStatusSync.onRemoteBlockChanged = value
        }

    /** ADB 独立热点（自检 / 拉起 / 停止）。**这是兜底方案**。 */
    val adbHotspot: CarAdbHotspotHost get() = adbHotspotManager

    /** 车机原生热点探测（探测 / 缓存）。**这是首选方案的第一步**。 */
    val nativeHotspot: CarNativeHotspotHost get() = nativeHotspotProbe

    /**
     * **预热自建热点**（`keepHotspotAlive` 打开时用）。
     *
     * 在会话之外把 ADB 自建热点先拉起来，于是用户点连接时
     * `ensureStarted` 见 `isRunning` 直接返回 true ⇒ **近乎秒开**。
     *
     * ## 为什么放在 App 启动 / 开机时做
     *
     * 热点拉起是 9 步（ADB 探测 + 网卡 + 信道 + hostapd + DHCP），实车 3~23 秒。
     * 卡在"用户点连接"那一刻就是启动慢；提到 App 启动时做，用户还在操作界面，
     * 时间被叠掉了。
     *
     * ## 不做的事
     *
     * - **不阻塞**：`ensureStarted` 内部把活儿投到 manager 自己的 worker 上，
     *   调用方（`CarPlayApplication.onCreate` / `BootReceiver`）立刻返回。
     * - **不判断成败**：起不来只是没预热成，用户点连接时 `ensureAdbHotspot`
     *   会照常重试一遍并给出可执行的中文原因（与 0.9.1 行为一致）。
     */
    // ------------------------------------------------- 滚动日志（PATCH-0095）

    /**
     * 开始记录**应用日志**（`--pid=<本进程>`，滚动落盘 `logs/xcertplay.log`）。幂等。
     *
     * ## 为什么这一份**随进程常驻**，而全系统那一份只覆盖会话
     *
     * 两份日志的成本差一个数量级：
     *
     * | | 内容 | 速率 | 一个文件能覆盖 |
     * | --- | --- | --- | --- |
     * | 应用日志 | 只有本进程的行（`--pid=`） | 稀疏 | **很久** |
     * | 全系统日志 | 不过滤，含 `VehicleHal` 刷屏 | 实测约 100KB/s | 几分钟 |
     *
     * 车机的 eMMC 要用很多年，所以"重"的那一份只在会话期间跑（[startSessionLogging]）；
     * 而"轻"的这一份**必须常驻** —— 否则**空闲期发生的事永远采不到**：
     * 后台静默更新（`CarUpdateManager`）恰好只在"没连手机"时动手，
     * 它那几行日志是排查"静默到底生效没有"的**唯一**客户端证据（PATCH-0095）。
     * 开机自启那一串（`BootReceiver` → 热点恢复 → 首次心跳）也只有常驻才覆盖得到。
     *
     * 由 `CarPlayApplication.onCreate` 在建好服务后立刻调用。
     */
    fun startAppLogging() {
        appLogRecorder.start()
    }

    /**
     * 会话开始：把**全系统**日志也滚起来（含 `system_server` / `VehicleHal`）。幂等。
     *
     * 由 `CarAppController` 在会话启动路径上调用。这一份补的是**系统视角**——
     * "为什么断连""WMS 为什么把副屏窗口拆了"的答案只在那一侧；
     * 而它也正是贵的那一份，所以只覆盖会话窗口（见 [startAppLogging] 的成本表）。
     */
    fun startSessionLogging() {
        startAppLogging()
    }

    /**
     * 会话结束：**只停全系统那一份**。幂等。
     *
     * ⚠️ 应用日志**不停**（见 [startAppLogging]）—— 会话结束后紧接着
     * 可能就是后台静默更新的空闲窗口，那正是最需要日志的时候。
     *
     * ⚠️ **文件保留**：下一次 [startSessionLogging] 会把已有内容归档成 `.1`，
     * 所以"上一次会话的最后一段"仍然在诊断包里。
     */
    fun stopSessionLogging() {
        systemLogRecorder.stop()
    }

    /** 两份都停（排障 / 用户明确要求停止记录时用）。幂等。 */
    fun stopAllLogging() {
        appLogRecorder.stop()
        systemLogRecorder.stop()
    }

    /** 滚动记录器的运行时快照（诊断包清单用；不含内容，只是状态）。 */
    fun logcatRecorderStatus(): List<String> =
        listOf(appLogRecorder.describe(), systemLogRecorder.describe())

    // ------------------------------------------------- 蓝牙适配器补恢复（PATCH-0095）

    /**
     * 进程启动时补一次「上次我们关了蓝牙适配器却没来得及恢复」（2026-10-11）。
     *
     * ## 为什么需要
     *
     * `DISABLE_ADAPTER`（本版起的默认档）会关掉整个蓝牙适配器，正常由会话结束时
     * 恢复。但**进程被杀**这条路径不经过任何会话事件：静默装自己、OOM、用户强停、
     * 车机重启。留下"蓝牙关着"的后果不只是 CarPlay 连不上 ——
     * 车钥匙 / 胎压 / 耳机全断，而用户不知道去哪儿开回来。
     *
     * ## 实现方式
     *
     * 现造一个 [CarBluetoothHandoffReleaser] 只用来调 [CarBluetoothHandoffReleaser.restoreIfLeftDisabled]：
     * 会话里那个实例属于 `CarPlaySessionBridge`（会话对象），进程刚起来时它可能
     * 还没建。这个临时实例的 worker 是懒创建的（见那边的注释），没有遗留标记时
     * 一个线程都不会起。
     *
     * ⚠️ **只恢复"我们关的"**：用户自己在系统设置里关掉蓝牙不会写那个标记，
     * 所以这里不会擅自打开它。由 `CarPlayApplication.onCreate` 调用。
     */
    fun restoreBluetoothAdapterIfLeftDisabled() {
        CarBluetoothHandoffReleaser(
            context = appContext,
            log = { message -> Log.i(BLUETOOTH_TAG, message) },
            // 启动补恢复也要拿同一条通道（Stage 2 的 allow 将来需要它）；
            // 没有通道时 restoreIfLeftDisabled 本身是空操作。
            privilegedShell = carPrivilegedShell,
        ).restoreIfLeftDisabled()
    }

    /**
     * 进程启动补还原「原厂仪表全屏地图模式」。
     *
     * 进程被 kill -9 / OOM / 强停时不经过任何会话事件，仪表可能停在全屏地图页。
     * 平台实现（吉利）用落盘账本判断是否有未完成抢屏；没有记录时是空操作。
     *
     * @return 是否受理了补还原（账本有记录且平台实现了本能力）。
     */
    fun restoreClusterModeIfLeftFullMap(): Boolean {
        val control = CarPlatformRegistry.current().cluster as? CarClusterFullMapControl
            ?: return false
        return runCatching { control.restoreIfLeftOn() }.getOrDefault(false)
    }

    fun prewarmHotspot() {
        val settings = settingsStoreImpl.load()
        if (!settings.keepHotspotAlive) return
        if (settings.hotspotMode != CarHotspotMode.ADB_HOSTAPD) return
        // 传 null 让 manager 自算网络参数（自检等非会话路径的既有用法）。
        adbHotspotManager.ensureStarted(network = null) { state ->
            android.util.Log.i(
                "XCExt.hotspot.prewarm",
                "prewarm ${state.phase}: ${state.message}",
            )
        }
    }

    /**
     * **ADB 预热**（2026-10-04）：开机 / App 启动时就把 adb shell 通道打通。
     *
     * ## 为什么需要
     *
     * 拿一条可用 adb shell 要走 5 步（开通 ADB → 校验二进制 → 起 server →
     * 自连 → 提权探测），首次实测秒级到十几秒。以前这些全压在会话启动路径上，
     * 用户点连接就得干等；而且热点自建本身还要依赖这条通道。
     *
     * 预热之后 `AdbShellSession.ensure()` 有缓存，会话里再问就是秒返回。
     *
     * ## 与热点的关系
     *
     * 刻意**不检查** `keepHotspotAlive`：ADB 通道是更底层的能力，即使用户
     * 把热点永久关了，也不该连带把 ADB 也断掉（用户可能自己在用）。
     * 只受 `enableBuiltInAdb` 门控。
     */
    fun prewarmAdb() {
        if (!settingsStoreImpl.load().enableBuiltInAdb) return
        if (isAdbDisabledByUser()) {
            // 语义一致 + 消噪（2026-10-15）：通道是用户主动关的，
            // 照旧调 ensure() 只会必然失败并往日志里写一条"看起来像故障"的记录。
            android.util.Log.i("XCExt.adb.prewarm", "用户已关闭 ADB 通道，跳过预热")
            return
        }
        runCatching {
            val access = adbSession.ensure()
            android.util.Log.i(
                "XCExt.adb.prewarm",
                "prewarm shellOk=${access.shellOk} rooted=${access.rooted} " +
                    "failure=${access.failure}",
            )
        }.onFailure {
            android.util.Log.w("XCExt.adb.prewarm", "prewarm failed", it)
        }
    }

    /**
     * **手动自愈 ADB**（设置页按钮，2026-10-04）。
     *
     * 场景：连接漂成 `offline`（实测 4 次）或 adbd 被系统回收，用户不想重启
     * App 就能修。做法是**丢缓存 + 重跑一遍"拿到 shell"全流程**（[ensure]
     * `force = true`），等价于开机预热那次，但由用户点触发。
     *
     * 返回是否拿到了可用 shell，供 UI 提示。
     *
     * ⚠️ 与 shell 层的即时自愈（`AdbShellRunner.shell` 遇 offline 就地重连）
     * **互补**：那条治"单条命令偶发 offline"，这条治"整条通道瘫了"。
     */
    fun healAdbNow(): Boolean {
        // 用户主动关掉通道之后不存在"自愈"这个动作 —— 自愈的定义是"把它修回可用"，
        // 而用户要的正是不可用。这里如实返回 false，不去建立连接（2026-10-15）。
        if (isAdbDisabledByUser()) {
            android.util.Log.i("XCExt.adb.heal", "用户已关闭 ADB 通道，跳过自愈")
            return false
        }
        // 【改动 C 2026-10-15】这一整段（开通 ADB → 起 server → 自连 → 提权探测）
        // 是"长期使用共享通道"，按 owner 记账登记 `OWNER_SELFCHECK`；它是**有界**
        // 动作，跑完就注销（漏登记会让 owner 列表误报，多登记不收会让 5038 收不掉）。
        adbSession.open(CarAdbHotspotDefaults.OWNER_SELFCHECK)
        try {
            adbSession.invalidate()
            val access = adbSession.ensure(force = true)
            android.util.Log.i(
                "XCExt.adb.heal",
                "manual heal shellOk=${access.shellOk} rooted=${access.rooted} " +
                    "failure=${access.failure}",
            )
            return access.shellOk
        } finally {
            adbSession.close(CarAdbHotspotDefaults.OWNER_SELFCHECK)
        }
    }

    /** 当前 ADB 状态读取的是持久记录；channelStatus 仍是 UNKNOWN，必须另行实连检测。 */
    fun adbInitializationState(): CarAdbInitializationState {
        val disabled = isAdbDisabledByUser()
        return CarAdbInitializationState(
            initializedByUser = adbInitializationPrefs.getBoolean(KEY_ADB_INIT_ATTEMPTED, false),
            initializationSucceeded = adbInitializationPrefs.getBoolean(KEY_ADB_INIT_SUCCEEDED, false),
            persistentPortVerified = adbInitializationPrefs.getBoolean(KEY_ADB_PERSIST_VERIFIED, false),
            channelStatus = CarAdbChannelStatus.UNKNOWN,
            disabledByUser = disabled,
            detail = if (disabled) {
                "已关闭 ADB（按用户口径停用 DBPlay 的 5556 通道）。" +
                    "车机系统自带的 ADB 端口不受本开关影响；点「永久开启 ADB（5556）」可恢复。"
            } else {
                "初始化记录不会代替当前端口检测。"
            },
        )
    }

    /**
     * 用户是否已主动「关闭 ADB」（落盘口径，重启后仍生效）。
     *
     * 读的是 prefs 而不是 `settingsStoreImpl`：禁用标记与
     * `KEY_ADB_INIT_*` 三条是同域的一次性记录（都在 [ADB_INITIALIZATION_PREFS]），
     * 放一起只需一处读、一处写；`enableBuiltInAdb` 那条属于"功能级开关"，
     * 与"通道级用户意图"不是一回事（见 [prewarmAdb] 的 KDoc）。
     */
    fun isAdbDisabledByUser(): Boolean =
        adbInitializationPrefs.getBoolean(KEY_ADB_USER_DISABLED, false)

    /**
     * **关闭 ADB 通道**（2026-10-15，设置页「关闭 ADB」按钮）。
     *
     * ## 做了什么、没做什么
     *
     * 做：① 落盘禁用标记（`commit()` 同步，**写不进就整个中止**）；② 收回共享通道
     * （[AdbShellSession.releaseAll] —— 它的 KDoc 里"用户点「关闭热点 / 关闭 ADB」"
     * 说的就是这个入口）；③ 丢弃已建立的 shell 缓存。
     *
     * **不做**：不写任何系统属性、不 `ctl.stop adbd`、不碰 `persist.adb.tcp.port`。
     * 这是刻意的 —— 那三条是不可逆方向的改动（adbd 一旦被 stop，能否起来由 init /
     * 厂商组件说了算），而用户点这颗按钮的收益只是"DBPlay 不再用它"。
     * 用可逆的手段达成目的，就不该动不可逆的开关；真实的"关闭 adbd"留到实车验证
     * `ctl.stop adbd` 确实停得住之后再谈。所以界面上不写"已断开系统 ADB"这类话，
     * 只说停用了 DBPlay 的通道。
     *
     * ## 失败语义
     *
     * 标记写不进 ⇒ **什么都不做**并如实报错。宁可"关不掉"，也不能出现
     * "关了但没记上"被看门狗/预热在 30 秒内打回 —— 那会让用户以为按钮是坏的。
     */
    fun disableAdbNow(): CarAdbInitializationState {
        val saved = adbInitializationPrefs.edit()
            .putBoolean(KEY_ADB_USER_DISABLED, true)
            .commit()
        if (!saved) {
            return adbInitializationState().copy(
                detail = "无法保存关闭状态，已取消关闭 ADB（不影响当前通道）。",
            )
        }
        runCatching { adbSession.releaseAll() }
            .onFailure { android.util.Log.w("XCExt.adb.disable", "释放共享通道异常", it) }
        runCatching { adbSession.invalidate() }
        Log.i("XCExt.adb.disable", "用户关闭 ADB 通道：禁用标记已落盘，共享通道已释放")
        return adbInitializationState()
    }

    /**
     * 每次实际使用前检查 5556；只重连固定端口，不初始化、不 root、不回退其他端口。
     *
     * ## 「可用」与「重启后还能用」必须分开报（2026-10-16）
     *
     * 旧实现只要实连通过就报 `READY`，于是"当前能连、但持久属性并没写成 5556"
     * 这种车会被显示成"全部就绪"，用户重启后才发现又回厂商口了。现在：
     *
     * - **可用** ← 实连 + shell 验活（唯一权威判据）；
     * - **重启后可用** ← 只读读回 `persist.adb.tcp.port` / `persist.adb.tcpip`
     *   （[DeviceAdbEnabler.snapshotNow]，不写任何属性、不重启 adbd）；
     * - 两者分开进 [CarAdbChannelStatusPolicy]，得到"可用 / 当前可用但持久性未确认 /
     *   不可用"三态，界面据此渲染状态行与按钮可点性。
     */
    fun checkAdbChannel(): CarAdbInitializationState {
        // 用户已关闭 ⇒ 不实连探测。探测在这里除了产出一条误导性的"5556 当前不可用"
        // 之外没有别的作用，而那句话会把用户引向「永久开启 ADB」，一点就把通道打开。
        if (isAdbDisabledByUser()) return adbInitializationState()
        val access = runCatching { adbSession.checkDedicatedPort() }.getOrNull()
        val ready = access?.shellOk == true
        val previous = adbInitializationState()
        // 只读取证（反射读四条属性，不写、不连、不重启 adbd）；读不到即"未验证"。
        val snapshot = runCatching { DeviceAdbEnabler.snapshotNow() }
            .getOrDefault(DeviceAdbEnabler.PortSnapshot("", "", "", ""))
        val verdict = AdbPortVerdict.judge(
            snapshot = snapshot.toVerdictSnapshot(shellExitCode = 0, connectionVerified = ready),
            targetPort = CarAdbHotspotDefaults.DEVICE_ADB_PORT,
        )
        val status = CarAdbChannelStatusPolicy.statusFor(
            usable = ready,
            persistentVerified = verdict.persistentVerified,
            initializedByUser = previous.initializedByUser,
        )
        return previous.copy(
            channelStatus = status,
            detail = when {
                !ready ->
                    access?.failure ?: "5556 当前不可用；请手动执行「永久开启 ADB」初始化/修复。"
                access?.rooted != true ->
                    verdict.detail + " 尚未取得 root：自建热点需要 root/su。"
                else -> verdict.detail
            },
        )
    }

    /**
     * 启动 ADB 专用口看门狗（幂等）。
     *
     * 与心跳同一语义：进程内常驻、重复调用不起第二个循环；真正动手的判据见
     * [CarAdbWatchdog]（enable 门 + 占用门 + 修复限速）。只在自建/AUTO 模式下巡检，
     * 健康时保持安静，不刷日志。
     */
    fun startAdbWatchdog() {
        adbWatchdog.start()
    }

    /** 停止 ADB 专用口看门狗（主要用于生命周期收尾与测试）。 */
    fun stopAdbWatchdog() {
        adbWatchdog.stop()
    }

    /**
     * 用户手动触发：**先检测 5556 是否已经可用**；可用就直接复用（不换口、不重启 adbd），
     * 未生效才执行开启流程。绝不启动热点。
     *
     * ## 三态结论（2026-10-16 按需求重写）
     *
     * | 现场 | channelStatus | 界面 |
     * |---|---|---|
     * | 端口可用 + 持久属性读回 5556 | `READY` | 「5556 可用」，开启按钮**置灰** |
     * | 端口可用 + 持久属性未确认 | `PERSISTENCE_UNVERIFIED` | 「当前可用，持久性未确认」，按钮**保持可点**（再点一次就是去补持久化） |
     * | 端口不可用 / adbd 异常 | `UNAVAILABLE` | 如实说出原因，按钮可重试 |
     *
     * ⚠️ 与旧实现的三点区别（都是这次报障的直接成因）：
     * ① 旧实现**一进来就换口 + 重启 adbd**，把一条本来好用的通道掐断重开；
     * ② 旧实现把"脚本退出码 / 属性读回"当成功判据 ⇒ 读回值全对也可能报失败；
     * ③ 旧实现把「旧版热点自启清理失败」这句**拼进同一行** ⇒ 读起来像 ADB 开失败了。
     */
    fun initializeAdbNow(): CarAdbInitializationState {
        // 用户显式重开（2026-10-15）：先清「关闭 ADB」落盘标记，再走初始化。
        // 顺序不能反 —— 清标记放在最后的话，中间任何一次 checkAdbChannel()/
        // 看门狗巡检都会因为标记还在而拒绝动作，用户点了却像没点。
        adbInitializationPrefs.edit().putBoolean(KEY_ADB_USER_DISABLED, false).commit()
        val result = adbSession.initializeDedicatedPort()
        val initialized = result.access.shellOk && result.currentPortConfigured && result.persistentPortVerified
        if (result.access.shellOk) adbSession.open(CarAdbHotspotDefaults.OWNER_USER_ADB)
        adbInitializationPrefs.edit()
            .putBoolean(KEY_ADB_INIT_ATTEMPTED, true)
            .putBoolean(KEY_ADB_INIT_SUCCEEDED, initialized)
            .putBoolean(KEY_ADB_PERSIST_VERIFIED, result.persistentPortVerified)
            .apply()

        // 旧版热点自启清理：**独立事项**（2026-10-16）。它是"清理上一个版本留下的
        // init 服务"，与"5556 能不能用"完全没有因果关系；失败时只作为附注如实报告，
        // 不许改变 ADB 的结论（需求第 6 条：不要因为无关的热点清理失败就误判端口不可用）。
        val cleanupNote = if (result.access.shellOk && result.access.rooted) {
            disableLegacyHotspotAutostart(result.access.prefixOrEmpty)
        } else {
            "另：旧版热点开机自启清理未执行（本次没有可用 root 通道）；与 ADB 5556 通道无关。"
        }
        val channelStatus = CarAdbChannelStatusPolicy.statusFor(
            usable = result.access.shellOk,
            persistentVerified = result.persistentPortVerified,
            initializedByUser = true,
        )
        return CarAdbInitializationState(
            initializedByUser = true,
            initializationSucceeded = initialized,
            persistentPortVerified = result.persistentPortVerified,
            channelStatus = channelStatus,
            detail = listOf(result.detail, cleanupNote).filter { it.isNotBlank() }.joinToString(" "),
        )
    }

    /**
     * 迁移旧版本 DBPlay 自建热点 init 服务；只移除 DBPlay 固定路径，不碰车机热点。
     *
     * ⚠️ 2026-10-16：**这条结论与「5556 能不能用」没有因果关系**，所以失败时：
     * ① 逐条说出到底是哪一步没成（清落盘开关 / 卸脚本 / 命令本身）；
     * ② 明确标注"与 ADB 5556 通道无关"，并且**只作为附注**拼在 ADB 结论之后。
     *
     * 旧文案是一句笼统的「警告：旧版热点开机自启未能完全清理」——它和 ADB 的结果
     * 挤在同一行里，读起来就像"这次开 ADB 失败了"，把用户引去反复点按钮。
     */
    private fun disableLegacyHotspotAutostart(prefix: String): String {
        val clearFlag = adbSession.runner.shell(
            "$prefix/system/bin/setprop ${CarAdbHotspotDefaults.BOOT_ENABLED_PROPERTY} " +
                CarAdbHotspotDefaults.BOOT_ENABLED_OFF,
        )
        val uninstall = adbSession.runner.shell(
            com.dbtool.carplay.api.hotspot.CarAdbHotspotBootInstaller.buildUninstallScript(prefix),
        )
        val filesGone = uninstall.output.contains("UNINSTALL_OK")
        if (clearFlag.ok && uninstall.ok && filesGone) {
            return "附注：旧版 DBPlay 热点开机自启已停用并清理。"
        }
        val reason = when {
            !clearFlag.ok -> "写落盘开关失败（${clearFlag.summary}）"
            !uninstall.ok -> "卸载命令退出码非 0（${uninstall.summary}）"
            else -> "卸载命令跑完但文件仍在（${uninstall.summary}）"
        }
        return "附注：旧版热点开机自启未清理干净 —— $reason；" +
            "请检查 root 与 /system 写入权限。" +
            "**这是旧版本残留的独立清理项，与 ADB 5556 通道是否可用无关**，" +
            "不影响本次开启结果，也不影响现有热点功能。"
    }

    /**
     * **启动性能优化**：让系统把本 App 的字节码完整 AOT 预编译（`pm compile -m speed -f`）。
     *
     * ## 斌哥口径（2026-10-07，verbatim）
     *
     *   「你在设置里面加入一个启动性能优化，启动后执行这条
     *     `pm compile -m speed -f`」
     *
     * ## 它到底做什么
     *
     * `pm compile -m speed -f <包名>` 是 PackageManager 的编译指令：
     * - `-m speed`：按 **speed 模式**编译（全部 AOT，不留给 JIT / 解释器），
     *   运行期最少抖动；代价是编译产物最大、首次编译最慢。
     * - `-f`：强制重编，即使系统认为已是最新。
     *
     * 首次执行通常要 **几十秒**（视 App 体积与车机 CPU），之后系统记录
     * 已编译状态，再跑会很快返回（`-f` 只是保险，避免"系统以为编好了"）。
     *
     * ## 为什么要 root
     *
     * `pm compile` 操作的是 **PackageManager 的 dexopt 服务**，只有 shell/root
     * 有权限；普通 App uid 调 `pm` 会被 `Permission Denial` 顶回。因此这里复用
     * 既有提权通道（[adbSession] 的 `adb root` / `su 0`），**不新增任何提权手段**。
     *
     * ## 返回值
     *
     * `(是否成功, 中文说明)`。失败时说明里带原始输出末段，便于设置页直接显示。
     * 没拿到 root **不算崩溃**，只是回报"本车无法执行"——用户仍可自己刷机解决。
     */
    fun runStartupPerfCompile(packageName: String = appContext.packageName): Pair<Boolean, String> {
        // 用户主动关闭 ADB 后这条必然失败（`pm compile` 要 root，而 root 得先有 shell）：
        // 如实早退，不去起 server / 自连 / 探测十几秒，再报一句误导性的"需要 root"（2026-10-15）。
        if (isAdbDisabledByUser()) {
            android.util.Log.i("XCExt.perf", "用户已关闭 ADB 通道，跳过启动性能优化")
            return false to "已关闭 ADB 通道，跳过启动性能优化。"
        }
        // 1) 打通 adb shell 并拿到提权前缀。force=true：用户是"现在点一下就要结果"，
        //    不该被启动阶段可能残留的缓存糊弄。
        val access = runCatching { adbSession.ensure(force = true) }.getOrNull()
        if (access == null) {
            return false to "adb 通道初始化异常（ensure 抛异常），请查看日志 XCExt.adb。"
        }
        if (!access.rooted) {
            return false to
                "需要 root 才能执行 `pm compile`，本车未拿到 root：" +
                (access.failure?.takeIf { it.isNotBlank() } ?: "未知原因")
        }

        // 2) 执行编译。⚠️ `AdbShellRunner.shell()` **不做自动提权**——按它自己的
        //    契约（见其 KDoc），提权前缀由调用方按 `probePrivilege()` 的结果自己拼，
        //    这样才能保证"看起来成功了其实没提权"不会发生。所以这里显式拼前缀：
        //    `${access.prefixOrEmpty}pm compile ...`（前缀是 `""` 或 `su 0 `）。
        val command = "${access.prefixOrEmpty}pm compile -m speed -f $packageName"
        android.util.Log.i("XCExt.perf", "执行启动性能优化：$command")
        val started = System.currentTimeMillis()
        val result = runCatching {
            adbSession.runner.shell(command, PERF_COMPILE_TIMEOUT_MILLIS)
        }.getOrNull()
        val elapsed = System.currentTimeMillis() - started

        if (result == null) {
            return false to "执行 `$command` 时异常（${elapsed}ms），请查看日志 XCExt.perf。"
        }

        val out = result.output.trim()
        android.util.Log.i("XCExt.perf", "启动性能优化结束：ok=${result.ok} 耗时=${elapsed}ms 输出=$out")

        // 3) 判据：`pm compile` 成功时通常无输出（exit 0）；失败会带
        //    "Error:" / "Failure" / "Permission Denial" 等字样。
        val ok = result.ok && !out.contains("Error", ignoreCase = true) &&
            !out.contains("Failure", ignoreCase = true)
        return if (ok) {
            true to "启动性能优化完成（speed 模式已应用，耗时 ${elapsed / 1000} 秒）。"
        } else {
            false to "启动性能优化失败（耗时 ${elapsed / 1000} 秒）：${out.take(400)}"
        }
    }

    /**
     * **手动关闭自建热点**（设置页按钮）。
     *
     * 斌哥口径（2026-10-04）：**"热点是永久开启，跟随车机自启动；
     * 关掉热点是永久关闭"**。所以这里是**永久关闭**：
     *
     * 1. 停掉当前热点；
     * 2. 把 `keepHotspotAlive` **落盘**成 false（不落盘就等于"本次开机"，
     *    而用户要的是"永久"）。
     *
     * 落盘之后，判据经 `autoStartGate` 回流 ⇒ **下次开机预热、下次会话
     * start 都跳过**，直到用户在设置页重新打开「热点常开」。
     *
     * ⚠️ 落盘由调用方（`CarAppController`）经 `applySettings` 做，
     * 因为那条路还要顺带刷新 UI model；这里只负责停硬件。
     */
    fun stopPrewarmedHotspot() {
        // 【改动 C · 方案 α 2026-10-15】这里**只表达"用户要放手"，不在此释放**。
        //
        // 曾经想在这里直接把长期 owner 撤掉并全收——那是"先掐通道、后清理"：
        // `stopByUser()` 内部（`clearBootEnabledFlag()` 的
        // setprop、`tearDown()` 的 killall / ip addr del）**全靠 shell**，通道一断
        // 这些动作全部空转 ⇒ 车主现场「点了关闭手机上还搜得到」复发。
        // 释放统一由 `CarAdbHotspotManager.tearDown()` 在清理结束后做。
        adbHotspotManager.stopByUser()
    }

    /**
     * **热点模式切换时的初始化**（2026-10-09，PATCH-0051）。
     *
     * 由 `CarAppController.applySettings()` 在**模式真的变了**时调用；
     * 同模式的普通设置变更（分辨率、音量…）会在 [CarHotspotModeInit.onModeChanged]
     * 里被 no-op 掉，**不会**平白重启热点。
     *
     * ## 为什么必须存在
     *
     * 两种模式共用同一张 `wlan0`。以往从"自建"切到"复用"时：
     * 自建留下的网关地址（planner 规划的那个，如 `192.168.176.1`）仍在网卡上，
     * 而复用分支的"我们的热点在跑吗"判据**恰好就是找这个地址** ⇒ 命中残留 ⇒
     * AirPlay 把一个**并不存在**的网段地址通告给 iPhone ⇒ 40 秒 attach 超时
     * （见 `CarHotspotModeInit` 类注释那条真车日志链）。
     *
     * ## 线程
     *
     * [CarHotspotModeInit.onModeChanged] 内部会 `stop()` 并**轮询确认**残留地址
     * 释放（最长 3 秒），所以本方法**只能在 worker 线程调用**，
     * **不要在 `Application.onCreate` 的主线程路径上直接调**。
     *
     * @return 本次切换做了什么；调用方建议把 [CarHotspotModeInit.Result.note] 落日志
     */
    fun onHotspotModeChanged(from: CarHotspotMode?, to: CarHotspotMode): CarHotspotModeInit.Result {
        val result = CarHotspotModeInit(
            context = appContext,
            adbHotspot = adbHotspotManager,
            diagnostics = { line -> Log.i("XCExt.hotspot.mode", line) },
        ).onModeChanged(from = from, to = to)
        // 【改动 D 2026-10-15；Ver.3 收缩】切到自建热点后核验一次端口并留证据。
        //
        // ✅ 只做一件事：读快照 + 记一行日志。**不写属性、不 ctl.stop/ctl.start、
        //    不进 ensure()/enable()**。
        //
        // ❌ "口不对且没有活跃会话 → 补一次"那条分支**整段删除**（Ver.3 · 复审 E3）：
        //    · 动作会掐断在用连接：`DeviceAdbEnabler.enable()` 在"瞬态口 != 5556"时
        //      会 `ctl.stop adbd` + `ctl.start adbd`，而 `DeviceAdbEnablerPlatformGateTest`
        //      正是把 `ctl.stop` 钉在"换口分支"、并写明"否则会掐断正在用的那条连接"；
        //    · 判据不可信：`peek()` 就是"缓存还在不在"，改动 C 之后 `tearDown()` 会
        //      `invalidate()` 但 owners 仍在、本地 5038 仍活着 ⇒ `peek() == null`
        //      恰恰会发生在通道正被人用的时候，用它当"没有活跃会话"的判据必然误判。
        //
        // 本行只做记录，**不做判断**（尤其不做整串 `contains`）——`adbPortSnapshot()`
        // 返回的是拼好的整串，"持久=5556 而瞬态=5555"这种撕裂状态会被整串匹配
        // 误判成"口是对的"。将来若真要按它做控制流，必须**按字段分别比较**
        // 瞬态口 / 持久口 / adbd 三个字段。
        if (to == CarHotspotMode.ADB_HOSTAPD) {
            Log.i("XCExt.hotspot.mode", "切换后端口快照（只读，不补写）：" + adbPortSnapshot())
        }
        return result
    }

    /**
     * 会话拉起热点前调用：判断"现在允许自动拉起吗"。
     *
     * 判据是落盘的 `keepHotspotAlive`（永久开关，见斌哥口径）。
     */
    fun isHotspotAutoStartAllowed(): Boolean = adbHotspotManager.isAutoStartAllowed()

    /** 自建热点当前是否在跑（设置页按钮的可用性/文案用）。 */
    fun isHotspotRunning(): Boolean = adbHotspotManager.state().isRunning

    /**
     * 【新增 2026-10-15】**只读**的 adbd 端口快照，用于"开机这条路走没走到"的观测。
     *
     * ⚠️ 只读三条系统属性（瞬态口 / 持久口 / adbd 状态），**不写、不重启 adbd、
     * 不建 adb 连接、不进 `ensure()`** —— 开机时刻任何写 / 重启动作都可能掐断
     * 别人正在用的通道。
     *
     * 读不到（ROM 禁读）时如实显示空串，**按设计不当失败**。
     * 调用方：`car:app` 的 `BootReceiver`（`BOOT_COMPLETED` 的那一刻留证据）。
     * 内部转调 [DeviceAdbEnabler.snapshot]：`AdbShellSession` / `CarAdbHotspotManager`
     * 都是 `internal`，跨模块读不到，只有这里的公开 API 能出去。
     */
    fun adbPortSnapshot(): String = DeviceAdbEnabler.snapshot()

    /**
     * 【改动 C 2026-10-15】**登记一个共享 adb 通道的 owner**（对外的门面）。
     *
     * 为什么要门面：`AdbShellSession` 是 `car:core-bridge` 模块内的 `internal` 类，
     * 而 `car:app` 的 `CarAppController` 只经 `services.*` 够得到它 ——
     * 设置页那条「允许内置 ADB」false→true 的**重新登记**点在 `car:app` 里，
     * 只能走这里。
     *
     * @param owner owner 标签，一律用契约常量 `CarAdbHotspotDefaults.OWNER_*`。
     */
    fun openAdbOwner(owner: String) {
        adbSession.open(owner)
    }

    /**
     * 【改动 C 2026-10-15】**显式全收**共享 adb 通道（"关热点就彻底放手"的策略点）。
     *
     * ⚠️ 必须在**清理动作全部完成之后**调（见
     * `CarAdbHotspotManager.releaseRequested` 的时序约束）：通道一断，
     * `killall hostapd` / `ip addr del` 这些 shell 全部空转。
     *
     * 正常路径不需要调它 —— 用户点「关闭热点」时释放由 `tearDown()` 统一做。
     */
    fun releaseAdbChannel(): String {
        adbSession.releaseAll()
        return "已释放共享 adb 通道"
    }

    @Suppress("DEPRECATION")
    private fun readAppVersionName(): String = runCatching {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    /**
     * 本机版本号。
     *
     * ⚠️ 用 `longVersionCode`：`versionCode` 在 API 28 起被标记为废弃，
     * 而本工程的 `minSdk` 正好是 28。两者在正常范围内同值，
     * 但读新 API 不需要 `@Suppress` 之外的解释。
     */
    private fun readAppVersionCode(): Long = runCatching {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).longVersionCode
    }.getOrDefault(0L)

    /** 当前所基于的上游 Core 版本（展示用）。 */
    val coreVersion: String get() = CarPlayCoreInfo.displayVersion

    /**
     * 当前无线热点方案的两行文案（设置页「本次将使用」用）。
     *
     * ⚠️ 内部会做 `WifiManager` 的 binder 调用与网卡枚举，**不要在渲染路径上调用**；
     * 设置页只在设置变化/会话启动时取一次。
     *
     * 用的是**最近一次 ADB 探测的缓存结果**（[CarNativeHotspotHost.cachedInfo]），
     * 不重新探测：探测要几秒，而设置页可能在用户改一个无关开关时就被刷新一次。
     * 缓存过期（或从未探测过）时会退回本进程反射——这一步很便宜。
     *
     * 为什么一次返回两行而不是分两个方法：[CarHotspotResolver.resolve] 的探测
     * 不便宜，拆成 `target()` / `summary()` 就会探两遍。
     */
    fun hotspotBriefing(settings: CarSessionSettings): CarHotspotBriefing {
        val startedAt = SystemClock.uptimeMillis()
        // PATCH-0135（L1-5）：自建档**不需要**原厂探测结果——它自己就是那张网的主人。
        // 之前无条件读 `cachedInfo`，缓存过期时会触发一次几秒级的 ADB 探测
        // （EX11 上实测单次简报 ~4s），而这份结果在自建档里最终被丢弃。
        val nativeInfo = if (settings.hotspotMode.readsAppleCarPlayHotspotCredentials) {
            runCatching { LocalSoftApReader.read(appContext) }.getOrNull()
        } else {
            null
        }
        val afterProbe = SystemClock.uptimeMillis()
        val plan = CarHotspotResolver.resolve(
            context = appContext,
            settings = settings,
            nativeInfo = nativeInfo,
        )
        val briefing = CarHotspotBriefing(
            target = plan.targetLabel,
            detail = plan.summary,
            phoneHint = phoneHintOf(plan, settings, nativeInfo),
            // 「复用车机热点」下手填名字与车机实际名字的比对（2026-10-06）。
            //
            // 为什么要在这里算而不是交给 UI：判据要读探测结果（`nativeInfo`），
            // 而 `:car:ui` 不依赖 `:car:core-bridge`、拿不到 `CarNativeHotspotInfo`。
            // 而且探测是**缓存**的，UI 每次重算会读到不同快照。
            warning = manualSsidWarningOf(settings, nativeInfo),
            // 车机热点开关：只读一次反射 + 一次粘性广播，微秒级；
            // 而且本方法已经在 `briefingExecutor` 的后台线程上跑。
            enabledState = CarHotspotEnabledReader.read(appContext),
            // 本机可用地址候选（PATCH-0102）：一次网卡枚举 + 纯函数筛选，微秒级。
            // 读失败按空表处理 —— 界面据此显示"读不到"，而不是编一个地址出来。
            endpointCandidates = runCatching {
                CarHotspotEndpointCandidates.of(CarNetworkInterfaces.snapshot(appContext))
            }.getOrDefault(emptyList()),
            // 一键填入的真实凭据（2026-10-13）：只在复用模式下给（见字段注释）。
            // 没有 ADB 探测缓存时（启动和连接路径从不触发探测）退回进程内反射读取：
            // 日志 8 里开发者页 ADB 探测耗时 15 秒，而密码正是靠这条反射读到的。
            // 这里已经在 briefingExecutor 的后台线程上，反射读取不会卡界面。
            nativeCredentials = if (settings.hotspotMode.readsAppleCarPlayHotspotCredentials) {
                CarNativeHotspotCredentials.of(
                    nativeInfo ?: runCatching { LocalSoftApReader.read(appContext) }.getOrNull(),
                )
            } else {
                null
            },
        )
        // PATCH-0135：分段耗时一行日志 —— 下次诊断包就能看出 2–4s 花在哪一段，
        // 而不是只知道「简报慢」。段名与 L1-5 方案一一对应。
        val afterAll = SystemClock.uptimeMillis()
        Log.i(
            "XCExt.hotspot",
            "热点简报分段：mode=${settings.hotspotMode} probe=${afterProbe - startedAt}ms " +
                "resolve=${afterAll - afterProbe}ms total=${afterAll - startedAt}ms",
        )
        return briefing
    }

    /**
     * 「复用车机热点」下用户手填的 SSID 与车机实际热点名不一致时的警告；否则 null。
     *
     * ## 为什么只在这一种模式下算
     *
     * 自建（`ADB_HOSTAPD`）时**我们自己就是那张网的主人**，名字想叫什么叫什么，
     * 不存在"与车机不一致"的问题。`AUTO` 已不探测（2026-10-05 起直接自建），
     * `SAME_LAN` 连热点都不建。所以这条只在用户**显式选了复用时**才有意义。
     *
     * ## 为什么警告而不是阻止
     *
     * 用户明确要求「改成用户手动输入即可」——不替他改值、也不硬拦。
     * 但填错的后果（iPhone 静默连不上）必须当场说出来，否则只能靠"连不上"倒推。
     * 判据本身是纯函数（[CarManualHotspotMatch]），有单测兜着。
     */
    private fun manualSsidWarningOf(
        settings: CarSessionSettings,
        nativeInfo: CarNativeHotspotInfo?,
    ): String? {
        if (!settings.hotspotMode.reusesCarNativeHotspot) return null
        return CarManualHotspotMatch.evaluate(
            typedSsid = settings.manualHotspotSsid,
            nativeInfo = nativeInfo,
        ).note
    }

    /**
     * 复用车机热点时，告诉用户 iPhone 该连哪张网。
     *
     * 三种情况分开写而不是统一"连「X」"：
     * - 开放网络 → 明确说不用密码（否则用户会去找一个不存在的密码）；
     * - 密码读得到 → 直接给出来（车机侧凭据，用户在本 App 里看不到）；
     * - 密码读不到（厂商把 `getPassphrase()` 置空、或 WPA3-SAE）→ 说清去哪儿找，
     *   而不是留一句"连不上"让用户猜。
     *
     * ## ⚠️ 名字填错时**不再给"请连 X"**（2026-10-06 加）
     *
     * 手填的 SSID 与车机实际热点名不一致时，这条提示会变成一句**谎话**——
     * 它让用户去连一个没人广播的网络，而真正该做的是**先把名字改对**。
     * 所以这种时候改说"先改名字"，把"连哪张网"留到名字对了之后。
     * 判据复用 [CarManualHotspotMatch]（与上面的 [manualSsidWarningOf] 同源），
     * 两处不会得出矛盾的结论。
     */
    private fun phoneHintOf(
        plan: CarHotspotPlan,
        settings: CarSessionSettings,
        nativeInfo: CarNativeHotspotInfo?,
    ): String? {
        if (!plan.reused) return null
        val ssid = plan.ssid ?: return null
        val mismatch = !CarManualHotspotMatch.evaluate(
            typedSsid = settings.manualHotspotSsid,
            nativeInfo = nativeInfo,
        ).matches
        if (mismatch) {
            val actual = nativeInfo?.ssid
            return "先把上方「热点名称 (SSID)」改成车机实际的热点名「$actual」，" +
                "iPhone 才找得到这张网。"
        }
        return when {
            plan.security == CarHotspotSecurity.OPEN ->
                "iPhone 请连「$ssid」（开放网络，无需密码）"
            plan.passphrase.isNotEmpty() ->
                // ⚠️ 必须写 `${plan.passphrase}`：`$plan.passphrase` 只会插值 `plan` 本身，
                // 把整个 CarHotspotPlan.toString()（含全部字段）连同字面量 ".passphrase" 打到设置页上。
                "iPhone 请连「$ssid」，密码 ${plan.passphrase}"
            else ->
                "iPhone 请连「$ssid」；本机读不到它的密码，请在车机侧查看后填入上方「热点密码」"
        }
    }

    /**
     * 创建一个 CarPlay 会话。
     *
     * @param canvasSizeProvider 视频输出区域的实测尺寸提供者。
     *        每次 start/reconnect 时被询问一次，因此"分辨率变化后重连"
     *        由应用层决定时机。
     */
    fun createSession(
        platform: CarPlatform,
        settings: CarSessionSettings,
        canvasSizeProvider: () -> CarVideoCanvas? = { null },
        /**
         * 仪表副屏规格解析（2026-10-07，吉利 HDMI2 移植）。
         *
         * 由应用层提供：它会拿 `platform.cluster` 去现查 `DisplayManager`，
         * 返回声明的副屏规格（没副屏返回 null）。默认 null ⇒ 与合并前一致。
         *
         * ⚠️ 为什么不让 core-bridge 自己从 `platform.cluster` 推：
         * `CarClusterPresenter` 是"呈现契约"，不负责"声明数据"；
         * 而且"声明哪块屏"与"往哪块屏挂窗口"必须由**同一个决策**产出
         * （见 `CarPlaySessionBridge.clusterSpecResolver` 的注释）。
         * 让应用层统一持有这个决策，才不会两边各查各的。
         */
        clusterSpecResolver: () -> CarClusterSpec? = { null },
    ): CarSessionHost = CarPlaySessionBridge(
        context = appContext,
        platform = platform,
        initialSettings = settings,
        // 首次调用会生成并落盘 Ed25519 长期身份，之后复用同一份，
        // 这是 iPhone 能记住本机、不必每次重新配对的前提。
        identity = identityStore.loadIdentity(),
        pairings = identityStore.loadPairings(),
        canvasSizeProvider = canvasSizeProvider,
        clusterSpecResolver = clusterSpecResolver,
        loadPairRecord = identityStore::loadPairRecord,
        savePairRecord = identityStore::savePairRecord,
        clearPairRecord = identityStore::clearPairRecord,
        // 与设置页共用同一个身份实例，导入后立刻对会话可见。
        mfiIdentity = mfiIdentityStore,
        // 图标同理：设置页刚导入的图，下一次建栈就要用上。
        airPlayIconStore = airPlayIconStore,
        // 这里必须传**同一个**实例，不能传 `adbHotspot`（那会 new 出第二个 manager）：
        // 会话桥在 start() 里拉起热点、在 stop()/close() 里收掉它，而开发者页的
        // 「ADB 热点自检」要读同一份 `snapshot`。两份实例 = 自检永远看到 IDLE。
        adbHotspot = adbHotspotManager,
        // 2026-10-21：蓝牙定向断链复用同一条 adb 通道（只 peek，不初始化）。
        privilegedShell = carPrivilegedShell,
        // 探测链路同样必须共用实例：它和 adbHotspot 共享同一个 adb server，
        // 而且会话启动时要先问它"车机有没有现成热点"。
        nativeHotspot = nativeHotspotProbe,
        // 心跳同样共用：它既被周期循环调用（报在线），又被会话桥在建栈最开始
        // 调用（连接前拦截拉黑的车）。两份实例会各起一个心跳循环。
        deviceStatus = deviceStatusSync,
    )

    /** 已保存配对的控制器数量。 */
    fun pairedControllerCount(): Int = identityStore.pairedControllerCount()

    /** 清除全部配对记录（有线 Lockdown 记录与 AirPlay 配对一起清）。 */
    fun clearPairings() {
        identityStore.clearPairings()
        identityStore.clearPairRecord()
    }

    /**
     * 取 VPN 授权 Intent。
     * @return null 表示已授权，无需用户确认。
     */
    fun vpnConsentIntent(): Intent? = CarPlayVpnConsent.prepareIntent(appContext)

    private companion object {
        const val ADB_INITIALIZATION_PREFS = "car_adb_initialization"
        const val KEY_ADB_INIT_ATTEMPTED = "adb_5556_init_attempted"
        const val KEY_ADB_INIT_SUCCEEDED = "adb_5556_init_succeeded"
        const val KEY_ADB_PERSIST_VERIFIED = "adb_5556_persist_verified"

        /**
         * 用户主动「关闭 ADB」的落盘标记（2026-10-15）。
         *
         * 与上面三条同域，因为它们是**同一件事的四个面**：曾尝试过、上次成功、
         * 持久属性验过、以及现在用户还允不允许用。拆到别的 store 会出现
         * "关闭标记在一个地方、状态记录在另一个地方"两处读两处写。
         */
        const val KEY_ADB_USER_DISABLED = "adb_5556_user_disabled"

        /**
         * 设置页复用探测结果的时限。
         *
         * 车机热点配置不会秒级变化，而探测要跑十几条 shell 命令（数秒）。
         * 设置页可能在用户改任意一个开关时被刷新，所以这里用缓存而不是重探。
         * 会话启动路径用的是更长的时限（见 `CarPlaySessionBridge`）。
         */
        const val BRIEFING_CACHE_MILLIS = 120_000L

        /** 心跳 / 拉黑判定的诊断 TAG（写进诊断包，现场排障时按它 grep）。 */
        const val HEARTBEAT_TAG = "dbplay-heartbeat"

        /**
         * 滚动日志的目录名（相对 `getExternalFilesDir(null) ?: filesDir`）。
         *
         * ⚠️ 与 `CarDiagnosticPackager` 采集时找的目录**必须一致** ——
         * "写在一个地方、采集去另一个地方找"正是旧版会话日志永远采不到的形态。
         */
        const val LOG_DIR_NAME = "logs"

        /** 滚动日志记录器自己的 TAG（它的启动/停止也进 logcat 与诊断包）。 */
        const val LOGCAT_TAG = "XCExt.logcat"

        /** 在线更新链路的 TAG（PATCH-0095 起它终于有日志了）。 */
        const val UPDATE_TAG = "XCExt.update"

        /** 蓝牙释放 / 适配器补恢复的 TAG（PATCH-0095）。 */
        const val BLUETOOTH_TAG = "XCExt.bluetooth"
    }
}
