package com.dbtool.carplay.bridge

/**
 * 上游异常文案 → 中文。
 *
 * ## 为什么需要它
 *
 * 上游所有 `require { }` / `check { }` / `error()` 的文案都是英文，而
 * [CarPlaySessionBridge] 在启动失败时把 `error.message` **原样**塞进
 * `CarSessionState.errorMessage`，这个字段最终就是首页「连接失败」下面的副标题。
 *
 * 现场实测到的就是这一句：
 * ```
 * A server address must be configured for the remote MFi target
 * ```
 * 在一整套中文界面里非常突兀——而且它恰恰是排障最该读懂的一句。
 *
 * ## 三层策略
 *
 * 1. **精确匹配** [EXACT]：上游固定文案，逐条给中文。绝大多数校验失败走这里。
 * 2. **前缀匹配** [PREFIXES]：带参数的文案（`Could not connect RFCOMM to AA:BB:…`），
 *    翻译骨架、**保留参数**（地址、长度这些是排障的关键信息，不能丢）。
 * 3. **兜底** [FALLBACK]：不认识的一律给一句**中文的行动指引**。
 *
 * ⚠️ 兜底刻意**不是**"直接显示英文原文"：
 * - 界面语言必须一致，否则用户会以为"这里出问题了所以显示乱码"；
 * - 需要原文排障时，日志区里上游自己已经完整打印过一遍了
 *   （`platform.diagnostics` 与诊断页的日志列表）。
 *
 * ## 维护规则
 *
 * 只加**确定会冒到界面上**的条目：
 * - 会话启动路径——`CarPlayController.start()` 及其调用的
 *   hotspot / MFi / VPN / iAP2 管理器；
 * - `CarPlayRuntimeConfig` 的构造校验（配置组合非法时抛的就是这些）；
 * - 媒体管线里会让画面/声音直接不出来的那几条。
 *
 * **协议内部的不变量不要加**（`bplist: bad magic`、`DER value is truncated`、
 * `Iap2WireCodec` 的取值域校验…）。它们不会单独成为失败原因，
 * 塞进来只会让这张表迅速烂掉、且永远无法验证是否还准确。
 *
 * ## 与上游版本的关系
 *
 * 上游改文案时，对应条目会**静默失效**并落到 [FALLBACK]——表现为"英文消失、
 * 变成一句通用提示"，不会崩、不会显示英文。这是刻意的降级方向：
 * 宁可少一句精确原因，也不要漏出英文。
 */
internal object CarErrorText {

    /**
     * 把上游异常文案翻成中文。
     *
     * @return null 表示入参为 null/空白——调用方据此保留"没有错误信息"的语义，
     *         不要拿 [FALLBACK] 去顶替。
     */
    fun of(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        translate(text)?.let { return it }

        // ⚠️ 上游 `CarPlayController.startWirelessHotspot` 会把**内层**失败原因
        // 再包一层：
        //   "Could not establish <MODE> hotspot: <内层英文>"
        // 而内层那句才是真正有用的。不剥壳的话，表里那些热点内层文案
        // （"did not report a usable SSID" / "startup was cancelled" / …）
        // **一条都匹配不上**，全都落到 [FALLBACK]——表现是"失败了但不说为什么"。
        WRAPPER.find(text)?.let { match ->
            val mode = match.groupValues[1]
            val inner = match.groupValues[2].trim()
            translate(inner)?.let { return it }
            return hotspotWrapperFallback(mode)
        }
        return FALLBACK
    }

    /** 查表：精确 → 前缀。返回 null 表示认不出来。 */
    private fun translate(text: String): String? {
        EXACT[text]?.let { return it }
        for ((prefix, template) in PREFIXES) {
            if (text.startsWith(prefix)) {
                return template.replace(PLACEHOLDER, text.removePrefix(prefix))
            }
        }
        return null
    }

    /**
     * 剥掉包装层后、内层也认不出来时给一句**分模式**的兜底。
     *
     * 比一句通用的"连接未能建立"有用得多：至少告诉用户是**哪一支**热点没起来
     * （接管车机热点 / 自建热点），以及该往哪个方向查。
     *
     * ⚠️ **刻意不把内层英文附在括号里**：本文件的既定原则是"宁可少一句精确原因，
     * 也不要漏出英文"（见类注释）。内层原文不丢——上游自己已经把它打进日志，
     * 诊断页的日志列表里能逐字比对。
     */
    private fun hotspotWrapperFallback(mode: String): String = when (mode) {
        // ⚠️ 2026-10-03：我们的产品线已不再产生 LOCAL_ONLY_HOTSPOT / WIFI_P2P 这两种
        // 形态（见 CarHotspotMode），但**上游 `WirelessHotspotMode` 里它们仍存在**，
        // 老配置文件、或将来上游新增的路径仍可能吐出这两种名字。保留翻译比删掉稳妥：
        // 用户至少能看到一句中文，而不是英文原文。
        "LOCAL_ONLY_HOTSPOT" ->
            "系统热点启动失败，这台车机不支持该方式——" +
                "请改用「自建热点」或复用车机自带热点"
        "WIFI_P2P" -> "Wi-Fi P2P 方式启动失败，请改用其它热点方式"
        // PATCH-0064：这个模式现在会**自己**打开车机 Wi-Fi（真车日志里车机 Wi-Fi 平时
        // 就是关的），所以兜底不能再写"请先打开 Wi-Fi"——用户已经不需要做这件事了。
        "WIFI_P2P_LEGACY" ->
            "Wi-Fi Direct 临时组网失败：DBPlay 已尝试自动打开车机 Wi-Fi；" +
                "若仍失败，请确认没有其他应用占用 Wi-Fi Direct，或改用其它热点方式"
        "MANUAL" ->
            "无法接管车机自带热点：请确认它已开启，且设置里的名称与密码与它一致"
        // 2026-10-25：新增「车机热点」独立枚举值。它扫 eth* 上的 198.18.x.x，
        // 失败点与 MANUAL 不同（不是"热点没开"，而是"找不到车机路由网段地址"），
        // 所以文案要分开 —— 否则用户按 MANUAL 的提示去检查热点开关，白费功夫。
        "GEELY_ROUTED" ->
            "没找到车机热点网络（eth* 上的 198.18.x.x 地址）：" +
                "请确认车机热点已开启，或在设置里改用「复用车机热点」"
        else -> FALLBACK
    }

    /**
     * 上游热点失败的包装层：`Could not establish <MODE> hotspot: <内层原因>`。
     *
     * `<MODE>` 是上游 `WirelessHotspotMode` 的枚举名；对**本产品**来说实际只可能是
     * `MANUAL`（我们的 `ADB_HOSTAPD` 借道 `MANUAL`，不会出现在这里）。上游还有
     * `LOCAL_ONLY_HOTSPOT` / `WIFI_P2P` 两个值，但它们对应的形态已在 2026-10-03
     * 从我们的产品线整体移除（见 `CarHotspotMode`），这里不再为它们维护文案。
     * `DOT_MATCHES_ALL` 是必需的：内层原因里可能带换行。
     */
    private val WRAPPER =
        Regex("""^Could not establish (\w+) hotspot: (.*)$""", RegexOption.DOT_MATCHES_ALL)

    /** 参数占位符。用 `{0}` 而不是 `%s`：文案里本来就可能出现百分号。 */
    private const val PLACEHOLDER = "{0}"

    /**
     * 认不出原因时的兜底。
     *
     * 给的是**下一步动作**而不是"未知错误"——用户拿"未知错误"什么也做不了。
     */
    const val FALLBACK = "连接未能建立，请检查连接方式与热点设置后重试"

    /** 带参数的上游文案：`前缀` → 含 `{0}` 的中文模板。 */
    private val PREFIXES: List<Pair<String, String>> = listOf(
        // ---------------------------------------------------- 我们自己的中文文案（放行）
        // `:shared` 的 `CarPlayController.armWirelessAttachWatchdog` 在
        // 「蓝牙已通、iPhone 始终不来连 AirPlay」超时重试耗尽后 `fail()` 的就是
        // 这句**中文**（含 SSID 与「忽略此网络」指引）。它经
        // `CarPlayStatus.Failed` → `CarPlayStatusMapper.detailOf` 时也要过本表，
        // 不放行就会被兜底成「连接未能建立」，把最有用的指引吃掉。
        // 模板 = 原样放行：`{0}` 接住前缀之后的全部内容。
        "无线 CarPlay 未连上：" to "无线 CarPlay 未连上：{0}",

        // ---------------------------------------------------- 热点失败（包装层）
        // ⚠️ 这两条**必须排在剥壳之前**（`of()` 里先跑 [translate] 再剥壳）：
        // 内层文案单独看不够特异——"Timed out after …" 与蓝牙的
        // "Timed out after …ms connecting RFCOMM to …" 前缀相同，只有带上
        // 包装层才能确定是"系统热点超时"。
        //
        // 模板里**没有 `{0}`**：`replace` 是空操作，正好把内层的英文参数丢掉
        // （60000ms / 枚举名这类参数对用户没有意义，日志里有原文）。
        "Could not establish LOCAL_ONLY_HOTSPOT hotspot: Timed out after " to
            "系统热点启动超时：这台车机的热点接口不会回调，该方式不可用" +
                "——请改用「ADB 独立热点」或复用车机自带热点",
        "Could not establish LOCAL_ONLY_HOTSPOT hotspot: " +
            "Caller already has an active LocalOnlyHotspot request" to
            "系统热点上次尝试残留了未释放的请求，本进程内已不可再用" +
                "——请改用「ADB 独立热点」或复用车机自带热点",

        // ---------------------------------------------------- PATCH-0064：Wi-Fi Direct 上电/建组
        // ⚠️ 这三条**必须带完整包装层**（`Could not establish WIFI_P2P_LEGACY hotspot: …`）：
        // 内层文案都以 "Wi-Fi Direct " 开头，而"Wi-Fi Direct 打开 Wi-Fi 超时"这句
        // 若只写 `Timed out after …` 前缀，会与蓝牙的 `Timed out after …ms connecting
        // RFCOMM to …` 撞车（本表的 PREFIXES 是 `startsWith` 匹配，先命中者胜）。
        // 包装层里带上枚举名之后，前缀才真正唯一。
        "Could not establish WIFI_P2P_LEGACY hotspot: " +
            "Wi-Fi Direct could not turn on the head unit's Wi-Fi station" to
            "Wi-Fi Direct 无法打开车机 Wi-Fi（系统拒绝了开启请求）" +
                "——请确认没有其他应用占用 Wi-Fi，或改用其它热点方式",
        "Could not establish WIFI_P2P_LEGACY hotspot: " +
            "Wi-Fi Direct did not see the head unit's Wi-Fi turn on within " to
            "Wi-Fi Direct 打开车机 Wi-Fi 超时：系统迟迟没有把 Wi-Fi 打开" +
                "——请重试，或改用其它热点方式",
        "Could not establish WIFI_P2P_LEGACY hotspot: Wi-Fi P2P createGroup failed after " to
            "Wi-Fi Direct 建组连续失败：车机 Wi-Fi 已开启，但 Wi-Fi Direct 一直起不来" +
                "——请确认没有其他应用占用 Wi-Fi Direct，或改用其它热点方式",

        "Could not connect RFCOMM to " to "无法与 iPhone 建立蓝牙连接（{0}）",
        "Interrupted while connecting RFCOMM to " to "与 iPhone 的蓝牙连接被中断（{0}）",
        "Invalid iAP2 MFi challenge length " to "iPhone 下发的 MFi 挑战数据长度非法（{0}）",
        "Invalid remote signature length " to "MFi 服务器返回的签名长度非法（{0}）",
        "Invalid remote MFI protocol major " to "MFi 服务器返回的协议版本不受支持（{0}）",
        "Unsupported remote MFI certificate type " to "MFi 服务器返回了不支持的证书类型（{0}）",
        "Remote MFI request failed: " to "无法连接 MFi 服务器：{0}",
        "Remote MFI response exceeds " to "MFi 服务器返回的数据过大（{0}）",
        "Remote MFI response is missing " to "MFi 服务器返回的数据缺少字段（{0}）",
        "Invalid advertised host: " to "AirPlay 广播的主机地址非法（{0}）",
        "Invalid LocalOnlyHotspot channel " to "热点信道非法（{0}）",
        "LocalOnlyHotspot reported an invalid BSSID: " to "系统返回的热点地址非法（{0}）",
        "Expected one LocalOnlyHotspot channel, got " to "系统返回了多个热点信道（{0}）",
        "Unsupported LocalOnlyHotspot security type: " to "系统返回了不支持的热点加密方式（{0}）",
        "iPhone USB identities must use Apple vendor ID " to
            "iPhone 的 USB 标识必须使用 Apple 厂商 ID（{0}）",
        "Unknown iAP2 endpoint " to "收到未知的 iAP2 端点（{0}）",
    )

    /** 精确匹配表。 */
    private val EXACT: Map<String, String> = buildMap {
        // ---------------------------------------------------- 会话配置校验
        // 上游 `CarPlayRuntimeConfig` 的 init 块。用户在设置页选了某一种
        // 认证/热点方式却没把配套项填全时，抛的就是这一组。
        put(
            "A server address must be configured for the remote MFi target",
            "认证方式选了「远程认证」，但没有填写 MFi 服务器地址",
        )
        put(
            "A Linux I2C path must be configured for the I2C MFi target",
            "认证方式选了「I2C」，但没有配置 I2C 设备路径",
        )
        put(
            "CH341 devices must be configured for the USB/CH341 MFi target",
            "认证方式选了「USB/CH341」，但没有配置任何 CH341 设备",
        )
        put("CH341 MFi reset GPIO must be D0..D5", "CH341 复位引脚只能是 D0~D5")
        put("Remote MFi server must not contain U+0000", "MFi 服务器地址含非法字符")
        put("Remote MFi token must not contain U+0000", "MFi 访问令牌含非法字符")
        put("manualHotspotSsid is required in manual hotspot mode", "手动热点模式下必须填写热点名称")
        put("manualHotspotSsid must not contain U+0000", "热点名称含非法字符")
        put("manualHotspotPassphrase must not contain U+0000", "热点密码含非法字符")
        put(
            "manualHotspotPassphrase must be empty or between 8 and 63 characters",
            "热点密码必须留空，或为 8~63 位",
        )
        put("manualHotspotChannel must be 0 or in 1..196", "热点信道只能是 0（自动）或 1~196")
        put(
            "manualHotspotChannel is not valid for the selected manual hotspot band",
            "所选频段下没有这个信道",
        )
        put(
            "A passphrase between 8 and 63 characters is required for secured manual hotspots",
            "要接管的热点需要密码，请填写 8~63 位密码",
        )
        put(
            "manualHotspotPassphrase is required for secured manual hotspots",
            "要接管的热点需要密码，请填写热点密码",
        )
        put("label must not be blank", "设备标识不能为空")
        put("hostName must not be blank", "主机名不能为空")
        put("hostMac must be 6 bytes", "本机网络配置有误：网卡地址必须是 6 字节")
        put(
            "linkLocal must be a link-local IPv6 literal",
            "本机网络配置有误：链路本地地址必须是 IPv6 格式",
        )
        put(
            "availableCurrentMilliAmps must be in 0..65535",
            "本机配置有误：可用电流必须在 0~65535 mA",
        )

        // ---------------------------------------------------- 启动主流程
        put("MFi coprocessor client is unavailable", "本机没有可用的 MFi 认证模块")
        put("Bluetooth adapter is unavailable", "本机没有蓝牙适配器")
        put("Bluetooth is not enabled", "蓝牙未开启，请先打开蓝牙再重试")
        put("Could not bind the CarPlay AirPlay service", "无法启动 AirPlay 服务")
        put("Wireless AirPlay transport is already attached", "无线 AirPlay 通道已经建立")
        put("Manual hotspot SSID is not configured", "没有配置要接管的热点名称")
        put(
            "No bonded PHONE_SMART devices found; pair an iPhone and retry",
            "没有找到已配对的 iPhone，请先在蓝牙里配对后重试",
        )
        put("Wireless Bluetooth connection was cancelled", "无线蓝牙连接已取消")
        put("LocalOnlyHotspot host address is unavailable", "热点没有拿到可用地址")

        // ---------------------------------------------------- 热点
        put("WifiManager is unavailable", "本机没有可用的 Wi-Fi 模块")
        put("WifiP2pManager is unavailable", "本机没有可用的 Wi-Fi P2P 模块")
        put(
            "Wi-Fi P2P credentials require Android 10 (API 29) or newer",
            "系统版本过低：Wi-Fi P2P 需要 Android 10 及以上",
        )
        put("Wi-Fi P2P device became a group client instead of owner", "Wi-Fi P2P 没有成为组拥有者")
        // ---------------------------------------------------------------- PATCH-0067
        // Wi-Fi Direct 的两条**可行动**失败（原先都落进"建组连续失败"那句，
        // 用户看不出到底是车不支持、还是定位没开）：
        put(
            "Wi-Fi Direct is not supported by this head unit",
            "这台车机的固件没有 Wi-Fi Direct 能力，该方式在此车不可用——" +
                "请改用「自建热点」或「复用车机热点」",
        )
        put(
            "Wi-Fi Direct needs the head unit's location switch to be on",
            "车机定位开关当前是关着的：Android 9 的 Wi-Fi Direct 需要定位开启，" +
                "请在车机设置里打开定位后重试",
        )
        // 真车实证（日志 1791626784246）：三条前提全部满足，框架仍一直回 BUSY
        // ⇒ 这台车机的 Wi-Fi Direct 服务本身跑不起来。别让它落进"被别的应用占用"
        // 那句（那句话会把用户引向一个查不出结果的方向）。
        put(
            "Wi-Fi Direct is not usable on this head unit",
            "这台车机的 Wi-Fi Direct 服务不可用（车机 Wi-Fi 已开启、定位已开、" +
                "固件也声明支持，但框架一直回「忙」）——请改用「自建热点」或「复用车机热点」",
        )
        // 2026-10-11 真车（设备 67b3c24af6b5）：三条前提全满足、`createGroup` 一律 5ms 内回 BUSY，
        // 且 P2P 网卡 `p2p0` 一直是 DOWN ⇒ 框架侧的 P2P 根本没起来（固件侧）。
        // 参考实现 DiPlay 在 CS11 上走的也是热点路（见 `LynkWifiP2pGroup` 类注释）——
        // 与"DBPlay 里 P2P 无法临时组网"这条口径一致，所以直接给出可行动结论。
        put(
            "Wi-Fi Direct is not running on this head unit",
            "这台车机的 Wi-Fi Direct 服务没有在运行（P2P 网卡 p2p0 处于 DOWN）——" +
                "该方式在此车不可用，请改用「自建热点」或「复用车机热点」",
        )
        put("Wi-Fi P2P startup was cancelled", "Wi-Fi P2P 启动被取消")
        put("Wi-Fi P2P group stopped before startup completed", "Wi-Fi P2P 在启动完成前就停止了")
        put("WifiP2pGroupManager closed while starting", "Wi-Fi P2P 启动过程中会话被关闭")
        put("MFi certificate must not be empty", "缺少 MFi 证书")
        put(
            "Manual hotspot is secured but no passphrase was provided",
            "要接管的热点需要密码，但设置里没有填",
        )
        put("Manual hotspot is not running on 2.4 GHz", "车机热点不在 2.4 GHz 频段")
        put("Manual hotspot is not running on 5 GHz", "车机热点不在 5 GHz 频段")
        put("LocalOnlyHotspot did not provide a Wi-Fi configuration", "系统没有返回热点配置")
        put("LocalOnlyHotspot did not report a usable SSID", "系统没有给出可用的热点名称")
        put(
            "LocalOnlyHotspot did not report a passphrase for secured Wi-Fi",
            "系统没有给出热点密码",
        )
        put("LocalOnlyHotspot did not report its key management", "系统没有返回热点的加密方式")
        put("LocalOnlyHotspot SSID contains U+0000", "热点名称含非法字符")
        put("LocalOnlyHotspot passphrase contains U+0000", "热点密码含非法字符")
        put("SoftApConfiguration.getChannel returned no channel", "系统没有返回热点信道")
        put("LocalOnlyHotspot startup was cancelled", "热点启动被取消")
        put("LocalOnlyHotspot stopped while starting", "热点在启动完成前就停止了")
        put("LocalOnlyHotspot stopped before startup completed", "热点在启动完成前就停止了")
        put("LocalOnlyHotspot manager closed while starting", "热点启动过程中会话被关闭")
        put("ssid must not be blank", "热点名称不能为空")
        put("Wireless SSID must not be blank", "热点名称不能为空")
        put("Wireless SSID must not contain U+0000", "热点名称含非法字符")
        put("ssid is required and must not be blank", "热点名称不能为空")
        put("passphrase is required for secured Wi-Fi", "加密热点必须填写密码")
        put("channel must be in 0..255", "热点信道必须在 0~255 之间")

        // ---------------------------------------------------- MFi 认证
        put("Timed out waiting for iAP2 MFi authentication", "等待 iPhone 完成 MFi 认证超时")
        put("Timed out sending iAP2 MFi authentication reply", "发送 MFi 认证结果超时")
        put("iPhone sent AuthenticationFailed", "iPhone 拒绝了本次 MFi 认证")
        put("iAP2 MFi challenge is missing parameter 0", "iPhone 下发的 MFi 挑战数据不完整")
        put("Authenticator does not provide BAA certificates", "认证模块没有提供 BAA 证书")
        put("BAA leaf certificate must not be empty", "本机 MFi 身份缺少叶子证书")
        put("BAA intermediate certificate must not be empty", "本机 MFi 身份缺少中间证书")
        put("Remote MFI server address must not be blank", "MFi 服务器地址不能为空")
        put("Remote MFI request failed", "无法连接 MFi 服务器")
        put("Remote MFI service is not using BAA certificates", "MFi 服务器返回的不是 BAA 证书")
        put(
            "Remote certificate SHA-256 does not match certificateSha256",
            "MFi 服务器返回的证书校验不通过",
        )

        // ---------------------------------------------------- 网络与 VPN
        put("VpnService.establish returned null", "无法建立 VPN 通道（系统拒绝）")
        put("AirPlay control service has no host address", "AirPlay 服务没有可用地址")
        put("AirPlay control probe returned no status line", "AirPlay 服务探测失败")
        put("At least one wired IPv6 address is required", "没有可用的有线 IPv6 地址")
        put("At least one wireless IP address is required", "没有可用的无线 IP 地址")
        put("Every wired address must be an IPv6 text literal", "有线地址不是合法的 IPv6")
        put("publicKey is required and must not be empty", "缺少设备公钥")
        put("sourceVersion is required and must not be empty", "缺少协议版本号")
        put("deviceIdentifier is required and must not be blank", "缺少设备标识")
        put("Identification identity strings must not be blank", "设备标识不能为空")
        put("Identification language must not be blank", "系统语言标识不能为空")
        put("External accessory protocol must not be blank", "配件协议标识不能为空")

        // ---------------------------------------------------- 音视频管线
        // 这一组不会让"会话"失败，但会让画面/声音出不来，同样是用户会追问的。
        put("Missing AVC parameter sets", "视频流缺少 H.264 参数集")
        put("Missing or invalid video parameter sets", "视频流的参数集无效")
        put("Malformed video access unit", "视频数据损坏")
        put("Empty or malformed video access unit", "视频数据为空或已损坏")
        put("Invalid NAL header", "视频流格式异常")
        put("Invalid HEVC NAL header", "H.265 视频流格式异常")
        put("Invalid SPS dimensions", "视频流分辨率非法")
        put("Truncated video configuration", "视频配置数据不完整")
        put("Reserved NAL length size", "视频流使用了保留的 NAL 长度")
        put("microphone recorder failed to initialize", "麦克风初始化失败")
        put("Opus system encoder returned a null input buffer", "音频编码器异常（输入缓冲为空）")
        put("Opus system encoder returned a null output buffer", "音频编码器异常（输出缓冲为空）")
    }
}
