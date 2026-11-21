package com.dbtool.carplay.api.hotspot

import com.dbtool.carplay.api.settings.CarHotspotBand
import com.dbtool.carplay.api.settings.CarHotspotSecurity

/**
 * 一条探测事实的**来源**。
 *
 * 为什么要区分来源而不是只给结论：同一台车机上，"ADB 读到的"与
 * "本进程反射读到的"可信度差一个量级。前者能看见 `dumpsys`、路由表、
 * 配置文件；后者受厂商裁剪与权限限制，经常只能看到一个被抹掉的配置对象。
 * 排障时必须知道手上这个结论是哪种。
 */
enum class CarHotspotProbeSource {
    /** 经随包 adb 拿到 shell 后读到的（最权威）。 */
    ADB_SHELL,

    /** 本进程反射 `WifiManager` 读到的（ADB 不可用时的次选）。 */
    LOCAL_REFLECTION,

    /** 什么都没读到。 */
    NONE,
}

/**
 * 车机**原生热点**的实际配置。
 *
 * ## 为什么字段几乎全是可空的
 *
 * 这份数据的用途是"**如实报告这台车机现在长什么样**"，不是"猜一个能用的值"。
 * 车机 ROM 差异极大（有的把 SoftAP 配置放进 `dumpsys wifi`、有的只有
 * `/data/misc/wifi/softap.conf`、有的两者都没有），任何一项读不到都是正常情况。
 * 因此：
 *
 * - 读不到就留 `null` / 空，**绝不用默认值填坑**。填了坑，日志与界面就会把
 *   "没读到"伪装成"读到了 192.168.43.1"，排障时反而更慢；
 * - [detected] 只表达"**是否找到一张正在运行的热点**"，与字段完整度无关。
 *
 * ## 各字段最可靠的来源（按此顺序找）
 *
 * | 字段 | 来源 |
 * |------|------|
 * | [enabled] | `dumpsys wifi` 的 SoftAp 段；退 `ip addr` 里 AP 网卡有 IPv4 |
 * | [ssid] | `dumpsys wifi` 的 `SoftApConfiguration{}`；退 `WifiConfigStore.xml` 的 `<Ssid>` |
 * | [runningSsid] | `iw dev` 里该网卡（**且 `type AP`**）的 `ssid` 行 —— 唯一能反映"实际在广播什么"的来源 |
 * | [passphrase] | 同上，但 Android 10+ 存的是**密文** ⇒ 多半为 null，如实报告 |
 * | [interfaceName] | `ip addr` 里"有 IPv4 且不是默认路由网卡"的那张 |
 * | [hostAddress] / [prefixLength] / [subnet] | `ip addr` + `ip route` |
 * | [dhcpStart] / [dhcpEnd] | `dumpsys tethering` 的 DHCP server 段 |
 * | [dns] | `getprop net.dns*` / `dumpsys connectivity` |
 * | [channel] / [band] | `dumpsys wifi` / `iw dev` |
 */
data class CarNativeHotspotInfo(
    /** 是否探测到一张**正在运行**的车机热点。 */
    val detected: Boolean = false,

    /** 车机 SoftAP 开关是否处于打开状态。 */
    val enabled: Boolean = false,

    /**
     * **存储配置**里的 SSID —— 也就是上游 `ManualHotspotManager` 会拿去校验、
     * 并最终发进 `0x5703` 给 iPhone 的那个名字。
     *
     * ⚠️ 它**未必等于网卡实际在广播的名字**，见 [runningSsid]。
     */
    val ssid: String? = null,

    /**
     * 网卡**实际在广播**的 SSID（`iw dev` 读到的，仅当该网卡处于 AP 模式）。
     *
     * 与 [ssid] 分开记的理由：车机上这两者可以不一致。`hostapd` 只在启动时读一次
     * 配置，之后改 SSID 不会让它重启 —— 于是"存储配置说 `Lynk`、实际广播 `car`"
     * 这种状态会一直存在，而 iPhone 只能连**实际在广播**的那个名字。
     * 只记一个值，就会把"发出去的名字没人广播"这种必然失败伪装成一切正常。
     *
     * null = 没读到（`iw dev` 里没有该网卡、或该网卡不在 AP 模式）。
     */
    val runningSsid: String? = null,

    /** AP 网卡名，如 `wlan0` / `wlan2` / `ap0`。 */
    val interfaceName: String? = null,

    /** AP 网卡自己的 IPv4，也就是 iPhone 要连的 AirPlay 主机地址。 */
    val hostAddress: String? = null,

    val prefixLength: Int? = null,

    /** 形如 `192.168.43.0/24`。 */
    val subnet: String? = null,

    /** 默认网关。通常等于 [hostAddress]，但**不假定**——如实读、如实报。 */
    val gateway: String? = null,

    val dhcpStart: String? = null,

    val dhcpEnd: String? = null,

    val dns: List<String> = emptyList(),

    /**
     * 本机**出现过的全部 IPv4**（各网卡地址 + 路由表里出现的地址）。
     *
     * 用途只有一个：**自建热点选网段时避开冲突**。
     * 车机上常见 `10.x`（蜂窝）、`192.168.43.x`（Android 自带热点）、
     * 厂商自定义段；直接照抄参考实现的 `192.168.175.0/24` 有可能撞上，
     * 撞上之后 `ip rule` / 路由会互相打架，表现为"热点起来了但 iPhone 连不上"。
     */
    val occupiedAddresses: List<String> = emptyList(),

    val band: CarHotspotBand = CarHotspotBand.AUTO,

    val channel: Int = 0,

    val security: CarHotspotSecurity = CarHotspotSecurity.WPA2,

    val passphrase: String? = null,

    val source: CarHotspotProbeSource = CarHotspotProbeSource.NONE,

    /**
     * **SSID 与密码这两个凭据**是从哪条通道读到的。
     *
     * 与 [source] 分开记的理由（2026-09-29 实车踩到，而且**当场把我自己误导过一次**）：
     * [source] 说的是"这次结论整体来自哪条通道"，而 `merge` 里 SSID 是
     * `adb.ssid ?: local.ssid ?: …` 取的 —— 实车上 ADB 侧那几份配置文件**全是过期的**
     * （`hostapd.conf` 是厂商模板、`softap.conf` 是老早的、`WifiConfigStore.xml` 里
     * 根本没有 SoftAp 段），真正给出 `Lynk` 的是**本地反射**的 `getSoftApConfiguration()`；
     * 而 [source] 照样写 `ADB_SHELL`（因为网卡名、地址确实是 adb 读的）
     * ⇒ **标签在撒谎**：读包的人会把磁盘上那份过期的 `LYNK&CO` 当成车机热点名。
     *
     * 默认等于 [source]（不传就是"同一来源"，不制造假信息）。
     */
    val credentialSource: CarHotspotProbeSource = source,
) {

    /** 一句话结论，直接进设置页「本次将使用」与诊断日志。 */
    val label: String
        get() = when {
            !detected -> "未探测到车机热点"
            ssid != null -> "车机热点「$ssid」"
            interfaceName != null -> "车机热点（网卡 $interfaceName）"
            else -> "车机热点（未读到名称）"
        }

    /** 是否有足够信息把这张热点交给上游 attach。 */
    val reusable: Boolean get() = detected && !ssid.isNullOrEmpty()

    /**
     * **这张热点是不是"真的在跑"** —— 判据比 [reusable] 更严。
     *
     * ⚠️ 2026-10-03 实车（CS11）补。现场日志（设备 d5ddb4312da1，16:12）：
     * ```
     * detected=true enabled=true ssid=Lynk iface=null addr=null channel=0
     * ```
     * `iface`/`addr` 全空 = **没有任何网卡在承载这个 SSID**（热点没起，
     * 只是配置文件里存着这个名字）。而 [reusable] 只看 `detected + ssid`，
     * 把这一行判成了"可复用" ⇒ 上游 `ManualHotspotManager` 找不到 AP 接口，
     * 退回按名字挑网卡，最终抓了有线口 `eth0.3`（`host=198.18.34.15`）通告给
     * iPhone ⇒ 走 Wi-Fi 的 iPhone 永远到不了 ⇒ 卡在「无线通道就绪」。
     *
     * ⇒ 决策时应以本判据为准（见 `CarHotspotResolver.resolve()`）：
     * 它为 false 就走自建，真的起一张 AP，而不是复用一张不存在的热点。
     *
     * [reusable] 保持原语义不动（"信息够 attach"，有单测钉着），
     * 两者的差别只用在决策分支上，不改变数据类自身的公共约定。
     */
    val actuallyRunning: Boolean
        get() = reusable && !interfaceName.isNullOrEmpty() && !hostAddress.isNullOrEmpty()

    /**
     * **复用它不会注定失败** —— 这才是"该不该走复用分支"的判据。
     *
     * 与 [reusable] 的区别只在 [ssidMismatch] 一项，但那一项是致命的：
     * 复用时发给 iPhone 的 SSID 只能是 [ssid]（**存储配置名**）——上游
     * `ManualHotspotManager` 会拿它跟 `getWifiApConfiguration()` 硬校验，
     * 传实际广播名会被直接拒绝（实车原文：
     * `Manual hotspot SSID does not match the active local AP configuration: 'Lynk'`）。
     *
     * 所以当"存储配置名 ≠ 实际广播名"时，复用等于**把一个没人广播的名字发给
     * iPhone** ⇒ 永远关联不上 ⇒ iAP2 无线隧道起不来 ⇒ 界面停在「无线通道就绪」，
     * 而整条链路上没有一句报错（2026-09-30 实车）。
     *
     * 这种时候正确做法是**改走自建**：我们起一张 AP，让网卡真的广播这个名字。
     * 判定写在 `CarHotspotResolver.resolve()`，这里只提供判据（纯数据类，
     * 所以它可单测）。
     */
    val safeToReuse: Boolean get() = reusable && !ssidMismatch

    /**
     * **存储配置的 SSID 与网卡实际广播的 SSID 不一致。**
     *
     * 这是一个**必然失败**的状态：我们只会把 [ssid]（存储配置）发进 `0x5703`，
     * 而 iPhone 只能关联实际在广播的那个名字。两边不一致 ⇒ iPhone 找不到网络
     * ⇒ iAP2 无线隧道永远起不来 ⇒ 界面停在「无线通道就绪」，
     * **而整条链路上不会有任何一句报错**。
     *
     * 两个值都读到时才能判定；只读到一个时不算不一致（不能凭缺失下结论）。
     */
    val ssidMismatch: Boolean
        get() = !ssid.isNullOrBlank() && !runningSsid.isNullOrBlank() && ssid != runningSsid

    /**
     * 不一致时的一句人话，直接进设置页与诊断日志；一致或读不全时为 null。
     *
     * 写成"发出去的是哪个 / 实际广播的是哪个"两个值并列，而不是只说"不一致"
     * ——现场要据此决定是改设置还是重启车机热点。
     */
    val ssidMismatchNote: String?
        get() = if (ssidMismatch) {
            "⚠️ 车机热点的实际广播名与存储配置不一致：实际「$runningSsid」/ 配置「$ssid」" +
                " —— 发给 iPhone 的是配置名，而它没有被广播，iPhone 必然连不上"
        } else {
            null
        }

    /**
     * 凭据（SSID / 密码）不是从 [source] 那条通道读到的时的一句提醒；否则为 null。
     *
     * 不点名两条通道分别是谁 —— 日志行里 `source=` 与 `credentialSource=` 已经并列打出来了，
     * 这里只负责把**含义**说清楚：磁盘上那些配置文件很可能是过期的。
     */
    val credentialSourceNote: String?
        get() = if (credentialSource != source) {
            "⚠️ SSID/密码不是从 source 那条通道读到的（见 credentialSource）：" +
                "磁盘上的配置文件可能是过期的模板或旧值，别拿它当车机热点名"
        } else {
            null
        }
}

/**
 * 探测过程里的一条记录：**原始命令 + 原始输出**。
 *
 * 刻意保留原始文本而不是只留解析后的字段：车机 ROM 千差万别，解析规则一定
 * 会漏。把原文摊在日志与开发者页里，"解析失败"就还能靠人眼救回来。
 */
data class CarHotspotProbeEntry(
    /** 1 起的序号，与执行顺序一致。 */
    val index: Int,

    /** 人类可读的步骤名（如「网卡与地址（`ip addr`）」）。 */
    val title: String,

    /**
     * 实际执行的命令（含 `su 0 ` 前缀），便于在车机上原样复现。
     *
     * 空串表示这一步不是一条 shell 命令（例如"开通车机 ADB"是反射调用）。
     */
    val command: String,

    /**
     * 这一步是否**没有挡住流程**。
     *
     * ⚠️ 语义是"放行"而不是"一定有内容"：例如 `cat /data/misc/wifi/softap.conf`
     * 在大多数新车上都是 `No such file`，但那不代表探测失败——换个文件继续找就是了。
     */
    val ok: Boolean,

    /** 原始输出。超长命令（`dumpsys wifi`）会被裁剪到 [DISPLAY_LIMIT] 并标注。 */
    val output: String,
) {
    companion object {
        /**
         * 单条记录在界面上的展示上限（字符）。
         *
         * 取 4000 是因为最长的那条（`dumpsys wifi`）原始输出可达几十万字符，
         * 直接渲染会卡住 Compose；而排障真正要看的 `SoftApConfiguration{}` 段
         * 通常只有几百字符，4000 足够覆盖。**完整原文仍在
         * [CarNativeHotspotProbeState.rawLog] 里，没有被丢弃。**
         */
        const val DISPLAY_LIMIT: Int = 4000
    }
}

/**
 * 「ADB 探测车机原生热点」的可观测状态。
 *
 * 与 `CarAdbHotspotHost` 分开的理由同 `CarUpdateState`：`:car:ui` 不依赖
 * `:car:core-bridge`，界面只认这个纯数据类。
 */
data class CarNativeHotspotProbeState(
    val running: Boolean = false,

    /** 至少完整跑过一次。 */
    val finished: Boolean = false,

    /** 探测时是否真的拿到了 adb shell。false ⇒ 结论来自本地反射或完全没探到。 */
    val shellAvailable: Boolean = false,

    val info: CarNativeHotspotInfo = CarNativeHotspotInfo(),

    val entries: List<CarHotspotProbeEntry> = emptyList(),

    /** 一句话结论，进开发者页与诊断日志。 */
    val summary: String = "",

    /**
     * 完整原始日志（所有命令 + 输出，逐条分段）。
     *
     * 与 [entries] 的分工：`entries` 给界面渲染（每条有裁剪），
     * `rawLog` 给"整段复制/上报"用，**不做裁剪**。
     */
    val rawLog: String = "",
) {
    val isBusy: Boolean get() = running
}

/**
 * 「ADB 探测车机原生热点」的宿主。
 *
 * ## 线程约定（与 `CarAdbHotspotHost` 一致）
 *
 * 本工程全链路没有协程。[probe] 是**阻塞**的——要起 adb server、再跑十几条
 * shell 命令（含 `dumpsys wifi` / `dumpsys tethering`），实测数秒。
 * **只能在 worker 线程调用**；`onState` 回调由实现投递回主线程。
 * [state] / [cachedInfo] 可在任意线程读。
 *
 * ## 与 `CarAdbHotspotHost` 的关系
 *
 * 两者**共用同一把 shell**（同一个自建 adb server 与同一个序列号），
 * 但职责不同：本接口只**读**（探测现状），`CarAdbHotspotHost` 只**写**
 * （建一张属于 DBPlay 的热点）。顺序是"先读后写"：先探测车机有没有现成热点，
 * 没有再自建。
 */
interface CarNativeHotspotHost {

    fun state(): CarNativeHotspotProbeState

    /**
     * 跑一次探测。**只读**——不写系统属性、不改网卡、不起守护进程。
     *
     * ⚠️ 唯一的例外是"开通车机 ADB"：没有 ADB 就没有 shell，探测无从谈起。
     * 它写的是系统属性（`service.adb.tcp.port`），幂等且可逆，不属于破坏性操作。
     *
     * @return 探测结论。[CarNativeHotspotInfo.detected] 为 false 表示**没找到
     *         正在运行的车机热点**——这不是错误，调用方应当继续走自建分支。
     */
    fun probe(onState: (CarNativeHotspotProbeState) -> Unit): CarNativeHotspotInfo

    /**
     * [probe] 的**非阻塞**版本：内部提交到 worker 线程。供界面按钮调用。
     *
     * 与 `CarAdbHotspotHost.selfCheck` 同一套约定——界面永远不自己开线程。
     */
    fun requestProbe(onState: (CarNativeHotspotProbeState) -> Unit)

    /**
     * 最近一次探测结论。**没探测过时返回全空的 [CarNativeHotspotInfo]**。
     *
     * 与 [cachedInfo] 的区别：这个方法不看时限，只要探测过就给（可能已经很旧）。
     * 诊断日志用它可以写出"上次探测到了什么"，而不必触发一次新探测。
     */
    fun lastInfo(): CarNativeHotspotInfo

    /**
     * 最近一次探测结论，且距现在不超过 [maxAgeMillis]；否则返回 null。
     *
     * 会话启动路径用它避免重复探测：探测要几秒，而车机热点配置在一分钟内
     * 不会变，没必要每次建栈都重跑一遍。
     */
    fun cachedInfo(maxAgeMillis: Long): CarNativeHotspotInfo?

    /** 丢弃缓存，强制下一次 [probe] 真的重跑。 */
    fun invalidate()
}
