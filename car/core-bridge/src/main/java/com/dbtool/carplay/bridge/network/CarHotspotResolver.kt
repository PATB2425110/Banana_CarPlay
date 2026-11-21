package com.dbtool.carplay.bridge.network

import android.content.Context
import com.dbtool.carplay.api.hotspot.CarAdbHotspotDefaults
import com.dbtool.carplay.api.hotspot.CarHotspotNetwork
import com.dbtool.carplay.api.hotspot.CarHotspotProbeSource
import com.dbtool.carplay.api.hotspot.CarNativeHotspotInfo
import com.dbtool.carplay.api.hotspot.CarSameLanInfo
import com.dbtool.carplay.api.hotspot.CarGeelyRoutedHost
import com.dbtool.carplay.api.hotspot.CarTcamHotspot
import com.dbtool.carplay.api.settings.CarHotspotBand
import com.dbtool.carplay.api.settings.CarHotspotMode
import com.dbtool.carplay.api.settings.CarHotspotSecurity
import com.dbtool.carplay.api.settings.CarSessionSettings
import com.dbtool.carplay.bridge.hotspot.CarAdbHotspotPlanner
import com.dbtool.carplay.bridge.hotspot.CarSameLanReader
import com.dbtool.carplay.bridge.hotspot.LocalSoftApReader
import java.net.Inet4Address
import java.net.InetAddress

/**
 * 一次无线连接实际要用的热点方案。**[CarHotspotMode.AUTO] 已经被解析掉了**，
 * 这里的 [mode] 只会是上游认识的那三种之一。
 */
data class CarHotspotPlan(
    val mode: CarHotspotMode,
    val ssid: String?,
    val passphrase: String,
    val security: CarHotspotSecurity,
    val band: CarHotspotBand,
    val channel: Int,
    /**
     * true = 复用本机已经开着的那张热点（车机自带，**所有权不属于本 App**）；
     * false = 由本 App 新建一张。
     *
     * 这个标志只用于文案与诊断：真正"绝不关闭别人的网络"由上游
     * `ManualHotspotManager.close()` 的空实现保证（它只 attach，不 start）。
     */
    val reused: Boolean,
    /**
     * **结论**式短标签，直接做设置页「本次将使用」的值列。
     *
     * 与 [summary] 的分工：这里是"到底用哪张网"，一句话能读完；
     * [summary] 是"为什么"（探测结果、回落原因、加密方式、频段）。
     * `AUTO` 下这两行必须分开，否则用户只看得到一句含糊的"自动判定"。
     */
    val targetLabel: String,
    /** 人类可读的一句话，直接进设置页提示与诊断日志。 */
    val summary: String,
    /**
     * 这次决策所依据的**车机原生热点探测结果**（可能为 null = 没探测过）。
     *
     * 放进 plan 是为了让诊断日志能一次性写出"为什么选了这一支"，
     * 而不是事后重新探测一遍（结果可能已经变了）。
     */
    val native: CarNativeHotspotInfo? = null,
    /**
     * 自建热点要用的网络参数（网卡 / 网段 / 网关 / DHCP / 信道）。
     *
     * ⚠️ **必须由这里算好再交给 `CarAdbHotspotHost`**，不能让它自己再算一遍：
     * 同一组参数还要用于给 iPhone 的 AirPlay 通告（`0x5703` 里带信道），
     * 两处不一致会让 iPhone 反复重试关联。
     *
     * null = 复用分支（不需要自建），或没有 Context 可用来选参数。
     */
    val adbNetwork: CarHotspotNetwork? = null,
    /**
     * PATCH-0010：`SAME_LAN` 模式下读到的本机局域网信息（其余模式为 null）。
     *
     * 放进 plan 的理由同 [native]：让诊断日志一次性写出"为什么选了这一支，
     * 当时读到的地址是什么"，而不是事后重读（网络可能已经变了）。
     * 真正交给上游的是它的 [CarSameLanInfo.hostAddress]。
     */
    val sameLan: CarSameLanInfo? = null,
    /**
     * PATCH-0010 修正：`SAME_LAN` 下**真正要通告给 iPhone 的 Wi-Fi 名**。
     *
     * ## 为什么必须单独开一个字段，而不是复用 [ssid]
     *
     * [ssid] 的语义是"本机热点名"（复用分支=车机热点、自建分支=我们起的那张网）。
     * 而 `SAME_LAN` **一张热点都不建**：车机接的是别人的路由器，这正是
     * iPhone 也要接的那张网 ⇒ 要通告的名字是**对接的 Wi-Fi**，与 [ssid] 无关。
     *
     * 2026-10-07 实测（诊断包 `1aedc494`）：因为这里被填成热点名 `DBPlay`，
     * 发给 iPhone 的 `0x5703` 带着一个**根本不存在的网络名**，iPhone 无从关联
     * ⇒ 40s 后 `wireless attach timed out` ⇒ 界面永远停在「无线通道就绪」。
     *
     * 取值优先级：设置里手填（`sameLanWifiSsid`）> 读到的真实 SSID（[sameLan]）。
     * 两者都拿不到时留 null，由上游按"空 SSID"处理（不编造）。
     */
    val sameLanWifiSsid: String? = null,
    /**
     * PATCH-0010 修正：与 [sameLanWifiSsid] 配套的密码。
     *
     * ⚠️ Android 读不到已保存网络的明文密码（`WifiConfiguration.preSharedKey`
     * 自 Android 10 起对三方不可读）⇒ 自动路径**永远是空串**，
     * 只能由用户在设置页手填。空串表示"该网开放或密码未知"，
     * **绝不能拿热点密码顶替**（那正是本次 bug 的来源）。
     */
    val sameLanWifiPassphrase: String? = null,

    /**
     * 复用时通告给 iPhone 的**本机 CarPlay/AirPlay IPv4 endpoint**（PATCH-0087/0099）。
     *
     * 车主口径：*"复用车机热点是要用户自己手动设置，而不是自动识别"*。
     * 于是复用的地址不再由我们猜（`wlan0` 上同时挂着车机热点的地址与自建残留的地址时，
     * 猜错过两次、每次都是 40 秒 `attach timed out`），而是**照用户填的直接用**：
     * 上游 `ManualHotspotManager` 会拿它去匹配网卡（见那里的 `hostAddressOverride`）。
     *
     * null = 没填（或不是复用那一支）⇒ 走 [reused] 前置检查拦下并提示用户填写，
     * 而不是回落到"按网卡名挑一个"。
     */
    val manualHostAddress: String? = null,
    /** Strict Ecarx route interface paired with [manualHostAddress]; null keeps legacy selection. */
    val manualHostInterfaceName: String? = null,
)

/**
 * 把 [CarHotspotMode.AUTO] 解析成一个具体的热点方案。
 *
 * ## 判定顺序（2026-09-29 重排：**车机原生热点优先**）
 *
 * ```
 * ① ADB 探测到车机正在跑的热点，拿到了它的 SSID，**且广播名与存储配置一致**
 *      → 复用它（上游 MANUAL 只 attach，不 start）
 * ② 探测不到，但本进程反射能读到（ADB 不可用时的次选）
 *      → 同样复用
 * ③ 其它情况（含"广播名与存储配置不一致"）→ DBPlay 自建（ADB + hostapd）
 * ```
 *
 * ⚠️ 重排之前是"反射优先、探测根本不存在"，结果是**把自建当成了默认**，
 * 而车机本来就有热点的情况下再叠一层是有害的（iPhone 要在两张陌生网络之间
 * 二选一，车机 AP 往往还承担流量出口）。所以第一优先级必须是"先问车机有什么"。
 *
 * ## ⚠️ 为什么①多了一个"广播名与存储配置一致"的条件（2026-09-30 实车修正）
 *
 * 复用时发给 iPhone 的 SSID 只能是**存储配置名**——上游 `ManualHotspotManager`
 * 会拿它跟 `getWifiApConfiguration()` 硬校验，传实际广播名会被直接拒绝
 * （实车原文：`Manual hotspot SSID does not match the active local AP configuration:
 * 'Lynk'`）。所以当"存储配置名 ≠ 实际广播名"时，复用等于**把一个没人广播的名字
 * 发给 iPhone** ⇒ 永远关联不上 ⇒ 界面停在「无线通道就绪」且全链路无报错。
 *
 * 这一支改走自建：我们起一张 AP，让网卡**真的**广播存储配置里那个名字。
 * 于是"发给 iPhone 的名字"、"网卡广播的名字"、"车机存储配置"三者一致。
 *
 * ## 为什么复用走上游 [CarHotspotMode.MANUAL]
 *
 * 上游的 [com.shilapi.xcertplay.network.ManualHotspotManager] 语义并不是
 * "新建一个手动热点"，而是 **"attach 到本机已经在跑的那张热点"**——
 * 它只读 `SoftApConfiguration` 找接口，从不调用任何 `startSoftAp`。
 * 这正是"复用车机热点"需要的全部行为。⇒ **AUTO 不需要任何上游改动**。
 *
 * ## 为什么自建那一支是 ADB 独立热点，而不是系统 LocalOnlyHotspot
 *
 * 历史上的两个候选都不行，**系统 LOH 尤其不行**（2026-10-03 已把该形态整体移除）：
 * SSID/密码读不出来、地址由系统选网卡（可能落到蜂窝口或只给出 link-local IPv6）
 * ⇒「iPhone 拿到 DHCP 却永远不连 AirPlay 7000」，实测从没成功过一次。
 *
 * 改走 [CarHotspotMode.ADB_HOSTAPD] 后，网卡、网段、网关、信道全由我们自己定，
 * 行为完全可预期。**ADB 拉起失败不再回落到任何东西**——失败点已挪到"真的跑起来
 * 了吗"之后由 `CarPlaySessionBridge` 报出，而不是在这里靠猜。
 *
 * ## 已知限制（如实写在这里，而不是假装支持）
 *
 * - **密码读不出来时**（厂商把 `getPassphrase()` 置空、密码存在密文里、
 *   或用了 WPA3-SAE），会退回用用户在设置页填的密码。用户没填对就连不上——
 *   这时设置页的提示会明确写出"检测到车机热点 X，需要填它的密码"。
 * - **车机只是"连着一张 Wi-Fi"而不是"开着热点"** 的情况没有覆盖：
 *   `ManualHotspotManager` 找的是 AP 接口，STA 侧网络它认不出来。
 *   这种情况请用车机自己的设置把热点打开。
 * - 解析发生在**会话启动时**（`buildSession`），之后车机再开关热点不会自动跟随。
 */
object CarHotspotResolver {

    /**
     * @param context 任意 Context，内部只取 applicationContext。
     *        ⚠️ 会做 binder 调用与网卡枚举，不要在每帧的渲染路径上调；
     *        设置页提示只在设置变化/会话启动时算一次。
     * @param nativeInfo `CarNativeHotspotProbe` 的探测结果。
     *        **强烈建议传**：不传的话这里只能退回本进程反射，
     *        而反射在三方应用上经常读不到任何东西（见 `LocalSoftApReader`）。
     */
    fun resolve(
        context: Context,
        settings: CarSessionSettings,
        nativeInfo: CarNativeHotspotInfo? = null,
    ): CarHotspotPlan {
        val appContext = context.applicationContext
        if (settings.hotspotMode != CarHotspotMode.AUTO) {
            return explicit(appContext, settings)
        }

        // ⚠️ 2026-10-05（斌哥定案·最高口径）：**直接按默认配置自建，不做任何探测**。
        //
        // 原逻辑（"能复用就复用车机自带热点"）**已作废**：
        // 车机上那套原生配置是厂商预置的旧模板（实车 `softap.conf` 里躺着
        // `LYNK&CO` / `12345678`），
        // ① 探测它要花 2.9 秒（dumpsys + WifiConfigStore + softap.conf 一大圈）；
        // ② 更糟的是**复用会把没人广播的旧名字发给 iPhone**——实车 0.10.1 日志实锤：
        //    `实际「DBPlay」/ 配置「Lynk」→ iPhone 必然连不上`。
        //
        // 用户已手动开启 DBPlay 永久热点，DBPlay 只需"按默认配置直接使用 DBPlay"
        // ⇒ 丢掉复用分支，同时丢掉上面两个问题。
        //
        // 🔒 保留 `explicit()` 那一支（非 AUTO 模式）不受影响。
        // 保留 [CarHotspotMode.MANUAL] 语义：仍可显式指定自建。
        return fallback(
            context = appContext,
            settings = settings,
            reason = "斌哥口径：不探测，直接按默认配置使用 DBPlay 热点",
            info = null,
        )
    }

    /**
     * 旧的探测+复用决策链（`LocalSoftApReader` + `safeToReuse`）。
     *
     * ⚠️ 2026-10-05：**已作废，不再被调用**。保留在这里只为了（a）说明历史口径，
     * （b）万一将来要恢复"复用车机原生热点"时有据可查。
     *
     * 作废原因见 [resolve] 上方注释：探测慢（2.9s）+ 复用时名字错（发 Lynk 给 iPhone）。
     */
    @Suppress("unused")
    private fun resolveLegacyProbeAndReuse(
        context: Context,
        settings: CarSessionSettings,
        nativeInfo: CarNativeHotspotInfo? = null,
    ): CarHotspotPlan {
        val appContext = context.applicationContext
        if (settings.hotspotMode != CarHotspotMode.AUTO) {
            return explicit(appContext, settings)
        }

        // 探测结果：优先用调用方给的（来自 ADB，最权威）。
        // 没给就现读一次本地反射——注意这一步**不能**省成"直接自建"，
        // 那会把"车机有热点却因为没探测而没用上"变成默认行为。
        val info = nativeInfo ?: LocalSoftApReader.read(appContext)

        // ⚠️ 存储配置名与实际广播名不一致时**不能复用**（2026-09-30 实车修正）。
        //
        // 复用只会把 `info.ssid`（**存储配置名**）发给 iPhone，而网卡实际广播的是
        // `info.runningSsid` —— 发给 iPhone 的那个名字**没有人广播**，
        // iPhone 永远关联不上 ⇒ iAP2 无线隧道起不来 ⇒ 界面停在「无线通道就绪」，
        // **而整条链路上没有一句报错**。
        //
        // 所以这一支改走自建：我们自己起一张 AP，让网卡**真的**广播这个名字。
        // 附带好处：`CarAdbHotspotPlanner` 会沿用网卡上残留 AP 的地址
        // （见那里的 KDoc），于是"通告给 iPhone 的地址"与"iPhone 实际能到的地址"
        // 也是同一个。
        //
        // 判据用 `safeToReuse`（= `reusable && !ssidMismatch`）而不是这里的
        // `!info.ssidMismatch`：把它放在纯数据类上，这条规则才有单测兜着
        // （`resolve()` 要 Context，本模块没有 Robolectric）。
        //
        // ⚠️ 2026-10-03 实车（CS11）补：**光有名字不够，还得真有一张 AP 在跑**。
        //
        // 现场日志（设备 d5ddb4312da1，16:12）：
        //   `detected=true enabled=true ssid=Lynk iface=null addr=null channel=0`
        // 这一行的 `iface`/`addr` 全空 ⇒ 本机**没有任何网卡承载 Lynk**。
        // 原判据（`safeToReuse` 只看名字）把它当成了"可复用"，上游
        // `ManualHotspotManager` 找不到 AP 接口，退回按名字挑网卡，抓到有线口
        // `eth0.3`（`host=198.18.34.15`）通告给 iPhone ⇒ 走 Wi-Fi 的 iPhone
        // 永远到不了 ⇒ 卡在「无线通道就绪」，且全链路无报错。
        //
        // ⇒ 这里加一道 `actuallyRunning`（`iface` 与 `addr` 都非空）：
        // 不满足就走自建，真的起一张 AP，而不是复用一张不存在的热点。
        if (info.safeToReuse && info.actuallyRunning) {
            val passphrase = usablePassphrase(info, settings)
            if (passphrase != null) {
                return reuse(info, passphrase)
            }
            return fallback(
                appContext,
                settings,
                reason = "探测到车机热点「${info.ssid}」，但读不到它的密码，" +
                    "且设置里的密码不是 8~63 位，无法复用",
                info = info,
            )
        }

        val reason = when {
            // 顺序刻意放在最前：mismatch 时下面几条原因都会误导现场
            // （"探测到车机热点但没读到名称"之类），而真正的问题是"名字对不上"。
            info.ssidMismatch ->
                "车机热点的实际广播名「${info.runningSsid}」与存储配置「${info.ssid}」不一致" +
                    "——发给 iPhone 的只能是存储配置名，而它没有被广播，复用必然连不上"
            // ⚠️ 2026-10-03 实车（CS11）补：**读到名字了但没有网卡承载**。
            //
            // 现场日志：`detected=true enabled=true ssid=Lynk iface=null addr=null`。
            // 这种状态下面那条 `info.detected` 分支会报"没读到它的名称"——**假话**，
            // 名称明明读到了（Lynk），缺的是**承载它的网卡**。
            // 现场要据此做的事完全不同：不是去查名字，而是**在车机设置里把热点打开**。
            info.reusable && !info.actuallyRunning ->
                "车机设置里存着热点「${info.ssid}」，但**没有任何网卡在承载它**" +
                    "（读到的网卡与地址都是空）⇒ 热点实际没开。" +
                    "请在车机设置里打开「个人热点」（或关一次再开），DBPlay 之后会自动复用它。"
            info.detected ->
                "探测到车机热点（网卡 ${info.interfaceName ?: "未知"}），但没读到它的名称，无法复用"
            nativeInfo != null ->
                "ADB 探测未发现正在运行的车机热点"
            else ->
                "未探测到车机热点（ADB 不可用，已退回本地反射）"
        }
        return fallback(appContext, settings, reason, info)
    }

    // ------------------------------------------------------------ 显式模式

    /**
     * **不探测**本机热点，直接按设置给出一套方案。
     *
     * [CarHotspotMode.AUTO] 在这里等价于"自建"——没有 Context 就无从知道
     * 车机有没有热点，也无从选网络参数（`adbNetwork` 会是 null，
     * 由 `CarAdbHotspotManager` 在拉起时自己算一份）。
     *
     * 仅用于单元测试与不持有 Context 的调用方；真实会话启动请用 [resolve]。
     */
    fun withoutProbe(settings: CarSessionSettings): CarHotspotPlan =
        if (settings.hotspotMode == CarHotspotMode.AUTO) {
            fallback(null, settings, reason = "未探测车机热点", info = null)
        } else {
            explicit(null, settings)
        }

    private fun explicit(context: Context?, settings: CarSessionSettings): CarHotspotPlan {
        // PATCH-0010：SAME_LAN 与其余三者**结构性不同**——它不建热点、也不 attach
        // 任何本机 AP，所以没有网卡/网段/信道可谈。单独成一支，避免下面的
        // `selfBuilt` / `reuses` 那两个布尔量把它算错。
        if (settings.hotspotMode == CarHotspotMode.SAME_LAN) {
            return sameLan(context, settings)
        }
        // TCAM 复用：接入卡与地址由 `TcamHotspotManager` 在连接时自动识别，
        // 这里只给出一份 `reused=true` 的骨架方案（manualHostAddress 由桥在
        // `ensureTcamHotspotReady` 里回填），不自建、也不走 MANUAL 的手填地址。
        if (settings.hotspotMode == CarHotspotMode.TCAM_HOTSPOT) {
            return tcam(settings)
        }
        // 车机热点（2026-10-25）：与 TCAM **并列的独立链路**，不走 TCAM 的
        // 「连接时反射读车机 AP 配置」那一套，而是用**用户手填**的凭据 +
        // 自己扫 `eth*` 上的 `198.18.x.x`。两支互不回退（车主要求）。
        if (settings.hotspotMode == CarHotspotMode.GEELY_ROUTED) {
            return geelyRouted(settings)
        }
        val reuses = settings.hotspotMode == CarHotspotMode.MANUAL
        val selfBuilt = settings.hotspotMode == CarHotspotMode.ADB_HOSTAPD
        // ⚠️ 2026-10-13（PATCH-0116）：**自建没有"让系统随机生成"这条路**。
        //
        // 那张 AP 是我们自己用 hostapd 起的 —— 配置里必须有一个真实 SSID，而我们
        // 也没有任何"让系统替我挑名字"的机制（`系统随机` 只属于已移除的系统 LOH）。
        // 留空 ⇒ 会话**在热点起来之前**就抛
        // `IllegalArgumentException: manualHotspotSsid is required in manual hotspot mode`
        // （上游 `CarPlayRuntimeConfig` 的 require）。
        //
        // 日志 9（`1791897995606`，21:24:54 / 21:25:01 各一次）实测：设置页把名字清空 ⇒
        // 计划显示「自建热点「系统随机」」 ⇒ 随即报上面那句、连接当场失败，
        // 而现场从计划文案里**完全看不出**这个名字是导致失败的原因。
        //
        // 处置与 `fallback()`（AUTO 落入自建那一支）一致：空 ⇒ 出厂默认名。
        // ⚠️ 复用分支**不动**（那里留 null 是为了让上游/探测到的车机名说了算，
        // 判据由 `HotspotManualReuseContractTest` 钉着）。
        val effectiveSsid = if (selfBuilt) {
            settings.manualHotspotSsid.ifBlank { CarAdbHotspotDefaults.SSID }
        } else {
            settings.manualHotspotSsid
        }
        val ssidLabel = effectiveSsid.ifEmpty { "系统随机" }
        // ADB 独立热点的网段/信道由我们自己定，所以在这里就把参数选好，
        // 让"给 iPhone 的通告"与"hostapd 实际配置"用同一组值。
        val network = if (selfBuilt && context != null) {
            CarAdbHotspotPlanner.plan(context, info = null, interfaceOverride = settings.adbHotspotInterface)
        } else {
            null
        }
        return CarHotspotPlan(
            mode = settings.hotspotMode,
            ssid = if (selfBuilt) {
                effectiveSsid
            } else {
                settings.manualHotspotSsid.takeIf { it.isNotEmpty() }
            },
            passphrase = settings.manualHotspotPassphrase,
            // 🔒 同 Bug1：安全类型由密码现推（此处可能选 MANUAL 复用分支，
            // 也可能显式指定自建），避免矛盾的 security/passphrase 组合。
            security = securityForSettings(settings, settings.manualHotspotPassphrase),
            // ⚠️ 复用分支**不能**用设置页里的 `manualHotspotBand`。
            //
            // 那个值是为**自建热点**准备的（出厂默认 GHZ_5，见
            // `CarAdbHotspotDefaults.CHANNEL_CANDIDATES`）；而复用时上游
            // `ManualHotspotManager.validateApConfiguration` 会拿它跟**车机实际热点
            // 的频段**硬校验。车机热点是 2.4 GHz 就必然对不上 ⇒
            //
            //   `IOException: Manual hotspot band 2.4 GHz does not match
            //    configured band 5 GHz`  →  `wireless bring-up failed`
            //
            // 2026-10-08 实车日志（设备 67b3c24af6b5）实锤：用户选「复用车机热点
            // 「Lynk」」，每次都停在这句 —— 这正是「复用车机热点无法使用」的根因。
            //
            // 传 AUTO 即放行：上游的判据是 `expectedBand != AUTO` 才校验频段
            // （`ManualHotspotManager.kt` 的 `validateApConfiguration`），
            // 于是改由车机热点自己的频段说了算。
            band = when {
                network != null -> bandOfChannel(network.channel)
                // 自建走 5 GHz（2026-10-07 起只走 5 GHz，见
                // `CarAdbHotspotDefaults.CHANNEL_CANDIDATES`）；
                // 这一支只在 `context == null`（选不出网络参数）时才会走到。
                selfBuilt -> CarHotspotBand.GHZ_5
                reuses -> CarHotspotBand.AUTO
                else -> settings.manualHotspotBand
            },
            // ⚠️ 同理：复用分支不能传设置里的信道 —— 上游对 `expectedChannel > 0`
            // 的信道**同样硬校验**（`channel X does not match configured channel Y`）。
            // 0 = 不指定，随车机热点。
            channel = when {
                network != null -> network.channel
                selfBuilt -> CarAdbHotspotDefaults.DEFAULT_CHANNEL
                reuses -> 0
                else -> settings.manualHotspotChannel
            },
            reused = reuses,
            // ⚠️ 2026-10-11（PATCH-0087）：**复用的地址由用户填**（车主口径：
            // "复用车机热点是要用户自己手动设置，而不是自动识别"）。
            // 只有显式「复用」这一支才带上它：自建的网段是我们自己规划的 ——
            // 谁都不该被这个字段影响。
            manualHostAddress = settings.manualHotspotHostAddress.trim()
                .takeIf { reuses && it.isNotEmpty() },
            targetLabel = when {
                // `reuses`（MANUAL）与自建的区别只在标签上：前者是接管车机已有的那张网，
                // 后者是我们自己起的。
                reuses -> "复用车机热点「$ssidLabel」"
                else -> "自建热点「$ssidLabel」"
            },
            summary = when {
                selfBuilt && network != null ->
                    "自建热点「$ssidLabel」：${network.interfaceName} / ${network.subnet} / " +
                        "网关 ${network.hostAddress} / 信道 ${network.channel}"
                selfBuilt ->
                    "自建热点「$ssidLabel」（网卡与网段在拉起时按车机环境选择）"
                reuses -> {
                    val address = settings.manualHotspotHostAddress.trim()
                    if (address.isEmpty()) {
                        "复用车机热点「$ssidLabel」（手动指定；⚠️ 车机热点地址还没填，" +
                            "连接前会被拦下并提示填什么）"
                    } else {
                        "复用车机热点「$ssidLabel」（手动指定；通告给 iPhone 的地址 $address）"
                    }
                }
                else -> "自建热点「$ssidLabel」（${hotspotModeLabel(settings.hotspotMode)}）"
            },
            adbNetwork = network,
        )
    }

    // ------------------------------------------------------------ TCAM 复用

    /**
     * TCAM 热点复用方案（[CarHotspotMode.TCAM_HOTSPOT]）。
     *
     * 只给骨架：接入卡（`eth0.11`）与地址由 `TcamHotspotManager` 在连接那一刻自动识别
     * （证据包 §B：`ConnectivityManager.getAllNetworks()` → `TRANSPORT_ETHERNET` →
     * `eth0.11` → 恰好一个 IPv4），SSID / 密码 / 加密 / 信道由它反射 `getWifiApConfiguration()`
     * 读取后回填。因此这里的 ssid/passphrase 只是**保存值回落源**，地址保持 null。
     */
    private fun tcam(settings: CarSessionSettings): CarHotspotPlan {
        // PATCH-0136：复用档的 SSID/密码**一律以车机现场为准**。
        //
        // 历史实现把设置里保存的 `manualHotspotSsid` 当"回落源"塞进 plan，而那份值属于
        // 自建档（现场是 `DBPlay`）；一旦模式切到 TCAM，`TcamHotspotManager` 会用
        // `Saved hotspot name differs from the active TCAM hotspot` 直接拒连。
        // 复用语义本来就是"接管车机现有的网"，名字不该由我们声明 ⇒ 这里传 null。
        val savedPassphrase = settings.manualHotspotPassphrase
        val ssidLabel = "车机原厂热点（名字连接时自动读取）"
        return CarHotspotPlan(
            mode = CarHotspotMode.TCAM_HOTSPOT,
            ssid = null,
            passphrase = savedPassphrase,
            // 先按保存密码现推，保证方案自洽；连接时由 TcamHotspotManager 按
            // allowedKeyManagement 重新定（OPEN→OPEN / 其它→WPA2）。
            security = securityForSettings(settings, savedPassphrase),
            // 复用分支不传信道/频段（与 MANUAL 同一条纪律：0 / AUTO 让上游跳过
            // 一致性校验，真值由车机热点说了算）。
            band = CarHotspotBand.AUTO,
            channel = 0,
            reused = true,
            targetLabel = "复用车机热点「$ssidLabel」",
            summary = "复用车机热点「$ssidLabel」：连接时自动识别接入卡 " +
                "${CarTcamHotspot.TCAM_ETH_IFACE} 与地址（热点未开或 " +
                "${CarTcamHotspot.DEFAULT_TIMEOUT_MILLIS / 1000} 秒内读不到即报错，绝不自行开热点）。",
            manualHostAddress = null,
            manualHostInterfaceName = null,
        )
    }

    // ------------------------------------------------------------ 车机热点（GEELY_ROUTED）

    /**
     * 车机热点方案（[CarHotspotMode.GEELY_ROUTED]，2026-10-25 新增）。
     *
     * ## 与 [tcam] 的结构性差别
     *
     * [tcam] 的 SSID/密码**一律以车机现场为准**（PATCH-0136：复用档不声明名字），
     * 地址由 `TcamHotspotManager` 连接时自动识别。
     *
     * 本档相反：**凭据由用户手填**、**地址由我们自己扫**（`eth*` + `198.18.x.x`，
     * `eth0` 优先）。判据来源是参考 APK 的 `GeelyRoutedHotspotManager`，见
     * `CarGeelyRoutedHost` 的 KDoc。
     *
     * ## 为什么 ssid 必须给值
     *
     * 上游要用 SSID/密码下发给 iPhone（`0x5703`），而本档**不反射读车机配置**
     * （那正是 TCAM 的做法，也是两条链路的区别之一）。所以保存值就是唯一来源；
     * 为空时宁可在这里报错，也不要静默用 `DBPlay` 顶上去 —— 那会让用户在车机上
     * 看到一张名字对不上的网。
     */
    private fun geelyRouted(settings: CarSessionSettings): CarHotspotPlan {
        val ssid = CarGeelyRoutedHost.normalizeSsid(settings.manualHotspotSsid)
        val passphrase = settings.manualHotspotPassphrase
        val security = securityForSettings(settings, passphrase)
        return CarHotspotPlan(
            mode = CarHotspotMode.GEELY_ROUTED,
            // 与 TCAM 的关键区别：这里**必须**给用户填的名字。
            ssid = ssid.ifEmpty { null },
            passphrase = passphrase,
            security = security,
            // 复用分支不传信道/频段（0 / AUTO 让上游跳过一致性校验）。
            band = CarHotspotBand.AUTO,
            channel = 0,
            reused = true,
            targetLabel = "车机热点「${ssid.ifEmpty { "（未填写名称）" }}」",
            summary = "车机热点：扫描 ${CarGeelyRoutedHost.INTERFACE_PREFIX}* 上 " +
                "${CarGeelyRoutedHost.ADDRESS_FIRST_OCTET}." +
                "${CarGeelyRoutedHost.ADDRESS_SECOND_OCTET}.x.x 的原厂路由地址" +
                "（${CarGeelyRoutedHost.PREFERRED_INTERFACE} 优先，" +
                "${CarGeelyRoutedHost.SCAN_TIMEOUT_MILLIS / 1000} 秒内找不到即报错，" +
                "绝不自行开热点、也不回退到其它模式）。",
            // 地址由桥在连接时扫出来回填（与 TCAM 同一条回填通道，
            // 但不读车机 AP 配置、不做 `eth0.11` 写死判定）。
            manualHostAddress = null,
            manualHostInterfaceName = null,
        )
    }

    // ------------------------------------------------------------ 局域网连接

    /**
     * PATCH-0010：**不建热点**，用车机当前的局域网地址直连。
     *
     * ## 为什么不能`require(hotspotMode != SAME_LAN)` 地绕开
     *
     * `CarHotspotPlan` 的字段是围绕"本机拥有一张 AP"设计的（ssid / 信道 /
     * `adbNetwork`）。本模式这些字段**全都不适用**，但 plan 仍必须给出一个
     * 自洽的值——它会被原样送进 `CarPlayConfigBuilder`，进而决定
     * `WirelessHotspotMode`。所以这里把不适用字段填成"中性值"，
     * 并在 [CarHotspotPlan.targetLabel] / [CarHotspotPlan.summary] 里
     * **说清楚这次一个热点都不建**，避免读日志的人以为走的是 MANUAL。
     *
     * ## 地址在哪读
     *
     * 在这里调 `CarSameLanReader.read()`（本方法在 worker 线程上跑，
     * 见 `CarHotspotResolver.resolve` 的调用约定）。用户手填的
     * [CarSessionSettings.sameLanHostAddress] **优先**：
     * 自动读取依赖厂商对 `LinkProperties` 的实现，个别 ROM 给不出 IPv4，
     * 那时人工值就是唯一的出路。
     *
     * ## 读不到地址时**不静默滑回自建**
     *
     * 如实把"读不到"写进 summary，让会话启动时报错（上游
     * `sameLanHotspotInfo()` 会抛），由设置页给出下一步。
     * 静默换一条用户没选的路，比报错更难查——这与本仓"不再回落 LOH"
     * 是同一条纪律。
     */
    private fun sameLan(context: Context?, settings: CarSessionSettings): CarHotspotPlan {
        val manual = settings.sameLanHostAddress.trim().takeIf { it.isNotEmpty() }
        val info = if (manual != null) {
            // 用户手填：仍要校验，否则下半程（上游 `sameLanHotspotInfo()`）
            // 才会抛，现场看到的是一句"起不来"而不知道是填错了。
            sameLanFromManual(manual)
        } else if (context != null) {
            CarSameLanReader.read(context)
        } else {
            // 没有 Context（单测 / 不持 Context 的调用方）⇒ 如实报告读不到，
            // 而不是编一个地址出来。
            CarSameLanInfo.unavailable("本调用方没有 Context，无法读取车机当前的局域网地址")
        }
        val usable = info.connected && !info.hostAddress.isNullOrEmpty()
        // 真实要通告给 iPhone 的 Wi-Fi 名：设置里手填优先（自动读取在个别 ROM
        // 只会给出 <unknown ssid>），否则用本次读到的那张网；都拿不到就 null。
        val realSsid = settings.sameLanWifiSsid.trim().takeIf { it.isNotEmpty() } ?: info.ssid
        val realPassphrase = settings.sameLanWifiPassphrase
        return CarHotspotPlan(
            mode = CarHotspotMode.SAME_LAN,
            // [ssid]/[passphrase] 对本模式没有实际意义（网是别人的），
            // 保留中性值只为让 plan 字段自洽。**不要再拿它当通告值**——
            // 真正发给 iPhone 的是下面的 sameLanWifiSsid / sameLanWifiPassphrase。
            ssid = realSsid,
            passphrase = realPassphrase,
            // 安全类型无从判断（路由器说了算）⇒ 按密码现推，保证出口自洽。
            security = securityForSettings(settings, realPassphrase),
            band = CarHotspotBand.AUTO,
            channel = 0,
            reused = false,
            targetLabel = if (usable) {
                info.targetLabel
            } else {
                "局域网连接（未读到可用地址）"
            },
            summary = if (usable) {
                buildString {
                    append(info.summary)
                    append(
                        when {
                            realSsid != null && realPassphrase.isNotEmpty() ->
                                "。iPhone 将按 SSID「$realSsid」+ 密码关联该网"
                            realSsid != null ->
                                "。iPhone 将按 SSID「$realSsid」关联该网" +
                                    "（开放网络或密码未知，若该网有密码请在设置里填写）"
                            else ->
                                "。⚠️ 未能确定要通告的 Wi-Fi 名，iPhone 可能无法关联；" +
                                    "请在设置里填写该 Wi-Fi 的名称"
                        },
                    )
                }
            } else {
                "局域网连接模式：${info.summary}。" +
                    "请在车机设置里连上一张 Wi-Fi（并确认 iPhone 连的是同一张），" +
                    "或在设置里手动填写本机地址。"
            },
            sameLan = info,
            sameLanWifiSsid = realSsid,
            sameLanWifiPassphrase = realPassphrase,
        )
    }

    /** 用户手填地址时构造 [CarSameLanInfo]；非法值如实标成不可用。 */
    private fun sameLanFromManual(text: String): CarSameLanInfo {
        val parsed = try {
            InetAddress.getByName(text)
        } catch (_: Exception) {
            null
        }
        if (parsed !is Inet4Address) {
            return CarSameLanInfo.unavailable(
                "设置里填的地址「$text」不是合法的 IPv4，请改成形如 192.168.1.5 的地址",
            )
        }
        if (parsed.isLoopbackAddress || parsed.isLinkLocalAddress) {
            return CarSameLanInfo.unavailable(
                "设置里填的地址「$text」是回环或 link-local 地址，iPhone 那一侧不可达",
            )
        }
        return CarSameLanInfo(
            connected = true,
            hostAddress = text,
            targetLabel = "局域网连接：$text",
            summary = "局域网连接模式：使用设置里填写的地址 $text（不建热点，" +
                "iPhone 连同一张网即可直连）",
        )
    }

    // ------------------------------------------------------------ 复用

    /**
     * ⚠️ 2026-10-04 重写（Bug1 + Bug3）：**安全类型不再照抄探测值，改由最终密码反推**。
     *
     * 旧实现写的是 `security = info.security`，而密码来自 [usablePassphrase]：探测到
     * 车机热点是「开放」（`info.security == OPEN`）时它会返回空串 `""` —— 这本身没错，
     * 但**下一行**却把 `security` 填成了探测值 `OPEN`，两者理论上自洽。
     *
     * 真正的坑在**调用方的第二个入口**：`manualHotspotSecurity=OPEN` 是用户在设置页
     * 里选的，而密码可能非空。上游 `ManualHotspotManager` 构造时硬校验
     * `passphrase must be empty for open manual hotspots`（见
     * `ManualHotspotValidation.kt`），于是 2026-10-04 13:55 现场刷了 15+ 次
     * `IllegalArgumentException`，会话直接起不来。
     *
     * 对照第三方同源实现 `com.ganfandw.carplay`（v1.3.2）的解法：
     * `security = if (passphrase.isEmpty()) OPEN else WPA2`
     * —— **它根本不存 security，只存密码，安全类型每次现推**。物理上不存在矛盾。
     *
     * 这里照这个思路做：把 `security` 收成**由最终 `passphrase` 现推**，
     * 而不是采信任何一个可能过期的输入字段。
     *
     * ---
     *
     * 复用分支：把车机热点交给 iPhone。
     *
     * ⚠️ 2026-10-04 修 Bug3：SSID **必须用实际广播的那个名字**（`runningSsid`），
     * 而不是存储配置里的名字（`info.ssid`）。
     *
     * 现场铁证（设备 d5ddb4312da1）：车机网卡上**广播**的是 `DBPlay`，而车机
     * 存储配置里写的是 `Lynk`；发给 iPhone 的是配置名 `Lynk` ⇒ 扫不到那个 SSID
     * ⇒ 必然连不上（另一台：广播 `car` / 配置 `小蛤蟆`）。这不是信号问题，是名字问题。
     *
     * 探测层已经把两者都读回来了（`runningSsid` = 网卡上的真名，
     * `ssid` = 存储配置名）。这里以真名为准 —— 与"通告给 iPhone 的地址必须是
     * iPhone 实际能到的地址"是同一条原则。
     */
    private fun reuse(info: CarNativeHotspotInfo, passphrase: String): CarHotspotPlan {
        // 广播名优先；探测不到才退回配置名（旧行为），并在 summary 里标出来。
        val advertisedSsid = info.runningSsid?.takeIf { it.isNotEmpty() } ?: info.ssid
        return CarHotspotPlan(
            mode = CarHotspotMode.MANUAL,
            ssid = advertisedSsid,
            passphrase = passphrase,
            // 🔒 安全类型follow密码：空密码 ⇒ OPEN，非空 ⇒ 探测值（大概率 WPA2）。
            // 这样出口处 `require(OPEN → passphrase 为空)` 恒成立，不可能再抛。
            security = securityForPassphrase(info, passphrase),
            // 频段/信道交给上游按探测值自己判断：传 AUTO / 0 会让
            // `validateApConfiguration` 跳过一致性校验——车机热点的
            // 信道我们无从控制，硬填一个值只会让启动直接失败。
            band = CarHotspotBand.AUTO,
            channel = 0,
            reused = true,
            // ⚠️ 走到这里时 `info.ssidMismatch` **一定为假**（[resolve] 已经把
            // mismatch 的那一支挡在门外、改走自建）。保留这个 ⚠️ 标记是为了两件事：
            // 1. `reuse()` 还有别的调用方（测试、将来的直连路径），它们不一定做了
            //    那层过滤；
            // 2. 万一将来有人把 [resolve] 里的过滤去掉，这里仍会在设置页留下痕迹，
            //    而不是静默地发出一个"没人广播的名字"。
            targetLabel = "复用车机热点「$advertisedSsid」" + if (info.ssidMismatch) " ⚠️" else "",
            summary = buildString {
                append("复用车机热点「").append(advertisedSsid).append("」（")
                append(securityLabel(securityForPassphrase(info, passphrase)))
                info.hostAddress?.let { append(" · ").append(it) }
                info.interfaceName?.let { append(" · ").append(it) }
                if (info.channel > 0) append(" · 信道 ").append(info.channel)
                append("）")
                append(sourceLabel(info.source))
                info.ssidMismatchNote?.let { append("；").append(it) }
            },
            native = info,
        )
    }

    /**
     * 由**最终密码**推安全类型，而不是照抄 `info.security` 或设置里的
     * `manualHotspotSecurity`。
     *
     * 这是 2026-10-04 修 Bug1 的核心：上游 `ManualHotspotManager` 构造时会抛
     * `passphrase must be empty for open manual hotspots` / `passphrase is required
     * for secured manual hotspots`，只要 `security` 与 `passphrase` 两个字段
     * 不匹配就必死。把 `security` 变成 `passphrase` 的**函数**之后，
     * 这个不变量在编译期就成立了。
     *
     * 参照 `com.ganfandw.carplay` v1.3.2 的 `ManualHotspotValidation.securityFor`：
     * `return passphrase.length() == 0 ? OPEN : WPA2;`
     *
     * 差异（我们这边刻意保留）：非空密码时**采信探测到的 security**
     * （车机可能是 WPA3 过渡/WPA3），只有探测值不可用（`OPEN` 但密码非空，
     * 即自相矛盾）时才退回 WPA2。
     */
    private fun securityForPassphrase(
        info: CarNativeHotspotInfo,
        passphrase: String,
    ): CarHotspotSecurity = when {
        passphrase.isEmpty() -> CarHotspotSecurity.OPEN
        info.security == CarHotspotSecurity.OPEN -> CarHotspotSecurity.WPA2
        else -> info.security
    }

    /**
     * 同 [securityForPassphrase]，用于**没有探测信息**的那两支（`explicit` / `fallback`）——
     * 那里只有设置页填的两个字段，同样要让它们自洽。
     */
    private fun securityForSettings(
        settings: CarSessionSettings,
        passphrase: String,
    ): CarHotspotSecurity = when {
        passphrase.isEmpty() -> CarHotspotSecurity.OPEN
        settings.manualHotspotSecurity == CarHotspotSecurity.OPEN -> CarHotspotSecurity.WPA2
        else -> settings.manualHotspotSecurity
    }

    // ------------------------------------------------------------ 自建

    /**
     * 自建分支：**车机没有可用热点**时才会走到这里。
     *
     * ⚠️ 模式是 [CarHotspotMode.ADB_HOSTAPD]，不再有系统 LOH 这一支（该形态有两个死结：
     * SSID 读不到、地址可能落到蜂窝口或只有 link-local IPv6 ⇒「iPhone 拿到 DHCP 却永远
     * 不连 AirPlay 7000」，2026-10-03 整体移除）。
     *
     * **ADB 拉起失败不再回落到任何东西**——失败点由 `CarPlaySessionBridge` 报出，
     * 而不是这里靠猜。
     *
     * 网卡 / 网段 / 网关 / DHCP / 信道全部由 [CarAdbHotspotPlanner] 按探测结果选，
     * 不再使用任何写死的值。
     */
    private fun fallback(
        context: Context?,
        settings: CarSessionSettings,
        reason: String?,
        info: CarNativeHotspotInfo?,
    ): CarHotspotPlan {
        val network = context?.let {
            CarAdbHotspotPlanner.plan(it, info, settings.adbHotspotInterface)
        }
        val ssid = settings.manualHotspotSsid.ifEmpty { CarAdbHotspotDefaults.SSID }
        return CarHotspotPlan(
            mode = CarHotspotMode.ADB_HOSTAPD,
            ssid = ssid,
            passphrase = settings.manualHotspotPassphrase,
            // 🔒 同 Bug1：安全类型由密码现推，避免"设置里选 OPEN + 密码非空"这种
            // 自相矛盾组合被原样送进上游 `ManualHotspotManager`（必抛）。
            security = securityForSettings(settings, settings.manualHotspotPassphrase),
            band = network?.let { bandOfChannel(it.channel) } ?: CarHotspotBand.GHZ_2_4,
            channel = network?.channel ?: CarAdbHotspotDefaults.DEFAULT_CHANNEL,
            reused = false,
            targetLabel = "自建热点「$ssid」",
            summary = buildString {
                append(reason ?: "未探测到车机热点")
                append("，改用 DBPlay 自建热点「").append(ssid).append("」")
                if (network != null) {
                    append("：").append(network.interfaceName)
                    append(" / ").append(network.subnet)
                    append(" / 网关 ").append(network.hostAddress)
                    append(" / 信道 ").append(network.channel)
                } else {
                    append("（网卡与网段在拉起时按车机环境选择）")
                }
                // ⚠️ 同一类静默失败的第二个入口：自建时我们把这个名字交给上游，
                // 而上游会拿**车机存储的热点配置**校验它，不一致就直接抛
                // `Manual hotspot SSID does not match ...`。此时自建根本起不来，
                // 会话会回落 LOH（本车 Android 9 上 LOH 一次即中毒）。
                // 实车踩过：设置里填 `car`、车机配置是 `Lynk` ⇒ 直接失败。
                val carSsid = info?.ssid
                if (carSsid != null && carSsid != ssid) {
                    append("；⚠️ 但车机热点配置的名字是「").append(carSsid)
                    append("」，与这里的「").append(ssid)
                    append("」不一致，上游会直接拒绝这个组合（需要把设置里的热点名称改成")
                    append("「").append(carSsid).append("」，或把车机热点重启成这个名字）")
                }
            },
            native = info,
            adbNetwork = network,
        )
    }

    // ------------------------------------------------------------ 杂项

    private fun sourceLabel(source: CarHotspotProbeSource): String = when (source) {
        CarHotspotProbeSource.ADB_SHELL -> " · 来源 ADB 探测"
        CarHotspotProbeSource.LOCAL_REFLECTION -> " · 来源本地反射（ADB 不可用）"
        CarHotspotProbeSource.NONE -> ""
    }

    private fun securityLabel(security: CarHotspotSecurity): String = when (security) {
        CarHotspotSecurity.OPEN -> "开放"
        CarHotspotSecurity.WPA2 -> "WPA2"
        CarHotspotSecurity.WPA3_TRANSITION -> "WPA3 过渡"
        CarHotspotSecurity.WPA3 -> "WPA3"
    }

    /**
     * 热点形态的人类可读名（进设置页提示用）。刻意不写 "LocalOnlyHotspot" —— 那是 Android
     * API 名，用户读不懂。模式对应的形态见 [CarHotspotMode]；`AUTO` 是保留的存量值。
     */
    private fun hotspotModeLabel(mode: CarHotspotMode): String = when (mode) {
        CarHotspotMode.AUTO -> "自动（优先复用车机热点）"
        CarHotspotMode.MANUAL -> "复用车机热点"
        CarHotspotMode.ADB_HOSTAPD -> "自建热点"
        CarHotspotMode.SAME_LAN -> "局域网连接（不建热点）"
        CarHotspotMode.TCAM_HOTSPOT -> "复用车机热点"
        CarHotspotMode.GEELY_ROUTED -> "车机热点"
    }

    /** 信道 → 频段。5 GHz 从 32 起（36 是第一个可用信道）。 */
    private fun bandOfChannel(channel: Int): CarHotspotBand = when {
        channel <= 0 -> CarHotspotBand.AUTO
        channel <= 14 -> CarHotspotBand.GHZ_2_4
        else -> CarHotspotBand.GHZ_5
    }

    /**
     * 选一个能交给上游的密码。
     *
     * 上游 `ManualHotspotManager` 在构造时就硬校验：开放网络必须空密码，
     * 加密网络必须 8~63 位。这里提前按同一口径筛，避免把"注定抛异常"的
     * 组合交给它——那样报出来的会是一句 Kotlin 的 `require` 文案，
     * 对现场排障毫无帮助。
     *
     * @return null 表示无法复用。
     */
    private fun usablePassphrase(
        info: CarNativeHotspotInfo,
        settings: CarSessionSettings,
    ): String? = when {
        info.security == CarHotspotSecurity.OPEN -> ""
        !info.passphrase.isNullOrEmpty() -> info.passphrase
        settings.manualHotspotPassphrase.length in 8..63 -> settings.manualHotspotPassphrase
        else -> null
    }
}
