package com.dbtool.carplay.api.developer

import com.dbtool.carplay.api.display.CarCustomDisplay
import com.dbtool.carplay.api.settings.CarSessionSettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把 [CarSessionSettings] 渲染成诊断包里的 `system/effective_settings.txt`。
 *
 * ## 为什么要落盘
 *
 * 这个工程最常缺的排查信息就是"**当时是什么配置**"。诊断包原本只有
 * `system_property.txt` / `package_status.txt` / `device_info.txt` / `logcat*`，
 * 一个设置字段都没有 ⇒ 只能从日志反推一部分（比如 `hotspot plan:` 能看出走的是
 * "探测复用"还是"自建"），而「方控开关 / 媒体卡片开关 / MFI 目标 / 有线无线 /
 * 显示参数 / 安全区」这些只能靠**问人**。落盘之后任何一份包都能自证配置。
 *
 * ## 为什么是纯函数、且单独一个文件
 *
 * 与 [com.dbtool.carplay.api.hotspot.CarAdbHotspotCommands] 同一个理由：
 * 这里的逻辑**有一个不能靠"上车试"发现的失败模式** —— 脱敏写漏了，就会把
 * `remoteMfiToken` / `manualHotspotPassphrase` **原样**写进一个**要上传到服务器**的
 * 文件里，而"漏了"这件事在功能上完全无感。所以它必须能被单测钉住。
 *
 * ## 三条硬规则
 *
 * 1. **绝不 `toString()`**：`CarSessionSettings` 是 data class，`toString()` 会把上面
 *    两个敏感字段原样写出来。必须逐字段写。
 * 2. **枚举一律 `.name`**：诊断文件不该因为 `:car:api` 的枚举新增了取值而编译失败。
 *    （"穷尽式 `when` 不写 `else`"是为了逼人显式决策**行为**，不适用于"把值照抄进日志"。）
 * 3. **新增设置字段要手动加进来** —— 这份清单是**白名单**，不是反射。宁可漏一个新字段，
 *    也不要因为"自动全量导出"把将来某个新的敏感字段带上天。
 *    **但"漏"不会静默**：`CarDiagnosticSettingsDumpTest` 里有一条反射写的白名单完整性测试，
 *    漏加会直接红（`linuxI2cPath` 就是这么被抓出来的 —— 人工按 grep 列字段时，
 *    含数字的名字会漏掉）。
 */
object CarDiagnosticSettingsDump {

    /** 敏感字段的占位文案。 */
    private const val NOT_SET = "(未设置)"

    /** WPA2-PSK 的下限，短于它 hostapd 会直接拒绝启动。 */
    private const val MIN_PASSPHRASE_LENGTH = 8

    fun render(
        settings: CarSessionSettings,
        clusterTheme: CarClusterThemeDiagnostics? = null,
    ): String = buildString {
        appendLine("# DBPlay 生效设置（脱敏）")
        appendLine("# 与 device_info.txt / collection_manifest.txt 里的 appVersion 配套看")
        appendLine("# 由 applySettings「先 save 再 updateSettings」保证：本文件 = 会话正在用的值")

        appendLine()
        appendLine("[身份]")
        appendLine("deviceName: ${settings.deviceName}")
        appendLine("manufacturer: ${settings.manufacturer}")
        appendLine("model: ${settings.model}")
        appendLine("oemLabel: ${settings.oemLabel}")
        appendLine("drivingSide: ${settings.drivingSide.name}")

        appendLine()
        appendLine("[连接]")
        appendLine("transport: ${settings.transport.name}")
        appendLine("isWireless: ${settings.isWireless}")
        appendLine("mfiTarget: ${settings.mfiTarget.name}")
        // ⚠️ 这行是**被白名单完整性测试抓出来的**：我最初按 grep `val [a-zA-Z]*:`
        // 人工列字段，而 `linuxI2cPath` 含数字 `2`，那条正则匹配不到它。
        // 它只在 `mfiTarget == I2C` 时有意义，但正是那种情况下的排查关键。
        appendLine("linuxI2cPath: ${settings.linuxI2cPath.ifBlank { NOT_SET }}")
        appendLine("remoteMfiServer: ${settings.remoteMfiServer.ifBlank { NOT_SET }}")
        appendLine("remoteMfiToken: ${maskedSecret(settings.remoteMfiToken)}")

        appendLine()
        appendLine("[热点]")
        appendLine("hotspotMode: ${settings.hotspotMode.name}")
        appendLine("manualHotspotSsid: ${settings.manualHotspotSsid}")
        appendLine("manualHotspotPassphrase: ${passphraseSummary(settings.manualHotspotPassphrase)}")
        appendLine("manualHotspotBand: ${settings.manualHotspotBand.name}")
        appendLine("manualHotspotChannel: ${settings.manualHotspotChannel}")
        // 2026-10-11（PATCH-0087）：**复用模式下唯一由用户指定、我们不许猜的那个值**。
        // 它直接决定发给 iPhone 的 `wirelessIP`，所以连不上时它是第一现场；
        // 空 = 未填 ⇒ 连接前会被拦下（见 `CarManualHotspotPrecheck.blocker`）。
        appendLine(
            "manualHotspotHostAddress: ${settings.manualHotspotHostAddress.ifBlank { NOT_SET }}",
        )
        appendLine("manualHotspotSecurity: ${settings.manualHotspotSecurity.name}")
        appendLine("adbHotspotInterface: ${settings.adbHotspotInterface.ifBlank { NOT_SET }}")
        // PATCH-0010：SAME_LAN 模式下这一个值就是"通告给 iPhone 的地址"，
        // 连不上时它是第一现场；空 = 自动读当前网络。
        appendLine("sameLanHostAddress: ${settings.sameLanHostAddress.ifBlank { NOT_SET }}")
        // PATCH-0010 修正：这两个值是"告诉 iPhone 去连哪张 Wi-Fi"。
        // 卡在「无线通道就绪」时，先看 SSID 是不是**车机当前所连的那张网**——
        // 曾经这里下发的是热点名 DBPlay，iPhone 找不到 ⇒ 永远不切 Wi-Fi。
        appendLine("sameLanWifiSsid: ${settings.sameLanWifiSsid.ifBlank { NOT_SET }}")
        appendLine("sameLanWifiPassphrase: ${passphraseSummary(settings.sameLanWifiPassphrase)}")
        appendLine("keepHotspotAlive: ${settings.keepHotspotAlive}")
        // 无线握手后怎么处理车机蓝牙。iPhone 下发 disableBluetooth 后若仍掉线，
        // 这一行是判断"定向断开有没有生效/是不是该换关适配器档"的第一手依据。
        appendLine("bluetoothHandoffPolicy: ${settings.bluetoothHandoffPolicy.name}")
        appendLine("enableBuiltInAdb: ${settings.enableBuiltInAdb}")
        appendLine("startupPerfCompile: ${settings.startupPerfCompile}")

        appendLine()
        appendLine("[显示]")
        appendLine("displayScaleTenths: ${settings.displayScaleTenths}（${settings.displayScaleLabel}）")
        appendLine("fps: ${settings.fps}")
        appendLine("widthPhysicalMm: ${settings.widthPhysicalMm}")
        appendLine("physicalSizeBasis: ${settings.physicalSizeBasis.name}")
        appendLine("customDisplayEnabled: ${settings.customDisplayEnabled}")
        appendLine("customDisplayWidthPx: ${settings.customDisplayWidthPx}")
        appendLine("customDisplayHeightPx: ${settings.customDisplayHeightPx}")
        appendLine("customDisplayDpi: ${settings.customDisplayDpi}")
        // 2026-10-08：仪表副屏 = 用户选屏（-1 = 未选）。现场排查"副屏挂错屏"
        // 时，第一眼要看的就是这个 id 与它是否对得上 `[窗口]` 段里的 displayId。
        appendLine("clusterDisplayId: ${settings.clusterDisplayId}")
        // 2026-10-11（PATCH-0088/0089 补登记）：副屏呈现三项 + 转向卡对齐。
        // 这几项**不参与**报给 iPhone 的声明（尺寸/安全区/帧率都不变），但现场排查
        // "仪表画面倒着 / 导航卡片不显示 / 转向卡在右边"时第一眼就要看它们。
        // ⚠️ 本文件是**白名单不是反射** ⇒ 漏加**不会有任何提示**，只会让人在诊断包里
        //    找不到（并由 `CarDiagnosticSettingsDumpTest` 的反射完整性用例兜住）。
        appendLine("clusterFlip180: ${settings.clusterFlip180}")
        // 2026-10-18：全屏地图模式守护灰度开关（默认 false）。
        // 现场排查“为什么没有 fullmap 日志 / 为什么还在直接抢屏”时先看这一行。
        appendLine("clusterModeGuardEnabled: ${settings.clusterModeGuardEnabled}")
        appendLine("clusterNavigationCard: ${settings.clusterNavigationCard}")
        appendLine("clusterMapLayoutTrial: ${settings.clusterMapLayoutTrial}")
        // 按**下标**存（0=左 / 1=右 / 2=顶），顺序由上游 `CarPlayClusterDisplay` 的
        // MANEUVER_LAYOUTS 决定。`:car:api` **不能** import `:shared` 的那张表
        // （依赖方向 `:shared` → `:car:api`，反向引用编译不过）⇒ 这里只落原始下标。
        appendLine("clusterAlignment: ${settings.clusterAlignment}")
        // ⚠️ 2026-10-12 补齐白名单（`c70e617` 引入该字段时漏了本行 ⇒ 白名单完整性用例一直红）。
        // 它本该决定 `CarClusterCruiseGate.canManualOpen()` 的一半条件 —— 但截至 PATCH-0100
        // 那个门**没有任何生产消费点**（详见 `CarClusterCruiseGate` 类注释）。
        // 仍然打出来：诊断包要如实反映用户设置，"这一项现在没人读"本身就是排查时要看到的信息。
        // 2026-10-13（PATCH-0114）：HUD 导航转向提示推送开关。
        // 放在 `[显示]` 这一段里与其它 `cluster*` 项同处 —— 它们都属于「仪表导航」分区。
        // 现场问「仪表上怎么没有转向卡片」时，这一行是第一现场：关着就什么都没推，
        // 而它与「桥没绑上」「iPhone 没下发」在仪表上长得一模一样。
        appendLine("hudGuidanceEnabled: ${settings.hudGuidanceEnabled}")
        // 2026-10-22：ECarX HUD **使能通道**（CB_VF_HUD_ActvReq 0/1），与上面两条 HUD 链无关。
        // 现场问「HUD 怎么不亮 / 为什么一直亮」时，先看这一行：关着就一个信号都没发；
        // 开着但通道不可用会在 xcertplay-hud 里留下具体卡在哪一步。
        appendLine("hudPushEnabled: ${settings.hudPushEnabled}")
        // 2026-10-22：HUD **地图画面**投屏（与上面的转向提示是两条链）。
        // 现场问「HUD 怎么没画面」时，这四行能一次说清：开关关着 / 没找到屏 /
        // 裁剪把画面裁没了。visibleOnHud 明确标 UNCONFIRMED —— 首帧不等于肉眼可见。
        appendLine("hudProjectionEnabled: ${settings.hudProjectionEnabled}")
        appendLine(
            "hudCrop: left=${settings.hudCrop.leftFraction} top=${settings.hudCrop.topFraction} " +
                "right=${settings.hudCrop.rightFraction} bottom=${settings.hudCrop.bottomFraction}",
        )
        appendLine("hudScaleMode: ${settings.hudScaleMode.name}")
        // P1 仪表主题（2026-10-17）：现场问"为什么仪表还是原样"时，先看这两行——
        // 皮肤开关默认关；开着但主题 id 不认识则主题文件缺失/版本过新。
        appendLine("clusterAppearanceMode: ${settings.clusterAppearanceMode.name}")
        appendLine("uiAppearanceMode: ${settings.uiAppearanceMode.name}")
        // P1.5 云端主题（2026-10-17）：主题到底来自内置还是云端、缓存里有几个、
        // 上次同步是什么时候。现场问"云端主题怎么没出来"时，这一行能区分
        // 「没网/没授权」与「缓存有但当前选的不是它」。
        appendLine("clusterThemeSource: ${clusterThemeLine(clusterTheme)}")
        if (settings.customDisplayEnabled) {
            // 实际发出去的是**换算并取偶后**的值，与用户填的可能差一点（例如填了奇数）。
            // 现场排查"为什么不是我要的分辨率"时，这一行就是答案。
            val resolved = CarCustomDisplay.resolve(
                widthPx = settings.customDisplayWidthPx,
                heightPx = settings.customDisplayHeightPx,
                dpi = settings.customDisplayDpi,
            )
            appendLine(
                "customDisplayResolved: ${resolved.widthPx}x${resolved.heightPx} px" +
                    " / ${resolved.widthMm}x${resolved.heightMm} mm（已取偶并夹取）",
            )
        }
        appendLine("hevc: ${settings.hevc}")
        appendLine("hevcSoftwareDecoder: ${settings.hevcSoftwareDecoder}")
        appendLine(
            "safeArea: x=${settings.safeArea.x} y=${settings.safeArea.y}" +
                " w=${settings.safeArea.width} h=${settings.safeArea.height}" +
                "（矩形·仪表副屏；w/h ≤ 0 = 未设置）",
        )
        appendLine(
            "mainSafeArea: x=${settings.mainSafeArea.x} y=${settings.mainSafeArea.y}" +
                " w=${settings.mainSafeArea.width} h=${settings.mainSafeArea.height}" +
                "（矩形·主屏；w/h ≤ 0 = 未设置，回落沉浸避让）",
        )
        appendLine("safeAreaDrawOutside: ${settings.safeAreaDrawOutside}")
        appendLine("clusterSafeAreaDrawOutside: ${settings.clusterSafeAreaDrawOutside}")
        appendLine("fpsOverlayEnabled: ${settings.fpsOverlayEnabled}")
        // 沉浸（画面延展到状态栏下）。主屏没画过 mainSafeArea 时，开着就会把
        // 实测状态栏高度报成 safeArea.top。排查主屏位置先看 mainSafeArea，再看这里。
        appendLine("immersiveVideo: ${settings.immersiveVideo}")

        appendLine()
        appendLine("[窗口]")
        appendLine("hideTopBar: ${settings.hideTopBar}")
        appendLine("hideBottomBar: ${settings.hideBottomBar}")
        appendLine("dockBar: ${settings.dockBar}")
        appendLine("dockAppOrder: ${settings.dockAppOrder.joinToString(",")}")
        appendLine("fullscreen: ${settings.fullscreen}")
        appendLine("localNavigationCardEnabled: ${settings.localNavigationCardEnabled}")
        // PATCH-0025：仪表地图投屏开关（会话级）。现场问「仪表没画面」时，
        // 先看这里是不是压根没开——它与「手机没建流」「窗口没建起来」是三种不同原因。
        appendLine("projectionEnabled: ${settings.projectionEnabled}")
        appendLine("immersiveNavigationEnabled: ${settings.immersiveNavigationEnabled}")
        appendLine("immersiveNavigationCardEnabled: ${settings.immersiveNavigationCardEnabled}")
        appendLine("immersiveGlassCardsEnabled: ${settings.immersiveGlassCardsEnabled}")
        appendLine("immersiveGlassColor: ${settings.immersiveGlassColor}")
        appendLine("immersiveGlassTransparency: ${settings.immersiveGlassTransparency}")
        appendLine("immersiveNavigationAlignment: ${settings.immersiveNavigationAlignment}")
        appendLine("immersiveNavigationSafeArea: ${settings.immersiveNavigationSafeArea}")
        appendLine("immersiveNavigationMapLayout: ${settings.immersiveNavigationMapLayout}")
        appendLine("immersiveNavigationDrawOutside: ${settings.immersiveNavigationDrawOutside}")
        // PATCH-0119（2026-10-14）：导航启动自动投屏（本次口径的主入口）。现场问
        // 「我开了导航，仪表怎么不投」时**先看这一行**：它关着就一帧都不会自动投，
        // 而"关着"与"没选屏 / 没连上 / iPhone 没建流"在仪表上长得一模一样。
        appendLine("clusterAutoShowOnRoute: ${settings.clusterAutoShowOnRoute}")

        appendLine()
        appendLine("[音频]")
        appendLine("advancedAudioChannelMapping: ${settings.advancedAudioChannelMapping}")
        appendLine("microphone: ${settings.microphone}")
        appendLine("higherQualityMedia: ${settings.higherQualityMedia} (experimental type102 LPCM; negotiated format is authoritative)")
        // 两条音频流逐条列出：现场最常问的是"到底哪条在生效"。
        // 0 = 按 usage 路由；1..20 = 显式指定的车机流号。
        appendLine("mediaAudioChannel: ${settings.mediaAudioChannel}")
        appendLine("navigationAudioChannel: ${settings.navigationAudioChannel}")
        appendLine("navigationAudioFocusEnabled: ${settings.navigationAudioFocusEnabled}")
        // 缓冲档位：排查「断断续续」时需要知道现场用的是哪一档，
        // 否则拿到日志也无法判断是不是档位没生效。
        appendLine("mediaAudioBufferMillis: ${settings.mediaAudioBufferMillis}")

        appendLine()
        appendLine("[功能开关]")
        appendLine("forwardSteeringWheelToCarPlay: ${settings.forwardSteeringWheelToCarPlay}")
        appendLine("reportMediaCardToCar: ${settings.reportMediaCardToCar}")
        appendLine("locationReporting: ${settings.locationReporting}")
        appendLine("autoStartOnBoot: ${settings.autoStartOnBoot}")
        appendLine("debugLogs: ${settings.debugLogs}")

        appendLine()
        appendLine("[调试]")
        // 调试反连隧道（2026-10-11 新增的设置项）。
        //
        // ⚠️ 这一段是**补齐白名单**：新字段加进 `CarSessionSettings` 时如果忘了写进本文件，
        // 现场排查会表现为"隧道到底开没开、连的哪台服务器"根本看不到，而且没人会想到是
        // 这个原因 —— 契约测试（`CarDiagnosticSettingsDumpTest` 的「白名单完整性」）
        // 就是为这种情况设的闸，它当时确实红了。
        appendLine("debugTunnelEnabled: ${settings.debugTunnelEnabled}")
        // ⚠️ 2026-10-11（PATCH-0076）：**服务器地址也不再落原值** —— 这份文件要上传服务器，
        //    而"公网跳板 IP + 端口 + 口令"三者凑齐就等于把调试入口交出去。
        //    只报"有没有 + 多长"，与密钥同一条口径（见 `CarSensitiveText`）。
        appendLine("debugTunnelHost: ${CarSensitiveText.hostLabel(settings.debugTunnelHost)}")
        appendLine("debugTunnelPort: ${settings.debugTunnelPort}")
        appendLine("debugTunnelLocalAdbPort: ${settings.debugTunnelLocalAdbPort}")
        // 隧道握手密钥与热点密码同级：**只报"有没有 + 多长"，绝不落原值**（本文件是白名单而不是反射，正是为了这个）。
        appendLine("debugTunnelSecret: ${CarSensitiveText.label(settings.debugTunnelSecret)}")
        // 2026-10-17（VIN 身份 P1）：token 同样是身份凭据，只报"有没有 + 多长"。
        // 新字段必须进白名单，否则既有的「白名单完整性」契约测试会直接红。
        appendLine("debugTunnelToken: ${CarSensitiveText.label(settings.debugTunnelToken)}")
    }

    /** `clusterThemeSource` 的取值：内置/云端 + 云端数量 + 上次同步时间。 */
    private fun clusterThemeLine(diagnostics: CarClusterThemeDiagnostics?): String {
        val source = if (diagnostics?.activeThemeFromCloud == true) "云端" else "内置"
        val count = diagnostics?.cloudThemeCount ?: 0
        val syncedAt = diagnostics?.lastSyncAtMillis ?: 0L
        val time = if (syncedAt > 0L) {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(syncedAt))
        } else {
            "从未"
        }
        return "$source（云端 $count 个，上次同步 $time）"
    }

    /**
     * 敏感值只报"有没有 + 多长"，**不报原值**。
     *
     * 口径已收到 [CarSensitiveText]（界面/日志/诊断包共用一份），这里只做转发，
     * 免得同一个规矩在三个文件里各写一遍、迟早漏一处。
     */
    private fun maskedSecret(secret: String): String = CarSensitiveText.label(secret)

    /**
     * 热点密码额外报"是不是出厂默认"与"够不够长"。
     *
     * ⚠️ 短于 8 位时 hostapd **直接拒绝启动**，现象是"iPhone 根本搜不到 SSID"，
     * 而日志里可能只有一句"热点已就绪"—— 这条警告就是为了让那种情况一眼可见。
     */
    private fun passphraseSummary(passphrase: String): String {
        if (passphrase.isBlank()) return NOT_SET
        return buildString {
            append("${passphrase.length} 字符")
            if (passphrase == CarSessionSettings.DEFAULT_HOTSPOT_PASSPHRASE) append("，出厂默认")
            if (passphrase.length < MIN_PASSPHRASE_LENGTH) {
                append(" ⚠️ 短于 $MIN_PASSPHRASE_LENGTH 位，WPA2 会失败")
            }
        }
    }
}
