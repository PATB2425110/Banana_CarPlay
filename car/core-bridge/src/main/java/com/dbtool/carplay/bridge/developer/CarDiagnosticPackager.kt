package com.dbtool.carplay.bridge.developer

import android.content.Context
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Process
import com.dbtool.carplay.api.developer.CarDiagnosticSettingsDump
import com.dbtool.carplay.api.developer.CarLogScope
import com.dbtool.carplay.api.settings.CarSessionSettings
import java.io.BufferedOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 采集 + 打包成诊断 zip。
 *
 * 包内结构与**服务端解析约定**严格对齐（见 `memory/REF-dbapi.md` §C）：
 * ```
 * DBPlay_Diagnostic/
 *   system/collection_manifest.txt   ← 服务端优先读这个拿系统信息
 *   system/system_property.txt       ← getprop 全量，服务端兜底解析
 *   system/package_status.txt        ← dumpsys package，服务端靠它找车机系统包版本
 *   system/device_info.txt
 *   system/effective_settings.txt    ← 生效设置（**我们对服务端契约的增量**，见下）
 *   system/environment.txt           ← 框架层环境快照（显示体系/内存/设置/网络/音频）
 *   logcat.txt / logcat_app.txt
 *   logcat_system.txt                ← 走 adb 通道的全系统日志（**含 system_server**）
 *   logcat_filtered.txt              ← 仪表/HUD/导航/投影 的**标签白名单**切片（2026-10-10 追加）
 *   logcat_system_filtered.txt       ← 同一张白名单，切的是 adb 通道那份全系统日志（PATCH-0112 追加）
 *   logs/<滚动日志>                  ← 应用日志 + 全系统滚动日志（PATCH-0095 起由 CarLogcatRecorder 现场滚动落盘）
 *   system/instrument_cluster.txt    ← 设备门控 + AVM 属性 + AirPlay 偏好安全摘要（2026-10-10 追加）
 *   system/airplay_tcp_evidence.txt  ← 用户主动启动时的 TCP/7000 SYN/RST 包头；未启动时明确标记未知
 *   system/update_state.txt          ← 后台静默更新状态（开关/上次检查/服务端表态/熔断，PATCH-0095 追加）
 *   dumpsys/window.txt               ← 窗口栈（走 adb 通道，含副屏窗口）
 *   dumpsys/display.txt              ← 显示服务（走 adb 通道）
 *   dumpsys/activity.txt             ← Activity 栈（走 adb 通道）
 *   dumpsys/media_codec.txt          ← 解码器实例（走 adb 通道）
 *   dumpsys/projection.txt           ← 投影（走 adb 通道）
 * ```
 * 服务端是按**路径后缀**匹配的（`name.endswith('system/collection_manifest.txt')`），
 * 所以外面这层根目录叫什么都可以——用 `DBPlay_Diagnostic` 是为了在后台
 * 一眼看出这个包是哪个 App 传的。**服务端忽略它不认识的路径**，所以
 * `effective_settings.txt` 是纯增量、不需要服务端配合（`REF-dbapi.md` §C）。
 *
 * ⚠️ **`logcat.txt` 只有本应用自己的行**：应用 uid 没有 `READ_LOGS`，
 * `logcat -b system` 也只回本进程。要看到 system_server / SurfaceFlinger /
 * WMS 那一侧，只能走 adb 通道（uid = `shell`，有 `DUMP` 权限）⇒ `logcat_system.txt`。
 * 详见 [CarSystemShell]。
 *
 * ⚠️ **为什么要把设置也打进包**：这个工程最常缺的排查信息就是"当时是什么配置"。
 * 光看 logcat 只能推断一部分（比如 `hotspot plan:` 能看出走的是探测还是自建），
 * 而"方控开关 / 媒体卡片开关 / MFI 目标 / 有线无线 / 显示参数"这些只能靠问。
 * 把生效设置落盘，之后任何一份诊断包都能自证配置。
 *
 * ⚠️ **脱敏**：`manualHotspotPassphrase` 与 `remoteMfiToken` **只写"是否已设置"与长度**，
 * 不写原值——包会被上传到服务器。热点密码额外报"是不是出厂默认"，
 * 因为 WPA2 要求至少 8 位，密码太短是"iPhone 连不上"的经典原因之一。
 *
 * ⚠️ 采集必须有**整体时间预算**：车机上 `dumpsys` / `logcat` 偶发卡死，
 * 没有预算就可能出现"点了上传，转圈五分钟"。
 */
internal class CarDiagnosticPackager(
    context: Context,
    private val deviceInfo: CarDeviceInfo,
    /**
     * 生效设置的读取器。用 `() ->` 而不是直接传值，是为了让每次采集都取到**当下**的值
     * （设置页改完「保存并重连」之后不需要重新构造本对象）。
     *
     * 传 `SharedPreferencesCarSettingsStore::load` 即可——`applySettings` 是
     * "先 save 再 updateSettings"，所以持久化值与生效值一致。
     */
    private val settingsProvider: () -> CarSessionSettings,
    /**
     * 一条**已经就绪**的高权限 shell（adb shell，uid = `shell`）；没有则返回 null。
     *
     * 刻意用 `() ->` 而不是直接传一个 `CarSystemShell`：一是取值必须发生在**采集那一刻**
     * （会话可能在这之后才起来），二是 **只 `peek()`、绝不 `ensure()`** ——
     * 采集诊断包不该顺手把车机的「ADB 调试」打开。契约见 [CarSystemShell]。
     */
    private val systemShell: () -> CarSystemShell?,
    /**
     * **已提权**（`su 0`）的那条 adb shell；没有 root 或通道未就绪时返回 null。
     *
     * ## 为什么诊断包需要 root 这一段（2026-10-10）
     *
     * 厂商那侧的 Wi-Fi / 热点事实用**应用 uid / shell uid 都查不下去**：
     * 厂商的 supplicant 配置 `Permission denied`、`dumpsys wifi` 的关键段读不全。
     * 只有 root 才读得到 `/vendor/etc/init/` 下那几份 init 脚本 ——
     * 它们决定"车机自带热点由谁拉起、谁负责拆、init 会不会把它重新拉起来"。
     *
     * ⚠️ 与 [systemShell] 同一条纪律：只 `peek()`、**绝不** `ensure()`。
     * 只有用户选了「自建热点（ADB）」那条路、通道已经提权到位时，这一段才有内容；
     * 否则「厂商 init 脚本」那一段会如实说明本次没有可用的 shell。
     */
    private val rootShell: () -> CarSystemShell? = { null },
    /**
     * **后台静默更新**的状态转储（PATCH-0095，2026-10-11）。
     *
     * ## 为什么不能塞进 `effective_settings.txt`
     *
     * 那个文件是 `CarDiagnosticSettingsDump.render(CarSessionSettings)` 的产物，
     * 契约就是"渲染 [CarSessionSettings] 的字段"（还有一条反射写的白名单完整性
     * 测试盯着它）。而「后台自动更新」这颗开关**刻意不放在
     * `CarSessionSettings`** 里 —— 它属于升级链路自己的 prefs
     * （见 `CarUpdatePrefs.silentUpdateEnabled`：恢复出厂设置不该把它清掉）。
     * 硬塞进去会破坏那条契约，所以单独出一份 `system/update_state.txt`。
     *
     * ## 它回答什么问题
     *
     * 2026-10-11 排查「静默更新到底生效没有」时，整条链路**客户端零日志**、
     * 开关也不在任何落盘文件里 —— 只能靠服务端数据库反推。有了这一格，
     * 下一份诊断包就能自证：车主开没开开关、上次检查是什么时候、
     * 服务端有没有点头（`autoUpdate`）、下没下、有没有被熔断。
     *
     * 默认实现返回空串（没接宿主的调用方行为不变：不生成该文件）。
     */
    private val updateStateProvider: () -> String = { "" },
    /**
     * 滚动日志记录器的**运行时快照**（PATCH-0095，2026-10-11）。
     *
     * ## 它回答什么
     *
     * 采集那一刻记录器**在不在跑**、累计多少行、滚动过几次、本次启动于何时。
     * 光看文件是分不出这两种情况的：
     * - "记录器从来没起来过"（⇒ 代码/权限问题，要修）；
     * - "起来了但那一刻刚 roll 过、当前文件还是空的"（⇒ 正常）。
     *
     * 每行直接进 `collection_manifest.txt`（与 `wifiDirect` / `instrumentCluster`
     * 同一节写法）。默认空表：没接宿主的调用方行为不变。
     */
    private val recorderStatusProvider: () -> List<String> = { emptyList() },
    /** Last explicitly requested root tcpdump capture; null means no capture has been requested. */
    private val airPlayTcpCaptureProvider: () -> AirPlayTcpCaptureResult? = { null },
) {

    private val appContext = context.applicationContext

    data class PackResult(
        val zipFile: File,
        /** 清单文本，界面回显与排障都用它。 */
        val manifest: String,
        val entryCount: Int,
    )

    /**
     * @param onStage 阶段文案回调（在调用线程触发）。
     */
    fun collectAndPack(
        scope: CarLogScope,
        appVersionName: String,
        includeClientIpv4: Boolean = false,
        onStage: (String) -> Unit,
    ): PackResult {
        val deadline = System.currentTimeMillis() + COLLECTION_BUDGET_MILLIS
        val root = File(appContext.cacheDir, STAGING_DIR_NAME)
        root.deleteRecursively()
        val systemDir = File(root, "system").apply { mkdirs() }

        val info = deviceInfo.systemInfo(forceRefresh = false)
        val deviceId = deviceInfo.deviceId()
        // briefBudget = false：诊断包本来就跑几十秒（见 COLLECTION_BUDGET_MILLIS），
        // 这一趟把 VIN 读全（含 logcat 兜底）是划算的，而且结果会进缓存。
        val vin = deviceInfo.vin(briefBudget = false)
        val collectedAt = TIMESTAMP_FORMAT.format(Date())

        // ---------------- 系统信息 ----------------
        onStage("正在读取系统属性…")
        val systemProperty = if (scope != CarLogScope.LOGCAT_ONLY) {
            CarShell.run("getprop", deadlineRemaining(deadline)).also {
                writeText(File(systemDir, "system_property.txt"), it)
            }
        } else {
            ""
        }

        val packageStatus = if (scope == CarLogScope.FULL) {
            onStage("正在读取车机系统包版本…")
            val text = buildString {
                for (pkg in FLYME_SYSTEM_PACKAGES) {
                    if (System.currentTimeMillis() > deadline) break
                    appendLine("### $pkg")
                    val output = CarShell.run("dumpsys package $pkg", deadlineRemaining(deadline))
                    // ⚠️ 先说明、再附原文 —— 顺序与"不丢证据"的理由：
                    // 本工程跑 shell 用的是**应用自己的 uid**，没有
                    // `android.permission.DUMP` ⇒ `dumpsys package` 必然被拒。
                    // 此前那段拒绝文本被**当成数据**写进文件，`collection_manifest.txt`
                    // 还把它的长度当"采集到多少字节"报出去，于是服务端拿不到任何包版本、
                    // 却看不出这是失败（2026-09-29 实车包：310 字节全是两行拒绝）。
                    // 这里保留原文（"到底是哪种失败"的证据不能丢），只在前面加一句说明。
                    when {
                        CarShell.looksLikePermissionDenial(output) -> appendLine(
                            "# ⚠️ 采集失败：dumpsys package 被系统拒绝（应用没有 " +
                                "android.permission.DUMP 权限）。原始输出附在下面。",
                        )
                        output.isBlank() -> appendLine(
                            "# ⚠️ 采集失败：没有任何输出（命令被拒、超时，或该包不存在）。",
                        )
                    }
                    appendLine(output)
                }
            }
            writeText(File(systemDir, "package_status.txt"), text)
            text
        } else {
            ""
        }

        val deviceInfoText = buildString {
            appendLine("product: DBPlay")
            appendLine("deviceId: $deviceId")
            appendLine("appVersion: $appVersionName")
            appendLine("android: ${info.androidVersion}")
            appendLine("systemVersion: ${info.systemVersion}")
            appendLine("brand/model: ${info.deviceModel}")
            appendLine("vin: $vin")
            appendLine("scope: ${scope.wireValue}")
            appendLine("collectedAt: $collectedAt")
            appendLine("buildManufacturer: ${Build.MANUFACTURER}")
            appendLine("buildModel: ${Build.MODEL}")
            appendLine("buildDisplay: ${Build.DISPLAY}")
            appendLine("sdkInt: ${Build.VERSION.SDK_INT}")
        }
        writeText(File(systemDir, "device_info.txt"), deviceInfoText)

        // ---------------- 生效设置 ----------------
        // 与 `system_property.txt` 同一个条件：`LOGCAT_ONLY` 时连 getprop 都不采，
        // 这里也不必采（那个档位就是"只要日志"）。
        var updateStateDump = ""
        if (scope != CarLogScope.LOGCAT_ONLY) {
            // `load()` 的契约是"任何异常都回落默认值、不抛"，所以这里不需要 try。
            // 渲染逻辑在 `:car:api` 的纯函数里 —— 脱敏漏了在功能上完全无感，
            // 必须能被单测钉住（见 `CarDiagnosticSettingsDumpTest`）。
            val settings = settingsProvider()
            // 云端主题状态（P1.5）：只读本地缓存，不联网；这里已经在采集的后台线程上。
            writeText(
                File(systemDir, "effective_settings.txt"),
                CarDiagnosticSettingsDump.render(settings),
            )
            // 后台静默更新的状态（PATCH-0095）。它与 `CarSessionSettings` **无关**
            // （开关刻意存在升级链路自己的 prefs 里），所以单独一份文件 ——
            // 理由与"为什么不塞进 effective_settings.txt"见 [updateStateProvider]。
            updateStateDump = runCatching { updateStateProvider() }.getOrDefault("")
            if (updateStateDump.isNotBlank()) {
                writeText(File(systemDir, UPDATE_STATE_FILE_NAME), updateStateDump)
            }
        }

        // ---------------- 环境快照（框架层，不需要任何权限） ----------------
        //
        // 这里放的都是 `getprop` / `logcat` **根本读不到**的东西：显示体系
        // （有哪几块屏、副屏 id/分辨率/刷新率）、内存/存储/电量/温度、系统设置表、
        // 网络与音频状态。全部走框架 API，在应用自己的 uid 下也拿得到
        // （见 [CarDiagnosticEnvironment]）。仪表副屏白屏类问题**没有这份就断不了案**：
        // 最后一轮实车包缺的正是"当时副屏在不在、id 是几、分辨率多少"。
        if (scope == CarLogScope.FULL) {
            onStage("正在读取显示/运行环境…")
            writeText(File(systemDir, "environment.txt"), CarDiagnosticEnvironment(appContext).render())
        }

        // ---------------- dumpsys（走 adb 通道，尽力而为） ----------------
        //
        // ⚠️ 应用自己的 uid 没有 `android.permission.DUMP`，`dumpsys` 必然被拒
        // （同 `package_status.txt` 的坑）；所以这一段**只在有 adb 通道时**才做，
        // 拿不到就整体跳过、清单里如实写明。
        //
        // ⚠️ **`dumpsys SurfaceFlinger` 刻意不采**：它的原始输出是 MB 级，
        // 在车机上会明显卡顿甚至拖垮采集预算。要 SurfaceFlinger 视角走 logcat
        // （`SurfaceFlinger` tag 会自己打关键行），比拉整份 dump 划算得多。
        val dumpsysSections: List<Pair<String, String>> = if (scope == CarLogScope.FULL) {
            systemShell()?.let { shell ->
                onStage("正在通过 adb 读取窗口/显示服务…")
                val dumpsysDir = File(root, "dumpsys")
                DUMPSYS_TARGETS.mapNotNull { (file, args) ->
                    if (System.currentTimeMillis() > deadline) return@mapNotNull null
                    val output = shell.run("dumpsys $args", deadlineRemaining(deadline))
                        ?: return@mapNotNull null
                    writeText(File(dumpsysDir, file), output)
                    file to output
                }
            }.orEmpty()
        } else {
            emptyList()
        }

        // ---------------- 日志 ----------------
        var logcatText = ""
        // 【2026-10-15】提为外层：F（热点简报合并）的判读段要读这两份文本，
        // 原来 `appLog` 只活在 `if` 块里，块外取不到（编译期就过不了）。
        var appLogText = ""
        if (scope != CarLogScope.SYSTEM_ONLY) {
            onStage("正在读取运行日志…")
            logcatText = CarShell.run(
                "logcat -d -v threadtime -b main,system,crash -t $LOGCAT_MAX_LINES",
                deadlineRemaining(deadline),
            )
            writeText(File(root, "logcat.txt"), logcatText)
        }
        if (scope == CarLogScope.FULL) {
            // 本应用自己的日志单独一份：现场最常看的就是"DBPlay 自己说了什么"，
            // 从几千行系统日志里翻太费时间。
            appLogText = CarShell.run(
                "logcat -d -v threadtime --pid=${Process.myPid()} -t $LOGCAT_MAX_LINES",
                deadlineRemaining(deadline),
            )
            writeText(File(root, "logcat_app.txt"), appLogText)
        }

        // ---------------- 仪表 / 导航专项（2026-10-10 追加） ----------------
        //
        // 起因（实测缺口）：`logcat_app.txt` 只取**尾部 8000 行**，而车机跑起来后
        // `DBPlay-CoverHttp`（封面请求）一条接一条 ⇒ 8000 行里可能一条仪表日志都不剩。
        // 上一份实车包里 `logcat_app.txt` 有 45 万字节，用 `XCExt.` / `carplay-cluster`
        // 去搜是 **0 命中**，仪表（副屏/白屏）类问题因此断不了案。
        //
        // 这一段补三件东西：
        //  1) `logcat_filtered.txt`：再抓一次**更大的尾部**，在应用内按标签白名单过滤
        //     （丢刷屏标签），保证仪表/HUD/导航/投影的行一定进包；
        //  2) `logs/<滚动日志>`：把 `CarLogcatRecorder` 现场滚动落盘的**应用日志 +
        //     全系统日志**打进包（PATCH-0095）—— 它们与 `logcat -d` 那种
        //     "只覆盖几秒的缓冲区快照"是**两套出口**，此前完全没采；
        //  3) `system/instrument_cluster.txt`：设备门控（`Build.DEVICE`）+ AVM 属性
        //     + AirPlay 偏好安全摘要（敏感值只报长度）。
        val instrumentClusterInfo: MutableList<String> = if (scope == CarLogScope.SYSTEM_ONLY) {
            mutableListOf()
        } else {
            collectInstrumentCluster(scope, root, systemDir, deadline, onStage).toMutableList()
        }

        // ---------------- 无线 / 热点专项 ----------------
        //
        // 采"复用 / 自建热点"排查真正会看的那几组系统事实：车机热点网口识别、
        // Ecarx 原厂热点与 CarPlay endpoint、内核网卡与 Wi-Fi 实现、wifi 服务转储。
        // **与日志范围无关**：SYSTEM_ONLY 也要采，它全是系统事实。
        //
        // ⚠️ 2026-10-13（PATCH-0113）：这里原来还采「Wi-Fi Direct 建组为什么失败」那一组
        // （`FEATURE_WIFI_DIRECT` / `WifiP2pManager` / `p2p0` / root 段 `ROOT_P2P_PROBES`），
        // 随 `CarHotspotMode.P2P_DIRECT` 一并移除 —— 保留的都是服务在用的那几条路。
        val wirelessInfo: List<String> = collectWirelessSnapshot(
            systemDir = systemDir,
            deadline = deadline,
            onStage = onStage,
            includeClientIpv4 = includeClientIpv4,
        )
        val airPlayTcpCapture = runCatching { airPlayTcpCaptureProvider() }.getOrNull()
        writeText(
            File(systemDir, "airplay_tcp_evidence.txt"),
            AirPlayTcpCaptureDiagnosticSummary.render(
                result = airPlayTcpCapture,
                includeClientAddresses = includeClientIpv4,
            ),
        )
        val wirelessInfoWithTcpCapture = wirelessInfo +
            "airPlayTcpEvidence: system/airplay_tcp_evidence.txt（TCP/7000 SYN/RST 头部；需提前启动限时采集）"

        // ---------------- F：热点简报合并取证（2026-10-15 新增） ----------------
        //
        // 「进队前合并」是 2026-10-14 上的改动（`refreshHotspotHint`），它唯一的
        // 现场留痕就是 `carplay-controller` 那行「热点简报：合并 N 次重复请求」。
        // 车友（小白）不会去 grep，所以这里**当场数一遍并把结论写进包**：
        // 直接回答"合并到底生效没有、最大合并掉几次"。
        //
        // ⚠️ 只用**已经从 `logcat -d` 里采到的那两份文本**（`logcat.txt` 是
        //    `-b main,system,crash`，`logcat_app.txt` 是本进程），不额外跑 `logcat`：
        //    1) 避免与上面那两次采集抢缓冲区、把证据冲掉；
        //    2) 不新增命令，越权/超时风险为零。
        // ⚠️ 与无线段同样「跟随模式」：`SYSTEM_ONLY` 时两份文本本就为空，
        //    这时不写文件、只在清单里如实说明，避免"空文件"被误读成"采到了但没内容"。
        val hotspotBriefingInfo: List<String> = if (scope == CarLogScope.SYSTEM_ONLY) {
            emptyList()
        } else {
            val summary = summariseHotspotBriefingMerges(
                listOf(
                    "logcat.txt" to logcatText,
                    "logcat_app.txt" to appLogText,
                ),
            )
            writeText(File(systemDir, "hotspot_briefing_marker.txt"), summary)
            listOf("hotspotBriefingMarker: system/hotspot_briefing_marker.txt（F 合并取证）")
        }

        // ---------------- 全系统日志（走 adb 通道，尽力而为） ----------------
        //
        // ⚠️ 上面那条 `logcat -b main,system,crash` 走的是**应用自己的 uid**，
        // 而 `READ_LOGS` 是 signature|privileged ⇒ 它实际只回本进程的行。
        // 2026-09-29 的实车包就是这样：`logcat.txt` 里只有本应用的 5 个 pid，
        // 于是"主线程某 300 ms 一行日志都没有"**无法区分**是应用内计算还是
        // 等 binder / 等 vsync —— 缺的正是 system_server 那一侧。
        // `shell` uid 有 `DUMP` 权限 ⇒ 走 adb 通道才有系统视角。
        //
        // 拿不到通道就返回 null，**如实**写进清单，不假装采到了（见 [CarSystemShell]）。
        val diagnosticShell = if (scope != CarLogScope.SYSTEM_ONLY) systemShell() else null
        if (scope != CarLogScope.SYSTEM_ONLY) {
            onStage("正在通过 adb 读取原厂进程崩溃与退出记录…")
            CarReceiverExitLogs.collect(diagnosticShell) { deadlineRemaining(deadline) }
                .forEach { (name, contents) -> writeText(File(root, name), contents) }
        }
        val systemLogcat: String? = if (scope != CarLogScope.SYSTEM_ONLY) {
            diagnosticShell?.let { shell ->
                onStage("正在通过 adb 读取全系统日志…")
                shell.run(
                    "logcat -d -v threadtime -b main,system,crash -t $LOGCAT_MAX_LINES",
                    deadlineRemaining(deadline),
                )?.also { writeText(File(root, "logcat_system.txt"), it) }
            }
        } else {
            null
        }

        // 同一张白名单，再切一刀**系统侧**（PATCH-0112）。
        //
        // ## 为什么必须切
        //
        // `logcat_filtered.txt` 抓的是 `--pid=<本进程>` ⇒ 它**只可能**有我们自己的行。
        // 而"HUD 连不上 / 卡片不出"这类问题的答案往往在**对面那一侧**：百度 hudbridge 服务
        // （`com.baidu.naviauto`）到底有没有收到我们的 transact、有没有拒绝；仪表窗口在
        // WMS 里挂上了没有。这些行过去在包里**一行都没有** —— 只能靠上车的次数去猜。
        //
        // ## 成本
        //
        // **不额外跑命令**：直接复用上面刚取到的文本在内存里过滤。
        // `logcat_system.txt` 已按 `LOGCAT_MAX_LINES` 截断 ⇒ 过滤结果天然有界。
        // 拿不到 adb 通道时 `systemLogcat` 为 null，这一段整体跳过（清单里那条
        // `systemLogcatLines: 0` 已经如实说明原因）。
        if (systemLogcat != null) {
            onStage("正在过滤全系统日志…")
            val systemLines = systemLogcat.lineSequence().filter { it.isNotBlank() }.toList()
            val keptSystemLines = systemLines.filter(::looksLikeInstrumentClusterLine)
            if (keptSystemLines.isNotEmpty()) {
                writeText(File(root, "logcat_system_filtered.txt"), keptSystemLines.joinToString("\n"))
            }
            instrumentClusterInfo += "systemLogcatFiltered: 抓 ${systemLines.size} 行 → 留 " +
                "${keptSystemLines.size} 行" +
                if (keptSystemLines.isEmpty()) {
                    "（⚠️ 全系统日志里没有一条仪表/HUD/导航/投影相关行）"
                } else {
                    "（logcat_system_filtered.txt）"
                }
        }

        // ---------------- 清单（服务端优先读它） ----------------
        val manifest = buildManifest(
            scope = scope,
            deviceId = deviceId,
            appVersionName = appVersionName,
            androidVersion = info.androidVersion,
            systemVersion = info.systemVersion,
            deviceModel = info.deviceModel,
            vin = vin,
            collectedAt = collectedAt,
            systemProperty = systemProperty,
            packageStatus = packageStatus,
            logcatText = logcatText,
            systemLogcat = systemLogcat,
            dumpsysSections = dumpsysSections,
            instrumentClusterInfo = instrumentClusterInfo,
            wirelessInfo = wirelessInfoWithTcpCapture,
            hotspotBriefingInfo = hotspotBriefingInfo,
            updateStateDump = updateStateDump,
            root = root,
        )
        writeText(File(systemDir, "collection_manifest.txt"), manifest)

        // ---------------- 打包 ----------------
        onStage("正在打包…")
        val zipFile = File(
            appContext.cacheDir,
            "DBPlay_Diagnostic_${appVersionName}_${FILE_STAMP_FORMAT.format(Date())}.zip",
        )
        val entryCount = zip(root, zipFile)
        // 只保留包本身：暂存目录里的明文日志没有留存价值，还占空间。
        root.deleteRecursively()

        return PackResult(zipFile = zipFile, manifest = manifest, entryCount = entryCount)
    }


    // ------------------------------------------------------------------
    // 仪表 / 导航专项采集（2026-10-10 追加）
    // ------------------------------------------------------------------

    /**
     * 采集仪表/导航排查真正需要、而原有段落**拿不到或会被刷掉**的三类数据。
     *
     * 为什么不直接复用 `logcat_app.txt`：它只取尾部 8000 行，而车机跑起来后
     * `DBPlay-CoverHttp`（封面请求）一条接一条 ⇒ 8000 行里可能一条仪表日志都不剩。
     * 实测：上一份实车包 `logcat_app.txt` 45 万字节，`XCExt.` / `carplay-cluster` **0 命中**。
     * 这里重新抓一次更大的尾部并在应用内过滤，输出体积可控（只留白名单行）。
     *
     * @return 每行一条说明，直接进 `collection_manifest.txt`。
     */
    private fun collectInstrumentCluster(
        scope: CarLogScope,
        root: File,
        systemDir: File,
        deadline: Long,
        onStage: (String) -> Unit,
    ): List<String> {
        val info = mutableListOf<String>()

        // ---- 1) 过滤版 logcat（仪表/HUD/导航/投影） ----
        onStage("正在采集仪表日志切片…")
        val rawLogcat = CarShell.run(
            "logcat -d -v threadtime --pid=${Process.myPid()} -t $FILTERED_LOGCAT_MAX_LINES",
            deadlineRemaining(deadline),
        )
        val rawLines = rawLogcat.lineSequence().filter { it.isNotBlank() }.toList()
        val keptLines = rawLines.filter(::looksLikeInstrumentClusterLine)
        if (keptLines.isNotEmpty()) {
            writeText(File(root, "logcat_filtered.txt"), keptLines.joinToString("\n"))
        }
        info += "filteredLogcat: 抓 ${rawLines.size} 行 → 留 ${keptLines.size} 行" +
            if (keptLines.isEmpty()) "（⚠️ 本次运行没有任何仪表/HUD/导航相关日志）" else ""

        // ---- 2) 滚动日志文件（会话日志 + 系统日志，PATCH-0095） ----
        //
        // ⚠️ 2026-10-11：这一段的语义**变了**，旧提示语是错的。
        //
        // 旧版找的是上游宿主 `CarPlayHostActivity.initializeSessionLog()` 写的
        // `logs/xcertplay.log`。而**那个 Activity 在当前产品里从未被实例化**
        // （launcher 是 `car/app` 的 `MainActivity : ComponentActivity`，
        // `BootReceiver` 也刻意不复用上游那个类）⇒ 文件**从不存在**，
        // 采集侧找不到是**必然**。而提示语写的是
        // "App 尚未起过 CarPlay 会话，或该文件不在预期目录" ——
        // 它把排查方向直接带偏（会让人去怀疑"会话到底起没起"）。
        //
        // 现在 `xcertplay.log` 这个名字由**我们自己**的 [CarLogcatRecorder]
        // 滚动落盘写（见该类 KDoc：它就是为解决"`logcat -d` 只覆盖 5 秒"加的），
        // 同时还有一份不过滤的系统日志。所以：
        //   · 找到 ⇒ 正常拷进包（含滚动出来的 `.1`/`.2`…，**最老在前**）；
        //   · 找不到 ⇒ 说明采集那一刻滚动记录器没在跑，**如实这么说**。
        val baseDirs = listOfNotNull(appContext.getExternalFilesDir(null), appContext.filesDir).distinct()
        var rollingLogFiles = 0
        for (base in baseDirs) {
            if (System.currentTimeMillis() > deadline) break
            val logsDir = File(base, "logs")
            if (!logsDir.isDirectory) continue
            // 内部存储与外部存储可能各有一份（设备挂载方式不同），文件名前缀区分开。
            val internal = base == appContext.filesDir
            for (name in ROLLING_LOG_NAMES) {
                for (source in RollingLogFileWriter.familyOf(logsDir, name, ROLLING_LOG_MAX_FILES)) {
                    if (System.currentTimeMillis() > deadline) break
                    // 🔴 §124（2026-10-12 修）：输出名必须带上**源文件自己的名字**（含序号）。
                    //
                    // `familyOf()` 返回的是**整族**（`xcertplay.log.3` → `.2` → `.1` → `xcertplay.log`），
                    // 而这里过去取的是**族名** `name` ⇒ 四条记录全落进同一个
                    // `logs/xcertplay.log`，后一条覆盖前一条，包里**只剩最后一个（当前文件）**，
                    // 中间整段永久丢失。
                    //
                    // 实证：`日志/1` 丢了 21:25:23–21:33:34（含仪表第一次挂载全过程）；
                    // `日志/3` 丢了 16:57–17:00:09，只能从 `logcat.txt` 拼回来。
                    // 详见 `REF-pitfalls.md` §124。
                    val entryName = if (internal) "internal-${source.name}" else source.name
                    val target = File(File(root, "logs"), entryName)
                    val copied = copyTail(source, target, ROLLING_LOG_TAIL_BYTES)
                    if (copied > 0L) {
                        rollingLogFiles++
                        // 带上 mtime：它一眼就能说明"记录器当时到底有没有在写"
                        // （一个几分钟没更新过的文件 = 记录器早停了）。
                        info += "rollingLog: ${source.absolutePath} (${source.length()} bytes，" +
                            "最后写入 ${LOG_MTIME_FORMAT.format(Date(source.lastModified()))}) → " +
                            "logs/$entryName (尾部 $copied bytes" +
                            (if (source.length() > copied) "，**已截断**" else "") + ")"
                    } else {
                        info += "rollingLog: ${source.absolutePath} 读取失败（被占用或权限）"
                    }
                }
            }
        }
        if (rollingLogFiles == 0) {
            info += "rollingLog: 未找到 ${ROLLING_LOG_NAMES.joinToString(" / ")}" +
                "（本产品的应用日志由 CarLogcatRecorder 滚动落盘生成；" +
                "找不到说明采集时它没在跑 —— **不是**「App 从未起过会话」）"
        }

        // ---- 2b) 记录器自身在不在跑（PATCH-0095） ----
        //
        // 与上面那一段分工不同：上面看的是**文件**（有内容 / mtime / 截断），
        // 这里看的是**进程**。两者一起才能区分"没起来过"与"起来了但刚 roll 过"。
        val recorderStatus = runCatching { recorderStatusProvider() }.getOrDefault(emptyList())
        if (recorderStatus.isEmpty()) {
            info += "rollingLogRecorder: （宿主未接入状态）"
        } else {
            recorderStatus.forEach { info += "rollingLogRecorder: $it" }
        }

        // ---- 3) 仪表专项快照（设备门控 + AVM 属性 + AirPlay 偏好安全摘要） ----
        onStage("正在采集仪表专项快照…")
        val avmProperties = AVM_PROPERTIES.joinToString("\n") { key ->
            val value = CarShell.run("getprop $key", deadlineRemaining(deadline)).trim()
            "$key: ${value.ifEmpty { "(空/不存在)" }}"
        }
        val airplayPrefs = appContext.getSharedPreferences(AIRPLAY_PREFS_NAME, Context.MODE_PRIVATE)
        val prefLines = buildString {
            appendLine("# $AIRPLAY_PREFS_NAME（安全摘要：敏感键只报是否设置与长度）")
            airplayPrefs.all.toSortedMap().forEach { (key, value) ->
                appendLine("$key: " + if (isSecretPrefKey(key)) describeSecret(value) else value.toString())
            }
            if (airplayPrefs.all.isEmpty()) appendLine("(空)")
        }
        writeText(
            File(systemDir, "instrument_cluster.txt"),
            buildString {
                appendLine("# 仪表 / 导航专项快照")
                appendLine("collectedAt: ${TIMESTAMP_FORMAT.format(Date())}")
                appendLine("scope: ${scope.wireValue}")
                appendLine()
                appendLine("# 设备门控（原版 AVM 等功能按此判断）")
                appendLine("Build.DEVICE: ${Build.DEVICE}")
                appendLine("Build.MANUFACTURER: ${Build.MANUFACTURER}")
                appendLine("Build.BRAND: ${Build.BRAND}")
                appendLine("Build.MODEL: ${Build.MODEL}")
                appendLine("Build.FINGERPRINT: ${Build.FINGERPRINT}")
                appendLine("Build.VERSION.SDK_INT: ${Build.VERSION.SDK_INT}")
                appendLine(
                    "avmNativePropertySupported(DEVICE==\"kx11_high\"): " +
                        "${Build.DEVICE == "kx11_high"}",
                )
                appendLine()
                appendLine("# AVM 属性（原版读 vendor.sys.avmon；本机实测另有 persist.sys.avmon）")
                appendLine(avmProperties)
                appendLine()
                appendLine(prefLines)
            },
        )
        info += "instrumentClusterSnapshot: system/instrument_cluster.txt"

        return info
    }

    /** 仪表/HUD/导航/投影相关行：先丢刷屏标签，再按标签白名单与关键字命中。 */
    private fun looksLikeInstrumentClusterLine(line: String): Boolean {
        if (NOISY_TAGS.any { line.contains(it) }) return false
        if (KEEP_TAGS.any { line.contains(it) }) return true
        val lower = line.lowercase(Locale.US)
        return KEEP_KEYWORDS_LOWER.any { lower.contains(it) }
    }

    /**
     * 无线 / 热点专项快照（PATCH-0067 起，2026-10-10；PATCH-0113 收窄）。
     *
     * ## 它回答什么问题
     *
     * 「复用车机热点」与「自建热点」的成败，全看**这台车机的 Wi-Fi / 热点栈长什么样**：
     *
     * | 侧 | 事实 | 用来判断 |
     * | --- | --- | --- |
     * | 应用 | `WifiManager` 的开关与 `wifiState` | 无线链路的前提在不在 |
     * | 网口 | 车机热点网口识别（`CarNetworkInterfaces.debugLine`） | 复用模式认的是哪张网 |
     * | 原厂 | Ecarx 原厂热点与 CarPlay endpoint | SDK 客户端 → 本地 OEM 网口的映射 |
     * | 内核 | 全部网卡 / `wlan0` / supplicant / `wificond` | 是哪一层没起来 |
     * | 服务 | `dumpsys wifi` 的关键字段 | 框架侧到底认没认那张 AP |
     *
     * ## 脱敏
     *
     * 只看"脚本本身"与"状态"，**不 `cat` 热点配置文件**（里面有 SSID / 口令，
     * 而诊断包要上传服务器）。需要那些内容时只在本地看，不进包。
     *
     * 全部**尽力而为**：单条命令失败只写一句原因，不影响整包采集。
     *
     * ⚠️ 2026-10-13（PATCH-0113）：原来这里还采「Wi-Fi Direct 建组为什么失败」那一组
     * （`FEATURE_WIFI_DIRECT` / `WifiP2pManager` 可用 / `p2p0` / root 段 `ROOT_P2P_PROBES`
     * + 自建 P2P 实测），随 `CarHotspotMode.P2P_DIRECT` 一并移除。
     */
    private fun collectWirelessSnapshot(
        systemDir: File,
        deadline: Long,
        onStage: (String) -> Unit,
        includeClientIpv4: Boolean,
    ): List<String> {
        onStage("正在采集无线 / 热点快照…")
        val info = mutableListOf<String>()
        val wifiManager = runCatching {
            appContext.getSystemService(WifiManager::class.java)
        }.getOrNull()
        val locationManager = runCatching {
            appContext.getSystemService(LocationManager::class.java)
        }.getOrNull()

        fun shell(command: String): String = runCatching {
            CarShell.run(command, deadlineRemaining(deadline)).trim()
        }.getOrElse { error -> "（执行失败：${error.javaClass.simpleName}）" }

        writeText(
            File(systemDir, "wireless_snapshot.txt"),
            buildString {
                appendLine("# 无线 / 热点专项快照")
                appendLine("collectedAt: ${TIMESTAMP_FORMAT.format(Date())}")
                appendLine()
                appendLine("# 应用侧能力事实")
                appendLine("WifiManager 可用: ${wifiManager != null}")
                appendLine(
                    "WifiManager.isWifiEnabled: " +
                        (runCatching { wifiManager?.isWifiEnabled }.getOrNull() ?: "（读不到）"),
                )
                appendLine(
                    "WifiManager.wifiState: " +
                        (runCatching { wifiManager?.wifiState }.getOrNull() ?: "（读不到）") +
                        "（1=DISABLED 2=ENABLING 3=ENABLED 0=DISABLING 4=UNKNOWN）",
                )
                appendLine()
                // 2026-10-11：复用模式的成败全看"车机热点网口认对没有"，而这条信息过去
                // 在包里根本没有（真车 08:57 把自建残留 192.168.176.1 当成车机热点通告出去，
                // 排查时无从判断"认的是哪张网"）。这里把识别结论与全部候选一次写清。
                appendLine(
                    "# 车机热点网口识别（复用模式用；'我方候选网段'里的地址不算车机热点，" +
                        "除非它落在车机固定网段里）",
                )
                appendLine(
                    runCatching {
                        com.dbtool.carplay.bridge.hotspot.CarNetworkInterfaces
                            .debugLine(appContext, emptyList())
                    }.getOrElse { error -> "车机热点网口: 读取失败（${error.javaClass.simpleName}）" },
                )
                // PATCH-0097/0098 的 route source 是 Android 本机选择的 source address，
                // 不是 T-Box/AP 网关。当前运行时选址决策与打包时重新读取快照分开记录，
                // 防止把两个时间点的结果误当成同一结论。
                appendLine()
                appendLine("# Ecarx 原厂热点与 CarPlay endpoint（地址角色分开记录）")
                appendLine(
                    runCatching {
                        val snap = com.dbtool.carplay.bridge.hotspot.EcarxHotspotReader(appContext).use { it.snapshot() }
                        val standardApState = com.dbtool.carplay.bridge.hotspot.CarHotspotEnabledReader
                            .readStandardWifiApState(appContext)
                        val configuredGateway = runCatching { settingsProvider().manualHotspotHostAddress }
                            .getOrNull()
                        val observation = com.dbtool.carplay.bridge.hotspot.EcarxHotspotSessionObservation.latest
                        buildString {
                            appendLine("Platform: MODEL=${Build.MODEL} / DEVICE=${Build.DEVICE} / sdkInt=${Build.VERSION.SDK_INT}")
                            appendLine("Snapshot time: ${TIMESTAMP_FORMAT.format(Date())}")
                            appendLine(
                                com.dbtool.carplay.bridge.hotspot.EcarxHotspotDiagnosticSummary.render(
                                    snapshot = snap,
                                    observation = observation,
                                    configuredGateway = configuredGateway,
                                    androidWifiApState = standardApState,
                                    includeClientIpv4 = includeClientIpv4,
                                    recentClientEvidence = com.dbtool.carplay.bridge.hotspot.CarHotspotClientWatcher.recentEvidence(),
                                ),
                            )
                            appendLine("Current local interface inventory: " + runCatching {
                                com.dbtool.carplay.bridge.hotspot.CarNetworkInterfaces.debugLine(appContext, emptyList())
                            }.getOrElse { "unavailable(${it.javaClass.simpleName})" })
                            appendLine("Read-only OEM route table (not itself proof an AP is enabled):")
                            val routes = runCatching { shell("ip -4 route show table all") }.getOrElse { "" }
                            val ecarxRoutes = routes.lineSequence()
                                .mapNotNull { com.dbtool.carplay.bridge.hotspot.EcarxClientRoute.parseIpv4Route(it) }
                                .filter { it.interfaceName.matches(Regex("eth[0-9]+(\\.[0-9]+)?")) }
                                .map { "  ${it.interfaceName} ${it.destination}/${it.prefix} table=${it.table}" }
                                .toList()
                            if (ecarxRoutes.isEmpty()) appendLine("  (no parsed eth* IPv4 route)")
                            else ecarxRoutes.forEach(::appendLine)
                            appendLine("Address note: 192.168.15.1, when shown in T-Box settings, is an external AP/gateway address; it is not expected to be assigned to this Android host.")
                        }.trimEnd()
                    }.getOrElse { error -> "Ecarx endpoint diagnostics: collection failed (${error.javaClass.simpleName})" },
                )
                // ⚠️ 上面这一行的 `selfAddresses` 传空表：诊断包拿不到 ADB 热点宿主的运行时状态
                // （那是会话期对象）。所以"我方自建地址"在包里显示 (无) —— 这是如实的：
                // 识别结论与全部候选地址都在同一行里，人一眼能看出"认的是哪张网口"。
                appendLine(
                    "LocationManager.isLocationEnabled: " +
                        (runCatching { locationManager?.isLocationEnabled }.getOrNull()
                            ?: "（读不到）"),
                )
                appendLine()
                appendLine("# 内核侧网卡状态")
                // 全部网卡：只看 wlan0 会漏掉"组接口"与厂商自己建出来的那张。
                appendLine("ip -o link show（全部网卡）:")
                appendLine(shell("ip -o link show"))
                appendLine(
                    "ip link show wlan0: " +
                        shell("ip link show wlan0").lineSequence().firstOrNull().orEmpty(),
                )
                appendLine()
                appendLine("# 车机 Wi-Fi 实现（IPCP / 芯片 / 各家服务）")
                appendLine("persist.service.wifi.ipcp: ${shell("getprop persist.service.wifi.ipcp")}")
                appendLine("ro.vendor.wlan.chip: ${shell("getprop ro.vendor.wlan.chip")}")
                appendLine("wifi.interface: ${shell("getprop wifi.interface")}")
                appendLine("init.svc.wpa_supplicant: ${shell("getprop init.svc.wpa_supplicant")}")
                appendLine("init.svc.wificond: ${shell("getprop init.svc.wificond")}")
                appendLine("persist.vendor.wifi.*: ${shell("getprop | grep -i -E 'vendor.wifi|vendor.wlan' | head -n 20")}")
                appendLine()
                appendLine("# 相关进程（supplicant / wificond 在不在跑）")
                appendLine(shell("ps -A | grep -i -E 'wpa|wificond' | head -n 20"))
                appendLine()
                appendLine("# wifi 服务转储（只 grep 关键字，避免整包过大）")
                appendLine(shell("dumpsys wifi | grep -i -E 'group|supplicant' | head -n 160"))
                appendLine()
                // ⚠️ 2026-10-13（PATCH-0113）：原来这里还有一段「root 侧证据」
                //    （`ROOT_P2P_PROBES` + 自建 P2P 实测），随 P2P_DIRECT 一并移除。
                //    `rootShell` 仍然要拿：下面的「厂商 init 脚本」优先用它。
                val root = rootShell()

                // ⚠️ 2026-10-11（PATCH-0075）：**厂商 init 脚本**（车主点名要看的那些）。
                //
                // 车主问的是 `ls -l /vendor/etc/init/udhcp*.rc hostapd*.rc wlan*.rc` ——
                // 这些文件回答的是"车机自带热点到底由谁拉起、谁负责拆、init 会不会把它重新拉起来"：
                //   · 有 `init.svc.<服务名>` ⇒ 可以用 `setprop ctl.stop <服务名>` 正经停掉它
                //     （比 `killall hostapd` 干净：init 不会把它当"挂了"再拉一次）；
                //   · `on property:` / `on boot` 的触发条件 ⇒ 车机什么时候会自己把热点拉回来
                //     （用户口径"热点随车机自启"就是这么来的）；
                //   · 有没有厂商 `udhcpd` 服务 ⇒ 我们自建热点能不能直接复用它。
                //
                // ⚠️ 这一批用 **root** 跑（没有 root 就退回 system shell）：`/vendor` 下的文件
                //    对普通应用不可读。**只看脚本本身**，不去 `cat` 热点配置文件
                //    （`softap.conf` / `wpa_supplicant.conf` 里有 SSID/口令，诊断包要上传，不采）。
                appendLine()
                appendLine("# 厂商 init 脚本（PATCH-0075；只读）")
                val initShell = root ?: systemShell()
                if (initShell == null) {
                    appendLine("本次既没有 root 也没有 adb 通道，这一段为空。")
                } else {
                    for ((title, command) in VENDOR_INIT_PROBES) {
                        appendLine("=== $title ===")
                        appendLine(
                            runCatching {
                                initShell.run(command, deadlineRemaining(deadline))
                                    ?.trim()
                                    ?.ifEmpty { "（无输出）" }
                                    ?: "（命令失败/被拒）"
                            }.getOrElse { error -> "（执行失败：${error.javaClass.simpleName}）" },
                        )
                    }
                }

                // 【新增 2026-10-15】DBPlay 自己的开机自启 / ADB 端口证据（**全只读**）。
                //
                // 为什么要它：这一批回答的是"开机这条路到底走没走到"——
                //   · `init.svc.dbplay-hotspot`：我们装的 init 服务被起过没有（空 = 从未启动）；
                //   · 三条 ADB 属性 + `init.svc.adbd`：5556 现在到底是瞬态口还是只写了持久口；
                //   · `ro.adb.secure` / `ro.debuggable`：那个无认证 root 口的暴露面（方案 §3.5）；
                //   · `/proc/net/tcp`：5556 有没有真在 LISTEN、绑的是 `127.0.0.1` 还是 `0.0.0.0`
                //     （这一段以前只能靠"包里没有"反推）；
                //   · 两个自启文件与开机脚本自己的日志：脚本装没装、装上了跑没跑。
                //
                // ⚠️ 纪律与厂商那一段完全一致：**只读**（`getprop` / `ls` / `cat` / `grep` /
                //    `dmesg`），不写 `/system`、不 `setprop`、不碰 `ctl.*`。没有 root 也没
                //    adb 通道时就如实写一句"这一段为空"。
                appendLine()
                appendLine("# DBPlay 自启与 ADB 端口证据（只读；5556 = 0x15B4）")
                val adbShell = root ?: systemShell()
                if (adbShell == null) {
                    appendLine("本次既没有 root 也没有 adb 通道，这一段为空。")
                } else {
                    for ((title, command) in DBPLAY_ADB_PROBES) {
                        appendLine("=== $title ===")
                        appendLine(
                            runCatching {
                                adbShell.run(command, deadlineRemaining(deadline))
                                    ?.trim()
                                    ?.ifEmpty { "（无输出）" }
                                    ?: "（命令失败/被拒）"
                            }.getOrElse { error -> "（执行失败：${error.javaClass.simpleName}）" },
                        )
                    }
                }
            },
        )
        info += "wifiDirectSnapshot: system/wifi_direct.txt"
        info += "clientIpv4OptIn: $includeClientIpv4 (SDK current peer address; not a DHCP lease record; MAC never included)"
        return info
    }

    /** 敏感键：白名单之外的兜底规则（名字里带这些词的都不落盘原值）。 */
    private fun isSecretPrefKey(key: String): Boolean {
        if (key in AIRPLAY_SECRET_KEYS) return true
        val lower = key.lowercase(Locale.US)
        return SECRET_KEY_PATTERN.any { lower.contains(it) }
    }

    /** 合并日志的模式串（`N` 是可变的合并次数）。 */
    private val HOTSPOT_MERGE_PATTERN = Regex("热点简报：合并 (\\d+) 次重复请求")

    /** F 取证：从两份日志文本里数「热点简报：合并 N 次重复请求」的出现次数与最大 N。 */
    private fun summariseHotspotBriefingMerges(logs: List<Pair<String, String>>): String {
        val hits = mutableListOf<Int>()
        var scan = 0
        for ((_, text) in logs) {
            if (text.isBlank()) continue
            scan++
            for (match in HOTSPOT_MERGE_PATTERN.findAll(text)) {
                match.groupValues.getOrNull(1)?.toIntOrNull()?.let { hits += it }
            }
        }
        val mergedRequests = hits.filter { it > 1 }.size
        val maxMerged = hits.maxOrNull() ?: 0
        return buildString {
            appendLine("扫描日志份数: $scan")
            appendLine("「热点简报：合并 N 次重复请求」总行数: ${hits.size}")
            appendLine("其中 N>1（真发生过合并）的行数: $mergedRequests")
            appendLine("单次最大合并次数: $maxMerged")
            appendLine(
                when {
                    hits.isEmpty() -> "# 判读：一次都没有这行 ⇒ F（进队前合并）未生效或该版本不含此代码"
                    mergedRequests == 0 -> "# 判读：有日志但 N 全为 1 ⇒ 合并从未触发（未连续快速改设置）"
                    else -> "# 判读：✅ F 生效（N>1 出现过 $mergedRequests 次）"
                },
            )
        }
    }

    /** 敏感值只报"是否设置 + 长度"，与 `CarDiagnosticSettingsDump` 的脱敏口径一致。 */
    private fun describeSecret(value: Any?): String {
        val text = value?.toString().orEmpty()
        return if (text.isEmpty()) "未设置" else "已设置(len=${text.length})"
    }

    /**
     * 从 `source` 尾部最多拷 `maxBytes` 字节到 `target`，并尽量对齐到行首。
     * @return 实际拷贝字节数；0 = 读取失败（不抛，交给调用方如实登记）。
     */
    private fun copyTail(source: File, target: File, maxBytes: Long): Long = runCatching {
        val start = (source.length() - maxBytes).coerceAtLeast(0L)
        target.parentFile?.mkdirs()
        RandomAccessFile(source, "r").use { input ->
            input.seek(start)
            if (start > 0L) {
                // 从行中间开始时，丢到第一个换行为止（最多再丢 64 KB，避免超长行卡住）。
                var skipped = 0
                while (skipped < 64 * 1024) {
                    val b = input.read()
                    if (b < 0 || b == '\n'.code) break
                    skipped++
                }
            }
            target.outputStream().buffered().use { output ->
                val buffer = ByteArray(64 * 1024)
                var copied = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    copied += read
                }
                copied
            }
        }
    }.getOrDefault(0L)

    private fun buildManifest(
        scope: CarLogScope,
        deviceId: String,
        appVersionName: String,
        androidVersion: String,
        systemVersion: String,
        deviceModel: String,
        vin: String,
        collectedAt: String,
        systemProperty: String,
        packageStatus: String,
        logcatText: String,
        /** null = 本次没有可用的 adb 通道，`logcat_system.txt` 不存在。 */
        systemLogcat: String?,
        /**
         * 已采到的 dumpsys 段（文件相对名 → 文本）。空 = 本次没有 adb 通道
         * 或全部被预算跳过，`dumpsys/` 目录不存在。
         */
        dumpsysSections: List<Pair<String, String>>,
        /**
         * 仪表 / 导航专项采集的结果说明（每行一条）。
         * 空 = 本次是 `SYSTEM_ONLY`（只要系统信息，不采日志）或专项采集整体失败。
         */
        instrumentClusterInfo: List<String>,
        /** 无线 / 热点专项采集的结果说明（每行一条）。 */
        wirelessInfo: List<String>,
        /**
         * F（进队前合并）取证的结果说明（每行一条）。空 = `SYSTEM_ONLY`
         * （两份日志文本本就未采），或本次没有可数的日志。
         */
        hotspotBriefingInfo: List<String>,
        /**
         * 后台静默更新的状态转储（PATCH-0095）。
         * 空 = 本次是 `LOGCAT_ONLY`，或宿主没接 [updateStateProvider]。
         */
        updateStateDump: String,
        root: File,
    ): String = buildString {
        appendLine("DBPlay 诊断包采集清单")
        appendLine("product: DBPlay")
        appendLine("deviceId: $deviceId")
        appendLine("appVersion: $appVersionName")
        appendLine("scope: ${scope.wireValue}（${scope.title}）")
        appendLine("collectedAt: $collectedAt")
        appendLine()
        appendLine("# 车辆信息（服务端解析这些行填后台列表）")
        appendLine("android: $androidVersion")
        appendLine("systemVersion: $systemVersion")
        appendLine("brand/model: $deviceModel")
        if (vin.isNotEmpty()) appendLine("vin: $vin")
        appendLine()
        appendLine("# 采集内容")
        appendLine("systemPropertyBytes: ${systemProperty.length}")
        appendLine("packageStatusBytes: ${packageStatus.length}")
        appendLine("logcatLines: ${logcatText.lineSequence().count()}")
        // ⚠️ 这一行必须**如实**反映有没有系统视角：`logcat.txt` 里只有本应用自己的行，
        // 服务端（和读包的人）不能从 `logcatLines` 非 0 就以为"日志是全的"。
        if (systemLogcat != null) {
            appendLine(
                "systemLogcatLines: ${systemLogcat.lineSequence().count()}" +
                    "（logcat_system.txt，走 adb 通道，含 system_server）",
            )
        } else {
            appendLine(
                "systemLogcatLines: 0（未采集：本次没有可用的 adb 通道，" +
                    "logcat.txt 只有本应用自己的日志）",
            )
        }
        if (dumpsysSections.isEmpty()) {
            appendLine("dumpsysSections: 0（未采集：本次没有可用的 adb 通道）")
        } else {
            appendLine("dumpsysSections: ${dumpsysSections.size}")
            dumpsysSections.forEach { (file, text) ->
                appendLine("  dumpsys/$file (${text.length} bytes)")
            }
        }
        // ⚠️ 仪表专项必须**逐行如实**报，不能只报"采了"：
        // 现场最需要区分的是"仪表根本没跑"与"跑了但没日志"。
        if (instrumentClusterInfo.isEmpty()) {
            appendLine("instrumentCluster: 0（未采集：本次为「仅系统信息」档）")
        } else {
            appendLine("instrumentCluster: ${instrumentClusterInfo.size}")
            instrumentClusterInfo.forEach { appendLine("  $it") }
        }
        // ---- F：热点简报合并取证（见 [summariseHotspotBriefingMerges]） ----
        if (hotspotBriefingInfo.isEmpty()) {
            appendLine("hotspotBriefingMarker: 未采集（本模式不采应用日志）")
        } else {
            hotspotBriefingInfo.forEach { appendLine(it) }
        }

        if (wirelessInfo.isEmpty()) {
            appendLine("wireless: 0（采集失败）")
        } else {
            appendLine("wireless: ${wirelessInfo.size}")
            wirelessInfo.forEach { appendLine("  $it") }
        }
        // 后台静默更新（PATCH-0095）：2026-10-11 排查"静默更新到底生效没有"时，
        // 整条链路客户端零日志、开关也不在任何落盘文件里，只能靠服务端反推。
        // 这里显式报一行，下一份包就能自证。
        if (updateStateDump.isBlank()) {
            appendLine("updateState: 0（未采集：本次为「仅日志」档，或宿主未接入）")
        } else {
            appendLine("updateState: system/$UPDATE_STATE_FILE_NAME（${updateStateDump.length} bytes）")
        }
        appendLine("entries:")
        root.walkTopDown()
            .filter { it.isFile }
            .sortedBy { it.relativeTo(root).path }
            .forEach { appendLine("  ${it.relativeTo(root).path} (${it.length()} bytes)") }
    }

    private fun zip(sourceDir: File, target: File): Int {
        var count = 0
        ZipOutputStream(BufferedOutputStream(target.outputStream())).use { zip ->
            sourceDir.walkTopDown()
                .filter { it.isFile }
                .sortedBy { it.relativeTo(sourceDir).path }
                .forEach { file ->
                    val entryName = "$ARCHIVE_ROOT/${file.relativeTo(sourceDir).path}"
                        .replace(File.separatorChar, '/')
                    zip.putNextEntry(ZipEntry(entryName))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                    count++
                }
        }
        return count
    }

    private fun writeText(file: File, text: String) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(text)
        }
    }

    /** 给单条命令的剩余预算，最少留 1 秒，避免传 0/负数让命令直接超时。 */
    private fun deadlineRemaining(deadline: Long): Long =
        (deadline - System.currentTimeMillis()).coerceAtLeast(1_000L)

    private companion object {
        const val STAGING_DIR_NAME = "diagnostic_staging"
        const val ARCHIVE_ROOT = "DBPlay_Diagnostic"

        /** 后台静默更新状态的文件名（相对 `system/`）。见 [updateStateProvider]。 */
        const val UPDATE_STATE_FILE_NAME = "update_state.txt"

        /** 整体采集预算：超了就跳过剩余命令，宁可少采也不能卡住。 */
        const val COLLECTION_BUDGET_MILLIS = 90_000L

        const val LOGCAT_MAX_LINES = 8_000

        /**
         * 过滤版 logcat 的抓取行数（抓完在应用内过滤，最终输出远小于此）。
         *
         * ⚠️ 必须显著大于 [LOGCAT_MAX_LINES]：刷屏标签（`DBPlay-CoverHttp`）会把
         * 小尾巴整个占满，仪表日志根本进不来（见 [collectInstrumentCluster] 的说明）。
         */
        const val FILTERED_LOGCAT_MAX_LINES = 40_000

        /**
         * 要拷进包的滚动日志（PATCH-0095）。
         *
         * - [CarLogcatRecorder.APP_LOG_NAME]：本 App 自己的行（**应用日志**，
         *   由 `CarLogcatRecorder` 写；旧版找的是上游那个从不存在的同名文件）；
         * - [CarLogcatRecorder.SYSTEM_LOG_NAME]：不过滤的全系统日志，
         *   补上 `logcat -d` 只覆盖几秒的缺口。
         */
        val ROLLING_LOG_NAMES = listOf(
            CarLogcatRecorder.APP_LOG_NAME,
            CarLogcatRecorder.SYSTEM_LOG_NAME,
        )

        /** 每族日志最多找几份（含当前那一份，与写入端 `maxFiles` 一致）。 */
        val ROLLING_LOG_MAX_FILES = CarLogcatRecorder.DEFAULT_MAX_FILES

        /** 单个滚动日志文件最多拷进包的字节数（取尾部；超出会在清单里标「已截断」）。 */
        const val ROLLING_LOG_TAIL_BYTES = 4L * 1024 * 1024

        /** 滚动日志的"最后写入时刻"格式（清单里报它，用来判断记录器有没有在写）。 */
        val LOG_MTIME_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

        /** 上游 `AirPlayPersistence` 的偏好文件名（只读，不改）。 */
        const val AIRPLAY_PREFS_NAME = "xcertplay_airplay"

        /** AVM 系统属性：原版读 `vendor.sys.avmon`，CS11 实测只有 `persist.sys.avmon`。 */
        val AVM_PROPERTIES = listOf("vendor.sys.avmon", "persist.sys.avmon")

        /**
         * **厂商 init 脚本**的只读探针（PATCH-0075，2026-10-11）。
         *
         * ## 它回答什么问题
         *
         * 车主点名要看 `/vendor/etc/init/udhcp*.rc` / `hostapd*.rc` / `wlan*.rc`。
         * 这些文件是"车机自带热点由谁拉起"的**唯一权威答案**，直接决定我们该怎么处置它：
         *
         * | 想知道的 | 从哪看出来 |
         * | --- | --- |
         * | 热点是不是 init 服务（能不能 `ctl.stop`） | `service <名字> …` + `init.svc.<名字>` 属性 |
         * | init 会不会把它重新拉起来 | `oneshot` / `disabled` / `class` / 有没有 `on property:` 触发器 |
         * | 车机何时自己把热点拉回来 | `on boot` / `on property:persist.…=1` 之类的触发条件 |
         * | 有没有厂商 `udhcpd` 可复用 | `service … /…/udhcpd` 那一行 |
         *
         * ## 纪律
         *
         * 全部**只读**：`ls` / `cat` 脚本本身。**不 cat 热点配置文件**
         * （`softap.conf`/`wpa_supplicant.conf` 里是 SSID/口令，而诊断包要上传服务器）——
         * 需要那些内容时只在本地看，不进包。
         */
        val VENDOR_INIT_PROBES = listOf(
            // ⚠️ 这三个是**车主实测点名**的（2026-10-11 车机上 `ls -l /vendor/etc/init/` 确实有）：
            //    `hostapd.android.rc`（框架 SoftAP 那份）、`hostapdwlan.rc`（厂商 wlan 上那张 AP）、
            //    `udhcpdwlan.rc`（厂商 AP 的 DHCP 服务）。
            //    因为文件确实存在，这里**按名字逐个 cat**（不靠通配符）—— 通配符在不同 shell 下
            //    行为不一致（车主那次 `wlan*.rc` 就没展开成文件而报了 No such file）。
            "车主点名的三个脚本（按名字逐个 cat）" to
                "for f in /vendor/etc/init/hostapd.android.rc /vendor/etc/init/hostapdwlan.rc " +
                    "/vendor/etc/init/udhcpdwlan.rc; do echo \"=== \$f ===\"; cat \"\$f\" 2>&1; " +
                    "done | head -n 300",
            // 车主实测那份 `ls -l` 里另外四份同样值钱：`vendor.ecarx.xma.connectivity`
            // （亿咖通的连通性服务 —— 很可能就是拉起/收掉 Wi-Fi 与 AP 的那一层）、
            // `android.hardware.wifi@1.0-service`（用的是哪套 Wi-Fi HAL）、
            // `com.qualcomm.qti.wifidisplayhal`（WFD，与无线组网同源）、
            // `manifest_sku.rc`（SKU 差异：不同车型启用的服务不同）。
            "厂商侧另外四份相关脚本" to
                "for f in /vendor/etc/init/vendor.ecarx.xma.connectivity@1.0-service.rc " +
                    "/vendor/etc/init/android.hardware.wifi@1.0-service.rc " +
                    "/vendor/etc/init/com.qualcomm.qti.wifidisplayhal@1.0-service.rc " +
                    "/vendor/etc/init/manifest_sku.rc; do echo \"=== \$f ===\"; cat \"\$f\" 2>&1; " +
                    "done | head -n 240",
            "热点相关进程与它们的 init.svc 状态" to
                "ps -A 2>/dev/null | grep -i -E 'hostapd|udhcpd|dnsmasq' | head -n 20; " +
                    "getprop | grep -i -E 'init\\.svc\\.(hostapd|udhcp|wlan|netmgrd)'",
            // 判"车机自带热点现在开着没有、谁在管它"：框架侧（SoftAP/tethering）与 init 侧要一起看，
            // 因为两种实现并存：`hostapd.android.rc`（框架 SoftAP 那份）与
            // `hostapdwlan.rc`/`udhcpdwlan.rc`（厂商自己那份）。
            "框架侧的 AP / tethering 状态" to
                "dumpsys wifi 2>/dev/null | grep -i -E 'softap|ap state|apstate|apinterface|tetherable' | head -n 60; " +
                    "echo '--- dumpsys tethering ---'; " +
                    "dumpsys tethering 2>/dev/null | grep -i -E 'wifi|softap|ap ' | head -n 60",
            "厂商 init 目录全量清单（/vendor/etc/init）" to
                "ls -l /vendor/etc/init/ 2>&1 | head -n 160",
            "厂商 init 里与 wlan/wifi/hostapd/udhcp 相关的文件" to
                "ls -l /vendor/etc/init/ 2>&1 | grep -i -E " +
                    "'wlan|wifi|hostapd|udhcp|dhcp|softap|tether|ecarx|connectivity|sku'",
            "这些脚本的内容（只 cat 脚本本身）" to
                "for f in /vendor/etc/init/*.rc; do " +
                    "case \"\$f\" in *wlan*|*wifi*|*hostapd*|*udhcp*|*dhcp*|*softap*|*tether*|*ecarx*|*connectivity*) " +
                    "echo \"=== \$f ===\"; cat \"\$f\";; esac; done 2>&1 | head -n 400",
            "系统侧 init 里同名的脚本（framework 那份）" to
                "for f in /system/etc/init/*.rc /system/etc/init/*.sh; do " +
                    "case \"\$f\" in *wlan*|*wifi*|*hostapd*|*udhcp*|*dhcp*|*softap*|*tether*|*supplicant*) " +
                    "echo \"=== \$f ===\"; cat \"\$f\";; esac; done 2>&1 | head -n 240",
            "相关二进制在不在" to
                "ls -l /vendor/bin/hw/hostapd /vendor/bin/hw/wpa_supplicant " +
                    "/system/bin/udhcpd /system/bin/dhcpcd /vendor/bin/udhcpd 2>&1 | head -n 20",
            "热点相关配置文件**只列名**（不 cat 内容）" to
                "ls -l /data/misc/wifi/ /data/vendor/wifi/ /data/vendor/wifi/wpa/ 2>&1 | head -n 80",
        )

        /**
         * DBPlay 自启 / ADB 5556 的**现场取证**（2026-10-15 新增，**全只读**）。
         *
         * ## 这一批回答什么
         *
         * `VENDOR_INIT_PROBES` 那一批讲的是"车机自己怎么管热点/ADB"，这一批讲的是
         * "**我们**那条开机路走没走到"：
         *
         * | 探针 | 判什么 |
         * | --- | --- |
         * | `init.svc.dbplay-hotspot` | 我们装的 init 服务被起过没有（**空 = 从未启动**） |
         * | 三条 ADB 属性 + `init.svc.adbd` | 5556 是瞬态口还是只写了持久口、adbd 在不在跑 |
         * | `ro.adb.secure` / `ro.debuggable` | 那个无认证 root 口的暴露面（方案 §3.5.2） |
         * | `/proc/net/tcp` | 5556 **有没有真在 LISTEN、绑在哪** |
         * | 自启文件 + 开机脚本日志 | 脚本装没装、装上了跑没跑 |
         *
         * ## `binding` 那一列为什么必须打表头
         *
         * ⚠️ 5556 = **0x15B4**。`/proc/net/tcp` 的第二列是**本地地址**：
         *
         * - `0100007F:15B4` = `127.0.0.1:5556`（只有本机可达）；
         * - `00000000:15B4` = `0.0.0.0:5556`（**所有网卡可达**，含自建热点 `192.168.175.1`）。
         *
         * 这一列直接决定暴露面，所以必须把**表头一起**打出来（缺了表头，看包的人
         * 得靠记忆数第几列，很容易把"状态列"当成"地址列"）。
         *
         * ## 纪律
         *
         * 全部**只读**：`getprop` / `cat` / `ls` / `grep` / `dmesg`。**不写 `/system`、不
         * `setprop`、不碰 `ctl.*`** —— 诊断包只是取证，任何写动作都会污染现场
         *（方案 §5.7："新增调用点一律不进 `ensure()`"）。
         */
        val DBPLAY_ADB_PROBES = listOf(
            "init 服务状态（空=从未启动）" to
                "echo \"init.svc.dbplay-hotspot=[\$(getprop init.svc.dbplay-hotspot)]\" ; " +
                    "echo \"ro.boottime.dbplay-hotspot=[\$(getprop ro.boottime.dbplay-hotspot)]\" ; " +
                    "echo \"sys.boot_completed=[\$(getprop sys.boot_completed)]\"",
            "ADB 端口属性与 adbd 状态" to
                "echo \"service.adb.tcp.port=[\$(getprop service.adb.tcp.port)]\" ; " +
                    "echo \"persist.adb.tcp.port=[\$(getprop persist.adb.tcp.port)]\" ; " +
                    "echo \"init.svc.adbd=[\$(getprop init.svc.adbd)]\" ; " +
                    "echo \"dbplay.hotspot.boot=[\$(getprop persist.dbplay.hotspot.boot)]\" ; " +
                    "echo \"dbplay.adb.boot=[\$(getprop persist.dbplay.adb.boot)]\" ; " +
                    "echo \"/data/property 原始值=[\$(cat /data/property/persist.adb.tcp.port 2>/dev/null)]\"",
            "ADB 认证姿态（ro.adb.secure 缺失 = 不做主机认证）" to
                "echo \"ro.adb.secure=[\$(getprop ro.adb.secure)]\" ; " +
                    "echo \"ro.debuggable=[\$(getprop ro.debuggable)]\" ; " +
                    "echo \"ro.build.type=[\$(getprop ro.build.type)]\" ; " +
                    "echo \"service.adb.root=[\$(getprop service.adb.root)]\"",
            "5556 是否真在 LISTEN + 绑在哪（第二列=本地地址，0A=LISTEN）" to
                "head -n 1 /proc/net/tcp ; cat /proc/net/tcp /proc/net/tcp6 2>/dev/null " +
                    "| grep -i ':15B4' || echo \"（没有 5556 条目）\"",
            "两个自启文件还在不在" to
                "ls -lZ /system/bin/dbplay-hotspot.sh /system/etc/init/dbplay-hotspot.rc 2>&1",
            "开机脚本自己的执行记录" to
                "ls -l /data/local/tmp/dbplay-hotspot-boot.log 2>&1 ; " +
                    "tail -n 40 /data/local/tmp/dbplay-hotspot-boot.log 2>/dev/null",
            "谁在开机改 adb 属性（写入者证据）" to
                "dmesg | grep -i -E 'dbplay|adbd|persist.adb' | tail -n 40 ; " +
                    "grep -rn 'persist.adb.tcp.port' /system/etc/init /vendor/etc/init 2>/dev/null | head -n 40",
            // 【新增 2026-10-15】G3 补盲：上面那条只搜 dmesg + init rc，
            // 若覆写者是个 Java/系统服务，这两处都抓不到。补采
            //   · `-b events`（属性变更事件 am_prop / 各服务 boot 事件）
            //   · 开机段全系统日志（`logcat -b all -d` 里 grep 三个 ADB 属性名）
            // 这是诊断包**唯一**能留下"谁把口钉回 5555"的地方，不再依赖车友手跑 adb。
            "谁在开机改 adb 属性（事件流 + 开机段，G3 补盲）" to
                "logcat -b events -d -v threadtime 2>/dev/null | " +
                    "grep -i -E 'prop|adb|boot' | tail -n 60 ; " +
                    "echo '--- 开机段全系统日志（搜 5555 / 5556 / tcp.port）---' ; " +
                    "logcat -b all -d -v threadtime 2>/dev/null | " +
                    "grep -i -E 'tcp\\.port|adb.*555[0-9]|555[56]' | tail -n 80",
        )

        /** 标签白名单（区分大小写的完整标签片段）。 */
        val KEEP_TAGS = listOf(
            "XCExt.",                 // 诊断适配器统一前缀（仪表/HUD/热点/蓝牙…）
            "carplay-cluster",
            "CarPlay-Navigation",
            // PATCH-0112：**HUD 发帧的唯一出口**（`GeelyHudBridge`）。
            //
            // 它本来也能靠下面的关键字 `hud` 命中（整行小写后含 `geelyhudadapter`），
            // 但那是**巧合**：谁精简一下关键字白名单，HUD 的成败留痕就整段消失了，
            // 而"看不到"和"HUD 没跑"在包里长得一模一样。显式列出来，把这份依赖写明。
            "GeelyHudAdapter",
            "CarPlay-NowPlaying",
            "xcertplay",
            "DBPlay-",
            // ⚠️ 2026-10-11（PATCH-0072）：**框架侧的 Wi-Fi / 热点**也必须留下。
            //
            // 起因：连采两份包，`logcat_system.txt` 里 `softap|tether|wlan0` 这些
            // framework 标签**命中数全是 0**。而"车机热点到底认没认、被谁顶掉"的答案
            // 恰恰只在框架的 Wi-Fi 服务日志里（它会把原因写出来）。
            // 没有这几行，现场就只能靠上车的次数去猜 —— 而装车一次很贵。
            //
            // ⚠️ 2026-10-13（PATCH-0112）**订正一处口径**：当时把"命中数 0"归因于
            // "被白名单滤掉了"，其实**站不住** —— 这张白名单过去只作用于
            // `logcat_filtered.txt`（`--pid=<本进程>`），而 `logcat_system.txt` 是**原样落盘、
            // 从不过滤**的。真正的原因是那 8000 行尾巴里没轮到它们。
            // 现在白名单**同时**作用于 `logcat_system_filtered.txt`（新增，见 [collectAndPack]），
            // 这几条 framework 标签才真正开始"把系统侧关键行捞出来"。
            //
            // ⚠️ 2026-10-13（PATCH-0113）：原来是 `WifiP2p|p2p|softap|…` 一起加的，
            // 那是为了查「Wi-Fi Direct 建组为什么回 BUSY」。该模式已整体移除，
            // 所以 `WifiP2p` 也一并去掉；留下的都是**车机热点**这条链路要用的。
            "WifiControllerIPCP",
            "WifiApState",
            "SoftAp",
            "ClientModeImpl",
            "wificond",
        )

        /** 关键字白名单（大小写不敏感；用于没走 `XCExt.` 前缀的日志）。 */
        val KEEP_KEYWORDS_LOWER = listOf(
            "cluster", "hud", "avm", "projection", "airplay", "clusterdisplay", "仪表", "导航",
            // 同 [KEEP_TAGS] 的理由：车机热点这条链路的 framework 日志。
            // 关键字是小写比较（调用点会 `lowercase`），所以这里一律写小写。
            "softap", "tether", "wpa_supplicant", "wifi_ap_state", "wifi_state_changed",
        )

        /** 刷屏标签：先丢。否则整份切片会被它们填满（仪表日志被挤出去）。 */
        val NOISY_TAGS = listOf(
            "DBPlay-CoverHttp",
        )

        /** 已知敏感偏好键（其余交给 [SECRET_KEY_PATTERN] 兜底）。 */
        val AIRPLAY_SECRET_KEYS = setOf(
            "identity_private",
            "identity_public",
            "pairing_id",
            "pairing_ids",
            "lockdown_host_id",
            "lockdown_system_buid",
            "lockdown_wifi_mac",
            "lockdown_device_public",
            "lockdown_device_cert",
            "lockdown_host_private",
            "lockdown_host_cert",
            "lockdown_root_private",
            "lockdown_root_cert",
            "manual_hotspot_passphrase",
        )

        /** 名字里带这些词的键一律不落盘原值（兜底，防将来新增敏感键漏脱敏）。 */
        val SECRET_KEY_PATTERN = listOf(
            "private", "passphrase", "password", "token", "cert", "secret", "key",
        )

        val FLYME_SYSTEM_PACKAGES = listOf("com.flyme.auto.launcher", "com.flyme.auto.systemui")

        /**
         * 走 adb 通道采集的 `dumpsys` 目标（文件名 → 服务名/参数）。
         *
         * 选取原则：**排查车机表现层问题真正会看、且体量可控**的几项。
         * 刻意**不含 `SurfaceFlinger`**（MB 级，会拖垮采集预算）。
         */
        val DUMPSYS_TARGETS = listOf(
            "window.txt" to "window windows",
            "display.txt" to "display",
            "activity.txt" to "activity activities",
            "media_codec.txt" to "media.codec",
            "projection.txt" to "media_projection",
        )

        val TIMESTAMP_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val FILE_STAMP_FORMAT = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
    }
}
