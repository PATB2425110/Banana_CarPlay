// 移植自 carlito12345/DiPlay（GPL-3.0）network/EcarxHotspotReader.kt
// DBPlay 适配：包名改为 core-bridge；`kx11Source` 调用点扩展为 CS11（见 EcarxClientRoute）。
// 只读：不配置热点、不改路由、不开客户端限制。
package com.dbtool.carplay.bridge.hotspot

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import dalvik.system.PathClassLoader
import java.io.Closeable
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit

/**
 * 读取吉利 Ecarx（亿咖通）平台车机**原厂热点**。
 *
 * ## 为什么不能只用 NetworkInterface
 *
 * CS11/KX11 的原厂热点由厂商 `tcam` 服务 + Ecarx SDK 暴露，地址挂在 `eth*`
 * （实测 `eth0 = 198.18.34.15`），**不进标准 Android 网卡 API**，
 * `getWifiApState()` 也返回假的"关闭"。所以必须反射调 Ecarx SDK。
 *
 * ## 怎么加载 SDK（三级回落）
 *
 * 1. 当前 ClassLoader 直接能加载 → 用它；
 * 2. `/system/framework`、`/system_ext/framework`、`/vendor/framework` 下
 *    名字含 `ecarx` / `adaptapi` 的 jar → `PathClassLoader`；
 * 3. 吉利固件把 SDK 塞在 **`com.geely.settings`** 这个系统 App 里 →
 *    从它的 `sharedLibraryFiles` / `sourceDir` 加载（**必须以本进程 UID 加载**，
 *    权限检查仍归本进程）。
 *
 * ## 只读保证
 *
 * 不写系统状态、不改热点配置、不动客户端限制；只读 SDK 快照 + `ip route/neigh`。
 * 读不到就返回空 `Snapshot`，不猜。
 */
internal class EcarxHotspotReader(
    private val context: Context,
    private val log: (String) -> Unit = {},
) : Closeable {

    /**
     * **进程级共享状态**（2026-10-12 PATCH-0107 修 B）。
     *
     * ## 为什么必须有它
     *
     * 之前 `drivers` / `failures` 是**实例字段**，而调用点是
     * `EcarxHotspotReader(appContext).use { it.snapshot() }`（`CarHotspotEnabledReader.kt:63`
     * 与 `CarDiagnosticPackager.kt:665`）—— 每次读都 new 一个新的，`use {}` 结束即 `close()`，
     * 而 `close()` 里 `drivers = null`。结果：
     *
     * - `drivers` 缓存**从未跨调用存活**，每轮重连都重新反射加载 SDK + 重连 binder；
     * - `failures` 去重**同样失效**，同一个 `ClassNotFoundException`（本机
     *   `com.ecarx.xui.adaptapi.wifiap.impl.Wifi6ApImpl` 不存在）**每轮重连都重打一遍堆栈**。
     *
     * 实测日志（`1791811199698_f1bd27eb_67b3c24af6b5`）：21:18~21:19 每轮重连都伴随
     * 一次完整 `ClassNotFoundException: Wifi6ApImpl` 堆栈，共 21 次。
     *
     * ## 语义
     *
     * - `drivers`：三级回落加载成功的 SDK 驱动，**进程内复用**（加载/连接成本高）。
     * - `failedNames`：`API_NAMES` 里**已确认加载不到**的名字，只试一次，之后不再重试。
     * - `loggedFailures`：已写日志过的失败键，保证同一错误全进程只打一次。
     *
     * ⚠️ 生命周期：本类实例 close() 时**不再清空**共享状态（除非 `releaseAll()` 被显式调用，
     * 那只在 App 退出 / 诊断包结束时才需要）。这与原实现的实例级语义刻意不同。
     */
    private object Shared {
        @Volatile
        var drivers: List<Driver>? = null

        /** 已确认加载不到的 API 名（`ClassNotFoundException` / `LinkageError`）。 */
        val failedNames: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())

        /** 已写过日志的失败键，避免每轮重连刷屏。 */
        val loggedFailures: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())

        /** 三级回落加载出的 ClassLoader（原本就在伴生对象里缓存，这里保持语义一致）。 */
        @Volatile
        var loader: ClassLoader? = null

        fun reset() {
            drivers = null
            failedNames.clear()
            loggedFailures.clear()
            loader = null
        }
    }

    enum class RouteStatus {
        VERIFIED,
        NO_CURRENT_CLIENT,
        NO_VERIFIED_LOCAL_ROUTE,
        AMBIGUOUS,
        UNAVAILABLE,
    }

    data class Snapshot(
        val enabled: Boolean? = null,
        val wifi6Enabled: Boolean? = null,
        val band: Int? = null,
        val clientCount: Int = 0,
        val addressCount: Int = 0,
        val routeCount: Int = 0,
        val routeStatus: RouteStatus = RouteStatus.UNAVAILABLE,
        /** interface -> source address selected on this car to reach an SDK-reported client. */
        val routeSources: Map<String, InetAddress> = emptyMap(),
        /**
         * 客户端所在网段（如 `192.168.15.0/24`），**不含具体客户端 IP/MAC**。
         *
         * 2026-10-15 PATCH-01xx（诊断补采 A1）：只写网段前缀，用于回答「iPhone 落在哪个网段」
         * 而不泄露客户端身份。前缀取自本机匹配到的接口 prefix（现成数据，不新增探测）。
         */
        val clientSubnet: String? = null,
        /**
         * 本机到客户端的内核选路摘要（`route get` / socket 选源 / 子网匹配结果）。
         *
         * 2026-10-15 PATCH-01xx（诊断补采 A1）：把 [EcarxHotspotReader] 里本来只进 logcat 的
         * `LOCAL_NETWORK route …` 结论带进诊断包。只记**本机**侧选源与判定结果，不记对端地址。
         */
        val routeProbe: String? = null,
        /** SDK 当前报告的热点客户端 IPv4；默认不序列化到上传诊断包。 */
        val clientIpv4Addresses: List<String> = emptyList(),
        val diagnostic: String = "ecarxHotspot=unavailable",
    )

    private data class Driver(val api: Class<*>, val instance: Any, val connection: Class<*>?)

    private var lastRead = Long.MIN_VALUE

    @Volatile
    private var latest = Snapshot()

    private var closed = false

    /** 三级回落的 ClassLoader：进程级缓存（PATCH-0107 B）。 */
    private val loader: ClassLoader get() = Shared.loader ?: apiLoader(context).also { Shared.loader = it }

    /**
     * 读一次 SDK 快照。
     *
     * PATCH-0107（B）：驱动改走**进程级** `Shared.drivers` 复用；
     * 且 `API_NAMES` 里已确认加载不到的名字进 `Shared.failedNames`，
     * 之后不再重试（原实现每轮重连都重试同一个不存在 `ClassNotFoundException`）。
     *
     * 实例内的 1 秒缓存（`lastRead` / `latest`）保留：它是「同一实例连续读」的优化，
     * 与进程级驱动缓存互补，不冲突。
     */
    @Synchronized
    fun snapshot(): Snapshot {
        if (closed) return Snapshot()
        val now = SystemClock.elapsedRealtime()
        if (lastRead != Long.MIN_VALUE && now - lastRead < 1_000) return latest
        lastRead = now
        val active: List<Driver> = Shared.drivers ?: API_NAMES
            // 已确认加载不到的名字直接跳过，不再反射、不再写日志。
            .filterNot { it in Shared.failedNames }
            .mapNotNull<String, Driver> { name ->
                val api = runCatching { Class.forName(name, false, loader) }
                    .onFailure {
                        // 加载失败即永久拉黑这个名字（本机 SDK 缺失不会自愈）。
                        Shared.failedNames.add(name)
                        failure(name.substringAfterLast('.'), "load", it)
                    }.getOrNull()
                    ?: return@mapNotNull null
                try {
                    val instance = api.getMethod("create", Context::class.java).invoke(null, context)
                        ?: return@mapNotNull null
                    val connectable = runCatching {
                        Class.forName("com.ecarx.xui.adaptapi.binder.IConnectable", false, loader)
                            .takeIf { it.isInstance(instance) }
                    }.getOrNull()
                    val connection = connectable?.also {
                        // 即使 connect 部分成功后又抛，也保留所有权，避免泄漏 binder 连接。
                        runCatching { it.getMethod("connect").invoke(instance) }
                            .onFailure { error -> failure(api.simpleName, "connect", error) }
                    }
                    Driver(api, instance, connection)
                } catch (error: Exception) {
                    failure(api.simpleName, "create", error)
                    null
                } catch (error: LinkageError) {
                    failure(api.simpleName, "create", error)
                    null
                }
            }
            .also { connected -> if (connected.isNotEmpty()) Shared.drivers = connected }

        val result = read(active.orEmpty())
        latest = result
        return result
    }

    private fun read(active: List<Driver>): Snapshot {
        if (active.isEmpty()) {
            return Snapshot(
                routeStatus = RouteStatus.UNAVAILABLE,
                diagnostic = "ecarxHotspot=unavailable failures=${Shared.loggedFailures.sorted()}",
            )
        }
        var enabled: Boolean? = null
        var wifi6Enabled: Boolean? = null
        var band: Int? = null
        var clientCount = 0
        val peers = mutableListOf<InetAddress>()
        val clientIpv4Addresses = mutableSetOf<String>()
        val clientMacs = mutableSetOf<String>()

        active.forEach { driver ->
            runCatching {
                invoke(driver, "getWifiApState")?.let { state ->
                    val value = (state as? Number)?.toInt() ?: return@let
                    if (enabled == null) enabled = value == AP_STATE_ENABLED
                }
            }
            val clients = runCatching {
                invoke(driver, "getWifiApClients") as? Collection<*>
            }.getOrNull().orEmpty()
            clientCount += clients.size
            val addresses = clients.mapNotNull { client ->
                client ?: return@mapNotNull null
                val clientApi = Class.forName(CLIENT_API, false, loader)
                if (!clientApi.isInstance(client)) return@mapNotNull null
                runCatching { clientApi.getMethod("getMac").invoke(client) as? String }
                    .getOrNull()?.let { clientMacs += it.lowercase() }
                numericAddress(
                    runCatching { clientApi.getMethod("getIP").invoke(client) as? String }.getOrNull()
                )
            }
            peers += addresses
            addresses.filterIsInstance<Inet4Address>()
                .mapNotNullTo(clientIpv4Addresses) { it.hostAddress }
            // Exact client addresses remain in memory for routing. The diagnostic renderer only
            // emits them when the user explicitly opts into private IPv4 collection.
            val host = invoke(driver, "getWifiAPHost")
            if (host != null) {
                val hostApi = Class.forName(HOST_API, false, loader)
                if (hostApi.isInstance(host)) {
                    (hostApi.getMethod("getCurrentFrequencyMode").invoke(host) as? Number)
                        ?.toInt()?.let { if (band == null) band = it }
                }
            }
        }

        val directRoutes = peers.distinctBy { it.hostAddress }.mapNotNull(::routeToClient)
        // SDK clients can omit getIP while still reporting the peer MAC. Consult both ARP and
        // IPv6 neighbor tables, matching only those MACs in-memory; never serialize/log the MAC.
        val neighborAddresses = if (clientMacs.isNotEmpty()) neighborPeers(clientMacs) else emptyList()
        clientIpv4Addresses.addAll(
            neighborAddresses.filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress },
        )
        val neighborRoutes = neighborAddresses.mapNotNull(::routeToClient)
        val peersForSnapshot = (peers + neighborAddresses).distinctBy { it.hostAddress }
        val routes = (directRoutes + neighborRoutes)
            .groupBy({ it.first }, { it.second }).mapNotNull { (name, hosts) ->
                // 同一 factory AP 同时可达两家族时优先已验证的 IPv4。
                wirelessHostAddress(hosts.distinct(), null)
                    ?.let { name to it }
            }
        val verifiedRoutes = routes.takeIf { it.size == 1 }.orEmpty()
        routes.forEach { (name, address) ->
            log("LOCAL_NETWORK route iface=$name localSource=${address.hostAddress} result=matched")
        }
        val routeStatus = when {
            routes.size > 1 -> RouteStatus.AMBIGUOUS
            verifiedRoutes.isNotEmpty() -> RouteStatus.VERIFIED
            peers.isNotEmpty() || clientCount > 0 -> RouteStatus.NO_VERIFIED_LOCAL_ROUTE
            else -> RouteStatus.NO_CURRENT_CLIENT
        }
        // 诊断补采 A1：写「客户端落在哪个网段」，用本机匹配到的接口 prefix 归一；具体客户端
        // 地址只在内存里保留（clientIpv4Addresses），由诊断渲染器按用户逐包勾选决定是否写出。
        // MAC 永不写出。取不到 prefix 就不写（宁缺勿猜）。
        var clientSubnet: String? = null
        val firstPeer4 = peersForSnapshot.firstOrNull { it is Inet4Address } as? Inet4Address
        if (firstPeer4 != null) {
            runCatching {
                NetworkInterface.getNetworkInterfaces().toList().forEach { candidate ->
                    if (clientSubnet != null || !candidate.isUp || candidate.isLoopback) return@forEach
                    candidate.interfaceAddresses.forEach { entry ->
                        val local = entry.address as? Inet4Address ?: return@forEach
                        val prefix = entry.networkPrefixLength.toInt()
                        if (!sameSubnet(local.address, firstPeer4.address, prefix)) return@forEach
                        if (prefix !in 1..32) return@forEach
                        val masked = local.address.copyOf()
                        for (index in 0 until 4) {
                            val bits = (prefix - index * 8).coerceIn(0, 8)
                            val keep = if (bits == 0) 0 else (0xFF shl (8 - bits)) and 0xFF
                            masked[index] = (masked[index].toInt() and keep).toByte()
                        }
                        val dotted = masked.joinToString(".") { (it.toInt() and 0xFF).toString() }
                        clientSubnet = "$dotted/$prefix"
                    }
                }
            }.onFailure { log("LOCAL_NETWORK clientSubnet=unavailable error=${it.javaClass.simpleName}") }
        }
        // 诊断补采 A1：把本轮内核选路的结论摘要带进诊断包（不含对端地址）。
        val routeSourceFlag = if (verifiedRoutes.firstOrNull() != null) "local" else "(none)"
        val routeProbe = buildString {
            append("routes=")
            append(routes.map { it.first }.sorted())
            append(" routeStatus=")
            append(routeStatus)
            append(" clientCount=")
            append(clientCount)
            append(" routeSource=")
            append(routeSourceFlag)
        }
        val path = when (routeStatus) {
            RouteStatus.VERIFIED -> "verified"
            RouteStatus.NO_CURRENT_CLIENT -> "no_current_client"
            RouteStatus.NO_VERIFIED_LOCAL_ROUTE -> "no_verified_local_route"
            RouteStatus.AMBIGUOUS -> "ambiguous_factory_routes"
            RouteStatus.UNAVAILABLE -> "unavailable"
        }
        return Snapshot(
            enabled = enabled,
            wifi6Enabled = wifi6Enabled,
            band = band,
            clientCount = clientCount,
            addressCount = peersForSnapshot.size,
            routeCount = routes.size,
            routeStatus = routeStatus,
            routeSources = verifiedRoutes.toMap(),
            clientSubnet = clientSubnet,
            routeProbe = routeProbe,
            clientIpv4Addresses = clientIpv4Addresses.sorted(),
            diagnostic = "ecarxHotspot=$path clients=$clientCount " +
                "addresses=${peersForSnapshot.size} " +
                "routes=${routes.map { it.first }.sorted()} band=${band ?: -1}",
        )
    }

    /**
     * 把一个 SDK 客户端地址映射到本机真实接口地址。
     *
     * 先用 `DatagramSocket.connect` 让内核选源（不发包、不绑进程），
     * 再用 `NetworkInterface` + 子网校验确认；跨网段走 [EcarxClientRoute] 严格判定。
     */
    private fun routeToClient(peer: InetAddress): Pair<String, InetAddress>? = runCatching {
        if (peer.isAnyLocalAddress || peer.isLoopbackAddress || peer.isMulticastAddress ||
            peer is Inet6Address && peer.isLinkLocalAddress && peer.scopeId <= 0
        ) {
            log("LOCAL_NETWORK route peer=${peer.hostAddress} result=unsupported_address_or_missing_scope")
            return@runCatching null
        }
        val routed = runCatching {
            DatagramSocket().use { socket ->
                // connect 只让内核解析本地源，不发任何包。
                socket.connect(peer, 1)
                val host = socket.localAddress
                log(
                    "LOCAL_NETWORK route peer=${peer.hostAddress} local=${host.hostAddress} " +
                        "family=${if (host is Inet4Address) "IPv4" else "IPv6"} " +
                        "sourceClass=${EcarxClientRoute.addressClass(host)} result=socket_route"
                )
                if (host.isAnyLocalAddress || host.isLoopbackAddress || host.isMulticastAddress) {
                    log("LOCAL_NETWORK route result=source_invalid")
                    return@use null
                }
                val iface = runCatching { NetworkInterface.getByInetAddress(host) }.onFailure {
                    log("LOCAL_NETWORK route result=interface_lookup_failed error=${it.javaClass.simpleName}")
                }.getOrNull()
                if (iface == null) {
                    log("LOCAL_NETWORK route result=source_interface_unmapped")
                    return@use null
                }
                if (EcarxClientRoute.unsafeInterface(iface.name) ||
                    iface.inetAddresses.toList().none { it.address.contentEquals(host.address) }
                ) {
                    log("LOCAL_NETWORK route iface=${iface.name} result=source_interface_ineligible")
                    return@use null
                }
                val direct = if (peer is Inet6Address) peer.scopeId == iface.index else
                    iface.interfaceAddresses.any { entry ->
                        val address = entry.address
                        address is Inet4Address && address.address.contentEquals(host.address) &&
                            sameSubnet(address.address, peer.address, entry.networkPrefixLength.toInt())
                    }
                if (direct) {
                    iface.name to host
                } else if (peer is Inet4Address && host is Inet4Address) {
                    verifiedFactoryGateway(peer, host, iface)
                } else null
            }
        }.onFailure { log("LOCAL_NETWORK route result=socket_route_failed error=${it.javaClass.simpleName}") }
            .getOrNull()
        if (routed != null) return@runCatching routed
        // 厂商 AP 可能没有可用的默认 socket 路由：只接受唯一的本机子网匹配，
        // 绝不把无关默认网络地址当成 AP。
        if (peer !is Inet4Address) return@runCatching null
        val matches = NetworkInterface.getNetworkInterfaces().toList()
            .filterNot { EcarxClientRoute.unsafeInterface(it.name) || !it.isUp || it.isLoopback }
            .flatMap { iface ->
                iface.interfaceAddresses.mapNotNull { entry ->
                    val host = entry.address
                    if (host is Inet4Address && EcarxClientRoute.usableIpv4(host) &&
                        sameSubnet(host.address, peer.address, entry.networkPrefixLength.toInt())
                    ) iface.name to host else null
                }
            }
        log(
            "LOCAL_NETWORK route peer=${peer.hostAddress} subnetMatches=${matches.size} " +
                "result=${when {
                    matches.size == 1 -> "unique_subnet"
                    matches.isEmpty() -> "no_local_subnet"
                    else -> "ambiguous_subnet"
                }}"
        )
        matches.singleOrNull()
    }.getOrElse {
        log("LOCAL_NETWORK route result=route_validation_failed error=${it.javaClass.simpleName}")
        null
    }

    /**
     * 跨网段原厂网关判定。
     *
     * 只有 `eth*`/`br*`/无线接口，且内核 `route get` + `route show` 与源接口子网
     * 网关全部一致（KX11/CS11 还允许默认路由），并本机可 bind 该源地址，才放行。
     */
    private fun verifiedFactoryGateway(
        peer: Inet4Address, host: Inet4Address, iface: NetworkInterface
    ): Pair<String, InetAddress>? {
        if (!EcarxClientRoute.usableIpv4(host) ||
            !(Regex("eth[0-9]+(\\.[0-9]+)?", RegexOption.IGNORE_CASE).matches(iface.name) ||
                iface.name.startsWith("br") || wirelessInterfaceName(iface.name))
        ) {
            log(
                "LOCAL_NETWORK route iface=${iface.name} " +
                    "sourceClass=${EcarxClientRoute.addressClass(host)} result=cross_subnet_policy_rejected"
            )
            return null
        }
        val allowDefault = EcarxClientRoute.kx11Source(
            Build.VERSION.SDK_INT, Build.MODEL, Build.DEVICE, iface.name, host
        )
        val routeGet = ipLines(listOf("-4", "route", "get", peer.hostAddress!!), "route_get") ?: return null
        val resolved = routeGet.mapNotNull { EcarxClientRoute.parseIpv4Route(it) }.singleOrNull()
        val routeTable = ipLines(listOf("-4", "route", "show", "table", "all"), "route_table") ?: return null
        if (resolved != null &&
            EcarxClientRoute.matchingMultipathRoute(routeTable, peer, resolved.table, allowDefault)
        ) {
            log("LOCAL_NETWORK route result=matching_multipath_route_unsupported")
            return null
        }
        val sourcePrefix = iface.interfaceAddresses.firstOrNull { it.address == host }
            ?.networkPrefixLength?.toInt() ?: 0
        val result = EcarxClientRoute.routedDecision(
            peer, host, iface.name, sourcePrefix, resolved,
            routeTable.mapNotNull { EcarxClientRoute.parseIpv4Route(it, allowDefault) }, allowDefault
        )
        log(
            "LOCAL_NETWORK route iface=${iface.name} " +
                "sourceClass=${EcarxClientRoute.addressClass(host)} " +
                "table=${resolved?.table ?: "unknown"} defaultRouteEligible=$allowDefault result=$result"
        )
        return if (result in setOf("verified_factory_gateway", "verified_factory_default_gateway") &&
            sourceBindable(host)
        ) iface.name to host else null
    }

    /** 本机 bind 成功证明地址归属（不代表对端可达）。 */
    private fun sourceBindable(host: InetAddress): Boolean = runCatching {
        DatagramSocket(null).use { it.bind(InetSocketAddress(host, 0)) }
        true
    }.getOrElse {
        log(
            "LOCAL_NETWORK route sourceClass=${EcarxClientRoute.addressClass(host)} " +
                "result=source_bind_failed error=${it.javaClass.simpleName}"
        )
        false
    }

    /**
     * 以本进程 UID 运行固定 `ip` 程序读路由/邻居。
     *
     * 不用 shell；限定 400ms；输出超 512 行即拒绝（防止无界读取）。
     */
    private fun ipLines(arguments: List<String>, topic: String): List<String>? {
        if (closed || Thread.currentThread().isInterrupted) return null
        var process: Process? = null
        return try {
            process = ProcessBuilder(listOf("/system/bin/ip") + arguments)
                .redirectErrorStream(true).start()
            if (!process.waitFor(400, TimeUnit.MILLISECONDS)) {
                log("ecarxHotspot $topic=timeout")
                null
            } else {
                val lines = process.inputStream.bufferedReader().use { it.lineSequence().take(513).toList() }
                when {
                    closed || Thread.currentThread().isInterrupted -> null
                    lines.size > 512 -> {
                        log("ecarxHotspot $topic=output_too_large")
                        null
                    }
                    process.exitValue() != 0 -> {
                        val denied = lines.any {
                            it.contains("permission", true) || it.contains("not permitted", true)
                        }
                        log(
                            "ecarxHotspot $topic=${if (denied) "permission_denied" else "query_failed"} " +
                                "exit=${process.exitValue()}"
                        )
                        null
                    }
                    else -> lines
                }
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (error: Exception) {
            log("ecarxHotspot $topic=unavailable error=${error.javaClass.simpleName}")
            null
        } finally {
            process?.destroyForcibly()
            runCatching { process?.inputStream?.close() }
            runCatching { process?.errorStream?.close() }
            runCatching { process?.outputStream?.close() }
        }
    }

    /** 按 SDK 客户端 MAC 关联系统 IPv4 ARP 与 IPv6 邻居项；MAC 仅留在内存匹配。 */
    private fun neighborPeers(clientMacs: Set<String>): List<InetAddress> {
        if (clientMacs.isEmpty()) return emptyList()
        val tables = listOf(
            listOf("-4", "neigh", "show") to "neighborTable4",
            listOf("-6", "neigh", "show") to "neighborTable6",
        )
        return tables.flatMap { (arguments, topic) ->
            val lines = ipLines(arguments, topic).orEmpty()
            lines.mapNotNull { line -> neighborEntry(line, clientMacs) }
                .mapNotNull { (raw, name) ->
                    runCatching {
                        val iface = NetworkInterface.getByName(name)?.takeIf {
                            it.isUp && !it.isLoopback && it.index > 0
                        } ?: return@runCatching null
                        val address = numericAddress(raw) ?: return@runCatching null
                        if (address is Inet4Address) address else
                            Inet6Address.getByAddress(null, address.address, iface.index)
                    }.getOrNull()
                }
        }.distinctBy { it.hostAddress }
    }

    /**
     * 关闭这个 reader 实例。
     *
     * PATCH-0107（B）：**不再拆掉进程级驱动**，也**不再 `disconnect` binder 连接**。
     *
     * 原实现在这里 `disconnect()` + `drivers = null`，但调用点是
     * `Reader(ctx).use { it.snapshot() }` —— 也就是**每次读都立刻连了又断**，
     * 缓存完全作废。现在驱动归进程级 `Shared.drivers` 所有，由
     * [releaseShared] 在 App 退出 / 诊断包结束时统一释放。
     *
     * 实例自身只是「一次读取的上下文」，close() 仅标记本实例不可再用。
     */
    @Synchronized
    override fun close() {
        closed = true
    }

    private fun invoke(driver: Driver, method: String): Any? = runCatching {
        driver.api.getMethod(method).invoke(driver.instance)
    }.onFailure { error ->
        val cause = (error as? InvocationTargetException)?.targetException ?: error
        failure(driver.api.simpleName, method, cause)
    }.getOrNull()

    private fun failure(api: String, stage: String, error: Throwable) {
        val key = "$api.$stage.${error.javaClass.simpleName}"
        // PATCH-0107（B）：去重集合改走进程级 Shared.loggedFailures。
        // 原实现用实例字段 `failures`，而每个实例读一次就 close()，
        // 于是同一错误在每轮重连都重打一遍 —— 已由 Shared 修掉。
        if (Shared.loggedFailures.add(key)) log("ecarxHotspot failure=$key message=${error.message}")
    }

    private fun wirelessInterfaceName(name: String): Boolean =
        Regex("^(wlan|ap|softap|p2p)[0-9]*.*", RegexOption.IGNORE_CASE).matches(name)

    companion object {
        /** 车机 AP 状态常量：13 = ENABLED。与 Android 官方 SoftAP 一致。 */
        private const val AP_STATE_ENABLED = 13

        private val API_NAMES = listOf(
            "com.ecarx.xui.adaptapi.wifiap.WifiAp",
            "com.ecarx.xui.adaptapi.wifiap.Wifi6Ap",
        )
        private const val CLIENT_API = "com.ecarx.xui.adaptapi.wifiap.IWifiApClient"
        private const val HOST_API = "com.ecarx.xui.adaptapi.wifiap.IWifiAPHost"

        @Volatile
        private var cachedLoader: ClassLoader? = null

        /**
         * 三级回落加载 Ecarx SDK。
         *
         * 关键：只把 SDK 的**类**加载进本进程，运行时仍以本进程 UID 做权限检查，
         * 不会继承 `com.geely.settings` 的权限。
         */
        private fun apiLoader(context: Context): ClassLoader {
            cachedLoader?.let { return it }
            val parent = context.classLoader
            if (API_NAMES.all { runCatching { Class.forName(it, false, parent) }.isSuccess }) {
                return parent.also { cachedLoader = it }
            }
            // 有些 ROM 只对系统 App 暴露 SDK，未加进普通 App 的 loader。
            val jars = listOf("/system/framework", "/system_ext/framework", "/vendor/framework")
                .flatMap { directory ->
                    runCatching { File(directory).listFiles()?.toList() }.getOrNull().orEmpty()
                }
                .filter { file ->
                    file.isFile && file.canRead() && file.extension == "jar" &&
                        (file.name.contains("ecarx", true) || file.name.contains("adaptapi", true))
                }
                .sortedBy { it.absolutePath }
            val frameworkLoader = if (jars.isEmpty()) parent else runCatching {
                PathClassLoader(jars.joinToString(File.pathSeparator) { it.absolutePath }, parent)
            }.getOrDefault(parent)
            // 部分固件把 SDK 塞在吉利系统设置 App 里：只加载它的 SDK，
            // 保持本进程 UID 与权限检查。
            val loader = if (API_NAMES.any {
                    runCatching { Class.forName(it, false, frameworkLoader) }.isSuccess
                }
            ) {
                frameworkLoader
            } else runCatching {
                val app = context.packageManager.getApplicationInfo(
                    "com.geely.settings", PackageManager.GET_SHARED_LIBRARY_FILES
                )
                check(app.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0)
                val paths = (app.sharedLibraryFiles.orEmpty().toList() + listOf(app.sourceDir) +
                    app.splitSourceDirs.orEmpty().toList()).filter { File(it).canRead() }
                if (paths.isEmpty()) frameworkLoader
                else PathClassLoader(paths.joinToString(File.pathSeparator), frameworkLoader)
            }.getOrDefault(frameworkLoader)
            return loader.also { cachedLoader = it }
        }

        /** SDK 是否在本机可用（用于决定要不要走原厂路径）。 */
        fun available(context: Context): Boolean = API_NAMES.any { name ->
            runCatching { Class.forName(name, false, apiLoader(context)) }.isSuccess
        }

        /**
         * **释放进程级共享状态**（PATCH-0107 B）。
         *
         * 只在 App 退出 / 诊断打包结束时调用。会 disconnect 所有已建立的 binder 连接、
         * 清空驱动缓存与失败拉黑表。日常读取**不要**调用它 —— 那正是原实现的毛病
         * （每个实例读完就断，缓存永远不生效）。
         */
        @JvmStatic
        fun releaseShared() {
            Shared.drivers.orEmpty().forEach { driver ->
                runCatching {
                    val api = driver.javaClass.getDeclaredField("api").apply { isAccessible = true }
                        .get(driver) as Class<*>
                    val instance = driver.javaClass.getDeclaredField("instance").apply { isAccessible = true }
                        .get(driver)
                    val connection = driver.javaClass.getDeclaredField("connection").apply { isAccessible = true }
                        .get(driver) as Class<*>?
                    connection?.let {
                        runCatching { it.getMethod("disconnect").invoke(instance) }
                    }
                    api.hashCode() // 保持引用，避免被优化掉；无副作用
                }
            }
            Shared.reset()
        }

        /** 把 SDK 返回的地址字符串解析成 [InetAddress]；非法一律 null。 */
        fun numericAddress(raw: String?): InetAddress? {
            val text = raw?.trim()?.substringBefore('%') ?: return null
            if (text.isEmpty() || text.any { it != '.' && it != ':' && !it.isDigit() &&
                    !(it in 'a'..'f') && !(it in 'A'..'F') }
            ) return null
            return runCatching { InetAddress.getByName(text) }.getOrNull()
        }

        /** 两地址是否同子网（按 prefix 位比较）。 */
        fun sameSubnet(a: ByteArray, b: ByteArray, prefix: Int): Boolean {
            if (a.size != b.size || prefix !in 0..a.size * 8) return false
            var remaining = prefix
            var index = 0
            while (remaining >= 8) {
                if (a[index] != b[index]) return false
                index++
                remaining -= 8
            }
            if (remaining == 0) return true
            val mask = (0xFF shl (8 - remaining)) and 0xFF
            return (a[index].toInt() and mask) == (b[index].toInt() and mask)
        }

        /** 从 `ip neigh` 输出里按 MAC 找客户端地址与接口名。 */
        fun neighborEntry(line: String, clientMacs: Set<String>): Pair<String, String>? {
            if (clientMacs.isEmpty()) return null
            val fields = line.trim().split(Regex("\\s+"))
            val states = setOf("REACHABLE", "STALE", "DELAY", "PROBE", "PERMANENT")
            if (fields.none { it.uppercase() in states }) return null
            val address = fields.getOrNull(0) ?: return null
            val devIndex = fields.indexOf("dev")
            val name = if (devIndex >= 0) fields.getOrNull(devIndex + 1) else null
            val mac = fields.firstOrNull { it.matches(Regex("([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}")) }
            if (name == null || mac == null || mac.lowercase() !in clientMacs) return null
            return address to name
        }

        /** 在候选地址里按家族偏好挑一个。 */
        fun wirelessHostAddress(addresses: List<InetAddress>, preferredFamily: String?): InetAddress? =
            HotspotAddressPolicy.select(addresses, preferredFamily)
    }
}
