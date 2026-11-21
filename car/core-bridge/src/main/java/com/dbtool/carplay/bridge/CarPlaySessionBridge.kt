package com.dbtool.carplay.bridge

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import com.dbtool.carplay.api.hotspot.CarGeelyRoutedInfo
import com.dbtool.carplay.api.hotspot.CarGeelyRoutedHost
import com.dbtool.carplay.api.CarPlatform
import com.dbtool.carplay.api.display.CarClusterSpec
import com.dbtool.carplay.api.display.CarCustomDisplay
import com.dbtool.carplay.api.display.CarVideoCanvas
import com.dbtool.carplay.api.hotspot.CarAdbHotspotHost
import com.dbtool.carplay.api.hotspot.CarAdbHotspotState
import com.dbtool.carplay.api.hotspot.CarHotspotEnabledState
import com.dbtool.carplay.api.hotspot.CarManualEndpointGate
import com.dbtool.carplay.api.hotspot.CarManualEndpointVerdict
import com.dbtool.carplay.api.hotspot.CarManualHotspotMatch
import com.dbtool.carplay.api.hotspot.CarManualHotspotPrecheck
import com.dbtool.carplay.api.hotspot.CarNativeHotspotHost
import com.dbtool.carplay.api.hotspot.CarNativeHotspotInfo
import com.dbtool.carplay.api.hotspot.CarTcamHotspot
import com.dbtool.carplay.api.input.CarInputEvent
import com.dbtool.carplay.api.input.CarInputListener
import com.dbtool.carplay.api.session.CarReconnectState
import com.dbtool.carplay.api.session.CarReconnectStatus
import com.dbtool.carplay.api.session.CarSessionEndReason
import com.dbtool.carplay.api.session.CarSessionEvent
import com.dbtool.carplay.api.session.CarSessionHost
import com.dbtool.carplay.api.session.CarSessionListener
import com.dbtool.carplay.api.session.CarSessionPhase
import com.dbtool.carplay.api.session.CarSessionState
import com.dbtool.carplay.api.session.CarTouchContact
import com.dbtool.carplay.api.session.appliedPhase
import com.dbtool.carplay.bridge.audio.CarAudioFocusGuard
import com.dbtool.carplay.bridge.developer.CarBlockStatus
import com.dbtool.carplay.bridge.developer.CarDeviceStatusSync
import com.dbtool.carplay.bridge.developer.CarMfiBlockPrefs
import com.dbtool.carplay.bridge.developer.CarMfiBlockStore
import com.dbtool.carplay.api.audio.CarAudioChannel
import com.dbtool.carplay.api.settings.CarBluetoothHandoffPolicy
import com.dbtool.carplay.api.settings.CarHotspotMode
import com.dbtool.carplay.api.settings.CarMfiTarget
import com.dbtool.carplay.api.settings.CarHotspotSecurity
import com.dbtool.carplay.api.settings.CarSessionSettings
import com.dbtool.carplay.api.settings.forAppleCarPlay
import com.dbtool.carplay.bridge.airplay.CarAirPlayIconStore
import com.dbtool.carplay.bridge.hotspot.CarAdbHotspotPlanner
import com.dbtool.carplay.bridge.hotspot.CarHotspotClientWatcher
import com.dbtool.carplay.bridge.hotspot.CarHotspotEnabledReader
import com.dbtool.carplay.bridge.hotspot.CarHotspotUnavailableException
import com.dbtool.carplay.bridge.hotspot.CarNetworkInterfaces
import com.dbtool.carplay.bridge.hotspot.EcarxClientRoute
import com.dbtool.carplay.bridge.hotspot.EcarxHotspotAttachmentPolicy
import com.dbtool.carplay.bridge.hotspot.EcarxHotspotReader
import com.dbtool.carplay.bridge.hotspot.EcarxHotspotSessionObservation
import com.dbtool.carplay.bridge.hotspot.LocalSoftApReader
import com.dbtool.carplay.bridge.hotspot.TcamHotspotManager
import com.dbtool.carplay.bridge.media.CarPlayNowPlayingClient
import com.dbtool.carplay.api.navigation.CarHudStatus
import com.dbtool.carplay.api.navigation.CarNavigationSource
import com.dbtool.carplay.bridge.navigation.CarPlayNavigationDataSource
import com.dbtool.carplay.bridge.navigation.CarPlayNavigationHudClient
import com.dbtool.carplay.bridge.navigation.CarPlayClusterRouteClient
import com.dbtool.carplay.bridge.mfi.CarMfiBlockGuardedSigner
import com.dbtool.carplay.bridge.bluetooth.CarBluetoothHandoffReleaser
import com.dbtool.carplay.bridge.bluetooth.CarPrivilegedShell
import com.dbtool.carplay.bridge.mfi.CarMfiIdentityStore
import com.dbtool.carplay.bridge.mfi.CarMfiLoopbackServer
import com.dbtool.carplay.bridge.mfi.UnavailableCarMfiSigner
import com.dbtool.carplay.bridge.network.CarHotspotPlan
import com.dbtool.carplay.bridge.network.CarHotspotResolver
import com.dbtool.carplay.bridge.power.CarPowerModeLoader
import com.dbtool.carplay.bridge.power.CarPowerPauseCoordinator
import com.dbtool.carplay.api.power.CarPowerPauseSetting
import com.shilapi.xcertplay.airplay.AirPlayContact
import com.shilapi.xcertplay.airplay.AirPlayDeviceInfo
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayKnobState
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.airplay.CarPlayMediaEngine
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import com.shilapi.xcertplay.transport.LockdownPairRecord
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import com.dbtool.carplay.api.session.CarProjectionState
import com.dbtool.carplay.bridge.projection.ClusterProjectionController

/**
 * [CarSessionController] 的 upstream 实现：把 `CarPlayController` 包装成
 * "UI / 服务只认识的稳定契约"。
 *
 * 职责边界（刻意收窄，避免变成第二个 CarPlayHostActivity）：
 * - **只管** 会话生命周期、状态/事件广播、命令转发、Surface 转发。
 * - **不管** 权限申请（VPN / 无线 / 麦克风 / 定位）、设置页、Activity 重建、
 *   显示尺寸变化后的整栈重启。这些属于 UI/产品层，由产品 App 决定策略，
 *   通过 [updateSettings] + [reconnect] 驱动本类。
 *
 * 这样切的原因：upstream 的重启/权限逻辑与它的 Activity 深度耦合
 * （`restartGeneration`、`adoptBackgroundSession` 等）。
 * 二开版重做 UI 时应当有自己的策略，而不是把上游那套搬过来。
 *
 * 线程约定：
 * - [addListener] 的首次回调与后续所有状态回调都在主线程投递。
 * - 阻塞的 Core 关闭/启动动作在内部单线程 executor 上执行，不会卡 UI。
 */
class CarPlaySessionBridge(
    context: Context,
    private val platform: CarPlatform,
    initialSettings: CarSessionSettings,
    private val identity: AirPlayIdentity,
    private val pairings: PairingStore,
    private val sinkFactory: CarPlayMediaSinkFactory = CarPlayMediaSinkFactory { AndroidCarPlayMediaSink(it) },
    private val audioCaptureDirectory: java.io.File? = null,
    /**
     * 视频画布尺寸来源。UI 提供承载 CarPlay 画面那个 View 的实测尺寸；
     * 返回 null 时回落到面板分辨率（仅用于无 UI 场景/自检）。
     *
     * 每次 [start] / [reconnect] 都会重新询问，因此"分辨率变化 → 重连"由应用层
     * 决定时机，本类只负责用最新值发起握手——与 upstream 的重启语义一致。
     */
    private val canvasSizeProvider: () -> CarVideoCanvas? = { null },
    private val loadPairRecord: () -> LockdownPairRecord? = { null },
    private val savePairRecord: (LockdownPairRecord) -> Unit = {},
    private val clearPairRecord: () -> Unit = {},
    /**
     * 本机软件认证（[CarMfiTarget.LOCAL_OFFLINE]）用的配件身份来源。
     *
     * 默认就地构造一个——它只是持有 `applicationContext`，不做任何 I/O，
     * 真正的身份载入（含一次真实签名自检）推迟到第一次 [CarMfiIdentityStore.availability] 调用。
     */
    private val mfiIdentity: CarMfiIdentityStore = CarMfiIdentityStore(context.applicationContext),
    /**
     * AirPlay OEM 图标来源（用户导入优先、随包内置兜底）。
     *
     * 与 [mfiIdentity] 同一手法：默认就地构造，只是持有 `applicationContext`，
     * 真正的读盘发生在 [CarAirPlayIconStore.loadActive] 被调用时（= 每次 buildSession）。
     */
    private val airPlayIconStore: CarAirPlayIconStore =
        CarAirPlayIconStore(context.applicationContext),
    /**
     * ADB 独立热点（[CarHotspotMode.ADB_HOSTAPD]）的宿主。
     *
     * 上游只会"attach 到一张已经在跑的热点"，所以这张网必须由我们**先起好**
     * 再交给它。见 [ensureAdbHotspot]。
     *
     * 默认值给 null 而不是就地构造：它内部会建一个 adb server 与一组长驻进程，
     * 必须是全应用单例（`CarAppServices` 里那个），就地构造会得到第二份。
     */
    private val adbHotspot: CarAdbHotspotHost? = null,
    /**
     * 2026-10-21：特权 adb 通道的只读视图（只 `peek()`，不在会话中初始化 ADB）。
     *
     * 用于握手完成后的**定向断链**：releaser 用已有通道起
     * `app_process ... BtLinkReleaseTool disconnect <mac>`。
     * 没有通道时 releaser 只记日志、保持连接（绝不回退关适配器）。
     */
    private val privilegedShell: CarPrivilegedShell? = null,
    /**
     * 车机原生热点探测（**首选方案**）。
     *
     * 建栈时会先问它"这台车机现在有没有在跑的热点"，拿到结果再交给
     * [CarHotspotResolver] 决策：能复用就复用，不能才自建（[adbHotspot]）。
     *
     * 与 [adbHotspot] 一样默认 null：两者共享同一个 adb server，
     * 必须是全应用单例（`CarAppServices` 里那两个），就地构造会得到第二份。
     */
    private val nativeHotspot: CarNativeHotspotHost? = null,
    /**
     * 仪表副屏规格解析（2026-10-07，吉利 HDMI2 移植）。
     *
     * 返回非 null ⇒ 在 AirPlay `/info` 里多声明一块副屏（`STREAM_TYPE_ALT_SCREEN`），
     * iPhone 会为该屏单独开一条视频流。返回 null ⇒ 与合并前完全一致。
     *
     * ⚠️ 做成"解析函数"而不是直接传 [com.dbtool.carplay.api.display.CarClusterSpec]：
     * 副屏是否存在要现查 `DisplayManager`（屏可能热插拔），且**必须与
     * `cluster.start()` 那一刻选中的屏一致**。传一个提前算好的值会和 start()
     * 各查各的，出现"声明了副屏却没有窗口接"的错配。
     */
    private val clusterSpecResolver: () -> CarClusterSpec? = { null },
    /**
     * 心跳 / 拉黑判定（报在线 + 取回本机是否被拉黑）。
     *
     * 与 [mfiIdentity] / [airPlayIconStore] 同一手法：默认就地构造，只是持有
     * `applicationContext` 与系统适配器；真正的网络往返发生在 [CarDeviceStatusSync.sync]。
     *
     * ⚠️ 生产路径上由 `CarAppServices` 传**同一个**实例进来——它同时被周期心跳循环
     * 使用，两份实例会各起一个循环，车机侧白打一倍心跳。
     */
    private val deviceStatus: CarDeviceStatusSync = CarDeviceStatusSync(
        context = context.applicationContext,
        store = CarMfiBlockPrefs(context.applicationContext),
        system = platform.system,
        log = { message -> platform.diagnostics.log(TAG, message) },
    ),
) : CarSessionHost {

    private val appContext = context.applicationContext

    /**
     * 无线握手完成后释放系统蓝牙链路（2026-10-10）。
     *
     * iPhone 在 AirPlay 建好后会下发 `disableBluetooth`（带 `deviceID`）要求车机断开
     * 蓝牙，而上游只关**自己进程**的 RFCOMM socket、不动系统蓝牙 ⇒ iPhone 等不到
     * "蓝牙已断"就把会话掐了（实车约 21 秒掉线，时间线见 [CarBluetoothHandoffReleaser]）。
     *
     * 力度由 [CarSessionSettings.bluetoothHandoffPolicy] 决定。
     */
    private val bluetoothHandoffReleaser = CarBluetoothHandoffReleaser(
        context = context.applicationContext,
        log = { message -> platform.diagnostics.log(TAG, message) },
        privilegedShell = privilegedShell,
        apkPath = context.applicationContext.packageCodePath,
    )

    /**
     * iPhone 在 `disableBluetooth` 里点名的蓝牙地址（大写）。
     *
     * 上游把地址丢掉了（只用它触发"关自己的 RFCOMM"），我们在
     * [AirPlaySessionListener.onCommand] 里留一份，供握手完成时定向断开。
     */
    @Volatile
    private var handoffTargetMac: String? = null

    /**
     * 拉黑状态的本地落盘（MFi 环回服务的第二道防线用）。
     *
     * 与 `CarVehicleProfileReporter` / `CarDeviceStatusSync` 里那两份是**不同实例**，
     * 但它们读写同一个 prefs 文件——实现刻意直读直写、不做内存缓存，正是为了
     * 让这种多实例共存是安全的（见 `CarMfiBlockStore` 的线程说明）。
     */
    private val mfiBlockStore: CarMfiBlockStore = CarMfiBlockPrefs(appContext)

    @Volatile
    private var settings: CarSessionSettings = initialSettings

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<CarSessionListener>()
    private val lifecycleLock = Any()
    private val workExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "xc-ext-session").apply { isDaemon = true }
    }

    /**
     * 当前会话状态。所有对外（UI/诊断）可见的状态都从这里读，
     * 由 [updateState] 统一改写，保证线程安全。
     */
    @Volatile
    private var currentState = CarSessionState()

    /**
     * 当前活跃会话。所有 Core 侧资源都挂在这个句柄上——
     * 用单一句柄而不是散落的 `controller` / `sink` 字段，是为了保证
     * "detach 时拿到的一定是同一组资源"，避免漏关某个对象。
     */
    @Volatile
    private var activeHandle: SessionHandle? = null

    /**
     * TCAM 热点复用的会话级附着器（[CarHotspotMode.TCAM_HOTSPOT]）。
     *
     * 与 [activeHandle] 同生命周期：`buildSession()` 里由 [ensureTcamHotspotReady] 创建，
     * 会话结束（stop / reconnect / close / 建栈失败）时由 [closeTcamHotspot] 回收。
     * 它持有系统 `NetworkCallback`，漏关会留下一个"链路变化就触发重连"的幽灵回调。
     *
     * 只由 [workExecutor] 线程读写（[closeTcamHotspot] 的唯一调用方都在 worker 上），
     * 加 `@Volatile` 只为让跨线程的可见性不依赖任何人的记忆。
     */
    @Volatile
    private var activeTcamHotspot: TcamHotspotManager? = null

    private val controller: CarPlayController? get() = activeHandle?.controller

    private val sink: CarPlayMediaSink? get() = activeHandle?.sink

    @Volatile
    private var currentSurface: Surface? = null

    /**
     * 仪表副屏 Surface（2026-10-07，吉利 HDMI2 移植）。
     *
     * 与 [currentSurface] 分开保存：主屏走 110 轨、副屏走 111 轨，两条流独立。
     * Core 不关心副屏窗口谁来建（那是 `:car:platform-geely` + `:car:app` 的事），
     * 只负责把上层交来的 Surface 转发给 ALT 轨。
     */
    @Volatile
    private var currentClusterSurface: Surface? = null

    /**
     * HUD 镜像 Surface（2026-10-22）。
     *
     * 与 [currentClusterSurface] **并存**：HUD 不抢占 111 的主输出，而是
     * `setMirrorSurface(111, "hud", ...)` 的第二个消费者。记住当前值是为了
     * 会话重建后自动重绑（新 sink 没有旧 Surface 的引用）。
     */
    @Volatile
    private var currentHudSurface: Surface? = null

    @Volatile
    private var closed = false

    /**
     * 启动 / 重连的世代号。
     *
     * [start] 现在把 `startInternal()` 投到 [workExecutor]（原因见那里），于是
     * "点开始"与"点停止"可能交错：停止先把 `activeHandle` 清空，排队的启动任务
     * 随后又把会话建起来——用户看到的是"我明明按了停止，它自己连上了"。
     *
     * 每次 start / reconnect / stop 都让世代号 +1；排队的任务在真正干活之前
     * 先核对自己的世代号，过期就整段丢弃。
     */
    private val generation = AtomicInteger(0)

    /**
     * 正在 [workExecutor] 上建栈的那一轮所属的世代号（只由 worker 线程写）。
     *
     * 世代号改成原子量、[reconnect] / [stop] 不再为它抢 [lifecycleLock]（2026-10-13，日志 8）：
     * worker 持着这把锁跑完整个 `startInternal()`（复用预检 + 建栈，数秒），主线程点「重连」时
     * 在 `synchronized` 上干等了 3.4 秒（`Long monitor contention … reconnect() for 3.446s`），
     * 界面冻住，期间的点击攒到解冻后一次性涌出。世代号 +1 本身不需要互斥；
     * 需要互斥的"摘句柄 / 建栈 / 赋句柄"仍然在锁里。
     *
     * 建栈途中发现 [generation] 已不是这个值 ⇒ 这一轮已被新的 stop / reconnect 作废，
     * 在 [buildSession] 的检查点直接放弃，不再把剩下的建栈做完再拆掉。
     */
    @Volatile
    private var buildToken = 0

    /** 建栈被更新的 stop / reconnect 作废（见 [buildToken]）；不是错误，不进「连接失败」。 */
    private class StaleBuildException : RuntimeException("session build superseded")

    /**
     * 当前活跃的 AirPlay 会话——方控 HID 的落点。
     *
     * 拿它的途径是公开回调 [AirPlaySessionListener.onSessionActive]：upstream 的
     * `CarPlayController` 把这个回调透传出来，而它自己的 `activeSession` 是
     * `private var`，也**没有**转发 `sendMedia` / `sendKnob` 的公开方法。
     * 所以走回调是零上游改动拿到会话的唯一入口。
     *
     * 用 [AtomicReference] 而不是普通字段，是为了让"清空"能带身份校验：
     * 重连时旧会话的 `onSessionEnded` 完全可能晚于新会话的 `onSessionActive` 到达，
     * 直接置 null 会把刚拿到的新会话顶掉。
     */
    private val airPlaySessionRef = AtomicReference<AirPlaySession?>(null)

    private val airPlaySession: AirPlaySession? get() = airPlaySessionRef.get()

    /**
     * 「这个会话是被**故意**拆掉的」标记（2026-10-20，重连链修复）。
     *
     * 用户停止、熄火暂停、以及为了重建而主动 `closeSession()`，都会让上游
     * 回调 `onSessionEnded()` —— 它和"iPhone 真的断开了"走的是**同一条回调**。
     * 若不区分，控制器会把一次正常停止/重建当成远端断开，排一次自动重连：
     *
     * - `stop()` 后 2 秒 App 自己连回去（用户以为按钮失灵）；
     * - `reconnect()` 拆旧会话时又排一次重连（重连套娃）。
     *
     * 发起方在**拆之前**把会话放进来；`onSessionEnded` 取出原因（取不到 =
     * 远端真实断开 `REMOTE`）并随 `SessionEnded(reason)` 发给控制器。
     *
     * 用并发 Map：写入可能在主线程 / worker 上，读取在上游回调线程。
     * 正常情况下每个会话只会有一条，且回调取走即删（不会长期增长）。
     */
    private val expectedEndReasons =
        ConcurrentHashMap<AirPlaySession, CarSessionEndReason>()

    /** 只在每个会话里记一次"方控已接管"，否则一次按键一行日志会把 logcat 刷满。 */
    private val steeringWheelForwardLogged = AtomicBoolean(false)

    /**
     * 「iPhone 现在在放什么」的读取端。
     *
     * 两个消费者：
     * 1. **方控播放/暂停键**——必须知道"现在在播吗"才能显式发 Play 或 Pause
     *    （见 [CarKeyHidMap.playPauseIndex]；实车上只发 `0xCD` toggle 是不生效的）；
     * 2. **车机平台的媒体卡片 / 仪表盘**——经 [CarMediaAdapter.attachNowPlaying]
     *    注入进去，由平台自己决定怎么展示。
     *
     * 构造本身不做 I/O（[CarPlayNowPlayingClient] 只是存了个 Context），
     * 所以直接 `val` 而不是 `by lazy`。
     */
    private val nowPlayingClient = CarPlayNowPlayingClient(appContext)

    /** App UI adapter for the already-decoded iPhone route state. */
    private val navigationDataSource = CarPlayNavigationDataSource()

    /**
     * 「熄火自动暂停」接线（2026-10-18）。
     *
     * 开关独立于 [CarSessionSettings]：它不参与与 iPhone 的握手，改完应当立即生效，
     * 所以直接读写设置文件里的 `power_pause_enabled`（见 [CarPowerPauseSetting]）。
     *
     * 电源实现按类名装载（`:car:core-bridge` 不依赖 `:car:platform-ecarx`）；
     * 非 ECARX 车机上拿到的是"不可用"实现，整条链路空转，不影响任何既有行为。
     */
    private val powerPauseCoordinator = CarPowerPauseCoordinator(
        powerMode = CarPowerModeLoader.create(appContext) { message ->
            platform.diagnostics.log(TAG, message)
        },
        setting = CarPowerPauseSetting(appContext),
        pausePlayback = { nowPlayingClient.pause() },
        // 熄火暂停必须带自己的来源：否则它与用户停止在事件流里无法区分，
        // 控制器会对一次"车都熄火了"的停止排自动重连（见 expectedEndReasons）。
        stopSession = { stopInternal(CarSessionEndReason.POWER_PAUSE) },
        log = { message -> platform.diagnostics.log(TAG, message) },
    )

    /**
     * 把「正在播放」这条通道交给应用层（底栏的媒体卡用它读数据 + 发 ⏸ / ⏭）。
     *
     * 这里**不做任何生命周期判断**，直接把 client 交出去：它自身已经处理了
     * "还没连上"（`observe` 给 null、命令是空操作）与重连退避，
     * 再包一层状态判断只会多一处可能不一致的地方。
     */
    override fun nowPlayingSource(): com.dbtool.carplay.api.media.CarNowPlayingSource = nowPlayingClient

    /** The local navigation card does not depend on platform HUD support. */
    override fun navigationSource(): CarNavigationSource = navigationDataSource

    override fun phonePowerSource(): com.dbtool.carplay.api.session.CarPhonePowerSource =
        CarPlayPhonePowerSource

    /** [CarMediaAdapter.attachNowPlaying] 返回的取消订阅句柄。 */
    private var nowPlayingDetach: (() -> Unit)? = null

    /**
     * 导航转向提示的 HUD 客户端（吉利百度 hudbridge）。
     *
     * **可空**：只有实现了 [CarPlatform.hud] 的平台（目前只有 `:car:platform-geely`）
     * 才构造。其余车机拿到 null，整条链路一行都不跑。
     *
     * 与 [nowPlayingClient] 不同，这里是 `null` 而非恒有值——因为 HUD 是
     * **增值能力**，而"正在播放"每个平台都可能有卡片要展示。
     */
    private val hudClient: CarPlayNavigationHudClient? =
        platform.hud?.let { CarPlayNavigationHudClient(it) }

    /**
     * HUD 导航推送的实时状态（设置页「仪表导航」用，2026-10-13）。
     *
     * 只读、便宜：`hudClient.status()` 按契约只读内存字段（见 `CarHudBridge.status()`）。
     * `hudClient` 为 null（本车机没有 HUD 通道）时返回默认值 = `supported = false`，
     * 界面据此显示"本车机没有 HUD 通道"，而不是一片空白。
     *
     * ⚠️ 不包 `runCatching`：`CarPlayNavigationHudClient.status()` 内部已经兜了一层。
     * 这里再兜一层只会把"实现违约"这件事藏起来，而它恰恰是该被看见的。
     */
    override fun hudStatus(): CarHudStatus = hudClient?.status() ?: CarHudStatus()
    override fun activeAudioStreams(): List<com.dbtool.carplay.api.audio.CarAudioStreamStatus> =
        activeHandle?.audioObserver?.activeAudioStreams().orEmpty()

    /**
     * 导航启动自动投屏的触发端（PATCH-0119，2026-10-14 车主口径）。
     *
     * 本客户端负责订阅 iPhone 路线状态，并判定何时明确结束导航。
     * 判据复用 [com.dbtool.carplay.api.display.CarClusterCruiseGate]
     * （"开始导航自动开启、明确结束导航自动关闭；持续无导航不关闭"）。上游把"明确结束"
     * 与"持续无导航"都折叠成了"不活跃"，所以本客户端维护一个导航停超时，见该类的类注释。
     *
     * 手动投屏仍由设置页按钮触发；本客户端只处理"跟随路线状态"这一条自动路径。
     *
     * ## 它不做的事
     *
     * 不建窗、不抢屏、不发 iAP2 命令 —— 只产出"该显示 / 该收起"的请求，
     * 由 [setClusterVisibilitySink] 注册的落点（应用层）去落成真实动作。
     */
    // Navigation-driven projection is retired. No route subscriber/timer is
    // instantiated here; saved legacy clusterAutoShowOnRoute=true is inert.

    /**
     * 「该显示 / 该收起」的落点，由应用层经 [setClusterVisibilitySink] 注册
     * （`CarAppController` → `CarUiActions.setClusterVisible`）。
     *
     * `@Volatile`：注册在主线程（App 构造期），读在 iAP2 帧线程。
     * 为 null（没有应用层 / 测试替身）时请求只进日志 —— 不建第二条建窗链。
     */
    @Volatile
    private var clusterVisibilitySink: ((Boolean) -> Unit)? = null

    override fun setClusterVisibilitySink(sink: ((Boolean) -> Unit)?) {
        clusterVisibilitySink = sink
    }

    /**
     * 导航自动投屏的请求落点。
     *
     * ⚠️ **必须切主线程**：落点会建窗（`Presentation.show()`）与拆窗，两者都要求主线程；
     * 而本方法跑在 iAP2 帧到达的线程上（`CarPlayNavigationBridge` 的契约）。
     * 顺带把异常挡住 —— 一个"增值"的自动投屏绝不能把 iAP2 帧线程带崩。
     */
    private fun onClusterVisibilityRequested(visible: Boolean) {
        val sink = clusterVisibilitySink
        platform.diagnostics.log(
            TAG,
            "导航自动投屏：请求${if (visible) "显示" else "收起"}仪表画面" +
                if (sink == null) "（没有注册落点，忽略）" else "",
        )
        if (sink == null) return
        mainHandler.post {
            runCatching { sink(visible) }
                .onFailure { platform.diagnostics.warn(TAG, "导航自动投屏：落点执行失败 ${it.message}") }
        }
    }


    private val inputListener = CarInputListener { event ->
        emit(CarSessionEvent.InputReceived(event))
        forwardToCarPlay(event)
    }

    // ------------------------------------------------------------------ state

    override val state: CarSessionState get() = currentState

    override fun addListener(listener: CarSessionListener) {
        listeners.addIfAbsent(listener)
        // 立即回放一次当前状态，避免 UI 首帧空白。
        mainHandler.post { listener.onStateChanged(currentState) }
    }

    override fun removeListener(listener: CarSessionListener) {
        listeners.remove(listener)
    }

    private fun updateState(transform: (CarSessionState) -> CarSessionState) {
        val next = transform(currentState)
        if (next == currentState) return
        currentState = next
        mainHandler.post {
            for (listener in listeners) {
                runCatching { listener.onStateChanged(next) }
            }
        }
    }

    private fun emit(event: CarSessionEvent) {
        mainHandler.post {
            for (listener in listeners) {
                runCatching { listener.onEvent(event) }
            }
        }
    }

    /**
     * 探测期间的**进度合并**器（Bug 修复，2026-10-04）。
     *
     * ## 病根
     *
     * `probeNativeHotspot()` 要跑十几条 shell 命令，实测 0.1~20 秒（车机主线程
     * 忙首帧时会拖到 20 秒）。原实现在探测的**每一步**都 `emit()` 一条进度，
     * 而 [emit] 每次都 `mainHandler.post{}` —— 于是子线程在十几秒里往主线程
     * 塞几十个 Runnable。主线程正忙首帧/初始化输入法时，这些任务排在队尾，
     * 界面上的表现就是「连接卡住十几秒没有任何输出」。
     *
     * 实测铁证（10 份诊断包）：空白 20.13s / 20.74s / 10.60s；
     * 唯一一次主线程**空闲**时是 0.1s —— 差别不在探测本身，在回调积压。
     *
     * ## 修法
     *
     * 探测期间不发中间进度，只在**进入**与**出来**各发一条；中间步骤全部
     * 走 `platform.diagnostics.log`（写文件，不进主线程）。这样主线程在
     * 整个探测窗口里最多收到 2 个 Runnable，无论探测走 21 步还是 1 步。
     */
    private class ProbeProgress(private val bridge: CarPlaySessionBridge) {
        fun begin() {
            bridge.emit(CarSessionEvent.LogLine("正在经 ADB 探测车机原生热点…"))
        }

        /** 探测过程中的细节只进诊断包，不进界面（避免主线程积压）。 */
        fun detail(message: String) {
            bridge.platform.diagnostics.log(TAG, message)
        }

        fun end(label: String) {
            bridge.emit(CarSessionEvent.LogLine("探测结果：$label"))
        }
    }

    // --------------------------------------------------------------- lifecycle

    /**
     * 更新已保存的设置。握手/建轨项等待重连；本地运行时开关在此就地应用。
     */
    override fun updateSettings(next: CarSessionSettings) {
        val constrained = next.forAppleCarPlay()
        if (constrained != next) { updateSettings(constrained); return }
        val previous = settings
        settings = next
        if (previous.navigationAudioFocusEnabled != next.navigationAudioFocusEnabled) {
            // AudioManager binder work must not block the settings UI or wait on the main thread.
            workExecutor.execute {
                activeHandle?.focusGuard?.setNavigationAudioFocusEnabled(settings.navigationAudioFocusEnabled)
            }
        }
        if (previous.projectionEnabled && !next.projectionEnabled && !next.hudProjectionEnabled) {
            workExecutor.execute {
                if (!closed && !settings.projectionEnabled && !settings.hudProjectionEnabled) {
                    setProjectionEnabled(false)
                    scheduleStopUnrequestedClusterUi()
                }
            }
        }
        // ------------------------------------------------------------------
        // PATCH-0025 曾在这里写 `projectionWanted = next.projectionEnabled`，
        // 用意是"投屏开关随「保存并重连」真正生效、重启不丢"。
        //
        // ⚠️ 2026-10-12（PATCH-0100）**删掉了这一行** —— 投屏改成**按需触发**之后，
        // 它的语义反了：`projectionWanted` 是「**用户此刻要求投屏**」的运行时意图，
        // 而 `settings.projectionEnabled` 变成了「**仪表投屏功能是否可用**」的持久开关
        // （车主口径：功能默认开着，但会话建立时不自动投屏，由导航或设置页手动操作触发）。
        //
        // 把持久开关灌进运行时意图，等于"上次开着 ⇒ 这次一连上就自动投"——
        // 而建窗动作（`GeelyClusterPresenter.attach()`）末尾会 `seizeCluster()`
        // 把车机仪表从原厂 DimMenu 手里抢走。那正是本次改造要消除的现象。
        //
        // 现在 `projectionWanted` 只由两处写：
        // - `setProjectionEnabled()`（显式投屏/收起动作）；
        // - 会话结束时清成 false（见 `sessionListener.onSessionEnded`）。
        //
        // 持久化能力开关仍决定投屏功能是否可用；丢掉的只是"会话重建后自动恢复投屏"。
        // ------------------------------------------------------------------
        // 2026-10-08：仪表屏由用户自选（设置页「仪表副屏」段）。
        // 车上有 3 块屏（内置主屏 / HDMI / HUD），自动挑会挑错 ——
        // 把用户选的 displayId 透传给投影控制器，下次 start() 生效。
        projectionController.setRequestedDisplayId(next.clusterDisplayId)
        // 2026-10-11：仪表内容三项（导航卡片 / 内容对齐 / 仅地图布局控制）改了要**立刻重推** URL。
        // 它们**不改变流号**，所以 `ensureClusterContentSent` 的流代次闸门自己发现不了；
        // 这里显式重放一次投屏（幂等：URL 真没变时内部直接跳过）。
        if (previous.clusterNavigationCard != next.clusterNavigationCard ||
            previous.clusterAlignment != next.clusterAlignment ||
            previous.clusterMapLayoutTrial != next.clusterMapLayoutTrial
        ) {
            reapplyProjection()
        }
        // 2026-10-13：HUD 导航推送开关不控制副屏流或投屏能力；HUD 是握手之后才开始的
        // **单向上报**，不需要 iPhone 同意任何东西。
        // 所以这里直接改闸门、**立即生效**（登记在 `CarSettingsReconnectPolicy.LIVE_FIELDS`）。
        //
        // ⚠️ 必须在这里接，不能只放在 `start()` 里：`reconnect()` **不经过** `start()`
        // 的接线段（它直接调 `startInternal()`）⇒ 只放 `start()` 的话，
        // 用户"关掉 → 点重连生效"也照样没用，而那是最难查的一类"设置改了没反应"。
        if (previous.hudGuidanceEnabled != next.hudGuidanceEnabled) {
            hudClient?.setEnabled(next.hudGuidanceEnabled)
            platform.diagnostics.log(
                TAG,
                "HUD 导航推送：${if (next.hudGuidanceEnabled) "已开启" else "已关闭"}" +
                    "（设置变更，立即生效）",
            )
        }
        // 2026-10-14（PATCH-0119）：导航启动自动投屏。与 HUD 那一项同一类 —— 它只是
        // "路线状态要不要自动投"的**本地闸门**，不参与与 iPhone 的握手声明，
        // 所以直接改闸门、**立即生效**（登记在 `CarSettingsReconnectPolicy.LIVE_FIELDS`）。
        //
        // ⚠️ 必须在这里接，不能只放在 `start()` 里：`reconnect()` **不经过** `start()`
        // 的接线段（它直接调 `startInternal()`）⇒ 只放 `start()` 的话，
        // 用户"关掉 → 点重连生效"也照样没用，而那是最难查的一类"设置改了没反应"。
        //
        // ⚠️ 关掉时闸门内部会**主动请求收起一次**（见客户端 `setEnabled`）：
        // 不这样的话"开关关了、仪表还亮着"，比没有开关更困惑。
        // Projection visibility follows explicit switch/gesture intent only.
    }

    override fun start() {
        val mine = synchronized(lifecycleLock) {
            if (closed) return
            activeHandle?.let { handle ->
                if (handle.controller.isClosed() && !settings.isWireless) {
                    onWiredControllerDisconnected(handle.controller)
                }
                return
            }
            val token = generation.incrementAndGet()
            updateState { it.copy(phase = CarSessionPhase.PREPARING_MFI, detail = "", errorMessage = null) }
            if (!platform.input.requestExclusiveAccess()) {
                platform.diagnostics.warn(TAG, "failed to acquire exclusive input access")
            }
            platform.input.attach(inputListener)
            // 「正在播放」要跟着会话一起起来：上游那个 MediaSessionService 只在
            // CarPlay 会话期间存在，早连也连不上（客户端自己会退避重试）。
            //
            // ⚠️ 这一句**不受** `reportMediaCardToCar` 控制：方控的播放/暂停键要靠
            // `current()?.isPlaying` 决定发 Play 还是 Pause（见 `CarKeyHidMap.playPauseIndex`），
            // "关掉车机卡片上报"不等于"关掉这个状态来源"。
            // ⚠️ 下面这一段跑在**调用线程**上，而调用方是主线程
            // （`MainActivity` 的权限/VPN 回调、Compose 里的点击）——
            // 与下方 `startInternal()` 是同一条规矩，只是这里还留在原线程。
            //
            // 先量，别急着挪：`attachNowPlaying` 里会做 `PackageManager` 查询、
            // 绑一个 loopback `ServerSocket`（封面 HTTP 服务）、再 bind 一次
            // OneOS 服务；而挪进 [workExecutor] 会与 `stop()` 里"撤卡片 + 断开
            // MediaController"的**顺序**耦合（`stop()` 的同步部分先跑，worker 里
            // 的接线后跑就会漏掉一次拆卸）。所以这里先给出实测值与线程名，
            // 下一个诊断包就能判断到底值不值得动、以及动了要连带改什么。
            val wiringStartedAt = SystemClock.uptimeMillis()
            nowPlayingClient.connect()
            // 导航 → HUD：本车机有 HUD 桥才连。绑的是**外部**服务（百度 hudbridge），
            // 绑不上是常态，客户端内部只记日志、不抛。
            //
            // ⚠️ 2026-10-13：「HUD 导航推送」开关是**闸门**，不是"连不连"——
            // 关掉时**照旧连**（绑定与订阅不变，见 `CarPlayNavigationHudClient.setEnabled`
            // 的 KDoc 里"为什么不用断开实现"），只是此后一帧都不往外发。
            // 这样关掉再打开是**瞬时**的，不必等一次 bindService 往返。
            hudClient?.setEnabled(settings.hudGuidanceEnabled)
            // ⚠️ lambda 里一律用安全调用：Kotlin 不会把成员属性的智能转换带进 lambda，
            // 写 `hudClient.status()` 会直接编译不过（已踩过）。
            hudClient?.connect { available ->
                // PATCH-0117（2026-10-13）：这句原来只有 `true`/`false`。
                //
                // 现场（日志 8/9）里它一次会话出现 30+ 次且**全是 false**，而
                // "车机没装那个服务" / "装了但这次没绑上" 这两种成因在包里
                // **完全相同** —— 恰恰是这两种的处置差得最远（换通道 vs 重试）。
                // 把目标组件与最近一次失败一起写进去，一次装车就能定案。
                // （可用性本身已在桥里去重 ⇒ 这句不再是刷屏的 false 墙。）
                val status = runCatching { hudClient?.status() }.getOrNull()
                val detail = if (status == null) {
                    ""
                } else {
                    "（目标 ${status.target}；最近失败 ${status.lastFailure.ifEmpty { "无" }}）"
                }
                platform.diagnostics.log(TAG, "HUD 桥可用性：$available$detail")
            }
            if (hudClient != null && !settings.hudGuidanceEnabled) {
                platform.diagnostics.log(TAG, "HUD 导航推送：设置里已关闭，本会话不推送")
            }
            // 导航启动自动投屏（PATCH-0119）：闸门 + 订阅。
            //
            // 顺序是"先改闸门再订阅"：订阅会**立刻回放当前导航值**（`observe` 的契约），
            // 若那时闸门还是上一会话的旧值，就可能在"设置里刚关掉"的情况下发出一次
            // 显示请求 —— 而那正是要避免的"开关关了仪表还亮着"。
            com.dbtool.carplay.api.display.ClusterProjectionIntent.resetSession()
            nowPlayingDetach?.invoke()
            // 卡片/仪表盘上报单独一个开关：这条链路没有真机可验，而且只要会话活着
            // 我们就每 2s 占着车机卡片，用户想放车机自己的媒体源时得能关掉它。
            nowPlayingDetach = if (settings.reportMediaCardToCar) {
                platform.media.attachNowPlaying(nowPlayingClient)
            } else {
                null
            }
            platform.diagnostics.log(
                TAG,
                "媒体接线耗时 ${SystemClock.uptimeMillis() - wiringStartedAt}ms" +                    "（线程 ${Thread.currentThread().name}" +
                    "，上报车机卡片=${settings.reportMediaCardToCar}）",
            )
            // 熄火自动暂停：随会话一起起停。开关关着时它只挂设置监听、不注册厂商回调，
            // 因此关掉开关不会留下任何电源侧监听。
            powerPauseCoordinator.start()
            token
        }
        // ⚠️ **不能**把 `startInternal()` 留在调用线程上。
        //
        // `buildSession()` 里现在要拉起 ADB 独立热点（`ensureAdbHotspot`）：
        // 起本地 adb server、`connect`、7 条配置命令、再起 hostapd 与 udhcpd，
        // 实测是**秒级到十几秒**。而本方法的调用方是主线程
        // （`MainActivity` 的权限/VPN 回调、Compose 里的点击），
        // 留在主线程上会把「连接」直接卡成 ANR——界面连进度都画不出来。
        //
        // `reconnect()` 一直是投到 [workExecutor] 的，这里与它对齐：
        // **只换线程，不改流程**（状态机、事件、回调全在原来的位置）。
        // 排队的任务用世代号挡掉"已经按过停止"的情况。
        workExecutor.execute {
            synchronized(lifecycleLock) {
                if (closed || generation.get() != mine) return@synchronized
                buildToken = mine
                startInternal()
            }
        }
    }

    override fun stop() {
        stopInternal(CarSessionEndReason.USER_STOP)
    }

    /**
     * 停止会话，并标记**是谁要停的**。
     *
     * 用户点停止（[CarSessionEndReason.USER_STOP]）与熄火自动暂停
     * （[CarSessionEndReason.POWER_PAUSE]）在这里分开：两者最终都会触发
     * 上游的 `onSessionEnded` 回调，桥接层必须提前把旧会话标成"预期结束"，
     * 否则控制器会把这次停止当成远端断开去自动重连。
     *
     * 两种原因的文案也分开：熄火暂停不是故障，界面应显示“熄火自动暂停/已停止（熄火）”。
     */
    private fun stopInternal(reason: CarSessionEndReason) {
        // 先让世代号失效：可能有一个 `startInternal()` 正排在 [workExecutor] 上，
        // 不挡掉它的话，"停止"之后会话会自己又建起来。
        // 原子自增，不抢 lifecycleLock：建栈中的 worker 看到世代号变了会在检查点放弃（见 buildToken）。
        generation.incrementAndGet()
        // 会话没了，电源监听也必须停：否则熄火 15 秒后还会对一个已经停止的会话再调一次 stop。
        powerPauseCoordinator.stop()
        // 车机那张媒体卡片是**我们的**责任，会话一停就得自己撤掉——
        // 否则仪表盘会一直挂着一首"已经不放了"的歌（上游服务也已随之销毁）。
        // 只取消订阅，**不** release 客户端：stop 之后还能再 start。
        nowPlayingDetach?.invoke()
        nowPlayingDetach = null
        // ⚠️ 还必须**断开**，不能只取消订阅：`MediaBrowser` 用
        // `bindService(BIND_AUTO_CREATE)` 连，绑定会**重建**上游刚 `stopService` 掉的服务，
        // 而上游把 idle 通知设成 ALWAYS ⇒ 车机上会凭空挂一条媒体通知。
        nowPlayingClient.disconnect()
        // 导航 → HUD 同理：停会话时断开订阅并解绑外部服务，别让它挂在后台。
        hudClient?.disconnect()
        // 导航 → 自动投屏：停会话时断开订阅。**不请求收起** —— 仪表本来就要还给
        // 原厂（`CarAppController` 的 `SessionEnded` → `stopCluster()`），这里再补一刀
        // 只会去拆一块已经不存在的窗。要清的是定时器与门里的记忆（`disconnect` 内做）。
        com.dbtool.carplay.api.display.ClusterProjectionIntent.resetSession()
        val stoppingDetail = if (reason == CarSessionEndReason.POWER_PAUSE) {
            "熄火自动暂停"
        } else {
            "正在停止"
        }
        val stoppedDetail = if (reason == CarSessionEndReason.POWER_PAUSE) {
            "已停止（熄火）"
        } else {
            "已停止"
        }
        val detached = detachSession()
        if (detached == null) {
            updateState { it.copy(phase = CarSessionPhase.STOPPED, detail = stoppedDetail).withoutDevice() }
            return
        }
        // 必须在 closeSession 之前标记：上游的 onSessionEnded 会在关闭过程中回调，
        // 那时 [expectedEndReasons] 里必须已经有这个会话。
        // 只记录**当前活跃的 AirPlay 会话**（可能还没有，握手中途停止时就是 null）。
        airPlaySessionRef.get()?.let { expectedEndReasons[it] = reason }
        updateState { it.copy(phase = CarSessionPhase.STOPPED, detail = stoppingDetail).withoutDevice() }
        workExecutor.execute {
            closeSession(detached)
            airPlaySessionRef.set(null)
            platform.input.releaseExclusiveAccess()
            platform.input.detach(inputListener)
            // ADB 独立热点是**我们自己起的**，会话结束就该收干净
            // （killall hostapd/busybox + 撤 ip rule + 删掉我们加的地址）。
            // 非阻塞——它只是往自己的 worker 上排一个任务，不会拖慢"返回上一页"。
            //
            // ⚠️ 只在 stop / close 里收，**不在 reconnect 里收**：重连时热点
            // 保持不动反而更好（iPhone 侧不用重学网络），而 buildSession 里的
            // ensureStarted 对已就绪的热点是直接返回 true 的。
            //
            // 自建热点只属于本次 ADB_HOSTAPD 会话；停止会话立即收回，ADB 5556 通道由共享 session 保留。
            if (settings.hotspotMode == CarHotspotMode.ADB_HOSTAPD) adbHotspot?.stop()
            updateState { it.copy(phase = CarSessionPhase.STOPPED, detail = stoppedDetail).withoutDevice() }
            emit(CarSessionEvent.SessionEnded(reason))
        }
    }

    override fun reconnect() {
        // 不在主线程上抢 lifecycleLock（原因见 buildToken）：只做原子自增就返回，
        // 真正的拆旧建新排到 worker 上；`closed` 在 worker 里持锁时再核一次。
        if (closed) return
        // 重连**不经过** `start()` 的接线段（它直接调 `startInternal()`），电源监听
        // 必须在这里补一次；幂等，已在监听时什么都不做。
        powerPauseCoordinator.start()
        val mine = generation.incrementAndGet()
        workExecutor.execute {
            synchronized(lifecycleLock) {
                if (closed || generation.get() != mine) return@synchronized
                // 旧会话即将被**故意**拆掉：先标 RECONNECT，再 close。这样它迟到的
                // onSessionEnded 不会以 REMOTE 身份发给控制器（否则会再排一次重连）。
                airPlaySessionRef.get()?.let {
                    expectedEndReasons[it] = CarSessionEndReason.RECONNECT
                }
                detachSession()?.let(::closeSession)
                updateState {
                    it.copy(
                        phase = CarSessionPhase.RECONNECTING,
                        detail = "正在重启 CarPlay 协议栈",
                        // 会话已经不在：设备三件套与音频通道必须跟着清，
                        // 否则重连窗口里首页还会显示上一台 iPhone 的音频通道。
                        activeAudioChannels = emptySet(),
                    ).withoutDevice()
                }
                // 兜底再发一次“故意重连”的结束事件：旧会话的结束回调可能晚到、
                // 甚至被 `releaseAirPlaySession` 判为过期（新会话已激活）。
                // 控制器收到后只做幂等清理，**绝不**排自动重连。
                emit(CarSessionEvent.SessionEnded(CarSessionEndReason.RECONNECT))
                buildToken = mine
                startInternal()
            }
        }
    }

    /**
     * 自动重连调度器的正交状态 → 会话快照。
     *
     * 映射规则（见 `CarReconnectStatus` 的 KDoc）：
     *
     * | state | phase | detail |
     * | --- | --- | --- |
     * | `NONE` | 不改 | 不改 |
     * | `SCHEDULED` | `RECONNECTING` | 第 N/M 次重试将在 X 秒后开始 |
     * | `IN_PROGRESS` | `RECONNECTING` | 正在重建连接（第 N/M 次） |
     * | `EXHAUSTED` | **`FAILED`** | 已自动重连 N 次未成功，可手动重连 |
     *
     * ⚠️ 耗尽必须落 `FAILED` 而不是停在 `RECONNECTING`：后者的
     * `allowsSilentInstall()` 是 false，会让后台静默更新被永久阻断。
     */
    override fun updateReconnect(status: CarReconnectStatus) {
        when (status.state) {
            CarReconnectState.NONE -> updateState { it.copy(reconnect = status) }

            CarReconnectState.SCHEDULED -> updateState {
                it.copy(
                    phase = status.state.appliedPhase() ?: it.phase,
                    detail = "第 ${status.attempt}/${status.maxAttempts} 次重试将在 " +
                        "${status.delayMillis / 1_000} 秒后开始",
                    errorMessage = null,
                    reconnect = status,
                )
            }

            CarReconnectState.IN_PROGRESS -> updateState {
                it.copy(
                    phase = status.state.appliedPhase() ?: it.phase,
                    detail = "正在重建连接（第 ${status.attempt}/${status.maxAttempts} 次）",
                    errorMessage = null,
                    reconnect = status,
                )
            }

            CarReconnectState.EXHAUSTED -> updateState {
                it.copy(
                    phase = status.state.appliedPhase() ?: CarSessionPhase.FAILED,
                    detail = "已自动重连 ${status.maxAttempts} 次未成功，可手动重连",
                    errorMessage = status.lastError ?: "自动重连失败",
                    activeAudioChannels = emptySet(),
                    reconnect = status,
                ).withoutDevice()
            }
        }
    }

    /** 释放全部资源。之后本实例不可再启动。 */
    fun close() {
        val detached: SessionHandle?
        synchronized(lifecycleLock) {
            closed = true
            detached = detachSession()
        }
        platform.input.detach(inputListener)
        platform.input.releaseExclusiveAccess()
        detached?.let(::closeSession)
        // 极窄窗口兜底：close() 撞上"manager 已建、activeHandle 还没挂"的半途建栈时，
        // detachSession() 拿不到句柄、上面的 closeSession 不会跑，这里再补一刀。
        closeTcamHotspot()
        // 实例要销毁了，ADB 热点也必须收干净——它持有自建的 adb server 与
        // hostapd / udhcpd 的长驻客户端进程，留着就是泄漏。
        //
        // 自建热点始终随 ADB_HOSTAPD 会话生命周期；不依赖应用常驻设置。
        if (settings.hotspotMode == CarHotspotMode.ADB_HOSTAPD) adbHotspot?.stop()
        // 「正在播放」同理：这是终态，除了取消订阅还要断开 MediaController 连接。
        nowPlayingDetach?.invoke()
        nowPlayingDetach = null
        // 实例终态：注销电源监听与设置监听，别让已销毁的桥继续被回调。
        powerPauseCoordinator.stop()
        nowPlayingClient.release()
        // 导航 → 自动投屏的订阅同样要收干净：`CarPlayNavigationBridge` 是**进程级**
        // 单例，本实例销毁后还留着观察者，下一次会话就可能出现两条链各请求一次。
        // 幂等，重复调无所谓。
        com.dbtool.carplay.api.display.ClusterProjectionIntent.resetSession()
        // 会话已经没了，别让方控继续往一条死通道里写。
        airPlaySessionRef.set(null)
        workExecutor.shutdown()
        runCatching { workExecutor.awaitTermination(CONTROLLER_CLOSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) }
        listeners.clear()
        updateState { it.copy(phase = CarSessionPhase.STOPPED, detail = "已关闭").withoutDevice() }
    }

    // ---------------------------------------------------------------- commands

    override fun sendTouch(contacts: List<CarTouchContact>): Boolean {
        val active = controller ?: return false
        // 空列表表示"抬起所有手指"，upstream 依赖这一语义，必须原样传递。
        val mapped = contacts.map { AirPlayContact(id = it.id, x = it.x, y = it.y, down = it.down) }
        return active.sendTouch(mapped)
    }

    /**
     * 方控按键 → CarPlay HID。
     *
     * 这是"移植方控"真正闭环的一步：`:car:platform-*` 只负责把车机按键采集并归一化成
     * [CarInputEvent]，若不再往 CarPlay 的 HID 通道送，方控就只是个"能收到按键"的摆设。
     * 映射规则全在 [CarKeyHidMap]（纯函数、有单测），这里只负责执行。
     *
     * 返回值即 `CarInputListener` 的"是否已消费"语义，三条分支刻意区分开：
     *
     * - **本层不管的键**（音量 / 静音 / MENU）→ false。事件交回车机，音量仍由功放管。
     * - **会话还没建立** → false。**不消费**是有意的：此时 CarPlay 没有在跑，
     *   把按键吞掉只会表现为"没连手机时方向盘突然失灵"。
     *   也就是说本层只在 CarPlay 真的活跃时接管方控。
     * - **真的转发了** → true，避免 Android 再把同一个键交给本机播放器
     *   （否则用户按"下一首"会同时跳 CarPlay 和本机音乐）。
     */
    private fun forwardToCarPlay(event: CarInputEvent): Boolean {
        if (!settings.forwardSteeringWheelToCarPlay) return false
        // Voice key belongs to the vehicle assistant. Never turn it into CarPlay Siri.
        if (event.key == com.dbtool.carplay.api.input.CarKey.VOICE) return false
        // 播放/暂停键必须知道"现在在播吗"：只发 `0xCD`（Play/Pause toggle）
        // 在实车上完全没反应，要显式发 `0xB0` Play 或 `0xB1` Pause。
        // 状态未知时传 null，映射会退回 toggle（与改动前行为一致，不会更差）。
        // 见 [CarKeyHidMap.playPauseIndex]。
        val isPlaying = nowPlayingClient.current()?.isPlaying
        val intent = CarKeyHidMap.planFor(event.key, event.action, event.source, isPlaying)
            ?: return false
        val session = airPlaySession ?: return false
        return try {
            when (intent) {
                is CarKeyHidMap.Intent.Media -> session.sendMedia(intent.index)
                CarKeyHidMap.Intent.Siri -> session.invokeSiri()
                is CarKeyHidMap.Intent.Knob -> session.sendKnob(
                    AirPlayKnobState(
                        select = intent.select,
                        home = intent.home,
                        back = intent.back,
                    ),
                )
            }
            if (steeringWheelForwardLogged.compareAndSet(false, true)) {
                platform.diagnostics.log(
                    TAG,
                    "steering wheel driving CarPlay HID: key=${event.key} -> $intent",
                )
            }
            true
        } catch (error: Throwable) {
            // 单次转发失败不值得把整个会话打挂；记一条 warn 继续。
            platform.diagnostics.warn(
                TAG,
                "failed to forward ${event.key} to CarPlay HID: $error",
            )
            false
        }
    }

    override fun hasActiveAttachment(): Boolean =
        controller?.hasActiveAirPlayAttachment() == true

    override fun attachVideoSurface(surface: Surface?) {
        currentSurface = surface
        sink?.attachSurface(surface)
    }

    override fun hasVideoSurface(): Boolean = currentSurface != null

    override fun requestClusterKeyFrame(): Boolean =
        airPlaySession?.requestClusterKeyFrame() ?: false

    /**
     * 仪表副屏 Surface → ALT(111) 轨（2026-10-07，吉利 HDMI2 移植）。
     *
     * 与 [attachVideoSurface] 同构：Core 只做转发，副屏窗口由上层负责建/销。
     * 记住当前值是为了**会话重建后自动重绑**（与主屏同一套逻辑，见 buildSession）。
     */
    override fun attachClusterVideoSurface(surface: Surface?) {
        if (settings.immersiveNavigationEnabled || immersiveModeForConnection) return
        currentClusterSurface = surface
        sink?.attachClusterSurface(surface)
    }
    private var currentImmersiveSurface: Surface? = null
    @Volatile private var immersiveModeForConnection = false
    private var immersiveConfigForConnection = CarSessionSettings.default()
    override fun attachImmersiveVideoSurface(surface: Surface?) {
        currentImmersiveSurface = surface
        if (immersiveModeForConnection) sink?.attachClusterSurface(surface)
    }

    /**
     * HUD 镜像 Surface → 111 轨的**第二消费者**（2026-10-22）。
     *
     * 与 [attachClusterVideoSurface] 不能混用：后者会换掉 111 的主输出，
     * 仪表窗口会立刻断流。这里走渲染槽位的镜像入口，两条输出并存。
     */
    override fun attachHudVideoSurface(surface: Surface?) {
        if (settings.immersiveNavigationEnabled || immersiveModeForConnection) return
        currentHudSurface = surface
        sink?.attachHudSurface(surface)
    }

    // ===== 仪表副屏投屏实现（CarPlay cluster stream 111）=====

    /**
     * 平台层是否**已经自己管着副屏窗口**（`platform.cluster != null`）。
     *
     * ## 2026-10-08 定案：窗口归平台层独占（对齐反编译原版）
     *
     * 反编译原版只有一个窗口 owner（`GeelyClusterSurfaceOwner` + `GeelyClusterPresentation`）。
     * DBPlay 一度有两条并行的链各自建窗、各往 ALT(111) 轨塞 Surface，诊断包实证
     * 同一次会话里发生了 Surface 换绑，先建的窗从此收不到帧又不会超时，一直留在屏上。
     *
     * 现在：平台声明了 [com.dbtool.carplay.api.display.CarClusterPresenter] ⇒ 窗口由它建
     * （触发点是 `CarAppController.startClusterIfPossible()`），本桥只负责
     * 「声明副屏规格 + 转发 iAP2 命令 + 读窗口状态」。
     * 没有平台实现的车机（如通用 Android）仍由 [projectionController] 建窗，行为不变。
     */
    private val platformOwnsClusterWindow: Boolean get() = platform.cluster != null

    /**
     * 「是否已要求 iPhone 显示仪表画面」的当前值（显示请求 = true / 收起请求 = false）。
     *
     * 平台层独占窗口时由本桥自己记：那种情况下 [projectionController] 是空转的，
     * 不能再从它读（否则永远读到 false）。
     */
    @Volatile
    private var clusterUiShown = false

    /**
     * 「仪表内容 URL 已经推给手机」的**流代次**（0 = 还没推过）。
     *
     * 用 `clusterStream` 的编号做代次：同一条 111 流上只推一次（`showUI` 每次都带
     * `forceKeyFrame`，无脑重发等于每秒逼手机重发 I 帧）；换了流（重新 `SETUP`）
     * 自然就不相等，会再推一次。见 [ensureClusterContentSent]。
     */
    @Volatile
    private var clusterContentUrlStream = 0

    /**
     * 上一次**真正发出去**的仪表内容 URL（2026-10-11）。
     *
     * ## 为什么除了流号还要记 URL
     *
     * 原来的判据只有"流号变了才重推"。用户改「显示 iPhone 导航卡片 / 内容对齐 /
     * 仅地图布局控制」时**流号不变**（还是同一条 111 流），于是新 URL 一次都发不出去 ——
     * 必须断线重连才生效，而这几项本该立即生效。
     *
     * 判据改成「流号 **且** URL 都相同才跳过」。
     */
    private var clusterContentUrlSent: String? = null

    /**
     * 投屏状态机。惰性创建：建悬浮窗需要 Context，而桥在 App 启动早期就存在。
     *
     * ## 为什么 streamReady 从会话里查而不是自己记
     *
     * `clusterStream > 0` 是**手机侧**的事实（iPhone 为本机建了第二路屏流）。
     * 它由 [AirPlaySession] 在收到 SETUP(type=111) 时递增，我们无法预测，
     * 只能每次现查。这也是「按了没反应」最常见的原因：窗建好了，但手机根本没开流。
     *
     * ⚠️ 平台层有窗口 owner 时（[platformOwnsClusterWindow]）本控制器**不建窗**，
     * 它只承担 `streamReady` 这类状态；窗口状态改由
     * [platformClusterProjectionState] 从平台 presenter 读。
     */
    private val projectionController: ClusterProjectionController by lazy {
        ClusterProjectionController(
            context = appContext,
            surfaceSink = { surface -> attachClusterVideoSurface(surface) },
            streamReady = { airPlaySession?.clusterStream ?: 0 > 0 },
            log = { message -> platform.diagnostics.log(TAG, message) },
            // 2026-10-08（PATCH-0034）：半程无首帧时催仪表关键帧。
            // 会话为空（未连接）时返回 false，不误报成功。
            requestKeyFrame = { airPlaySession?.requestClusterKeyFrame() ?: false },
            // 2026-10-08：平台层有窗口 owner 时，本控制器永不建窗（结构性封堵双窗口）。
            platformOwnsWindow = { platformOwnsClusterWindow },
        )
    }

    /** 用户的投屏意图；会话重建后用它自动恢复。 */
    @Volatile
    private var projectionWanted = false

    override val projectionState: CarProjectionState
        get() = if (platformOwnsClusterWindow) {
            platformClusterProjectionState()
        } else {
            projectionController.state()
        }

    /**
     * 平台层独占窗口时的投屏状态。
     *
     * 字段来源与 [ClusterProjectionController.state] 一一对应，只是"窗口"那一半
     * 改问 `CarClusterPresenter`：
     * - `streamReady` ← 会话的 `clusterStream > 0`（手机侧事实，两套一致）
     * - `attached` / `firstFrame` / `displayId` ← 平台 presenter 的窗口
     * - `shown` ← 本桥记录的显隐请求
     */
    private fun platformClusterProjectionState(): CarProjectionState {
        val presenter = platform.cluster ?: return CarProjectionState()
        val attached = presenter.isWindowAttached()
        return CarProjectionState(
            streamReady = (airPlaySession?.clusterStream ?: 0) > 0,
            attached = attached,
            firstFrame = presenter.isRendering(),
            // `displayId` 的语义是「**接管的**屏」，未接管恒 -1
            // （与 `CarProjectionState` 的注释一致）。顺带省掉没窗口时的
            // DisplayManager 查询 —— 本函数在每次 refreshModel 都会被读到。
            displayId = if (attached) {
                runCatching { presenter.currentSpecOrNull()?.displayId }.getOrNull() ?: -1
            } else {
                -1
            },
            shown = clusterUiShown,
        )
    }

    override fun setProjectionEnabled(enabled: Boolean): Boolean {
        if (immersiveModeForConnection) {
            // No instrument window or OEM mode request in this path.
            projectionWanted = true
            airPlaySession?.let(::ensureClusterContentSent)
            return (airPlaySession?.clusterStream ?: 0) > 0
        }
        projectionWanted = enabled
        if (!enabled) {
            // 【PATCH-0121 ①】收屏时**补发 stopUI**。此前这一步只拆车机窗口，而"拆窗"
            // 只是车机侧不显示了 —— **没人告诉 iPhone 停下**，它继续在这条 111 流上画
            // ⇒「导航结束了、iPhone 还在画」，残影与"回不去 / 状态分叉"都从这儿来。
            //
            // 顺序要紧：**先停手机，再拆车机窗口**。反过来的话窗口一拆、上层就认为
            // "已经没在投"，这条命令再没有机会发出。
            //
            // 只在**确实投着**时才发，保持幂等（重复收起不再刷一条 stopUI）。
            // ⚠️ 判据**不**用 `clusterUiShown`：本树里只有 `toggleProjectionUi()` 会把它
            // 置 true，而那条链全工程没有调用点（PATCH-0100 起悬空）⇒ 它恒为 false，
            // 拿它当闸门等于永不发 stopUI。这里问**会话侧的事实**：`clusterUrl()` 是
            // "命令成功才写"的已投 URL（`AirPlaySession.ClusterContent.displayed`），
            // 非 null = 手机此刻确实有内容在屏上；没推过 showUI / 流已断时恒为 null。
            clusterUiShown = false
            // 【PATCH-0121 ③ 的"同步复位"落在这里】两条窗口路径的"iPhone 画不画"镜像
            // 一起复位：平台独占路径读 `clusterUiShown`，通用 Android 路径读
            // `projectionController.isUiShown()`，两者都喂给 `projectionState.shown`
            // （设置页「投屏状态」与画面页那个按钮的判据）。只复位一半，另一条路径的
            // 界面就停在"投屏中"。
            projectionController.setUiShown(false)
            // iPhone may draw initialURL as soon as stream 111 is established,
            // even when DBPlay never sent showUI and clusterUrl() remains null.
            val phoneStreamReady = (airPlaySession?.clusterStream ?: 0) > 0
            val stopSent = if (phoneStreamReady && !settings.hudProjectionEnabled) {
                // `== true` 不能省：`airPlaySession` 可空，直接 `getOrDefault`
                // 拿到的是 `Result<Boolean?>`，类型上过不去（方案原文那行也在这里）。
                runCatching { airPlaySession?.setClusterUiShown(false) == true }.getOrDefault(false)
            } else false
            val windowOk = projectionController.setEnabled(false)
            if (phoneStreamReady && !settings.hudProjectionEnabled && !stopSent) {
                platform.diagnostics.warn(TAG, "仪表投屏：stopUI 未发出（手机侧可能仍在绘制）")
            }
            // 手机已被叫停 ⇒ 把「这条流上已经推过 showUI」的记账一并清掉。
            // 不清的话 `ensureClusterContentSent()` 会按"流号 + URL 都没变"直接跳过，
            // 于是**下一次开启**不再推 showUI（手机不肯再画），窗口建起来却没有画面。
            // stopUI 的语义正是"**保留 111 流**、只让手机别画"，流的编号不变，
            // 所以这里必须显式作废这条判据。
            clusterContentUrlStream = 0
            clusterContentUrlSent = null
            return windowOk
        }
        val session = airPlaySession
        if (session == null) {
            platform.diagnostics.log(TAG, "仪表投屏：尚无 CarPlay 会话，无法开始")
            return false
        }
        if (session.clusterStream <= 0) {
            // 手机还没开第二路流。此时开窗没意义（会是一块透明空窗盖在仪表上）。
            platform.diagnostics.log(TAG, "仪表投屏：等待 iPhone 建立仪表屏流（clusterStream=0）")
            return false
        }
        // 流已经在了：把「仪表上画什么」推给手机（每条 111 流只推一次）。
        ensureClusterContentSent(session)
        if (platformOwnsClusterWindow) {
            // 窗口不归本桥建 —— 平台 presenter 已在主屏 Surface 就绪时挂好。
            // 这里只把"窗口在不在"汇报上去，**绝不另建一个**。
            val attached = platform.cluster?.isWindowAttached() == true
            platform.diagnostics.log(
                TAG,
                "仪表投屏：副屏窗口归平台层（CarClusterPresenter），当前" +
                    if (attached) "已挂载" else "尚未挂载（等主屏 Surface 就绪）",
            )
            return attached
        }
        val ok = projectionController.setEnabled(true)
        platform.diagnostics.log(TAG, "仪表投屏：建立窗口 ${if (ok) "成功" else "失败"}")
        return ok
    }

    override fun setProjectionContent(url: String): Boolean =
        airPlaySession?.setClusterUrl(url, send = projectionWanted) ?: false

    override fun zoomProjectionMap(zoomIn: Boolean): Boolean =
        airPlaySession?.changeMapZoomLevel(zoomIn) ?: false

    override fun toggleProjectionUi(): Boolean {
        val session = airPlaySession ?: return false
        if (session.clusterStream <= 0) return false
        val current = if (platformOwnsClusterWindow) clusterUiShown else projectionController.isUiShown()
        val next = !current
        val sent = session.setClusterUiShown(next)
        if (sent) {
            clusterUiShown = next
            projectionController.setUiShown(next)
        }
        platform.diagnostics.log(TAG, "仪表投屏：切换显示 -> $next（命令${if (sent) "已发" else "未发"}）")
        return next
    }

    /**
     * 会话就绪后补做投屏。
     *
     * 场景：用户先开了开关，但那时手机还没建流；等流来了要自动接上，
     * 否则用户得再点一次开关 —— 那是可以避免的困惑。
     */
    private fun reapplyProjection() {
        if (settings.immersiveNavigationEnabled || immersiveModeForConnection) return
        if (!projectionWanted) return
        runCatching { setProjectionEnabled(true) }
    }

    /**
     * 把仪表内容 URL 推给 iPhone：`showUI` + `forceKeyFrame`，**每一条 111 流只推一次**。
     *
     * ## 为什么必须推（2026-10-11，真车日志 1791680035426）
     *
     * `/info` 的副屏声明里**不带 `initialURL`** 时，iPhone 照样把 111 流建起来、
     * 照样推帧 —— 但推的是**黑帧**。真车现象逐字吻合：窗口建了、`Surface` 交给
     * ALT(111)、`SETUP stream type=111` 成功、`帧更新 #1..#40` 之后**彻底安静**，
     * 集群解码器随之停摆（主屏解码器仍 40fps）⇒「仪表盘屏幕是对的，但没有内容」。
     *
     * `CarPlayConfigBuilder.toAirPlayDisplay` 现在已给声明带上 `initialURL`（主修法）；
     * 这里再补一次**主动 `showUI`** 作为兜底 —— 不同 iOS 版本对 `initialURL` 的处理
     * 并不完全一致，而 `showUI` + `forceKeyFrame` 是上游同款、且本项目已有单测钉住的路径。
     *
     * ## 为什么要"每条流只推一次"
     *
     * 本方法会被 `setProjectionEnabled` / [reapplyProjection] 反复走到（状态刷新、
     * 重连、用户点开关）。`showUI` 每次都带 `forceKeyFrame`，无脑重发等于每秒钟逼
     * 手机重发 I 帧 —— 那是明摆着的浪费。用流号做代次：换流（`SETUP` 重来）才允许再推。
     */
    private fun ensureClusterContentSent(session: AirPlaySession) {
        if (!projectionWanted) return
        val stream = session.clusterStream
        if (stream <= 0) return
        // 2026-10-11：内容 URL 与 `CarPlayConfigBuilder` 同源（同一个 `contentUrl()`），
        // 否则「声明里是 M / showUI 推的是 N」会让 iPhone 反复换内容。
        val url = CarPlayClusterDisplay.contentUrl(
            showNavigationCard = if (immersiveModeForConnection) immersiveConfigForConnection.immersiveNavigationCardEnabled else settings.clusterNavigationCard,
            alignment = if (immersiveModeForConnection) immersiveConfigForConnection.immersiveNavigationAlignment else settings.clusterAlignment,
            mapLayoutTrial = if (immersiveModeForConnection) immersiveConfigForConnection.immersiveNavigationMapLayout else settings.clusterMapLayoutTrial,
        )
        // 流号与 URL 都没变才跳过（见 [clusterContentUrlSent]）。
        if (stream == clusterContentUrlStream && url == clusterContentUrlSent) return
        val sent = runCatching { session.setClusterUrl(url, send = true) }.getOrDefault(false)
        // 只在真的发出去之后才记账：失败（比如流刚断）下次还要接着试。
        clusterContentUrlStream = if (sent) stream else 0
        clusterContentUrlSent = if (sent) url else null
        platform.diagnostics.log(
            TAG,
            "仪表内容：showUI url=$url → ${if (sent) "已发" else "未发"}（clusterStream=$stream）",
        )
    }

    /**
     * PATCH-0025：会话建立后按落盘开关恢复投屏，**等第二路流就绪**再开窗。
     *
     * ## 为什么要重试而不是调一次
     *
     * iPhone 的第二路屏流（SETUP type=111）通常在会话 active **之后**才到。
     * 而 [setProjectionEnabled] 在 `clusterStream <= 0` 时会直接返回 false
     * （有意为之：流没来就开窗只会留一块透明空窗盖在仪表上）。
     * 所以这里按 1 秒间隔试到 20 秒——超出就放弃，交给用户手动再点。
     *
     * 一旦开窗成功（或意图被撤下）即停。
     */
    private fun scheduleReapplyProjection() {
        if (!projectionWanted) {
            scheduleStopUnrequestedClusterUi()
            return
        }
        // 2026-10-08：平台层独占窗口时**不做**这个重试。
        //
        // 这个循环存在的唯一目的是"等第二路流来了再建窗"。窗口归平台层后，
        // 建窗由 `CarAppController` 在主屏 Surface 就绪时完成，与流无关；
        // 继续重试只会每秒打一条"等待 iPhone 建立仪表屏流"，把日志刷满
        // （诊断包里 14:08:20~14:08:39 那 20 条就是这么来的）却什么也不做。
        if (platformOwnsClusterWindow) return
        // Generic Android/tablets have no car-owned instrument window. Immersive 111
        // belongs to MAIN, with content sent by the existing workExecutor callbacks.
        // Never run the legacy main-thread instrument restore timer for that surface:
        // setProjectionEnabled -> showUI writes a socket and closes the session on StrictMode.
        if (settings.immersiveNavigationEnabled || immersiveModeForConnection) {
            platform.diagnostics.log(TAG, "通用平台沉浸111：跳过仪表恢复定时器，沿用主屏后台内容发送")
            return
        }
        val deadline = android.os.SystemClock.elapsedRealtime() + 20_000L
        val tick = object : Runnable {
            override fun run() {
                if (!projectionWanted || closed) return
                if (projectionController.state().attached) return
                val ok = runCatching { setProjectionEnabled(true) }.getOrDefault(false)
                if (ok) return
                if (android.os.SystemClock.elapsedRealtime() < deadline) {
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this, 1_000L)
                } else {
                    platform.diagnostics.log(
                        TAG,
                        "仪表投屏：20 秒内未等到 iPhone 第二路流，放弃自动恢复（可手动再开）",
                    )
                }
            }
        }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(tick, 1_000L)
    }

    /** A disabled window is not proof that the phone stopped its initialURL UI.
     * Watch the late 111 SETUP for a bounded period and explicitly stop it once.
     * Network commands run on the worker, never the main thread.
     */
    private fun scheduleStopUnrequestedClusterUi() {
        val expected = airPlaySession ?: return
        val deadline = android.os.SystemClock.elapsedRealtime() + 20_000L
        val check = object : Runnable {
            override fun run() {
                if (closed || airPlaySession !== expected || projectionWanted ||
                    settings.hudProjectionEnabled) return
                workExecutor.execute {
                    if (closed || airPlaySession !== expected || projectionWanted ||
                        settings.hudProjectionEnabled) return@execute
                    val ready = expected.clusterStream > 0
                    val sent = ready && runCatching { expected.setClusterUiShown(false) }.getOrDefault(false)
                    if (sent) {
                        platform.diagnostics.log(TAG, "仪表投屏：总开关关闭，已向手机发送 stopUI（含初始副屏内容）")
                    } else if (android.os.SystemClock.elapsedRealtime() < deadline) {
                        mainHandler.postDelayed(this, 500L)
                    } else {
                        platform.diagnostics.warn(TAG, "仪表投屏：关闭校验到期，streamReady=$ready stopUI=$sent")
                    }
                }
            }
        }
        mainHandler.post(check)
    }

    // ------------------------------------------------------------------ internals

    private fun startInternal() {
        if (closed) return
        try {
            // DISABLE_ADAPTER 策略下，上一无线会话可能刚请求恢复蓝牙：enable() 返回 true
            // 仅代表请求受理，不代表 STATE_ON。进入 upstream wireless bring-up 前等待本 App
            // 关闭的适配器实际恢复，避免重连/切模式后马上报 "Bluetooth is not enabled"。
            if (settings.isWireless &&
                settings.bluetoothHandoffPolicy == CarBluetoothHandoffPolicy.DISABLE_ADAPTER &&
                !bluetoothHandoffReleaser.restoreBeforeWirelessStart()
            ) {
                val reason = "蓝牙适配器正在恢复但尚未就绪。请等待蓝牙开启后再连接；DBPlay 不会关闭用户自行关闭的蓝牙。"
                platform.diagnostics.warn(TAG, "session refused: bluetooth handoff restoration did not reach STATE_ON")
                failStart(reason)
                return
            }

            // 无线起栈前把「适配器 on 但配对表还是空」这一瞬间的空窗吃掉：
            // 上游第一步就要从已配对设备里找 PHONE_SMART，拿不到会直接抛
            // `No bonded PHONE_SMART devices found`，现场表现是「第一次连不上、重进 App 才行」。
            // 只等不拦：超时照样继续，native 会给真实错误。
            if (settings.isWireless) {
                val bt = runCatching { bluetoothHandoffReleaser.awaitBondedPhoneForWirelessStart() }
                    .getOrNull()
                platform.diagnostics.log(
                    TAG,
                    "蓝牙就绪：适配器=${bt?.adapterOn} 已配对=${bt?.bondedCount} " +
                        "等待=${bt?.waitedMs}ms 超时=${bt?.timedOut}",
                )
            }

            // 拉黑拦截：在**建栈最开始**打一次心跳，问服务端"本机还能不能用"。
            //
            // 放在这里而不是 `start()` 里：这是一次网络往返，必须待在 [workExecutor]
            // 上（`start()` 的同步段跑在主线程）。
            //
            // 结果里只有 `Blocked` 才拦——`Unknown`（连不上服务端且本地无标记）
            // 必须放行：车机常年没网，把"没问到"当成"已拉黑"等于整个车队瘫痪。
            // 判定策略全在 `resolveBlockStatus` 里，那边有单测。
            when (val status = deviceStatus.sync()) {
                is CarBlockStatus.Blocked -> {
                    // 复用现成的「连接失败」卡片。文案直接用服务端那句（管理员填的原因），
                    // 它本身就是面向用户的中文，首页一眼能看到该找谁，不必再过 `CarErrorText`。
                    platform.diagnostics.warn(
                        TAG,
                        "session refused: device blocked " +
                            "(reason=${status.reason}, fromCache=${status.fromCache})",
                    )
                    failStart(status.reason)
                    return
                }

                // Clear / Unknown / NoIdentity 一律放行。
                // `Unknown` 是"连不上服务端、本地也没有拉黑记录"——车机常年没网，
                // 把"没问到"当成"已拉黑"等于让整个车队瘫痪（与服务端
                // "任何异常都回 blocked=false" 是同一条约定）。
                else -> Unit
            }
            val handle = buildSession()
            activeHandle = handle
            // Covers a setting saved while buildSession was constructing the guard.
            handle.focusGuard?.setNavigationAudioFocusEnabled(settings.navigationAudioFocusEnabled)
            handle.controller.start()
        } catch (_: StaleBuildException) {
            // 被更新的 stop / reconnect 作废：什么都还没建出来（检查点在热点解析之后、
            // 本地 MFi / 音频焦点 / sink 之前），状态由接手的那一轮或 stop() 去改，这里不碰。
            platform.diagnostics.log(TAG, "session build superseded by a newer stop/reconnect; abandoned")
            // TCAM 附着器是在检查点之前创建的，这一轮被作废后它不会再有人认领，
            // 必须就地收掉，否则会留下一个"链路变化 → 重连"的幽灵回调。
            closeTcamHotspot()
        } catch (error: CarHotspotUnavailableException) {
            // 这一条的文案**是我们自己写的中文**（`ensureAdbHotspot` 里那句
            // "请打开车机自带热点…"），刻意**不过** [CarErrorText]——那张表是
            // "上游英文 → 中文"，中文喂进去只会撞到兜底，把具体原因吃掉。
            platform.diagnostics.error(TAG, "no usable hotspot for this session", error)
            failStart(error.message ?: CarErrorText.FALLBACK)
        } catch (error: Throwable) {
            platform.diagnostics.error(TAG, "failed to start CarPlay session", error)
            // 上游的 `error.message` 是英文（`require { }` 文案），而 errorMessage
            // 会直接变成首页「连接失败」的副标题 ⇒ 必须过一遍中文层。
            // 原始英文仍然进了上面的 diagnostics.error，诊断页查得到。
            failStart(
                CarErrorText.of(error.message) ?: error.javaClass.simpleName,
            )
        }
    }

    /**
     * 会话没建起来：清句柄、把原因落到首页「连接失败」、广播一条事件。
     *
     * 抽出来是因为现在有**两条**失败路径（上游英文 / 我们自己的中文），
     * 而"失败长什么样"必须完全一致——否则两条路会在界面上表现成两种东西。
     */
    private fun failStart(reason: String) {
        activeHandle = null
        // 建栈失败时把这次半途创建的 TCAM 附着器一并收掉：
        // 它可能已经注册了 NetworkCallback，留着会在"会话根本没起来"的情况下继续触发重连。
        closeTcamHotspot()
        updateState {
            it.copy(
                phase = CarSessionPhase.FAILED,
                detail = "启动失败：$reason",
                errorMessage = reason,
            )
        }
        emit(CarSessionEvent.TransportError(reason))
    }

    /**
     * 方案是 [CarHotspotMode.MANUAL]（「复用车机热点」）时，先**快速**问一次
     * "车机自带热点现在开着吗"，明确读到"关着"就立刻带着可执行文案失败。
     *
     * ## 为什么必须有这一步
     *
     * 这一支原先**什么检查都不做**：热点关着时整条链路照样往下走，最后在上游
     * `ManualHotspotManager.start()` 里空转 `HOTSPOT_START_TIMEOUT_MILLIS`
     * 数十秒，抛一句 `Timed out … local hotspot interface was not found`。
     * 那句话**没有告诉用户下一步做什么**（去车机设置里把热点打开），
     * 而且这几十秒里界面停在「无线通道就绪」，看起来像卡死。
     *
     * 2026-10-08 移植自 DiPlay：`CarPlayController.startWirelessHotspot` 在
     * `MANUAL` 分支上先读 `CarHotspotStatus.isEnabled()`，读到 `false` 就抛
     * *"The car hotspot is off. Turn it on in the car settings and connect again."*
     *
     * ## 只拦「复用车机热点」
     *
     * 判据是 [CarHotspotPlan.mode]（二开层自己的四态枚举），**不是**上游那个
     * 被压扁成 `MANUAL` 的 `WirelessHotspotMode`——自建热点也映射到上游 `MANUAL`，
     * 但它那张网是**我们自己起的**，车机开关关着才正常，绝不能拦。
     * 具体判据见 [CarManualHotspotPrecheck.blocker]。
     *
     * ## 宁可放过，不可错杀
     *
     * 只有 [CarHotspotEnabledState.OFF] 才拦。并且额外加一道**旁证**：
     * 若本地反射明确看到一张**在跑**的车机热点（[CarNativeHotspotInfo.actuallyRunning]，
     * 要求网卡与地址都读到），则不拦——三种隐藏 API 读数在个别 ROM 上会一起失灵，
     * 那正是"错杀一张能用的热点"的高发场景。旁证只用来**放行**，从不用来拦截。
     */
    private fun ensureManualHotspotReady(plan: CarHotspotPlan): CarHotspotPlan {
        if (plan.mode != CarHotspotMode.MANUAL) return plan

        // 分段耗时（2026-10-13，日志 8）：复用预检固定 5.37 秒，时间戳间隔 1.0/2.0/1.0 秒，
        // 与"每次网卡枚举约 1 秒、共 5 次"吻合——但那是从时间戳推出来的，没直接量过。
        // 这里按段记下来，下一个诊断包就能直接核对，不必再倒推。
        val precheckStartedAt = SystemClock.elapsedRealtime()
        var lastMark = precheckStartedAt
        val timings = StringBuilder()
        fun mark(label: String) {
            val now = SystemClock.elapsedRealtime()
            timings.append(label).append('=').append(now - lastMark).append("ms ")
            lastMark = now
        }

        val ecarxSnapshot = if (plan.reused) {
            CarHotspotEnabledReader.readEcarxSnapshot(appContext)
        } else {
            null
        }
        mark("ecarxSdk")
        // 整个预检只枚举这一次网卡，下面所有需要网卡的判据共用这份快照
        // （原来要枚举 5 次，真车每次约 1 秒）。几个调用之间相隔毫秒级，状态不会变。
        val interfaces = runCatching { CarNetworkInterfaces.snapshot(appContext) }.getOrDefault(emptyList())
        mark("ifaces")
        val ecarxDecision = if (plan.reused) {
            EcarxHotspotAttachmentPolicy.resolve(
                snapshot = ecarxSnapshot ?: EcarxHotspotReader.Snapshot(),
                sdkInt = Build.VERSION.SDK_INT,
                model = Build.MODEL,
                device = Build.DEVICE,
                interfaces = interfaces,
            )
        } else {
            null
        }
        if (plan.reused && ecarxDecision != null) {
            EcarxHotspotSessionObservation.record(
                snapshot = ecarxSnapshot,
                configuredHost = settings.manualHotspotHostAddress,
                decision = ecarxDecision,
            )
        }
        val effectivePlan = ecarxDecision?.endpoint?.let { endpoint ->
            plan.copy(
                manualHostAddress = endpoint.address,
                manualHostInterfaceName = endpoint.interfaceName,
            )
        } ?: plan
        if (plan.reused) {
            val configured = settings.manualHotspotHostAddress.trim().ifEmpty { "(未设置)" }
            val endpoint = ecarxDecision?.endpoint
            platform.diagnostics.log(
                TAG,
                "Ecarx hotspot endpoint: reason=${ecarxDecision?.reason} " +
                    "configuredHost=$configured routeSource=${endpoint?.address ?: "(none)"} " +
                    "interface=${endpoint?.interfaceName ?: "(none)"} " +
                    "sdkEnabled=${ecarxSnapshot?.enabled} " +
                    "routeStatus=${ecarxSnapshot?.routeStatus ?: "unavailable"}",
            )
        }

        val strict = CarHotspotEnabledReader.read(
            appContext,
            ecarxSnapshot,
            ecarxPlatform = plan.reused && EcarxClientRoute.isEcarxPlatform(
                Build.VERSION.SDK_INT,
                Build.MODEL,
                Build.DEVICE,
            ),
        )
        // A validated, current Ecarx client route is positive evidence even where Android SoftAP
        // reports the external T-Box AP as disabled. Generic interface-up evidence remains only
        // a non-Ecarx fallback and never selects an endpoint by itself.
        // 只读一次：下面的"在跑旁证"与连接前的热点名核对（reconcileReusedSsid）共用这份读数。
        mark("decide+enabled")
        val localSoftAp = runCatching { LocalSoftApReader.read(appContext, interfaces) }.getOrNull()
        mark("softAp")
        val localSoftApRunning = localSoftAp?.actuallyRunning ?: false
        val ecarxRouteRunning = ecarxDecision?.endpoint != null
        val ecarxPlatform = plan.reused && EcarxClientRoute.isEcarxPlatform(
            Build.VERSION.SDK_INT,
            Build.MODEL,
            Build.DEVICE,
        )
        val corroborated = strict == CarHotspotEnabledState.OFF &&
            (if (ecarxPlatform) ecarxRouteRunning else localSoftApRunning)
        val effective = if (corroborated) CarHotspotEnabledState.ON else strict

        val blocker = CarManualHotspotPrecheck.blocker(
            mode = effectivePlan.mode,
            state = effective,
            ssid = effectivePlan.ssid,
            // A verified Ecarx route supplies the local AirPlay endpoint for this session only;
            // otherwise the existing user-entered address remains authoritative.
            hostAddress = effectivePlan.manualHostAddress,
            // ⚠️ 只对 MANUAL 传 true。TCAM 档的 reused 同样是 true，但它的接入卡与
            // 地址由 `TcamHotspotManager` 连接时自动识别（`manualHostAddress` 那时才回填），
            // 照 reused 传会让预检误报复用模式需要填地址。
            reuseRequested = effectivePlan.mode == CarHotspotMode.MANUAL,
        )
        if (blocker == null) {
            // ⚠️ 2026-10-11：复用模式下，凭据"还是我们自己的出厂默认"是最常见、也最隐蔽的阻塞项。
            //
            // 真车日志（设备 67b3c24af6b5，08:57）：`advertise SSID="Lynk" passphraseLength=8`，
            // 而诊断包里 `manualHotspotPassphrase: 8 字符，**出厂默认**` —— 也就是说我们
            // 把 **DBPlay 的出厂密码**告诉 iPhone，让它去连车机的「Lynk」。
            // 密码不对 ⇒ 关联不上 ⇒ 40 秒 `attach timed out`，而现场看不出这句原因。
            //
            // 这里**只告警、不拦截**（与"宁可放过不可错杀"一致：车机热点密码**有可能**
            // 就是 12345678）。要真正自动，就得用 ADB 工具读车机热点凭据（待定）。
            // 车机热点的真实密码读得到时，本次会替换这个出厂默认（见 reconcileReusedPassphrase），
            // 此时再喊"密码是出厂默认"就是错话，所以只在**替换不了**时才告警。
            if (plan.passphrase == CarSessionSettings.DEFAULT_HOTSPOT_PASSPHRASE &&
                CarManualHotspotMatch.reconcilePassphrase(plan.passphrase, localSoftAp?.passphrase) == null
            ) {
                val warning = "复用车机热点：密码仍是 DBPlay 的出厂默认（12345678）—— " +
                    "若「${plan.ssid}」不是用它加密的，iPhone 会关联不上（现象就是 40 秒连接超时）。" +
                    "请在热点身份里填车机热点的真实密码。"
                platform.diagnostics.log(
                    TAG,
                    "manual hotspot precheck warn: passphrase is DBPlay factory default (reuse mode)",
                )
                // 2026-10-13（PATCH-0116）：上面那句告警有**三种**互不相同的成因，而
                // "到底是哪一种"直接决定后面往哪查 —— 日志 9 就卡在这里：分不清是
                // "车机没返回密码"还是"车机的密码本来就是它"，于是不知道该去修密码读取，
                // 还是该去查关联/网络路径。补一行**可判定**的读数（密码本身仍不入日志）。
                platform.diagnostics.log(
                    TAG,
                    "manual hotspot precheck: car passphrase " +
                        CarManualHotspotMatch.describeCarPassphraseRead(localSoftAp?.passphrase),
                )
                emit(CarSessionEvent.LogLine(warning))
            }
            // 2026-10-11：复用分支的**前置事实**都写进日志（或直接据此拦下）。
            //
            // ① 「车机热点网口」识别结论 —— 复用能不能通全看它（真车 08:57 那次把自建残留
            //    的 192.168.176.1 当成车机热点地址通告出去 ⇒ 40 秒 attach 超时，
            //    而日志里当时根本没有"认的是哪张网口"这条信息）。
            // ② 网上是否还躺着我方候选网段的地址（= 自建残留）—— **只告警**：
            //    地址已经由用户填、以它为准（PATCH-0087），残留不再改变我们通告什么。
            // ③ 用户填的那个地址**此刻在不在网卡上** —— 不在就拦，这才是复用真正的判据
            //    （"不是我认不出来"，而是"你填的这个地址现在没人承载"）。
            //
            // 残留判据：先认「**我们自建热点实际配上去的那个地址**」
            // （`CarAdbHotspotState.hostAddress`，最准）；足迹丢失（进程被杀过）时退回
            // 「我方候选网段里的地址」—— 车主口径本车车机固定 192.168.15.0/24，
            // 与我们的候选池（175~179 / 172.20 / 10.42）不相交 ⇒ 按池判不会误伤车机。
            val selfBuiltAddress = runCatching { adbHotspot?.state()?.hostAddress }
                .getOrNull()?.trim().orEmpty()
            // 两种来源都算残留：① 落盘足迹里的那个地址（最准）；② 我方候选网段里的地址
            // （足迹丢失后仍有效 —— 本车车机热点固定 192.168.15.0/24，与我们的池不相交）。
            val residue = (if (selfBuiltAddress.isNotEmpty()) {
                CarNetworkInterfaces.addressPresentAnywhere(appContext, selfBuiltAddress, interfaces)
            } else {
                null
            }) ?: CarNetworkInterfaces.selfPoolAddressAnywhere(appContext, interfaces)
            mark("residue")
            if (residue != null) {
                val (iface, address) = residue
                val message = "提示：网卡 $iface 上还留着我方自建热点的地址 $address。" +
                    "本次通告给 iPhone 的是你填的车机热点地址，不受它影响；" +
                    "但建议把热点方式切回「自建热点」再切回复用，让残留被拆掉。"
                platform.diagnostics.log(
                    TAG,
                    "manual hotspot precheck warn: self residue $address on $iface",
                )
                emit(CarSessionEvent.LogLine(message))
            }

            // ⚠️ 2026-10-11（PATCH-0087）：**复用的地址以用户填的为准**（不再自动识别）。
            //
            // 车主口径：*"复用车机热点是要用户自己手动设置，而不是自动识别"*。
            // 上一版（PATCH-0086）是"我们认网口，认不出就拦"—— 方向反了：认错的两次
            // （2026-10-11 的 `1791624952142` 与 `1791702691839`）都是我们**猜到了自己
            // 自建热点的残留地址**。现在只做一件确定的事：**核对你填的那个地址在不在网卡上**。
            //
            // - 在 ⇒ 放行，并把"用你填的地址 X（网卡 Y）"写进日志（上游据此 attach，
            //   不用再按网卡名挑，见 `ManualHotspotManager.hostAddressOverride`）；
            // - 不在 ⇒ 当场拦下：这是**可执行的**结论（去车机设置里把热点打开，或核对地址），
            //   而不是让 iPhone 白等 40 秒。
            //
            // `plan.reused` 为假的分支（自建 / 局域网）不走这里：
            // 只有「复用」这一支才会带 `plan.manualHostAddress`。
            val manualAddress = effectivePlan.manualHostAddress
            if (effectivePlan.reused && manualAddress != null) {
                val manualInterface = effectivePlan.manualHostInterfaceName
                val currentInterfaces = interfaces
                val onInterface = if (manualInterface != null) {
                    currentInterfaces.singleOrNull { iface ->
                        iface.name == manualInterface && iface.isUp &&
                            iface.addresses.any { it.trim() == manualAddress }
                    }?.let { it.name to manualAddress }
                } else {
                    CarNetworkInterfaces.addressPresentAnywhere(appContext, manualAddress, interfaces)
                }
                // 🔴 2026-10-12（日志 4 排查）加的**秒级闸门**。
                //
                // 过去这里只在"地址根本不在任何网卡上"时才拦（`onInterface == null`），
                // 可 `198.18.34.15` 挂在有线口 `eth0.11` 上 ⇒ 拦不到 ⇒ 流程滑到上游
                // `ManualHotspotManager`，而它把 `eth*` 全排除 ⇒ 每 2.27 秒刷一行
                // "was not found on any interface"，60 秒后抛一句对现场毫无信息量的英文。
                //
                // 更糟的是**自相矛盾**：上面那行日志刚打了
                // `manual hotspot endpoint: source=user configuration … interface=eth0.11`
                // （分明是"找到了"），用户侧却要干等一分钟。
                //
                // 判据本身不改变"什么算可用"（那仍由上下游各自的读取决定），
                // 只把已经知道的结论**提前说出来**。详见 `CarManualEndpointGate`。
                val gateVerdict = CarManualEndpointGate.evaluate(
                    address = manualAddress,
                    interfaces = currentInterfaces,
                    // 非空 ⟺ Ecarx SDK 亲眼看到客户端 ⇒ 闸门永不拦。
                    verifiedRouteInterface = manualInterface,
                )
                // 🔴 2026-10-13（日志 7）：Ecarx 上 iPhone 还没入网时 SDK 看不到客户端
                // （NO_CURRENT_CLIENT），闸门会把"只挂有线口"的 198.18.x.x 整个拦下，
                // 0x5703 因此没发出去，用户只能先手动连车机热点。放行条件见
                // `EcarxHotspotAttachmentPolicy.endpointAwaitingClient`；只放宽 WIRED_ONLY 这一种，
                // 地址不在本机 / 自建残留 / 网关地址等其它拦截一律照旧。
                val endpointVerdict = if (
                    gateVerdict is CarManualEndpointVerdict.WIRED_ONLY &&
                    EcarxHotspotAttachmentPolicy.endpointAwaitingClient(
                        decision = ecarxDecision,
                        address = manualAddress,
                        interfaces = currentInterfaces,
                        sdkInt = Build.VERSION.SDK_INT,
                        model = Build.MODEL,
                        device = Build.DEVICE,
                    )
                ) {
                    platform.diagnostics.log(
                        TAG,
                        "manual hotspot endpoint: Ecarx has no client yet (NO_CURRENT_CLIENT); " +
                            "advertising local endpoint $manualAddress so the iPhone can join with the " +
                            "credentials in 0x5703 (wired interfaces=${gateVerdict.interfaces})",
                    )
                    CarManualEndpointVerdict.OK
                } else {
                    gateVerdict
                }
                if (onInterface == null || endpointVerdict != CarManualEndpointVerdict.OK) {
                    val line = runCatching {
                        CarNetworkInterfaces.debugLine(
                            appContext,
                            listOfNotNull(selfBuiltAddress.takeIf { it.isNotEmpty() }),
                            interfaces,
                        )
                    }.getOrElse { "车机本机网卡快照: 读取失败（${it.javaClass.simpleName}）" }
                    val ecarxReason = ecarxDecision?.reason?.name ?: "not_applicable"
                    // Ecarx 平台 + 地址只在有线口上：这是日志 4 里最常见、也最容易被
                    // 误判成"地址填错了"的一种。文案要点明"只在内部有线网口"，
                    // 并给出两条可操作出路（先连手机 / 或改用自建热点）。
                    val wiredOnly = endpointVerdict as? CarManualEndpointVerdict.WIRED_ONLY
                    val message = when {
                        manualInterface != null -> "Ecarx 已验证的 CarPlay 地址 $manualAddress 不再位于接口 $manualInterface（或接口已关闭）；" +
                            "为避免把错误地址发给 iPhone，本次停止连接。请确认网络后重试。" +
                            "（route 状态：$ecarxReason；诊断：$line）"

                        // 2026-10-13：自建池地址是**我们自己的**网段（175~179 / 172.20 / 10.42），
                        // 车机热点不在那里 —— 真车 08:57 把它通告出去，iPhone 白等 40 秒。
                        // 放在有线判定之前：它挂在哪张卡上都不改变"这不是车机热点"。
                        endpointVerdict == CarManualEndpointVerdict.SELF_POOL ->
                            "复用车机热点：地址 $manualAddress 是 DBPlay「自建热点」的网段地址" +
                            "（自建残留），不是车机热点的地址——把它通告给 iPhone 必然连不上。" +
                            "请先把热点方式切回「自建热点」再切回复用以清理残留，" +
                            "再到设置里点选「本机 CarPlay 地址」的候选。（诊断：$line）"

                        wiredOnly != null &&
                            EcarxClientRoute.isEcarxPlatform(
                            Build.VERSION.SDK_INT,
                            Build.MODEL,
                            Build.DEVICE,
                        ) -> "复用车机热点：地址 $manualAddress 当前只挂在车机内部有线网口" +
                            "（${wiredOnly.interfaces.joinToString("、")}）上，且车机 SDK 没有报告" +
                            "任何已连接的客户端（$ecarxReason）—— 无法确认它就是给 iPhone 的 " +
                            "CarPlay 通道，本次不再等待一分钟。请先把 iPhone 连上车机原厂热点后重试；" +
                            "若确认已连接仍出现这句，说明车机热点客户端读取失败，" +
                            "可改用 DBPlay 自建热点。（诊断：$line）"

                        wiredOnly != null -> "复用车机热点：地址 $manualAddress 当前只挂在有线网口" +
                            "（${wiredOnly.interfaces.joinToString("、")}）上，不能作为给 iPhone 的 " +
                            "Wi-Fi 通道。请核对车机热点地址后重试。（诊断：$line）"

                        EcarxClientRoute.isEcarxPlatform(
                            Build.VERSION.SDK_INT,
                            Build.MODEL,
                            Build.DEVICE,
                        ) -> "复用车机热点：设置中的 " +
                            "${settings.manualHotspotHostAddress.trim().ifEmpty { "(未设置)" }} " +
                            "是 T-Box 热点侧地址，不是 Android 本机接口上的 CarPlay endpoint；" +
                            "当前没有可用的 Ecarx 客户端路由（$ecarxReason），无法安全自动选址。" +
                            "请让手机连上车机原厂热点后重试，或核对车机网络。（诊断：$line）"

                        else -> "复用车机热点：地址 $manualAddress 当前不在任何本机网卡上。" +
                            "请检查车机热点地址并重试。（诊断：$line）"
                    }
                    platform.diagnostics.log(
                        TAG,
                        "manual hotspot precheck blocked: host=$manualAddress " +
                            "iface=${manualInterface ?: "(any)"} ecarxReason=$ecarxReason " +
                            "gate=$endpointVerdict",
                    )
                    emit(CarSessionEvent.LogLine(message))
                    throw CarHotspotUnavailableException(message)
                }
                val source = if (manualInterface != null) "Ecarx verified client route" else "user configuration"
                platform.diagnostics.log(
                    TAG,
                    "manual hotspot endpoint: source=$source address=$manualAddress " +
                        "interface=${onInterface.first} configured=${settings.manualHotspotHostAddress.trim().ifEmpty { "(unset)" }}",
                )
                if (manualInterface != null && settings.manualHotspotHostAddress.trim() != manualAddress) {
                    emit(
                        CarSessionEvent.LogLine(
                            "本次使用 Ecarx SDK 验证的本机 CarPlay 地址 $manualAddress（${onInterface.first}）。" +
                                "车机热点网关 ${settings.manualHotspotHostAddress.trim().ifEmpty { "未设置" }} 与本机服务地址不同，" +
                                "本次不把网关地址通告给 iPhone。",
                        ),
                    )
                }
            }
            platform.diagnostics.log(
                TAG,
                "manual hotspot precheck: state=$strict effective=$effective ssid=${effectivePlan.ssid} " +
                    "ecarxReason=${ecarxDecision?.reason ?: "not_applicable"}" +
                    "｜${CarNetworkInterfaces.debugLine(appContext, listOfNotNull(selfBuiltAddress.takeIf { it.isNotEmpty() }), interfaces)}",
            )
            mark("gate+log")
            platform.diagnostics.log(
                TAG,
                "manual hotspot precheck timing: $timings total=${SystemClock.elapsedRealtime() - precheckStartedAt}ms",
            )
            // 复用专属：通告给 iPhone 的热点名与车机存储配置名核对一次（见该函数的 KDoc）。
            return if (effectivePlan.reused) {
                // PATCH-0116：这一支接下来**必然要等 iPhone 从车机那张网络回连**
                // （40 秒 attach 窗口，超时后还会再来一轮）。开一个**只读、有界**的
                // 观测器，把"车机侧到底有没有客户端关联上来"按固定间隔写进日志 ——
                // 它是"iPhone 没关联上（密码/名字问题）"与"关联上了但到不了 endpoint
                // （路由问题）"之间**唯一**的分水岭，且**不需要手机侧信息**就能判。
                CarHotspotClientWatcher.start(
                    context = appContext,
                    diagnostics = platform.diagnostics,
                    tag = TAG,
                )
                reconcileReusedSsid(effectivePlan, localSoftAp?.ssid, localSoftAp?.passphrase)
            } else {
                effectivePlan
            }
        }
        platform.diagnostics.log(
            TAG,
            "manual hotspot precheck blocked: state=$strict corroborated=$corroborated ssid=${plan.ssid}",
        )
        emit(CarSessionEvent.LogLine(blocker))
        throw CarHotspotUnavailableException(blocker)
    }

    /**
     * TCAM 热点复用：连接时**自动识别接入卡（eth0.11）与地址**，回填到「复用」会话路径。
     *
     * 只对 [CarHotspotMode.TCAM_HOTSPOT] 生效（其余模式原样返回），因此与
     * [ensureManualHotspotReady] / [ensureAdbHotspot] 互不相交、顺序无副作用。
     *
     * ## 为什么回填的是 `manualHostAddress` / `manualHostInterfaceName`
     *
     * 这两个字段正是既有「复用」会话路径的落点（`CarPlayConfigBuilder` →
     * 上游 `ManualHotspotManager` 的 `hostAddressOverride` / `hostInterfaceOverride`，
     * 见 `CarPlaySessionBridge.kt:1328` 那一支）。TCAM 识别出的地址与网卡照填这两个字段，
     * 上游就会做"精确 iface+IP"校验后 attach —— **不另建并行会话栈**。
     *
     * ## 自愈
     *
     * manager 的 `onNetworkChanged`（`onLost` / 地址变化 → invalidated）回调到这里，
     * 直接 `reconnect()`：拆旧建新、重新 `start`。manager 与 [activeHandle] 同生命周期，
     * 会话结束 / 失败时由 [closeTcamHotspot] 回收，避免留下幽灵 NetworkCallback。
     *
     * ## 进程级网络绑定（2026-10-17，方案 A）
     *
     * 回包路径的钉钉子动作（`bindProcessToNetwork`）**在 [TcamHotspotManager] 内部**：
     * 它 `start()` 成功就 bind、`close()` 里 unbind ⇒ 解绑的覆盖面与"注销 NetworkCallback"
     * 完全一致（本方法首行的 [closeTcamHotspot] 以及 [startInternal] 的各条失败出口
     * 都会走到 `close()`）。这里**不再**自己调一次，避免两个"谁负责解绑"的源头；
     * 本方法只在 `start()` 成功后记一行"钉住的是哪条网络"，供真机核对回包路径。
     *
     * @throws 原样透传 [TcamHotspotManager.start] 的异常（热点未开 / 名字不一致 /
     *         链路启动期变化 / 60 秒超时等），由 [startInternal] 统一转成失败文案。
     */
    /**
     * 车机热点（[CarHotspotMode.GEELY_ROUTED]）的地址回填（2026-10-25 新增）。
     *
     * ## 与 [ensureTcamHotspotReady] 的关系
     *
     * 两支**互不调用、互不回退**：一支走 `eth0.11` + 反射读车机 AP 配置（TCAM），
     * 一支走任意 `eth*` + `198.18.x.x` + 用户手填凭据（本函数）。车主要求
     * 「新模式失败时不自动回退到 TCAM、自建热点或其他模式」，所以这里任何失败
     * 都直接抛，交给会话层展示，**不 catch 后换一条路**。
     *
     * ## 失败口径（5 条中文文案）
     *
     * 按扫描结果分阶段说清「卡在哪一步」，不用一句「连接失败」糊过去。
     */
    private fun ensureGeelyRoutedReady(plan: CarHotspotPlan): CarHotspotPlan {
        if (plan.mode != CarHotspotMode.GEELY_ROUTED) return plan

        // ⚠️ 2026-10-25（三版）：**这里不再扫地址、也不回填 manualHostAddress**。
        //
        // 上游 `GeelyRoutedHotspotManager`（`:shared`）已经自己扫 `eth*` 198.18，
        // 并且有自己的 60 秒预算与分阶段 IOException。若这里再扫一遍，
        // 最坏会变成「60 秒 × 2」，而且新 manager 根本不读 manualHostAddress。
        //
        // 本函数现在只做两件**快速失败**的事：机型门禁 + 凭据校验。
        // 这样用户在设置页点连接时能立刻看到中文原因，不必等 60 秒。
        val model = android.os.Build.MODEL
        val device = android.os.Build.DEVICE
        val sdk = android.os.Build.VERSION.SDK_INT
        if (!CarGeelyRoutedHost.usesRoutedHotspot(model, device, sdk)) {
            platform.diagnostics.log(
                TAG,
                "GEELY routed gate rejected: model=$model device=$device sdk=$sdk " +
                    "source=${CarGeelyRoutedInfo.SOURCE_TAG}",
            )
            throw IllegalStateException(
                "「车机热点」只支持吉利 KX11/CS11/CX11 的 Android 9（当前 model=$model device=$device sdk=$sdk）。" +
                    "请检查车机型号与系统版本；本产品不会回退到复用或自建热点。",
            )
        }

        val ssid = CarGeelyRoutedHost.normalizeSsid(plan.ssid.orEmpty())
        val reject = CarGeelyRoutedHost.validateCredentials(ssid, plan.passphrase, plan.security)
        if (reject != null) {
            platform.diagnostics.log(
                TAG,
                "GEELY routed credentials rejected: ${reject} source=${CarGeelyRoutedInfo.SOURCE_TAG}",
            )
            throw IllegalStateException(reject)
        }

        platform.diagnostics.log(
            TAG,
            "GEELY routed precheck ok: model=$model device=$device sdk=$sdk ssidSet=${ssid.isNotEmpty()} " +
                "（地址由上游 GeelyRoutedHotspotManager 扫描，预算 " +
                "${CarGeelyRoutedHost.SCAN_TIMEOUT_MILLIS / 1000}s）" +
                " source=${CarGeelyRoutedInfo.SOURCE_TAG}",
        )
        return plan
    }

    private fun ensureTcamHotspotReady(plan: CarHotspotPlan): CarHotspotPlan {
        if (plan.mode != CarHotspotMode.TCAM_HOTSPOT) return plan
        closeTcamHotspot()
        // PATCH-0136：attach 前先清自建残留。现场（VIN …368562 / 1.1.14）模式已是 TCAM，
        // 但 wlan0 还挂着自建地址 192.168.175.1 —— 复用会把我们的地址当成车机热点通告
        // 给 iPhone，结果到不了 AirPlay 7000。只在真的检出我们自己的地址时才动手。
        val selfBuiltAddress = runCatching { adbHotspot?.state()?.hostAddress }.getOrNull()
        if (!selfBuiltAddress.isNullOrBlank()) {
            platform.diagnostics.log(
                TAG,
                "TCAM 前置清理：检出自建残留 $selfBuiltAddress，先拆自建热点再 attach",
            )
            runCatching { adbHotspot?.stop() }
            runCatching { adbHotspot?.releaseLeftoverAddress() }.getOrNull()?.let { note ->
                platform.diagnostics.log(TAG, "TCAM 前置清理：$note")
            }
        }
        val manager = TcamHotspotManager(
            context = appContext,
            // PATCH-0136：复用档**不许**用设置里保存的 SSID/密码去校验。
            // 现场：模式切到 TCAM 后 `manualHotspotSsid` 还是自建档的 `DBPlay`，
            // TcamHotspotManager 直接抛 `Saved hotspot name differs from the active TCAM hotspot`，
            // 一次会话都没起来。原厂热点名只能由车机现场读取。
            savedSsid = null,
            savedPassphrase = null,
            onDiagnostic = { message -> platform.diagnostics.log(TAG, message) },
            onNetworkChanged = {
                platform.diagnostics.log(TAG, "TCAM hotspot link changed; restarting wireless session")
                emit(CarSessionEvent.LogLine("车机热点链路已变化，正在重新连接…"))
                reconnect()
            },
        )
        activeTcamHotspot = manager
        val info = try {
            manager.start(CarTcamHotspot.DEFAULT_TIMEOUT_MILLIS)
        } catch (error: Throwable) {
            activeTcamHotspot = null
            runCatching { manager.close() }
            throw error
        }
        // 只读诊断，不改行为：车机日志里要能一眼看到本次钉的是哪条网络。
        platform.diagnostics.log(
            TAG,
            "TCAM selected network=${manager.attachedNetwork} iface=${info.interfaceName} " +
                "host=${info.hostAddress?.hostAddress}",
        )
        // PATCH-0134：TCAM 复用档同样需要一份**只读**客户端数快照，否则 RFCOMM 早停
        // （PATCH-0133）在 TCAM 路径上永远拿到 null ⇒ 只能跑满 3 轮 × 2 台
        // （实测 398296 那台 6 连败 26.7s）。这里复用同一个观测器（它内部就是
        // `EcarxHotspotReader` 只读读数），不新建探针、不发任何命令；读不到时它自己
        // 不下结论，早停判据自然返回 false。
        CarHotspotClientWatcher.start(
            context = appContext,
            diagnostics = platform.diagnostics,
            tag = TAG,
        )
        return plan.copy(
            ssid = info.ssid,
            passphrase = info.passphrase,
            security = info.security,
            manualHostAddress = info.hostAddress?.hostAddress,
            manualHostInterfaceName = info.interfaceName,
        )
    }

    /** 回收本次 TCAM 附着器（幂等）。只在 [workExecutor] 线程调用。 */
    private fun closeTcamHotspot() {
        val manager = activeTcamHotspot ?: return
        activeTcamHotspot = null
        runCatching { manager.close() }
    }

    /**
     * 复用车机热点：连接前把"通告给 iPhone 的热点名"与车机存储配置名核对一次（2026-10-13，日志 4）。
     *
     * ## 为什么（日志 4，CS11，2026-10-12 18:12）
     *
     * 设置里填的是 `Lynk&Co`，车机热点真名是 `Lynk & Co`（`Tcamsoftap.conf` / 反射读数
     * `ssid=Lynk & Co`）。0x5703 把不存在的 `Lynk&Co` 发给了 iPhone，随后
     * `wireless attach timed out`（两轮各 40 秒、期间没有任何 AirPlay 回连），而日志里没有一句
     * 说明原因。设置页的名字比对只吃探测缓存（2 分钟有效），`buildSession` 又跳过了探测，
     * 所以连接这一刻没人核对。同一台车 9/30 用真名 `Lynk & Co` 时，StartSession 后约 0.2 秒
     * iPhone 就回连了。
     *
     * ## 只做三件确定的事（判据见 [CarManualHotspotMatch.reconcile]）
     *
     * - 一致 / 读不到车机名 / 用户没填 ⇒ 不动；
     * - 只差空格或大小写 ⇒ 本次改用车机真名（不可能认成另一张网），**设置不改**；
     * - 实质不同 ⇒ 只写日志与界面日志行，不改不拦（用户手填为准，存储名也可能是残留）。
     *
     * 只在 [CarHotspotPlan.reused] 为真时被调用，自建 / 局域网不经过这里。
     */
    private fun reconcileReusedSsid(
        plan: CarHotspotPlan,
        storedSsid: String?,
        storedPassphrase: String? = null,
    ): CarHotspotPlan {
        val typed = plan.ssid.orEmpty()
        // 身份行：每次复用连接都留一条"这张网是谁的、名字从哪来"，日志 4 就缺这个，
        // 只能靠倒推才看出通告的名字不是车机热点。
        platform.diagnostics.log(
            TAG,
            "hotspot identity: mode=reuse owner=car-native typedSsid=\"$typed\" " +
                "storedSsid=${storedSsid?.takeIf { it.isNotBlank() }?.let { "\"$it\"" } ?: "unavailable"}",
        )
        val named = when (val result = CarManualHotspotMatch.reconcile(typed, storedSsid)) {
            CarManualHotspotMatch.Reconcile.Keep -> plan
            is CarManualHotspotMatch.Reconcile.UseStored -> {
                platform.diagnostics.log(
                    TAG,
                    "manual hotspot ssid reconciled: typed=\"$typed\" stored=\"${result.ssid}\" " +
                        "⇒ advertising stored name (differs only in spacing/case)",
                )
                emit(CarSessionEvent.LogLine(result.note))
                plan.copy(ssid = result.ssid)
            }

            is CarManualHotspotMatch.Reconcile.Mismatch -> {
                platform.diagnostics.log(
                    TAG,
                    "manual hotspot ssid mismatch: typed=\"$typed\" stored=\"${storedSsid.orEmpty()}\" " +
                        "(kept user value)",
                )
                emit(CarSessionEvent.LogLine(result.note))
                plan
            }
        }
        return reconcileReusedPassphrase(named, storedPassphrase)
    }

    /**
     * 复用车机热点：用户没填密码、或还停在 DBPlay 出厂默认密码时，本次改用车机热点存储配置里的
     * 真实密码通告给 iPhone（2026-10-13，日志 5/6/7/8：通告的一直是 `12345678` 或手填值）。
     *
     * 判据在 [CarManualHotspotMatch.reconcilePassphrase]（纯函数，有单测）：用户自己填了别的密码
     * 一律不动；设置里的值不改；**密码本身不写日志**，只记长度与"已替换"。
     */
    private fun reconcileReusedPassphrase(plan: CarHotspotPlan, storedPassphrase: String?): CarHotspotPlan {
        val replacement = CarManualHotspotMatch.reconcilePassphrase(plan.passphrase, storedPassphrase)
            ?: return plan
        platform.diagnostics.log(
            TAG,
            "manual hotspot passphrase reconciled: typedLength=${plan.passphrase.length} " +
                "storedLength=${replacement.length} ⇒ advertising car hotspot passphrase (not logged)",
        )
        emit(
            CarSessionEvent.LogLine(
                "热点密码已按车机校正：你没有填写车机热点的密码（空或 DBPlay 出厂默认），" +
                    "本次使用车机热点自己的密码通告给 iPhone，设置里的值没有改动。",
            ),
        )
        return plan.copy(
            passphrase = replacement,
            // 密码由空变非空时安全类型不能还是 OPEN，否则出口闸门会把密码清回空串。
            security = if (plan.security == CarHotspotSecurity.OPEN) CarHotspotSecurity.WPA2 else plan.security,
        )
    }

    /**
     * 若方案是 [CarHotspotMode.ADB_HOSTAPD]，**先把热点拉起来**。
     *
     * ## 为什么必须在这里
     *
     * 上游拿到 `WirelessHotspotMode.MANUAL` 后做的是"attach 到一张**已经在跑**的热点"
     * （`ManualHotspotManager` 只读配置、从不 `startSoftAp`）。所以这张网必须
     * 在 `runtimeConfig()` 之前就绪——而 `buildSession()` 正好在 worker 线程上，
     * 允许阻塞。
     *
     * ## 失败处理
     *
     * 车机差异大（网卡名、`su` 是否存在、`hostapd` 路径、ABI），**拉起失败是正常分支
     * 而不是异常**：把**具体哪一步挂的**写进诊断与事件流，然后抛出
     * [com.dbtool.carplay.bridge.hotspot.CarHotspotUnavailableException]，由
     * [startInternal] 落到首页「连接失败」并给出**可执行的**下一步。
     *
     * ⚠️ 2026-10-03：**不再回落到系统 LOH**（该形态整体移除，见 CarHotspotMode）。
     */
    private fun ensureAdbHotspot(plan: CarHotspotPlan): CarHotspotPlan {
        val host = adbHotspot
        if (plan.mode != CarHotspotMode.ADB_HOSTAPD) return plan
        if (host == null) return plan

        emit(CarSessionEvent.LogLine("正在拉起 DBPlay 自建热点…"))
        // ⚠️ 2026-10-11（各模式互不影响）：本车的车机自带热点**就在同一张 `wlan0` 上**
        // （诊断包 1791680364941 的全量接口清单里只有 wlan0/p2p0 两张 Wi-Fi 网卡）。
        // 而自建热点启动时会 `killall hostapd` + 在 wlan0 上配地址/广播 DBPlay ⇒
        // **会把车机自己的热点顶掉**。这里先把事实说出来（不改行为）：
        // 是否要"检测到车机热点在跑就拒绝自建"，是产品取舍，留给车主定。
        val carHotspotState = runCatching { CarHotspotEnabledReader.read(appContext) }.getOrNull()
        if (carHotspotState == com.dbtool.carplay.api.hotspot.CarHotspotEnabledState.ON) {
            val warning = "车机自带热点正在运行 —— 自建热点会顶掉它（两者共用同一张 wlan0）。" +
                "若要保留车机热点，请改用「复用车机热点」或「局域网连接」。"
            platform.diagnostics.log(TAG, "adb hotspot: self-built will take over wlan0 while car hotspot is ON")
            emit(CarSessionEvent.LogLine(warning))
        }
        // ⚠️ 网络参数（网卡 / 网段 / 网关 / DHCP / 信道）由 resolver 算好放在 plan 里，
        // 必须**传进去**而不是让 manager 自己再算一遍：同一组值还要用于给 iPhone 的
        // AirPlay 通告（`0x5703` 里带信道），两处不一致会让 iPhone 反复重试关联。
        val started = host.ensureStarted(plan.adbNetwork) { state ->
            platform.diagnostics.log(TAG, "adb hotspot ${state.phase}: ${state.message}")
        }
        if (started) {
            val state = host.state()
            platform.diagnostics.log(
                TAG,
                "adb hotspot ready iface=${state.interfaceName} host=${state.hostAddress} " +
                    "subnet=${state.subnet} channel=${state.channel}",
            )
            // ⚠️ 成功路径**也要**把每一步写进诊断（2026-09-30 补）。
            //
            // 之前只在失败路径写，理由是"成功了就不需要看细节"。实车证明那是错的：
            // 2026-09-30 那次"判成就绪、iPhone 却永远连不上"的故障里，
            // **唯一的证据就在那几步的 detail 里**（"校验实际广播的 SSID"记的是
            // `实际广播「car」而不是「Lynk」`），而它只存在于设置页的 UI 上，
            // 诊断包里一个字都没有 ⇒ 只能事后逐行翻 `iw dev` 才发现。
            //
            // 一句话：**判成"成功"的那些步骤，恰恰是最需要留证的**——
            // 因为一旦"成功"了，就再没人会去怀疑它们。
            logHotspotSteps(state)
            emit(CarSessionEvent.LogLine("自建热点已就绪（${state.hostAddress}）"))
            // ⚠️ 把**实际生效**的网络参数写回 plan（2026-09-30 起信道、2026-10-06 补全）。
            //
            // `CarAdbHotspotManager` 是**逐个候选信道**试的（1/6/11，见 `channelAttempts`），
            // 成功那一轮的实际参数记在 `state` 里，而 plan 里存的仍可能是**选出时的值**。
            // 不写回就有下面这些后果，实车都踩过：
            //
            // 1. **上游会直接抛**：`ManualHotspotManager.validateApConfiguration()` 有一句
            //    `expectedChannel > 0 && configuration.channel > 0 &&
            //     configuration.channel != expectedChannel` ⇒ `IOException("Manual hotspot
            //    channel X does not match configured channel Y")`。也就是说，**凡是平台 API
            //    能报出真实信道的车机**，传一个过期的候选信道 = 会话直接失败。
            //    （本车平台报 0，所以没抛——这纯属运气，不能当设计依据。）
            // 2. **给 iPhone 的信道会不对**：`0x5703` 的信道取自上游的
            //    `WirelessHotspotInfo.channel`。实车日志（09:03:20）：
            //    `adb hotspot ready … channel=6` 而 `hotspot plan … 信道 1` ⇒
            //    上游拿到的是 1，不是实际在用的 6。
            //
            // ⚠️ 2026-10-06 诊断包 `1791279270080` 新发现：**host / subnet 也漏写回**。
            //    连续三代日志都是「plan 说 `192.168.176.0/24` / 网关 `.176.1`，
            //    而 `adb hotspot ready` 与实际 `ifconfig` 都是 `.175`」。
            //    成因：plan 在 `resolve()` 时（建热点**之前**）由
            //    `CarAdbHotspotPlanner` 按当时的可见网卡选出；而
            //    `CarAdbHotspotManager` 拉起时可能沿用网卡上的残留地址/重新规划，
            //    两组值就此分叉。此前**只有信道**被写回，host/subnet 留在过期值上。
            //
            //    危害（本次因为运气好没炸，但不能留）：
            //      a. `hotspotAlreadyRunningLocally()` 拿 `plan.adbNetwork.hostAddress`
            //         去网卡上找地址——plan 说 `.176.1`、网卡上是 `.175.1` ⇒
            //         **找不到** ⇒ 这条「零 ADB 快路径」失效，每次开机白白多等约 10 秒
            //         （见该函数的 KDoc：单条 `adb connect 127.0.0.1` 实测 9983ms）。
            //      b. 诊断/设置页读到的网段与真实网络对不上，排障时误导现场。
            //
            //    写回口径：**只在 manager 真读到了值时覆盖**（空串/0 保持原样），
            //    与信道的"只在实际读到 >0 时覆盖"同一条原则——
            //    绝不把"没读到"当成"值是空的"写进去。
            val actualChannel = state.channel
            val actualHost = state.hostAddress
            val actualSubnet = state.subnet
            val network = plan.adbNetwork
            val patchedNetwork = if (network != null) {
                network.copy(
                    hostAddress = actualHost.takeIf { it.isNotBlank() } ?: network.hostAddress,
                    subnet = actualSubnet.takeIf { it.isNotBlank() } ?: network.subnet,
                    channel = actualChannel.takeIf { it > 0 } ?: network.channel,
                )
            } else {
                null
            }
            return plan.copy(
                channel = actualChannel.takeIf { it > 0 } ?: plan.channel,
                adbNetwork = patchedNetwork,
                // summary 也要跟着改：诊断日志里打的就是它，不修的话
                // 「plan 说 .176 / 实际 .175」这类矛盾还会继续出现，误导下一个读日志的人。
                summary = rewriteHotspotSummary(
                    original = plan.summary,
                    interfaceName = patchedNetwork?.interfaceName,
                    hostAddress = actualHost.takeIf { it.isNotBlank() },
                    subnet = actualSubnet.takeIf { it.isNotBlank() },
                    channel = actualChannel.takeIf { it > 0 },
                ),
            )
        }

        val state = host.state()
        val failure = state.firstFailure
        val reason = failure
            ?.let { "第 ${it.index} 步「${it.title}」失败：${it.detail}" }
            ?: state.message.ifEmpty { "原因未知" }
        platform.diagnostics.warn(TAG, "ADB hotspot failed: $reason")

        // 把**每一步**的原始命令输出都写进诊断。只报"第一个失败步"不够用：
        // 这条链有 6 个车机相关的不确定点（网卡名、`su` 是否存在、`hostapd` 路径、
        // ABI、`FxBridge`、Android 版本），而"第 4 步失败"往往要结合第 2、3 步
        // 实际取到的值才能判断。用户上一次车才能取一次日志，所以必须一次给全。
        logHotspotSteps(state)

        // ⚠️ 网卡被**别人的 AP** 占着时，先于 LOH 回落拦下来（2026-09-30 实车）。
        //
        // 为什么不能回落：LOH 用的是**同一张 Wi-Fi 网卡**，别人占着它就一定起不来；
        // 而本车 Android 9 上 LOH 一次即永久中毒（见 [LocalOnlyHotspotPoison]），
        // 白等 60 秒还落下一个此后每次都秒失败的状态。
        //
        // 更关键的是：这一支的失败**不是"车机差异"**，而是"车机上有一张 AP 占着网卡、
        // 且它广播的名字与我们配的不一样"。它不需要换方案，需要的是**人**去让网卡
        // 回到正确的名字——所以这里必须把真相和下一步一起说出来，
        // 而不是换一条注定失败的路再试一次。
        //
        // ⚠️ 文案纪律：**只陈述日志能证明的事**（网卡上广播的是什么、设置里配的是什么），
        // "那张 AP 是谁开的"日志证明不了（`ps` 只给父进程与 argv），所以只写成
        // "可能是…"，并指路去看诊断包里那一步。把猜测写成结论，下一个读日志的人
        // 就会拿它当前提。
        val foreignAp = state.foreignApSsid
        if (foreignAp != null) {
            platform.diagnostics.warn(
                TAG,
                "skipping LocalOnlyHotspot fallback: interface " +
                    "${state.interfaceName} is occupied by a foreign AP「$foreignAp」",
            )
            throw CarHotspotUnavailableException(
                "Wi-Fi 网卡（${state.interfaceName.ifEmpty { "未知" }}）上广播的是「$foreignAp」，" +
                    "不是设置里的「${plan.ssid ?: "未知"}」——网卡被别的 hostapd 占着" +
                    "（多半是车机上装过的第三方 CarPlay 转换类 App 留下的），" +
                    "自建热点抢不到它。\n" +
                    "处理办法：在车机设置里把自带热点关掉再打开（或改一次热点名称），" +
                    "必要时重启车机；之后 DBPlay 会直接复用它。" +
                    "诊断包里的「网卡占用者」那两步能确认「$foreignAp」是谁写进去的。",
            )
        }

        // ⚠️ **2026-10-03：本函数不再有"回落到系统 LOH"这一步。**
        //
        // 原来这里会 `return plan.copy(mode = LOCAL_ONLY_HOTSPOT, ...)`，让上游用系统
        // `startLocalOnlyHotspot` 再起一次。实测（见 LocalOnlyHotspotPoison）这台车机上
        // 系统热点的三条回调一条都不来、60 秒超时后框架侧请求泄漏，此后每次都是"秒失败"；
        // 且 LOH 用的是**同一张 Wi-Fi 网卡**，前面 ADB 起不来往往正是网卡被占/名字不对，
        // 再落一次必然同样失败，只白白多等 60 秒。该形态已整体移除（见 CarHotspotMode）。
        //
        // ⇒ 失败就是失败，把**卡在第几步**说清楚，出路交给用户（打开车机自带热点让
        // DBPlay 复用 / 在设置里改热点方案重试 / 改用有线）。
        val fallbackStepHint = failure
            ?.let { "第 ${it.index} 步「${it.title}」失败" }
            ?: "原因未知"
        platform.diagnostics.warn(
            TAG,
            "ADB hotspot failed and there is no LocalOnlyHotspot fallback any more: $fallbackStepHint",
        )
        throw CarHotspotUnavailableException(
            "自建热点未建立（$fallbackStepHint）。" +
                "请先确认车机自带的「个人热点」已打开（DBPlay 会自动复用它），" +
                "或在设置里换一种「热点方案」，或改用有线连接。",
        )
    }

    /**
     * 把 plan 的 `summary` 里那段网络参数改写成**实际生效**的值（2026-10-06 补）。
     *
     * 实现委托给纯函数 [rewriteHotspotSummary]（本模块没有 Robolectric，
     * 放方法里就没法单测；而这段字符串处理恰恰是"改坏了没人发现"的高危区）。
     */
    private fun rewriteHotspotSummary(
        original: String,
        interfaceName: String?,
        hostAddress: String?,
        subnet: String?,
        channel: Int?,
    ): String = rewriteHotspotSummaryText(
        original = original,
        interfaceName = interfaceName,
        hostAddress = hostAddress,
        subnet = subnet,
        channel = channel,
    )

    /**
     * 把自建热点的**每一步**写进诊断日志。
     *
     * 成功与失败两条路径都调它——理由见调用点那段注释：2026-09-30 实车那次
     * "判成就绪却永远连不上"的故障，唯一证据就在**成功路径**的步骤 detail 里，
     * 而当时只写失败路径 ⇒ 诊断包里一个字都没有。
     *
     * 换行折成空格：诊断日志是逐行读的，一个步骤占一行才扫得动。
     */
    private fun logHotspotSteps(state: CarAdbHotspotState) {
        state.steps.forEach { step ->
            platform.diagnostics.log(
                TAG,
                "adb hotspot step ${step.index} ${if (step.ok) "OK  " else "FAIL"} " +
                    "${step.title}｜" + step.detail.replace('\n', ' '),
            )
        }
    }

    /**
     * 探测车机原生热点（**首选方案的第一步**）。
     *
     * ## 为什么带缓存
     *
     * 探测要跑十几条 shell 命令（含三次 `dumpsys`），实测数秒。而
     * `start()` / `reconnect()` 每次都会走到这里，用户调一次分辨率就可能重连一次
     * ——每次都重探会让"点连接"多等好几秒，而车机热点配置根本不会秒级变化。
     *
     * 所以用 [NATIVE_PROBE_CACHE_MILLIS] 的缓存；过期才真探。
     *
     * ## 探测失败不是错误
     *
     * 返回 null 或 `detected = false` 都只意味着"没找到车机热点"，
     * 调用方应当继续走自建分支——车机差异大，这是正常路径。
     */
    private fun probeNativeHotspot(): CarNativeHotspotInfo? {
        val host = nativeHotspot ?: return null
        // ⚠️ 2026-10-05（斌哥定论）：**热点已经在跑时，根本不要跑 ADB 探测**。
        //
        // 斌哥口径：「ADB 仅用于开启 DBPlay 永久热点，开启后热点随车机开机自动
        // 启动，使用固定网段，后续无需每次都执行 ADB。」
        //
        // 实车代价（20:01 日志）：`host.probe()` → `session.ensure()` →
        // `adb connect 127.0.0.1` 单条 **9983ms**（车机 SELinux 不许 adb 读
        // `/dev/bus/usb`，adb 的 `device poll` 线程卡在重试上）。每次连接都付。
        //
        // 短路判据（毫秒级、零 ADB）：**我们在跑的那张热点，此刻网卡上有没有
        // 我们配的网关地址**。有 ⇒ 热点健在 ⇒ 直接返回一份"已运行"的信息，
        // 让下游 resolve 走复用分支。查不到才落回原来的 ADB 探测（首次 / 开机）。
        hotspotAlreadyRunningLocally()?.let { running ->
            platform.diagnostics.log(
                TAG,
                "热点已在跑（iface=${running.interfaceName} addr=${running.hostAddress}），" +
                    "跳过 ADB 探测（原会白等 10~20 秒）",
            )
            emit(CarSessionEvent.LogLine("热点已在运行，跳过 ADB 探测：${running.label}"))
            return running
        }
        host.cachedInfo(NATIVE_PROBE_CACHE_MILLIS)?.let { cached ->
            platform.diagnostics.log(TAG, "native hotspot probe reused from cache: ${cached.label}")
            // 缓存分支也要把不一致喊出来：这条结论会被直接用进热点方案，
            // 而它恰恰是"发出去的名字没人广播"这种必然失败的唯一线索。
            cached.ssidMismatchNote?.let { platform.diagnostics.log(TAG, it) }
            return cached
        }
        emit(CarSessionEvent.LogLine("正在经 ADB 探测车机原生热点…"))
        val info = host.probe { state ->
            platform.diagnostics.log(
                TAG,
                "native hotspot probe: ${state.entries.size} 条记录，${state.summary}",
            )
        }
        platform.diagnostics.log(
            TAG,
            "native hotspot probe result: ${info.label} iface=${info.interfaceName} " +
                "addr=${info.hostAddress} channel=${info.channel} source=${info.source} " +
                "ssid=${info.ssid} runningSsid=${info.runningSsid} " +
                "credentialSource=${info.credentialSource}",
        )
        // 凭据来源与整体来源不一致时点一句：实车上磁盘那几份配置文件全是过期的，
        // 真正的 SSID/密码来自本地反射，而 source 照样写 ADB_SHELL。
        // 只进诊断日志，**不进界面**——它是给读包的人看的，不是给现场用户看的。
        info.credentialSourceNote?.let { platform.diagnostics.log(TAG, it) }
        emit(CarSessionEvent.LogLine("探测结果：${info.label}"))
        // ⚠️ 存储配置名与实际广播名不一致 ⇒ 发给 iPhone 的名字注定连不上。
        // 这行是"界面停在无线通道就绪、日志里却没有任何错误"的唯一出口，
        // 所以它必须出现在**两处**：诊断包（事后分析）与界面日志（现场就能看见）。
        info.ssidMismatchNote?.let { note ->
            platform.diagnostics.log(TAG, note)
            emit(CarSessionEvent.LogLine(note))
        }
        return info
    }

    /**
     * **毫秒级判据（零 ADB）**：我们建的那张热点此刻是不是真的在网卡上跑着。
     *
     * ## 为什么必须不碰 ADB
     *
     * 斌哥口径（2026-10-05）：「ADB 仅用于开启 DBPlay 永久热点，开启后热点随
     * 车机开机自动启动，使用固定网段，后续无需每次都执行 ADB。」
     *
     * 而实车每次连接都走 `host.probe()` → `session.ensure()` → `adb connect
     * 127.0.0.1`，单条 **9983ms**（车机 SELinux 挡 adb 读 USB，`device poll`
     * 线程卡住重试）。既然热点已经开好了，这一步就是纯浪费。
     *
     * ## 判据（都从设备读，不信任内存）
     *
     * 1. 有 `wlan*` / `ap*` / `softap*` 网卡处于 UP；
     * 2. 该网卡上出现**我们计划里的网关地址**（[CarAdbHotspotPlanner.plan]
     *    的 `hostAddress`，实车 `192.168.175.1`）。
     *
     * 两条都成立 ⇒ 这就是我们那张热点（网关地址是我们自己配的、别人不会占），
     * 直接返回一份 `detected=true` 的信息，让下游 resolve 走复用分支。
     *
     * ⚠️ 刻意**不**验证 hostapd 进程 —— 那要 root，而本方法的意义就在于不碰 root
     *    通道。网卡上挂着我们配的网关地址，已经是足够强的证据（车机原生热点不会
     *    用这个地址，见 `CarHotspotSubnet` 的冲突规避注释）。
     *
     * ## 🔴 这个判据**只对自建模式成立**（2026-10-09，PATCH-0051）
     *
     * 它找的是 **planner 规划的网关地址**，即"我们自己会配上去的那个地址"。
     * 在**自建**语境下这是对的（地址是我们配的、别人不会占）。
     *
     * 但在**复用**语境下它会**把自建残留误认成车机热点**：
     *
     * ```
     * 自建时 wlan0 被配上 192.168.176.1  →  切到复用，残留仍在  →
     * 本函数命中残留 ⇒ 返回 detected=true ⇒ AirPlay 把 192.168.176.1
     * 通告给 iPhone（车机热点其实在 192.168.175.x）⇒ 40s attach timed out
     * ```
     *
     * 当前**安全**，因为唯一调用方 `probeNativeHotspot()` 在链路里已不被调用
     * （见 `buildSession()` 里 `nativeInfo = null` 的注释）。
     * **但这是隐式依赖，不是契约。** 若有人把 `probeNativeHotspot()` 接回去，
     * 必须先在这里加"当前 mode 不是 MANUAL"的前置闸门，
     * 或改用 [com.dbtool.carplay.api.hotspot.CarHotspotResidue] 显式区分残留与真热点。
     *
     * @return 读到了返回信息；读不到（首次 / 热点没跑 / 无权限）返回 null，
     *         调用方落回原来的 ADB 探测路径。
     */
    private fun hotspotAlreadyRunningLocally(): CarNativeHotspotInfo? = runCatching {
        // 期望地址：planner 按当前实况选出来的网关（与真正配到网卡上的一致）。
        // interfaceOverride 传 "wlan0"（planner 里默认/参考实现的值），它只在
        // "没有原生热点"这条分支上参与"是否采用已有网卡地址"的判断。
        val plan = CarAdbHotspotPlanner.plan(appContext, info = null, interfaceOverride = "wlan0")
        val expected = plan.hostAddress.takeIf { it.isNotBlank() } ?: return@runCatching null

        val nic = java.net.NetworkInterface.getNetworkInterfaces()
            ?.toList().orEmpty()
            .firstOrNull { n ->
                val name = runCatching { n.name }.getOrNull().orEmpty()
                val wifiish = name.startsWith("wlan") ||
                    name.startsWith("ap") ||
                    name.startsWith("softap")
                val up = runCatching { n.isUp }.getOrDefault(false)
                val loopback = runCatching { n.isLoopback }.getOrDefault(false)
                wifiish && up && !loopback &&
                    runCatching {
                        n.inetAddresses.toList().any {
                            it is java.net.Inet4Address &&
                                it.hostAddress.orEmpty() == expected
                        }
                    }.getOrDefault(false)
            } ?: return@runCatching null

        val iface = runCatching { nic.name }.getOrNull() ?: return@runCatching null
        CarNativeHotspotInfo(
            detected = true,
            enabled = true,
            interfaceName = iface,
            hostAddress = expected,
            subnet = plan.subnet.takeIf { it.isNotBlank() },
            prefixLength = plan.subnet.takeIf { it.isNotBlank() }
                ?.let { com.dbtool.carplay.api.hotspot.CarHotspotSubnet.prefixLength(it) },
            gateway = expected,
            channel = plan.channel,
            source = com.dbtool.carplay.api.hotspot.CarHotspotProbeSource.LOCAL_REFLECTION,
        )
    }.getOrNull()

    private fun buildSession(): SessionHandle {
        val current = settings
        val display = platform.display.primaryDisplay()
        // ⚠️ 2026-10-05：本函数的**每一步耗时**都写进诊断日志。
        // 起因：实车日志显示 17:38:53.542 → 17:39:13.630 之间有 20.09 秒空白，
        // 但空白落在哪一段无法从既有日志判断（clamp 与 probe 各自的日志之间
        // 有 20 秒没有任何输出）。靠猜改过一版，实测被证伪 —— 现在改成
        // **自己报账**：装车跑一次，20 秒属于谁立刻就明确。
        val stepTimer = StepTimer(TAG, platform)
        // 画布尺寸按优先级取：
        // ① 开了自定义分辨率 ⇒ 用户填的像素。这是"按车机屏实际参数精确对齐"的唯一入口，
        //    必须**先于**下面的解码器夹取，否则夹取会基于错误的目标尺寸做等比缩小；
        // ② 否则由 UI 提供（视频 View 实测尺寸）；
        // ③ 没有 UI 时回落到面板分辨率。
        //
        // ⚠️ `CarCustomDisplay.resolve` 是**全链路唯一**一次对自定义像素做夹取/取偶，
        // `CarPlayConfigBuilder` 不再重复（重复会把解码器夹小的尺寸又放大回去）。
        //
        // 画布对账快照提前到这里取：下面的"沉浸模式安全区"也要用同一份
        // （`topInsetPx` / `statusBarWindowHeightPx`）。分两次采样会串不上——
        // 这是本项目在日志对账上反复踩过的坑，同一批数字必须来自同一次读取。
        val metrics = platform.display.probeCanvasMetrics()
        val requestedCanvas = if (current.customDisplayEnabled) {
            val custom = CarCustomDisplay.resolve(
                widthPx = current.customDisplayWidthPx,
                heightPx = current.customDisplayHeightPx,
                dpi = current.customDisplayDpi,
            )
            platform.diagnostics.log(
                TAG,
                "custom display canvas ${custom.widthPx}x${custom.heightPx} " +
                    "dpi=${current.customDisplayDpi} " +
                    "physicalMm=${custom.widthMm}x${custom.heightMm}",
            )
            CarVideoCanvas(custom.widthPx, custom.heightPx)
        } else {
            val fromUi = canvasSizeProvider()
            val fromPanel = CarVideoCanvas(display.widthPixels, display.heightPixels)
            val chosen = fromUi ?: fromPanel
            // ------------------------------------------------------------------
            // ⚠️ 2026-10-07 新增：**画布尺寸三方对账**（探测用）。
            //
            // ## 为什么必须打这一条
            //
            // 0.10.10 车机日志里 6 次协商全是 `1920x1008@60`，而面板是 1920x1080。
            // 差的正好 72px 是顶部状态栏。问题在于：**这个 1008 是我们报给 iPhone 的
            // 握手分辨率**，而 iPhone 会按它编码 → 画面被拉到 1080 面板 → 缺 72 行
            // → 用户看到的"画面模糊"。
            //
            // 三个数字必须放进**同一条日志**才能定论，分三次采样会串不上：
            //   ① 面板（`display`）：车机物理屏，权威值；
            //   ② 窗口（`fitsSystemWindows` 后的实测区域）：我们真正拿到多大；
            //   ③ 两条栏的 inset：到底各占多少、是否有栏被钉死抢不掉。
            //
            // 三者关系决定改法：
            //   · 面板高 = 窗口高 + 顶部 inset + 底部 inset ⇒ 正常避让，按栏高算差值即可；
            //   · 窗口高 < 面板高，但 inset 报 0 ⇒ 车机在窗口层面就切了（厂商 overlay），
            //     inset 量不到，只能按"面板 − 窗口"反推被抢走多少；
            //   · 窗口高 == 面板高 ⇒ 画面已顶格，状态栏是浮在上面的（会遮住内容）。
            //
            // 输出刻意**紧凑且带标签**，方便在一份万字日志里 grep 一行就拿全。
            // ------------------------------------------------------------------
            platform.diagnostics.log(
                TAG,
                "画布对账｜面板=${fromPanel.widthPixels}x${fromPanel.heightPixels}" +
                    "｜窗口=${fromUi?.let { "${it.widthPixels}x${it.heightPixels}" } ?: "无(回落面板)"}" +
                    "｜顶栏inset=${metrics.topInsetPx}px" +
                    " 底栏inset=${metrics.bottomInsetPx}px" +
                    "（忽略可见性=${metrics.bottomIgnoringVisibilityPx}px）" +
                    "｜车机状态栏实高=${metrics.statusBarWindowHeightPx}px" +
                    "｜最终上报=${chosen.widthPixels}x${chosen.heightPixels}",
            )
            if (fromUi != null && fromUi.heightPixels != fromPanel.heightPixels) {
                platform.diagnostics.log(
                    TAG,
                    "画布对账｜⚠️ 窗口与面板不等高：" +
                        "差 ${fromPanel.heightPixels - fromUi.heightPixels}px" +
                        "（顶${metrics.topInsetPx} + 底${metrics.bottomInsetPx}" +
                        " = ${metrics.topInsetPx + metrics.bottomInsetPx}）" +
                        "｜若两者不等 ⇒ 有栏的 inset 量不到，是厂商在窗口层切的",
                )
            }
            chosen
        }
        // 必须夹到本机解码器真正支持的范围：真机 vivo Y78+ 的硬解上限只有
        // 1920x1088，而横屏全屏画布是 2314x1080。超限时 iPhone 侧握手全绿
        // （会话 active、屏幕流 active、H265 帧照收），车机侧却因为
        // MediaCodec.configure() 抛 BAD_VALUE 而永远黑屏。
        stepTimer.mark("拉画布尺寸（自定义/UI/面板）")
        // ⚠️ 返回值里带**生效帧率**：解码器在这个尺寸下跑不满用户选的帧率时，
        //    `clamp` 会连同 fps 一起降下来（见 `ClampResult`）。旧版只取宽高、
        //    把降下来的帧率丢掉，于是 iPhone 仍按用户选的 60fps 编码，
        //    车机侧当场掉帧。这里必须把 fps 一直传到 `airPlayConfig`。
        val clampResult = CarVideoSizeLimiter.clamp(
            canvas = requestedCanvas,
            fps = current.fps,
            hevc = current.hevc,
            softwareDecoder = current.hevcSoftwareDecoder,
        )
        val canvas = clampResult.canvas
        if (clampResult.fps != current.fps) {
            platform.diagnostics.log(
                TAG,
                "video frame rate reduced ${current.fps} → ${clampResult.fps} fps " +
                    "（解码器在该尺寸下跑不满所选帧率）",
            )
        }
        stepTimer.mark("解码器夹取 clamp()")
        // ------------------------------------------------------------------
        // 沉浸模式（2026-10-08）：把车机状态栏的**实测高度**作为 `safeArea.top`
        // 声明给 iPhone —— 这是「顶格但不被遮挡」的关键一半。
        //
        // 取数口径（两个来源都在 `CarWindowInsetsHolder` 快照里）：
        //   ① `topInsetPx`：系统告诉我们顶部被占了多少（本机 72px）；
        //   ② `statusBarWindowHeightPx`：SystemUI 状态栏窗口的实测高度，仅当 ① 量不到时兜底
        //      （有些 ROM 在窗口层就切掉了，inset 报 0 但栏确实在）。
        // 两个都取不到就**不声明**（= 0）：宁可保持全屏安全区，也不猜一个数字 ——
        // 猜错会让 iPhone 把 UI 排到错误的位置，比不避让更难排查。
        //
        // ⚠️ 这里读的是**同一次**快照（上面 `metrics`），不是重新采样。
        // ------------------------------------------------------------------
        // 隐藏顶部栏关闭、且不是全屏时，状态栏还占着顶部。画布已经铺到它下面，
        // 要把这条高度报成安全区顶边，iPhone 才把可点界面排开，画面仍画进状态栏区域。
        val extendUnderStatusBar = !current.hideTopBar && !current.fullscreen
        val immersiveSafeAreaTopPx = if (extendUnderStatusBar) {
            when {
                metrics.topInsetPx > 0 -> metrics.topInsetPx
                metrics.statusBarWindowHeightPx > 0 -> metrics.statusBarWindowHeightPx
                else -> 0
            }
        } else {
            0
        }
        if (current.immersiveVideo) {
            platform.diagnostics.log(
                TAG,
                "沉浸模式｜画布=${canvas.widthPixels}x${canvas.heightPixels}" +
                    "｜声明 safeArea.top=${immersiveSafeAreaTopPx}px" +
                    "（顶栏inset=${metrics.topInsetPx} 状态栏实高=${metrics.statusBarWindowHeightPx}）" +
                    "｜drawUIOutsideSafeArea=${current.safeAreaDrawOutside}",
            )
        }
        val airPlayConfig = CarPlayConfigBuilder.airPlayConfig(
            settings = current,
            display = display,
            widthPixels = canvas.widthPixels,
            heightPixels = canvas.heightPixels,
            // 解码器实测的生效帧率覆盖用户设置值；仅在真的被降级时才传，
            // 传 null 表示"沿用 settings.fps"，避免无谓地绕开消毒逻辑。
            effectiveFps = clampResult.fps,
            // 每次建栈都重新读一次：用户刚在设置页导入的图标应当立刻生效，
            // 而不是等下一次冷启动。
            icon = airPlayIconStore.loadActive(),
            // 仪表副屏（2026-10-07，吉利 HDMI2 移植）。
            // 没有副屏能力的平台返回 null ⇒ 与合并前完全一致。
            // No phone-side ALT display when both video consumers are disabled.
            // Local instrument theme rendering does not require an iPhone video stream.
            cluster = if (settings.immersiveNavigationEnabled) null else if (settings.projectionEnabled || settings.hudProjectionEnabled) clusterSpecResolver() else null,
            // 沉浸：顶部避让值（见上方注释）。非沉浸时恒为 0，不影响既有行为。
            immersiveSafeAreaTopPx = immersiveSafeAreaTopPx,
        )
        immersiveModeForConnection = current.immersiveNavigationEnabled
        immersiveConfigForConnection = current
        stepTimer.mark("组装 airPlayConfig（含图标读取）")
        // 无线热点方案在这里解析。
        //
        // ⚠️ 2026-10-05（斌哥定案·最高口径）：
        //   **不探测**。用户手动点「立即建立热点+ADB」开了永久热点之后，
        //   DBPlay 就**按默认配置直接使用「DBPlay」热点**——
        //   不再跑 `probeNativeHotspot()` 去翻车机原有的热点（dumpsys / WifiConfigStore /
        //   softap.conf 那一大圈，实车白花 2.9 秒），也不再交给 CarHotspotResolver 去做
        //   "能复用就复用"的决策。
        //
        //   原口径（"先探测，能复用就复用车机自带热点"）**已作废**：
        //   车机上那套原生配置是厂商预置的旧模板（实车 `softap.conf` 里躺着
        //   `LYNK&CO` / `12345678`），探测出来只会把**没人广播的旧名字**发给 iPhone
        //   （实车 0.10.1 日志实锤：`实际「DBPlay」/ 配置「Lynk」→ iPhone 必然连不上`）。
        //   丢掉探测，同时也就丢掉了这个错误源。
        //
        //   保留 `ensureAdbHotspot`：它才是"把 DBPlay 那张热点**真的**建起来"的一步
        //   （ADB → hostapd + DHCP），且内部已有"已在跑就复用"的零 ADB 短路。
        //   探测结果一律传 `null`，`resolve()` 拿不到可复用的原生热点，
        //   自然走**默认配置自建 DBPlay**这一支。
        //
        // ⚠️ 2026-10-09（PATCH-0051）追加定论：**这条 `nativeInfo = null` 也是
        //   "复用模式不再被自建残留误导"的落点之一**，与 `CarHotspotModeInit`
        //   是同一件事的两端。
        //
        //   缘由：`probeNativeHotspot()` 的第一条判据是 `hotspotAlreadyRunningLocally()`
        //   —— 它拿 **planner 的网关地址**去网卡上找。这个判据在**自建**语境下是对的
        //   （地址是我们自己配的），但一旦模式切到「复用车机热点」而自建残留还在
        //   （`192.168.176.1`），它就会把残留**认成"车机热点"**并返回 `detected=true`，
        //   于是 AirPlay 把一个并不存在的网段地址通告给 iPhone ⇒ 40 秒 attach 超时。
        //
        //   现在 `probeNativeHotspot()` 在这条链路上**根本不被调用**（见上行），
        //   所以复用走的是用户手填的 SSID + 车机真实网卡，残留地址不可能再上通告。
        //   维护提醒：**若将来有人把 `probeNativeHotspot()` 接回这里**，
        //   必须同时给 `hotspotAlreadyRunningLocally()` 加"当前不是复用模式"的前置闸门，
        //   否则本条 bug 会以完全相同的形态复发。
        val hotspotPlan = if (current.isWireless) {
            stepTimer.mark("进入热点解析")
            // 注意：这里**故意不调用** `probeNativeHotspot()`。
            // 传 `null` = "没有可复用的车机原生热点"，解析器即按默认配置自建。
            stepTimer.mark("跳过原生热点探测（斌哥口径：直接用 DBPlay 默认配置）")
            CarHotspotResolver.resolve(appContext, current, nativeInfo = null).also { plan ->
                platform.diagnostics.log(TAG, "hotspot plan: ${plan.summary}")
                emit(CarSessionEvent.LogLine("热点方案：${plan.summary}"))
            }
                .let(::ensureManualHotspotReady)
                // ⚠️ `ensureManualHotspotReady` 只对 `MANUAL` 生效（复用模式的车机热点开关检查），
                // `ensureTcamHotspotReady` 只对 `TCAM_HOTSPOT` 生效（自动识别接入卡与地址），
                // `ensureAdbHotspot` 只对 `ADB_HOSTAPD` 生效 —— 三者互不相交，顺序无副作用。
                .let(::ensureTcamHotspotReady)
                .let(::ensureGeelyRoutedReady)
                .let(::ensureAdbHotspot)
        } else {
            null
        }
        stepTimer.mark("热点方案解析完毕（probe + resolve + 自建）")
        // 检查点：热点解析是建栈里最慢的一段（数秒）。这期间若又来了 stop / reconnect，
        // 这一轮已经被作废，别再起本地 MFi / 抢音频焦点 / 建 sink 然后马上拆掉
        // （日志 8：第一轮建出句柄 28 ms 后就被第二轮 closeSession 掉）。
        if (generation.get() != buildToken) throw StaleBuildException()
        // 本机软件认证：先起环回服务，再把地址交给配置翻译层。
        // 刻意放在 try 外面——它一旦起来就持有端口与线程，
        // 必须由 catch 负责回收，否则半途失败会泄漏一个正在监听的 socket。
        val localMfi = if (current.mfiTarget == CarMfiTarget.LOCAL_OFFLINE) {
            startLocalMfi()
        } else {
            null
        }
        return try {
            val runtimeConfig = CarPlayConfigBuilder.runtimeConfig(
                settings = current,
                localMfiEndpoint = localMfi?.endpoint,
                localMfiToken = localMfi?.token,
                hotspotPlan = hotspotPlan,
                // PATCH-0010：地址已经在 `resolve()` 里读好、放进 plan 了
                // （`CarHotspotResolver.sameLan`）。这里只做透传，不再读一次——
                // 两次读取之间网络若变化，通告给 iPhone 的地址会与日志里记的不一致。
                // 同理，通告的 Wi-Fi 名/密码也来自 plan（那里已定好优先级），
                // **不在这里回退到任何"本机热点"字段**——那正是本次卡住 bug 的成因。
                sameLanHostAddress = hotspotPlan?.sameLan?.hostAddress,
                sameLanWifiSsid = hotspotPlan?.sameLanWifiSsid,
                sameLanWifiPassphrase = hotspotPlan?.sameLanWifiPassphrase,
            )

            val mediaSink = sinkFactory.create(
                CarPlaySinkRequest(
                    context = appContext,
                    display = display,
                    widthPixels = airPlayConfig.main.widthPixels,
                    heightPixels = airPlayConfig.main.heightPixels,
                    preferSoftwareHevcDecoder = current.hevcSoftwareDecoder,
                    advancedAudioChannelMapping = platform.audio.supportsAdvancedChannelMapping(),
                    // 从 `current`（重建 sink 时那份设置快照）读，而不是 `settings` 字段：
                    // 通道号只在**建轨那一刻**用得上，中途改动必须重连才生效，
                    // 这正是设置页「保存并重连」的语义。让它在会话中途改变反而会造成
                    // 「界面显示已改、声音还是老通道」的假象。
                    mediaAudioChannel = current.mediaAudioChannel,
                    navigationAudioChannel = current.navigationAudioChannel,
                    mediaAudioBufferMillis = current.mediaAudioBufferMillis,
                ),
            )
            mediaSink.setScreenStreamActiveListener { type, active ->
                platform.diagnostics.log(TAG, "screen stream type=$type active=$active")
                // 2026-10-22：111 流真正建立/断开时通知 HUD。HUD 可能在握手前
                // 就开了开关，那时 `setProjectionEnabled(true)` 会因 clusterStream=0
                // 被拒；流出现后由 HUD 协调器补一次，才不会永远空窗。
                if (type == AndroidCarPlayMediaSink.SCREEN_TYPE_ALT) {
                    if (current.immersiveNavigationEnabled) {
                        if (active) workExecutor.execute {
                            airPlaySession?.takeIf { immersiveModeForConnection }?.let(::ensureClusterContentSent)
                        }
                    } else platform.hudProjection?.onScreenStreamActive(active)
                }
            }
            // HUD 镜像解码器的 config 状态（P1-A：Surface 晚到时回放 lastVideoConfig）。
            mediaSink.setHudMirrorStateListener { ready ->
                if (!current.immersiveNavigationEnabled) platform.hudProjection?.onConfigReady(ready)
            }
            currentSurface?.let(mediaSink::attachSurface)
            // 副屏同理：会话重建后把已有的仪表 Surface 重新绑回 ALT 轨。
            if (current.immersiveNavigationEnabled) currentImmersiveSurface?.let(mediaSink::attachClusterSurface)
            else currentClusterSurface?.let(mediaSink::attachClusterSurface)
            // HUD 镜像也重绑：新 sink 不认识上一轮的 Surface；不重绑则窗口还在、
            // 解码器却不知道往哪画（HUD 黑屏，但日志里窗口状态一切正常）。
            if (!current.immersiveNavigationEnabled) currentHudSurface?.let(mediaSink::attachHudSurface)

            val focusGuard = CarAudioFocusGuard(
                context = appContext,
                sink = mediaSink,
                log = { message -> platform.diagnostics.log(TAG, message) },
                navigationAudioFocusEnabled = settings.navigationAudioFocusEnabled,
            )
            focusGuard.acquire()
            val decoratedSink = UpstreamMediaSinkBridge(
                delegate = mediaSink.mediaSink,
                media = platform.media,
                displayProvider = { platform.display.primaryDisplay() },
                navigationSpeechFocus = { active ->
                    focusGuard.setNavigationSpeechActive(active)
                },
                advancedAudioChannelMapping = platform.audio.supportsAdvancedChannelMapping(),
                audioStreamsChanged = { emit(CarSessionEvent.AudioStreamsChanged) },
            )
            val mediaEngine = CarPlayMediaEngine(
                sink = decoratedSink,
                microphoneEnabled = current.microphone,
                audioCaptureDirectory = audioCaptureDirectory,
            )
            val locationProvider = if (current.locationReporting) {
                UpstreamLocationBridge(
                    navigation = platform.navigation,
                    onDroppedFix = { reason ->
                        platform.diagnostics.warn(TAG, "dropped location fix: $reason")
                    },
                )
            } else {
                null
            }

            val controller = CarPlayController(
                context = appContext,
                config = runtimeConfig,
                airPlayConfig = airPlayConfig,
                identity = identity,
                pairings = pairings,
                listener = sessionListener,
                media = mediaEngine,
                reportStatus = ::onStatus,
                loadPairRecord = loadPairRecord,
                savePairRecord = savePairRecord,
                clearPairRecord = clearPairRecord,
                locationProvider = locationProvider,
                vehicleStatusProvider = platform.vehicleStatus?.takeIf { it.enabled }?.let { source ->
                    UpstreamVehicleStatusBridge(source) { message -> platform.diagnostics.warn(TAG, message) }
                },
                onWiredDisconnected = ::onWiredControllerDisconnected,
            )
            SessionHandle(controller, mediaSink, locationProvider, localMfi, focusGuard, decoratedSink)
        } catch (error: Throwable) {
            // 只回收到这里为止已经拿到的东西：mediaSink 由 controller 内部持有，
            // 而 controller 还没构造出来，所以只需要管环回服务。
            runCatching { localMfi?.close() }
            throw error
        }
    }

    /**
     * 为 [CarMfiTarget.LOCAL_OFFLINE] 起一个只监听 `127.0.0.1` 的 MFi 环回服务。
     *
     * 身份缺失时**不抛异常**，而是换上一个"不可用"的签名器：服务照常应答，
     * 但每个端点都返回 503 + 可读原因，由上游一路透传到状态里。
     * 直接抛异常只会得到一句 `Required value was null.`，对用户毫无帮助。
     */
    private fun startLocalMfi(): CarMfiLoopbackServer {
        val signer = when (val availability = mfiIdentity.availability()) {
            is CarMfiIdentityStore.Availability.Ready -> {
                platform.diagnostics.log(
                    TAG,
                    "local MFi identity ready source=${availability.source} " +
                        "fingerprint=${availability.key.publicKeyFingerprint}",
                )
                availability.key
            }

            is CarMfiIdentityStore.Availability.Missing -> {
                platform.diagnostics.warn(
                    TAG,
                    "local MFi identity unavailable: ${availability.reason}",
                )
                UnavailableCarMfiSigner(availability.reason)
            }
        }
        // 第二道防线：外面再包一层拉黑守卫。上面 [startInternal] 那道是**快照判断**
        // （建栈之前查一次），而拉黑是管理员在后台随时点的——守卫在**每个请求**上
        // 现读本地落盘，于是"查过之后、握手之前"那几秒里到达的证书/签名请求也会被拒。
        // 没有它，"拦截"就只是一次性快照，而不是这条服务真的不签名。
        val guarded = CarMfiBlockGuardedSigner(signer, mfiBlockStore)
        return CarMfiLoopbackServer(guarded).also {
            // 只记地址，不记令牌——令牌是本进程的一次性凭证。
            platform.diagnostics.log(TAG, "local MFi loopback listening on ${it.endpoint}")
        }
    }

    private fun detachSession(): SessionHandle? = synchronized(lifecycleLock) {
        val handle = activeHandle ?: return@synchronized null
        activeHandle = null
        handle
    }

    private fun closeSession(handle: SessionHandle): Boolean {
        runCatching { handle.controller.close() }
        val clean = runCatching {
            handle.controller.awaitClosed(CONTROLLER_CLOSE_TIMEOUT_MILLIS)
        }.getOrDefault(false)
        if (!clean) {
            platform.diagnostics.warn(TAG, "CarPlay controller did not close cleanly in time")
        }
        // 注意：locationProvider 会由 CarPlayController.close() 内部关闭
        // （见 upstream CarPlayController 的 closeBestEffort("location provider")），
        // 这里只负责关掉我们自己持有的渲染槽位。
        runCatching { handle.sink?.close() }
        // 焦点必须在会话真正结束时才释放（暂停期间要一直握着，
        // 否则用户再按播放时要重新抢焦点，中间那段路由是断的）。
        runCatching { handle.focusGuard?.release() }
        // 环回服务放在最后关：Core 停下来之前可能还有一次在途的证书/签名请求，
        // 提前关掉只会让它拿到"连接被拒绝"，日志里多一条看不出原因的失败。
        runCatching { handle.localMfi?.close() }
        // TCAM 附着器与本会话同生命周期：会话一结束就注销 NetworkCallback，
        // 否则它会在 stop 之后继续"链路变化 → 重连"，把刚停下的会话又拉起来。
        closeTcamHotspot()
        return clean
    }

    private fun onWiredControllerDisconnected(controller: CarPlayController) {
        val observedGeneration = generation.get()
        workExecutor.execute {
            synchronized(lifecycleLock) {
                if (!shouldRecoverWiredController(
                        bridgeClosed = closed,
                        wireless = settings.isWireless,
                        expectedGeneration = observedGeneration,
                        currentGeneration = generation.get(),
                        sameController = activeHandle?.controller === controller,
                        controllerClosed = controller.isClosed(),
                    )) return@synchronized
                airPlaySessionRef.get()?.let {
                    expectedEndReasons[it] = CarSessionEndReason.RECONNECT
                }
                val old = detachSession() ?: return@synchronized
                platform.diagnostics.log(TAG, "有线会话已关闭：释放旧句柄并等待USB/VPN清理")
                val clean = closeSession(old)
                airPlaySessionRef.set(null)
                updateState {
                    it.copy(
                        phase = if (clean) CarSessionPhase.RECONNECTING else CarSessionPhase.FAILED,
                        detail = if (clean) "等待重新连接 USB" else "USB资源尚未释放，请稍后重连",
                        activeAudioChannels = emptySet(),
                    ).withoutDevice()
                }
                emit(CarSessionEvent.SessionEnded(CarSessionEndReason.RECONNECT))
                // User stop/reconnect changes generation without waiting for lifecycleLock.
                if (!clean || closed || generation.get() != observedGeneration || settings.isWireless) {
                    return@synchronized
                }
                buildToken = generation.incrementAndGet()
                // New controller registers fresh USB receivers and polls if unplugged.
                startInternal()
            }
        }
    }

    // ------------------------------------------------------------- core callbacks

    /**
     * 清掉方控 HID 的落点，**只在这个引用确实还是它**时才清。
     *
     * 重连时旧会话的结束回调可能晚于新会话的激活回调到达；无条件置 null 会把
     * 刚拿到的新会话顶掉，表现为"重连之后方控失灵直到下次重连"。
     * [AtomicReference.compareAndSet] 让这个判断是原子的。
     */
    private fun releaseAirPlaySession(session: AirPlaySession): Boolean =
        airPlaySessionRef.compareAndSet(session, null)

    private fun onStatus(status: CarPlayStatus) {
        val transport = if (settings.isWireless) {
            CarPlayTransport.WIRELESS
        } else {
            CarPlayTransport.WIRED
        }
        val phase = CarPlayStatusMapper.phaseOf(status)
        val detail = CarPlayStatusMapper.detailOf(status, transport)
        // 同 `startInternal`：上游失败文案是英文，必须过中文层才允许进界面。
        val errorMessage = CarErrorText.of((status as? CarPlayStatus.Failed)?.message)

        // ⚠️ 2026-10-03：这里原本会记住"系统热点在本进程内已废"，用来让后续 ADB
        // 失败时不再往它回落。LOH 形态已整体移除（见 CarHotspotMode），该标志
        // 与判断一并删除；上游热点失败文案仍照常翻译给用户（见上面 errorMessage）。

        // 2026-10-21 防降级（方案 §3.3 #10）：`onSessionActive` 已经把 bridge 写成
        // ACTIVE 后，上游的 bootstrap 重试/超时还会继续上报 `RunningWireless`
        // （映射为 CONNECTING）与 `ConnectingBluetooth`（映射为 WAITING_FOR_DEVICE）。
        // 旧实现会把 ACTIVE 降级成"无线通道就绪"，这正是现场"双流 active 但界面
        // 停在无线通道就绪"的直接原因。这里只挡**进行中**状态的降级；
        // Failed / Reconnecting / Stopped 是真实生命周期变化，仍允许覆盖。
        var downgradeBlocked = false
        updateState { current ->
            val wouldDowngradeActive = current.phase == CarSessionPhase.ACTIVE &&
                phase != CarSessionPhase.ACTIVE &&
                phase != CarSessionPhase.FAILED &&
                phase != CarSessionPhase.RECONNECTING &&
                phase != CarSessionPhase.STOPPED
            if (wouldDowngradeActive) {
                downgradeBlocked = true
                current
            } else {
                current.copy(
                    phase = phase,
                    detail = detail,
                    // 失败之外的阶段清掉上一次的错误，避免 UI 一直显示陈旧报错。
                    errorMessage = if (phase == CarSessionPhase.FAILED) errorMessage else null,
                )
            }
        }
        if (downgradeBlocked) {
            platform.diagnostics.log(
                TAG,
                "忽略会覆盖 ACTIVE 的状态降级：$status（phase=$phase detail=$detail）",
            )
        } else {
            emit(CarSessionEvent.PhaseChanged(phase, detail))
        }

        // 无线握手完成 = 上游刚关掉自己的 RFCOMM bootstrap
        // （`closeBluetoothBootstrapTransport()` 紧挨着 `onStatus(WirelessActive)`）
        // ⇒ 这正是"该把系统蓝牙也断掉"的时刻。异步、失败不影响会话。
        if (status is CarPlayStatus.WirelessActive) {
            bluetoothHandoffReleaser.onHandoffComplete(
                settings.bluetoothHandoffPolicy,
                handoffTargetMac,
            )
        }
    }

    private val sessionListener = object : AirPlaySessionListener {
        override fun onHostUiRequested(session: AirPlaySession) {
            if (airPlaySessionRef.get() !== session) return
            mainHandler.post {
                if (airPlaySessionRef.get() !== session) return@post
                // OEM/AirPlay returns to the system launcher, not DBPlay's home screen.
                // This is only a UI action: never stop or tear down the CarPlay session.
                runCatching {
                    appContext.startActivity(
                        Intent(Intent.ACTION_MAIN)
                            .addCategory(Intent.CATEGORY_HOME)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }.onFailure {
                    platform.diagnostics.warn(TAG, "CarPlay 请求系统桌面失败：${it.javaClass.simpleName}: ${it.message}")
                }
            }
        }

        override fun onSessionActive(session: AirPlaySession) {
            // 方控 HID 的落点在这里拿到——见 airPlaySessionRef 的说明。
            airPlaySessionRef.set(session)
            steeringWheelForwardLogged.set(false)
            updateState {
                it.copy(phase = CarSessionPhase.ACTIVE, detail = "CarPlay 会话已建立",
                    immersiveNavigationActive = immersiveModeForConnection)
            }
            emit(CarSessionEvent.SessionStarted)
            if (immersiveModeForConnection) {
                projectionWanted = true
                workExecutor.execute { if (airPlaySessionRef.get() === session) ensureClusterContentSent(session) }
            }
            // PATCH-0025：会话建立后按落盘的投屏开关自动恢复投屏。
            // 第二路流（111）常在会话 active 之后才到，故用带重试的调度。
            scheduleReapplyProjection()
        }

        /**
         * 会话结束。这是**唯一**可靠的收尾回调——upstream `AirPlaySession.close()`
         * 在 `teardown()` 之后必定调它一次（用 `notified` 的 CAS 保证不重复），
         * 远端主动断开走的也是这里。所以不需要再挂 `AirPlayMediaHandler.onSessionClosed`：
         * 那是另一条接口（媒体流），且 `onSessionEnded` 已经覆盖全部关闭路径。
         */
        override fun onSessionEnded(session: AirPlaySession) {
            // 先取来源再判过期：即使这次回调被新会话顶掉，标记也要取走，避免 map 里留垃圾。
            // 没有标记 = 上游自己结束的 ⇒ 远端真实断开（REMOTE）。
            val reason = expectedEndReasons.remove(session) ?: CarSessionEndReason.REMOTE
            if (!releaseAirPlaySession(session)) {
                // 旧会话可能在新会话激活后才迟到结束；忽略其状态/事件，
                // 否则应用层会误拆新会话的仪表 Presentation。
                platform.diagnostics.log(TAG, "忽略过期的 CarPlay 会话结束回调（reason=$reason）")
                return
            }
            updateState {
                // 设备信息必须跟着会话一起清掉（见 `withoutDevice` 的说明）。
                //
                // 只有 REMOTE 才把 phase 改成 RECONNECTING：其余来源（用户停止 /
                // 熄火暂停 / 故意重建）的 phase 由发起方设置，这里不能覆盖 ——
                // 否则 stop() 刚写的 STOPPED 会被改回 RECONNECTING。
                val cleared = it.copy(activeAudioChannels = emptySet(), immersiveNavigationActive = false).withoutDevice()
                if (reason == CarSessionEndReason.REMOTE) {
                    cleared.copy(
                        phase = CarSessionPhase.RECONNECTING,
                        detail = "CarPlay 会话已结束",
                    )
                } else {
                    cleared
                }
            }
            emit(CarSessionEvent.SessionEnded(reason))
            // 运行时投屏意图随会话作废。下一次会话只能由新的导航状态或用户手动操作触发；
            // 不清的话，非平台建窗路径（通用 Android）会在 `onSessionActive` 的
            // `scheduleReapplyProjection()` 里看到 wanted=true，断线重连后会自动恢复旧投屏，
            // 这不是用户在新会话里的明确请求。
            //
            // ⚠️ 这里直接改字段、**不**走 `setProjectionEnabled(false)`：后者会顺带
            // 惰性创建 `ClusterProjectionController`（要 Context），而本路径只是清两个布尔。
            //
            // 同理，这里也**不补发 stopUI**（PATCH-0121 ②，特意写下免得后人以为是漏了）：
            // 会话结束时 iPhone 那条 111 流通常已经被对端拆掉（`clusterStream` 归零 /
            // `clusterContent` 清空），而 `AirPlaySession.setClusterUiShown(false)` 内部
            // 就要求 `clusterStream > 0` **且** `clusterContent != null` —— 此刻必然返回
            // false，发了也没人收。会话都没了，"手机还在画"这件事也随之消失（下次接上
            // 是新 `SETUP`、新流号），故收尾路径不需要、也不应该补这一刀。
            projectionWanted = false
            clusterUiShown = false
            // 「关闭蓝牙适配器」档关过适配器，这里恢复——否则下次无线 CarPlay
            // 会因为连不上蓝牙 bootstrap 直接失败。其余档位本调用是空操作。
            bluetoothHandoffReleaser.onSessionEnded(settings.bluetoothHandoffPolicy)
        }

        override fun onTransportError(message: String) {
            emit(CarSessionEvent.TransportError(message))
        }

        override fun onDeviceInfo(session: AirPlaySession, info: AirPlayDeviceInfo) {
            // 三个字段一次写全：界面上「名称 / ID / 型号」是同一台设备的三个面，
            // 分两次 copy 会出现"名字换了、ID 还是上一台"的中间态。
            updateState {
                it.copy(
                    deviceName = info.name.ifEmpty { null },
                    deviceModel = info.model.ifEmpty { null },
                    deviceId = info.deviceId.ifEmpty { null },
                )
            }
            emit(CarSessionEvent.DeviceConnected(info.name, info.model))
        }

        /**
         * 抓 `disableBluetooth` 里的 `deviceID`（2026-10-10）。
         *
         * iPhone 在 AirPlay 建好后会下发这条命令要求车机断开蓝牙，命令体里带着本次
         * iPhone 的蓝牙地址（实车抓包 `params={deviceID: "1c:8e:2a:49:29:f6"}`）。
         * 上游只用它触发"关自己的 RFCOMM"，地址本身丢掉了；我们留一份，
         * 供握手完成时定向断开（见 [CarBluetoothHandoffReleaser]）。
         *
         * ⚠️ 这里**只记录、不动作**：真正的断点在 `onStatus(WirelessActive)`——
         * 那一刻上游刚关掉 bootstrap socket，顺序才对。
         */
        override fun onCommand(session: AirPlaySession, type: String, params: Map<String, Any?>) {
            val isHandoff = type.equals("disableBluetooth", ignoreCase = true) ||
                type.equals("disable-bluetooth", ignoreCase = true)
            if (!isHandoff) return
            val raw = params.entries
                .firstOrNull { it.key.equals("deviceID", ignoreCase = true) }
                ?.value as? String
            handoffTargetMac = raw?.trim()?.takeIf { it.isNotEmpty() }?.uppercase()
            platform.diagnostics.log(
                TAG,
                "无线握手：iPhone 要求断开蓝牙（deviceID=${handoffTargetMac ?: "未提供"}）",
            )
        }

        override fun onDebugLog(message: String) {
            emit(CarSessionEvent.LogLine(message))
        }
    }

    private class SessionHandle(
        val controller: CarPlayController,
        val sink: CarPlayMediaSink?,
        val locationProvider: UpstreamLocationBridge?,
        /**
         * [CarMfiTarget.LOCAL_OFFLINE] 时本会话起的环回服务，其余目标为 null。
         * 挂在句柄上而不是散落的字段上，是为了保证"detach 时拿到的一定是同一组资源"。
         */
        val localMfi: CarMfiLoopbackServer? = null,
        /**
         * 音频焦点守卫。挂在句柄上而不是散落字段，理由同 [localMfi]：
         * 保证 detach 时释放的一定是**本会话申请到的那一份**焦点，
         * 避免重连交错时把新会话的焦点误释放掉。
         */
        val focusGuard: CarAudioFocusGuard? = null,
        val audioObserver: UpstreamMediaSinkBridge? = null,
    )

    companion object {
        /** 与 upstream `CarPlayHostActivity.CONTROLLER_CLOSE_TIMEOUT_MILLIS` 一致。 */
        const val CONTROLLER_CLOSE_TIMEOUT_MILLIS = 4_000L

        /**
         * 车机原生热点探测结果的复用时限。
         *
         * 比设置页那个（2 分钟）长：会话启动/重连比翻设置页少得多，而重探一次
         * 要好几秒。5 分钟足够覆盖「用户调参数、重连、再调、再重连」的节奏。
         */
        const val NATIVE_PROBE_CACHE_MILLIS = 300_000L

        const val TAG = "session"
    }
}

/**
 * `buildSession()` 的分段计时器。
 *
 * 存在的唯一理由：实车日志里 20 秒空白落点无法从既有日志判断。与其继续猜
 * （猜过一版，被实测证伪），不如让代码**自己报账** —— 每过一个阶段就写一行
 * 「阶段名 + 距上一阶段毫秒数 + 距起点毫秒数」到诊断日志。
 *
 * 刻意用「距上一阶段」而不是绝对时间：20 秒空白属于谁，一眼就能从相邻两行差
 * 值看出来，不需要在时间戳之间做心算。
 *
 * ⚠️ 只在诊断包（`platform.diagnostics`）落盘，**不**发到界面日志流：这些是
 * 给排障用的，不该占据用户能看到的日志面板。
 */
private class StepTimer(private val tag: String, private val platform: CarPlatform) {
    private val startedAt = System.nanoTime()
    private var lastAt = startedAt

    fun mark(label: String) {
        val now = System.nanoTime()
        val sinceLast = (now - lastAt) / 1_000_000
        val sinceStart = (now - startedAt) / 1_000_000
        lastAt = now
        // 慢阶段加 ⚠️：装车后 grep 这一个符号即可定位元凶。
        val flag = if (sinceLast >= 500) "⚠️ 慢" else "ok"
        platform.diagnostics.log(tag, "buildSession 耗时｜$label｜本段=${sinceLast}ms｜累计=${sinceStart}ms｜$flag")
    }
}

/**
 * 把热点 plan 的 `summary` 里的网络参数段改写成**实际生效**的值（2026-10-06）。
 *
 * ## 为什么需要它
 *
 * `summary` 是唯一进诊断日志与人眼的那句话（`hotspot plan: …`）。它由
 * `CarHotspotResolver` 在**建热点之前**拼好，而建好后实际值可能不同
 * （见 `CarPlaySessionBridge.ensureAdbHotspot` 里那段写回注释）。
 * 不改就会在诊断包里长期出现**自相矛盾的两行**——实车 0.10.7 三代日志都是：
 * ```
 * hotspot plan: 自建热点「DBPlay」：wlan0 / 192.168.176.0/24 / 网关 192.168.176.1 / 信道 1
 * adb hotspot ready iface=wlan0 host=192.168.175.1 subnet=192.168.175.0/24 channel=1
 * ```
 * 读日志的人第一反应是"有 bug，但不知道哪个值可信"。
 *
 * ## 只替换参数尾段
 *
 * 保留 `：` 之前那半句（如"自建热点「DBPlay」"，或复用分支那句
 * "未探测到车机热点…"）——那部分讲的是**决策来源**，与网络参数无关，
 * 不该被覆盖。
 *
 * ## 拿不准就原样返回
 *
 * 没有 `：` 锚点、或一个参数都没读到 ⇒ 返回原串。宁可少改一处，
 * 也不能把一句给人看的文案改坏。
 *
 * 抽成**顶层纯函数**是为了单测：`CarPlaySessionBridge` 要 Context，
 * 本模块没有 Robolectric。
 */
internal fun rewriteHotspotSummaryText(
    original: String,
    interfaceName: String?,
    hostAddress: String?,
    subnet: String?,
    channel: Int?,
): String {
    // 一个参数都没读到 ⇒ 无从改写，保持原样（别把 "?" 写进给人看的文案）。
    if (interfaceName.isNullOrBlank() &&
        hostAddress.isNullOrBlank() &&
        subnet.isNullOrBlank() &&
        channel == null
    ) {
        return original
    }
    // 没有决策句 + 参数段的分隔符 ⇒ 不是我们能安全重建的形态，原样返回。
    if (!original.contains("：")) return original
    val head = original.substringBefore("：")
    return buildString {
        append(head).append("：")
        // 网卡名读到了才写；没读到就保留一个占位（调用方通常能给到）。
        append(interfaceName?.takeIf { it.isNotBlank() } ?: "?")
        if (!subnet.isNullOrBlank()) append(" / ").append(subnet)
        if (!hostAddress.isNullOrBlank()) append(" / 网关 ").append(hostAddress)
        if (channel != null) append(" / 信道 ").append(channel)
    }
}
