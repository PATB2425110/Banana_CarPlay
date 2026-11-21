package com.dbtool.carplay.geely

import com.dbtool.carplay.api.display.ClusterDialLayout
import com.dbtool.carplay.api.display.ClusterDrawRegion
import com.dbtool.carplay.api.display.ClusterVideoTransform
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.dbtool.carplay.api.display.CarClusterCandidate
import com.dbtool.carplay.api.display.CarClusterDisplaySelection
import com.dbtool.carplay.api.display.CarClusterFlipControl
import com.dbtool.carplay.api.display.CarClusterFullMapControl
import com.dbtool.carplay.api.display.CarClusterFullMapPhase
import com.dbtool.carplay.api.display.CarClusterFullMapState
import com.dbtool.carplay.api.display.CarClusterPresenter
import com.dbtool.carplay.api.display.CarClusterSpec
import com.dbtool.carplay.api.display.ClusterLayerMode
import com.dbtool.carplay.api.diagnostics.CarDiagnosticsAdapter

/**
 * 吉利仪表副屏呈现实现（HDMI2 / 1920x1080）。
 *
 * ## 移植自哪
 *
 * 反编译 `GeelyCarPlay-0.3.2` 的 `io.dushu.car.geely.GeelyClusterSurfaceOwner`
 * 的 **display attach 流程**（`onDisplayChanged`），去掉原版那些与
 * "自绘导航叠层 / 工厂模式 / 手动重开" 相关的部分：
 *
 * ```
 * getDisplays() → select(唯一命中) → DisplayManager.getDisplay(id)
 *   → new GeelyClusterPresentation(...) → show()
 * ```
 *
 * ⚠️ **注意这里不是 `createVirtualDisplay`**：HDMI2 是车机**本来就存在**的一块
 * 非主屏，DBPlay 只是往它上面挂一个 `Presentation`。原版也是这么做的
 * （见 `GeelyClusterSurfaceOwner.onDisplayChanged` 第 220~231 行）。
 *
 * ## 与原版的差异（刻意收敛）
 *
 * - 原版默认 display id 来自 `GeelyClusterSafeAreaStore.displayId(context)`（持久化，
 *   缺省 `0x2`）。DBPlay 侧对应的是 `CarSessionSettings.clusterDisplayId`，由
 *   `CarAppController` 在启动/设置变更时通过 [setRequestedDisplayId] 灌入；
 *   选屏判据与反编译原版一致（见 [GeelyDisplaySelector.select]）。
 * - 原版的重连/重试定时器（`invalidateManualOpen` / `modePoll`）不复刻：
 *   那些服务的是它的"工厂全屏地图模式"功能，与"把视频画面放到仪表"无关。
 *
 * ## 窗口所有权（2026-10-08 定案）
 *
 * **本类是该平台上仪表副屏窗口的唯一 owner**（对齐反编译原版
 * `GeelyClusterSurfaceOwner` 的单窗口结构）。`:car:core-bridge` 的
 * `ClusterProjectionController` 在平台声明了本实现时**不再建窗**，
 * 避免两套各挂一块 `Presentation` 抢同一条 ALT(111) 轨。
 *
 * ## 窗口生命周期（PATCH-0052，2026-10-09 定案）
 *
 * **[start] 一律重建窗口，绝不跨会话复用。** 原版的"复用"是"同一块屏 ⇒
 * 直接 return，什么都不做"；我们曾把它实现成"同屏就复用旧窗口并把旧 Surface
 * 再喂一次"，结果对端断开重连时把上一会话作废的 Surface 交给了 ALT(111) ⇒ 白屏
 * （第三轮真车实证，日志 `1791519459715_...`）。详见 [start] 内的注释。
 */
class GeelyClusterPresenter(
    private val context: Context,
    private val diagnostics: CarDiagnosticsAdapter,
) : CarClusterPresenter,
    CarClusterDisplaySelection,
    CarClusterFlipControl,
    CarClusterFullMapControl {

    private val appContext: Context = context.applicationContext

    private var presentation: GeelyClusterPresentation? = null
    private var currentDisplayId: Int? = null
    private var densityDpi: Int = 0
    private var presentationGeneration: Long = 0L

    /**
     * 车机私有 DimMenu 抢屏控制器。
     *
     * 2026-10-08 补：只挂 `Presentation` 不够 —— 车机自己的 DimMenu 占着仪表屏，
     * 我们的 Surface 几秒后被回收（真车日志 `BufferQueue has been abandoned`）。
     * 必须在挂窗后**反射调车机私有 API 把仪表要过来**，见 [GeelyDimModeController]。
     *
     * 🔴 2026-10-09：抢屏参数此前写反了（`enter` 用了 `1`），实际是"关掉全屏导航"
     * ⇒ 主动让屏 ⇒ 白屏。已在 [GeelyDimModeController] 修正为 `enter=3` / `exit=1`。
     */
    private val dim = GeelyDimModeController(appContext) { diagnostics.log(TAG, it) }

    private val projectionIntent = com.dbtool.carplay.api.display.ClusterProjectionIntent

    /**
     * 仪表被车机收回的监听（**第一道检测**：车机日志）。
     *
     * 2026-10-08 补：实测「一启动地图那里就黑屏」—— 车机切模式时把仪表拿回，
     * 我方无人得知。监听车机日志里的 `switchNaviMode naviMode:1` 作诊断提示，
     * 见 [GeelyDimModeObserver]。
     */
    private val modeObserver = GeelyDimModeObserver(
        diagnostics = { diagnostics.log(TAG, it) },
        onModeOff = { onClusterTakenBack("logcat") },
    )

    /**
     * **第二道检测**：1500ms 反射轮询车机当前仪表模式（对齐原版 `modePoll`）。
     *
     * 2026-10-09 补：原版 `GeelyClusterSurfaceOwner` 除了 logcat 观察器，还有一个
     * 1500ms 的 `modePoll` 直接反射读 `getNaviMode()` —— 若发现模式被拨回
     * OFF(1) 时确认稳定关闭。这条**不依赖车机肯不肯打日志**
     * （日志可能被限流 / 改版），是更可靠的一道保险。此前 DBPlay 完全没有它。
     */
    private val modePoll = object : Runnable {
        override fun run() {
            if (stopped || presentation == null) return
            runCatching { pollNaviModeOnce() }
            mainHandler.postDelayed(this, MODE_POLL_INTERVAL_MS)
        }
    }

    /**
     * 挂载后延迟一次诊断快照（PATCH-0054）。
     *
     * 用来判定「窗口在、Surface 在，但 TextureView 的帧回调到底有没有来」——
     * 关键就是快照里的 `updated回调累计`。**它为 0 就说明回调始终没触发**，
     * 不必再从日志里找 `帧更新` 那几行有没有出现过。
     */
    private val diagnosticSnapshot = object : Runnable {
        override fun run() {
            val p = presentation ?: return
            diagnostics.log(TAG, "Cluster: 挂载后快照 ${p.diagnosticSnapshot()}")
        }
    }

    /** 主线程 Handler（[modePoll] 与窗口操作都跑在主线程）。 */
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * 还原账本（阶段 1：灰度默认关，开关打开才走守护链路）。
     *
     * 账本独立于设置落盘，进程被杀后由 `CarPlayApplication.onCreate` 补还原。
     */
    private val modeJournal = GeelyClusterRestoreJournal(appContext)

    /** 全屏地图模式状态机。 */
    private val modeSession = GeelyClusterModeSession(
        dim = dim,
        journal = modeJournal,
        handler = mainHandler,
        diagnostics = { diagnostics.log(TAG, it) },
        canAcquire = { projectionIntent.allowsAutomaticOpen() },
    ).also { session ->
        session.onFirstFrameTimeout = { onGuardFirstFrameTimeout() }
    }

    /** 灰度开关；false = 保持 PATCH-0046 起的直接调用路径。 */
    @Volatile
    private var modeGuardEnabled = false

    /**
     * 抢屏失败后的抑制截止时刻（对齐原版 `suppressedForRoute` 思路）。
     *
     * 2026-10-09 补：参数改对后，若某个场景车机**顽固拒绝**（例如原厂正在全屏导航
     * 硬占屏），不设抑制就会每 1500ms 重抢一次、打成风暴。这里在"抢屏返回 false
     * 或车机立刻又拨回 OFF"时，短时不再重试。
     */
    @Volatile
    private var suppressReopenUntil = 0L

    /**
     * 窗口是否已出画（首帧）。
     *
     * 2026-10-08：由 [GeelyClusterPresentation.onFirstFrame] 置位。
     * 此前 `onFirstFrame` 是个**从未被赋值**的 var —— 于是上层无从知道首帧，
     * 只能由 core-bridge 那条重复的链自己记。定案"平台层独占窗口"后，
     * 首帧信号必须从这里出去（见 [isRendering]）。
     */
    @Volatile
    private var rendering = false

    @Volatile
    private var stopped = true

    /** 用户选中的仪表 displayId（-1 = 未选）。由上层在设置变更时灌进来。 */
    @Volatile
    private var requestedDisplayId: Int = -1

    /** 副屏画面是否 180° 翻转（倒装）。由上层在设置变更时灌进来。 */
    @Volatile
    private var flip180: Boolean = false

    /**
     * 仪表图层模式（三模式分层）。
     *
     * 设置可能在窗口建立之前到达（冷启动 / 会话尚未 start）⇒ 先记在这里，
     * [attach] 建窗成功后立刻补应用一次。模式变化**不重建窗口、不重连**。
     */
    private val pendingLayerMode = PendingClusterLayerMode()

    /**
     * 导航视频层此刻是否显示（2026-10-22 解耦，P0）。
     *
     * 默认 `true` ⇒ 未接入新 API 的调用方行为不变。
     * 与 [pendingLayerMode] 一样需要跨窗口重建保留：[attach] 成功后补应用一次。
     */
    @Volatile
    private var navigationLayerVisible: Boolean = true

    /** 屏监听是否已注册（PATCH-0069）。防 `start` 反复调用时重复注册。 */
    private var displayListenerRegistered = false

    /**
     * 上次落盘的「副屏声明跳过」原因（PATCH-0118，诊断去重用）。
     *
     * [currentSpecOrNull] 每次建栈都会被调，原因不变时**只打一行**——
     * 真车场景（`clusterDisplayId=-1`）下，第一次建栈就把「为什么 /info
     * 不声明副屏」钉进诊断包，而不是每个会话刷十几行。
     */
    private var lastSpecFailureReason: String? = null

    /** 上次落盘的副屏候选签名（`id×宽×高`，PATCH-0118，诊断去重用）。 */
    private var lastCandidatesSignature: String? = null

    /** 窗口是否挂在屏上（[CarClusterPresenter.isWindowAttached]）。 */
    override fun isWindowAttached(): Boolean = presentation != null

    /** 窗口是否已出画（[CarClusterPresenter.isRendering]）。 */
    override fun isRendering(): Boolean = rendering

    /**
     * 设置用户选中的仪表屏 id（-1 = 未选）。
     *
     * 上层（`CarAppController`）从 `CarSessionSettings.clusterDisplayId` 读取后灌入，
     * 变更时重新调用；presenter 会在下一次 [start] / [currentSpecOrNull] 时生效。
     */
    override fun setRequestedDisplayId(id: Int) {
        requestedDisplayId = id
    }

    /**
     * 画面 180° 翻转（副屏倒装；见 [CarClusterFlipControl]）。
     *
     * **就地生效**：窗口已在时立刻改视图变换，不等下次 [start]。
     * 与选屏不同 —— 翻转不改变向 iPhone 的声明（尺寸/安全区/帧率都不动），
     * 所以既不需要重建窗口，也不需要重连 CarPlay。
     *
     * 冷启动时 [start] 早于本调用也没问题：[attach] 建窗时会把当前值带进去。
     */
    override fun setFlip180(enabled: Boolean) {
        if (flip180 == enabled) return
        flip180 = enabled
        presentation?.setFlip180(enabled)
        diagnostics.log(TAG, "Cluster: 画面翻转 setFlip180=$enabled（窗口${if (presentation != null) "已在，就地生效" else "未挂，下次建窗带上"}）")
    }

    /**
     * 应用仪表图层模式（三模式分层）。
     *
     * 只改窗口内图层的可见性与几何：THEME_ONLY 把视频层设为 `INVISIBLE`
     * （保留 Surface，不断流），CARPLAY_ONLY 下一帧即可恢复画面。
     * 模式在窗口未建立时先暂存，[attach] 成功后补应用。
     */
    /** 用户已保存的表盘布局；建窗时由 [attach] 补应用。 */
    private var lastDialLayout: ClusterDialLayout = ClusterDialLayout.DEFAULT

    /** 用户已保存的自绘区域；建窗时由 [attach] 补应用（2026-10-22）。 */
    private var lastDrawRegion: ClusterDrawRegion = ClusterDrawRegion.DEFAULT

    /** 用户已保存的视频画面变换；建窗时由 [attach] 补应用（2026-10-22）。 */
    private var lastVideoTransform: ClusterVideoTransform = ClusterVideoTransform.DEFAULT

    override fun applyDialLayout(layout: ClusterDialLayout) {
        val safe = ClusterDialLayout.sanitize(layout)
        lastDialLayout = safe
        presentation?.applyDialLayout(safe)
        diagnostics.log(TAG, "仪表表盘布局：左(${safe.left.dx},${safe.left.dy},x${safe.left.scale},${safe.left.visible}) 右(${safe.right.dx},${safe.right.dy},x${safe.right.scale},${safe.right.visible})")
    }

    override fun applyDrawRegion(region: ClusterDrawRegion) {
        val safe = ClusterDrawRegion.sanitize(region)
        lastDrawRegion = safe
        presentation?.applyDrawRegion(safe)
        diagnostics.log(
            TAG,
            "仪表自绘区域：x=${safe.x} y=${safe.y} w=${safe.width} h=${safe.height} " +
                "内容缩放=x${"%.3f".format(safe.contentScale)}",
        )
    }

    override fun applyVideoTransform(transform: ClusterVideoTransform) {
        val safe = ClusterVideoTransform.sanitize(transform)
        lastVideoTransform = safe
        presentation?.applyVideoTransform(safe)
        diagnostics.log(
            TAG,
            "仪表画面变换：scale=x${"%.3f".format(safe.scale)} dx=${safe.dx} dy=${safe.dy}",
        )
    }

    override fun applyClusterLayerMode(mode: ClusterLayerMode) {
        pendingLayerMode.set(mode)
        applyLayerModeToPresentation(mode, attached = presentation != null)
    }

    private fun applyLayerModeToPresentation(mode: ClusterLayerMode, attached: Boolean) {
        presentation?.applyLayerMode(mode)
        diagnostics.log(TAG, "仪表图层：平台层应用 mode=${mode.name} (attached=$attached)")
    }

    /**
     * 只切换导航视频层显隐（2026-10-22 解耦，P0）。
     *
     * 契约同 [applyClusterLayerMode]：不拆窗、不断流、不解绑 Surface；
     * 主题层与窗口生命周期不受影响。窗口未建立时先记住，[attach] 后补应用。
     */
    override fun applyNavigationVisible(visible: Boolean) {
        if (navigationLayerVisible == visible) return
        navigationLayerVisible = visible
        presentation?.applyNavigationVisible(visible)
        diagnostics.log(
            TAG,
            "仪表图层：导航视频层 ${if (visible) "显示" else "隐藏"}" +
                "（窗口${if (presentation != null) "已在，就地生效" else "未挂，下次建窗带上"}）",
        )
    }

    // ------------------------------------------------- 全屏地图模式守护（阶段 1）

    override fun isFullMapAvailable(): Boolean =
        runCatching { dim.isAvailable() }.getOrDefault(false)

    override fun requestFullMap(enabled: Boolean, reason: String): Boolean {
        if (enabled) {
            if (!projectionIntent.allowsAutomaticOpen()) return false
            if (!isFullMapAvailable()) {
                modeSession.acquire(reason)
                return false
            }
            modeSession.acquire(reason)
        } else {
            projectionIntent.resetObservation()
            modeObserver.markOwnModeOff()
            modeSession.release(reason)
        }
        return true
    }

    override fun onManualProjectionOpen() {
        projectionIntent.onManualRequest(true)
        suppressReopenUntil = 0L
        if (stopped || presentation == null) return
        if (modeGuardEnabled) modeSession.acquire("manual-open") else seizeCluster("manual-open")
    }

    override fun fullMapState(): CarClusterFullMapState = modeSession.state()

    override fun restoreIfLeftOn(): Boolean {
        val hasPending = modeJournal.pending() != null
        if (!hasPending) return false
        modeSession.restoreIfLeftOn()
        return true
    }

    override fun setKeyFrameRequester(requester: (() -> Boolean)?) {
        modeSession.setKeyFrameRequester(requester)
    }

    override fun setModeGuardEnabled(enabled: Boolean) {
        if (modeGuardEnabled == enabled) return
        modeGuardEnabled = enabled
        diagnostics.log(TAG, "Cluster: 全屏模式守护 setModeGuardEnabled=$enabled")
        if (!enabled) {
            // 运行中关闭守护：先把可能残留的全屏模式按老路径还回去。
            if (modeSession.state().phase != CarClusterFullMapPhase.IDLE &&
                modeSession.state().phase != CarClusterFullMapPhase.RESTORED
            ) {
                modeSession.release("guard-disabled")
            }
        }
    }

    /**
     * 首帧看门狗回调：状态机已回滚模式，这里负责把黑窗拆掉。
     *
     * 只在守护路径生效；开关关闭时不会走到这里（session 不会被 acquire）。
     */
    private fun onGuardFirstFrameTimeout() {
        if (!modeGuardEnabled || stopped || presentation == null) return
        diagnostics.log(TAG, "Cluster: 首帧超时 ⇒ 放弃本轮仪表窗口，等下次 start 重建")
        presentationGeneration += 1L
        modeObserver.markOwnModeOff()
        runCatching { modeObserver.stop() }
        mainHandler.removeCallbacks(modePoll)
        mainHandler.removeCallbacks(diagnosticSnapshot)
        removePresentation()
        currentDisplayId = null
    }

    /**
     * 列出当前所有可选副屏（**所有非主屏**），供设置页下拉展示。
     *
     * 候选清单会走 [logCandidatesOnce] 落一行诊断（PATCH-0118）：
     * 真车诊断里「用户看到的那份列表长什么样」此前完全问不出来。
     */
    override fun listCandidates(): List<CarClusterCandidate> {
        if (!GeelyDisplaySelector.isEcarx(appContext)) {
            logCandidatesOnce(emptyList(), "非 eCarx 车机（平台不提供副屏）")
            return emptyList()
        }
        val manager = displayManager()
        if (manager == null) {
            logCandidatesOnce(emptyList(), "DisplayManager 不可用")
            return emptyList()
        }
        val displays = GeelyDisplaySelector.snapshotDisplays(manager)
        val list = GeelyDisplaySelector.candidates(isEcarx = true, displays = displays)
        logCandidatesOnce(list, if (list.isEmpty()) "没有非主屏候选" else null)
        return list.map {
            CarClusterCandidate(
                displayId = it.id,
                label = GeelyDisplaySelector.labelOf(it),
                widthPixels = it.widthPixels,
                heightPixels = it.heightPixels,
            )
        }
    }

    override fun isAvailable(): Boolean {
        if (!GeelyDisplaySelector.isEcarx(appContext)) return false
        val manager = displayManager() ?: return false
        val displays = GeelyDisplaySelector.snapshotDisplays(manager)
        return GeelyDisplaySelector.candidates(isEcarx = true, displays = displays).isNotEmpty()
    }

    /**
     * 当前副屏规格。**只认用户选中的那块**（见 [setRequestedDisplayId]）。
     *
     * ⚠️ 未选（-1）或选中的屏此刻不在 ⇒ 返回 null（上层按「无副屏」处理），
     * **不再自动挑第一块** —— 这是 2026-10-08「选错屏」事故的根治点。
     *
     * 与 [start] 用**同一套** `snapshotDisplays + select` 判据，
     * 保证「声明的屏」与「挂窗口的屏」是同一块。
     */
    override fun currentSpecOrNull(): CarClusterSpec? {
        if (!GeelyDisplaySelector.isEcarx(appContext)) {
            logSpecFailureOnce("非 eCarx 车机（平台不提供仪表副屏）")
            return null
        }
        val manager = displayManager()
        if (manager == null) {
            logSpecFailureOnce("DisplayManager 不可用")
            return null
        }
        val displays = GeelyDisplaySelector.snapshotDisplays(manager)
        val selected = GeelyDisplaySelector.select(
            isEcarx = true,
            displays = displays,
            requestedId = requestedDisplayId,
        )
        if (selected == null) {
            // PATCH-0118：**为什么 /info 不声明副屏**，落一行可判定日志。
            // 真车诊断（1.1.3/196）里 `clusterDisplayId=-1` 而日志一字没有，
            // 事后只能靠 settings 反推 —— 现在第一次建栈就把原因钉进包。
            logSpecFailureOnce(
                GeelyDisplaySelector.selectFailureReason(
                    isEcarx = true,
                    displays = displays,
                    requestedId = requestedDisplayId,
                ) ?: "未知原因",
            )
            return null
        }
        if (lastSpecFailureReason != null) {
            diagnostics.log(
                TAG,
                "Cluster: 副屏声明恢复：displayId=${selected.id}（此前：$lastSpecFailureReason）",
            )
            lastSpecFailureReason = null
        }
        return GeelyDisplaySelector.specOf(selected, panelDensityDpi())
    }

    override fun start(spec: CarClusterSpec, onSurfaceAvailable: (Surface?) -> Unit) {
        if (!projectionIntent.allowsAutomaticOpen()) return
        projectionIntent.resetObservation()
        stopped = false
        densityDpi = spec.densityDpi
        // PATCH-0118：一次用户动作一行，把「iPhone 被告知的仪表屏」全字段钉进日志。
        // 建窗成不成、窗口挂哪块屏，都以这一行作基准 —— 尺寸/可见高/密度/
        // 物理尺寸/帧率/安全区少记一项，排查时就会被怀疑成那一项的锅。
        diagnostics.log(TAG, "Cluster: 开始建窗，本次申报规格 ${describeSpec(spec)}")
        registerDisplayListenerIfNeeded()

        val manager = displayManager() ?: run {
            diagnostics.log(TAG, "Cluster: DisplayManager unavailable")
            return
        }
        val displays = GeelyDisplaySelector.snapshotDisplays(manager)
        // PATCH-0054：把这次能看到的**全部**显示器原样记下来。排查"选错屏/找不到副屏"
        // 时，先看这一行就够 —— 免得再回头怀疑"是不是系统压根没枚举出来"。
        diagnostics.log(
            TAG,
            "Cluster: 显示器快照（共 ${displays.size}）：" +
                displays.joinToString(" | ") { d ->
                    "id=${d.id} name=${d.name} type=${d.displayType} " +
                        "${d.widthPixels}x${d.heightPixels} dpi=${d.xdpi}x${d.ydpi} " +
                        "refresh=${d.refreshRate}Hz default=${d.isDefault}"
                },
        )
        runCatching {
            val candidates = GeelyDisplaySelector.candidates(isEcarx = true, displays = displays)
            val list = candidates.joinToString(",") { candidate ->
                "${candidate.id}:${candidate.widthPixels}x${candidate.heightPixels}:" +
                    if (candidate.isDefault) "main" else "alt"
            }
            diagnostics.log(
                TAG,
                "T0|display.candidates|req=$requestedDisplayId n=${candidates.size} list=[$list]",
            )
        }
        // 只认用户选中的那块（-1 未选 ⇒ null），不再自动挑。
        val selected = GeelyDisplaySelector.select(
            isEcarx = true,
            displays = displays,
            requestedId = requestedDisplayId,
        )

        if (selected == null) {
            // PATCH-0118：不选中**为什么**——未选 / 不在场 / 选的是主屏，
            // 三种成因分属不同的处置（去设置页选 / 查屏是否上电 / 换一块）。
            val reason = GeelyDisplaySelector.selectFailureReason(
                isEcarx = true,
                displays = displays,
                requestedId = requestedDisplayId,
            ) ?: "未知原因"
            runCatching {
                diagnostics.log(TAG, "T0|display.fail|reason=$reason req=$requestedDisplayId")
            }
            return
        }
        // 2026-10-08：`spec` 是调用方在**建栈时**声明的规格，可能已经过期
        // （用户刚换了屏 / 屏刚重排）。契约要求"声明的屏"与"挂窗口的屏"必须是
        // 同一块，不一致时**不挂窗**——挂上去就是"声明了 A 屏、窗口在 B 屏"：
        // 手机按 A 推流，窗口在 B 上收不到，用户看到的是白块。
        // 宁可这一轮不挂，等调用方重建会话后拿到一致的 spec。
        if (spec.displayId != selected.id) {
            diagnostics.log(
                TAG,
                "Cluster: 声明屏与当前选屏不一致（spec=${spec.displayId}, 选中=${selected.id}）" +
                    "—— 不挂窗，等重建会话",
            )
            return
        }
        // PATCH-0052（方案 A）：**不再复用副屏窗口**，每次 start() 都重建。
        //
        // 病根（第三轮真车实证，日志 1791519459715_...）：副屏窗口的生命周期比会话长。
        // 对端断开再连时，`stop()` 不会被调用（它只挂在 App 退出上），窗口连同它的
        // `TextureView`/`SurfaceTexture` 一直活着 ⇒ `presentation != null` 且
        // `surface.isValid == true` ⇒ 旧的复用判据成立 ⇒ 把**上一会话的旧 Surface**
        // 又交给 ALT(111)，而解码器实际绑的是另一条新 Surface ⇒ 画面永远到不了窗口 ⇒ 白屏。
        //
        // PATCH-0049 加「通道是否有效」也只挡住了「纹理被系统回收」这一种姿势；
        // 「对端断流、纹理还在但没帧了」它挡不住。判据追不上，得从源头禁止跨会话复用。
        //
        // 原版（`GeelyClusterSurfaceOwner.onDisplayChanged`）就是这么做的：
        // 同屏 ⇒ **直接 return，什么都不做**；只要决定要动 ⇒ `removePresentation()`
        // （dismiss 并把 presentation/surface/displayId 统统置空）→ 新建 → **每次 show()**。
        // 同理，也没有「同屏就直接 return」这条 —— 那会让"同一块屏重连"什么都不做，
        // 而重连时窗口其实已经该重建了。所以这里干脆不设同屏短路：每次都 attach() 重建。
        attach(manager, selected, onSurfaceAvailable)
    }

    private fun attach(
        manager: DisplayManager,
        descriptor: GeelyDisplayDescriptor,
        onSurfaceAvailable: (Surface?) -> Unit,
    ) {
        val generation = ++presentationGeneration
        // 先解绑上一会话的 Surface。旧 Presentation 后续若再迟到回调，
        // generation 闸门会丢弃，不会把新会话刚绑定的 Surface 清空。
        onSurfaceAvailable(null)
        removePresentation()
        val display = runCatching { manager.getDisplay(descriptor.id) }.getOrNull()
        if (display == null) {
            diagnostics.log(TAG, "Cluster: HDMI2 display ${descriptor.id} unavailable")
            return
        }
        runCatching {
            diagnostics.log(
                TAG,
                "T0|display.selected|id=${descriptor.id} " +
                    "${descriptor.widthPixels}x${descriptor.heightPixels} " +
                    "isDefault=${descriptor.isDefault} name=${descriptor.name} " +
                    "displayType=${descriptor.displayType} xdpi=${descriptor.xdpi} " +
                    "ydpi=${descriptor.ydpi} refreshRate=${descriptor.refreshRate}",
            )
        }
        val spec = GeelyDisplaySelector.specOf(descriptor, densityDpi)
        rendering = false
        val p = runCatching {
            GeelyClusterPresentation(appContext, display, spec, flip180) { surface ->
                if (shouldAcceptClusterPresentationCallback(generation, presentationGeneration, stopped)) {
                    runCatching {
                        diagnostics.log(
                            TAG,
                            "T0|surface.gate|gen_cb=$generation gen_cur=$presentationGeneration " +
                                "stopped=$stopped accept=true surface=${if (surface == null) "null" else "non-null"}",
                        )
                    }
                    onSurfaceAvailable(surface)
                } else {
                    runCatching {
                        diagnostics.log(
                            TAG,
                            "T0|surface.gate|gen_cb=$generation gen_cur=$presentationGeneration " +
                                "stopped=$stopped accept=false surface=${if (surface == null) "null" else "non-null"}",
                        )
                    }
                }
            }.also { created ->
                created.onFirstFrame = {
                    if (shouldAcceptClusterPresentationCallback(generation, presentationGeneration, stopped)) {
                        rendering = true
                        if (modeGuardEnabled) modeSession.onFirstFrame()
                    }
                }
                // PATCH-0049：通道被系统回收 ⇒ 立刻丢窗，等下次 start() 重建。
                created.onSurfaceInvalidated = { onWindowInvalidated(descriptor.id, generation) }
                created.show()
                runCatching {
                    diagnostics.log(
                        TAG,
                        "T0|window.create|gen=$generation displayId=${descriptor.id} showing=${created.isShowing}",
                    )
                }
            }
        }.getOrElse { error ->
            diagnostics.log(TAG, "Cluster: 创建仪表窗口失败 ${error.javaClass.simpleName}: ${error.message}")
            return
        }
        presentation = p
        currentDisplayId = descriptor.id
        // 建窗前到达的模式在这里补应用一次；不触发新的 attach / 重连。
        pendingLayerMode.applyTo { mode ->
            applyLayerModeToPresentation(mode, attached = true)
        }
        // 2026-10-22：建窗前保存的标定参数统一补应用。
        // 原来只有模式有补应用，表盘布局（lastDialLayout）只存不发 ——
        // 用户在窗口建立前调好的布局，窗口重建后会退回 DEFAULT。
        p.applyDialLayout(lastDialLayout)
        p.applyDrawRegion(lastDrawRegion)
        p.applyVideoTransform(lastVideoTransform)
        // 2026-10-22 解耦（P0）：导航层显隐与模式一样跨窗口重建保留。
        p.applyNavigationVisible(navigationLayerVisible)
        diagnostics.log(
            TAG,
            "Cluster: 仪表副屏已挂载 displayId=${descriptor.id} " +
                "面板=${spec.widthPixels}x${spec.heightPixels} 可见高=${spec.visibleHeightPixels} " +
                "dpi=${spec.densityDpi} fps=${spec.fps} " +
                "物理=${spec.widthPhysicalMm?.let { "${it}×${spec.heightPhysicalMm}mm" } ?: "未自报（下游用 350mm 出厂值）"}",
        )
        // PATCH-0054：窗口刚挂上时快照一次（此时 Surface 多半还没来，updated 计数应为 0），
        // 作为后续对比的基线。
        diagnostics.log(TAG, "Cluster: 挂载快照 ${p.diagnosticSnapshot()}")

        // 2026-10-08：窗口挂上≠有画面。车机自己的 DimMenu 占着仪表，必须把它要过来，
        // 同时开始监听「被它抢回去」的事件。两步都做完，画面才稳得住。
        // 2026-10-09：再加一道 1500ms 反射轮询（对齐原版 modePoll），不依赖车机打日志。
        modeObserver.start()
        mainHandler.removeCallbacks(modePoll)
        mainHandler.removeCallbacks(diagnosticSnapshot)
        mainHandler.postDelayed(modePoll, MODE_POLL_INTERVAL_MS)
        if (modeGuardEnabled) {
            modeSession.setDisplayId(descriptor.id)
            modeSession.acquire("attach")
        } else {
            val taken = seizeCluster("attach")
            diagnostics.log(
                TAG,
                "Cluster: 仪表抢屏 entered=$taken（类=${dim.resolvedClass ?: "候选全不可用"}）",
            )
        }
        // PATCH-0054：抢屏后 2s 再快照一次。此时若 Surface 已到、帧已在更新，
        // `updated回调累计` 应 > 0；若仍为 0，则"回调没来"被坐实，
        // 无需再从日志里翻 `帧更新` 有没有出现过。
        mainHandler.removeCallbacks(diagnosticSnapshot)
        mainHandler.postDelayed(diagnosticSnapshot, DIAGNOSTIC_SNAPSHOT_DELAY_MS)
    }

    /**
     * 调一次抢屏，并维护失败抑制。
     *
     * @param source 触发来源（`attach` / `logcat` / `poll`），只入日志便于真车核对。
     * @return 车机是否接受（见 [GeelyDimModeController.enterFullNavi] 对返回值的说明）。
     */
    private fun seizeCluster(source: String): Boolean {
        if (!projectionIntent.allowsAutomaticOpen()) return false
        val now = SystemClock.elapsedRealtime()
        if (now < suppressReopenUntil) {
            diagnostics.log(TAG, "Cluster: 抢屏抑制中（${suppressReopenUntil - now}ms 后再试，source=$source）")
            return false
        }
        val ok = dim.enterFullNavi()
        if (!ok) {
            // 调用直接失败（类/方法不可用或抛异常）⇒ 抑制一段时间，别打成风暴。
            suppressReopenUntil = now + REOPEN_SUPPRESS_MS
            diagnostics.log(TAG, "Cluster: 抢屏失败 ⇒ 抑制 ${REOPEN_SUPPRESS_MS}ms（source=$source）")
        }
        return ok
    }

    /**
     * 日志只是模式关闭提示，不携带 VIEW 键或调用者身份，不能据此重开。
     */
    private fun onClusterTakenBack(source: String) {
        // A mode-off log carries no key/user identity and may be delayed. Never seize
        // from it; the main-thread poll confirms a stable 3 -> 1 transition instead.
        if (stopped || presentation == null) return
        diagnostics.log(TAG, "Cluster: 模式关闭提示（$source），等待稳定回读；不推断 VIEW、不自动抢屏")
    }

    /**
     * 轮询一次车机仪表模式（[modePoll] 的每次 tick）。
     *
     * 只在已出画且导航层可见时确认 3 -> 稳定1；不把所有模式1当作VIEW。
     */
    private fun pollNaviModeOnce() {
        val phase = modeSession.state().phase
        val eligible = !stopped && presentation != null && rendering && navigationLayerVisible &&
            !modeObserver.isOwnModeOffQuiet() && (!modeGuardEnabled ||
            phase == CarClusterFullMapPhase.ACTIVE || phase == CarClusterFullMapPhase.WAITING_FRAME)
        if (projectionIntent.observeMode(dim.readNaviMode(), eligible, SystemClock.elapsedRealtime())) {
            if (modeGuardEnabled) modeSession.acceptExternalModeOff()
            diagnostics.log(TAG, "Cluster: 全屏3后稳定关闭1；尊重外部关闭，本次连接等待手动打开（非VIEW键识别）")
        }
    }


    override fun stop() {
        stopped = true
        // Window teardown is not a new connection or permission to auto-open.
        projectionIntent.resetObservation()
        presentationGeneration += 1L
        // 顺序要紧：先声明「这次是我们主动关的」，再退模式、停监听 ——
        // 否则监听会把自己触发的关闭当成「被车机抢走」，白跑一次重开。
        modeObserver.markOwnModeOff()
        if (modeGuardEnabled) {
            modeSession.release("stop")
        } else {
            dim.exitFullNavi()
        }
        modeObserver.stop()
        mainHandler.removeCallbacks(modePoll)
        mainHandler.removeCallbacks(diagnosticSnapshot)
        suppressReopenUntil = 0L
        removePresentation()
        currentDisplayId = null
        unregisterDisplayListener()
    }

    override fun onSpecChanged(spec: CarClusterSpec) {
        // 本轮不改行为：屏插拔由上层重新 start() 触发。
        diagnostics.log(TAG, "Cluster: spec changed → ${spec.widthPixels}x${spec.heightPixels}")
    }

    /**
     * 画面通道被系统回收（PATCH-0049；PATCH-0052 后语义更纯粹）。
     *
     * 窗口壳子可能还在，但里面的 SurfaceTexture 已经废了 —— 留着它只会让下一次
     * [start] 拿到一个死 Surface（仪表黑屏）。这里**主动丢窗**
     * （`release()` 内部会 dismiss，不留残窗），下次 start() 重建。
     *
     * PATCH-0052（方案 A）后 [start] 已不再复用窗口，这条回调不再是"防止复用死通道"
     * 的唯一防线；保留它仍有用：通道真被系统回收时，及时把窗口与抢屏状态收干净，
     * 免得留一个"活着但永远不出画"的空窗占着 ALT(111) 轨。
     *
     * 只在窗口确实是当前那块屏时动手：避免上一块屏的迟到回调把新窗拆掉。
     */
    private fun onWindowInvalidated(displayId: Int, generation: Long) {
        // 主动 stop() 拆窗或新会话已重建时，旧窗口的迟到回调不能拆掉当前窗口。
        if (!shouldAcceptClusterPresentationCallback(generation, presentationGeneration, stopped) ||
            currentDisplayId != displayId
        ) {
            return
        }
        diagnostics.log(
            TAG,
            "Cluster: 画面通道失效（displayId=$displayId）—— 丢弃窗口，等下次 start 重建",
        )
        runCatching {
            diagnostics.log(
                TAG,
                "T0|window.invalidated|gen_cb=$generation gen_cur=$presentationGeneration displayId=$displayId",
            )
        }
        presentationGeneration += 1L
        if (modeGuardEnabled) {
            modeSession.release("window-invalidated")
        } else {
            runCatching { dim.exitFullNavi() }
        }
        runCatching { modeObserver.stop() }
        mainHandler.removeCallbacks(modePoll)
        mainHandler.removeCallbacks(diagnosticSnapshot)
        removePresentation()
        currentDisplayId = null
    }

    /**
     * 屏热插拔感知（PATCH-0069，对齐原版 `GeelyClusterSurfaceOwner` L141-168 + L366）。
     *
     * 原版在 `attach$common` 里 `registerDisplayListener(listener, mainHandler)`，
     * 并在 attach 时**主动触发一次** `onDisplayChanged(detect(...))`：
     * ```
     * onDisplayAdded/Changed/Removed(displayId)
     *   → onDisplayChanged(selector.detect(context, selectedDisplayId))
     *       → removePresentation() → new Presentation → show()
     * ```
     *
     * 当前版只在上层调用 [start] 时建窗 —— 仪表副屏热插拔 / 被系统重排 / 分辨率变化时
     * 不会自动重建（`grep registerDisplayListener` 全工程 0 命中，PATCH-0069 前）。
     *
     * 这里补上监听：**只关心「当前挂窗那块屏」的增删改**，且用生成号 + 未停止双重闸门
     * 防抖动（连续回调不会把新窗拆掉）。回调触发后走 [start] 等价重建。
     */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            onClusterDisplayTopologyChanged("added", displayId)
        }

        override fun onDisplayChanged(displayId: Int) {
            onClusterDisplayTopologyChanged("changed", displayId)
        }

        override fun onDisplayRemoved(displayId: Int) {
            onClusterDisplayTopologyChanged("removed", displayId)
        }
    }

    /**
     * 屏拓扑变化 → 按需重建仪表窗。
     *
     * 只处理 `currentDisplayId` 那块屏（挂窗中），以及**新出现的候选屏在用户已选屏 id 上**
     * 这种情况（插入后正好是选中那块）。其余一律忽略，避免误拆。
     */
    private fun onClusterDisplayTopologyChanged(reason: String, displayId: Int) {
        if (stopped) return
        val watching = currentDisplayId
        val relevant = displayId == watching || displayId == requestedDisplayId
        if (!relevant) return
        diagnostics.log(
            TAG,
            "Cluster: 屏拓扑变化($reason) displayId=$displayId 当前窗=${watching ?: "无"} " +
                "→ 下一拍重建",
        )
        // 交回主线程下一拍：避免正在派发的回调里直接 dismiss（框架回调中拆窗易踩时序）。
        mainHandler.post {
            if (stopped) return@post
            if (currentDisplayId != watching && displayId != requestedDisplayId) return@post
            if (currentDisplayId == null && presentation == null) return@post
            // 屏没了 / 变了 ⇒ 先收干净；接着交给上层重新 start（这里不自行挑屏，
            // 选屏判据永远是 GeelyDisplaySelector + requestedDisplayId，见 KDoc）。
            presentationGeneration += 1L
            if (modeGuardEnabled) {
                modeSession.release("display-topology")
            } else {
                runCatching { dim.exitFullNavi() }
            }
            runCatching { modeObserver.stop() }
            mainHandler.removeCallbacks(modePoll)
            mainHandler.removeCallbacks(diagnosticSnapshot)
            removePresentation()
            currentDisplayId = null
            diagnostics.log(TAG, "Cluster: 屏拓扑变化后已收窗，等上层 start 重建")
        }
    }

    private fun removePresentation() {
        presentation?.release()
        presentation = null
        // 窗口没了，首帧状态一并复位 —— 否则上层会读到"窗口已出画"却找不到窗口。
        rendering = false
    }

    /**
     * 注册屏监听（幂等）。`start` 时挂上，`stop` 时摘掉（PATCH-0069）。
     *
     * 用 `displayListenerRegistered` 防重复注册 —— `start` 每次重建窗口都会走一遍，
     * 不加闸门会往 `DisplayManager` 里塞 N 份同一 listener。
     */
    private fun registerDisplayListenerIfNeeded() {
        if (displayListenerRegistered) return
        val manager = displayManager() ?: return
        runCatching { manager.registerDisplayListener(displayListener, mainHandler) }
            .onSuccess {
                displayListenerRegistered = true
                diagnostics.log(TAG, "Cluster: 屏监听已注册（热插拔感知）")
            }
            .onFailure { Log.w(TAG, "注册屏监听失败：${it.javaClass.simpleName}") }
    }

    /** 摘掉屏监听（[stop] 时调用）。 */
    private fun unregisterDisplayListener() {
        if (!displayListenerRegistered) return
        val manager = displayManager() ?: return
        runCatching { manager.unregisterDisplayListener(displayListener) }
        displayListenerRegistered = false
    }

    private fun displayManager(): DisplayManager? =
        runCatching {
            appContext.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        }.getOrNull()

    /**
     * 面板密度。取不到时回落到 160（`AndroidCarDisplayAdapter.DEFAULT_DENSITY_DPI` 同源）。
     *
     * 密度只影响报给 iPhone 的**物理尺寸**（mm = px/dpi×25.4），不影响像素尺寸，
     * 因此取不到时的回落是安全的。
     */
    private fun panelDensityDpi(): Int {
        val dpi = appContext.resources.displayMetrics.densityDpi
        return if (dpi > 0) dpi else 160
    }

    // ------------------------------------------------------------- 诊断落盘（PATCH-0118）

    /**
     * 「副屏声明跳过」落盘，**原因不变只打一行**（PATCH-0118）。
     *
     * [currentSpecOrNull] 每次建栈都会被调；不按原因去重的话，
     * 一个 `clusterDisplayId=-1` 的会话会刷出一串同样的行。去重后
     * **第一次**建栈就把「为什么 /info 不声明副屏」钉进诊断包，
     * 之后只在原因变化（选屏 / 拔屏 / 换屏）时再各打一行。
     */
    private fun logSpecFailureOnce(reason: String) {
        if (lastSpecFailureReason == reason) return
        lastSpecFailureReason = reason
        diagnostics.log(TAG, "Cluster: 副屏声明跳过：$reason ⇒ /info 不声明副屏（仪表不会收到 CarPlay 画面）")
    }

    /**
     * 副屏候选清单落盘，**清单没变不重复打**（PATCH-0118）。
     *
     * [listCandidates] 每次 `refreshModel` 都会被调（设置页只是其中一处），
     * 按「id×宽×高」签名去重后，只有**应用启动后的第一次枚举**与
     * **屏插拔 / 系统重排**时才各落一行。`emptyReason` 非空时记的是
     * 「为什么一份候选都没有」（非 eCarx / 取不到 DisplayManager / 没有非主屏）。
     */
    private fun logCandidatesOnce(list: List<GeelyDisplayDescriptor>, emptyReason: String?) {
        if (emptyReason != null) {
            if (lastCandidatesSignature == emptyReason) return
            lastCandidatesSignature = emptyReason
            diagnostics.log(TAG, "Cluster: 副屏候选为空：$emptyReason")
            return
        }
        val signature = list.joinToString(",") { "${it.id}x${it.widthPixels}x${it.heightPixels}" }
        if (lastCandidatesSignature == signature) return
        lastCandidatesSignature = signature
        diagnostics.log(
            TAG,
            "Cluster: 副屏候选（${list.size}）：" + list.joinToString(" | ") {
                "displayId=${it.id} ${it.widthPixels}×${it.heightPixels}（${GeelyDisplaySelector.panelHint(it)}）"
            },
        )
    }

    /**
     * 把一次申报出去的 [CarClusterSpec] 打成一行诊断文案（PATCH-0118）。
     *
     * 完整记录「iPhone 被告知的仪表屏是什么」——尺寸、可见高、密度、
     * 物理尺寸、帧率、安全区、区外绘制。缺一项，事后排查时都会被
     * 怀疑成那一项的锅；全记下来才能一眼排除。
     */
    private fun describeSpec(spec: CarClusterSpec): String {
        val physical = spec.widthPhysicalMm?.let { "${it}×${spec.heightPhysicalMm}mm" }
            ?: "未自报（下游用 350mm 出厂值等比推算）"
        val safe = spec.safeArea
            ?.let { "(left=${it.left},top=${it.top},right=${it.right},bottom=${it.bottom})" }
            ?: "不声明"
        return "displayId=${spec.displayId} 尺寸=${spec.widthPixels}x${spec.heightPixels} " +
            "可见高=${spec.visibleHeightPixels} dpi=${spec.densityDpi} 物理=$physical " +
            "fps=${spec.fps} 安全区=$safe 区外绘制=${spec.safeAreaDrawOutside}"
    }


    companion object {
        private const val TAG = "carplay-cluster"

        /** `modePoll` 轮询周期（毫秒）。对齐原版 `GeelyClusterSurfaceOwner$modePoll$1` 的 `0x5dc`。 */
        private const val MODE_POLL_INTERVAL_MS = 1500L

        /** 抢屏失败后的抑制时长（毫秒）。对齐原版 `suppressedForRoute` 的"别打成风暴"思路。 */
        private const val REOPEN_SUPPRESS_MS = 10_000L

        /**
         * 挂载后多久打一次诊断快照（毫秒，PATCH-0054）。
         *
         * 取 2s：比抢屏（同步）与首个 modePoll tick（1500ms）都晚，
         * 给解码器留够出首帧的时间；此时 `updated回调累计` 才有判别力。
         */
        private const val DIAGNOSTIC_SNAPSHOT_DELAY_MS = 2_000L
    }
}

/** 只接受当前 Presentation 世代的 Surface、首帧和失效回调。 */
internal fun shouldAcceptClusterPresentationCallback(
    callbackGeneration: Long,
    currentGeneration: Long,
    stopped: Boolean,
): Boolean = !stopped && callbackGeneration == currentGeneration

/**
 * 「attach 前先记住模式、attach 后补应用」的纯逻辑容器。
 *
 * 抽出来是为了能在 JVM 单测里验证：设置早于建窗到达时不会丢失，
 * 建窗后传给窗口的是最后一次设置的值。
 */
internal class PendingClusterLayerMode(
    initial: ClusterLayerMode = ClusterLayerMode.DEFAULT,
) {
    @Volatile
    var current: ClusterLayerMode = initial
        private set

    fun set(mode: ClusterLayerMode) {
        current = mode
    }

    /** 传入 null 表示窗口尚未建立：只保留值，不应用。 */
    fun applyTo(target: ((ClusterLayerMode) -> Unit)?) {
        target?.invoke(current)
    }
}

/**
 * 「图层模式是否要求主动建窗」的纯判据（2026-10-20）。
 *
 * ## 为什么抽成纯函数
 *
 * 1.1.32 现场：`THEME_ONLY` 已经应用了两次，但副屏窗口一直没建，
 * 直到用户手动去设置页选屏（`attached=false` 一直挂着）。建窗条件必须
 * 能脱离 Android 在 JVM 上钉住，否则下次仍只能靠上车试。
 *
 * 判据只有四条：
 * - 模式要显示主题（[ClusterLayerMode.THEME_ONLY] / [ClusterLayerMode.BOTH]）；
 * - 窗口当前没挂（已挂就不重建 —— 模式切换的契约是"不拆窗、不断流"）；
 * - 已有一块可用的副屏（`displayId >= 0`）；
 * - 会话已进入可用阶段（还没启动 / 已停 / 失败时不建）。
 *
 * ⚠️ 放在本文件而不是 `:car:app`：application 模块没有 test source set，
 * 纯判据放那里就没法被 `:car:platform-geely:testDebugUnitTest` 覆盖。
 */
object ClusterLayerAutoAttach {

    /** 该模式是否需要仪表主题层（从而需要一块副屏窗口）。 */
    fun requiresTheme(mode: ClusterLayerMode): Boolean = when (mode) {
        ClusterLayerMode.THEME_ONLY, ClusterLayerMode.BOTH -> true
        ClusterLayerMode.CARPLAY_ONLY -> false
    }

    fun shouldAttach(
        mode: ClusterLayerMode,
        attached: Boolean,
        displayId: Int,
        sessionActive: Boolean,
    ): Boolean = requiresTheme(mode) && !attached && displayId >= 0 && sessionActive
}

/**
 * 启动期「崩溃恢复」动作的提交器（2026-10-20）。
 *
 * ## 它解决什么
 *
 * 蓝牙适配器补恢复 / 仪表全屏模式补还原都是**崩溃恢复**动作：与首帧无关，
 * 却会读落盘标记、走厂商 Binder。1.1.32 现场冷启动 `BIND_APPLICATION`
 * 慢派发 7.4 秒，这两段不该再占用主线程。
 *
 * ## 语义（单测钉住的部分）
 *
 * - [submit] 只做 `executor.execute`，**不等待**动作完成，调用线程立即返回；
 * - 动作内异常只落日志，不让执行器线程崩掉；
 * - 动作结束后打一行 `启动恢复：<label>=<ms>ms`；
 * - 执行器拒绝（已关闭）时也只落日志，不抛给启动路径。
 *
 * ⚠️ 放在本文件的原因同 [ClusterLayerAutoAttach]：`:car:app` 没有 test source set。
 */
class StartupRestoreTasks(
    private val executor: java.util.concurrent.Executor,
    private val clock: () -> Long,
    private val log: (String) -> Unit,
) {

    /** @return true = 执行器已受理（不代表动作成功）。 */
    fun submit(label: String, action: () -> Unit): Boolean {
        val accepted = runCatching {
            executor.execute {
                val startedAt = clock()
                runCatching(action).onFailure { error ->
                    log("启动恢复：$label 失败：${error.message ?: error.javaClass.simpleName}")
                }
                log("启动恢复：$label=${clock() - startedAt}ms")
            }
        }
        if (accepted.isFailure) {
            log("启动恢复：$label 提交失败：${accepted.exceptionOrNull()?.message.orEmpty()}")
            return false
        }
        return true
    }
}
