package com.dbtool.carplay.bridge.navigation

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.dbtool.carplay.api.display.CarClusterCruiseGate
import com.shilapi.xcertplay.iap2.message.CarNavigationState
import com.shilapi.xcertplay.navigation.CarPlayNavigationBridge

/**
 * 导航状态 → 仪表投屏的**触发端**（PATCH-0119，2026-10-14 车主口径）。
 *
 * ## 它负责什么
 *
 * 本类只负责跟随导航路线状态自动显示/收起仪表投屏；用户手动操作由设置页按钮处理。
 * 不把手动操作与路线自动触发合并到本状态机中。
 *
 * ## 判据不在本类里
 *
 * 判据复用 [CarClusterCruiseGate]（2026-10-11 从原版 `GeelyClusterModeState` 同步）；
 * 原版设置页原文是这套路线自动显隐语义的权威来源：
 *
 * > 开始导航自动开启，明确结束导航自动关闭；**持续的无导航状态不会关闭手动巡航地图**。
 *
 * 本类只做三件事：**订阅 → 把上游的"是不是还在导航"判成"是不是明确结束了" → 落成一次
 * 显示/收起请求**。真正的显隐由落点做（桥 → `CarAppController.setClusterVisible`，
 * 即"建窗 + 抢屏 / 拆窗 + 还屏"）。
 *
 * ## ⚠️ 为什么必须有"导航停超时"（本类存在的核心理由）
 *
 * [CarPlayNavigationBridge] 只发 `CarNavigationState?`，**`null` 表示"导航不活跃"**，
 * 而发布方把「用户明确结束了导航」与「持续的无导航（路口等灯、切源过渡空帧）」
 * **都折叠成了同一件事**。可 [CarClusterCruiseGate] 的语义**依赖这个区分**：
 *
 * | 上游给的东西 | 门的入口 | 结果 |
 * | --- | --- | --- |
 * | `routeActive == true` | `onRouteActive(true)` | 自动开屏 |
 * | 非活跃，但只是**一时**没有帧 | `onRouteActive(false)` | **不收屏**（"持续无导航不关闭"） |
 * | 非活跃，且**持续**超过 [NAV_STOP_GRACE_MS] | `onRouteEndedExplicitly()` | 收屏 |
 *
 * 所以**绝不能**把 `null` 直接当成"导航结束"去调 `onRouteEndedExplicitly()` ——
 * 那会在每次路口等灯（iPhone 几秒不推帧）时把仪表收掉。判定权在本类：**自己维护
 * 一个"导航停超时"计时**（见 [NAV_STOP_GRACE_MS] 的取值理由）。
 *
 * ## 数据流
 *
 * ```
 * iPhone ──iAP2 0x5201/0x5202──▶ CarPlayController
 *                                      │ CarPlayNavigationBridge.publish
 *                                      ▼
 *                            本类（订阅 + 导航停超时 + CarClusterCruiseGate）
 *                                      │ onVisibilityRequest(Boolean)
 *                                      ▼
 *                        CarPlaySessionBridge（切主线程）──▶ 落点：
 *                        CarAppController.setClusterVisible（建窗 + 抢屏 / 拆窗 + 还屏）
 * ```
 *
 * ## 线程
 *
 * 与 [CarPlayNavigationHudClient] 同一套纪律：
 *
 * - 订阅回调跑在 **iAP2 帧到达的线程**上（[CarPlayNavigationBridge] 的契约），
 *   每次推帧都可能换线程；
 * - [setEnabled] 由设置变更路径在**主线程**调用；
 * - 超时判定由 [postTimeout] 排的定时任务在**主线程**跑；
 *
 * ⇒ 跨线程读写的字段一律 `@Volatile`，而 [gate] 这条状态机（三个线程都会摸）
 * 统一由 [lock] 串行化 —— 门本身不是线程安全的，靠调用方纪律。
 *
 * ## 不碰 Android 实体
 *
 * 本类**不** `Context` / 不注入具体实现类 / 不直接 `AirPlaySession.setClusterUiShown`：
 * 显隐请求通过构造参数 [onVisibilityRequest] 交出去，落点侧才去碰窗口与 iAP2 命令。
 * 这样判据能在 JVM 上单测（见 `CarPlayClusterRouteClientTest`），而车上的显隐行为
 * 只由一处（`CarAppController`）决定 —— 不会出现第二条建窗链。
 *
 * @param onVisibilityRequest 显隐**请求**（幂等语义：只在结论变化时被调一次）。
 *   `true` = "该显示"，`false` = "该收起"。落点侧负责线程与真实动作。
 * @param isProjectionEnabled 「仪表投屏功能是否可用」（`CarSessionSettings.projectionEnabled`），
 *   如实喂给门的 `enabled`。默认 `true` 只为单测方便，生产由桥注入真值。
 * @param isDisplayAvailable 「是否存在投屏目标」（由桥按平台能力 / 历史配置注入），
 *   喂给门的 `displayAvailable`。"屏在不在"由落点侧与平台 presenter 现查，
 *   本类不碰 `DisplayManager`（那会要求 Context 与主线程）。
 * @param clock 单调时钟（毫秒）。默认 [SystemClock.elapsedRealtime]；单测注入假时钟，
 *   于是"超时"不需要真等 8 秒。
 * @param postTimeout 排一个延迟任务；返回的取消动作由 [cancelTimeout] 负责。
 *   默认走主线程 [Handler]。
 * @param cancelTimeout 取消 [postTimeout] 排下的任务。**必须可取消**：
 *   [connect] / [disconnect] / [setEnabled] / 恢复活跃时都会取消，别让静默期的
 *   定时任务在会话结束后还去请求收起（那时仪表已经还给车机了）。
 * @param log 日志出口。桥注入 `platform.diagnostics.log`（进诊断包）；
 *   默认静默，免得单测里碰到 `android.util.Log`。
 */
class CarPlayClusterRouteClient(
    private val onVisibilityRequest: (Boolean) -> Unit,
    private val isProjectionEnabled: () -> Boolean = { true },
    private val isDisplayAvailable: () -> Boolean = { true },
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val postTimeout: (delayMs: Long, task: Runnable) -> Unit = { delayMs, task ->
        clusterRouteMainHandler.postDelayed(task, delayMs)
    },
    private val cancelTimeout: (task: Runnable) -> Unit = { task ->
        clusterRouteMainHandler.removeCallbacks(task)
    },
    private val log: (String) -> Unit = {},
) {

    /** 订阅句柄；`null` = 未订阅（[connect] / [disconnect] 的幂等判据）。 */
    @Volatile
    private var unsubscribe: (() -> Unit)? = null

    /**
     * 「导航时自动投屏」开关（`CarSessionSettings.clusterAutoShowOnRoute`）。
     *
     * **是闸门而不是断开**（与 [CarPlayNavigationHudClient.setEnabled] 同一口径）：
     * 关掉时订阅照旧、门照旧，只是不再因导航自动投；再打开是瞬时的。
     *
     * `@Volatile`：设置变更路径（主线程）写，iAP2 帧线程读。
     */
    @Volatile
    private var enabled: Boolean = true

    /** 最近一帧看到的"导航是否进行中"。关掉开关期间也照记（重新打开时要补一次）。 */
    @Volatile
    private var routeActiveSeen: Boolean = false

    /**
     * 本会话内**是否见过**"导航进行中"（只升不降，[connect] / [disconnect] 清）。
     *
     * ## 为什么要与 [routeActiveSeen] 分开（PATCH-0121 ④）
     *
     * 上游**只在有变化时推帧**，而导航期间恰恰会出现 `routeActive = false` 的帧
     * （路口等灯、切源过渡的空帧，见类注释那张表）⇒ "最近一帧"完全可能是 false，
     * 哪怕导航一直在跑。而 [setEnabled] 打开开关时要回答的是**另一个问题**：
     * "这一段导航是不是还在（只是这一会儿没有帧）"—— 拿"最近一帧"去答会答成
     * "没在导航"，于是用户把开关拨开却什么都不发生（现场表现：开关像坏了）。
     */
    @Volatile
    private var routeEverActive: Boolean = false

    /** [gate] 与配套字段的串行化锁（iAP2 帧线程 / 主线程的定时任务 / 设置线程）。 */
    private val lock = Any()

    /** 判据状态机（[CarClusterCruiseGate]）。只在 [lock] 内访问。 */
    private var gate = newGate()

    /** 已经发出去的请求值；`null` = 还没发过。状态没变就不再请求（幂等）。 */
    private var requestedShown: Boolean? = null

    /**
     * 「持续不活跃」的起点（[clock] 的时刻）；`null` = 当前不是静默中。
     *
     * ⚠️ 用 `null` 而不是 `0` 当哨兵：`clock` 是**注入**的，单测从 0 起步，
     * 而生产用的 `elapsedRealtime` 理论上也可能是 0 —— 哨兵撞上真值会让"导航停"
     * 永远判不出来（仪表一直亮到会话结束），而这种错完全没有日志。
     */
    private var inactiveSinceMs: Long? = null

    /** 静默期的判定任务；每次静默只排一个，恢复活跃即取消。 */
    private val timeoutCheck = Runnable { onTimeoutFired() }

    /** 造一个"干净的门"。每次订阅/断开都重造，理由见 [connect]。 */
    private fun newGate() = CarClusterCruiseGate(
        enabled = isProjectionEnabled(),
        displayAvailable = isDisplayAvailable(),
    )

    /**
     * 开始订阅导航状态。幂等：重复调用不会连出两条订阅。
     *
     * ⚠️ 每次订阅都**重造状态机**：上一会话留下的 `routeActive=true` 会让新会话的首帧
     * `onRouteActive(true)` 被门当成"没变化"直接丢掉（`CarPlayNavigationBridge.observe`
     * 也只按变化回调），结果是"断线重连后导航还在、仪表却不投了"。
     */
    fun connect() {
        if (unsubscribe != null) return
        com.dbtool.carplay.api.display.ClusterProjectionIntent.resetSession()
        synchronized(lock) {
            gate = newGate()
            requestedShown = null
            // 上一会话的"导航是否进行中"必须作废：它属于**上一个**路线会话，
            // 留着会让"重新打开开关"（[setEnabled]）按一段早就结束的导航去请求投屏。
            // 真在导航的话，紧接着的 `observe` 回放会立刻把真值送回来。
            // [routeEverActive]（曾见过导航）同理：它是**会话级**记忆，新会话一律从零起。
            routeActiveSeen = false
            routeEverActive = false
            inactiveSinceMs = null
            cancelTimeout(timeoutCheck)
        }
        unsubscribe = CarPlayNavigationBridge.observe(::onState)
    }

    /**
     * 停止订阅。幂等。
     *
     * 这里**不**请求收起：会话结束时车机仪表本来就要还给原厂
     * （`CarAppController` 的 `SessionEnded` → `stopCluster()`），本类再补一刀只会
     * 在"已经拆完窗"之后再跑一遍拆窗逻辑。要清的是**本类的记忆**：定时器与门。
     */
    fun disconnect() {
        unsubscribe?.invoke()
        unsubscribe = null
        com.dbtool.carplay.api.display.ClusterProjectionIntent.resetSession()
        synchronized(lock) {
            cancelTimeout(timeoutCheck)
            inactiveSinceMs = null
            requestedShown = null
            routeActiveSeen = false
            routeEverActive = false
            gate = newGate()
        }
    }

    /**
     * 设置「导航时自动投屏」开关（设置 → 仪表导航 → 导航时自动投屏）。**立即生效**。
     *
     * - **关掉**：若此刻正由本链路投着（[CarClusterCruiseGate.modeActive]），主动请求
     *   收起一次 —— 只把开关置 false 是不够的，那会变成"开关关了、仪表还亮着"。
     *   之后不再因导航自动投（但订阅与门都留着，见 [enabled]）。
     * - **打开**：按最近一帧的导航状态补一次，该显示的一定显示（否则要等下一次
     *   iPhone 推帧才出现，而路口等灯时可能几秒都没有新帧）。
     */
    fun setEnabled(enabled: Boolean) {
        val note = synchronized(lock) {
            if (this.enabled == enabled) return
            this.enabled = enabled
            if (enabled) {
                // ④（PATCH-0121）：判据从"最近一帧活跃"放宽到"本会话见过导航"。
                // 只看前者时，**导航早开始、iPhone 已经不再推帧（静默期已过）**的情况下
                // 拨开开关 = 什么都不做 —— 现场表现就是"开关像坏了"。
                // 本条判据无法排除"导航其实已经结束"，但两种情形在本类**分不开**；
                // 而用户此刻是显式要求"导航时自动投屏"，故按仍在导航补投一次：
                // 补错了也只是多一块地图窗（设置页按钮可收回），
                // 总比"开关拨了没反应"可控。
                if (!routeActiveSeen && !routeEverActive) return
                // 重造门：关掉期间门里的 `routeActive` 可能是 true（那时导航仍在进行），
                // 直接调 `onRouteActive(true)` 会被当成"没变化"吞掉 ⇒ 打开了却不投。
                gate = newGate()
                gate.onRouteActive(true)
                syncRequest()
                return
            }
            // 关掉：静默期计时已经没有意义，连定时任务一起清干净。
            cancelTimeout(timeoutCheck)
            inactiveSinceMs = null
            // 本链路此刻"是投屏中"= 上一次发出去的请求是显示。
            val wasShown = requestedShown == true
            // 干净的门：重新打开时首帧不会被"没变化"吞掉（理由同上）。
            gate = newGate()
            // 结论从"显示"翻成"收起"时，这一句正好把**收起**请求发出一次；
            // 没投着就什么都不发（幂等）。
            // 这一句同时也是给落点的**状态回报**（"本链路不再持屏" ⇒ 拆窗 + 复位界面态），
            // 与 [onTimeoutFired] 那一处走同一条通道，见 PATCH-0121 ③ 的结论：
            // 落点本来就有复位入口，不另造回报通道。
            syncRequest()
            if (wasShown) "导航自动投屏：已关闭，并请求收起当前画面" else "导航自动投屏：已关闭"
        }
        note?.let(log)
    }

    /**
     * 本链路的只读快照（设置页 / 诊断用）。
     *
     * 与 [CarPlayNavigationHudClient.status] 同一口径：**只读、便宜、不阻塞**，
     * 并包一层 `runCatching` —— 排障界面自己不能成为新的崩溃源。
     */
    fun status(): CarClusterRouteStatus = runCatching {
        synchronized(lock) {
            CarClusterRouteStatus(
                subscribed = unsubscribe != null,
                enabled = enabled,
                routeActive = routeActiveSeen,
                shown = gate.modeActive,
                silentMillis = inactiveSinceMs?.let { clock() - it } ?: 0L,
            )
        }
    }.getOrDefault(CarClusterRouteStatus())

    // ------------------------------------------------------------------ internals

    private fun onState(state: CarNavigationState?) {
        // ⚠️ `null` = "导航不活跃"，**不等于**"用户明确结束了导航"（见类注释的表格）。
        // 所以这里只更新门的"路线是否进行中"，收屏一律走超时判定。
        handleRouteActive(state?.routeActive == true)
    }

    private fun handleRouteActive(active: Boolean) {
        routeActiveSeen = active
        // 只升不降：它记的是"这一段导航曾经真的跑过"，供 [setEnabled] 补投时判定。
        if (active) routeEverActive = true
        val note = synchronized(lock) {
            // 前置条件**每帧现读**：用户可能在会话中途才打开总闸「仪表地图投屏」或选好副屏，
            // 而门不会自己拉取 —— 不在这里喂，就会出现"设置都开好了、导航也在跑，仪表还是不投"
            // 这种没有任何报错的静默失败。顺带：前置条件本身的变化也可能翻结论（刚选好屏），
            // 所以下面每条分支末尾都要 `syncRequest()`。
            gate.onEnabled(isProjectionEnabled())
            gate.onDisplayAvailable(isDisplayAvailable())
            if (active) {
                // 恢复活跃：静默期作废（这是"路口等灯所以几秒没帧"的正常收尾）。
                inactiveSinceMs = null
                cancelTimeout(timeoutCheck)
                if (enabled) gate.onRouteActive(true)
                syncRequest()
                null
            } else if (inactiveSinceMs != null) {
                // 已经在静默计时里：这一帧什么都不改（判定交给超时任务）。
                syncRequest()
                null
            } else {
                // 非活跃：**不立刻收屏**，只记下从什么时候开始静默，交 [timeoutCheck] 判定。
                inactiveSinceMs = clock()
                postTimeout(NAV_STOP_GRACE_MS, timeoutCheck)
                syncRequest()
                "导航转为不活跃：${NAV_STOP_GRACE_MS}ms 内没有新帧才当作「明确结束」"
            }
        }
        note?.let(log)
    }

    /** 静默期的判定：真的停够 [NAV_STOP_GRACE_MS] 才算"明确结束导航"。 */
    private fun onTimeoutFired() {
        val note = synchronized(lock) {
            // 已经恢复活跃（或已断开）⇒ 这个任务作废。
            val since = inactiveSinceMs ?: return@synchronized null
            val silentFor = clock() - since
            if (silentFor < NAV_STOP_GRACE_MS) {
                // 被提前唤醒（定时器与注入的时钟不同源）⇒ 补排剩余时长，别提前收屏。
                postTimeout(NAV_STOP_GRACE_MS - silentFor, timeoutCheck)
                return@synchronized null
            }
            inactiveSinceMs = null
            if (!enabled) return@synchronized null
            gate.onRouteEndedExplicitly()
            syncRequest()
            // 上面这一句（收屏请求）就是本链路给落点的**状态回报**：落点
            // （`CarAppController.hideClusterProjection`）据此拆窗 + 复位界面态。
            // 本链路不再另造一套"我不持屏了"的通道（PATCH-0121 ③ 的结论）。
            "导航已持续不活跃 ${silentFor}ms ⇒ 当作「明确结束导航」"
        }
        note?.let(log)
    }

    /**
     * 把门此刻的结论落成**一次**显隐请求。
     *
     * 幂等靠 [requestedShown]：门的结论没变 ⇒ 一个字节都不发。iPhone 会以不规律频率
     * 反复推帧（同一毫秒推两次也有），不这样挡一下会变成"每帧都请求显示/收起"，
     * 落点那条链（建窗 `Presentation.show()` / 拆窗）最怕的就是反复重来。
     *
     * ⚠️ 还有一条**只属于本链路**的纪律：**从没请求过显示时，不许凭空发一次收起**
     * （[requestedShown] 为 `null`）。因为车机上那块画面可能根本不是本链路开的 ——
     * 用户可以点设置页的「投到仪表」按钮手动开屏，该操作**不受本开关约束**。
     * 会话刚开始（导航还没开始）时静默超时会走到这一步，不挡的话就是"连上手机 8 秒后，
     * 把用户通过设置页手动打开的投屏收掉"。
     */
    private fun syncRequest() {
        val wanted = gate.modeActive
        if (wanted && !com.dbtool.carplay.api.display.ClusterProjectionIntent.allowsAutomaticOpen()) return
        val previous = requestedShown
        if (wanted == previous) return
        if (previous == null && !wanted) return
        requestedShown = wanted
        runCatching { onVisibilityRequest(wanted) }.onFailure {
            // 落点是外来的（桥 / App 层），它抛异常不该把订阅回调这条 iAP2 线程带崩。
            requestedShown = null
            log("导航自动投屏：显隐请求抛出异常（已忽略）：${it.message}")
        }
    }

    companion object {

        /**
         * 「导航停」的容忍时长：持续不活跃超过它，才当作**明确结束导航**并收屏。
         *
         * ## 为什么是 8 秒
         *
         * 上游只在**有变化**时推帧：iPhone 在路口等灯、切源过渡、或单纯"这一秒没东西可报"
         * 时会有数秒静默，但导航并没有结束（`Iap2RouteState` 的 `STALE_ROUTE_NS` /
         * `pendingEndNs` 就是为这件事存在的，可它只给到"不活跃"这一层）。
         * 所以这个阈值要在两类错误之间取平衡：
         *
         * - **太短** ⇒ 路口等个灯就把仪表收掉，用户看到"导航还在、仪表没了"；
         * - **太长** ⇒ 用户明确结束导航后仪表还亮着，且这块画面是**从车机原厂
         *   DimMenu 手里抢来的**（`seizeCluster()`），亮着就是"原厂仪表用不了"。
         *
         * 8 秒的依据：实测的推帧间隔里绝大多数 < 3 秒（`CarPlayNavigationHudClient`
         * 的注释：59 个相邻间隔里 21 个 < 3 秒），等灯这类静默也在个位数秒级
         * ⇒ 8 秒留了一倍以上的余量，同时仍在"人能忍"的收屏延迟内。
         *
         * ⚠️ 这是本链路**唯一**需要人工判断的数值，改动它的后果只能上车看。
         */
        const val NAV_STOP_GRACE_MS = 8_000L
    }
}

/**
 * 导航自动投屏链路的只读状态快照（形状照 `CarHudStatus`：全字段带默认值，
 * 无参构造 = "什么都没发生"）。
 */
data class CarClusterRouteStatus(
    /** 是否已订阅上游导航状态。 */
    val subscribed: Boolean = false,
    /** 「导航时自动投屏」开关是否开着。 */
    val enabled: Boolean = true,
    /** 最近一帧上游给的"导航是否进行中"。 */
    val routeActive: Boolean = false,
    /** 本链路此刻的结论："该显示"为 true。 */
    val shown: Boolean = false,
    /** 已持续不活跃的毫秒数（0 = 正在导航 / 没在计时）。 */
    val silentMillis: Long = 0L,
)

/**
 * 默认定时器用的主线程 [Handler]。
 *
 * 放**文件级**而不是类里：构造参数的默认值在 `this` 可用的更早时刻求值，
 * 引用伴生对象成员容易踩"Companion 还没初始化"的坑。`by lazy` 保证单测
 * （注入自己的 `postTimeout`）根本不会碰到 `Looper`。
 */
private val clusterRouteMainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }
