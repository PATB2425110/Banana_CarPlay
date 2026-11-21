package com.dbtool.carplay.api.navigation

/**
 * 导航转向提示桥（HUD bridge）契约。
 *
 * ## 它解决什么
 *
 * 吉利 eCarx 车机的仪表/HUD 侧挂着一个 **hudbridge 服务**（百度/高德车机版
 * 都有各自的宿主包，见 `HudTargetResolver`），
 * 车机据此在仪表上显示"前方左转"这类**转向提示**（不接管全屏画面）。
 * DBPlay 要把 iPhone 的 CarPlay 导航提示喂给那个服务。
 *
 * ## 与其它导航契约的分工
 *
 * `car:api` 里已有两个"导航"，语义各不相同，别混：
 *
 * | 契约 | 方向 | 用途 |
 * | --- | --- | --- |
 * | [CarNavigationAdapter] | **车机 → iPhone** | 把车机定位回传给 iPhone |
 * | [CarNavigationRequest] | 车机 → iPhone | 定位回传的请求参数 |
 * | **本契约** | **iPhone → 车机** | 把 iPhone 的转向提示推给车机仪表 |
 *
 * 前两者是"车机告诉手机我在哪"，本契约是"手机告诉车机往哪转"——方向相反，
 * 因此刻意不复用同一个接口。
 *
 * ## 数据来源
 *
 * 上游 `:shared` 原本**不解码** iAP2 导航帧（`0x5201/0x5202/0x5204` 只注册了端点、
 * 没有解码器），DBPlay 补了这段解码（见 `PATCHES.md` 的 PATCH-0016）。
 * `:car:core-bridge` 把解码结果 [com.shilapi.xcertplay.iap2.message.CarNavigationState]
 * 转成本模块的 [CarHudGuidance] 后喂给实现。
 *
 * ## 线程与生命周期
 *
 * - [update] 会在任意线程被调用（iAP2 帧到达的线程）；实现若要碰
 *   `IBinder`/`Parcel`，必须自己 post 到自己的 handler。
 * - [start] / [stop] 在会话起止时调用，可能多次（重连）。
 * - **实现必须容忍车机上没有那个 hudbridge 服务**：绑定失败是常态
 *   （未装高德车机版 / 服务被禁），此时不发即可，**不得抛异常**。
 * - [stop] 之后不得再发送；实现须幂等。
 *
 * ## 为什么是"增值能力"
 *
 * 与 [com.dbtool.carplay.api.display.CarClusterPresenter] 同理：HUD 只是锦上添花，
 * 它的任何故障都不该影响 CarPlay 主会话。因此上层若拿到 null，
 * 应直接跳过，而不是报错。
 */
interface CarHudBridge {

    /**
     * 本车机当前是否能推送导航提示。
     *
     * 返回 false 时上层应停发。**注意**：这个值是"此刻能否"，会随服务出现/消失
     * 而变，因此实现不该把它当作一次性结论缓存。
     */
    fun isAvailable(): Boolean

    /**
     * 开始推送。[onAvailabilityChanged] 在可用性变化时回调，供 UI 反映状态。
     *
     * 幂等：重复调用不重复绑定。
     */
    fun start(onAvailabilityChanged: (Boolean) -> Unit)

    /** 结束推送并解绑。幂等。 */
    fun stop()

    /**
     * 推送一次导航提示。
     *
     * @param guidance **null 表示导航已结束/取消**，实现应清掉车机侧残留的提示
     *   （原版的做法是发一个"停止"事务）。这与"有导航但这一帧没数据"不同——
     *   后者由上层直接不调用 [update] 表达。
     */
    fun update(guidance: CarHudGuidance?)

    /**
     * 本车机 HUD 通道的**实时状态快照**（2026-10-13，设置页「HUD」独立版块用）。
     *
     * ## 为什么要有它
     *
     * 这条链路在车上**完全无声**：绑不上外部服务、iPhone 一帧都没下发、车机拒收某一帧
     * —— 三种情况在手机上都只表现为"仪表没有转向卡片"，而真相要事后采集诊断包才看得到。
     * 设置页把这几段摊开，用户与现场就能一眼定位是哪一段断的。
     *
     * ## 实现要求（三条都是硬约束）
     *
     * 1. **只读**：调用它**不得**触发绑定、重连或发包 —— 它纯粹是回显；
     * 2. **便宜且不阻塞**：调用方在渲染路径上取它（`CarAppController.refreshModel()`），
     *    所以实现只能读自己内存里的 `@Volatile` 字段，**不许**做 binder 调用或 I/O；
     * 3. **不抛异常**：拿不到就给默认值。
     *
     * ## 默认实现
     *
     * 返回 [CarHudStatus] 的默认值（= "本车机没有 HUD 通道"）。这样还没接 HUD 的平台、
     * 以及测试替身都不必写桩 —— 与 [CarHudStatus] 全字段带默认值是同一个用意。
     *
     * ⚠️ 实现**只需要填桥侧那几项**（[CarHudStatus.supported] / [CarHudStatus.available] /
     * [CarHudStatus.target] / [CarHudStatus.lastFailure] / [CarHudStatus.pushedFrames] /
     * [CarHudStatus.lastPush]）；上层侧的 [CarHudStatus.pushing] /
     * [CarHudStatus.receivedFrames] / [CarHudStatus.droppedFrames] 由
     * `:car:core-bridge` 的 `CarPlayNavigationHudClient` 合并时填。
     */
    fun status(): CarHudStatus = CarHudStatus()
}

/**
 * HUD 通道的实时状态（[CarHudBridge.status] 的返回值，2026-10-13）。
 *
 * ## 字段分两组，各由谁填
 *
 * | 组 | 字段 | 填写者 |
 * | --- | --- | --- |
 * | **桥侧** | [supported] / [available] / [connecting] / [target] / [targetPackage] /
 *   [lastFailure] / [pushedFrames] / [lastPush] / [lastManeuverText] / [lastSegmentMeters] | 桥的实现（如 `GeelyHudBridge`） |
 * | **上层侧** | [pushing] / [receivedFrames] / [droppedFrames] | `:car:core-bridge` 的 `CarPlayNavigationHudClient` |
 *
 * 后者用 `copy()` 把桥侧字段原样带过来（见 `CarPlayNavigationHudClient.status()`）。
 *
 * ## 为什么合成**一个**类型而不是桥侧/上层侧各一个
 *
 * 设置页要的是一段**完整的因果链**：
 *
 * ```
 * iPhone 发了几帧 → 被"内容没变化"去掉几帧 → 桥受理了几帧 → 最近一帧是什么
 * ```
 *
 * 拆成两个对象只会让界面自己拼，而且迟早有一边漏更新（那时界面会显示一个
 * 自相矛盾的组合，比少显示一行更糟）。
 *
 * ## 计数器的用途（为什么值得带这么多数字）
 *
 * 仪表上没出现转向卡片时，这四个数字能直接定位断点，不必采诊断包：
 *
 * | 现象 | 结论 |
 * | --- | --- |
 * | [receivedFrames] = 0 | iPhone 根本没下发（没在导航，或 RGD 组件宣告没生效） |
 * | [receivedFrames] > 0 但 [droppedFrames] ≈ 它 | 帧都到了，被"内容没变化"丢掉了 —— 数值不动时**这是正常**的 |
 * | [pushedFrames] = 0 而 [receivedFrames] > 0 | 桥没受理（转向码不支持 / 车机拒收，看 [lastFailure]） |
 * | [pushedFrames] > 0 | 我们这一侧全部正常，问题在车机侧 |
 *
 * 所有字段带默认值 ⇒ `CarHudStatus()` 恰好就是"本车机没有 HUD 通道"这一态。
 */
data class CarHudStatus(
    /** 本车机有没有 HUD 通道（即 `CarPlatform.hud != null`）。false ⇒ 下面全部无意义。 */
    val supported: Boolean = false,
    /** 桥此刻是否可用（已绑上外部 hudbridge 服务）。 */
    val available: Boolean = false,
    /** 正在绑定、还没拿到连接回调（设置页显示"绑定中…"）。 */
    val connecting: Boolean = false,
    /**
     * 目标组件（`包名/服务类名`）。
     *
     * 空 = 实现没暴露它。**不要**在界面侧补一个写死的值 —— 它是"到底往哪儿发"的
     * 唯一线索，编一个出来会让排查被带偏（换车机后第一件要核对的就是它）。
     */
    val target: String = "",
    /**
     * 目标宿主包（`com.baidu.naviauto` / `com.autonavi.amapauto`）。
     *
     * 空 = 本次会话还没解析到可用服务。设置页据此显示"未找到车机 HUD 服务"，
     * 而不是拿 [target] 的默认值假装已经解析到了。
     */
    val targetPackage: String = "",
    /** 上层是否已在推送（会话已连上、并订阅了导航状态）。 */
    val pushing: Boolean = false,
    /** 最近一次绑定失败 / 事务异常的说明；空 = 没失败过。 */
    val lastFailure: String = "",
    /** 最近一帧**被车机受理**的摘要（距离/剩余时间/转向/两行文案）；空 = 还没推成功过。 */
    val lastPush: String = "",
    /** 最近一帧的转向文案（中文，如"左转"）；空 = 还没有受理过帧。 */
    val lastManeuverText: String = "",
    /** 最近一帧距下一个转向点的距离（米）；null = 没给。 */
    val lastSegmentMeters: Int? = null,
    /** 累计**被车机受理**的帧数。 */
    val pushedFrames: Long = 0,
    /** 累计收到 iPhone 的导航状态帧数（含被去重丢掉的）。 */
    val receivedFrames: Long = 0,
    /** 累计被"内容没变化"丢掉的帧数（仪表"没动"时多半是它）。 */
    val droppedFrames: Long = 0,
)

/**
 * 一次导航转向提示。
 *
 * ## 字段刻意"少而稳"
 *
 * 只带**上游解码器确实能产出**的字段（见 [CarHudGuidance] 的映射说明），
 * 不预先塞入"以后可能有用"的东西——上游解码出的导航字段是推断的
 * （PATCH-0016），契约层跟着一起膨胀只会让错误更难定位。
 *
 * ## 与 iPhone 原始值的关系
 *
 * [maneuverType] 是**手机给的原始转向类型码**，不是车机 HUD 的转向编号。
 * 映射（→ 语义串 → 转向 kind → OneOS 转向 id）全部发生在具体实现里：
 * 那是厂商差异，且需要原版的反编译映射表，不属于契约层。
 */
data class CarHudGuidance(
    /**
     * 采集时刻，**epoch 毫秒**（`System.currentTimeMillis()`）。
     *
     * 与 Fake Ultra 的 `updatedAtMs` 同口径（2026-10-18 从
     * `SystemClock.elapsedRealtime()` 改过来）：车机侧把它当"这条提示是什么时候的"
     * 参与 120 s 过期判断，uptime 毫秒（开机计时）与 epoch 相差几十年，服务端
     * 会算出一个永远过期/永远不过期的时间。
     */
    val updatedAtMs: Long,
    /** 全程剩余距离（米）。null = 手机没给。 */
    val routeMeters: Int?,
    /** 全程剩余时间（秒）。null = 手机没给。 */
    val routeSeconds: Int?,
    /** 距下一个转向点的距离（米）。null = 手机没给。 */
    val segmentMeters: Int?,
    /** 手机给的原始转向类型码；null = 未知。 */
    val maneuverType: Int?,
    /** 当前道路名；null = 手机没给。 */
    val currentRoad: String?,
    /** 下一段道路名；null = 手机没给。 */
    val nextRoad: String?,
    /**
     * 路线代次标识（手机给的 session + revision）。
     *
     * ## 为什么必须有它，而不是拿时间戳凑
     *
     * 车机 HUD 服务需要一个"是否换路了"的信号：换路要 bump `generation`，
     * 否则新路线会被当成旧路线的一次更新。原版是用导航快照的
     * `(sessionId, revision)` 做这个判据的（见 `.ai/HUD-BRIDGE-REVERSE.md` §6）。
     *
     * 用"道路名 + 转向码"或"时间分桶"去近似都会错：同一条路上的两个不同转向点
     * 长得一样，而用户改目的地后第一帧可能时间戳相近。所以契约直接带上真值。
     *
     * 两者都可能为 null（手机没给），实现应把"都为 null"视为"无法判定换路"，
     * 此时不要 bump——宁可少 bump 一次，也不要无谓地让车机重画。
     */
    val sessionId: Long?,
    val revision: Long?,
    val lanes: List<CarHudLane> = emptyList(),
    /** Actual road after the maneuver. Separate from the legacy second display line (destination). */
    val nextManeuverRoad: String? = null,
    /** Raw maneuver context used by the box-equivalent normalizer, not fabricated IDs. */
    val maneuverDescription: String? = null,
    val maneuverIndex: Int? = null,
    val drivingSide: Int? = null,
    val junctionAngle: Int? = null,
    val exitAngle: Int? = null,
) {
    /**
     * 是否至少有一项"用户能看到"的内容。
     *
     * 全空时上层不该调 [CarHudBridge.update]——发一个空提示会让车机仪表
     * 显示"0 米 0 分钟"这种假数据，比什么都不显示更糟。
     */
    val hasContent: Boolean
        get() = routeMeters != null ||
            routeSeconds != null ||
            segmentMeters != null ||
            maneuverType != null ||
            currentRoad != null ||
            nextRoad != null
}

/** Left-to-right lane directions; null highlight must not be invented by the bridge. */
data class CarHudLane(
    val index: Int,
    val angles: List<Int>,
    val highlightAngle: Int? = null,
)
