package com.dbtool.carplay.bridge.hotspot

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Looper
import com.dbtool.carplay.api.hotspot.CarTcamHotspot
import com.dbtool.carplay.api.hotspot.CarTcamHotspotInfo
import com.dbtool.carplay.api.hotspot.TcamAttachmentState
import com.shilapi.xcertplay.network.Kx11FactoryRoutePort
import java.io.Closeable
import java.io.IOException
import java.net.Inet4Address
import java.util.concurrent.atomic.AtomicReference

/**
 * 车机 TCAM 热点复用（`backend = TCAM_HOTSPOT`）—— DiPlay `TcamHotspotManager.*` 的原样移植。
 *
 * ## 语义（与 DiPlay 一致）
 *
 * - **复用而非自建**：绝不主动开热点。是否拦由 [TcamHotspotGate] 判定：厂商 SDK
 *   明确说关（state 10/11/14 且无客户端）才抛 `IOException`；`isWifiApEnabled`
 *   在 TCAM 平台上会假关闭，不再单独作为拦截依据；
 * - **自动识别接入卡与地址**：`getAllNetworks()` → `hasTransport(TRANSPORT_ETHERNET)` →
 *   接口名 == [CarTcamHotspot.TCAM_ETH_IFACE] → `linkAddresses` 过滤 IPv4 后**恰好一个**
 *   才算候选，候选总数 == 1 才接受；
 * - **等待与超时**：`Thread.sleep(200)` 轮询，超时抛
 *   `IOException("TCAM Ethernet interface eth0.11 is unavailable")`；
 * - **线程纪律**：入口必须在非主线程，主线程调用抛 `IllegalStateException`；
 * - **状态机与自愈**：`registerNetworkCallback`，`onLost` / `onLinkPropertiesChanged` →
 *   `invalidated` → 回调 [onNetworkChanged] 触发无线会话重启；
 * - **配置读取（反射）**：`getWifiApConfiguration()` 读 SSID / preSharedKey /
 *   allowedKeyManagement / apChannel / apBand，读不到回落到保存值。
 *
 * ## 进程级网络绑定（2026-10-17，方案 A · 斌哥定案）
 *
 * 附着成功后**把本进程显式绑到选中的那条 `Network`**（`ConnectivityManager
 * .bindProcessToNetwork`，API 23+），[close] 里解绑。
 *
 * 为什么：这条链以前只"读"网卡、不绑任何东西，而**回包**走哪张路由表由 socket 的
 * fwmark 决定 —— 只有"进程默认网络恰好是 eth0.11"时才正确。默认网络一漂（蜂窝抢到
 * 默认 / 另一路 Ethernet 上来），回包就从错口出去 ⇒ 手机连不上 7000
 * （现象：约 40 秒超时、复用档偶发用不了）。显式绑定把这个"偶然"钉成"必然"。
 * 完整核实链见 `docs/DBPlay-复用档回包路径核实与改动方案-20261017.md`。
 *
 * 三条纪律：
 * - 绑定**只影响本进程自己的路由选择**，不改热点、不改网卡配置
 *   （TCAM 档"复用而不自建、只读不改网卡"这条语义没有变）；
 * - 绑定失败**只记一条诊断、不抛异常**（回包路径没钉住是风险，不是本次会话的致命错误）；
 * - 绑定/解绑都留在本类内，与 NetworkCallback 的注册/注销同一处收尾；调用方只读
 *   [attachedNetwork]，**不要**再自己调一次 `bindProcessToNetwork`（那会出现两个
 *   "谁负责解绑"的源头）。
 *
 * ## 与 DiPlay 的差别（如实写）
 *
 * - DiPlay 的 `startTethering` 反射（`CarHotspotTethering_enable.java`）**不移植**——
 *   本仓库判据明确禁止照搬开热点，TCAM 只在原厂热点已开时复用；
 * - 结果类型是 DBPlay 自己的 [CarTcamHotspotInfo]，而非上游 `WirelessHotspotInfo`：
 *   本仓库把 TCAM 接进现有「复用」会话路径（`manualHostAddress` / `manualHostInterfaceName`），
 *   不另建一套并行会话栈。
 *
 * @param savedSsid / savedPassphrase 本机已保存值（设置页 `manualHotspotSsid` /
 *        `manualHotspotPassphrase`）。反射读不到时的回落源。
 * @param onDiagnostic 诊断串回调（只写日志，不写密码）。
 * @param onNetworkChanged 链路失效（onLost / 地址变化）时触发无线会话重启的回调。
 */
class TcamHotspotManager(
    context: Context,
    private val savedSsid: String?,
    private val savedPassphrase: String?,
    private val onDiagnostic: (String) -> Unit,
    private val onNetworkChanged: () -> Unit,
) : Closeable {

    private val appContext = context.applicationContext

    private val connectivity: ConnectivityManager =
        appContext.getSystemService(ConnectivityManager::class.java)
            ?: throw IOException("ConnectivityManager is unavailable")

    private val wifi: WifiManager =
        appContext.getSystemService(WifiManager::class.java)
            ?: throw IOException("WifiManager is unavailable")

    /** 附着状态机；`selectedNetwork` 是 Android 类型，单独由 manager 持有。 */
    private val state = AtomicReference(TcamAttachmentState())

    @Volatile
    private var selectedNetwork: Network? = null

    /**
     * 本进程**是否已被我们绑到** [selectedNetwork] 上（`bindProcessToNetwork` 的返回值）。
     *
     * 只由 [start]（worker 线程）置真、[close] 置假；`@Volatile` 只为让跨线程可见性
     * 不依赖任何人的记忆（[close] 也可能从别的线程进来，见会话桥的 `close()`）。
     *
     * 为什么用独立标志而不是"看 [selectedNetwork] 是不是非空"：绑定**可能失败**
     * （返回 false，或厂商 ROM 上抛异常）。失败时我们根本没绑过，
     * [close] 里就不该去动系统默认网络 —— 那是别人的东西。
     */
    @Volatile
    private var processBound = false

    /**
     * 本次附着选中的那条 `Network`（`start()` 成功前恒为 null）。
     *
     * **只读视图**：绑定与解绑都由本类自己负责（[start] 成功后 bind、[close] 里 unbind）。
     * 调用方拿它只做诊断 / 自检（例如核对"钉住的是哪条网络"），
     * **不要**再拿它调一次 `ConnectivityManager.bindProcessToNetwork` —— 那会造出
     * 第二个"谁负责解绑"的源头。
     */
    val attachedNetwork: Network? get() = selectedNetwork

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) {
            if (network == selectedNetwork) {
                invalidate("Ethernet network lost")
            }
        }

        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
            if (network != selectedNetwork || sameLink(properties)) return
            invalidate("Ethernet address changed")
        }
    }

    /**
     * 等待并附着到车机 TCAM 热点。必须在**非主线程**调用。
     *
     * @throws IllegalStateException 主线程调用
     * @throws IllegalArgumentException timeoutMillis <= 0
     * @throws IOException 热点未开 / 名字不一致 / 链路在启动期变化 / 超时 / 已被取消
     */
    fun start(timeoutMillis: Long): CarTcamHotspotInfo {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw IllegalStateException("TcamHotspotManager.start must not run on the main thread")
        }
        if (timeoutMillis <= 0) {
            throw IllegalArgumentException("Failed requirement.")
        }
        // 原厂热点未开 → 报错，**绝不自己开**。
        // 2026-10-19：TCAM 平台下 `isWifiApEnabled()` 会假关闭（现场热点实际开着却报 off），
        // 拦截改由厂商 SDK 快照 + 客户端数决定；SDK 读不到时降级放行，交给后续链路超时。
        val vendor = EcarxWifiApReader.read(appContext)
        val legacy = readApEnabled()
        TcamHotspotGate.blockReason(vendor, legacy)?.let { throw IOException(it) }
        onDiagnostic(
            "热点状态：vendor=${vendor.state}/${vendor.enabled} " +
                "客户端=${vendor.clients.size} legacy=$legacy",
        )

        val apConfiguration = readApConfiguration()
        val liveSsid = normalizedSsid(apConfiguration)
        if (liveSsid != null && !savedSsid.isNullOrBlank() && liveSsid != savedSsid) {
            throw IOException("Saved hotspot name differs from the active TCAM hotspot")
        }
        val confirmedSsid = liveSsid
            ?: savedSsid?.takeIf { it.isNotBlank() }
            ?: throw IOException("TCAM hotspot name is not readable; save its name in DBPlay")

        val livePassphrase = normalizedPassphrase(apConfiguration?.preSharedKey)
        val passphrase = livePassphrase ?: savedPassphrase.orEmpty()

        val openKeyManagement = apConfiguration?.allowedKeyManagement?.get(0) == true
        if (!openKeyManagement && (passphrase.length < 8 || passphrase.length >= 64)) {
            throw IOException("TCAM hotspot password is not readable; save it in DBPlay")
        }
        val security = CarTcamHotspot.security(openKeyManagement, passphrase)

        val channel = readApChannel(apConfiguration)
        val apBand = apConfiguration?.let { readIntField(it, "apBand") }
        val frequencyMHz = CarTcamHotspot.wifiChannelToFrequencyMhz(
            channel,
            CarTcamHotspot.legacyHotspotBandToSoftApBand(apBand),
        )

        val deadlineNanos = System.nanoTime() + timeoutMillis * 1_000_000L
        while (!state.get().closed) {
            val candidate = findCandidate()
            if (candidate != null) {
                val (network, address) = candidate
                selectedNetwork = network
                state.getAndUpdate { it.select(address, confirmedSsid) }
                // ⚠️ 不要用 clearCapabilities()：它是 API 30（Android 11）才有的方法，本机
                // minSdk=28 的车机（Android 9 / SDK 28）上直调必 NoSuchMethodError。
                // 新建的 NetworkRequest.Builder 本来就没有任何 capability，无需清理。
                // （2026-10-16 assembleRelease 的 lintVitalRelease 实拦于此）
                connectivity.registerNetworkCallback(
                    NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                        .build(),
                    callback,
                )
                state.getAndUpdate { it.registerCallback() }

                // DiPlay 的启动期复查：注册回调后链路变了 / 被失效，一律算失败。
                val properties = connectivity.getLinkProperties(network)
                if (properties == null || !sameLink(properties) || state.get().invalidated) {
                    throw IOException("TCAM Ethernet link changed during startup")
                }

                // 链路复查无误 ⇒ 把**本进程**显式绑到这条 Network 上（2026-10-17，方案 A）。
                //
                // ⚠️ 位置很关键：必须在 `CarPlayController.start()` 之前 —— 会话桥是在
                // `buildSession()` 之后才调它的，而 AirPlay 那条 7000 监听 socket 正是那时
                // 才建出来（`CarPlayVpnService.startAirPlayServer`，:169；`server.bind(...)` :174，
                // shared/src/main/java/com/shilapi/xcertplay/network/CarPlayVpnService.kt）。
                // socket 的 fwmark 在**创建时**取进程默认网络，而入包连接还会继承**监听
                // socket** 的 mark ⇒ 绑晚了等于没绑。
                //
                // ⚠️ 2026-10-21（移植 DiPlay v0.2.16 / docs/KX11_ROUTED_HOTSPOT.md 第 6 条）：
                // **KX11/CS11 严格匹配的原厂网关不走这条绑定。** DiPlay 在同一代 Android 9
                // KX11 上的实测方案是：原厂热点是 T-Box 侧“路由式热点”，本机只是 eth* 上的
                // 一个 198.18/15 源地址；对它调用 Android `bindProcessToNetwork`/`bindSocket`
                // 会把本进程钉在一条并非手机入网侧的 Network 上，导致 iPhone → 198.18.34.15:7000
                // 的 SYN 到不了监听 socket（现场：40 秒零 `connection accepted`）。
                // DiPlay 对这条分支只做**显式 socket 源地址绑定**，不绑网络。
                //
                // 作用域**只有** `EcarxClientRoute.kx11Source(...)` 为真的这一支
                // （SDK28 + MODEL KX11/CS11 + DEVICE 前缀 + eth* + 198.18/15）；
                // 其它任何候选（地址不匹配/其它车型）仍然照旧 bindProcess，行为不变。
                val factoryGatewayBypass = Kx11FactoryRoutePort.ENABLED && shouldBypassProcessBind(
                    Build.VERSION.SDK_INT,
                    Build.MODEL,
                    Build.DEVICE,
                    CarTcamHotspot.TCAM_ETH_IFACE,
                    address,
                )
                val bound = if (factoryGatewayBypass) {
                    onDiagnostic(
                        "KX11 原厂网关：绕过网络绑定（DiPlay docs/KX11_ROUTED_HOTSPOT.md）" +
                            " iface=${CarTcamHotspot.TCAM_ETH_IFACE} host=${address.hostAddress}",
                    )
                    false
                } else {
                    bindProcess(network)
                }
                onDiagnostic(
                    "TCAM hotspot attached iface=${CarTcamHotspot.TCAM_ETH_IFACE} " +
                        "host=${address.hostAddress} configReadable=${apConfiguration != null} " +
                        "passwordReadable=${livePassphrase != null} channel=$channel " +
                        "security=$security processBind=$bound",
                )
                return CarTcamHotspotInfo(
                    ssid = confirmedSsid,
                    passphrase = if (openKeyManagement) "" else passphrase,
                    security = security,
                    channel = channel,
                    frequencyMHz = frequencyMHz,
                    interfaceName = CarTcamHotspot.TCAM_ETH_IFACE,
                    hostAddress = address,
                    bandLabel = CarTcamHotspot.bandLabel(apBand),
                )
            }

            if (System.nanoTime() >= deadlineNanos) {
                throw IOException(
                    "TCAM Ethernet interface ${CarTcamHotspot.TCAM_ETH_IFACE} is unavailable",
                )
            }
            Thread.sleep(CarTcamHotspot.POLL_INTERVAL_MILLIS)
        }
        throw IOException("TCAM hotspot attachment cancelled")
    }

    /**
     * DiPlay `validateReady`：发布前复查链路 / 地址 / 热点开关 / 热点名仍一致。
     * 本仓库把 TCAM 接进上游 `ManualHotspotManager` 的「复用」路径，
     * 该路径有自己的就绪判定，因此本方法仅作为语义对齐保留、由调用方按需使用。
     */
    fun validateReady() {
        val network = selectedNetwork ?: throw IOException("TCAM Ethernet link is not selected")
        val properties = connectivity.getLinkProperties(network)
        if (state.get().closed || state.get().invalidated || properties == null ||
            !sameLink(properties) || readApEnabled() == false
        ) {
            throw IOException("TCAM hotspot or Ethernet link changed before publication")
        }
        val currentSsid = normalizedSsid(readApConfiguration())
        if (currentSsid != null && currentSsid != state.get().confirmedSsid) {
            throw IOException("TCAM hotspot name changed before publication")
        }
    }

    /** DiPlay `onCarPlayConfirmed`：确认回调，DiPlay 实现为 no-op。 */
    fun onCarPlayConfirmed() = Unit

    /** DiPlay `connectionDiagnosticSnapshot`。 */
    fun connectionDiagnosticSnapshot(): String =
        "tcamEthernetLink=" + if (state.get().closed || state.get().invalidated) "lost" else "attached"

    override fun close() {
        // `getAndUpdate` 保证"读状态 + 置 closed"是一步原子操作，避免与并发 invalidate()
        // 的 CAS 交错时把 invalidated 标志回写掉（close 是终态，此后 closed 短路一切判据）。
        val previous = state.getAndUpdate { it.close() }
        // 解绑必须与"注销回调"同处收尾，而且排在最前：先停止用这条网络，再拆监控。
        // 这一步不能省 —— 绑定是**进程级**的，不跟着 NetworkCallback 走；漏解绑会把
        // App 的流量一直钉在 eth0.11 上，自建热点档（自己的网卡）被污染后就再也连不上。
        releaseProcessBind()
        if (previous.callbackRegistered) {
            runCatching { connectivity.unregisterNetworkCallback(callback) }
        }
    }

    /**
     * 把**本进程**绑到 [network] 上（`ConnectivityManager.bindProcessToNetwork`）。
     *
     * - API 23+；本机 minSdk 28 ✅（`Network.bindSocket()` 是 21+）。⚠️ 不要改用
     *   `clearCapabilities()` 那一类 API 30 的方法，见 [start] 里的踩坑注释。
     * - 返回系统是否接受本次绑定（[processBound] 随之更新）。**任何失败都只记一条诊断、
     *   不抛异常**：回包路径没钉住是风险，不是本次会话的致命错误 —— 宁可按今天的行为
     *   继续跑，也不把会话判死。
     * - 捕获 `Throwable` 而不是 `Exception`：厂商 ROM 上若真缺这个方法，抛出来的是
     *   `NoSuchMethodError`（`Error` 的子类）—— 与本仓 `clearCapabilities()` 那次
     *   教训（见 `TcamHotspotApiLevelGuardTest`）是同一个形状。
     */
    private fun bindProcess(network: Network): Boolean = try {
        connectivity.bindProcessToNetwork(network).also { processBound = it }
    } catch (error: Throwable) {
        onDiagnostic("TCAM process bind failed: ${error.javaClass.simpleName}: ${error.message}")
        false
    }

    /**
     * 解绑：把本进程的默认网络还给系统（`bindProcessToNetwork(null)`）。
     *
     * 只解**我们自己绑过的那一次**（[processBound]）：没绑成过就一个字节都不动，
     * 免得顺手清掉别人的绑定。幂等 —— 未绑定时、重复调用时都是空操作。
     */
    private fun releaseProcessBind() {
        if (!processBound) return
        processBound = false
        runCatching { connectivity.bindProcessToNetwork(null) }
    }

    /** DiPlay `invalidate`：只置标志；首次失效时写诊断并触发无线会话重启。 */
    private fun invalidate(reason: String) {
        val previous = state.get()
        if (previous.closed || previous.invalidated) return
        if (!state.compareAndSet(previous, previous.invalidate())) return
        onDiagnostic("TCAM $reason; restarting wireless session")
        onNetworkChanged()
    }

    /** DiPlay `sameLink(LinkProperties)`。 */
    private fun sameLink(properties: LinkProperties): Boolean =
        CarTcamHotspot.sameLink(
            interfaceName = properties.interfaceName,
            addresses = properties.linkAddresses.map { it.address },
            selectedAddress = state.get().selectedAddress,
        )

    /** 在 `getAllNetworks()` 里找**恰好一个** TCAM 接入卡候选。 */
    private fun findCandidate(): Pair<Network, Inet4Address>? {
        val networks = connectivity.allNetworks ?: return null
        val candidates = ArrayList<Pair<Network, Inet4Address>>()
        for (network in networks) {
            val capabilities = connectivity.getNetworkCapabilities(network) ?: continue
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) continue
            val properties = connectivity.getLinkProperties(network) ?: continue
            if (!CarTcamHotspot.isTcamInterface(properties.interfaceName)) continue
            val address = CarTcamHotspot.singleInet4Address(
                properties.linkAddresses.map { it.address },
            ) ?: continue
            candidates.add(network to address)
        }
        return candidates.singleOrNull()
    }

    /** DiPlay `readApEnabled`：反射 `WifiManager.isWifiApEnabled()`。 */
    private fun readApEnabled(): Boolean? = try {
        WifiManager::class.java.getMethod("isWifiApEnabled").invoke(wifi) as? Boolean
    } catch (_: ReflectiveOperationException) {
        null
    } catch (_: SecurityException) {
        null
    }

    /** DiPlay `readApConfiguration`：反射 `WifiManager.getWifiApConfiguration()`。 */
    private fun readApConfiguration(): WifiConfiguration? = try {
        WifiManager::class.java.getMethod("getWifiApConfiguration").invoke(wifi)
            as? WifiConfiguration
    } catch (_: ReflectiveOperationException) {
        null
    } catch (_: SecurityException) {
        null
    }

    /** DiPlay `readIntField`：反射读 `WifiConfiguration` 的整型字段。 */
    private fun readIntField(configuration: WifiConfiguration, name: String): Int? = try {
        WifiConfiguration::class.java.getField(name).getInt(configuration)
    } catch (_: ReflectiveOperationException) {
        null
    }

    /** DiPlay 的信道口径：`apChannel` 落在 1..196 才算数，否则 0（不指定）。 */
    private fun readApChannel(configuration: WifiConfiguration?): Int {
        val channel = configuration?.let { readIntField(it, "apChannel") } ?: return 0
        return if (channel in 1..196) channel else 0
    }

    /** DiPlay 的 SSID 归一化：去首尾引号，空/空白 → null。 */
    private fun normalizedSsid(configuration: WifiConfiguration?): String? {
        val ssid = configuration?.SSID?.trim('"') ?: return null
        return ssid.takeIf { it.isNotBlank() }
    }

    /** DiPlay 的口令归一化：去引号，8..63 位且不是掩码 `********` / `*` 才算可读。 */
    private fun normalizedPassphrase(value: String?): String? {
        val trimmed = value?.trim('"') ?: return null
        return trimmed.takeIf {
            it.length in 8..63 && it != "********" && it != "*"
        }
    }

    internal companion object {
        /**
         * 是否属于“KX11/CS11 严格匹配的原厂网关”这一支。
         *
         * 纯函数（不碰 Android 状态），单测直接覆盖：只有 SDK 28、MODEL KX11/CS11、
         * DEVICE 前缀匹配、`eth*` 接口、`198.18/15` 源地址同时成立才返回 true。
         * 目的：让“绕过网络绑定”的作用域可被测试钉死，避免日后被放宽成所有 TCAM 候选。
         */
        fun shouldBypassProcessBind(
            sdk: Int,
            model: String,
            device: String,
            interfaceName: String,
            address: java.net.InetAddress,
        ): Boolean = EcarxClientRoute.kx11Source(sdk, model, device, interfaceName, address)
    }
}
