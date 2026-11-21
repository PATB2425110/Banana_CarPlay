package com.dbtool.carplay.bridge.update

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.dbtool.carplay.api.system.CarSystemAdapter
import com.dbtool.carplay.api.update.CarInstallOutcome
import com.dbtool.carplay.bridge.hotspot.AdbShellSession
import com.dbtool.carplay.api.update.CarUpdateEndpoints
import com.dbtool.carplay.api.update.CarUpdateHost
import com.dbtool.carplay.api.update.CarUpdatePhase
import com.dbtool.carplay.api.update.CarUpdateState
import com.dbtool.carplay.api.update.UPDATE_AUTO_CHECK_INTERVAL_MILLIS
import com.dbtool.carplay.bridge.developer.CarDeviceInfo
import com.dbtool.carplay.bridge.developer.CarDeveloperPrefs
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 在线更新的实现：查询 → 下载 → 交给系统安装。
 *
 * 线程模型与 `CarDeveloperMode` / `CarPlaySessionBridge` 一致——
 * **回调风格 + 主线程投递**：联网与写盘在单线程池里跑，每次阶段推进都
 * post 回主线程，由 `CarAppController` 写进 Compose 状态。
 * 全工程没有引入协程，这里也不破例。
 *
 * ⚠️ **安装结果靠"下次启动比对版本号"**，不靠回调：
 * 安装自身应用会让本进程被杀掉重建，任何 `PendingIntent` 都等不到结果。
 * 见 [CarUpdatePrefs.pendingInstallVersionCode]；失败原因再叠加读回的
 * 设备端日志（[explainFailedInstall]）。
 *
 * ⚠️ **ADB 通道跑在 worker 线程**（2026-10-11）：`adb push` 几十 MB +
 * 设备端六级降级，实车可能几分钟，主线程放不下。所以 [install] 在
 * 有 ADB 通道时**立刻返回** `ADB_COMMITTED` 并在后台继续，
 * 失败才回主线程退到系统安装器。
 *
 * ⚠️ **后台静默更新**（2026-10-11，P0）：开关打开时，"下载"与"ADB 安装"
 * 两步从"用户点击触发"改成"条件满足自动触发"，全程零弹窗。三条硬设计
 * （见 `CarSilentUpdatePolicy`）：
 * ① 只在**会话空闲 ≥30 秒**时动手（装自己会杀进程，不能打断投屏）；
 * ② 复用 `CarAppServices` 那条**共享** adb 通道（否则与热点互踢）；
 * ③ **坏包熔断**（同一版本自动失败 2 次就永久停手，否则能把 App 装死）。
 *
 * ⚠️ 本类是 `internal`（2026-10-11 从 public 收紧）：它的构造要收一个
 * `AdbShellSession`，而那个类型是 internal 的 —— Kotlin 不允许 internal 类型
 * 出现在 public 签名上（**`private val` 构造参数也算**，坑 §58）。
 * 外部只需要 `CarUpdateHost` 接口（`CarAppServices.update` 就是那么暴露的）。
 */
internal class CarUpdateManager(
    context: Context,
    system: CarSystemAdapter,
    private val appVersionName: String,
    private val appVersionCode: Long,
    /**
     * **共享的** adb shell 通道（2026-10-11）。
     *
     * ⚠️ 必须是 `CarAppServices` 那一条，不能自己 new —— 见
     * [CarApkInstaller] 的构造注释（两个实例会互相 `kill-server`，
     * 而后台静默安装恰好会在"会话刚结束"的瞬间动手，必踩）。
     *
     * 传 `null` = 本机不支持 ADB 通道（单测 / 非车机环境）。
     */
    private val adbSession: AdbShellSession? = null,
    /**
     * 诊断日志出口（PATCH-0095，2026-10-11）。
     *
     * ## 为什么必须补（现场证据）
     *
     * 2026-10-11 排查「静默更新到底生效没有」时，整条链路**客户端零日志**：
     * `CarUpdateManager` / `CarUpdatePrefs` 两个文件里一条 `Log` 都没有，
     * 「关于」页那颗开关也不进 `effective_settings.txt`
     * （它刻意不属于 `CarSessionSettings`）。结果只能靠服务端数据库反推
     * "40 秒走完了 pushed → downloaded → installed"。
     *
     * 现在每次检查/静默判定/下载/安装/熔断都留一行，并且随
     * `system/update_state.txt`（[diagnosticDump]）一起进诊断包。
     *
     * 默认空实现：单测与"没有日志出口"的调用方行为不变。
     */
    private val log: (String) -> Unit = {},
) : CarUpdateHost {

    private val appContext = context.applicationContext
    private val prefs = CarUpdatePrefs(appContext)

    /**
     * 设备号与日志上传共用同一份。
     *
     * 这里自己 new 一个 [CarDeviceInfo] 而不是从开发者模式借：两者读的是
     * **同一份 SharedPreferences**，`deviceId` 一旦落盘就终身不变，
     * 多一个实例不会产生第二个号；而借过来会把"更新"与"开发者模式"
     * 两个本不相干的功能耦上（更新对普通用户可见，开发者模式是隐藏页）。
     */
    private val deviceInfo = CarDeviceInfo(appContext, system, CarDeveloperPrefs(appContext))

    private val api = CarUpdateApi()
    private val downloader = CarApkDownloader()

    /**
     * 安装器（三条路径：`PackageInstaller` 静默 → ADB 静默 → 系统安装器）。
     *
     * ADB 那条走的是**共享**的 [adbSession]，不是自己 new 的 runner
     * （2026-10-11 修正，见构造注释）。
     */
    private val installer = CarApkInstaller(appContext, adbSession)

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 查询/下载串行执行。 */
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "dbplay-update").apply { isDaemon = true }
    }

    /** 同一时刻只允许一趟下载。 */
    private val downloading = AtomicBoolean(false)

    /** 本进程是否已经尝试过自动检查（见 [maybeAutoCheck]）。 */
    private val autoCheckAttempted = AtomicBoolean(false)

    /**
     * 本进程是否已经**提交过**一次后台静默安装（§3.1 的"进程内冷却"）。
     *
     * 与跨进程的熔断计数（[CarUpdatePrefs.silentAttemptCount]）分工不同：
     * 这个只防"同一进程内被通知多次、装好几遍"，进程重启后自然重置；
     * 熔断跨进程生效，防的是"装失败 → 重启 → 又装"的循环。
     *
     * 用 `compareAndSet` 而不是普通布尔：`notifyIdleWindow()` 可能被连续
     * 调用多次（每次会话状态变化都算一次），必须保证只有一个能真正发起。
     */
    private val silentInstallSubmitted = AtomicBoolean(false)

    private val lock = Any()

    @Volatile
    private var snapshot: CarUpdateState = CarUpdateState(
        currentVersionName = appVersionName,
        currentVersionCode = appVersionCode,
        installNeedsUserConfirm = installer.needsUserConfirm(),
        // 静默开关是**车主**的态度，落盘在升级链路自己的 prefs 里。
        silentUpdateEnabled = prefs.silentUpdateEnabled,
    )

    init {
        log(
            "初始化：当前 $appVersionName($appVersionCode)｜" +
                "静默开关=${prefs.silentUpdateEnabled}｜" +
                "上次检查=${stamp(prefs.lastCheckAtMillis)}｜" +
                "待安装版本=${prefs.pendingInstallVersionCode}｜" +
                "熔断版本=${prefs.silentBlockedVersionCode}",
        )
        settlePendingInstall()
        restoreDownloaded()
    }

    override fun state(): CarUpdateState = snapshot

    // ---------------------------------------------------------------- 查询

    override fun checkAsync(manual: Boolean, onState: (CarUpdateState) -> Unit) {
        // ⚠️ 时间戳在**发起前**就落盘，不是成功后才写：安装自我更新会把进程
        // 杀掉重建，一次"发出去还没回来就被杀"的检查如果不记账，每次重建
        // 都会再发一遍请求。实测踩过——装完新版后短时间内连发了 4 次。
        prefs.lastCheckAtMillis = System.currentTimeMillis()
        log(
            "检查更新（${if (manual) "手动" else "自动"}）→ " +
                "${CarUpdateEndpoints.BASE_URL}${CarUpdateEndpoints.CHECK_PATH}",
        )
        publish(onState) {
            it.copy(
                phase = CarUpdatePhase.CHECKING,
                message = "正在检查更新…",
            )
        }
        worker.execute {
            val result = api.check(
                baseUrl = CarUpdateEndpoints.BASE_URL,
                path = CarUpdateEndpoints.CHECK_PATH,
                appId = CarUpdateEndpoints.APP_ID,
                currentVersionCode = appVersionCode,
                currentVersionName = appVersionName,
                deviceId = deviceInfo.deviceId(),
                timeoutSeconds = CarUpdateEndpoints.CHECK_TIMEOUT_SECONDS,
            )
            if (!result.success) {
                // 2026-10-11 排查「静默更新到底生效没有」时，这里一条日志都没有，
                // 只能靠服务端数据库反推。现在失败原因必须落地。
                log("检查失败：${result.message}")
                publish(onState) {
                    // 自动检查失败**保持安静**：车机可能长期没有网络，
                    // 每次启动都报一句"检查更新失败"只会变成噪声。
                    // 手动检查必须给出原因——用户刚点了按钮，不能没反应。
                    if (manual) {
                        it.copy(phase = CarUpdatePhase.FAILED, message = result.message, dialogVisible = false)
                    } else {
                        it.copy(phase = CarUpdatePhase.IDLE, message = "")
                    }
                }
                return@execute
            }
            val info = result.info
            if (info == null || !info.hasUpdate || info.latestVersionCode <= appVersionCode) {
                log(
                    "服务端无新版：hasUpdate=${info?.hasUpdate}｜" +
                        "服务端最新=${info?.latestVersionName}(${info?.latestVersionCode})｜" +
                        "本机 $appVersionCode",
                )
                publish(onState) {
                    it.copy(
                        phase = CarUpdatePhase.UP_TO_DATE,
                        latestVersionCode = 0L,
                        latestVersionName = "",
                        releaseNotes = emptyList(),
                        downloadUrl = "",
                        forceUpdate = false,
                        autoUpdate = false,
                        isTargeted = false,
                        progress = 0f,
                        message = "已是最新版本（$appVersionName）",
                        lastCheckedAt = now(),
                        dialogVisible = false,
                    )
                }
                return@execute
            }

            // 用户点过「稍后再说」的版本，自动检查不再弹窗；手动检查照弹。
            val suppressed = !manual && prefs.dismissedVersionCode == info.latestVersionCode
            // 后台静默续跑（2026-10-11）：只有**自动**检查才可能静默；
            // 用户手动点了「检查更新」就是想自己决定，永远走弹窗。
            val silent = !manual && shouldSilent(info)
            // 2026-10-11：这三行是本次排查缺的**最关键**证据 —— 静默"没生效"
            // 到底是哪一条不成立（车主开关 / 服务端表态 / 地址 / 熔断），
            // 以前完全无从判断。逐项打出来，不用再靠服务端反推。
            log(
                "发现新版本 ${info.latestVersionName}(${info.latestVersionCode})｜" +
                    "强制=${info.forceUpdate}｜服务端允许静默=${info.autoUpdate}｜" +
                    "定向=${info.isTargeted}｜" +
                    "下载地址=${if (info.downloadUrl.isEmpty()) "空" else "已配置"}｜" +
                    "已忽略=$suppressed｜车主开关=${prefs.silentUpdateEnabled}｜" +
                    "熔断版本=${prefs.silentBlockedVersionCode} ⇒ silent=$silent",
            )
            publish(onState) {
                it.copy(
                    phase = CarUpdatePhase.AVAILABLE,
                    latestVersionCode = info.latestVersionCode,
                    latestVersionName = info.latestVersionName,
                    releaseNotes = info.releaseNotes,
                    forceUpdate = info.forceUpdate,
                    autoUpdate = info.autoUpdate,
                    silentUpdateEnabled = prefs.silentUpdateEnabled,
                    isTargeted = info.isTargeted,
                    downloadUrl = info.downloadUrl,
                    progress = 0f,
                    message = if (info.downloadUrl.isEmpty()) {
                        "发现新版本 ${info.latestVersionName}，但服务端还没有配置下载地址"
                    } else {
                        "发现新版本 ${info.latestVersionName}"
                    },
                    lastCheckedAt = now(),
                    // 静默时**不弹**：用户唯一该看到的是「关于」页那一行。
                    dialogVisible = !suppressed && !silent,
                )
            }
            if (silent) {
                log("静默条件全部满足 ⇒ 自动下载（不弹窗）")
                // 紧接着自动下载（不弹窗），下完进 PENDING_INSTALL 等空闲窗口。
                // ⚠️ 这里已经在 worker 线程上，而 download() 内部还会再
                // `worker.execute` —— 单线程池，任务排队即可，不会死锁。
                download(onState, silent = true)
            }
        }
    }

    override fun maybeAutoCheck(onState: (CarUpdateState) -> Unit) {
        // 一个进程只自动查一次。
        //
        // 持久化的 6 小时节流之外还要这道闸门：安装自我更新后 Android 会
        // 连续把应用拉起几次，每次都新建一个 `CarUpdateManager`，而
        // `CarAppController` 在 `Application.onCreate` 里就会调它——
        // 只靠落盘的时间戳，几次重建之间的窗口足够把请求发出去好几遍。
        if (!autoCheckAttempted.compareAndSet(false, true)) {
            log("自动检查：本进程已经查过（安装自我更新会被系统连拉几次）⇒ 跳过")
            return
        }
        val elapsed = System.currentTimeMillis() - prefs.lastCheckAtMillis
        if (prefs.lastCheckAtMillis > 0L && elapsed < UPDATE_AUTO_CHECK_INTERVAL_MILLIS) {
            log(
                "自动检查：距上次 ${elapsed / 60_000L} 分钟 < " +
                    "${UPDATE_AUTO_CHECK_INTERVAL_MILLIS / 3_600_000L} 小时 ⇒ 节流跳过",
            )
            return
        }
        checkAsync(manual = false, onState = onState)
    }

    // ---------------------------------------------------------------- 下载

    override fun download(onState: (CarUpdateState) -> Unit, silent: Boolean): Boolean {
        val current = snapshot
        if (current.phase != CarUpdatePhase.AVAILABLE && current.phase != CarUpdatePhase.FAILED) {
            log("下载请求被拒：当前阶段 ${current.phase} 不允许下载")
            return false
        }
        if (current.latestVersionCode <= 0L) {
            log("下载请求被拒：没有已知的目标版本号")
            return false
        }
        if (current.downloadUrl.isEmpty()) {
            log("下载请求被拒：服务端没有配置下载地址")
            publish(onState) {
                it.copy(
                    phase = CarUpdatePhase.FAILED,
                    message = "服务端没有配置下载地址，请联系维护者",
                    // 静默时连这条也不弹：后台没人看，弹出来只会挂在那儿。
                    dialogVisible = !silent,
                )
            }
            return false
        }
        if (!downloading.compareAndSet(false, true)) {
            log("下载请求被拒：已有一趟下载在进行中")
            return false
        }

        val versionCode = current.latestVersionCode

        // 同一个版本的包已经在本地就直接跳到"待安装"。
        // 这让"安装失败 → 重试"不会白下几十 MB，也让应用重启后
        // （`restoreDownloaded` 之外的情况，例如用户点了稍后再回来）不必重下。
        val cached = downloadedApk()
        if (cached != null && prefs.downloadedVersionCode == versionCode) {
            // ⚠️ 缓存包**必须重新校验**：`downloadedApkPath` 只记了路径与版本号，
            // 文件本身可能被写坏、被截断，或服务端换过包（同名不同内容）。
            // 校验不过就删掉重下 —— 拿一个坏包去 `pm install` 只会白跑一趟
            // 六级降级，最后还是失败，还白白触发一次熔断计数。
            val cachedError = installer.validateUpdateApk(cached, versionCode)
            if (cachedError == null) {
                downloading.set(false)
                log("本地已有 $versionCode 的安装包（${cached.length()} bytes）⇒ 跳过下载")
                publish(onState) {
                    it.copy(
                        phase = if (silent) CarUpdatePhase.PENDING_INSTALL else CarUpdatePhase.READY,
                        progress = 1f,
                        message = if (silent) PENDING_INSTALL_MESSAGE else "安装包已就绪，可以安装",
                        dialogVisible = !silent,
                    )
                }
                return true
            }
            log("本地缓存的 $versionCode 安装包校验失败（$cachedError）⇒ 删除并重新下载")
            cached.delete()
            prefs.downloadedApkPath = ""
            prefs.downloadedVersionCode = 0L
        }

        log("开始${if (silent) "后台静默" else ""}下载 $versionCode ← ${current.downloadUrl}")
        publish(onState) {
            it.copy(
                phase = CarUpdatePhase.DOWNLOADING,
                progress = 0f,
                message = if (silent) {
                    "正在后台下载 ${current.latestVersionName}…"
                } else {
                    "正在下载 ${current.latestVersionName}…"
                },
                // ⚠️ 静默下载**不弹窗**：用户唯一该看到的是「关于」页那一行
                // （`hintText()` 会显示"已下载 xx%"）。
                dialogVisible = !silent,
            )
        }
        worker.execute {
            try {
                val result = downloader.download(
                    url = current.downloadUrl,
                    targetFile = apkFileFor(versionCode),
                    timeoutSeconds = CarUpdateEndpoints.DOWNLOAD_TIMEOUT_SECONDS,
                ) { progress ->
                    publish(onState) {
                        it.copy(
                            phase = CarUpdatePhase.DOWNLOADING,
                            progress = progress,
                            message = if (silent) {
                                "正在后台下载 ${(progress * 100).toInt()}%"
                            } else {
                                "正在下载 ${(progress * 100).toInt()}%"
                            },
                        )
                    }
                }
                if (result.success && result.file != null) {
                    prefs.downloadedApkPath = result.file.absolutePath
                    prefs.downloadedVersionCode = versionCode
                    reportStatus(CarUpdateEndpoints.STATUS_DOWNLOADED, result.file.name)
                    log("下载完成：${result.file.absolutePath}（${result.file.length()} bytes）")
                    publish(onState) {
                        it.copy(
                            // 静默下载完成 ⇒ 进"等空闲窗口"，而不是"等用户点按钮"。
                            phase = if (silent) CarUpdatePhase.PENDING_INSTALL else CarUpdatePhase.READY,
                            progress = 1f,
                            message = if (silent) PENDING_INSTALL_MESSAGE else result.message,
                            dialogVisible = !silent,
                        )
                    }
                } else {
                    log("下载失败：${result.message}")
                    publish(onState) {
                        it.copy(
                            phase = CarUpdatePhase.FAILED,
                            progress = 0f,
                            message = if (silent) {
                                // 车机可能正跑自建热点（无外网）。下载失败就是失败，
                                // 不阻塞、不报警，下次启动（6h 节流后）自然会再试。
                                "后台下载失败，将在下次启动时重试"
                            } else {
                                result.message
                            },
                            dialogVisible = !silent,
                        )
                    }
                    reportStatus(CarUpdateEndpoints.STATUS_FAILED, result.message)
                }
            } catch (error: Throwable) {
                log("下载异常：${error.javaClass.simpleName} ${error.message.orEmpty()}".trim())
                publish(onState) {
                    it.copy(
                        phase = CarUpdatePhase.FAILED,
                        progress = 0f,
                        message = if (silent) {
                            "后台下载失败，将在下次启动时重试"
                        } else {
                            "下载失败：${error.javaClass.simpleName} ${error.message.orEmpty()}".trim()
                        },
                        dialogVisible = !silent,
                    )
                }
            } finally {
                downloading.set(false)
            }
        }
        return true
    }

    // ---------------------------------------------------------------- 安装

    override fun install(): CarInstallOutcome {
        val current = snapshot
        val file = downloadedApk()
        if (file == null || !file.exists()) {
            log("手动安装失败：安装包不存在（路径=${prefs.downloadedApkPath.ifEmpty { "(空)" }}）")
            publishInstallFailure("安装包不存在，请重新下载")
            return CarInstallOutcome.FAILED
        }
        val versionCode = current.latestVersionCode.takeIf { it > 0L } ?: prefs.downloadedVersionCode
        // ⚠️ 手动路径也要**先校验包**（2026-10-15）：`installViaAdb` 内部虽然也校验，
        // 但它失败后会回退到系统安装器，那条路**不校验** —— 若包本身已损坏/错版本，
        // 用户会看到一个必然失败的安装界面。这里一次性挡在最前面，坏包直接删掉重下。
        val apkError = installer.validateUpdateApk(file, versionCode)
        if (apkError != null) {
            log("手动安装失败：$apkError ⇒ 删除无效包，请重新下载")
            file.delete()
            prefs.downloadedApkPath = ""
            prefs.downloadedVersionCode = 0L
            publishInstallFailure("安装包无效（$apkError），请重新下载")
            return CarInstallOutcome.FAILED
        }
        // ⚠️ 手动路径**不受熔断限制**，并且在这里**解除**熔断（2026-10-11）：
        // 用户明确点了「立即安装」，那是在**他的**判断下装这个包。
        // 熔断拦的是"无人值守的自动重试"，不是他本人 —— 这也是车主在
        // "静默装不上"时唯一能自救的手段（否则关掉开关再打开也行）。
        prefs.clearSilentBreaker()
        silentInstallSubmitted.set(false)
        // 先记下"我把它交出去了"，下次启动比对版本号就能判定装成功。
        prefs.pendingInstallVersionCode = versionCode
        log(
            "手动安装 $versionCode ← ${file.absolutePath}（${file.length()} bytes）｜" +
                "ADB 通道=${if (installer.canUseAdb()) "有" else "无"}｜" +
                "需用户在系统界面确认=${installer.needsUserConfirm()}",
        )

        // ⚠️ 2026-10-11：ADB 通道**必须离开主线程**。
        //
        // 它要 `adb push` 几十 MB、再在设备端跑六级降级（最多六次 `pm install`），
        // 实车几分钟；`probePrivilege()` 里的 `adb root` 还要等 adbd 重启。
        // 旧实现整段跑在主线程——那不是"安装中"，是 ANR。
        //
        // 这里立刻返回 [CarInstallOutcome.ADB_COMMITTED]（"已交给设备端"），
        // 真实成败仍由下次启动比版本号 + 读回设备端日志判定。
        if (installer.canUseAdb()) {
            // 用 `update` 而不是 `publish`：这里没有 `onState` 回调可用
            // （`install()` 是同步 API，没有回调参数）。状态变化由调用方
            // 随后的 `refreshModel()` 读走。
            update {
                it.copy(phase = CarUpdatePhase.INSTALLING, message = "正在通过 ADB 静默安装…")
            }
            reportStatus(CarUpdateEndpoints.STATUS_PUSHED, file.name)
            worker.execute {
                val adbOutcome = installer.installViaAdb(file, versionCode)
                log("手动安装走 ADB，返回：$adbOutcome")
                mainHandler.post {
                    if (adbOutcome == CarInstallOutcome.ADB_COMMITTED) {
                        update {
                            it.copy(
                                phase = CarUpdatePhase.INSTALLING,
                                message = "正在通过 ADB 静默安装，完成后应用会自动重启",
                            )
                        }
                    } else {
                        // ADB 这条路打不通 → 回主线程退到系统安装器（要 `startActivity`）。
                        fallbackToSystemInstaller(file)
                    }
                }
            }
            return CarInstallOutcome.ADB_COMMITTED
        }

        // 没有 ADB 通道：同步走「平台静默 → 系统安装器」。
        // 两者都不慢，且后者本来就要求主线程（`startActivity`）。
        val outcome = installer.installWithoutAdb(file, versionCode)
        log("手动安装走「平台静默 → 系统安装器」，返回：$outcome")
        applyInstallOutcome(outcome)
        reportStatus(
            status = if (outcome == CarInstallOutcome.FAILED) {
                CarUpdateEndpoints.STATUS_FAILED
            } else {
                CarUpdateEndpoints.STATUS_PUSHED
            },
            detail = file.name,
        )
        return outcome
    }

    /**
     * ADB 通道失败后的兜底：拉起系统安装器。
     *
     * ⚠️ **只能在主线程调用**（`startActivity`）—— 调用点是 worker 任务里的
     * `mainHandler.post`。
     */
    private fun fallbackToSystemInstaller(file: File) {
        log("ADB 通道没打通 ⇒ 回退系统安装器")
        val outcome = installer.launchSystemInstaller(file)
        applyInstallOutcome(outcome)
        reportStatus(
            status = if (outcome == CarInstallOutcome.FAILED) {
                CarUpdateEndpoints.STATUS_FAILED
            } else {
                CarUpdateEndpoints.STATUS_PUSHED
            },
            detail = file.name,
        )
    }

    /**
     * 把安装结果翻译成状态与文案。
     *
     * 抽成方法是因为它有**两个调用点**（同步路径与 ADB 失败回退），
     * 而每个分支的文案都是"用户此刻该知道什么"，散在两处必然走样。
     */
    private fun applyInstallOutcome(outcome: CarInstallOutcome) {
        when (outcome) {
            CarInstallOutcome.SILENT_COMMITTED -> update {
                it.copy(phase = CarUpdatePhase.INSTALLING, message = "正在安装，完成后应用会自动重启")
            }

            CarInstallOutcome.ADB_COMMITTED -> update {
                it.copy(
                    phase = CarUpdatePhase.INSTALLING,
                    message = "正在通过 ADB 静默安装，完成后应用会自动重启",
                )
            }

            CarInstallOutcome.LAUNCHER_OPENED -> update {
                it.copy(phase = CarUpdatePhase.INSTALLING, message = "请在系统安装界面点「安装」")
            }

            CarInstallOutcome.NEEDS_UNKNOWN_SOURCES -> {
                // 授权页已经拉起来了，把话说清楚，否则用户回来再点一次还是同样结果。
                prefs.pendingInstallVersionCode = 0L
                update {
                    it.copy(
                        phase = CarUpdatePhase.READY,
                        message = "请先在本机允许 DBPlay 安装应用，再点一次「安装」",
                    )
                }
            }

            CarInstallOutcome.FAILED -> {
                prefs.pendingInstallVersionCode = 0L
                publishInstallFailure("拉起安装器失败，请重试")
            }
        }
    }

    // ------------------------------------------------------- 后台静默更新

    /**
     * 自动检查发现新版本时，**这次**该不该走静默？
     *
     * 三个条件缺一不可（两把锁 + 一个熔断）：
     * 1. 车主打开了「后台自动更新」（`prefs.silentUpdateEnabled`）；
     * 2. **服务端**允许（`info.autoUpdate`，来自后台「应用更新」页那颗开关）。
     *    服务端不点头，客户端不得自作主张 —— 发布方要能"踩刹车"；
     * 3. 该版本**没被熔断**（`silentBlocked`）。
     *
     * 另外还要求有下载地址 —— 没有地址连弹窗都下不了，谈不上静默。
     */
    private fun shouldSilent(info: CarUpdateApi.ReleaseInfo): Boolean = silentAllowed(
        silentUpdateEnabled = prefs.silentUpdateEnabled,
        serverAutoUpdate = info.autoUpdate,
        downloadUrl = info.downloadUrl,
        versionCode = info.latestVersionCode,
    )

    /**
     * [shouldSilent] 的**参数化核心**（PATCH-0095，2026-10-11）。
     *
     * 抽出来只有一个理由：诊断包里的 [diagnosticDump] 要用**同一套**判据复盘
     * "此刻到底满不满足静默"。如果那边自己再写一遍 `&&` 链，两边迟早会走样 ——
     * 而这一条链正是 2026-10-11 排查"静默更新到底生效没有"时唯一想知道的东西。
     *
     * ⚠️ 输入是**当前快照**（`CarUpdateState`）而不是 `ReleaseInfo`：
     * 诊断包是在"检查早就结束了"之后才生成的，那时手里只有快照。
     */
    private fun silentAllowed(
        silentUpdateEnabled: Boolean,
        serverAutoUpdate: Boolean,
        downloadUrl: String,
        versionCode: Long,
    ): Boolean = silentUpdateEnabled &&
        serverAutoUpdate &&
        downloadUrl.isNotEmpty() &&
        !silentBlocked(versionCode, prefs.silentBlockedVersionCode)

    /**
     * 会话已连续空闲 ≥30 秒（由 `CarAppController` 按 `CarSilentUpdatePolicy` 计时）。
     *
     * ## 这个方法只做"闸门 + 提交"，不做重活
     *
     * `installViaAdb()` 要 `adb push` 几十 MB 再跑六级降级，实车几分钟，
     * 必须在 worker 线程。所以这里做完所有**判据**后立刻把活扔给 worker 返回。
     *
     * ## 幂等
     *
     * 空闲期间每次会话状态变化都会通知一次，靠 [silentInstallSubmitted]
     * 的 `compareAndSet` 保证一个待安装版本只发起一次。
     *
     * ## 为什么失败**不**退系统安装器
     *
     * 设计 §1 问题 3 的建议是"退一次，但必须**用户在车里**才弹"。静默安装
     * 本身就只在**没连手机**时发生，那一刻基本可以认为车里没人 ——
     * 拉起一个没人点的系统安装框，比不装更糟（它会一直挂在前台）。
     * 所以这里只记失败，让下次启动的 [settlePendingInstall] 推进熔断计数；
     * **用户手动点「立即安装」时**才走完整的"平台静默 → 系统安装器"兜底。
     */
    override fun notifyIdleWindow() {
        val current = snapshot
        if (!current.silentUpdateEnabled) {
            log("空闲窗口：静默开关关着 ⇒ 不动手")
            return
        }
        if (current.phase != CarUpdatePhase.PENDING_INSTALL) {
            log("空闲窗口：当前阶段 ${current.phase} ≠ PENDING_INSTALL ⇒ 不动手")
            return
        }

        val versionCode = current.latestVersionCode.takeIf { it > 0L } ?: prefs.downloadedVersionCode
        if (versionCode <= 0L) {
            log("空闲窗口：没有可安装的版本号 ⇒ 不动手")
            return
        }

        // 已熔断 ⇒ 直接停手（下次启动的 settle 已经写过这个标记了，这里是防御）。
        if (silentBlocked(versionCode, prefs.silentBlockedVersionCode)) {
            log("空闲窗口：$versionCode 已被熔断 ⇒ 不动手")
            return
        }

        // ⚠️ 判断"还能不能再试"必须发生在**落盘之前** —— 那时读到的才是
        // "已经尝试过几次"（见 silentAttemptLimitReached 的时序表）。
        if (silentAttemptLimitReached(versionCode, prefs.silentAttemptVersionCode, prefs.silentAttemptCount)) {
            prefs.silentBlockedVersionCode = versionCode
            log("空闲窗口：$versionCode 静默尝试已达上限（${prefs.silentAttemptCount} 次）⇒ 写入熔断")
            update {
                it.copy(
                    phase = CarUpdatePhase.READY,
                    message = SILENT_BLOCKED_MESSAGE,
                    dialogVisible = false,
                )
            }
            return
        }

        val file = downloadedApk()
        if (file == null || !file.exists()) {
            log("空闲窗口：安装包不见了（路径=${prefs.downloadedApkPath.ifEmpty { "(空)" }}）⇒ 不动手")
            return
        }

        // ⚠️ 校验必须放在 [prefs.recordSilentAttempt] **之前**（2026-10-15）：
        // 坏包是"包的问题"，不是"安装通道的问题"。若先记一次尝试再发现包坏了，
        // 就会白白烧掉一次熔断额度（上限只有 2 次），最终把一个其实能装好的
        // 版本误判成"装不上"而熔断。这里发现坏包 ⇒ 删掉、退到 READY 等重下。
        val apkError = installer.validateUpdateApk(file, versionCode)
        if (apkError != null) {
            log("空闲窗口：本地包校验失败（$apkError）⇒ 删除并退回可安装")
            file.delete()
            prefs.downloadedApkPath = ""
            prefs.downloadedVersionCode = 0L
            update {
                it.copy(
                    phase = CarUpdatePhase.READY,
                    progress = 0f,
                    message = "安装包已失效，请重新下载",
                    dialogVisible = false,
                )
            }
            return
        }

        // 本进程内只提交一次（§3.1 冷却）。必须放在所有判据之后、真正动手之前。
        if (!silentInstallSubmitted.compareAndSet(false, true)) {
            log("空闲窗口：本进程已经提交过静默安装 ⇒ 不重复")
            return
        }

        // 🔴 熔断计数**在发起之前**落盘：装自己会把进程杀掉，写在"成功之后"
        // 的代码可能永远执行不到，那次尝试就白记了（与 lastCheckAtMillis 同理）。
        prefs.recordSilentAttempt(versionCode)
        prefs.pendingInstallVersionCode = versionCode
        log(
            "空闲窗口满足 ⇒ 发起静默安装 $versionCode" +
                "（本版本第 ${prefs.silentAttemptCount} 次尝试，包 ${file.length()} bytes）",
        )

        update {
            it.copy(phase = CarUpdatePhase.INSTALLING, message = "正在后台静默安装…")
        }
        reportStatus(CarUpdateEndpoints.STATUS_PUSHED, file.name)

        worker.execute {
            val outcome = installer.installViaAdb(file, versionCode)
            log("静默安装走 ADB，返回：$outcome")
            if (outcome == CarInstallOutcome.ADB_COMMITTED) {
                // 已交给设备端。成败由下次启动比版本号判定 —— 装成功这一刻
                // 本进程会被系统杀掉，这里的代码不会再往下走。
                return@execute
            }
            // 通道没打通（adb 起不来 / push 失败 / 本机不支持）。
            // 不弹系统安装器，见方法注释；把状态退回"可安装"，等用户自己决定。
            mainHandler.post {
                update {
                    it.copy(
                        phase = CarUpdatePhase.READY,
                        message = "后台自动安装未成功，可手动点「立即安装」重试",
                        dialogVisible = false,
                    )
                }
            }
        }
    }

    /**
     * 开/关「后台自动更新」（「关于」页那颗开关）。
     *
     * - **打开**：解除熔断（车主自救路径之一），已有下载好的包就直接进
     *   [CarUpdatePhase.PENDING_INSTALL] 等空闲窗口；
     * - **关闭**：把"待安装"退回 [CarUpdatePhase.READY]（包留着，用户仍可手动装），
     *   并清掉进程内冷却标记。
     */
    override fun setSilentUpdateEnabled(enabled: Boolean): CarUpdateState {
        prefs.silentUpdateEnabled = enabled
        silentInstallSubmitted.set(false)
        if (enabled) {
            // 用户重新打开开关 = 明确表示"我愿意让它自己装"，此时把熔断清掉。
            prefs.clearSilentBreaker()
        }
        log("后台自动更新开关 → ${if (enabled) "开（并解除熔断）" else "关"}")
        return update {
            val wasPending = it.phase == CarUpdatePhase.PENDING_INSTALL
            it.copy(
                silentUpdateEnabled = enabled,
                phase = when {
                    enabled && it.phase == CarUpdatePhase.READY && it.latestVersionCode > 0L ->
                        CarUpdatePhase.PENDING_INSTALL

                    !enabled && wasPending -> CarUpdatePhase.READY
                    else -> it.phase
                },
                message = when {
                    enabled && it.phase == CarUpdatePhase.READY && it.latestVersionCode > 0L ->
                        PENDING_INSTALL_MESSAGE

                    !enabled && wasPending -> "安装包已下载完成，可以安装"
                    else -> it.message
                },
                dialogVisible = if (enabled) false else it.dialogVisible,
            )
        }
    }

    override fun dismiss(): CarUpdateState {
        val current = snapshot
        if (current.latestVersionCode > 0L && !current.forceUpdate) {
            prefs.dismissedVersionCode = current.latestVersionCode
        }
        return update { it.copy(dialogVisible = false) }
    }

    override fun reportStatus(status: String, detail: String) {
        val deviceId = runCatching { deviceInfo.deviceId() }.getOrDefault("")
        if (deviceId.isEmpty()) return
        worker.execute {
            api.reportStatus(
                baseUrl = CarUpdateEndpoints.BASE_URL,
                path = CarUpdateEndpoints.STATUS_PATH,
                appId = CarUpdateEndpoints.APP_ID,
                deviceId = deviceId,
                status = status,
                detail = detail,
                timeoutSeconds = CarUpdateEndpoints.CHECK_TIMEOUT_SECONDS,
            )
        }
    }

    // ---------------------------------------------------------------- 诊断

    /**
     * 后台更新状态转储（PATCH-0095，2026-10-11）。
     *
     * ## 为什么要有它
     *
     * 2026-10-11 排查「静默更新到底生效没有」时，整条链路在客户端**零日志**、
     * 而「后台自动更新」那颗开关也**不进任何落盘文件**（它刻意不属于
     * `CarSessionSettings`，见 [CarUpdatePrefs.silentUpdateEnabled]）⇒ 只能靠
     * 服务端数据库反推"40 秒走完了 pushed → downloaded → installed"，
     * 却始终说不清"客户端当时是怎么判的"。
     *
     * 本方法就是那一格证据：由 `CarAppServices` 接进
     * `CarDiagnosticPackager.updateStateProvider`，产物是诊断包里的
     * `system/update_state.txt`，并在 `collection_manifest.txt` 里报一行。
     *
     * ## 内容组织
     *
     * 按"**排查时要按顺序排除什么**"排，而不是按代码里的字段顺序：
     * ① 版本 → ② 静默三条件（逐条给出取值，一眼看出卡在哪一条）→
     * ③ 节流/计数 → ④ 本地包 → ⑤ 当前阶段与文案。
     *
     * ⚠️ **只读**：不触发任何网络请求、不改状态（诊断包采集不该有副作用）。
     * ⚠️ 判"此刻满足静默条件"复用的是 [silentAllowed]，与真实决策**同一套逻辑**。
     */
    fun diagnosticDump(): String {
        val state = snapshot
        val file = downloadedApk()
        val blocked = silentBlocked(state.latestVersionCode, prefs.silentBlockedVersionCode)
        val silentNow = silentAllowed(
            silentUpdateEnabled = prefs.silentUpdateEnabled,
            serverAutoUpdate = state.autoUpdate,
            downloadUrl = state.downloadUrl,
            versionCode = state.latestVersionCode,
        )
        return buildString {
            appendLine("# DBPlay 后台更新状态（PATCH-0095）")
            appendLine("generatedAt: ${now()}")
            appendLine()
            appendLine("# 版本")
            appendLine("本机: $appVersionName ($appVersionCode)")
            appendLine(
                "服务端最新: " +
                    state.latestVersionName.ifEmpty { "（未查询 / 无新版）" } +
                    " (${state.latestVersionCode})",
            )
            appendLine("定向灰度命中(isTargeted): ${state.isTargeted}")
            appendLine("强制更新(forceUpdate): ${state.forceUpdate}")
            appendLine()
            appendLine("# 后台静默更新：两把锁 + 一个熔断（缺一不可）")
            appendLine("① 车主开关 silentUpdateEnabled: ${prefs.silentUpdateEnabled}")
            appendLine("② 服务端表态 autoUpdate: ${state.autoUpdate}（来自 /api/app/check_update）")
            appendLine(
                "③ 熔断 silentBlockedVersionCode: ${prefs.silentBlockedVersionCode}" +
                    "（对版本 ${state.latestVersionCode} 命中=$blocked）",
            )
            appendLine("下载地址: ${state.downloadUrl.ifEmpty { "（空 ⇒ 静默必不成立）" }}")
            appendLine("⇒ 此刻满足静默条件: $silentNow")
            appendLine()
            appendLine("# 节流与计数")
            appendLine("上次检查(发起时刻): ${stamp(prefs.lastCheckAtMillis)}")
            appendLine("用户已忽略版本(dismissedVersionCode): ${prefs.dismissedVersionCode}")
            appendLine(
                "静默尝试: 版本 ${prefs.silentAttemptVersionCode} 已试 " +
                    "${prefs.silentAttemptCount} 次（上限见 CarSilentUpdatePolicy）",
            )
            appendLine("待安装版本(pendingInstallVersionCode): ${prefs.pendingInstallVersionCode}")
            appendLine("本进程已提交过静默安装: ${silentInstallSubmitted.get()}")
            appendLine("本进程已自动检查过: ${autoCheckAttempted.get()}")
            appendLine()
            appendLine("# 已下载的包")
            if (file == null) {
                appendLine("无（downloadedApkPath=${prefs.downloadedApkPath.ifEmpty { "(空)" }}）")
            } else {
                appendLine(
                    "${file.absolutePath}（${file.length()} bytes，" +
                        "最后写入 ${stamp(file.lastModified())}，" +
                        "记录版本 ${prefs.downloadedVersionCode}）",
                )
            }
            appendLine()
            appendLine("# 当前状态")
            appendLine("阶段(phase): ${state.phase}")
            appendLine("文案(message): ${state.message}")
            appendLine(
                "安装是否需要用户在系统界面确认: ${installer.needsUserConfirm()}" +
                    "（快照里记的是 ${state.installNeedsUserConfirm}）",
            )
            appendLine("ADB 通道可用: ${installer.canUseAdb()}")
        }
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 判定"上一次交出去的安装"到底成没成。
     *
     * 只在构造时跑一次：安装自我更新会杀掉本进程，所以"当前进程的版本号
     * 已经 ≥ 待安装版本号"就是安装成功的**充分证据**（Android 不允许同包
     * 降级安装，因此不可能出现"版本号变大但不是我们装的"）。
     *
     * ⚠️ 2026-10-11：失败分支现在会去**读回设备端安装日志**（见 [explainFailedInstall]）。
     * 在那之前，失败只是"静默清掉标记"——用户只知道版本没变，不知道被什么挡了。
     */
    private fun settlePendingInstall() {
        val pending = prefs.pendingInstallVersionCode
        if (pending <= 0L) return
        if (appVersionCode >= pending) {
            log("上次交出的安装（$pending）成功：本机已是 $appVersionName($appVersionCode)")
            reportStatus(CarUpdateEndpoints.STATUS_INSTALLED, appVersionName)
            prefs.pendingInstallVersionCode = 0L
            // ⚠️ 必须把安装包删掉：`restoreDownloaded()` 只看 `downloadedApkPath`，
            // 这里清了路径它就会提前返回，文件就永远留在 filesDir 里——
            // 车机的存储本来就不宽裕，一次 OTA 白占几十 MB。
            downloadedApk()?.delete()
            prefs.downloadedApkPath = ""
            prefs.downloadedVersionCode = 0L
            // 装成功了 ⇒ 熔断与计数一起清零（这一轮已经结束）。
            prefs.clearSilentBreaker()
        } else {
            // 版本号没变，说明上次没装成（用户取消 / 系统安装器失败 / ADB 通道没打通）。
            // 清掉标记，免得它一直挂着，下次启动又误判。
            // **不删包**：用户很可能只是点了取消，包留着让他能直接重试。
            log("上次交出的安装（$pending）未成功：本机仍是 $appVersionCode")
            prefs.pendingInstallVersionCode = 0L
            explainFailedInstall()

            // 🔴 静默路径的熔断（2026-10-11）：`silentAttemptCount` 已经在**发起安装前**
            // 就落盘了（见 notifyIdleWindow），所以这里读到的次数是准的。
            // 用完允许的次数仍失败 ⇒ 写死熔断标记，下次启动不再自动装它。
            if (silentAttemptLimitReached(
                    pending,
                    prefs.silentAttemptVersionCode,
                    prefs.silentAttemptCount,
                )
            ) {
                prefs.silentBlockedVersionCode = pending
                log("静默安装连续失败达上限 ⇒ 熔断 $pending")
                update {
                    it.copy(
                        phase = CarUpdatePhase.READY,
                        message = SILENT_BLOCKED_MESSAGE,
                        dialogVisible = false,
                    )
                }
            }
        }
    }

    /**
     * 读回设备端安装日志，把失败原因写进状态（2026-10-11 加）。
     *
     * ## 为什么必须异步
     *
     * 本方法由 [settlePendingInstall] 在**构造时**（主线程）调用，而读日志
     * 要重新起一遍 adb 通道（`prepare` → `startServer` → `connect` → `shell cat`），
     * 实车十几秒。挂在启动路径上就是 ANR。
     *
     * ## 为什么只在"确实失败过"时才读
     *
     * 日志是**设备端**的 `/data/local/tmp/dbplay-install.log`，应用读不到
     * （`/data/local/tmp` 对 app 只有 `--x`），只能经 adb 取。代价不低，
     * 所以绝不放在正常路径上。
     */
    private fun explainFailedInstall() {
        if (!installer.canUseAdb()) return
        worker.execute {
            // ⚠️ 局部名**不能**叫 `log`：那会遮蔽本类的 [log] 出口
            // （PATCH-0095 加了那个属性之后，这里再叫 `log` 就没法写日志了）。
            val installLog = installer.readInstallLog().orEmpty()
            if (installLog.isBlank()) {
                log("读回设备端安装日志：空（通道没起来或日志文件不存在）")
                return@execute
            }
            val reason = CarInstallScript.parseFailureReason(installLog)
            log("读回设备端安装日志，失败原因：$reason")
            update { it.copy(message = "上次自动安装未成功：$reason") }
        }
    }

    /**
     * 恢复"上次已经下好、还没装"的包。
     *
     * 应用重启（比如用户切走又切回）后不该要求重下几十 MB。
     * 版本号 ≤ 当前版本的一律清掉——那说明已经装上了，或者包过期了。
     *
     * ⚠️ 2026-10-11：静默开关打开且该版本未被熔断时，恢复出来的阶段是
     * [CarUpdatePhase.PENDING_INSTALL]（等空闲窗口）而**不是** [CarUpdatePhase.READY]
     * （等用户点按钮）—— 否则"上次下载到一半被杀"就永远停在那儿等用户。
     */
    private fun restoreDownloaded() {
        val path = prefs.downloadedApkPath
        if (path.isEmpty()) return
        val file = File(path)
        val versionCode = prefs.downloadedVersionCode
        if (!file.exists() || versionCode <= appVersionCode) {
            val existed = file.exists()
            file.delete()
            prefs.downloadedApkPath = ""
            prefs.downloadedVersionCode = 0L
            log("清理无效安装包：$path（存在=$existed，包版本=$versionCode，本机=$appVersionCode）")
            return
        }
        val blocked = silentBlocked(versionCode, prefs.silentBlockedVersionCode)
        // ⚠️ 恢复前先校验（2026-10-15）：这个包可能是上次写到一半被杀留下的半截文件，
        // 也可能服务端已换包。若不校验就恢复成 PENDING_INSTALL，静默路径会拿它去装，
        // 失败一次还白烧一次熔断额度。坏包直接删掉，等下次检查重新下载。
        val apkError = installer.validateUpdateApk(file, versionCode)
        if (apkError != null) {
            file.delete()
            prefs.downloadedApkPath = ""
            prefs.downloadedVersionCode = 0L
            log("恢复已下载的包：$path 校验失败（$apkError）⇒ 已删除，等待重新下载")
            return
        }
        val silent = prefs.silentUpdateEnabled && !blocked
        log(
            "恢复已下载的包：$path（$versionCode）⇒ 阶段 " +
                "${if (silent) "PENDING_INSTALL（等空闲窗口）" else "READY（等用户点按钮）"}" +
                "（开关=${prefs.silentUpdateEnabled}，熔断=$blocked）",
        )
        snapshot = snapshot.copy(
            phase = if (silent) CarUpdatePhase.PENDING_INSTALL else CarUpdatePhase.READY,
            latestVersionCode = versionCode,
            progress = 1f,
            message = when {
                // 熔断命中：包留着让用户手动装，但话要说清楚。
                blocked -> SILENT_BLOCKED_MESSAGE
                silent -> PENDING_INSTALL_MESSAGE
                else -> "安装包已下载完成，可以安装"
            },
            // 静默路径**永远不弹窗**（这是它叫"静默"的全部意义）。
            dialogVisible = false,
        )
    }

    private fun downloadedApk(): File? {
        val path = prefs.downloadedApkPath
        if (path.isEmpty()) return null
        return File(path).takeIf { it.exists() && it.length() > 0L }
    }

    private fun apkFileFor(versionCode: Long): File {
        val dir = File(appContext.filesDir, UPDATE_DIR_NAME)
        return File(dir, "DBPlay-$versionCode.apk")
    }

    private fun publishInstallFailure(message: String) {
        update { it.copy(phase = CarUpdatePhase.FAILED, message = message, dialogVisible = true) }
    }

    private fun update(transform: (CarUpdateState) -> CarUpdateState): CarUpdateState =
        synchronized(lock) {
            snapshot = transform(snapshot)
            snapshot
        }

    private fun publish(
        onState: (CarUpdateState) -> Unit,
        transform: (CarUpdateState) -> CarUpdateState,
    ) {
        val latest = update(transform)
        mainHandler.post { onState(latest) }
    }

    private fun now(): String = TIME_FORMAT.format(Date())

    /**
     * 毫秒时间戳 → 可读文本；`<= 0` 一律当"从未"。
     *
     * ⚠️ 与 [now] 分开：`SimpleDateFormat.format(Date(0))` 会打出
     * `1970-01-01 08:00:00`（东八区），而本工程里 0 的语义**永远是**
     * "还没发生过"（`lastCheckAtMillis` / `file.lastModified()`）。
     * 照直格式化会让人以为"1970 年查过一次更新"。
     *
     * ⚠️ 这里**另建**一个 formatter，不复用 [TIME_FORMAT]：`SimpleDateFormat`
     * 不是线程安全的，而本方法会被**诊断包采集线程**调用，[now] 却跑在主线程 ——
     * 共用一个实例迟早打出乱码（PATCH-0095 才引入这个跨线程调用）。
     */
    private fun stamp(millis: Long): String =
        if (millis <= 0L) {
            "从未"
        } else {
            SimpleDateFormat(TIME_PATTERN, Locale.US).format(Date(millis))
        }

    private companion object {
        /** 安装包存放目录（相对 `filesDir`）。与 `file_paths.xml` 里的 `files-path` 对应。 */
        const val UPDATE_DIR_NAME = "update"

        /**
         * 静默路径"包已下好、在等空闲窗口"的文案。
         *
         * ⚠️ 要短：它最终会显示在「关于」页 `CarSettingRow.description` 里，
         * 而那一行是 `maxLines=3` **静默截断**（坑 §76，不补省略号）。
         */
        const val PENDING_INSTALL_MESSAGE = "已下载，将在未连接手机时自动安装"

        /** 熔断命中的文案。同样受 `maxLines=3` 限制，必须短。 */
        const val SILENT_BLOCKED_MESSAGE = "自动更新失败，请手动安装"

        /**
         * 时间格式串。
         *
         * 单独抽成常量是因为它现在有**两个使用者**：[TIME_FORMAT]（主线程，
         * 与上游既有写法一致）与 [stamp]（诊断采集线程，各自 new 实例，
         * 见那边的线程安全说明）。
         */
        const val TIME_PATTERN = "yyyy-MM-dd HH:mm:ss"

        val TIME_FORMAT = SimpleDateFormat(TIME_PATTERN, Locale.US)
    }
}
