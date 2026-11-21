package com.shilapi.xcertplay.orchestration

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayContact
import com.shilapi.xcertplay.airplay.AirPlayDeviceInfo
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.iap2.message.Iap2HidMessages
import com.shilapi.xcertplay.iap2.message.Iap2MediaRemoteCommand
import com.shilapi.xcertplay.iap2.message.Iap2NavigationAccumulator
import com.shilapi.xcertplay.iap2.message.Iap2NowPlayingAccumulator
import com.shilapi.xcertplay.navigation.CarPlayNavigationBridge
import com.shilapi.xcertplay.iap2.session.Iap2FileTransferReceiver
import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.media.CarPlayMediaSessionBridge
import com.shilapi.xcertplay.mfi.Iap2MfiAuthenticationClient
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import com.shilapi.xcertplay.mfi.MfiAuthenticationClient
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import com.shilapi.xcertplay.mfi.RemoteMfiAuthenticationClient
import com.shilapi.xcertplay.network.CarPlayBonjour
import com.shilapi.xcertplay.network.CarPlayVpnService
import com.shilapi.xcertplay.network.Kx11FactoryRoutePort
import com.shilapi.xcertplay.network.LegacyWifiP2pGroupManager
import com.shilapi.xcertplay.network.LocalOnlyHotspotManager
import com.shilapi.xcertplay.network.GeelyRoutedHotspotManager
import com.shilapi.xcertplay.network.ManualHotspotManager
import com.shilapi.xcertplay.network.WifiP2pGroupManager
import com.shilapi.xcertplay.network.WirelessHotspotBackend
import com.shilapi.xcertplay.network.WirelessHotspotInfo
import com.shilapi.xcertplay.network.WirelessHotspotManager
import com.shilapi.xcertplay.network.mfiCertificateWifiP2pCredentials
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import com.shilapi.xcertplay.transport.BluetoothRfcommDuplexStream
import com.shilapi.xcertplay.transport.Ch341DeviceMatcher
import com.shilapi.xcertplay.transport.Ch341I2cTransport
import com.shilapi.xcertplay.transport.Ch341UsbHost
import com.shilapi.xcertplay.transport.Ch341UsbSession
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.Iap2LocationProvider
import com.shilapi.xcertplay.transport.Iap2VehicleStatusProvider
import com.shilapi.xcertplay.transport.Iap2UsbMuxHost
import com.shilapi.xcertplay.transport.Iap2UsbSession
import com.shilapi.xcertplay.transport.Iap2WiredCarPlayEndpoint
import com.shilapi.xcertplay.transport.Iap2WiredControlClient
import com.shilapi.xcertplay.transport.Iap2WiredControlTerminal
import com.shilapi.xcertplay.transport.Iap2WirelessCarPlayEndpoint
import com.shilapi.xcertplay.transport.Iap2WirelessControlClient
import com.shilapi.xcertplay.transport.Iap2WirelessControlTerminal
import com.shilapi.xcertplay.transport.Iap2WirelessIdentification
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import com.shilapi.xcertplay.transport.I2cTransport
import com.shilapi.xcertplay.transport.I2cTransportException
import com.shilapi.xcertplay.transport.IphoneCarPlayConfiguration
import com.shilapi.xcertplay.transport.IphoneUsbException
import com.shilapi.xcertplay.transport.IphoneUsbHost
import com.shilapi.xcertplay.transport.IphoneUsbMatcher
import com.shilapi.xcertplay.transport.LinuxI2cTransport
import com.shilapi.xcertplay.transport.LockdownCarKitClient
import com.shilapi.xcertplay.transport.LockdownPairingClient
import com.shilapi.xcertplay.transport.LockdownPairRecord
import com.shilapi.xcertplay.transport.NcmFunctionDiscovery
import com.shilapi.xcertplay.transport.NcmUsbBridge
import java.io.Closeable
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Inet6Address
import java.util.IdentityHashMap
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

sealed class CarPlayStatus {
    data object DiscoveringMfi : CarPlayStatus()
    data object WaitingForMfi : CarPlayStatus()
    data object RequestingMfiPermission : CarPlayStatus()
    data object MfiReady : CarPlayStatus()
    data object StartingHotspot : CarPlayStatus()
    data class HotspotReady(
        val ssid: String,
        val band: String,
        val channel: Int,
        val bssid: String,
        val address: String,
        val backend: String,
    ) : CarPlayStatus()
    data object WaitingForPairedIphone : CarPlayStatus()
    data object ConnectingBluetooth : CarPlayStatus()
    data object RunningWireless : CarPlayStatus()
    data object WirelessActive : CarPlayStatus()
    data object DiscoveringIphone : CarPlayStatus()
    data object WaitingForIphone : CarPlayStatus()
    data object RequestingIphonePermission : CarPlayStatus()
    data object WaitingForReenumeration : CarPlayStatus()
    data object SelectingConfiguration : CarPlayStatus()
    data object OpeningDataPaths : CarPlayStatus()
    data object Pairing : CarPlayStatus()
    data object ConnectingControl : CarPlayStatus()
    data object AttachingNetwork : CarPlayStatus()
    data object RunningControl : CarPlayStatus()
    data object ControlEnded : CarPlayStatus()
    data class Failed(val message: String) : CarPlayStatus()
}

internal fun isWirelessHandoffInProgress(
    handoffRequested: Boolean,
    tunnelActive: Boolean,
    sessionActive: Boolean,
): Boolean = handoffRequested || tunnelActive || sessionActive

/** Keep the old WIFI_P2P Android 9 fallback isolated from DBPlay's opt-in legacy path. */
internal fun effectiveWirelessHotspotMode(mode: WirelessHotspotMode, sdkInt: Int): WirelessHotspotMode =
    if (sdkInt < Build.VERSION_CODES.Q && mode == WirelessHotspotMode.WIFI_P2P) {
        WirelessHotspotMode.LOCAL_ONLY_HOTSPOT
    } else {
        mode
    }

/**
 * 复用档 RFCOMM 重试的**早停判据**（PATCH-0133）。
 *
 * 现场实测：复用车机热点首连失败时，两台已配对手机 × 3 轮 = 6 次 RFCOMM 全败，
 * 耗时 26.7 秒；期间车机侧 `NO_CURRENT_CLIENT`、`clients=0` —— 热点开着，
 * 但一台手机都没关联上来。此时继续轮询不会改变结果（iPhone 根本没在这张网上），
 * 再跑两轮只是把失败时间从 ~4 秒拖到 ~27 秒。
 *
 * ## 判据
 *
 * | 场景 | 结果 | 理由 |
 * | --- | --- | --- |
 * | 热点开 + 0 客户端 + 已完成至少 1 轮 | true | 再轮也没用 |
 * | 热点开 + 有客户端 | false | 手机已在网上，RFCOMM 失败可能是时序 / 隧道问题，继续重试 |
 * | 热点关 | false | 不归本判据管（热点没开不是"没人连"） |
 * | 客户端数读不到（null） | false | **读不到 ≠ 没有**，保持原来的跑满 3 轮 |
 *
 * [round] 是**已完成**的轮次（1 起）。客户端数必须来自已有的只读快照，
 * 不许为了这个判据新增 shell / ip 调用或探针。
 */
internal fun shouldStopRfcommRetry(
    round: Int,
    clientCount: Int?,
    apEnabled: Boolean,
): Boolean = apEnabled && clientCount == 0 && round >= 1

/**
 * Wires the complete wired or wireless CarPlay path: MFi coprocessor discovery, iPhone bring-up,
 * iAP2 control, transport setup, and the AirPlay media/input sessions.
 *
 * All blocking USB/I2C work runs on one worker executor. Status callbacks are delivered on the
 * main thread. This class is the integration seam only and is not evidence of hardware operation.
 */
class CarPlayController(
    context: Context,
    private val config: CarPlayRuntimeConfig,
    private val airPlayConfig: AirPlayConfig,
    private val identity: AirPlayIdentity,
    private val pairings: PairingStore,
    listener: AirPlaySessionListener,
    private val media: AirPlayMediaHandler,
    reportStatus: (CarPlayStatus) -> Unit,
    private val loadPairRecord: () -> LockdownPairRecord? = { null },
    private val savePairRecord: (LockdownPairRecord) -> Unit = {},
    private val clearPairRecord: () -> Unit = {},
    private val locationProvider: Iap2LocationProvider? = null,
    private val onWiredDisconnected: (CarPlayController) -> Unit = {},
    private val vehicleStatusProvider: Iap2VehicleStatusProvider? = null,
) : Closeable {
    init {
        require(!config.locationReportingEnabled || locationProvider != null) {
            "A location provider is required when location reporting is enabled"
        }
    }

    private enum class Phase { IDLE, MFI, WIRELESS, IPHONE, REENUMERATION, DATAPATHS, CONTROL }

    private val appContext = context.applicationContext
    private val usbManager = context.getSystemService(UsbManager::class.java)

    /**
     * 本机蓝牙适配器（2026-10-08 改：由「构造时一次性取」改为惰性 + 就绪判定）。
     *
     * 现场日志（VIN L6TTEST1111111112）里 `BluetoothAdapter` 打了 19 行：
     *
     *   W BluetoothAdapter: getBluetoothService() called with no BluetoothManagerCallback
     *
     * 这是 AOSP 在 **蓝牙服务尚未就绪**时打出的警告——此时 `BluetoothManager.adapter`
     * 拿到的对象内部 binder 还是空的，任何后续调用（`bondedDevices`、`isEnabled`…）
     * 都可能返回空值或再次触发同一警告。
     *
     * 旧写法在 `CarPlayController` **构造时**就取值：控制器是每次会话新建的，
     * 若恰好在车机蓝牙栈重启/开机早期构造，就会把「半成品 adapter」固化下来，
     * 之后整个会话都在用一个不健康的句柄 —— 无线 CarPlay 的蓝牙段会莫名失败，
     * 而日志里只有那行不起眼的 `no BluetoothManagerCallback`。
     *
     * 改成 `by lazy`：真正要用蓝牙时（`selectWirelessBluetoothDevices` 等）才取值，
     * 那时通常已过启动窗口；并且**每个进程只如实提示一次**「当前拿不到可用 adapter」，
     * 避免构造 N 个控制器就刷 N 行。
     */
    private val bluetoothAdapter: BluetoothAdapter?
        by lazy {
            val manager = appContext.getSystemService(BluetoothManager::class.java)
            val adapter = manager?.adapter
            if (adapter == null && bluetoothAdapterWarningIssued.compareAndSet(false, true)) {
                debugLog(
                    "蓝牙适配器当前不可用（BluetoothManager.adapter == null）" +
                        "；无线 CarPlay 的蓝牙段会跳过，仅提示一次",
                )
            }
            adapter
        }

    /** 进程级：蓝牙不可用的提示是否已发出（见 [bluetoothAdapter]）。 */
    private val bluetoothAdapterWarningIssued = AtomicBoolean(false)
    private val iphoneHost = IphoneUsbHost(
        appContext,
        usbManager,
        if (config.iphoneDevices.isNotEmpty()) {
            IphoneUsbMatcher(config.iphoneDevices)
        } else {
            IphoneUsbMatcher.appleVendor()
        },
        // 2026-10-08 移植 DiPlay：USB 读排队"退到 16 KiB 兼容长度"这类**一次性**事件
        // 之前完全没有出口，现场只能看到"时好时坏"。低频（每根管道最多一次）。
        onDiagnostic = { line -> debugLog(line) },
    )
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val touchExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val tunnelExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val hostId = UUID.randomUUID().toString().uppercase(Locale.US)
    private val systemBuid = UUID.randomUUID().toString().uppercase(Locale.US)
    private val lifecycleLock = Any()
    @Volatile private var uiListener: AirPlaySessionListener? = listener
    @Volatile private var uiStatusReporter: ((CarPlayStatus) -> Unit)? = reportStatus
    private val permissionGrant = AtomicBoolean(false)
    private val availabilityPollGeneration = AtomicInteger(0)
    private var permissionPollGeneration = 0
    private var reenumerationAttempts = 0
    private var lastReportedStatus: CarPlayStatus? = null
    private var mfiResetLogged = false

    @Volatile private var closed = false
    @Volatile private var phase = Phase.IDLE
    @Volatile private var ch341Host: Ch341UsbHost? = null
    @Volatile private var mfiSession: MfiSession? = null
    @Volatile private var mux: Iap2UsbMuxHost? = null
    @Volatile private var csm: Iap2Session? = null
    @Volatile private var activeSession: AirPlaySession? = null
    @Volatile private var hotspot: WirelessHotspotManager? = null
    @Volatile private var bonjour: CarPlayBonjour? = null
    @Volatile private var bluetoothSocket: BluetoothSocket? = null
    @Volatile private var bluetoothStream: BluetoothRfcommDuplexStream? = null
    @Volatile private var wirelessTunnelChannel: Iap2Session? = null
    @Volatile private var activeMediaRemoteSession: Iap2Session? = null
    private val fileTransferReceivers = IdentityHashMap<Iap2Session, Iap2FileTransferReceiver>()
    @Volatile private var wirelessIdentification: Iap2IdentificationConfig? = null
    @Volatile private var wirelessAirPlayEndpoint: Iap2WirelessCarPlayEndpoint? = null
    @Volatile private var vpnService: CarPlayVpnService? = null
    @Volatile private var vpnBound = false
    private val wirelessHandoffRequested = AtomicBoolean(false)
    private val wirelessTunnelReady = AtomicBoolean(false)
    private val wirelessActiveReported = AtomicBoolean(false)
    private val wirelessGeneration = AtomicInteger(0)

    /**
     * 「等 iPhone 反向连 AirPlay TCP」这段的任务句柄。
     *
     * ## 为什么需要它（PATCH-0009，2026-10-06）
     *
     * 已有 45 秒 watchdog（[armWirelessHandoffWatchdog]）**覆盖不了**本场景：
     * 它只在收到 iPhone 的 `disableBluetooth` 时才武装，而那条命令**只有在
     * iPhone 已经建立 AirPlay 之后**才可能下发。
     * ⇒ "iPhone 从不连接"时它根本不会被武装，界面永久停在
     * 「无线通道就绪」，**整份诊断包里没有一句报错**。
     *
     * 而两个阻塞点都不会自己退出：
     * - `CarPlayVpnService.acceptLoop` 的 `server.accept()` **没有 setSoTimeout**；
     * - `Iap2WirelessControlClient` 的 `recv(60_000)` 超时后只 `continue`。
     *
     * 这里加一个**独立的** deadline：从置 `RunningWireless` 起计时，
     * 到 iPhone 真正建立 AirPlay（[sessionListener.onSessionActive]）为止。
     * 超时即结束这次尝试并**有界重试**；**不关闭已存在的热点**（见
     * `CarPlaySessionBridge` 的 `keepHotspotAlive` 守卫）。
     */
    @Volatile private var wirelessAttachWatchdog: Runnable? = null
    private val wirelessAttachAttempt = AtomicInteger(0)

    /**
     * 2026-10-21：watchdog 超时后只**请求重试**并释放本次 bootstrap，
     * 由 [runWirelessBootstrapLoop] 按 attempt 决定走 bootstrap-only 还是
     * 第 3 次前的那一次全量重启。这样超时路径不会再无条件
     * `closeWirelessStack()`（拆掉监听/Bonjour/热点）。
     */
    @Volatile private var wirelessBootstrapRetryRequested = false

    /**
     * 末次（第 [WIRELESS_ATTACH_MAX_ATTEMPTS] 次）超时后的迟到连接宽限。
     * 宽限期间 AirPlay 监听 / Bonjour / 热点全部保留；`onSessionActive`
     * 一到就取消它并走成功路径。
     */
    @Volatile private var lateAttachGraceRunnable: Runnable? = null

    private val nowPlaying = Iap2NowPlayingAccumulator()

    /** Per-session budget for the 0x5001 attribute dump. */
    private val nowPlayingAttributeDumpCount = AtomicInteger(0)

    /** DBPlay PATCH-0016: merges the iPhone's route-guidance frames for the HUD bridge. */
    private val navigation = Iap2NavigationAccumulator().apply {
        setDiagnostics { message -> debugLog(message) }
    }
    private val navigationEndCheck = object : Runnable {
        override fun run() {
            if (closed) return
            val state = navigation.expirePendingEnd()
            CarPlayNavigationBridge.publish(this@CarPlayController, state.takeIf { it.hasContent })
            if (navigation.hasPendingEnd()) mainHandler.postDelayed(this, 250L)
        }
    }

    private var permissionCloseable: Closeable? = null
    private var attachCloseable: Closeable? = null
    private var detachCloseable: Closeable? = null
    @Volatile private var wiredDeviceName: String? = null
    private val wiredDisconnectReported = AtomicBoolean(false)
    private var ch341PermissionCloseable: Closeable? = null
    private var vpnLatch = CountDownLatch(1)
    private val teardownComplete = CountDownLatch(1)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            vpnService = (binder as CarPlayVpnService.LocalBinder).service
            vpnLatch.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            vpnService = null
            fail(IphoneUsbException.DeviceUnavailable("CarPlay VPN service disconnected"))
        }
    }

    private val sessionListener = object : AirPlaySessionListener {
        override fun onSessionActive(session: AirPlaySession) {
            activeSession = session
            // iPhone 的反向 AirPlay TCP 已经连上 ⇒ 「等连接」阶段结束，撤销 deadline。
            // 这是本 watchdog 唯一的正常解除点，必须放在最前，避免下面任何一行
            // 抛异常时把已成功的连接误判成超时。
            cancelWirelessAttachWatchdog()
            // 迟到连接宽限同样要撤销：手机在末次超时后的 15s 内连上了，就是成功。
            cancelLateAttachGrace()
            debugLog(
                "AirPlay session active controller=${session.controllerId ?: "unknown"} " +
                    "peer=${session.host}",
            )
            uiListener?.onSessionActive(session)
        }

        override fun onSessionEnded(session: AirPlaySession) {
            val wasActive = activeSession === session
            if (wasActive) activeSession = null
            debugLog("AirPlay session ended peer=${session.host}")
            uiListener?.onSessionEnded(session)
            if (wasActive && config.transport == CarPlayTransport.WIRED) {
                finishWiredConnection("AirPlay session ended")
            }
        }

        override fun onTransportError(message: String) {
            debugLog("AirPlay transport error: $message")
            uiListener?.onTransportError(message)
        }

        override fun onDeviceInfo(session: AirPlaySession, info: AirPlayDeviceInfo) {
            debugLog(
                "AirPlay device info name=${info.name} deviceId=${info.deviceId} " +
                    "wifiMac=${info.wifiMac} model=${info.model}",
            )
            uiListener?.onDeviceInfo(session, info)
        }

        override fun onHostUiRequested(session: AirPlaySession) {
            uiListener?.onHostUiRequested(session)
        }

        override fun onCommand(session: AirPlaySession, type: String, params: Map<String, Any?>) {
            debugLog(
                "AirPlay command type=$type params=${params.keys.sorted().joinToString(",")}",
            )
            if (
                config.transport == CarPlayTransport.WIRELESS &&
                !closed &&
                activeSession === session &&
                isBluetoothHandoffCommand(type) &&
                wirelessHandoffRequested.compareAndSet(false, true)
            ) {
                debugLog(
                    "wireless CarPlay Bluetooth handoff requested; " +
                        "waiting for tunnel iAP2 readiness",
                )
                armWirelessHandoffWatchdog(wirelessGeneration.get())
                maybeCompleteWirelessHandoff()
            }
            uiListener?.onCommand(session, type, params)
        }

        override fun onDebugLog(message: String) {
            debugLog(message)
        }
    }

    fun attachUi(
        listener: AirPlaySessionListener,
        reportStatus: (CarPlayStatus) -> Unit,
    ) {
        uiListener = listener
        uiStatusReporter = reportStatus
        mainHandler.post {
            if (uiListener === listener) lastReportedStatus?.let(reportStatus)
        }
    }

    fun isClosed(): Boolean = closed

    fun hasActiveAirPlayAttachment(): Boolean = synchronized(lifecycleLock) {
        !closed && vpnService?.isAttached() == true
    }

    fun start() {
        synchronized(this) {
            if (closed) return
        }
        // PATCH-0009：一次全新的会话 ⇒ 重试预算归零。
        // ⚠️ 重置点刻意放在 `start()` 而**不是** `startWireless()`：后者也会被
        // `restartWireless()` 调用（即本 watchdog 的重试路径），放那里会把预算
        // 每次重试都清零 ⇒ 退化成无限重试，与"有界"的初衷相反。
        wirelessAttachAttempt.set(0)
        CarPlayMediaSessionBridge.attach(appContext, this, ::sendMediaRemoteCommand)
        // DBPlay PATCH-0016: claim the navigation channel so HUD observers never
        // see the previous session's route before this one's first frame lands.
        CarPlayNavigationBridge.attach(this)
        com.shilapi.xcertplay.power.CarPlayPhonePowerBridge.attach(this)
        if (config.transport == CarPlayTransport.WIRED) {
            permissionCloseable = iphoneHost.registerPermissionReceiver(::onIphonePermission)
            attachCloseable = iphoneHost.registerAttachReceiver(::onIphoneAttached)
            detachCloseable = iphoneHost.registerDetachReceiver { device ->
                if (config.transport == CarPlayTransport.WIRED && device.deviceName == wiredDeviceName) {
                    debugLog("wired USB detached; releasing USB VPN")
                    finishWiredConnection("USB detached")
                }
            }
        }
        startMfi()
    }

    /** Reopens the CH341/MFi path without restarting the app. */
    fun reconnectMfi() = synchronized(lifecycleLock) {
        if (closed) return
        closeMfiSession()
        startMfi()
    }

    /** Re-runs iPhone discovery/bring-up using the already-open MFi session. */
    fun reconnectIphone() = synchronized(lifecycleLock) {
        if (closed) return
        if (mfiSession == null) {
            startMfi()
        } else if (config.transport == CarPlayTransport.WIRELESS) {
            // PATCH-0009：用户手动点「重连」⇒ 重试预算同样归零。
            // 人主动重试与 watchdog 自动重试是两回事，前者应重新拿到完整预算。
            wirelessAttachAttempt.set(0)
            restartWireless()
        } else {
            startIphone()
        }
    }

    fun sendTouch(contacts: List<AirPlayContact>): Boolean {
        if (closed) return false
        val session = activeSession ?: return false
        return try {
            touchExecutor.execute { session.sendTouch(contacts) }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun onIap2Incoming(frame: Iap2Frame) {
        if (frame.messageId == 0xae01 || frame.messageId == 0x4158) {
            com.shilapi.xcertplay.power.CarPlayPhonePowerBridge.accept(this, frame)
            return
        }
        if (frame.messageId == NOW_PLAYING_UPDATE) {
            try {
                CarPlayMediaSessionBridge.publish(this, nowPlaying.update(frame))
                if (nowPlayingAttributeDumpCount.getAndIncrement() < NOW_PLAYING_ATTRIBUTE_DUMP_LIMIT) {
                    debugLog("NowPlaying attrs: " + nowPlaying.describeAttributes(frame))
                }
            } catch (error: RuntimeException) {
                debugLog("Could not decode iAP2 NowPlayingUpdate", error)
            }
            return
        }
        // DBPlay PATCH-0016: route-guidance frames reach the Core but upstream
        // has no decoder for them. Forward whatever the accumulator can extract;
        // it never throws, because a HUD problem must not break the session.
        if (Iap2NavigationAccumulator.isNavigationMessage(frame.messageId)) {
            val state = navigation.update(frame)
            // Skip publishing an empty snapshot: downstream would otherwise show
            // a zeroed navigation card as if a route were active.
            CarPlayNavigationBridge.publish(this, state.takeIf { it.hasContent })
            // State=0 may be the final message. The old lazy expiry waited for
            // another iAP2 frame that never came, leaving the factory card alive.
            mainHandler.removeCallbacks(navigationEndCheck)
            if (navigation.hasPendingEnd()) mainHandler.postDelayed(navigationEndCheck, 3_100L)
        }
    }

    private fun activateMediaRemote(session: Iap2Session) {
        nowPlayingAttributeDumpCount.set(0)
        startFileTransferReceiver(session)
        activeMediaRemoteSession = session
        CarPlayMediaSessionBridge.setControlsAvailable(this, true)
    }

    private fun deactivateMediaRemote(session: Iap2Session) {
        stopFileTransferReceiver(session)
        if (activeMediaRemoteSession !== session) return
        activeMediaRemoteSession = null
        nowPlayingAttributeDumpCount.set(0)
        CarPlayMediaSessionBridge.setControlsAvailable(this, false)
    }

    private fun startFileTransferReceiver(session: Iap2Session) {
        val receiver = synchronized(fileTransferReceivers) {
            if (fileTransferReceivers.containsKey(session)) return
            Iap2FileTransferReceiver(
                session = session,
                onArtwork = { artwork ->
                    CarPlayMediaSessionBridge.publishArtwork(
                        this,
                        artwork.fileTransferId,
                        artwork.bytes,
                    )
                },
                onLog = ::debugLog,
            ).also { fileTransferReceivers[session] = it }
        }
        receiver.start()
    }

    private fun stopFileTransferReceiver(session: Iap2Session) {
        val receiver = synchronized(fileTransferReceivers) {
            fileTransferReceivers.remove(session)
        }
        receiver?.close()
    }

    private fun stopFileTransferReceivers() {
        val receivers = synchronized(fileTransferReceivers) {
            fileTransferReceivers.values.toList().also { fileTransferReceivers.clear() }
        }
        receivers.forEach { it.close() }
    }

    private fun sendMediaRemoteCommand(command: Iap2MediaRemoteCommand): Boolean {
        if (closed) return false
        val session = activeMediaRemoteSession ?: return false
        if (session.isClosed) return false
        return try {
            touchExecutor.execute {
                if (closed || activeMediaRemoteSession !== session || session.isClosed) return@execute
                try {
                    session.send(Iap2HidMessages.mediaReport(command), MEDIA_REMOTE_SEND_TIMEOUT_MILLIS)
                    // Consumer Control buttons are momentary: hold the press long enough for the
                    // phone to register the 0→1 edge before releasing, otherwise it may debounce
                    // the button as a glitch and never change track.
                    Thread.sleep(HID_REPORT_HOLD_MILLIS)
                    session.send(Iap2HidMessages.releaseMediaButtons(), MEDIA_REMOTE_SEND_TIMEOUT_MILLIS)
                } catch (error: Exception) {
                    debugLog("Could not send iAP2 media command $command", error)
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
        }
        closeReceivers()
        stopFileTransferReceivers()
        availabilityPollGeneration.incrementAndGet()
        wirelessGeneration.incrementAndGet()
        nowPlayingAttributeDumpCount.set(0)
        // PATCH-0009：关掉会话必须撤掉 deadline，否则定时器还会在几秒后醒来
        // 去重建一个已经关掉的栈。generation 守卫也能挡住，但显式撤销更清楚。
        cancelWirelessAttachWatchdog()
        permissionPollGeneration += 1
        activeMediaRemoteSession = null
        CarPlayMediaSessionBridge.detach(appContext, this)
        // DBPlay PATCH-0016: release navigation and drop accumulated state, so a
        // later session never inherits this route.
        navigation.reset()
        mainHandler.removeCallbacks(navigationEndCheck)
        CarPlayNavigationBridge.detach(this)
        com.shilapi.xcertplay.power.CarPlayPhonePowerBridge.detach(this)
        touchExecutor.shutdownNow()
        tunnelExecutor.shutdownNow()
        val service = vpnService
        unbindVpn()
        Thread(
            {
                try {
                    if (config.transport == CarPlayTransport.WIRED) {
                        closeBestEffort("USB VPN first") { service?.detachWired() }
                    }
                    if (config.transport == CarPlayTransport.WIRELESS) {
                        closeBestEffort("wireless stack") { closeWirelessStack(service) }
                    } else {
                        closeBestEffort("CSM") { csm?.close() }
                        csm = null
                    }
                    closeBestEffort("USBMUX") { mux?.close() }
                    mux = null
                    if (config.transport == CarPlayTransport.WIRED) {
                        closeBestEffort("VPN/NCM") { service?.detach() }
                    }
                    closeBestEffort("MFi") { mfiSession?.close() }
                    mfiSession = null
                    wirelessIdentification = null
                    wirelessAirPlayEndpoint = null
                    closeBestEffort("location provider") { locationProvider?.close() }
                    closeBestEffort("vehicle status provider") { vehicleStatusProvider?.close() }
                } finally {
                    executor.shutdownNow()
                    try {
                        executor.awaitTermination(EXECUTOR_CLOSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                    teardownComplete.countDown()
                }
            },
            "xcertplay-controller-teardown",
        ).apply {
            isDaemon = true
            start()
        }
    }

    /** Waits for USB, iAP2, MFi and VPN teardown; intended for a non-main lifecycle thread. */
    fun awaitClosed(timeoutMillis: Long): Boolean {
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }
        return try {
            teardownComplete.await(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    /** A closed controller is single-use. Its owner must retire the handle and create a new one. */
    private fun finishWiredConnection(reason: String) {
        if (closed || !wiredDisconnectReported.compareAndSet(false, true)) return
        debugLog("wired connection ended: $reason; retiring controller")
        close()
        // Notify even when AirPlay never became active (there will be no session-ended callback).
        runCatching { onWiredDisconnected(this) }
            .onFailure { debugLog("wired disconnect owner callback failed", it) }
    }

    private fun startMfi() {
        availabilityPollGeneration.incrementAndGet()
        phase = Phase.MFI
        onStatus(CarPlayStatus.DiscoveringMfi)
        when (config.mfiTarget) {
            MfiTarget.USB_CH341 -> {
                debugLog("mfi discovery backend=CH341 devices=${config.ch341Devices}")
                val host = ch341Host ?: Ch341UsbHost(
                    appContext,
                    usbManager,
                    Ch341DeviceMatcher(config.ch341Devices),
                ).also {
                    ch341Host = it
                    ch341PermissionCloseable = it.registerPermissionReceiver(::onCh341Permission)
                }
                checkCh341Mfi(host)
            }
            MfiTarget.I2C -> {
                debugLog("mfi discovery backend=Linux I2C path=${config.linuxI2cPath}")
                openLinuxMfi()
            }
            MfiTarget.REMOTE -> {
                debugLog("mfi discovery backend=Remote server=${config.remoteMfiServer.orEmpty()}")
                openRemoteMfi()
            }
            MfiTarget.LOCAL_FILES -> {
                debugLog("mfi discovery backend=Local documents")
                openLocalMfi()
            }
        }
    }

    private fun openLocalMfi() {
        executor.execute {
            try {
                val resolver = appContext.contentResolver
                val certificateUri = Uri.parse(checkNotNull(config.localMfiCertificateUri))
                val privateKeyUri = Uri.parse(checkNotNull(config.localMfiPrivateKeyUri))
                val client = resolver.openInputStream(certificateUri)?.use { certificateInput ->
                    resolver.openInputStream(privateKeyUri)?.use { privateKeyInput ->
                        LocalMfiAuthenticationClient.load(certificateInput, privateKeyInput)
                    } ?: throw IOException("Could not open the selected local MFi private key")
                } ?: throw IOException("Could not open the selected local MFi certificate")
                val protocolMajor = client.protocolMajor()
                if (closed || phase != Phase.MFI) return@execute
                mfiSession = MfiSession(client, null)
                debugLog("mfi local documents ready protocolMajor=$protocolMajor")
                onStatus(CarPlayStatus.MfiReady)
                startPhone()
            } catch (error: Throwable) {
                fail(error)
            }
        }
    }

    private fun openRemoteMfi() {
        executor.execute {
            try {
                val client = RemoteMfiAuthenticationClient(
                    serverAddress = checkNotNull(config.remoteMfiServer),
                    token = config.remoteMfiToken,
                )
                client.reset()
                val protocolMajor = client.protocolMajor()
                if (closed || phase != Phase.MFI) return@execute
                mfiSession = MfiSession(client, null)
                debugLog(
                    "mfi remote service ready server=${config.remoteMfiServer} " +
                        "protocolMajor=$protocolMajor",
                )
                onStatus(CarPlayStatus.MfiReady)
                startPhone()
            } catch (error: Throwable) {
                fail(error)
            }
        }
    }

    private fun checkCh341Mfi(host: Ch341UsbHost) {
        if (closed || phase != Phase.MFI) return
        val device = host.discover().firstOrNull()
        if (device == null) {
            waitForMfi()
        } else {
            availabilityPollGeneration.incrementAndGet()
            requestCh341Permission(device)
        }
    }

    private fun openLinuxMfi() {
        executor.execute {
            try {
                val transport = LinuxI2cTransport.open(config.linuxI2cPath!!)
                try {
                    mfiSession = MfiSession(MfiRuntime.scan(transport), transport)
                    debugLog("mfi Linux I2C coprocessor ready path=${config.linuxI2cPath}")
                    onStatus(CarPlayStatus.MfiReady)
                    startPhone()
                } catch (error: Throwable) {
                    transport.close()
                    throw error
                }
            } catch (error: MfiCoprocessorNotFoundException) {
                debugLog("mfi Linux discovery failed: ${error.message}")
                waitForMfi()
            } catch (error: Throwable) {
                fail(error)
            }
        }
    }

    private fun requestCh341Permission(device: UsbDevice) {
        try {
            when (val request = ch341Host!!.requestPermission(device)) {
                is Ch341UsbHost.PermissionRequest.AlreadyGranted -> {
                    permissionGrant.set(false)
                    onCh341Permission(Ch341UsbHost.PermissionResult.Granted(request.device))
                }
                is Ch341UsbHost.PermissionRequest.Requested -> {
                    permissionGrant.set(false)
                    onStatus(CarPlayStatus.RequestingMfiPermission)
                    pollCh341Permission(device)
                }
            }
        } catch (error: Throwable) {
            fail(error)
        }
    }

    private fun onCh341Permission(result: Ch341UsbHost.PermissionResult) {
        if (closed || phase != Phase.MFI) return
        when (result) {
            is Ch341UsbHost.PermissionResult.Granted -> {
                // The system broadcast and CarUsbHandler's direct grant can both observe success.
                if (!permissionGrant.compareAndSet(false, true)) return
                permissionPollGeneration++
                openCh341(result.device)
            }
            is Ch341UsbHost.PermissionResult.Denied -> {
                permissionGrant.set(true)
                onStatus(CarPlayStatus.Failed("CH341 USB permission was denied"))
            }
        }
    }

    /** Some car systems grant USB access through CarUsbHandler without delivering a broadcast. */
    private fun pollCh341Permission(device: UsbDevice) {
        val generation = ++permissionPollGeneration
        val deadlineNanos = System.nanoTime() + PERMISSION_POLL_TIMEOUT_MILLIS * 1_000_000L
        val check = object : Runnable {
            override fun run() {
                if (closed || phase != Phase.MFI || generation != permissionPollGeneration) return
                if (usbManager.hasPermission(device)) {
                    onCh341Permission(Ch341UsbHost.PermissionResult.Granted(device))
                    return
                }
                if (System.nanoTime() >= deadlineNanos) {
                    if (permissionGrant.compareAndSet(false, true)) {
                        onStatus(
                            CarPlayStatus.Failed(
                                "MFi USB permission was not granted; reconnect the CH341 to retry",
                            ),
                        )
                    }
                    return
                }
                mainHandler.postDelayed(this, PERMISSION_POLL_INTERVAL_MILLIS)
            }
        }
        mainHandler.postDelayed(check, PERMISSION_POLL_INTERVAL_MILLIS)
    }

    private fun openCh341(device: UsbDevice) {
        ch341Host!!.openAsync(device, executor) { result ->
            when (result) {
                is Ch341UsbHost.OpenResult.Connected -> {
                    val session: Ch341UsbSession = result.session
                    if (closed || phase != Phase.MFI) {
                        session.close()
                        return@openAsync
                    }
                    try {
                        val transport = Ch341I2cTransport(session)
                        config.ch341MfiResetGpio?.let { gpio ->
                            transport.pulseActiveLowReset(gpio)
                            if (!mfiResetLogged) {
                                mfiResetLogged = true
                                debugLog("mfi reset pulse gpio=D$gpio mode=low/high-z")
                            }
                        }
                        val client = MfiRuntime.scan(transport)
                        val probes = mfiCandidateAddresses
                            .associateWith { address -> probeMfiCandidate(transport, address) }
                        for ((address, probe) in probes) {
                            debugLog("mfi probe address=0x${address.toString(16)} ${probe.describe()}")
                        }
                        val selected = preferCertificateBearingAddress(client, transport, probes)
                        debugLog(
                            "mfi coprocessor address=0x${selected.address7Bit.toString(16)} " +
                                "protocolMajor=${selected.protocolMajor()}",
                        )
                        mfiSession = MfiSession(selected, session)
                        debugLog("mfi CH341 session ready")
                        onStatus(CarPlayStatus.MfiReady)
                        startPhone()
                    } catch (error: MfiCoprocessorNotFoundException) {
                        debugLog("mfi CH341 discovery failed: ${error.message}")
                        Log.w(IphoneCarPlayConfiguration.TAG, error.message ?: "MFi discovery failed")
                        session.close()
                        waitForMfi()
                    } catch (error: Throwable) {
                        session.close()
                        fail(error)
                    }
                }
                is Ch341UsbHost.OpenResult.Failed -> when (result.error) {
                    is I2cTransportException.DeviceUnavailable -> waitForMfi()
                    else -> fail(result.error)
                }
            }
        }
    }

    private val mfiCandidateAddresses = listOf(0x10, 0x11)
    private val maxMfiCertificateBytes = 1280

    private data class MfiCandidateProbe(
        val deviceVersion: Int?,
        val firmwareVersion: Int?,
        val protocolMajor: Int?,
        val accessoryCertificateLength: Int?,
        val appleCertificateLength: Int?,
        val failure: String?,
    ) {
        val hasAccessoryCertificate: Boolean
            get() = (accessoryCertificateLength ?: 0) in 1..1280

        fun describe(): String {
            if (failure != null) return failure
            return "deviceVersion=" + hex(deviceVersion) +
                " firmwareVersion=" + hex(firmwareVersion) +
                " protocolMajor=" + hex(protocolMajor) +
                " accessoryCertificateLength=" + accessoryCertificateLength +
                " appleCertificateLength=" + appleCertificateLength
        }

        private fun hex(value: Int?): String =
            if (value == null) "?" else "0x" + value.toString(16).padStart(2, '0')
    }

    private fun probeMfiCandidate(transport: I2cTransport, address7Bit: Int): MfiCandidateProbe = try {
        MfiCandidateProbe(
            deviceVersion = readMfiRegister(transport, address7Bit, 0x00, 1),
            firmwareVersion = readMfiRegister(transport, address7Bit, 0x01, 1),
            protocolMajor = readMfiRegister(transport, address7Bit, 0x02, 1),
            accessoryCertificateLength = readMfiRegister(transport, address7Bit, 0x30, 2),
            appleCertificateLength = readMfiRegister(transport, address7Bit, 0x50, 2),
            failure = null,
        )
    } catch (error: Throwable) {
        MfiCandidateProbe(
            deviceVersion = null,
            firmwareVersion = null,
            protocolMajor = null,
            accessoryCertificateLength = null,
            appleCertificateLength = null,
            failure = "failed: " + error.javaClass.simpleName + ": " + error.message,
        )
    }

    private fun readMfiRegister(
        transport: I2cTransport,
        address7Bit: Int,
        register: Int,
        length: Int,
    ): Int {
        transport.transaction(address7Bit, byteArrayOf(register.toByte()), 0)
        var value = 0
        for (byte in transport.transaction(address7Bit, ByteArray(0), length)) {
            value = (value shl 8) or (byte.toInt() and 0xff)
        }
        return value
    }

    private fun preferCertificateBearingAddress(
        client: MfiAuthenticationClient,
        transport: I2cTransport,
        probes: Map<Int, MfiCandidateProbe>,
    ): MfiAuthenticationClient {
        if (probes[client.address7Bit]?.hasAccessoryCertificate == true) return client
        val alternative = probes.entries.firstOrNull { (address, probe) ->
            address != client.address7Bit && probe.hasAccessoryCertificate
        } ?: return client
        debugLog(
            "mfi address override: 0x" + client.address7Bit.toString(16) +
                " has no accessory certificate; using 0x" + alternative.key.toString(16),
        )
        return MfiAuthenticationClient(transport, alternative.key)
    }

    private fun waitForMfi() {
        if (closed || phase != Phase.MFI) return
        onStatus(CarPlayStatus.WaitingForMfi)
        scheduleAvailabilityPoll(Phase.MFI) {
            when (config.mfiTarget) {
                MfiTarget.USB_CH341 -> ch341Host?.let(::checkCh341Mfi)
                MfiTarget.I2C -> openLinuxMfi()
                MfiTarget.REMOTE, MfiTarget.LOCAL_FILES -> Unit
            }
        }
    }

    private fun startPhone() {
        if (config.locationReportingEnabled) {
            val started = try {
                locationProvider?.start() == true
            } catch (error: Throwable) {
                Log.w(
                    IphoneCarPlayConfiguration.TAG,
                    "Could not prewarm the Android location provider",
                    error,
                )
                false
            }
            debugLog("location provider prewarmed=$started")
        }
        if (config.transport == CarPlayTransport.WIRELESS) {
            startWireless()
        } else {
            startIphone()
        }
    }

    private fun startWireless() {
        // 2026-10-21 状态机不变量：AirPlay 已经 active 时绝不能重入无线起栈。
        // 旧实现里 `restartWireless()` 会 `closeWirelessStack()`，
        // 在会话已成功的情况下等于主动把双流打断。
        if (activeSession != null && phase == Phase.WIRELESS) {
            debugLog("startWireless ignored: wireless AirPlay session already active")
            return
        }
        availabilityPollGeneration.incrementAndGet()
        phase = Phase.WIRELESS
        wirelessHandoffRequested.set(false)
        wirelessTunnelReady.set(false)
        wirelessActiveReported.set(false)
        onStatus(CarPlayStatus.StartingHotspot)
        val generation = wirelessGeneration.incrementAndGet()
        executor.execute {
            runWireless(generation)
        }
    }

    private fun restartWireless() {
        wirelessGeneration.incrementAndGet()
        Thread(
            {
                closeWirelessStack()
                if (!closed) startWireless()
            },
            "xcertplay-wireless-restart",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun runWireless(generation: Int) {
        try {
            debugLog("wireless bring-up generation=$generation starting")
            closeWirelessStack()
            if (
                closed ||
                phase != Phase.WIRELESS ||
                generation != wirelessGeneration.get()
            ) {
                return
            }

            val service = awaitVpnService()
                ?: throw IOException("Could not bind the CarPlay AirPlay service")
            service.detachWired()
            debugLog("wireless bring-up: USB VPN cleared before hotspot setup")

            val mfi = mfiSession?.client
                ?: throw IOException("MFi coprocessor client is unavailable")
            val hotspotInfo = startWirelessHotspot(generation, mfi)
            if (isStaleWirelessRun(generation)) {
                closeWirelessStack()
                return
            }
            val hostAddress = hotspotInfo.hostAddress
                ?: throw IOException(
                    "Wireless hotspot did not provide a usable host address",
                )
            if (
                hostAddress is Inet6Address &&
                (!hostAddress.isLinkLocalAddress || hostAddress.scopeId == 0)
            ) {
                throw IOException(
                    "Wireless hotspot link-local IPv6 address is not scoped",
                )
            }
            val hostAddressText = hostAddressText(hostAddress)
            val deviceIdentifier = hotspotInfo.bssid ?: airPlayConfig.deviceId
            debugLog(
                "wireless hotspot backend=${hotspotInfo.backend.label} " +
                    "iface=${hotspotInfo.interfaceName ?: "unknown"} " +
                    "host=$hostAddressText bssid=${hotspotInfo.bssid ?: "unavailable"} " +
                    "band=${hotspotInfo.bandLabel} channel=${hotspotInfo.channel} " +
                    "frequency=${hotspotInfo.frequencyMHz?.toString() ?: "unknown"}MHz",
            )
            onStatus(
                CarPlayStatus.HotspotReady(
                    ssid = hotspotInfo.ssid,
                    band = hotspotInfo.bandLabel,
                    channel = hotspotInfo.channel,
                    bssid = deviceIdentifier,
                    address = hostAddressText,
                    backend = hotspotInfo.backend.label,
                ),
            )
            onStatus(CarPlayStatus.WaitingForPairedIphone)

            val adapter = bluetoothAdapter
                ?: throw IOException("Bluetooth adapter is unavailable")
            if (!adapter.isEnabled) throw IOException("Bluetooth is not enabled")
            val devices = selectWirelessBluetoothDevices(adapter)
            val hostBluetoothMac = accessoryBluetoothMac(adapter)
            debugLog(
                "wireless Bluetooth PHONE_SMART candidates=${devices.size} " +
                    "localBt=$hostBluetoothMac",
            )
            val wirelessAirPlayConfig = airPlayConfig.copy(
                deviceId = deviceIdentifier,
                btMac = hostBluetoothMac,
                wirelessAudio = true,
            )

            onStatus(CarPlayStatus.AttachingNetwork)
            // DiPlay docs/KX11_ROUTED_HOTSPOT.md 第 6 条：监听地址 = 通告地址，
            // 不再用 `::` 让监听 socket 继承进程默认网络。
            // 总开关关闭时逐字节回到 1.1.42 的 bind `::`。
            val airPlayBindAddress = if (Kx11FactoryRoutePort.ENABLED) {
                hostAddress
            } else {
                InetAddress.getByName("::")
            }
            when (
                val result = service.attachWireless(
                    bindAddress = airPlayBindAddress,
                    config = wirelessAirPlayConfig,
                    identity = identity,
                    pairings = pairings,
                    mfi = mfi,
                    listener = sessionListener,
                    media = media,
                )
            ) {
                CarPlayVpnService.AttachResult.Started -> Unit
                CarPlayVpnService.AttachResult.AlreadyStarted ->
                    throw IOException("Wireless AirPlay transport is already attached")
                is CarPlayVpnService.AttachResult.Failed ->
                    throw IOException(result.message)
            }
            debugLog(
                "wireless AirPlay listener attached bind=${airPlayBindAddress.hostAddress} " +
                    "advertised=$hostAddressText port=${airPlayConfig.port} " +
                    "(primary address unified: listener/mDNS/0x5703)",
            )
            if (isStaleWirelessRun(generation)) {
                closeWirelessStack()
                return
            }

            val bonjourClient = CarPlayBonjour(
                context = appContext,
                config = wirelessAirPlayConfig,
                identity = identity,
                advertisedHost = hostAddress.hostAddress,
                onEvent = { event -> debugLog("wireless bonjour: $event") },
            )
            bonjour = bonjourClient
            bonjourClient.start()
            debugLog("wireless Bonjour services started")
            if (isStaleWirelessRun(generation)) {
                closeWirelessStack()
                return
            }

            // 🔴 2026-10-20 真车实测（VIN 381425，同一台车、同一 SSID=L、0x5703 内容逐字节相同）：
            //   成功：客户端出现 09:07:14.280 → 0x5703 09:07:24.837（+10.6s）→ 0.4s 后 airplay connection accepted
            //   失败：客户端出现 22:04:02.411 → 0x5703 22:04:03.269（+0.86s）→ 40s attach timed out
            // ⇒ 复用原厂热点时，iPhone 刚关联、DHCP 还没走完就进 iAP2，它收下 0x5703 却不发起 AirPlay 连接。
            // 所以复用模式先等客户端网络稳定，再连蓝牙触发 iAP2。
            //
            // 2026-10-21 条件化（PATCH 方案 §1.1）：复用场景大多数时候客户端已经在网上，
            // 无条件 8s 是纯延迟。用已有的只读快照 `hotspotClientCountProvider`
            // （`CarHotspotClientWatcher`）判断：>0 → 2s；=0 或读不到 → 保守 8s。
            if (hotspotInfo.backend == WirelessHotspotBackend.MANUAL_HOTSPOT) {
                val clientCount = runCatching {
                    config.hotspotClientCountProvider?.invoke()
                }.getOrNull()
                // 2026-10-24：固定 8s → 动态 settle。
                // 旧实现四轮实测占全链路 69%，而 iPhone 实际 0.17s 就连上。
                // 新逻辑：一进来就有客户端 → 2s；否则最多等 8s 找客户端，
                // 一旦发现再补 2s（DHCP 收尾），硬上限 10s。
                val waited = ReuseClientSettle.waitForSettle(
                    initialClientCount = clientCount,
                    poll = {
                        runCatching { config.hotspotClientCountProvider?.invoke() }.getOrNull()
                    },
                    sleep = { Thread.sleep(it) },
                    clientSearchMillis = ReuseClientSettle.bootstrapSearchBudget(
                        selfHosted = config.manualHotspotSkipClientSettle,
                    ),
                )
                debugLog(
                    "wireless 复用热点：客户端数=${clientCount ?: "未知"}，" +
                        "动态 settle 等待 ${waited}ms 后进入 iAP2 " +
                        "skipClientSettle=${config.manualHotspotSkipClientSettle}",
                )
            }

            val identification = config.identification.copy(
                wireless = Iap2WirelessIdentification(hostBluetoothMac, hotspotInfo.ssid),
            )
            val endpoint = Iap2WirelessCarPlayEndpoint(
                ssid = hotspotInfo.ssid,
                passphrase = hotspotInfo.passphrase,
                channel = hotspotInfo.channel,
                security = hotspotInfo.security,
                ipAddresses = listOf(hostAddressText),
                airPlayPort = airPlayConfig.port,
                deviceIdentifier = deviceIdentifier,
                publicKey = identity.publicKeyHex,
                sourceVersion = airPlayConfig.sourceVersion,
            )
            // 把即将下发给 iPhone 的关联凭据写进日志：iPhone 一直不来连 AirPlay 时，
            // 第一件要核对的正是"它被要求接的到底是哪张网"。密码只记长度，不记内容。
            debugLog(
                "wireless advertise SSID=\"${endpoint.ssid}\" " +
                    "passphraseLength=${endpoint.passphrase.length} " +
                    "security=${endpoint.security.name} ip=$hostAddressText",
            )
            wirelessIdentification = identification
            wirelessAirPlayEndpoint = endpoint
            media.setIapTunnelHandler(::startWirelessTunnelControl)

            // 2026-10-21：setup（热点/监听/Bonjour/endpoint）只跑一次；
            // RFCOMM + iAP2 + 等 AirPlay accept 放进有界重试循环。
            runWirelessBootstrapLoop(
                generation = generation,
                devices = devices,
                mfi = mfi,
                identification = identification,
                endpoint = endpoint,
            )
        } catch (error: Throwable) {
            if (closed || generation != wirelessGeneration.get()) {
                return
            }
            // 2026-10-21 状态机不变量：`activeSession != null` 是最高优先级成功态。
            // 旧实现只要 `wirelessActiveReported == false` 就拆栈 + fail，
            // 会把已经 active 的 AirPlay 会话一起打掉（「双流 active 但界面停在无线通道就绪」）。
            if ((activeSession != null || wirelessActiveReported.get()) && error !is Error) {
                debugLog("bootstrap failed after AirPlay active; keeping session: ${error.message}")
            } else {
                debugLog("wireless bring-up failed", error)
                closeWirelessStack()
                if (error is Error) throw error
                fail(error)
            }
        }
    }

    /**
     * 2026-10-21：无线 bootstrap 循环（RFCOMM + iAP2 + 等 AirPlay accept）。
     *
     * 每次迭代 = 一次「尝试总预算」：[WIRELESS_ATTACH_TIMEOUT_MILLIS]
     * 在 [connectWirelessBluetoothDevices] **之前**武装，覆盖 RFCOMM + iAP2 +
     * AirPlay accept 整段（旧实现从 RFCOMM 连上后才起算，首次 advertise 白等）。
     *
     * 重试分档（含首次共 [WIRELESS_ATTACH_MAX_ATTEMPTS] 次）：
     * - 第 2 次：bootstrap-only —— 保留 AirPlay 监听 / Bonjour / 热点，只重建 RFCOMM + iAP2；
     * - 第 3 次：全量重启（[restartWireless]），覆盖「监听 / accept 循环已坏」；
     * - 第 4 次：新栈上的 bootstrap-only；
     * - 第 4 次仍超时：进入 [LATE_ATTACH_GRACE_MILLIS] 迟到连接宽限，不立即 fail。
     *
     * 不变量：`activeSession != null` 时任何分支都不得拆栈、不得 fail。
     * bootstrap-only 重试不递增 [wirelessGeneration]，迟到连接仍然有效。
     */
    private fun runWirelessBootstrapLoop(
        generation: Int,
        devices: List<BluetoothDevice>,
        mfi: MfiAuthenticator,
        identification: Iap2IdentificationConfig,
        endpoint: Iap2WirelessCarPlayEndpoint,
    ) {
        while (true) {
            if (isStaleWirelessRun(generation) || activeSession != null) return
            wirelessBootstrapRetryRequested = false
            val attempt = armWirelessAttachWatchdog(generation)

            val channel = try {
                connectWirelessBluetoothDevices(devices, generation)
            } catch (error: Throwable) {
                if (handleWirelessBootstrapFailure(generation, attempt, error)) continue else return
            }
            // 连接期间可能已经发生全量重启/用户停止，旧 socket 不再属于当前 generation。
            if (isStaleWirelessRun(generation)) return
            // 成功态优先：AirPlay 已经 active 时不再上报「无线通道就绪」。
            if (activeSession == null) {
                onStatus(CarPlayStatus.RunningWireless)
            }
            debugLog(
                "wireless Bluetooth iAP2 control starting " +
                    "(attempt=$attempt/$WIRELESS_ATTACH_MAX_ATTEMPTS)",
            )

            val result = try {
                Iap2WirelessControlClient(
                    session = channel,
                    mfi = Iap2MfiAuthenticationClient(mfi),
                ).run(
                    identification = identification,
                    endpoint = endpoint,
                    bringUpTimeoutMillis = CONTROL_BRING_UP_TIMEOUT_MILLIS,
                    locationProvider = locationProvider,
                    vehicleStatusProvider = vehicleStatusProvider,
                    onReady = { activateMediaRemote(channel) },
                    onStopped = { deactivateMediaRemote(channel) },
                    onIncoming = ::onIap2Incoming,
                    onProgress = ::debugLog,
                )
            } catch (error: Throwable) {
                if (handleWirelessBootstrapFailure(generation, attempt, error)) continue else return
            }
            if (isStaleWirelessRun(generation)) return
            if (activeSession != null || wirelessActiveReported.get()) {
                debugLog(
                    "wireless bootstrap attempt finished after AirPlay active; " +
                        "keeping the Wi-Fi AirPlay session",
                )
                return
            }
            debugLog(
                "wireless RFCOMM EOF: iap2State=${result.stage} " +
                    "wirelessCarPlayAvailable=${result.wirelessCarPlayAvailableSeen} " +
                    "transportIdentifier=${result.transportNotificationSeen} " +
                    "carPlayStartSessions=${result.carPlayStartSessionsSent} " +
                    "postTransportConfigs=${result.postTransportWiFiConfigurationsSent} " +
                    "handoffRequested=${wirelessHandoffRequested.get()} " +
                    "tunnelReady=${wirelessTunnelReady.get()} " +
                    "wirelessActive=${wirelessActiveReported.get()}",
            )
            if (
                isWirelessHandoffInProgress(
                    handoffRequested = wirelessHandoffRequested.get(),
                    tunnelActive = wirelessTunnelChannel != null,
                    sessionActive = activeSession != null,
                )
            ) {
                debugLog(
                    "wireless Bluetooth bootstrap closed during handoff; " +
                        "keeping the Wi-Fi AirPlay tunnel alive",
                )
                return
            }
            val failure = IOException(
                "Wireless CarPlay control channel closed before tunnel iAP2 ready",
            )
            if (handleWirelessBootstrapFailure(generation, attempt, failure)) continue else return
        }
    }

    /**
     * 一次 bootstrap 尝试失败后的统一处置。
     *
     * @return true = 循环继续下一次尝试；false = 循环结束
     *   （已成功 / 已失效 / 已触发全量重启 / 已进入末次迟到宽限）。
     */
    private fun handleWirelessBootstrapFailure(
        generation: Int,
        attempt: Int,
        error: Throwable,
    ): Boolean {
        if (isStaleWirelessRun(generation)) return false
        if (activeSession != null) {
            // 成功态绝不被后续失败覆盖。
            debugLog("bootstrap failed after AirPlay active; keeping session", error)
            return false
        }
        cancelWirelessAttachWatchdog()
        if (attempt >= WIRELESS_ATTACH_MAX_ATTEMPTS) {
            // 末次失败：保留监听/Bonjour/热点，再给 15 秒迟到连接宽限。
            armLateAttachGrace(generation, attempt)
            return false
        }
        if (attempt == 2) {
            // 第 3 次尝试必须跑在**新栈**上：这是唯一一次全量重启。
            // `restartWireless()` 会递增 generation，旧监听的回调由 stale 检查丢弃；
            // `wirelessAttachAttempt` 不归零（继续走到第 4 次）。
            debugLog(
                "wireless attach retrying with a full stack restart " +
                    "(attempt=${attempt + 1}/$WIRELESS_ATTACH_MAX_ATTEMPTS): " +
                    (error.message ?: error.javaClass.simpleName),
            )
            restartWireless()
            return false
        }
        // 第 2 次与第 4 次：bootstrap-only —— 不重建 AirPlay 监听 / Bonjour /
        // 热点，也不重新 advertise；只重建 RFCOMM + iAP2。
        debugLog(
            "wireless attach retrying (bootstrap-only " +
                "attempt=${attempt + 1}/$WIRELESS_ATTACH_MAX_ATTEMPTS): " +
                (error.message ?: error.javaClass.simpleName) +
                "；" + wirelessAttachHint(),
        )
        closeBluetoothBootstrapTransport()
        sleepQuietly(WIRELESS_ATTACH_RETRY_INTERVAL_MILLIS)
        return !isStaleWirelessRun(generation) && activeSession == null
    }

    private fun startWirelessTunnelControl(stream: BlockingDuplexByteStream): Boolean {
        if (closed || config.transport != CarPlayTransport.WIRELESS) return false
        val identification = wirelessIdentification ?: return false
        val endpoint = wirelessAirPlayEndpoint ?: return false
        val mfi = mfiSession?.client ?: return false
        debugLog("wireless type-130 tunnel data stream accepted")
        val channel = try {
            Iap2Session.openTunnel(
                stream,
                traceContext = "wireless-tunnel",
                onTrace = ::debugLog,
            )
        } catch (error: Throwable) {
            debugLog("Could not open the tunneled iAP2 link", error)
            return false
        }
        wirelessTunnelChannel = channel
        val generation = wirelessGeneration.get()
        debugLog("wireless iAP2 tunnel control starting")
        return try {
            tunnelExecutor.execute {
                try {
                    val result = Iap2WirelessControlClient(
                        session = channel,
                        mfi = Iap2MfiAuthenticationClient(mfi),
                    ).run(
                        identification = identification,
                        endpoint = endpoint,
                        bringUpTimeoutMillis = Iap2WirelessControlClient.NO_TIMEOUT_MILLIS,
                        locationProvider = locationProvider,
                        vehicleStatusProvider = vehicleStatusProvider,
                        onReady = {
                            activateMediaRemote(channel)
                            onWirelessTunnelReady(generation)
                        },
                        onStopped = { deactivateMediaRemote(channel) },
                        onIncoming = ::onIap2Incoming,
                        onProgress = { message -> debugLog("iAP tunnel $message") },
                    )
                    if (closed || generation != wirelessGeneration.get()) return@execute
                    when (result.terminal) {
                        Iap2WirelessControlTerminal.CHANNEL_CLOSED ->
                            onStatus(CarPlayStatus.Failed("Wireless iAP2 tunnel closed"))
                    }
                } catch (error: Throwable) {
                    if (!closed && generation == wirelessGeneration.get()) {
                        debugLog("tunneled iAP2 control failed", error)
                        onStatus(
                            CarPlayStatus.Failed(
                                error.message ?: error.javaClass.simpleName,
                            ),
                        )
                    }
                } finally {
                    if (wirelessTunnelChannel === channel) wirelessTunnelChannel = null
                }
            }
            true
        } catch (error: Throwable) {
            if (wirelessTunnelChannel === channel) wirelessTunnelChannel = null
            closeBestEffort("tunneled iAP2 link") { channel.close() }
            debugLog("iAP2 tunnel executor rejected the link", error)
            false
        }
    }

    private fun onWirelessTunnelReady(generation: Int) {
        if (
            closed ||
            phase != Phase.WIRELESS ||
            generation != wirelessGeneration.get()
        ) {
            return
        }
        wirelessTunnelReady.set(true)
        debugLog(
            "wireless iAP2 tunnel ready; " +
                "handoffRequested=${wirelessHandoffRequested.get()}",
        )
        maybeCompleteWirelessHandoff()
    }

    private fun maybeCompleteWirelessHandoff() {
        if (!wirelessHandoffRequested.get() || !wirelessTunnelReady.get()) return
        if (!wirelessActiveReported.compareAndSet(false, true)) return
        val generation = wirelessGeneration.get()
        Thread(
            {
                if (
                    closed ||
                    phase != Phase.WIRELESS ||
                    generation != wirelessGeneration.get()
                ) {
                    return@Thread
                }
                debugLog("wireless handoff ready; closing Bluetooth bootstrap transport")
                closeBluetoothBootstrapTransport()
                onStatus(CarPlayStatus.WirelessActive)
            },
            "xcertplay-wireless-handoff",
        ).apply {
            isDaemon = true
            start()
        }
    }

    /**
     * PATCH-0009：武装「等 iPhone 反向连 AirPlay」的单次尝试 deadline。
     *
     * ## 与 [armWirelessHandoffWatchdog] 的分工（**两个 watchdog 管两段，不重叠**）
     *
     * | | 本 watchdog | `armWirelessHandoffWatchdog` |
     * |---|---|---|
     * | 起点 | 每次 bootstrap 尝试开始（**RFCOMM 连接之前**） | 收到 `disableBluetooth`（AirPlay 已建） |
     * | 等什么 | RFCOMM + iAP2 + iPhone **来连** AirPlay TCP | 隧道 iAP2 **ready** |
     * | 解除 | `onSessionActive` / 尝试结束 | `maybeCompleteWirelessHandoff` |
     *
     * 2026-10-21：武装点从「RFCOMM 连上之后」前移到
     * [connectWirelessBluetoothDevices] **之前**。旧实现把 RFCOMM/iAP2 的时间
     * 排除在预算外，22:05 现场首次 advertise 白等了 60s。
     *
     * ## 超时动作
     *
     * - 非末次：只置 [wirelessBootstrapRetryRequested] 并
     *   [closeBluetoothBootstrapTransport]（**不拆**监听/Bonjour/热点），
     *   由 bootstrap 循环决定下一次是 bootstrap-only 还是全量重启；
     * - 末次：进入 [armLateAttachGrace] 的 15 秒迟到连接宽限，不立即 `fail()`。
     *
     * 返回本次尝试序号（从 1 开始，含首次）。
     */
    private fun armWirelessAttachWatchdog(generation: Int): Int {
        cancelWirelessAttachWatchdog()
        val attempt = wirelessAttachAttempt.incrementAndGet()
        val watchdog = Runnable {
            if (
                closed ||
                phase != Phase.WIRELESS ||
                generation != wirelessGeneration.get() ||
                activeSession != null
            ) {
                return@Runnable
            }
            debugLog(
                "wireless attach timed out: iPhone did not open the AirPlay connection " +
                    "within ${WIRELESS_ATTACH_TIMEOUT_MILLIS}ms " +
                    "(attempt=$attempt/$WIRELESS_ATTACH_MAX_ATTEMPTS)；" +
                    wirelessAttachHint(),
            )
            if (attempt >= WIRELESS_ATTACH_MAX_ATTEMPTS) {
                armLateAttachGrace(generation, attempt)
                return@Runnable
            }
            // 只请求重试 + 释放本次 bootstrap；监听/Bonjour/热点全部保留。
            // 全量重启只由 `handleWirelessBootstrapFailure()` 在第 2 次失败后触发一次。
            wirelessBootstrapRetryRequested = true
            Thread(
                {
                    if (
                        closed ||
                        generation != wirelessGeneration.get() ||
                        activeSession != null
                    ) {
                        return@Thread
                    }
                    closeBluetoothBootstrapTransport()
                },
                "xcertplay-wireless-attach-timeout",
            ).apply {
                isDaemon = true
                start()
            }
        }
        wirelessAttachWatchdog = watchdog
        mainHandler.postDelayed(watchdog, WIRELESS_ATTACH_TIMEOUT_MILLIS)
        return attempt
    }

    /**
     * 末次超时后的 15 秒迟到连接宽限（LATE_ATTACH_GRACE）。
     *
     * 现场证据（2026-10-21 报告 §2.7）：21:50:20.275 超时 →
     * 21:50:30.597 accepted（+10.3s）。手机确实可能在超时之后才连上。
     * 宽限期间 **AirPlay 监听 / Bonjour / 热点全部保留**；
     * `onSessionActive` 一到就 [cancelLateAttachGrace] 并走成功路径；
     * 只有宽限到期且 `activeSession` 仍为 null 才 `fail()`。
     */
    private fun armLateAttachGrace(generation: Int, attempt: Int) {
        cancelWirelessAttachWatchdog()
        cancelLateAttachGrace()
        debugLog(
            "wireless attach timed out (attempt=$attempt/$WIRELESS_ATTACH_MAX_ATTEMPTS); " +
                "grace ${LATE_ATTACH_GRACE_MILLIS}ms（保留 AirPlay 监听/Bonjour/热点）",
        )
        val grace = Runnable {
            if (
                closed ||
                phase != Phase.WIRELESS ||
                generation != wirelessGeneration.get()
            ) {
                return@Runnable
            }
            if (activeSession != null) {
                debugLog("late attach grace: AirPlay session already active; success")
                return@Runnable
            }
            lateAttachGraceRunnable = null
            // 让仍阻塞在 Iap2WirelessControlClient 的循环看到 generation 变化后直接退出，
            // 避免 fail 之后又被当成一次可重试的失败。
            wirelessGeneration.incrementAndGet()
            // 与 onSessionActive 的竞态再收一次：它可能恰好在上面那次检查之后、
            // 这里之前把 activeSession 写好。成功态优先，绝不拆刚成功的会话。
            if (activeSession != null) {
                debugLog("late attach grace: AirPlay session became active; success")
                return@Runnable
            }
            closeWirelessStack()
            fail(
                IOException(
                    "无线 CarPlay 未连上：等待 iPhone 建立连接超时" +
                        "（已尝试 $attempt 次，并额外等待 " +
                        "${LATE_ATTACH_GRACE_MILLIS / 1000} 秒迟到连接）。" +
                        wirelessAttachHint(),
                ),
            )
        }
        lateAttachGraceRunnable = grace
        mainHandler.postDelayed(grace, LATE_ATTACH_GRACE_MILLIS)
    }

    private fun cancelLateAttachGrace() {
        val grace = lateAttachGraceRunnable ?: return
        lateAttachGraceRunnable = null
        mainHandler.removeCallbacks(grace)
    }

    /**
     * 「iPhone 没来连 AirPlay」时给用户的动作提示。
     *
     * 注意：:shared 层拿不到 :car:core-bridge 的读热点实现，
     * 只能引用传给本层的 RuntimeConfig（config.manualHotspotSsid）。
     */
    private fun wirelessAttachHint(): String {
        // 2026-10-20 现场复核：车机侧只能证明「没有 AirPlay 连接」，不能证明
        // iPhone 有没有关联热点（客户端列表可能是另一台设备）。所以文案不再下
        // 「iPhone 没连上热点」的结论，而是先让用户**强制重新关联** —— 现场
        // 失败包里 TCAM 客户端地址是 .6，用户手机后来重新连上拿到 .7 才恢复；
        // 重新关联也是唯一不依赖系统权限、能重建 TCAM 侧客户端转发表的现场动作。
        val hotspotSsid = config.manualHotspotSsid?.takeIf { it.isNotBlank() }
        return if (hotspotSsid != null) {
            "车机侧没有收到 iPhone 的 AirPlay 连接。" +
                "请先把手机 Wi-Fi 关掉再打开（强制重新关联），确认连上「$hotspotSsid」；" +
                "若手机里还存着这张热点的旧密码，请先「忽略此网络」再重连。"
        } else {
            "车机侧没有收到 iPhone 的 AirPlay 连接。" +
                "请先把手机 Wi-Fi 关掉再打开（强制重新关联），确认连上本车释放的热点；" +
                "若手机里还存着这张热点的旧密码，请先「忽略此网络」再重连。"
        }
    }

    private fun cancelWirelessAttachWatchdog() {
        val watchdog = wirelessAttachWatchdog ?: return
        wirelessAttachWatchdog = null
        mainHandler.removeCallbacks(watchdog)
    }

    private fun armWirelessHandoffWatchdog(generation: Int) {
        // 进入「等隧道 iAP2」这一段 ⇒ 「等 iPhone 来连」那一段必然已成功
        // （`disableBluetooth` 只可能在 AirPlay 建好之后下发）。
        // 正常路径上 `onSessionActive` 已经撤过，这里再撤一次是防御性的：
        // 万一将来有别的路径能走到这一支，也不会留下一个会误杀的定时器。
        cancelWirelessAttachWatchdog()
        mainHandler.postDelayed(
            {
                if (
                    closed ||
                    phase != Phase.WIRELESS ||
                    generation != wirelessGeneration.get() ||
                    !wirelessHandoffRequested.get() ||
                    wirelessActiveReported.get()
                ) {
                    return@postDelayed
                }
                debugLog(
                    "wireless handoff timed out waiting for tunnel iAP2 readiness; " +
                        "continuing with Bluetooth iAP2",
                )
            },
            WIRELESS_HANDOFF_TIMEOUT_MILLIS,
        )
    }

    private fun closeBluetoothBootstrapTransport() {
        val activeCsm = csm
        csm = null
        if (activeCsm != null) closeBestEffort("wireless CSM") { activeCsm.close() }

        val activeStream = bluetoothStream
        bluetoothStream = null
        if (activeStream != null) closeBestEffort("wireless RFCOMM stream") { activeStream.close() }

        val activeSocket = bluetoothSocket
        bluetoothSocket = null
        if (activeSocket != null) closeBestEffort("wireless Bluetooth socket") { activeSocket.close() }
    }

    private fun startIphone() {
        availabilityPollGeneration.incrementAndGet()
        phase = Phase.IPHONE
        reenumerationAttempts = 0
        onStatus(CarPlayStatus.DiscoveringIphone)
        checkIphoneAvailability()
    }

    private fun checkIphoneAvailability() {
        if (closed || phase != Phase.IPHONE) return
        val device = iphoneHost.discover().firstOrNull()
        if (device == null) {
            onStatus(CarPlayStatus.WaitingForIphone)
            scheduleAvailabilityPoll(Phase.IPHONE, ::checkIphoneAvailability)
        } else {
            debugLog(
                "wired iPhone discovered vid=0x${device.vendorId.toString(16)} " +
                    "pid=0x${device.productId.toString(16)}",
            )
            availabilityPollGeneration.incrementAndGet()
            requestIphonePermission(device)
        }
    }

    private fun requestIphonePermission(device: UsbDevice) {
        mainHandler.post { doRequestIphonePermission(device) }
    }

    private fun doRequestIphonePermission(device: UsbDevice) {
        if (closed) return
        try {
            when (val request = iphoneHost.requestPermission(device)) {
                is IphoneUsbHost.PermissionRequest.AlreadyGranted -> {
                    debugLog("wired iPhone USB permission already granted")
                    permissionGrant.set(false)
                    onIphonePermission(IphoneUsbHost.PermissionResult.Granted(request.device))
                }
                is IphoneUsbHost.PermissionRequest.Requested -> {
                    debugLog("wired iPhone USB permission requested")
                    permissionGrant.set(false)
                    onStatus(CarPlayStatus.RequestingIphonePermission)
                    pollIphonePermission(device)
                }
            }
        } catch (error: Throwable) {
            fail(error)
        }
    }

    private fun onIphonePermission(result: IphoneUsbHost.PermissionResult) {
        when (result) {
            is IphoneUsbHost.PermissionResult.Granted -> {
                // The system broadcast and the polling fallback can both observe the grant.
                if (!permissionGrant.compareAndSet(false, true)) return
                debugLog("wired iPhone USB permission granted")
                permissionPollGeneration++
                when (phase) {
                    Phase.REENUMERATION, Phase.IPHONE -> {
                        if (IphoneCarPlayConfiguration.find(result.device) != null) {
                            openDataPaths(result.device)
                        } else if (reenumerationAttempts < MAXIMUM_REENUMERATION_ATTEMPTS) {
                            beginReenumeration(result.device)
                        } else {
                            fail(
                                IphoneUsbException.Protocol(
                                    "iPhone did not expose a complete CarPlay USB configuration",
                                ),
                            )
                        }
                    }
                    else -> Unit
                }
            }
            is IphoneUsbHost.PermissionResult.Denied -> {
                permissionGrant.set(true)
                onStatus(CarPlayStatus.Failed("iPhone USB permission was denied"))
            }
        }
    }

    /** Some Android builds grant the dialog without delivering the permission broadcast. */
    private fun pollIphonePermission(device: UsbDevice) {
        val generation = ++permissionPollGeneration
        val deadlineNanos = System.nanoTime() + PERMISSION_POLL_TIMEOUT_MILLIS * 1_000_000L
        val check = object : Runnable {
            override fun run() {
                if (closed || generation != permissionPollGeneration) return
                if (usbManager.hasPermission(device)) {
                    onIphonePermission(IphoneUsbHost.PermissionResult.Granted(device))
                    return
                }
                if (System.nanoTime() >= deadlineNanos) {
                    if (permissionGrant.compareAndSet(false, true)) {
                        onStatus(
                            CarPlayStatus.Failed(
                                "iPhone USB permission was not granted; tap Reconnect iPhone to retry",
                            ),
                        )
                    }
                    return
                }
                mainHandler.postDelayed(this, PERMISSION_POLL_INTERVAL_MILLIS)
            }
        }
        mainHandler.postDelayed(check, PERMISSION_POLL_INTERVAL_MILLIS)
    }

    private fun beginReenumeration(device: UsbDevice) {
        phase = Phase.REENUMERATION
        reenumerationAttempts += 1
        onStatus(CarPlayStatus.SelectingConfiguration)
        iphoneHost.requestCarPlayReenumerationAsync(device, executor) { transition ->
            when (transition) {
                IphoneUsbHost.TransitionResult.ReenumerationRequested -> {
                    onStatus(CarPlayStatus.WaitingForReenumeration)
                    // Some head units miss ACTION_USB_DEVICE_ATTACHED on re-enumeration.
                    // Do not wait forever for that broadcast: observe the new device identity.
                    val pollGeneration = availabilityPollGeneration.get()
                    val deadline = System.nanoTime() + 15_000_000_000L
                    val poll = object : Runnable {
                        override fun run() {
                            if (closed || phase != Phase.REENUMERATION ||
                                availabilityPollGeneration.get() != pollGeneration) return
                            val replacement = iphoneHost.discover().firstOrNull {
                                it.deviceId != device.deviceId && IphoneCarPlayConfiguration.find(it) != null
                            }
                            if (replacement != null) {
                                debugLog("wired re-enumeration discovered by polling id=${replacement.deviceId}")
                                onIphoneAttached(replacement)
                            } else if (System.nanoTime() < deadline) {
                                mainHandler.postDelayed(this, DEVICE_AVAILABILITY_POLL_INTERVAL_MILLIS)
                            } else {
                                fail(IphoneUsbException.DeviceUnavailable("USB re-enumeration timed out; no CarPlay device appeared"))
                            }
                        }
                    }
                    mainHandler.post(poll)
                }
                is IphoneUsbHost.TransitionResult.Failed -> fail(transition.error)
            }
        }
    }

    private fun onIphoneAttached(device: UsbDevice) {
        when (phase) {
            Phase.REENUMERATION, Phase.IPHONE -> {
                availabilityPollGeneration.incrementAndGet()
                requestIphonePermission(device)
            }
            else -> Unit
        }
    }

    private fun scheduleAvailabilityPoll(phase: Phase, check: () -> Unit) {
        val generation = availabilityPollGeneration.get()
        mainHandler.postDelayed(
            {
                if (
                    !closed &&
                    this.phase == phase &&
                    generation == availabilityPollGeneration.get()
                ) {
                    check()
                }
            },
            DEVICE_AVAILABILITY_POLL_INTERVAL_MILLIS,
        )
    }

    private fun openDataPaths(device: UsbDevice) {
        wiredDeviceName = device.deviceName
        phase = Phase.DATAPATHS
        debugLog("wired opening iPhone USB data paths")
        onStatus(CarPlayStatus.SelectingConfiguration)
        onStatus(CarPlayStatus.OpeningDataPaths)
        iphoneHost.openIap2UsbSessionAsync(device, executor) { result ->
            when (result) {
                is IphoneUsbHost.Iap2SessionResult.Connected -> {
                    if (closed) {
                        result.session.close()
                        return@openIap2UsbSessionAsync
                    }
                    try {
                        val ncm = openNcm(device)
                        runStack(result.session, ncm)
                    } catch (error: Throwable) {
                        result.session.close()
                        fail(error)
                    }
                }
                is IphoneUsbHost.Iap2SessionResult.Failed -> fail(result.error)
            }
        }
    }

    private fun openNcm(device: UsbDevice): NcmUsbBridge {
        val configuration = IphoneCarPlayConfiguration.find(device)
            ?: throw IphoneUsbException.Protocol(
                "iPhone exposes no CarPlay configuration for NCM",
            )
        val function = NcmFunctionDiscovery.find(configuration)
            ?: throw IphoneUsbException.Protocol("iPhone configuration does not expose an NCM function")
        debugLog(
            "ncm config=${configuration.id} control=${function.control.id}/${function.control.alternateSetting}" +
                " data=${function.data.id}/${function.data.alternateSetting}" +
                " status=${function.statusIn?.address?.let { "0x${it.toString(16)}" } ?: "none"}" +
                " in=0x${function.bulkIn.address.toString(16)} out=0x${function.bulkOut.address.toString(16)}",
        )
        val connection = usbManager.openDevice(device)
            ?: throw IphoneUsbException.DeviceUnavailable("Could not open the iPhone NCM connection")
        return NcmUsbBridge.open(connection, function, onDiagnostic = { line -> debugLog(line) })
    }

    private fun runStack(usbSession: Iap2UsbSession, ncm: NcmUsbBridge) {
        phase = Phase.CONTROL
        var ncmOwnedLocally = true
        try {
            if (closed) return
            val mux = Iap2UsbMuxHost.open(usbSession, onDiagnostic = { line -> debugLog(line) })
            this.mux = mux
            debugLog("wired USBMUX host opened")
            onStatus(CarPlayStatus.Pairing)
            val pairingClient = LockdownPairingClient(mux)
            val savedPairRecord = loadPairRecord()
            var pairRecord = savedPairRecord ?: pairNewRecord(pairingClient)
            debugLog(
                if (savedPairRecord != null) {
                    "wired using saved Lockdown pair record"
                } else {
                    "wired created a new Lockdown pair record"
                },
            )
            onStatus(CarPlayStatus.ConnectingControl)
            val carKitClient = LockdownCarKitClient(mux)
            val carkit = try {
                carKitClient.open(pairRecord, config.label)
            } catch (error: Throwable) {
                if (!isInvalidPairRecord(error)) throw error
                debugLog("saved Lockdown pair record rejected; pairing again")
                clearPairRecord()
                pairRecord = pairNewRecord(pairingClient)
                carKitClient.open(pairRecord, config.label)
            }
            debugLog("wired com.apple.carkit.service stream opened")
            val csm = Iap2Session.open(
                carkit,
                traceContext = "wired",
                onTrace = ::debugLog,
            )
            this.csm = csm
            debugLog("wired iAP2 CSM channel opened")

            val ncmHostMac = ncm.hostMac ?: config.hostMac
            debugLog("ncm using hostMac=${ncmHostMac.macString()}")
            if (!attachVpn(ncm, ncmHostMac)) {
                throw IphoneUsbException.DeviceUnavailable("Could not attach the NCM/VPN AirPlay transport")
            }
            ncmOwnedLocally = false
            debugLog("wired NCM/VPN AirPlay transport attached")
            if (closed) {
                vpnService?.detach()
                return
            }

            val mfi = mfiSession?.client
                ?: throw IphoneUsbException.DeviceUnavailable("MFi coprocessor client is unavailable")
            val endpoint = Iap2WiredCarPlayEndpoint(
                ipv6Addresses = listOf(config.linkLocal),
                airPlayPort = airPlayConfig.port,
                publicKey = identity.publicKeyHex,
                sourceVersion = airPlayConfig.sourceVersion,
                deviceIdentifier = ncmHostMac.macString(),
            )
            onStatus(CarPlayStatus.RunningControl)
            debugLog("wired iAP2 control starting")
            val result = Iap2WiredControlClient(csm, Iap2MfiAuthenticationClient(mfi)).run(
                identification = config.identification,
                endpoint = endpoint,
                availableCurrentMilliAmps = config.availableCurrentMilliAmps,
                bringUpTimeoutMillis = CONTROL_BRING_UP_TIMEOUT_MILLIS,
                locationProvider = locationProvider,
                vehicleStatusProvider = vehicleStatusProvider,
                onReady = { activateMediaRemote(csm) },
                onStopped = { deactivateMediaRemote(csm) },
                onIncoming = ::onIap2Incoming,
                onProgress = { message -> debugLog("wired $message") },
            )
            onStatus(
                when (result.terminal) {
                    Iap2WiredControlTerminal.CHANNEL_CLOSED ->
                        CarPlayStatus.Failed("CarPlay control channel closed")
                },
            )
            finishWiredConnection("iAP2 control channel closed")
        } catch (error: Throwable) {
            debugLog("wired bring-up failed", error)
            if (!ncmOwnedLocally) vpnService?.detach()
            fail(error)
        } finally {
            if (ncmOwnedLocally) {
                runCatching { ncm.close() }
            } else {
                // Also covers a normal CHANNEL_CLOSED, not just exceptions or USB detach broadcasts.
                runCatching { vpnService?.detachWired() }
            }
        }
    }

    private fun pairNewRecord(client: LockdownPairingClient): LockdownPairRecord =
        client.pair(
            label = config.label,
            hostId = hostId,
            systemBuid = systemBuid,
            totalTimeoutMillis = PAIR_TIMEOUT_MILLIS,
            isCancelled = { closed },
        ).pairRecord.also(savePairRecord)

    private fun isInvalidPairRecord(error: Throwable): Boolean {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause.message?.contains("InvalidPairRecord", ignoreCase = true) == true) return true
            cause = cause.cause
        }
        return false
    }

    private fun isBluetoothHandoffCommand(type: String): Boolean =
        type.equals("disableBluetooth", ignoreCase = true) ||
            type.equals("disable-bluetooth", ignoreCase = true)

    private fun startWirelessHotspot(
        generation: Int,
        mfi: MfiAuthenticator,
    ): WirelessHotspotInfo {
        // 保持原 WIFI_P2P 的 Android 9 降级完全不变。DBPlay 新增方案用独立的
        // WIFI_P2P_LEGACY runtime 值，显式选它才会走旧版 createGroup 路径。
        val hotspotMode = effectiveWirelessHotspotMode(config.wirelessHotspotMode, Build.VERSION.SDK_INT)
        // ⚠️ PATCH-0010：SAME_LAN **不走 manager 这条路**——它一个热点都不建。
        // `WirelessHotspotManager` 的全部实现（P2P / LOH / MANUAL）语义都是
        // "让本机拥有一张 AP"，与本模式"网络是别人的"前提正好相反。
        // 所以在这里提前返回，而不是硬塞进下面那个 when。
        if (hotspotMode == WirelessHotspotMode.SAME_LAN) {
            return sameLanHotspotInfo()
        }
        val manager: WirelessHotspotManager = when (hotspotMode) {
            WirelessHotspotMode.WIFI_P2P ->
                mfiCertificateWifiP2pCredentials(mfi.readCertificate()).let { credentials ->
                    WifiP2pGroupManager(
                        context = appContext,
                        networkName = credentials.ssid,
                        passphrase = credentials.passphrase,
                    )
                }
            WirelessHotspotMode.WIFI_P2P_LEGACY -> if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                LegacyWifiP2pGroupManager(context = appContext) { message ->
                    debugLog(message)
                }
            } else {
                mfiCertificateWifiP2pCredentials(mfi.readCertificate()).let { credentials ->
                    WifiP2pGroupManager(
                        context = appContext,
                        networkName = credentials.ssid,
                        passphrase = credentials.passphrase,
                    )
                }
            }
            // PATCH-0005：把二开设置里的固定 SSID/密码透传给 LocalOnlyHotspot。
            // 不传的话 Android 每次启动都会随机生成 SSID 与密码，iPhone 侧就永远在
            // 重学一张陌生网络，无线重连会变得不可靠（真机上表现为"连一下断一下"）。
            // 凭据为空时 manager 会自动回落到系统随机行为。
            //
            // ⚠️ 上游 1.3.0 给 WIFI_P2P 加了 mfiCertificateWifiP2pCredentials()，
            // 那是"从配件证书派生"的固定凭据，与这里**不是一回事**：
            // 证书派生的名字用户改不了，而本工程要的是可自定义的「DBPlay」。
            // 两条路都保留：默认走本工程的热点方案，WIFI_P2P 仍是可选项。
            WirelessHotspotMode.LOCAL_ONLY_HOTSPOT -> LocalOnlyHotspotManager(
                context = appContext,
                ssid = config.manualHotspotSsid,
                passphrase = config.manualHotspotPassphrase,
                security = config.manualHotspotSecurity,
            )
            WirelessHotspotMode.MANUAL -> ManualHotspotManager(
                context = appContext,
                ssid = config.manualHotspotSsid
                    ?: throw IOException("Manual hotspot SSID is not configured"),
                passphrase = config.manualHotspotPassphrase.orEmpty(),
                band = config.manualHotspotBand,
                channel = config.manualHotspotChannel,
                security = config.manualHotspotSecurity,
                // PATCH-0087：复用模式下由用户填的车机热点地址；为空 ⇒ 与改动前一致
                // （manager 自己按网卡名打分挑）。P2P 建组那条路不带这个值。
                hostAddressOverride = config.manualHotspotHostAddress,
                hostInterfaceOverride = config.manualHotspotInterfaceName,
                // PATCH-0116：自建（ADB hostapd）时告诉上游"要用的这张 AP 是我们自己起的"，
                // 于是它不会拿车机系统里那份（可能是同名的）配置来校验我们这张网。
                selfHostedAccessPoint = config.manualHotspotSelfHosted,
            )
            // 车机原厂路由热点（2026-10-25 新增，对齐参考包 GeelyRoutedHotspotManager）。
            // 与 MANUAL 的关键区别：它**不读** SoftApConfiguration、**不用**
            // hostAddressOverride，自己扫 `eth*` 上的 `198.18.x.x`，返回
            // `bssid=null / channel=0 / bandLabel="Car hotspot" /
            // backend=GEELY_ROUTED_HOTSPOT`（与参考包逐字段一致）。
            WirelessHotspotMode.GEELY_ROUTED -> GeelyRoutedHotspotManager(
                ssid = config.manualHotspotSsid
                    ?: throw IOException("Routed hotspot SSID is not configured"),
                passphrase = config.manualHotspotPassphrase.orEmpty(),
                // `ManualHotspotSecurity.toIap2Security()` 是 ManualHotspotManager.kt
                // 文件内的 private 扩展，跨文件调不到，这里等价内联（映射与它逐条一致）。
                security = when (config.manualHotspotSecurity) {
                    ManualHotspotSecurity.OPEN -> Iap2WirelessSecurity.NONE
                    ManualHotspotSecurity.WPA2 -> Iap2WirelessSecurity.WPA_WPA2
                    ManualHotspotSecurity.WPA3_TRANSITION -> Iap2WirelessSecurity.WPA3_TRANSITION
                    ManualHotspotSecurity.WPA3 -> Iap2WirelessSecurity.WPA3_ONLY
                },
                onDiagnostic = { message -> debugLog(message) },
            )
            // PATCH-0010：SAME_LAN 在上面已经提前返回，走到这里说明条件判断与 when
            // 不一致（唯一的可能是 `hotspotMode` 在本方法内被重新赋值）。
            // 写成显式抛异常而不是 `else`：将来若有人再加枚举值，编译器会在这里
            // 提醒他"新值没被处理"，而不是被一个 `else` 悄悄吞掉。
            WirelessHotspotMode.SAME_LAN -> throw IOException(
                "同一 Wi-Fi 模式不需要热点管理器；这行本应不可达",
            )
        }
        hotspot = manager
        // ⚠️ PATCH-0064：`WIFI_P2P_LEGACY` 的预算比上游 `WIFI_P2P` 大一档。
        //
        // 它不是"多给点时间"的随手放宽，而是**多了一段必经的前置工作**：
        // 车机框架 Wi-Fi 平时是关的（真车 `WifiControllerIPCP: WIFI_STATE_DISABLED`），
        // 而 Wi-Fi Direct 走框架、框架要求 Wi-Fi 已开启，所以
        // `LegacyWifiP2pGroupManager` 要先自己把 Wi-Fi 打开（最长 20s）**再**建组。
        // 上游 `WIFI_P2P`（Android 10+，凭据来自配件证书）没有这段，保持 20s 不变。
        val timeoutMillis = when (hotspotMode) {
            WirelessHotspotMode.WIFI_P2P -> WIFI_P2P_START_TIMEOUT_MILLIS
            WirelessHotspotMode.WIFI_P2P_LEGACY -> WIFI_P2P_LEGACY_START_TIMEOUT_MILLIS
            else -> HOTSPOT_START_TIMEOUT_MILLIS
        }
        return try {
            manager.start(timeoutMillis)
        } catch (failure: Exception) {
            if (hotspot === manager) hotspot = null
            closeBestEffort(hotspotMode.name) { manager.close() }
            if (isStaleWirelessRun(generation)) throw failure
            throw IOException(
                "Could not establish ${hotspotMode.name} hotspot: " +
                    (failure.message ?: failure.javaClass.simpleName),
                failure,
            )
        }
    }

    /**
     * **PATCH-0010**：`WirelessHotspotMode.SAME_LAN` 的 `WirelessHotspotInfo`。
     *
     * ## 本方法一个热点都不建
     *
     * 整段逻辑就是"把配置里那个地址包成一个 `WirelessHotspotInfo`"。
     * 之所以不建热点：该模式的前提是**车机与 iPhone 已经在同一张网上**，
     * 再起一张 AP 只会有害（多层 NAT 让 mDNS 与反向回连更容易断，
     * 还逼用户在两张网之间做选择）。
     *
     * ## 各字段为什么这么填
     *
     * - [WirelessHotspotInfo.ssid] / `.passphrase`：**取自本模式自己的字段**
     *   （`config.sameLanWifiSsid` / `config.sameLanWifiPassphrase`），
     *   即"车机当前接的那张 Wi-Fi"——因为 iPhone 要接的就是同一张。
     *
     *   ⚠️ **2026-10-07 修正（原先这里填的是 `manualHotspotSsid`，是 bug）**：
     *   旧代码填的是本机热点名（出厂默认 `DBPlay`），并在注释里假设
     *   "iPhone 已经在网上，会忽略它们"。实测证明**假设是错的**——iPhone 是
     *   照着 `0x5703` 的 SSID/密码去**关联**网络的。于是它拿着一个本模式
     *   根本不存在的网络名（`DBPlay`）去关联，永远找不到 ⇒ 40s 后
     *   `wireless attach timed out` ⇒ 界面停在「无线通道就绪」且全链路无报错。
     *   诊断包 `1aedc494` 完整记录了这条链。
     *
     *   ⚠️ **同日再修正：SSID 为空不再"放行"，改为直接报错**。
     *   上一版误判为"空 SSID 只是可能被 iOS 拒绝"，实测是**崩溃级失败**：
     *   下游 `Iap2WirelessIdentification` 有 `require(ssid.isNotBlank())`
     *   （诊断包 `1791345066541` 实录 `IllegalArgumentException: Wireless SSID
     *   must not be blank`，2.2 秒即失败）。现在在下方显式抛 `IOException`。
     *   **不编造兜底值**的理由不变：SAME_LAN 通告的是别人的网，编不出它的名字，
     *   随便填只会重演"关联一张不存在的网"。
     * - `.security`：由最终 `passphrase` 现推（空 ⇒ OPEN，非空 ⇒ WPA2），
     *   与 `CarPlayConfigBuilder.passphraseFor` 同一口径，避免自相矛盾组合。
     * - `.channel` 取 0（自动）：我们**没法**知道路由器用哪个信道，
     *   随便填一个反而可能让 iPhone 去做无谓的重新关联。
     * - `.bssid` 取 null：那是**AP 的 MAC**，路由器的不在我们的能力范围内。
     *   上游会回落到 `airPlayConfig.deviceId`（见调用点 `?: airPlayConfig.deviceId`）。
     * - `.backend` 用 `MANUAL_HOTSPOT` 标签：`WirelessHotspotBackend` 没有
     *   适合本模式的枚举值（三个值都是"本机拥有网络"的形态）。
     *   加一个新值要动上游枚举与它的 `label`，而这里只需要一个能进日志的名字。
     *   ⚠️ 因此**不要**靠 `backend` 判断"有没有建热点"——诊断里以
     *   `hotspot plan:` 那一行为准。
     *
     * ## 失败即失败
     *
     * 地址缺失时直接抛 `IOException`，**不回落到自建热点**。
     * 理由与 `CarPlaySessionBridge` 里"不再回落 LOH"完全一致：用户显式选了
     * "同一 Wi-Fi"，悄悄换成自建会让他拿着一台连着陌生热点的 iPhone 去猜
     * "为什么密码不对"。如实报错 + 设置页给出下一步，比换一条路更有用。
     */
    private fun sameLanHotspotInfo(): WirelessHotspotInfo {
        val text = config.sameLanHostAddress?.trim().orEmpty()
        if (text.isEmpty()) {
            throw IOException(
                "同一 Wi-Fi 模式需要车机的局域网地址，但配置里没有。" +
                    "请在设置里确认车机已连上 Wi-Fi，或手动填写本机地址。",
            )
        }
        val address = try {
            InetAddress.getByName(text)
        } catch (failure: Exception) {
            throw IOException("同一 Wi-Fi 模式的本机地址「$text」不是合法地址", failure)
        }
        if (address !is Inet4Address) {
            throw IOException(
                "同一 Wi-Fi 模式只支持 IPv4 地址，收到的是「$text」；" +
                    "请在设置里改成 IPv4 或留空让它自动读取。",
            )
        }
        if (address.isLoopbackAddress || address.isLinkLocalAddress) {
            // 这两种地址在 iPhone 那一侧**不可达**：通告出去等于让它去敲一扇
            // 不存在的门，而我们会以为"网络已就绪"、界面停在等待。
            throw IOException(
                "同一 Wi-Fi 模式收到的是一个不可用的地址「$text」" +
                    "（回环或 link-local）。车机需要先从路由器拿到正常的 IPv4 地址。",
            )
        }
        // ⚠️ 通告给 iPhone 的必须是**车机当前所连那张 Wi-Fi**，绝不是本机热点名。
        val ssid = config.sameLanWifiSsid.orEmpty()
        val passphrase = config.sameLanWifiPassphrase.orEmpty()
        // 🔴 **SSID 为空必须在这里拦住，绝不能放行到下游**（2026-10-07 真机实测修正）。
        //
        // 早先这里只是 `Log.w` 提示一句"可能被 iOS 拒绝"，然后把空串交给上游——
        // **那个判断是错的**：上游 `Iap2WirelessIdentification` 的构造函数有
        // `require(ssid.isNotBlank()) { "Wireless SSID must not be blank" }`
        // ⇒ 空 SSID 不是"握手可能失败"，而是**直接抛 IllegalArgumentException
        // 把整个无线 bring-up 打死**，现场表现为"点了启动、2 秒就断，界面无任何说明"。
        //
        // 诊断包 `1791345066541` 实录（11:48:19.814）：
        //     wireless bring-up failed
        //     java.lang.IllegalArgumentException: Wireless SSID must not be blank
        //         at Iap2WirelessIdentification.<init>(Iap2IdentificationClient.kt:25)
        //
        // ⇒ 改成在这里抛一句**能指导用户下一步**的 IOException（与上面地址那几段同一口径），
        // 由设置页/会话错误提示原样展示，而不是让上游抛一句对现场毫无帮助的英文 require。
        //
        // 为什么不给"兜底值"：SAME_LAN 要通告的是**一张别人的网**，我们编不出它的名字。
        // 随便填一个（例如热点名 DBPlay）只会重演 2026-10-07 上午那个"iPhone 去关联
        // 一个不存在的网络名"的 bug。⇒ **宁可明确报错，也不编造**。
        if (ssid.isBlank()) {
            throw IOException(
                "同一 Wi-Fi 模式需要知道「车机连的是哪张 Wi-Fi」，但读不到它的名字。" +
                    "请在设置 → 无线连接 → 局域网模式里填写「Wi-Fi 名称」" +
                    "（就是 iPhone 个人热点在其它设备上显示的那个名字），然后重试。",
            )
        }
        debugLog("same LAN mode: no hotspot will be created, advertising $text, ssid=$ssid")
        return WirelessHotspotInfo(
            ssid = ssid,
            passphrase = passphrase,
            // 🔒 与密码保持自洽：空密码 ⇒ OPEN，非空 ⇒ WPA2。
            // 与 `CarPlayConfigBuilder.passphraseFor` 同一口径，避免出现
            // "OPEN + 非空密码"这种自相矛盾组合。
            security = if (passphrase.isEmpty()) {
                Iap2WirelessSecurity.NONE
            } else {
                Iap2WirelessSecurity.WPA_WPA2
            },
            // 0 = 自动。路由器用哪个信道我们无从得知，也管不着。
            channel = 0,
            frequencyMHz = null,
            bssid = null,
            interfaceName = null,
            hostAddress = address,
            bandLabel = "同一 Wi-Fi",
            backend = WirelessHotspotBackend.MANUAL_HOTSPOT,
        )
    }

    private fun isStaleWirelessRun(generation: Int): Boolean =
        closed || phase != Phase.WIRELESS || generation != wirelessGeneration.get()
    private fun selectWirelessBluetoothDevices(adapter: BluetoothAdapter): List<BluetoothDevice> {
        val bonded = adapter.bondedDevices.orEmpty()
        val smartPhones = bonded.filter { device ->
            val bluetoothClass = device.bluetoothClass
            debugLog(
                "wireless Bluetooth bonded name=${device.name ?: "unknown"} " +
                    "address=${device.address} class=${bluetoothClass ?: "unknown"} " +
                    "deviceClass=${bluetoothClass?.deviceClass?.toString(16) ?: "unknown"}",
            )
            bluetoothClass?.deviceClass == BluetoothClass.Device.PHONE_SMART
        }
        if (smartPhones.isEmpty()) {
            throw IOException("No bonded PHONE_SMART devices found; pair an iPhone and retry")
        }
        val directlyConnectedSmartPhones = smartPhones.filter(::isBluetoothDeviceConnected)
        Log.i(
            IphoneCarPlayConfiguration.TAG,
            "wireless Bluetooth bondedSmartPhones=${smartPhones.size} " +
                "directlyConnected=${directlyConnectedSmartPhones.size}",
        )
        val connectedAddresses = if (directlyConnectedSmartPhones.isNotEmpty()) {
            directlyConnectedSmartPhones.mapTo(mutableSetOf()) { it.address }
        } else {
            connectedBluetoothDevices(adapter).mapTo(mutableSetOf()) {
                it.address
            }
        }
        return smartPhones.sortedWith(
            compareByDescending<BluetoothDevice> { it.address in connectedAddresses }
                .thenBy { it.address },
        )
    }

    /**
     * 建立无线 CarPlay 的蓝牙 bootstrap（iAP2 over RFCOMM）。
     *
     * ## 2026-10-08 改：加「轮次重试 + 递增退避」
     *
     * ### 现场证据（VIN `L6TTEST1111111112`，设备 `e3de67df72a0`，App 1.0.6）
     *
     * ```
     * 18:35:42.151 W xcertplay-usb: IAP2 CLOSE [wireless-rfcomm]        ← 旧连接被关
     * 18:35:42.427 W xcertplay-usb: reporting iAP2 channel 0 (auto)
     * 18:35:42.500 I xcertplay-usb: wireless RFCOMM attempt=1/1 name=Royal的iPhone16promax
     * 18:35:42.904 W xcertplay-usb: read failed, socket might closed or timeout, read ret: -1
     *                 at BluetoothSocket.readAll(BluetoothSocket.java:767)
     *                 at BluetoothSocket.connect(BluetoothSocket.java:400)
     * 18:35:42.907 W xcertplay-usb: wireless bring-up failed
     * ```
     *
     * 关键：从 `attempt=1/1` 到失败**只有 404ms**，远小于 15s 的连接超时 ⇒
     * 不是超时，而是**iPhone 主动把 RFCOMM 拒掉/关掉**（`read ret: -1`）。
     * 上一刻刚 `IAP2 CLOSE`，iPhone 端尚未释放旧连接；原实现**只跑一遍就放弃**，
     * 于是整轮 bring-up 失败，用户看到的就是「无线 CarPlay 连不上」。
     *
     * ### 同一台车的反证
     *
     * 同一天 18:31 那轮，同一台 iPhone、同一段代码，
     * `IAP2 RX 0x5702` / `IAP2 TX 0x5703`（WiFi 配置交换）**是成功的** ——
     * 说明蓝牙链路本身没问题，**只是时序**：关掉旧连接后需要给 iPhone 一点时间。
     *
     * ### 措施
     *
     * 每个设备**多轮尝试**（[RFCOMM_MAX_ROUNDS] 轮），轮与轮之间**指数退避**
     * （[RFCOMM_RETRY_BACKOFF_BASE_MILLIS] × 2^round）。任一设备成功即返回。
     * 退避在 `isStaleWirelessRun` 检查之后执行，会话被取消时不会白等。
     */
    private fun connectWirelessBluetoothDevices(
        devices: List<BluetoothDevice>,
        generation: Int,
    ): Iap2Session {
        var lastFailure: Exception? = null
        // PATCH-0133：复用档下由二开层注入一个只读快照读取器（读
        // `CarHotspotClientWatcher` 已有的客户端数，不发任何新命令）。
        // null = 本次不是复用档，或该层没有可用的快照 ⇒ 保持原行为。
        val clientCountProvider = config.hotspotClientCountProvider
        for (round in 0 until RFCOMM_MAX_ROUNDS) {
            for ((index, device) in devices.withIndex()) {
                if (isStaleWirelessRun(generation)) {
                    throw IOException("Wireless Bluetooth connection was cancelled")
                }
                onStatus(CarPlayStatus.ConnectingBluetooth)
                debugLog(
                    "wireless RFCOMM attempt=${index + 1}/${devices.size} " +
                        "round=${round + 1}/$RFCOMM_MAX_ROUNDS " +
                        "name=${device.name ?: "unknown"} address=${device.address} " +
                        "uuid=$IAP2_IPHONE_UUID",
                )
                try {
                    val socket = device
                        .createRfcommSocketToServiceRecord(UUID.fromString(IAP2_IPHONE_UUID))
                        .also { bluetoothSocket = it }
                    connectBluetoothSocket(socket, device.address)
                    if (isStaleWirelessRun(generation)) {
                        throw IOException("Wireless Bluetooth connection was cancelled")
                    }
                    debugLog(
                        "wireless RFCOMM connected address=${device.address} round=${round + 1}",
                    )
                    val stream = BluetoothRfcommDuplexStream(socket).also { bluetoothStream = it }
                    val channel = Iap2Session.openWireless(
                        stream,
                        traceContext = "wireless-rfcomm",
                        onTrace = ::debugLog,
                    ).also { csm = it }
                    debugLog("wireless iAP2 CSM channel opened address=${device.address}")
                    return channel
                } catch (error: Exception) {
                    closeBluetoothBootstrapTransport()
                    if (isStaleWirelessRun(generation)) throw error
                    lastFailure = error
                    debugLog(
                        "wireless RFCOMM attempt failed address=${device.address} " +
                            "round=${round + 1}/$RFCOMM_MAX_ROUNDS",
                        error,
                    )
                }
            }
            // 一轮（两台手机）都失败后，先看车机侧的只读快照：
            // 热点开着却一个客户端都没有 ⇒ 手机压根没关联上这张网，再轮无意义。
            // provider 非空只会在「复用车机热点」且热点已由 `start()` 校验过时注入，
            // 所以这里的 apEnabled 取 true；读不到客户端数时传 null，判据自然返回 false。
            if (clientCountProvider != null) {
                val clientCount = runCatching { clientCountProvider() }.getOrNull()
                val completedRound = round + 1
                if (shouldStopRfcommRetry(completedRound, clientCount, apEnabled = true)) {
                    debugLog(
                        "RFCOMM 早停：热点已开但无客户端接入（round=$completedRound），不再跑满 3 轮",
                    )
                    break
                }
            }
            // 还有下一轮 ⇒ 退避一会儿。iPhone 刚关掉旧 RFCOMM 时需要时间释放，
            // 立刻重连必被拒（实测 404ms 即 -1）。
            if (round < RFCOMM_MAX_ROUNDS - 1) {
                val backoffMillis = RFCOMM_RETRY_BACKOFF_BASE_MILLIS shl round
                debugLog(
                    "wireless RFCOMM all devices failed this round; " +
                        "backing off ${backoffMillis}ms before round ${round + 2}/$RFCOMM_MAX_ROUNDS",
                )
                sleepQuietly(backoffMillis)
            }
        }
        throw IOException(
            "Could not open iAP2 over Bluetooth with any of ${devices.size} bonded PHONE_SMART " +
                "devices after $RFCOMM_MAX_ROUNDS rounds",
            lastFailure,
        )
    }

    /** 退避等待；被中断时只恢复中断标记，不抛（调用方下一轮会检查 stale）。 */
    private fun sleepQuietly(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun connectBluetoothSocket(socket: BluetoothSocket, address: String) {
        val result = AtomicReference<Throwable?>()
        val connected = CountDownLatch(1)
        Thread(
            {
                try {
                    socket.connect()
                } catch (error: Throwable) {
                    result.set(error)
                } finally {
                    connected.countDown()
                }
            },
            "wireless-rfcomm-connect",
        ).apply {
            isDaemon = true
            start()
        }
        val completed = try {
            connected.await(RFCOMM_CONNECT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            runCatching { socket.close() }
            throw IOException("Interrupted while connecting RFCOMM to $address", error)
        }
        if (!completed) {
            debugLog(
                "wireless RFCOMM connect timed out after " +
                    "${RFCOMM_CONNECT_TIMEOUT_MILLIS}ms address=$address",
            )
            runCatching { socket.close() }
            throw IOException(
                "Timed out after ${RFCOMM_CONNECT_TIMEOUT_MILLIS}ms connecting RFCOMM to $address",
            )
        }
        when (val failure = result.get()) {
            null -> Unit
            is IOException -> throw failure
            else -> throw IOException("Could not connect RFCOMM to $address", failure)
        }
    }

    private fun closeWirelessStack(service: CarPlayVpnService? = vpnService) {
        // 全量重启/用户停止时，上一轮的迟到连接宽限必须一起撤销，
        // 否则它会在新栈上把 generation 推走并误报失败。
        cancelLateAttachGrace()
        media.setIapTunnelHandler(null)
        val activeTunnel = wirelessTunnelChannel
        wirelessTunnelChannel = null
        if (activeTunnel != null) closeBestEffort("tunneled iAP2 link") { activeTunnel.close() }

        closeBluetoothBootstrapTransport()

        val activeBonjour = bonjour
        bonjour = null
        if (activeBonjour != null) closeBestEffort("Bonjour") { activeBonjour.close() }

        val activeHotspot = hotspot
        hotspot = null
        if (activeHotspot != null) closeBestEffort("wireless hotspot") { activeHotspot.close() }
        wirelessIdentification = null
        wirelessAirPlayEndpoint = null
        wirelessHandoffRequested.set(false)
        wirelessTunnelReady.set(false)
        wirelessActiveReported.set(false)

        if (service != null) closeBestEffort("AirPlay service") { service.detach() }
    }

    private fun isBluetoothDeviceConnected(device: BluetoothDevice): Boolean = try {
        val method = BluetoothDevice::class.java.getMethod("isConnected")
        method.invoke(device) as? Boolean == true
    } catch (error: ReflectiveOperationException) {
        false
    } catch (error: RuntimeException) {
        Log.w(IphoneCarPlayConfiguration.TAG, "Could not read Bluetooth connection state", error)
        false
    }

    private fun connectedBluetoothDevices(adapter: BluetoothAdapter): Set<BluetoothDevice> =
        buildSet {
            addAll(connectedBluetoothDevices(adapter, BluetoothProfile.HEADSET, BluetoothHeadset::class.java))
            addAll(connectedBluetoothDevices(adapter, BluetoothProfile.A2DP, BluetoothA2dp::class.java))
        }

    private fun <T : BluetoothProfile> connectedBluetoothDevices(
        adapter: BluetoothAdapter,
        profile: Int,
        profileClass: Class<T>,
    ): Set<BluetoothDevice> {
        val latch = CountDownLatch(1)
        val devices = java.util.Collections.synchronizedSet(mutableSetOf<BluetoothDevice>())
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profileId: Int, proxy: BluetoothProfile) {
                try {
                    if (profileClass.isInstance(proxy)) {
                        devices.addAll(proxy.connectedDevices.orEmpty())
                    }
                } catch (error: SecurityException) {
                    Log.w(IphoneCarPlayConfiguration.TAG, "Could not read connected Bluetooth devices", error)
                } finally {
                    adapter.closeProfileProxy(profileId, proxy)
                    latch.countDown()
                }
            }

            override fun onServiceDisconnected(profileId: Int) {
                latch.countDown()
            }
        }
        if (!adapter.getProfileProxy(appContext, listener, profile)) return emptySet()
        if (!latch.await(3, TimeUnit.SECONDS)) {
            Log.w(IphoneCarPlayConfiguration.TAG, "Timed out reading Bluetooth profile $profile")
        }
        return synchronized(devices) { devices.toSet() }
    }

    @Suppress("DEPRECATION")
    private fun accessoryBluetoothMac(adapter: BluetoothAdapter): String {
        val address = try {
            adapter.address
        } catch (_: SecurityException) {
            null
        }
        return address
            ?.takeIf {
                BLUETOOTH_ADDRESS.matches(it) &&
                    !it.equals(ADAPTER_ADDRESS_PLACEHOLDER, ignoreCase = true)
            }
            ?: airPlayConfig.btMac
    }

    private fun hostAddressText(address: InetAddress): String {
        val text = address.hostAddress?.substringBefore('%')
        if (text.isNullOrBlank()) {
            throw IOException("LocalOnlyHotspot host address is unavailable")
        }
        return text
    }

    private fun closeBestEffort(name: String, close: () -> Unit) {
        try {
            close()
        } catch (error: Throwable) {
            debugLog("$name teardown failed", error)
        }
    }

    private fun attachVpn(ncm: NcmUsbBridge, hostMac: ByteArray): Boolean {
        onStatus(CarPlayStatus.AttachingNetwork)
        val service = awaitVpnService() ?: run {
            debugLog("wired VPN service bind failed")
            ncm.close()
            return false
        }
        debugLog("wired VPN service bound; attaching NCM transport")
        val result = try {
            service.attach(
                ncm = ncm,
                linkLocal = config.linkLocal,
                hostMac = hostMac,
                config = airPlayConfig,
                identity = identity,
                pairings = pairings,
                mfi = mfiSession?.client,
                listener = sessionListener,
                media = media,
            )
        } catch (error: Throwable) {
            ncm.close()
            onStatus(CarPlayStatus.Failed(error.message ?: error.javaClass.simpleName))
            return false
        }
        return when (result) {
            CarPlayVpnService.AttachResult.Started -> {
                debugLog("wired VPN/NCM transport attach result=started")
                true
            }
            CarPlayVpnService.AttachResult.AlreadyStarted -> {
                debugLog("wired VPN/NCM transport attach result=already-started")
                ncm.close()
                false
            }
            is CarPlayVpnService.AttachResult.Failed -> {
                debugLog("wired VPN/NCM transport attach result=failed ${result.message}")
                ncm.close()
                onStatus(CarPlayStatus.Failed(result.message))
                false
            }
        }
    }

    private fun ByteArray.macString(): String =
        joinToString(":") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun awaitVpnService(): CarPlayVpnService? {
        vpnService?.let { return it }
        bindVpn()
        return try {
            if (vpnLatch.await(VPN_CONNECT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) vpnService else null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    private fun bindVpn() {
        if (vpnBound) return
        vpnBound = true
        try {
            val intent = Intent(appContext, CarPlayVpnService::class.java)
            if (!appContext.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)) {
                vpnBound = false
                vpnLatch.countDown()
            }
        } catch (_: Throwable) {
            vpnBound = false
            vpnLatch.countDown()
        }
    }

    private fun unbindVpn() {
        if (!vpnBound) return
        vpnBound = false
        try {
            appContext.unbindService(serviceConnection)
        } catch (_: Exception) {
            // The service may have already been unbound.
        }
        vpnService = null
    }

    private fun closeReceivers() {
        listOfNotNull(permissionCloseable, attachCloseable, detachCloseable, ch341PermissionCloseable).forEach {
            try {
                it.close()
            } catch (_: Exception) {
                // Receiver is already unregistered.
            }
        }
        permissionCloseable = null
        attachCloseable = null
        detachCloseable = null
        ch341PermissionCloseable = null
    }

    private fun closeMfiSession() {
        val session = mfiSession
        mfiSession = null
        if (session != null) {
            executor.execute {
                try {
                    session.close()
                } catch (_: Exception) {
                    // Best effort.
                }
            }
        }
    }

    private fun fail(error: Throwable) {
        if (closed) return
        onStatus(CarPlayStatus.Failed(error.message ?: error.javaClass.simpleName))
    }

    private fun debugLog(message: String) {
        Log.i(IphoneCarPlayConfiguration.TAG, message)
        try {
            uiListener?.onDebugLog(message)
        } catch (error: Exception) {
            Log.w(IphoneCarPlayConfiguration.TAG, "debug log callback failed", error)
        }
    }

    private fun debugLog(message: String, error: Throwable) {
        Log.w(IphoneCarPlayConfiguration.TAG, message, error)
        try {
            uiListener?.onDebugLog(
                "$message: ${error.message ?: error.javaClass.simpleName}",
            )
        } catch (callbackError: Exception) {
            Log.w(IphoneCarPlayConfiguration.TAG, "debug log callback failed", callbackError)
        }
    }

    private fun onStatus(status: CarPlayStatus) {
        if (closed) return
        mainHandler.post {
            if (!closed && status != lastReportedStatus) {
                lastReportedStatus = status
                uiListener?.onDebugLog(status.debugLogMessage())
                uiStatusReporter?.invoke(status)
            }
        }
    }

    private fun CarPlayStatus.debugLogMessage(): String = when (this) {
        CarPlayStatus.DiscoveringMfi ->
            "STEP mfi/start: preparing the configured MFi authentication provider"
        CarPlayStatus.WaitingForMfi ->
            "STEP mfi/wait: MFi coprocessor not present; polling"
        CarPlayStatus.RequestingMfiPermission ->
            "STEP mfi/permission: requesting CH341 USB access"
        CarPlayStatus.MfiReady ->
            "STEP mfi/ready: MFi authentication provider is ready"
        CarPlayStatus.StartingHotspot ->
            "STEP wifi/ap: starting the wireless CarPlay access point"
        is CarPlayStatus.HotspotReady ->
            "STEP wifi/ap-ready: backend=$backend ssid=$ssid band=$band " +
                "channel=$channel bssid=$bssid address=$address"
        CarPlayStatus.WaitingForPairedIphone ->
            "STEP bt/select: waiting for a paired or connected iPhone"
        CarPlayStatus.ConnectingBluetooth ->
            "STEP bt/rfcomm: connecting to the iPhone iAP2 RFCOMM service"
        CarPlayStatus.RunningWireless ->
            "STEP iap2/wireless: Bluetooth control loop running"
        CarPlayStatus.WirelessActive ->
            "STEP handoff/complete: tunnel iAP2 ready; Bluetooth bootstrap released"
        CarPlayStatus.DiscoveringIphone ->
            "STEP usb/discover: searching for an iPhone USB device"
        CarPlayStatus.WaitingForIphone ->
            "STEP usb/wait: iPhone USB device not present; polling"
        CarPlayStatus.RequestingIphonePermission ->
            "STEP usb/permission: requesting USB access to the iPhone"
        CarPlayStatus.WaitingForReenumeration ->
            "STEP usb/reenum: waiting for the CarPlay USB configuration"
        CarPlayStatus.SelectingConfiguration ->
            "STEP usb/config: selecting the iPhone CarPlay configuration"
        CarPlayStatus.OpeningDataPaths ->
            "STEP usb/data: opening iAP2 and NCM USB data paths"
        CarPlayStatus.Pairing ->
            "STEP lockdown/pair: loading or creating the pairing record"
        CarPlayStatus.ConnectingControl ->
            "STEP lockdown/carkit: opening com.apple.carkit.service"
        CarPlayStatus.AttachingNetwork ->
            "STEP network/attach: attaching the AirPlay network transport"
        CarPlayStatus.RunningControl ->
            "STEP iap2/wired: wired iAP2 control loop running"
        CarPlayStatus.ControlEnded ->
            "STEP control/end: the control window ended"
        is CarPlayStatus.Failed ->
            "ERROR $message"
    }

    companion object {
        private const val NOW_PLAYING_UPDATE = 0x5001

        /**
         * 复用原厂热点、**客户端数为 0 或读不到**时，进入 iAP2 前的保守等待（毫秒）。
         *
         * 依据（2026-10-20 VIN 381425 真车日志，同一台车/同一 SSID/0x5703 内容完全相同）：
         * 成功样本 客户端出现→0x5703 = 10.6s，0.4s 后 iPhone 连上 AirPlay；
         * 失败样本 该间隔只有 0.86s，iPhone 收下配置却不连，40s 超时。
         * 取 8s：覆盖失败缺口，又比成功样本略短，避免明显拖慢首次连接。
         */
        private const val REUSE_CLIENT_SETTLE_MILLIS = 8_000L

        /**
         * 复用原厂热点、**客户端快照 > 0**（手机已经在网上）时的等待（毫秒）。
         *
         * 2026-10-21 方案 §1.1：复用场景大多数时候客户端已在网上，无条件 8s 是纯延迟。
         * 2s 只留给 DHCP/邻居表落地；若实车证明仍高概率失败，再调到 4s。
         */
        private const val REUSE_CLIENT_SETTLE_CLIENT_PRESENT_MILLIS = 2_000L
        private const val NOW_PLAYING_ATTRIBUTE_DUMP_LIMIT = 20
        private const val MEDIA_REMOTE_SEND_TIMEOUT_MILLIS = 2_000L
        private const val HID_REPORT_HOLD_MILLIS = 50L
        private const val IAP2_IPHONE_UUID = "00000000-deca-fade-deca-deafdecacafe"
        private const val HOTSPOT_START_TIMEOUT_MILLIS = 60_000L
        private const val WIFI_P2P_START_TIMEOUT_MILLIS = 20_000L

        /**
         * PATCH-0064：DBPlay 独立的 Android 9 legacy Wi-Fi Direct 路径的预算。
         *
         * 比上游 [WIFI_P2P_START_TIMEOUT_MILLIS]（20s）大一档，因为它**多了一段必经的
         * 前置工作**：车机框架 Wi-Fi 平时是关的（真车日志
         * `WifiControllerIPCP: current state: WIFI_STATE_DISABLED`），而 Wi-Fi Direct
         * 走框架、框架要求 Wi-Fi 已开启 ⇒ `LegacyWifiP2pGroupManager` 要先自己把 Wi-Fi
         * 打开（其内部上限 20s）**再**建组（还含最多 3 次 `createGroup` 重试）。
         * 20s 装不下这两段，会出现"Wi-Fi 刚开好、建组预算已经用完"的假超时。
         */
        private const val WIFI_P2P_LEGACY_START_TIMEOUT_MILLIS = 45_000L
        private const val PAIR_TIMEOUT_MILLIS = 5 * 60_000L
        private const val VPN_CONNECT_TIMEOUT_MILLIS = 10_000L
        private const val CONTROL_BRING_UP_TIMEOUT_MILLIS = 5 * 60_000L
        private const val PERMISSION_POLL_INTERVAL_MILLIS = 500L
        private const val PERMISSION_POLL_TIMEOUT_MILLIS = 120_000L
        private const val DEVICE_AVAILABILITY_POLL_INTERVAL_MILLIS = 2_000L
        private const val WIRELESS_HANDOFF_TIMEOUT_MILLIS = 45_000L

        /**
         * PATCH-0009 / 2026-10-21：**单次 bootstrap 尝试的总预算**（毫秒）。
         *
         * 从 `connectWirelessBluetoothDevices()` **之前**起算，覆盖
         * RFCOMM + iAP2 + iPhone 反向连 AirPlay 整段（旧实现只在 RFCOMM 连上后
         * 武装，首次 advertise 白等 60s；见 22:04:40→22:05:50 实测）。
         *
         * 取 20 秒的理由：
         * - 成功路径实测 3~5s（accepted → active <1s），20s 是 4~6 倍余量；
         * - 22:05 首次 70s 无连接、重试 0.63s 就 accepted ⇒ 首次超时要短，
         *   让它尽快走到重试，60s 反而是最差取值；
         * - 21:50 手机在超时后 10.3s 才 accepted ⇒ 超时后不能立刻拆监听，
         *   由 [LATE_ATTACH_GRACE_MILLIS] 接住迟到连接。
         * 若 1.2.6 实车数据显示成功路径稳定 ≤5s，可再降到 15s；不建议 10s。
         */
        private const val WIRELESS_ATTACH_TIMEOUT_MILLIS = 20_000L

        /**
         * 「等 iPhone 来连」最多尝试几次（含首次）。
         *
         * 2026-10-21 定稿 4 次：22:05 第二次就成功；中间重试改为 bootstrap-only 后
         * 单次成本只有 ~2~4s，4 次不再昂贵。第 2/4 次只重建 RFCOMM+iAP2，
         * 第 3 次才全量重启（覆盖"监听/accept 循环已坏"）。
         */
        private const val WIRELESS_ATTACH_MAX_ATTEMPTS = 4

        /**
         * 外层重试的固定间隔（毫秒）。
         *
         * 不递增：RFCOMM 层已有 2.5s / 5s 指数退避（[RFCOMM_RETRY_BACKOFF_BASE_MILLIS]），
         * outer 再叠指数会让单次失败拖到 10s 以上，违背「快」的目标。
         */
        private const val WIRELESS_ATTACH_RETRY_INTERVAL_MILLIS = 300L

        /**
         * 末次超时后的迟到连接宽限（毫秒）。
         *
         * 实测 21:50:20.275 超时 → 21:50:30.597 accepted（+10.3s）。
         * 15s 覆盖该值并留 4.7s 余量；宽限期间监听/Bonjour/热点全部保留。
         */
        private const val LATE_ATTACH_GRACE_MILLIS = 15_000L

        /**
         * 单次 RFCOMM 连接超时（毫秒）。
         *
         * 实车成功 RFCOMM 1.6s、旧连接被拒 0.4s；15s 只会拖长失败轮。
         * 总预算 [WIRELESS_ATTACH_TIMEOUT_MILLIS] 已能兜底。
         */
        private const val RFCOMM_CONNECT_TIMEOUT_MILLIS = 8_000L

        /**
         * 蓝牙 iAP2 bootstrap 的**轮次上限**（2026-10-08 新增）。
         *
         * 现场实测：iPhone 关掉旧 RFCOMM 后**立刻**重连必被拒（404ms 即 `read ret: -1`）。
         * 给 3 轮（首轮 + 2 次退避重试，累计最多多等 2.5s + 5s = 7.5s），
         * 足够覆盖"旧连接释放"这类时序问题；再多就是在拖长用户的等待。
         */
        private const val RFCOMM_MAX_ROUNDS = 3

        /** 退避基数（毫秒）；实际等待 = 基数 << 轮次序号（2.5s、5s…）。 */
        private const val RFCOMM_RETRY_BACKOFF_BASE_MILLIS = 2_500L

        private const val MAXIMUM_REENUMERATION_ATTEMPTS = 2
        private const val EXECUTOR_CLOSE_TIMEOUT_MILLIS = 2_000L
        private const val ADAPTER_ADDRESS_PLACEHOLDER = "02:00:00:00:00:00"
        private val BLUETOOTH_ADDRESS = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")
    }
}
