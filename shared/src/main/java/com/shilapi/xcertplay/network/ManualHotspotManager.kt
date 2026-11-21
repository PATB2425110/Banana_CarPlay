package com.shilapi.xcertplay.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Looper
import android.util.Log
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.net.UnknownHostException
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * Attaches to a hotspot that is already running on this device.
 *
 * The hotspot remains owned by the system. This manager only locates its interface and reads the
 * channel/security data that the public Android APIs expose. Some vendors hide the current SoftAP
 * configuration, in which case the caller-supplied credentials remain authoritative and the iAP2
 * channel is reported as zero ("auto").
 */
internal data class ManualHotspotInterfaceCandidate(
    val name: String,
    val isUp: Boolean,
    val isLoopback: Boolean,
    val ipv4Addresses: List<String>,
    val hardwareAddress: String?,
)

/** Pure selector for a caller-verified interface/address pair. No scoring or fallback is allowed. */
internal object ManualHotspotInterfaceSelection {
    fun selectStrict(
        candidates: List<ManualHotspotInterfaceCandidate>,
        interfaceName: String,
        hostAddress: String,
    ): ManualHotspotInterfaceCandidate? = candidates.singleOrNull { candidate ->
        candidate.name == interfaceName && candidate.isUp && !candidate.isLoopback &&
            candidate.ipv4Addresses.any { it == hostAddress }
    }
}

class ManualHotspotManager(
    context: Context,
    ssid: String,
    passphrase: String,
    band: ManualHotspotBand,
    channel: Int,
    security: ManualHotspotSecurity,
    /**
     * **PATCH-0087/0099**：MANUAL 会话通告给 iPhone 的本机 IPv4 endpoint。
     *
     * ## 为什么要有这个参数（车主口径 2026-10-11）
     *
     * *"复用车机热点是要用户自己手动设置，而不是自动识别"*。
     *
     * 不给它时（`null`）本类行为与改动前**完全一致**：枚举网卡、按名字
     * （`ap*` / `wlan*` / `p2p*`）与"是不是 `192.168.x`"打分挑一个。
     * 而车机上这条自动判据会认错 —— `wlan0` 上可能同时挂着**车机热点的地址**
     * 与**本 App 自建热点的残留地址**（2026-10-11 的两份日志分别把残留通告给了
     * iPhone、把残留写进了诊断包）。
     *
     * 给了它就以它为准：**按地址找网卡**，不再打分。
     *
     * ⚠️ 找不着时**回落**到打分（而不是抛异常）：这条参数是二开层从"用户填了什么"
     * 推断出来的，判死一次连接的代价远大于回落（二开层在连接前已经核过一次，
     * 并给出中文的可执行文案）。
     */
    hostAddressOverride: String? = null,
    /** Exact interface paired with a caller-verified local address; mismatch fails closed. */
    hostInterfaceOverride: String? = null,
    /**
     * **本次要用的这张 AP 是我们自己起的**（PATCH-0116，2026-10-13）。
     *
     * ## 为什么不能用"名字一致"推断（现场日志实证）
     *
     * 本类过去对系统 `SoftApConfiguration` 的处置是：
     * `readApConfiguration()?.takeIf { it.ssid == expectedSsid }` ⇒ 名字一致就当成
     * **真·复用**，顺手用它的信道/频段/加密去校验我们这张网。
     *
     * 这个推断在**自建**路径上必碎：设置页里 SSID 是**复用与自建共用**的一个字段。
     * 用户先用「Lynk」复用车机热点，再切回「自建热点」时名字还是「Lynk」，于是
     * *我们自己的* AP 与 *车机那份同名配置* 撞上 ⇒ 校验生效 ⇒ 必抛
     * （日志 9 `1791897995606`：`Manual hotspot band 2.4 GHz does not match configured band 5 GHz`，
     * 21:24:22/26/30 各一次；同一份日志里换个名字立刻成功）。
     *
     * ## 语义
     *
     * - `false`（默认）= 复用车机那张已在跑的 AP：系统配置**就是**它，
     *   保留原有的信道/频段/加密一致性校验（这条能力仍然有意义）；
     * - `true` = 自建（ADB hostapd，配置由我们自己写、我们自己起）：
     *   系统那份描述的是**另一张** AP（可能同名、可能根本没开、可能是残留），
     *   ⇒ **不读、也不校验**，连信道/频段也不从它那里借。
     *
     * 与 [hostAddressOverride] 同一条纪律：这类"这次是哪种模式"的事实
     * **由调用方声明**，不在本层靠名字猜。
     */
    selfHostedAccessPoint: Boolean = false,
) : WirelessHotspotManager {
    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(ConnectivityManager::class.java)
    private val wifiManager = appContext.getSystemService(WifiManager::class.java)
        ?: throw IllegalStateException("WifiManager is unavailable")
    private val expectedSsid = ssid
    private val passphrase = passphrase
    private val expectedBand = band
    private val expectedChannel = channel
    private val expectedSecurity = security.toIap2Security()
    /** 见构造参数 KDoc：`true` = 这张 AP 是我们自己起的（自建），系统配置不参与。 */
    private val selfHostedAccessPoint = selfHostedAccessPoint
    private val hostAddressOverride = hostAddressOverride?.trim()?.takeIf { it.isNotEmpty() }
    private val hostInterfaceOverride = hostInterfaceOverride?.trim()?.takeIf { it.isNotEmpty() }

    @Volatile
    private var closed = false

    init {
        require(expectedSsid.isNotBlank()) { "ssid must not be blank" }
        require('\u0000' !in expectedSsid) { "ssid must not contain U+0000" }
        require('\u0000' !in passphrase) { "passphrase must not contain U+0000" }
        require(passphrase.isEmpty() || passphrase.length in 8..63) {
            "passphrase must be empty or between 8 and 63 characters"
        }
        require(channel in 0..196) { "channel must be 0 or in 1..196" }
        require(expectedSecurity == Iap2WirelessSecurity.NONE || passphrase.isNotEmpty()) {
            "passphrase is required for secured manual hotspots"
        }
        require(expectedSecurity != Iap2WirelessSecurity.NONE || passphrase.isEmpty()) {
            "passphrase must be empty for open manual hotspots"
        }
        require(hostInterfaceOverride == null || hostAddressOverride != null) {
            "hostInterfaceOverride requires hostAddressOverride"
        }
        require(hostInterfaceOverride == null || hostInterfaceOverride.matches(Regex("[A-Za-z0-9_.-]+"))) {
            "hostInterfaceOverride must be a plain interface name"
        }
    }

    override fun start(timeoutMillis: Long): WirelessHotspotInfo {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "ManualHotspotManager.start must not run on the main thread"
        }
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }

        // ------------------------------------------------------------------
        // KX11/CS11 原厂网关 fallback（移植自 DiPlay v0.2.16，
        // 依据 docs/KX11_ROUTED_HOTSPOT.md 第 2、5、6 条）。
        //
        // 只在“调用方没有显式指定地址”且“不是自建热点”时才探测：
        //   * TCAM 复用会在 ensureTcamHotspotReady 里回填 hostAddressOverride；
        //   * MANUAL 复用用户手填地址时同样有 override；
        //   * ADB_HOSTAPD 自建走 selfHostedAccessPoint=true。
        // 这三条既有路径**一个字节都不变**。其它车型/系统在
        // EcarxClientRoute.isEcarxPlatform 处直接返回 null，零影响。
        //
        // 为什么必须在读 Android SoftApConfiguration **之前**：
        // 原厂热点不由标准 Android SoftAP 提供，`getWifiApState()` 会假关闭，
        // 系统里那份配置也可能根本描述的是另一张 AP。DiPlay 的判据是
        // “Android AP=false 不拒绝该网关”，因此这里先按“当前客户端路由 +
        // eth* + 198.18/15”判定；命中就完全跳过 Android AP 配置校验。
        // ------------------------------------------------------------------
        resolveEcarxFactoryHotspot(timeoutMillis)?.let { selection ->
            return factoryHotspotInfo(selection)
        }

        val deadlineNanos = deadlineAfter(timeoutMillis)
        // ⚠️ 2026-10-03 实车（CS11, QUALCOMM, Android 9）修正：
        //
        // 这一段原先是"先读系统 SoftApConfiguration，再拿它的 SSID 跟我们要用的
        // SSID 比对，不一样就抛异常"。设计意图是**复用**车机自带热点：复用要求
        // 网上那张 AP 的名字就是我们打算告诉 iPhone 的名字，否则 iPhone 连不上。
        //
        // 但这个前提在本机不成立，现场日志（设备 d5ddb4312da1，0.8.1，17:26）：
        //
        //   hotspot plan: 自建热点「DBPlay」：wlan0 / 192.168.175.0/24 / 信道 1
        //   adb hotspot step 15 OK  信道 1：启动 hostapd（/vendor/bin/hw/hostapd）
        //   adb hotspot step 18 OK  信道 1：校验实际广播的 SSID（应为 DBPlay）｜实际广播「DBPlay」
        //   adb hotspot ready iface=wlan0 host=192.168.175.1 ...
        //   wireless bring-up failed
        //   java.io.IOException: Could not establish MANUAL hotspot:
        //     Manual hotspot SSID does not match the active local AP configuration: 'Lynk'
        //       at ManualHotspotManager.start(ManualHotspotManager.kt:80)
        //
        // 即：**ADB 通道已经把自建热点真正起起来了**（hostapd 在跑、`wlan0` 拿到
        // `192.168.175.1`、实际广播名就是 `DBPlay`，18/29 两步都 OK），可紧接着
        // `start()` 在这里读到车机系统里**残留的那份老配置 `Lynk`**
        // （`/data/softap.conf` 一路留下的，读者是别人的 hostapd），
        // `Lynk != DBPlay` ⇒ 抛异常 ⇒ 刚起好的热点被就地拆掉。
        // 表现就是"每 40 秒起来一次、3 秒内死掉"，`RunningWireless` 永远不成立。
        //
        // ⇒ 自建路径不读、也不该校验系统 SoftApConfiguration：
        // 我们用的是自己写、自己起的 hostapd 配置（step 8 / 19 写的
        // `/data/user/0/<pkg>/files/softap.conf` → `/data/local/tmp/dbplay-softap.conf`），
        // 与系统那份没有任何关系。**系统里存的是什么名字，都不该拦住自建。**
        //
        // 保留下来的只有一个仍然有意义的能力：如果系统配置**恰好**和我们要用的一致
        // （真·复用场景），那就顺带用它的信道/频段信息；不一致时**不报错**，按自己的配置起。
        //
        // ⚠️ 2026-10-13（PATCH-0116）：上面那句"名字一致 ⇒ 真·复用"的前提**同样会碎**，
        // 而且碎法比上一次更隐蔽 —— 上一次是名字对不上被拦，这一次是名字**恰好撞上**。
        //
        // 现场日志（日志 9，`1791897995606`，21:24:22 / 26 / 30 各一次）：
        //
        //   hotspot plan: 自建热点「Lynk」：wlan0 / 192.168.176.0/24 / 信道 36
        //   wireless bring-up failed
        //   java.io.IOException: Could not establish MANUAL hotspot:
        //     Manual hotspot band 2.4 GHz does not match configured band 5 GHz
        //
        // 自建跑在信道 36（5 GHz），而车机系统里那份同名配置是 2.4 GHz 的 ⇒
        // 名字撞上 ⇒ 被当成"真·复用" ⇒ 拿**别人的**配置校验**我们自己的** AP ⇒ 必抛，
        // 刚起好的 hostapd 被就地拆掉。同一份日志里把名字换回 `DBPlay`，名字对不上、
        // 校验被跳过，同一条链立刻成功（21:25:28，`iface=wlan0 host=192.168.175.1 band=5 GHz`）。
        //
        // ⇒ 能不能用系统那份配置，取决于本类这次是"接管别人的 AP"还是"给我们自己的 AP
        // 做就绪判定"，与名字是否相同无关。后者由调用方用 [selfHostedAccessPoint] 声明。
        val apConfiguration = if (selfHostedAccessPoint) {
            null
        } else {
            readApConfiguration()?.takeIf { it.ssid == expectedSsid }
        }
        validateApConfiguration(apConfiguration)

        var lastReason = "local hotspot interface was not found"
        while (true) {
            check(!closed) { "ManualHotspotManager is closed" }
            val localInterface = resolveLocalHotspotInterface()
            if (localInterface != null) {
                val connectionFrequency = frequencyFromConnectionInfo()
                val scanFrequency = frequencyFromScanResult(localInterface)
                val channel = observedManualHotspotChannel(
                    apChannel = apConfiguration?.channel ?: 0,
                    connectionFrequencyMHz = connectionFrequency,
                    scanFrequencyMHz = scanFrequency,
                    apFrequencyMHz = apConfiguration?.frequencyMHz,
                )
                val frequencyMHz = when {
                    apConfiguration?.frequencyMHz != null -> apConfiguration.frequencyMHz
                    connectionFrequency != null -> connectionFrequency
                    scanFrequency != null -> scanFrequency
                    else -> null
                }
                val security = apConfiguration?.security ?: expectedSecurity
                if (security != Iap2WirelessSecurity.NONE && passphrase.isEmpty()) {
                    throw IOException("Manual hotspot is secured but no passphrase was provided")
                }

                if (channel == 0) {
                    Log.w(
                        TAG,
                        "Could not read the active hotspot channel from Android public APIs; " +
                            "reporting iAP2 channel 0 (auto) instead of configured channel " +
                            "$expectedChannel",
                    )
                }
                val observedBandLabel = wifiBandLabel(apConfiguration?.band)
                return WirelessHotspotInfo(
                    ssid = expectedSsid,
                    passphrase = passphrase,
                    security = security,
                    channel = channel,
                    frequencyMHz = frequencyMHz,
                    bssid = localInterface.hardwareAddress,
                    interfaceName = localInterface.name,
                    hostAddress = localInterface.hostAddress,
                    bandLabel = when (expectedBand) {
                        ManualHotspotBand.GHZ_2_4 -> "2.4 GHz"
                        ManualHotspotBand.GHZ_5 -> "5 GHz"
                        ManualHotspotBand.AUTO ->
                            frequencyMHz?.let(::bandLabel) ?: observedBandLabel ?: "Auto"
                    },
                    backend = WirelessHotspotBackend.MANUAL_HOTSPOT,
                )
            }

            val remainingNanos = remainingNanos(deadlineNanos)
            if (remainingNanos <= 0) {
                throw IOException(
                    "Timed out after ${timeoutMillis}ms waiting for the manual hotspot: " +
                        lastReason,
                )
            }
            sleep(minOf(remainingNanos, INTERFACE_POLL_NANOS))
        }
    }

    override fun close() {
        closed = true
    }

    private fun validateApConfiguration(configuration: ManualApConfiguration?) {
        configuration ?: return
        if (expectedChannel > 0 && configuration.channel > 0 &&
            configuration.channel != expectedChannel
        ) {
            throw IOException(
                "Manual hotspot channel ${configuration.channel} does not match configured " +
                "channel $expectedChannel",
            )
        }
        val actualBand = when (configuration.band) {
            1 -> ManualHotspotBand.GHZ_2_4
            2 -> ManualHotspotBand.GHZ_5
            else -> null
        }
        if (actualBand != null && expectedBand != ManualHotspotBand.AUTO &&
            actualBand != expectedBand
        ) {
            throw IOException(
                "Manual hotspot band ${wifiBandLabel(configuration.band)} does not match " +
                    "configured band ${wifiBandLabel(if (expectedBand == ManualHotspotBand.GHZ_2_4) 1 else 2)}",
            )
        }
        if (configuration.security != expectedSecurity) {
            throw IOException(
                "Manual hotspot security ${configuration.security} does not match configured " +
                    "security $expectedSecurity",
            )
        }
        val frequency = configuration.frequencyMHz ?: return
        when (expectedBand) {
            ManualHotspotBand.GHZ_2_4 -> if (frequency !in 2_400..2_500) {
                throw IOException("Manual hotspot is not running on 2.4 GHz")
            }
            ManualHotspotBand.GHZ_5 -> if (frequency !in 5_150..5_895) {
                throw IOException("Manual hotspot is not running on 5 GHz")
            }
            ManualHotspotBand.AUTO -> Unit
        }
    }

    private fun findLocalHotspotInterface(): LocalHotspotInterface? {
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()
        } catch (_: SocketException) {
            null
        } ?: return null
        val primaryInterface = connectivityManager?.activeNetwork
            ?.let { connectivityManager.getLinkProperties(it)?.interfaceName }
        return Collections.list(interfaces)
            .asSequence()
            .filter { isUsableInterface(it, primaryInterface) }
            .mapNotNull { networkInterface ->
                networkInterface.hotspotAddress()?.let { address ->
                    LocalHotspotInterface(
                        name = networkInterface.name,
                        hostAddress = address,
                        hardwareAddress = networkInterface.hardwareAddress?.toMacAddressString(),
                        score = interfaceScore(networkInterface.name, address),
                    )
                }
            }
            .maxByOrNull(LocalHotspotInterface::score)
    }

    /**
     * **PATCH-0087**：通告地址从哪来 —— 用户填了就用它，没填才按老判据挑。
     *
     * `hostAddressOverride` 是二开层从设置页透传下来的"车机热点地址"。车主口径：
     * *"复用车机热点是要用户自己手动设置，而不是自动识别"*。有它时**按地址找网卡**，
     * 完全绕开 [interfaceScore]（那条打分在 `wlan0` 上分不清"车机热点"与"自建残留"）。
     *
     * 找不到时**回落**到 [findLocalHotspotInterface]：二开层在连接前已经核过一遍并
     * 给出中文文案，这里的回落是第二道保险（比如网卡在这一瞬刚好重配过），
     * 绝不因此判死一次连接。
     */
    private fun resolveLocalHotspotInterface(): LocalHotspotInterface? {
        val override = hostAddressOverride ?: return findLocalHotspotInterface()
        // 字面量在本地解析，不会走 DNS；仍包一层是因为 `getByName` 声明了受检异常。
        val target = try {
            InetAddress.getByName(override)
        } catch (_: Exception) {
            null
        }
        if (target !is Inet4Address) {
            if (hostInterfaceOverride != null) {
                Log.e(TAG, "verified hotspot endpoint $override is not IPv4; refusing interface fallback")
                return null
            }
            Log.w(TAG, "manual hotspot host address $override is not IPv4; using interface scoring")
            return findLocalHotspotInterface()
        }
        val strictInterface = hostInterfaceOverride
        if (strictInterface != null) {
            val matched = findInterfaceCarryingVerifiedAddress(target, strictInterface)
            if (matched == null) {
                Log.e(
                    TAG,
                    "verified hotspot endpoint $override disappeared from interface $strictInterface; " +
                        "refusing interface scoring fallback",
                )
            }
            return matched
        }
        val matched = findInterfaceCarryingAddress(target)
        if (matched == null) {
            Log.w(
                TAG,
                "manual hotspot host address $override was not found on any interface; " +
                    "falling back to interface scoring",
            )
            return findLocalHotspotInterface()
        }
        Log.i(TAG, "manual hotspot host address $override matched interface ${matched.name}")
        return matched
    }

    /**
     * 按**用户填的地址**找网卡（PATCH-0087 的 `hostAddressOverride` 路径）。
     *
     * ⚠️ 2026-10-12（日志 4 排查）：本方法只排 [EXCLUDED_INTERFACE_PREFIXES]，
     * **刻意不排有线口（`eth`/`usb`）** —— 那份排除只属于"按名字猜 AP"的
     * [isUsableInterface]。主语不同：这里是"用户告诉我要用这个地址"，
     * 且二开层刚在 UP 的网卡上核过它；猜那条路才是"我们自己猜"。
     * 详见 [GUESS_EXCLUDED_INTERFACE_PREFIXES] 的 KDoc。
     */
    private fun findInterfaceCarryingAddress(target: Inet4Address): LocalHotspotInterface? {
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()
        } catch (_: SocketException) {
            null
        } ?: return null
        val primaryInterface = connectivityManager?.activeNetwork
            ?.let { connectivityManager.getLinkProperties(it)?.interfaceName }
        return Collections.list(interfaces)
            .asSequence()
            .filter { networkInterface ->
                try {
                    networkInterface.name != primaryInterface &&
                        !networkInterface.isLoopback &&
                        networkInterface.isUp &&
                        EXCLUDED_INTERFACE_PREFIXES.none {
                            networkInterface.name.startsWith(it)
                        } &&
                        Collections.list(networkInterface.inetAddresses).any { address ->
                            address is Inet4Address && !address.isLoopbackAddress &&
                                address.hostAddress == target.hostAddress
                        }
                } catch (_: SocketException) {
                    false
                }
            }
            .map { networkInterface ->
                LocalHotspotInterface(
                    name = networkInterface.name,
                    hostAddress = target,
                    hardwareAddress = networkInterface.hardwareAddress?.toMacAddressString(),
                    score = interfaceScore(networkInterface.name, target),
                )
            }
            .firstOrNull()
    }

    /** Verified OEM route lookup: exact iface+IP, UP, and no primary-interface exclusion/fallback. */
    private fun findInterfaceCarryingVerifiedAddress(
        target: Inet4Address,
        interfaceName: String,
    ): LocalHotspotInterface? {
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()
        } catch (_: SocketException) {
            null
        } ?: return null
        val candidates = Collections.list(interfaces).mapNotNull { networkInterface ->
            runCatching {
                ManualHotspotInterfaceCandidate(
                    name = networkInterface.name,
                    isUp = networkInterface.isUp,
                    isLoopback = networkInterface.isLoopback,
                    ipv4Addresses = Collections.list(networkInterface.inetAddresses)
                        .filterIsInstance<Inet4Address>()
                        .filterNot { it.isLoopbackAddress }
                        .map { it.hostAddress.orEmpty() },
                    hardwareAddress = networkInterface.hardwareAddress?.toMacAddressString(),
                )
            }.getOrNull()
        }
        val selected = ManualHotspotInterfaceSelection.selectStrict(
            candidates,
            interfaceName,
            target.hostAddress.orEmpty(),
        ) ?: return null
        Log.i(TAG, "verified hotspot route matched interface ${selected.name}")
        return LocalHotspotInterface(
            name = selected.name,
            hostAddress = target,
            hardwareAddress = selected.hardwareAddress,
            score = interfaceScore(selected.name, target),
        )
    }

    private fun isUsableInterface(
        networkInterface: NetworkInterface,
        primaryInterface: String?,
    ): Boolean = try {
        networkInterface.name != primaryInterface &&
            !networkInterface.isLoopback &&
            networkInterface.isUp &&
            // 「按名字猜哪张是 AP」这条路必须排掉有线口 —— 见
            // [GUESS_EXCLUDED_INTERFACE_PREFIXES] 的 KDoc（2026-10-03 `5f1c43e`）。
            GUESS_EXCLUDED_INTERFACE_PREFIXES.none { networkInterface.name.startsWith(it) } &&
            // ⚠️ 2026-10-03 实车（CS11）补：**名字不像 AP 的网卡一律不选**。
            //
            // 原实现只按 `maxByOrNull(score)` 挑，而 `interfaceScore` 对任何
            // 非 ap/p2p/wlan 的网卡返回 0 —— 也就是"一个都不像 AP"时它照样
            // 从一堆候选里挑出分数最高的那个（全是 0 分）。
            // 现场就是靠这条选中了 `eth0.3`：`iface=eth0.3 host=198.18.34.15`。
            //
            // 语义上：本方法要找的是"iPhone 要关联的那张 AP 的网卡"。
            // 没有一张网卡名叫 ap*/wlan*/p2p*/softap* ⇒ **本机没有可复用的热点**，
            // 应该返回 null 让 `CarHotspotResolver` 走自建，而不是硬凑一个有线口上去。
            looksLikeApInterface(networkInterface.name)
    } catch (_: SocketException) {
        false
    }

    /**
     * 网卡名是否**像一张 AP**（`ap*` / `wlan*` / `p2p*` / 含 `softap`）。
     *
     * 与 [interfaceScore] 的判据保持一致——那里给这些前缀打正分，其余打 0 分；
     * 这里把"0 分即不选"钉死，避免 0 分候选被 `maxByOrNull` 误选。
     */
    private fun looksLikeApInterface(name: String): Boolean =
        name.startsWith("ap") ||
            name.startsWith("wlan") ||
            name.startsWith("p2p") ||
            name.contains("softap", ignoreCase = true)

    /**
     * KX11/CS11 原厂网关 fallback 选择器（移植自 DiPlay `ManualHotspotReadiness` /
     * `selectHotspotInterface`，见 `docs/KX11_ROUTED_HOTSPOT.md`）。
     *
     * ## 判据（严格，缺一不可）
     *
     * 1. `EcarxClientRoute.isEcarxPlatform`：SDK 28 + MODEL KX11/CS11 + DEVICE 前缀匹配；
     * 2. `EcarxHotspotReader` 报告的**当前客户端路由**命中某张 `eth*` 网卡，
     *    且该网卡上真实挂着 `198.18/15` 的源地址（`kx11Source` 再核一遍）；
     * 3. 采样稳定（`ManualHotspotReadiness` 的 3 次稳定判定），避免瞬时路由。
     *
     * 只看 `factoryRoute == true` 的选择；普通 AP / 无线 STA / 平台 SoftAP 候选一律丢弃，
     * 交给下面既有的 `resolveLocalHotspotInterface()` 逻辑，绝不改变它们的优先级。
     *
     * 读不到（无客户端 / SDK 不可用 / 路由歧义 / 超时）⇒ 返回 null，走原逻辑。
     * “读不到 ≠ 否”，不据此判死任何一次连接。
     */
    private fun resolveEcarxFactoryHotspot(timeoutMillis: Long): HotspotSelection? {
        if (!Kx11FactoryRoutePort.ENABLED) return null
        if (selfHostedAccessPoint || hostAddressOverride != null) return null
        if (!EcarxClientRoute.isEcarxPlatform(Build.VERSION.SDK_INT, Build.MODEL, Build.DEVICE)) {
            return null
        }
        val interfaces = runCatching {
            ManualHotspotInterfaces(appContext) { message ->
                Log.i(TAG, "ecarx factory $message")
            }
        }.getOrNull() ?: return null
        return try {
            ManualHotspotReadiness(
                sample = { interfaces.sample() },
                cancelled = { closed },
                pause = { millis -> sleep(TimeUnit.MILLISECONDS.toNanos(millis)) },
                log = { message -> Log.i(TAG, "ecarx factory $message") },
            ).await(minOf(FACTORY_ROUTE_PROBE_MILLIS, timeoutMillis))
                .takeIf { selection ->
                    selection.factoryRoute && EcarxClientRoute.kx11Source(
                        Build.VERSION.SDK_INT,
                        Build.MODEL,
                        Build.DEVICE,
                        selection.name,
                        selection.address,
                    )
                }
        } catch (_: WirelessStartupException) {
            // 没有稳定的原厂客户端路由：按原逻辑继续，不影响其它热点形态。
            Log.i(TAG, "ecarx factory route: no verified current-client route; using generic selection")
            null
        } catch (_: IOException) {
            Log.i(TAG, "ecarx factory route: probe interrupted/unavailable; using generic selection")
            null
        } finally {
            runCatching { interfaces.close() }
        }
    }

    /**
     * 原厂网关命中后的 [WirelessHotspotInfo]。
     *
     * 与 DiPlay `docs/KX11_ROUTED_HOTSPOT.md` 第 5、6 条一致：
     * - 用**已保存的** SSID/security/password；
     * - channel 0（不声明未知信道）、无 Wi-Fi BSSID；
     * - 只通告**一个**主地址（AirPlay / mDNS / 0x5703 / 0x4301 下游统一使用它）。
     */
    private fun factoryHotspotInfo(selection: HotspotSelection): WirelessHotspotInfo {
        Log.i(
            TAG,
            "KX11 原厂网关：iface=${selection.name} host=${selection.address.hostAddress} " +
                "（DiPlay docs/KX11_ROUTED_HOTSPOT.md；复用已保存 SSID/密码，channel=0，无 BSSID，" +
                "跳过 Android SoftApConfiguration 校验）",
        )
        return WirelessHotspotInfo(
            ssid = expectedSsid,
            passphrase = passphrase,
            security = expectedSecurity,
            channel = 0,
            frequencyMHz = null,
            bssid = null,
            interfaceName = selection.name,
            hostAddress = selection.address,
            bandLabel = when (expectedBand) {
                ManualHotspotBand.GHZ_2_4 -> "2.4 GHz"
                ManualHotspotBand.GHZ_5 -> "5 GHz"
                ManualHotspotBand.AUTO -> "Auto"
            },
            backend = WirelessHotspotBackend.MANUAL_HOTSPOT,
        )
    }

    private fun interfaceScore(name: String, address: InetAddress): Int {
        var score = when {
            name.startsWith("ap") || name.contains("softap", ignoreCase = true) -> 100
            name.startsWith("p2p") -> 80
            name.startsWith("wlan") -> 70
            else -> 0
        }
        if (address is Inet4Address) {
            val bytes = address.address
            when {
                bytes[0] == 192.toByte() && bytes[1] == 168.toByte() -> score += 30
                address.isSiteLocalAddress -> score += 20
            }
        }
        if (address is Inet6Address && address.isLinkLocalAddress) score += 15
        return score
    }

    private fun NetworkInterface.hotspotAddress(): InetAddress? {
        var ipv6: Inet6Address? = null
        for (address in Collections.list(inetAddresses)) {
            if (address is Inet4Address && !address.isLoopbackAddress &&
                !address.isLinkLocalAddress
            ) {
                return address
            }
            if (address is Inet6Address && address.isLinkLocalAddress) {
                if (ipv6 == null) {
                    ipv6 = if (address.scopeId == index) {
                        address
                    } else {
                        try {
                            Inet6Address.getByAddress(null, address.address, this)
                        } catch (_: UnknownHostException) {
                            null
                        }
                    }
                }
            }
        }
        return ipv6
    }

    private fun frequencyFromConnectionInfo(): Int? {
        val connectionInfo = try {
            wifiManager.connectionInfo
        } catch (_: SecurityException) {
            null
        } ?: return null
        if (unquote(connectionInfo.ssid) != expectedSsid) return null
        return connectionInfo.frequency.takeIf { it > 0 }
    }

    private fun frequencyFromScanResult(localInterface: LocalHotspotInterface): Int? {
        val localBssid = localInterface.hardwareAddress ?: return null
        val scanResults = try {
            wifiManager.scanResults
        } catch (_: SecurityException) {
            return null
        }
        return scanResults.firstOrNull { result ->
            result.SSID == expectedSsid &&
                result.BSSID.equals(localBssid, ignoreCase = true) &&
                result.frequency > 0
        }?.frequency
    }

    @SuppressLint("PrivateApi")
    private fun readApConfiguration(): ManualApConfiguration? =
        readSoftApConfiguration() ?: readLegacyApConfiguration()

    @SuppressLint("PrivateApi")
    private fun readSoftApConfiguration(): ManualApConfiguration? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            val method = WifiManager::class.java.getMethod("getSoftApConfiguration")
            val configuration = method.invoke(wifiManager) as? SoftApConfiguration
                ?: return null
            val ssid = configuration.ssid ?: return null
            val bandAndChannel = when {
                Build.VERSION.SDK_INT >= 36 -> {
                    val channels = configuration.channels
                    if (channels.size() == 0) null else channels.keyAt(0) to channels.valueAt(0)
                }
                else -> {
                    val band = (
                        SoftApConfiguration::class.java
                            .getMethod("getBand")
                            .invoke(configuration) as? Number
                        )?.toInt()
                    val channel = SoftApConfiguration::class.java
                        .getMethod("getChannel")
                        .invoke(configuration) as? Number
                    if (band == null || channel == null) null else band to channel.toInt()
                }
            }
            val band = bandAndChannel?.first
            val channel = bandAndChannel?.second ?: 0
            ManualApConfiguration(
                ssid = ssid,
                band = band,
                channel = channel,
                frequencyMHz = wifiChannelToFrequencyMhz(channel, band),
                security = mapSoftApSecurity(configuration.securityType),
            )
        } catch (_: Throwable) {
            null
        }
    }

    @SuppressLint("PrivateApi")
    private fun readLegacyApConfiguration(): ManualApConfiguration? {
        return try {
            val method = WifiManager::class.java.getMethod("getWifiApConfiguration")
            val configuration = method.invoke(wifiManager) as? WifiConfiguration
                ?: return null
            val ssid = unquote(configuration.SSID) ?: return null
            val channel = try {
                WifiConfiguration::class.java.getField("apChannel").getInt(configuration)
            } catch (_: ReflectiveOperationException) {
                0
            }
            val band = try {
                WifiConfiguration::class.java.getField("apBand").getInt(configuration)
            } catch (_: ReflectiveOperationException) {
                null
            }
            ManualApConfiguration(
                ssid = ssid,
                band = band,
                channel = channel,
                frequencyMHz = wifiChannelToFrequencyMhz(channel, band),
                security = mapWifiConfigurationSecurity(configuration),
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun mapSoftApSecurity(securityType: Int): Iap2WirelessSecurity = when (securityType) {
        SoftApConfiguration.SECURITY_TYPE_OPEN -> Iap2WirelessSecurity.NONE
        SoftApConfiguration.SECURITY_TYPE_WPA2_PSK -> Iap2WirelessSecurity.WPA_WPA2
        SoftApConfiguration.SECURITY_TYPE_WPA3_SAE_TRANSITION ->
            Iap2WirelessSecurity.WPA3_TRANSITION
        SoftApConfiguration.SECURITY_TYPE_WPA3_SAE -> Iap2WirelessSecurity.WPA3_ONLY
        else -> Iap2WirelessSecurity.WPA_WPA2
    }

    private fun mapWifiConfigurationSecurity(
        configuration: WifiConfiguration,
    ): Iap2WirelessSecurity {
        val keyManagement = configuration.allowedKeyManagement ?: return Iap2WirelessSecurity.NONE
        val open = keyManagement.get(WifiConfiguration.KeyMgmt.NONE)
        val wpa2 = keyManagement.get(WifiConfiguration.KeyMgmt.WPA2_PSK)
        val sae = keyManagement.get(WifiConfiguration.KeyMgmt.SAE)
        return when {
            open && !wpa2 && !sae -> Iap2WirelessSecurity.NONE
            wpa2 && sae -> Iap2WirelessSecurity.WPA3_TRANSITION
            wpa2 -> Iap2WirelessSecurity.WPA_WPA2
            sae -> Iap2WirelessSecurity.WPA3_ONLY
            else -> Iap2WirelessSecurity.WPA_WPA2
        }
    }

    private fun bandLabel(frequencyMHz: Int): String = when (frequencyMHz) {
        in 2400..2500 -> "2.4 GHz"
        in 5150..5895 -> "5 GHz"
        in 5925..7125 -> "6 GHz"
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

    private fun ByteArray.toMacAddressString(): String =
        joinToString(":") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun sleep(nanos: Long) {
        try {
            TimeUnit.NANOSECONDS.sleep(nanos)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while waiting for the manual hotspot", interrupted)
        }
    }

    private fun deadlineAfter(timeoutMillis: Long): Long {
        val now = System.nanoTime()
        val delta = timeoutMillis * NANOS_PER_MILLISECOND
        return if (Long.MAX_VALUE - now < delta) Long.MAX_VALUE else now + delta
    }

    private fun remainingNanos(deadlineNanos: Long): Long =
        (deadlineNanos - System.nanoTime()).coerceAtLeast(0L)

    private class ManualApConfiguration(
        val ssid: String,
        val band: Int?,
        val channel: Int,
        val frequencyMHz: Int?,
        val security: Iap2WirelessSecurity,
    )

    private class LocalHotspotInterface(
        val name: String,
        val hostAddress: InetAddress,
        val hardwareAddress: String?,
        val score: Int,
    )

    private companion object {
        const val TAG = "xcertplay-usb"
        const val NANOS_PER_MILLISECOND = 1_000_000L
        val INTERFACE_POLL_NANOS: Long = TimeUnit.MILLISECONDS.toNanos(250)

        /**
         * 原厂网关 fallback 的最长探测时间。
         *
         * 3 次稳定采样（`WirelessStartupPolicy.STABLE_SAMPLES` = 3，间隔 250ms）
         * 约 500ms 就能给出结论；留 1.5s 只覆盖第一次 SDK/反射读数的慢路径。
         * 超时即回落到既有逻辑，**不会**把原本能连的会话拖死。
         */
        const val FACTORY_ROUTE_PROBE_MILLIS = 1_500L

        /**
         * **任何路径下**都不可能是 iPhone 要关联的那张网卡的名字前缀。
         *
         * 它们与下面那份 [GUESS_EXCLUDED_INTERFACE_PREFIXES] 的分界是**语义**，不是
         * "哪个更可疑"：本表里的名字在任何场景下都不承载用户面流量给手机
         * （回环、隧道、 bonded、内核内的 sit），所以无论"用户在选"还是"我们在猜"，
         * 都该排掉。
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

        /**
         * 🔴 **只给"按名字猜 AP 网卡"用的**排除前缀（2026-10-12 从
         * [EXCLUDED_INTERFACE_PREFIXES] 拆出来）。
         *
         * ## 为什么非拆不可
         *
         * 日志 4（2026-10-12，CS11）实测：用户在设置里**填好了**车机热点地址
         * `198.18.34.15`（复用模式），日志里 `manual hotspot precheck: state=ON`
         * 也通过了，但上游这条"按地址找网卡"的路每次都返回 null：
         *
         * ```text
         * manual hotspot host address 198.18.34.15 was not found on any interface; falling back to interface scoring
         * java.io.IOException: Timed out after 60000ms waiting for the manual hotspot: local hotspot interface was not found
         * ```
         *
         * 因为本机承载该地址的网卡**全是** `eth*`（`eth0` / `eth0.2~eth0.6` / `eth0.11`），
         * 而"eth"当时被放在 [EXCLUDED_INTERFACE_PREFIXES] 里，**两条路一起被挡**。
         *
         * "eth" 是 2026-10-03（提交 `5f1c43e`，修「无线通道就绪中」卡死）加进来的，
         * 针对的是**另一个问题**：没有任何 AP 在跑、地址全在有线口上时，
         * [findLocalHotspotInterface] 按名字打分居然挑出 `eth0.3` 通告给 iPhone。
         * **那次要修的是"我们猜哪张是 AP"这条路**，却不该把"用户显式指定了地址"
         * 这条路一起挡掉 —— 两者的主语不同：
         *
         * - 猜（[isUsableInterface] / [findLocalHotspotInterface]）⇒ 主语是**我们**。
         *   有线口当然不是 iPhone 要关联的 AP，猜它必然是错的，必须排。
         * - 找（[findInterfaceCarryingAddress]）⇒ 主语是**用户**。
         *   地址是用户在设置里填的，且二开层刚刚在**某张 UP 的网卡**上核过它。
         *   再用"有线口不是 AP"去否掉它，等于把用户的有效配置判死。
         *
         * ⚠️ 拆开后 [findInterfaceCarryingAddress] 只保留 [EXCLUDED_INTERFACE_PREFIXES]。
         * 日后若有人想"统一"这两份表，先读上面这段 —— 合回去就是日志 4 那个 60 秒死等。
         */
        val GUESS_EXCLUDED_INTERFACE_PREFIXES = listOf(
            // ⚠️ 实车（CS11）现场日志（设备 d5ddb4312da1，16:12）：
            //   wlan0 = type managed（**没有 AP 在跑**）、无地址；
            //   网卡地址全是 eth0 / eth0.2~eth0.11，地址 198.18.34.15。
            // 因为这份排除表漏了 eth，候选集里只剩 eth*，它们 score 都是 0，
            // `maxByOrNull` 照样挑出一个（挑中 eth0.3），于是：
            //   wireless hotspot backend=Manual hotspot iface=eth0.3 host=198.18.34.15
            // ⇒ 把**有线口**当成 Wi-Fi 热点网卡，再把 198.18.34.15 通告给 iPhone
            //   ⇒ 走 Wi-Fi 的 iPhone 永远到不了 ⇒ 卡在「无线通道就绪」。
            //
            // 有线口（eth/usb）从语义上就不可能是 iPhone 要关联的那张 AP ——
            // **但仅在"我们自己在猜"时成立**，见上面那段说明。
            "eth",
            "usb",
        )
    }
}

private fun ManualHotspotSecurity.toIap2Security(): Iap2WirelessSecurity = when (this) {
    ManualHotspotSecurity.OPEN -> Iap2WirelessSecurity.NONE
    ManualHotspotSecurity.WPA2 -> Iap2WirelessSecurity.WPA_WPA2
    ManualHotspotSecurity.WPA3_TRANSITION -> Iap2WirelessSecurity.WPA3_TRANSITION
    ManualHotspotSecurity.WPA3 -> Iap2WirelessSecurity.WPA3_ONLY
}
