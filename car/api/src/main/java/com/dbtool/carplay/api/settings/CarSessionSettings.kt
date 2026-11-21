package com.dbtool.carplay.api.settings

import com.dbtool.carplay.api.display.ClusterLayerMode

import com.dbtool.carplay.api.audio.CarAudioChannel
import com.dbtool.carplay.api.display.CarClusterSafeAreaInsets
import com.dbtool.carplay.api.display.HudCropPreset
import com.dbtool.carplay.api.display.HudCropSpec
import com.dbtool.carplay.api.display.HudScaleMode
import com.dbtool.carplay.api.display.theme.ClusterAppearanceMode

/**
 * 连接方式。
 */
enum class CarTransportMode {
    /** 有线 CarPlay（USB + NCM）。 */
    WIRED,

    /** 无线 CarPlay（蓝牙 + Wi-Fi）。 */
    WIRELESS,
}

/**
 * MFi 认证目标。
 *
 * 前三个值与 upstream `MfiTarget` 一一对应；[LOCAL_OFFLINE] 是本工程新增的，
 * **上游没有对应类型**，靠 `CarPlayConfigBuilder` 翻译成 `MfiTarget.REMOTE` 实现
 * （见该类里的映射说明）。所以本枚举扩容不会让上游需要任何改动。
 */
enum class CarMfiTarget {
    /** 通过 CH341 USB-I2C 桥接 MFi 协处理器。 */
    USB_CH341,

    /** 板载 `/dev/i2c-N` 直连 MFi 协处理器。 */
    I2C,

    /** 远程 MFi 认证服务（HTTP）。 */
    REMOTE,

    /**
     * 本机软件认证：**不需要任何 MFi 硬件**。
     *
     * 配件身份（P-256 私钥 + Apple 配件证书）由 `:car:core-bridge` 的
     * `CarMfiIdentityStore` 供给：优先用设置页导入的 `identity.pk8` +
     * `certificate.p7b`，没有导入就用随 APK 内置的 `assets/offline-mfi/`。
     *
     * 实现方式是在本进程内起一个只监听 `127.0.0.1` 的最小 HTTP 服务
     * （`CarMfiLoopbackServer`），把上游的"远程认证"后端指到自己身上。
     * 这样做的唯一目的是**上游零改动**——将来 `git merge upstream/master`
     * 不会因为多了一个枚举值而冲突。
     *
     * 身份缺失时不会静默失败：服务照常起来，但每个端点都返回带原因的错误，
     * 上游会把原因一路带到设置页/状态栏上。
     */
    LOCAL_OFFLINE,
}

/**
 * 无线热点形态。
 *
 * ⚠️ **2026-10-03 起不含 `LOCAL_ONLY_HOTSPOT`**（原枚举值已移除）。原因见
 * [ADB_HOSTAPD] 的注释：系统 LOH 在车机上有两个死结（随机 SSID、地址落错网卡），
 * 实测从没成功过一次，留着只是一条永远走不到的死路。
 *
 * ⚠️ **2026-10-13 起不含 `P2P_DIRECT`（Wi-Fi Direct 临时组网）**（原枚举值已移除）。
 * 它是照车机厂商方案加的一档（2026-10-11 曾一度成为出厂默认），现按车主口径整体撤掉，
 * 见 [hotspotMode] 的「历次默认值」表。**注意**：撤掉的只是**这一档热点模式**；
 * 上游 `:shared` 里给无线 CarPlay 建 Wi-Fi 链路的那套 P2P 组管理（`WifiP2pGroupManager` /
 * `LegacyWifiP2pGroupManager`）**原样保留**，与本枚举无关。
 *
 * 现可选形态为：**ADB 自建热点**（[ADB_HOSTAPD]）、**局域网连接**（[SAME_LAN]）与
 * **复用车机 TCAM 热点**（[TCAM_HOTSPOT]）；[AUTO] 与 [MANUAL] 是保留的存量枚举值，
 * 不显示在选择器中（2026-10-16 起 `MANUAL` 从界面移除、由 `TCAM_HOTSPOT` 取代，
 * 但**枚举值保留**，老安装持久化里的 `"MANUAL"` 会在解析/迁移层等价到 `TCAM_HOTSPOT`）。
 */
enum class CarHotspotMode {
    /**
     * **attach 到一张已经在跑的热点**（不是新建）。
     *
     * ⚠️ 别照名字理解：上游 `ManualHotspotManager` 的语义是"接管本机现有热点"——
     * 它只读 `SoftApConfiguration` 找接口，**从不调用 `startSoftAp`**，与"反射起一个
     * 自定义 SoftAP"完全是两回事。
     *
     * 本工程把它用在两个场景：用户在设置页**手动指定**车机热点的 SSID；以及
     * `AUTO` 探测到车机热点后的**复用**（见 `CarHotspotResolver.reuse`）。
     *
     * ⚠️ **2026-10-16 起不再由设置页提供**（存量值专用）：车主定案由
     * [TCAM_HOTSPOT] 取代「复用车机热点」这一档。枚举值**刻意保留**——老安装
     * SharedPreferences 里存着 `"MANUAL"`，删掉会让解析器 `when` 漏一支；现在它只在
     * `SharedPreferencesCarSettingsStore` 的解析/迁移层被等价到 `TCAM_HOTSPOT`。
     */
    MANUAL,

    /**
     * **ADB 独立热点：完全绕开 Android 的 Wi-Fi 框架，自己用 `hostapd` + `busybox udhcpd` 起热点。**
     *
     * ## 为什么需要它
     *
     * 上面两种模式都建立在 Android 的 Wi-Fi 框架上，而框架在车机上有两个死结：
     * ① `startLocalOnlyHotspot` 每次随机生成 SSID/密码，且部分车机把它藏在
     *    `getSoftApConfiguration()`（需 `NETWORK_SETTINGS` 系统权限）后面，三方 App 读不到；
     * ② 地址由系统选网卡，车机上可能落到蜂窝口或只给出 link-local IPv6
     *    ⇒「iPhone 拿到 DHCP 却永远不连 AirPlay 7000」。
     *
     * 本模式先开通车机 ADB，拿到 shell（再 `su 0` 到 root）后由 DBPlay 自己决定
     * 网卡、网段、网关、信道、SSID 与密码，行为完全可预期。
     *
     * ## 参数
     *
     * 网卡 / 网段 / 网关 / DHCP 池 / 信道**全部在运行时按车机实际环境选**（候选池见
     * `CarAdbHotspotDefaults`，选择逻辑见 `CarAdbHotspotPlanner`），没有任何写死值——
     * 早期版本把参考实现的 `192.168.175.1` / `wlan0` / 信道 36 当常量，换一台车就废。
     * **不做 NAT**（只提供本地三层可达性，CarPlay 不需要上网）。
     * SSID/密码取设置页的 [manualHotspotSsid] / [manualHotspotPassphrase]，
     * 出厂即 `DBPlay` / `12345678`。
     *
     * ## 零上游改动
     *
     * 映射到上游既有的 `WirelessHotspotMode.MANUAL`——它的语义本就是
     * **"attach 到一张已经在跑的热点"**（只读配置、从不 `startSoftAp`），
     * 正好用来把我们已经起好的这张网交给上游。⇒ 不需要 PATCH-0002。
     *
     * ## 失败回落
     *
     * ⚠️ **2026-10-03 起，拉起失败不再回落到系统 LOH**（该形态已整体移除，见文件头
     * 枚举注释）。车机差异大（网卡名、`su` 是否存在、`hostapd` 路径、ABI），
     * 拉起失败时把**失败在哪一步**写进诊断，由用户在设置页改「热点方案」重试。
     * 见 `ADB-HOTSPOT.md`。
     */
    ADB_HOSTAPD,

    /**
     * **自动（出厂默认）：车机已经有热点就用它，没有才自建一个。**
     *
     * 4G 车机大多自带 Wi-Fi 热点。这时再叠一层热点是有害的：iPhone 要在一张陌生网络
     * 和车机网络之间二选一，而车机的 AP 往往还承担着流量出口，两层 NAT 会让
     * AirPlay 的 mDNS/回连更容易断。所以"能复用就复用"应当是默认行为。
     *
     * 判定顺序（实现见 `CarHotspotResolver`，2026-09-29 重排）：
     * 1. **ADB 探测**到车机正在跑的热点且拿到 SSID → 复用它（上游 `MANUAL` 只 attach）；
     * 2. ADB 不可用时退**本进程反射**读一次，读到同样复用；
     * 3. 都没有 → **自建**（[ADB_HOSTAPD]）。
     *
     * ⚠️ **2026-10-03 起不再有第 4 步回落**：原来 ADB 拉起失败会回落到系统 LOH，
     * 该形态实测从没成功过、已整体移除。现在第 3 步失败就是失败，诊断里写明卡在哪一步。
     *
     * ⚠️ **退出时绝不关闭复用来的那张网络**：它是车机的，不是我们的。
     * 由 `CarHotspotManager.close()` 用空实现保证。
     */
    AUTO,

    /**
     * **局域网连接（Same LAN，PATCH-0010）：车机与 iPhone 在同一个局域网里，不建任何热点。**
     *
     * ⚠️ 界面文案是「**局域网连接**」，不叫「同一 Wi-Fi」：后者描述的是"前提"
     * （两张网得是同一张），前者描述的是"它到底是什么"（沿局域网直连）。
     * 用户要理解的是后者——他不需要先知道"Wi-Fi"这个前提，只需要知道"别建热点、走局域网"。
     *
     * ## 为什么需要它
     *
     * 前三种模式的前提都是"**由车机提供那张网**"（自己起，或从车机现成的热点接管）。
     * 但现场有第三类环境：车机本身就在一张 Wi-Fi 上（4G 车机连手机热点、或车机
     * 连着车内路由器），而 iPhone 也连在同一张网上。这时再自建一张热点是**纯负担**：
     * 多层 NAT 让 AirPlay 的 mDNS 与反向回连更容易断，用户还要为"该连哪张网"做选择。
     *
     * 本模式**一个热点都不建**：直接把车机当前所在局域网里的地址通告给 iPhone，
     * 让它沿同一张网直连 AirPlay 7000。
     *
     * ## 与 [MANUAL] 的关键区别
     *
     * [MANUAL] 找的是**本机的 AP 接口**（`ManualHotspotManager` 只认 ap / wlan / p2p
     * 开头的网卡，见 `looksLikeApInterface`），本模式要的是 **STA 侧地址**——车机作为客户端
     * 在路由器上拿到的那个 IPv4。两者在网卡枚举上有交集（都是 `wlan*`），
     * 但判据相反：前者要"这网卡在当 AP"，后者要"这网卡连着别人"。
     * ⇒ **不能靠 `ManualHotspotManager` 复用**，必须新增一支（见 `CarSameLanReader`）。
     *
     * ## 前置条件（不满足时应如实报错，不要静默滑回自建）
     *
     * 1. 车机已连上一张 Wi-Fi，且拿到**非 link-local 的 IPv4**；
     * 2. iPhone 连的是**同一张网**（这我们无从验证，只能靠用户自己确认）；
     * 3. 路由器没有开"客户端隔离"（AP isolation）—— 开了的话同一张网内的两台
     *    设备也互相 ping 不通，AirPlay 必然失败，而这**在车机上完全看不出来**。
     *
     * 第 3 条是这一模式最主要的现场失败原因，所以设置页必须把这个前提写清楚，
     * 而不是等用户连不上再猜。
     *
     * ## 上游改动
     *
     * 上游 `CarPlayController.startWirelessHotspot()` 的 `when (hotspotMode)` 对三个值
     * **穷举**且三条都调 `manager.start()` ⇒ 上游**不存在**"跳过热点"这条路。
     * 本模式必须 PATCH 该函数（见 `PATCHES.md` 的 PATCH-0010），加一条
     * "不建热点、只读 STA 地址"的分支。
     */
    SAME_LAN,

    /**
     * **车机 TCAM 热点复用（backend = TCAM_HOTSPOT）：原厂 TCAM 热点已开时，自动识别接入卡与地址。**
     *
     * ## 为什么是独立一档，而不是并入 [MANUAL]
     *
     * [MANUAL] 的地址由用户手填（车主口径：复用车机热点要用户自己设置）；而本档
     * 照搬 DiPlay 的判据——`ConnectivityManager.getAllNetworks()` → `TRANSPORT_ETHERNET` →
     * 接口名 == `eth0.11` → 地址里**恰好一个 IPv4**——由 App **自动识别**地址，
     * 不要求用户在设置页填车机热点地址。
     *
     * ## 与 [MANUAL] 相同的部分
     *
     * 两者都**只复用、不自建**：绝不主动开热点（`isWifiApEnabled` 为 false 立刻报错），
     * 上游都映射成 `WirelessHotspotMode.MANUAL`（attach 到已在跑的网，从不 `startSoftAp`），
     * 最终接入地址都走 `manualHostAddress` / `manualHostInterfaceName` 那一支。
     *
     * ## 与 [MANUAL] 不同的部分
     *
     * - 接入卡写死 `eth0.11`（DiPlay 口径，本车实测 `198.18.34.15/16`，证据包 §A.1）；
     * - 有**状态机与自愈**：`registerNetworkCallback` 监听 `onLost` /
     *   `onLinkPropertiesChanged`，链路失效即置 invalidated 并触发无线会话重启；
     * - SSID / 密码 / 加密 / 信道 / 频段通过反射 `WifiManager.getWifiApConfiguration()`
     *   读取，读不到才回落到设置页保存值。
     *
     * 实现见 `TcamHotspotManager`（`:car:core-bridge`）与纯逻辑层 `CarTcamHotspot`（`:car:api`）。
     */
    TCAM_HOTSPOT,

    /**
     * **车机热点（`backend = GEELY_ROUTED`）：扫描任意 `eth*` 上的 `198.18.x.x`，凭据由用户手填。**
     *
     * ## 与 [TCAM_HOTSPOT] 是两条独立链路（2026-10-25 新增）
     *
     * 车主口径：「两个独立实现并行验证、互不干扰，待实车结果明确后再移除其中一个」。
     * 因此本档**不是** TCAM 的参数变体，而是独立的模式值：设置页单独一个选项、
     * 诊断单独一个 `source=geely-routed` 标识、失败各自报错、**绝不相互回退**。
     *
     * ## 来源（参考 APK 只读核实，不是凭类名推断）
     *
     * 依据 `GeelyCarPlay-Settings-Refined-debug.apk`（`io.dushu.car` v0.3.2-debug）
     * 的 `GeelyRoutedHotspotManager` + `GeelyRoutedHostReadiness`。要点：
     * - **它不启动热点**：没有反射系统服务、没有 `startSoftAp`/`startTetheredHotspot`，
     *   Manifest 也没有 `NETWORK_SETTINGS`/`TETHER_PRIVILEGED`/`WRITE_SETTINGS`；
     * - 只枚举 `NetworkInterface` 找**已存在**的 `eth*` + `198.18.x.x` 地址；
     * - `ssid`/`passphrase`/`security` 是**要通告给 iPhone 的凭据**，由用户填写；
     * - 带**有界等待**（`await(timeoutMillis, scan, isClosed, onProgress)`）。
     *
     * ## 与 [TCAM_HOTSPOT] 的差别
     *
     * | | [TCAM_HOTSPOT] | 本档 |
     * |---|---|---|
     * | 网卡 | 写死 `eth0.11` | **任意 `eth*`**，`eth0` 优先 |
     * | 地址 | 自动识别，恰好一个 IPv4 | 必须 `198.18.x.x`（严格两字节） |
     * | 凭据 | 反射读车机 AP 配置 | **用户手填** |
     * | 预算 | 60 秒 | **5 秒**（见 `CarGeelyRoutedHost.SCAN_TIMEOUT_MILLIS`） |
     *
     * ## 为什么不是"自建热点"
     *
     * 普通三方 App 在 Android 9 上唯一能"起热点"的公开 API 是 LOH（随机 SSID/密码，
     * 本仓库已实车淘汰）；`startTethering`/`setWifiApEnabled` 需系统权限或系统签名。
     * 参考包同样做不到，所以本档**只复用、不新建**。
     *
     * 实现：纯逻辑层 `CarGeelyRoutedHost`（`:car:api`）+ 地址扫描器
     * `GeelyRoutedHotspotManager`（`:shared`）+ 桥层
     * `CarPlaySessionBridge.ensureGeelyRoutedReady`；最终仍由上游
     * `ManualHotspotManager` 以「精确 iface + IP」attach（不新建 manager 类）。
     */
    GEELY_ROUTED,
}

/**
 * 复用类档位：**只 attach 车机现有热点、绝不自己起网**的那两档。
 *
 * [MANUAL] 是 2026-10-16 之前界面上的「复用车机热点」（现为存量值专用），
 * [TCAM_HOTSPOT] 是当前的「复用车机热点」。两档在设置页里的**身份类信息完全一致**：
 * 车机自带热点开没开、车机侧的真实 SSID / 密码、手填名字与车机真名的比对。
 *
 * ⚠️ **这些点必须共用本判据**。它们原先各自写 `== CarHotspotMode.MANUAL`，
 * 2026-10-16 档位由 MANUAL 换成 TCAM_HOTSPOT 后**同时静默失效**——
 * 「车机自带热点」读数行、一键填入按钮、名字填错警告、
 * `CarNativeHotspotCredentials` 全都不再出现，且**没有任何编译或测试信号**。
 */
val CarHotspotMode.reusesCarNativeHotspot: Boolean
    // ⚠️ 2026-10-25：**故意不含 GEELY_ROUTED**。
    //
    // 这个属性的用途是"能不能反射读车机 AP 配置、在设置页显示一键填入的真实凭据"。
    // 车机热点（GEELY_ROUTED）的凭据是**用户手填**的，压根不读车机那份配置 ——
    // 若把新档纳入，界面会出现"一键填入车机凭据"按钮，点下去填的却不是本档要用的值，
    // 车主会以为填错了。两档的凭据来源不同，这条判据就必须不同。
    get() = this == CarHotspotMode.MANUAL || this == CarHotspotMode.TCAM_HOTSPOT

/** 手动热点频段。 */
enum class CarHotspotBand {
    AUTO,
    GHZ_2_4,
    GHZ_5,
}

/** 手动热点加密方式。 */
enum class CarHotspotSecurity {
    OPEN,
    WPA2,
    WPA3_TRANSITION,
    WPA3,
}

/** 物理尺寸参考基准。 */
enum class CarPhysicalSizeBasis {
    /** 以最宽宽度为参考。 */
    WIDEST_WIDTH,

    /** 以最长高度为参考。 */
    LONGEST_HEIGHT,
}

/** 驾驶侧。影响 CarPlay 界面镜像与安全带提醒位置。 */
enum class CarDrivingSide {
    LEFT,
    RIGHT,
}

/**
 * 无线 CarPlay 握手完成（iPhone 下发 `disableBluetooth`）后，DBPlay 如何处理车机蓝牙。
 *
 * ## 背景：iPhone 会明确要求"关蓝牙"
 *
 * 无线 CarPlay 的启动要借蓝牙：iPhone 先用 RFCOMM 上的 iAP2 把 Wi-Fi 凭据交给车机
 * （bootstrap），AirPlay 建好之后，iPhone 会下发一条 `disableBluetooth`，要求车机
 * **断掉这条蓝牙链路**——命令体里带 `deviceID` = 本次 iPhone 的蓝牙地址
 * （实车抓包：`params={deviceID: "1c:8e:2a:49:29:f6"}`）。
 *
 * ## 上游只关了"自己那条管子"
 *
 * 上游（以及本工程此前的版本）在这条命令上只做一件事：关掉**本进程**的 RFCOMM
 * socket（`closeBluetoothBootstrapTransport()`）。系统蓝牙与 iPhone 之间的
 * ACL/profile 连接**一点没动**。
 *
 * 实车诊断包（设备 `d5ddb4312da1`，2026-10-09 20:51）里这条因果是完整的：
 *
 * | 时刻 | 发生了什么 |
 * |---|---|
 * | `20:51:27.308` | 收到 `disableBluetooth`（`deviceID=1c:8e:2a:49:29:f6`）|
 * | `20:51:27.744` | 只关了进程内 socket（`BluetoothSocket ... CONNECTED → CLOSED`），系统蓝牙未动 |
 * | `20:51:48.106` | iPhone 关掉 AirPlay control socket ⇒ `AirPlay session ended`（约 21 秒后）|
 * | `20:51:48.351` | 又从头重连一次 RFCOMM（说明 iPhone 认为这次握手失败）|
 *
 * ⇒ iPhone 等的是"蓝牙真的断了"，等不到就把会话掐掉。本枚举就是这条修复的力度旋钮。
 */
enum class CarBluetoothHandoffPolicy {
    /**
     * **什么都不做** —— 与上游原版行为一致。
     *
     * 保留它是为了**可回退**：若某台车机上"定向断开"反而把会话搞坏，
     * 现场可以一键回到上游行为，不必等新版本。
     */
    OFF,

    /**
     * **定向断开**：只断开系统蓝牙与本次 CarPlay 手机之间的连接。
     *
     * 2026-10-21 新实现（方案 §2）：主路径改为**特权 `app_process` 独立进程**
     * （uid 0 或 shell uid）直调 `BluetoothDevice.disconnect()`，
     * 应用进程内反射只作为快路径。**全程不碰蓝牙适配器**，车上其它蓝牙设备
     * （耳机、胎压、车钥匙…）不受影响。
     *
     * 本档需要 ADB / root 通道：通道不可用时**只记日志、保持连接**，
     * 绝不自动回退到 [DISABLE_ADAPTER]。
     */
    DISCONNECT_LINK,

    /**
     * **关闭蓝牙适配器**：字面执行 `disableBluetooth`，会话结束时再恢复。
     *
     * ⚠️ **非默认、仅用户显式选择时生效**（2026-10-21 车主口径：
     * 默认不能关车机蓝牙）。代价是**车机上所有蓝牙设备都会被断开**
     * （耳机、胎压、车钥匙…），并且必须保证会话结束时把适配器重新打开——
     * 否则下次无线 CarPlay 会因为连不上蓝牙 bootstrap 直接失败。
     *
     * 恢复路径有两条，缺一不可：
     * 1. 会话结束（`onSessionEnded`）；
     * 2. **下次进程启动**（`CarBluetoothHandoffPrefs` 落盘了"我们关过它"）——
     *    覆盖"会话中途进程被杀/装自己重启"这条路径，否则蓝牙会一直停着。
     */
    DISABLE_ADAPTER,
}

/**
 * 「核心安全区」= 一块**矩形**（px）。
 *
 * ## 🔴 只作用于仪表副屏（2026-10-11，PATCH-0094；车主口径「仪表导航 的安全区只限副屏」）
 *
 * 这个矩形**唯一**的消费方是副屏那条链：`CarAppController.resolveClusterSpec()`
 * → `CarClusterSafeAreaPolicy` → `CarClusterSpec.safeArea` → `/info` 的副屏声明。
 * **主屏不读它**（`CarPlayConfigBuilder.airPlayConfig` 里主屏只剩"沉浸模式自动避让顶部"
 * 一个来源）。理由：它挂在「仪表导航 → 核心安全区」下，用户画的是仪表那块面板；
 * 此前主屏也吃这份值 ⇒ 画一个"上方 720px"会把主屏的 CarPlay UI 一起挤到上方一条，
 * 现场表现是"我只调了仪表，主屏怎么变小了"。
 *
 * ## 坐标系（2026-10-11 与画框 UI 对齐后的定案）
 *
 * **坐标系 = 主屏上报给 iPhone 的那块画布**（不是物理面板尺寸）。
 * 依据：设置页的画框（`SafeAreaFrameEditor`）就是按 `CarUiModel.safeAreaCanvasWidthPx/
 * safeAreaCanvasHeightPx` 预览的；两处必须是同一个空间，否则"拖出来的框"与"报出去的数"不成比例。
 * 报给副屏时由 `CarAppController.resolveClusterSpec()` 用
 * `scaledTo(主画布 → 副屏画布)` 换算过去。
 * （⚠️ 借用主画布的宽高做比例基准 **≠** 作用于主屏 —— 只是因为画框 UI 在那块画布上预览。）
 *
 * ## 🔴 2026-10-11 从"四边内边距"改成"矩形"（PATCH-0080，车主口径：按矩形模型 + 画框改）
 *
 * ### 为什么必须改（真车事故）
 *
 * 旧模型存的是四边内边距，外部直接把四个数塞进 `/info` 的 `safeArea`。CX11 真车日志里
 * 两块屏都报了 `safeArea={width 1912, height **−1560**, originY 1920}`（面板 1920×1080）——
 * 反解出来是 `top=1920`（**比面板还高**）⇒ 高度为负 ⇒ **iPhone 直接拆会话**
 * （`iAP tunnel peer EOF` + 空 TEARDOWN，视频流一条都没建）。
 *
 * ### 原版怎么做（`GeelyClusterSafeArea`，即设置页那个"画框"）
 *
 * 画框存的是矩形 `(x, y, w, h)`，**先归一化再转内边距**：
 *
 * ```
 * normalized(canvasW, canvasH)：w/h 夹进画布（最小 300×200，或画布本身更小就取画布），
 *                              x/y 夹进 0..canvas−w/h
 * toInsets(canvasW, canvasH) ：top=y、bottom=(H−y)−h、left=x、right=(W−x)−w
 * ```
 *
 * ⇒ 四边**恒 ≥0 且自洽**（`top+bottom+h == H`）。**结构上不可能发出负宽高。**
 *
 * ### 本模型的三条纪律
 *
 * 1. 坐标空间是**主画布**（画框是在那块画布上拖出来的），报给副屏时先 `scaledTo()`
 *    到副屏面板、再 `toInsets()`；
 * 2. [width]/[height] `<= 0` 表示**未设置**（= 整块都可用），此时由调用方
 *    （`CarClusterSafeAreaPolicy`）决定是否套用副屏默认核心区 1920×720；
 * 3. 任何出口都必须先 [normalized] 或 [toInsets] —— **不允许**有人再把 `x/y/w/h`
 *    当内边距直接塞给上游（那正是这次事故的成因）。
 */
data class CarSettingsSafeArea(
    val x: Int = 0,
    val y: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
) {
    /** 未设置（宽或高非正）：整块面板都可用。 */
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    /**
     * 归一化到某块画布 —— **逐条照抄原版 `GeelyClusterSafeArea.normalized()` 的夹取顺序**：
     * 先把 `w/h` 夹进画布（下界是 `min(MIN_, canvas)`），再把 `x/y` 夹进 `0..canvas−w/h`。
     *
     * 顺序不能反：先夹坐标再夹尺寸的话，"w 比画布还大"时 `canvas−w` 会是负数，
     * `coerceIn(0, 负数)` 会**抛异常**（Kotlin 的 `coerceIn` 要求区间非空）。
     */
    fun normalized(canvasWidthPixels: Int, canvasHeightPixels: Int): CarSettingsSafeArea {
        require(canvasWidthPixels > 0 && canvasHeightPixels > 0) {
            "canvas dimensions must be positive"
        }
        val w = width.coerceIn(minOf(MIN_WIDTH_PIXELS, canvasWidthPixels), canvasWidthPixels)
        val h = height.coerceIn(minOf(MIN_HEIGHT_PIXELS, canvasHeightPixels), canvasHeightPixels)
        return CarSettingsSafeArea(
            x = x.coerceIn(0, canvasWidthPixels - w),
            y = y.coerceIn(0, canvasHeightPixels - h),
            width = w,
            height = h,
        )
    }

    /**
     * 从一块画布换算到另一块（照原版 `GeelyClusterSafeArea.scale()`）：
     * `x/w` 按宽的比例、`y/h` 按高的比例，最后统一 [normalized] 兜底。
     */
    fun scaledTo(
        fromWidthPixels: Int,
        fromHeightPixels: Int,
        toWidthPixels: Int,
        toHeightPixels: Int,
    ): CarSettingsSafeArea {
        require(fromWidthPixels > 0 && fromHeightPixels > 0) { "from dimensions must be positive" }
        require(toWidthPixels > 0 && toHeightPixels > 0) { "to dimensions must be positive" }
        return CarSettingsSafeArea(
            x = x.toLong().times(toWidthPixels).div(fromWidthPixels).toInt(),
            y = y.toLong().times(toHeightPixels).div(fromHeightPixels).toInt(),
            width = width.toLong().times(toWidthPixels).div(fromWidthPixels).toInt(),
            height = height.toLong().times(toHeightPixels).div(fromHeightPixels).toInt(),
        ).normalized(toWidthPixels, toHeightPixels)
    }

    /**
     * 转成四边内边距（照原版 `GeelyClusterSafeArea.toInsets()`）：
     * `top=y`、`bottom=(H−y)−h`、`left=x`、`right=(W−x)−w`，**四边恒 ≥0**。
     */
    fun toInsets(canvasWidthPixels: Int, canvasHeightPixels: Int): CarClusterSafeAreaInsets {
        val area = normalized(canvasWidthPixels, canvasHeightPixels)
        return CarClusterSafeAreaInsets(
            left = area.x,
            top = area.y,
            right = (canvasWidthPixels - area.x) - area.width,
            bottom = (canvasHeightPixels - area.y) - area.height,
        )
    }

    companion object {
        /** 最小可画尺寸（原版是 `min(300, canvasW)` / `min(200, canvasH)`，这里同口径）。 */
        const val MIN_WIDTH_PIXELS = 300
        const val MIN_HEIGHT_PIXELS = 200
    }
}

/**
 * CarPlay 会话设置：**UI 侧唯一的设置模型**。
 *
 * 为什么放在 `:car:api` 而不是 `:car:core-bridge`：
 * 设置页需要渲染这些值，而 UI 只允许依赖 `:car:api`。
 * 所以模型必须在这里，`:car:core-bridge` 负责把它翻译成 upstream 的
 * `CarPlayRuntimeConfig` / `AirPlayConfig`。
 *
 * 所有枚举都是**平台无关**的自有枚举（不是 upstream 的 `MfiTarget` 等）。
 * 代价是 core-bridge 里多一层枚举映射，收益是 UI 永远不引用 upstream 类型——
 * 上游改枚举时改映射函数即可。
 *
 * **默认值与 upstream `CarPlayHostActivity` 的出厂默认逐项一致**，三处例外：
 * 1. [mfiTarget]——本工程默认走本机软件认证（见该字段说明）；
 * 2. [deviceName] / [manufacturer] / [model] / [oemLabel]——出厂标识统一为
 *    [DEFAULT_IDENTITY]（`DBPlay`），这样 iPhone 的 CarPlay 设备列表与 AirPlay
 *    菜单里第一眼就能区分是二开版还是上游原版；
 * 3. [hotspotMode]——**本独立产品默认 [CarHotspotMode.GEELY_ROUTED]（车机热点）**，
 *    而上游默认是 `WIFI_P2P`。历次默认值与每次改动的代价见该字段。
 * 除此之外，二开版在默认设置下的行为与上游原版相同。
 */
data class CarSessionSettings(
    // ------------------------------------------------------------ 身份与外观
    val deviceName: String = DEFAULT_IDENTITY,
    val manufacturer: String = DEFAULT_IDENTITY,
    val model: String = DEFAULT_IDENTITY,
    /**
     * OEM 标签。上游出厂默认是空串（= 不在 CarPlay 侧显示品牌），
     * 二开版统一填 [DEFAULT_IDENTITY]。
     */
    val oemLabel: String = DEFAULT_IDENTITY,
    val drivingSide: CarDrivingSide = CarDrivingSide.LEFT,

    // ------------------------------------------------------------------ 连接
    /**
     * 连接方式。**2026-10-06 起出厂默认 [CarTransportMode.WIRELESS]。**
     *
     * ⚠️ 为什么从 WIRED 改过来：本工程二开的目标场景就是**无线 CarPlay**
     * （自建 DBPlay 热点 + 手机连上直接投屏），有线只是备选。出厂默认无线
     * ⇒ 用户装完打开就能连，不必先发现"要去设置里把连接方式改成无线"。
     *
     * 老安装不受影响：已持久化的 `transport` 会原样读回
     * （见 `SharedPreferencesCarSettingsStore.load`），只有全新安装才吃这个默认值。
     */
    val transport: CarTransportMode = CarTransportMode.WIRELESS,

    /**
     * MFi 认证目标。默认 [CarMfiTarget.LOCAL_OFFLINE]。
     *
     * **这是本工程相对上游的一处出厂默认偏离**：上游默认走 CH341 硬件，
     * 本工程默认走本机软件认证——因为二开的目的就是摆脱 MFi 硬件。
     * 其余偏离项见类注释。
     *
     * 硬件用户不受影响：老安装里已经持久化的 `mfi_target` 会原样读回
     * （见 `SharedPreferencesCarSettingsStore.load`），只有全新安装才吃这个默认值。
     */
    val mfiTarget: CarMfiTarget = CarMfiTarget.LOCAL_OFFLINE,
    val linuxI2cPath: String = DEFAULT_I2C_PATH,
    val remoteMfiServer: String = "",
    val remoteMfiToken: String = "",
    /**
     * 无线热点形态。
     *
     * **2026-10-16 起出厂默认 [CarHotspotMode.TCAM_HOTSPOT]（复用车机 TCAM 热点）。**
     * 车主口径（2026-10-16）：删掉 `MANUAL`（复用车机热点）这一界面选项、让 `TCAM_HOTSPOT` 取代它。
     *
     * ## 历次默认值（都留在这里，免得下次有人以为"一直就是这个"）
     *
     * | 时间 | 默认 | 为什么 |
     * | --- | --- | --- |
     * | 更早 | [CarHotspotMode.AUTO] | 先探测车机原生热点、能复用就复用 |
     * | 2026-10-04 | [CarHotspotMode.ADB_HOSTAPD] | `AUTO` 要先跑 21 条 shell 探测，实车 3.1~23.2 秒（3 条 `dumpsys` 单条可卡十几秒）；自建不探测，近乎秒开 |
     * | 2026-10-11 | ~~`P2P_DIRECT`~~ | 照厂商（车机自带 `com.geely.carlink`）那条路：P2P 才是这台车声明的连接方式（`setSupportP2P(true)` + `setSupportSoftAP(false)`），而它过去失败的真因是"车机自带热点一直开着"（P2P 与 SoftAP 互斥） |
     * | **2026-10-13** | **[CarHotspotMode.MANUAL]** | `P2P_DIRECT` 已整体移除（车主口径），默认改回复用车机热点 |
     * | **2026-10-16** | **[CarHotspotMode.TCAM_HOTSPOT]** | `MANUAL` 从界面移除、由 `TCAM_HOTSPOT` 取代；默认改回复用车机 TCAM 热点 |
     *
     * ## 为什么撤掉 `P2P_DIRECT`（车主已定，写在这里备查）
     *
     * 1. **它要先把车机自带热点关掉**（原 `prepareEnvironment` 按厂方口径关；不关则框架
     *    `createGroup` 一直回 `BUSY`）—— 那等于**动了车机自己的网络**；
     * 2. **SSID/口令由系统生成**（`DIRECT-xx-…`），不是 [DEFAULT_HOTSPOT_SSID] 那个固定名。
     *    iPhone 会把随机 SSID 当陌生网络重学，重连表现不稳（2026-10-04 当初选"自建"的
     *    理由之一）；
     * 3. ⇒ 撤销后，[CarHotspotMode.MANUAL] 不额外占射频、不动车机的网络，是代价最小的默认。
     *
     * ## 存量安装的迁移
     *
     * 老安装的 `hotspot_mode` 持久化值若是已移除的 `P2P_DIRECT`，读回时**显式迁移**到本默认
     * （见 `SharedPreferencesCarSettingsStore.load` 的 `MIGRATED_HOTSPOT_MODES`），
     * **不静默崩、也不会变成别的模式**；设置页会就这次回退给一行提示。
     */
    // 2026-10-16 起默认复用车机 TCAM 热点（`MANUAL` 从界面移除，由 `TCAM_HOTSPOT` 取代）。
    // 老安装里已持久化的 `"MANUAL"` 由 SharedPreferencesCarSettingsStore 迁移/解析到 `TCAM_HOTSPOT`。
    val hotspotMode: CarHotspotMode = CarHotspotMode.GEELY_ROUTED,

    /**
     * 热点名称与密码。对 [CarHotspotMode.AUTO] / [CarHotspotMode.ADB_HOSTAPD] /
     * [CarHotspotMode.MANUAL] 都生效（AUTO 只在"车机没有现成热点"这一分支用到）。
     *
     * 默认值刻意写死而不是留空让系统随机：原生 LOH 的行为是**每次启动都重新随机
     * 生成 SSID 与密码**，而 iPhone 会把它当成一张陌生网络反复重学，重连表现极不稳定
     * （真机上表现为"连一下断一下"）。车机本来就该有稳定 SSID。
     */
    val manualHotspotSsid: String = DEFAULT_HOTSPOT_SSID,
    val manualHotspotPassphrase: String = DEFAULT_HOTSPOT_PASSPHRASE,
    val manualHotspotBand: CarHotspotBand = CarHotspotBand.AUTO,
    val manualHotspotChannel: Int = 0,

    /**
     * **[CarHotspotMode.MANUAL]（「复用车机热点」）要通告给 iPhone 的车机热点地址**
     * ——车机那张 AP 网卡上的网关 IPv4（本车 `192.168.15.1`）。
     *
     * ## 为什么由用户填，而不是我们认（车主口径 2026-10-11）
     *
     * > 复用车机热点是要用户自己手动设置，而不是自动识别
     *
     * 这条口径是对两次真实事故的收口：`wlan0` 上可能同时挂着**车机热点的地址**
     * 与**我们自建热点的残留地址**，两者都是"一张 UP 的 Wi-Fi 网卡上的 `192.168.x.1`"。
     * 2026-10-11 的两份日志里我们分别认错过：
     *
     * | 包 | 我们把谁当成车机热点 | 后果 |
     * | --- | --- | --- |
     * | `1791624952142` | 自建残留 `192.168.176.1` 通告给 iPhone | 40 秒 `attach timed out` |
     * | `1791702691839` | 把 `192.168.176.1` 写成"车机热点网口" | 结论骗人，排查被带偏 |
     *
     * ⇒ 复用时**不猜**：地址由用户填，填了就**以它为准**
     * （见 `ManualHotspotManager` 的 `hostAddressOverride`）；留空则**拦下并告诉用户填什么**，
     * 而不是随便挑一个网卡上的地址发出去（那正是上面两次事故的形态）。
     *
     * ## 只在「复用」这一支生效
     *
     * [CarHotspotMode.ADB_HOSTAPD]（自建）的网段/网关由 `CarAdbHotspotPlanner` 选，
     * [CarHotspotMode.SAME_LAN] 通告车机在局域网里的地址（见 [sameLanHostAddress]）
     * —— 本字段对它们一律不读取。
     *
     * ⚠️ 只接受 IPv4 字面量（判据同 [sameLanHostAddress]）：AirPlay 的监听与
     * iPhone 的 mDNS 解析都按 IPv4 走。
     */
    val manualHotspotHostAddress: String = "",

    /**
     * 加密方式。默认 WPA2 —— 与 [DEFAULT_HOTSPOT_PASSPHRASE] 配套；
     * 选 OPEN 时密码会被忽略。
     */
    val manualHotspotSecurity: CarHotspotSecurity = CarHotspotSecurity.WPA2,

    /**
     * 旧版本「热点常开」设置字段，仅为读取旧偏好兼容保留。
     *
     * 新逻辑完全忽略此字段：DBPlay 自建热点只在 ADB_HOSTAPD 会话启动时按需建立，
     * 会话停止时收回，不安装 init 自启服务，也不在开机阶段恢复热点。
     */
    val keepHotspotAlive: Boolean = false,

    /**
     * 无线握手完成后如何处理车机蓝牙。
     *
     * ## 🔴 默认档：2026-10-11（PATCH-0095）从 `DISCONNECT_LINK` 改成 `DISABLE_ADAPTER`
     *
     * 起因：iPhone 在 AirPlay 建好后会下发 `disableBluetooth`（带 `deviceID`）要求
     * 车机断开蓝牙，而上游只关自己的 RFCOMM socket、不动系统蓝牙 ⇒ iPhone 等不到
     * "蓝牙已断"就把会话掐了（实车约 21 秒掉线，完整时间线见
     * [CarBluetoothHandoffPolicy] 的 KDoc）。
     *
     * 上一版为此加了 `DISCONNECT_LINK` 档（定向断开），但它在本车机上**没生效** ——
     * 实车日志里 `BluetoothDevice.disconnect()` 返回 null、复查仍处于连接态，
     * 代码自己都打出了"可把…改成「关闭蓝牙适配器」档"的提示。
     * 既然目标档是唯一实测有效的路径，就把它做成默认，用户不必自己去翻设置。
     *
     * ## 代价（写在这里，因为它是**默认值**的代价）
     *
     * `DISABLE_ADAPTER` 会关掉整个蓝牙适配器 ⇒ 车上其它蓝牙设备（耳机、胎压、
     * 车钥匙…）在会话期间一并断开，会话结束时由
     * `CarBluetoothHandoffReleaser.onSessionEnded()` 恢复。
     * 车主明确接受这个取舍（2026-10-11 确认「两个都要」）。
     *
     * 想回到旧行为或上游行为：[CarBluetoothHandoffPolicy.DISCONNECT_LINK] /
     * [CarBluetoothHandoffPolicy.OFF]，设置页「无线握手后的蓝牙处理」三选一。
     *
     * ⚠️ 老安装里已经持久化过 `DISCONNECT_LINK`（那是**旧出厂值**），
     * 靠 `SharedPreferencesCarSettingsStore.migrateBluetoothHandoffToDisableAdapter()`
     * 一次性改写；改 `load()` 的默认值对它们无效。
     */
    // 2026-10-21 车主口径：**不能关车机蓝牙**（会断耳机/胎压/车钥匙，且下次连接要等恢复）。
    // 默认改为 OFF —— 握手后完全不动蓝牙适配器。实测 AirPlay 接管后 iAP2 会自行 CLOSE，
    // 不需要外部断开。
    val bluetoothHandoffPolicy: CarBluetoothHandoffPolicy =
        CarBluetoothHandoffPolicy.DISCONNECT_LINK,

    /**
     * 旧版本的“允许内置 ADB”偏好，仅为持久化兼容保留，不再驱动 ADB 行为。
     * 新版由独立的一次性“永久开启 ADB（5556）”操作授权；普通 ADB 使用只检查并复用
     * 固定端口，不自动改系统属性。实际可用状态由 `CarAdbInitializationState` 实测给出。
     */
    val enableBuiltInAdb: Boolean = false,

    /**
     * 「启动性能优化」（2026-10-07，斌哥口径）。
     *
     * 原文："你在设置里面加入一个启动性能优化，启动后执行这条
     * `pm compile -m speed -f`"。
     *
     * 开 = App 每次启动时自动让系统把本 App 的字节码**完整 AOT 预编译**
     * （`pm compile -m speed -f <包名>`）。首次跑要几十秒，之后系统已编译
     * 好就不会重复干活，启动更快、运行更少 JIT 抖动。
     *
     * 默认 **false**：这是"按需优化"而非必须项，不该在用户没点之前
     * 就占掉一次几十秒的开机后台开销。
     */
    val startupPerfCompile: Boolean = false,

    /**
     * 「调试反连隧道」总开关（2026-10-10，斌哥口径）。
     *
     * ## 原文
     *
     * 斌哥：「我想让你在 DBPlay 开着模式里面加一个，服务器就是公网 IP」。
     *
     * ## 解决什么
     *
     * 改一行代码 → 出包 → 传包 → 车机手装，一轮十几分钟。开了这个，
     * **车机 App 启动就自动反连公网服务器**，开发者在服务器上
     * `adb connect 127.0.0.1:5556` 就能 logcat / shell / push，
     * 不用再出包（见 `LynkDebugTunnel`）。
     *
     * ## 语义
     *
     * 开 = App 启动即拉起反连隧道，断线自动重连；关 = 完全不起隧道。
     * 默认 **true**（2026-10-19 口径：方便后台随时看设备在线，直接远程调试）。
     * 老安装由 [SharedPreferencesCarSettingsStore] 的一次性迁移强制打开一次，
     * 用户之后手动关掉则永久保持关闭（迁移只跑一次）。
     *
     * ⚠️ 依赖 [enableBuiltInAdb]（车机 adbd 得在监听），但它**不负责**
     * 开 adbd——两者职责分开，见 [LynkDebugTunnel] 类注释。
     */
    val debugTunnelEnabled: Boolean = true,

    /**
     * 调试隧道对端**公网服务器地址**（IP 或域名）。
     *
     * 默认填斌哥指定的公网 IP。留空则等价于关闭隧道（启动前会判空）。
     */
    val debugTunnelHost: String = "39.108.105.65",

    /**
     * 调试隧道对端**服务器端口**（服务器上等待反连的口）。
     *
     * 默认 7000，对应服务器 `dbplay-tunnel.service`（socat 中继）
     * 的 `TCP-LISTEN:7000`。
     */
    val debugTunnelPort: Int = 7000,

    /**
     * 车机本机 **adbd 端口**（隧道把外部流量接到这里）。
     *
     * DBPlay 的全局 ADB 初始化固定配置 5556；调试隧道默认复用同一端口，避免继续连到
     * 厂商端口 5555。保留可配置项仅用于已验证的 ROM 特殊情况，不改变全局 ADB 端口策略。
     */
    val debugTunnelLocalAdbPort: Int = 5556,

    /**
     * 反连口令（2026-10-10，斌哥选"加口令"防 7000 裸奔）。
     *
     * 服务器中继会先校验这串口令，对不上直接踢 —— 避免端口被扫到就
     * 白捡一台车机的 ADB（能装 App / 读数据 / shell）。
     *
     * 车机与服务器两侧必须一致；留空 = 不校验（**不推荐**）。
     */
    // 2026-10-17（PATCH-0130）：默认口令必须 **≥12 位**——
    // 跳板 `relay.py` 的弱校验模式 `MIN_SECRET_LEN = 12`，11 位的旧默认
    // `dbplay-2026` 会被判「口令不匹配」直接断开，现场表现是
    // 「隧道开关开着、跳板一次拨号都看不到/连上即断」。
    val debugTunnelSecret: String = "dbplay-2026!",

    /**
     * 隧道身份 token（2026-10-17，VIN 身份鉴权 P1）。
     *
     * 由授权中心按 `hex(HMAC-SHA256(TUNNEL_KEY, "dbplay-tunnel:" + VIN))[:24]`
     * 派生后下发；车机只持有 token，**永不持有 TUNNEL_KEY**。
     *
     * 空串 = 不发 `tok=`，按 legacy 帧走 default 槽（兼容尚未接入授权中心的车机）。
     */
    val debugTunnelToken: String = "",

    /**
     * 仪表副屏的历史选中 **displayId**，默认 **-1 = 未选**。
     *
     * **本字段自 2026-10-20 起只做诊断留痕，不再参与选屏。** 当前投屏目标固定优先使用
     * displayId=2；固定屏不在场时由运行时按原规则自动挑选。字段与 SharedPreferences
     * 中的 key 均保留，用于 [com.dbtool.carplay.api.developer.CarDiagnosticSettingsDump]
     * 打印和历史配置兼容，不做迁移，也不要求用户再手动选择。
     *
     * ## 为什么要有它（2026-10-08）
     *
     * 之前仪表副屏是**代码自动挑**（`GeelyDisplaySelector.select` 的
     * 「非主屏 + 恰好 1920×1080 + 唯一命中」），它假设车上那块仪表屏**有且只有
     * 一块**。2026-10-08 现场日志推翻了该假设 —— 车上出现**不止一块**非主屏，
     * 自动挑抓错了屏，用户在**主屏**上看到本属于副屏的画面（报「选错了屏幕 /
     * 主屏有 1920×720 白块」）。
     *
     * 当时语义：由用户在设置页从屏列表里明确选一块，选中的 id 存这里。
     * 2026-10-20 已移除手动选屏 UI；新投屏目标规则见本 KDoc 开头。
     *
     * ⚠️ 用 `-1` 而不是 `0` 表示未选：`0` 是 `Display.DEFAULT_DISPLAY`（主屏），
     * 是合法值但用户不该选它当仪表，用 `-1` 可与「主屏」严格区分。
     */
    val clusterDisplayId: Int = -1,

    /**
     * 副屏画面是否**旋转 180°**。默认 `false` = 原样。
     *
     * ## 为什么要有它（2026-10-11）
     *
     * 副屏常常是**倒装**的：把一块 HDMI 屏塞进原仪表壳体，上下颠倒装。
     * 这种翻转是**物理**的 —— 车机 `DisplayManager` 报的 `rotation` 恒为 0，
     * 系统与 iPhone 都不知道，只能在呈现层把画面转回来。
     *
     * 真车实证（CS11 / `cs11_high`）：诊断包里 5 块屏旋转全为 0，
     * 而现场仪表画面是倒的。
     *
     * ## 生效时机
     *
     * 与 [clusterDisplayId] **不同**：本项只影响窗口里的画面朝向，**不参与**
     * 报给 iPhone 的声明（尺寸 / 安全区 / 帧率都不变）⇒ 切换**不需要重连**，
     * 由 `CarAppController.applySettings` 直接灌给呈现层并立即生效。
     */
    val clusterFlip180: Boolean = false,

    /**
     * 仪表「原厂全屏地图模式」守护链路灰度开关。**默认 false。**
     *
     * false = 完全沿用 PATCH-0046 起的直接路径（`seizeCluster("attach")` /
     * `dim.exitFullNavi()`），不记账、不回读、不加超时，日志与行为逐字不变。
     *
     * true = 启用 `GeelyClusterModeSession`：先记账再切模式、回读确认、
     * 首帧 5s 看门狗、退出确认后才清账本。
     *
     * ⚠️ 它**不是**「是否抢屏」的产品开关：真正的用户开关是投屏 / 不投屏。
     */
    val clusterModeGuardEnabled: Boolean = false,

    /**
     * DBPlay 本机导航卡片总开关：主页入口 + 仪表中央自绘卡片。
     *
     * 与「让 iPhone 在自己的投屏画面里画导航卡」的 [clusterNavigationCard] 无关：
     * 本开关只控制 DBPlay 自己的 UI / 皮肤绘制。开启后，仅在收到新鲜路线信息时
     * 显示；关闭则两处都隐藏。
     */
    val localNavigationCardEnabled: Boolean = true,

    /** 副屏内容「显示 iPhone 导航卡片」（原版 `GeelyClusterContent.showNavigationCard`）。 */
    val clusterNavigationCard: Boolean = false,

    /**
     * 副屏内容「仅地图布局控制（测试）」（原版 `mapLayoutTrial`）。
     *
     * 关闭导航卡片时，仍向「仅地图」内容请求发送对齐参数，用于验证车标布局。
     * [clusterNavigationCard] 优先：两者都开时按导航卡片走（原版 `when` 顺序）。
     */
    val clusterMapLayoutTrial: Boolean = false,

    /**
     * 副屏转向卡对齐：0=左侧 / 1=右侧 / 2=顶部。
     *
     * 按**下标**存（原版同款）：顺序由 `CarPlayClusterDisplay.MANEUVER_LAYOUTS` 决定。
     * 两个内容开关都关时 URL 不带 `maneuverLayout`，本值被忽略（设置页据此置灰）。
     */
    val clusterAlignment: Int = 0,

    /**
     * **允许无导航时手动开启仪表地图**（原版 `allow_manual_cruise`）。
     *
     * ## 语义（原版设置页原文，本工程未改其文案）
     *
     * > 开启后，无导航时四指左滑可手动开启，方控菜单可关闭。
     * > 开始导航自动开启，明确结束导航自动关闭；持续的无导航状态不会关闭手动巡航地图。
     *
     * 判据落在 `CarClusterCruiseGate`（纯状态机，有单测）；本项只决定
     * `canManualOpen()` 里的那一半条件 —— 关掉时，无导航就只剩"跟导航走"。
     *
     * ⚠️ 打开它**不代表**仪表会常驻地图：没有导航、也没手动开时仍然不显示。
     *
     * ## ⚠️ 当前没有任何消费点（2026-10-12，PATCH-0100）
     *
     * 当前仪表投屏没有接入无导航手动巡航判据；用户仍可通过设置页按钮手动投屏。
     * 详见 `CarClusterCruiseGate` 的类注释。
     * 本项因此目前只在设置页回显、并被诊断包原样打印（`CarDiagnosticSettingsDump`）。
     */

    /**
     * **HUD 导航转向提示推送开关**（2026-10-13，出厂默认**开**）。
     *
     * ## 它管什么
     *
     * 打开时，`CarPlaySessionBridge` 会把 iPhone 解出的导航转向提示推给车机侧的
     * 仪表/HUD（吉利 eCarx 是百度 hudbridge 服务，见 `CarHudBridge`）；
     * 关掉后**一帧都不推**。
     *
     * ⚠️ 与 [projectionEnabled] 是**两条不同的通道**，互不影响：
     *
     * | | [projectionEnabled] | 本项 |
     * | --- | --- | --- |
     * | 方向 | iPhone → **车机的一块副屏**（接管画面） | iPhone → **车机的 hudbridge 服务**（只给转向提示） |
     * | 生效前提 | 与 iPhone 握手时**声明**第二块屏 | 握手之后才开始的单向推送 |
     * | 关了会怎样 | 副屏上什么都没有 | 仪表上不出现 CarPlay 的转向卡片 |
     *
     * 关掉 HUD **不会**影响投屏，关掉投屏也**不会**影响 HUD。
     *
     * ## 为什么默认开
     *
     * 这条链路**已在实车上验证可用**（2026-10-13 车主确认：仪表能出转向卡片、
     * 中文路名正确、卡片不再闪烁）。默认关会让"新装用户永远看不到这个能力"，
     * 而那正是它存在的意义。开关的用途是**现场回退**：某台车机上它表现异常时，
     * 用户与现场能先关掉它，不必等新版本。
     *
     * ## 生效时机：**立即生效，不必重连**
     *
     * 关掉/打开当场生效（`CarPlaySessionBridge.updateSettings` 里直接重接一次 HUD 接线），
     * 因此它登记在 `CarSettingsReconnectPolicy.LIVE_FIELDS` 里。
     *
     * ⚠️ HUD 与 [projectionEnabled] / [clusterAutoShowOnRoute] 的投屏控制不同：HUD 是握手后
     * 才开始的**单向上报**，不需要 iPhone 同意，所以没有理由拖到下次重连。
     *
     * ## 关掉之后**哪一半留着**（刻意的不对称，别当漏改）
     *
     * 关掉只停"推送"，**不停** [CarPlaySessionBridge] 里那条导航状态订阅链路本身，
     * 也不影响方控、媒体卡片、导航音频 —— 它们与 HUD 无关。
     *
     * ## 为什么不做成"关掉连外部服务也解绑"
     *
     * 解绑会把 `GeelyHudBridge` 推进"已停止"态，而它过去**不可重启**
     * （`stop()` 置位后 `start()` 直接返回 ⇒ 关一次就永久失效）。那个坑已随本项一起修
     * （见 `GeelyHudBridge.start`）。即便如此，"关掉即解绑"仍然多一次绑定往返、
     * 重新打开时还要多等一次 `bindService`。现在的取舍与
     * [reportMediaCardToCar] 同形：**开关只决定"推不推"**，绑定由会话生命周期管
     * （会话起则连、会话停则断）。
     */
    val hudGuidanceEnabled: Boolean = true,

    /**
     * **HUD 使能通道开关**（2026-10-22）。默认 **false**。
     *
     * ## 它管什么
     *
     * 打开 = 会话建立后调用 ECarX 车控的 `CB_VF_HUD_ActvReq(1)`，会话结束时调用
     * `CB_VF_HUD_ActvReq(0)`（实现见 `EcarxVfhudChannel`）。这是**第三条** HUD
     * 通道，与上面两个开关互不影响：
     *
     * | | [hudGuidanceEnabled] | [hudProjectionEnabled] | 本项 |
     * | --- | --- | --- | --- |
     * | 内容 | 转向提示文字/箭头 | 整幅地图画面 | **只发 HUD 使能信号** |
     * | 渲染方 | 车机自带地图服务 | DBPlay 自己 | 车机 HUD 栈 |
     * | 数据 | CarPlay 导航快照 | 111 流镜像 | 无（只有 0/1） |
     *
     * ## 为什么默认关
     *
     * `ActvReq(1)` 是否真能让 HUD 玻璃出画面，**尚未实车验证**；通道不可用时
     * 本工程只记日志、不影响主流程（不弹窗、不阻塞连接）。在车主确认前不能让
     * 升级用户无感地动到车控 HUD 状态。通道诊断见 `HudChannelProbe`。
     *
     * 本项**就地生效**：打开/关闭时若会话已建立，当场 activate/deactivate，
     * 不参与 CarPlay 握手声明，登记在 `CarSettingsReconnectPolicy.LIVE_FIELDS`。
     */
    // 2026-10-22 车主选择「用新通道，不依赖外部 HudBridge APK」：
    // 默认开启。通道不可用时 EcarxVfhudChannel.probe() 返回 Unavailable，
    // CarAppController 只记日志跳过，不影响主流程。
    val hudPushEnabled: Boolean = true,

    /**
     * **HUD 地图投屏开关**（2026-10-22）。默认 **false**。
     *
     * 打开 = 在 1024×274 的 HUD 屏上开一个 `Presentation`，把 CarPlay 地图
     * （111 流的镜像）裁剪后画上去。
     *
     * ## 与 [hudGuidanceEnabled] 是两条路（别合并）
     *
     * | | [hudGuidanceEnabled] | 本项 |
     * | --- | --- | --- |
     * | 内容 | 转向提示文字/箭头 | 整幅地图画面 |
     * | 渲染方 | 车机自带地图服务 | DBPlay 自己 |
     * | 依赖 | 外部 hudbridge 服务 | 一块 1024×274 的 display |
     *
     * ## 默认关
     *
     * 投屏会占用一块屏 + 至少一个额外解码实例（111 mirror），且**HUD 玻璃是否
     * 真有像素尚未实车确认**（见 [com.dbtool.carplay.api.display.CarHudDisplayStatus.visibleOnHud]）。
     * 在车主亲眼确认前，不能让升级用户无感地多开一路解码。
     *
     * 本项**就地生效**：不参与 CarPlay 握手声明（声明与否由仪表副屏 spec 决定），
     * 登记在 `CarSettingsReconnectPolicy.LIVE_FIELDS`。
     */
    val hudProjectionEnabled: Boolean = false,

    /**
     * HUD 源画面裁剪（归一化四边，见 [HudCropSpec]）。
     *
     * 默认 [HudCropPreset.FULL_WIDTH]：任务书 2026-10-21 给定值
     * `(0, 0.55, 1, 0.803)` —— 全宽 + 中下部 25.3% 高的带子。
     *
     * ⚠️ 这条带子自身是 7.03:1，比 HUD 的 3.74:1 更宽：
     * [hudScaleMode] 选 FIT 会左右留黑边，选 FILL 会再裁两侧。
     * 想「严格 3.74:1、无黑边」请用 [HudCropPreset.CENTERED]。
     *
     * 裁剪在 GPU 纹理矩阵上完成（`TextureView.setTransform`），**不做 CPU 逐帧裁**。
     */
    val hudCrop: HudCropSpec = HudCropPreset.FULL_WIDTH.crop,

    /**
     * 裁剪区与 HUD 宽高比不一致时如何缩放。默认 [HudScaleMode.FILL]。
     *
     * - `FILL`：铺满 HUD，超出部分裁掉（超宽条屏最常用的选择）；
     * - `FIT`：完整显示裁剪区，不足处补黑边；
     * - `STRETCH`：忽略比例直接拉伸（会压扁，仅诊断用）。
     *
     * 就地生效，不重连。
     */
    val hudScaleMode: HudScaleMode = HudScaleMode.FILL,

    /**
     * [CarHotspotMode.ADB_HOSTAPD] 要用的 AP 网卡名。**留空 = 自动探测**。
     *
     * 为什么必须留一个手动口子：参考实现把网卡名写死成 `wlan0`，而实测线索显示
     * 目标车机的 AP 网卡是 `wlan2`（车机上 Wi-Fi 网卡的命名由厂商决定，
     * `wlan0`/`wlan1`/`wlan2`/`ap0` 都见过）。自动探测覆盖不了的情况，
     * 用户在这里填一次即可，不必等我们发版。
     *
     * 自动探测顺序（见 `CarAdbHotspotManager`）：
     * ① `wlan*` 且**没有 IPv4 地址**且不是默认路由网卡（空闲的 AP 网卡最像这个）；
     * ② `wlan*` 且不是默认路由网卡；
     * ③ 任意 `wlan*`。
     */
    val adbHotspotInterface: String = "",

    /**
     * [CarHotspotMode.SAME_LAN] 下通告给 iPhone 的本机地址。**留空 = 自动读当前网络**。
     *
     * 默认自动：从 `ConnectivityManager.activeNetwork` 的 `LinkProperties` 里取
     * 那个非 link-local 的 IPv4（见 `CarSameLanReader`）。这正是"车机在路由器上
     * 拿到的地址"，也就是 iPhone 能 ping 到的那个地址。
     *
     * 为什么仍留一个手动口子：自动读依赖 `ACCESS_NETWORK_STATE` 与厂商对
     * `LinkProperties` 的实现，个别 ROM 可能给不出 IPv4（只给 IPv6）或给错网卡。
     * 那种情况下用户在这里填一次即可。
     *
     * ⚠️ 只接受 IPv4 字面量：AirPlay 侧我们自己的监听与 iPhone 的 mDNS 解析
     * 都按 IPv4 走，填一个主机名或 IPv6 只会让连接更绕。
     */
    val sameLanHostAddress: String = "",

    /**
     * [CarHotspotMode.SAME_LAN] 下**通告给 iPhone 的那张 Wi-Fi 的 SSID**。
     *
     * ## 为什么必须有这个字段（2026-10-07 真机修复）
     *
     * 实测（手机 vivo V2271A / Android 14，诊断包 `1791342910937`）：SAME_LAN 模式下
     * 我们发给 iPhone 的 iAP2 `0x5703` / `0x4301` 里，**SSID 填的是热点名 `DBPlay`、
     * 密码填的是工厂默认 `12345678`** ⇒ iPhone 被指示去关联一张**根本不存在**的
     * 「DBPlay」（SAME_LAN 不建任何热点），于是永远不切 Wi-Fi
     * （`handoffRequested=false`）⇒ 40 秒超时 ⇒ 界面停在「无线通道就绪」。
     *
     * **iPhone 是照着 `0x5703` 的 SSID/密码去关联网络的，不是"已经在网里就忽略"。**
     * 所以这里必须填**车机当前所连那张 Wi-Fi 的真实 SSID**。
     *
     * 留空 = 自动读取当前所连 Wi-Fi 的 SSID（见 `CarSameLanReader`）。
     * 自动读依赖厂商对 `WifiInfo.getSSID()` 的实现，个别 ROM 会返回 `<unknown ssid>`
     * ⇒ 那时用户在这里手填一次即可。
     */
    val sameLanWifiSsid: String = "",

    /**
     * [CarHotspotMode.SAME_LAN] 下通告给 iPhone 的 Wi-Fi 密码。**通常留空**。
     *
     * ## 为什么默认留空、且往往填不了
     *
     * Android **读不到已保存网络的明文密码**（`WifiConfiguration.preSharedKey`
     * 自 Android 10 起对三方应用不可读）。而本模式的前提是"iPhone 已经连过/正在连
     * 这张网"——iPhone 自己有该网的密码，`0x5703` 里的密码对它只是冗余信息。
     *
     * 所以策略是：**能读到就填，读不到就留空**，不要拿热点密码去顶替
     * （那正是本次 bug 的成因）。若实测某些 iOS 版本要求非空密码，再由用户手填。
     */
    val sameLanWifiPassphrase: String = "",

    // ------------------------------------------------------------ 显示与视频
    /** 0.3x ~ 1.0x，十分位（10 = 1.0x）。口径与 upstream `displayScaleTenths` 一致。 */
    val displayScaleTenths: Int = DEFAULT_SCALE_TENTHS,
    val fps: Int = DEFAULT_FPS,
    val widthPhysicalMm: Int = DEFAULT_WIDTH_PHYSICAL_MM,
    val physicalSizeBasis: CarPhysicalSizeBasis = CarPhysicalSizeBasis.WIDEST_WIDTH,

    /**
     * 用**自定义分辨率**取代"面板实测尺寸 × 缩放系数"。
     *
     * ## 为什么需要它
     *
     * 默认链路是"UI 视频 View 的实测尺寸 × [displayScaleTenths]"，分辨率只能按
     * 0.1x 步进调整。但车机屏幕的实际参数千差万别（1024x600 / 1280x720 /
     * 1920x720 / 1920x1080 …），滑杆只能**逼近**目标值、永远落不到正好一档；
     * 而 CarPlay 握手分辨率必须与呈现区域一致，差一点就是拉伸或黑边。
     *
     * 打开这个开关后，直接按下面三项向 iPhone 声明，不再受滑杆步进与面板尺寸影响。
     *
     * ⚠️ 打开后 [displayScaleTenths] **不再参与计算**（自定义值就是最终值），
     * 但仍会被解码器上限（`CarVideoSizeLimiter`）兜底——那是"能不能解码"的
     * 硬约束，不是偏好。
     */
    val customDisplayEnabled: Boolean = false,
    /** 自定义宽度（px）。[customDisplayEnabled] 为 true 时生效。 */
    val customDisplayWidthPx: Int = DEFAULT_CUSTOM_WIDTH_PX,
    /** 自定义高度（px）。[customDisplayEnabled] 为 true 时生效。 */
    val customDisplayHeightPx: Int = DEFAULT_CUSTOM_HEIGHT_PX,

    /**
     * 自定义密度（dpi）。[customDisplayEnabled] 为 true 时生效。
     *
     * ⚠️ CarPlay 协议里**没有 dpi 字段**——iPhone 按"像素数 + 物理尺寸(mm)"
     * 推算界面密度。所以这里存的是 dpi，真正发出去的是由它换算的物理尺寸
     * （见 `CarCustomDisplay.physicalSizeMm`）：`mm = px / dpi * 25.4`。
     * 给定 dpi 就等于给定物理尺寸，这正是"精确调整"的入口。
     */
    val customDisplayDpi: Int = DEFAULT_CUSTOM_DPI,

    /**
     * 协商 HEVC (H.265) 而不是 H.264。
     *
     * **出厂默认关**，尽管上游默认是开——这是有真机证据的偏离：
     *
     * 1. 在 Holi（vivo PD2271）上，HEVC 与 H.264 被 `CarVideoSizeLimiter`
     *    夹到**完全相同**的画布（都是 `chosen=1920x896 decoderMax=1920x1920`），
     *    两者的像素统计也逐位一致。也就是说这台机器上 HEVC **换不到任何画质收益**。
     * 2. 同一条 HEVC 通路会**间歇性**整屏偏绿：画面几何、文字、图标全对，
     *    但视频区域的 R/B 通道 90%+ 的采样恰好为 0（色度丢失）。H.264 从未复现。
     *
     * 该故障已用「连续 5 轮冷启动会话」复现，频率实测如下（同一台机器、同一份 APK）：
     * - `c2.qti.hevc.decoder` @1920x896 → **2/4 绿**
     * - `c2.android.hevc.decoder` @1056x492（软解）→ 0/4
     * - `c2.qti.avc.decoder` @1920x896 → 0
     *
     * 性质已钉死为「**只有视频层坏**」：绿屏那一帧里，我们自己的 Compose UI
     * 颜色完全正常（胶囊 R/G/B≈221/226/225），只有 iOS 画面区域是 `(0, 95.2, 0)`
     * —— 正是 BT.709 limited 下 `Y=16, U=V=0` 的理论值 `(0, 95.5, 0)`。
     * 所以不是 screencap 失真、不是窗口合成问题。
     *
     * 已排除的原因（见 `VideoCodecConfigDiagnostics`）：hvcC 逐字节正常
     * （Main/4:2:0/8bit）、`csd-0` 确实带上了 VPS+SPS+PPS、解码器输出 BT.709 limited；
     * 绿的两轮与正常的两轮，从 hvcC、输入/输出 MediaFormat、`CCodecBufferChannel`
     * 分配器查询、`SurfaceUtils` 连接序列到 `first render dataSpace=513` **逐字节相同**，
     * 连全量日志归一化 diff 也只剩 GC/JIT 与随机数差异。
     *
     * ⇒ 问题在**解码器之后**。最强机制假设是**多平面图形缓冲的 fence/布局竞态**：
     * 亮度平面先就绪、色度平面尚未写完就被采样，于是读到全 0（新分配的 gralloc
     * 缓冲是清零的）。这同时解释了「间歇性」与「只影响高通 HEVC 硬解」。
     * 验证方向是把视频输出的 `TextureView` 换成 `SurfaceView`（未做，见下）。
     *
     * 需要 HEVC 的机器（例如 HEVC 能协商出更大画布时）在设置页打开即可，
     * 老安装里已持久化的 `hevc` 会原样读回，不受这个默认值影响。
     */
    val hevc: Boolean = false,
    val hevcSoftwareDecoder: Boolean = false,
    /**
     * 仪表副屏的「核心安全区」（矩形；坐标空间 = 主画布，见 [CarSettingsSafeArea]）。
     *
     * ⚠️ **只作用于副屏**（PATCH-0094，2026-10-11 车主口径「仪表导航 的安全区只限副屏」）。
     * 主屏**不读**这一项。主屏用单独的 [mainSafeArea]；没画过那块框时，
     * 才回落到 [immersiveVideo] 的顶部避让。
     */
    val safeArea: CarSettingsSafeArea = CarSettingsSafeArea(),
    /**
     * 主屏安全区（矩形；坐标空间 = 主屏上报给 iPhone 的画布）。
     *
     * 与 [safeArea] **不是同一份值**。2026-10-11 把共用矩形拆开，是因为仪表框
     * （典型画法：上方 720px）会把主屏界面一起挤窄。主屏要单独调，就画这一块。
     *
     * 未设置（宽或高 ≤ 0），或画框拉满整块画布，都算「没画过」：
     * 主屏回落到 [immersiveVideo] 的顶部避让。画过一块更小的框之后，以框为准，
     * 不再用状态栏实测高度覆盖。
     *
     * 消费点只有 `CarPlayConfigBuilder.airPlayConfig`。`/info` 在握手时定，改完要重连。
     */
    val mainSafeArea: CarSettingsSafeArea = CarSettingsSafeArea(),
    /** 画面绘制到安全区之外（`drawUIOutsideSafeArea`；主屏与副屏共用同一份声明）。 */
    val safeAreaDrawOutside: Boolean = true,
    /** Instrument-only counterpart; does not change the main display safe-area policy. */
    val clusterSafeAreaDrawOutside: Boolean = true,

    /**
     * 画面延展到状态栏下（沉浸）。
     *
     * ## 解决什么问题
     *
     * 车机那条 72px 状态栏是 **SystemUI 的独立窗口**（实机日志
     * `StatusBarWindowView{... 0,0-1920,72}`），不在我们进程里，
     * `insets.hide(statusBars())` 被 ROM 拒绝 ⇒ **永远抢不掉、永远压在最上层**。
     *
     * 于是只剩两种排布：
     * - **避让**（本字段 `false`，2026-10-06 定案 A）：画面砍 72px，协商
     *   `1920x1080 → 1920x1008`，顶部留一条应用主题色的边；
     * - **顶格**（本字段 `true`）：画面铺满 `1920x1080`，状态栏浮在画面之上。
     *
     * 2026-10-06 曾经试过顶格，车主实车否决——原因是**光顶格不够**：
     * CarPlay 自己的顶栏与按钮也会画进那 72px，被状态栏盖住。
     *
     * ## 打开后做了什么（两件事必须成对，少一件就是被否决过的那个状态）
     *
     * 1. **画面页顶格**：`CarPlayApp` 的 `barsToIgnore` 额外摘掉 `statusBars`
     *    ⇒ 视频 View 实测高度变成整屏 ⇒ 协商画布 `1920x1080`。
     *    ⚠️ **只对画面页生效**：设置页/主页继续避让，否则顶栏按钮点不到。
     * 2. **声明安全区**：把车机状态栏的**实测高度**作为 `safeArea.top` 发给
     *    iPhone（见 `CarPlayConfigBuilder.airPlayConfig` 的 `immersiveSafeAreaTopPx`）
     *    ⇒ iPhone 把可交互 UI 排在那条栏之下，只有壁纸/背景铺满。
     *
     * 第 2 条是本次与 2026-10-06 那次尝试的**唯一区别**，也是当时无解的原因：
     * 那时 `safeArea` 没有任何输入入口（恒为 0）。
     *
     * ## 代价与前提
     *
     * - **必须「保存并重连」**：画布尺寸与 `/info` 都在握手时定，改完不重连不生效；
     * - ⚠️ `drawUIOutsideSafeArea` 与 `safeArea` 的**组合行为 Apple 没写死**
     *   （壁纸铺满 / 整体缩进，取决于 iPhone 侧实现）⇒ 实车验证项，
     *   两档都留着让用户自己试；
     * - 没画过 [mainSafeArea] 时，本字段是主屏安全区的来源（[safeArea] 仍只给仪表）；
     *   量不到状态栏高度时**不声明**，宁可保持现状也不猜一个数字。
     *   用户在「显示 → 主屏安全区」里画过框之后，以那块矩形为准。
     *
     * 默认关闭。设置页不再露出这个开关。已装设备里存过的 `true`
     * 由 `SharedPreferencesCarSettingsStore` 一次性改回 `false`。
     */
    val immersiveVideo: Boolean = false,

    /**
     * 隐藏车机的顶部状态栏。
     *
     * **出厂默认关**（2026-09-29 从 `true` 改为 `false`）。理由：
     * 打开它等于让应用去跟车机抢系统栏，而车机状态栏往往是**厂商钉死的**——
     * 隐藏请求不生效时，我们的顶栏（含右上角设置入口）会被压在它下面，
     * 用户点不到，表现为"开启隐藏顶部栏后按钮被遮挡"。
     * 默认关闭时状态栏照常显示，由 Compose 侧的 inset 处理保证两者不重叠
     * （见 `CarScreen` / `HomeScreen` 的 `windowInsetsPadding`）。
     *
     * ⚠️ 已装设备里持久化的 `true` 由 `SharedPreferencesCarSettingsStore`
     * 的 `migrateHideTopBarDefault` 改写——**改默认值不影响已持久化字段**。
     */
    val hideTopBar: Boolean = false,
    val hideBottomBar: Boolean = true,

    /**
     * 在 DBPlay 自己的界面底部画一条底栏（主页 / 应用 / 媒体）。
     *
     * 出厂**关**。
     *
     * ⚠️ 它**不是**[hideBottomBar] 的反向开关：那个管的是**车机自带**的那条栏，
     * 本项管的是 **DBPlay 自己画**的那条。两者同时开着会上下各出现一条栏，
     * 由用户自己取舍（DBPlay 不替他做决定）。
     *
     * 形态与尺寸来源见 `DBPlay-底栏Dock-设计方案-20261011.md`。
     * 温度与应用位在数据源没接通前**整块不渲染**（见 `CarDockBar` 的说明）。
     */
    val dockBar: Boolean = false,

    /**
     * 底栏应用位的**顺序**（包名列表，靠前的优先占位）。
     *
     * ⚠️ 存**包名**而不是下标：应用列表是每次启动重新查的，用下标存会让
     * "卸载一个应用"把后面所有位置整体错位（原 dock 的编辑态也是按 item 存的）。
     *
     * 空 = 没配过 ⇒ 按系统给出的顺序取前几个（见 `CarAppController.loadDockApps`）。
     * 配过但已卸载的包在加载时静默剔除，不报错、也不占位。
     */
    val dockAppOrder: List<String> = emptyList(),

    /**
     * 全屏（沉浸模式）。
     *
     * 与上游 `Fullscreen` 开关对应，语义取"同时隐藏状态栏与导航栏"：
     * 打开时强制隐藏两条系统栏，等价于把 [hideTopBar] 和 [hideBottomBar]
     * 一起打开；关闭时仍由这两个开关各自决定。
     *
     * 为什么不做成独立的第三套窗口标志：上游的 Fullscreen 与两条栏开关
     * 作用于同一组窗口属性，硬拆成互不相干的两层只会产生
     * "全屏开着但状态栏还在"这类无法解释的组合。
     *
     * 默认关闭——与上游出厂值一致。
     */
    val fullscreen: Boolean = false,

    /**
     * **仪表投屏功能是否可用**（CarPlay cluster stream 111）。
     *
     * 打开后立即请求副屏画面；连接尚未就绪时在会话就绪后投屏。
     * CarPlay画面内三指左滑显示、右滑收起；不跟随路线开始/结束。
     *
     * 它不参与向 iPhone 声明副屏：未选屏时 `resolveClusterSpec()` 返回 null，
     * 不声明第二块屏，也不会投到未知屏幕。
     *
     * 默认关闭，用户明确开启才投屏。与原车仪表卡片独立。
     */
    // 2026-10-21 斌哥口径：投屏到仪表默认关闭，用户主动打开才投
    val projectionEnabled: Boolean = false,
    /** Main-display ALT(111). Mutually exclusive with every instrument video consumer. */
    val immersiveNavigationEnabled: Boolean = false,
    val immersiveNavigationCardEnabled: Boolean = false,
    /** App-local glass cards only; never changes phone capabilities or native card settings. */
    val immersiveGlassCardsEnabled: Boolean = false,
    /** -1 retains the original gradient; otherwise a 24-bit RGB background for both cards. */
    val immersiveGlassColor: Int = -1,
    /** Background transparency only: 0=opaque, 100=clear. Text and controls stay legible. */
    val immersiveGlassTransparency: Int = 15,
    val immersiveNavigationAlignment: Int = 0,
    val immersiveNavigationMapLayout: Boolean = false,
    val immersiveNavigationSafeArea: CarSettingsSafeArea = CarSettingsSafeArea(0, 72, 1920, 880),
    val immersiveNavigationDrawOutside: Boolean = true,

    /**
     * 旧版导航自动投屏配置，仅保留序列化/导入兼容。
     * 用户已取消此机制并隐藏开关；生产会话不再订阅路线显隐请求。
     * 即使旧配置为true，也不能重新开启导航驱动的投屏或收起。
     */
    // Retained solely for config compatibility; production no longer consumes it.
    val clusterAutoShowOnRoute: Boolean = false,

    /**
     * 仪表皮肤选中的主题 id（P1，2026-10-17）。
     *
     * 只存**稳定 id**（如 `ultra_dual`），不存主题全量 JSON：主题文件随 APK 更新，
     * 存全量会把老配色永久钉在用户机器上，改主题时无法统一升级。
     *
     * ⚠️ P1 只负责"选谁 / 记下来"，**不参与副屏绘制**；真正把皮肤画到仪表上在 P2，
     * 那时才会读这两个字段（见 `clusterSkinEnabled`）。
     */

    /**
     * 仪表皮肤**历史**开关（P1，2026-10-17）。默认关。
     *
     * 2026-10-23 起**不参与渲染**：主题是否显示由 `ClusterLayerMode.showsTheme`
     * （即主题页「在仪表上显示主题」开关）决定。本字段只为旧配置读取、
     * 诊断回滚保留，**不要**再用它判断主题显隐 —— 那正是“点了启用没反应”的来源。
     */

    /**
     * 仪表皮肤明暗模式（2026-10-21）。
     *
     * [ClusterAppearanceMode.DARK] 是出厂值，保证升级后画面与旧版一致。
     * [ClusterAppearanceMode.SYSTEM] 读车机 `uiMode` 的 night mask；
     * 当前车辆快照没有日夜字段，因此不依赖未验证的车辆信号。
     *
     * 本项只替换当前主题的 palette，不参与 CarPlay 握手，属于就地生效字段。
     */
    val clusterAppearanceMode: ClusterAppearanceMode = ClusterAppearanceMode.DARK,

    /**
     * **整个 App 的界面明暗**（2026-10-23 设计系统重构新增）。
     *
     * 与 [clusterAppearanceMode] 是两个东西：
     * - [clusterAppearanceMode] 只决定**仪表主题那层皮肤**的深浅；
     * - 本项决定 **DBPlay 应用界面本身**（设置页、首页、列表等）的深浅。
     *
     * 出厂 [ClusterAppearanceMode.DARK]：车载全屏应用以深色为主，
     * 且本机车机 `uiMode` 不随原厂日夜切换，`SYSTEM` 在实车上恒为深色。
     */
    val uiAppearanceMode: ClusterAppearanceMode = ClusterAppearanceMode.SYSTEM,

    // ------------------------------------------------------------ 音频与输入
    val advancedAudioChannelMapping: Boolean = false,
    val microphone: Boolean = true,

    /**
     * 媒体通道（音乐/播客/有声书）走哪条音频流。
     *
     * **0 = 按 usage 由系统路由**（出厂值，等价于没有这个设置）；
     * 1..20 = 直接指定车机 ROM 的老式 `streamType`。
     *
     * ## 为什么要有它（DBPlay PATCH-0013）
     *
     * 现场问题：在 CS11 / 亿咖通 GKUI 车机上，CarPlay 音乐的数据全部正常送到
     * 应用（收流 / 解密 / 解码 / 建轨全部成功），但车机音频 HAL 把它路由进了
     * `dayin`（大印/氛围）总线而不是扬声器，于是「连接正常、音乐没声」。
     *
     * 上游与本项目原先只有一档 `usage` 路由（`MOBILE_COMPATIBLE`），
     * 且注释自认**从未在真车上验证**；厂商适配器则一律返回 null 总线。
     * 与其猜一条总线名写死进代码，不如把「流号」开放给用户逐个数现场试。
     *
     * 思路来自 DiPlay（另一套同源工程）的 `mediaAudioChannel` 设置项；
     * 本实现为**重写**，不复制其源码（其许可为 GPL-3.0，照搬会传染）。
     *
     * ⚠️ 改动后需**保存并重连**才生效——通道号只在建轨那一刻用得上。
     */
    val mediaAudioChannel: Int = 3,

    /**
     * 导航提示音（含 Siri 提示、系统提示音）走哪条音频流，语义同 [mediaAudioChannel]。
     *
     * 与媒体分开的原因：导航提示在多数车机上落在与音乐**不同**的提示流上，
     * 共用一个流号会造成「音乐对了、导航还是没声」。
     *
     * ## ⚠️ 为什么默认仍是 0（自动路由），而不是吉利原厂的 14
     *
     * DiPlay 吉利定制版（`carlito12345/DiPlay`）的 `docs/GEELY-FACTORY-CARPLAY.md` 写明：
     *
     * > When no explicit navigation channel has been saved, detected Geely units use
     * > factory navigation channel 14 instead of automatic media routing.
     *
     * 即**吉利原厂把导航播报放在流号 14（主驾侧喇叭）**；上游
     * `AudioChannelMapper.DEFAULT_NAVIGATION_STREAM_TYPE` 亦为 14。
     * 但**本项目尚未在实车上验证过流号 14**：
     *
     * - 流号 14 属 Android 未公开的厂商私有号段（公开 `STREAM_*` 只到 10）；
     * - ROM 不认该流号时，`AudioTrack` 建轨失败 ⇒ **导航播报静默消失**，
     *   全链路不报错 —— 比"导航盖住音乐"更难发现、更难回退。
     *
     * ⇒ 这里**保持 0**，把 14 作为**推荐值写进设置页提示**（见 `SettingsScreen`），
     * 由用户在实车上确认后再手动选。等实车验证通过，再考虑改成出厂默认。
     */
    val navigationAudioChannel: Int = 15,

    /** 导航播报时申请原生导航焦点；不控制音轨或媒体焦点，保存后立即生效。 */
    val navigationAudioFocusEnabled: Boolean = true,

    /**
     * 媒体流（音乐/播客）的抖动缓冲时长（毫秒）。
     *
     * 无线 CarPlay 的音频与视频共用 Wi-Fi：链路一紧张，音频包会成串到达、中间
     * 空上几百毫秒——这就是「音乐断断续续」的物理来源。媒体流需要一条够长的
     * 缓冲把空档吞掉；通话、Siri、导航提示恰好相反（要的是及时），保持小缓冲。
     *
     * 合法档位见 [Companion.AUDIO_BUFFER_PRESETS]；其他值夹回默认档。
     */
    val mediaAudioBufferMillis: Int = DEFAULT_MEDIA_AUDIO_BUFFER_MILLIS,
    /** Experimental type-102 LPCM advertisement; reconnect required, off restores AAC-LC. */
    val higherQualityMedia: Boolean = false,

    /**
     * 是否把方控/车机按键转发给 CarPlay（HID）。
     *
     * 默认打开：二开工程接方控的目的就是控制 CarPlay，关掉它方控就只是个摆设。
     * 之所以仍然留一个开关，是因为这条链路**没有真机可验**——媒体键的 HID index
     * 语义是从 upstream 描述符解出来的（见 `CarKeyHidMap`），还需一台真实 iPhone
     * 配合确认。万一在某个车机上表现异常，现场应当能先关掉它，而不是等新版本。
     *
     * 关掉只影响"转发"：方控事件照常采集，UI 的 [com.dbtool.carplay.api.session.CarSessionEvent.InputReceived]
     * 也照常发出，只是不再驱动 CarPlay。
     */
    val forwardSteeringWheelToCarPlay: Boolean = true,

    /**
     * 是否把"正在播放"上报给车机媒体中心（卡片 / dock / **仪表盘**）。
     *
     * 默认打开：不开的话仪表盘永远是空的，那正是这个功能要解决的问题。
     *
     * ## 为什么仍然留一个开关
     *
     * 与 [forwardSteeringWheelToCarPlay] 同一个理由——这条链路**没有真机可验**：
     * 车机侧那套广播常量来自参考实现 DBTOOL2.0（吉利车机实测），
     * 但这台车机上到底有没有那个接收者还没确认过。
     *
     * 另有一个具体的使用冲突：**只要 CarPlay 会话活着，我们就每 2s 维持一次心跳，
     * 车机的媒体卡片会被我们占住**。此时用户若去放真正的帆书，两边会互相覆盖。
     * 关掉这个开关就能把卡片还给车机自己的媒体源。
     *
     * 关掉只影响"上报"：`CarNowPlaying` 照常读取（方控的播放/暂停判断还靠它），
     * 只是不再往车机广播、也不起本地封面服务。
     */
    val reportMediaCardToCar: Boolean = true,

    // ---------------------------------------------------------------- 系统
    val locationReporting: Boolean = false,
    val autoStartOnBoot: Boolean = false,

    /**
     * 是否在 CarPlay 画面上叠加协议日志。
     *
     * 只影响二开界面上的浮层；上游自己的 `logcat` 输出不受它控制。
     * 开启后每次日志行都会触发一次模型重建，会明显影响画面流畅度，
     * 所以非排查期间必须保持关闭。
     */
    val debugLogs: Boolean = false,
    val fpsOverlayEnabled: Boolean = false,
) {
    /** 显示缩放倍率（人类可读标签，如 `0.7x`）。 */
    val displayScaleLabel: String
        get() = "${displayScaleTenths / 10}.${displayScaleTenths % 10}x"

    /** 是否使用无线链路。 */
    val isWireless: Boolean get() = transport == CarTransportMode.WIRELESS

    // ------------------------------------------------------------ 仪表分层显示

    /** 当前仪表图层模式（双层 / 仅 CarPlay / 仅主题）。 */
    fun clusterLayerMode(): ClusterLayerMode = ClusterLayerModeSetting.current()

    /**
     * 切换仪表图层模式。
     *
     * 该值不参与 CarPlay 握手，写入后立即生效；存储实现负责同步落盘。
     */
    fun setClusterLayerMode(mode: ClusterLayerMode) {
        ClusterLayerModeSetting.set(mode)
    }

    companion object {
        /**
         * 音频流可选择范围。0 = 按 usage 路由；1..20 = 老式 streamType。
         *
         * 上限取 20 而不是 `STREAM_ACCESSIBILITY`(10)：部分车机 ROM 把厂商私有
         * 音频通道挂在 10 以上的号段上，写死 10 会让那些车机直接无解。
         */
        val AUDIO_CHANNEL_RANGE = 0..20

        /** 导航滑杆最后一档，界面显示「领克01专用」。 */
        const val LYNK01_NAVIGATION_CHANNEL = 21

        /**
         * 导航提示音比媒体多一档 [LYNK01_NAVIGATION_CHANNEL]。
         * 这一档不是 streamType，而是 `setUsage(12)` + `setContentType(17)`。
         */
        val NAVIGATION_AUDIO_CHANNEL_RANGE = 0..LYNK01_NAVIGATION_CHANNEL

        /** 媒体缓冲默认档（毫秒）。 */
        const val DEFAULT_MEDIA_AUDIO_BUFFER_MILLIS = 300

        /** 媒体缓冲可选档位（毫秒）：越大越抗断流，代价是切歌/起播更迟钝。 */
        val AUDIO_BUFFER_PRESETS = listOf(200, 250, 300, 350, 400, 450, 500)

        /** 把任意输入夹到合法档位；非法值回落默认档。 */
        fun sanitizeAudioBufferMillis(millis: Int): Int =
            millis.takeIf { it in AUDIO_BUFFER_PRESETS } ?: DEFAULT_MEDIA_AUDIO_BUFFER_MILLIS

        /** 把任意输入收敛到合法流号。**UI、持久化、诊断三处共用**，避免各写一份。 */
        fun sanitizeAudioChannel(channel: Int): Int =
            channel.takeIf { it in AUDIO_CHANNEL_RANGE } ?: 0

        /** 导航提示音：0..20 同媒体，21 = 领克 01。其它值回到自动。 */
        fun sanitizeNavigationAudioChannel(channel: Int): Int =
            channel.takeIf { it in NAVIGATION_AUDIO_CHANNEL_RANGE } ?: 0

        /**
         * 出厂设备标识。
         *
         * 设备名称 / 制造商 / 型号 / OEM 标签**共用同一个值**，理由：
         * 这四个字段会分别出现在 iPhone 的蓝牙列表、CarPlay 设备列表和 AirPlay 菜单里。
         * 拆成不同品牌串（例如名称 `DBPlay`、制造商 `DBTool`）只会让同一台车机在
         * 三个地方显示三个名字，用户无从判断"哪个才是它"。
         */
        const val DEFAULT_IDENTITY = "Lynk&Co"

        /**
         * 上游出厂标识。**只用于一次性迁移**：老安装里持久化的这些值
         * 会被改写成 [DEFAULT_IDENTITY]（见 `SharedPreferencesCarSettingsStore`）。
         *
         * 注意只匹配**恰好等于出厂值**的情况——用户自己改过的名字必须原样保留。
         */
        val LEGACY_UPSTREAM_IDENTITIES = setOf("xcertplay", "XCertPlay", "shilapi")

        const val DEFAULT_SCALE_TENTHS = 10
        const val MIN_SCALE_TENTHS = 3
        const val MAX_SCALE_TENTHS = 10

        const val DEFAULT_FPS = 60
        const val MIN_FPS = 30
        const val MAX_FPS = 60
        const val FPS_STEP = 5

        /**
         * 参考物理长度（mm）出厂值。**2026-10-06 起 200 → 350。**
         *
         * 车机屏幕对角线普遍在 9~12 英寸区间，而 [physicalSizeBasis] 取的是
         * 「最宽边」——对应到横屏车机的实际宽度，350mm 比 200mm 更接近真值。
         * 这个值直接影响 iPhone 按「像素数 + 物理尺寸」推算的界面密度：
         * 偏小 ⇒ iPhone 认为屏幕很小 ⇒ 控件放大、有效显示区域变少。
         */
        const val DEFAULT_WIDTH_PHYSICAL_MM = 350
        const val MIN_WIDTH_PHYSICAL_MM = 100
        const val MAX_WIDTH_PHYSICAL_MM = 400
        const val WIDTH_PHYSICAL_MM_STEP = 50

        // 自定义分辨率的出厂值取 1920x1080@160 —— 车机里最常见的一档，
        // 也是用户第一次打开这个开关时最可能想试的那组数。
        // 开关默认关，所以这三个值在用户主动打开前不产生任何影响。
        const val DEFAULT_CUSTOM_WIDTH_PX = 1920
        const val DEFAULT_CUSTOM_HEIGHT_PX = 1080
        const val DEFAULT_CUSTOM_DPI = 160

        const val DEFAULT_I2C_PATH = "/dev/i2c-1"

        /**
         * 出厂热点凭据。
         *
         * 取"固定且好记"而不是随机：无线 CarPlay 的第一次配对靠蓝牙下发 Wi-Fi 凭据，
         * 之后 iPhone 会记住这张网络。SSID 每次启动都变的话，iPhone 侧永远在重学，
         * 重连就变得不可靠。密码 8 位，正好是 WPA2-PSK 的 8~63 位下限。
         *
         * ⚠️ 密码在 2026-09-29 从 9 位（`123456789`）改回 8 位（`12345678`）：
         * 产品要求与 ADB 独立热点的参考实现对齐（那边就是 8 位，见 `ADB-HOTSPOT.md`）。
         * 已安装设备里持久化的 9 位值由 `SharedPreferencesCarSettingsStore`
         * 的 `migrateHotspotPassphraseToEightChars` 改写——
         * **改默认值不影响已持久化字段**，所以必须配一次迁移。
         */
        const val DEFAULT_HOTSPOT_SSID = "Lynk&Co"
        const val DEFAULT_HOTSPOT_PASSPHRASE = "12345678"

        /** 上一版的出厂密码（9 位）。迁移时改写成 [DEFAULT_HOTSPOT_PASSPHRASE]。 */
        const val PREVIOUS_HOTSPOT_PASSPHRASE = "123456789"

        /**
         * 更早一版的出厂密码（8 位）。
         *
         * ⚠️ 它现在与 [DEFAULT_HOTSPOT_PASSPHRASE] **同值**，所以
         * `migrateLegacyHotspotDefaults` 里针对它的那个分支已经是空操作。
         * 保留这个常量只为让那次迁移的判据仍然自解释，不要删。
         */
        const val LEGACY_HOTSPOT_PASSPHRASE = "12345678"

        /**
         * 上一版的出厂热点组合：`LOCAL_ONLY_HOTSPOT` + 开放 + **空 SSID** + **空密码**。
         *
         * **只用于一次性迁移**（见 `SharedPreferencesCarSettingsStore`
         * 的 `migrateLegacyHotspotDefaults`）：老安装里持久化的这一组值会被改写成
         * [CarHotspotMode.AUTO] + [DEFAULT_HOTSPOT_SSID] + [DEFAULT_HOTSPOT_PASSPHRASE]
         * + [CarHotspotSecurity.WPA2]。
         *
         * ⚠️ 判据刻意取得很严——**四项全中**才算出厂态。只命中模式一项就改写的话，
         * 会把"用户特意选了 LocalOnlyHotspot"的配置一起翻掉。
         *
         * ⚠️ **2026-10-03：`CarHotspotMode.LOCAL_ONLY_HOTSPOT` 已从枚举移除**，
         * 所以这里不能再写成 `CarHotspotMode.LOCAL_ONLY_HOTSPOT`（编译不过）。
         * 但**老安装的 SharedPreferences 里存的仍是这串名字**，迁移判据必须照旧能命中，
         * 否则那些用户会永久卡在一条已不存在的模式上、再也升不到 AUTO。
         * ⇒ 改成字符串字面量，语义与旧 `LEGACY_HOTSPOT_MODE.name` 完全等价。
         */
        const val LEGACY_HOTSPOT_MODE_NAME = "LOCAL_ONLY_HOTSPOT"
        val LEGACY_HOTSPOT_MODE: String = LEGACY_HOTSPOT_MODE_NAME
        val LEGACY_HOTSPOT_SECURITY = CarHotspotSecurity.OPEN

        /** 与 upstream `AirPlayPersistence.DEFAULT_*` 保持一致的出厂值。 */
        fun default(): CarSessionSettings = CarSessionSettings()
    }
}

/**
 * 把身份与外观恢复成出厂标识（设置页「恢复出厂标识」用）。
 *
 * ⚠️ 必须是**顶层**函数，不能写成 `companion object` 里的成员扩展：
 * 成员扩展只在 companion 本身作为接收者时才可见，设置页调用不到，
 * 编译会报 `Unresolved reference`（已踩过）。同理 [hasDefaultIdentity]。
 */
fun CarSessionSettings.withDefaultIdentity(): CarSessionSettings = copy(
    deviceName = CarSessionSettings.DEFAULT_IDENTITY,
    manufacturer = CarSessionSettings.DEFAULT_IDENTITY,
    model = CarSessionSettings.DEFAULT_IDENTITY,
    oemLabel = CarSessionSettings.DEFAULT_IDENTITY,
)

/**
 * 当前身份是否已经是出厂标识。
 *
 * 用于让设置页那条"恢复"入口在无事可做时隐藏，而不是点下去毫无反应。
 */
fun CarSessionSettings.hasDefaultIdentity(): Boolean =
    deviceName == CarSessionSettings.DEFAULT_IDENTITY &&
        manufacturer == CarSessionSettings.DEFAULT_IDENTITY &&
        model == CarSessionSettings.DEFAULT_IDENTITY &&
        oemLabel == CarSessionSettings.DEFAULT_IDENTITY
