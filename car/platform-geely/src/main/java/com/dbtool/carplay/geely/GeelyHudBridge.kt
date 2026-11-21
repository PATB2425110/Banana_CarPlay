package com.dbtool.carplay.geely

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import android.util.Log
import com.dbtool.carplay.api.navigation.CarHudBridge
import com.dbtool.carplay.api.navigation.CarHudGuidance
import com.dbtool.carplay.api.navigation.CarHudStatus
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 吉利车机的导航转向提示桥 —— 把 iPhone 的 CarPlay 导航推给车机侧的
 * **百度导航 hudbridge 服务**。
 *
 * ## 协议来源
 *
 * 完整实证见 `.ai/HUD-BRIDGE-REVERSE.md`（由 `GeelyHudAdapter` 的 smali 反编译而来）。
 * 关键事实：
 *
 * | 项 | 值 |
 * | --- | --- |
 * | action | `com.stewie.carplay.hudbridge.BIND` |
 * | 描述符 | `com.stewie.carplay.hudbridge.ICarPlayHudBridge` |
 * | 组件 | `com.baidu.naviauto` 或 `com.autonavi.amapauto` /
 *   `com.baidu.naviauto.hudbridge.CarPlayHudBridgeService`（双目标见 [HudTargetResolver]） |
 * | transaction codes | `1` 传统导航 / `2` 停止 / `12` 清车道 / `10` 现代导航 |
 * | magic | `0x48445532`（"HDU2"） |
 *
 * ⚠️ 组件包名原版写成 `com.autonavi.amapauto`，**PATCH-0111 起改成 `com.baidu.naviauto`**
 * （见 [PACKAGE] 的注释：跟的是 dbplay v40 现场补丁，车机日志证明改后能连上）。
 * 2026-10-18 起**两个包都探测**：百度优先、高德回退；绑不上自动换另一个候选
 * （目标车 1.1.15 日志里"车机没有该服务"就是写死百度造成的）。
 *
 * ## 为什么用裸 Parcel 而不是 AIDL
 *
 * 对面不是 AIDL 生成的 Stub，是**手写 Parcel + `IBinder.transact`**。
 * 描述符与字段顺序都取自反编译，任何一处对不上，服务会静默拒绝（返回 false 或抛异常），
 * 而**不会**给出有用的错误信息。所以本类里字段顺序**必须与台账逐行一致**。
 *
 * ## 实车状态（PATCH-0112）
 *
 * 以下为2026-10-13旧版现场记录，不代表本轮领克配套版本已实车验证。
 * 2026-11-13配套版使用code13身份校验、独立串行发送线程、2秒时间戳心跳，
 * 来源有效期只由真实输入更新；停止屏障不与空队列混同。字段编码已抽出单测。
 *
 * - 上表里的**事务码、组件包名、`writeLegacy()` 的字段布局都属于"已实证"**，
 *   不要凭"看起来不对"去改（详见 [writeLegacy] 与 [PACKAGE] 的注释）；
 * - 出问题时**先看日志再改代码**：成功发帧与失败都有单行留痕（见 [dispatch]），
 *   两者能区分"链路没跑"与"车机拒收"；
 * - 唯一的**回退入口**是 [PACKAGE]（换非吉利/非百度车机时第一个回退它）。
 *
 * ## 生命周期与容错
 *
 * - 绑定用 `BIND_AUTO_CREATE`；`binding || bound || closed` 任一为真即跳过（幂等）。
 * - **PATCH-0111 补齐"绑定卡死"的出口**：`onServiceDisconnected` 原来只复位 `bound`，
 *   而幂等判据含 `binding` ⇒ 绑定请求发出但服务从未连上时 `binding` 停在 true，
 *   此后永远不再补绑。现同时复位 `binding`，并补上 `onBindingDied` / `onNullBinding`
 *   两个回调（现场实测服务会走"静默绑定失败"，只回调 `onBindingDied`）。
 * - **绑不上是常态**（没装高德车机版 / 服务被禁 / 用户没开 HUD）⇒ 绝不抛异常，
 *   只把 [isAvailable] 报成 false，上层据此跳过。
 * - 所有 Parcel/binder 与连接回调状态都排在自己的串行工作线程，不阻塞UI主线程。
 * - [stop] 解绑并置 `closed`；此后不再发送。⚠️ **2026-10-13 起 [stop] 不再是一次性的**：
 *   [start] 会复位 `closed` 允许重新启用（见 [start] 的注释里那个"关一次就永久失效"的坑）。
 *
 * ## 状态回显（PATCH-0114，2026-10-13）
 *
 * [status] 把"到底往哪儿发、绑上没绑上、发了几帧、最近一帧是什么"读出来给设置页
 * （「仪表导航 → HUD 导航推送」）。它**只读内存字段**、不碰 binder ——
 * 契约要求见 `CarHudBridge.status()`。
 *
 * ⚠️ 那几项数字不是装饰：仪表上没有转向卡片时，它们能直接区分
 * "没绑上服务" / "绑上了但我们一帧没发" / "发了但车机拒收"，不必等诊断包。
 *
 * ## 与反编译原版的差异（刻意为之）
 *
 * 1. **不做 `modernBridge` 记忆式探测的持久化**：原版跨会话记住"该服务只支持 code 1"。
 *    我们每次 start 重新探测一次——多一次 transact 换"服务升级后能自动用上新格式"。
 * 2. 车道通过 code11 发完整位置/方向/推荐角度，空列表通过 code12 关闭。
 *    接收/JSON 能力由配套桥实现；Binder 受理不等于已经实车渲染。
 * 3. **state 全空时不发送**：原版会发一个空 payload，我们选择不发，
 *    避免车机仪表显示"0 米 0 分钟"。
 */
class GeelyHudBridge(
    context: Context,
) : CarHudBridge, AutoCloseable {

    private val appContext = context.applicationContext
    // Binder 往返绝不能占住车机主线程；整个发送/计时队列放在单独的串行线程。
    private val mainHandler = Handler(android.os.HandlerThread("DBPlay-HudSender").apply { start() }.looper)
    private val updateLock = Any()
    private val lifecycleEpoch = HudLifecycleEpoch()
    @Volatile private var activeEpoch = 0L
    private var updateQueued = false
    private val navigationState = HudNavigationState()
    private val stopBarrier = HudStopBarrier()
    private var lastWireAtMs: Long? = null
    private var lastSentLanes: List<com.dbtool.carplay.api.navigation.CarHudLane>? = null
    // Optional extension: basic navigation is connected and replayed BEFORE probing.
    // A missing/failed code14 must never reject the basic code13 contract.
    private var laneCapabilityAvailable = false
    private var lastInputDiagnostic: String? = null
    private var lastInputDiagnosticAt = 0L

    private fun logInput(guidance: CarHudGuidance?, decision: String) {
        val detail = "decision=$decision active=${guidance != null} maneuver=${guidance?.maneuverType} " +
            "index=${guidance?.maneuverIndex} lanes=${guidance?.lanes?.size ?: 0} " +
            "generation=$generation laneCapability=$laneCapabilityAvailable"
        val now = SystemClock.elapsedRealtime()
        synchronized(updateLock) {
            if (detail == lastInputDiagnostic && now - lastInputDiagnosticAt < 5_000L) return
            lastInputDiagnostic = detail
            lastInputDiagnosticAt = now
        }
        Log.i(TAG, "原车仪表卡片 input $detail")
    }
    private val updateRunnable = Runnable { flushUpdates() }

    /** Exactly one periodic task. It never renews the source deadline or registers LBS listeners. */
    private val heartbeatRunnable: Runnable = object : Runnable {
        override fun run() {
            val epoch = activeEpoch
            if (!isCurrentEpoch(epoch)) return
            val now = SystemClock.elapsedRealtime()
            val due = lastWireAtMs?.let { now - it >= HUD_HEARTBEAT_INTERVAL_MILLIS } != false
            flushUpdates(replay = due, force = due, epoch = epoch)
            if (isCurrentEpoch(epoch)) mainHandler.postDelayed(this, HUD_HEARTBEAT_INTERVAL_MILLIS)
        }
    }

    private fun isCurrentEpoch(epoch: Long): Boolean =
        !closed.get() && activeEpoch == epoch && lifecycleEpoch.isCurrent(epoch)

    private fun flushUpdates(replay: Boolean = false, force: Boolean = false, epoch: Long = activeEpoch) {
        val batch = synchronized(updateLock) {
            if (!isCurrentEpoch(epoch)) return
            updateQueued = false
            mainHandler.removeCallbacks(updateRunnable)
            navigationState.takeForSend(SystemClock.elapsedRealtime(), replay)
        }
        if (!isCurrentEpoch(epoch)) return
        // A null stop is an event, not "no pending value". Keep it before a new route.
        if (batch.stop || stopBarrier.isPending || (replay && batch.guidance == null)) {
            if (!stopGuidance(clearSource = false)) {
                // Do not let the next route cross an unacknowledged stop, even after rebind.
                if (isCurrentEpoch(epoch)) ensureBound()
                return
            }
        }
        batch.guidance?.let { dispatch(it, force = force, epoch = epoch) }
    }

    @Volatile
    private var binder: IBinder? = null

    private val binding = AtomicBoolean(false)
    private val bound = AtomicBoolean(false)
    private val closed = AtomicBoolean(true)

    /**
     * 服务是否支持现代格式（transaction code 10）。
     *
     * `null` = 尚未探测；`false` = 已知只支持 code 1，别再试 code 10。
     */
    @Volatile
    private var modernBridge: Boolean? = null

    @Volatile
    private var onAvailability: ((Boolean) -> Unit)? = null

    @Volatile
    private var requestId = 0L

    /** 路线代次：`(sessionId, revision)` 变了就 +1，告诉车机"换路了"。 */
    @Volatile
    private var generation = 0L

    @Volatile
    private var routeKey: Pair<Long?, Long?>? = null

    // ------------------------------------------------------- 状态回显（PATCH-0114）
    // 只给 `status()` 读。刻意都是 `@Volatile` 简单值：`status()` 会在渲染路径上被调，
    // 不能加锁、不能做 binder 调用。字符串都**短**（单行、可读），因为界面那一行
    // 最多显示三行（`CarSettingRow` 的 `maxLines = 3`）。

    /** 最近一次失败的中文说明；空 = 没失败过。 */
    @Volatile
    private var lastFailure: String = ""

    /**
     * 目标组件的存在性探针结论（PATCH-0117，2026-10-13）。空 = 还没探过。
     *
     * 见 [probeTargetIfNeeded]：它区分"车机没装那个服务"与"装了但这次没绑上" ——
     * 这两种成因在诊断包里原先**长得一模一样**（都只有一句 `HUD 桥可用性：false`），
     * 而处置完全不同（换通道 vs 重试）。
     */
    @Volatile
    private var targetProbe: String = ""

    /**
     * 本次会话探测到的目标（[HudTargetResolver.resolve] 的结果）。`null` = 还没探过，
     * 或两个包都不可解析。**只在本会话内复用**，[stop] 时清空 ⇒ 下次会话重新探测
     * （目标车开机后服务可能晚于本应用起来）。
     */
    @Volatile
    private var resolvedTarget: HudTarget? = null

    /** 探测过没有。单独一个标志：`resolvedTarget == null` 是合法结论，不能拿它当"没探过"。 */
    @Volatile
    private var probedThisSession: Boolean = false

    /**
     * 这一次会话里**真正绑成功过**的目标。重连时优先用它（服务刚重启时另一个包的
     * 服务可能也刚好在，但上次能连上的那个更可信）。内存字段，进程重启即丢。
     */
    @Volatile
    private var preferredTarget: HudTarget? = null

    /** 当前正在绑定/已绑上的目标。`status().target` 与回退日志都看它。 */
    @Volatile
    private var activeTarget: HudTarget? = null

    /**
     * 本轮绑定里每个候选的失败原因（`候选 → 中文原因`）。
     *
     * 只在主线程读写；**两个候选都失败**时由 [finishFailure] 拼成一行写进
     * [lastFailure]，避免设置页只看到"最后试的那个"而不知道另一个为什么也不行。
     */
    private val attemptFailures = LinkedHashMap<HudTarget, String>()

    /** 上一次推给上层的可用性，用于**去重**（见 [notifyAvailability]）。 */
    @Volatile
    private var lastNotifiedAvailability: Boolean? = null

    /** 最近一帧**被车机受理**的单行摘要；空 = 还没推成功过。 */
    @Volatile
    private var lastPush: String = ""

    /** 累计**被车机受理**的帧数。 */
    @Volatile
    private var pushedFrames: Long = 0

    /** 最近一帧的转向文案（中文，如"左转"）；空 = 还没有受理过帧。 */
    @Volatile
    private var lastManeuverText: String = ""

    /** 最近一帧距下一个转向点的距离（米）；`null` = 手机没给。 */
    @Volatile
    private var lastSegmentMeters: Int? = null

    /**
     * 来源失活窗口由HudNavigationState的单调时钟管理；心跳绝不续来源期限。
     */

    /**
     * 服务尚未连接时收到的最后一帧有效导航。
     *
     * 对应反编译 HudBridge 客户端的 `current`：`onServiceConnected` 要重放它，
     * `onServiceDisconnected` 用它决定是否重新绑定。见 [HudPendingGuidance]。
     */
    /** Transport timestamp is not content. Reset only when the receiving connection changes. */
    private var lastAcceptedGuidance: CarHudGuidance? = null
    private var stopPublished = false

    /** 断线重连的退避任务（主线程）。 */
    private val reconnectRunnable = Runnable {
        if (!closed.get() && binder == null && navigationState.fresh(SystemClock.elapsedRealtime()) != null) {
            bindIfNeeded()
        }
    }


    private var bindingToken = 0L
    private var identityRejected = false
    private var nextBindAtMs = 0L
    private var connection: ServiceConnection? = null

    private fun newConnection(token: Long, epoch: Long) = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            Log.i(TAG, "原车仪表卡片 connection event=connected token=$token epoch=$epoch")
            mainHandler.post {
            if (token != bindingToken) return@post
            if (!isCurrentEpoch(epoch)) return@post
            // 回调可能晚于 stop()：此时解绑任务已经排进 handler，若还记账会留下
            // 一条再也没人解绑的连接。直接放掉。
            if (closed.get()) {
                releaseBindingQuietly()
                return@post
            }
            bound.set(true)
            binding.set(false)
            val compatible = isPairedLynkBridge(service)
            if (compatible != true) {
                binder = null
                identityRejected = compatible == false
                lastFailure = if (identityRejected) "踏板基础导航版本不匹配：请安装配套领克踏板"
                    else "领克踏板身份读取暂时失败，稍后重试"
                bound.set(false)
                binding.set(false)
                releaseBindingQuietly()
                if (!identityRejected) {
                    nextBindAtMs = SystemClock.elapsedRealtime() + HUD_RECONNECT_DELAY_MS
                    mainHandler.removeCallbacks(reconnectRunnable)
                    mainHandler.postDelayed(reconnectRunnable, HUD_RECONNECT_DELAY_MS)
                }
                notifyAvailability(false)
                Log.w(TAG, lastFailure)
                return@post
            }
            binder = service
            laneCapabilityAvailable = false
            lastAcceptedGuidance = null
            stopPublished = false
            modernBridge = null
            lastSentLanes = null
            bound.set(true)
            binding.set(false)
            val connected = activeTarget
                ?: preferredTarget
                ?: HudTarget(name.packageName, name.className)
            activeTarget = connected
            preferredTarget = connected
            // 绑上了 ⇒ 本轮失败记录作废：下次断开重连时两个候选都应重新有机会。
            attemptFailures.clear()
            // 连上就清掉上一次的失败说明（PATCH-0114）：设置页那一行显示的是
            // "**最近一次**失败"，一直挂着一条早就自愈的旧错误会把人带偏。
            lastFailure = ""
            Log.i(TAG, "基础导航接口已匹配；先启用并重放导航，车道能力单独探测")
            Log.i(TAG, "已连上 HUD 桥服务：$name")
            notifyAvailability(true)
            transact(CODE_ENABLE) { it.writeInt(1) }
            // 对齐反编译原版：连接成功先重放最后一帧；没有可重放的帧才发停止。
            flushUpdates(replay = true, force = true)
            laneCapabilityAvailable = HudBridgeCapabilities.fromTags(
                HudBridgeCapabilities.IDENTITY_TAG,
                readBridgeTag(service, CODE_LANE_CAPABILITY),
            ).lanes
            Log.i(TAG, "可选车道能力：$laneCapabilityAvailable（不影响基础导航）")
            if (laneCapabilityAvailable) flushUpdates(replay = true, force = true)
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            Log.i(TAG, "原车仪表卡片 connection event=disconnected token=$token epoch=$epoch")
            mainHandler.post {
            if (token != bindingToken) return@post
            if (!isCurrentEpoch(epoch)) return@post
            binder = null
            laneCapabilityAvailable = false
            bound.set(false)
            // PATCH-0111（移植自 dbplay v40）：原来只复位 `bound`，而 `bindIfNeeded()`
            // 的幂等判据是 `binding || bound`。`binding` 若停在 true（绑定请求发出但
            // 服务从未连上），此后所有补绑都会被跳过 ⇒ HUD 永久不可用。
            binding.set(false)
            lastFailure = "服务断开（车机侧服务重启或被回收），下次发帧会自动重绑"
            // 保留 preferredTarget：重连优先还回原目标；activeTarget 清掉，
            // 让 status 回落到 preferred（"当前实际目标"仍是它）。
            activeTarget = null
            Log.i(TAG, "HUD 桥服务断开：$name")
            notifyAvailability(false)
            // 对齐反编译原版：仍有一帧有效导航就重新 bind，而不是干等下一帧。
            // 3 秒退避，避免服务正在重启时形成 bind/unbind 抖动。
            if (navigationState.fresh(SystemClock.elapsedRealtime()) != null) {
                mainHandler.removeCallbacks(reconnectRunnable)
                mainHandler.postDelayed(reconnectRunnable, HUD_RECONNECT_DELAY_MS)
            }
            releaseBindingQuietly()
            }
        }

        /**
         * PATCH-0111（移植自 dbplay v40）：**静默绑定失败**的回调。
         *
         * 现场实测（车机日志）：`bindService()` 返回 true 之后服务可能**始终不回调**
         * `onServiceConnected`，只在最后回调 `onBindingDied`。原版不实现这个回调，
         * 于是 `binding` 永远停在 true ⇒ 再也绑不上。这里复位两个标志，允许重试。
         */
        override fun onBindingDied(name: ComponentName) {
            Log.i(TAG, "原车仪表卡片 connection event=binding_died token=$token epoch=$epoch")
            mainHandler.post {
            if (token != bindingToken) return@post
            if (!isCurrentEpoch(epoch)) return@post
            val failed = activeTarget
            bound.set(false)
            binding.set(false)
            binder = null
            activeTarget = null
            releaseBindingQuietly()
            lastFailure = "静默绑定失败（服务从未回调 onServiceConnected）"
            Log.i(TAG, "HUD 桥绑定已失效（静默绑定失败）：$name")
            switchToNext(failed, "静默绑定失败（服务从未回调 onServiceConnected）")
            }
        }

        /**
         * PATCH-0111（移植自 dbplay v40）：服务 `onBind()` 返回 null 时触发。
         *
         * 语义与 [onBindingDied] 相同——复位标志、允许重试。
         */
        override fun onNullBinding(name: ComponentName) {
            Log.i(TAG, "原车仪表卡片 connection event=null_binding token=$token epoch=$epoch")
            mainHandler.post {
            if (token != bindingToken) return@post
            if (!isCurrentEpoch(epoch)) return@post
            val failed = activeTarget
            bound.set(false)
            binding.set(false)
            binder = null
            activeTarget = null
            releaseBindingQuietly()
            lastFailure = "服务 onBind 返回 null"
            Log.i(TAG, "HUD 桥 onBind 返回 null：$name")
            switchToNext(failed, "服务 onBind 返回 null")
            }
        }
    }

    override fun isAvailable(): Boolean = binder != null

    /**
     * 本桥的实时状态（PATCH-0114，设置页「仪表导航」用）。只读内存字段，不碰 binder。
     *
     * [target] 用 `包名/服务类名` 的形式（与 `ComponentName.flattenToString()` 同形）：
     * 换车机后第一个要核对的就是它，而 [PACKAGE] 正是唯一的回退入口
     * （见那边"如果换成非吉利/非百度的车机后 HUD 连不上，第一个要回退的就是这一行"）。
     */
    override fun status(): CarHudStatus = CarHudStatus(
        supported = true,
        available = binder != null,
        connecting = binding.get() && binder == null,
        target = currentTargetDisplay(),
        targetPackage = currentTarget()?.packageName.orEmpty(),
        lastFailure = lastFailure,
        lastPush = lastPush,
        pushedFrames = pushedFrames,
        lastManeuverText = lastManeuverText,
        lastSegmentMeters = lastSegmentMeters,
    )

    /** 优先级：正在绑/绑上的 → 上次绑成功的 → 本次探测到的。 */
    private fun currentTarget(): HudTarget? = activeTarget ?: preferredTarget ?: resolvedTarget

    /**
     * `status().target`：`包名/服务类名`。
     *
     * 没解析到任何候选时回显第一候选 —— 那是"我们会往哪儿发"的默认答案；
     * "到底解析到没有"由 [CarHudStatus.targetPackage]（空 = 未解析到）单独表达，
     * 界面不会拿这个兜底值冒充"已解析"。
     */
    private fun currentTargetDisplay(): String {
        val target = "$PACKAGE/$SERVICE"
        return currentTarget()?.flatName ?: target
    }

    override fun start(onAvailabilityChanged: (Boolean) -> Unit) {
        // ⚠️ 2026-10-13（PATCH-0114）：**允许在 stop() 之后重新启用**。
        //
        // 原来这里是 `if (closed.get()) return`，而 `closed` 由 `stop()` 置位且**永不复位**。
        // 而 `CarPlaySessionBridge` 在**每次会话 stop 时**都会调 `hudClient.disconnect()`
        // ⇒ `bridge.stop()`；平台实例又是进程内唯一的（`GeelyCarPlatform.hud` 是 `by lazy`）
        // ⇒ 一旦"断开 → 再连接"，本桥在本进程内**永久失效**：
        // 订阅照旧建立、`update()` 却全部在第一行被丢掉，现场表现是
        // **"仪表再也没出现过转向卡片"**，且没有任何报错（§128 那一类最难查的形态）。
        //
        // 契约 `CarHudBridge.stop()` 的原文是"结束推送并解绑"，**没有**"不可再启动"的语义，
        // 所以这里按契约复位。
        synchronized(updateLock) {
        val epoch = if (closed.getAndSet(false)) lifecycleEpoch.next() else lifecycleEpoch.current()
        mainHandler.post {
            if (!lifecycleEpoch.isCurrent(epoch) || closed.get()) return@post
            activeEpoch = epoch
            identityRejected = false
            nextBindAtMs = 0L
            mainHandler.removeCallbacks(reconnectRunnable)
            mainHandler.removeCallbacks(heartbeatRunnable)
            mainHandler.postDelayed(heartbeatRunnable, HUD_HEARTBEAT_INTERVAL_MILLIS)
            // ⚠️ 回调必须在 post 里赋值，不能同步赋值：`stop()` 也往同一个 handler 排了
            // "清回调 + 解绑"的任务，而这里按提交顺序排在它之后 —— 同步赋值会被它随后清掉
            // （"先 stop 再 start"时后者必须赢）。
            onAvailability = onAvailabilityChanged
            // PATCH-0117：**新会话至少收到一次当前值**。
            // 去重记的是"上一次推出去的值"，若不复位，上一会话已经报过 false，
            // 本会话的首次 false 就会被吃掉 ⇒ 新会话的诊断日志里反而没有这一行
            // （比不去重更糟：那是"该有的信息消失"，不是"多了一行噪声"）。
            lastNotifiedAvailability = null
            bindIfNeeded()
        }
        }
    }

    override fun stop() {
        synchronized(updateLock) {
        if (closed.compareAndSet(false, true)) {
                lifecycleEpoch.next()
                mainHandler.removeCallbacks(updateRunnable)
                navigationState.clear()
                updateQueued = false
            mainHandler.post {
                laneCapabilityAvailable = false
                onAvailability = null
                // 取消本会话唯一心跳，停止后不再产生周期发送。
                mainHandler.removeCallbacks(heartbeatRunnable)
                mainHandler.removeCallbacks(reconnectRunnable)
                // Disconnecting must publish the factory stop event before unbinding.
                // closed only blocks future updates; transact remains usable here.
                stopGuidance(clearSource = false)
                unbindIfNeeded()
                // 探测结果只在"本次会话"内有效：下次 start 重新探测（服务可能刚起来）。
                resolvedTarget = null
                probedThisSession = false
                activeTarget = null
                attemptFailures.clear()
                targetProbe = ""
            }
        }
        }
    }

    override fun close() = stop()

    override fun update(guidance: CarHudGuidance?) {
        if (closed.get()) { logInput(guidance, "drop_closed"); return }
        if (guidance != null && !guidance.hasContent) { logInput(guidance, "drop_empty"); return }
        if (guidance != null && LynkHudNormalizer.normalize(guidance) == null) {
            logInput(guidance, "drop_no_valid_turn")
            return
        }
        logInput(guidance, "enqueue")
        synchronized(updateLock) {
            if (closed.get()) return
            navigationState.offer(guidance, SystemClock.elapsedRealtime())
            if (!updateQueued) {
                updateQueued = true
                mainHandler.postDelayed(updateRunnable, if (guidance == null) 0L else HUD_UPDATE_INTERVAL_MILLIS)
            } else if (guidance == null) {
                mainHandler.removeCallbacks(updateRunnable)
                mainHandler.post(updateRunnable)
            }
        }
    }

    // ------------------------------------------------------------- 绑定

    /**
     * 需要绑定时选目标并尝试绑定（幂等）。
     *
     * 目标选择顺序：本次会话已绑成功过的 [preferredTarget] → 本次会话探测到的
     * [resolvedTarget] → 第一候选。**探测只在本次会话第一次绑定时做一次**，
     * 之后复用（[stop] 清空缓存）。
     *
     * 绑定失败（返回 false / 抛异常 / `onNullBinding` / `onBindingDied`）会走
     * [switchToNext]，把另一个候选试一次；两个都失败时把每个候选的原因拼进
     * [lastFailure]。
     */
    private fun bindIfNeeded() {
        if (closed.get()) return
        if (!isCurrentEpoch(activeEpoch) || identityRejected || SystemClock.elapsedRealtime() < nextBindAtMs) return
        if (binding.get() || bound.get()) return
        if (!binding.compareAndSet(false, true)) return
        // 新一轮绑定：上一轮（可能是几秒前那一帧触发的）的失败记录不能带到这一轮，
        // 否则 switchToNext 会以为"两个都试过了"而少试一个候选。
        attemptFailures.clear()
        val target = chooseTarget()
        Log.i(TAG, "HUD 桥目标：${target.flatName}（探测=${targetSource(target)}）")
        bindTarget(target)
    }

    /** 本次会话第一次绑定：探测一次并把结论缓存进 [resolvedTarget]。 */
    private fun chooseTarget(): HudTarget {
        resolveIfNeeded()
        return preferredTarget ?: resolvedTarget ?: HudTargetResolver.CANDIDATES.first()
    }

    /**
     * 首次绑定时探测一次（无论是否已有 [preferredTarget]）。
     *
     * 即使这次用 preferred 先绑，也要探测：否则 preferred 失败时 [switchToNext]
     * 手里没有任何候选结论，只能盲目按固定顺序试，"探测=resolved" 这条日志
     * 也就永远打不出来。探测本身是几次 `PackageManager` 查询，每会话一次，可忽略。
     */
    private fun resolveIfNeeded() {
        if (probedThisSession) return
        probeTargetIfNeeded()
        resolvedTarget = HudTargetResolver.resolve(
            HudTargets.resolverFor(appContext.packageManager, ACTION),
        )
        probedThisSession = true
        val resolved = resolvedTarget
        if (resolved != null) {
            Log.i(TAG, "HUD 桥目标解析：${resolved.flatName}")
        } else {
            Log.i(
                TAG,
                "HUD 桥目标解析：没有可解析的候选" +
                    "（${HudTargetResolver.CANDIDATES.joinToString { it.packageName }}），仍尝试第一候选",
            )
        }
    }

    /** 日志里的"探测=…"：让现场一眼看出这次用的是记住的、解析到的，还是默认第一候选。 */
    private fun targetSource(target: HudTarget): String = when {
        preferredTarget == target -> "preferred"
        resolvedTarget == target -> "resolved"
        else -> "default"
    }

    /**
     * 真正调用 `bindService`。
     *
     * 失败时**不在这里**决定后续（[switchToNext] 负责回退），但会把原因
     * 写进 [attemptFailures] 与 [lastFailure]。
     */
    private fun bindTarget(target: HudTarget) {
        activeTarget = target
        val newConnection = newConnection(++bindingToken, activeEpoch)
        connection = newConnection
        val intent = Intent(ACTION).setComponent(
            ComponentName(target.packageName, target.serviceName),
        )
        val reason = runCatching {
            appContext.bindService(intent, newConnection, Context.BIND_AUTO_CREATE)
        }.fold(
            onSuccess = { ok ->
                if (ok) null else "车机没有该服务（bindService 返回 false）"
            },
            onFailure = { error ->
                // PATCH-0114：失败要留痕给设置页（"最近一次失败"那一行）。
                // 只报异常类名 + 截断的消息：完整堆栈进 logcat 就够了，
                // 而界面那一行最多三行（`CarSettingRow` 的 `maxLines = 3`）。
                Log.i(TAG, "绑定 HUD 桥服务失败（车机可能没装该服务）：$error")
                "绑定异常：" + error.javaClass.simpleName +
                    (error.message?.let { "（${it.take(60)}）" } ?: "")
            },
        )
        if (reason != null) {
            binding.set(false)
            bound.set(false)
            binder = null
            activeTarget = null
            lastFailure = reason
            switchToNext(target, reason)
        }
    }

    /**
     * 首选失败后自动换下一个候选**试一次**（2026-10-18）。
     *
     * 换目标的三种入口共用这里：`bindService` 返回 false / 抛异常（同步），
     * `onNullBinding`、`onBindingDied`（异步）。
     *
     * 排除判据是 [attemptFailures]：本轮已失败过的候选不再重试，因此最多
     * 两个候选各试一次；成功连上后 [attemptFailures] 清空，下一轮重新有机会。
     */
    private fun switchToNext(failed: HudTarget?, reason: String) {
        if (failed != null) attemptFailures[failed] = reason
        if (closed.get()) return
        // preferred 存在时首次绑定也会探测（见 [resolveIfNeeded]），这里只是兜底。
        resolveIfNeeded()
        val next = listOfNotNull(preferredTarget, resolvedTarget)
            .plus(HudTargetResolver.CANDIDATES)
            .firstOrNull { it !in attemptFailures }
        if (next == null) {
            finishFailure()
            return
        }
        Log.i(
            TAG,
            "HUD 桥切换目标：${failed?.packageName ?: "（无）"} 不可用（$reason）→ 尝试 ${next.packageName}",
        )
        activeTarget = null
        // 同步失败路径已经复位过 binding；异步回调路径也必须复位后才能 bind。
        binding.set(false)
        if (!binding.compareAndSet(false, true)) return
        Log.i(TAG, "HUD 桥目标：${next.flatName}（探测=${targetSource(next)}）")
        bindTarget(next)
    }

    /** 两个候选都失败：把每个候选的原因写进一行，给设置页与诊断包。 */
    private fun finishFailure() {
        binding.set(false)
        bound.set(false)
        activeTarget = null
        val detail = attemptFailures.entries.joinToString("；") {
            "${it.key.packageName}：${it.value}"
        }
        lastFailure = detail.ifEmpty { "车机没有该服务（bindService 返回 false）" }
        // PATCH-0117（2026-10-13）：**这条路径原先一个字都不打**。
        //
        // 结果就是：诊断包里只看得见上层那句 `HUD 桥可用性：false`，而
        // "车机没装那个服务" / "服务存在但拒绝绑定" / "绑定成功但从不回调"
        // 这三种成因在包里**长得一模一样**（日志 8/9 各 30+ 条 false，全无线索）。
        // 这里补一行，并带上上面那次探针的结论。
        Log.i(TAG, "HUD 桥绑定未成功：$lastFailure（候选探针 $targetProbe）")
        notifyAvailability(false)
    }

    /**
     * **只跑一次**的目标存在性探针（PATCH-0117，2026-10-13）。
     *
     * ## 为什么需要它
     *
     * `bindService()` 返回 false 只说明"这次没绑上"，它**不能**区分：
     *
     * - 车机压根没装 `com.baidu.naviauto`（HUD 推送到这台车上**永远**不可能生效）；
     * - 装了，但那个组件的 `exported`/权限/`<queries>` 声明让我们看不见它；
     * - 装了也能看见，但服务进程被禁/被杀。
     *
     * 三种成因的处置完全不同（换通道 / 加 queries / 等它起来），而现场只能靠上车去猜
     * —— 装车一次很贵。所以这里用 `PackageManager` 直接问一次，把结论写进诊断日志。
     *
     * 结论进 [targetProbe]（也被 [status] 之外的日志引用），**只算一次**：
     * 每次发帧都重算是纯浪费（`bindService` 现在每帧都在重试）。
     *
     * ⚠️ **本车是 Android 9**：`getPackageInfo()` 对别的包是直接可读的。
     * 在 Android 11+ 上包可见性受 `<queries>` 限制，`installed=false` **未必**等于真没装
     * —— 那时这一行只能当"我们从这里看不见它"用，别当"车机没有它"下结论。
     */
    private fun probeTargetIfNeeded() {
        if (targetProbe.isNotEmpty()) return
        val packageManager = appContext.packageManager
        val resolvable = HudTargets.resolverFor(packageManager, ACTION)
        // 第一候选按 PATCH-0117 的旧口径单独问一次：诊断包里
        // `installed=… resolvable=…` 是现场核对用的固定格式；per-candidate 列表跟在其后。
        val installed = runCatching { packageManager.getPackageInfo(PACKAGE, 0) != null }
            .getOrDefault(false)
        val primaryResolvable = runCatching {
            packageManager.resolveService(
                Intent(ACTION).setComponent(ComponentName(PACKAGE, SERVICE)),
                0,
            ) != null
        }.getOrDefault(false)
        targetProbe = "installed=$installed resolvable=$primaryResolvable；" +
            HudTargetResolver.CANDIDATES.joinToString("；") { target ->
                val candidateInstalled =
                    HudTargets.packageInstalled(packageManager, target.packageName)
                "${target.packageName}: installed=$candidateInstalled" +
                    " resolvable=${resolvable(target)}"
            }
        Log.i(TAG, "HUD 桥目标探针：$targetProbe")
        if (HudTargetResolver.CANDIDATES.none { resolvable(it) }) {
            Log.i(TAG, "HUD 桥目标探针：两个候选都不可解析（HUD 推送到本车可能不生效）")
        }
    }

    private fun unbindIfNeeded() {
        // `binding` 为 true 也要解：`bindService` 已受理但 `onServiceConnected` 还没来的
        // 情况下，连接是登记在系统里的，只复位本地标志会把它泄漏到进程结束。
        releaseBindingQuietly()
        binder = null
        bound.set(false)
        binding.set(false)
    }

    /**
     * 换目标前释放旧绑定。
     *
     * `onNullBinding` / `onBindingDied` 之后系统仍登记着这条 `ServiceConnection`；
     * 不先解绑就绑另一个组件，后续 `unbindService` 会抛 `IllegalArgumentException`，
     * 而且两个组件的回调会串到同一条连接上。这里吞掉"本来就没绑"的异常。
     */
    private fun releaseBindingQuietly() {
        ++bindingToken
        val oldConnection = connection
        connection = null
        if (oldConnection != null) {
            runCatching { appContext.unbindService(oldConnection) }
                .onFailure { Log.i(TAG, "释放旧 HUD 桥绑定：${it.javaClass.simpleName}") }
        }
    }

    /**
     * 把可用性告诉上层。**只在值变化时通知**（PATCH-0117，2026-10-13）。
     *
     * ## 为什么要去重
     *
     * 车机上这个服务绑不上是常态，而 [dispatch] 每收到一帧就会
     * `ensureBound()` → `bindIfNeeded()` → 失败 → 这里报一次 false。
     * 上层（`CarPlaySessionBridge`）把每次通知都写一行诊断日志 ⇒
     * 一次会话能刷出 **30+ 条** `HUD 桥可用性：false`（日志 8/9 实测），
     * 把"可用性真的变了"这个信号**淹掉**，也挤压诊断包的行数预算。
     *
     * 判据是"上一次推给上层的值"（不是 `binder != null`）：服务断开→重连这类
     * 真实变化照旧通知，只有"反复报同一个值"被吃掉。
     */
    private fun notifyAvailability(available: Boolean) {
        val callback = onAvailability ?: return
        if (lastNotifiedAvailability == available) return
        lastNotifiedAvailability = available
        runCatching { callback(available) }
            .onFailure { Log.w(TAG, "HUD 可用性回调异常", it) }
    }

    // ------------------------------------------------------------- 发送

    /** 在 binder 缺席时尝试补绑，返回 false 表示这一帧无法发送。 */
    private fun ensureBound(): Boolean {
        if (binder != null) return true
        bindIfNeeded()
        return binder != null
    }

    /**
     * 把一帧导航提示发给车机。
     *
     * `guidance == null` ⇒ 发"停止"事务（code 2），清掉车机侧的残留提示。
     */
    private fun dispatch(guidance: CarHudGuidance?, force: Boolean, epoch: Long) {
        if (!isCurrentEpoch(epoch)) return
        if (guidance == null) {
            stopGuidance()
            return
        }
        if (!guidance.hasContent) return
        val normalizedTurn = LynkHudNormalizer.normalize(guidance) ?: return
        // 输入已经保存在有界mailbox，连接成功可重放仍新鲜的状态。
        if (!ensureBound()) return
        val content = guidance.copy(updatedAtMs = 0L)
        if (!force && lastAcceptedGuidance == content && (!laneCapabilityAvailable || lastSentLanes == guidance.lanes)) return
        bumpGenerationIfRouteChanged(guidance)
        val request = ++requestId
        // Refresh only the wire timestamp; the monotonic source deadline is unchanged.
        val wireGuidance = guidance.copy(updatedAtMs = System.currentTimeMillis())
        if (!isCurrentEpoch(epoch)) return
        val accepted = sendModern(wireGuidance, normalizedTurn, request)
        if (accepted) {
            lastAcceptedGuidance = content
            lastWireAtMs = SystemClock.elapsedRealtime()
            stopPublished = false
            if (laneCapabilityAvailable && lastSentLanes != guidance.lanes) {
                if (sendLanes(wireGuidance)) lastSentLanes = guidance.lanes
            }
            // PATCH-0112：**成功路径也要留痕**（过去只有失败才打日志）。
            //
            // 为什么必须有这一行：车机侧是裸 Parcel + `transact`，**不给我们任何回执**
            // ——"车机到底被告知了什么"只能在发送端记。而实车确认"HUD 能用"之后，
            // 诊断包里 `GeelyHudAdapter` 一行都没有（只有失败才记），于是**无法区分**
            // "推成功了" 与 "整条链路压根没跑"（桥没绑上 / iPhone 没下发 / 去重全丢）。
            // 这一行让下一份诊断包能自证：推了几帧、每帧的距离/转向/两行文案是什么。
            //
            // 量级可控：上游 `CarPlayNavigationHudClient` 已按"内容变化"去重
            // （PATCH-0111）⇒ 行驶中约每秒一行，静止时零行。
            val summary = summarize(guidance, normalizedTurn, request)
            // PATCH-0114：同一份摘要顺手留给设置页（"最近一帧"那一行）——
            // 现场问"仪表上那行字到底是我们发的还是车机自己编的"时，这是唯一答案。
            lastPush = summary
            pushedFrames += 1
            // 新 HUD 版块要的是"人话"版本：转向文案 + 距转向点距离。
            lastManeuverText = maneuverLabel(guidance.maneuverType)
            lastSegmentMeters = guidance.segmentMeters
            // 心跳不重新安排自身、不重置来源有效期，也不重复发车道清空。
            if (!force) Log.i(TAG, "HUD 已受理本帧（$summary）")
        } else {
            Log.i(TAG, "HUD 未受理本帧（request=$request session=${guidance.updatedAtMs}）")
            // PATCH-0114：车机明确拒绝时也要留痕 —— 设置页显示"收到过帧但一帧都没受理"
            // 时，用户唯一能自己动手的就是看这一句。
            lastFailure = "车机未受理本帧（request=$request）"
        }
    }

    /**
     * 一帧导航提示的**单行摘要**，只用于日志（PATCH-0112）。
     *
     * 字段刻意取"车机实际会看到的那几个值"，而不是原始快照的全部字段：
     * 距离/剩余时间（第一行）、转向语义（箭头）、两行文案（当前路名 + 目的地）。
     * 现场比对"HUD 显示的和这里记的不一致"时，一眼就能定位是解码、映射还是车机侧的问题。
     *
     * `null` 与空串都写成"（空）"：现场要区分的是"有没有值"，不是"哪种空"。
     */
    private fun summarize(guidance: CarHudGuidance, turn: NormalizedHudTurn, request: Long): String =
        "request=$request generation=$generation " +
            "${guidance.routeMeters ?: "-"}米/${guidance.routeSeconds ?: "-"}秒 " +
            "转向=${turn.semantic}(${turn.baiduTurnKind}) 领克ID=${turn.expectedTurnId} " +
             "路名=${roadLabel(guidance.currentRoad)} 转入路=${roadLabel(guidance.nextManeuverRoad)} " +
             "目的地=${roadLabel(guidance.nextRoad)} 机动距离=${guidance.segmentMeters ?: "-"}"

    /**
     * 转向类型码 → 设置页用的中文文案。
     *
     * 与 [GeelyHudTurnMapping.semantic] 同一张表，只是给用户看：设置页里出现
     * `UTURN_LEFT` 这种英文枚举既不好读，也容易让人以为是车机返回的原始值。
     * 类型码分组与 [GeelyHudTurnMapping.semantic] 保持一致（那张表是实车验证过的）。
     */
    private fun maneuverLabel(type: Int?): String = when (type) {
        1, 20, 22, 52 -> "左转"
        2, 21, 23, 53 -> "右转"
        3 -> "直行"
        5 -> "沿路行驶"
        51 -> "高速变道"
        4, 18, 26 -> "掉头"
        6, 19 -> "进入环岛"
        7, in 28..46 -> "驶出环岛"
        10, 12, 24, 25, 27 -> "到达目的地"
        13 -> "靠左"
        14 -> "靠右"
        11 -> "出发"
        0, null -> "继续行驶"
        else -> "转向"
    }

    /** `null` / 空串 / 纯空白统一显示为"（空）"。 */
    private fun roadLabel(value: String?): String = if (value.isNullOrBlank()) "（空）" else value

    private fun bumpGenerationIfRouteChanged(guidance: CarHudGuidance) {
        // 换路判据用手机给的 (sessionId, revision)，与原版一致（台账 §6）。
        // 两者都为 null 表示无法判定 ⇒ 不 bump：少 bump 一次只是让车机
        // 把新路线当旧路线的更新，多 bump 一次却会让车机无谓重画。
        if (guidance.sessionId == null && guidance.revision == null) return
        val key = guidance.sessionId to guidance.revision
        if (routeKey != key) {
            routeKey = key
            generation += 1
            lastSentLanes = null // same arrows on a new route still need the new lane generation
        }
    }

    private fun sendModern(guidance: CarHudGuidance, turn: NormalizedHudTurn, request: Long): Boolean {
        // 已探测出只支持传统格式 ⇒ 直接走 code 1，不再浪费一次 code 10。
        if (modernBridge == false) {
            return transact(CODE_LEGACY_NAV) { writeLegacy(it, guidance, turn.baiduTurnKind) } == true
        }
        val modern = transact(CODE_MODERN_NAV) { parcel ->
            writeModern(parcel, guidance, turn, request)
        }
        if (modern == null && binder != null) {
            // code 10 不被支持 ⇒ 记住并降级。
            modernBridge = false
            Log.i(TAG, "HUD 服务不支持现代格式（code 10），改用传统格式（code 1）")
            return transact(CODE_LEGACY_NAV) { writeLegacy(it, guidance, turn.baiduTurnKind) } == true
        }
        if (modern != null) modernBridge = true
        return modern == true
    }

    private fun isPairedLynkBridge(service: IBinder): Boolean? {
        return try {
            val identity = readBridgeTag(service, CODE_IDENTITY)
            if (!service.isBinderAlive) return null
            val capabilities = HudBridgeCapabilities.fromTags(identity, null)
            capabilities.navigation
        } catch (error: Exception) {
            Log.w(TAG, "领克踏板身份校验未通过", error)
            null // transport failure is retryable, not a permanent version rejection
        }
    }

    private fun readBridgeTag(service: IBinder, code: Int): String? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            if (!service.transact(code, data, reply, 0)) null else {
                reply.readException()
                reply.readString()
            }
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /**
     * 所有发送的统一入口。
     *
     * @return `null` = 该事务码不被支持（或 binder 没了）；
     *   `true`/`false` = 服务明确受理/拒绝。异常一律归为 `null`。
     */
    private fun transact(code: Int, body: (Parcel) -> Unit): Boolean? {
        val target = binder ?: run {
            Log.i(TAG, "原车仪表卡片 transaction code=$code result=no_binder")
            return null
        }
        val request = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            request.writeInterfaceToken(DESCRIPTOR)
            body(request)
            if (!target.transact(code, request, reply, 0)) {
                Log.i(TAG, "原车仪表卡片 transaction code=$code transportAccepted=false")
                return null
            }
            reply.readException()
            val accepted = (reply.dataAvail() >= 4) && reply.readInt() != 0
            Log.i(TAG, "原车仪表卡片 transaction code=$code transportAccepted=true accepted=$accepted")
            accepted
        } catch (error: Exception) {
            Log.w(TAG, "HUD 事务 $code 失败", error)
            // PATCH-0114：异常也要留给设置页。`null` 的另一种含义（binder 没了）不记 ——
            // 那由 `ensureBound()` / 绑定回调那条路负责，混在一起会掩盖真正的原因。
            lastFailure = "HUD 事务 $code 异常：" + error.javaClass.simpleName
            null
        } finally {
            runCatching { reply.recycle() }
            runCatching { request.recycle() }
        }
    }

    private fun sendLanes(guidance: CarHudGuidance): Boolean {
        if (guidance.lanes.isEmpty()) {
            return clearLanes("no_lanes")
        }
        val sequence = requestId + 1
        Log.i(TAG, "原车仪表卡片 lane.send code=11 sequence=$sequence generation=$generation " +
            "count=${guidance.lanes.size} timestamp=${guidance.updatedAtMs}")
        val accepted = transact(11) { parcel ->
            LynkHudLaneWireCodec.write(
                guidance.lanes, ++requestId, generation, guidance.updatedAtMs,
                parcel::writeInt, parcel::writeLong, parcel::writeString,
            )
        }
        Log.i(TAG, "HUD 车道 lanes=${guidance.lanes.size} accepted=$accepted")
        return accepted == true
    }

    /** Explicit stop or monotonic source expiry; a stop barrier may precede a new snapshot. */
    private fun stopGuidance(clearSource: Boolean = true): Boolean {
        val stopToken = stopBarrier.request()
        mainHandler.removeCallbacks(reconnectRunnable)
        if (clearSource) navigationState.clear()
        lastAcceptedGuidance = null
        lastSentLanes = null
        lastWireAtMs = null
        routeKey = null
        if (stopPublished) {
            stopBarrier.acknowledge(stopToken)
            return true
        }
        val accepted = transact(CODE_STOP, body = {})
        stopPublished = accepted == true
        if (stopPublished) stopBarrier.acknowledge(stopToken)
        Log.i(TAG, "HUD 停止指引：${accepted ?: "服务未响应"}")
        return stopPublished
    }

    private fun clearLanes(reason: String): Boolean {
        Log.i(TAG, "原车仪表卡片 lane.clear code=12 reason=$reason generation=$generation")
        val accepted = transact(CODE_CLEAR_LANES, body = {})
        Log.i(TAG, "HUD 清空车道（$reason）：${accepted ?: "服务未响应"}")
        return accepted == true
    }

    // ------------------------------------------------------------- 正文格式
    // ⚠️ 以下字段顺序**逐行对应** `.ai/HUD-BRIDGE-REVERSE.md` §5。
    // 错一个字段后面全部错位，而服务不会给出有用的报错。

    /** §5.1 现代格式（transaction code 10）。 */
    private fun writeModern(
        parcel: Parcel,
        guidance: CarHudGuidance,
        turn: NormalizedHudTurn,
        request: Long,
    ) {
        LynkHudWireCodec.writeModern(parcelWriter(parcel), guidance, turn, request, generation)
    }

    /** §5.3 传统片段（现代格式内嵌同一个，也是 code 1 的正文）。 */
    private fun writeLegacy(parcel: Parcel, guidance: CarHudGuidance, turnKind: Int) {
        LynkHudWireCodec.writeLegacy(parcelWriter(parcel), guidance, turnKind)
    }

    private fun parcelWriter(parcel: Parcel) = object : HudParcelWriter {
        override fun writeInt(value: Int) = parcel.writeInt(value)
        override fun writeLong(value: Long) = parcel.writeLong(value)
        override fun writeString(value: String) = parcel.writeString(value)
    }

    companion object {
        /** Full-state heartbeat mirrors the box; content dedup remains in the paired bridge. */
        private const val HUD_UPDATE_INTERVAL_MILLIS = 1_000L
        private const val HUD_HEARTBEAT_INTERVAL_MILLIS = 2_000L
        private const val TAG = "GeelyHudAdapter"

        private const val ACTION = "com.stewie.carplay.hudbridge.BIND"
        private const val DESCRIPTOR = "com.stewie.carplay.hudbridge.ICarPlayHudBridge"
        private const val CODE_LANE_CAPABILITY = 14

        /**
         * PATCH-0111：**从 `com.autonavi.amapauto` 改成 `com.baidu.naviauto`**。
         *
         * 上游原版把 ComponentName 写成"高德包名 + 百度服务类名"的跨包组合，并在 KDoc 里
         * 注明"这是原版的写法，照抄，不要顺手修正"。但 dbplay v40 现场补丁把它改成了
         * 百度包名，且**车机实测证明改后能连上**：
         * ```
         * BIND probe: resolveService OK (HudBridge service visible)
         * BIND probe: package com.baidu.naviauto INSTALLED
         * 已连上 HUD 桥服务：ComponentInfo{com.baidu.naviauto/com.baidu.naviauto.hudbridge.CarPlayHudBridgeService}
         * ```
         * 这里跟随现场补丁（与原版一致优先于上游注释）。
         *
         * 2026-10-18：**不再把这一行当唯一目标**。两个宿主包都由
         * [HudTargetResolver.CANDIDATES] 探测（百度 → 高德），本常量只作为
         * "都没解析到"时的第一候选与 [status] 的展示兜底；换车机不再需要改代码。
         */
        private const val PACKAGE = "com.baidu.naviauto"
        private const val SERVICE = "com.baidu.naviauto.hudbridge.CarPlayHudBridgeService"

        /** "HDU2" —— 现代格式的魔数。 */
        private const val MAGIC = 0x48445532

        private const val CODE_LEGACY_NAV = 1
        private const val CODE_STOP = 2
        private const val CODE_ENABLE = 3
        private const val CODE_IDENTITY = 13
        private const val PAIRED_BRIDGE_ID = HudBridgeCapabilities.IDENTITY_TAG

        /**
         * PATCH-0111：清车道事务码 **3 → 12**（现场补丁实测值）。
         *
         * 上游原版按 `.ai/HUD-BRIDGE-REVERSE.md` 的记录用 3；现场补丁改用 12，
         * 且 v40 装机验证通过。跟随现场补丁。
         */
        private const val CODE_CLEAR_LANES = 12
        private const val CODE_MODERN_NAV = 10

        /** 传统帧正文里那个恒为 12 的标记字段（见 [writeLegacy]）。 */
        private const val LEGACY_FRAME_MARKER = 12

        /** 传统帧正文尾部恒为 0 的 int 字段个数（见 [writeLegacy]）。 */
        private const val LEGACY_TRAILING_INTS = 5

        /** 契约层没有 maneuver.index，用原版哨兵值表示"无"。 */
        private const val NO_MANEUVER_INDEX = -1
    }
}
