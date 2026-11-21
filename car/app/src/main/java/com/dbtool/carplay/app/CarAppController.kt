package com.dbtool.carplay.app

import com.dbtool.carplay.api.display.DialTransform
import com.dbtool.carplay.api.display.ClusterDialLayout
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.Surface
import android.view.View
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import com.dbtool.carplay.android.AndroidCarInputAdapter
import com.dbtool.carplay.api.CarPlatform
import com.dbtool.carplay.api.CarPlatformRegistry
import com.dbtool.carplay.api.developer.CarDebugTunnelStatus
import com.dbtool.carplay.api.developer.CarDeveloperHost
import com.dbtool.carplay.api.developer.CarLogScope
import com.dbtool.carplay.api.developer.CarSensitiveText
import com.dbtool.carplay.api.developer.DEVELOPER_UNLOCK_TAP_COUNT
import com.dbtool.carplay.api.display.CarClusterDisplaySelection
import com.dbtool.carplay.api.display.CarClusterFlipControl
import com.dbtool.carplay.api.display.CarClusterFullMapControl
import com.dbtool.carplay.api.display.CarClusterPresenter
import com.dbtool.carplay.api.display.CarClusterSafeAreaInsets
import com.dbtool.carplay.api.display.CarClusterSafeAreaPolicy
import com.dbtool.carplay.api.display.CarClusterSpec
import com.dbtool.carplay.api.display.CarHudDisplayMode
import com.dbtool.carplay.api.display.CarHudDisplayProfile
import com.dbtool.carplay.api.display.CarHudProjection
import com.dbtool.carplay.api.display.CarHudStartResult
import com.dbtool.carplay.api.display.CarVideoCanvas
import com.dbtool.carplay.api.display.AltScreenSlotDecision
import com.dbtool.carplay.api.display.AltScreenSlotPolicy
import com.dbtool.carplay.api.display.HudScaleSpec
import com.dbtool.carplay.api.display.ClusterDrawRegion
import com.dbtool.carplay.api.display.ClusterLayerMode
import com.dbtool.carplay.api.display.ClusterLayerVisibilityPolicy
import com.dbtool.carplay.api.display.ClusterVideoTransform
import com.dbtool.carplay.api.display.theme.ClusterAppearanceMode
import com.dbtool.carplay.api.hotspot.CarAdbHotspotDefaults
import com.dbtool.carplay.api.hotspot.CarAdbChannelStatus
import com.dbtool.carplay.api.hotspot.CarAdbInitializationState
import com.dbtool.carplay.api.hotspot.CarHotspotEnabledState
import com.dbtool.carplay.api.media.CarMediaControlCommand
import com.dbtool.carplay.api.media.CarNowPlaying
import com.dbtool.carplay.api.navigation.CarNavigationRequest
import com.dbtool.carplay.api.navigation.CarNavigationSnapshot
import com.dbtool.carplay.api.navigation.CarNavigationTarget
import com.dbtool.carplay.api.session.CarReconnectState
import com.dbtool.carplay.api.session.CarReconnectStatus
import com.dbtool.carplay.api.session.CarSessionEndReason
import com.dbtool.carplay.api.session.CarSessionEvent
import com.dbtool.carplay.api.session.CarSessionHost
import com.dbtool.carplay.api.session.CarSessionListener
import com.dbtool.carplay.api.session.CarSessionPhase
import com.dbtool.carplay.api.session.CarSessionState
import com.dbtool.carplay.api.session.CarTouchContact
import com.dbtool.carplay.api.settings.CarSessionSettings
import com.dbtool.carplay.api.settings.forAppleCarPlay
import com.dbtool.carplay.api.settings.withReadCarHotspot
import com.dbtool.carplay.api.settings.CarSettingsReconnectPolicy
import com.dbtool.carplay.api.settings.CarSettingsSafeArea
import com.dbtool.carplay.api.settings.ClusterLayerModeSetting
import com.dbtool.carplay.api.projection.CarPlayClusterProjection
import com.dbtool.carplay.api.projection.ClusterVitals
import com.dbtool.carplay.api.update.CarInstallOutcome
import com.dbtool.carplay.api.update.CarUpdateHost
import com.dbtool.carplay.api.update.CarUpdatePhase
import com.dbtool.carplay.bridge.CarAppServices
import com.dbtool.carplay.bridge.CarHotspotBriefing
import com.dbtool.carplay.bridge.hotspot.HotspotBriefingCache
import com.dbtool.carplay.bridge.session.AUTO_RECONNECT_MAX_ATTEMPTS
import com.dbtool.carplay.bridge.session.AUTO_RECONNECT_STABLE_RESET_MILLIS
import com.dbtool.carplay.bridge.session.autoReconnectDelayMillis
import com.dbtool.carplay.bridge.session.exhaustedReconnectStatus
import com.dbtool.carplay.bridge.session.shouldAutoReconnect
import com.dbtool.carplay.bridge.session.shouldExhaustAutoReconnect
import com.dbtool.carplay.bridge.update.SILENT_INSTALL_IDLE_MILLIS
import com.dbtool.carplay.bridge.update.advanceIdleClock
import com.dbtool.carplay.bridge.update.idleWindowSettled
import com.dbtool.carplay.bridge.vehicle.CarVehicleSignalPoller
import com.dbtool.carplay.bridge.vehicle.EcarxVfhudChannel
import com.dbtool.carplay.bridge.vehicle.EcarxVehicleSignals
import com.dbtool.carplay.bridge.vehicle.HudCallResult
import com.dbtool.carplay.bridge.vehicle.HudChannelAvailability
import com.dbtool.carplay.ecarx.bluetooth.EcarxBluetoothAudioGuard
import com.dbtool.carplay.geely.ClusterLayerAutoAttach
import com.dbtool.carplay.geely.lynk.toUiStatus
import com.dbtool.carplay.ui.CarClusterCandidateSummary
import com.dbtool.carplay.ui.CarClusterFullMapEventUi
import com.dbtool.carplay.ui.CarClusterFullMapUi
import com.dbtool.carplay.ui.CarClusterLayerStatus
import com.dbtool.carplay.ui.CarUiActions
import com.dbtool.carplay.ui.CarUiModel
import com.dbtool.carplay.ui.CarUiStateMapper
import com.dbtool.carplay.ui.components.CarDockApp
import com.dbtool.carplay.ui.components.CarDockAppChoice
import com.dbtool.carplay.ui.components.CarDockMedia
import com.dbtool.carplay.ui.components.CarDockTemperature
import com.dbtool.carplay.api.vehicle.CarVehicleSnapshot
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 底栏应用位的槽位数。
 *
 * 取 5 是照参考图（那里正好 5 个）。"用户可配"还没做，见
 * `DBPlay-底栏Dock-设计方案-20261011.md` §0-3。
 */
private const val DOCK_APP_SLOT_COUNT = 5

/**
 * 重连发起方（唯一入口 [CarAppController.requestReconnect] 的参数）。
 *
 * 只有 [USER] 会重置自动重连计数；其余来源都不能把它当成"一次新的断线事件"：
 * - [AUTO]：自动重连到点，计数要继续累加（退避才有意义）；
 * - [CANVAS]：画布尺寸变化后的分辨率协商，不是断线；
 * - [SETTINGS]：设置/身份/图标变更后的重建，也不是断线。
 */
private enum class ReconnectSource {
    USER,
    AUTO,
    CANVAS,
    SETTINGS,
}

/**
 * 连接 UI 与 CarPlay 会话的中间层（相当于 ViewModel 的角色）。
 *
 * 位置：**进程级单例**，挂在 [CarPlayApplication] 上。
 * 车机上的 CarPlay 会话必须跨 Activity 重建、跨页面切换存活，
 * 因此它不能是 Activity 作用域的对象。
 *
 * 职责边界（这是"UI 不直接操作 Core"的落点）：
 * - 实现 [CarUiActions]：把界面意图翻译成对 [CarSessionHost] / 设置存储的调用；
 * - 实现 [CarSessionListener]：把会话状态/事件翻译成 [CarUiModel]；
 * - 所有 Compose 状态通过 [model] 单向输出，界面只读不写。
 *
 * 界面不知道 `CarPlayController`、`AndroidMediaSink`、`CarPlayStatus` 的存在，
 * 甚至不知道 `:shared` 的存在——它只认识 `CarUiModel` 与 `CarUiActions`。
 */
class CarAppController(
    private val app: Application,
    private val services: CarAppServices,
) : CarUiActions, CarSessionListener {

    private val platform: CarPlatform = CarPlatformRegistry.current()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 底栏应用位缓存；只在「显示底栏」打开时才加载（见 [refreshDockApps]）。 */
    private var dockAppsCache: List<CarDockApp> = emptyList()

    /** 是否已经查过一次 —— 避免每次 [refreshModel] 都往后台队列排一次查询。 */
    private var dockAppsLoaded = false

    /** 底栏是否处于编辑态（纯界面态，不落盘）。 */
    private var dockEditMode = false

    /** "选应用"面板：可见性、候选缓存、是否已查过。 */
    private var dockAppPickerVisible = false
    private var dockAppChoices: List<CarDockAppChoice> = emptyList()
    private var dockAppChoicesLoaded = false

    /** 底栏媒体卡的数据快照（由订阅回调更新；null = 没有在播 / 没数据）。 */
    private var dockNowPlaying: CarNowPlaying? = null

    /** 「正在播放」的退订句柄；null = 当前没订阅。 */
    private var nowPlayingDetach: (() -> Unit)? = null

    /** Latest route snapshot and its app-local freshness clock. Main-thread only. */
    private var navigationSnapshot: CarNavigationSnapshot? = null
    private var navigationLastReceivedUptimeMillis: Long = 0L
    private var navigationDetach: (() -> Unit)? = null
    private var phonePowerDetach: (() -> Unit)? = null
    private var phonePowerLevel: com.dbtool.carplay.api.session.CarPhonePower? = null

    private fun observePhonePowerSource() {
        if (phonePowerDetach != null) return
        val source = runCatching { session.phonePowerSource() }.getOrNull() ?: return
        phonePowerDetach = source.observe { value ->
            mainHandler.post {
                if (phonePowerLevel != value) {
                    phonePowerLevel = value
                    refreshModel()
                }
            }
        }
    }

    /**
     * 封面缓存（字节 → 位图）。
     *
     * 按**字节内容**比对本首歌是否变过：`artworkBytes` 每次回调都是同一份引用时
     * 直接命中缓存，换歌才重新解码 —— 否则每帧解一次 JPEG，主线程直接卡死。
     */
    private var dockArtworkCache: Pair<ByteArray, androidx.compose.ui.graphics.ImageBitmap>? = null

    /**
     * 订阅 / 退订「正在播放」。
     *
     * 与 [refreshDockApps] 同一套思路：**只在打开了底栏时才订阅**，且用句柄守卫
     * 避免每次 `refreshModel` 都重新 observe（那会越订越多）。
     */
    private fun refreshNowPlayingSubscription() {
        if (!settings.dockBar && !(settings.immersiveNavigationEnabled && settings.immersiveGlassCardsEnabled)) {
            nowPlayingDetach?.invoke()
            nowPlayingDetach = null
            dockNowPlaying = null
            return
        }
        if (nowPlayingDetach != null) return
        val source = runCatching { session.nowPlayingSource() }.getOrNull() ?: return
        nowPlayingDetach = source.observe { value ->
            mainHandler.post {
                dockNowPlaying = value
                refreshModel()
            }
        }
    }

    /** Subscribe once; the source immediately reports its current session snapshot. */
    private fun observeNavigationSource() {
        if (navigationDetach != null) return
        val source = runCatching { session.navigationSource() }.getOrNull() ?: return
        navigationDetach = source.observe { value ->
            mainHandler.post { acceptNavigationSnapshot(value) }
        }
    }

    private fun acceptNavigationSnapshot(value: CarNavigationSnapshot?) {
        val next = value?.takeIf { it.hasContent }
        val previous = navigationSnapshot
        val visibleFieldsChanged = previous.navigationCardKey() != next.navigationCardKey()
        // 仪表卡片比主页卡片多一个转向箭头：`maneuverType` 变了也要立刻重画。
        val clusterFieldsChanged =
            previous.navigationClusterKey() != next.navigationClusterKey()

        mainHandler.removeCallbacks(navigationExpiryCheck)
        navigationSnapshot = next
        if (next == null) {
            navigationLastReceivedUptimeMillis = 0L
        } else {
            navigationLastReceivedUptimeMillis = SystemClock.uptimeMillis()
            mainHandler.postDelayed(navigationExpiryCheck, NAVIGATION_SNAPSHOT_TTL_MILLIS)
        }
        // iAP2 can publish internal route revisions at a high rate. Refresh the full
        // Compose model only when a field shown on the card actually changes.
        if (visibleFieldsChanged) refreshModel()
        // 仪表卡片走投影层契约立即重拉一次；吉利 presenter 没有注册成 active，
        // 由它自己的 200ms tick 兜底（最多晚 200ms）。
        if (clusterFieldsChanged) CarPlayClusterProjection.notifyNavigationChanged()
    }

    /**
     * 仪表中央卡片的内容键：比主页卡片多 `maneuverType`（转向箭头），
     * 少 `routeSeconds`（仪表不显示剩余时间）。
     */
    private fun CarNavigationSnapshot?.navigationClusterKey(): List<Any?>? = this?.let {
        listOf(
            it.routeMeters,
            it.segmentMeters,
            it.currentRoad,
            it.nextRoad,
            it.maneuverDescription,
            it.maneuverType,
            it.destination,
            it.arrivalEpochSeconds,
            it.routeActive,
            it.lanes,
            it.sessionId,
            it.routeGeneration,
        )
    }

    /** A stream that goes silent forever must not leave stale turn guidance on screen. */
    private fun expireNavigationIfNeeded() {
        if (navigationSnapshot == null) return
        val elapsed = SystemClock.uptimeMillis() - navigationLastReceivedUptimeMillis
        val remaining = NAVIGATION_SNAPSHOT_TTL_MILLIS - elapsed
        if (remaining > 0L) {
            mainHandler.postDelayed(navigationExpiryCheck, remaining)
            return
        }
        navigationSnapshot = null
        navigationLastReceivedUptimeMillis = 0L
        refreshModel()
        // 立即清掉仪表中央卡片；不等下一次 200ms tick。
        CarPlayClusterProjection.notifyNavigationChanged()
    }

    /**
     * `CarNowPlaying` → 底栏媒体卡要的那份快照。
     *
     * 没有歌名也没有歌手时返回 null（组件会整块不渲染）—— 那说明 iPhone 侧
     * 没在放媒体，或还没推过来，显示一张空卡比不显示更糟。
     */
    private fun dockMediaOf(now: CarNowPlaying?): CarDockMedia? {
        if (now == null || !now.hasContent) return null
        val progress = if (now.durationMillis > 0L) {
            (now.positionMillis.toFloat() / now.durationMillis.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
        return CarDockMedia(
            title = now.title.ifBlank { now.artist },
            isPlaying = now.isPlaying,
            progress = progress,
            artwork = dockArtworkOf(now.artworkBytes),
        )
    }

    private fun dockArtworkOf(bytes: ByteArray?): androidx.compose.ui.graphics.ImageBitmap? {
        if (bytes == null || bytes.isEmpty()) return null
        dockArtworkCache?.let { (cached, bitmap) ->
            if (cached.contentEquals(bytes)) return bitmap
        }
        val decoded = runCatching {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull() ?: return null
        dockArtworkCache = bytes to decoded
        return decoded
    }

    // ------------------------------------------------------------------ 底栏

    /**
     * 按需加载底栏应用位。
     *
     * 三条约束：
     * 1. **关着的时候一次都不查**：`PackageManager` 查询 + `Drawable` 转位图都不便宜，
     *    而底栏出厂是关的；[dockAppsLoaded] 保证每次打开只查一遍；
     * 2. **必须离开主线程**：转位图是 CPU 活，借用 [briefingExecutor]（就是个单线程
     *    后台队列，为一次图标查询再开一条线程不值得），算完 `mainHandler` 回投；
     * 3. **不监听应用装卸**：车机上装/卸极少发生，下次启动或下次改设置就会重查 ——
     *    为此挂一个 `BroadcastReceiver` 属于给罕见事件付常驻成本。
     */
    private fun refreshDockApps() {
        if (!settings.dockBar) {
            if (dockAppsCache.isNotEmpty()) {
                dockAppsCache = emptyList()
                dockAppsLoaded = false
                refreshModel()
            }
            return
        }
        if (dockAppsLoaded) return
        dockAppsLoaded = true
        briefingExecutor.execute {
            val loaded = loadDockApps()
            mainHandler.post {
                dockAppsCache = loaded
                refreshModel()
            }
        }
    }

    /**
     * 查一遍"可启动的应用"并转成底栏条目。
     *
     * 只取前 [DOCK_APP_SLOT_COUNT] 个，且**排除自己**（底栏的应用位是"去别的应用"）。
     * 按包名去重：同一包多个 launcher activity 时只留第一个。
     */
    private fun loadDockApps(): List<CarDockApp> {
        val packageManager = app.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = runCatching { packageManager.queryIntentActivities(launcher, 0) }
            .getOrDefault(emptyList())
            .mapNotNull { it.activityInfo }
            .filter { it.packageName != app.packageName }
            .distinctBy { it.packageName }
        // 用户配过的顺序优先（靠前的占位），剩下的按系统给出的顺序补位；
        // 配过的包里已卸载的那些自然匹配不上 ⇒ 静默剔除，不占位也不报错。
        val pinned = settings.dockAppOrder.mapNotNull { pkg ->
            resolved.firstOrNull { it.packageName == pkg }
        }
        val rest = resolved.filterNot { settings.dockAppOrder.contains(it.packageName) }
        return (pinned + rest)
            .take(DOCK_APP_SLOT_COUNT)
            .map { info ->
                CarDockApp(
                    key = info.packageName,
                    // 标签与图标都可能抛（个别车机应用的资源是坏的）⇒ 逐项降级，
                    // 不能让一个坏应用把整条底栏打掉。
                    label = runCatching { info.loadLabel(packageManager).toString() }
                        .getOrDefault(info.packageName),
                    icon = runCatching { info.loadIcon(packageManager).toBitmap().asImageBitmap() }
                        .getOrNull(),
                )
            }
            .toList()
    }

    override fun onDockHomeClick() {
        val home = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { app.startActivity(home) }
            .onFailure { appendLog("底栏：打开桌面失败：${it.message}") }
    }

    override fun onDockAppClick(key: String) {
        val launch = app.packageManager.getLaunchIntentForPackage(key) ?: run {
            appendLog("底栏：$key 没有可启动的入口")
            return
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { app.startActivity(launch) }
            .onFailure { appendLog("底栏：启动 $key 失败：${it.message}") }
    }

    /**
     * 底栏媒体卡：播放 / 暂停。
     *
     * 判断依据是**数据源自己的 `current()`**，不是界面上的那份快照 ——
     * 界面快照可能是上一帧的（甚至因为没订阅而是空的），拿它决定发哪条命令
     * 会在"刚连上/刚切歌"那一瞬发反。发令必须是显式命令，不能是 toggle。
     */
    override fun onDockMediaToggle() {
        val source = runCatching { session.nowPlayingSource() }.getOrNull() ?: return
        val playing = runCatching { source.current()?.isPlaying == true }.getOrDefault(false)
        if (playing) source.pause() else source.play()
    }

    /** 底栏媒体卡：下一曲。 */
    override fun onDockMediaNext() {
        runCatching { session.nowPlayingSource()?.next() }
    }

    override fun onImmersiveMediaPrevious() {
        runCatching { session.nowPlayingSource()?.previous() }
    }

    /**
     * 车机侧下发的**显式**媒体命令（2026-10-19 新增）。
     *
     * 来源是车机以 `ecarx.intent.broadcast.action.MEDIA_CONTROL_RECEIVER` 发出的
     * 隐式广播，由 [io.dushu.car.receiver.MediaControlReceiver] 收下后转到这里。
     * 与底栏的 [onDockMediaToggle] 是**两个入口、同一条出口**：
     * 底栏只知道"用户点了那颗按钮"，要自己判断当前是播还是停；
     * 车机给的是明确命令（PLAY / PAUSE / NEXT / PREVIOUS），所以这里**不做 toggle**。
     *
     * 会话没起来 / 媒体源还没连上时是安全空操作 —— 此时车机卡片本来也不该有内容。
     *
     * ⚠️ 不要把它合并进 [onDockMediaToggle]：那条路径会读 `current()` 反推方向，
     * 而广播里已经带了方向，再推一次只会在"刚切歌那一瞬"发反。
     */
    fun onCarMediaControl(command: CarMediaControlCommand) {
        val source = runCatching { session.nowPlayingSource() }.getOrNull() ?: return
        runCatching {
            when (command) {
                CarMediaControlCommand.PLAY -> source.play()
                CarMediaControlCommand.PAUSE -> source.pause()
                CarMediaControlCommand.NEXT -> source.next()
                CarMediaControlCommand.PREVIOUS -> source.previous()
            }
        }.onFailure { appendLog("车机媒体命令 $command 执行失败：${it.message}") }
    }

    override fun onDockEnterEdit() {
        if (!settings.dockBar) return
        dockEditMode = true
        refreshModel()
    }

    override fun onDockExitEdit() {
        if (!dockEditMode) return
        dockEditMode = false
        refreshModel()
    }

    /**
     * 拖拽落位 → 存进 `dockAppOrder`。
     *
     * 走 [applySettings]（`reconnect = false`）而不是直接写 store：顺序变了要连带
     * ①刷模型 ②让应用行按新顺序重取（`dockAppsLoaded` 置回 false），三件事分头做迟早漏一件。
     * **不重连** —— 改的是界面配置，与 CarPlay 会话无关。
     */
    override fun onDockReorder(order: List<String>) {
        if (order == settings.dockAppOrder) return
        dockAppsLoaded = false
        dockAppsCache = emptyList()
        applySettings(settings.copy(dockAppOrder = order), reconnect = false)
    }

    override fun onDockOpenAppPicker() {
        if (!settings.dockBar) return
        dockAppPickerVisible = true
        refreshModel()
        if (dockAppChoicesLoaded) return
        dockAppChoicesLoaded = true
        // 与底栏图标同一条理由：查询 + 取标签都离开主线程，算完回投。
        briefingExecutor.execute {
            val all = loadLaunchableApps()
            mainHandler.post {
                dockAppChoices = all
                refreshModel()
            }
        }
    }

    override fun onDockAppPickerDismiss() {
        if (!dockAppPickerVisible) return
        dockAppPickerVisible = false
        refreshModel()
    }

    override fun onDockAppPickerConfirm(keys: List<String>) {
        dockAppPickerVisible = false
        dockAppsLoaded = false
        dockAppsCache = emptyList()
        applySettings(settings.copy(dockAppOrder = keys), reconnect = false)
    }

    /**
     * 全量可启动应用（只要 key + 标签，不解图标）。
     *
     * 与 [loadDockApps] 分开是有意的：这一个为"列表"服务（要全、要快），
     * 那一个为"底栏几个槽"服务（要图标、要按配置排序）。
     */
    private fun loadLaunchableApps(): List<CarDockAppChoice> {
        val packageManager = app.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return runCatching { packageManager.queryIntentActivities(launcher, 0) }
            .getOrDefault(emptyList())
            .mapNotNull { it.activityInfo }
            .filter { it.packageName != app.packageName }
            .distinctBy { it.packageName }
            .map { info ->
                CarDockAppChoice(
                    key = info.packageName,
                    label = runCatching { info.loadLabel(packageManager).toString() }
                        .getOrDefault(info.packageName),
                )
            }
            .sortedBy { it.label }
    }

    /**
     * ⚠️ **临时**：底栏的示例数据（两侧温度 + 媒体卡）。
     *
     * 媒体与温度的**数据源都还没接**（媒体要在 `CarSessionHost` 上开接缝、
     * 温度得先上实车探权限 —— 见 `DBPlay-底栏Dock-设计方案-20261011.md` §7），
     * 但 UI 要按**最终形态**验收 ⇒ 开发者模式下先用示例值把版面摆出来。
     *
     * **只在开发者模式打开时生效**：真实用户永远看不到假温度。
     * 真数据接上（或决定不接）之后，本函数连同这三个返回值一起删掉。
     */
    private fun dockSampleData(): Triple<CarDockTemperature?, CarDockTemperature?, CarDockMedia?> {
        if (!settings.dockBar || !developerHost.state().unlocked) return Triple(null, null, null)
        return Triple(
            CarDockTemperature(celsius = 23.5f),
            CarDockTemperature(celsius = 23.5f),
            CarDockMedia(title = "Dance with", isPlaying = true, progress = 0.74f),
        )
    }

    /**
     * 仪表副屏呈现器（2026-10-07，吉利 HDMI2 移植）。
     *
     * 只有平台声明了副屏能力（`platform.cluster != null`）时才非空。
     * 本轮**没有**把副屏做成可开关的设置项——用户没要求，而且多一个开关就多一处
     * "声明了副屏却没窗口接"的错配空间。有屏就用、没屏就 null。
     */
    private val clusterPresenter: CarClusterPresenter? = platform.cluster

    /**
     * HUD 地图画面投屏（2026-10-22）。只有吉利平台声明了该能力时才非空。
     *
     * 与 `CarHudBridge`（转向提示）是两条链，各自独立开关。
     */
    private val hudProjection: CarHudProjection? = platform.hudProjection

    /** 共享 111 槽位策略：HUD 独立请求时必须能拿到副屏 spec，否则 iPhone 不开 111。 */
    private val altScreenSlotPolicy = AltScreenSlotPolicy()

    /** HUD 窗口当前是否由本层拉起（用于「关开关时只收一次」与日志）。 */
    private var hudProjectionActive = false





    /**
     * 缓存的副屏声明规格。
     *
     * 副屏尺寸在"屏插拔"之外不会变，而 [resolveClusterSpec] 每次建栈都会问一次，
     * 因此这里缓存一份。屏真的变了由 `presenter.onSpecChanged` / 重建会话处理，
     * 不需要在本层再开观察者（本层没有生命周期，观察者无处注销）。
     *
     * 用 [Lazy] 而不是 `by lazy`：`by lazy` 一旦求值就锁死，而本值在
     * "首次建栈时屏还没枚举出来"的情况下需要能重算。
     */
    private var pendingClusterSpec: CarClusterSpec? = null

    /**
     * 固定屏不在场时，自动挑中的副屏 id（-1 = 没挑过）。**只存在内存里，不落盘。**
     *
     * 固定屏 displayId=2 每次都会优先重新确认；只有确认它不在候选里，才复用这里
     * 缓存的自动挑选结果。该值不写回 `CarSessionSettings.clusterDisplayId`
     * （该设置字段只做诊断留痕），避免运行期选择触发无谓的设置保存。
     */
    private var autoPickedClusterDisplayId: Int = -1

    /**
     * 模式要求显示主题、但设置变更时会话还没起来 ⇒ 等会话可用后补建一次窗。
     *
     * 只在主线程读写（设置监听与会话状态回调都在主线程）。
     */
    private var pendingThemeClusterMode: ClusterLayerMode? = null

    private val _model = mutableStateOf(CarUiModel())

    @Volatile
    private var adbInitializationState = services.adbInitializationState()

    /**
     * **首次启动引导**是否已经弹过（2026-10-08）。
     *
     * 存在独立的 `SharedPreferences` 里，**不塞进 `CarSessionSettings`**：
     * 它不是一条"用户设置"（没有对应的设置项、用户也没法改），
     * 而是一块"这应用在这台车上用过没有"的一次性标记。
     * 塞进设置里会让它出现在导出/重置设置的语义里，那是错的。
     *
     * ⚠️ `by lazy` 而非直接赋值：`init` 块按**文本顺序**执行，
     * 而这里的初始化必须早于下面那个会调 `refreshModel()` 的 init。
     * 用 lazy 可以不受声明顺序约束（首次访问时才初始化），
     * 少一类"顺序踩错"的坑。
     */
    private val guidePrefs by lazy {
        app.getSharedPreferences("dbplay_first_launch", android.content.Context.MODE_PRIVATE)
    }

    private val KEY_HAS_SEEN_HOTSPOT_GUIDE = "has_seen_hotspot_guide_v1"

    /**
     * 首次启动引导是否该显示（2026-10-08）。
     *
     * ## ⚠️ 必须在 [refreshModel] 之前就绪
     *
     * 类里**下面**还有一个 `init` 块（`session.addListener` 那个）会调
     * `refreshModel()`。Kotlin 的 `init` 块按**文本顺序**执行，所以这一段的
     * 声明与赋值必须排在那个 init **之前**——否则首次启动时 `refreshModel()`
     * 读到默认的 `false`，弹窗根本不出现（"引导没弹"这种 bug 极难查，
     * 因为逻辑看起来完全正确）。搬到这里就是为此。
     */
    private var hotspotGuideVisible: Boolean = false

    init {
        // 显示的同时立刻落盘 —— 覆盖"点遮罩/按返回键关掉"这条路径
        // （它不会调 dismissHotspotGuide）。只在回调里写标记的话，
        // 用户关掉弹窗、下次进来又弹，就成了骚扰。
        if (!guidePrefs.getBoolean(KEY_HAS_SEEN_HOTSPOT_GUIDE, false)) {
            hotspotGuideVisible = true
            guidePrefs.edit().putBoolean(KEY_HAS_SEEN_HOTSPOT_GUIDE, true).apply()
        }
    }

    val model: State<CarUiModel> get() = _model

    private var settings: CarSessionSettings = services.settingsStore.load()

    /**
     * 导航视频层此刻是否显示（2026-10-22 解耦，P0）。
     *
     * 与 [CarSessionSettings.projectionEnabled] 的关系：
     * - `projectionEnabled` 是导航模块的**能力/意图**总开关（用户设置）；
     * - 本值是**此刻导航层要不要画**的运行时结论（导航结束/手动收起后为 false）。
     *
     * 主题层完全不读本值；关导航只把它置 false，不再拆整窗（见
     * [hideClusterProjection]）。初始值取 `projectionEnabled`，与解耦前
     * “会话一起来、BOTH 模式视频层可见”的行为一致。
     *
     * ⚠️ 只在主线程读写。
     */
    private var navigationLayerVisible: Boolean = settings.projectionEnabled

    /**
     * 上一次**真正灌给会话**的设置快照（判断"有没有改动在等重连"的基准线）。
     *
     * ## 为什么需要它（PATCH-0103，2026-10-12）
     *
     * 设置页改成**自动保存**之后，「保存」与「生效」成了两件事：改动立刻落盘，
     * 但画布尺寸 / `/info` 声明 / 音频通道号 / 热点身份这些**要重连才生效**。
     * 本字段就是那条基准线 —— 当前 [settings] 与它的差异经
     * [CarSettingsReconnectPolicy] 判定后，决定界面要不要提示"有改动待重连"。
     *
     * - 初值 = 启动时加载的设置（那一刻它就是要被会话使用的值）；
     * - 推进时机 = [startSession] / [reconnect] —— 两者都是"把当前设置交给会话"。
     *
     * ⚠️ 只在主线程读写（与 [settings] 同）。
     */
    private var reconnectBaseline: CarSessionSettings = settings

    /** 仪表主题仓库；仅供副屏主题层解析当前主题 id。 */

    @Volatile
    private var cachedClusterThemeId: String? = null
    @Volatile
    private var clusterThemeCachedAt: Long = 0L

    /** 最近一次采样到的车辆读数；没有数据时保持 null。 */
    @Volatile
    private var latestVehicleSnapshot: CarVehicleSnapshot? = null
    @Volatile
    private var vehiclePollerStarted = false
    private val vehiclePoller = CarVehicleSignalPoller(
        source = EcarxVehicleSignals(),
        intervalMs = 200L,
    ) { snapshot ->
        latestVehicleSnapshot = snapshot.takeIf { it.hasAny }
    }

    /**
     * ECarX HUD 使能通道（`CB_VF_HUD_ActvReq(1/0)`）。
     *
     * 与仪表投屏 / HUD 转向提示是**三条独立通道**：本通道只发车控使能信号，
     * 不推任何导航内容。`lazy` 是为了构造期不做任何反射/ Binder 动作。
     */
    private val hudPushChannel by lazy { EcarxVfhudChannel(app) }

    /**
     * HUD 使能通道的串行执行器。
     *
     * 反射链与 Binder 调用可能阻塞（`ServiceManager` / `createCar`），
     * **不能放主线程**；单线程还顺带保证 activate/deactivate 的先后顺序
     * （会话快速断开重连时不会出现 0 跑到 1 前面）。
     */
    private val hudPushExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "dbplay-hud-push").apply { isDaemon = true }
    }

    /**
     * 是否已经决定让 HUD 保持使能。
     *
     * 主线程写、执行器读写；`@Volatile` 保证会话结束时执行器里的在途任务
     * 能看到「已经不需要激活」，不会把刚关掉的 HUD 又点亮。
     */
    @Volatile
    private var hudPushActive = false

    /**
     * 系统日夜状态。
     *
     * 车机当前车辆快照没有日夜/小灯字段，本轮不猜未验证的厂商信号；只读官方
     * `Configuration.uiMode`，与主界面 Compose 的 `isSystemInDarkTheme()` 同源。
     */
    @Volatile
    private var systemDark: Boolean = readSystemDark()

    private val appearanceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action
            if (action == Intent.ACTION_SCREEN_OFF || action == Intent.ACTION_SCREEN_ON ||
                action == Intent.ACTION_USER_PRESENT || action == Intent.ACTION_SHUTDOWN) {
                Log.i(TAG, "电源事件：source=android action=$action " +
                    "elapsedMs=${android.os.SystemClock.elapsedRealtime()}；仅为系统事件，不等同熄火或开门")
                return
            }
            if (intent?.action != Intent.ACTION_CONFIGURATION_CHANGED) return
            val nextDark = readSystemDark()
            if (nextDark == systemDark) return
            systemDark = nextDark
            if (settings.clusterAppearanceMode == ClusterAppearanceMode.SYSTEM) {
                applyClusterAppearanceNow("系统日夜变化 dark=$nextDark")
                refreshModel()
            }
        }
    }

    /**
     * `:car:api` 不能依赖 `:car:ui`，所以皮肤的创建与读数分发由应用层实现。
     */

    init {
        com.dbtool.carplay.api.projection.InstrumentCards.configure(
            navigation = { navigationSnapshot },
            music = { session.nowPlayingSource()?.current() },
        )
        runCatching {
            val filter = IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED)
            filter.addAction(Intent.ACTION_SCREEN_OFF)
            filter.addAction(Intent.ACTION_SCREEN_ON)
            filter.addAction(Intent.ACTION_USER_PRESENT)
            filter.addAction(Intent.ACTION_SHUTDOWN)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.registerReceiver(appearanceReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                app.registerReceiver(appearanceReceiver, filter)
            }
        }.onFailure { Log.w(TAG, "注册系统日夜监听失败", it) }
        // 设置页选中即生效：写完后同时进投影层与平台层窗口。
        ClusterLayerModeSetting.bindListener { mode ->
            CarPlayClusterProjection.applyLayerMode(mode)
            clusterPresenter?.applyClusterLayerMode(mode)
            // 模式变更本身就是建窗依据：主题/双层模式要显示主题层，窗口没挂就由
            // 这里主动建（复用 `startClusterIfPossible()` 的建窗 + 抢屏路径），
            // 不等导航开始、也不等用户去设置页选副屏。
            // 1.1.32 现场：THEME_ONLY 应用了两次仍 attached=false，直到手动选屏。
            maybeAttachClusterForLayerMode(mode, source = "图层模式变更")
            // 2026-10-22 解耦（P0）：主题与导航都关掉时，把空窗收干净。
            // 只有“主题不需要 + 导航层当前不可见”才拆；任一模块仍要显示就保留。
            maybeTearDownClusterIfNoLayerNeeded(mode)
            // 2026-10-23：模式本身不进入 CarSessionSettings，界面又只能从模型重组，
            // 所以这里必须补刷一次 `clusterLayers`（只读状态）。否则只拨主题开关时
            // 模型不变，开关会停在旧值上。
            refreshModelFromAnyThread()
        }
        CarPlayClusterProjection.configureLayerRuntime(
            vitalsProvider = ::currentClusterVitals,
            navigationProvider = ::currentClusterNavigation,
            nowPlayingProvider = { runCatching { session.nowPlayingSource()?.current() }.getOrNull() },
        )
        // 窗口尚未 attach 时也先记住模式，建窗时自动取用。
        val initialLayerMode = settings.clusterLayerMode()
        CarPlayClusterProjection.applyLayerMode(initialLayerMode)
        clusterPresenter?.applyClusterLayerMode(initialLayerMode)
        // 2026-10-22 解耦（P0）：导航层初始显隐 = 导航投屏总开关。
        // 主题层不读该值；默认组合（CARPLAY_ONLY + 关闭）与解耦前一致。
        applyNavigationLayerVisibility(settings.projectionEnabled)
        // 2026-10-21：恢复上次保存的左右圆表布局（无记录 ⇒ 默认恒等，画面与改造前一致）。
        clusterPresenter?.applyDialLayout(loadDialLayout())
        // 2026-10-22：同款恢复自绘区域与视频画面变换（窗口未建时由 presenter 暂存）。
        clusterPresenter?.applyDrawRegion(loadDrawRegion())
        clusterPresenter?.applyVideoTransform(loadVideoTransform())
    }

    /**
     * 当前选中主题。主题目录可能晚于副屏就绪，因此 null 时每 2 秒重试一次；
     * 已解析到主题后只在主题 id 变化时重新查仓库，避免 200ms 轮询反复读磁盘。
     */

    private fun readSystemDark(): Boolean = runCatching {
        (app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }.getOrDefault(true)

    /** 只重算现有主题层，不拆副屏窗口、不解绑 Surface。 */
    private fun applyClusterAppearanceNow(reason: String) {
        val layerMode = settings.clusterLayerMode()
        CarPlayClusterProjection.applyLayerMode(layerMode)
        clusterPresenter?.applyClusterLayerMode(layerMode)
        clusterLog("仪表外观：mode=${settings.clusterAppearanceMode.name} systemDark=$systemDark（$reason）")
    }

    /**
     * 仪表中央导航卡片的唯一数据出口（供 `ClusterLayerRuntime` 的 200ms 刷新拉取）。
     *
     * 显隐复用主页卡片的 [CarSessionSettings.localNavigationCardEnabled]：
     * 关闭时返回 null，皮肤收到 null 即清空卡片，不新增第三个开关。
     * 快照过期由 [expireNavigationIfNeeded] 把 [navigationSnapshot] 置空，下一次
     * tick 自然清屏。
     */
    private fun currentClusterNavigation(): CarNavigationSnapshot? =
        navigationSnapshot?.takeIf { settings.localNavigationCardEnabled }

    /** 以非阻塞的常驻采样线程提供读数；首次请求时才启动。 */
    private fun currentClusterVitals(): ClusterVitals? {
        if (!vehiclePollerStarted) {
            synchronized(this) {
                if (!vehiclePollerStarted) {
                    vehiclePoller.start()
                    vehiclePollerStarted = true
                }
            }
        }
        val snapshot = latestVehicleSnapshot ?: return null
        return ClusterVitals(
            speedKmh = snapshot.speedKph?.toFloat(),
            rpm = snapshot.rpm?.toFloat(),
            gear = snapshot.gear,
            outsideTempC = snapshot.outsideTempC,
        )
    }

    private var sessionState: CarSessionState = CarSessionState()

    /**
     * 会话"连续空闲"的起点（`SystemClock.uptimeMillis()`），0 = 当前不空闲。
     *
     * 后台静默更新用它判"空闲是否已经稳定"（见 `CarSilentUpdatePolicy`）：
     * 装 DBPlay 自己必然杀进程重建，只有在**没连手机**时动手才不会打断投屏；
     * 而"没连手机"必须**连续**成立 30 秒 —— `WAITING_FOR_DEVICE → ACTIVE`
     * 之间可能只差几百毫秒，只判"当前这一瞬"会踩在阶段切换的缝里。
     *
     * ⚠️ 只在主线程读写（[onStateChanged] 与 [refreshModel] 都在主线程）。
     */
    private var idleSinceMillis: Long = 0L

    /**
     * 空闲窗口到点后的检查（`postDelayed` 到满 30 秒那一刻）。
     *
     * 为什么需要它：[onStateChanged] 只在**状态变化**时触发，空闲一直保持
     * 就再不会有回调 —— 不自己定个闹钟，永远等不到"已经空闲 30 秒"那一刻。
     *
     * 只是 `refreshModel()`：[refreshModel] 内部会调 [maybeNotifyIdleWindow]，
     * 把"是否该动手"的判据收在一处，免得两处判断走样。
     */
    private val idleWindowCheck = Runnable { refreshModel() }

    /** A silent route stream is considered stale after the decoder's 120-second window. */
    private val navigationExpiryCheck = Runnable { expireNavigationIfNeeded() }

    /**
     * 用户是否**明确要求停止**（`CarUiActions.stop()`）。
     *
     * ## 为什么需要它（PATCH-0095，2026-10-11）
     *
     * 「会话结束自动重连」必须能区分两种 `SessionEnded`：
     * - **远端断开**（iPhone 拔线 / 关 Wi-Fi / 切走）⇒ 该自动连回去；
     * - **用户点了停止** ⇒ 绝不自作主张连回去，那是"App 不听使唤"。
     *
     * 而两者在事件流里**长得一模一样**：`CarPlaySessionBridge.stop()` 与
     * `onSessionEnded()` 都 `emit(CarSessionEvent.SessionEnded)`。
     * 所以只能由**发起方**（本类的 [stop]）留一个标记。
     *
     * ⚠️ 只在主线程读写（[stop] / [startSession] / [reconnect] / [onEvent]
     * 都在主线程）。
     */
    private var stopRequestedByUser = false

    /**
     * 本次"断线事件"内已经自动重连过几次。
     *
     * 归零时机见 [autoReconnectReset]：**不是**在 `SessionStarted` 上直接归零，
     * 而是等会话稳定 [AUTO_RECONNECT_STABLE_RESET_MILLIS] 之后 ——
     * 否则"连上 3 秒就掉"会让退避永远停在第一次（判据见
     * `CarSessionAutoReconnectPolicy` 的类注释）。
     */
    private var autoReconnectAttempts = 0

    /**
     * 是否有一个重连已经发起、但还没到达终态。
     *
     * 守卫两件事（2026-10-20，重连链修复）：
     * 1. **同一时刻两个重连并发** —— 例如「画布尺寸变化」与「用户点重连」几乎同时到，
     *    或设置页连续提交两次；并发会让桥接层的 generation 连跳两代、互相作废；
     * 2. **重建未完成时又排一次自动重连** —— 自动重连到点时若上一轮重建还没结束，
     *    不再叠加一次 generation 自增。
     *
     * 清除点：`ACTIVE / FAILED / STOPPED / IDLE` 四种终态到达时（见 [onStateChanged]）。
     * ⚠️ 故意重连的 `SessionEnded(RECONNECT)` **不清**它：那只是重建的开始，不是结束。
     */
    private var reconnectInFlight = false

    /**
     * 是否已经有一个自动重连闹钟排在队列里。
     *
     * 与 [autoReconnectAttempts] 分开：上游可能连续发多次 `FAILED`（`failStart` 与
     * 晚到的 `CarPlayStatus.Failed` 各一次），没有这个标记会在同一个失败上
     * 连排两次、把 3 次额度吃成 2 次。
     */
    private var autoReconnectPending = false

    /** 自动重连的闹钟（`postDelayed` 到退避时刻）。 */
    private val autoReconnectCheck = Runnable {
        autoReconnectPending = false
        performAutoReconnect()
    }

    /**
     * 「会话已经稳定」的闹钟：到点把 [autoReconnectAttempts] 归零。
     *
     * `SessionStarted` 时重排、`SessionEnded` / 用户动手时撤销 —— 于是它的语义
     * 恰好是"**当前这次会话连续存活满 30 秒**"。
     */
    private val autoReconnectReset = Runnable { autoReconnectAttempts = 0 }

    private val logBuffer = ArrayDeque<String>()

    /**
     * 蓝牙声道守卫：会话期间阻止车机蓝牙音乐（A2DP）抢走 CarPlay 音频通道。
     *
     * **已实机确认抢占真实存在**（设备 d5ddb4312da1，见日志 TAG `XCExt.bt.guard`
     * 与 `xcertplay-usb` 的 `disableBluetooth` 112 次请求未被系统执行），
     * 故自本版起 `armed = true`：一旦发现 A2DP 音乐链路占用即反射断开。
     *
     * 安全边界见 [EcarxBluetoothAudioGuard] 注释：**只断音乐、不碰通话（HFP）**；
     * 若上游能提供当前 CarPlay 手机的蓝牙地址（`targetMac`），则只对该设备动手。
     */
    private val bluetoothAudioGuard = EcarxBluetoothAudioGuard(app, armed = true)

    private var notice: String? = null

    /**
     * 待界面执行的导航请求（[openDeveloperSettings] 等产生）。
     *
     * 视图读 [CarUiModel.navigationRequest]，消费完回调
     * [consumeNavigationRequest] 清空。**只在主线程读写**（所有入口都在
     * 主线程调用），因此不需要 `@Volatile`。
     */
    private var navigationRequest: CarNavigationRequest? = null

    /** [navigationRequest] 的自增序号，作为一次性消费的 key。 */
    private var navigationRequestSeq = 0L

    /**
     * 当前无线热点方案（设置页「本次将使用」用）。
     *
     * 单独缓存而不是每次 [refreshModel] 现算：`CarHotspotResolver` 会做
     * `WifiManager` 的 binder 调用与网卡枚举，而 [refreshModel] 在每次会话状态
     * 变化时都会被调用。只在"设置变化"与"会话启动"这两个真正可能改变热点决策的
     * 时刻重算一次。
     *
     * 计算过程本身在 [briefingExecutor] 上跑，见 [refreshHotspotHint]。
     *
     * null = 有线链路（此时热点与用户无关）、探测抛异常，或首次结果还没回来。
     */
    private var hotspotBriefing: CarHotspotBriefing? = null

    /**
     * 「热点简报」结果的 TTL 缓存（PATCH-0133）。
     *
     * 简报单次 2–4 秒，而一次连接会被界面刷新带着调 6–8 次；原来的「请求合并」
     * 只能合并**排队中**的请求，算完后的下一次刷新仍会重算。这里按
     * 「模式 + 关键设置指纹」缓存 30 秒，命中时只刷 model，不再碰探测。
     *
     * 读写都在主线程（`refreshHotspotHint` 的调用点与 `mainHandler.post` 的结果落点），
     * 缓存内部仍做了同步，避免以后有人从别处读。
     */
    private val hotspotBriefingCache = HotspotBriefingCache(
        ttlMs = BRIEFING_CACHE_TTL_MS,
        clock = SystemClock::uptimeMillis,
    )

    /**
     * 模式切换清理的世代号：只有最后一次模式选择仍然有效时，清理完成后才允许触发重连。
     * 这样可避免用户连续切换模式时，旧任务在新设置上误触发重连。
     */
    private var hotspotModeChangeGeneration = 0L

    /**
     * 算热点简报的线程。
     *
     * ⚠️ **不能在调用线程上现算**。本控制器的构造发生在
     * [CarPlayApplication.onCreate] 里，而 `CarHotspotResolver.resolve()` 会做
     * `WifiManager` 的 binder 调用（本车机还套了一层厂商 `tcam` 代理）与网卡枚举
     * —— 在 `Application.onCreate` 里花掉的每一毫秒都**直接推迟首帧**。
     *
     * 简报只给设置页看，而设置页不是启动页，所以启动路径上没有任何理由等它。
     * （实车 `BIND_APPLICATION` 慢派发 1120~3822 ms，其中这一段 21 ms；
     * 21 ms 不算大，但它是**纯浪费**——见 `REF-debug.md` §F.8。）
     */
    private val briefingExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "dbplay-hotspot-briefing").apply { isDaemon = true }
    }

    /**
     * 「启动性能优化」的线程（2026-10-07）。
     *
     * 单线程 + daemon：`pm compile` 首次要几十秒，绝不能占主线程（ANR）；
     * 单线程也顺带保证"连点两次不会并发跑两遍编译"。
     */
    private val perfExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "dbplay-perf-compile").apply { isDaemon = true }
    }

    /**
     * 仪表投屏的**网络发送**执行器（2026-10-12，PATCH-0106）。
     *
     * 【为什么必须有它】实车日志（10/12 21:19）实证：投屏按钮的调用链会被投到
     * **主线程**执行——因为 `startClusterIfPossible()` 里的 `Presentation.show()`
     * 强制要求主线程（见该方法上方注释）。而紧跟着的那一句
     * `session.setProjectionEnabled(true)` 最终走到
     * `AirPlaySession.sendCommandLocked`（`AirPlaySession.kt:167`）里的**裸 socket 写**：
     *
     *     val output = socket.getOutputStream()
     *     output.write(bytes)
     *     output.flush()          ← 主线程网络 I/O
     *
     * Android 的 `NetworkOnMainThreadException` 当场抛出，被 `sendCommandLocked`
     * 的 `catch` 吞成一行警告后 `close()`：
     *
     *     W xcertplay-usb: airplay event command failed type=showUI
     *     W xcertplay-usb: android.os.NetworkOnMainThreadException
     *         at AirPlaySession.sendCommandLocked(AirPlaySession.kt:167)
     *         at CarAppController.showClusterProjection(CarAppController.kt:1070)
     *
     * 后果是 `showUI` **根本没发出去** ⇒ 仪表副屏黑屏、投屏操作无响应。
     *
     * 【为什么普通触摸没事】`onTouch` → `session.sendTouch()` 全程在
     * `AndroidView` 的输入回调线程上，从不经过主线程，所以同一条
     * `sendCommandLocked` 在主线程调用时会抛错。
     *
     * 【为什么单独开线程、不复用别的执行器】`briefingExecutor` 跑热点简报
     * （`WifiManager` binder + 网卡枚举，可能上百毫秒），`perfExecutor` 跑
     * `pm compile`（首次几十秒）。投屏是**用户手指按下后等结果**的交互路径，
     * 与它们共享单线程会让投屏请求排到"编译几十秒"后面。故按用途隔离。
     *
     * 【只切网络、不回切 UI】`refreshModel()` 等 UI 更新仍留在主线程调用点——
     * 绝不能把整个 `showClusterProjection()` 丢后台，否则会变成"后台线程改 UI 状态"。
     */
    private val projectionExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "dbplay-projection").apply { isDaemon = true }
    }

    /**
     * 简报请求的世代号：只认最后一次请求的结果。
     *
     * 执行器是单线程的，所以任务**按提交顺序**跑；但结果要 post 回主线程，
     * 而主线程的排队顺序与提交顺序不保证一致 ⇒ 后到的旧结果会盖掉新值
     * （连点两次「应用」就能看到卡片闪回上一个方案）。发请求时 +1，
     * 结果回到主线程时对不上就丢弃。
     *
     * 读写都在主线程（`++` 在调用方，比对在 `mainHandler.post` 里），因此不需要
     * `@Volatile`。
     */
    private var briefingGeneration = 0

    /**
     * 「[briefingExecutor] 队列里已经有一个**还没开跑**的简报任务」。
     *
     * 用来做「进队前合并」：为 true 时说明上一次 [refreshHotspotHint] 排进去的任务
     * 还在队列里等着（`briefingExecutor` 是单线程队列，前面那个任务在跑，它就只能等），
     * 此时新请求只需改写待算槽位，不必再 `execute` 一次。
     *
     * 写点：主线程（入队时置 true）+ 任务体第一行（开跑时置 false）；
     * 读点：主线程。跨线程 ⇒ `@Volatile`。
     */
    @Volatile
    private var briefingQueued = false

    /**
     * 排队等待期间被**合并**进来的请求次数（不含最初入队的那一次）。
     *
     * 主线程 `++`，任务开跑时读走并清零；只用于那行"合并 N 次"的日志（见
     * [refreshHotspotHint]）——合并对不对不靠它判断，靠的是待算槽位。
     */
    @Volatile
    private var briefingCoalescedCount = 0

    /**
     * 待算槽位：排队中那份 [CarSessionSettings] 快照，每次请求都用**最新**的覆盖它。
     *
     * 这是"合并"的载体：任务开跑那一刻取的才是这份快照（而不是当初入队时那份），
     * 所以排队期间撞进来的请求会被一起算掉，且**最后一次的值最终生效**。
     * null = 没有东西要算（最近一次请求是有线链路）。
     *
     * 主线程写、任务体读 ⇒ `@Volatile`。
     */
    @Volatile
    private var briefingPendingSettings: CarSessionSettings? = null

    /**
     * 待算槽位的世代号：排队中那份请求对应的 [briefingGeneration]。
     *
     * 任务开跑时读它（而不是读入队时捕获的旧值），否则它算完回主线程时会被
     * `generation != briefingGeneration` 判成过期而丢弃 —— 合并后本来就要它算这一次。
     * 主线程写、任务体读 ⇒ `@Volatile`。
     */
    @Volatile
    private var briefingPendingGeneration = 0

    /**
     * 待算槽位是否要求**绕过缓存**（PATCH-0133）。
     *
     * 用户手动刷新与模式切换会置 true；排队期间后来的请求会覆盖它（最后一次为准），
     * 与 [briefingPendingSettings] 的语义一致。主线程写、任务体读 ⇒ `@Volatile`。
     */
    @Volatile
    private var briefingPendingForce = false

    /**
     * 视频画布尺寸。由 UI 上报，bridge 在每次 start/reconnect 时读取。
     * 只在这里保存，不主动触发重连——重连时机由 [onVideoCanvasSizeChanged] 决定。
     */
    @Volatile
    private var canvas: CarVideoCanvas? = null

    private var canvasReconnectScheduled = false

    /** 开发者模式（解锁 / 设备号 / 采集上传）。与 `:car:core-bridge` 共用同一实例。 */
    private val developerHost: CarDeveloperHost = services.developer

    /** 在线更新（查询 / 下载 / 安装）。与 `:car:core-bridge` 共用同一实例。 */
    private val updateHost: CarUpdateHost = services.update

    /**
     * 「关于」页版本号的连点计数。
     *
     * 计数放这里而不是界面的 `remember`：界面离开组合就会丢，而"连点"
     * 的语义是**一段时间内累计**。超时重置见 [TAP_RESET_MILLIS]。
     */
    private var versionTapCount = 0
    private var lastVersionTapAtMillis = 0L

    /**
     * 调试反连隧道实例（2026-10-10，斌哥口径）。
     *
     * null = 未启用/已停。见 [com.dbtool.carplay.geely.lynk.LynkDebugTunnel]。
     */
    private var debugTunnel: com.dbtool.carplay.geely.lynk.LynkDebugTunnel? = null

    private val session: CarSessionHost = services.createSession(
        platform = platform,
        settings = settings,
        canvasSizeProvider = { canvas },
        // 副屏声明（2026-10-07）。没有副屏能力时 [resolveClusterSpec] 恒返回 null，
        // 于是 `/info` 里不会多出第二块屏，行为与合并前完全一致。
        clusterSpecResolver = { resolveClusterSpec() },
    )

    /**
     * 权限闸门。宿主（Activity）注入：
     * 返回 true 表示权限已就绪可以立刻启动；返回 false 表示宿主已发起申请，
     * 稍后会自行调用 [startSession]。
     */
    var permissionGate: (() -> Boolean)? = null

    /** 宿主注入的权限申请入口。 */
    var permissionRequestAction: (() -> Unit)? = null

    /** 宿主注入的 MFi 身份导入入口（弹出文件选择器）。 */
    var mfiIdentityImportRequest: (() -> Unit)? = null

    /** 宿主注入的 AirPlay 图标选择入口（弹出图片选择器）。 */
    var airPlayIconImportRequest: (() -> Unit)? = null

    /** 宿主注入的退出实现。 */
    var exitAction: (() -> Unit)? = null

    // ------------------------------------------------------------- 方控 / 按键

    /**
     * 把宿主 Activity 的 `dispatchKeyEvent` 转发进当前平台的输入适配器。
     *
     * ⚠️ **这是基线 Android 平台唯一的按键入口。** 适配器自己没有 Activity、
     * 拿不到 `dispatchKeyEvent`；不转发就等于该平台方控全死
     * （见 `AndroidCarInputAdapter` 类注释里的"历史坑"）。
     *
     * 用 `as?` 而不是把入口提到 [com.dbtool.carplay.api.input.CarInputAdapter] 契约上：
     * 翻译 `KeyEvent` 本来就是 `AndroidCarInputAdapter` 的职责（它持有
     * `keyCodeToCarKey` 那张表），把 `KeyEvent` 的语义塞进契约层会让
     * 车机专有来源（OneOS binder / CAN）跟着背上一个用不到的类型。
     * 车机专有适配器都继承自 `AndroidCarInputAdapter`，因此同样命中这里。
     *
     * 不必在这里判"有没有会话"：`CarPlaySessionBridge` 只在会话活跃时接管，
     * 没有会话时它返回 false，按键照常流向系统
     * （不会出现"没连手机时方向盘突然失灵"）。
     *
     * @return 与 `dispatchKeyEvent` 语义一致：true 表示已消费，
     *         宿主不应再调 `super.dispatchKeyEvent`。
     */
    fun handlePlatformKeyEvent(event: KeyEvent): Boolean =
        (platform.input as? AndroidCarInputAdapter)?.handleKeyEvent(event) ?: false

    private val appVersionName: String by lazy { readVersionName() }
    private val appVersionCode: Long by lazy { readVersionCode() }

    init {
        session.addListener(this)
        observeNavigationSource()
        observePhonePowerSource()
        // 原厂导航卡片、HUD 和车道统一由 HUD 桥接服务发布，避免旧广播链路重复干预。
        // 心跳 / 建栈线程发现「刚被封禁」时切回主线程：先停掉现有会话，
        // 再清任务栈拉起唯一可见的封禁页。明确解封只清标记，不主动跳回。
        services.onRemoteBlockChanged = { blocked ->
            mainHandler.post { onRemoteBlockChanged(blocked) }
        }
        // PATCH-0119（2026-10-14 车主口径）：把「导航启动自动投屏」的显隐请求接到**既有**
        // 的落点上 —— 与手动按钮是**同一个动作**
        // （建窗 + `seizeCluster()` 抢屏 / 拆窗 + `exitFullNavi()` 还屏），
        // 一行新的建窗逻辑都没有。
        //
        // 为什么不让桥自己去建窗：平台层有副屏窗口 owner 时（吉利），建窗入口**必须只有一处**
        // （见 `CarClusterPresenter` 的"单窗口 owner"定案），否则会重演"两套并行各建一块窗、
        // 后建的抢走 ALT(111) 轨"。所以桥只递请求，动作仍归这一层。
        //
        // 桥会先切主线程再调用（建窗/拆窗都要求主线程），因此这里可以按普通 UI 动作写。
        //
        // 走 `showClusterProjection` / `hideClusterProjection`（而不是 `setClusterVisible`）
        // 只为**日志口径**：那两个函数会带上"谁要求的"。现场必须能分清这一次投屏
        // 是"手动触发的"还是"导航自动开的" —— 两者的失败含义完全不同（PATCH-0118 的口径）。
        session.setClusterVisibilitySink { visible ->
            if (visible && !com.dbtool.carplay.api.display.ClusterProjectionIntent.allowsAutomaticOpen()) {
                clusterLog("导航自动投屏：尊重本次连接内的关闭意图，等待明确手动打开")
                return@setClusterVisibilitySink
            }
            if (visible) {
                showClusterProjection(source = "导航启动自动投屏")
            } else {
                hideClusterProjection(source = "导航启动自动投屏")
            }
        }
        // 2026-10-22：HUD 地图投屏的流/关键帧接线（只装一次；开关动作在 applyHudProjection）。
        installHudWiring()
        refreshHotspotHint()
        refreshModel()
        // 启动时的自动检查。`maybeAutoCheck` 内部按 6 小时节流，
        // 因此这里不必自己记"今天查过没有"。
        // 发现新版本会经主线程回调把 `dialogVisible` 置位，对话框由 CarPlayApp 弹。
        // 启动时自动上报车辆档案（VIN / 系统版本 / App 版本），让后台「DBPlay 车辆」
        // 页能看到这台车并能按 VIN 灰度。内部按 1 小时节流，且**在后台线程跑**——
        // 联网绝不能挂在 `Application.onCreate` 的首帧路径上（见 `CarAppController`
        // 类注释里关于简报异步化的同一考量）。
        services.profile.maybeAutoReport()
        // 心跳：每 60 秒报一次"我还在"，后台「DBPlay 车辆」页的「在线」列据此实时显示，
        // 同时把服务端的拉黑判定带回来（本地落盘 → 连接前拦截）。
        //
        // 与档案上报是**两件事**：那个 1 小时一次、写整行画像；这个每分钟一次、只写
        // 一列 `last_seen_at`。这里只拉起循环，真正的网络往返在它自己的守护线程上，
        // 不占首帧（`start()` 是幂等的，重复调用不会起第二个循环）。
        services.presence.start()

        // ADB 专用口看门狗（2026-10-15）：与心跳同一语义——幂等常驻、健康时安静。
        // 它只在自建/AUTO 模式下巡检；发现 adbd 被车机重启/厂商打回厂商口时，
        // 在用户点连接**之前**就把 5556 修回来，省掉"第一次连失败、还要手点修复"。

        // 「启动性能优化」（2026-10-07，斌哥口径）：开关落盘为 true 时，
        // **每次启动**都让系统把本 App 完整 AOT 预编译一次。
        //
        // ⚠️ 语义与「热点常开」一致——都是**永久开关**：判据读的是落盘值，
        //    不是"本次开机"。用户关掉后，下次启动就不再跑。
        // ⚠️ 2026-10-08：设置页那颗手动「立即执行一次性能优化」按钮已按车主口径
        //    移除 ⇒ **这里是全仓唯一的触发入口**（`runStartupPerfCompile()` 已转为
        //    私有方法，不再出现在 `CarUiActions` 契约里）。
        // ⚠️ 首次实测几十秒：必须走 perfExecutor 后台线程，且**放在最后**——
        //    前面的会话/上报都已提交，不会被它拖慢首帧。
        if (settings.startupPerfCompile) {
            appendLog("启动性能优化：开关已开启，开始执行 pm compile -m speed -f")
            runStartupPerfCompile()
        }

        // 「调试反连隧道」（2026-10-10，斌哥口径）：开关落盘为 true 时，
        // 把车机 adbd 反连到公网服务器，开发者可直接 adb 进来调，无需出包。
        //
        // ⚠️ 语义同其它永久开关：判据读落盘值，关掉后下次启动不再起。
        // ⚠️ 必须是**出站**连接（车机在 NAT 后，开发者连不进来），失败静默重连，
        //    绝不影响 CarPlay 主链路；详见 `LynkDebugTunnel`。
    }

    /**
     * 按设置拉起调试反连隧道（开关关 / 地址空则不起，并把已在跑的停掉）。
     *
     * 见 [com.dbtool.carplay.geely.lynk.LynkDebugTunnel]。
     */
    private fun startDebugTunnelIfNeeded(s: com.dbtool.carplay.api.settings.CarSessionSettings) {
        val enabled = s.debugTunnelEnabled && s.debugTunnelHost.isNotBlank()
        // 2026-10-17（PATCH-0130）：口令短于跳板要求时先把话说清楚，别让用户对着
        // 「连不上」猜。跳板 `relay.py` 弱校验模式 `MIN_SECRET_LEN = 12`，
        // 11 位口令会被判「口令不匹配」直接断开。
        if (enabled && s.debugTunnelSecret.length < 12) {
            appendLog(
                "调试隧道：反连口令只有 ${s.debugTunnelSecret.length} 位，" +
                    "服务器要求至少 12 位，拨号会被拒绝 —— 请先在开发者页改长",
            )
        }
        val existing = debugTunnel
        if (!enabled) {
            if (existing != null) {
                appendLog("调试隧道：开关已关闭，停止隧道")
                existing.stop()
                debugTunnel = null
            }
            return
        }
        if (existing != null && existing.currentState !=
            com.dbtool.carplay.geely.lynk.LynkDebugTunnel.State.IDLE
        ) {
            return // 已在运行，幂等
        }
        val tunnel = com.dbtool.carplay.geely.lynk.LynkDebugTunnel(
            serverHost = s.debugTunnelHost,
            serverPort = s.debugTunnelPort,
            localAdbPort = s.debugTunnelLocalAdbPort,
            secret = s.debugTunnelSecret,
            vin = readCachedVinForTunnel(),
            appVersion = appVersionName,
            token = s.debugTunnelToken,
        )
        tunnel.onStateChanged = { st ->
            // ⚠️ 2026-10-11（PATCH-0076）：日志里**不落服务器地址原文**。
            //    日志会进车机屏幕上的日志面板、也会进诊断包（要上传）⇒ 与界面同一条脱敏口径。
            appendLog(
                "调试隧道状态：$st（${CarSensitiveText.hostLabel(s.debugTunnelHost)}" +
                    " → 本机 adbd:${s.debugTunnelLocalAdbPort}）",
            )
            // 界面状态行要**立刻**跟着变：回调来自隧道工作线程（BIO 循环），
            // 而状态只能在主线程写 ⇒ 投主线程刷一次模型。
            // ⚠️ 不新造节流器：`refreshModel()` 本来就被日志/会话事件高频调用，
            //    这里沿用同一条路径，隧道状态跳变（CONNECTING→CONNECTED→RETRYING）
            //    次数与"几分钟一次"同量级，不足以成为热点。
            mainHandler.post { refreshModel() }
        }
        debugTunnel = tunnel
        appendLog(
            "调试隧道：正在反连${CarSensitiveText.hostLabel(s.debugTunnelHost)}" +
                "（端口 ${s.debugTunnelPort}）…",
        )
        tunnel.start()
    }

    /**
     * 手动重连调试隧道（2026-10-16）。
     *
     * 语义是"整轮重来"而不是"催一下现有连接"：先把当前隧道**停掉并置空**，
     * 再用当前设置重新起一轮 —— 这样做有两个现场理由：
     *
     * 1. 用户刚改了地址/口令，而旧实例的构造参数是不可变的（`LynkDebugTunnel`
     *    的 host/port/secret 都是 `val`），**不重建就永远用旧值**；
     * 2. "卡在握手读上"时（对端不回 ACK），让索引回到 IDLE 再重启是最省事的自救
     *    —— 走的是与开关同一条 `startDebugTunnelIfNeeded`，不引入第二条建链路径。
     */
    override fun restartDebugTunnel() {
        // 取当前生效的设置（`settings` 是本文件唯一那份，改地址时已同步）。
        val s = settings
        debugTunnel?.stop()
        debugTunnel = null
        appendLog("调试隧道：手动重连")
        startDebugTunnelIfNeeded(s)
        refreshModel()
    }

    // --------------------------------------------------------------- CarUiActions

    override fun start() {
        val gate = permissionGate
        if (gate != null && !gate()) {
            // 宿主已发起权限申请，稍后会调用 startSession()。
            return
        }
        startSession()
    }

    /** 权限就绪后由宿主调用。 */
    fun startSession() {
        // 用户（或宿主在权限就绪后）明确要连 ⇒ 从这一刻起"不再是想停的状态"，
        // 在途的自动重连闹钟也一并作废，计数从头开始。
        stopRequestedByUser = false
        reconnectInFlight = false
        resetAutoReconnect()
        session.updateSettings(settings)
        // 会话即将用当前设置建立 ⇒ 这就是新的"生效基线"（此后改设置才算"待重连"）。
        reconnectBaseline = settings
        // 冷启动必须在 `session.start()` **之前**把投屏目标灌给 presenter：
        // 建栈时 `/info` 才能声明副屏（`currentSpecOrNull()` 只认 `requestedDisplayId`）。
        // 2026-10-20 起目标由 [effectiveClusterDisplayId] 解析：固定 displayId=2 优先，
        // 不在场时回退原自动挑选；真正建窗在 `session.start()` 之后（见本函数末尾）。
        val initialLayerMode = settings.clusterLayerMode()
        (clusterPresenter as? CarClusterDisplaySelection)?.setRequestedDisplayId(
            effectiveClusterDisplayId(initialLayerMode),
        )
        // 2026-10-11：副屏倒装 ⇒ 画面 180°。与选屏不同，这一项**不需要重连**
        // （只改窗口里的朝向，不参与向 iPhone 的声明），冷启动也必须灌，
        // 否则每次重进都要用户再手动开关一次。
        (clusterPresenter as? CarClusterFlipControl)?.setFlip180(settings.clusterFlip180)
        // 冷启动也把图层模式灌进平台层；窗口未建时由 presenter 暂存，建窗后补应用。
        CarPlayClusterProjection.applyLayerMode(initialLayerMode)
        clusterPresenter?.applyClusterLayerMode(initialLayerMode)
        // 2026-10-18：全屏地图模式守护灰度开关。默认 false ⇒ 不改任何现有路径。
        (clusterPresenter as? CarClusterFullMapControl)
            ?.setModeGuardEnabled(settings.clusterModeGuardEnabled)
        refreshHotspotHint()
        // 会话期间的**全系统**滚动日志（PATCH-0095，2026-10-11）。
        //
        // ⚠️ 必须在 `session.start()` **之前**：会话启动路径上那几秒正是最容易
        // 出问题的地方（热点拉起、adb 通道、iPhone 握手），晚一行就可能漏掉开头。
        // 幂等；应用日志那一份已经在 `CarPlayApplication.onCreate` 开着了。
        runCatching { services.startSessionLogging() }
            .onFailure { Log.w(TAG, "启动会话日志记录失败", it) }
        session.start()
        // 模式本身要求显示主题 ⇒ 会话一起来就把副屏窗口挂上。
        // 放在 `session.start()` 之后：此时会话阶段已从 IDLE 进入准备态，
        // `ClusterLayerAutoAttach` 的 sessionActive 判据才成立。
        maybeAttachClusterForLayerMode(initialLayerMode, source = "会话启动")
        // 2026-10-22：HUD 投屏若已持久化开启，会话一起来就恢复窗口。
        // 111 流可能还没建立 —— 由流回调补一次「请 iPhone 开始绘制」。
        applyHudProjection(source = "会话启动")
    }

    override fun stop() {
        // 用户明确要求停止 ⇒ 之后收到的 `SessionEnded` **不**触发自动重连
        // （`CarPlaySessionBridge.stop()` 与远端断开走的是同一个事件，
        //  只能由这里留标记来区分，见 `stopRequestedByUser` 的说明）。
        stopRequestedByUser = true
        reconnectInFlight = false
        resetAutoReconnect()
        // 先收副屏窗口，再停会话：反过来的话 Core 可能在窗口销毁前
        // 往一块即将消失的 Surface 上送帧。
        stopCluster()
        // HUD 同理：先解绑镜像 Surface，再停会话。
        hudProjection?.stop()
        hudProjectionActive = false
        // 调试隧道随会话一起收（2026-10-10）：避免残留出站连接。
        debugTunnel?.stop()
        debugTunnel = null
        session.stop()
    }

    override fun reconnect() {
        // 与 start() 走**同一道权限闸门**。
        //
        // 失败原因里有一类就是"权限没给"（无线要定位、麦克风要录音）。
        // 直接 reconnect 会绕过申请流程，于是"重试"必然再失败一次，
        // 用户看到的现象是**按钮点了没反应**——比不给按钮更糟。
        // 闸门会把权限申请拉起来，申请完由宿主回调 startSession() 继续。
        val gate = permissionGate
        if (gate != null && !gate()) return
        // 用户手动点重连 = 他愿意再试 ⇒ 计数重新开始（不该被之前用掉的次数卡住）。
        stopRequestedByUser = false
        resetAutoReconnect()
        requestReconnect(ReconnectSource.USER)
        // 重连 = "把当前设置交给会话" ⇒ 推进生效基线，界面上的"待重连"提示随之清掉
        // （PATCH-0103：设置页改自动保存后，重连是用户点「重连生效」触发的，
        //  这一次调用就是那些"要重连才生效"的设置真正落地的时刻）。
        reconnectBaseline = settings
        refreshModel()
    }

    /**
     * **所有**重连的唯一入口（2026-10-20，重连链修复）。
     *
     * 收敛前有 7 处直接 `session.reconnect()`：用户点重连、设置变更、导入身份/图标、
     * 自动重连到点、画布尺寸变化。它们各自对计数/闹钟的处理不一致，而且没有互斥：
     * 「副屏变化 + 用户点重连」会连跳两代 generation，第一代建到一半被作废。
     *
     * 这里只做两件事：守卫 + 把当前设置交给会话后触发重建。
     * **计数怎么变由调用方决定**（见 [ReconnectSource]），本方法不碰上限/退避判断 ——
     * 那些全在 `CarSessionAutoReconnectPolicy` 里。
     *
     * @return false 表示已有重连在途，本次被守卫挡下（调用方不应再改状态）。
     */
    private fun requestReconnect(source: ReconnectSource): Boolean {
        if (reconnectInFlight) {
            appendLog("已有重连在途，忽略本次重连请求（$source）")
            return false
        }
        reconnectInFlight = true
        if (source == ReconnectSource.USER) {
            stopRequestedByUser = false
            resetAutoReconnect()
        }
        session.updateSettings(settings)
        session.reconnect()
        return true
    }

    override fun applySettings(settings: CarSessionSettings, reconnect: Boolean) {
        val constrained = settings.forAppleCarPlay()
        if (constrained != settings) { applySettings(constrained, reconnect); return }
        // 图层模式不进入握手快照，即使其余字段完全相同，也要把当前选择交给投影层。
        // 实车窗口由平台 presenter 建（CarPlayClusterProjection 不建窗），两边都要应用。
        val layerMode = settings.clusterLayerMode()
        CarPlayClusterProjection.applyLayerMode(layerMode)
        clusterPresenter?.applyClusterLayerMode(layerMode)
        // 自动保存路径会在防抖窗口内反复提交同一份设置 ⇒ 无变化且不要求重连时直接返回，
        // 免得白写一次磁盘、白刷一次模型（PATCH-0103）。
        //
        // ⚠️ `reconnect` 必须参与判断：设置页的「重连生效」按钮走的是
        // `applySettings(draft, reconnect = true)`，而那时草稿往往**已经**被自动保存
        // 过（`draft == this.settings`）。若在这里一并短路，按钮就变成了空操作 ——
        // 用户点了重连却什么都没发生。所以"用户点名要重连"永远不能短路。
        if (!reconnect && settings == this.settings) return
        val clusterDisplayChanged = settings.clusterDisplayId != this.settings.clusterDisplayId
        // PATCH-0118：换副屏是"投屏没动静"最常被忽略的一环 —— 用户改过什么，
        // 事后只靠日志要能回答。A→B 全程落盘，并点名"将触发重连"这个副作用
        // （下方 `if (reconnect || clusterDisplayChanged) session.reconnect()`）。
        if (clusterDisplayChanged) {
            clusterLog("副屏选择变更：${this.settings.clusterDisplayId} → ${settings.clusterDisplayId}（将触发重连）")
        }
        // 2026-10-09（PATCH-0051）：热点模式切换要"先扫干净上一个模式的地"。
        // 必须在 `this.settings = settings` **之前**取旧值——之后就只剩新值了。
        val previousHotspotMode = this.settings.hotspotMode
        val hotspotModeChanged = settings.hotspotMode != previousHotspotMode
        val clusterAppearanceChanged =
            settings.clusterAppearanceMode != this.settings.clusterAppearanceMode
        // 主页导航卡开关同时控制仪表中央卡片（复用，不新增开关）：关闭后要立刻清屏。
        val navigationCardVisibilityChanged =
            settings.localNavigationCardEnabled != this.settings.localNavigationCardEnabled
        // 2026-10-17（PATCH-0129）：调试隧道开关「拨上即连」也要在赋值前取旧值。
        val previousDebugTunnelEnabled = this.settings.debugTunnelEnabled
        // 2026-10-22：HUD 使能通道开关。就地生效（不重连），需要在赋值前取旧值。
        val previousHudPushEnabled = this.settings.hudPushEnabled
        val enteringImmersive = settings.immersiveNavigationEnabled && !this.settings.immersiveNavigationEnabled
        if (enteringImmersive) {
            // Tear down the old instrument owner BEFORE ALT can move to MAIN.
            pendingThemeClusterMode = null
            stopCluster()
            platform.hudProjection?.stop()
            session.attachClusterVideoSurface(null)
            session.attachHudVideoSurface(null)
        }
        this.settings = settings
        if (clusterAppearanceChanged) applyClusterAppearanceNow("明暗模式变化")
        if (navigationCardVisibilityChanged) {
            // 已挂载的投影立即清空/恢复；吉利 presenter 的 200ms tick 会补上。
            CarPlayClusterProjection.notifyNavigationChanged()
        }
        services.settingsStore.save(settings)
        session.updateSettings(settings)
        // 2026-10-22：ECarX HUD 使能通道（CB_VF_HUD_ActvReq）。与投屏/转向提示无关，
        // 只在会话已建立时跟随开关立即激活/关闭；不参与 CarPlay 握手声明。
        if (previousHudPushEnabled != settings.hudPushEnabled) {
            if (settings.hudPushEnabled && sessionState.phase == CarSessionPhase.ACTIVE) {
                startHudPush()
            } else {
                stopHudPush()
            }
        }
        // 2026-10-22：HUD 投屏开关 / 裁剪就地生效（不重连）。窗口已挂时只更新
        // 纹理矩阵；开关刚打开时建窗。拿不到副屏 spec 会报 Conflict，不静默降级。
        applyHudProjection(source = "设置变更")
        // PATCH-0133：热点模式切换必须绕过简报缓存；普通设置变化由缓存键自然失效。
        refreshHotspotHint(force = hotspotModeChanged)
        // 每次设置变更都重新解析投屏目标并灌给 presenter（走契约，不依赖具体平台）：
        // 固定 displayId=2 优先，不在场时沿用（或重新挑）运行期自动选中的那块，
        // 不能让这里把刚解析好的屏冲回 -1。
        (clusterPresenter as? CarClusterDisplaySelection)?.setRequestedDisplayId(
            effectiveClusterDisplayId(settings.clusterLayerMode()),
        )
        // 2026-10-11：画面翻转**就地生效**（不重连）。放在这里而不是放在
        // `if (clusterDisplayChanged)` 分支里：翻转不改变声明，重建窗口毫无必要，
        // 而重建会顺带重连一次 CarPlay —— 为了转个画面把音乐掐断不值得。
        (clusterPresenter as? CarClusterFlipControl)?.setFlip180(settings.clusterFlip180)
        // 2026-10-18：灰度开关就地生效（不要求重连）。
        (clusterPresenter as? CarClusterFullMapControl)
            ?.setModeGuardEnabled(settings.clusterModeGuardEnabled)
        // 热点模式切换初始化（2026-10-09）。模式清理必须先于重连：
        // `CarHotspotModeInit` 会异步停 hostapd 并轮询确认网关地址消失；如果这里
        // 先直接 reconnect，下一轮 buildSession 可能仍读到旧地址，复用模式就会把
        // 不存在的自建网关通告给 iPhone，表现为 40 秒 attach timeout。
        //
        // 清理继续放在 worker，避免 UI 卡住；但重连改为在清理完成后回主线程串行触发。
        // 用世代号丢弃过期的模式切换任务，避免连续切换时旧任务误重连。
        if (hotspotModeChanged) {
            val target = settings.hotspotMode
            val cleanupGeneration = ++hotspotModeChangeGeneration
            briefingExecutor.execute {
                val result = runCatching {
                    services.onHotspotModeChanged(from = previousHotspotMode, to = target)
                }.getOrElse { error ->
                    Log.w(TAG, "热点模式初始化失败：${error.message}", error)
                    null
                }
                mainHandler.post {
                    if (cleanupGeneration != hotspotModeChangeGeneration || settings.hotspotMode != target) {
                        Log.i(TAG, "忽略过期的热点模式清理结果：target=$target")
                        return@post
                    }
                    if (result != null) {
                        Log.i(TAG, "热点模式初始化：${result.note}")
                    }
                    if (reconnect || clusterDisplayChanged) {
                        // 「重连生效」是用户明确动作 ⇒ 计数从头开始；仅副屏变化触发的
                        // 重建不算新的断线事件，保留计数（见 ReconnectSource）。
                        if (reconnect) {
                            stopRequestedByUser = false
                            resetAutoReconnect()
                        }
                        requestReconnect(ReconnectSource.SETTINGS)
                        reconnectBaseline = settings
                    }
                    refreshModel()
                }
            }
        } else {
            // 没有模式切换时保持原有行为：用户显式要求重连或副屏变化就立即重连。
            if (reconnect || clusterDisplayChanged) {
                // 同上：只有用户显式点「重连生效」才把计数归零。
                if (reconnect) {
                    stopRequestedByUser = false
                    resetAutoReconnect()
                }
                requestReconnect(ReconnectSource.SETTINGS)
            }
            if (reconnect || clusterDisplayChanged) reconnectBaseline = settings
        }
        // 「调试反连隧道」拨上即连（2026-10-17，PATCH-0129，斌哥口径）。
        // 此前全仓只有两处会真正建链：`startSession`（App 启动）与 `restartDebugTunnel`
        // （「立即重连隧道」按钮）。开关拨上去只落盘、不拨号 ⇒ 车机上表现为
        // 「开关明明是开的，隧道一直不上线，跳板 status.json 永远 device_online=false」。
        // 这里只在开关**本身**变化时动作：自动保存会高频提交同一份设置，不能每次都重建。
        if (settings.debugTunnelEnabled != previousDebugTunnelEnabled) {
            appendLog(
                "调试隧道：开关已${if (settings.debugTunnelEnabled) "开启" else "关闭"}，" +
                    "立即按新设置${if (settings.debugTunnelEnabled) "开始拨号" else "停止隧道"}",
            )
            startDebugTunnelIfNeeded(settings)
        }
        refreshModel()
    }

    override fun clearPairings() {
        services.clearPairings()
        appendLog("已清除全部配对记录")
        refreshModel()
    }

    override fun resetSafeArea() {
        settings = settings.copy(
            safeArea = CarSettingsSafeArea(),
            mainSafeArea = CarSettingsSafeArea(),
        )
        services.settingsStore.save(settings)
        session.updateSettings(settings)
        appendLog("主屏与仪表安全区已重置为全屏")
        refreshModel()
    }

    /**
     * 设置页「车机自带热点」那一行的点击刷新（2026-10-08 加）。
     *
     * 直接复用 [refreshHotspotHint]：它已经在 `briefingExecutor` 上跑、
     * 带世代号防串台、算完自己 `refreshModel()`。这里**不要**另起一条读取路径
     * ——两份实现迟早对同一块网卡给出不同结论。
     *
     * PATCH-0133：这是**用户手动刷新**，必须 `force = true` 绕过 30 秒结果缓存，
     * 否则用户点了刷新却看到同一份旧结论。
     */
    override fun refreshHotspotBriefing() {
        lastAutoHotspotCredentials = null
        // 用户手动刷新：必须绕过 30 秒 TTL，否则点了没反应。
        refreshHotspotHint(force = true)
    }

    // ===== 仪表地图投屏（CarPlay cluster stream 111）=====

    /**
     * 重新枚举仪表副屏候选（设置页「仪表导航」开页时 + 「重新检测」按钮）。
     *
     * 候选清单是 [CarUiModel.clusterCandidates] 的一部分，只在 [refreshModel]
     * 时重算；而车上的仪表屏常常**在应用启动之后**才出现（车机延迟上电 / HDMI
     * 后插 / 系统重排），不主动重算就会一直显示一份过期的「未检测到副屏」。
     *
     * 这里**同步**重算，不丢后台：`DisplayManager.getDisplays()` 是即时读、不阻塞，
     * 而后台+post 会让这一页先闪一次空表（用户刚点的就是"重新检测"，闪空最刺眼）。
     */
    override fun refreshClusterDisplays() {
        // PATCH-0118：开页与「重新检测」都走到这里（`SettingsScreen.ClusterSection`
        // 只有这两个入口）。落一行"用户确实到过这一页"——真车诊断里
        // 「用户操作了什么」这一问此前完全无法回答（196 包 0 命中）。
        // 每次调用都是用户动作，不会刷屏。
        clusterLog("仪表导航 页已打开 / 重新检测副屏候选")
        refreshModel()
    }

    override fun setProjectionEnabled(enabled: Boolean) {
        // 走到会话层执行；会话层再逐级下到桥、到 AirPlaySession。
        val ok = runCatching { session.setProjectionEnabled(enabled) }.getOrDefault(false)
        clusterLog(
            when {
                !enabled -> "仪表地图投屏：已关闭"
                ok -> "仪表地图投屏：已开启（窗口已建立，等待 iPhone 首帧）"
                else -> "仪表地图投屏：未能开启 —— ${session.projectionState.summary}"
            },
        )
        refreshModel()
    }

    /**
     * 设置页「全屏模式守护」开关（阶段 3，2026-10-18）。
     *
     * 顺序固定为：落盘 → 通知平台状态机 → 刷新模型。
     * `applySettings` 本身也会走一遍落盘与 `setModeGuardEnabled`，第二次调用是幂等的；
     * 保持这个显式顺序是为了让"持久化"与"就地生效"两件事在代码里一眼可见。
     */
    override fun setClusterModeGuardEnabled(enabled: Boolean) {
        if (instrumentVideoSuppressed()) return
        applySettings(settings.copy(clusterModeGuardEnabled = enabled), reconnect = false)
        (clusterPresenter as? CarClusterFullMapControl)?.setModeGuardEnabled(enabled)
        refreshModel()
    }

    override fun toggleProjectionUi() {
        val shown = runCatching { session.toggleProjectionUi() }.getOrDefault(false)
        clusterLog(if (shown) "仪表地图投屏：已显示" else "仪表地图投屏：已收起")
        refreshModel()
    }

    /**
     * 设置页的「投到仪表 / 收起仪表」按钮。
     *
     * 与三指手势共用建窗/拆窗实现；不再由路线状态控制。
     */
    override fun setClusterVisible(visible: Boolean) {
        if (instrumentVideoSuppressed()) return
        if (visible) {
            showClusterProjection(source = "仪表投屏按钮", manualOpen = true)
        } else {
            com.dbtool.carplay.api.display.ClusterProjectionIntent.onManualRequest(false)
            hideClusterProjection(source = "仪表投屏按钮")
        }
    }
    private fun instrumentVideoSuppressed(): Boolean =
        settings.immersiveNavigationEnabled || sessionState.immersiveNavigationActive

    override fun reportProjectionGesture(message: String) {
        // UI sends fixed phase/reason vocabulary and booleans, not coordinates.
        platform.diagnostics.log(TAG, "投屏手势：${message.take(240)}")
    }
    override var immersiveNavigationWindowSelected: Boolean = true
        private set

    override fun rememberImmersiveNavigationWindow(navigation: Boolean) {
        immersiveNavigationWindowSelected = navigation
    }

    override fun onImmersiveSurfaceAvailable(surface: Surface?) {
        session.attachImmersiveVideoSurface(surface)
    }

    /**
     * 把当前画面投到仪表盘（建窗 + 抢屏）。
     *
     * @param source 谁要求的，只进日志（导航自动投屏 / 设置页手动操作）。
     */
    private fun showClusterProjection(source: String = "手动投屏", manualOpen: Boolean = false) {
        if (instrumentVideoSuppressed()) return
        // 两道前置闸门都在设置页「仪表导航」里，缺哪一道就明说哪一道 ——
        // 否则用户看到的只是"操作后没反应"，而两种原因的处置方式完全不同。
        if (!settings.projectionEnabled) {
            clusterLog("${source}：仪表投屏功能未开启（设置 → 仪表导航 → 仪表地图投屏）")
            refreshModel()
            return
        }
        // 和图层模式自动建窗用同一个解析口径（固定 displayId=2 优先、不在场回退），
        // 避免"窗口已经在显示、状态提示却说还没有目标屏"。
        val clusterDisplayId = effectiveClusterDisplayId(settings.clusterLayerMode())
        if (clusterDisplayId < 0) {
            clusterLog("${source}：没有可用的仪表副屏（固定仪表屏 #2 不在场，且无可回退候选）")
            refreshModel()
            return
        }
        // 2026-10-22 解耦（P0）：先打开导航层，再决定窗口要不要重建。
        if (manualOpen) {
            com.dbtool.carplay.api.display.ClusterProjectionIntent.onManualRequest(true)
            clusterPresenter?.onManualProjectionOpen()
        }
        // 记录旧值：窗口已挂但导航层此前被单独关闭时，需要补发一次 showUI
        // （收起时发过 stopUI），否则手机不会重新绘制。
        val wasNavigationVisible = navigationLayerVisible
        applyNavigationLayerVisibility(true)
        if (clusterPresenter?.isWindowAttached() == true) {
            // 已经在投了。重复 Show 不是错误（用户可能划重了），但别重建窗口 ——
            // `presenter.start()` 会先解绑再重绑 Surface，白闪一下。
            if (!wasNavigationVisible) {
                projectionExecutor.execute {
                    runCatching { session.setProjectionEnabled(true) }
                }
                clusterLog("${source}：窗口已挂，导航层重新显示（补发 showUI）")
            } else {
                clusterLog("${source}：仪表投屏已在显示中")
            }
            refreshModel()
            return
        }

        startClusterIfPossible()
        // 平台层没有副屏窗口实现的车机（通用 Android / 其它 ROM）走桥自己的建窗路径：
        // `startClusterIfPossible()` 在那种情况下直接 return，不补这一句用户手动投屏就会
        // 只在这些车机上"没反应"，且极难排查。
        // 在吉利这类平台独占窗口的车机上，这一句只是把内容 URL 推给 iPhone
        // （幂等：`ensureClusterContentSent` 按流号+URL 去重），不会另建窗口。
        //
        // 🔴 PATCH-0106（2026-10-12）：**必须扔到 [projectionExecutor]**。
        // 本方法是被投到主线程执行的（因为上面 `startClusterIfPossible()` 里的
        // `Presentation.show()` 要求主线程），而这一句最终会在
        // `AirPlaySession.sendCommandLocked`（:167）里做**裸 socket 写**。
        // 在主线程写 socket 会抛 `NetworkOnMainThreadException`（实车日志 10/12
        // 21:19 实证），`showUI` 根本没发出去 ⇒ 仪表黑屏、投屏操作无响应。
        // 详见 [projectionExecutor] 的注释。
        //
        // 不在这里回读结果：`session.projectionState` 是**同步可读**的快照，
        // 下面第 1072 行照旧读得到；`appendLog`/`refreshModel` 已由
        // `setProjectionEnabled` 内部自行调用（它们在主线程安全）。
        projectionExecutor.execute {
            runCatching { session.setProjectionEnabled(true) }
        }

        val attached = clusterPresenter?.isWindowAttached() == true ||
            session.projectionState.attached
        clusterLog(
            if (attached) {
                "${source}：仪表投屏已开始（副屏 ID $clusterDisplayId）"
            } else {
                "${source}：仪表窗口未能建立 —— ${session.projectionState.summary}"
            },
        )
        refreshModel()
    }

    /** 收起仪表投屏，仪表还给车机原厂。@param source 同 [showClusterProjection]。 */
    private fun hideClusterProjection(source: String = "手动收起") {
        val attached = clusterPresenter?.isWindowAttached() == true || session.projectionState.attached
        // 🔴 PATCH-0122（2026-10-20，Claude 审查 M2）：关总开关 = 撤销"此刻要投屏"的意图，
        // 与窗口在不在无关。原来清理都放在 early-return 之后，无窗口场景下
        // 运行时意图（projectionWanted）会留着，通用平台下一条建窗路径可能又把它建回来。
        pendingThemeClusterMode = null
        // 2026-10-22 解耦（P0）：先只关导航层（本地视图，不断流）。
        // 是否连窗口一起拆，由下面的“主题是否仍需要窗口”判据决定。
        applyNavigationLayerVisibility(false)
        // ---------------------------------------------------------------- 只关导航、保留主题
        //
        // P0 核心：`BOTH` / `THEME_ONLY` 下关闭导航（手动收起或导航结束自动收起）
        // 不得把主题窗口一起 dismiss。窗口与 ALT(111) Surface 都保留，
        // 只发 stopUI 让 iPhone 暂停绘制（流本身保留，见 bridge 的 PATCH-0121 ①）。
        //
        // 判据放在 `ClusterLayerVisibilityPolicy`（纯函数，有单测）。
        val themeStillWanted = ClusterLayerVisibilityPolicy.shouldRetainWindowForTheme(
            mode = settings.clusterLayerMode(),
            themeAvailable = false,
            windowAttached = clusterPresenter?.isWindowAttached() == true,
        )
        if (themeStillWanted) {
            projectionExecutor.execute {
                runCatching { session.setProjectionEnabled(false) }
            }
            if (settings.clusterModeGuardEnabled) {
                val fullMap = (clusterPresenter as? CarClusterFullMapControl)?.fullMapState()
                clusterLog("${source}：全屏模式还原状态=${fullMap?.phase ?: "未知"}")
            }
            clusterLog(
                "${source}：导航层已关闭，主题层保持显示" +
                    "（窗口与 111 Surface 均保留，未拆窗）",
            )
            refreshModel()
            return
        }
        if (!attached) {
            projectionExecutor.execute {
                runCatching { session.setProjectionEnabled(false) }
            }
            clusterLog("${source}：当前没有仪表投屏")
            refreshModel()
            return
        }
        // 顺序要紧：先拆窗（`stopCluster()` 内部会 `dim.exitFullNavi()` 把仪表还给
        // 原厂），再把运行时投屏意图撤下来。反过来的话，撤意图的过程中
        // `reapplyProjection` 之类的路径可能又把窗口建回去。
        stopCluster()
        // 🔴 PATCH-0106：同 `showClusterProjection()` —— 这一句也是网络发送
        // （`hideUI`/`stopUI` 一类的 `sendCommand`），在主线程会抛
        // `NetworkOnMainThreadException`。详见 [projectionExecutor] 的注释。
        projectionExecutor.execute {
            runCatching { session.setProjectionEnabled(false) }
        }
        if (settings.clusterModeGuardEnabled) {
            val fullMap = (clusterPresenter as? CarClusterFullMapControl)?.fullMapState()
            clusterLog("${source}：全屏模式还原状态=${fullMap?.phase ?: "未知"}")
        }
        clusterLog("${source}：仪表投屏已收起，仪表已还给车机")
        refreshModel()
    }

    // ---------------------------------------------------------------- 仪表标定参数（2026-10-21 / 2026-10-22）
    //
    // 表盘布局、自绘区域、视频画面变换都存同一个 SharedPreferences
    // （不进 CarSessionSettings）：它们不参与 CarPlay 握手，改动必须立即生效，
    // 且与「显示模式」一样属于纯本地标定数据。
    //
    // ⚠️ 文件名沿用 "dbplay_dial_layout"：老版本已把表盘布局写在这里，
    // 改名会让用户调好的布局丢失。新增参数用不同前缀的键（draw_ / video_）。
    //
    // ⚠️ 不能用 `by lazy`：本字段被 refreshModel() 使用，而 refreshModel 在 init 块里
    // 就被调用（CarAppController.kt:1174），早于本字段的 lazy 委托完成赋值
    // ⇒ 运行时 NPE「Lazy.getValue() on a null object reference」（1.1.45 实测崩溃，
    // 2026-10-21）。改成 getter 每次取：SharedPreferences 本身由系统缓存，无性能代价。
    private val clusterCalibrationPrefs: android.content.SharedPreferences
        get() = app.getSharedPreferences("dbplay_dial_layout", Context.MODE_PRIVATE)

    private fun dialKey(side: String, field: String) = "${side}_$field"

    /** 读取已保存的布局；无记录 ⇒ 默认恒等（画面与改造前一致）。 */
    private fun loadDialLayout(): ClusterDialLayout {
        val p = clusterCalibrationPrefs
        // 缺键时回落到工厂默认（2026-10-21 车机实测调优值），不是恒等变换。
        val d = ClusterDialLayout.DEFAULT
        fun read(side: String, fallback: DialTransform): DialTransform = DialTransform(
            dx = p.getFloat(dialKey(side, "dx"), fallback.dx),
            dy = p.getFloat(dialKey(side, "dy"), fallback.dy),
            scale = p.getFloat(dialKey(side, "scale"), fallback.scale),
            visible = p.getBoolean(dialKey(side, "visible"), fallback.visible),
        )
        return ClusterDialLayout.sanitize(ClusterDialLayout(read("left", d.left), read("right", d.right)))
    }

    private fun saveDialLayout(layout: ClusterDialLayout) {
        clusterCalibrationPrefs.edit().apply {
            putFloat(dialKey("left", "dx"), layout.left.dx)
            putFloat(dialKey("left", "dy"), layout.left.dy)
            putFloat(dialKey("left", "scale"), layout.left.scale)
            putBoolean(dialKey("left", "visible"), layout.left.visible)
            putFloat(dialKey("right", "dx"), layout.right.dx)
            putFloat(dialKey("right", "dy"), layout.right.dy)
            putFloat(dialKey("right", "scale"), layout.right.scale)
            putBoolean(dialKey("right", "visible"), layout.right.visible)
        }.apply()
    }

    override fun setDialLayout(layout: com.dbtool.carplay.api.display.ClusterDialLayout) {
        val safe = com.dbtool.carplay.api.display.ClusterDialLayout.sanitize(layout)
        saveDialLayout(safe)
        runCatching { clusterPresenter?.applyDialLayout(safe) }
            .onFailure { Log.w(TAG, "applyDialLayout failed: ${it.message}") }
        clusterLog(
            "仪表表盘布局：左(${safe.left.dx},${safe.left.dy},x${safe.left.scale}) " +
                "右(${safe.right.dx},${safe.right.dy},x${safe.right.scale})",
        )
    }

    // ------------------------------------------------------- 自绘区域 / 视频画面变换（2026-10-22）

    private fun drawRegionKey(field: String) = "draw_$field"

    /** 读取自绘区域；无记录 ⇒ 整幅 1920×720（与改造前逐像素一致）。 */
    private fun loadDrawRegion(): ClusterDrawRegion {
        val p = clusterCalibrationPrefs
        val d = ClusterDrawRegion.DEFAULT
        return ClusterDrawRegion.sanitize(
            ClusterDrawRegion(
                x = p.getFloat(drawRegionKey("x"), d.x),
                y = p.getFloat(drawRegionKey("y"), d.y),
                width = p.getFloat(drawRegionKey("w"), d.width),
                height = p.getFloat(drawRegionKey("h"), d.height),
            ),
        )
    }

    private fun saveDrawRegion(region: ClusterDrawRegion) {
        clusterCalibrationPrefs.edit().apply {
            putFloat(drawRegionKey("x"), region.x)
            putFloat(drawRegionKey("y"), region.y)
            putFloat(drawRegionKey("w"), region.width)
            putFloat(drawRegionKey("h"), region.height)
        }.apply()
    }

    override fun setDrawRegion(region: ClusterDrawRegion) {
        val safe = ClusterDrawRegion.sanitize(region)
        saveDrawRegion(safe)
        runCatching { clusterPresenter?.applyDrawRegion(safe) }
            .onFailure { Log.w(TAG, "applyDrawRegion failed: ${it.message}") }
        clusterLog(
            "仪表自绘区域：x=${safe.x} y=${safe.y} w=${safe.width} h=${safe.height} " +
                "内容缩放=x${"%.3f".format(safe.contentScale)}",
        )
        // 与 setDialLayout 一致：滑块每次拖动都会回调，这里不做 refreshModel()
        // 那种整页刷新；设置页用本地 state 即时回显，model 由下一次常规刷新带入。
    }

    private fun videoTransformKey(field: String) = "video_$field"

    /** 读取视频画面变换；无记录 ⇒ 恒等变换。 */
    private fun loadVideoTransform(): ClusterVideoTransform {
        val p = clusterCalibrationPrefs
        val d = ClusterVideoTransform.DEFAULT
        return ClusterVideoTransform.sanitize(
            ClusterVideoTransform(
                scale = p.getFloat(videoTransformKey("scale"), d.scale),
                dx = p.getFloat(videoTransformKey("dx"), d.dx),
                dy = p.getFloat(videoTransformKey("dy"), d.dy),
            ),
        )
    }

    private fun saveVideoTransform(transform: ClusterVideoTransform) {
        clusterCalibrationPrefs.edit().apply {
            putFloat(videoTransformKey("scale"), transform.scale)
            putFloat(videoTransformKey("dx"), transform.dx)
            putFloat(videoTransformKey("dy"), transform.dy)
        }.apply()
    }

    override fun setVideoTransform(transform: ClusterVideoTransform) {
        val safe = ClusterVideoTransform.sanitize(transform)
        saveVideoTransform(safe)
        runCatching { clusterPresenter?.applyVideoTransform(safe) }
            .onFailure { Log.w(TAG, "applyVideoTransform failed: ${it.message}") }
        clusterLog("仪表画面变换：scale=x${"%.3f".format(safe.scale)} dx=${safe.dx} dy=${safe.dy}")
        // 同上：拖动期间不整页刷新。
    }

    override fun setClusterAppearanceMode(mode: ClusterAppearanceMode) {
        if (settings.clusterAppearanceMode == mode) return
        settings = settings.copy(clusterAppearanceMode = mode)
        services.settingsStore.save(settings)
        session.updateSettings(settings)
        applyClusterAppearanceNow("设置页手动切换")
        refreshModel()
    }

    override fun setProjectionContent(url: String) {
        val ok = runCatching { session.setProjectionContent(url) }.getOrDefault(false)
        appendLog("仪表地图投屏：切换内容 ${if (ok) "成功" else "失败"}（$url）")
        refreshModel()
    }

    override fun zoomProjectionMap(zoomIn: Boolean) {
        // 🔴 2026-10-22（真车日志实证）：原来直接在主线程调 `session.zoomProjectionMap`，
        // 它在 `AirPlaySession.sendCommandLocked` 里做**裸 socket 写** ⇒
        // `NetworkOnMainThreadException`，命令根本没发出去。诊断包
        // `xcertplay.log.1:5175` 就是这一条：
        //   `airplay event command failed type=changeMapZoomLevel`
        //   `android.os.NetworkOnMainThreadException ... CarAppController.zoomProjectionMap`
        // 与 showClusterProjection / hideClusterProjection 同解：网络发送扔
        // [projectionExecutor]，日志与 model 刷新 post 回主线程。
        // 反编译原版同样走 `touchExecutor` 后台线程（CarPlayController.zoomDashboardMap）。
        projectionExecutor.execute {
            val ok = runCatching { session.zoomProjectionMap(zoomIn) }.getOrDefault(false)
            mainHandler.post {
                appendLog(
                    "仪表地图投屏：${if (zoomIn) "放大" else "缩小"}一步" +
                        if (ok) "" else "（未下发：未连接或仪表屏流未建立）",
                )
                refreshModel()
            }
        }
    }

    override fun requestMfiIdentityImport() {
        val request = mfiIdentityImportRequest
        if (request == null) {
            appendLog("当前宿主未提供文件选择器，无法导入本地 MFi 身份")
            return
        }
        request()
    }

    override fun importMfiIdentity(identityUri: Uri, certificateUri: Uri) {
        val status = services.mfiIdentity.importIdentity(identityUri, certificateUri)
        appendLog(
            if (status.available) {
                "本地 MFi 身份已更新：${status.sourceLabel}（${status.detail}）"
            } else {
                "本地 MFi 身份导入失败：${status.detail}"
            },
        )
        // 身份换了要重连才会生效：环回服务是在 buildSession() 里按当时读到的身份起的。
        if (sessionState.phase == CarSessionPhase.ACTIVE ||
            sessionState.phase == CarSessionPhase.PREPARING_MFI
        ) {
            requestReconnect(ReconnectSource.SETTINGS)
        }
        refreshModel()
    }

    override fun clearMfiIdentity() {
        val removed = services.mfiIdentity.clearImported()
        appendLog(if (removed) "已清除导入的本地 MFi 身份，改用随包内置版本" else "没有导入过本地 MFi 身份")
        refreshModel()
    }

    // ------------------------------------------------------------- AirPlay 图标

    override fun requestAirPlayIconImport() {
        val request = airPlayIconImportRequest
        if (request == null) {
            appendLog("当前宿主未提供图片选择器，无法更换 AirPlay 图标")
            return
        }
        request()
    }

    override fun importAirPlayIcon(iconUri: Uri) {
        val status = services.airPlayIcon.importIcon(iconUri)
        appendLog(
            if (status.imported) {
                "AirPlay 图标已更新：${status.sourceLabel}（${status.detail}）"
            } else {
                "AirPlay 图标导入失败：${status.detail}"
            },
        )
        // 图标是在 buildSession() 里读进 AirPlayConfig 的，换了必须重连才生效。
        // 与 MFi 身份同一条判据：只在会话真的在跑/正在准备时才重建。
        if (sessionState.phase == CarSessionPhase.ACTIVE ||
            sessionState.phase == CarSessionPhase.PREPARING_MFI
        ) {
            requestReconnect(ReconnectSource.SETTINGS)
        }
        refreshModel()
    }

    override fun clearAirPlayIcon() {
        val removed = services.airPlayIcon.clearImported()
        appendLog(if (removed) "已恢复默认 AirPlay 图标" else "没有导入过 AirPlay 图标")
        refreshModel()
    }

    override fun exitApp() {
        exitAction?.invoke()
    }

    /**
     * 无线建链失败页的 ADB 修复入口：导航到设置 → 连接 → ADB 通道卡片。
     *
     * 只发起导航，不代用户初始化、不启动自建热点；用户可在目标卡片查看当前 5556
     * 实测状态并显式触发初始化或修复。
     */
    override fun openDeveloperSettings() {
        requestNavigation(CarNavigationTarget.SETTINGS_HOTSPOT)
    }

    /**
     * 首次启动引导弹窗是否该显示。
     *
     * 判据只有一条：**落盘标记不在**。不在 ⇒ 显示并立刻把标记写上
     * （写在这里而不是在两个回调里，是为了覆盖"用户按返回键/点遮罩关掉"
     * 这条路径——那条路径不会调 [dismissHotspotGuide]，若只在回调里写标记，
     * 下次进来还会弹）。
     */
    override fun openHotspotGuide() {
        hotspotGuideVisible = false
        // 送到「设置 → 连接 → 热点服务」，那里有「立即建立热点 + ADB（永久）」。
        navigationRequest = CarNavigationRequest.fromTarget(
            CarNavigationTarget.SETTINGS_HOTSPOT,
            ++navigationRequestSeq,
        )
        refreshModel()
    }

    override fun dismissHotspotGuide() {
        hotspotGuideVisible = false
        refreshModel()
    }

    override fun consumeNavigationRequest() {
        if (navigationRequest == null) return
        navigationRequest = null
        refreshModel()
    }

    /**
     * 发一次"请界面跳过去"的请求。
     *
     * 自增 [navigationRequestSeq] 保证同一个目标**每次都触发**
     * （连点两次也各跳一次），因为 `LaunchedEffect` 的 key 变了才会重跑。
     */
    private fun requestNavigation(target: CarNavigationTarget) {
        navigationRequest = CarNavigationRequest.fromTarget(target, ++navigationRequestSeq)
        refreshModel()
    }

    override fun onVideoSurfaceAvailable(surface: Surface) {
        session.attachVideoSurface(surface)
    }

    override fun onVideoSurfaceDestroyed() {
        session.attachVideoSurface(null)
    }

    // ------------------------------------------------------------- 仪表副屏

    /**
     * 解析仪表副屏声明规格（2026-10-07，吉利 HDMI2 移植）。
     *
     * 返回 null ⇒ 不声明副屏。两种情形都会走到这里：
     * 1. 本平台没有副屏能力（`platform.cluster == null`）；
     * 2. 有副屏能力但本机此刻查不到那块屏（未接 / 已拔）。
     *
     * 规格由平台给出（[CarClusterPresenter.currentSpecOrNull]），本层只做转发与缓存，
     * 保证"声明的屏"与"挂窗口的屏"是同一块（见该接口注释）。
     *
     * ⚠️ 本函数会被 [com.dbtool.carplay.bridge.CarPlaySessionBridge] 在**每次建栈**时
     * 调用一次；因此必须便宜且不抛。
     */
    private fun resolveClusterSpec(): CarClusterSpec? {
        val presenter = clusterPresenter ?: return null
        val spec = runCatching { presenter.currentSpecOrNull() }.getOrNull() ?: return null
        // 把用户设置带进副屏规格：平台层不知道用户设置（`specOf` 是纯函数），在这里补齐。
        // - `safeArea` =「仪表导航 → 核心安全区」的矩形 → 报给 iPhone 的 insets；
        // - `safeAreaDrawOutside` =「显示与视频 → 画面绘制到安全区外」开关（主屏副屏共用）。
        // 两者缺一，副屏都会无视用户设置铺满整块面板（现象："画面绘制到安全区外"）。
        //
        // ⚠️ 2026-10-10 补：`safeArea` 这一项以前**根本没往 spec 里带**（只带了
        // `safeAreaDrawOutside`），于是 `CarClusterSpec.safeArea` 恒为 null ⇒
        // `CarPlayConfigBuilder.toAirPlayDisplay()` 认为整块面板都是可用区，
        // 用户在设置里填的核心区对副屏一点作用都没有（改前：填了也白填）。
        //
        // 全 0 时**保持原样（null）**，不塞一个"全 0 的 insets 对象"：`null` 在
        // `toAirPlayDisplay()` 里表示"不声明安全区"。
        // PATCH-0080：`settings.safeArea` 是**矩形**（坐标空间 = 主画布），副屏要用它
        // 必须先 `scaledTo()` 换算到副屏面板、再 `toInsets()` —— 直接把 x/y/w/h 当
        // insets 读出来，正是"报出去的高度为负 ⇒ iPhone 拆会话"那次事故的成因。
        //
        // ⚠️ 2026-10-11（PATCH-0093，车主：「仪表核心安全区改成 1920×720，现在的太大了」）：
        // 以前"用户没画过框"直接回落到 `spec.safeArea`（= null = 不声明）⇒ 副屏铺满
        // 1920×1080 面板、压到车机自己的油量/车速表。现在改成回落到**副屏自己的默认核心区**
        // （面板整宽 × 上方 720px）。整条策略在 `CarClusterSafeAreaPolicy` 里（纯函数、可单测）。
        //
        // ⚠️ 2026-10-11（PATCH-0094，车主：「仪表导航 的安全区只限副屏」）：
        // **这里是 `settings.safeArea` 在全工程唯一的消费点** —— 主屏已不再读它
        // （见 `CarPlayConfigBuilder.airPlayConfig` 里的长注释）。所以这一行不能删，
        // 删了就等于"用户画的框彻底没人用"。
        val mainCanvas = canvas
        val streamHeight = if (spec.widthPixels == 1920) minOf(spec.heightPixels, 720) else spec.heightPixels
        val resolved = spec.copy(
            heightPixels = streamHeight,
            heightPhysicalMm = spec.heightPhysicalMm?.let {
                (it.toDouble() * streamHeight / spec.heightPixels).toInt().coerceAtLeast(1)
            },
            visibleHeightPixels = minOf(spec.visibleHeightPixels, streamHeight),
            safeArea = CarClusterSafeAreaPolicy.resolve(
                drawn = settings.safeArea,
                // Instrument safety coordinates are the physical content canvas 1920x720.
                // Do not scale through Android HDMI mode 1920x1080: the lower 360px
                // belongs to the factory HUD and is outside DBPlay's coordinate space.
                mainCanvasWidthPixels = spec.widthPixels,
                mainCanvasHeightPixels = streamHeight,
                panelWidthPixels = spec.widthPixels,
                panelHeightPixels = streamHeight,
            ) ?: spec.safeArea,
            safeAreaDrawOutside = settings.clusterSafeAreaDrawOutside,
        )
        pendingClusterSpec = resolved
        return resolved
    }

    /**
     * 会话是否已进入"可建副屏窗口"的阶段（未启动 / 已停 / 失败 ⇒ 不建）。
     *
     * ⚠️ 读 `session.state` 而不是本类的 [sessionState]：`session.start()` 是同步
     * 推进阶段的，而 [onStateChanged] 由桥 `post` 回主线程。若读缓存值，
     * `startSession()` 末尾调本判据时还会看到 IDLE ⇒ 冷启动永远建不了窗。
     */
    private fun isClusterSessionUsable(): Boolean {
        val phase = runCatching { session.state.phase }.getOrDefault(sessionState.phase)
        return when (phase) {
            CarSessionPhase.IDLE, CarSessionPhase.STOPPED, CarSessionPhase.FAILED -> false
            else -> true
        }
    }

    /**
     * 解析"这次建窗该挂哪块屏"。
     *
     * 2026-10-20 起优先固定到 displayId=2（仪表面板），在提供
     * [CarClusterPresenter] 的平台上不再读取 `CarSessionSettings.clusterDisplayId`
     * 决定投屏目标。固定屏必须先在 [CarClusterPresenter.listCandidates] 的候选里确认；
     * 不在场时回退到原有的自动挑选：**仅当模式要求显示主题**（THEME_ONLY / BOTH）
     * 才按候选顺序挑第一块。
     *
     * 没有 [CarClusterPresenter] 的平台（通用 Android / Flyme 等）窗口由 core-bridge
     * 的投影控制器自建，本层拿不到候选：沿用历史 `clusterDisplayId`（≥0）作为目标，
     * 保住老配置的投屏链路（别的车型 displayId=2 未必是仪表，不能套用固定策略）。
     *
     * 自动挑选结果只记在 [autoPickedClusterDisplayId]，同时灌给 presenter；**不写设置**。
     * 固定屏在每次解析时都先重新确认；已挂在回退屏上的窗口不会因为 displayId=2 后到
     * 而热切换，下一次重连 / 重建窗口时会改用它。
     * 挑不到就返回 -1，由调用方只打日志、不抛异常。
     */
    private fun effectiveClusterDisplayId(mode: ClusterLayerMode): Int {
        val presenter = clusterPresenter
        if (presenter == null) {
            // 桥自建窗口路径（platformOwnsWindow=false）：没有候选枚举能力。
            // 固定 displayId=2 只适用于吉利仪表面板，这里沿用历史设置。
            val configured = settings.clusterDisplayId
            if (configured >= 0) {
                autoPickedClusterDisplayId = -1
                return configured
            }
            return -1
        }
        // 🔴 2026-10-20 现场回退：这里曾经加过 `if (!requiresTheme(mode)) return -1`，
        // 理由是"CARPLAY_ONLY 不该多声明副屏"——**这个判断是错的**。
        // CARPLAY_ONLY 的语义是"仪表只显示 CarPlay 画面、不画主题层"，副屏仍然必须声明，
        // 否则 iPhone 不发 111 流，仪表整块收不到画面。实车日志：
        //   `Cluster: 副屏声明跳过：未选副屏（clusterDisplayId=-1） ⇒ /info 不声明副屏
        //    （仪表不会收到 CarPlay 画面）`
        // requiresTheme 只决定"要不要画主题层"，不决定"要不要副屏"。
        val candidates = runCatching { presenter.listCandidates() }.getOrDefault(emptyList())
        val fixed = candidates.firstOrNull { it.displayId == FIXED_CLUSTER_DISPLAY_ID }
        if (fixed != null) {
            // 固定屏在场 ⇒ 旧的自动挑选作废，以仪表板 displayId=2 为准。
            autoPickedClusterDisplayId = -1
            pendingClusterSpec = null
            (presenter as? CarClusterDisplaySelection)?.setRequestedDisplayId(fixed.displayId)
            clusterLog("Cluster: 固定投到 displayId=2（仪表面板）")
            return fixed.displayId
        }
        clusterLog("Cluster: 固定屏不在场，回退自动挑（displayId=2 不在候选）")
        if (!ClusterLayerAutoAttach.requiresTheme(mode)) return -1
        if (autoPickedClusterDisplayId >= 0) return autoPickedClusterDisplayId
        val picked = candidates.firstOrNull() ?: return -1
        clusterLog(
            "T1|display.auto_pick|picked=${picked.displayId} " +
                "picked_size=${picked.widthPixels}x${picked.heightPixels} " +
                "cache_before=$autoPickedClusterDisplayId " +
                "n=${candidates.size} " +
                "candidates=[${candidates.joinToString(",") { it.displayId.toString() }}]",
        )
        autoPickedClusterDisplayId = picked.displayId
        pendingClusterSpec = null
        (presenter as? CarClusterDisplaySelection)?.setRequestedDisplayId(picked.displayId)
        clusterLog("Cluster: 副屏候选自动挑选 displayId=${picked.displayId}（${picked.label}）")
        return picked.displayId
    }

    /**
     * 模式要求显示主题、但窗口没挂时，主动建一次窗（建窗 + 抢屏都复用
     * [startClusterIfPossible]，不另开一条建窗链）。
     *
     * 三条降级路径都只打日志，绝不让会话看到异常：
     * - 没有可用副屏候选；
     * - 会话还没起来（挂起，等 ACTIVE 后由 [onStateChanged] 补建）；
     * - 建窗失败。
     *
     * 必须在主线程执行（`Presentation.show()` 要求）；设置监听可能来自后台
     * （例如设置仓库在别的线程恢复（restore）模式）⇒ 这里统一切回主线程。
     */
    private fun maybeAttachClusterForLayerMode(mode: ClusterLayerMode, source: String) {
        if (!ClusterLayerAutoAttach.requiresTheme(mode)) {
            // 切到"仅 CarPlay"：之前挂起的补建窗请求作废（不拆已存在的窗口，
            // 模式契约是"不拆窗、不断流"）。
            pendingThemeClusterMode = null
            return
        }
        // 2026-10-22 解耦（P0）：主题建窗**不再依赖**「投屏到仪表」总开关。
        //
        // 总开关只属于导航模块；主题模式（THEME_ONLY / BOTH）要求显示主题时，
        // 即使导航投屏关着也必须有一块窗口来画主题。原来这里在
        // `projectionEnabled=false` 时直接跳过（PATCH-0122 口径），导致
        // “只开主题、关导航”永远建不出窗（C4）。
        //
        // 默认组合（`CARPLAY_ONLY` + 关闭）仍会在下面的 requiresTheme 判据
        // 直接返回，与解耦前逐项一致。
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { maybeAttachClusterForLayerMode(mode, source) }
            return
        }
        val presenter = clusterPresenter ?: return
        if (presenter.isWindowAttached()) {
            // 已经挂上了：模式切换只改图层，不重建窗口（契约：不拆窗、不断流）。
            pendingThemeClusterMode = null
            return
        }
        val displayId = effectiveClusterDisplayId(mode)
        if (displayId < 0) {
            clusterLog("仪表图层：$source 未建窗 —— 没有可用的副屏候选")
            return
        }
        val sessionActive = isClusterSessionUsable()
        if (!sessionActive) {
            pendingThemeClusterMode = mode
            return
        }
        if (!ClusterLayerAutoAttach.shouldAttach(
                mode = mode,
                attached = presenter.isWindowAttached(),
                displayId = displayId,
                sessionActive = true,
            )
        ) {
            return
        }
        clusterLog("仪表图层：模式要求显示主题 → 主动建窗 displayId=$displayId")
        val failure = runCatching { startClusterIfPossible() }.exceptionOrNull()
        if (failure != null || !presenter.isWindowAttached()) {
            val reason = failure?.let { it.message ?: it.javaClass.simpleName }
                ?: if (runCatching { presenter.currentSpecOrNull() }.getOrNull() == null) {
                    "副屏规格不可用（displayId=$displayId 不在场或不可选）"
                } else {
                    "窗口未挂载（${runCatching { session.projectionState.summary }.getOrDefault("未知")}）"
                }
            clusterLog("仪表图层：仅主题模式建窗失败（$reason）")
            return
        }
        pendingThemeClusterMode = null
        // 网络发送必须在后台（同 `showClusterProjection` 的 PATCH-0106 口径）。
        //
        // 2026-10-22 解耦（P0）：只有导航模块确实要求显示时才让 iPhone 画。
        // 只开主题（projectionEnabled=false / navigationLayerVisible=false）时
        // 窗口可以挂、111 Surface 可以绑，但不发 showUI —— 导航层反正不可见，
        // 没必要让手机持续绘制一条没人看的流。
        if (settings.projectionEnabled && navigationLayerVisible) {
            projectionExecutor.execute {
                runCatching { session.setProjectionEnabled(true) }
            }
        }
    }

    /**
     * 主题与导航两个图层都不需要时，拆掉仪表窗口（2026-10-22 解耦，P0）。
     *
     * 只处理**运行时**已经存在窗口的情况：
     * - `mode.showsTheme=false`（模式不再要求主题）；
     * - `navigationLayerVisible=false`（导航层此刻不可见）；
     * - 窗口确实挂着。
     *
     * 任一条件不满足都保留窗口 —— 这正是“关一个不影响另一个”的落点。
     * 必须在主线程拆窗（`Presentation.dismiss()` 的要求）；设置恢复可能来自
     * 后台线程，所以这里统一切主线程。
     */
    private fun maybeTearDownClusterIfNoLayerNeeded(mode: ClusterLayerMode) {
        val presenter = clusterPresenter ?: return
        val teardown = {
            if (ClusterLayerVisibilityPolicy.shouldTearDownWindow(
                    mode = mode,
                    navigationVisible = navigationLayerVisible,
                    windowAttached = presenter.isWindowAttached(),
                )
            ) {
                clusterLog("仪表图层：主题与导航都已关闭 → 拆除仪表窗口")
                stopCluster()
                refreshModel()
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) teardown()
        else mainHandler.post(teardown)
    }

    /**
     * 只改导航视频层的显隐：不拆窗、不解绑 Surface、不动主题层。
     *
     * 2026-10-22 解耦（P0）。同时推给两条路径：
     * - 平台 presenter（吉利实车窗口）；
     * - `CarPlayClusterProjection` 的进程内运行时（通用平台投影控制器建窗时取用）。
     *
     * ⚠️ 只在主线程调用（设置恢复走 [maybeAttachClusterForLayerMode] 主线程分支）。
     */
    private fun applyNavigationLayerVisibility(visible: Boolean) {
        navigationLayerVisible = visible
        CarPlayClusterProjection.applyNavigationVisible(visible)
        clusterPresenter?.applyNavigationVisible(visible)
    }

    /**
     * 挂载仪表副屏窗口，并把它的 Surface 交给 ALT(111) 轨。
     *
     * ## 谁调它（2026-10-20 更新）
     *
     * 两个入口，都必须由**用户/模式明确要求**触发：
     * - [showClusterProjection]（设置页手动操作 / 导航启动自动投屏）；
     * - [maybeAttachClusterForLayerMode]（图层模式是 THEME_ONLY / BOTH）。
     *
     * ⚠️ `onEvent(SessionStarted)` 里**仍然不调** —— 见那里的长注释：建窗会连带
     * `seizeCluster()` 把车机仪表从原厂 DimMenu 手里抢走，单纯"连上手机"不该抢。
     * 主题/双层模式是车主明确选的显示方式，所以由模式变更本身驱动。
     *
     * ## 窗口生命周期
     *
     * 每次调都**重建**窗口（`GeelyClusterPresenter.start()` 内部 PATCH-0052 起
     * 就不复用副屏窗口），确保 Surface 不跨会话/跨投屏复用 —— 复用的后果是
     * 把上一轮的旧 Surface 又交给 ALT(111)，画面永远到不了窗口（白屏）。
     *
     * 必须在**主线程**调用（`Presentation.show()` 要求）；会话事件由 session 桥投递到主线程。
     */
    private fun startClusterIfPossible() {
        if (instrumentVideoSuppressed()) return
        val presenter = clusterPresenter ?: return
        if (!presenter.isAvailable()) return
        val spec = pendingClusterSpec ?: resolveClusterSpec() ?: return
        // 2026-10-18：守护链路在「模式已进入、但首帧迟迟不到」时半程催一次 I 帧。
        // 开关关闭时状态机不会调用它，行为与旧版一致。
        (presenter as? CarClusterFullMapControl)?.setKeyFrameRequester {
            session.requestClusterKeyFrame()
        }
        presenter.start(spec) { surface ->
            // presenter 保证在主线程回调；直接转发。
            runCatching {
                Log.i(
                    TAG,
                    "T0|surface.downstream|surface=${if (surface == null) "null" else "non-null"} " +
                        "valid=${surface?.isValid ?: false} thread=${Thread.currentThread().name}",
                )
            }
            // A theme-only window is allowed, but it must not own a phone video decoder.
            session.attachClusterVideoSurface(surface.takeIf { settings.projectionEnabled && navigationLayerVisible })
        }
    }

    /** 关闭仪表副屏窗口（会话结束/进程退出时）。幂等。 */
    private fun stopCluster() {
        clusterPresenter?.stop()
        // 明确解绑 ALT 轨：副屏窗口没了还挂着旧 Surface，会让 Core 往一块
        // 已销毁的 Surface 上渲染（Android 会报错刷日志）。
        session.attachClusterVideoSurface(null)
    }

    // ------------------------------------------------------------- HUD 地图投屏（2026-10-22）

    /**
     * 把 HUD 投屏的「流建立 / 关键帧」回调接到会话上。
     *
     * 只在 init 里装一次；setter 是幂等的（重复装只会替换引用）。
     */
    private fun installHudWiring() {
        val hud = hudProjection ?: return
        hud.setStreamActiveListener { active ->
            // 111 流可能晚于 HUD 开关出现（握手才建流）。流真出现后补一次
            // `setProjectionEnabled(true)`，让 iPhone 开始绘制；否则 HUD 永远空窗。
            if (active && settings.hudProjectionEnabled) {
                projectionExecutor.execute {
                    runCatching { session.setProjectionEnabled(true) }
                }
                clusterLog("HUD 投屏：111 流已建立，已请求 iPhone 开始绘制")
            }
        }
        hud.setKeyFrameRequester { session.requestClusterKeyFrame() }
    }

    /**
     * 按当前设置启动 / 更新 / 关闭 HUD 地图投屏。
     *
     * ## 为什么先过 [AltScreenSlotPolicy]
     *
     * HUD 在 [CarHudDisplayMode.SHARED_111] 下消费的是**仪表那条 111 流**。
     * 如果本机没有可声明的副屏 spec（`resolveClusterSpec()` 返回 null），
     * iPhone 根本不会开 111 —— 此时开 HUD 窗口只会得到一块永远空白的屏。
     * 策略层返回 [AltScreenSlotDecision.Conflict]，这里如实报错，
     * **不回退到别的屏或别的流**。
     *
     * ## 线程
     *
     * `Presentation.show()` 要求主线程；设置自动保存可能来自任意线程，
     * 因此入口处统一切主线程。窗口已在时只更新裁剪（下一帧生效）。
     */
    private fun applyHudProjection(source: String = "设置变更") {
        if (instrumentVideoSuppressed()) { platform.hudProjection?.stop(); return }
        val hud = hudProjection ?: return
        if (!settings.hudProjectionEnabled) {
            if (hudProjectionActive) {
                hud.stop()
                hudProjectionActive = false
                // 只收 HUD：仪表窗口还挂着时**绝不能**发 stopUI，否则仪表也黑。
                if (clusterPresenter?.isWindowAttached() != true) {
                    projectionExecutor.execute {
                        runCatching { session.setProjectionEnabled(false) }
                    }
                }
                clusterLog("HUD 投屏：已关闭（$source）")
            }
            return
        }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { applyHudProjection(source) }
            return
        }

        val clusterWindowActive = clusterPresenter?.isWindowAttached() == true
        val decision = altScreenSlotPolicy.decide(
            clusterProjectionRequested = clusterWindowActive,
            hudIndependentRequested = true,
            hudSpecKnown = true,
            clusterSpec = resolveClusterSpec(),
        )
        if (decision is AltScreenSlotDecision.Conflict) {
            if (hudProjectionActive) {
                hud.stop()
                hudProjectionActive = false
            }
            clusterLog(
                "HUD 投屏：槽位冲突 ${decision.reason}（$source）—— " +
                    "拿不到副屏 spec 时 iPhone 不会建立 111 流",
            )
            return
        }

        val profile = CarHudDisplayProfile(
            enabled = true,
            mode = CarHudDisplayMode.SHARED_111,
            crop = settings.hudCrop,
            scale = HudScaleSpec(mode = settings.hudScaleMode),
        )
        val wasActive = hudProjectionActive
        when (
            val result = hud.start(profile, clusterWindowActive) { surface ->
                session.attachHudVideoSurface(surface)
            }
        ) {
            is CarHudStartResult.Started -> {
                hudProjectionActive = true
                if (!wasActive) {
                    clusterLog(
                        "HUD 投屏：已启动（$source，共享 111" +
                            if (clusterWindowActive) "，仪表窗口同时挂载 ⇒ 三路解码）" else "）",
                    )
                    // 111 流已存在时立即请 iPhone 开始绘制；尚未建立时会被拒，
                    // 等 `setStreamActiveListener` 的 active=true 再补。
                    projectionExecutor.execute {
                        runCatching { session.setProjectionEnabled(true) }
                    }
                }
            }

            is CarHudStartResult.Unavailable -> {
                hudProjectionActive = false
                clusterLog("HUD 投屏：不可用 ${result.reason}（$source）")
            }

            is CarHudStartResult.Conflict -> {
                hudProjectionActive = false
                clusterLog("HUD 投屏：冲突 ${result.reason}（$source）")
            }
        }
    }

    override fun onVideoCanvasSizeChanged(widthPixels: Int, heightPixels: Int) {
        if (widthPixels <= 0 || heightPixels <= 0) return
        val next = CarVideoCanvas(widthPixels, heightPixels)
        // ------------------------------------------------------------------
        // ⚠️ 2026-10-07：换掉旧的 `roughlyEquals` 容差判定，改用
        // `isRealResolutionChange`（宽高比判据）。
        //
        // 【为什么】0.10.10 车机日志（20:43~20:45）实证了 4 次这样的循环：
        //
        //     AudioTrack pause()+stop()  →  4~5 秒静音  →  AudioTrack start()
        //     20:43:32.629 → 20:43:37.150   （静默 4.52s）
        //     20:44:29.811 → 20:44:34.535   （静默 4.72s）
        //     20:44:52.910 → 20:44:58.122   （静默 5.21s）
        //     20:45:51.954 → （日志结束）
        //
        // 触发链：画布尺寸上报变化 → 这里判为"真变了" → `session.reconnect()`
        // → 整条协议栈重建 → `AndroidMediaSink.close()` → `track.release()`
        // → 音轨整体销毁 → 4~5 秒真静音。用户听到的就是"音乐卡一下又继续"。
        //
        // 旧判据 `roughlyEquals`（96px 容差）挡的是**单次跳变**，挡不住
        // 高度在容差内来回蹭：1080 → 1008 → 1032 → 1080 每一步都 < 96px，
        // 但 1032 越过了"上一次已接受值"，于是放行重建。详见
        // `CarVideoCanvas.isRealResolutionChange` 的说明。
        //
        // 判据细节与实测数据都写在 `CarVideoCanvas` 上（放在那里才能被
        // `car/core-bridge` 的单测覆盖到）。这里只负责调用。
        // ------------------------------------------------------------------
        val previous = canvas
        canvas = next
        // 首次上报（进入 CarPlay 页面时）只记录，不重连：
        // 那时画布"从无到有"，尺寸通常与面板一致，不需要重新握手。
        if (previous == null) return
        if (!previous.isRealResolutionChange(next)) return
        // 只有**运行中尺寸真的换了比例**才值得重建整条栈（代价很高：断音 4~5 秒）。
        if (sessionState.phase == CarSessionPhase.ACTIVE) {
            scheduleCanvasReconnect()
        }
    }

    override fun onTouch(contacts: List<CarTouchContact>) {
        session.sendTouch(contacts)
    }

    override fun requestPermissions() {
        permissionRequestAction?.invoke()
    }

    // ------------------------------------------------------------ 开发者模式

    /**
     * 版本号连点。
     *
     * 7 次内任意两次间隔超过 [TAP_RESET_MILLIS] 就重新计数——车机上误触
     * 很常见，没有超时重置的话"零星点了 7 次"也会解锁。
     */
    override fun onVersionTap() {
        val now = SystemClock.uptimeMillis()
        versionTapCount = if (now - lastVersionTapAtMillis > TAP_RESET_MILLIS) {
            1
        } else {
            versionTapCount + 1
        }
        lastVersionTapAtMillis = now
        if (versionTapCount >= DEVELOPER_UNLOCK_TAP_COUNT) {
            versionTapCount = 0
            developerHost.unlock()
            appendLog("开发者模式已开启")
        }
        refreshModel()
    }

    override fun refreshDeveloperInfo() {
        // 读 getprop / dumpsys 会阻塞，必须走异步；回调已经在主线程。
        developerHost.refreshAsync { refreshModel() }
    }

    override fun scanAudioOutput() {
        val inputs = modelAudioScanInputs()
        if (!developerHost.scanAudioOutput(inputs) { refreshModel() }) appendLog("音频扫描正在进行或开发者模式未解锁")
    }

    private fun modelAudioScanInputs(): List<String> =
        session.activeAudioStreams().map { it.description() }

    override fun collectAndUploadLogs(scope: CarLogScope, includeClientIpv4: Boolean) {
        val accepted = developerHost.collectAndUpload(scope, includeClientIpv4) { refreshModel() }
        if (!accepted) appendLog("上一次采集或 TCP 握手取证还没结束，请稍候再试")
        refreshModel()
    }

    override fun startAirPlayTcpCapture() {
        val accepted = developerHost.startAirPlayTcpCapture { refreshModel() }
        if (!accepted) appendLog("诊断上传或 TCP 抓包正在运行，请等本次操作完成")
        refreshModel()
    }

    override fun lockDeveloperMode() {
        developerHost.lock()
        versionTapCount = 0
        appendLog("已退出开发者模式")
        refreshModel()
    }

    override fun runAdbHotspotSelfCheck(interfaceName: String) {
        appendLog("开始 ADB 热点自检…")
        // 回调由实现投递回主线程，这里直接刷新模型即可。
        services.adbHotspot.selfCheck(interfaceName) { refreshModel() }
        refreshModel()
    }

    override fun runNativeHotspotProbe() {
        appendLog("开始探测车机原生热点（ADB）…")
        // 同样是"实现侧开线程、回调回主线程"：探测要跑十几条 shell 命令，
        // 放到主线程会直接 ANR。
        services.nativeHotspot.requestProbe { refreshModel() }
        refreshModel()
    }

    /**
     * 「启动性能优化」（2026-10-07，斌哥口径）。
     *
     * 原文："你在设置里面加入一个启动性能优化，启动后执行这条
     * `pm compile -m speed -f`"。
     *
     * ⚠️ 2026-10-08：设置页那颗「立即执行一次性能优化」按钮已按车主口径移除
     * （「移除设置-启动-立即执行一次性性能优化」），所以本方法**不再是契约方法**，
     * 只剩**开机自动执行**这一条调用路径（见 `startSession()`）。
     * 相应地 `CarUiModel.perfCompileResult` 也删了——结果不再回填设置页，
     * 用户可见的反馈就是下面那个 `notice` 气泡。
     *
     * 首次实测几十秒，**绝不能放主线程**（会 ANR）。
     */
    private fun runStartupPerfCompile() {
        appendLog("开始启动性能优化：pm compile -m speed -f")
        perfExecutor.execute {
            val (ok, detail) = services.runStartupPerfCompile()
            mainHandler.post {
                appendLog(detail)
                // 结果以通知气泡展示一次 —— 按钮移除后这是**唯一**的用户可见反馈，
                // 不能让"开机自动预编译"变成静默动作（车主无从判断开关生效没有）。
                notice = if (ok) "启动性能优化完成" else "启动性能优化未完成：$detail"
                refreshModel()
            }
        }
    }

    /** 用户明确触发 5556 初始化；此操作只准备 ADB 通道，不会启动自建热点。 */
    override fun onInitializeAdb(onResult: (CarAdbInitializationState) -> Unit) {
        adbInitializationState = adbInitializationState.copy(
            channelStatus = CarAdbChannelStatus.INITIALIZING,
            detail = "正在初始化 5556；仅在此显式操作中写端口属性/请求提权。",
        )
        refreshModel()
        appendLog("用户手动触发 ADB 5556 初始化；自建热点不会随初始化启动。")
        briefingExecutor.execute {
            val result = runCatching { services.initializeAdbNow() }.getOrElse { error ->
                adbInitializationState.copy(
                    initializedByUser = true,
                    initializationSucceeded = false,
                    persistentPortVerified = false,
                    channelStatus = CarAdbChannelStatus.UNAVAILABLE,
                    detail = "ADB 初始化失败：${error.message ?: error.javaClass.simpleName}",
                )
            }
            mainHandler.post {
                adbInitializationState = result
                appendLog(result.detail)
                onResult(result)
                refreshModel()
            }
        }
    }

    /**
     * 「关闭 ADB」（2026-10-15）。与 [onInitializeAdb] 互为开关：一颗开、一颗关。
     *
     * 关的是 **DBPlay 自己的 5556 通道**：落盘禁用标记 + 收回共享通道。
     * **不写系统属性、不 `ctl.stop adbd`** —— 车机系统自带的 ADB 不受影响；
     * 这句话写在回执的 `detail` 里由界面显示，不让用户以为"已经把系统 ADB 关了"。
     *
     * 收通道（`releaseAll`）会关掉自建 adb server 并回收长驻客户端进程，
     * 可能花上几百毫秒，所以与自愈同规格：挪到 worker 线程，结果 post 回主线程刷 UI。
     */
    override fun onDisableAdb(onResult: (CarAdbInitializationState) -> Unit) {
        appendLog("用户主动关闭 ADB 5556 通道。")
        briefingExecutor.execute {
            val result = runCatching { services.disableAdbNow() }.getOrElse { error ->
                adbInitializationState.copy(
                    detail = "关闭 ADB 失败：${error.message ?: error.javaClass.simpleName}",
                )
            }
            mainHandler.post {
                adbInitializationState = result
                appendLog(result.detail)
                onResult(result)
                refreshModel()
            }
        }
    }

    /**
     * 「立即自愈 ADB」（2026-10-04）。
     *
     * `healAdbNow()` **阻塞**（要起 adb server、自连、探测，秒级到十几秒），
     * 所以必须挪到 worker 线程；结果用 `post` 回主线程刷 UI。
     */
    override fun onHealAdb(onResult: (Boolean, String) -> Unit) {
        appendLog("正在自愈 ADB 通道（重建 adb server 并重连）…")
        briefingExecutor.execute {
            val ok = runCatching { services.healAdbNow() }
                .onFailure { appendLog("ADB 自愈异常：${it.message ?: it::class.java.simpleName}") }
                .getOrDefault(false)
            mainHandler.post {
                appendLog(if (ok) "ADB 自愈完成：shell 通道可用。" else "ADB 自愈失败：仍拿不到可用 shell。")
                onResult(ok, if (ok) "" else "仍拿不到可用 shell，可稍后重试或重启 App")
                refreshModel()
            }
        }
    }

    // ------------------------------------------------------------ 在线更新

    override fun onCheckUpdate() {
        updateHost.checkAsync(manual = true) { refreshModel() }
    }

    override fun onDownloadUpdate() {
        // ⚠️ 必须用**命名参数**：`download` 现在还有第二个参数 `silent`，
        // 直接把 lambda 写在括号外（trailing lambda）会被当成 `silent: Boolean`
        // —— 编译报的是一句很难懂的 "No value passed for parameter 'onState'"。
        val accepted = updateHost.download(onState = { refreshModel() })
        if (!accepted) appendLog("更新包正在下载中，或服务端没有配置下载地址")
        refreshModel()
    }

    /**
     * 安装。
     *
     * 依次尝试：`PackageInstaller` 静默 → **ADB 静默**(2026-10-07 新增)
     * → 系统安装器（`startActivity`）。
     *
     * ⚠️ 这里**仍然可以在主线程调用**，但原因已经变了（2026-10-11）：
     * ADB 那条路（`adb push` 几十 MB + 设备端六级降级，实车几分钟）
     * **已经下沉到 `CarUpdateManager` 的 worker 线程**，本方法会在提交后
     * 立刻返回 `ADB_COMMITTED`；只有走到系统安装器兜底那一步才需要主线程
     * （`startActivity`，由 manager 自己 `post` 回主线程）。
     * 换句话说：主线程不再被 adb 阻塞，`install()` 也不会 ANR。
     */
    override fun onInstallUpdate() {
        when (updateHost.install()) {
            CarInstallOutcome.SILENT_COMMITTED -> appendLog("已提交系统安装")
            CarInstallOutcome.ADB_COMMITTED -> appendLog("已通过 ADB 静默提交安装")
            CarInstallOutcome.LAUNCHER_OPENED -> appendLog("已拉起系统安装界面")
            CarInstallOutcome.NEEDS_UNKNOWN_SOURCES ->
                appendLog("需要先允许 DBPlay 安装应用")

            CarInstallOutcome.FAILED -> appendLog("安装失败，请重试")
        }
        refreshModel()
    }

    override fun onDismissUpdate() {
        updateHost.dismiss()
        refreshModel()
    }

    /**
     * 「关于」页的「后台自动更新」开关（2026-10-11）。
     *
     * 打开后：发现新版本会自动下载，并在**未连接手机**时自动安装（全程不弹窗）。
     * 关掉后再打开 = **解除熔断**（车主在"自动装不上"时的自救手段之一）。
     */
    override fun onToggleSilentUpdate(enabled: Boolean) {
        updateHost.setSilentUpdateEnabled(enabled)
        appendLog(if (enabled) "已开启后台自动更新" else "已关闭后台自动更新")
        refreshModel()
    }

    /** 由宿主设置提示信息（例如缺少权限）。 */
    fun setNotice(text: String?) {
        notice = text
        refreshModel()
    }

    // ------------------------------------------------------- CarSessionListener

    private fun onRemoteBlockChanged(blocked: Boolean) {
        if (!blocked) return
        stop()
        app.startActivity(
            Intent(app, BlockedActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            },
        )
    }

    override fun onStateChanged(state: CarSessionState) {
        sessionState = state
        // 重连已经走到终态 ⇒ 放开 in-flight 守卫，允许下一次重连请求进来。
        // ⚠️ 不在 RECONNECTING / CONNECTING 清：那正是"重建还在进行"的窗口。
        when (state.phase) {
            CarSessionPhase.ACTIVE,
            CarSessionPhase.FAILED,
            CarSessionPhase.STOPPED,
            CarSessionPhase.IDLE,
            -> reconnectInFlight = false
            else -> Unit
        }
        // 空闲窗口计时（2026-10-11，后台静默更新）：纯函数推进，非空闲清零、
        // 空闲记起点。判定逻辑全在 `CarSilentUpdatePolicy` 里，可单测。
        idleSinceMillis = advanceIdleClock(state.phase, idleSinceMillis, SystemClock.uptimeMillis())
        scheduleIdleWindowCheck()
        // 模式变更先到、会话后到 ⇒ 会话一旦进入 ACTIVE，补一次建窗。
        // 这是"由模式本身触发"的延迟执行，不是"连上手机就抢仪表"：
        // 只有模式要求显示主题、且当时因会话未就绪被挂起时才会走到这里。
        if (state.phase == CarSessionPhase.ACTIVE) {
            pendingThemeClusterMode?.let { mode ->
                pendingThemeClusterMode = null
                maybeAttachClusterForLayerMode(mode, source = "会话就绪补建窗")
            }
        }
        if (state.phase == CarSessionPhase.FAILED) {
            when (state.reconnect.state) {
                // 这一次（第 N 次）自动重建失败了 ⇒ 记录原因并排下一次，或落耗尽。
                // 断链 A 的修复点：以前 failStart 之后没有人再排期，3 次额度跑不满。
                CarReconnectState.IN_PROGRESS -> scheduleAutoReconnect()

                // 晚到的上游 `Failed` 把已排期的 RECONNECTING 覆盖成了 FAILED：
                // 重新断言重连状态，**不**重复排期（额度不能被同一个失败吃两次）。
                CarReconnectState.SCHEDULED -> session.updateReconnect(state.reconnect)

                else -> Unit
            }
        }
        refreshModel()
    }

    /**
     * 排一个"空闲满 30 秒"的闹钟。
     *
     * 每次状态变化都重排（先 `removeCallbacks`）：转入非空闲时 [idleSinceMillis]
     * 被清零，这里直接返回，闹钟也就取消了 —— 正好是想要的语义
     * （"中途连上手机 ⇒ 重新计时"）。
     */
    private fun scheduleIdleWindowCheck() {
        mainHandler.removeCallbacks(idleWindowCheck)
        if (idleSinceMillis <= 0L) return
        val elapsed = SystemClock.uptimeMillis() - idleSinceMillis
        val remaining = (SILENT_INSTALL_IDLE_MILLIS - elapsed).coerceAtLeast(0L)
        mainHandler.postDelayed(idleWindowCheck, remaining)
    }

    /**
     * 空闲已经稳定、且正有一个包在等安装 ⇒ 通知更新链路动手。
     *
     * ⚠️ 由 [refreshModel] 调用（而不是只在闹钟里），因为"包下好"这个事件
     * 本身也会触发 `refreshModel()` —— 只挂闹钟的话，"空闲早就满了、
     * 包是后来才下好的"这条路径永远等不到通知。
     *
     * 幂等由实现侧保证（`CarUpdateManager` 用 `AtomicBoolean` 挡住重复提交）。
     */
    private fun maybeNotifyIdleWindow() {
        if (updateHost.state().phase != CarUpdatePhase.PENDING_INSTALL) return
        if (!idleWindowSettled(idleSinceMillis, SystemClock.uptimeMillis())) return
        updateHost.notifyIdleWindow()
    }

    override fun onEvent(event: CarSessionEvent) {
        if (event == CarSessionEvent.AudioStreamsChanged) { refreshModel(); return }
        when (event) {
            is CarSessionEvent.LogLine -> appendLog(event.message)
            is CarSessionEvent.DeviceConnected ->
                appendLog("设备已连接：${event.name}${event.model?.let { " ($it)" } ?: ""}")

            is CarSessionEvent.TransportError -> appendLog("传输错误：${event.message}")
            CarSessionEvent.SessionStarted -> {
                appendLog("CarPlay 会话已建立")
                // 自动重连（或用户手动连）成功 ⇒ 撤掉在途闹钟，并把"稳定 30 秒后
                // 归零计数"排上。⚠️ 这里**不直接归零**：`SessionStarted` 在
                // "连上就掉"的坏会话上也会触发，直接归零会让退避变成无限循环
                // （见 `CarSessionAutoReconnectPolicy` 的类注释）。
                cancelAutoReconnectCheck()
                mainHandler.removeCallbacks(autoReconnectReset)
                mainHandler.postDelayed(autoReconnectReset, AUTO_RECONNECT_STABLE_RESET_MILLIS)
                // 连上 ⇒ 重试轴回到 NONE（次数**不**归零：那要等稳定 30 秒）。
                // 故意重连的 `SessionEnded(RECONNECT)` 刻意保留 IN_PROGRESS，
                // 这里才是它真正的清除点。
                reconnectInFlight = false
                session.updateReconnect(CarReconnectStatus())
                // 2026-10-22：会话建立后按设置激活 ECarX HUD 使能通道。
                // 通道不可用时 `startHudPush()` 只记 `xcertplay-hud` 日志，
                // 不弹窗、不阻塞连接（设置默认关）。
                if (settings.hudPushEnabled) startHudPush()
                // ------------------------------------------------------------------
                // Only the user's saved projection switch starts projection on
                // connection; route start/end never controls this window.
                mainHandler.post {
                    if (sessionState.phase == CarSessionPhase.ACTIVE && settings.projectionEnabled) {
                        showClusterProjection(source = "投屏开关已开启，会话就绪", manualOpen = true)
                    }
                }
                // 历史原因：未打开投屏开关时，不因连接手机抢走原厂仪表。
                //
                // 【为什么必须删】`startClusterIfPossible()` → `GeelyClusterPresenter.start()`
                // → `attach()` 末尾会 `seizeCluster()`（`DimMenuInteraction.switchNaviMode(3)`）。
                // 也就是说，原来的行为是「**只要 iPhone 连上，车机仪表就被抢成"全屏地图"
                // 模式**」——用户想用车机原厂仪表（油量/车速/胎压）反而用不了。
                // 真车诊断包 `1791725755414_…` 逐字对得上：21:34:25.824 `仪表副屏已挂载`、
                // 21:34:25.828 `仪表抢屏 entered=true`，而这一切的触发点只是"会话建立了"。
                //
                // 【现在谁建窗】设置页的手动投屏操作、导航自动投屏请求，或
                // `maybeAttachClusterForLayerMode()`（THEME_ONLY / BOTH 模式变更）。
                // Presentation 仍按既有语义重建窗口，不复用跨会话的旧 Surface。
                //
                // 【会话结束仍然拆窗】见下面 `SessionEnded` 分支的 `stopCluster()`。
                // ------------------------------------------------------------------
                // 蓝牙观测：会话期间盯车机蓝牙音乐是否来抢声道（只观测、不动作）。
                runCatching { bluetoothAudioGuard.start() }
                    .onFailure { Log.w(TAG, "bt guard start failed: ${it.message}") }
            }

            is CarSessionEvent.SessionEnded -> {
                appendLog(
                    when (event.reason) {
                        CarSessionEndReason.REMOTE -> "CarPlay 会话已结束（远端断开）"
                        CarSessionEndReason.USER_STOP -> "CarPlay 会话已停止（用户操作）"
                        CarSessionEndReason.POWER_PAUSE -> "CarPlay 会话已停止（熄火自动暂停）"
                        CarSessionEndReason.RECONNECT -> "CarPlay 会话重建中"
                    },
                )
                // 会话已经不在 ⇒ "稳定计时"作废；重连计数按**来源**决定（见末尾分支）。
                mainHandler.removeCallbacks(autoReconnectReset)
                // 2026-10-22：HUD 使能通道随会话结束关闭。**这是我们与 HudBridge 的
                // 关键差异**：它从不发 ActvReq(0)，HUD 可能一直保持最后一次状态。
                stopHudPush()
                // 远端断开不会经过 CarUiActions.stop()；主动拆窗并解绑 ALT(111)，
                // 下次 SessionStarted 会创建全新的 Presentation / Surface。
                stopCluster()
                // 图层模式是 THEME_ONLY / BOTH 时，窗口没了主题就没地方显示 ⇒
                // 记一个"待重建"，会话回到 ACTIVE 后由 `onStateChanged` 补建。
                // CARPLAY_ONLY 不需要窗口，不记。
                val layerMode = settings.clusterLayerMode()
                // 2026-10-22 解耦（P0）：是否补建主题窗只由**主题模式**决定，
                // 不再看导航投屏总开关（PATCH-0122 的旧口径在这里反转）。
                //
                // 理由：主题是独立模块。`THEME_ONLY` + 投屏关着的用户，断线重连后
                // 主题必须回来；旧条件会把它永久留在“没窗”的状态（只有 App 重启
                // 才恢复）。导航仍由 `projectionEnabled` 单独控制，不会因此被拉起。
                if (ClusterLayerAutoAttach.requiresTheme(layerMode)) {
                    pendingThemeClusterMode = layerMode
                }
                runCatching { bluetoothAudioGuard.stop() }
                    .onFailure { Log.w(TAG, "bt guard stop failed: ${it.message}") }
                // 全系统滚动日志跟着**真正的**会话结束收（PATCH-0095）。
                //
                // ⚠️ 这里**只收全系统那一份**，应用日志继续跑 —— 会话结束后
                // 紧接着可能就是后台静默更新的空闲窗口，那正是最需要日志的时候
                // （见 `CarAppServices.startAppLogging()` 的成本表）。
                //
                // ⚠️ `RECONNECT` 不是终止：它只是桥接层拆旧建新的开始。这里若也停掉，
                // 重建后的整段会话都没有系统侧日志（`startSessionLogging()` 只在
                // `startSession()` 里调，重连不会补），恰恰丢掉最需要排查的那一段。
                if (event.reason != CarSessionEndReason.RECONNECT) {
                    runCatching { services.stopSessionLogging() }
                        .onFailure { Log.w(TAG, "停止会话日志记录失败", it) }
                }
                // 只有**远端真实断开**才排自动重连（PATCH-0095 / 2026-10-20 重连链修复）。
                // 用户停止、熄火暂停、故意重建一律不排 —— 否则会出现
                // 「用户点了停止，2 秒后 App 自己连回去」和熄火后的重连回路。
                when (event.reason) {
                    CarSessionEndReason.REMOTE -> scheduleAutoReconnect()

                    CarSessionEndReason.USER_STOP,
                    CarSessionEndReason.POWER_PAUSE,
                    -> {
                        cancelAutoReconnectCheck()
                        resetAutoReconnect()
                    }

                    CarSessionEndReason.RECONNECT -> {
                        // 撤销可能存在的旧闹钟；**不重置计数、不清 reconnect 状态**。
                        // 若这轮重建由 AUTO 发起，status=IN_PROGRESS 必须保留 ——
                        // 重建失败时 `onStateChanged(FAILED)` 靠它续排下一次（断链 A 的修复）。
                        cancelAutoReconnectCheck()
                    }
                }
            }
            is CarSessionEvent.PhaseChanged -> Unit // 状态已由 onStateChanged 覆盖
            is CarSessionEvent.InputReceived -> Unit // 方控事件当前不入日志，避免刷屏
            CarSessionEvent.AudioStreamsChanged -> Unit
        }
    }

    // ----------------------------------------------------------------- internals

    /**
     * 会话**意外**结束 ⇒ 排一次有界自动重连（PATCH-0095，2026-10-11）。
     *
     * ## 为什么需要
     *
     * 1.0.51 诊断包里会话在 `17:06:45` 被 iPhone 关掉之后**再没回来** ——
     * 一行动作都没有，直到用户自己发现"投屏断了"再手动点一次连接。
     * 而断连的成因（蓝牙释放未生效、Wi-Fi 抖动、iPhone 切走）大多**是瞬时的**。
     *
     * ## 判据与节奏全在 `CarSessionAutoReconnectPolicy`
     *
     * 本方法只负责"照着算出来的值排闹钟"，不做任何判断 —— 那个模块是纯函数、
     * 有 JVM 单测；本模块（`car/app`）是 application 模块、**没有 test source set**，
     * 把判断写在这里就没法钉住了。
     */
    private fun scheduleAutoReconnect() {
        // 已经在队列里的闹钟不重复排：上游可能连续发多条 FAILED
        // （failStart 一次 + 晚到的 CarPlayStatus.Failed 一次），
        // 不挡住会把 3 次额度当成 2 次花掉。
        if (autoReconnectPending) return
        // 重建还没结束时不叠加排期：这次重建的终态（ACTIVE/FAILED）会决定下一步。
        // 这是守卫 ② 的排期侧 —— 不能等闹钟到点才在 performAutoReconnect 里跳过。
        if (reconnectInFlight) {
            appendLog("自动重连：已有重连在途，跳过本次排期")
            return
        }
        // 判据全部来自策略模块：来源必须是 REMOTE、次数未用满、用户没要求停止。
        if (!shouldAutoReconnect(autoReconnectAttempts, CarSessionEndReason.REMOTE) ||
            stopRequestedByUser
        ) {
            // 只有“真正的远端断开 + 真的用满 + 用户没停”才算耗尽。
            // 用户主动停止绝不能落 EXHAUSTED（否则首页会把正常停止说成故障）。
            if (shouldExhaustAutoReconnect(
                    attemptsSoFar = autoReconnectAttempts,
                    reason = CarSessionEndReason.REMOTE,
                    endedByUser = stopRequestedByUser,
                )
            ) {
                appendLog(
                    "自动重连已达上限（$AUTO_RECONNECT_MAX_ATTEMPTS 次），停止重试；" +
                        "可手动点「连接」",
                )
                session.updateReconnect(exhaustedReconnectStatus(sessionState.errorMessage))
            }
            return
        }
        val attempt = autoReconnectAttempts + 1
        val delay = autoReconnectDelayMillis(attempt)
        autoReconnectAttempts = attempt
        autoReconnectPending = true
        mainHandler.removeCallbacks(autoReconnectCheck)
        mainHandler.postDelayed(autoReconnectCheck, delay)
        // 正交重试轴：状态 SCHEDULED + 桥接层把 phase 重新断言成 RECONNECTING。
        session.updateReconnect(
            CarReconnectStatus(
                state = CarReconnectState.SCHEDULED,
                attempt = attempt,
                maxAttempts = AUTO_RECONNECT_MAX_ATTEMPTS,
                delayMillis = delay,
                lastError = sessionState.errorMessage,
            ),
        )
        appendLog(
            "连接已断开，${delay / 1_000} 秒后自动重连" +
                "（第 $attempt/$AUTO_RECONNECT_MAX_ATTEMPTS 次）",
        )
    }

    /**
     * 自动重连闹钟到点：真的去重连一次。
     *
     * ⚠️ 与用户点「重连」走**同一道权限闸门**：权限没给时直接重连必然再失败一次，
     * 而闸门会把申请流程拉起来，申请完由宿主回调 [startSession] 继续。
     * 闸门拦下时落 `EXHAUSTED`（phase=FAILED），**不**留下 RECONNECTING，
     * 也不再排下一次 —— 决定权已经交回用户（他要么给权限，要么放弃）。
     */
    private fun performAutoReconnect() {
        if (stopRequestedByUser) return
        val gate = permissionGate
        if (gate != null && !gate()) {
            appendLog("自动重连：权限未就绪，已拉起申请流程（授权后会自动继续）")
            if (!stopRequestedByUser) {
                appendLog("自动重连已停止：权限未就绪，可手动重连")
                session.updateReconnect(exhaustedReconnectStatus("权限未就绪"))
            }
            return
        }
        appendLog("正在自动重连…")
        // 先落 IN_PROGRESS（含这是第几次），再走唯一入口。
        // 重建失败时 onStateChanged(FAILED) 靠这个状态续排下一次。
        session.updateReconnect(
            CarReconnectStatus(
                state = CarReconnectState.IN_PROGRESS,
                attempt = autoReconnectAttempts,
                maxAttempts = AUTO_RECONNECT_MAX_ATTEMPTS,
            ),
        )
        if (!requestReconnect(ReconnectSource.AUTO)) {
            // 上一轮重建还没结束：保留 IN_PROGRESS，等它的终态（ACTIVE/FAILED）决定下一步。
            appendLog("自动重连：上一轮重建尚未结束，本次跳过")
        }
    }

    /** 撤销在途的自动重连闹钟（用户动手了 / 新会话已经起来）。幂等。 */
    private fun cancelAutoReconnectCheck() {
        mainHandler.removeCallbacks(autoReconnectCheck)
        autoReconnectPending = false
    }

    /**
     * 把自动重连状态整个复位：撤闹钟 + 计数归零 + 重试轴回 `NONE`。
     *
     * 调用点（[startSession] / [reconnect] / [stop] / 用户停止事件）都是
     * "用户明确表态"的时刻，语义统一：**从这一刻起重新算**。
     *
     * ⚠️ 耗尽后用户点「重新连接」正是走这里：计数归零、状态回 `NONE`，
     * 再走 [requestReconnect]；否则 `FAILED + EXHAUSTED` 会一直挂在那里。
     */
    private fun resetAutoReconnect() {
        mainHandler.removeCallbacks(autoReconnectCheck)
        mainHandler.removeCallbacks(autoReconnectReset)
        autoReconnectAttempts = 0
        autoReconnectPending = false
        session.updateReconnect(CarReconnectStatus())
    }

    /**
     * 分辨率变化后的重连。
     *
     * 去抖 1200ms：屏幕旋转/分屏调整会连续触发多次尺寸回调，
     * 每次都重建整条协议栈会造成明显的连接抖动。
     *
     * 走唯一入口 [ReconnectSource.CANVAS]：**不**重置自动重连计数（这不是断线），
     * 但仍受 in-flight 守卫约束 —— 重建中再点出一次尺寸变化不会连跳两代 generation。
     */
    private fun scheduleCanvasReconnect() {
        if (canvasReconnectScheduled) return
        canvasReconnectScheduled = true
        mainHandler.postDelayed(
            {
                canvasReconnectScheduled = false
                if (sessionState.phase == CarSessionPhase.ACTIVE) {
                    appendLog("视频区域尺寸变化，重新与 iPhone 协商分辨率")
                    requestReconnect(ReconnectSource.CANVAS)
                }
            },
            CANVAS_RECONNECT_DEBOUNCE_MILLIS,
        )
    }

    private fun appendLog(message: String) {
        if (logBuffer.size >= MAX_LOG_LINES) logBuffer.removeFirst()
        logBuffer.addLast(message)
        if (settings.debugLogs) refreshModel()
    }

    /**
     * 会话已建立 / 设置刚打开 ⇒ 激活 ECarX HUD 使能通道。
     *
     * ## 失败必须静默降级
     *
     * 通道不可用（没有该服务 / ROM 没有 VfhudManager / 反射被拦）时**只记
     * `xcertplay-hud` 日志**：不弹窗、不报错、不影响 CarPlay、不触发重连。
     * 设置默认关，用户主动打开才走到这里。
     *
     * ## 线程
     *
     * 反射链 + Binder 全部在 [hudPushExecutor] 上；主线程只翻转
     * [hudPushActive] 这一个标志。
     */
    private fun startHudPush() {
        if (hudPushActive) return
        hudPushActive = true
        hudPushExecutor.execute {
            // 会话可能在排队期间结束：stopHudPush() 已把标志翻回 false。
            if (!hudPushActive) return@execute

            val availability = hudPushChannel.probe()
            if (availability != HudChannelAvailability.Available) {
                hudPushActive = false
                Log.i(
                    EcarxVfhudChannel.TAG,
                    "HUD 使能：通道不可用，跳过 ActvReq(1)（$availability）",
                )
                return@execute
            }
            if (!hudPushActive) {
                Log.i(
                    EcarxVfhudChannel.TAG,
                    "HUD 使能：探针返回时会话已结束，放弃 ActvReq(1)",
                )
                return@execute
            }

            when (val result = hudPushChannel.activate()) {
                is HudCallResult.Returned -> Log.i(
                    EcarxVfhudChannel.TAG,
                    "HUD 使能：ActvReq(1) 返回 ${result.returnText}" +
                        "（apiSucceeded=${result.apiSucceeded}）",
                )

                is HudCallResult.Failed -> {
                    hudPushActive = false
                    Log.w(EcarxVfhudChannel.TAG, "HUD 使能失败：${result.reason}")
                }
            }
        }
    }

    /**
     * 会话结束 / 设置关闭 ⇒ 发 `CB_VF_HUD_ActvReq(0)`。
     *
     * ⚠️ 这一步 HudBridge **没有做**：它停止时只推导航结束，从不关闭 HUD 使能。
     * 本工程必须在会话结束时补上，否则 HUD 可能一直保持最后一次状态。
     */
    private fun stopHudPush() {
        if (!hudPushActive) return
        hudPushActive = false
        hudPushExecutor.execute {
            when (val result = hudPushChannel.deactivate()) {
                is HudCallResult.Returned -> Log.i(
                    EcarxVfhudChannel.TAG,
                    "HUD 关闭：ActvReq(0) 返回 ${result.returnText}" +
                        "（apiSucceeded=${result.apiSucceeded}）",
                )

                is HudCallResult.Failed ->
                    Log.w(EcarxVfhudChannel.TAG, "HUD 关闭失败：${result.reason}")
            }
        }
    }

    /**
     * 仪表投屏日志：**同时**进界面浮层与诊断包。
     *
     * [appendLog] 只写内存里的浮层 buffer，关闭界面日志时不落盘。把功能未启用、
     * 未选副屏、建窗失败等原因写进诊断包，现场可以区分配置门槛与平台窗口问题。
     *
     * ⚠️ 只在投屏请求结果变化时调用，不在高频循环里调用。
     */
    private fun clusterLog(message: String) {
        platform.diagnostics.log(TAG, "仪表投屏：$message")
        appendLog(message)
    }

    /**
     * 重算热点方案提示（异步）。
     *
     * 有线链路下清空：此时热点与用户无关，显示一句"当前不使用热点"反而更像噪声
     * （设置页自己会说明这一点）。
     *
     * 无线链路下把计算丢到 [briefingExecutor]，算完 post 回主线程再落
     * [hotspotBriefing] 并刷新一次 model。调用方**不阻塞**：本方法在
     * [CarPlayApplication.onCreate] 的构造路径上，阻塞就等于推迟首帧。
     *
     * 旧值**故意留着不提前清空**：`hotspotBriefing` 走的是
     * `nativeHotspotProbe.cachedInfo(...)`，**从不触发新的 ADB 探测**，
     * 所以重算窗口只有几十毫秒（实车实测 21 ms）——低于可感知阈值，
     * 而提前清空会让设置页那一行"本次将使用"闪一下（先消失再出现），
     * 比短暂显示上一版结论更显眼。
     *
     * **进队前合并**（2026-10-14，见方法体里那段注释）：队里已有一个还没开跑的任务时，
     * 新请求只更新待算槽位（[briefingPendingSettings] / [briefingPendingGeneration]），
     * 不再往单线程队列里再排一个。调用点有 5 处（启动、会话启动、[applySettings]、
     * [resetSettings]、[refreshHotspotBriefing]），全部走这一处合并，不必各自去重。
     *
     * **结果缓存**（PATCH-0133）：合并只能省掉"排队中"的重复请求；算完后的下一次
     * 界面刷新仍会重算。这里再加一层 30 秒 TTL 缓存（[hotspotBriefingCache]），
     * 命中时只落快照 + `refreshModel()`，不再碰探测。
     *
     * @param force true = 强制绕过缓存（用户手动刷新 / 热点模式切换）。
     *        它会先清掉旧结果，再按正常路径重算。
     */
    private fun refreshHotspotHint(force: Boolean = false) {
        // 世代号无论如何都 +1：有线那一支也要把在途的无线结果作废，
        // 否则"切到有线 → 在途结果回来"会把 hotspotBriefing 又填回去。
        //
        // 这份"最新一次请求的世代号"同时写进待算槽位：排队中的任务开跑时读它，
        // 于是它交出来的结果不会被这一次请求判成过期。
        briefingPendingGeneration = ++briefingGeneration
        briefingPendingForce = force

        // ---- 进队前合并（防抖，2026-10-14） ----
        //
        // 实车日志实证：热点模式初始化那一下会连着撞 6 次本方法，而 [briefingExecutor]
        // 是**单线程队列**、每次实跑 2.35 秒（`WifiManager` binder + 网卡枚举），
        // 6 次 ≈ 14 秒纯浪费。
        //
        // 做法：每次请求都把"现在要算什么"写进一份**最新覆盖旧值**的待算槽位；
        // 队里已经有一个**还没开跑**的任务时就不再 `execute` —— 它开跑时读这份最新值，
        // 排队期间撞进来的请求因此被合并成一次。队里没有待跑任务（上一个已跑完或正在跑）
        // 时按原逻辑正常入队。
        briefingPendingSettings = if (settings.isWireless) settings else null

        if (!settings.isWireless) {
            // 短路照旧：有线链路下热点与用户无关，清掉即可。
            // 这里不再排队（槽位已经是 null）：若队里正有个待跑任务，它开跑时会看到
            // null ⇒ 直接结束 —— 所以"切到有线"不会多排一个什么都不干的任务，
            // 重复切到有线也不会重复排。
            hotspotBriefing = null
            hotspotBriefingCache.clear()
            return
        }

        if (force) {
            // 强制失效：即使指纹相同、TTL 未到，也不允许返回旧快照。
            hotspotBriefingCache.clear()
        } else {
            val hit = hotspotBriefingCache.get(settings)
            if (hit != null) {
                // 缓存已经满足了这次请求 ⇒ 取消排队中（还没开跑）的计算。
                // 正在跑的那一份会因世代号对不上而在 `mainHandler.post` 里被丢弃。
                briefingPendingSettings = null
                hotspotBriefing = hit.briefing
                autoFillCarHotspot(hit.briefing)
                Log.i(TAG, "热点简报：命中缓存（剩余 ${hit.remainingSeconds}s）")
                // 简报是 model 的一部分；命中缓存也要刷一次，否则界面看不到。
                refreshModel()
                return
            }
        }

        if (briefingQueued) {
            // 队里已有待跑任务（还没开跑）⇒ 这次请求已被上面那份最新槽位吸收。
            briefingCoalescedCount += 1
            return
        }

        briefingQueued = true
        briefingExecutor.execute {
            // 【这两步的顺序不能反】先撤"排队中"标记，再取待算槽位：
            // 反过来的话，"取槽位"与"撤标记"之间到来的请求会看到标记还在而被合并掉，
            // 可它要算的值已经被这份任务取走了 ⇒ 那一次请求就丢了。
            briefingQueued = false
            val merged = briefingCoalescedCount + 1
            briefingCoalescedCount = 0
            // 快照走待算槽位而不是直接读 `settings`：任务在别的线程上跑，
            // 而 settings 是可变字段（`applySettings` 会整个换掉），不能让它读到一半
            // 被换的新值。槽位在排队期间被后来的请求改写过的话，这里取到的就是**最新那份**
            // ——这正是"合并后最后一次的值生效"的来源。
            val snapshot = briefingPendingSettings
            val generation = briefingPendingGeneration
            val force = briefingPendingForce
            if (snapshot == null) {
                // 排队等的这段时间里切到了有线（或最后一次请求本身就是有线）⇒ 没有东西要算。
                // 清空已经在请求那一侧做过，这里直接结束，不算也不落日志。
                return@execute
            }
            // PATCH-0133：入队时缓存可能还是空的，但排在前面的任务可能刚算完并回填。
            // 开跑前再查一次，避免同一批刷新把同一份结论算两遍。
            if (!force) {
                val hit = hotspotBriefingCache.get(snapshot)
                if (hit != null) {
                    Log.i(TAG, "热点简报：命中缓存（剩余 ${hit.remainingSeconds}s）")
                    mainHandler.post {
                        if (generation != briefingGeneration) return@post
                        hotspotBriefing = hit.briefing
                        autoFillCarHotspot(hit.briefing)
                        refreshModel()
                    }
                    return@execute
                }
            }
            // 一行日志证明合并生效：N=1 表示这一次没合并（队里本来没有待跑任务）。
            Log.i(TAG, "热点简报：合并 $merged 次重复请求，本次开始计算")
            val startedAt = SystemClock.uptimeMillis()
            // 探测失败（权限缺失、厂商隐藏 API）不该让整个设置页炸掉——
            // 拿不到就说拿不到，会话启动时还会再探一次。
            val briefing = runCatching { services.hotspotBriefing(snapshot) }.getOrNull()
            val elapsed = SystemClock.uptimeMillis() - startedAt
            // 一行耗时日志：下次拿到诊断包就能直接看出"这段本来会卡住首帧多久"，
            // 以及异步化之后它是否真的不再影响主线程（线程名见 logcat 的 pid/tid）。
            Log.i(TAG, "热点简报在后台算完：耗时 ${elapsed}ms，target=${briefing?.target ?: "（无）"}")
            mainHandler.post {
                // 已经有更新的请求发出 ⇒ 这份结果过期了，丢掉。
                if (generation != briefingGeneration) return@post
                hotspotBriefing = briefing
                autoFillCarHotspot(briefing)
                // 只缓存**被采纳**的结果：过期任务算出来的旧快照不能回填缓存。
                if (briefing != null) {
                    hotspotBriefingCache.put(snapshot, briefing)
                }
                // 简报是 model 的一部分，算完必须刷一次，否则界面永远看不到。
                refreshModel()
            }
        }
    }

    /**
     * 设置恢复 / 图层模式变更可能来自后台线程，而 [refreshModel] 只能主线程跑。
     *
     * 2026-10-23 加：它只负责把“实时图层状态”送达界面，不改变任何投影行为。
     */
    private fun refreshModelFromAnyThread() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            refreshModel()
        } else {
            mainHandler.post { refreshModel() }
        }
    }
    private var lastAutoHotspotCredentials: Pair<String, String?>? = null
    private fun autoFillCarHotspot(briefing: com.dbtool.carplay.bridge.CarHotspotBriefing?) {
        val credentials = briefing?.nativeCredentials ?: return
        if (credentials.ssid.isBlank()) return
        val key = credentials.ssid to credentials.passphrase
        if (lastAutoHotspotCredentials == key) return
        lastAutoHotspotCredentials = key
        val next = settings.withReadCarHotspot(credentials.ssid, credentials.passphrase)
        if (next == settings) return
        settings = next
        services.settingsStore.save(next)
        session.updateSettings(next)
        // Never put the password or a fingerprint of it in a log.
        appendLog("已自动读取车机热点并填入设置；密码可读=${credentials.passphrase != null}，连接中变更需重连")
    }

    private fun refreshModel() {
        refreshDockApps()
        refreshNowPlayingSubscription()
        // ⚠️ 必须在构造 `_model` **之前**：它可能推进更新状态（发起静默安装），
        // 而下面那行 `update = updateHost.state()` 要读到推进后的值。
        maybeNotifyIdleWindow()
        val (dockLeft, dockRight, dockMediaSample) = dockSampleData()
        val projectionStateSnapshot = runCatching { session.projectionState }
            .getOrDefault(com.dbtool.carplay.api.session.CarProjectionState())
        _model.value = CarUiModel(
            activeAudioStreams = session.activeAudioStreams(),
            immersiveNavigationActive = sessionState.immersiveNavigationActive,
            dialLayout = loadDialLayout(),
            drawRegion = loadDrawRegion(),
            videoTransform = loadVideoTransform(),
            session = CarUiStateMapper.project(sessionState, settings),
            settings = settings,
            // 「有改动在等重连」（PATCH-0103）：当前设置与**已灌给会话的基线**相比，
            // 有没有落在"必须重连才生效"那一侧。设置页据此显示提示 + 「重连生效」按钮。
            settingsPendingReconnect = CarSettingsReconnectPolicy
                .requiresReconnect(reconnectBaseline, settings),
            platformInfo = platform.system.info,
            pairedControllerCount = services.pairedControllerCount(),
            appVersionName = appVersionName,
            appVersionCode = appVersionCode,
            coreVersionName = services.coreVersion,
            logs = logBuffer.toList(),
            notice = notice,
            mfiIdentity = services.mfiIdentity.status(),
            airPlayIcon = services.airPlayIcon.status(),
            hotspotTarget = hotspotBriefing?.target.orEmpty(),
            hotspotHint = hotspotBriefing?.detail.orEmpty(),
            hotspotPhoneHint = hotspotBriefing?.phoneHint.orEmpty(),
            hotspotWarning = hotspotBriefing?.warning.orEmpty(),
            // 简报还没回来（或有线链路）时按 UNKNOWN 显示 —— **不是** OFF：
            // 连接时也是这么放行的，界面不能比逻辑更严格。
            hotspotEnabledState = hotspotBriefing?.enabledState
                ?: CarHotspotEnabledState.UNKNOWN,
            // 本机可填地址候选（PATCH-0102）：与上面同源，都来自那一份简报 ——
            // 界面列出来的候选，与连接时那道 precheck 校验的是**同一次网卡枚举**，
            // 不会出现"页面说能填、连接说不在网卡上"的自相矛盾。
            hotspotAddressCandidates = hotspotBriefing?.endpointCandidates.orEmpty(),
            // 一键填入的车机真实凭据（2026-10-13）：同样来自那一份简报。
            nativeHotspotCredentials = hotspotBriefing?.nativeCredentials,
            // 隧道状态并进开发者状态回显（2026-10-16）：null（未启用/已停）按
            // `CarDebugTunnelStatus()` 的默认值显示成"未开启"，界面不必再判一次空。
            developer = developerHost.state().copy(
                debugTunnel = debugTunnel?.status?.toUiStatus() ?: CarDebugTunnelStatus(),
            ),
            developerTapCount = versionTapCount,
            update = updateHost.state(),
            adbHotspot = services.adbHotspot.state(),
            adbInitialization = adbInitializationState,
            nativeHotspot = services.nativeHotspot.state(),
            navigationRequest = navigationRequest,
            hotspotGuideVisible = hotspotGuideVisible,
            // 仪表自绘（PATCH-0017）。只读回显 + 拖拽回填，不带行为。
            projectionState = projectionStateSnapshot,
            // 2026-10-23（B2）：两个图层各自的实时状态。只读快照，
            // 不参与建窗/拆窗/断流决策；界面用它替代原来的三态控件与黑屏误报。
            clusterLayers = clusterLayerStatusSnapshot(projectionStateSnapshot),
            // HUD 导航推送的实时状态（PATCH-0114，2026-10-13）。只读回显：
            // 桥绑上没绑上、iPhone 发了几帧、去重丢了几帧、车机受理了几帧。
            // 与上面那行不同，这里**不包 runCatching**：`CarSessionHost.hudStatus()` 有默认实现、
            // `CarPlaySessionBridge` 那一侧也已兜过异常，再兜一层只会把"实现违约"藏起来。
            hud = session.hudStatus(),
            navigation = navigationSnapshot,
            immersiveNowPlaying = dockNowPlaying,
            phonePowerLevel = phonePowerLevel?.level,
            phoneChargingState = phonePowerLevel?.chargingState,
            phoneSignalStrength = phonePowerLevel?.communications?.signalStrength,
            phoneMaxCurrentDrawnMa = phonePowerLevel?.maxCurrentDrawnMa,
            // 2026-10-08 修正：仪表副屏**用户选屏**——候选清单 + 当前选中 id。
            //
            // 候选来源用 `ClusterDisplayFinder.selectable()`（**非主屏**的有效显示）。
            // 之前误用了 `all()`（含主屏）：主屏混进清单后，用户一旦选中它，副屏窗口
            // 就开在**主屏**上（1920×720 白块），这正是"选屏无效 / 投屏异常"的直接根因。
            // 反编译原版 `GeelyDisplaySelector.select` 的 `!isDefault` 是硬条件，
            // 主屏从不可作仪表副屏，这里对齐。
            clusterCandidates = clusterCandidatesSnapshot(),
            clusterDisplayId = settings.clusterDisplayId,
            // 仪表全屏模式守护的只读快照（阶段 2）：复用本函数既有的刷新时机，
            // 不新增轮询 —— 状态机每次推进都会走一次 `refreshModel()`。
            clusterFullMap = clusterFullMapSnapshot(settings),
            // 安全区「画框」的坐标空间（PATCH-0080）。`canvas` 是 UI 上报的主屏画布，
            // 与 `CarPlayConfigBuilder` 里 `safeArea.scaledTo(...)` 用的是同一个值——
            // 两处不一致的话，用户在设置页拖出来的框会与真实坐标整体偏。
            safeAreaCanvasWidthPx = canvas?.widthPixels ?: 0,
            safeAreaCanvasHeightPx = canvas?.heightPixels ?: 0,
            dockApps = dockAppsCache,
            dockLeftTemperature = dockLeft,
            dockRightTemperature = dockRight,
            dockMedia = dockMediaOf(dockNowPlaying) ?: dockMediaSample,
            // 底栏关掉时编辑态必须落回 false：编辑一个看不见的栏没有意义。
            dockEditMode = dockEditMode && settings.dockBar,
            dockAppChoices = dockAppChoices,
            dockAppPickerVisible = dockAppPickerVisible && settings.dockBar,
        )
    }

    /**
     * 两个图层的实时状态快照（2026-10-23，B2）。
     *
     * - 主题：`mode.showsTheme` 是意图，`currentClusterTheme() != null` 且窗口已挂才算真的可见；
     * - 导航：模式允许 + 用户打开投屏 = 意图，再叠加运行时 [navigationLayerVisible]
     *   （导航结束/手动收起会把它置 false，但窗口仍可能为主题保留）；
     * - 窗口：优先问平台 presenter，通用平台回退到会话层快照。
     *
     * 纯读，不改变任何状态。`currentClusterTheme()` 自带 2 秒缓存，不会被高频
     * `refreshModel()` 拖成每帧读盘。
     */
    private fun clusterLayerStatusSnapshot(
        projectionStateSnapshot: com.dbtool.carplay.api.session.CarProjectionState,
    ): CarClusterLayerStatus {
        val mode = settings.clusterLayerMode()
        val navigationRequested = mode.showsNavigation && settings.projectionEnabled
        val themeAvailable = false
        val windowAttached = clusterPresenter?.isWindowAttached() == true ||
            projectionStateSnapshot.attached
        return CarClusterLayerStatus(
            themeRequested = mode.showsTheme,
            themeAvailable = themeAvailable,
            // 主题只存在于仪表窗口里：窗口没挂时不能对用户说“显示中”。
            themeVisible = mode.showsTheme && themeAvailable && windowAttached,
            navigationRequested = navigationRequested,
            navigationVisible = navigationRequested && navigationLayerVisible,
            windowAttached = windowAttached,
            firstFrameShown = projectionStateSnapshot.firstFrame,
        )
    }

    /**
     * 仪表副屏候选快照（2026-10-10 重写）。
     *
     * ## 为什么优先用平台自己的清单
     *
     * `CarClusterPresenter.listCandidates()` 与「能不能选中、能不能开窗」是**同一套**
     * 判据（吉利侧 = `GeelyDisplaySelector.candidates`：非主屏，不限分辨率）。
     * 而通用枚举 `ClusterDisplayFinder.selectable()` 多了两条硬条件
     * （`isValid` + 物理宽高 > 0）—— 车上只要有一块屏报 0 尺寸，它就被这条**额外**
     * 条件剔掉：用户看到空清单（"设置里没有可选的屏"），平台其实完全能挂窗。
     * 两侧判据不一致，正是这类故障的温床，所以优先平台清单、通用枚举兜底。
     *
     * ## 为什么逐块包 runCatching
     *
     * 原来整段一个 `runCatching`：只要**任何一块**屏在读 `name` / `mode` 时抛异常，
     * 整张清单直接变空表 —— 症状与"没插屏"一模一样，而且日志里什么都没有。
     * 现在一块屏出问题只丢它自己，其余照常列出。
     */
    private fun clusterCandidatesSnapshot(): List<CarClusterCandidateSummary> {
        // 通用枚举先取一份，作用有二：给平台候选补齐"能分辨"的标签；平台无清单时兜底。
        val displays = runCatching {
            com.dbtool.carplay.api.projection.ClusterDisplayFinder.selectable(app)
        }.getOrDefault(emptyList())
        val byId = displays.associateBy { it.displayId }

        val fromPlatform = runCatching { clusterPresenter?.listCandidates().orEmpty() }
            .getOrDefault(emptyList())
            .mapNotNull { candidate ->
                runCatching {
                    val display = byId[candidate.displayId]
                    CarClusterCandidateSummary(
                        displayId = candidate.displayId,
                        // 平台标签（如吉利 `labelOf`）本就带 displayId 与分辨率；
                        // 能拿到 `Display` 时优再用本机的 `labelFor`，保证两条来源
                        // 出同一形状的标签（列表里不会一半长一半短）。
                        label = display?.let { labelFor(it) } ?: candidate.label,
                        widthPixels = candidate.widthPixels,
                        heightPixels = candidate.heightPixels,
                    )
                }.getOrNull()
            }
        if (fromPlatform.isNotEmpty()) return fromPlatform

        return displays.mapNotNull { display ->
            runCatching {
                val mode = display.mode
                CarClusterCandidateSummary(
                    displayId = display.displayId,
                    label = labelFor(display),
                    widthPixels = mode?.physicalWidth ?: display.width,
                    heightPixels = mode?.physicalHeight ?: display.height,
                )
            }.getOrNull()
        }
    }

    /**
     * 仪表「原厂全屏地图模式」的只读快照（阶段 2，2026-10-18）。
     *
     * 平台没实现 [CarClusterFullMapControl] 时返回默认值（未启用 / 不支持 / 空闲），
     * 设置页据此显示"守护未启用"，而不是一片空白。
     *
     * ⚠️ `clusterPresenter` 就是 `platform.cluster`（见它的初始化处）。这里用前者，
     * 与其它仪表控制（选屏 / 翻转 / 全屏守护）走同一个对象，避免两处取到不同的实例。
     */
    private fun clusterFullMapSnapshot(settings: CarSessionSettings): CarClusterFullMapUi =
        (clusterPresenter as? CarClusterFullMapControl)?.let { control ->
            val state = control.fullMapState()
            CarClusterFullMapUi(
                guardEnabled = settings.clusterModeGuardEnabled,
                supported = state.supported,
                phase = state.phase.name,
                lastError = state.lastError,
                sinceElapsedMs = state.sinceElapsedMs,
                events = state.events.map { event ->
                    CarClusterFullMapEventUi(
                        kind = event.kind,
                        atElapsedMs = event.atElapsedMs,
                        detail = event.detail,
                    )
                },
            )
        } ?: CarClusterFullMapUi()

    /**
     * 候选屏的可读标签（PATCH-0062）。
     *
     * 现场问题：车上 4 块屏里 3 块 `Display.getName()` 都叫「HDMI 屏幕」，
     * 用户面对「副屏 · HDMI 屏幕」×3 无从下手，只能盲选。
     *
     * 这里补三类**能区分**的信息（都只是提示，不替用户自动选）：
     * - 分辨率（1024×274 明显是仪表长条，1920×1080 更像外接屏）；
     * - 触摸类型（`EXTERNAL` 那块就是仪表屏）；
     * - 尺寸形状判据（细长/矮屏 ⇒ 更像仪表）。
     */
    private fun labelFor(d: android.view.Display): String {
        val mode = d.mode
        val w = mode?.physicalWidth ?: d.width
        val h = mode?.physicalHeight ?: d.height
        val refresh = mode?.refreshRate ?: 0f
        val shape = when {
            w <= 0 || h <= 0 -> ""
            w.toDouble() / h.toDouble() >= 3.0 -> "细长·像仪表"
            h <= 480 -> "矮屏·像仪表"
            else -> "宽屏"
        }
        // 触摸类型：EXTERNAL(3) 是仪表屏，NONE(0) 多见于外接/虚拟屏。
        // 反射读 getType()（与 GeelyDisplaySelector 同一理由：compileSdk 解析不到）。
        val touch = runCatching {
            val m = android.view.Display::class.java.getMethod("getType")
            when ((m.invoke(d) as? Int) ?: 0) {
                3 -> "触摸:外部"
                else -> ""
            }
        }.getOrDefault("")
        return buildString {
            // displayId 放在最前面：车上多块屏名字常常一模一样（都叫「HDMI 屏幕」），
            // 没有 id 用户根本对不上"日志里说的那块"和"列表里的那块"。
            append("副屏 · displayId=").append(d.displayId)
            append(" · ").append(d.name)
            append(" · ").append(w).append("×").append(h)
            if (refresh >= 1f) append("@").append(refresh.toInt()).append("Hz")
            if (shape.isNotEmpty()) append(" · ").append(shape)
            if (touch.isNotEmpty()) append(" · ").append(touch)
        }
    }

    @Suppress("DEPRECATION")
    private fun readVersionName(): String = runCatching {
        app.packageManager.getPackageInfo(app.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    /**
     * 调试隧道握手用的 VIN（2026-10-17，车队化）。
     *
     * 复用 `CarVinReader` 的既有结果：它读到合法 VIN 后会写进
     * `dbplay_vin_cache`，这里只读这份缓存，不新造一套读取链。
     * 缓存未命中（首次启动且档案上报尚未读到 VIN）时返回 null，
     * 隧道按旧协议走 default 槽；下一次重连/重启即可带上 VIN。
     */
    private fun readCachedVinForTunnel(): String? = runCatching {
        app.getSharedPreferences("dbplay_vin_cache", Context.MODE_PRIVATE)
            .getString("vin", null)
    }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    @Suppress("DEPRECATION")
    private fun readVersionCode(): Long = runCatching {
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }
    }.getOrDefault(0L)

    private companion object {
        /** 吉利 OneOS 仪表面板的固定 displayId。 */
        const val FIXED_CLUSTER_DISPLAY_ID = 2

        /** No iAP2 heartbeat exists; clear guidance after the decoder's staleness window. */
        const val NAVIGATION_SNAPSHOT_TTL_MILLIS = 120_000L

        /** logcat 标签。与 `BootReceiver` 的 `carplay-boot` 同一命名风格。 */
        const val TAG = "carplay-controller"

        /**
         * 热点简报结果缓存的 TTL（PATCH-0133）。
         *
         * 依据：实车诊断包实测单次简报 2.0–4.0 秒，一次连接会被界面刷新调 6–8 次；
         * 30 秒足够覆盖一次连接窗口内的连续刷新，又不至于让用户切完设置再切回来时
         * 长时间看到旧结论。用户手动刷新与模式切换走 `force`，不受此值限制。
         */
        const val BRIEFING_CACHE_TTL_MS = 30_000L

        const val MAX_LOG_LINES = 200
        const val CANVAS_RECONNECT_DEBOUNCE_MILLIS = 1_200L

        // ⚠️ 判定"真实分辨率切换"的阈值不在这里，在
        // `CarVideoCanvas.CANVAS_ASPECT_TOLERANCE`（0.15）。
        // 放那边是因为 `car/core-bridge` 的单测覆盖不到本模块
        // （`car/app` 是 application 模块，没配 test source set）。

        /**
         * 连点之间的最大间隔。超过就重新计数。
         *
         * 取 1500ms：比双击间隔宽得多（手指慢一点也算连点），
         * 又短到"分几次随手点"不会意外凑满 7 次。
         */
        const val TAP_RESET_MILLIS = 1_500L
    }
}
