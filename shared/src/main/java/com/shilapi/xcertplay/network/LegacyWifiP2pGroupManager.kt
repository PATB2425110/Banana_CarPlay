package com.shilapi.xcertplay.network

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.HandlerThread
import android.os.Looper
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.net.UnknownHostException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Android 9-compatible, system-credential Wi-Fi Direct group path.
 *
 * This manager is deliberately separate from [WifiP2pGroupManager]: it uses only the legacy
 * createGroup(channel, listener) overload and never changes the existing WIFI_P2P behavior.
 * Android 9 operating channel reporting remains unverified on vendor head units.
 *
 * ## 2026-10-10：本类自己负责"把车机 Wi-Fi 打开"（PATCH-0064）
 *
 * 真车日志（诊断包 `1791546694008_e2f499c9_67b3c24af6b5`，VIN `L6TTEST1111111111`）
 * 显示 `P2P_DIRECT` 7 次会话尝试**全部**停在
 * `checkPrerequisites` 的 `isWifiEnabled == false` 上，而系统日志同时写着
 * `WifiControllerIPCP: current state: WIFI_STATE_DISABLED`——**这台车机的框架 Wi-Fi
 * 平时就是关的**（上行是以太网）。旧实现把这件事当成"用户该自己去系统设置里开"，
 * 于是这个模式永远走不到组网阶段。现在改为：
 *
 * 1. [ensureWifiStationPowered]：不是 ENABLED 就自己 `setWifiEnabled(true)`，
 *    并**轮询等到状态同步到 ENABLED**（`setWifiEnabled` 只是"受理"，状态要经过
 *    ENABLING 才到位）；
 * 2. [createGroupWithRetry]：刚上电时 P2P 状态机可能还没就绪 ⇒ 对
 *    `BUSY` / `ERROR` 做有界重试；
 * 3. [restoreWifiStation]：`close()` 时**只归还自己打开的那张** Wi-Fi
 *    （见 [legacyWifiStaShouldBeRestored]）——车机 Wi-Fi 一直开着会让
 *    `CarAdbHotspotPlanner` 把 `wlan0` 判成上行网卡而排除它，反过来影响「自建热点」。
 *
 * ⚠️ **2026-10-13（PATCH-0113）**：上面引用的 `P2P_DIRECT` 那一档热点模式已整体移除，
 * 本文现场属**历史证据**。本类按车主边界**原样保留**（它属上游 `:shared` 给无线
 * CarPlay 建 Wi-Fi 链路的那套 P2P 组管理；撤掉的只是二开层的那一档模式）。
 */
class LegacyWifiP2pGroupManager(
    context: Context,
    private val diagnostic: (String) -> Unit = {},
) : WirelessHotspotManager {
    private val appContext = context.applicationContext
    private val p2pManager = appContext.getSystemService(WifiP2pManager::class.java)
        ?: throw IllegalStateException("WifiP2pManager is unavailable")
    private val wifiManager: WifiManager? = runCatching {
        appContext.getSystemService(WifiManager::class.java)
    }.getOrNull()
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Object()

    private var channel: WifiP2pManager.Channel? = null
    private var callbackThread: HandlerThread? = null
    private var startAttempt: StartAttempt? = null
    private var created = false
    private var closed = false
    @Volatile
    private var ownedGroupName: String? = null

    /**
     * 本次会话里，车机 Wi-Fi 是**我们**打开的（不是用户 / 其他应用本来就开着的）。
     *
     * 只决定"收尾要不要关回去"，绝不参与任何其它判断——见
     * [legacyWifiStaShouldBeRestored] 的两条归属纪律。
     */
    @Volatile
    private var staPoweredByUs = false


    override fun start(timeoutMillis: Long): WirelessHotspotInfo {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            throw IOException("Legacy Wi-Fi P2P manager is only for Android 9 and earlier")
        }
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "LegacyWifiP2pGroupManager.start must not run on the main thread"
        }
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        checkPermissions()

        val attempt = StartAttempt()
        synchronized(lock) {
            check(!closed) { "LegacyWifiP2pGroupManager is closed" }
            check(startAttempt == null && !created) { "A Wi-Fi P2P group is already starting or active" }
            startAttempt = attempt
        }
        val thread = HandlerThread("dbplay-wifi-p2p-legacy").apply { start() }
        attempt.thread = thread
        val deadline = deadlineAfter(timeoutMillis)

        try {
            // ⚠️ 顺序：先把车机 Wi-Fi 打开，再 initialize()。Wi-Fi 没开时框架的 P2P
            // 状态机根本不会起来，requestGroupInfo / createGroup 只会回 BUSY / ERROR。
            ensureWifiStationPowered(attempt, deadline, timeoutMillis)
            val p2pChannel = p2pManager.initialize(appContext, thread.looper) {
                failAttempt(attempt, IOException("Wi-Fi P2P channel disconnected"))
            } ?: throw IOException("Wi-Fi P2P could not initialize its channel")
            attempt.channel = p2pChannel
            synchronized(lock) {
                ensureActiveLocked(attempt)
                channel = p2pChannel
                callbackThread = thread
            }

            val existing = requestGroupInfo(attempt, p2pChannel, REQUEST_TIMEOUT_NANOS, requireResponse = true)
            if (existing != null) {
                val recordedName = prefs.getString(KEY_OWNED_GROUP, null)
                if (existing.isGroupOwner && existing.networkName == recordedName && !recordedName.isNullOrBlank()) {
                    ownedGroupName = recordedName
                    diagnostic("Wi-Fi P2P legacy reclaiming recorded group")
                    removeGroupBlocking(p2pChannel, recordedName)
                    waitForGroupRemoval(attempt, p2pChannel, deadline, timeoutMillis)
                } else {
                    throw IOException("Wi-Fi Direct is already in use by another app; close that connection and retry")
                }
            }

            createGroupWithRetry(attempt, p2pChannel, deadline, timeoutMillis)
            val group = awaitUsableGroup(attempt, p2pChannel, deadline, timeoutMillis)
            if (!prefs.edit().putString(KEY_OWNED_GROUP, group.ssid).commit()) {
                ownedGroupName = group.ssid
                throw IOException("Could not record ownership of the Wi-Fi Direct group")
            }
            synchronized(lock) {
                ensureActiveLocked(attempt)
                ownedGroupName = group.ssid
                created = true
                startAttempt = null
            }
            diagnostic(
                "Wi-Fi P2P legacy ready band=${group.bandLabel} channel=${group.channel} " +
                    "frequencyMHz=unavailable verified=false",
            )
            return group
        } catch (failure: Exception) {
            diagnostic("Wi-Fi P2P legacy startup failed type=${failure.javaClass.simpleName}")
            cleanupFailedStart(attempt)
            throw failure
        }
    }

    override fun close() {
        val attempt: StartAttempt?
        val activeChannel: WifiP2pManager.Channel?
        val activeThread: HandlerThread?
        synchronized(lock) {
            if (closed) return
            closed = true
            attempt = startAttempt
            attempt?.stopped = true
            lock.notifyAll()
            activeChannel = channel ?: attempt?.channel
            activeThread = callbackThread ?: attempt?.thread
            channel = null
            callbackThread = null
            startAttempt = null
        }
        if (activeChannel != null) {
            val knownName = ownedGroupName
            if (knownName != null) removeOwnedGroup(activeChannel, knownName)
        }
        activeThread?.quitSafely()
        // 最后归还 Wi-Fi：必须在拆组之后 —— 组还在时把 Wi-Fi 关掉，框架的
        // removeGroup 反而会拿不到通道。本方法不抛异常（见其 KDoc）。
        restoreWifiStation()
    }

    private fun checkPermissions() {
        if (appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            throw IOException("请在车机应用权限中允许 DBPlay 使用位置信息；Android 9 的 Wi-Fi Direct 需要此权限")
        }
        // Location service state is diagnostic only; some Android 9 vendor builds allow P2P while off.
        val frequency = runCatching {
            @Suppress("DEPRECATION")
            wifiManager?.connectionInfo?.frequency
        }.getOrNull()
        diagnostic(
            "Wi-Fi P2P legacy preflight wifiState=${readWifiState()} " +
                "stationMHz=${frequency ?: "unknown"} ${preflightFacts()}",
        )
    }

    /**
     * 与 Wi-Fi Direct 成败直接相关的三条事实（诊断与失败文案共用）。
     *
     * 为什么要专门取这三条：Android 9 上 `createGroup` 失败只会给
     * `P2P_UNSUPPORTED` / `ERROR` / `BUSY` 三个整数，**看不出是缺哪一条前提**，
     * 而那三条前提的"能不能自己解决"完全不同：
     *
     * | 事实 | 取不到时的表现 | 用户能做什么 |
     * | --- | --- | --- |
     * | `featureWifiDirect`（车机固件是否声明 Wi-Fi Direct） | `P2P_UNSUPPORTED` | 这台车用不了这个模式 ⇒ 改用自建热点 |
     * | `locationEnabled`（车机定位开关） | `ERROR` / `BUSY` | 去车机设置里打开定位（Android 9 把 Wi-Fi Direct 划在定位权限域） |
     * | `wifiState`（STA 是否 ENABLED） | `BUSY` | 由本类自己解决（[ensureWifiStationPowered]） |
     *
     * ⚠️ 这三条**只用于诊断与提示**，不作硬门禁：厂商 ROM 有漏声明
     * `FEATURE_WIFI_DIRECT` 却仍能建组的先例，硬拦会把能用的路堵死。
     */
    private fun preflightFacts(): String = "featureWifiDirect=${hasWifiDirectFeature()} " +
        "locationEnabled=${isLocationEnabled()} wifiState=${readWifiState() ?: "unknown"} " +
        "p2p0=${p2pInterfaceState()}"

    /**
     * P2P 网卡（`p2p0`）现在是什么状态；读不到返回 `null`。
     *
     * ## 为什么要看它（2026-10-11 真车：`createGroup` 一律 BUSY）
     *
     * Android 上 Wi-Fi Direct 正常工作时，框架会让 supplicant 把 `p2p0` 拉起来
     * （P2P-device 接口）。真车这台（CS11，Android 9，厂商用 IPCP 代管 Wi-Fi）上
     * `p2p0` **一直是 DOWN、`init.svc.wpa_supplicant` 是空的**，而 `createGroup` 每次都
     * 在 5ms 内回 BUSY —— 三者合起来说明**框架侧的 P2P 根本没起来**，不是我们调用姿势的问题。
     *
     * ⚠️ 它只是**旁证**：`p2p0` 尚未建立也可能出现在"刚启用、还没拉起接口"的瞬间，
     * 所以只在"三位前提全满足 + 连续 BUSY"时才拿来下结论（见 [createGroupFailure]）。
     */
    private fun p2pInterfaceState(): String {
        val iface = runCatching { java.net.NetworkInterface.getByName(P2P_INTERFACE) }.getOrNull()
            ?: return "absent"
        val up = runCatching { iface.isUp }.getOrDefault(false)
        return if (up) "up" else "down"
    }

    private fun hasWifiDirectFeature(): Boolean = runCatching {
        appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)
    }.getOrDefault(false)

    private fun isLocationEnabled(): Boolean = runCatching {
        appContext.getSystemService(LocationManager::class.java)?.isLocationEnabled
    }.getOrNull() ?: false

    /**
     * 确保车机 Wi-Fi（STA）已上电。
     *
     * ## 为什么由本类来做，而不是报错让用户去系统设置里开
     *
     * 真车日志（诊断包 `1791546694008_e2f499c9_67b3c24af6b5`）里这台车机的框架 Wi-Fi
     * 平时就是关的（上行是以太网，见 `system/environment.txt`），而**车机 UI 上并没有
     * 一个"打开 Wi-Fi"的入口**——用户拿着"请去车机设置里打开 Wi-Fi"这句话什么也做不了，
     * 只能反复重试，日志里 7 次全败。DBPlay 已经声明了 `CHANGE_WIFI_STATE`
     * （普通权限，安装即授予），这件事自己就做得了。
     *
     * ## 归属纪律
     *
     * - 本来就开着 ⇒ 只记诊断，**永不接管**（[staPoweredByUs] 保持 false），
     *   收尾也绝不去关它——车机可能正连着手机热点（`SAME_LAN` 模式的前提）。
     * - 由本方法打开 ⇒ 记 [staPoweredByUs]，由 [restoreWifiStation] 归还。
     */
    @Suppress("DEPRECATION")
    private fun ensureWifiStationPowered(attempt: StartAttempt, deadline: Long, timeoutMillis: Long) {
        val wifi = wifiManager
            ?: throw IOException("WifiManager is unavailable")
        val powerBudget = minOf(WIFI_STATION_POWER_TIMEOUT_MILLIS, timeoutMillis)
        val powerDeadline = minOf(deadline, deadlineAfter(powerBudget))
        // 先让在途的状态迁移落定：上一次会话收尾的 `setWifiEnabled(false)` 可能还停在
        // DISABLING(0)，此时立刻再 `setWifiEnabled(true)`，厂商 ROM 有的直接丢掉、
        // 有的长时间卡在 ENABLING —— 现场表现是"连续重试时第二、三次反而开不起来"。
        awaitWifiStationSettled(attempt, powerDeadline)
        val initial = readWifiState()
        if (legacyWifiStaIsPoweredOn(initial)) {
            diagnostic("Wi-Fi P2P legacy Wi-Fi station already on state=$initial; leaving it untouched")
            return
        }
        val requested = runCatching { wifi.setWifiEnabled(true) }.getOrDefault(false)
        diagnostic("Wi-Fi P2P legacy powering on Wi-Fi station state=$initial requested=$requested")
        if (!requested) {
            throw IOException("Wi-Fi Direct could not turn on the head unit's Wi-Fi station")
        }
        // 状态同步：`setWifiEnabled` 只是"受理"，`getWifiState()` 要经过 ENABLING(2)
        // 才会到 ENABLED(3)。必须等到 3 才继续——在 ENABLING 上调 createGroup
        // 拿到的是 BUSY / ERROR（见 legacyWifiStaIsPoweredOn 的 KDoc）。
        while (true) {
            ensureActive(attempt)
            val state = readWifiState()
            if (legacyWifiStaIsPoweredOn(state)) {
                staPoweredByUs = true
                diagnostic("Wi-Fi P2P legacy Wi-Fi station on state=$state")
                return
            }
            val remaining = remainingNanos(powerDeadline)
            if (remaining <= 0) {
                // ⚠️ 文案刻意**不**用 "Timed out after …"：那与蓝牙 RFCOMM 的超时
                // 前缀相同，`CarErrorText` 的 PREFIXES 是按 `startsWith` 匹配的，
                // 撞车后两条会互相抢。这里用一句自成一体的句子，便于精确翻译。
                throw IOException(
                    "Wi-Fi Direct did not see the head unit's Wi-Fi turn on within ${powerBudget}ms " +
                        "(state=${state ?: "unknown"})",
                )
            }
            synchronized(lock) { waitNanos(minOf(remaining, WIFI_STATION_POLL_NANOS)) }
        }
    }

    /**
     * 等 Wi-Fi 离开 `DISABLING` / `ENABLING` 这类"在途"状态。
     *
     * 超时**不算失败**：等不到就交给后面的正常判定（读到 ENABLED 就复用、
     * 读到别的就发开启请求），而不是在这里把会话判死。
     */
    private fun awaitWifiStationSettled(attempt: StartAttempt, powerDeadline: Long) {
        while (true) {
            ensureActive(attempt)
            if (!legacyWifiStaIsTransitioning(readWifiState())) return
            val remaining = remainingNanos(powerDeadline)
            if (remaining <= 0) return
            synchronized(lock) { waitNanos(minOf(remaining, WIFI_STATION_POLL_NANOS)) }
        }
    }

    /**
     * 归还本次会话由我们打开的车机 Wi-Fi。
     *
     * **绝不抛异常**：收尾路径上不能因为一个清理动作把整个会话的收尾打断
     * （与 [removeOwnedGroup] / `CarHotspotModeInit` 同一条纪律）。
     * 是否真的关得掉只进诊断，由下一次 `start()` 自己重新判断。
     *
     * ⚠️ 这里**不等**到状态真正变成 DISABLED 才返回之外的任何事：`setWifiEnabled(false)`
     * 是异步的，而 `close()` 在会话收尾路径上（用户可能正等着返回上一页）。
     * 只做一次有界确认（[WIFI_STATION_RELEASE_TIMEOUT_MILLIS]）并如实记录结果。
     */
    @Suppress("DEPRECATION")
    private fun restoreWifiStation() {
        if (!staPoweredByUs) return
        val wifi = wifiManager ?: return
        val enabled = runCatching { wifi.isWifiEnabled }.getOrNull()
        if (!legacyWifiStaShouldBeRestored(staPoweredByUs, enabled)) {
            diagnostic("Wi-Fi P2P legacy Wi-Fi station restore skipped enabled=$enabled")
            return
        }
        val requested = runCatching { wifi.setWifiEnabled(false) }.getOrDefault(false)
        val confirmed = if (requested) awaitWifiStationOff() else false
        diagnostic("Wi-Fi P2P legacy Wi-Fi station released requested=$requested confirmed=$confirmed")
    }

    /** 有界轮询等 Wi-Fi 真的关掉。读不到状态按"已归还"处理（同 `awaitResidueCleared`）。 */
    private fun awaitWifiStationOff(): Boolean {
        val releaseDeadline = deadlineAfter(WIFI_STATION_RELEASE_TIMEOUT_MILLIS)
        while (true) {
            val state = readWifiState()
            if (state == null || state == LEGACY_WIFI_STATE_DISABLED) return true
            if (remainingNanos(releaseDeadline) <= 0) return false
            runCatching { Thread.sleep(WIFI_STATION_RELEASE_POLL_MILLIS) }
        }
    }

    private fun readWifiState(): Int? = runCatching { wifiManager?.wifiState }.getOrNull()

    private fun createActionListener(attempt: StartAttempt) = object : WifiP2pManager.ActionListener {
        override fun onSuccess() {
            synchronized(lock) {
                attempt.createSucceeded = true
                lock.notifyAll()
            }
        }

        override fun onFailure(reason: Int) {
            // ⚠️ 这里**不**走 failAttempt：那条通道是"致命、不可重试"的（通道断开），
            // 而 createGroup 的失败原因要先过 legacyP2pCreateGroupRetryable
            // 才知道该不该再试一次。
            synchronized(lock) {
                if (startAttempt === attempt && !attempt.stopped && !closed) {
                    attempt.createFailureReason = reason
                    lock.notifyAll()
                }
            }
        }
    }

    /** 通道断开之类**不可重试**的致命失败（与 createGroup 的失败原因分开记）。 */
    private fun failAttempt(attempt: StartAttempt, failure: IOException) {
        synchronized(lock) {
            if (startAttempt === attempt && !attempt.stopped && !closed) {
                if (attempt.channelFailure == null) attempt.channelFailure = failure
                lock.notifyAll()
            }
        }
    }

    /**
     * 发 `createGroup`，对**可重试**的失败（`BUSY` / `ERROR`）有界重试。
     *
     * ## 为什么需要重试（"状态同步"）
     *
     * [ensureWifiStationPowered] 等到 `WIFI_STATE_ENABLED` 才放行，但框架侧的 P2P 状态机
     * 是**随后**才被拉起来的（`WifiController` 在 Wi-Fi 到位之后才启用 P2P）。
     * 中间那一小段窗口里 `createGroup` 会立刻回 `BUSY` / `ERROR`——现场表现就是
     * "Wi-Fi 明明开好了，Wi-Fi Direct 却说自己忙"。
     *
     * 只对 [legacyP2pCreateGroupRetryable] 认可的原因重试；`P2P_UNSUPPORTED` /
     * `NO_PERMISSION` 立即失败（重试一万次也一样，还白吃用户的等待时间）。
     */
    private fun createGroupWithRetry(
        attempt: StartAttempt,
        channel: WifiP2pManager.Channel,
        deadline: Long,
        timeoutMillis: Long,
    ) {
        // ⚠️ 2026-10-10（真车日志 1791626784246）：`requestGroupInfo` 刚回来就立刻发
        // `createGroup`，车机**在 5ms 内**就回 `BUSY` —— 那是框架"上一个请求还在处理 /
        // P2P 状态机尚未就绪"的答复，不是真的有人占着组（同一次里 `requestGroupInfo`
        // 明确答过"没有组"，两者互相矛盾 ⇒ 只能是状态没落定）。
        // 先给一小段落定时间，再发第一次。
        awaitCreateGroupSettle(attempt, deadline)
        // ------------------------------------------------------------------
        // 🟢 2026-10-11：按 DiPlay 的 Android 9 路**沿信道计划走**，而不是重试同一个请求。
        //
        // DiPlay 原文：`BUSY is a per-channel outcome, not a verdict on Wi-Fi Direct:
        // several firmwares reject every 5 GHz request while 2.4 GHz or the system
        // default configuration still succeeds.` —— 与真车日志完全吻合：旧实现 4 次
        // 全部是同一个请求、5ms 内 BUSY，2.4GHz 与系统默认重载**一次都没试过**。
        //
        // 每一步：先 pin 工作信道（Android 9 只能走隐藏 API）→ 再 `createGroup`（经典重载）。
        // `BUSY` 在**同一信道上**最多再试一次，然后换下一个信道；`P2P_UNSUPPORTED` /
        // `NO_PERMISSION` 换信道也救不了，立即失败。
        // ------------------------------------------------------------------
        val plan = LegacyWifiP2pChannels.planChannels()
        var totalAttempts = 0
        var lastReason = WifiP2pManager.ERROR
        var restrictionPinned = false
        for ((stepIndex, frequencyMHz) in plan.withIndex()) {
            ensureActive(attempt)
            if (remainingNanos(deadline) <= 0) {
                // 文案刻意沿用既有前缀（`Wi-Fi P2P createGroup failed after …`）：
                // `CarErrorText` 是按前缀匹配的，另起一句会漏出英文原文。
                throw IOException(
                    "Wi-Fi P2P createGroup failed after $totalAttempts attempts: " +
                        "Wi-Fi Direct startup timed out (${preflightFacts()})",
                )
            }
            val step = "step=${stepIndex + 1}/${plan.size}"
            if (frequencyMHz != null) {
                val operatingChannel = LegacyWifiP2pChannels.operatingChannelFor(frequencyMHz)
                if (operatingChannel == null) {
                    diagnostic("Wi-Fi P2P legacy $step frequencyMHz=$frequencyMHz unmappable; skipping")
                    continue
                }
                val closed = runCatching { pinLegacyOperatingChannel(attempt, channel, operatingChannel) }
                if (closed.isFailure) {
                    // ⚠️ 2026-10-11（PATCH-0082）：pin 结果未知（**超时**）**不再中止整轮**。
                    //
                    // 真车 1.0.44 实测（CS11）：`channel pin callback timeout channel=36` ⇒
                    // 旧逻辑在这里立刻抛错，于是**第一个信道就把整轮掐了**（`createGroup` 一次都没发）。
                    // 而同一份日志里另外两条路的证据是：`createGroup` 会在 5ms 内回 `BUSY(2)`。
                    // 车机厂方自己的做法更能说明问题 —— 它的 pin 回调 **成功与失败都会接着 createGroup**
                    // （`WifiP2pService.i.b`），即 pin 只是"希望用这个信道"，不是前提。
                    //
                    // ⇒ 现在：pin 超时/异常只**记一条**，然后照常在本信道 `createGroup`；
                    //    `BUSY` 会很快回，于是 5 个信道都能真正试到（旧逻辑只试了 1 个）。
                    diagnostic(
                        "Wi-Fi P2P legacy $step channel=$operatingChannel pin unknown " +
                            "(${closed.exceptionOrNull()?.message}); still createGroup",
                    )
                } else if (closed.getOrDefault(false)) {
                    restrictionPinned = true
                } else {
                    // pin 没成功（方法不存在 / 明确被拒）⇒ 同上：只记，不跳过 createGroup。
                    diagnostic("Wi-Fi P2P legacy $step channel=$operatingChannel pin=false; still createGroup")
                }
                diagnostic(
                    "Wi-Fi P2P legacy $step channel=$operatingChannel " +
                        "band=${LegacyWifiP2pChannels.bandLabel(frequencyMHz)}",
                )
            } else {
                // 最后一步：不指定信道（系统默认重载）。先清掉我们 pin 过的限制，
                // 否则默认重载会**继承**上一次 pin 的结果（DiPlay 的 `legacyRestrictionOwned` 同理）。
                if (restrictionPinned) {
                    val cleared = runCatching { clearLegacyOperatingChannel(attempt, channel) }
                        .getOrDefault(false)
                    if (!cleared) {
                        throw IOException(
                            "Wi-Fi Direct could not clear its previous channel restriction",
                        )
                    }
                    restrictionPinned = false
                }
                diagnostic("Wi-Fi P2P legacy $step channel=system_default（不指定信道）")
            }

            var sameChannelRetried = false
            while (true) {
                totalAttempts++
                synchronized(lock) {
                    attempt.createSucceeded = false
                    attempt.createFailureReason = null
                    lock.notifyAll()
                }
                diagnostic(
                    "Wi-Fi P2P legacy createGroup sdk=${Build.VERSION.SDK_INT} $step " +
                        "attempt=$totalAttempts credentials=system_generated",
                )
                p2pManager.createGroup(channel, createActionListener(attempt))
                if (awaitGroupCreated(attempt, deadline, timeoutMillis)) return
                val reason = synchronized(lock) { attempt.createFailureReason } ?: WifiP2pManager.ERROR
                lastReason = reason
                if (!legacyP2pCreateGroupRetryable(reason)) {
                    // P2P_UNSUPPORTED / NO_PERMISSION：换信道也救不了。
                    throw IOException(createGroupFailure(totalAttempts, reason))
                }
                if (reason == WifiP2pManager.BUSY && !sameChannelRetried) {
                    // DiPlay：同一个信道上再给它一次机会（`retriedBusy`），之后才换信道。
                    sameChannelRetried = true
                    diagnostic("Wi-Fi P2P legacy createGroup busy on channel ${frequencyMHz ?: "default"}; retry once")
                    awaitCreateGroupRetryDelay(attempt, deadline)
                    continue
                }
                diagnostic(
                    "Wi-Fi P2P legacy createGroup rejected after ${failureReason(reason)} " +
                        "on channel ${frequencyMHz ?: "default"}; next channel",
                )
                awaitCreateGroupRetryDelay(attempt, deadline)
                break
            }
        }
        throw IOException(createGroupFailure(totalAttempts, lastReason))
    }

    /**
     * 用隐藏 API pin 住 P2P 工作信道（Android 9 唯一办法）。
     *
     * `WifiP2pManager.setWifiP2pChannels(Channel, int listen, int operating, ActionListener)`：
     * 第 2 个参数是**监听信道**（固定 [LegacyWifiP2pChannels.LISTEN_CHANNEL]），第 3 个才是工作信道。
     *
     * @return true = 框架接受了；false = 方法不存在或明确被拒（**不要**当成致命错误，
     *         换下一个信道即可）。
     * @throws IOException 回调超时 —— 结果未知，调用方必须中止整轮（类 DiPlay 的
     *         `LegacyChannelOutcomeUnknown`）。
     */
    private fun pinLegacyOperatingChannel(
        attempt: StartAttempt,
        channel: WifiP2pManager.Channel,
        operatingChannel: Int,
    ): Boolean = invokeSetWifiP2pChannels(attempt, channel, operatingChannel, required = true)

    /** 清掉信道限制（读作"工作信道 = 0"，即恢复系统默认）。 */
    private fun clearLegacyOperatingChannel(
        attempt: StartAttempt,
        channel: WifiP2pManager.Channel,
    ): Boolean = invokeSetWifiP2pChannels(attempt, channel, 0, required = false)

    private fun invokeSetWifiP2pChannels(
        attempt: StartAttempt,
        channel: WifiP2pManager.Channel,
        operatingChannel: Int,
        required: Boolean,
    ): Boolean {
        val method = runCatching {
            WifiP2pManager::class.java.getMethod(
                "setWifiP2pChannels",
                WifiP2pManager.Channel::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                WifiP2pManager.ActionListener::class.java,
            )
        }.getOrNull()
        if (method == null) {
            diagnostic("Wi-Fi P2P legacy channel pin unavailable（本机框架没有 setWifiP2pChannels）")
            return false
        }
        val settled = CountDownLatch(1)
        val accepted = java.util.concurrent.atomic.AtomicBoolean(false)
        val listener = object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                accepted.set(true)
                settled.countDown()
            }

            override fun onFailure(reason: Int) {
                diagnostic("Wi-Fi P2P legacy channel pin rejected code=$reason channel=$operatingChannel")
                settled.countDown()
            }
        }
        val invoked = runCatching {
            method.invoke(
                p2pManager,
                channel,
                LegacyWifiP2pChannels.LISTEN_CHANNEL,
                operatingChannel,
                listener,
            )
        }
        if (invoked.isFailure) {
            val error = invoked.exceptionOrNull()
            diagnostic(
                "Wi-Fi P2P legacy channel pin call failed " +
                    "failureClass=${(error?.cause ?: error)?.javaClass?.simpleName}",
            )
            // 反射调用抛 InvocationTargetException ⇒ 底层已经动过 supplicant 状态，结果未知。
            if (error is java.lang.reflect.InvocationTargetException && required) {
                throw IOException("Wi-Fi Direct channel pin failed with an unknown outcome", error)
            }
            return false
        }
        val completed = await(
            settled,
            TimeUnit.MILLISECONDS.toNanos(LegacyWifiP2pChannels.CALLBACK_TIMEOUT_MILLIS),
        )
        if (!completed) {
            diagnostic("Wi-Fi P2P legacy channel pin callback timeout channel=$operatingChannel")
            if (required) {
                throw IOException("Wi-Fi Direct channel pin did not respond（结果未知）")
            }
            return false
        }
        ensureActive(attempt)
        return accepted.get()
    }

    /**
     * `createGroup` 连续失败时的**可行动**文案（PATCH-0067，2026-10-10）。
     *
     * 框架只给一个错误码，而"缺固件特性 / 定位关着 / 被别的应用占着"三种情况的
     * 处置方式完全不同。这里先按**能与不能自己动手**分档，取到哪条就说哪条；
     * 三条都不成立时保持原来那句（`CarErrorText` 已有对应中文）。
     *
     * ⚠️ 新加的两句各有独立前缀，必须同时在 `CarErrorText` 里登记，否则用户
     * 只会看到英文原文（那正是这个文件里最忌讳的事）。
     */
    private fun createGroupFailure(attempts: Int, reason: Int): String = when {
        !hasWifiDirectFeature() ->
            "Wi-Fi Direct is not supported by this head unit"
        !isLocationEnabled() ->
            "Wi-Fi Direct needs the head unit's location switch to be on"
        // 2026-10-10（真车日志 1791626784246）：三条前提**全部满足**（固件声明支持、
        // 定位已开、Wi-Fi 已 ENABLED），`createGroup` 仍在 5ms 内回 BUSY ×N。
        // 2026-10-11（真车日志 1791680364941）补上第四个旁证：`p2p0` **一直是 DOWN**。
        // 三者合起来指向一个具体结论：**框架侧的 P2P 服务根本没跑起来**（固件侧），
        // 而不是"被别的应用占用"。单独给它一句，UI 才能给出可行动结论。
        // （参考实现 DiPlay 在 CS11 上走的也是热点路，没走 P2P —— 见 `LynkWifiP2pGroup` 类注释。）
        reason == WifiP2pManager.BUSY &&
            hasWifiDirectFeature() &&
            isLocationEnabled() &&
            legacyWifiStaIsPoweredOn(readWifiState()) &&
            p2pInterfaceState() != "up" ->
            "Wi-Fi Direct is not running on this head unit"
        // 前提全满足、连续 BUSY，但 `p2p0` 是 UP ⇒ 服务在跑却一直说忙（更像被占用）。
        reason == WifiP2pManager.BUSY &&
            hasWifiDirectFeature() &&
            isLocationEnabled() &&
            legacyWifiStaIsPoweredOn(readWifiState()) ->
            "Wi-Fi Direct is not usable on this head unit"
        else ->
            "Wi-Fi P2P createGroup failed after $attempts attempts: ${failureReason(reason)} " +
                "(${preflightFacts()})"
    }

    /**
     * 等 `createGroup` 出结果。
     *
     * @return true = 成功；false = **可重试**的失败（原因已记进
     *         [StartAttempt.createFailureReason]，由调用方决定还试不试）。
     *         不可重试的失败与超时在这里直接抛。
     */
    private fun awaitGroupCreated(attempt: StartAttempt, deadline: Long, timeoutMillis: Long): Boolean {
        synchronized(lock) {
            while (true) {
                ensureActiveLocked(attempt)
                if (attempt.createSucceeded) return true
                attempt.createFailureReason?.let { reason ->
                    if (legacyP2pCreateGroupRetryable(reason)) return false
                    throw IOException("Wi-Fi P2P createGroup failed: ${failureReason(reason)}")
                }
                val remaining = remainingNanos(deadline)
                if (remaining <= 0) {
                    throw IOException("Timed out after ${timeoutMillis}ms waiting for legacy Wi-Fi P2P group creation")
                }
                waitNanos(remaining)
            }
        }
    }

    /** 两次 `createGroup` 之间留一点时间给 P2P 状态机起来（同样受总截止时间约束）。 */
    private fun awaitCreateGroupRetryDelay(attempt: StartAttempt, deadline: Long) {
        val delayDeadline = minOf(deadline, deadlineAfter(CREATE_GROUP_RETRY_DELAY_MILLIS))
        synchronized(lock) {
            while (true) {
                ensureActiveLocked(attempt)
                val remaining = remainingNanos(delayDeadline)
                if (remaining <= 0) return
                waitNanos(remaining)
            }
        }
    }

    /**
     * 第一次 `createGroup` 之前的**落定等待**（PATCH-0067）。
     *
     * 真车实测：紧跟 `requestGroupInfo` 之后发 `createGroup`，车机 5ms 内回 `BUSY`。
     * 这只是"给框架一点时间把上一个请求收尾"，与"上电等状态同步"是同一类补丁 ——
     * 代价 400ms，收益是少一整轮（3 次 × 1.5s）无谓重试。
     */
    private fun awaitCreateGroupSettle(attempt: StartAttempt, deadline: Long) {
        val settleDeadline = minOf(deadline, deadlineAfter(CREATE_GROUP_SETTLE_MILLIS))
        synchronized(lock) {
            while (true) {
                ensureActiveLocked(attempt)
                val remaining = remainingNanos(settleDeadline)
                if (remaining <= 0) return
                waitNanos(remaining)
            }
        }
    }

    private fun awaitUsableGroup(
        attempt: StartAttempt,
        channel: WifiP2pManager.Channel,
        deadline: Long,
        timeoutMillis: Long,
    ): WirelessHotspotInfo {
        var lastReason = "group information was not available"
        while (true) {
            ensureActive(attempt)
            if (remainingNanos(deadline) <= 0) {
                throw IOException("Timed out after ${timeoutMillis}ms waiting for a usable legacy Wi-Fi P2P group: $lastReason")
            }
            val group = requestGroupInfo(attempt, channel, minOf(remainingNanos(deadline), REQUEST_TIMEOUT_NANOS)) ?: continue
            if (!group.isGroupOwner) throw IOException("Wi-Fi P2P device became a group client instead of owner")
            val ssid = group.networkName?.takeIf { it.isNotBlank() }
            val passphrase = group.passphrase?.takeIf { it.isNotBlank() }
            val iface = runCatching { group.getInterface()?.takeIf { it.isNotBlank() } }.getOrNull()
            if (ssid == null || passphrase == null || iface == null) {
                lastReason = "system credentials or the group interface are unavailable"
                continue
            }
            // Keep the exact observed identity before waiting for the address. close() can then
            // clean up this group if startup is cancelled, but can never remove an unrelated one.
            ownedGroupName = ssid
            val address = awaitInterfaceAddress(attempt, iface, deadline)
                ?: requestConnectionAddress(attempt, channel, minOf(remainingNanos(deadline), REQUEST_TIMEOUT_NANOS))
            if (address == null) {
                lastReason = "interface $iface has no usable address"
                continue
            }
            val ifAddress = networkInterface(iface)?.hardwareAddress
            val bssid = ifAddress?.takeIf { it.isNotEmpty() }
                ?.joinToString(":") { "%02x".format(it.toInt() and 0xff) }
                ?: group.owner?.deviceAddress?.takeIf { it.isNotBlank() }
            return WirelessHotspotInfo(
                ssid = ssid,
                passphrase = passphrase,
                security = Iap2WirelessSecurity.WPA_WPA2,
                channel = 0,
                frequencyMHz = null,
                bssid = bssid,
                interfaceName = iface,
                hostAddress = address,
                bandLabel = "系统选择（Android 9 未验证）",
                backend = WirelessHotspotBackend.WIFI_P2P,
            )
        }
    }

    private fun requestGroupInfo(
        attempt: StartAttempt,
        channel: WifiP2pManager.Channel,
        timeoutNanos: Long,
        requireResponse: Boolean = false,
    ): WifiP2pGroup? {
        val result = AtomicReference<WifiP2pGroup?>()
        val responded = java.util.concurrent.atomic.AtomicBoolean(false)
        val latch = CountDownLatch(1)
        p2pManager.requestGroupInfo(channel) {
            result.set(it)
            responded.set(true)
            latch.countDown()
        }
        if (!await(latch, timeoutNanos) && requireResponse) {
            throw IOException("Wi-Fi Direct did not respond while checking existing groups")
        }
        if (!responded.get()) return null
        ensureActive(attempt)
        return result.get()
    }

    private fun requestConnectionAddress(
        attempt: StartAttempt,
        channel: WifiP2pManager.Channel,
        timeoutNanos: Long,
    ): InetAddress? {
        val result = AtomicReference<WifiP2pInfo?>()
        val latch = CountDownLatch(1)
        p2pManager.requestConnectionInfo(channel) {
            result.set(it)
            latch.countDown()
        }
        if (!await(latch, timeoutNanos)) return null
        ensureActive(attempt)
        val info = result.get() ?: return null
        if (!info.groupFormed) return null
        return info.groupOwnerAddress?.takeUnless(InetAddress::isAnyLocalAddress)
    }

    private fun awaitInterfaceAddress(attempt: StartAttempt, iface: String, deadline: Long): InetAddress? {
        val addressDeadline = minOf(deadline, deadlineAfter(2_000))
        var latest: InetAddress? = null
        while (true) {
            ensureActive(attempt)
            val networkInterface = networkInterface(iface)
            val addresses = networkInterface?.let { Collections.list(it.inetAddresses) }.orEmpty()
            val linkLocal = addresses.firstOrNull { it is Inet6Address && it.isLinkLocalAddress }
            latest = if (linkLocal is Inet6Address && networkInterface != null) {
                try {
                    Inet6Address.getByAddress(null, linkLocal.address, networkInterface)
                } catch (_: UnknownHostException) {
                    linkLocal
                }
            } else {
                addresses.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            }
            if (latest is Inet6Address || remainingNanos(addressDeadline) <= 0) return latest
            try {
                Thread.sleep(100)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Interrupted while waiting for the Wi-Fi P2P interface address", interrupted)
            }
        }
    }

    private fun waitForGroupRemoval(
        attempt: StartAttempt,
        channel: WifiP2pManager.Channel,
        deadline: Long,
        timeoutMillis: Long,
    ) {
        val expectedName = ownedGroupName
            ?: throw IOException("No owned Wi-Fi Direct group identity was recorded")
        while (true) {
            val remaining = remainingNanos(deadline)
            if (remaining <= 0) {
                throw IOException("Timed out after ${timeoutMillis}ms removing the previously owned Wi-Fi Direct group")
            }
            val result = AtomicReference<WifiP2pGroup?>()
            val latch = CountDownLatch(1)
            p2pManager.requestGroupInfo(channel) {
                result.set(it)
                latch.countDown()
            }
            if (!await(latch, minOf(remaining, REQUEST_TIMEOUT_NANOS))) {
                throw IOException("Wi-Fi Direct did not respond while verifying group removal")
            }
            ensureActive(attempt)
            val group = result.get() ?: return
            if (!legacyP2pGroupCanBeRemoved(group.isGroupOwner, group.networkName, expectedName)) {
                throw IOException("Wi-Fi Direct group identity changed during cleanup; refusing to remove another group's network")
            }
            synchronized(lock) { waitNanos(TimeUnit.MILLISECONDS.toNanos(100)) }
        }
    }

    private fun removeOwnedGroup(channel: WifiP2pManager.Channel, expectedName: String) {
        val current = requestGroupInfoBestEffort(channel) ?: return
        if (!legacyP2pGroupCanBeRemoved(current.isGroupOwner, current.networkName, expectedName)) {
            diagnostic("Wi-Fi P2P legacy cleanup skipped=group_identity_changed")
            return
        }
        if (removeGroupBlocking(channel, expectedName)) {
            prefs.edit().remove(KEY_OWNED_GROUP).apply()
        }
    }

    private fun requestGroupInfoBestEffort(channel: WifiP2pManager.Channel): WifiP2pGroup? {
        val result = AtomicReference<WifiP2pGroup?>()
        val latch = CountDownLatch(1)
        return try {
            p2pManager.requestGroupInfo(channel) { result.set(it); latch.countDown() }
            if (latch.await(REQUEST_TIMEOUT_NANOS, TimeUnit.NANOSECONDS)) result.get() else null
        } catch (failure: Exception) {
            diagnostic("Wi-Fi P2P legacy cleanup inspection failed type=${failure.javaClass.simpleName}")
            null
        }
    }

    private fun removeGroupBlocking(channel: WifiP2pManager.Channel, expectedName: String): Boolean {
        val current = requestGroupInfoBestEffort(channel)
        if (current == null ||
            !legacyP2pGroupCanBeRemoved(current.isGroupOwner, current.networkName, expectedName)
        ) {
            diagnostic("Wi-Fi P2P legacy cleanup skipped=group_identity_changed")
            return false
        }
        val removed = AtomicReference(false)
        val latch = CountDownLatch(1)
        try {
            p2pManager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    removed.set(true)
                    latch.countDown()
                }
                override fun onFailure(reason: Int) {
                    diagnostic("Wi-Fi P2P legacy removeGroup failed: ${failureReason(reason)}")
                    latch.countDown()
                }
            })
            if (!latch.await(REMOVE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                diagnostic("Wi-Fi P2P legacy removeGroup callback timed out")
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (failure: RuntimeException) {
            diagnostic("Wi-Fi P2P legacy removeGroup could not be issued type=${failure.javaClass.simpleName}")
        }
        return removed.get()
    }

    private fun cleanupFailedStart(attempt: StartAttempt) {
        val failedChannel: WifiP2pManager.Channel?
        val failedThread: HandlerThread?
        val groupName: String?
        synchronized(lock) {
            if (startAttempt === attempt) startAttempt = null
            attempt.stopped = true
            lock.notifyAll()
            failedChannel = attempt.channel
            failedThread = attempt.thread
            groupName = ownedGroupName
            if (channel === failedChannel) channel = null
            if (callbackThread === failedThread) callbackThread = null
        }
        if (attempt.createSucceeded && failedChannel != null && groupName != null) {
            if (removeGroupBlocking(failedChannel, groupName)) {
                prefs.edit().remove(KEY_OWNED_GROUP).apply()
            }
        }
        failedThread?.quitSafely()
    }

    private fun ensureActive(attempt: StartAttempt) {
        synchronized(lock) { ensureActiveLocked(attempt) }
    }

    private fun ensureActiveLocked(attempt: StartAttempt) {
        if (closed) throw IOException("Legacy Wi-Fi P2P manager closed while starting")
        if (startAttempt !== attempt || attempt.stopped) throw IOException("Legacy Wi-Fi P2P startup was cancelled")
        attempt.channelFailure?.let { throw it }
    }

    private fun waitNanos(nanos: Long) {
        try {
            lock.wait(nanos / NANOS_PER_MILLISECOND, (nanos % NANOS_PER_MILLISECOND).toInt())
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while waiting for Wi-Fi P2P", interrupted)
        }
    }

    private fun await(latch: CountDownLatch, nanos: Long): Boolean = try {
        latch.await(nanos.coerceAtLeast(1), TimeUnit.NANOSECONDS)
    } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        throw IOException("Interrupted while waiting for Wi-Fi P2P", interrupted)
    }

    private fun networkInterface(name: String): NetworkInterface? = try {
        NetworkInterface.getByName(name)
    } catch (_: SocketException) {
        null
    }

    private fun deadlineAfter(millis: Long): Long {
        val now = System.nanoTime()
        val delta = TimeUnit.MILLISECONDS.toNanos(millis)
        return if (Long.MAX_VALUE - now < delta) Long.MAX_VALUE else now + delta
    }

    private fun remainingNanos(deadline: Long): Long = (deadline - System.nanoTime()).coerceAtLeast(0)

    private fun failureReason(reason: Int): String = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi P2P is unsupported"
        WifiP2pManager.BUSY -> "Wi-Fi P2P is busy"
        WifiP2pManager.ERROR -> "generic error"
        WifiP2pManager.NO_PERMISSION -> "permission denied"
        else -> "reason $reason"
    }

    private class StartAttempt(
        var channel: WifiP2pManager.Channel? = null,
        var thread: HandlerThread? = null,
        var createSucceeded: Boolean = false,
        /** 通道断开等**致命、不可重试**的失败（见 [failAttempt]）。 */
        var channelFailure: IOException? = null,
        /**
         * `createGroup` 的失败原因码（`WifiP2pManager.BUSY` 等）。
         *
         * 与 [channelFailure] **分开记**是有意的：重试前要把它清掉，而清掉一个
         * 致命失败会让"通道已断开"被当成"还没出结果"而空转到超时。
         */
        var createFailureReason: Int? = null,
        var stopped: Boolean = false,
    )

    private companion object {
        const val PREFS_NAME = "dbplay_wifi_p2p_legacy"
        const val KEY_OWNED_GROUP = "owned_group_ssid"

        /** P2P 设备接口名（Android 固定用这个名字）。见 [p2pInterfaceState]。 */
        const val P2P_INTERFACE = "p2p0"

        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val REMOVE_TIMEOUT_MILLIS = 2_000L
        val REQUEST_TIMEOUT_NANOS = TimeUnit.MILLISECONDS.toNanos(500)

        /** 两次 `createGroup` 之间的等待：给刚上电的 P2P 状态机一点时间。 */
        const val CREATE_GROUP_RETRY_DELAY_MILLIS = 1_500L

        /**
         * 第一次 `createGroup` 之前的落定等待（毫秒）。
         *
         * 真车日志：紧跟 `requestGroupInfo` 的 `createGroup` 会在 **5ms 内**被回 `BUSY`
         * —— 框架"上一个请求还在处理"，不是真的有人占着组。给这一段落定时间。
         */
        const val CREATE_GROUP_SETTLE_MILLIS = 400L

        /** 等车机 Wi-Fi 上电的总预算（还受 `start()` 的 `timeoutMillis` 约束）。 */
        const val WIFI_STATION_POWER_TIMEOUT_MILLIS = 20_000L

        /** 上电状态轮询间隔。 */
        val WIFI_STATION_POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(200)

        /** 收尾归还 Wi-Fi 的有界确认窗口。 */
        const val WIFI_STATION_RELEASE_TIMEOUT_MILLIS = 2_000L

        /** 归还确认的轮询间隔。 */
        const val WIFI_STATION_RELEASE_POLL_MILLIS = 100L
    }
}
