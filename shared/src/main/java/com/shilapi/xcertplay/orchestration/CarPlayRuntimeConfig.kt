package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.UsbDeviceId
import java.net.Inet6Address
import java.net.InetAddress

enum class CarPlayTransport {
    WIRED,
    WIRELESS,
}

enum class MfiTarget {
    USB_CH341,
    I2C,
    REMOTE,
    LOCAL_FILES,
}

enum class WirelessHotspotMode {
    WIFI_P2P,
    LOCAL_ONLY_HOTSPOT,
    MANUAL,

    /**
     * **PATCH-0010：同一 Wi-Fi（不建热点）。**
     *
     * 上游原本是三个值、`startWirelessHotspot()` 里三条分支都 `manager.start()`
     * ⇒ **不存在"跳过热点"这条路**。本值由二开层 `CarHotspotMode.SAME_LAN` 映射而来，
     * 对应的分支**不建任何热点**，只使用 [CarPlayRuntimeConfig.sameLanHostAddress]
     * 作为通告给 iPhone 的地址。
     *
     * ⚠️ 该模式下 [CarPlayRuntimeConfig.manualHotspotSsid] /
     * [CarPlayRuntimeConfig.manualHotspotPassphrase] **不再有意义**：
     * 网络是别人的，凭据不由我们决定。iAP2 的 `0x5703` 里仍要填这两个字段，
     * 所以上游会照常把它们发出去——iPhone 会忽略它们（它已经在网上了）。
     * 这也是 DiPlay 同源实现的做法。
     */
    SAME_LAN,
    /**
     * **车机原厂路由热点（2026-10-25 新增）。**
     *
     * 对齐参考 APK 的 `GeelyRoutedHotspotManager`：扫 `eth*` 上的 `198.18.x.x`，
     * 返回 `bssid=null / channel=0 / bandLabel="Car hotspot"`。
     * 与 [MANUAL] 是**两条独立链路**，互不回退。
     */
    GEELY_ROUTED,

    /**
     * DBPlay-only isolated Wi-Fi Direct path. Unlike [WIFI_P2P], this value explicitly opts into
     * the Android 9 legacy createGroup API; it must not inherit the legacy WIFI_P2P downgrade.
     */
    WIFI_P2P_LEGACY,
}

enum class ManualHotspotBand {
    AUTO,
    GHZ_2_4,
    GHZ_5,
}

enum class ManualHotspotSecurity {
    OPEN,
    WPA2,
    WPA3_TRANSITION,
    WPA3,
}

/**
 * Deployment-owned constants for one head unit. There are deliberately no built-in Apple or
 * CH341 product IDs: the physical devices attached to the target must be identified first.
 */
class CarPlayRuntimeConfig(
    val iphoneDevices: List<UsbDeviceId> = emptyList(),
    val mfiTarget: MfiTarget = MfiTarget.USB_CH341,
    val ch341Devices: List<UsbDeviceId> = emptyList(),
    val ch341MfiResetGpio: Int? = null,
    val linuxI2cPath: String? = null,
    val remoteMfiServer: String? = null,
    val remoteMfiToken: String? = null,
    val localMfiCertificateUri: String? = null,
    val localMfiPrivateKeyUri: String? = null,
    val hostMac: ByteArray = DEFAULT_HOST_MAC,
    val linkLocal: String = "fe80::2",
    val identification: Iap2IdentificationConfig,
    val availableCurrentMilliAmps: Int = 2400,
    val label: String = "xcertplay",
    val hostName: String = "xcertplay",
    val transport: CarPlayTransport = CarPlayTransport.WIRED,
    val wirelessHotspotMode: WirelessHotspotMode = WirelessHotspotMode.WIFI_P2P,
    val manualHotspotSsid: String? = null,
    val manualHotspotPassphrase: String? = null,
    val manualHotspotBand: ManualHotspotBand = ManualHotspotBand.AUTO,
    val manualHotspotChannel: Int = 0,
    val manualHotspotSecurity: ManualHotspotSecurity = ManualHotspotSecurity.WPA2,
    /**
     * **PATCH-0087/0099**：`WirelessHotspotMode.MANUAL` 下通告给 iPhone 的本机 CarPlay/AirPlay IPv4。
     * 它是 Android 本机服务 endpoint，不是外置 T-Box/AP 的网关（本车设置中可见 `192.168.15.1`）。
     *
     * 上游 `ManualHotspotManager` 会将该本机地址用于 AirPlay/CarPlay endpoint，并据此查找接口。
     * 不要把外置 T-Box/AP 的 gateway（本车设置显示 `192.168.15.1`）当成本机地址：它不会
     * 出现在 Android/Linux 本机接口上。当前 DBPlay 对 Ecarx 采用例外的安全路径：二开层只有在
     * 厂商 SDK 客户端路由唯一验证后，才同时传入 route source 与 interface name；否则保留
     * 原有用户手动 endpoint 行为。
     *
     * `hostInterfaceOverride` 非空时表示调用方已验证过一条 OEM 客户端 route。上游会严格
     * 匹配这个接口与地址，接口失效时不按名字打分回退。普通用户手填和 P2P 都不设置它。
     * 该字段可选：上游仍只校验 IPv4 字面量；MANUAL 复用必需性和 Ecarx route 状态由二开层把关。
     */
    val manualHotspotHostAddress: String? = null,
    /**
     * **PATCH-0010**：`WirelessHotspotMode.SAME_LAN` 下通告给 iPhone 的地址。
     *
     * 必填（该模式下）：车机在它所连局域网里的 IPv4 字面量。由二开层
     * `CarSameLanReader` 从 `ConnectivityManager` 读出来后传进来——
     * 上游不碰 `ConnectivityManager`，只消费这个结果。
     *
     * ⚠️ **必须是 IPv4**：AirPlay 的监听与 iPhone 的 mDNS 解析都按 IPv4 走，
     * 塞一个 IPv6 或主机名进去只会让连接更绕甚至不可达。
     * 读取侧（`CarSameLanReader`）已经排除 link-local 与回环地址。
     */
    val sameLanHostAddress: String? = null,
    /**
     * **PATCH-0010 修正**：`WirelessHotspotMode.SAME_LAN` 下通告给 iPhone 的 Wi-Fi 名。
     *
     * ## 为什么 SAME_LAN 也需要 SSID（2026-10-07 实测修正）
     *
     * 原实现假设"iPhone 已经在同一张网里，会忽略 `0x5703` 里的 SSID"——**假设是错的**。
     * 实测（诊断包 `1aedc494`）：iPhone 是照着 `0x5703` 的 SSID/密码去**关联**网络的，
     * 而不是"已经在网里就跳过"。旧代码把这里填成热点名（`DBPlay`），于是下发了一个
     * **根本不存在的网络名** ⇒ iPhone 无从关联 ⇒ 40s 后
     * `wireless attach timed out` ⇒ 界面永远停在「无线通道就绪」。
     *
     * 取值来自二开层（[com.dbtool.carplay.bridge.network.CarHotspotPlan.sameLanWifiSsid]）：
     * 设置里手填 > `CarSameLanReader` 读到的真实 SSID。可能为 null
     * （个别 ROM 只给 `<unknown ssid>`），此时如实留空、不编造。
     */
    val sameLanWifiSsid: String? = null,
    /**
     * **PATCH-0010 修正**：与 [sameLanWifiSsid] 配套的密码。
     *
     * ⚠️ 自动路径**永远读不到明文**（`WifiConfiguration.preSharedKey` 自 Android 10
     * 起对三方不可读）⇒ 只能由用户在设置页手填。为空表示"该网开放或密码未知"，
     * **绝不能拿热点密码顶替**。长度校验与手动热点一致（空 或 8..63）。
     */
    val sameLanWifiPassphrase: String? = null,
    val locationReportingEnabled: Boolean = false,
    /**
     * Optional strict interface selector paired with [manualHotspotHostAddress].
     * Used only when the extension layer has a verified OEM client route. A mismatch must fail
     * closed; the manual manager must not fall back to interface-name scoring.
     */
    val manualHotspotInterfaceName: String? = null,
    /**
     * **PATCH-0116**：本次 `MANUAL` 形态的热点**是我们自己起的**（ADB hostapd），
     * 不是"复用车机那张已在跑的 AP"。
     *
     * 两者在上游都映射到 [WirelessHotspotMode.MANUAL]（自建借道该枚举：
     * `ManualHotspotManager` 只读配置、从不 `startSoftAp`），于是
     * `ManualHotspotManager` 无法从枚举区分它们。而这个区别**决定它能不能用系统
     * `SoftApConfiguration` 校验本次的热点**：
     *
     * - `false`（默认，= 复用）：系统那份配置描述的就是要用的那张 AP ⇒ 照旧校验；
     * - `true`（= 自建）：系统那份描述的是**另一张** AP ⇒ 不读、不校验。
     *
     * 不区分会怎样（2026-10-13 日志 9，`1791897995606` 实证）：设置页 SSID 由复用与自建
     * **共用**，用户先填「Lynk」复用、再切回自建，名字与车机那份 2.4 GHz 配置撞上 ⇒
     * 自建的 5 GHz AP 被判"频段不符"当场拆掉，`wireless bring-up failed` ×3，
     * 而换个名字就成功。见 `ManualHotspotManager.selfHostedAccessPoint`。
     */
    val manualHotspotSelfHosted: Boolean = false,
    /** Only an AP actually started and validated by this app may bypass client settling.
     * Separate from manualHotspotSelfHosted, which GEELY_ROUTED also uses to skip config validation.
     */
    val manualHotspotSkipClientSettle: Boolean = false,
    /**
     * **PATCH-0133**：复用档 RFCOMM 早停用的「已有只读快照」客户端数读取器。
     *
     * `null` = 本次不是复用档，或二开层没有可用的快照 ⇒ 保持原来的 3 轮重试。
     * 非 null 时每次调用只允许读**内存里已经采到的快照**，**不许**发 shell / ip
     * 命令或启动新探针：早停的收益本来就来自「不额外探测」。
     *
     * 二开层只在「复用车机热点」且热点已通过 `start()` 校验后注入它，来源是
     * `CarHotspotClientWatcher` 已采到的 `clientCount`；读不到时返回 null。
     */
    val hotspotClientCountProvider: (() -> Int?)? = null,
) {
    init {
        require(iphoneDevices.all { it.vendorId == APPLE_VENDOR_ID }) {
            "iPhone USB identities must use Apple vendor ID 0x${APPLE_VENDOR_ID.toString(16)}"
        }
        require(hostMac.size == 6) { "hostMac must be 6 bytes" }
        require(isLinkLocalIpv6(linkLocal)) { "linkLocal must be a link-local IPv6 literal" }
        require(availableCurrentMilliAmps in 0..0xffff) {
            "availableCurrentMilliAmps must be in 0..65535"
        }
        require(label.isNotBlank()) { "label must not be blank" }
        require(hostName.isNotBlank()) { "hostName must not be blank" }
        require(mfiTarget != MfiTarget.USB_CH341 || ch341Devices.isNotEmpty()) {
            "CH341 devices must be configured for the USB/CH341 MFi target"
        }
        require(mfiTarget != MfiTarget.I2C || !linuxI2cPath.isNullOrBlank()) {
            "A Linux I2C path must be configured for the I2C MFi target"
        }
        require(mfiTarget != MfiTarget.REMOTE || !remoteMfiServer.isNullOrBlank()) {
            "A server address must be configured for the remote MFi target"
        }
        require(mfiTarget != MfiTarget.LOCAL_FILES || !localMfiCertificateUri.isNullOrBlank()) {
            "A certificate document must be selected for the local-files MFi target"
        }
        require(mfiTarget != MfiTarget.LOCAL_FILES || !localMfiPrivateKeyUri.isNullOrBlank()) {
            "A private-key document must be selected for the local-files MFi target"
        }
        require(
            mfiTarget != MfiTarget.LOCAL_FILES ||
                localMfiCertificateUri?.startsWith("content://") == true,
        ) {
            "The local MFi certificate must use an Android content URI"
        }
        require(
            mfiTarget != MfiTarget.LOCAL_FILES ||
                localMfiPrivateKeyUri?.startsWith("content://") == true,
        ) {
            "The local MFi private key must use an Android content URI"
        }
        require(ch341MfiResetGpio == null || ch341MfiResetGpio in 0..5) {
            "CH341 MFi reset GPIO must be D0..D5"
        }
        require(remoteMfiServer?.contains('\u0000') != true) {
            "Remote MFi server must not contain U+0000"
        }
        require(remoteMfiToken?.contains('\u0000') != true) {
            "Remote MFi token must not contain U+0000"
        }
        require(localMfiCertificateUri?.contains('\u0000') != true) {
            "Local MFi certificate URI must not contain U+0000"
        }
        require(localMfiPrivateKeyUri?.contains('\u0000') != true) {
            "Local MFi private-key URI must not contain U+0000"
        }
        // ⚠️ 2026-10-08（移植 DiPlay PR #124 / #130）：**只有无线会话才需要热点设置**。
        //
        // 原实现无条件校验（`wirelessHotspotMode == MANUAL` 就要求 SSID 非空），
        // 而 `wirelessHotspotMode` 描述的是"无线那条路打算怎么起热点"，
        // 与本次到底走有线还是无线无关。于是出现下面这条**必然失败**的组合：
        //   设置页选「复用车机热点」+ 热点名留空 + 插线连 CarPlay
        // ⇒ `CarPlayRuntimeConfig` 构造即抛 `IllegalArgumentException`
        //   （`manualHotspotSsid is required in manual hotspot mode`），
        //   有线会话在**还没碰到 USB 之前**就崩了。
        //
        // 现场表现：用户以为"有线连接坏了"，实际是一条与他无关的无线设置挡住了它。
        // DiPlay 的 issue #124 就是这个（原文：*"connecting by cable crashed at start"*）。
        //
        // ⇒ 加上 transport 判断。**只放宽、不收紧**：无线会话的校验一字未动，
        //   有线会话本来也不会用到这些字段（有线不建热点、不 attach 热点）。
        // ⚠️ 2026-10-25：`GEELY_ROUTED` 与 `MANUAL` **共用同一组校验**。
        //
        // 参考包里 routed 走的是 `WirelessHotspotMode.MANUAL`（靠
        // `usesGeelyRoutedHotspot` 在 MANUAL 分支内切 manager），所以这组 require
        // 对它是生效的。DBPlay 给 routed 单独开了枚举值，若不在这里补上，
        // `GEELY_ROUTED` 会**整层绕过** SSID/密码校验 —— 那时
        // `ensureGeelyRoutedReady` 就是唯一一道墙，绕过它就会在 60 秒扫描之后
        // 才在 `Iap2WirelessIdentification` 里炸，或者 SSID>32 字节一路写进 0x5703。
        if (
            transport == CarPlayTransport.WIRELESS &&
            (wirelessHotspotMode == WirelessHotspotMode.MANUAL ||
                wirelessHotspotMode == WirelessHotspotMode.GEELY_ROUTED)
        ) {
            val ssid = manualHotspotSsid
            require(!ssid.isNullOrBlank()) {
                "manualHotspotSsid is required in manual hotspot mode"
            }
            require('\u0000' !in ssid) {
                "manualHotspotSsid must not contain U+0000"
            }
            val passphrase = manualHotspotPassphrase.orEmpty()
            require('\u0000' !in passphrase) {
                "manualHotspotPassphrase must not contain U+0000"
            }
            require(passphrase.isEmpty() || passphrase.length in 8..63) {
                "manualHotspotPassphrase must be empty or between 8 and 63 characters"
            }
            require(manualHotspotChannel in 0..196) {
                "manualHotspotChannel must be 0 or in 1..196"
            }
            require(
                manualHotspotSecurity == ManualHotspotSecurity.OPEN ||
                    passphrase.length in 8..63,
            ) {
                "A passphrase between 8 and 63 characters is required for secured manual hotspots"
            }
            require(
                manualHotspotChannel == 0 ||
                    isManualHotspotChannelCompatible(manualHotspotBand, manualHotspotChannel),
            ) {
                "manualHotspotChannel is not valid for the selected manual hotspot band"
            }
            require(
                manualHotspotSecurity == ManualHotspotSecurity.OPEN || passphrase.isNotEmpty(),
            ) {
                "manualHotspotPassphrase is required for secured manual hotspots"
            }
            // PATCH-0087：**用户填的车机热点地址**（复用模式）。这里只校验**形态**。
            //
            // ⚠️ 刻意**不**在这里要求非空：上游分不清"复用车机热点"与"P2P 组主被翻译成
            // MANUAL 形状"（后者地址来自组主的 GO，见二开层 `CarHotspotResolver`）。
            // 必需性由二开层把关，报出来的也是**中文**、可执行的话。
            manualHotspotHostAddress?.takeIf { it.isNotBlank() }?.let { address ->
                require('\u0000' !in address) {
                    "manualHotspotHostAddress must not contain U+0000"
                }
                require(isStrictIpv4Literal(address)) {
                    "manualHotspotHostAddress must be a dotted-quad IPv4 literal, got '$address'"
                }
            }
            manualHotspotInterfaceName?.let { interfaceName ->
                require(!manualHotspotHostAddress.isNullOrBlank()) {
                    "manualHotspotInterfaceName requires manualHotspotHostAddress"
                }
                require(interfaceName.matches(Regex("[A-Za-z0-9_.-]+"))) {
                    "manualHotspotInterfaceName must be a plain interface name"
                }
            }
        }
        // 同 MANUAL 那一支（2026-10-08 移植 DiPlay PR #124/#130）：**只有无线会话**
        // 才用得到局域网连接的这些字段。原实现无条件校验 ⇒ 设置页停在「局域网连接」
        // 而地址/SSID 还没填时，插线连 CarPlay 会在构造配置这一步就抛
        // `sameLanHostAddress is required in same-LAN mode`——一条与有线会话
        // 毫无关系的设置把有线连接挡死了。
        //
        // 有线会话既不建热点、也不通告任何 Wi-Fi，这些字段它一个都不读。
        // ⇒ 与 MANUAL 保持一致地放宽；无线侧校验一字未动。
        if (transport == CarPlayTransport.WIRELESS && wirelessHotspotMode == WirelessHotspotMode.SAME_LAN) {
            // PATCH-0010：本模式的核心输入就是这一个地址，缺了它整条链无从谈起
            // （`sameLanHotspotInfo()` 也会抛，但在这里拦住能给出更早、更准的报错）。
            val address = sameLanHostAddress
            require(!address.isNullOrBlank()) {
                "sameLanHostAddress is required in same-LAN mode"
            }
            require('\u0000' !in address) {
                "sameLanHostAddress must not contain U+0000"
            }
            // ⚠️ 只接受 IPv4 字面量。塞主机名进来会让 iPhone 侧多做一次 DNS，
            // 塞 IPv6 则与我们自己的监听/通告口径不符——两者都属于"能配但必然失败"，
            // 所以在这里直接拒掉，而不是等现场连不上再猜。
            //
            // ⚠️ 2026-10-11（PATCH-0087）：判据从 `InetAddress.getByName(...) is Inet4Address`
            // 收紧成 [isStrictIpv4Literal] —— 老判据会放行 `192.168.15` 这种
            // "能解析、但不是用户敲的那个地址"的写法（详见该函数 KDoc）。
            require(isStrictIpv4Literal(address)) {
                "sameLanHostAddress must be a dotted-quad IPv4 literal, got '$address'"
            }
            // 🔴 SSID 在本模式是**必填**（2026-10-07 真机修正）。
            //
            // 早先这里写成"可选"，理由是"部分 iOS 版本看到空 SSID 才会出问题"——
            // **实测证明空 SSID 必然出问题，而且是崩溃级的**：下游
            // `Iap2WirelessIdentification` 有 `require(ssid.isNotBlank())`，
            // 空串会让整个无线 bring-up 抛 IllegalArgumentException 直接失败
            // （诊断包 `1791345066541` 实录 11:48:19.814）。
            //
            // 与 `sameLanHostAddress` 同一处理：在**配置构造**这一层就拦，
            // 报错信息能直接指导用户去设置页填，而不是拖到会话启动后崩掉。
            val ssid = sameLanWifiSsid?.takeIf { it.isNotEmpty() }
            require(ssid != null) {
                "sameLanWifiSsid is required in same-LAN mode " +
                    "(the iPhone hotspot's real SSID; a blank SSID crashes the " +
                    "wireless bring-up)"
            }
            require('\u0000' !in ssid) { "sameLanWifiSsid must not contain U+0000" }
            // 与手动热点同一条纪律：SSID 是网络名，超长必然是配置事故。
            require(ssid.toByteArray(Charsets.UTF_8).size <= 32) {
                "sameLanWifiSsid must be at most 32 bytes (UTF-8)"
            }
            val passphrase = sameLanWifiPassphrase.orEmpty()
            require('\u0000' !in passphrase) {
                "sameLanWifiPassphrase must not contain U+0000"
            }
            require(passphrase.isEmpty() || passphrase.length in 8..63) {
                "sameLanWifiPassphrase must be empty or between 8 and 63 characters"
            }
        }
    }

    companion object {
        const val APPLE_VENDOR_ID = 0x05ac
        val DEFAULT_HOST_MAC = byteArrayOf(0x02, 0x00, 0x00, 0x00, 0x00, 0x02)
        private fun isLinkLocalIpv6(value: String): Boolean {
            if (value.contains('%') || '\u0000' in value || !value.contains(':')) return false
            return try {
                val address = InetAddress.getByName(value)
                address is Inet6Address && address.isLinkLocalAddress
            } catch (_: Exception) {
                false
            }
        }
    }
}

fun isManualHotspotChannelCompatible(band: ManualHotspotBand, channel: Int): Boolean = when (band) {
    ManualHotspotBand.AUTO -> channel in 1..196
    ManualHotspotBand.GHZ_2_4 -> channel in 1..14
    ManualHotspotBand.GHZ_5 -> channel in 32..177
}

/**
 * **严格**的点分四段 IPv4 字面量（PATCH-0087）。
 *
 * 为什么不用 `InetAddress.getByName(...) is Inet4Address`：那个判据**太宽**。
 * Java（沿用 inet_aton 的老规矩）接受 `192.168.15` 这种"三段"写法，并把它解析成
 * **`192.168.0.15`** —— 与用户敲进去的字面量不是同一个地址。对一个"要拿去网卡上匹配"
 * 的字段来说，这种"能过校验、却永远匹配不上"是最坏的形态：现场只看到连不上，
 * 而设置里那个值看起来完全正常。
 *
 * 所以这里只有一条判据：四段十进制、每段 `0..255`、不许前导 `+`、空格或十六进制。
 */
internal fun isStrictIpv4Literal(value: String): Boolean {
    val parts = value.trim().split('.')
    if (parts.size != 4) return false
    return parts.all { part ->
        part.isNotEmpty() &&
            part.length <= 3 &&
            part.all { it in '0'..'9' } &&
            part.toInt() in 0..255
    }
}
