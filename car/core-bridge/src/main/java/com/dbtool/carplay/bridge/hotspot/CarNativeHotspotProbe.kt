package com.dbtool.carplay.bridge.hotspot

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.dbtool.carplay.api.hotspot.CarConfigFileDigest
import com.dbtool.carplay.api.hotspot.CarHotspotProbeEntry
import com.dbtool.carplay.api.hotspot.CarHotspotProbeSource
import com.dbtool.carplay.api.hotspot.CarHotspotSubnet
import com.dbtool.carplay.api.hotspot.CarIwDevOutput
import com.dbtool.carplay.api.hotspot.CarNativeHotspotHost
import com.dbtool.carplay.api.hotspot.CarNativeHotspotInfo
import com.dbtool.carplay.api.hotspot.CarNativeHotspotMerge
import com.dbtool.carplay.api.hotspot.CarNativeHotspotProbeState
import com.dbtool.carplay.api.settings.CarHotspotBand
import com.dbtool.carplay.api.settings.CarHotspotSecurity
import java.util.concurrent.Executors

/**
 * **经 adb shell 探测车机原生热点**。
 *
 * ## 为什么要有它
 *
 * 之前的做法是"假设车机长什么样"：写死 SSID / IP / 网卡，然后直接去建热点。
 * 换一台车就废。正确的第一步是**先问清楚这台车现在到底有什么**：
 *
 * - 热点开着吗？叫什么名字？密码是什么？
 * - 是哪张网卡在当 AP？它自己的地址、掩码、网关、DHCP 池是多少？
 * - 路由表和策略路由里有没有已经占用的网段（自建时不能撞）？
 * - `hostapd` 在哪、能不能提权？
 *
 * 拿到这些之后才谈得上"复用车机热点"，或者"选一组不冲突的参数自建"。
 *
 * ## 探测手段
 *
 * 全部通过 `AdbShellRunner` 跑只读命令，逐条把**原始输出**记下来：
 *
 * | 手段 | 想拿到什么 |
 * |------|-----------|
 * | `getprop` | ABI / `service.adb.tcp.port` / `init.svc.adbd` / `net.dns*` |
 * | `ip addr` | 所有网卡、UP 状态、IPv4（**核心**） |
 * | `ip route show table all` | 默认路由（谁是上行）、各网段（**核心**） |
 * | `ip rule show` | 策略路由（自建热点要加规则，先看有没有残留） |
 * | `dumpsys wifi` | `SoftApConfiguration{}`：SSID / 密码 / 频段 / 信道 / 加密 |
 * | `dumpsys tethering` | DHCP 池、SoftAP 接口名、上行接口 |
 * | `dumpsys connectivity` | DNS、各网络接口的 `LinkProperties` |
 * | `iw dev` | 无线网卡的实际信道 |
 * | `ps -A` | hostapd / udhcpd / dnsmasq / wpa_supplicant 是否在跑 |
 * | `cat hostapd.conf` | 热点配置——密码是明文的（需 root）。⚠️ **可能是厂商模板，不一定是运行中那份** |
 * | `cat WifiConfigStore.xml` | 持久化配置（SSID 明文、密码是密文） |
 * | `cat softap.conf` | 老式配置（明文，需 root） |
 * | `cat dnsmasq.*` | DHCP 池与租约（需 root） |
 * | `netstat -tlnp` | 67（DHCP）/ 5555（adbd）是否在监听 |
 * | `/proc/<pid>/cmdline` + 配置目录 `ls -l` | **hostapd 真正在用的配置**是哪份、哪些文件是老早没动过的（需 root） |
 *
 * ## 三条设计约束
 *
 * 1. **只读**。除了"开通车机 ADB"（不打开 ADB 就没有 shell，探测无从谈起），
 *    不写任何系统状态、不动网卡、不起进程。
 * 2. **不猜**。读不到就留空，绝不用默认值填坑——填了坑，"没读到"会被
 *    伪装成"读到了 192.168.43.1"，排障时反而更慢。
 * 3. **原文全留**。解析规则一定会漏（车机 ROM 千差万别），所以每条命令的
 *    原始输出都进 [CarNativeHotspotProbeState.rawLog] 并写 logcat。
 *
 * ## 线程
 *
 * [probe] **阻塞**（十几条 shell 命令 + 三次 `dumpsys`，实测数秒），
 * 只能在 worker 线程调用。[requestProbe] 是它的非阻塞版本，供界面按钮用。
 * 结果缓存在 [cachedInfo] 里，会话启动路径按 TTL 复用，避免每次建栈都重探。
 */
internal class CarNativeHotspotProbe(
    private val appContext: Context,
    private val session: AdbShellSession,
) : CarNativeHotspotHost {

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "dbplay-hotspot-probe").apply { isDaemon = true }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private val lock = Any()

    @Volatile
    private var snapshot = CarNativeHotspotProbeState()

    /** 最近一次探测结论（字段名刻意不叫 lastInfo：会与接口方法同名）。 */
    @Volatile
    private var cachedInfoValue: CarNativeHotspotInfo = CarNativeHotspotInfo()

    @Volatile
    private var lastAtMillis: Long = 0L

    // ------------------------------------------------------------ 对外

    override fun state(): CarNativeHotspotProbeState = snapshot

    override fun lastInfo(): CarNativeHotspotInfo = cachedInfoValue

    override fun cachedInfo(maxAgeMillis: Long): CarNativeHotspotInfo? {
        if (lastAtMillis == 0L) return null
        if (System.currentTimeMillis() - lastAtMillis > maxAgeMillis) return null
        return cachedInfoValue
    }

    override fun invalidate() {
        lastAtMillis = 0L
    }

    override fun requestProbe(onState: (CarNativeHotspotProbeState) -> Unit) {
        if (snapshot.running) return
        worker.execute { probe(onState) }
    }

    override fun probe(onState: (CarNativeHotspotProbeState) -> Unit): CarNativeHotspotInfo {
        val entries = mutableListOf<CarHotspotProbeEntry>()
        val rawLog = StringBuilder()
        var rawTruncated = false

        publish(onState) {
            it.copy(
                running = true,
                finished = false,
                entries = emptyList(),
                rawLog = "",
                summary = "正在探测车机原生热点…",
            )
        }

        fun record(title: String, command: String, ok: Boolean, output: String) {
            val index = entries.size + 1
            entries += CarHotspotProbeEntry(
                index = index,
                title = title,
                command = command,
                ok = ok,
                output = clipForDisplay(output),
            )
            if (!rawTruncated) {
                rawLog.append("\n===== [").append(index).append("] ").append(title).append(" =====\n")
                if (command.isNotEmpty()) rawLog.append("$ ").append(command).append('\n')
                rawLog.append(clipForRaw(output)).append('\n')
                if (rawLog.length > RAW_LOG_LIMIT) {
                    rawTruncated = true
                    rawLog.append("\n…（原始日志超过 ${RAW_LOG_LIMIT} 字符，后续内容已省略）\n")
                }
            }
            publish(onState) { it.copy(entries = entries.toList(), rawLog = rawLog.toString()) }
            logChunked(index, title, command, output)
        }

        val startedAt = System.currentTimeMillis()

        // ---- 1. 拿到 shell（含开通 ADB / 二进制 / server / 自连 / 提权）
        val access = session.ensure()
        access.steps.forEach { step -> record(step.title, step.command, step.ok, step.detail) }
        publish(onState) { it.copy(shellAvailable = access.shellOk) }

        // ---- 2. 跑探测命令
        val raw = Raw()
        var adbInfo = CarNativeHotspotInfo()
        if (access.shellOk) {
            val prefix = access.prefixOrEmpty
            specs().forEach { spec ->
                if (spec.needsRoot && !access.rooted) {
                    record(
                        title = spec.title,
                        command = prefix + spec.body,
                        ok = false,
                        output = "跳过：需要 root。当前 adb shell 既不是 root 也没有可用的 su，" +
                            "而这一步（读热点配置文件）通常是拿到车机热点密码的关键。",
                    )
                    return@forEach
                }
                val command = prefix + spec.body
                var result = session.runner.shell(command, spec.timeoutMillis)
                if (spec.fallback != null && needsFallback(result)) {
                    val fallbackCommand = prefix + spec.fallback
                    val retried = session.runner.shell(fallbackCommand, spec.timeoutMillis)
                    // 只有兜底真拿到东西才替换：否则保留主命令的输出（含报错原因），
                    // 那个报错本身也是排障信息。
                    if (retried.output.isNotBlank()) result = retried
                }
                spec.slot(raw, result.output)
                // 记录前先压缩（见 Spec.compact）：配置文件里的注释不该占用诊断预算。
                // ⚠️ ok 仍用**原文**判——全是注释的文件压完可能为空，不能因此判成失败。
                val recorded =
                    if (spec.compact) CarConfigFileDigest.compact(result.output) else result.output
                record(spec.title, command, result.ok || result.output.isNotBlank(), recorded)
            }
            adbInfo = parse(raw, source = CarHotspotProbeSource.ADB_SHELL)
        }

        // ---- 3. ADB 不通（或没探到）时退回本地反射
        val localInfo = LocalSoftApReader.read(appContext)
        if (!access.shellOk || (!adbInfo.detected && localInfo.detected)) {
            record(
                title = "本地反射读取（WifiManager）",
                command = "WifiManager.getSoftApConfiguration() / isWifiApEnabled()",
                ok = localInfo.detected,
                output = describe(localInfo),
            )
        }
        val info = merge(adbInfo, localInfo)

        // ---- 4. 结论
        val elapsed = System.currentTimeMillis() - startedAt
        val summary = buildString {
            append(summarize(info))
            append("（耗时 ${elapsed} ms")
            append(if (access.shellOk) "，来源 adb shell" else "，来源本地反射：ADB 不可用")
            if (access.shellOk && !access.rooted) append("，未提权")
            append("）")
        }

        synchronized(lock) {
            cachedInfoValue = info
            lastAtMillis = System.currentTimeMillis()
        }
        publish(onState) {
            it.copy(
                running = false,
                finished = true,
                info = info,
                entries = entries.toList(),
                rawLog = rawLog.toString(),
                summary = summary,
            )
        }
        Log.i(TAG, "probe done: $summary")
        if (!access.shellOk) {
            Log.w(TAG, "probe ran without adb shell: ${access.failure}")
        }
        return info
    }

    // ------------------------------------------------------------ 命令清单

    /** 一次探测里要跑的一条命令。 */
    private class Spec(
        val title: String,
        /** 命令体，**不含**提权前缀（调用方按探测结果加）。 */
        val body: String,
        /** true = 没有 root 时跳过（读配置文件需要）。 */
        val needsRoot: Boolean = false,
        /** 主命令没拿到东西时再试这条。 */
        val fallback: String? = null,
        val timeoutMillis: Long = DEFAULT_SHELL_TIMEOUT_MILLIS,
        /**
         * true = 记录前先去掉整行注释与空行（[CarConfigFileDigest.compact]）。
         *
         * 只给 `cat` 配置文件的步骤开：这类文件（尤其 `hostapd.conf` 这种
         * AOSP 模板）**前面两百多行全是注释**，按字符裁剪时预算会被注释吃光，
         * 真正要看的 `ssid2=` / `wpa_passphrase=` / `channel=` 反而被裁掉
         * ——实车 `[15]` 就是这么把「含明文密码」那一步裁成没密码的。
         *
         * ⚠️ 只影响**记录/展示**，不影响 [slot]（解析仍吃原文）。
         */
        val compact: Boolean = false,
        /** 把输出收进哪个槽位。 */
        val slot: (Raw, String) -> Unit,
    )

    /** 探测输出按"用途"分槽，避免用标题字符串当键（改标题就会静默失配）。 */
    private class Raw {
        var properties = ""
        var ipAddr = ""
        var ipRoute = ""
        var ipRule = ""
        var wifi = ""
        var tethering = ""
        var connectivity = ""
        var iwDev = ""
        var processes = ""
        var hostapdConf = ""
        var hostapdRuntime = ""
        var wifiStore = ""
        var softapConf = ""
        var dnsmasq = ""
        var netstat = ""
    }

    private fun specs(): List<Spec> = listOf(
        Spec(
            title = "系统属性（ABI / ADB / DNS）",
            body = "getprop ro.product.cpu.abilist; getprop ro.product.cpu.abi; " +
                "getprop ro.build.version.release; getprop ro.product.model; " +
                "getprop service.adb.tcp.port; getprop init.svc.adbd; " +
                "getprop net.dns1; getprop net.dns2",
            slot = { raw, out -> raw.properties = out },
        ),
        Spec(
            title = "网卡与地址（ip addr）",
            body = "/system/bin/ip addr",
            slot = { raw, out -> raw.ipAddr = out },
        ),
        Spec(
            title = "路由表（ip route show table all）",
            body = "/system/bin/ip route show table all",
            slot = { raw, out -> raw.ipRoute = out },
        ),
        Spec(
            title = "策略路由（ip rule show）",
            body = "/system/bin/ip rule show",
            slot = { raw, out -> raw.ipRule = out },
        ),
        Spec(
            title = "Wi-Fi 服务转储（SoftAP 段）",
            body = grep("dumpsys wifi", WIFI_KEYWORDS),
            fallback = "dumpsys wifi",
            timeoutMillis = DUMP_TIMEOUT_MILLIS,
            slot = { raw, out -> raw.wifi = out },
        ),
        Spec(
            title = "网络共享服务转储（DHCP 段）",
            body = grep("dumpsys tethering", TETHERING_KEYWORDS),
            fallback = "dumpsys tethering",
            timeoutMillis = DUMP_TIMEOUT_MILLIS,
            slot = { raw, out -> raw.tethering = out },
        ),
        Spec(
            title = "连接服务转储（DNS / 接口属性）",
            body = grep("dumpsys connectivity", CONNECTIVITY_KEYWORDS),
            fallback = "dumpsys connectivity",
            timeoutMillis = DUMP_TIMEOUT_MILLIS,
            slot = { raw, out -> raw.connectivity = out },
        ),
        Spec(
            title = "无线网卡详情（iw dev）",
            body = "iw dev",
            slot = { raw, out -> raw.iwDev = out },
        ),
        Spec(
            title = "相关进程（hostapd / udhcpd / dnsmasq）",
            body = "/system/bin/ps -A | grep -i -E 'hostapd|udhcpd|dnsmasq|wpa_supplicant|adbd'",
            fallback = "/system/bin/ps -A",
            slot = { raw, out -> raw.processes = out },
        ),
        // ⚠️ 这一步的名字是**愿望**，不是保证。2026-09-29 实车核实：
        // `/data/vendor/wifi/hostapd/hostapd.conf` 是**厂商没改过的 AOSP 模板**
        // （`#ssid=QualcommSoftAP` 还注释着、`ssid2` 是出厂默认 `AndroidShare_4682`、
        // `interface=wlan1` 而 `ip addr` 里**根本没有 wlan1**），运行中的 hostapd
        // 是 init 拉起的 pid 2724（用户 `wifi`），用的不是这个文件。
        // ⇒ **"网卡实际在广播什么名字"只认 `iw dev`（步骤 [13]）**，
        // 别拿这里读到的 SSID 当事实。
        Spec(
            title = "运行中的热点配置（hostapd.conf，含明文密码）",
            body = catMany(HOSTAPD_CONF_PATHS),
            needsRoot = true,
            compact = true,
            slot = { raw, out -> raw.hostapdConf = out },
        ),
        Spec(
            title = "持久化热点配置（WifiConfigStore.xml）",
            body = catMany(WIFI_STORE_PATHS),
            needsRoot = true,
            compact = true,
            slot = { raw, out -> raw.wifiStore = out },
        ),
        Spec(
            title = "旧式热点配置（softap.conf，含明文密码）",
            body = catMany(SOFTAP_CONF_PATHS),
            needsRoot = true,
            compact = true,
            slot = { raw, out -> raw.softapConf = out },
        ),
        Spec(
            title = "DHCP 池与租约（dnsmasq）",
            body = catMany(DNSMASQ_PATHS),
            needsRoot = true,
            compact = true,
            slot = { raw, out -> raw.dnsmasq = out },
        ),
        Spec(
            title = "监听端口（DHCP 67 / adb 5555）",
            body = "/system/bin/netstat -tlnp | grep -E ':(67|5555|5038)'",
            fallback = "/system/bin/netstat -tlnp",
            slot = { raw, out -> raw.netstat = out },
        ),
        // 2026-09-29 新增，刻意**排在最后**（避免把已有步骤编号整体挪位——
        // 记忆文档里按编号引用过 [13] / [15] / [19]）。
        //
        // 起因：`[15]` 读到的 `/data/vendor/wifi/hostapd/hostapd.conf` 是
        // **厂商没改过的 AOSP 模板**（`interface=wlan1` 而 `ip addr` 里没有 wlan1、
        // `#ssid=QualcommSoftAP` 还注释着、`ssid2` 是出厂默认 `AndroidShare_4682`），
        // 运行中的 hostapd（init 拉起）用的显然不是它。那么**到底用的哪份**？
        // 光靠猜不行 —— `/proc/<pid>/cmdline` 是唯一能直接问出来的地方，
        // 再配上配置目录的 mtime（模板文件的 mtime 会停在出厂日期）。
        Spec(
            title = "hostapd 真正在用的配置（启动参数 + 配置目录时间戳）",
            body = "for p in \$(pidof hostapd); do echo \"=== pid \$p ===\"; " +
                "echo \"--- cmdline（\\0 已换成空格）---\"; " +
                "tr '\\000' ' ' < /proc/\$p/cmdline; echo; done; " +
                "for d in /data/vendor/wifi/hostapd /data/misc/wifi; do " +
                "echo \"=== ls -l \$d ===\"; ls -l \$d 2>&1; done",
            fallback = "/system/bin/ps -A | grep -i hostapd",
            needsRoot = true,
            compact = true,
            slot = { raw, out -> raw.hostapdRuntime = out },
        ),
        // 2026-09-30 新增，同样**排在最后**（编号纪律见上一条）。
        //
        // 起因：上一条（`[20]`）用 `/proc/<pid>/cmdline` 问出了"运行中的 hostapd
        // 用的是哪份配置"，实车答案是
        // `/vendor/bin/hw/hostapd /data/softap.conf` 与
        // `/vendor/bin/hw/hostapd /vendor/etc/softap.conf` —— 而这两份**都不在**
        // `[15]` 的候选列表里（`[15]` 读的是厂商模板：`interface=wlan1`，
        // 而 `ip addr` 里根本没有 wlan1）。
        //
        // 也就是说：**"网卡实际在广播什么名字、密码是什么"这条链上最关键的文件，
        // 之前一个都没读过。** 2026-09-30 实车因此只能靠 `iw dev` 看到一个光秃秃的
        // `car`，回答不了"它是谁写的、凭据是什么"——而正是这个 `car`
        // 占着 Wi-Fi 网卡、让整条无线 CarPlay 起不来。
        //
        // ⚠️ 这里只**读并记录**，**不喂给** [parse]：`info.ssid` 必须保持
        // "车机**存储**配置"的语义（上游 `ManualHotspotManager` 拿它校验、
        // 并最终发进 `0x5703`）。把"运行配置"混进 `info.ssid` 会让发给 iPhone 的
        // 名字跟着变，那是另一条链的决定（见 `CarHotspotResolver` 对
        // `ssidMismatch` 的处理）。所以 `slot` 是空的。
        Spec(
            title = "运行中 hostapd 实际读的配置（cmdline 指到的文件）",
            body = catMany(RUNNING_HOSTAPD_CONF_PATHS),
            needsRoot = true,
            compact = true,
            slot = { _, _ -> },
        ),
    )

    // ------------------------------------------------------------ 解析

    /**
     * 把原始输出解析成结论。
     *
     * 解析是**尽力而为**：车机 ROM 的输出格式千差万别，任何一条正则都可能不匹配。
     * 不匹配时字段留空，由 [summarize] 如实报告"没读到"，
     * 而原始文本已经进了 `rawLog` 与 logcat，人眼可以接手。
     */
    private fun parse(raw: Raw, source: CarHotspotProbeSource): CarNativeHotspotInfo {
        val interfaces = parseInterfaces(raw.ipAddr)
        val uplink = parseDefaultRouteDev(raw.ipRoute)
        val seenAddresses = collectAddresses(interfaces, raw.ipRoute)
        val dumps = listOfNotNull(
            parseWifiDump(raw.wifi),
            parseHostapdConf(raw.hostapdConf),
            parseWifiStoreXml(raw.wifiStore),
            parseLegacySoftapConf(raw.softapConf),
        )
        val dump = dumps.firstOrNull { it.ssid != null } ?: dumps.firstOrNull()
        val passphrase = dumps.asSequence()
            .mapNotNull { it.passphrase }
            .firstOrNull { it.isNotBlank() && !looksEncrypted(it) }
            ?: dumps.asSequence().mapNotNull { it.passphrase }.firstOrNull()

        val explicitInterface = parseExplicitApInterface(raw.wifi, raw.tethering)
            ?: dumps.asSequence().mapNotNull { it.interfaceName }.firstOrNull()
        val apInterface = explicitInterface
            ?: pickApInterface(interfaces, uplink)
        val hostAddress = apInterface?.let { name ->
            interfaces.firstOrNull { it.name == name }?.addresses?.firstOrNull()?.substringBefore('/')
        }
        val prefixLength = apInterface?.let { name ->
            interfaces.firstOrNull { it.name == name }
                ?.addresses?.firstOrNull()?.substringAfter('/', "")?.toIntOrNull()
        }
        val subnet = if (hostAddress != null && prefixLength != null) {
            CarHotspotSubnet.networkOf(hostAddress, prefixLength)
        } else {
            null
        }
        val dhcp = parseDhcpRange(raw.tethering) ?: parseDhcpRange(raw.dnsmasq)
        val enabled = parseSoftApEnabled(raw.wifi, raw.tethering)
            ?: (hostAddress != null && apInterface != null)
        val channel = dump?.channel?.takeIf { it > 0 }
            ?: parseIwChannel(raw.iwDev, apInterface)
            ?: 0
        val band = dump?.band ?: bandOfChannel(channel)

        // 网卡**实际在广播**的 SSID。与 `dump?.ssid`（存储配置）分开记：
        // 两者可以不一致，而不一致时发给 iPhone 的名字注定连不上（见
        // `CarNativeHotspotInfo.ssidMismatch`）。要求 `type AP`，免得把
        // STA 网卡的**上行**网络名当成热点名。
        val runningSsid = CarIwDevOutput.apSsid(raw.iwDev, apInterface)

        val detected = enabled && (dump?.ssid != null || apInterface != null)
        return CarNativeHotspotInfo(
            detected = detected,
            enabled = enabled,
            ssid = dump?.ssid,
            runningSsid = runningSsid,
            interfaceName = apInterface,
            hostAddress = hostAddress,
            prefixLength = prefixLength,
            subnet = subnet,
            // 网关：AP 场景下客户端把 AP 自己当默认网关，所以等于 hostAddress。
            // 这是**定义**而不是读到的值；读不到 hostAddress 就留空。
            gateway = hostAddress,
            dhcpStart = dhcp?.first,
            dhcpEnd = dhcp?.second,
            dns = parseDns(raw.properties, raw.connectivity),
            occupiedAddresses = seenAddresses.sorted(),
            band = band,
            channel = channel,
            security = dump?.security ?: CarHotspotSecurity.WPA2,
            passphrase = passphrase,
            source = if (detected || enabled) source else CarHotspotProbeSource.NONE,
        )
    }

    /**
     * 合并 ADB 侧与本地反射侧的结果。
     *
     * 逻辑本体在 `:car:api` 的 [CarNativeHotspotMerge]（纯函数、可单测）——
     * 这段取值顺序（`ssid` 存储配置优先于实际广播名、`source` 与
     * [CarNativeHotspotInfo.credentialSource] 分开记）都是踩过坑才定下来的，
     * 放在这个需要 `Context` 的类里就永远没有用例兜着。
     */
    private fun merge(adb: CarNativeHotspotInfo, local: CarNativeHotspotInfo): CarNativeHotspotInfo =
        CarNativeHotspotMerge.merge(adb, local)

    // ---- ip addr / ip route

    private class Iface(val name: String, val addresses: List<String>)

    private fun parseInterfaces(ipAddr: String): List<Iface> {
        val result = mutableListOf<Iface>()
        var name: String? = null
        var addresses = mutableListOf<String>()

        fun flush() {
            val current = name ?: return
            result += Iface(current, addresses.toList())
        }

        ipAddr.lineSequence().forEach { line ->
            val header = IFACE_HEADER_RE.find(line)
            if (header != null) {
                flush()
                name = header.groupValues[1]
                addresses = mutableListOf()
            } else {
                val inet = INET_RE.find(line.trim())
                if (inet != null) addresses += inet.groupValues[1]
            }
        }
        flush()
        return result
    }

    private fun parseDefaultRouteDev(ipRoute: String): String? =
        ipRoute.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("default") }
            ?.let { DEV_RE.find(it)?.groupValues?.get(1) }

    /** 收集本机所有出现过的 IPv4，用于判断候选网段是否已被占用。 */
    private fun collectAddresses(interfaces: List<Iface>, ipRoute: String): Set<String> {
        val addresses = mutableSetOf<String>()
        interfaces.forEach { iface ->
            iface.addresses.forEach { addresses += it.substringBefore('/') }
        }
        IPV4_RE.findAll(ipRoute).forEach { addresses += it.value }
        return addresses
    }

    /**
     * 挑一张"像 AP"的网卡。
     *
     * 判据：**Wi-Fi 系网卡名** + 有 IPv4 + 不是默认路由网卡（那是上行）。
     *
     * ⚠️ 刻意**不**排除 `wlan0`。老代码写的是 `wlan* 且 != wlan0`，那是错的：
     * 部分车机的 AP 网卡就叫 `wlan0`，STA 侧叫别的。正确的判据是"是不是上行"。
     */
    private fun pickApInterface(interfaces: List<Iface>, uplink: String?): String? =
        interfaces.asSequence()
            .filter { looksLikeWifiInterface(it.name) }
            .filter { it.name != uplink }
            .firstOrNull { it.addresses.isNotEmpty() }
            ?.name

    private fun looksLikeWifiInterface(name: String): Boolean =
        name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("softap")

    // ---- dumpsys wifi

    private class Dump(
        val ssid: String? = null,
        val passphrase: String? = null,
        val band: CarHotspotBand? = null,
        val channel: Int? = null,
        val security: CarHotspotSecurity? = null,
        val interfaceName: String? = null,
    )

    /**
     * 从 `dumpsys wifi` 里抠 SoftAP 配置。
     *
     * 主要目标是 `SoftApConfiguration{ssid=…, passphrase=…, band=…, channel=…,
     * securityType=…}` 这一段——它是 `SoftApConfiguration.toString()` 的输出，
     * 在 Android 11+ 的 `dumpsys wifi` 里普遍存在。
     */
    private fun parseWifiDump(wifi: String): Dump? {
        if (wifi.isBlank()) return null
        val blocks = SOFTAP_CONFIG_RE.findAll(wifi).map { it.groupValues[1] }.toList()
        val withSsid = blocks.firstOrNull { field(it, "ssid") != null }
        val block = withSsid ?: blocks.firstOrNull()

        val ssid = block?.let { field(it, "ssid") }
        val passphrase = block?.let { field(it, "passphrase") }
        val bandRaw = block?.let { field(it, "band") }?.toIntOrNull()
        val channel = block?.let { field(it, "channel") }?.toIntOrNull()
        val securityRaw = block?.let { field(it, "securityType") }?.toIntOrNull()

        if (ssid == null && passphrase == null) return null
        return Dump(
            ssid = ssid,
            passphrase = passphrase,
            band = bandRaw?.let(::bandOfInt),
            channel = channel,
            security = securityRaw?.let(::securityOfInt),
        )
    }

    /**
     * 从 dumpsys 里抠出**明确的** AP 网卡名。
     *
     * ⚠️ 只认 SoftAP 专用的键（`apInterfaceName` / `mApInterfaceName` /
     * `SoftApInterfaceName`）。**绝不能**用泛化的 `interfaceName=`：
     * `dumpsys wifi` 里到处是 `interfaceName`（STA 侧、p2p 侧都有），
     * 拿错就会把 STA 网卡当成 AP，之后所有地址判断全错。
     */
    private fun parseExplicitApInterface(wifi: String, tethering: String): String? =
        SOFTAP_IFACE_RE.find("$wifi\n$tethering")?.groupValues?.get(1)

    /**
     * 热点是否处于开启状态。
     *
     * 试几种常见写法；全都匹配不到时返回 null（由网卡判据兜底）。
     */
    private fun parseSoftApEnabled(wifi: String, tethering: String): Boolean? {
        val text = "$wifi\n$tethering"
        if (text.isBlank()) return null
        val match = SOFTAP_STATE_RE.find(text)?.groupValues?.get(2) ?: return null
        val normalized = match.uppercase()
        return normalized.contains("ENABLED") ||
            normalized.contains("STARTED") ||
            normalized.contains("ACTIVE") ||
            normalized == "ON"
    }

    // ---- 配置文件

    /**
     * 运行中的 hostapd 配置。
     *
     * ⚠️ **这是拿到明文密码的最可靠来源**：hostapd 需要明文 `wpa_passphrase`
     * 才能算出 PMK，所以文件里存的是明文（而 `WifiConfigStore.xml` 里是密文）。
     */
    private fun parseHostapdConf(text: String): Dump? {
        if (text.isBlank() || text.contains("No such file")) return null
        val ssid = confValue(text, "ssid")
        val passphrase = confValue(text, "wpa_passphrase")
        val iface = confValue(text, "interface")
        val channel = confValue(text, "channel")?.toIntOrNull()
        val hwMode = confValue(text, "hw_mode")
        if (ssid == null && passphrase == null && iface == null) return null
        return Dump(
            ssid = ssid,
            passphrase = passphrase,
            band = when (hwMode) {
                "a" -> CarHotspotBand.GHZ_5
                "b", "g" -> CarHotspotBand.GHZ_2_4
                else -> null
            },
            channel = channel,
            security = when {
                passphrase.isNullOrEmpty() -> CarHotspotSecurity.OPEN
                else -> CarHotspotSecurity.WPA2
            },
            interfaceName = iface,
        )
    }

    /**
     * `WifiConfigStore.xml` 里的 `<SoftAp>` 段。
     *
     * SSID 是明文；`Passphrase` 在 Android 10+ 是**密文**（`WifiConfigStoreEncryptionUtil`
     * 用 keystore 里的密钥加密），所以这里拿到的多半是不能直接用的字符串——
     * 由 [looksEncrypted] 识别出来并降级使用。
     */
    private fun parseWifiStoreXml(text: String): Dump? {
        if (text.isBlank() || text.contains("No such file")) return null
        val ssid = xmlString(text, "Ssid") ?: xmlString(text, "SSID")
        val passphrase = xmlString(text, "Passphrase") ?: xmlString(text, "PreSharedKey")
        val channel = xmlInt(text, "Channel")
        val band = xmlInt(text, "ApBand")
        val security = xmlInt(text, "SecurityType")
        if (ssid == null && passphrase == null) return null
        return Dump(
            ssid = ssid,
            passphrase = passphrase,
            band = band?.let(::bandOfInt),
            channel = channel,
            security = security?.let(::securityOfInt),
        )
    }

    /**
     * 老式 `/data/misc/wifi/softap.conf`。
     *
     * 格式（第一行是版本号）：
     * ```
     * 2
     * ssid=AndroidAP
     * key=12345678
     * channel=6
     * ```
     */
    private fun parseLegacySoftapConf(text: String): Dump? {
        if (text.isBlank() || text.contains("No such file")) return null
        val ssid = confValue(text, "ssid")
        val key = confValue(text, "key")
        val channel = confValue(text, "channel")?.toIntOrNull()
        val band = confValue(text, "ap_band")?.toIntOrNull()
        if (ssid == null && key == null) return null
        return Dump(
            ssid = ssid,
            passphrase = key,
            band = band?.let(::bandOfInt),
            channel = channel,
            security = when {
                key.isNullOrEmpty() -> CarHotspotSecurity.OPEN
                else -> CarHotspotSecurity.WPA2
            },
        )
    }

    // ---- 其他

    /**
     * DHCP 地址池。
     *
     * 两种写法都认：
     * - `dumpsys tethering` → `mDhcpRange: 192.168.43.2-192.168.43.254`
     * - `dnsmasq.conf` → `dhcp-range=192.168.43.2,192.168.43.254,12h`
     */
    private fun parseDhcpRange(text: String): Pair<String, String>? {
        if (text.isBlank()) return null
        DHCP_RANGE_DASH_RE.find(text)?.let {
            return it.groupValues[1] to it.groupValues[2]
        }
        DHCP_RANGE_COMMA_RE.find(text)?.let {
            return it.groupValues[1] to it.groupValues[2]
        }
        return null
    }

    private fun parseDns(properties: String, connectivity: String): List<String> {
        val found = mutableSetOf<String>()
        properties.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("net.dns") }
            .forEach { line ->
                IPV4_RE.find(line.substringAfter('=', ""))?.let { found += it.value }
            }
        connectivity.lineSequence()
            .map { it.trim() }
            .filter { it.contains("Dns", ignoreCase = true) }
            .forEach { line -> IPV4_RE.findAll(line).forEach { found += it.value } }
        return found.toList()
    }

    /** `iw dev` → `channel 36 (5180 MHz)`。 */
    private fun parseIwChannel(iwDev: String, iface: String?): Int? {
        if (iwDev.isBlank()) return null
        val section = if (iface == null) {
            iwDev
        } else {
            val start = iwDev.indexOf("Interface $iface")
            if (start < 0) iwDev else iwDev.substring(start)
        }
        return IW_CHANNEL_RE.find(section)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun bandOfChannel(channel: Int): CarHotspotBand = when {
        channel <= 0 -> CarHotspotBand.AUTO
        channel <= 14 -> CarHotspotBand.GHZ_2_4
        else -> CarHotspotBand.GHZ_5
    }

    /** Android 的 band 常量：1 = 2.4GHz、2 = 5GHz、3 = 6GHz。 */
    private fun bandOfInt(value: Int): CarHotspotBand = when (value) {
        1 -> CarHotspotBand.GHZ_2_4
        2 -> CarHotspotBand.GHZ_5
        else -> CarHotspotBand.AUTO
    }

    /** Android 的 `SoftApConfiguration` 加密类型常量。 */
    private fun securityOfInt(value: Int): CarHotspotSecurity = when (value) {
        0 -> CarHotspotSecurity.OPEN
        1 -> CarHotspotSecurity.WPA2
        2 -> CarHotspotSecurity.WPA3_TRANSITION
        3 -> CarHotspotSecurity.WPA3
        else -> CarHotspotSecurity.WPA2
    }

    /**
     * 密文识别。
     *
     * Android 10+ 把 `Passphrase` 加密后 base64 存在 `WifiConfigStore.xml` 里，
     * 长度远超 WPA 密码上限（63）且带 `=` 填充。直接把它当密码交给 hostapd
     * 会得到一个"密码错了"的谜题，所以在 [parse] 里优先选明文。
     */
    private fun looksEncrypted(value: String): Boolean =
        value.length > 63 || (value.length > 24 && value.endsWith("="))

    /** 从 `key=value` 形式的文本里取值（hostapd.conf / softap.conf 共用）。 */
    private fun confValue(text: String, key: String): String? =
        Regex("^\\s*" + Regex.escape(key) + "\\s*=\\s*(.+?)\\s*$", RegexOption.MULTILINE)
            .find(text)
            ?.groupValues?.get(1)
            ?.trim()
            ?.trim('"', '\'')
            ?.takeIf { it.isNotEmpty() }

    /** 从 `SoftApConfiguration{}` 那种 `key=value, key=value` 里取值。 */
    private fun field(text: String, key: String): String? =
        Regex("(?:^|[\\s,{])" + Regex.escape(key) + "=('?)([^,'}\\n]*)\\1")
            .find(text)
            ?.groupValues?.get(2)
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it != "null" }

    /** `WifiConfigStore.xml` 的 `<string name="X">value</string>`。 */
    private fun xmlString(text: String, name: String): String? =
        Regex("<string name=\"" + Regex.escape(name) + "\">([^<]*)</string>")
            .find(text)
            ?.groupValues?.get(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    /** `WifiConfigStore.xml` 的 `<int name="X" value="N" />`。 */
    private fun xmlInt(text: String, name: String): Int? =
        Regex("<int name=\"" + Regex.escape(name) + "\" value=\"(-?\\d+)\"")
            .find(text)
            ?.groupValues?.get(1)
            ?.toIntOrNull()

    // ------------------------------------------------------------ 输出

    private fun summarize(info: CarNativeHotspotInfo): String = buildString {
        if (!info.detected && !info.enabled) {
            append("未探测到正在运行的车机热点")
            return@buildString
        }
        append(info.label)
        val details = buildList {
            info.interfaceName?.let { add("网卡 $it") }
            if (info.hostAddress != null) {
                val suffix = info.prefixLength?.let { "/$it" }.orEmpty()
                add("${info.hostAddress}$suffix")
            }
            if (info.channel > 0) add("信道 ${info.channel}")
            if (info.passphrase != null) add("已读到密码") else add("密码未读到")
        }
        if (details.isNotEmpty()) append("：").append(details.joinToString("、"))
        // 凭据不是从 source 那条通道读到的就说出来 —— 否则读包的人会把磁盘上
        // 那份过期的配置文件当成车机热点名（2026-09-29 实车踩过）。
        info.credentialSourceNote?.let { append(" ").append(it) }
    }

    private fun describe(info: CarNativeHotspotInfo): String = buildString {
        append("detected=").append(info.detected)
        append(" enabled=").append(info.enabled)
        append(" ssid=").append(info.ssid ?: "null")
        append(" iface=").append(info.interfaceName ?: "null")
        append(" addr=").append(info.hostAddress ?: "null")
        append(" channel=").append(info.channel)
        append(" passphrase=").append(if (info.passphrase == null) "null" else "已读到")
    }

    /** 界面展示用：超长裁剪，但**明确标注被裁过**，避免误以为输出就这么多。 */
    private fun clipForDisplay(output: String): String {
        val trimmed = output.trim()
        if (trimmed.length <= CarHotspotProbeEntry.DISPLAY_LIMIT) return trimmed
        return trimmed.take(CarHotspotProbeEntry.DISPLAY_LIMIT) +
            "\n…（本条已裁剪到 ${CarHotspotProbeEntry.DISPLAY_LIMIT} 字符，完整内容见原始日志）"
    }

    /** 原始日志用：上限更宽，但仍然要有上限（`dumpsys wifi` 能到几十万字符）。 */
    private fun clipForRaw(output: String): String {
        val trimmed = output.trim()
        if (trimmed.length <= RAW_PER_COMMAND_LIMIT) return trimmed
        return trimmed.take(RAW_PER_COMMAND_LIMIT) +
            "\n…（本条原始输出超过 $RAW_PER_COMMAND_LIMIT 字符，已裁剪）"
    }

    /**
     * 把一条命令的输出写进 logcat。
     *
     * 分块是因为 **logcat 单行上限约 4000 字符**，超了会被系统截断成多行或丢弃。
     * 按 3000 一块自己切，至少保证每一块都是完整可读的。
     */
    private fun logChunked(index: Int, title: String, command: String, output: String) {
        val header = "[$index] $title" + if (command.isEmpty()) "" else "  ← $command"
        val body = clipForDisplay(output)
        Log.i(TAG, header)
        if (body.isEmpty()) {
            Log.i(TAG, "[$index] （无输出）")
            return
        }
        var offset = 0
        var part = 1
        while (offset < body.length) {
            val end = minOf(offset + LOGCAT_CHUNK, body.length)
            val suffix = if (end < body.length) " ⟶第 $part 块" else ""
            Log.i(TAG, "[$index/$part] ${body.substring(offset, end)}$suffix")
            offset = end
            part += 1
        }
    }

    private fun publish(
        onState: ((CarNativeHotspotProbeState) -> Unit)?,
        transform: (CarNativeHotspotProbeState) -> CarNativeHotspotProbeState,
    ) {
        val next = synchronized(lock) {
            snapshot = transform(snapshot)
            snapshot
        }
        if (onState == null) return
        mainHandler.post { onState(next) }
    }

    // ------------------------------------------------------------ 命令构造

    /** `dumpsys X | grep -i -E 'kw' | head -n N`。 */
    private fun grep(dump: String, keywords: String, lines: Int = GREP_HEAD_LINES): String =
        "$dump | grep -i -E '$keywords' | head -n $lines"

    /**
     * 逐个 `cat` 一批候选路径，每条前面打一个 `=== 路径 ===` 分隔。
     *
     * 一次 shell 往返问完所有候选（每个文件一次往返会让探测慢到不可用）。
     * 末尾的 `true` 让整条命令返回 0——否则"最后一个文件不存在"会把这一步
     * 记成失败，而它其实已经成功读到了前面的文件。
     */
    /**
     * 逐个 `cat` 一批候选路径，每条前面打一个 `=== 路径 ===` 分隔。
     *
     * ⚠️ `cat` 之后必须补一个 `echo`：有些配置文件**结尾没有换行**
     * （实车 `/data/misc/wifi/softap.conf` 就是），不补的话下一个
     * `=== 路径 ===` 会跟上一个文件的末尾挤在同一行 ——
     * 归属当场串掉，而"这行来自哪个文件"正是 `catMany` 唯一的产出。
     */
    private fun catMany(paths: List<String>): String {
        val list = paths.joinToString(separator = " ")
        return "for f in $list; do echo \"=== \$f ===\"; " +
            "cat \"\$f\" 2>&1 || echo \"(读取失败)\"; echo; done; true"
    }

    /**
     * 主命令要不要改走兜底。
     *
     * 三种情况都算"主命令没拿到东西"：退出码非 0、输出为空、
     * 或者管道里的 `grep` / `head` 在车机上不存在（会打印 `xxx: not found`）。
     */
    private fun needsFallback(result: AdbShellRunner.CommandResult): Boolean =
        !result.ok || result.output.isBlank() || result.output.contains("not found")

    private companion object {
        /** logcat 标签。与其它模块一致用 `XCExt.` 前缀，方便一次 `grep` 抓全。 */
        const val TAG = "XCExt.hotspot.probe"

        const val DEFAULT_SHELL_TIMEOUT_MILLIS = 15_000L

        /** `dumpsys` 在慢车机上可能超过 15 秒，单独放宽。 */
        const val DUMP_TIMEOUT_MILLIS = 30_000L

        const val GREP_HEAD_LINES = 400

        const val LOGCAT_CHUNK = 3000

        /** 单条命令进原始日志的上限。 */
        const val RAW_PER_COMMAND_LIMIT = 20_000

        /** 整份原始日志的上限。 */
        const val RAW_LOG_LIMIT = 300_000

        // ---- grep 关键字

        const val WIFI_KEYWORDS =
            "SoftApConfiguration|SoftApState|SoftApManager|SoftApInterface|" +
                "apInterfaceName|mApInterface|WifiApConfigStore|hostapd|tether|" +
                "isWifiApEnabled|ApConfiguration|softap0"

        const val TETHERING_KEYWORDS =
            "DhcpServer|mDhcpRange|DhcpRange|SoftApInterface|InterfaceName|" +
                "Upstream|upstream|TetherState|dhcp"

        const val CONNECTIVITY_KEYWORDS =
            "DnsAddresses|DnsServers|InterfaceName|LinkAddresses|Routes|" +
                "Tethering|SoftAp"

        // ---- 设备上的候选路径

        val HOSTAPD_CONF_PATHS = listOf(
            "/data/vendor/wifi/hostapd/hostapd.conf",
            "/data/misc/wifi/hostapd.conf",
            "/data/vendor/wifi/wpa/hostapd.conf",
            "/data/misc/wifi/wpa/hostapd.conf",
            "/data/hostapd/hostapd.conf",
            "/data/local/tmp/hostapd.conf",
        )

        val WIFI_STORE_PATHS = listOf(
            "/data/misc/apexdata/com.android.wifi/WifiConfigStore.xml",
            "/data/misc/wifi/WifiConfigStore.xml",
            "/data/vendor/wifi/WifiConfigStore.xml",
        )

        val SOFTAP_CONF_PATHS = listOf(
            "/data/misc/wifi/softap.conf",
            "/data/vendor/wifi/softap.conf",
            "/data/misc/wifi/softap/softap.conf",
        )

        val DNSMASQ_PATHS = listOf(
            "/data/misc/dhcp/dnsmasq.conf",
            "/data/misc/dhcp/dnsmasq.leases",
            "/data/misc/dhcp/dhcpd.conf",
        )

        /**
         * **运行中的 hostapd 真正在读的**那几份配置。
         *
         * 取值依据不是"常见路径"，而是实车 `ps -A` + `/proc/<pid>/cmdline` 的原文
         * （见 `[20]`）：
         * - `/data/softap.conf` —— 那个占着 `wlan0`、广播 `car` 的 hostapd 用的；
         * - `/vendor/etc/softap.conf` —— init 拉起的那个 hostapd 用的；
         * - `/data/misc/wifi/Tcamsoftap.conf` —— 厂商 `tcam` Wi-Fi shim 的配置
         *   （实车 mtime 恰好在热点启动那一分钟，32 字节，很像 `ssid=…` 那一行）。
         *
         * ⚠️ 读它们**只为了回答"谁在广播什么名字"**，不参与 [parse]（见调用点的注释）。
         */
        val RUNNING_HOSTAPD_CONF_PATHS = listOf(
            "/data/softap.conf",
            "/vendor/etc/softap.conf",
            "/data/misc/wifi/Tcamsoftap.conf",
        )

        // ---- 正则

        private val IFACE_HEADER_RE = Regex("^\\d+:\\s+(\\S+):")
        private val INET_RE = Regex("^inet\\s+(\\d+\\.\\d+\\.\\d+\\.\\d+/\\d+)")
        private val DEV_RE = Regex("\\bdev\\s+(\\S+)")
        private val IPV4_RE = Regex("\\d{1,3}(?:\\.\\d{1,3}){3}")
        private val SOFTAP_CONFIG_RE = Regex("SoftApConfiguration\\{([^}]*)\\}")
        private val SOFTAP_IFACE_RE = Regex(
            "(?i)(?:apInterfaceName|mApInterfaceName|SoftApInterfaceName)" +
                "\\s*[:=]\\s*\"?([A-Za-z][\\w.]*)\"?",
        )
        private val SOFTAP_STATE_RE = Regex(
            "(?i)(softap[A-Za-z]*|ap)[A-Za-z]*state\\s*[:=]\\s*[\"']?(\\w+)",
        )
        private val DHCP_RANGE_DASH_RE = Regex(
            "(\\d{1,3}(?:\\.\\d{1,3}){3})\\s*-\\s*(\\d{1,3}(?:\\.\\d{1,3}){3})",
        )
        private val DHCP_RANGE_COMMA_RE = Regex(
            "dhcp-range\\s*=\\s*(\\d{1,3}(?:\\.\\d{1,3}){3})\\s*,\\s*(\\d{1,3}(?:\\.\\d{1,3}){3})",
            RegexOption.IGNORE_CASE,
        )
        private val IW_CHANNEL_RE = Regex("channel\\s+(\\d+)\\s*\\(")
    }
}
