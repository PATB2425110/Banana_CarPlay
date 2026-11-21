package com.dbtool.carplay.api.hotspot

import com.dbtool.carplay.api.settings.CarHotspotMode

/**
 * 车机自带热点「开着吗」的**三态**读数。
 *
 * ## 为什么是三态而不是布尔
 *
 * 这是一个**拦截**判据：只有 [OFF] 才会挡掉一次连接。而"读不到"在车机上是常态
 * （厂商裁掉隐藏 API、权限不足、ROM 换了一套实现），把它当成 [OFF] 会让
 * **本来能用的复用车机热点**连不上——那比"多等 40 秒超时"糟糕得多。
 *
 * ⇒ 读不到一律 [UNKNOWN]，由调用方**放行**。
 *
 * 2026-10-08 移植自 DiPlay 的 `CarHotspotStatus`（`shared/.../network/CarHotspotStatus.kt`）。
 * 原文注释把这条纪律写得很直白：*"null when the firmware hides it
 * (then callers must not block the connection)"*。
 */
enum class CarHotspotEnabledState {
    ON,
    OFF,

    /** 读不到 —— **调用方不得据此阻断连接**。 */
    UNKNOWN,
}

/**
 * 「复用车机热点」在连接前的一道**快速**前置检查。
 *
 * ## 它补的是什么
 *
 * 原先「复用车机热点」这一支**完全不做任何检查**：用户选了它、车机热点却是关着的，
 * 那么整条链路会走到上游 `ManualHotspotManager`，在那里**空转
 * `HOTSPOT_START_TIMEOUT_MILLIS`（数十秒）**，最后抛一句
 * `Timed out … waiting for the manual hotspot: local hotspot interface was not found`
 * ——**用户从中读不出下一步该做什么**（要去车机设置里把热点打开）。
 *
 * DiPlay 的做法（`CarPlayController.startWirelessHotspot`）：先读一次车机热点开关，
 * 明确读到"关着"就立刻抛一句可执行的话：
 * *"The car hotspot is off. Turn it on in the car settings and connect again."*
 *
 * ## 为什么这一支是纯逻辑
 *
 * 读开关需要 `WifiManager` 与 `Context`（Android 侧在 `CarHotspotEnabledReader`）。
 * 但"什么情况该拦、文案怎么写"是**纯决策**，放这里就能脱机单测——
 * 与 `CarNativeHotspotMerge` / `CarHotspotSubnet` 同一手法。
 *
 * ## 只作用于「复用车机热点」
 *
 * [blocker] 对除 [CarHotspotMode.MANUAL] 以外的模式**一律返回 null**：
 * - `ADB_HOSTAPD`（自建热点）：那张网是**我们自己起的**，车机开关关着才正常；
 * - `AUTO`：解析阶段就已经落到 `ADB_HOSTAPD`（见 `CarHotspotResolver.resolve`）；
 * - `SAME_LAN`：一张热点都不建。
 */
object CarManualHotspotPrecheck {

    /**
     * `WifiManager.getWifiApState()` 的取值区间是 `10..14`，其中 `13` = `ENABLED`。
     *
     * ⚠️ 区间之外的取值一律 [CarHotspotEnabledState.UNKNOWN]，**不是 OFF**：
     * 有些 ROM 会返回 `0` / `-1` / 自定义枚举，把它当"关着"就是错杀。
     */
    private const val AP_STATE_MIN = 10
    private const val AP_STATE_MAX = 14
    private const val AP_STATE_ENABLED = 13

    /** 把 `getWifiApState()` 的原始值翻译成三态。 */
    fun fromWifiApState(state: Int?): CarHotspotEnabledState = when {
        state == null -> CarHotspotEnabledState.UNKNOWN
        state !in AP_STATE_MIN..AP_STATE_MAX -> CarHotspotEnabledState.UNKNOWN
        state == AP_STATE_ENABLED -> CarHotspotEnabledState.ON
        // 10 = DISABLING / 11 = DISABLED / 12 = ENABLING / 14 = FAILED
        //
        // ⚠️ `12`（ENABLING，正在开）也算 OFF：这一刻热点**还没起来**，
        // 上游此刻 attach 必然找不到接口。让它走快速失败 + 可执行文案，
        // 比让它空转数十秒更好。用户按提示再点一次即可。
        else -> CarHotspotEnabledState.OFF
    }

    /**
     * 该不该在**开始连接之前**就拦下这次「复用车机热点」。
     *
     * @param mode 本次会话实际用的热点形态（**不是**上游那个被压扁成 MANUAL 的枚举）。
     * @param state [CarHotspotEnabledReader] 读到的三态。
     * @param ssid 设置页里填的车机热点名，只用于让文案能指名道姓；可为 null。
     * @param hostAddress 设置页里填的车机热点地址（`manualHotspotHostAddress`）；可为空。
     * @param reuseRequested true = 这一支**真的是**"复用车机热点"（用户显式选了它）。
     *        P2P 建组成功后也会被翻译成 `MANUAL` 形状的 plan，但它那条路的地址来自
     *        组主的 GO、不从设置读 —— 靠本参数把它排除在这条检查之外。
     * @return 要显示给用户的中文文案；null = 放行。
     */
    fun blocker(
        mode: CarHotspotMode,
        state: CarHotspotEnabledState,
        ssid: String? = null,
        hostAddress: String? = null,
        reuseRequested: Boolean = false,
    ): String? {
        // 复用类档位（MANUAL / TCAM_HOTSPOT）都要这道预检：车机热点关着时，
        // 两档都会在连接时干等到超时（TCAM 由 `TcamHotspotManager` 报热点未开）。
        // ⚠️ `reuseRequested` 只管地址是否必须由用户填——TCAM 档地址自动识别，
        // 调用方对它传 false，所以放宽门槛不会给 TCAM 档误报地址没填。
        // ⚠️ 2026-10-25：GEELY_ROUTED（车机热点）**故意不做这道预检**。
        //
        // 本预检判的是"车机自带的 Wi-Fi AP 开没开"（反射 `isWifiApEnabled`），
        // 而车机热点走的是原厂**路由**网段（`eth*` + 198.18.x.x），与 Wi-Fi AP 的开关
        // 不是同一件事 —— 拿 AP 开关去拦它会产生"热点明明能用却说没开"的误报。
        // 新档的"热点没开"由 `ensureGeelyRoutedReady` 的扫地址结果判定，
        // 并且会给出分阶段文案（无 eth* / 有 eth* 无 198.18 / 超时）。
        if (mode != CarHotspotMode.MANUAL && mode != CarHotspotMode.TCAM_HOTSPOT) return null
        // 🔴 2026-10-11（PATCH-0087）：「复用车机热点」的地址**必须由用户填**
        // （车主口径：*"复用车机热点是要用户自己手动设置，而不是自动识别"*）。
        // 空 ⇒ 拦下并说清填什么，而不是回落到"按网卡名猜一个"——
        // 2026-10-11 那两份日志里，我们猜到的是**自己自建热点的残留地址**，
        // 一次让 iPhone 白等 40 秒，一次把假结论写进了诊断包。
        val addressMissing = reuseRequested && hostAddress.isNullOrBlank()
        if (state != CarHotspotEnabledState.OFF && !addressMissing) return null
        // 🟢 2026-10-11（PATCH-0088）：吉利 Ecarx（CS11/KX11）平台上，外置 T-Box AP
        // 不进入标准 Android SoftAP API，此时 `wifiApState=11` 可能是假关闭。历史手填地址
        // 若是本机 `198.18/15` endpoint，不要仅凭标准 AP 状态一票否决；后续仍会核对它
        // 是否真的挂在本机接口上。T-Box 网关地址不是本机 CarPlay endpoint。
        // 未填地址时保持既有缺省拦截；PATCH-0099 的运行时已验证 Ecarx route 会提供临时 endpoint。
        if (state == CarHotspotEnabledState.OFF && !addressMissing &&
            isEcarxLocalRouteSourceAddress(hostAddress)
        ) return null
        val named = ssid?.takeIf { it.isNotBlank() }?.let { "「$it」" } ?: ""
        return buildString {
            // ⚠️ 汉字在 Kotlin 里是**合法标识符字符**，所以 `$named现在` 会被整个当成一个变量名
            // （编译报 Unresolved reference 'named现在是关闭状态'）。模板插值后面紧跟汉字时
            // **必须**写 `${...}`。这条同样适用于日志文案与测试断言。
            //
            // ⚠️ "关着"那句必须**原样保持开头**：它是 2026-10-08 移植 DiPlay 时钉下的
            // 文案，`CarManualHotspotPrecheckTest` 里有 `startsWith` 断言守着。
            if (state == CarHotspotEnabledState.OFF) {
                append("车机自带热点${named}现在是关闭状态。")
                append("请先在车机设置里打开「个人热点」，或把热点方案改成「自建热点」再连接。")
            }
            if (addressMissing) {
                if (isNotEmpty()) append('\n')
                append("另外：复用模式需要一个 iPhone 可访问的本机 CarPlay/AirPlay IPv4 地址。")
                append("请在「热点身份」里填写承载 CarPlay 服务的本机接口地址；")
                append("外置 T-Box 热点网关（例如 192.168.15.1）不是本机地址，不能当作 endpoint。")
            }
        }
    }

    /**
     * 用户填的本机 endpoint 是否落在已知 Ecarx 车载内网源地址段（`198.18/15`）。
     *
     * 这是 PATCH-0088 的窄兼容：Ecarx 的 SoftAP 状态可能假报关闭，而在用户手填
     * 一个本机 `198.18/15` 地址时，不能仅凭该假关闭拦截。地址必须在后续本机接口检查
     * 中确认存在；这里不把它认作 T-Box/AP 网关，也不单独证明路由有效。
     *
     * `198.18.0.0/15` 是 RFC 2544 基准测试保留段（`198.18.0.0`–`198.19.255.255`），
     * 车厂拿它做车载内网是常见做法。
     */
    private fun isEcarxLocalRouteSourceAddress(hostAddress: String?): Boolean {
        val text = hostAddress?.trim()?.substringBefore('/')?.substringBefore('%') ?: return false
        if (text.isEmpty()) return false
        val parts = text.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        // 198.18.0.0/15：前两字节 198.18 或 198.19
        return octets[0] == 198 && (octets[1] == 18 || octets[1] == 19)
    }
}
