package com.dbtool.carplay.bridge

import com.dbtool.carplay.api.hotspot.CarAdbHotspotDefaults
import com.dbtool.carplay.api.display.CarClusterSpec
import com.dbtool.carplay.api.display.CarClusterSafeAreaInsets
import com.dbtool.carplay.api.display.CarCustomDisplay
import com.dbtool.carplay.api.display.CarDisplay
import com.dbtool.carplay.api.settings.CarDrivingSide
import com.dbtool.carplay.api.settings.CarHotspotBand
import com.dbtool.carplay.api.settings.CarHotspotMode
import com.dbtool.carplay.api.settings.CarHotspotSecurity
import com.dbtool.carplay.api.settings.CarMfiTarget
import com.dbtool.carplay.api.settings.CarPhysicalSizeBasis
import com.dbtool.carplay.api.settings.CarSessionSettings
import com.dbtool.carplay.api.settings.CarTransportMode
import com.dbtool.carplay.bridge.hotspot.CarHotspotClientWatcher
import com.dbtool.carplay.bridge.network.CarHotspotPlan
import com.dbtool.carplay.bridge.network.CarHotspotResolver
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplaySettings
import com.shilapi.xcertplay.airplay.AirPlayIcon
import com.shilapi.xcertplay.airplay.AirPlayInsets
import com.shilapi.xcertplay.airplay.AirPlayPhysicalSizeBasis
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.airplay.CarPlayDisplayScale
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.UsbDeviceId

/**
 * [CarSessionSettings] → upstream 配置对象。
 *
 * **这是整个二开工程唯一的"设置翻译点"。**
 * upstream 改配置结构时只改这里；UI（`:car:ui`）与车机适配器
 * （`:car:platform-*`）完全不感知。
 *
 * 默认值与 upstream `CarPlayHostActivity.createRuntimeConfig()` /
 * `createAirPlayConfig()` 逐项对齐，保证二开版在默认设置下的行为与上游一致。
 */
object CarPlayConfigBuilder {

    /**
     * @param localMfiEndpoint [CarMfiTarget.LOCAL_OFFLINE] 时本进程环回服务的地址
     *        （形如 `http://127.0.0.1:54321`）；其余目标必须留空。
     * @param localMfiToken 与 [localMfiEndpoint] 配套的一次性访问令牌。
     * @param hotspotPlan [CarHotspotMode.AUTO] 的解析结果（见 `CarHotspotResolver`）。
     *        传 null 时会现场按 [settings] 解析一次——但**不要**在 UI 线程走这条路，
     *        它会做 binder 调用与网卡枚举。会话启动路径请显式传进来。
     */
    fun runtimeConfig(
        settings: CarSessionSettings,
        localMfiEndpoint: String? = null,
        localMfiToken: String? = null,
        hotspotPlan: CarHotspotPlan? = null,
        /**
         * PATCH-0010：`CarHotspotMode.SAME_LAN` 下要通告给 iPhone 的本机 IPv4。
         *
         * 由调用方在 worker 线程上读好（见 `CarSameLanReader`）再传进来——
         * 这里刻意不自己读：本函数会被多处调用，而读网络状态是 binder 调用，
         * 不该藏在"组装配置"这种看起来纯函数的地方。
         */
        sameLanHostAddress: String? = null,
        /**
         * PATCH-0010 修正：`CarHotspotMode.SAME_LAN` 下要通告给 iPhone 的 Wi-Fi 名。
         *
         * 通常不需要单独传——`hotspotPlan` 里已经带了（[CarHotspotPlan.sameLanWifiSsid]），
         * 本参数只是给"没有 plan、只有地址"的调用方留的口子。两者同时给时以
         * plan 为准（plan 里做过"设置手填 > 自动读取"的优先级判定）。
         */
        sameLanWifiSsid: String? = null,
        /** PATCH-0010 修正：与 [sameLanWifiSsid] 配套的密码（读不到就留空）。 */
        sameLanWifiPassphrase: String? = null,
    ): CarPlayRuntimeConfig {
        val localOffline = settings.mfiTarget == CarMfiTarget.LOCAL_OFFLINE
        // AUTO 必须在这里就解掉：上游没有"自动"这个模式。
        // 传进来的 plan 由调用方在 worker 线程上探测好（见 CarHotspotResolver）；
        // 没传就退化成"按设置自建"，至少不会把 AUTO 漏给上游。
        val hotspot = hotspotPlan ?: CarHotspotResolver.withoutProbe(settings)
        // 选了本机软件认证却没给端点，说明调用方漏了"启动环回服务"这一步。
        // 在这里直接报出来，比让上游在 checkNotNull(config.remoteMfiServer)
        // 抛一句 "Required value was null" 有用得多。
        require(!localOffline || !localMfiEndpoint.isNullOrBlank()) {
            "LOCAL_OFFLINE 需要先把本地 MFi 环回服务跑起来，并把地址传进来"
        }
        return CarPlayRuntimeConfig(
            mfiTarget = settings.mfiTarget.toUpstream(),
            ch341Devices = if (settings.mfiTarget == CarMfiTarget.USB_CH341) {
                listOf(UsbDeviceId(CH341_VENDOR_ID, CH341_PRODUCT_ID))
            } else {
                emptyList()
            },
            // upstream 刻意不脉冲 RST：过早驱动 D0 会把器件锁到 0x10 地址，
            // 那里读不到 accessory 证书。保持 null 与上游行为一致。
            ch341MfiResetGpio = null,
            linuxI2cPath = if (settings.mfiTarget == CarMfiTarget.I2C) {
                settings.linuxI2cPath.trim().ifBlank { null }
            } else {
                null
            },
            // 本机软件认证与"远程认证"共用同一条上游通路，只是地址来源不同：
            // 前者来自我们自己起的环回服务，后者来自用户填的地址。
            remoteMfiServer = if (localOffline) {
                localMfiEndpoint
            } else {
                settings.remoteMfiServer.trim().takeIf { it.isNotEmpty() }
            },
            remoteMfiToken = if (localOffline) {
                localMfiToken?.takeIf { it.isNotEmpty() }
            } else {
                settings.remoteMfiToken.takeIf { it.isNotEmpty() }
            },
            identification = Iap2IdentificationConfig(
                name = settings.deviceName,
                modelIdentifier = settings.model,
                manufacturer = settings.manufacturer,
                serialNumber = settings.deviceName,
                firmwareVersion = DEFAULT_FIRMWARE_VERSION,
                hardwareVersion = DEFAULT_HARDWARE_VERSION,
                // CarPlay USB interface number 固定 3，改动会破坏 USB 数据通路。
                carPlayUsbInterfaceNumber = CARPLAY_USB_INTERFACE_NUMBER,
                locationInformationEnabled = settings.locationReporting,
            ),
            label = settings.deviceName,
            hostName = settings.deviceName,
            transport = settings.transport.toUpstream(),
            wirelessHotspotMode = hotspot.mode.toUpstream(),
            manualHotspotSsid = hotspot.ssid,
            // ⚠️ 出口自洽闸门（Bug 修复）：上游 ManualHotspotManager 构造时硬校验
            // `OPEN ⇒ 密码必须为空`、`加密 ⇒ 密码必须 8~63 位`。任一上层分支
            // （显式设置 / 复用探测结果）若漏算，就会把「OPEN + 非空密码」这类
            // 矛盾组合交给上游，抛出一句对现场排障毫无帮助的 Kotlin `require` 文案
            // （实测刷 15+ 次 `passphrase must be empty for open manual hotspots`，
            // 车机热点直接起不来）。这里统一收口，保证两组字段永远自洽。
            manualHotspotPassphrase = passphraseFor(hotspot),
            manualHotspotBand = hotspot.band.toUpstream(),
            manualHotspotChannel = hotspot.channel,
            manualHotspotSecurity = hotspot.security.toUpstream(),
            // PATCH-0087：**复用时由用户填的车机热点地址**（上游据此按地址找网卡，
            // 不再按网卡名打分猜）。同理只在「复用」这一支传：
            // 自建的网段是我们自己规划的、P2P 的地址来自组主的 GO、SAME_LAN 走上面那条。
            manualHotspotHostAddress = hotspot.manualHostAddress
                ?.trim()
                ?.takeIf { it.isNotEmpty() && hotspot.reused },
            manualHotspotInterfaceName = hotspot.manualHostInterfaceName
                ?.trim()
                ?.takeIf { it.isNotEmpty() && hotspot.reused },
            // PATCH-0116：自建热点时告诉上游"这张 AP 是我们自己起的"，于是
            // `ManualHotspotManager` **不会**再拿车机系统里那份 `SoftApConfiguration`
            // 校验我们这张网（它们可能只是**同名**，见 ManualHotspotManager 的 KDoc）。
            //
            // 必须显式告诉它：复用 / ADB 自建 / AUTO **三种模式在上游都映射成
            // `WirelessHotspotMode.MANUAL`**（见本文件 `CarHotspotMode.toUpstream()`），
            // 上游从枚举分不出来。判据与 `CarHotspotResolver.explicit()` 的 `reuses`
            // 同一口径：只有「复用车机热点」是接管别人的 AP，AUTO 在解析阶段已落到自建。
            // PATCH-0116 的判据是"非复用即自建"：MANUAL / SAME_LAN / TCAM_HOTSPOT
            // 三支都不是我们自己起的网（TCAM 与 MANUAL 同属接管车机已有热点），
            // 只有 ADB_HOSTAPD（以及解析阶段早该落成自建的 AUTO）才 self-hosted。
            // 2026-10-25（二轮修正）：`GEELY_ROUTED` **故意设为 true，借用它跳过
            // `ManualHotspotManager` 的 `SoftApConfiguration` 校验**。
            //
            // 背景：上游 `ManualHotspotManager.start()` 里
            //     val apConfiguration = if (selfHostedAccessPoint) null
            //         else readApConfiguration()?.takeIf { it.ssid == expectedSsid }
            //     validateApConfiguration(apConfiguration)
            // 一旦读到车机那份 AP 配置且 SSID 与我们手填的一致，它就会拿车机的
            // **security** 去比我们手填的 security，不一致直接抛英文 IOException ——
            // 表现为「车机热点明明开着就是连不上」。参考包的 `GeelyRoutedHotspotManager`
            // **从不读 SoftApConfiguration**，没有这条失败路径。
            //
            // 为什么用 selfHosted 而不是新增参数：上游 `selfHostedAccessPoint` 只有两处
            // 用途（`ManualHotspotManager.kt:227` 跳过 AP 配置、`:558` 跳过
            // `resolveEcarxFactoryHotspot`），而第二处本来就会被 `hostAddressOverride != null`
            // （我们已回填 manualHostAddress）挡掉。所以这里的**净效果只有跳过 AP 配置校验**，
            // 不引入其它行为。
            //
            // ⚠️ 语义提醒：这个字段名字叫 self-hosted，但本档**不是**自建热点 ——
            // 它复用原厂路由网段。这里只是"借用同一个开关关掉配置校验"。
            // 想彻底干净就得给上游加 `skipApConfigurationValidation`，本轮不动 `:shared`。
            manualHotspotSelfHosted = hotspot.mode != CarHotspotMode.MANUAL &&
                hotspot.mode != CarHotspotMode.SAME_LAN &&
                hotspot.mode != CarHotspotMode.TCAM_HOTSPOT,
            // GEELY_ROUTED 借用 selfHosted 跳过配置校验，但它仍是原厂 AP，不能跳过稳定等待。
            manualHotspotSkipClientSettle = hotspot.mode == CarHotspotMode.ADB_HOSTAPD,
            // PATCH-0010：只在 SAME_LAN 模式下有意义，其余模式一律不传——
            // 传了会让上游多一段"配置里有个地址却没人用"的困惑。
            sameLanHostAddress = sameLanHostAddress
                ?.trim()
                ?.takeIf { it.isNotEmpty() && hotspot.mode == CarHotspotMode.SAME_LAN },
            // PATCH-0010 修正：SAME_LAN 下真正通告给 iPhone 的 Wi-Fi 名与密码。
            // 优先用 plan 里的值（那里已做"设置手填 > 自动读取"的优先级判定），
            // 只有在调用方没给 plan 时才回落到显式参数。其余模式一律不传。
            sameLanWifiSsid = (hotspot.sameLanWifiSsid ?: sameLanWifiSsid)
                ?.trim()
                ?.takeIf { it.isNotEmpty() && hotspot.mode == CarHotspotMode.SAME_LAN },
            sameLanWifiPassphrase = (hotspot.sameLanWifiPassphrase ?: sameLanWifiPassphrase)
                ?.takeIf { hotspot.mode == CarHotspotMode.SAME_LAN },
            locationReportingEnabled = settings.locationReporting,
            // PATCH-0133/0134：复用档 RFCOMM 早停只读**已有的**客户端数快照
            // （`CarHotspotClientWatcher`，内部就是 `EcarxHotspotReader` 只读读数；
            // MANUAL 复用与 TCAM 复用两条路径上都已经 `start()` 过它）。
            // 这里不新建探针、不发命令。多轮之间读不到（还没采到 / 本机不支持
            // Ecarx 读数）就返回 null ⇒ 不早停，保持原来的跑满 3 轮。
            hotspotClientCountProvider = if (
                (hotspot.mode == CarHotspotMode.MANUAL && hotspot.reused) ||
                hotspot.mode == CarHotspotMode.TCAM_HOTSPOT ||
                hotspot.mode == CarHotspotMode.GEELY_ROUTED
            ) {
                { CarHotspotClientWatcher.recentEvidence()?.clientCount }
            } else {
                null
            },
        )
    }

    /**
     * 让「安全类型 / 密码」配对自洽（Bug 修复）。
     *
     * 上游 `ManualHotspotManager` 的构造 `require`：
     * - `expectedSecurity != NONE || passphrase.isEmpty()` ⇒ 开放网络必须空密码；
     * - `expectedSecurity == NONE || passphrase.isNotEmpty()` ⇒ 加密网络必须有密码。
     *
     * 这里在**交给上游之前的最后一处**做同样的收敛：开放网络强制清空密码，
     * 加密网络若密码为空则退回出厂默认密码，避免把注定抛异常的组合递下去。
     */
    private fun passphraseFor(hotspot: CarHotspotPlan): String {
        val security = hotspot.security.toUpstream()
        if (security == ManualHotspotSecurity.OPEN) return ""
        return hotspot.passphrase.ifEmpty { CarAdbHotspotDefaults.PASSPHRASE }
    }

    /**
     * @param widthPixels/heightPixels 实际要声明的画布尺寸
     *        （通常是 UI 里视频 View 的实测尺寸，与 upstream 用 TextureView 尺寸口径一致）。
     *        ⚠️ 开了 `customDisplayEnabled` 时，调用方（`CarPlaySessionBridge`）传进来的
     *        已经是**用户填的自定义尺寸**（并且过了 `CarVideoSizeLimiter`），
     *        本函数只负责按 `customDisplayDpi` 换算物理尺寸、并跳过缩放。
     * @param icon OEM 图标。由 `CarAirPlayIconStore` 供给（用户导入优先、随包内置兜底）；
     *        传 null 时 `icons` 留空，上游会回落到它自带的占位图。
     *        ⚠️ 上游 `decodeAirPlayIcon()` **只接受正方形**，非正方形的图会被静默丢弃，
     *        所以裁剪这件事在 store 里做掉了，这里不再重复校验。
     * @param effectiveFps 解码器**实测能跑满**的帧率（来自 `CarVideoSizeLimiter.ClampResult`）。
     *        非空时覆盖 `settings.fps`——车机解不动用户选的帧率时就该按真实能力声明，
     *        否则 iPhone 按高帧率编码、车机侧当场掉帧。传 null 表示沿用 `settings.fps`
     *        （仍过 `sanitizeFps` 消毒）。
     */
    fun airPlayConfig(
        settings: CarSessionSettings,
        display: CarDisplay,
        widthPixels: Int,
        heightPixels: Int,
        effectiveFps: Int? = null,
        icon: AirPlayIcon? = null,
        /**
         * 仪表副屏声明规格（2026-10-07，吉利 HDMI2 移植）。
         *
         * 非空 ⇒ 在 `/info` 里多声明一块 `STREAM_TYPE_ALT_SCREEN = 111` 的屏，
         * iPhone 随即单独开一条视频流给它（上游 `AirPlayInfoPlist.build` 已支持）。
         * null ⇒ 与合并前完全一致（**默认零行为变化**）。
         *
         * ⚠️ 传进来的必须是**已解析好的面板规格**（见 `CarClusterSpec`），
         * 这里只做物理尺寸换算，不再做任何"能不能用"的判断——
         * 判断归平台适配器（它才知道本机有没有那块屏）。
         */
        cluster: CarClusterSpec? = null,
        /**
         * 沉浸模式（[CarSessionSettings.immersiveVideo]）下要避让的**顶部高度**（px）。
         *
         * 由调用方（`CarPlaySessionBridge`）从车机实测的 inset 快照里取好再传进来——
         * 这里刻意不自己去量：本函数是纯翻译层，且可能在无窗口时机被调。
         *
         * 没画过主屏安全区时的顶部回退高度（px）。
         *
         * - [CarSessionSettings.immersiveVideo] 为 true 且 [CarSessionSettings.mainSafeArea]
         *   未设置（或拉满整屏）时，用它作为声明给 iPhone 的 `safeArea.top`；
         * - 沉浸关闭、且主屏没画框时恒为 0。
         *
         * ⚠️ 主屏读的是 `mainSafeArea`，**不读**仪表那份 `safeArea`。
         * 传 0 表示"量不到状态栏高度"——此时不猜一个数字。
         */
        immersiveSafeAreaTopPx: Int = 0,
    ): AirPlayConfig {
        require(widthPixels > 0 && heightPixels > 0) {
            "AirPlay canvas must be positive, got ${widthPixels}x$heightPixels"
        }
        // ⚠️ 生效帧率优先于设置值，但**仍要过 `sanitizeFps`**：它同时做取值域校验
        //    （夹到 [MIN_FPS, MAX_FPS] 并对齐步长），不能因为"这是解码器算出来的"
        //    就跳过——`ClampResult.fps` 也来自同一条 `fit()` 折半路径，理论上仍可能
        //    落到水印值（如 15），交给消毒统一收敛。
        val fps = AirPlayDisplaySettings.sanitizeFps(effectiveFps ?: settings.fps)
        // 物理尺寸有两条来源，取决于是否开了自定义分辨率：
        //
        // - 默认：按"面板全宽 = widthPhysicalMm"的比例推算。像素变了物理尺寸跟着变，
        //   用户调的是"相对面板的缩放"。
        // - 自定义：由用户给的 dpi 直接换算（mm = px / dpi * 25.4）。
        //   这才是"精确调整"——同一块屏报的物理尺寸越小，iPhone 越认为它密度高，
        //   界面元素就画得越小。
        // 两条分支的返回类型不同（我们自己的 `PhysicalSizeMm` vs 上游的
        // `AirPlayPhysicalSizeMm`），所以在这里就解成一对 Int——
        // 让编译器去推断公共父类只会得到一个 `Any`。
        val (resolvedWidthMm, resolvedHeightMm) = if (settings.customDisplayEnabled) {
            val custom = CarCustomDisplay.physicalSizeMm(
                widthPx = widthPixels,
                heightPx = heightPixels,
                dpi = settings.customDisplayDpi,
            )
            custom.widthMm to custom.heightMm
        } else {
            val derived = AirPlayDisplaySettings.resolvePhysicalSizeMm(
                currentWidthPixels = widthPixels,
                currentHeightPixels = heightPixels,
                maximumWidthPixels = maxOf(display.widthPixels, widthPixels),
                maximumHeightPixels = maxOf(display.heightPixels, heightPixels),
                referenceMillimeters = AirPlayDisplaySettings.sanitizeWidthPhysicalMm(
                    settings.widthPhysicalMm,
                ),
                basis = settings.physicalSizeBasis.toUpstream(),
            )
            derived.widthMm to derived.heightMm
        }
        val baseDisplay = AirPlayDisplayConfig(
            widthPixels = widthPixels,
            heightPixels = heightPixels,
            widthPhysicalMm = resolvedWidthMm,
            heightPhysicalMm = resolvedHeightMm,
            fps = fps,
        )
        // ⚠️ 自定义模式下**跳过缩放**：用户填的像素就是最终值。
        // 再乘一次 displayScaleTenths 会让"填 1920 实际发出 1344"，
        // 那是比滑杆更难理解的行为。
        //
        // ⚠️ 像素本身**不在这里二次夹取**。它已经由调用方（`CarPlaySessionBridge`）
        // 定好并过了解码器上限（`CarVideoSizeLimiter`）。在这里再 `resolve` 一次的话，
        // 万一解码器把画布压到 MIN_EDGE 之下，我们会把它**放大**回 320——
        // 正好抵消掉那次夹取，等于把"解不动的尺寸"又送给 iPhone。
        val scaled = if (settings.customDisplayEnabled) {
            baseDisplay
        } else {
            CarPlayDisplayScale.apply(baseDisplay, settings.displayScaleTenths)
        }
        // 主屏安全区：用户画过的 `mainSafeArea` 优先；没画过（或拉满整屏）才用沉浸避让。
        // 仪表那份 `safeArea` 不进这里，避免"只调了仪表，主屏跟着变小"。
        val mainSafeArea = resolveMainSafeArea(
            settings = settings,
            canvasWidthPixels = scaled.widthPixels,
            canvasHeightPixels = scaled.heightPixels,
            immersiveSafeAreaTopPx = immersiveSafeAreaTopPx,
        ).clampedTo(scaled.widthPixels, scaled.heightPixels)
        return AirPlayConfig(
            deviceName = settings.deviceName,
            deviceId = DEFAULT_DEVICE_ID,
            btMac = DEFAULT_BT_MAC,
            sourceVersion = DEFAULT_SOURCE_VERSION,
            main = scaled.copy(
                safeArea = AirPlayInsets(
                    top = mainSafeArea.top,
                    bottom = mainSafeArea.bottom,
                    left = mainSafeArea.left,
                    right = mainSafeArea.right,
                ),
                safeAreaDrawOutside = settings.safeAreaDrawOutside,
            ),
            rightHandDrive = settings.drivingSide == CarDrivingSide.RIGHT,
            hevc = settings.hevc,
            microphone = settings.microphone,
            experimentalBufferedLpcm = settings.higherQualityMedia,
            manufacturer = settings.manufacturer,
            model = settings.model,
            oemLabel = settings.oemLabel,
            // OEM 图标：iPhone 的 CarPlay 启动器与 AirPlay 菜单用它显示车机品牌。
            // 没有可用图标时留空，上游会回落到内置占位图，行为安全。
            icons = listOfNotNull(icon),
            // 仪表副屏（2026-10-07，吉利 HDMI2 移植）。
            // null ⇒ 不声明第二块屏，与合并前完全一致。
            cluster = if (settings.immersiveNavigationEnabled) AirPlayDisplayConfig(
                widthPixels = 1920,
                heightPixels = 1080,
                fps = 60,
                initialUrl = CarPlayClusterDisplay.contentUrl(
                    showNavigationCard = settings.immersiveNavigationCardEnabled,
                    alignment = settings.immersiveNavigationAlignment,
                    mapLayoutTrial = settings.immersiveNavigationMapLayout,
                ),
                safeArea = settings.immersiveNavigationSafeArea.takeUnless { it.isEmpty }?.toInsets(1920, 1080)?.let {
                    AirPlayInsets(top = it.top, bottom = it.bottom, left = it.left, right = it.right)
                },
                safeAreaDrawOutside = settings.immersiveNavigationDrawOutside,
            ) else cluster?.toAirPlayDisplay(
                // 2026-10-11：副屏内容不再写死地图 —— 按用户设置在「导航卡片 / 仅地图布局 /
                // 纯地图」之间选 URL（原版 `GeelyClusterContent.url()` 的等价实现）。
                CarPlayClusterDisplay.contentUrl(
                    showNavigationCard = settings.clusterNavigationCard,
                    alignment = settings.clusterAlignment,
                    mapLayoutTrial = settings.clusterMapLayoutTrial,
                ),
            ),
        )
    }

    /**
     * 主屏要报给 iPhone 的四边。
     *
     * 画过 [CarSessionSettings.mainSafeArea] 且没有盖满整块画布 ⇒ 用那块矩形。
     * 否则回落沉浸避让：开着就报实测状态栏高度，关着就是 0。
     * 拉满整屏算没画过，避免一个"整屏框"把 72px 顶部避让顶掉。
     */
    private fun resolveMainSafeArea(
        settings: CarSessionSettings,
        canvasWidthPixels: Int,
        canvasHeightPixels: Int,
        immersiveSafeAreaTopPx: Int,
    ): CarClusterSafeAreaInsets {
        val coverStatusBar = !settings.hideTopBar && !settings.fullscreen
        val immersive = CarClusterSafeAreaInsets(
            top = if (coverStatusBar && canvasHeightPixels > 0) {
                immersiveSafeAreaTopPx.coerceIn(0, canvasHeightPixels - 1)
            } else {
                0
            },
        )
        val drawn = settings.mainSafeArea
        if (drawn.isEmpty || canvasWidthPixels <= 0 || canvasHeightPixels <= 0) {
            return immersive
        }
        val insets = drawn.toInsets(canvasWidthPixels, canvasHeightPixels)
        val coversFullCanvas = insets.top == 0 &&
            insets.bottom == 0 &&
            insets.left == 0 &&
            insets.right == 0
        return if (coversFullCanvas) immersive else insets
    }

    /**
     * 副屏规格 → 上游 `AirPlayDisplayConfig`。
     *
     * 物理尺寸换算口径与主屏**刻意不同**：主屏走 `resolvePhysicalSizeMm`
     * （按"面板全宽 = widthPhysicalMm"的比例推算，用户调的是相对缩放）；
     * 副屏是车机固有的一块屏，物理尺寸应当**如实报**（面板比例），
     * 不该受用户的"主屏物理尺寸"设置影响——否则用户调主屏大小会连带
     * 改变仪表的界面密度，那是没有道理的行为。
     *
     * 缺省时按面板宽 [CLUSTER_DEFAULT_WIDTH_PHYSICAL_MM]（= 主屏出厂值 350mm）等比推算，
     * 与主屏默认量级一致，避免仪表界面密度与主屏差得离谱。
     *
     * ## 🔴 PATCH-0095 修的是这一行（2026-10-11）
     *
     * 上面那句"350mm"从 2026-10-07 就写在注释里，**代码写的却是
     * `AirPlayDisplaySettings.DEFAULT_WIDTH_PHYSICAL_MM`** —— 那是**上游**的
     * 出厂值 **200**（见 `shared/.../AirPlayDisplaySettings.kt`），而本产品的
     * 出厂值是 `CarSessionSettings.DEFAULT_WIDTH_PHYSICAL_MM` = **350**
     * （2026-10-06 专门从 200 提到 350，理由：车机横屏实际宽度 350mm 更接近真值）。
     *
     * 后果是**副屏与主屏报的物理尺寸差 1.75 倍**，而 iPhone 是按
     * 「像素数 + 物理尺寸」推算界面密度的 ⇒ 仪表上的 CarPlay 内容被画得
     * 明显偏小。实车诊断包里这一格一直是 `widthPhysical=200`
     * （`widthPhysicalMm: 350` 那项设置**只作用于主屏**，从没到过副屏）。
     *
     * ⚠️ 改的是**我们这一层**，不动 `:shared`：上游那个 200 兜底只在
     * `AirPlayDisplayConfig.widthPhysicalMm == null` 时才生效，而本函数
     * 保证传出去的一定非空 ⇒ 上游的兜底路径在我们这条链上永远走不到。
     */
    private fun CarClusterSpec.toAirPlayDisplay(initialUrl: String): AirPlayDisplayConfig {
        val widthMm = widthPhysicalMm ?: CLUSTER_DEFAULT_WIDTH_PHYSICAL_MM
        val heightMm = heightPhysicalMm
            ?: Math.round(widthMm * heightPixels.toDouble() / widthPixels).toInt()
        return AirPlayDisplayConfig(
            widthPixels = widthPixels,
            heightPixels = heightPixels,
            widthPhysicalMm = AirPlayDisplaySettings.sanitizeReportedPhysicalMm(widthMm),
            heightPhysicalMm = AirPlayDisplaySettings.sanitizeReportedPhysicalMm(heightMm),
            fps = AirPlayDisplaySettings.sanitizeFps(fps),
            // ------------------------------------------------------------------
            // 仪表内容：**必须给 `initialURL`，否则 iPhone 只推黑帧**（2026-10-11）
            // ------------------------------------------------------------------
            // 这一行是「车机仪表盘屏幕是对的、但没有投射出内容」的直接原因：
            // `/info` 的副屏声明里没有 `initialURL` 时，iPhone 照样把 111 流建起来、
            // 照样推帧 —— 但推的是**黑帧**（见 `CarPlayClusterDisplay` 的类注释：
            // 「不给 `initialUrl` 时，iPhone 会推黑帧（屏是黑的，但流是活的）」）。
            //
            // 真车日志（2026-10-11 08:52）逐字对得上：窗口建了、Surface 交给 ALT(111)、
            // `SETUP stream type=111` 成功、`帧更新 #1..#40` 之后**彻底安静**，
            // 集群解码器随之停摆（主屏解码器仍 40fps）—— 屏亮着、流活着、什么都没有。
            //
            // 内容 URL 由调用方按用户设置算好传进来（默认仍是地图：与上游
            // `CarPlayClusterDisplay.Content.MAP` 与 `/info` 的 `altScreenURLs` 首项一致）。
            initialUrl = initialUrl,
            // 安全区：把用户在「仪表地图与安全区域」里设的核心区报给 iPhone。
            // 不报（= null）时 iPhone 认为整块面板都是可用区，会把导航铺满，
            // 画到下方车机自己的仪表区——就是"画面绘制到安全区外"。
            safeArea = safeArea?.let {
                // ⚠️ 2026-10-11（PATCH-0079）：**先夹进面板，再报给 iPhone**。
                //    上游 `AirPlayInfoPlist` 是按 `height = 面板高 - top - bottom` 现算的 ⇒
                //    只要上下两边之和超过面板高，报出去的 `safeArea.heightPixels` 就是**负数**，
                //    而 iPhone 拿到自相矛盾的 safeArea 会**直接拆会话**（真车 CX11 实测：
                //    `safeArea={height -1560, originY 1920}` on a 1080-tall panel，
                //    反解 = `top=1920 / bottom=720` —— 正是"把矩形数当内边距用"的后果）。
                //    原版 `GeelyClusterSafeArea` 靠"矩形 → normalized → toInsets"保证四边自洽；
                //    这里补上同一道保证（数据源将来仍应改成矩形模型）。
                val safe = it.clampedTo(widthPixels, heightPixels)
                AirPlayInsets(top = safe.top, bottom = safe.bottom, left = safe.left, right = safe.right)
            },
            // 与主屏**共用**「显示与视频 → 画面绘制到安全区外」开关；null 时回落到上游默认 true。
            safeAreaDrawOutside = safeAreaDrawOutside,
        )
    }

    // ------------------------------------------------------------ 枚举映射
    // 集中在这里，上游改枚举只改这一小段。

    private fun CarMfiTarget.toUpstream(): MfiTarget = when (this) {
        CarMfiTarget.USB_CH341 -> MfiTarget.USB_CH341
        CarMfiTarget.I2C -> MfiTarget.I2C
        CarMfiTarget.REMOTE -> MfiTarget.REMOTE
        // 借道：上游没有"本机软件认证"这个后端，而 REMOTE 的实现
        // `RemoteMfiAuthenticationClient` 只是一个普通 HTTP 客户端。
        // 把它指向我们自己的 127.0.0.1 环回服务，就得到了"软件 MFi"，
        // 且上游一行不用改。
        CarMfiTarget.LOCAL_OFFLINE -> MfiTarget.REMOTE
    }

    private fun CarTransportMode.toUpstream(): CarPlayTransport = when (this) {
        CarTransportMode.WIRED -> CarPlayTransport.WIRED
        CarTransportMode.WIRELESS -> CarPlayTransport.WIRELESS
    }

    private fun CarHotspotMode.toUpstream(): WirelessHotspotMode = when (this) {
        CarHotspotMode.MANUAL -> WirelessHotspotMode.MANUAL
        // ADB 独立热点借道上游的 MANUAL —— 它的语义本就是
        // **"attach 到一张已经在跑的热点"**（`ManualHotspotManager` 只读配置、
        // 从不调用 `startSoftAp`）。热点由 `CarAdbHotspotManager` 在
        // `CarPlaySessionBridge.buildSession()` 里先起好，上游负责把网卡上那个
        // **运行时选出来的**地址（见 `CarHotspotNetwork.hostAddress`）通告给 iPhone。
        // ⇒ 零上游改动，PATCH-0002（hotspotFactory）仍然不需要。
        CarHotspotMode.ADB_HOSTAPD -> WirelessHotspotMode.MANUAL
        // 上游没有"自动"，落到这里说明调用方**没走 CarHotspotResolver**。
        // ⚠️ **2026-10-03 起不再回落到上游的 LOCAL_ONLY_HOTSPOT**（那个形态已在
        // 我们的产品线里整体移除，见 CarHotspotMode 头注释）。保守地按"自建"处理：
        // 走 ADB_HOSTAPD 那条（借道上游 MANUAL），绝不要在这里猜"复用车机热点"——
        // 猜错的代价是 attach 到一张不存在的 AP，然后一路等到超时。
        CarHotspotMode.AUTO -> WirelessHotspotMode.MANUAL
        // TCAM 复用：与 MANUAL 同为"attach 到已在跑的网，从不 startSoftAp"。
        // 地址由 `TcamHotspotManager` 识别后经 `manualHotspotHostAddress` /
        // `manualHotspotInterfaceName` 透传，上游 `ManualHotspotManager` 拿它们做
        // "精确 iface+IP" 校验（findInterfaceCarryingVerifiedAddress）。
        CarHotspotMode.TCAM_HOTSPOT -> WirelessHotspotMode.MANUAL
        // 车机热点（2026-10-25，三版定稿）：映射到**独立上游值** `GEELY_ROUTED`，
        // 由 `GeelyRoutedHotspotManager`（`:shared`）自己扫 `eth*` 198.18 并返回
        // `bssid=null / channel=0 / bandLabel="Car hotspot" / backend=GEELY_ROUTED_HOTSPOT`
        // —— 与参考包逐字段一致。
        //
        // 历史：v1 曾借道 MANUAL（回填 hostAddressOverride 精确 attach），
        // 但那样 bssid 会变成 eth 网卡 MAC、且会被 `SoftApConfiguration` 校验拦住，
        // 与参考包不一致。现在彻底分开，两条链路互不干扰。
        CarHotspotMode.GEELY_ROUTED -> WirelessHotspotMode.GEELY_ROUTED
        // PATCH-0010：同一 Wi-Fi。上游为此新增了 SAME_LAN 枚举值
        // （见 `WirelessHotspotMode.SAME_LAN`），对应的分支**不建任何热点**，
        // 只用 `sameLanHostAddress` 作为通告地址。
        CarHotspotMode.SAME_LAN -> WirelessHotspotMode.SAME_LAN
    }

    private fun CarHotspotBand.toUpstream(): ManualHotspotBand = when (this) {
        CarHotspotBand.AUTO -> ManualHotspotBand.AUTO
        CarHotspotBand.GHZ_2_4 -> ManualHotspotBand.GHZ_2_4
        CarHotspotBand.GHZ_5 -> ManualHotspotBand.GHZ_5
    }

    private fun CarHotspotSecurity.toUpstream(): ManualHotspotSecurity = when (this) {
        CarHotspotSecurity.OPEN -> ManualHotspotSecurity.OPEN
        CarHotspotSecurity.WPA2 -> ManualHotspotSecurity.WPA2
        CarHotspotSecurity.WPA3_TRANSITION -> ManualHotspotSecurity.WPA3_TRANSITION
        CarHotspotSecurity.WPA3 -> ManualHotspotSecurity.WPA3
    }

    private fun CarPhysicalSizeBasis.toUpstream(): AirPlayPhysicalSizeBasis = when (this) {
        CarPhysicalSizeBasis.WIDEST_WIDTH -> AirPlayPhysicalSizeBasis.WIDTH
        CarPhysicalSizeBasis.LONGEST_HEIGHT -> AirPlayPhysicalSizeBasis.HEIGHT
    }

    // --------------------------------------------------------------- 常量
    // 与 upstream CarPlayHostActivity 的取值保持一致，改动前请确认不会破坏协议协商。

    const val DEFAULT_DEVICE_ID = "02:00:00:00:00:02"
    const val DEFAULT_BT_MAC = "02:00:00:00:00:01"
    const val DEFAULT_SOURCE_VERSION = "950.7.1"
    const val DEFAULT_FIRMWARE_VERSION = "1.0.0"
    const val DEFAULT_HARDWARE_VERSION = "1.0"
    const val CARPLAY_USB_INTERFACE_NUMBER = 3

    /** CH341 USB\VID_1A86&PID_5512，与 upstream `usb_device_filter.xml` 同源。 */
    const val CH341_VENDOR_ID = 0x1a86
    const val CH341_PRODUCT_ID = 0x5512

    /**
     * 副屏在**面板没报物理尺寸**时的兜底宽度（mm）。PATCH-0095，2026-10-11。
     *
     * 刻意**指回** [CarSessionSettings.DEFAULT_WIDTH_PHYSICAL_MM]（= 350），
     * 而不是上游 `AirPlayDisplaySettings.DEFAULT_WIDTH_PHYSICAL_MM`（= 200）：
     * 后者是上游从未按车机实际屏宽校正过的老出厂值，而本产品在 2026-10-06
     * 专门把它提到 350（理由见那个常量的注释）。
     *
     * ⚠️ 写成"引用"而不是"再写一个 350"：两处一旦分叉，副屏与主屏报给 iPhone 的
     * 物理尺寸又会不一致 —— 而**界面密度就是按这个算的**，那正是本次修的问题。
     */
    const val CLUSTER_DEFAULT_WIDTH_PHYSICAL_MM = CarSessionSettings.DEFAULT_WIDTH_PHYSICAL_MM
}
