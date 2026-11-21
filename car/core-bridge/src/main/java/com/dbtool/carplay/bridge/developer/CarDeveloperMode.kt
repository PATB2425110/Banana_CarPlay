package com.dbtool.carplay.bridge.developer

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.dbtool.carplay.api.developer.CarDeveloperEndpoints
import com.dbtool.carplay.api.developer.CarDeveloperHost
import com.dbtool.carplay.api.developer.CarDeveloperState
import com.dbtool.carplay.api.developer.CarLogScope
import com.dbtool.carplay.api.developer.CarLogUploadPhase
import com.dbtool.carplay.api.settings.CarSessionSettings
import com.dbtool.carplay.api.system.CarSystemAdapter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 开发者模式的实现：解锁状态 + 设备身份回显 + 采集打包上传。
 *
 * 线程模型与 `CarPlaySessionBridge` 一致——**回调风格 + 主线程投递**：
 * 采集与上传在单线程池里跑，每次阶段推进都 post 回主线程，
 * 由 `CarAppController` 写进 Compose 状态。全工程没有引入协程，
 * 这里也不破例。
 *
 * ⚠️ `internal`：只由 `CarAppServices` 构造，对外只暴露 `CarDeveloperHost`。
 * 构造参数里有 `internal` 的 [CarSystemShell]，public 类暴露它会编译不过 ——
 * 而这个类本来也不该被模块外直接 new。
 */
internal class CarDeveloperMode(
    context: Context,
    private val system: CarSystemAdapter,
    private val appVersionName: String,
    /**
     * 生效设置的读取器，采集时写进 `system/effective_settings.txt`。
     *
     * 用 `() ->` 而不是直接传一个 `CarSessionSettings`：设置页改完「保存并重连」之后
     * 不需要重新构造本对象，每次采集取到的都是**当下**的值。
     * 传 `CarSettingsStore::load` 即可（`applySettings` 先 save 再 updateSettings，
     * 所以持久化值就是会话正在用的值）。
     */
    private val settingsProvider: () -> CarSessionSettings,
    /**
     * 已就绪的 adb shell 通道（没有则返回 null）。契约见 [CarSystemShell]：
     * **只 `peek()`、不 `ensure()`** —— 采集诊断包不该顺手打开车机的「ADB 调试」。
     */
    private val systemShell: () -> CarSystemShell?,
    /**
     * **已提权**（`su 0`）的 adb shell 通道；没有 root、或通道未就绪时返回 null。
     *
     * 只给诊断包的 `system/wireless_snapshot.txt` 用：那一段要读厂商
     * `/vendor/etc/init/` 下的 init 脚本（车机自带热点由谁拉起、谁负责拆）——
     * 这些文件**只有 root 读得到**。
     *
     * 同 `systemShell` 的纪律：只 `peek()`、**绝不** `ensure()`。
     */
    private val rootShell: () -> CarSystemShell? = { null },
    /**
     * 后台静默更新的状态转储（PATCH-0095，2026-10-11），写进 `system/update_state.txt`。
     *
     * 为什么不塞进 `effective_settings.txt`：那个文件是
     * `CarDiagnosticSettingsDump.render(CarSessionSettings)` 的产物，而
     * 「后台自动更新」开关**刻意不在 `CarSessionSettings` 里**（它属于升级链路的
     * 私有 prefs，恢复出厂设置不该把它清掉）。理由详见
     * [CarDiagnosticPackager] 的同名参数。
     */
    private val updateStateProvider: () -> String = { "" },
    /**
     * 滚动日志记录器的运行时快照（PATCH-0095，2026-10-11）。
     *
     * 采集那一刻"记录器在不在跑 / 累计多少行 / 滚动几次"，每行进
     * `collection_manifest.txt`。理由与用法见
     * [CarDiagnosticPackager] 的同名参数。
     */
    private val recorderStatusProvider: () -> List<String> = { emptyList() },
) : CarDeveloperHost {

    private val appContext = context.applicationContext
    private val prefs = CarDeveloperPrefs(appContext)
    private val deviceInfo = CarDeviceInfo(appContext, system, prefs)

    @Volatile
    private var lastAirPlayTcpCapture: AirPlayTcpCaptureResult? = null

    private val packager =
        CarDiagnosticPackager(
            appContext,
            deviceInfo,
            settingsProvider,
            systemShell,
            rootShell,
            updateStateProvider,
            recorderStatusProvider,
            airPlayTcpCaptureProvider = { lastAirPlayTcpCapture },
        )
    private val uploader = CarDeviceLogUploader()

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 采集/上传串行执行：同时跑两趟只会让两趟都变慢，还互相抢 IO。 */
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "dbplay-developer").apply { isDaemon = true }
    }

    /** TCP 采集可与用户在主界面复现连接并行，但始终只有一份有界采集。 */
    private val tcpCaptureWorker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "dbplay-airplay-tcp-capture").apply { isDaemon = true }
    }

    private val tcpCaptureBusy = AtomicBoolean(false)

    /** 同一时刻只允许一趟采集上传。 */
    private val busy = AtomicBoolean(false)

    private val lock = Any()

    @Volatile
    private var snapshot: CarDeveloperState = buildCheapState()

    override fun state(): CarDeveloperState = snapshot

    private val audioScanBusy = AtomicBoolean(false)

    override fun scanAudioOutput(inputs: List<String>, onState: (CarDeveloperState) -> Unit): Boolean {
        if (!snapshot.unlocked || !audioScanBusy.compareAndSet(false, true)) return false
        val captured = inputs.toList()
        publish(onState) { it.copy(audioScanBusy = true, audioScanReport = "正在扫描音轨、路由与系统读取能力…") }
        worker.execute {
            val report = runCatching { CarAudioOutputScanner(appContext).scan(captured) }
                .getOrElse { "扫描失败：${it.javaClass.simpleName} ${it.message.orEmpty()}\n最终输出格式：未知" }
            audioScanBusy.set(false)
            publish(onState) { it.copy(audioScanBusy = false, audioScanReport = report) }
        }
        return true
    }

    // ---------------------------------------------------------------- 解锁

    override fun unlock(): CarDeveloperState {
        prefs.unlocked = true
        return update { it.copy(unlocked = true, message = "开发者模式已开启") }
    }

    override fun lock(): CarDeveloperState {
        prefs.unlocked = false
        return update { it.copy(unlocked = false, message = "") }
    }

    // ---------------------------------------------------------------- 回显

    /**
     * 重算设备号与系统信息。**会跑 `getprop` / `dumpsys`，必须放在后台线程**。
     */
    override fun refresh(): CarDeveloperState {
        val info = deviceInfo.systemInfo(forceRefresh = false)
        return update {
            it.copy(
                unlocked = prefs.unlocked,
                deviceId = deviceInfo.deviceId(),
                serverUrl = CarDeveloperEndpoints.LOG_UPLOAD_URL,
                androidVersion = info.androidVersion,
                systemVersion = info.systemVersion,
                deviceModel = info.deviceModel,
            )
        }
    }

    /** 强制重读系统信息（用户点"重新读取"时用），比 [refresh] 更慢。 */
    fun refreshSystemInfo(force: Boolean = true): CarDeveloperState {
        val info = deviceInfo.systemInfo(forceRefresh = force)
        return update {
            it.copy(
                androidVersion = info.androidVersion,
                systemVersion = info.systemVersion,
                deviceModel = info.deviceModel,
            )
        }
    }

    /**
     * 后台重算 + 主线程回调。
     *
     * 复用 [worker] 而不是另起线程：它同时也在跑采集上传，
     * 串行反而更安全（两个 `dumpsys` 并行只会互相拖慢）。
     */
    override fun refreshAsync(onState: (CarDeveloperState) -> Unit) {
        worker.execute {
            val latest = runCatching { refresh() }.getOrElse { snapshot }
            mainHandler.post { onState(latest) }
        }
    }

    // ------------------------------------------------------------ 采集上传

    override fun collectAndUpload(
        scope: CarLogScope,
        includeClientIpv4: Boolean,
        onState: (CarDeveloperState) -> Unit,
    ): Boolean {
        if (tcpCaptureBusy.get() || !busy.compareAndSet(false, true)) return false
        publish(onState) {
            it.copy(
                phase = CarLogUploadPhase.COLLECTING,
                progress = 0f,
                message = "准备采集（${scope.title}）…",
            )
        }
        worker.execute {
            try {
                val packed = packager.collectAndPack(
                    scope = scope,
                    appVersionName = appVersionName,
                    includeClientIpv4 = includeClientIpv4,
                    onStage = { stage ->
                        publish(onState) { it.copy(phase = CarLogUploadPhase.COLLECTING, message = stage) }
                    },
                )
                publish(onState) {
                    it.copy(
                        phase = CarLogUploadPhase.PACKAGING,
                        message = "已打包 ${packed.entryCount} 个文件（" +
                            "${packed.zipFile.length() / 1024} KB）",
                    )
                }
                publish(onState) {
                    it.copy(
                        phase = CarLogUploadPhase.UPLOADING,
                        progress = 0f,
                        message = "正在上传…",
                        lastFileName = packed.zipFile.name,
                    )
                }

                val info = deviceInfo.systemInfo(forceRefresh = false)
                val result = uploader.uploadDiagnostic(
                    url = CarDeveloperEndpoints.LOG_UPLOAD_URL,
                    appId = CarDeveloperEndpoints.LOG_APP_ID,
                    device = deviceInfo.deviceId(),
                    model = info.deviceModel,
                    androidRelease = info.androidVersion,
                    app = appVersionName,
                    note = "${scope.wireValue} ${packed.zipFile.name}",
                    zipFile = packed.zipFile,
                    timeoutSeconds = CarDeveloperEndpoints.UPLOAD_TIMEOUT_SECONDS,
                ) { progress ->
                    publish(onState) {
                        it.copy(
                            phase = CarLogUploadPhase.UPLOADING,
                            progress = progress,
                            message = "正在上传 ${(progress * 100).toInt()}%",
                        )
                    }
                }

                if (result.success) {
                    val now = System.currentTimeMillis()
                    prefs.lastLogId = result.logId
                    prefs.lastFileName = packed.zipFile.name
                    prefs.lastUploadAtMillis = now
                    publish(onState) {
                        it.copy(
                            phase = CarLogUploadPhase.DONE,
                            progress = 1f,
                            message = if (result.logId.isEmpty()) {
                                "已上传到日志服务，应用目录 ${CarDeveloperEndpoints.LOG_APP_ID}"
                            } else {
                                "上传成功，后台日志编号 ${result.logId}"
                            },
                            lastLogId = result.logId,
                            lastFileName = packed.zipFile.name,
                            lastUploadAt = formatTime(now),
                        )
                    }
                } else {
                    publish(onState) {
                        it.copy(
                            phase = CarLogUploadPhase.FAILED,
                            progress = 0f,
                            message = result.message,
                        )
                    }
                }
            } catch (error: Throwable) {
                publish(onState) {
                    it.copy(
                        phase = CarLogUploadPhase.FAILED,
                        progress = 0f,
                        message = "采集失败：${error.javaClass.simpleName} ${error.message.orEmpty()}".trim(),
                    )
                }
            } finally {
                busy.set(false)
            }
        }
        return true
    }

    override fun startAirPlayTcpCapture(onState: (CarDeveloperState) -> Unit): Boolean {
        if (busy.get() || !tcpCaptureBusy.compareAndSet(false, true)) return false
        if (busy.get()) {
            tcpCaptureBusy.set(false)
            return false
        }
        publish(onState) {
            it.copy(
                airPlayTcpCaptureActive = true,
                airPlayTcpCaptureStatus = "正在检查 root ADB 与 tcpdump…",
            )
        }
        tcpCaptureWorker.execute {
            val result = runCatching { AirPlayTcpCapture(rootShell).capture() }
                .getOrElse { error ->
                    AirPlayTcpCaptureResult(
                        status = AirPlayTcpCaptureResult.Status.FAILED,
                        startedAt = formatTime(System.currentTimeMillis()),
                        detail = "抓包失败：${error.javaClass.simpleName} ${error.message.orEmpty()}".trim(),
                    )
                }
            lastAirPlayTcpCapture = result
            tcpCaptureBusy.set(false)
            publish(onState) {
                it.copy(
                    airPlayTcpCaptureActive = false,
                    airPlayTcpCaptureStatus = result.detail,
                )
            }
        }
        return true
    }

    // ---------------------------------------------------------------- 内部

    /** 构造阶段的轻量快照：只读 prefs，不跑 shell。 */
    private fun buildCheapState(): CarDeveloperState {
        val cached = prefs.readSystemInfo(System.currentTimeMillis())
        return CarDeveloperState(
            unlocked = prefs.unlocked,
            deviceId = runCatching { deviceInfo.deviceId() }.getOrDefault(""),
            serverUrl = CarDeveloperEndpoints.LOG_UPLOAD_URL,
            appVersionName = appVersionName,
            androidVersion = cached?.androidVersion.orEmpty(),
            systemVersion = cached?.systemVersion.orEmpty(),
            deviceModel = cached?.deviceModel.orEmpty(),
            lastLogId = prefs.lastLogId,
            lastFileName = prefs.lastFileName,
            lastUploadAt = prefs.lastUploadAtMillis.takeIf { it > 0L }?.let(::formatTime).orEmpty(),
            airPlayTcpCaptureActive = tcpCaptureBusy.get(),
            airPlayTcpCaptureStatus = lastAirPlayTcpCapture?.detail ?: "未启动",
        )
    }

    private fun update(transform: (CarDeveloperState) -> CarDeveloperState): CarDeveloperState =
        synchronized(lock) {
            snapshot = transform(snapshot)
            snapshot
        }

    /** 更新快照并把最新值投递到主线程。 */
    private fun publish(onState: (CarDeveloperState) -> Unit, transform: (CarDeveloperState) -> CarDeveloperState) {
        val latest = update(transform)
        mainHandler.post { onState(latest) }
    }

    private fun formatTime(millis: Long): String = TIME_FORMAT.format(Date(millis))

    private companion object {
        val TIME_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    }
}
