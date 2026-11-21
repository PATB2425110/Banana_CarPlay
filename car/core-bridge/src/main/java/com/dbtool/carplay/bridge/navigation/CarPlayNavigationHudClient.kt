package com.dbtool.carplay.bridge.navigation

import android.util.Log
import com.dbtool.carplay.api.navigation.CarHudBridge
import com.dbtool.carplay.api.navigation.CarHudGuidance
import com.dbtool.carplay.api.navigation.CarHudLane
import com.dbtool.carplay.api.navigation.CarHudStatus
import com.shilapi.xcertplay.iap2.message.CarNavigationState
import com.shilapi.xcertplay.navigation.CarPlayNavigationBridge

/**
 * 订阅 Core 解出的导航状态，转成 [CarHudGuidance] 喂给 HUD 桥。
 *
 * ## 数据流
 *
 * ```
 * iPhone ──iAP2 0x5201/0x5202──▶ CarPlayController（PATCH-0016 解码）
 *                                      │ CarPlayNavigationBridge.publish
 *                                      ▼
 *                            本类（订阅 + 转换）
 *                                      │ CarHudBridge.update
 *                                      ▼
 *                        GeelyHudBridge ──Parcel──▶ 百度 hudbridge 服务
 * ```
 *
 * ## 为什么转换层在这里，而不是让 HUD 桥直接读 Core 的类型
 *
 * `:car:api` 的 `CarHudBridge` **不能**依赖 `:shared`（构建脚本里写死"契约层零依赖"）。
 * 所以 `:shared` 的 `CarNavigationState` 必须由 `:car:core-bridge` 转成契约类型——
 * 这也是"只有 `:car:core-bridge` 可以 import `com.shilapi.xcertplay.*`"这条硬约束的
 * 标准用法，与 `CarPlayNowPlayingClient` 同构。
 *
 * ## null 的两种含义（别混）
 *
 * | 上游给的值 | 含义 | 本类动作 |
 * | --- | --- | --- |
 * | `null`（整条快照） | 导航结束/取消 | 调 `update(null)` ⇒ 桥发"停止"事务 |
 * | 非 null 但 `hasContent == false` | 有导航但这一帧没解出东西 | **不发**，保留车机上一条 |
 *
 * 第二种情况若也发"停止"，会在导航途中因为一帧解不出而把车机提示清掉——
 * 那是闪断，比不更新更糟。
 *
 * ## 内容去重（PATCH-0111，移植自 dbplay v40-road-20261013 现场补丁）
 *
 * iPhone 会以不规律频率反复下发状态（实测 59 个相邻间隔里 21 个 < 3 秒，最小 0.0 秒
 * ——同一毫秒推两次），其中还穿插"切源过渡空帧"。原版每收到一帧就推一帧，
 * 车机仪表被反复清空重绘，视觉上就是**卡片一闪一闪**。
 *
 * 修法：**距离与剩余秒数都没变 ⇒ 这一帧对用户无意义，直接丢弃。**
 * 静止时距离/秒数不变 ⇒ 完全不推；行驶时距离每秒都在变 ⇒ 本来就该频繁更新。
 * 因此不需要加时间节流（现场试过 800ms 节流，两次因寄存器状态崩溃，已弃用）。
 *
 * 本次按用户要求改成无时间窗口的完整内容去重；重复 null 不再放行，null 与 0 不混同。
 *
 * ## 实车状态（PATCH-0112）
 *
 * 中文路名映射与内容去重**已在实车上验证可用**（2026-10-13，车主确认：仪表能出转向卡片、
 * 路名是中文、卡片不再闪烁）。因此本类里的**判据与映射都属"已实证"** ——
 * 要改动，先确认不是在推翻车机上已经验证过的表现。
 *
 * ## 线程
 *
 * `CarPlayNavigationBridge` 在 iAP2 帧到达的线程上回调；本类只做纯转换，
 * 然后交给桥（桥自己 post 到主线程）。因此这里不需要额外 marshalling。
 *
 * ⚠️ 例外是 [setEnabled]：它由设置变更路径在**主线程**调用，而 [enabled] 在
 * iAP2 帧线程上被读 ⇒ 那个字段必须是 `@Volatile`。计数器同理（单写者 + volatile）。
 *
 * ## 开关（2026-10-13）
 *
 * [setEnabled] 是「设置 → 仪表导航 → HUD 导航推送」的落点。它是**闸门**而不是
 * 断开：关掉后一帧都不推，但订阅与绑定照旧（理由见 [setEnabled] 的 KDoc）。
 */
class CarPlayNavigationHudClient(
    private val bridge: CarHudBridge,
    private val log: (String) -> Unit = { Log.i("CarPlay-Navigation", it) },
) {

    @Volatile
    private var unsubscribe: (() -> Unit)? = null

    /**
     * 是否允许推送（设置 → 仪表导航 → HUD 导航推送，2026-10-13）。
     *
     * ## 为什么是"闸门"而不是"断开"
     *
     * 关掉时**不** `disconnect()`：那条路会调 `bridge.stop()`，把桥推进"已停止"态
     * （`GeelyHudBridge` 的 `closed`），而它**跨会话是同一个实例**
     * （`GeelyCarPlatform.hud` 是 `by lazy`，平台本身进程内唯一）。
     * 于是"关一次"就会变成"本进程内永久失效"——**开关关不回来**。
     *
     * 取舍与 `CarSessionSettings.reportMediaCardToCar` 同形：**开关只决定"推不推"**，
     * 绑定/解绑仍由会话生命周期管（会话起则连、会话停则断）。
     * 代价是关掉后仍持有一个外部服务绑定；那点开销远小于"开关关不回来"。
     *
     * `@Volatile`：由设置变更路径（主线程）写，由 iAP2 帧到达线程读。
     */
    @Volatile
    private var enabled: Boolean = true

    /** 累计收到 iPhone 的导航状态帧数（含被去重丢掉的）。排障用，见 `CarHudStatus`。 */
    @Volatile
    private var receivedFrames: Long = 0

    /** 累计被"内容没变化"丢掉的帧数。排障用。 */
    @Volatile
    private var droppedFrames: Long = 0

    /** 内容去重（PATCH-0111）。纯逻辑，见 [HudPushDeduper]。 */
    private val deduper = HudPushDeduper()
    private var lastDuplicateLogAtMs = 0L
    @Volatile private var connected = false
    private val subscriptionEpoch = HudSubscriptionEpoch()

    /**
     * 开始订阅并推送。
     *
     * 幂等：重复调用不会重复订阅（`CarPlayNavigationBridge.observe` 按身份去重，
     * 但这里也显式防一手，避免连出两条回调）。
     */
    @Synchronized
    fun connect(onAvailabilityChanged: (Boolean) -> Unit = {}) {
        if (connected) return
        connected = true
        val epoch = subscriptionEpoch.next()
        // PATCH-0111 对现场补丁的**唯一一处偏离**：新订阅开始时清掉去重基线。
        //
        // 现场补丁把 lastMeters/lastSeconds 一直留着，于是"导航结束（发过 update(null)
        // 把车机提示清空）→ 重新导航"时，若新会话首帧的距离/秒数与上一会话最后一次
        // 相同，首帧会被当成"没变化"丢掉 ⇒ 车机提示一直空着，直到数值变化才出现。
        // 复位只影响每个会话的第一帧，且方向是"该推的一定推"，比现场表现更正确。
        deduper.reset()
        bridge.start(onAvailabilityChanged)
        unsubscribe = CarPlayNavigationBridge.observe { state -> onState(epoch, state) }
    }

    /** 停止订阅并让桥停止推送。幂等。 */
    @Synchronized
    fun disconnect() {
        connected = false
        subscriptionEpoch.next()
        unsubscribe?.invoke()
        unsubscribe = null
        bridge.stop()
    }

    /**
     * 设置「HUD 导航推送」开关（2026-10-13）。**立即生效**，不必重连。
     *
     * ## 关掉时要**主动清一次车机上的提示**
     *
     * 只把 [enabled] 置 false 是不够的：如果关掉的那一刻仪表上正挂着一张转向卡片，
     * 它会**一直挂在那儿**（此后我们再不发任何一帧，包括导航结束时的"停止"事务），
     * 用户看到的正是"开关关掉了、卡片还在"——比没有开关更困惑。
     * 所以这里补发一次 `update(null)`（桥侧会走"停止"事务）。
     *
     * ## 重新打开时清一次去重基线
     *
     * 与 [connect] 同一个理由：关掉期间基线不更新，重新打开后若首帧数值与关掉前
     * 最后一帧相同，会被当成"没变化"丢掉 ⇒ 车机提示一直空着。复位只影响重开后的
     * 第一帧，方向是"该推的一定推"。
     *
     * @return 无（幂等：状态没变时什么都不做）
     */
    @Synchronized
    fun setEnabled(enabled: Boolean) {
        val wasEnabled = this.enabled
        if (wasEnabled == enabled) return
        this.enabled = enabled
        if (!enabled) {
            // 关掉：把车机上已经画出来的提示清掉（见 KDoc）。
            bridge.update(null)
        } else {
            deduper.reset()
        }
    }

    /**
     * 本链路的实时状态快照（设置页「仪表导航」用，2026-10-13）。
     *
     * 把**桥侧**的状态（`bridge.status()`）与**本类**才知道的三项合并成一个对象：
     * 订阅是否在推、收到过多少帧、被去重丢掉多少帧。
     * 合成一个对象的理由见 `CarHudStatus` 的类注释。
     *
     * ⚠️ 只读、便宜：`bridge.status()` 按契约只读内存字段，不做 binder 调用。
     * `runCatching` 兜住"实现没按契约写"的情况 —— 排障界面自己不能成为新的崩溃源。
     */
    fun status(): CarHudStatus {
        val bridgeStatus = runCatching { bridge.status() }.getOrDefault(CarHudStatus())
        return bridgeStatus.copy(
            // 本类只在本车机确实有 HUD 通道时才会被构造（见 `CarPlaySessionBridge.hudClient`）。
            supported = true,
            // 开关关掉时不算"在推"：桥可能仍然连着，但一帧都不会出去。
            pushing = enabled && unsubscribe != null,
            receivedFrames = receivedFrames,
            droppedFrames = droppedFrames,
        )
    }

    @Synchronized
    private fun onState(epoch: Long, state: CarNavigationState?) {
        // 闸门放在最前面（含"导航结束"那一支）：关掉后我们**一点都不碰**车机 ——
        // 不清提示、不补绑、不发停止事务。理由见 [setEnabled]。
        if (!enabled) return
        if (!connected) return
        if (!subscriptionEpoch.isCurrent(epoch)) return
        if (state == null) {
            // 导航结束：明确告诉车机清掉提示。
            deduper.reset()
            bridge.update(null)
            return
        }
        receivedFrames += 1
        if (!state.hasContent) return
        if (state.maneuverType == null || state.guidanceState in 3..5) {
            // A newly selected/missing maneuver or reroute is not the old turn.
            // Clear the native card until a matching 5202 record resolves it.
            deduper.reset()
            bridge.update(null)
            return
        }
        if (!deduper.accept(
                meters = state.routeMeters,
                seconds = state.routeSeconds,
                maneuverSignature = state.hudManeuverSignature(),
            )
        ) {
            droppedFrames += 1
            // Content dedup is not source liveness. The bounded sender mailbox consumes
            // repeated complete snapshots without retransmitting unchanged content.
            bridge.update(state.toGuidance())
            val now = System.currentTimeMillis()
            if (now - lastDuplicateLogAtMs >= 5_000L) {
                lastDuplicateLogAtMs = now
                log("导航重复快照：机动=${state.maneuverType} 距离=${state.segmentMeters} " +
                    "全程=${state.routeMeters} 时间=${state.routeSeconds} 路名=${state.hudCurrentRoad()} " +
                    "转入路=${state.nextRoad} 终点=${state.destination} 车道=${state.lanes.size}")
            }
            return
        }
        val guidance = state.toGuidance()
        if (!guidance.hasContent) return
        log("推送 HUD：${guidance.routeMeters}米 / ${guidance.nextRoad ?: guideNoRoad}")
        bridge.update(guidance)
    }

    private fun CarNavigationState.toGuidance() = CarHudGuidance(
        // epoch ms（2026-10-18）：车机 HUD 服务用这个时间判断"提示是不是过期了"，
        // 口径必须与 Fake Ultra 一致（`System.currentTimeMillis()`）。
        // 曾经的 `SystemClock.elapsedRealtime()` 是开机计时，与服务端相差几十年。
        updatedAtMs = System.currentTimeMillis(),
        routeMeters = routeMeters,
        routeSeconds = routeSeconds,
        segmentMeters = segmentMeters,
        maneuverType = maneuverType,
        currentRoad = hudCurrentRoad(),
        nextRoad = hudNextRoad(),
        sessionId = routeGeneration ?: sessionId,
        revision = revision,
        lanes = lanes.take(16).map { CarHudLane(it.index, it.angles.take(8), it.highlightAngle) },
        nextManeuverRoad = nextRoad.cleanHudText() ?: hudCurrentRoad(),
        maneuverDescription = maneuverDescription,
        maneuverIndex = maneuverIndex,
        drivingSide = drivingSide,
        junctionAngle = junctionAngle,
        exitAngle = exitAngle,
    )

    private companion object {
        const val TAG = "CarPlay-Navigation"
        const val guideNoRoad = "（无路名）"
    }
}

/**
 * HUD 推送的**内容去重**（PATCH-0111，移植自 dbplay `v40-road-20261013` 现场补丁）。
 *
 * 距离、剩余秒数、转向/道路签名都没变 ⇒ [accept] 返回 false，这一帧不推。
 *
 * ## 为什么单独抽一个类
 *
 * 判据是**纯算术**，不含任何 Android 类型 ⇒ 能在 JVM 上跑单测（与
 * [com.dbtool.carplay.geely.GeelyHudTurnMapping] 同理）。去重写错方向的表现是
 * "车机卡片继续闪"或"车机提示卡住不刷新"——**都在车上才看得出来**，
 * 用单测钉死比上车试便宜得多。
 *
 * ## 判据
 *
 * - null 与 0 分开比较；即使距离/时间缺失，重复的有效转向也只推一次。
 * - 时间经过不构成内容变化；新连接/开关重开仍允许首帧。
 *
 * ## 为什么把转向/路名并入键（2026-10-19，1.1.24 现场日志）
 *
 * iAP2 常把 `0x5201`（剩余距离/时间）和 `0x5202`（转向记录）分成两帧到达：
 * `0x5201` 先到、`0x5202` 后到，而两帧之间距离/秒数完全不变。旧键只有
 * `(距离, 秒数)`，会把后到的真实转向丢掉；服务端只收到“无转向”的上下文帧，
 * 仪表最终不显示内容。
 *
 * 现在把 `maneuverType + 第一行路名 + 第二行目的地/下一段路` 作为
 * `maneuverSignature`。只要这些可见内容有变化就允许推送；完全相同才丢弃，
 * “同距离同秒数反复推同一内容”的防闪烁语义保持不变。
 *
 * 线程：`CarPlayNavigationBridge` 在 iAP2 帧到达的线程上回调，本类因此实际被
 * 单线程访问；字段加 `@Volatile` 只是让"换线程时能看到最新值"不依赖运气。
 */
internal class HudPushDeduper(
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private var lastForwardedAt: Long? = null

    /** 上一次**已推送**的距离（米）；`null` 表示还没推过。 */
    @Volatile
    private var lastMeters: Int? = null

    /** 上一次**已推送**的剩余秒数；`null` 表示还没推过。 */
    @Volatile
    private var lastSeconds: Int? = null

    /** 上一次**已推送**的转向/道路签名。 */
    @Volatile
    private var lastManeuverSignature: Any? = null

    /**
     * 清掉基线。
     *
     * ⚠️ **每个新会话开始时必须调**：否则"导航结束 → 重新导航"时，新会话首帧若与
     * 上一会话末帧数值相同，会被当成"没变化"丢掉 ⇒ 车机提示一直空着。
     */
    fun reset() {
        lastForwardedAt = null
        lastMeters = null
        lastSeconds = null
        lastManeuverSignature = null
    }

    /** @return `true` = 内容有变化，这一帧该推；`false` = 该丢弃。 */
    fun accept(
        meters: Int?,
        seconds: Int?,
        maneuverSignature: Any? = null,
    ): Boolean {
        val metersUnchanged = meters == lastMeters
        val secondsUnchanged = seconds == lastSeconds
        // 0x5201 的剩余距离/时间常常先到，0x5202 的转向记录后到；若只按
        // (距离, 秒数) 去重，后到的真实转向会被丢掉，仪表就只剩空上下文。
        // 把转向类型与两行文案并入签名：只有“用户看到的全部内容”都没变才丢。
        // 不传 signature 的调用方（旧测试/旧路径）继续按原来的双字段判据；
        // 传了 signature 时，只有它也和上次相同才允许丢弃。
        val signatureUnchanged = maneuverSignature == lastManeuverSignature
        // 无时间窗口，null 也是可比较的内容；首帧始终放行。
        if (lastForwardedAt != null && metersUnchanged && secondsUnchanged && signatureUnchanged) return false
        lastMeters = meters
        lastSeconds = seconds
        lastManeuverSignature = maneuverSignature
        lastForwardedAt = nowMillis()
        return true
    }
}

/**
 * HUD 去重签名：转向类型 + 第一行路名 + 第二行目的地/下一段路。
 *
 * 不是把整个快照塞进去——只放用户能在 HUD 卡片上看到、且 0x5202 可能晚于
 * 0x5201 到达的字段。这样既保留“距离/时间不变时不反复推”的防闪烁语义，
 * 又不会漏掉“进入无名道路/右转”这类转向更新。
 */
internal fun CarNavigationState.hudManeuverSignature(): Any =
    listOf(maneuverType, hudCurrentRoad(), hudNextRoad(), nextRoad.cleanHudText(), segmentMeters,
        lanes.take(16).map { CarHudLane(it.index, it.angles.take(8), it.highlightAngle) }, sessionId, revision,
        maneuverIndex, drivingSide, junctionAngle, exitAngle, routeGeneration)

/**
 * HUD 第一行要显示的路名（PATCH-0111）。
 *
 * `0x5201` 的 `0x0003 CURRENT_ROAD_NAME` 在百度/高德/Apple 三个导航 App 下**全部为
 * NULL**（实测），原样透传会让车机显示英文占位符 `Next Road`。真正的中文路名在
 * `0x5202`（ManeuverUpdate）的 `DESCRIPTION` 里（"八一西路" / "安仁大道" / "进入环岛"），
 * 而 xcertplay 的 `mergeManeuver()` **早就把它解析进**
 * [CarNavigationState.maneuverDescription] ⇒ 数据层无需改动。
 *
 * [CarNavigationState.currentRoad] 保留作 fallback——某些场景（如 Android 端模拟
 * CarPlay）确实有值。
 */
internal fun CarNavigationState.hudCurrentRoad(): String? =
    maneuverDescription.cleanHudText() ?: nextRoad.cleanHudText() ?: currentRoad.cleanHudText()

/**
 * HUD 第二行要显示的内容（PATCH-0111）：目的地优先（如 "万达影城(万达广场店)"），
 * [CarNavigationState.nextRoad] 作 fallback。
 */
internal fun CarNavigationState.hudNextRoad(): String? = destination.cleanHudText() ?: nextRoad.cleanHudText()

private fun String?.cleanHudText(): String? =
    this?.trim { it <= ' ' || it == '\u0000' }?.takeIf { it.isNotBlank() }
