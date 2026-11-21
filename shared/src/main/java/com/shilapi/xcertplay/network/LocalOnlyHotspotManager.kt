package com.shilapi.xcertplay.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.MacAddress
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import java.io.IOException
import java.lang.reflect.Method
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.net.UnknownHostException
import java.util.Collections
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Owns one Android LocalOnlyHotspot reservation and reports its live configuration.
 *
 * [start] must run on a worker thread because it blocks until the system callback arrives and
 * the AP interface is usable. The reservation and multicast lock stay owned by this instance
 * until [close].
 *
 * ## 固定 SSID / 密码
 *
 * 不传 [ssid] 时走 Android 的原生行为：**每次启动都随机生成 SSID 与密码**。
 * 传了 [ssid] 则尝试用带 `SoftApConfiguration` 的重载把热点名字固定下来
 * （车机场景必须如此，否则 iPhone 每次重连都要重学一张陌生网络）。
 * 这条路径依赖隐藏 API，探测不到时会自动回落，详见 [startWithFixedCredentials]。
 *
 * 只设置 SSID / 密码 / 加密方式三项，**刻意不碰频段与信道**：在 LocalOnlyHotspot 上
 * 这两项与系统的自动选择相互冲突（同时设 band 和 channel 会被框架直接拒绝），
 * 而现有实现本来就把信道上报为 0（auto）。
 */
class LocalOnlyHotspotManager(
    context: Context,
    private val ssid: String? = null,
    private val passphrase: String? = null,
    private val security: ManualHotspotSecurity = ManualHotspotSecurity.WPA2,
) : WirelessHotspotManager {
    private val connectivityManager =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
        ?: throw IllegalStateException("WifiManager is unavailable")
    private val stateLock = Object()

    /** 一次性诊断开关：只打一遍网卡枚举结果，便于在真机上定位热点网卡识别失败。 */
    @Volatile
    private var interfaceProbeLogged = false

    /** 一次性诊断开关：只打一遍网卡选择结果。 */
    @Volatile
    private var interfaceChoiceLogged = false

    private var startAttempt: StartAttempt? = null
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var callbackThread: HandlerThread? = null
    private var stopped = false
    private var closed = false

    /**
     * Starts a LocalOnlyHotspot and waits up to [timeoutMillis] for the live configuration and AP
     * interface. The returned credentials are not retained by this manager.
     */
    override fun start(timeoutMillis: Long): WirelessHotspotInfo {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "LocalOnlyHotspotManager.start must not run on the main thread"
        }
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }

        val attempt = StartAttempt()
        synchronized(stateLock) {
            check(!closed) { "LocalOnlyHotspotManager is closed" }
            check(startAttempt == null && reservation == null) {
                "A LocalOnlyHotspot is already starting or active"
            }
            startAttempt = attempt
        }

        val thread = HandlerThread("xcertplay-local-only-hotspot").apply { start() }
        attempt.thread = thread
        var acquiredMulticastLock: WifiManager.MulticastLock? = null
        val deadlineNanos = deadlineAfter(timeoutMillis)
        val preStartInterfaces = networkInterfaceNames()

        try {
            ensureStartActive(attempt)
            val callback = createCallback(attempt)
            // 回调统一投到这条热点线程上；带配置的重载只收 Executor（没有 Handler 变体）。
            val executor = Executor { command -> Handler(thread.looper).post(command) }
            if (!startWithFixedCredentials(executor, callback)) {
                wifiManager.startLocalOnlyHotspot(callback, Handler(thread.looper))
            }

            val activeReservation = awaitStart(attempt, deadlineNanos, timeoutMillis)
            acquiredMulticastLock = acquireMulticastLock(attempt)
            val configuration = readConfiguration(activeReservation)
            val apInterface = awaitApInterface(
                bssid = configuration.bssidBytes,
                preStartInterfaces = preStartInterfaces,
                attempt = attempt,
                deadlineNanos = deadlineNanos,
            )

            synchronized(stateLock) {
                ensureStartActiveLocked(attempt)
                reservation = activeReservation
                multicastLock = acquiredMulticastLock
                callbackThread = thread
                startAttempt = null
                stopped = false
                acquiredMulticastLock = null
            }

            return WirelessHotspotInfo(
                ssid = configuration.ssid,
                passphrase = configuration.passphrase,
                security = configuration.security,
                channel = configuration.channel,
                frequencyMHz = null,
                bssid = apInterface?.bssid ?: configuration.bssid,
                interfaceName = apInterface?.name,
                hostAddress = apInterface?.hostAddress,
                bandLabel = configuration.bandLabel,
                backend = WirelessHotspotBackend.LOCAL_ONLY_HOTSPOT,
            )
        } catch (failure: Exception) {
            cleanupFailedStart(attempt, acquiredMulticastLock)
            throw failure
        }
    }

    override fun close() {
        val activeReservation: WifiManager.LocalOnlyHotspotReservation?
        val activeMulticastLock: WifiManager.MulticastLock?
        val activeCallbackThread: HandlerThread?
        synchronized(stateLock) {
            if (closed) return
            closed = true
            startAttempt?.stopped = true
            stateLock.notifyAll()
            activeReservation = reservation
            activeMulticastLock = multicastLock
            activeCallbackThread = callbackThread
            reservation = null
            multicastLock = null
            callbackThread = null
        }

        releaseMulticastLock(activeMulticastLock)
        activeReservation?.close()
        activeCallbackThread?.quitSafely()
    }

    private fun createCallback(attempt: StartAttempt): WifiManager.LocalOnlyHotspotCallback =
        object : WifiManager.LocalOnlyHotspotCallback() {
            override fun onStarted(
                reservation: WifiManager.LocalOnlyHotspotReservation,
            ) {
                val closeReservation = synchronized(stateLock) {
                    if (
                        closed ||
                        startAttempt !== attempt ||
                        attempt.stopped ||
                        attempt.reservation != null
                    ) {
                        true
                    } else {
                        attempt.reservation = reservation
                        stateLock.notifyAll()
                        false
                    }
                }
                if (closeReservation) reservation.close()
            }

            override fun onFailed(reason: Int) {
                synchronized(stateLock) {
                    if (startAttempt === attempt && attempt.failure == null) {
                        attempt.failure = IOException(
                            "LocalOnlyHotspot failed: ${failureReason(reason)}",
                        )
                        stateLock.notifyAll()
                    }
                }
            }

            override fun onStopped() {
                var lockToRelease: WifiManager.MulticastLock? = null
                synchronized(stateLock) {
                    if (startAttempt === attempt) attempt.stopped = true
                    if (reservation === attempt.reservation) {
                        stopped = true
                        lockToRelease = multicastLock
                        multicastLock = null
                    }
                    stateLock.notifyAll()
                }
                releaseMulticastLock(lockToRelease)
            }
        }

    private fun awaitStart(
        attempt: StartAttempt,
        deadlineNanos: Long,
        timeoutMillis: Long,
    ): WifiManager.LocalOnlyHotspotReservation = synchronized(stateLock) {
        while (true) {
            if (closed) throw IOException("LocalOnlyHotspot manager closed while starting")
            attempt.failure?.let { throw it }
            if (attempt.stopped) {
                throw IOException("LocalOnlyHotspot stopped before startup completed")
            }
            attempt.reservation?.let { return@synchronized it }

            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0) {
                throw IOException(
                    "Timed out after ${timeoutMillis}ms waiting for LocalOnlyHotspot",
                )
            }
            waitNanos(remainingNanos)
        }
        error("unreachable")
    }

    private fun acquireMulticastLock(attempt: StartAttempt): WifiManager.MulticastLock {
        ensureStartActive(attempt)
        val lock = wifiManager.createMulticastLock(MULTICAST_LOCK_TAG)
        lock.setReferenceCounted(false)
        try {
            lock.acquire()
            synchronized(stateLock) {
                ensureStartActiveLocked(attempt)
            }
            return lock
        } catch (failure: Exception) {
            releaseMulticastLock(lock)
            throw failure
        }
    }

    /**
     * 尝试用固定 SSID / 密码启动热点。
     *
     * 返回 false 表示"这条路径在本机不可用"，调用方应回落到系统随机凭据的原生行为。
     * 只有**配置本身不合法**（SSID 超 32 字节、加密热点密码长度不对）才抛异常 ——
     * 那种情况静默变随机会把用户搞懵，不如直接报出来。
     *
     * ## 为什么必须走反射
     *
     * `WifiManager.startLocalOnlyHotspot(SoftApConfiguration, Executor, Callback)` 与
     * `SoftApConfiguration.Builder` 在 Android 11~15 上是 **@SystemApi（即 @hide）**，
     * Android 16 才转为公开 API，Android 17 又改名成
     * `startLocalOnlyHotspotWithConfiguration`（`Builder.setSsid(String)` 同时换成
     * `setWifiSsid(WifiSsid)`）。
     *
     * 本工程 compileSdk = 37，而运行时要覆盖 Android 11 起的设备（真机 vivo Y78+ 是 Android 14）：
     * 两套名字各自只存在于一部分 SDK 里，编译期符号必然对不上其中一端，只能按可用性探测。
     *
     * 探测失败时**不报错**：拿不到固定 SSID 只是体验退化（iPhone 每次都要重学一张陌生网络），
     * 不该把整条无线 CarPlay 链路搞挂。
     */
    @SuppressLint("PrivateApi")
    private fun startWithFixedCredentials(
        executor: Executor,
        callback: WifiManager.LocalOnlyHotspotCallback,
    ): Boolean {
        val requestedSsid = ssid?.trim().orEmpty()
        if (requestedSsid.isEmpty()) return false
        // 802.11 的 SSID 上限是 32 字节。自己先拦一道，好给出比框架异常更好懂的提示；
        // UI 允许自由输入，中文名很容易在 UTF-8 下超限。
        require(requestedSsid.toByteArray(Charsets.UTF_8).size <= MAX_SSID_BYTES) {
            "Hotspot SSID must be at most $MAX_SSID_BYTES bytes in UTF-8, got '$requestedSsid'"
        }
        val securityType = security.toSoftApSecurityType()
        val requestedPassphrase = passphrase.orEmpty()
        if (securityType != SoftApConfiguration.SECURITY_TYPE_OPEN) {
            require(requestedPassphrase.length in 8..63) {
                "Hotspot passphrase must be 8..63 characters for a secured hotspot, " +
                    "got ${requestedPassphrase.length}"
            }
        }

        val builder = newSoftApConfigurationBuilder()
            ?: return useSystemGeneratedCredentials("SoftApConfiguration.Builder is unavailable")
        if (!invokeSetSsid(builder, requestedSsid)) {
            return useSystemGeneratedCredentials("no usable SSID setter")
        }
        if (!invokeSetPassphrase(builder, requestedPassphrase, securityType)) {
            return useSystemGeneratedCredentials("no usable passphrase setter")
        }
        val configuration = runCatching {
            builder.javaClass.getMethod("build").invoke(builder) as SoftApConfiguration
        }.getOrNull() ?: return useSystemGeneratedCredentials("SoftApConfiguration.Builder failed")

        val start = findStartMethod()
            ?: return useSystemGeneratedCredentials("no configured LocalOnlyHotspot starter")
        val invoked = runCatching { start.invoke(wifiManager, configuration, executor, callback) }
        if (invoked.isFailure) {
            Log.w(
                TAG,
                "fixed-credential LocalOnlyHotspot start failed",
                invoked.exceptionOrNull(),
            )
            return false
        }
        Log.i(
            TAG,
            "LocalOnlyHotspot starting with fixed credentials ssid='$requestedSsid' " +
                "security=$securityType via ${start.name}",
        )
        return true
    }

    private fun useSystemGeneratedCredentials(reason: String): Boolean {
        Log.w(
            TAG,
            "fixed hotspot credentials unavailable ($reason); " +
                "falling back to the system-generated SSID",
        )
        return false
    }

    @SuppressLint("PrivateApi")
    private fun newSoftApConfigurationBuilder(): Any? = runCatching {
        Class.forName("android.net.wifi.SoftApConfiguration\$Builder")
            .getConstructor()
            .newInstance()
    }.getOrNull()

    @SuppressLint("PrivateApi")
    private fun invokeSetSsid(builder: Any, ssid: String): Boolean {
        // Android 16.1 起叫 setWifiSsid(WifiSsid)，更早的版本是 setSsid(String)。
        runCatching { builder.javaClass.getMethod("setSsid", String::class.java) }
            .getOrNull()
            ?.let { return runCatching { it.invoke(builder, ssid) }.isSuccess }

        val wifiSsidClass = runCatching { Class.forName("android.net.wifi.WifiSsid") }
            .getOrNull() ?: return false
        val wifiSsid = runCatching {
            wifiSsidClass.getMethod("fromString", String::class.java).invoke(null, ssid)
        }.getOrNull() ?: runCatching {
            wifiSsidClass.getMethod("fromBytes", ByteArray::class.java)
                .invoke(null, ssid.toByteArray(Charsets.UTF_8))
        }.getOrNull() ?: return false
        val setter = runCatching {
            builder.javaClass.getMethod("setWifiSsid", wifiSsidClass)
        }.getOrNull() ?: return false
        return runCatching { setter.invoke(builder, wifiSsid) }.isSuccess
    }

    @SuppressLint("PrivateApi")
    private fun invokeSetPassphrase(
        builder: Any,
        passphrase: String,
        securityType: Int,
    ): Boolean {
        val intType = Int::class.javaPrimitiveType ?: return false
        val setter = runCatching {
            builder.javaClass.getMethod("setPassphrase", String::class.java, intType)
        }.getOrNull() ?: return false
        // 开放网络必须传 null：框架对 OPEN 带密码会直接抛 IllegalArgumentException。
        val argument = passphrase.takeIf { securityType != SoftApConfiguration.SECURITY_TYPE_OPEN }
        return runCatching { setter.invoke(builder, argument, securityType) }.isSuccess
    }

    @SuppressLint("PrivateApi")
    private fun findStartMethod(): Method? {
        // 按参数表精确匹配，避免撞上 (Callback, Handler) 那个两参重载。
        val parameters = arrayOf(
            SoftApConfiguration::class.java,
            Executor::class.java,
            WifiManager.LocalOnlyHotspotCallback::class.java,
        )
        return WifiManager::class.java.methods.firstOrNull { method ->
            (method.name == "startLocalOnlyHotspotWithConfiguration" ||
                method.name == "startLocalOnlyHotspot") &&
                method.parameterTypes.contentEquals(parameters)
        }
    }

    private fun ManualHotspotSecurity.toSoftApSecurityType(): Int = when (this) {
        ManualHotspotSecurity.OPEN -> SoftApConfiguration.SECURITY_TYPE_OPEN
        ManualHotspotSecurity.WPA2 -> SoftApConfiguration.SECURITY_TYPE_WPA2_PSK
        ManualHotspotSecurity.WPA3_TRANSITION ->
            SoftApConfiguration.SECURITY_TYPE_WPA3_SAE_TRANSITION
        ManualHotspotSecurity.WPA3 -> SoftApConfiguration.SECURITY_TYPE_WPA3_SAE
    }

    private fun readConfiguration(
        reservation: WifiManager.LocalOnlyHotspotReservation,
    ): HotspotConfiguration {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            readSoftApConfiguration(reservation.softApConfiguration)
        } else {
            @Suppress("DEPRECATION")
            val configuration = reservation.wifiConfiguration
                ?: throw IOException("LocalOnlyHotspot did not provide a Wi-Fi configuration")
            readWifiConfiguration(configuration)
        }
    }

    @Suppress("DEPRECATION")
    @RequiresApi(Build.VERSION_CODES.R)
    private fun readSoftApConfiguration(configuration: SoftApConfiguration): HotspotConfiguration {
        val ssid = validateSsid(configuration.ssid)
        val security = mapSoftApSecurity(configuration.securityType)
        val passphrase = validatePassphrase(security, configuration.passphrase)
        val channel = if (Build.VERSION.SDK_INT >= 36) {
            readConfiguredChannel(configuration)
        } else {
            readLegacySoftApChannel(configuration)
        }
        val bssid = configuration.bssid

        return HotspotConfiguration(
            ssid = ssid,
            passphrase = passphrase,
            security = security,
            channel = channel.first,
            bssid = bssid?.toString(),
            bssidBytes = bssid?.toByteArray(),
            bandLabel = channel.second,
        )
    }

    @Suppress("DEPRECATION")
    private fun readWifiConfiguration(configuration: WifiConfiguration): HotspotConfiguration {
        val ssid = validateSsid(configuration.SSID)
        val security = mapWifiConfigurationSecurity(configuration)
        val passphrase = validatePassphrase(security, unquote(configuration.preSharedKey))
        val bssid = configuration.BSSID?.let {
            try {
                MacAddress.fromString(it)
            } catch (failure: IllegalArgumentException) {
                throw IOException("LocalOnlyHotspot reported an invalid BSSID: $it", failure)
            }
        }
        val channel = readWifiConfigurationChannel(configuration)

        return HotspotConfiguration(
            ssid = ssid,
            passphrase = passphrase,
            security = security,
            channel = channel,
            bssid = bssid?.toString(),
            bssidBytes = bssid?.toByteArray(),
            bandLabel = readWifiConfigurationBandLabel(configuration, channel),
        )
    }

    @RequiresApi(36)
    private fun readConfiguredChannel(configuration: SoftApConfiguration): Pair<Int, String> {
        val channels = configuration.channels
        if (channels.size() != 1) {
            throw IOException("Expected one LocalOnlyHotspot channel, got ${channels.size()}")
        }
        val band = channels.keyAt(0)
        val channel = requireChannel(
            channels.valueAt(0),
            "SoftApConfiguration.channels",
            allowAuto = true,
        )
        return channel to softApBandLabel(band)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun readLegacySoftApChannel(configuration: SoftApConfiguration): Pair<Int, String> {
        val channel = try {
            val getter = SoftApConfiguration::class.java.getMethod("getChannel")
            val value = getter.invoke(configuration) as? Number
                ?: throw IOException("SoftApConfiguration.getChannel returned no channel")
            requireChannel(
                value.toInt(),
                "SoftApConfiguration.getChannel",
                allowAuto = true,
            )
        } catch (failure: ReflectiveOperationException) {
            throw IOException(
                "Android ${Build.VERSION.RELEASE} does not expose SoftApConfiguration.getChannel",
                failure,
            )
        }
        return channel to readLegacySoftApBandLabel(configuration, channel)
    }

    private fun readWifiConfigurationChannel(configuration: WifiConfiguration): Int {
        val channel = try {
            WifiConfiguration::class.java.getField("apChannel").getInt(configuration)
        } catch (failure: ReflectiveOperationException) {
            throw IOException(
                "Android ${Build.VERSION.RELEASE} does not expose WifiConfiguration.apChannel",
                failure,
            )
        }
        return requireChannel(channel, "WifiConfiguration.apChannel", allowAuto = true)
    }

    private fun readWifiConfigurationBandLabel(
        configuration: WifiConfiguration,
        channel: Int,
    ): String {
        val band = try {
            (WifiConfiguration::class.java.getField("apBand").get(configuration) as? Number)
                ?.toInt()
        } catch (_: ReflectiveOperationException) {
            null
        }
        return band?.let(::softApBandLabel) ?: legacyBandLabel(channel)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun readLegacySoftApBandLabel(
        configuration: SoftApConfiguration,
        channel: Int,
    ): String {
        val band = try {
            (SoftApConfiguration::class.java.getMethod("getBand").invoke(configuration) as? Number)
                ?.toInt()
        } catch (_: ReflectiveOperationException) {
            null
        }
        return band?.let(::softApBandLabel) ?: legacyBandLabel(channel)
    }

    private fun awaitApInterface(
        bssid: ByteArray?,
        preStartInterfaces: Set<String>,
        attempt: StartAttempt,
        deadlineNanos: Long,
    ): ApInterface? {
        var matchedInterfaceName: String? = null
        while (true) {
            ensureStartActive(attempt)
            val networkInterface = findInterface(bssid, preStartInterfaces)
            if (networkInterface != null) {
                matchedInterfaceName = networkInterface.name
                networkInterface.hotspotAddress()?.let { hostAddress ->
                    val interfaceBssid = networkInterface.hardwareAddress?.toMacAddressString()
                        ?: (hostAddress as? Inet6Address)?.toEui64MacAddress()
                    // 只要「有身份」或「有 192.168 私网地址」就采用这张网卡。
                    // 不再要求 bssid 必须非空：真机 vivo Y78+ 的 wlan2 读不到硬件地址，
                    // 而 IPv4 主机地址又无法像 link-local IPv6 那样 EUI-64 反推，
                    // 于是被旧守卫一直丢弃、轮询到超时；deviceIdentifier 交给
                    // CarPlayController 回退到配置里的稳定值。
                    if (interfaceBssid == null && !networkInterface.hasPrivate192Address()) {
                        return@let
                    }
                    return ApInterface(
                        name = networkInterface.name,
                        hostAddress = hostAddress,
                        bssid = interfaceBssid,
                    )
                }
            }

            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0) {
                return matchedInterfaceName?.let {
                    ApInterface(it, null, null)
                }
            }
            try {
                TimeUnit.NANOSECONDS.sleep(minOf(remainingNanos, INTERFACE_POLL_NANOS))
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Interrupted while waiting for the LocalOnlyHotspot interface", interrupted)
            }
        }
    }

    private fun findInterface(
        bssid: ByteArray?,
        preStartInterfaces: Set<String>,
    ): NetworkInterface? {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        val enumerated = Collections.list(interfaces)
        if (!interfaceProbeLogged) {
            interfaceProbeLogged = true
            Log.i(
                TAG,
                "hotspot interface probe preStart=${preStartInterfaces.joinToString(",")} all=" +
                    enumerated.joinToString(",") { networkInterface ->
                        val up = try {
                            networkInterface.isUp.toString()
                        } catch (error: SocketException) {
                            "ERR(${error.message})"
                        }
                        networkInterface.name + "(up=$up," +
                            Collections.list(networkInterface.inetAddresses)
                                .joinToString("|") { it.hostAddress ?: "?" } + ")"
                    },
            )
        }
        // 刻意不用 isUp() 过滤：真机 vivo Y78+ 上热点网卡 wlan2 的
        // /sys/class/net/wlan2/flags 会被 SELinux 拒绝读取，Java 侧 isUp() 抛
        // SocketException，于是热点网卡整张被排除，旧逻辑只能退而选中 rmnet_data0。
        // 这里改用「地址特征」筛：热点接口必然持有 192.168.x 私网 IPv4。
        val candidates = enumerated.filter { networkInterface ->
            try {
                !networkInterface.isLoopback &&
                    EXCLUDED_INTERFACE_PREFIXES.none { networkInterface.name.startsWith(it) }
            } catch (_: SocketException) {
                false
            }
        }
        if (bssid != null) {
            return candidates.firstOrNull {
                try {
                    it.hardwareAddress?.contentEquals(bssid) == true
                } catch (_: SocketException) {
                    false
                }
            }
        }
        // 热点接口由系统在启动后新建，且必然持有 192.168.x 私网 IPv4。
        // 先按「192.168 私网地址」这个硬特征定位，不依赖 isUp() 与枚举顺序：
        // 真机 vivo Y78+ 上热点网卡 wlan2 的 isUp() 返回 false、硬件地址也读不到，
        // 旧逻辑因此把整张网卡排除在候选之外，只能退而选中 rmnet_data0，
        // 把 iPhone 根本不可达的 link-local IPv6 通告了出去
        //（实测 iPhone 能连热点、能拿 DHCP，却始终不连 AirPlay 7000）。
        val appeared = candidates.filter { it.name !in preStartInterfaces }
        val primaryInterface = connectivityManager?.activeNetwork
            ?.let { connectivityManager.getLinkProperties(it)?.interfaceName }
        val nonPrimary = candidates.filter { it.name != primaryInterface }
        val chosen = appeared.firstOrNull { it.hasPrivate192Address() }
            ?: candidates.firstOrNull { it.hasPrivate192Address() }
            ?: appeared.firstOrNull { it.hotspotAddress() != null }
            ?: nonPrimary.firstOrNull { it.hasSiteLocalAddress() }
            ?: nonPrimary.firstOrNull { it.hasLinkLocalAddress() }
            ?: nonPrimary.firstOrNull { it.hotspotAddress() != null }
        if (!interfaceChoiceLogged) {
            interfaceChoiceLogged = true
            Log.i(
                TAG,
                "hotspot interface choice primary=${primaryInterface ?: "none"} " +
                    "candidates=${candidates.joinToString(",") { it.name }} " +
                    "appeared=${appeared.joinToString(",") { it.name }} " +
                    "chosen=${chosen?.name ?: "none"} " +
                    "address=${chosen?.hotspotAddress()?.hostAddress ?: "none"}",
            )
        }
        return chosen
    }

    private fun networkInterfaceNames(): Set<String> =
        NetworkInterface.getNetworkInterfaces()
            ?.let {
                Collections.list(it)
                    .filter { networkInterface ->
                        try {
                            networkInterface.isUp
                        } catch (_: SocketException) {
                            false
                        }
                    }
                    .mapTo(linkedSetOf()) { n -> n.name }
            }
            .orEmpty()

    private fun NetworkInterface.hasPrivate192Address(): Boolean =
        Collections.list(inetAddresses).any {
            it is Inet4Address && it.address.size == 4 &&
                (it.address[0].toInt() and 0xff) == 192 &&
                (it.address[1].toInt() and 0xff) == 168
        }

    private fun NetworkInterface.hasSiteLocalAddress(): Boolean =
        Collections.list(inetAddresses).any { it is Inet4Address && it.isSiteLocalAddress }

    private fun NetworkInterface.hasLinkLocalAddress(): Boolean =
        Collections.list(inetAddresses).any { it is Inet6Address && it.isLinkLocalAddress }

    private fun NetworkInterface.hotspotAddress(): InetAddress? {
        var ipv4: InetAddress? = null
        var ipv6: InetAddress? = null
        for (address in Collections.list(inetAddresses)) {
            if (address is Inet6Address && address.isLinkLocalAddress && ipv6 == null) {
                ipv6 = if (address.scopeId == index) {
                    address
                } else {
                    try {
                        Inet6Address.getByAddress(null, address.address, this)
                    } catch (_: UnknownHostException) {
                        address
                    }
                }
            }
            if (address is Inet4Address && !address.isLoopbackAddress && ipv4 == null) {
                ipv4 = address
            }
        }
        // 优先 IPv4：IPv6 link-local 依赖 scope id，跨协议栈（iOS）容易连不上。
        // 真机实测：通告 link-local 时 iPhone 已连上热点并拿到 DHCP，
        // 却始终不建立 AirPlay 连接；热点网卡本身有 192.168.x 私网地址，直接用它。
        return ipv4 ?: ipv6
    }

    private fun ensureStartActive(attempt: StartAttempt) {
        synchronized(stateLock) {
            ensureStartActiveLocked(attempt)
        }
    }

    private fun ensureStartActiveLocked(attempt: StartAttempt) {
        if (closed) throw IOException("LocalOnlyHotspot manager closed while starting")
        if (startAttempt !== attempt) throw IOException("LocalOnlyHotspot startup was cancelled")
        if (attempt.stopped) throw IOException("LocalOnlyHotspot stopped while starting")
    }

    private fun cleanupFailedStart(
        attempt: StartAttempt,
        multicastLock: WifiManager.MulticastLock?,
    ) {
        val failedReservation: WifiManager.LocalOnlyHotspotReservation?
        val failedThread: HandlerThread?
        synchronized(stateLock) {
            if (startAttempt === attempt) startAttempt = null
            attempt.stopped = true
            stateLock.notifyAll()
            failedReservation = attempt.reservation
            failedThread = attempt.thread
        }
        releaseMulticastLock(multicastLock)
        failedReservation?.close()
        failedThread?.quitSafely()
    }

    private fun waitNanos(nanos: Long) {
        val millis = nanos / NANOS_PER_MILLISECOND
        val remainder = (nanos % NANOS_PER_MILLISECOND).toInt()
        try {
            stateLock.wait(millis, remainder)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while waiting for LocalOnlyHotspot", interrupted)
        }
    }

    private fun deadlineAfter(timeoutMillis: Long): Long {
        val now = System.nanoTime()
        val delta = timeoutMillis * NANOS_PER_MILLISECOND
        return if (Long.MAX_VALUE - now < delta) Long.MAX_VALUE else now + delta
    }

    private fun validateSsid(value: String?): String {
        val ssid = unquote(value)
        if (ssid.isNullOrEmpty() || ssid == WifiManager.UNKNOWN_SSID) {
            throw IOException("LocalOnlyHotspot did not report a usable SSID")
        }
        if ('\u0000' in ssid) throw IOException("LocalOnlyHotspot SSID contains U+0000")
        return ssid
    }

    private fun validatePassphrase(
        security: Iap2WirelessSecurity,
        value: String?,
    ): String {
        val passphrase = value.orEmpty()
        if (security != Iap2WirelessSecurity.NONE && passphrase.isEmpty()) {
            throw IOException("LocalOnlyHotspot did not report a passphrase for secured Wi-Fi")
        }
        if ('\u0000' in passphrase) {
            throw IOException("LocalOnlyHotspot passphrase contains U+0000")
        }
        return passphrase
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun mapSoftApSecurity(securityType: Int): Iap2WirelessSecurity = when (securityType) {
        SoftApConfiguration.SECURITY_TYPE_OPEN -> Iap2WirelessSecurity.NONE
        SoftApConfiguration.SECURITY_TYPE_WPA2_PSK -> Iap2WirelessSecurity.WPA_WPA2
        SoftApConfiguration.SECURITY_TYPE_WPA3_SAE_TRANSITION ->
            Iap2WirelessSecurity.WPA3_TRANSITION
        SoftApConfiguration.SECURITY_TYPE_WPA3_SAE -> Iap2WirelessSecurity.WPA3_ONLY
        SoftApConfiguration.SECURITY_TYPE_WPA3_OWE,
        SoftApConfiguration.SECURITY_TYPE_WPA3_OWE_TRANSITION,
        -> throw IOException("Unsupported LocalOnlyHotspot security type: OWE")
        else -> throw IOException(
            "Unsupported LocalOnlyHotspot security type: $securityType",
        )
    }

    private fun mapWifiConfigurationSecurity(
        configuration: WifiConfiguration,
    ): Iap2WirelessSecurity {
        val keyManagement = configuration.allowedKeyManagement
            ?: throw IOException("LocalOnlyHotspot did not report its key management")
        val open = keyManagement.get(WifiConfiguration.KeyMgmt.NONE)
        val wpa2 = keyManagement.get(WifiConfiguration.KeyMgmt.WPA2_PSK)
        val sae = keyManagement.get(WifiConfiguration.KeyMgmt.SAE)
        val owe = keyManagement.get(WifiConfiguration.KeyMgmt.OWE)

        return when {
            owe -> throw IOException("Unsupported LocalOnlyHotspot security type: OWE")
            open && !wpa2 && !sae -> Iap2WirelessSecurity.NONE
            wpa2 && sae -> Iap2WirelessSecurity.WPA3_TRANSITION
            wpa2 -> Iap2WirelessSecurity.WPA_WPA2
            sae -> Iap2WirelessSecurity.WPA3_ONLY
            else -> throw IOException(
                "Unsupported LocalOnlyHotspot key management: $keyManagement",
            )
        }
    }

    private fun requireChannel(channel: Int, source: String): Int {
        return requireChannel(channel, source, allowAuto = false)
    }

    private fun requireChannel(channel: Int, source: String, allowAuto: Boolean): Int {
        if (channel !in 0..0xff || (!allowAuto && channel == 0)) {
            throw IOException("$source returned invalid channel: $channel")
        }
        return channel
    }

    private fun ByteArray.toMacAddressString(): String =
        joinToString(":") { "%02x".format(it.toInt() and 0xff) }

    private fun Inet6Address.toEui64MacAddress(): String? {
        val bytes = address
        if (!isLinkLocalAddress || bytes.size != 16 || bytes[11] != 0xff.toByte() ||
            bytes[12] != 0xfe.toByte()
        ) {
            return null
        }
        return byteArrayOf(
            (bytes[8].toInt() xor 0x02).toByte(),
            bytes[9],
            bytes[10],
            bytes[13],
            bytes[14],
            bytes[15],
        ).toMacAddressString()
    }

    private fun softApBandLabel(band: Int): String = when (band) {
        SoftApConfiguration.BAND_2GHZ -> "2.4 GHz"
        SoftApConfiguration.BAND_5GHZ -> "5 GHz"
        SoftApConfiguration.BAND_6GHZ -> "6 GHz"
        SoftApConfiguration.BAND_60GHZ -> "60 GHz"
        else -> "Unknown band ($band)"
    }

    private fun legacyBandLabel(channel: Int): String = when (channel) {
        in 1..14 -> "2.4 GHz"
        in 32..177 -> "5 GHz"
        else -> "Unknown band"
    }

    private fun unquote(value: String?): String? {
        if (value == null) return null
        return if (value.length >= 2 && value.first() == '"' && value.last() == '"') {
            value.substring(1, value.length - 1)
        } else {
            value
        }
    }

    private fun failureReason(reason: Int): String = when (reason) {
        WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL -> "no channel available"
        WifiManager.LocalOnlyHotspotCallback.ERROR_GENERIC -> "generic error"
        WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE -> "incompatible Wi-Fi mode"
        WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED -> "tethering disallowed"
        else -> "reason $reason"
    }

    private fun releaseMulticastLock(lock: WifiManager.MulticastLock?) {
        if (lock == null) return
        try {
            if (lock.isHeld) lock.release()
        } catch (_: RuntimeException) {
            // close() is best-effort; the hotspot reservation remains the authoritative owner.
        }
    }

    private class StartAttempt {
        var thread: HandlerThread? = null
        var reservation: WifiManager.LocalOnlyHotspotReservation? = null
        var failure: IOException? = null
        var stopped = false
    }

    private class HotspotConfiguration(
        val ssid: String,
        val passphrase: String,
        val security: Iap2WirelessSecurity,
        val channel: Int,
        val bssid: String?,
        val bssidBytes: ByteArray?,
        val bandLabel: String,
    )

    private class ApInterface(
        val name: String,
        val hostAddress: InetAddress?,
        val bssid: String?,
    )

    private companion object {
        const val TAG = "xcertplay-usb"
        const val MULTICAST_LOCK_TAG = "xcertplay-local-only-hotspot-mdns"
        const val NANOS_PER_MILLISECOND = 1_000_000L

        /** 802.11 SSID 的字节上限。 */
        const val MAX_SSID_BYTES = 32
        val INTERFACE_POLL_NANOS: Long = TimeUnit.MILLISECONDS.toNanos(100)

        /**
         * 与 [ManualHotspotManager] 保持一致：这些网卡不可能承载热点。
         * 少了这份排除表时，真机 vivo Y78+ 上 `rmnet_data0` 会先于 `wlan2` 被枚举到，
         * 而它恰好带 link-local IPv6，于是被误判成热点接口。
         */
        val EXCLUDED_INTERFACE_PREFIXES = listOf(
            "lo",
            "dummy",
            "rmnet",
            "r_rmnet",
            "tun",
            "ppp",
            "sit",
            "ip6",
            "bond",
        )
    }
}
