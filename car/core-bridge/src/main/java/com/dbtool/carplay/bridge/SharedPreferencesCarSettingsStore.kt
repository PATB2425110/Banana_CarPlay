package com.dbtool.carplay.bridge

import android.content.Context
import com.dbtool.carplay.api.settings.forAppleCarPlay
import com.dbtool.carplay.api.display.CarCustomDisplay
import com.dbtool.carplay.api.display.ClusterLayerMode
import com.dbtool.carplay.api.display.HudScaleMode
import com.dbtool.carplay.api.display.sanitizeHudCrop
import com.dbtool.carplay.api.display.theme.ClusterAppearanceMode
import com.dbtool.carplay.api.settings.CarDrivingSide
import com.dbtool.carplay.api.settings.CarHotspotBand
import com.dbtool.carplay.api.settings.CarHotspotMode
import com.dbtool.carplay.api.settings.CarHotspotSecurity
import com.dbtool.carplay.api.settings.CarMfiTarget
import com.dbtool.carplay.api.settings.CarPhysicalSizeBasis
import com.dbtool.carplay.api.settings.CarSessionSettings
import com.dbtool.carplay.api.settings.CarSettingsSafeArea
import com.dbtool.carplay.api.settings.CarSettingsStore
import com.dbtool.carplay.api.settings.CarTransportMode
import com.dbtool.carplay.api.settings.ClusterLayerModeSetting
import com.dbtool.carplay.api.power.CAR_POWER_PAUSE_DEFAULT_ENABLED
import com.dbtool.carplay.api.power.CAR_POWER_PAUSE_KEY

/**
 * [CarSettingsStore] 的 SharedPreferences 实现。
 *
 * 使用**自己的** prefs 文件（`car_ui_settings`），不复用 upstream 的
 * `xcertplay_airplay`。原因：
 * - 二开产品 App 的 `applicationId` 与上游不同（`io.dushu.car`），
 *   两者数据目录天然隔离，**不存在与上游安装互相兼容的需求**；
 * - 因此没必要为了"和上游 key 对齐"而引入对 `:common` 的依赖
 *   （`:common` 会把上游 3427 行的宿主 Activity 一起带进我们的应用清单）；
 * - 自己的文件也让键名可以随 UI 模型自由演进。
 *
 * 读取策略：任何字段缺失或取值非法都回落到 [CarSessionSettings] 的默认值，
 * **绝不抛异常**——设置文件损坏不应导致应用起不来。
 */
class SharedPreferencesCarSettingsStore(
    context: Context,
) : CarSettingsStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        // 图层模式改动必须即时生效；设置页不等 500ms 自动保存防抖，直接从这里落盘。
        ClusterLayerModeSetting.bindPersistence { mode ->
            prefs.edit().putString(KEY_CLUSTER_LAYER_MODE, mode.name).apply()
        }
    }

    override fun load(): CarSessionSettings {
        if (!prefs.getBoolean("apple_carplay_product_v1", false)) {
            prefs.edit().putString(KEY_UI_APPEARANCE_MODE, "SYSTEM")
                .putBoolean("apple_carplay_product_v1", true).apply()
        }
        val d = CarSessionSettings.default()
        // 首次升级到桥接版先归还原厂仪表；之后尊重用户手动开启投屏的选择。
        if (!prefs.getBoolean("bridge_projection_opt_in_v1", false)) {
            prefs.edit()
                .putBoolean(KEY_PROJECTION_ENABLED, false)
                .putBoolean("bridge_projection_opt_in_v1", true)
                .commit()
        }
        migrateLegacyBranding(d)
        migrateLegacyHotspotDefaults(d)
        migrateHotspotPassphraseToEightChars(d)
        migrateAutoHotspotModeToSelfBuilt(d)
        migrateRemovedP2pHotspotMode(d)
        migrateManualHotspotModeToTcam(d)
        migrateHideTopBarDefault(d)
        migrateImmersiveVideoDefaultOff()
        migrateDockBarOff()
        migrateDisplayScaleTo1x()
        migrateTransportToWireless(d)
        migrateWidthPhysicalMmTo350(d)
        migrateBluetoothHandoffToDisableAdapter(d)
        migrateBluetoothHandoffToOff(d)
        migrateDebugTunnelSecretToTwelveChars(d)
        migrateDebugTunnelEnabledDefaultOn()
        // 缺省、空串与旧版本陌生命令都回落 BOTH；解析函数本身不抛异常。
        ClusterLayerModeSetting.restore(prefs.getString(KEY_CLUSTER_LAYER_MODE, null))
        return CarSessionSettings(
            deviceName = prefs.getString(KEY_DEVICE_NAME, d.deviceName).orEmpty().ifBlank { d.deviceName },
            manufacturer = prefs.getString(KEY_MANUFACTURER, d.manufacturer).orEmpty()
                .ifBlank { d.manufacturer },
            model = prefs.getString(KEY_MODEL, d.model).orEmpty().ifBlank { d.model },
            oemLabel = prefs.getString(KEY_OEM_LABEL, d.oemLabel).orEmpty(),
            drivingSide = enumOf(prefs.getString(KEY_DRIVING_SIDE, null), d.drivingSide),

            transport = enumOf(prefs.getString(KEY_TRANSPORT, null), d.transport),
            mfiTarget = enumOf(prefs.getString(KEY_MFI_TARGET, null), d.mfiTarget),
            linuxI2cPath = prefs.getString(KEY_I2C_PATH, d.linuxI2cPath).orEmpty()
                .ifBlank { d.linuxI2cPath },
            remoteMfiServer = prefs.getString(KEY_REMOTE_SERVER, d.remoteMfiServer).orEmpty(),
            remoteMfiToken = prefs.getString(KEY_REMOTE_TOKEN, d.remoteMfiToken).orEmpty(),
            // 2026-10-16：`MANUAL` 已从界面移除、由 `TCAM_HOTSPOT` 取代。迁移层
            // [migrateManualHotspotModeToTcam] 已把存量 `"MANUAL"` 改写掉；这里再兜底一次，
            // 覆盖"迁移标记已置位、值却仍是 MANUAL"（降级→升级）的边缘路径——
            // 解析/映射层把 `MANUAL` 等价到 `TCAM_HOTSPOT`，绝不丢成别的模式。
            hotspotMode = enumOf(prefs.getString(KEY_HOTSPOT_MODE, null), d.hotspotMode)
                .let { mode ->
                    if (mode == CarHotspotMode.MANUAL) CarHotspotMode.TCAM_HOTSPOT else mode
                },
            manualHotspotSsid = prefs.getString(KEY_HOTSPOT_SSID, d.manualHotspotSsid).orEmpty(),
            manualHotspotPassphrase = prefs.getString(KEY_HOTSPOT_PASS, d.manualHotspotPassphrase)
                .orEmpty(),
            manualHotspotBand = enumOf(prefs.getString(KEY_HOTSPOT_BAND, null), d.manualHotspotBand),
            manualHotspotChannel = prefs.getInt(KEY_HOTSPOT_CHANNEL, d.manualHotspotChannel),
            // PATCH-0087：复用车机热点时**由用户填**的车机热点地址（空 = 未填 ⇒ 连接前拦下）。
            manualHotspotHostAddress =
                prefs.getString(KEY_HOTSPOT_HOST_ADDRESS, d.manualHotspotHostAddress).orEmpty(),
            manualHotspotSecurity = enumOf(
                prefs.getString(KEY_HOTSPOT_SECURITY, null),
                d.manualHotspotSecurity,
            ),
            adbHotspotInterface = prefs.getString(KEY_ADB_HOTSPOT_IFACE, d.adbHotspotInterface)
                .orEmpty(),
            sameLanHostAddress = prefs.getString(KEY_SAME_LAN_HOST, d.sameLanHostAddress).orEmpty(),
            sameLanWifiSsid = prefs.getString(KEY_SAME_LAN_SSID, d.sameLanWifiSsid).orEmpty(),
            sameLanWifiPassphrase = prefs.getString(KEY_SAME_LAN_PASS, d.sameLanWifiPassphrase)
                .orEmpty(),
            // ⚠️ 2026-10-11（PATCH-0095）：出厂值从 `DISCONNECT_LINK` 改成
            // `DISABLE_ADAPTER`（前者在本车实测无效）。**老安装必须靠迁移**：
            // 只要保存过一次设置，`bt_handoff_policy` 就被写成了旧出厂值。
            // 见 [migrateBluetoothHandoffToDisableAdapter]。
            bluetoothHandoffPolicy = enumOf(
                prefs.getString(KEY_BT_HANDOFF_POLICY, null),
                d.bluetoothHandoffPolicy,
            ),

            displayScaleTenths = prefs.getInt(KEY_SCALE_TENTHS, d.displayScaleTenths)
                .coerceIn(CarSessionSettings.MIN_SCALE_TENTHS, CarSessionSettings.MAX_SCALE_TENTHS),
            fps = prefs.getInt(KEY_FPS, d.fps)
                .coerceIn(CarSessionSettings.MIN_FPS, CarSessionSettings.MAX_FPS),
            widthPhysicalMm = prefs.getInt(KEY_WIDTH_MM, d.widthPhysicalMm)
                .coerceIn(
                    CarSessionSettings.MIN_WIDTH_PHYSICAL_MM,
                    CarSessionSettings.MAX_WIDTH_PHYSICAL_MM,
                ),
            physicalSizeBasis = enumOf(prefs.getString(KEY_SIZE_BASIS, null), d.physicalSizeBasis),
            // 自定义分辨率：全部是新 key ⇒ 老安装天然取默认值，不需要迁移。
            // 越界值在读取时夹回区间，免得旧版本写进去的脏值一路送到 iPhone。
            customDisplayEnabled = prefs.getBoolean(KEY_CUSTOM_DISPLAY, d.customDisplayEnabled),
            customDisplayWidthPx = prefs.getInt(KEY_CUSTOM_WIDTH, d.customDisplayWidthPx)
                .coerceIn(CarCustomDisplay.MIN_EDGE, CarCustomDisplay.MAX_EDGE),
            customDisplayHeightPx = prefs.getInt(KEY_CUSTOM_HEIGHT, d.customDisplayHeightPx)
                .coerceIn(CarCustomDisplay.MIN_EDGE, CarCustomDisplay.MAX_EDGE),
            customDisplayDpi = prefs.getInt(KEY_CUSTOM_DPI, d.customDisplayDpi)
                .coerceIn(CarCustomDisplay.MIN_DPI, CarCustomDisplay.MAX_DPI),
            hevc = prefs.getBoolean(KEY_HEVC, d.hevc),
            hevcSoftwareDecoder = prefs.getBoolean(KEY_HEVC_SOFT, d.hevcSoftwareDecoder),
            safeArea = loadSafeArea(d.safeArea),
            mainSafeArea = loadMainSafeArea(d.mainSafeArea),
            immersiveNavigationEnabled = prefs.getBoolean("immersive_navigation_enabled", false),
            immersiveNavigationCardEnabled = prefs.getBoolean("immersive_navigation_card", false),
            immersiveGlassCardsEnabled = prefs.getBoolean("immersive_glass_cards", false),
            immersiveGlassColor = prefs.getInt("immersive_glass_color", -1).takeIf { it in -1..0xffffff } ?: -1,
            immersiveGlassTransparency = prefs.getInt("immersive_glass_transparency", 15).coerceIn(0, 100),
            immersiveNavigationAlignment = prefs.getInt("immersive_navigation_alignment", 0),
            immersiveNavigationMapLayout = prefs.getBoolean("immersive_navigation_map_layout", false),
            immersiveNavigationDrawOutside = prefs.getBoolean("immersive_navigation_draw_outside", true),
            immersiveNavigationSafeArea = CarSettingsSafeArea(
                prefs.getInt("immersive_navigation_safe_x", d.immersiveNavigationSafeArea.x),
                prefs.getInt("immersive_navigation_safe_y", d.immersiveNavigationSafeArea.y),
                prefs.getInt("immersive_navigation_safe_width", d.immersiveNavigationSafeArea.width),
                prefs.getInt("immersive_navigation_safe_height", d.immersiveNavigationSafeArea.height),
            ).takeUnless { it.isEmpty } ?: d.immersiveNavigationSafeArea,
            safeAreaDrawOutside = prefs.getBoolean(KEY_SAFE_OUTSIDE, d.safeAreaDrawOutside),
            clusterSafeAreaDrawOutside = prefs.getBoolean(KEY_CLUSTER_SAFE_OUTSIDE, d.clusterSafeAreaDrawOutside),
            // 沉浸默认关。已存过 true 的安装由 migrateImmersiveVideoDefaultOff 改回。
            immersiveVideo = prefs.getBoolean(KEY_IMMERSIVE_VIDEO, d.immersiveVideo),
            hideTopBar = prefs.getBoolean(KEY_HIDE_TOP, d.hideTopBar),
            hideBottomBar = prefs.getBoolean(KEY_HIDE_BOTTOM, d.hideBottomBar),
            dockBar = prefs.getBoolean(KEY_DOCK_BAR, d.dockBar),
            // 用 `\n` 拼接存字符串：包名里不可能有换行，比 putStringSet 强 ——
            // StringSet 是无序的，而这里要的恰恰是"顺序"。
            dockAppOrder = prefs.getString(KEY_DOCK_APP_ORDER, null)
                .orEmpty()
                .split("\n")
                .filter { it.isNotBlank() },
            // ⚠️ `fullscreen` 曾经**只读不写**（save() 里漏了这一项），
            // 于是「全屏」开关关掉应用就丢，用户以为设置没生效。
            fullscreen = prefs.getBoolean(KEY_FULLSCREEN, d.fullscreen),
            localNavigationCardEnabled = prefs.getBoolean(
                KEY_LOCAL_NAVIGATION_CARD_ENABLED,
                d.localNavigationCardEnabled,
            ),
            projectionEnabled = prefs.getBoolean(KEY_PROJECTION_ENABLED, d.projectionEnabled),
            // PATCH-0119（2026-10-14）：导航启动自动投屏。新键 ⇒ 老安装天然取默认值 true
            // （= 车主本次口径「导航启动就自动投」），**不需要迁移**。
            // 与 projectionEnabled 分开保存：它控制能力门，本项只控制是否跟随导航自动投屏。
            clusterAutoShowOnRoute = prefs.getBoolean(
                KEY_CLUSTER_AUTO_SHOW_ON_ROUTE,
                d.clusterAutoShowOnRoute,
            ),

            advancedAudioChannelMapping = prefs.getBoolean(KEY_ADVANCED_AUDIO, d.advancedAudioChannelMapping),
            microphone = prefs.getBoolean(KEY_MICROPHONE, d.microphone),
            mediaAudioChannel = CarSessionSettings.sanitizeAudioChannel(
                prefs.getInt(KEY_MEDIA_AUDIO_CHANNEL, d.mediaAudioChannel),
            ),
            navigationAudioChannel = CarSessionSettings.sanitizeNavigationAudioChannel(
                prefs.getInt(KEY_NAVIGATION_AUDIO_CHANNEL, d.navigationAudioChannel),
            ),
            navigationAudioFocusEnabled = prefs.getBoolean(KEY_NAVIGATION_AUDIO_FOCUS_ENABLED, d.navigationAudioFocusEnabled),
            higherQualityMedia = prefs.getBoolean("higher_quality_media", d.higherQualityMedia),
            mediaAudioBufferMillis = CarSessionSettings.sanitizeAudioBufferMillis(
                prefs.getInt(KEY_MEDIA_AUDIO_BUFFER_MILLIS, d.mediaAudioBufferMillis),
            ),
            forwardSteeringWheelToCarPlay = prefs.getBoolean(
                KEY_FORWARD_STEERING_WHEEL,
                d.forwardSteeringWheelToCarPlay,
            ),
            reportMediaCardToCar = prefs.getBoolean(KEY_REPORT_MEDIA_CARD, d.reportMediaCardToCar),

            locationReporting = prefs.getBoolean(KEY_LOCATION, d.locationReporting),
            autoStartOnBoot = prefs.getBoolean(KEY_AUTO_START, d.autoStartOnBoot),
            debugLogs = prefs.getBoolean(KEY_DEBUG_LOGS, d.debugLogs),
            fpsOverlayEnabled = prefs.getBoolean(KEY_FPS_OVERLAY, d.fpsOverlayEnabled),
            // 2026-10-08：补上遗漏的持久化（原实现只在内存里，重启即丢）。
            startupPerfCompile = prefs.getBoolean(KEY_STARTUP_PERF_COMPILE, d.startupPerfCompile),
            // 2026-10-08：仪表副屏由用户选，id 持久化（-1 = 未选）。
            clusterDisplayId = prefs.getInt(KEY_CLUSTER_DISPLAY_ID, d.clusterDisplayId),
            // 2026-10-11：副屏倒装 ⇒ 画面转 180°。新 key ⇒ 老安装天然取默认 false
            // （不倒装的车机维持原样），不需要迁移。
            clusterFlip180 = prefs.getBoolean(KEY_CLUSTER_FLIP_180, d.clusterFlip180),
            // 2026-10-18：全屏地图模式守护（灰度）。新 key ⇒ 老安装默认 false，
            // 保持「直接抢屏 / 直接还屏」的老路径。
            clusterModeGuardEnabled = prefs.getBoolean(
                KEY_CLUSTER_MODE_GUARD_ENABLED,
                d.clusterModeGuardEnabled,
            ),
            // 2026-10-11：仪表内容三项（从原版 GeelyClusterContent 同步）。都是新 key
            // ⇒ 老安装天然取默认（导航卡片关 / 仅地图关 / 对齐 0 = 左侧），行为不变。
            clusterNavigationCard = prefs.getBoolean(
                KEY_CLUSTER_NAV_CARD,
                d.clusterNavigationCard,
            ),
            clusterMapLayoutTrial = prefs.getBoolean(
                KEY_CLUSTER_MAP_LAYOUT_TRIAL,
                d.clusterMapLayoutTrial,
            ),
            clusterAlignment = prefs.getInt(KEY_CLUSTER_ALIGNMENT, d.clusterAlignment),
            // 2026-10-11：手动巡航。新 key ⇒ 老安装取默认 false（不改变既有行为）。
            // 2026-10-13：HUD 导航推送开关（默认 true）。新 key ⇒ 老安装天然取默认值
            // （= 保持现行为：HUD 照推），**不需要迁移**。
            hudGuidanceEnabled = prefs.getBoolean(KEY_HUD_GUIDANCE_ENABLED, d.hudGuidanceEnabled),
            // 2026-10-22：ECarX HUD 使能通道（CB_VF_HUD_ActvReq）。新键 ⇒ 老安装天然取
            // 默认 false（未实车确认前不动车控 HUD 状态）。
            hudPushEnabled = prefs.getBoolean(KEY_HUD_PUSH_ENABLED, d.hudPushEnabled),
            // 2026-10-22：HUD 地图投屏。新键 ⇒ 老安装天然取默认 false（不无感多开一路解码）。
            hudProjectionEnabled = prefs.getBoolean(
                KEY_HUD_PROJECTION_ENABLED,
                d.hudProjectionEnabled,
            ),
            // 裁剪四边逐项存：比压成一个字符串更耐手工排查，坏值由 sanitizeHudCrop 兜底回合法区间。
            hudCrop = sanitizeHudCrop(
                leftFraction = prefs.getFloat(KEY_HUD_CROP_LEFT, d.hudCrop.leftFraction),
                topFraction = prefs.getFloat(KEY_HUD_CROP_TOP, d.hudCrop.topFraction),
                rightFraction = prefs.getFloat(KEY_HUD_CROP_RIGHT, d.hudCrop.rightFraction),
                bottomFraction = prefs.getFloat(KEY_HUD_CROP_BOTTOM, d.hudCrop.bottomFraction),
            ),
            hudScaleMode = parseHudScaleMode(
                prefs.getString(KEY_HUD_SCALE_MODE, null),
            ) ?: d.hudScaleMode,
            // P1 仪表主题：只存 id 与开关（主题全量在 assets，随 APK 升级）。
            uiAppearanceMode = ClusterAppearanceMode.parse(
                prefs.getString(KEY_UI_APPEARANCE_MODE, null),
            ) ?: d.uiAppearanceMode,
            clusterAppearanceMode = ClusterAppearanceMode.parse(
                prefs.getString(KEY_CLUSTER_APPEARANCE_MODE, null),
            ) ?: d.clusterAppearanceMode,
            // 2026-10-17（PATCH-0130）：调试隧道 5 个字段此前**一个都没落盘**
            // ⇒ 用户改完地址/口令，重启即回默认；开关拨上去也只是内存态，
            // 刷新模型后读回 false。全仓唯一的隧道入口因此长年「连不上」。
            debugTunnelEnabled = prefs.getBoolean(KEY_DEBUG_TUNNEL_ENABLED, d.debugTunnelEnabled),
            debugTunnelHost = prefs.getString(KEY_DEBUG_TUNNEL_HOST, d.debugTunnelHost)
                .orEmpty().ifBlank { d.debugTunnelHost },
            debugTunnelPort = prefs.getInt(KEY_DEBUG_TUNNEL_PORT, d.debugTunnelPort),
            debugTunnelLocalAdbPort = prefs.getInt(
                KEY_DEBUG_TUNNEL_LOCAL_ADB_PORT,
                d.debugTunnelLocalAdbPort,
            ),
            debugTunnelSecret = prefs.getString(KEY_DEBUG_TUNNEL_SECRET, d.debugTunnelSecret)
                .orEmpty().ifBlank { d.debugTunnelSecret },
            // 2026-10-17（VIN 身份 P1）：新键 ⇒ 老安装天然取空串，不需要迁移。
            debugTunnelToken = prefs.getString(KEY_DEBUG_TUNNEL_TOKEN, d.debugTunnelToken)
                .orEmpty(),
        ).forAppleCarPlay()
    }

    override fun save(settings: CarSessionSettings) {
        val constrained = settings.forAppleCarPlay()
        if (constrained != settings) { save(constrained); return }
        prefs.edit()
            .putString(KEY_DEVICE_NAME, settings.deviceName)
            .putString(KEY_MANUFACTURER, settings.manufacturer)
            .putString(KEY_MODEL, settings.model)
            .putString(KEY_OEM_LABEL, settings.oemLabel)
            .putString(KEY_DRIVING_SIDE, settings.drivingSide.name)

            .putString(KEY_TRANSPORT, settings.transport.name)
            .putString(KEY_MFI_TARGET, settings.mfiTarget.name)
            .putString(KEY_I2C_PATH, settings.linuxI2cPath)
            .putString(KEY_REMOTE_SERVER, settings.remoteMfiServer)
            .putString(KEY_REMOTE_TOKEN, settings.remoteMfiToken)
            .putString(KEY_HOTSPOT_MODE, settings.hotspotMode.name)
            .putString(KEY_HOTSPOT_SSID, settings.manualHotspotSsid)
            .putString(KEY_HOTSPOT_PASS, settings.manualHotspotPassphrase)
            .putString(KEY_HOTSPOT_BAND, settings.manualHotspotBand.name)
            .putInt(KEY_HOTSPOT_CHANNEL, settings.manualHotspotChannel)
            .putString(KEY_HOTSPOT_HOST_ADDRESS, settings.manualHotspotHostAddress)
            .putString(KEY_HOTSPOT_SECURITY, settings.manualHotspotSecurity.name)
            .putString(KEY_ADB_HOTSPOT_IFACE, settings.adbHotspotInterface)
            .putString(KEY_SAME_LAN_HOST, settings.sameLanHostAddress)
            .putString(KEY_SAME_LAN_SSID, settings.sameLanWifiSsid)
            .putString(KEY_SAME_LAN_PASS, settings.sameLanWifiPassphrase)
            .putString(KEY_BT_HANDOFF_POLICY, settings.bluetoothHandoffPolicy.name)

            .putInt(KEY_SCALE_TENTHS, settings.displayScaleTenths)
            .putInt(KEY_FPS, settings.fps)
            .putInt(KEY_WIDTH_MM, settings.widthPhysicalMm)
            .putString(KEY_SIZE_BASIS, settings.physicalSizeBasis.name)
            .putBoolean(KEY_CUSTOM_DISPLAY, settings.customDisplayEnabled)
            .putInt(KEY_CUSTOM_WIDTH, settings.customDisplayWidthPx)
            .putInt(KEY_CUSTOM_HEIGHT, settings.customDisplayHeightPx)
            .putInt(KEY_CUSTOM_DPI, settings.customDisplayDpi)
            .putBoolean(KEY_HEVC, settings.hevc)
            .putBoolean(KEY_HEVC_SOFT, settings.hevcSoftwareDecoder)
            // ⚠️ 2026-10-11（PATCH-0080）：写**矩形**（`x/y/w/h`）。老的四个内边距键不再写，
            //    但仍保留读取路径（`loadSafeArea` 里做一次性换算），免得降级回旧版时丢掉用户设的值。
            .putInt(KEY_SAFE_X, settings.safeArea.x)
            .putInt(KEY_SAFE_Y, settings.safeArea.y)
            .putInt(KEY_SAFE_WIDTH, settings.safeArea.width)
            .putInt(KEY_SAFE_HEIGHT, settings.safeArea.height)
            .putInt(KEY_MAIN_SAFE_X, settings.mainSafeArea.x)
            .putBoolean("immersive_navigation_enabled", settings.immersiveNavigationEnabled)
            .putBoolean("immersive_navigation_card", settings.immersiveNavigationCardEnabled)
            .putBoolean("immersive_glass_cards", settings.immersiveGlassCardsEnabled)
            .putInt("immersive_glass_color", settings.immersiveGlassColor.takeIf { it in -1..0xffffff } ?: -1)
            .putInt("immersive_glass_transparency", settings.immersiveGlassTransparency.coerceIn(0, 100))
            .putInt("immersive_navigation_alignment", settings.immersiveNavigationAlignment)
            .putBoolean("immersive_navigation_map_layout", settings.immersiveNavigationMapLayout)
            .putBoolean("immersive_navigation_draw_outside", settings.immersiveNavigationDrawOutside)
            .putInt("immersive_navigation_safe_x", settings.immersiveNavigationSafeArea.x)
            .putInt("immersive_navigation_safe_y", settings.immersiveNavigationSafeArea.y)
            .putInt("immersive_navigation_safe_width", settings.immersiveNavigationSafeArea.width)
            .putInt("immersive_navigation_safe_height", settings.immersiveNavigationSafeArea.height)
            .putInt(KEY_MAIN_SAFE_Y, settings.mainSafeArea.y)
            .putInt(KEY_MAIN_SAFE_WIDTH, settings.mainSafeArea.width)
            .putInt(KEY_MAIN_SAFE_HEIGHT, settings.mainSafeArea.height)
            .putBoolean(KEY_SAFE_OUTSIDE, settings.safeAreaDrawOutside)
            .putBoolean(KEY_CLUSTER_SAFE_OUTSIDE, settings.clusterSafeAreaDrawOutside)
            .putBoolean(KEY_IMMERSIVE_VIDEO, settings.immersiveVideo)
            .putBoolean(KEY_HIDE_TOP, settings.hideTopBar)
            .putBoolean(KEY_HIDE_BOTTOM, settings.hideBottomBar)
            .putBoolean(KEY_DOCK_BAR, settings.dockBar)
            .putString(KEY_DOCK_APP_ORDER, settings.dockAppOrder.joinToString("\n"))
            .putBoolean(KEY_FULLSCREEN, settings.fullscreen)
            .putBoolean(KEY_LOCAL_NAVIGATION_CARD_ENABLED, settings.localNavigationCardEnabled)
            .putBoolean(KEY_PROJECTION_ENABLED, settings.projectionEnabled)
            // PATCH-0119：导航启动自动投屏。⚠️ 必须写：`fullscreen` 曾经漏写一次，
            // 表现为"开关关掉应用就丢"，用户以为设置没生效（见上面那段注释）。
            .putBoolean(KEY_CLUSTER_AUTO_SHOW_ON_ROUTE, settings.clusterAutoShowOnRoute)

            .putBoolean(KEY_ADVANCED_AUDIO, settings.advancedAudioChannelMapping)
            .putBoolean(KEY_MICROPHONE, settings.microphone)
            .putInt(KEY_MEDIA_AUDIO_CHANNEL, CarSessionSettings.sanitizeAudioChannel(settings.mediaAudioChannel))
            .putInt(
                KEY_NAVIGATION_AUDIO_CHANNEL,
                CarSessionSettings.sanitizeNavigationAudioChannel(settings.navigationAudioChannel),
            )
            .putBoolean(KEY_NAVIGATION_AUDIO_FOCUS_ENABLED, settings.navigationAudioFocusEnabled)
            .putInt(
                KEY_MEDIA_AUDIO_BUFFER_MILLIS,
                CarSessionSettings.sanitizeAudioBufferMillis(settings.mediaAudioBufferMillis),
            )
            .putBoolean("higher_quality_media", settings.higherQualityMedia)
            .putBoolean(KEY_FORWARD_STEERING_WHEEL, settings.forwardSteeringWheelToCarPlay)
            .putBoolean(KEY_REPORT_MEDIA_CARD, settings.reportMediaCardToCar)

            .putBoolean(KEY_LOCATION, settings.locationReporting)
            .putBoolean(KEY_AUTO_START, settings.autoStartOnBoot)
            .putBoolean(KEY_DEBUG_LOGS, settings.debugLogs)
            .putBoolean(KEY_FPS_OVERLAY, settings.fpsOverlayEnabled)
            // 2026-10-08：补上遗漏的持久化 + 新增仪表屏 id。
            .putBoolean(KEY_STARTUP_PERF_COMPILE, settings.startupPerfCompile)
            .putInt(KEY_CLUSTER_DISPLAY_ID, settings.clusterDisplayId)
            .putBoolean(KEY_CLUSTER_FLIP_180, settings.clusterFlip180)
            .putBoolean(KEY_CLUSTER_MODE_GUARD_ENABLED, settings.clusterModeGuardEnabled)
            .putBoolean(KEY_CLUSTER_NAV_CARD, settings.clusterNavigationCard)
            .putBoolean(KEY_CLUSTER_MAP_LAYOUT_TRIAL, settings.clusterMapLayoutTrial)
            .putInt(KEY_CLUSTER_ALIGNMENT, settings.clusterAlignment)
            // 2026-10-13：HUD 导航推送开关。⚠️ 必须写：`fullscreen` 曾经漏写一次，
            // 表现为"开关关掉应用就丢"，用户以为设置没生效（见上面那段注释）。
            .putBoolean(KEY_HUD_GUIDANCE_ENABLED, settings.hudGuidanceEnabled)
            // 2026-10-22：ECarX HUD 使能通道。⚠️ 必须写：漏写会表现为
            // “开关关掉重启又变回开”，用户以为设置没生效。
            .putBoolean(KEY_HUD_PUSH_ENABLED, settings.hudPushEnabled)
            // 2026-10-22：HUD 地图投屏（开关 + 裁剪 + 缩放）。⚠️ 必须写：
            // `fullscreen` 曾经漏写一次，表现为"开关关掉应用就丢"，用户以为设置没生效。
            .putBoolean(KEY_HUD_PROJECTION_ENABLED, settings.hudProjectionEnabled)
            .putFloat(KEY_HUD_CROP_LEFT, settings.hudCrop.leftFraction)
            .putFloat(KEY_HUD_CROP_TOP, settings.hudCrop.topFraction)
            .putFloat(KEY_HUD_CROP_RIGHT, settings.hudCrop.rightFraction)
            .putFloat(KEY_HUD_CROP_BOTTOM, settings.hudCrop.bottomFraction)
            .putString(KEY_HUD_SCALE_MODE, settings.hudScaleMode.name)
            .putString(KEY_CLUSTER_APPEARANCE_MODE, settings.clusterAppearanceMode.name)
            .putString(KEY_UI_APPEARANCE_MODE, settings.uiAppearanceMode.name)
            .putString(KEY_CLUSTER_LAYER_MODE, settings.clusterLayerMode().name)
            // 2026-10-17（PATCH-0130）：调试隧道字段落盘（此前完全没写，见 load 处注释）。
            .putBoolean(KEY_DEBUG_TUNNEL_ENABLED, settings.debugTunnelEnabled)
            .putString(KEY_DEBUG_TUNNEL_HOST, settings.debugTunnelHost)
            .putInt(KEY_DEBUG_TUNNEL_PORT, settings.debugTunnelPort)
            .putInt(KEY_DEBUG_TUNNEL_LOCAL_ADB_PORT, settings.debugTunnelLocalAdbPort)
            .putString(KEY_DEBUG_TUNNEL_SECRET, settings.debugTunnelSecret)
            .putString(KEY_DEBUG_TUNNEL_TOKEN, settings.debugTunnelToken)
            // PATCH-0013：把已移除的「分通道音量」旧键从设备上真正删掉。
            // 留着不删的话，将来若有人把语义改回去，这些陈旧值会「复活」成生效配置，
            // 现场表现是「没人改设置却换了行为」。
            .also { editor -> REMOVED_VOLUME_KEYS.forEach(editor::remove) }
            .apply()
    }

    /**
     * 「熄火自动暂停」开关（2026-10-18，默认开）。
     *
     * 刻意**不进** [CarSessionSettings]：它不参与与 iPhone 的握手，改完立即生效，
     * 走"保存设置 → 重连"那条链反而会让开关看着像没反应。键名与设置页
     * （[com.dbtool.carplay.api.power.CarPowerPauseSetting]）共用同一个常量。
     */
    fun powerPauseEnabled(): Boolean =
        CAR_POWER_PAUSE_DEFAULT_ENABLED

    /** 写入「熄火自动暂停」开关；异步落盘，不阻塞设置页。 */
    fun setPowerPauseEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(CAR_POWER_PAUSE_KEY, enabled).apply()
    }

    /**
     * 一次性迁移：把 11 位的旧默认口令换成 12 位新默认。
     *
     * 为什么必须迁：跳板 `relay.py` 弱校验要求口令 **≥12 位**，11 位的
     * `dbplay-2026` 会被直接判「口令不匹配」。改 data class 默认值只对新安装生效，
     * 老安装 prefs 里躺着的仍是旧值 ⇒ 必须改写一次。
     *
     * 判据用**裸字符串**（上一版真正写进 prefs 的那串文本），不用常量引用，
     * 避免将来改常量名时判据悄悄失效。
     */
    private fun migrateDebugTunnelSecretToTwelveChars(fallback: CarSessionSettings) {
        if (prefs.getBoolean(KEY_DEBUG_TUNNEL_SECRET_MIGRATED, false)) return
        val editor = prefs.edit()
        val current = prefs.getString(KEY_DEBUG_TUNNEL_SECRET, null)
        // 只在真存在、且确实是旧默认 / 空串时才改写；用户自己写过的口令不动。
        if (current.isNullOrBlank() || current == "dbplay-2026") {
            editor.putString(KEY_DEBUG_TUNNEL_SECRET, fallback.debugTunnelSecret)
        }
        editor.putBoolean(KEY_DEBUG_TUNNEL_SECRET_MIGRATED, true).apply()
    }

    /**
     * 调试隧道开关默认值改为 `true` 的一次性迁移（2026-10-19）。
     *
     * 布尔键的值是二义的：`true` 既可能是"旧出厂值"，也可能是"用户特意打开的"。
     * 这里的口径是**强制一次**：升级到本版的存量安装一律打开一次隧道，
     * 让后台能立刻看到设备在线；用户之后手动关掉，标记已置位，永久保持关闭。
     *
     * 铁律：独立标记 [KEY_DEBUG_TUNNEL_ENABLED_MIGRATED]，不复用被迁移的键本身；
     * 只在 `KEY_DEBUG_TUNNEL_ENABLED` 真的存在时才写（新安装不该凭空多一条持久化记录）。
     */
    private fun migrateDebugTunnelEnabledDefaultOn() {
        if (prefs.getBoolean(KEY_DEBUG_TUNNEL_ENABLED_MIGRATED, false)) return
        val editor = prefs.edit()
        if (prefs.contains(KEY_DEBUG_TUNNEL_ENABLED)) {
            editor.putBoolean(KEY_DEBUG_TUNNEL_ENABLED, true)
        }
        editor.putBoolean(KEY_DEBUG_TUNNEL_ENABLED_MIGRATED, true).apply()
    }

    /**
     * 读一个枚举设置项；取值缺失或不在枚举里时回落到默认值。
     *
     * 用 `runCatching` 而不是 `valueOf`：旧版本写进 prefs 的枚举名若后来被删掉，
     * `valueOf` 会抛 `IllegalArgumentException` 把 `load()` 整个带崩 ——
     * 现象是「升级后应用起不来」，而设置文件损坏本不该有这种后果。
     */
    private inline fun <reified T : Enum<T>> enumOf(name: String?, fallback: T): T {
        if (name.isNullOrEmpty()) return fallback
        return runCatching { enumValueOf<T>(name) }.getOrDefault(fallback)
    }

    /**
     * 一次性品牌迁移：上游出厂标识 → DBPlay。
     *
     * 为什么需要它：`deviceName` 这类值一旦写进 prefs 就**永远不会再读默认值**，
     * 所以"改默认值"对已装设备完全无效——老机器上「身份与外观」会一直显示
     * `xcertplay`。这里在读取前先把遗留值改写掉。
     *
     * ⚠️ 只改写**恰好等于上游出厂值**的项：用户自己起的名字必须原样保留。
     * 用 [KEY_BRANDING_MIGRATED] 标记保证只跑一次——否则用户手动把 OEM 标签
     * 清空后，下次启动又会被填回 DBPlay。
     *
     * ⚠️ **不要往这个函数里再塞别的迁移**。它的标记在老设备上早就是 `true`，
     * 新加的分支在那些机器上**一次都不会执行**。热点默认值踩过这个坑，
     * 见 [migrateLegacyHotspotDefaults]。每个一次性迁移必须自带标记。
     */
    /** 「自动按钮移除」迁移的标记 key。一次性迁移各自带标记（老规矩）。 */
    private val KEY_AUTO_MODE_MIGRATED = "hotspot_auto_mode_migrated_v1"

    /** 被移除的那个模式名。用字面量——枚举值仍在，但语义上已是存量值。 */
    private val LEGACY_AUTO_MODE_NAME = "AUTO"

    /** 「复用车机热点（MANUAL）从界面移除、由 TCAM 取代」迁移的标记 key。 */
    private val KEY_MANUAL_MODE_MIGRATED = "hotspot_manual_mode_migrated_v1"

    /**
     * 被 TCAM 取代的那个模式名。用字面量——枚举值仍在（存量值专用），
     * 但 prefs 里存的老值永远是这串文本。
     */
    private val LEGACY_MANUAL_MODE_NAME = "MANUAL"

    /**
     * 「Wi-Fi Direct 临时组网模式移除」迁移的标记 key。
     * 见 [migrateRemovedP2pHotspotMode]。
     */
    private val KEY_P2P_MODE_MIGRATED = "hotspot_p2p_mode_migrated_v1"

    /**
     * 已被移除的枚举值名字面量。
     *
     * ⚠️ **必须是字面量，不能写 `CarHotspotMode.P2P_DIRECT.name`** —— 那个枚举值
     * 本身已经删了（2026-10-13），写成引用会编译不过；而 prefs 里存的老值永远是
     * 这个字符串。用字面量也顺便说明"它只作为历史值存在"。
     */
    private val REMOVED_P2P_MODE_NAME = "P2P_DIRECT"

    private fun migrateLegacyBranding(fallback: CarSessionSettings) {
        if (prefs.getBoolean(KEY_BRANDING_MIGRATED, false)) return
        val editor = prefs.edit()
        for (key in BRANDING_KEYS) {
            val current = prefs.getString(key, null) ?: continue
            // 空串也算遗留值：上游 `DEFAULT_OEM_LABEL` 就是空串。
            if (current.isEmpty() || current in CarSessionSettings.LEGACY_UPSTREAM_IDENTITIES) {
                editor.putString(key, fallback.deviceName)
            }
        }
        editor.putBoolean(KEY_BRANDING_MIGRATED, true).apply()
    }

    /**
     * 一次性热点默认值迁移：上一版出厂热点 → `DBPlay` / `123456789` / WPA2 / 自动。
     *
     * ## 为什么必须独立成一个迁移
     *
     * 这条迁移最早是**塞在 [migrateLegacyBranding] 里的**（"密码 8 位升 9 位"那两行）。
     * 但品牌迁移的标记 [KEY_BRANDING_MIGRATED] 在老设备上早就是 `true`，
     * 整个函数第一行就 `return` 了 —— 于是那两行**在已装设备上从未执行过**。
     * 现场表现就是：代码里默认值明明是对的，设置页却还是上一版的
     * 空 SSID / 空密码 / 开放网络 / `LOCAL_ONLY_HOTSPOT`。
     *
     * ⇒ 一次性迁移**必须各自带标记**，不能共用一个。
     *
     * ## 改写范围
     *
     * 只动**恰好等于上一版出厂组合**的那四项（见
     * [CarSessionSettings.LEGACY_HOTSPOT_MODE] 的说明）：用户自己填过的 SSID、
     * 密码、加密方式、模式一律原样保留。迁移只跑一次，之后用户在设置页
     * 怎么改都不会被覆盖。
     */
    private fun migrateLegacyHotspotDefaults(fallback: CarSessionSettings) {
        if (prefs.getBoolean(KEY_HOTSPOT_MIGRATED, false)) return

        val ssid = prefs.getString(KEY_HOTSPOT_SSID, null)
        val passphrase = prefs.getString(KEY_HOTSPOT_PASS, null)
        val security = prefs.getString(KEY_HOTSPOT_SECURITY, null)
        val mode = prefs.getString(KEY_HOTSPOT_MODE, null)

        // 出厂态判据：四项全中。少判一项都可能把用户特意选过的组合翻掉。
        val untouchedFactoryState = ssid.isNullOrEmpty() &&
            passphrase.isNullOrEmpty() &&
            security == CarSessionSettings.LEGACY_HOTSPOT_SECURITY.name &&
            mode == CarSessionSettings.LEGACY_HOTSPOT_MODE

        val editor = prefs.edit()
        if (untouchedFactoryState) {
            editor.putString(KEY_HOTSPOT_SSID, fallback.manualHotspotSsid)
            editor.putString(KEY_HOTSPOT_PASS, fallback.manualHotspotPassphrase)
            editor.putString(KEY_HOTSPOT_SECURITY, fallback.manualHotspotSecurity.name)
            // 模式翻成 AUTO 才是"车机有热点就用车机热点"；车机没有热点时
            // AUTO 会自己拉起自建热点（ADB_HOSTAPD），与旧默认行为一致。
            editor.putString(KEY_HOTSPOT_MODE, fallback.hotspotMode.name)
        } else if (passphrase == CarSessionSettings.LEGACY_HOTSPOT_PASSPHRASE) {
            // 非出厂态、但密码还停在上一版的 8 位出厂值：只换密码，别的一律不碰。
            editor.putString(KEY_HOTSPOT_PASS, fallback.manualHotspotPassphrase)
        }
        editor.putBoolean(KEY_HOTSPOT_MIGRATED, true).apply()
    }

    /**
     * 一次性迁移：存量的 `hotspotMode = AUTO` → `ADB_HOSTAPD`。
     *
     * ## 为什么必须迁移
     *
     * 2026-10-08（斌哥定案）：设置页的「自动」按钮**已从界面移除**
     * （见 `SettingsScreen` 里 `CarSettingChoice` 的 options）。但**枚举值本身保留**
     * ——老安装的 SharedPreferences 里存的仍是字符串 `"AUTO"`。
     *
     * 若放着不管，这些用户的设置页会**整排模式按钮一个都不高亮**（选中值不在
     * 选项列表里 ⇒ `option == selected` 永远为假）。界面不会崩（已核对
     * `CarSettingChoice` 只做相等比较），但用户看到的是一排"没选中"的按钮，
     * 会误以为设置坏了。
     *
     * ## 为什么改写目标是 ADB_HOSTAPD 而不是 MANUAL
     *
     * `AUTO` 在实际解析里**早就不自动了**：`CarHotspotResolver.resolve()` 自
     * 2026-10-05 起对 `AUTO` 也是**直接自建、不做任何探测**。⇒ 把存量 `AUTO`
     * 改写成 `ADB_HOSTAPD` **行为完全等价**，只是让界面能正确高亮。
     *
     * ## 改写范围
     *
     * **只动这一项**，且只在它恰好等于 `"AUTO"` 时。其他设置一律不碰。
     * 迁移只跑一次（自带标记），之后用户怎么改都不会被覆盖。
     */
    private fun migrateAutoHotspotModeToSelfBuilt(d: CarSessionSettings) {
        if (prefs.getBoolean(KEY_AUTO_MODE_MIGRATED, false)) return
        val editor = prefs.edit()
        if (prefs.getString(KEY_HOTSPOT_MODE, null) == LEGACY_AUTO_MODE_NAME) {
            editor.putString(KEY_HOTSPOT_MODE, CarHotspotMode.ADB_HOSTAPD.name)
        }
        editor.putBoolean(KEY_AUTO_MODE_MIGRATED, true).apply()
    }

    /**
     * 一次性迁移：**已移除的** `hotspotMode = P2P_DIRECT` → 新出厂默认 [CarHotspotMode.MANUAL]。
     *
     * ## 背景
     *
     * 2026-10-13（车主口径）：撤掉「Wi-Fi Direct 临时组网」这一档热点模式，
     * 出厂默认改回「复用车机热点」。枚举值 `P2P_DIRECT` 已从 [CarHotspotMode] 删除。
     *
     * ## 为什么必须显式迁移（而不是靠 `enumOf` 的兜底）
     *
     * [enumOf] 确实会 `runCatching` 兜底到默认值，所以**不会崩**。但那样有两个问题：
     *
     * 1. prefs 里会**永久留着**一个已不存在的枚举名字符串 —— 每次启动都要靠兜底，
     *    而设置页的「恢复出厂设置」之类的判据会看到"值 ≠ 默认值"，行为不确定；
     * 2. 界面上表现为"用户明明没改过，模式却和出厂默认不一样"，而**没有任何痕迹**
     *    说明这是被移除造成的（与 [migrateAutoHotspotModeToSelfBuilt] 那个坑同源：
     *    老安装的模式按钮会一个都不高亮，用户以为设置坏了）。
     *
     * 显式改写一次之后，prefs 与默认值**一致**，界面高亮正确，且迁移只跑一次。
     *
     * ## 改写范围
     *
     * **只动恰好等于 `"P2P_DIRECT"` 的那一项**；用户后来自己选的模式一律不碰。
     */
    private fun migrateRemovedP2pHotspotMode(d: CarSessionSettings) {
        if (prefs.getBoolean(KEY_P2P_MODE_MIGRATED, false)) return
        val editor = prefs.edit()
        if (prefs.getString(KEY_HOTSPOT_MODE, null) == REMOVED_P2P_MODE_NAME) {
            editor.putString(KEY_HOTSPOT_MODE, d.hotspotMode.name)
        }
        editor.putBoolean(KEY_P2P_MODE_MIGRATED, true).apply()
    }

    /**
     * 一次性迁移：存量的 `hotspotMode = MANUAL` → 新出厂默认 [CarHotspotMode.TCAM_HOTSPOT]。
     *
     * ## 背景
     *
     * 2026-10-16（车主定案）：设置页的「复用车机热点」按钮**已从界面移除**，由
     * 「复用车机 TCAM 热点」取代（见 `SettingsScreen` 里 `CarSettingChoice` 的 options）。
     * 但**枚举值 `MANUAL` 保留**——老安装的 SharedPreferences 里存的仍是字符串 `"MANUAL"`。
     *
     * 若放着不管，这些用户会**丢失 TCAM 语义**（`MANUAL` 走的是手填地址那一支，而
     * 车主要的是 TCAM 自动识别 `eth0.11`），且设置页会整排模式按钮一个都不高亮
     * （选中值不在选项列表里 ⇒ `option == selected` 永远为假）。
     *
     * ## 为什么不动 `manualHotspotSsid` / `manualHotspotPassphrase`
     *
     * 这两个老字段是**共享凭据**（自建热点 `ADB_HOSTAPD` 与 TCAM 回落源都在读），
     * 本次迁移**只读不改、不许清空**；只动 `hotspot_mode` 这一项。
     *
     * ## 改写范围
     *
     * **只动恰好等于 `"MANUAL"` 的那一项**；用户后来自己选的模式一律不碰。
     * 迁移只跑一次（自带标记）；若因降级→升级等边缘路径迁移标记已置位、值仍为
     * `"MANUAL"`，则由 [load] 的解析层兜底等价到 `TCAM_HOTSPOT`。
     */
    private fun migrateManualHotspotModeToTcam(d: CarSessionSettings) {
        if (prefs.getBoolean(KEY_MANUAL_MODE_MIGRATED, false)) return
        val editor = prefs.edit()
        if (prefs.getString(KEY_HOTSPOT_MODE, null) == LEGACY_MANUAL_MODE_NAME) {
            editor.putString(KEY_HOTSPOT_MODE, d.hotspotMode.name)
        }
        editor.putBoolean(KEY_MANUAL_MODE_MIGRATED, true).apply()
    }

    /**
     * 一次性迁移：出厂热点密码从 9 位（`123456789`）改回 8 位（`12345678`）。
     *
     * ## 为什么必须独立成一个迁移
     *
     * 老规矩（[migrateLegacyHotspotDefaults] 的注释里踩过一次）：
     * **一次性迁移各自带标记，不能共用**。而且 `load()` 改默认值
     * **不影响已经持久化的字段**——已装设备里 `hotspot_pass` 是实打实的
     * `123456789`，不写一次迁移就永远停在 9 位。
     *
     * ## 为什么要改
     *
     * ADB 独立热点（[com.dbtool.carplay.api.settings.CarHotspotMode.ADB_HOSTAPD]）
     * 的参考实现用 8 位密码，产品要求与之对齐。**hostapd 用的密码与通告给 iPhone 的
     * 密码必须是同一个值**，否则 iPhone 会拿着 A 密码去连 B 密码的 AP。
     *
     * ## 改写范围
     *
     * 只动**恰好等于上一版出厂值**的项；用户自己改过的密码一律保留。
     */
    private fun migrateHotspotPassphraseToEightChars(fallback: CarSessionSettings) {
        if (prefs.getBoolean(KEY_HOTSPOT_PASS8_MIGRATED, false)) return
        val editor = prefs.edit()
        if (prefs.getString(KEY_HOTSPOT_PASS, null) ==
            CarSessionSettings.PREVIOUS_HOTSPOT_PASSPHRASE
        ) {
            editor.putString(KEY_HOTSPOT_PASS, fallback.manualHotspotPassphrase)
        }
        editor.putBoolean(KEY_HOTSPOT_PASS8_MIGRATED, true).apply()
    }

    /**
     * 一次性把「画面延展到状态栏下」写回关闭。
     *
     * 开关已从设置页拿掉。只改 Kotlin 默认值不会动已经落盘的 `true`，
     * 用户会一直顶格，又没有入口关掉。
     */
    private fun migrateImmersiveVideoDefaultOff() {
        if (prefs.getBoolean(KEY_IMMERSIVE_OFF_MIGRATED, false)) return
        prefs.edit()
            .putBoolean(KEY_IMMERSIVE_VIDEO, false)
            .putBoolean(KEY_IMMERSIVE_OFF_MIGRATED, true)
            .apply()
    }

    /**
     * 一次性迁移：`hideTopBar` 出厂值从 `true` 改回 `false`。
     *
     * 老规矩：一次性迁移各自带标记，不能共用。`load()` 改默认值不影响已经持久化的字段。
     * 判据是上一版出厂组合 `hide_top_bar == true && hide_bottom_bar == true`。
     */
    /**
     * 「分辨率缩放」和「自定义分辨率」已从设置页拿掉。
     * 缩放固定 1.0x，自定义分辨率关闭。已保存的旧值在这里改回。
     */
    private fun migrateDisplayScaleTo1x() {
        if (prefs.getBoolean(KEY_DISPLAY_SCALE_1X_MIGRATED, false)) return
        prefs.edit()
            .putInt(KEY_SCALE_TENTHS, CarSessionSettings.DEFAULT_SCALE_TENTHS)
            .putBoolean(KEY_CUSTOM_DISPLAY, false)
            .putBoolean(KEY_DISPLAY_SCALE_1X_MIGRATED, true)
            .apply()
    }

    /** 底栏入口已从「车机配置」拿掉，已打开的安装改回关闭。 */
    private fun migrateDockBarOff() {
        if (prefs.getBoolean(KEY_DOCK_OFF_MIGRATED, false)) return
        prefs.edit()
            .putBoolean(KEY_DOCK_BAR, false)
            .putBoolean(KEY_DOCK_OFF_MIGRATED, true)
            .apply()
    }

    private fun migrateHideTopBarDefault(fallback: CarSessionSettings) {
        if (prefs.getBoolean(KEY_HIDE_TOP_MIGRATED, false)) return
        val editor = prefs.edit()
        val untouchedFactoryState =
            prefs.getBoolean(KEY_HIDE_TOP, true) && prefs.getBoolean(KEY_HIDE_BOTTOM, true)
        if (untouchedFactoryState) {
            editor.putBoolean(KEY_HIDE_TOP, fallback.hideTopBar)
        }
        editor.putBoolean(KEY_HIDE_TOP_MIGRATED, true).apply()
    }

    /**
     * 一次性迁移：`transport` 出厂值 `WIRED` → `WIRELESS`。
     *
     * ## 为什么必须独立成一个迁移
     *
     * 老规矩（见 [migrateLegacyHotspotDefaults] 的注释）：**一次性迁移各自带标记，
     * 不能共用**。`load()` 里把默认值改成 `WIRELESS` **不影响已经持久化的字段**——
     * 只要老安装写过一个 `transport=WIRED`，不写迁移它就永远停在有线。
     *
     * ## 改写范围：只认"恰好等于旧出厂值"
     *
     * 判据是**持久化值恰好等于 `WIRED`**。这是产品要求（2026-10-06）：
     * 本工程的目标场景是无线 CarPlay，装的就该是无线。
     *
     * ⚠️ 这个判据**拦不住**"特意选了有线"的用户——单靠持久化值无法区分
     * "从没改过"与"手动改成有线"。取舍与 [migrateHideTopBarDefault] 相同：
     * 绝大多数用户从未碰过；少数确实需要有线（USB 调试/无 Wi-Fi 环境）的用户
     * 重新去设置里选一次即可，设置项本身没变。
     */
    private fun migrateTransportToWireless(fallback: CarSessionSettings) {
        if (prefs.getBoolean(KEY_TRANSPORT_MIGRATED, false)) return
        val editor = prefs.edit()
        val current = prefs.getString(KEY_TRANSPORT, null)
        if (current == LEGACY_DEFAULT_TRANSPORT) {
            editor.putString(KEY_TRANSPORT, fallback.transport.name)
        }
        editor.putBoolean(KEY_TRANSPORT_MIGRATED, true).apply()
    }

    /**
     * 一次性迁移：`widthPhysicalMm` 出厂值 `200` → `350`。
     *
     * ## 为什么必须独立成一个迁移
     *
     * 同上：`load()` 改 `DEFAULT_WIDTH_PHYSICAL_MM` 不影响已持久化的
     * `display_width_mm`。老安装里存着 `200`，不写迁移就永远是 200。
     *
     * ## 改写范围：只认"恰好等于旧出厂值"
     *
     * 判据是持久化值**恰好等于 200**。超过 200 的值（例如用户已按实际屏宽
     * 调成 250/300）一律不碰——那已经是用户自己量过的真实值，改成 350 反而更差。
     *
     * ⚠️ 拦不住"特意选 200"的用户，理由与取舍同上。
     */
    private fun migrateWidthPhysicalMmTo350(fallback: CarSessionSettings) {
        if (prefs.getBoolean(KEY_WIDTH_MM_MIGRATED, false)) return
        val editor = prefs.edit()
        val current = prefs.getInt(KEY_WIDTH_MM, NO_STORED_WIDTH_MM)
        if (current == LEGACY_DEFAULT_WIDTH_MM) {
            editor.putInt(KEY_WIDTH_MM, fallback.widthPhysicalMm)
        }
        editor.putBoolean(KEY_WIDTH_MM_MIGRATED, true).apply()
    }

    /**
     * 一次性迁移：蓝牙释放默认档 `DISCONNECT_LINK` → `DISABLE_ADAPTER`（PATCH-0095）。
     *
     * ## 为什么必须独立成一个迁移
     *
     * 老规矩（见 [migrateLegacyHotspotDefaults] 的注释）：**一次性迁移各自带标记，
     * 不能共用**。而且 `load()` 改默认值**不影响已经持久化的字段** —— 只要老安装
     * 保存过一次设置，`bt_handoff_policy` 就是实打实的 `DISCONNECT_LINK`
     * （`save()` 每次都写它），不写迁移它们会永远停在**实测无效**的那一档。
     *
     * ## 改写范围：只认"恰好等于旧出厂值"
     *
     * 判据是持久化值**恰好等于 `DISCONNECT_LINK`**。已经选了 `OFF`
     * （上游行为）或 `DISABLE_ADAPTER` 的一律不碰。
     *
     * ⚠️ 这个判据**拦不住**"特意选了「断开手机」"的用户——单靠持久化值无法区分
     * "从没改过"与"手动改成旧出厂值"。取舍与 [migrateTransportToWireless] 相同：
     * 绝大多数用户从未碰过；确实要留在旧档的用户重新去设置里选一次即可
     * （设置项本身没变，只是默认值换了）。
     */
    /**
     * 2026-10-21 车主口径「不能关车机蓝牙」：把老安装里持久化的 `DISABLE_ADAPTER`
     * 一次性迁到 `OFF`。新装用户直接拿新默认值，不受影响；
     * 确实想关适配器的用户重新去设置里选一次即可。
     */
    private fun migrateBluetoothHandoffToOff(d: CarSessionSettings) {
        if (prefs.getBoolean(KEY_BT_HANDOFF_MIGRATED_OFF, false)) return
        val editor = prefs.edit()
        if (prefs.getString(KEY_BT_HANDOFF_POLICY, null) == "DISABLE_ADAPTER") {
            editor.putString(KEY_BT_HANDOFF_POLICY, "OFF")
        }
        editor.putBoolean(KEY_BT_HANDOFF_MIGRATED_OFF, true).apply()
    }

    private fun migrateBluetoothHandoffToDisableAdapter(fallback: CarSessionSettings) {
        if (prefs.getBoolean(KEY_BT_HANDOFF_MIGRATED, false)) return
        val editor = prefs.edit()
        if (prefs.getString(KEY_BT_HANDOFF_POLICY, null) == LEGACY_DEFAULT_BT_HANDOFF) {
            editor.putString(KEY_BT_HANDOFF_POLICY, fallback.bluetoothHandoffPolicy.name)
        }
        editor.putBoolean(KEY_BT_HANDOFF_MIGRATED, true).apply()
    }

    /**
     * 读「核心安全区」。
     *
     * ⚠️ 2026-10-11（PATCH-0080）：模型已从**四边内边距**改成**矩形**（`safe_area_x/y/w/h`）。
     * 老安装里存的是四边（`safe_area_top/bottom/left/right`）⇒ 这里做**一次性换算**：
     * 按两块屏都是 1920×1080 的面板把它折成矩形（`x=left, y=top, w=W−left−right, h=H−top−bottom`）。
     * 读得到矩形键就用矩形；读不到才回落到老的换算。
     *
     * ⚠️ 换算只在**真的填过**（四边非全零）时发生；全零 = "没设过" ⇒ 保持"未设置"，
     * 这样老安装里没动过这一项的用户行为完全不变。
     */
    private fun loadSafeArea(fallback: CarSettingsSafeArea): CarSettingsSafeArea {
        if (prefs.contains(KEY_SAFE_WIDTH) || prefs.contains(KEY_SAFE_HEIGHT)) {
            return CarSettingsSafeArea(
                x = prefs.getInt(KEY_SAFE_X, fallback.x).coerceAtLeast(0),
                y = prefs.getInt(KEY_SAFE_Y, fallback.y).coerceAtLeast(0),
                width = prefs.getInt(KEY_SAFE_WIDTH, fallback.width).coerceAtLeast(0),
                height = prefs.getInt(KEY_SAFE_HEIGHT, fallback.height).coerceAtLeast(0),
            )
        }
        val top = prefs.getInt(KEY_SAFE_TOP, 0).coerceAtLeast(0)
        val bottom = prefs.getInt(KEY_SAFE_BOTTOM, 0).coerceAtLeast(0)
        val left = prefs.getInt(KEY_SAFE_LEFT, 0).coerceAtLeast(0)
        val right = prefs.getInt(KEY_SAFE_RIGHT, 0).coerceAtLeast(0)
        if (top == 0 && bottom == 0 && left == 0 && right == 0) return fallback
        return CarSettingsSafeArea(
            x = left,
            y = top,
            width = LEGACY_SAFE_AREA_PANEL_WIDTH - left - right,
            height = LEGACY_SAFE_AREA_PANEL_HEIGHT - top - bottom,
        )
    }

    /** 主屏安全区。新键，没有旧四边需要换算；没存过就保持未设置。 */
    private fun loadMainSafeArea(fallback: CarSettingsSafeArea): CarSettingsSafeArea {
        if (!prefs.contains(KEY_MAIN_SAFE_WIDTH) && !prefs.contains(KEY_MAIN_SAFE_HEIGHT)) {
            return fallback
        }
        return CarSettingsSafeArea(
            x = prefs.getInt(KEY_MAIN_SAFE_X, fallback.x).coerceAtLeast(0),
            y = prefs.getInt(KEY_MAIN_SAFE_Y, fallback.y).coerceAtLeast(0),
            width = prefs.getInt(KEY_MAIN_SAFE_WIDTH, fallback.width).coerceAtLeast(0),
            height = prefs.getInt(KEY_MAIN_SAFE_HEIGHT, fallback.height).coerceAtLeast(0),
        )
    }

    /**
     * 读一条通道的增益百分比并夹回区间。
     *
     * 抽成一个函数而不是在 `load()` 里写五遍 `.coerceIn(...)`：
     * 抽成组件只为保证两条通道的区间与标签格式完全一致——
     * 两段复制粘贴里只要有一处把上限写成别的数，表现就是「这条能选到 20，那条只能到 10」，
     * 而代码上看不出来。
     */

    companion object {
        const val PREFS_NAME = "car_ui_settings"

        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_MANUFACTURER = "manufacturer"
        private const val KEY_MODEL = "model"
        private const val KEY_OEM_LABEL = "oem_label"
        private const val KEY_DRIVING_SIDE = "driving_side"

        private const val KEY_TRANSPORT = "transport"
        private const val KEY_MFI_TARGET = "mfi_target"
        private const val KEY_I2C_PATH = "i2c_path"
        private const val KEY_REMOTE_SERVER = "remote_mfi_server"
        private const val KEY_REMOTE_TOKEN = "remote_mfi_token"
        private const val KEY_HOTSPOT_MODE = "hotspot_mode"
        private const val KEY_HOTSPOT_SSID = "hotspot_ssid"
        private const val KEY_HOTSPOT_PASS = "hotspot_pass"
        private const val KEY_HOTSPOT_BAND = "hotspot_band"
        private const val KEY_HOTSPOT_CHANNEL = "hotspot_channel"
        /** 复用车机热点的网关地址（PATCH-0087）。**没填过 = 空串**，不是"用默认值"。 */
        private const val KEY_HOTSPOT_HOST_ADDRESS = "hotspot_host_address"
        private const val KEY_HOTSPOT_SECURITY = "hotspot_security"

        /**
         * ADB 独立热点的 AP 网卡名覆盖值（空 = 自动探测）。
         *
         * 不是枚举也不是布尔：车机上 Wi-Fi 网卡的命名由厂商决定
         * （`wlan0` / `wlan1` / `wlan2` / `ap0` 都见过），自动探测覆盖不到时
         * 用户在这里填一次即可，不必等我们发版。
         */
        private const val KEY_ADB_HOTSPOT_IFACE = "adb_hotspot_interface"
        // PATCH-0010：新 key ⇒ 老安装天然取默认值（空 = 自动读取），不需要迁移。
        private const val KEY_SAME_LAN_HOST = "same_lan_host_address"
        // PATCH-0010 修正：SAME_LAN 下通告给 iPhone 的那张 Wi-Fi 的凭据。
        // 同样是新 key ⇒ 老安装取默认空值（= 自动读取 SSID、密码未知），无需迁移。
        private const val KEY_SAME_LAN_SSID = "same_lan_wifi_ssid"
        private const val KEY_SAME_LAN_PASS = "same_lan_wifi_passphrase"
        private const val KEY_BT_HANDOFF_POLICY = "bluetooth_handoff_policy"
        private const val KEY_BT_HANDOFF_MIGRATED_OFF = "bluetooth_handoff_migrated_off"

        private const val KEY_SCALE_TENTHS = "display_scale_tenths"
        private const val KEY_DISPLAY_SCALE_1X_MIGRATED = "display_scale_1x_migrated"
        private const val KEY_FPS = "display_fps"
        private const val KEY_WIDTH_MM = "display_width_mm"
        private const val KEY_SIZE_BASIS = "display_size_basis"

        // 自定义分辨率（2026-09-30）。四个 key 都是新的，**刻意不复用**
        // `display_scale_tenths` 那组——复用会让"关掉自定义"之后仍读到残留值。
        private const val KEY_CUSTOM_DISPLAY = "display_custom_enabled"
        private const val KEY_CUSTOM_WIDTH = "display_custom_width_px"
        private const val KEY_CUSTOM_HEIGHT = "display_custom_height_px"
        private const val KEY_CUSTOM_DPI = "display_custom_dpi"

        private const val KEY_HEVC = "hevc"
        private const val KEY_HEVC_SOFT = "hevc_software"
        private const val KEY_SAFE_TOP = "safe_area_top"
        private const val KEY_SAFE_BOTTOM = "safe_area_bottom"
        private const val KEY_SAFE_LEFT = "safe_area_left"
        private const val KEY_SAFE_RIGHT = "safe_area_right"
        /** 矩形模型的新键（PATCH-0080）。 */
        private const val KEY_SAFE_X = "safe_area_x"
        private const val KEY_SAFE_Y = "safe_area_y"
        private const val KEY_SAFE_WIDTH = "safe_area_width"
        private const val KEY_SAFE_HEIGHT = "safe_area_height"
        private const val KEY_MAIN_SAFE_X = "main_safe_area_x"
        private const val KEY_MAIN_SAFE_Y = "main_safe_area_y"
        private const val KEY_MAIN_SAFE_WIDTH = "main_safe_area_width"
        private const val KEY_MAIN_SAFE_HEIGHT = "main_safe_area_height"

        /**
         * 老四边值换算成矩形时假定的面板尺寸。
         *
         * 两块在用车机（CS11 / CX11）的仪表面板都是 1920×1080；而老的四边本来就是**按面板**填的，
         * 所以用面板尺寸反推矩形误差为零。换台别的面板尺寸时也只影响一次换算的绝对值
         * （比例仍在，画框里再微调一次即可）—— 比"直接丢掉用户设过的值"好。
         */
        private const val LEGACY_SAFE_AREA_PANEL_WIDTH = 1920
        private const val LEGACY_SAFE_AREA_PANEL_HEIGHT = 1080
        private const val KEY_SAFE_OUTSIDE = "safe_area_draw_outside"
        private const val KEY_CLUSTER_SAFE_OUTSIDE = "cluster_safe_area_draw_outside"
        private const val KEY_IMMERSIVE_VIDEO = "immersive_video"
        private const val KEY_IMMERSIVE_OFF_MIGRATED = "immersive_video_off_migrated"
        private const val KEY_HIDE_TOP = "hide_top_bar"
        private const val KEY_HIDE_BOTTOM = "hide_bottom_bar"
        private const val KEY_DOCK_BAR = "dock_bar"
        private const val KEY_DOCK_OFF_MIGRATED = "dock_bar_off_migrated"
        private const val KEY_DOCK_APP_ORDER = "dock_app_order"
        private const val KEY_FULLSCREEN = "fullscreen"
        private const val KEY_LOCAL_NAVIGATION_CARD_ENABLED = "local_navigation_card_enabled"
        private const val KEY_PROJECTION_ENABLED = "projection_enabled"
        /** 2026-10-17（PATCH-0130）：调试反连隧道。新键 ⇒ 老安装天然取默认，
         *  但「口令长度」要迁移（11 → 12 位），见 [migrateDebugTunnelSecretToTwelveChars]。 */
        private const val KEY_DEBUG_TUNNEL_ENABLED = "debug_tunnel_enabled"
        private const val KEY_DEBUG_TUNNEL_HOST = "debug_tunnel_host"
        private const val KEY_DEBUG_TUNNEL_PORT = "debug_tunnel_port"
        private const val KEY_DEBUG_TUNNEL_LOCAL_ADB_PORT = "debug_tunnel_local_adb_port"
        private const val KEY_DEBUG_TUNNEL_SECRET = "debug_tunnel_secret"
        private const val KEY_DEBUG_TUNNEL_TOKEN = "debug_tunnel_token"
        private const val KEY_DEBUG_TUNNEL_SECRET_MIGRATED = "debug_tunnel_secret_migrated_v1"

        /** 调试隧道开关「默认改为开」的一次性迁移标记（独立于被迁移的键）。 */
        private const val KEY_DEBUG_TUNNEL_ENABLED_MIGRATED = "debug_tunnel_enabled_migrated_v1"

        /**
         * PATCH-0119（2026-10-14）：**导航启动自动投屏**开关。
         *
         * 新键 ⇒ 老安装天然取默认值 `true`（= 车主本次口径），**不需要迁移**。
         * 与 [KEY_PROJECTION_ENABLED] 分开保存：能力开关不等于导航自动触发开关。
         */
        private const val KEY_CLUSTER_AUTO_SHOW_ON_ROUTE = "cluster_auto_show_on_route"

        private const val KEY_ADVANCED_AUDIO = "advanced_audio_mapping"
        private const val KEY_MEDIA_AUDIO_CHANNEL = "media_audio_channel"
        private const val KEY_NAVIGATION_AUDIO_CHANNEL = "navigation_audio_channel"
        private const val KEY_NAVIGATION_AUDIO_FOCUS_ENABLED = "navigation_audio_focus_enabled"
        private const val KEY_MEDIA_AUDIO_BUFFER_MILLIS = "media_audio_buffer_millis"

        /**
         * PATCH-0013 移除分通道音量后**不再使用**的旧键。
         *
         * 留在这里是为了在 `writeTo` 时把它们从 SharedPreferences 里删干净：
         * 不删的话，用户设备上会一直躺着几个永不读取的键；更要紧的是，
         * 万一将来有人把某条通道的语义改回去，旧值会突然「复活」成生效配置，
         * 而现场看到的将是「没改过设置却换了行为」。
         */
        private val REMOVED_VOLUME_KEYS = listOf(
            "per_channel_volume_enabled",
            "volume_percent_media",
            "volume_percent_voice",
            "volume_percent_assistant",
            "volume_percent_navigation",
            "volume_percent_compatibility",
        )
        private const val KEY_MICROPHONE = "microphone"

        // 分通道音量（2026-09-30）。六个 key 全是新的 ⇒ 老安装天然取默认值
        // （开关 true、五条通道 100%），**不需要配迁移**。
        //
        // ⚠️ 不要图省事把五条通道塞进一个 key（例如逗号分隔的字符串）：
        // 那样任何一条通道的读取都要解析整串，`effective_settings.txt` 里也会变成
        // 一行看不出来是什么的东西，而诊断包正是现场唯一能拿到的证据。
        private const val KEY_FORWARD_STEERING_WHEEL = "forward_steering_wheel_to_carplay"

        /**
         * ⚠️ 新增设置项一律**新建 key**，不要复用别人的。
         *
         * 复用的后果与"迁移共用一个标记"是同一类：后来者会读到前一个字段留下的值，
         * 表现为"代码默认值对、设置页却是旧值"。见 `MEMORY.md` 的迁移硬规则。
         */
        private const val KEY_REPORT_MEDIA_CARD = "report_media_card_to_car"

        private const val KEY_LOCATION = "location_reporting"
        private const val KEY_AUTO_START = "auto_start_on_boot"
        private const val KEY_DEBUG_LOGS = "debug_logs"
        private const val KEY_FPS_OVERLAY = "fps_overlay_enabled"

        // 2026-10-08：启动性能优化（原来漏了持久化）+ 仪表副屏选择。
        private const val KEY_STARTUP_PERF_COMPILE = "startup_perf_compile"
        private const val KEY_CLUSTER_DISPLAY_ID = "cluster_display_id"

    /**
     * 副屏画面 180° 翻转（2026-10-11）。
     *
     * 名字用 `cluster_flip_180` —— 值是布尔（是否翻转），不是角度：
     * 目前只需要"正装 / 倒装"两态；将来若要支持 90°/270°，再另立枚举 key，
     * 不要把这里扩成角度以免既有值含义漂移。
     */
    private const val KEY_CLUSTER_FLIP_180 = "cluster_flip_180"

    /** 全屏地图模式守护灰度开关（默认 false）。 */
    private const val KEY_CLUSTER_MODE_GUARD_ENABLED = "cluster_mode_guard_enabled"

    /** 副屏内容：显示 iPhone 导航卡片（原版 `iphone_navigation_card`）。 */
    private const val KEY_CLUSTER_NAV_CARD = "iphone_navigation_card"

    /** 副屏内容：仅地图布局控制（原版同名字面量，便于与老版本设置对齐）。 */
    private const val KEY_CLUSTER_MAP_LAYOUT_TRIAL = "iphone_map_layout_trial"

    /**
     * 副屏转向卡对齐（原版 `iphone_navigation_alignment`）。
     *
     * 存**下标**（0=左 / 1=右 / 2=顶），不是枚举名 —— 与
     * `CarPlayClusterDisplay.MANEUVER_LAYOUTS` 的顺序绑定。
     */
    private const val KEY_CLUSTER_ALIGNMENT = "iphone_navigation_alignment"

    /** 允许无导航时手动开启仪表地图（原版 `allow_manual_cruise`）。 */
    private const val KEY_CLUSTER_MANUAL_CRUISE = "allow_manual_cruise"

    /**
     * HUD 导航转向提示推送开关（2026-10-13，PATCH-0114）。
     *
     * 新键 ⇒ 老安装天然取默认值 `true`（= 保持现行为：HUD 照推），**不需要迁移**。
     * 不复用 `projection_enabled`：
     * 两者是**两条不同的通道**（投屏接管画面 / HUD 只给转向提示），
     * 复用会让"关掉 HUD"顺手把投屏也关掉。
     */
    private const val KEY_HUD_GUIDANCE_ENABLED = "hud_guidance_enabled"
    // 2026-10-22：ECarX HUD 使能通道。**新键**，与上面的转向提示、下面的地图投屏
    // 是三条独立通道，不复用任何旧键。
    private const val KEY_HUD_PUSH_ENABLED = "hud_push_enabled"
    // 2026-10-22：HUD 地图投屏。**新键**，不复用 hud_guidance_enabled：
    // 一个是转向提示、一个是整幅地图，复键会让用户关掉其中一个时顺手关掉另一个。
    private const val KEY_HUD_PROJECTION_ENABLED = "hud_projection_enabled"
    private const val KEY_HUD_CROP_LEFT = "hud_crop_left"
    private const val KEY_HUD_CROP_TOP = "hud_crop_top"
    private const val KEY_HUD_CROP_RIGHT = "hud_crop_right"
    private const val KEY_HUD_CROP_BOTTOM = "hud_crop_bottom"
    private const val KEY_HUD_SCALE_MODE = "hud_scale_mode"

    /**
     * P1 仪表主题（2026-10-17）。键名按任务书固定：`cluster_theme_id` / `cluster_skin_enabled`。
     * 不复用任何既有 `cluster_*` 键 —— 主题与副屏窗口是两套语义。
     */
    private const val KEY_CLUSTER_THEME_ID = "cluster_theme_id"
    private const val KEY_CLUSTER_SKIN_ENABLED = "cluster_skin_enabled"
        private const val KEY_CLUSTER_APPEARANCE_MODE = "cluster_appearance_mode"
    private const val KEY_UI_APPEARANCE_MODE = "ui_appearance_mode"

        /** 仪表分层显示模式；存枚举名，缺省/非法值回落 [ClusterLayerMode.BOTH]。 */
        private const val KEY_CLUSTER_LAYER_MODE = "cluster_layer_mode"

        /** 品牌迁移是否已执行过。见 [migrateLegacyBranding]。 */
        private const val KEY_BRANDING_MIGRATED = "branding_migrated_dbplay"

        /**
         * 热点默认值迁移是否已执行过。见 [migrateLegacyHotspotDefaults]。
         *
         * ⚠️ 与 [KEY_BRANDING_MIGRATED] **必须分开**：共用一个标记的话，
         * 后加的迁移在老设备上永远不会跑（这正是当初的 bug）。
         */
        private const val KEY_HOTSPOT_MIGRATED = "hotspot_migrated_dbplay"

        /**
         * 「热点密码 9 位 → 8 位」迁移是否已执行过。
         * 见 [migrateHotspotPassphraseToEightChars]。
         */
        private const val KEY_HOTSPOT_PASS8_MIGRATED = "hotspot_pass8_migrated_dbplay"

        /**
         * 「隐藏顶部栏默认值 true → false」迁移是否已执行过。
         * 见 [migrateHideTopBarDefault]。
         */
        private const val KEY_HIDE_TOP_MIGRATED = "hide_top_bar_migrated_dbplay"

        /**
         * 「连接方式默认值 WIRED → WIRELESS」迁移是否已执行过。
         * 见 [migrateTransportToWireless]。
         */
        private const val KEY_TRANSPORT_MIGRATED = "transport_wireless_migrated_dbplay"

        /**
         * 「参考物理长度默认值 200 → 350」迁移是否已执行过。
         * 见 [migrateWidthPhysicalMmTo350]。
         */
        private const val KEY_WIDTH_MM_MIGRATED = "width_mm_350_migrated_dbplay"

        /**
         * 「蓝牙释放默认档 DISCONNECT_LINK → DISABLE_ADAPTER」迁移是否已执行过。
         * 见 [migrateBluetoothHandoffToDisableAdapter]。
         */
        private const val KEY_BT_HANDOFF_MIGRATED = "bt_handoff_adapter_migrated_dbplay"

        /**
         * 上一版的 `transport` 出厂值（写死在偏好里的名字）。
         *
         * 用裸字符串而不是 `CarTransportMode.WIRED.name`：迁移的判据是"上一版
         * 出厂时**写进去的那串文本**"，不是"这个枚举某个成员现在叫什么"。
         * 万一将来重命名枚举成员，用枚举名会让判据悄悄失效（迁移永不命中）。
         */
        private const val LEGACY_DEFAULT_TRANSPORT = "WIRED"

        /** 上一版的 `bluetoothHandoffPolicy` 出厂值。见 [migrateBluetoothHandoffToDisableAdapter]。 */
        private const val LEGACY_DEFAULT_BT_HANDOFF = "DISCONNECT_LINK"

        /** 上一版的 `widthPhysicalMm` 出厂值。见 [migrateWidthPhysicalMmTo350]。 */
        private const val LEGACY_DEFAULT_WIDTH_MM = 200

        /**
         * `display_width_mm` 从未被写入时的哨兵值。
         *
         * 不能拿 `0` 当哨兵：`0` 会与"用户手动把滑杆拖到最小值 100 以下"混淆，
         * 也不在合法区间 `[MIN, MAX]` 里，一旦某个版本真写进 0 就会被误判成"没存过"。
         * 用 `Int.MIN_VALUE` 保证与任何合法的 mm 值都不相等。见 [migrateWidthPhysicalMmTo350]。
         */
        private const val NO_STORED_WIDTH_MM = Int.MIN_VALUE

        /**
         * 解析 HUD 缩放方式。未知值返回 null，由调用方回落默认值。
         *
         * 用 `entries` + 名字匹配而不是 `valueOf`：后者对坏值抛
         * `IllegalArgumentException`，而设置文件损坏**不能**让应用起不来。
         */
        private fun parseHudScaleMode(raw: String?): HudScaleMode? =
            raw?.let { name -> HudScaleMode.entries.firstOrNull { it.name == name } }

        private val BRANDING_KEYS = listOf(
            KEY_DEVICE_NAME,
            KEY_MANUFACTURER,
            KEY_MODEL,
            KEY_OEM_LABEL,
        )
    }
}
