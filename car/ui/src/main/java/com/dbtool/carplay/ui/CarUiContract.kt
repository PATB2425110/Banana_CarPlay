package com.dbtool.carplay.ui

import com.dbtool.carplay.api.display.ClusterDialLayout
import android.net.Uri
import android.view.Surface
import com.dbtool.carplay.api.airplay.CarAirPlayIconStatus
import com.dbtool.carplay.api.developer.CarDeveloperState
import com.dbtool.carplay.api.developer.CarLogScope
import com.dbtool.carplay.api.hotspot.CarAdbHotspotState
import com.dbtool.carplay.api.hotspot.CarAdbInitializationState
import com.dbtool.carplay.api.hotspot.CarHotspotEnabledState
import com.dbtool.carplay.api.hotspot.CarHotspotEndpointCandidate
import com.dbtool.carplay.api.hotspot.CarNativeHotspotCredentials
import com.dbtool.carplay.api.hotspot.CarNativeHotspotProbeState
import com.dbtool.carplay.api.mfi.CarMfiIdentityStatus
import com.dbtool.carplay.api.navigation.CarHudStatus
import com.dbtool.carplay.api.navigation.CarNavigationRequest
import com.dbtool.carplay.api.navigation.CarNavigationSnapshot
import com.dbtool.carplay.api.session.CarSessionState
import com.dbtool.carplay.api.session.CarTouchContact
import com.dbtool.carplay.api.settings.CarSessionSettings
import com.dbtool.carplay.api.system.CarPlatformInfo
import com.dbtool.carplay.api.update.CarUpdateState
import com.dbtool.carplay.api.session.CarProjectionState
import com.dbtool.carplay.ui.components.CarDockApp
import com.dbtool.carplay.ui.components.CarDockAppChoice
import com.dbtool.carplay.ui.components.CarDockMedia
import com.dbtool.carplay.ui.components.CarDockTemperature

/**
 * 界面渲染所需的全部数据。
 *
 * **UI 只认这个类型。** 它不包含任何 upstream 类型、不包含控制器引用，
 * 因此界面可以在没有 Core、没有硬件的情况下用预览/单元测试渲染。
 */
data class CarUiModel(
    val activeAudioStreams: List<com.dbtool.carplay.api.audio.CarAudioStreamStatus> = emptyList(),
    val immersiveNavigationActive: Boolean = false,
    val immersiveNowPlaying: com.dbtool.carplay.api.media.CarNowPlaying? = null,
    /** Phone-reported battery level per caller-supplied iAP2 parameter definitions. */
    val phonePowerLevel: Int? = null,
    val phoneChargingState: Int? = null,
    val phoneSignalStrength: Int? = null,
    val phoneMaxCurrentDrawnMa: Int? = null,
    val session: CarPlayScreenState = CarUiStateMapper.project(CarSessionState()),
    val settings: CarSessionSettings = CarSessionSettings.default(),

    /**
     * 有没有**已保存、但还没生效**的设置改动（需要重连才生效）。
     *
     * ## 为什么必须有这个标志（PATCH-0103，2026-10-12）
     *
     * 设置页改成**自动保存**之后，「保存」与「生效」变成了两件事：
     *
     * - 改动立刻落盘（不需要点任何按钮）；
     * - 但画布尺寸、`/info` 声明、音频通道号、热点身份这些**必须重连**才真正生效
     *   （各自理由见 `CarSessionSettings` 里对应字段的注释）。
     *
     * 没有这个标志，用户改完看到底部写着「已保存」就会以为**已经生效** ——
     * 而实际要等到下次重连。那正是现场最难查的一类误解（"设置改了没反应"）。
     *
     * 判据是 `CarSettingsReconnectPolicy.requiresReconnect`（纯函数、有单测）。
     * 置位：`applySettings` 提交后相对**上次重连时的基线**存在需要重连的差异；
     * 清位：用户点「重连生效」（`CarUiActions.reconnect()`）之后。
     */
    val settingsPendingReconnect: Boolean = false,

    /**
     * 左右圆表的当前布局（2026-10-21）。
     *
     * 与 [settings] 分开：它不参与 CarPlay 握手、不进重连策略，只用于仪表主题页的标定控件。
     * 默认 [ClusterDialLayout.DEFAULT]（恒等变换）⇒ 未调过时画面与改造前一致。
     */
    val dialLayout: ClusterDialLayout = ClusterDialLayout.DEFAULT,

    /**
     * DBPlay 自绘层的绘制区域（2026-10-22）。
     *
     * 与 [dialLayout] 叠加：区域在外（裁剪 + 等比缩放），单表变换在内。
     * 默认整幅 1920×720 ⇒ 未调过时画面与改造前一致。
     */
    val drawRegion: com.dbtool.carplay.api.display.ClusterDrawRegion =
        com.dbtool.carplay.api.display.ClusterDrawRegion.DEFAULT,

    /**
     * 仪表视频层的本机画面缩放/平移（2026-10-22）。
     *
     * 只作用于仪表副屏的 `TextureView`，不影响主屏、不向 iPhone 发命令。
     * 默认 1.0/0/0 ⇒ 未调过时与改造前一致。
     */
    val videoTransform: com.dbtool.carplay.api.display.ClusterVideoTransform =
        com.dbtool.carplay.api.display.ClusterVideoTransform.DEFAULT,

    val platformInfo: CarPlatformInfo? = null,
    /** 已保存的配对控制器数量。 */
    val pairedControllerCount: Int = 0,
    val appVersionName: String = "",
    val appVersionCode: Long = 0,
    /** 上游 Core 版本（`versionName`），用于诊断区回显。 */
    val coreVersionName: String = "",
    val logs: List<String> = emptyList(),
    /** 权限缺失等需要提示用户的信息。 */
    val notice: String? = null,
    /**
     * 本地 MFi 配件身份状态。
     *
     * 只在 [CarSessionSettings.mfiTarget] 为 `LOCAL_OFFLINE` 时有意义——
     * 其余认证方式不使用这份身份。
     */
    val mfiIdentity: CarMfiIdentityStatus = CarMfiIdentityStatus(),

    /**
     * AirPlay OEM 图标状态（设置页预览用）。
     *
     * 这个图标会出现在 iPhone 的 CarPlay 启动器与 AirPlay 菜单里，
     * 是"这台车机是谁"最直观的一处标识。
     */
    val airPlayIcon: CarAirPlayIconStatus = CarAirPlayIconStatus(),

    /**
     * 是否显示**首次启动引导**弹窗（2026-10-08，斌哥口径）。
     *
     * 「**首次启动时，引导弹窗进入「立即建立热点 + ADB 永久」二级页面。**」
     *
     * ## 触发时机 = App 第一次打开就弹（不问 ADB 好不好）
     *
     * 车主在 A/B 两个方案里选了 A（「App 第一次打开就弹，不管 ADB 是否正常」）。
     * 另一条候选是「只在检测到 ADB 不可用时才弹」——被否：那时用户已经在
     * 看失败页了，引导的意义不大；不如一进来就告诉他这台车怎么配。
     *
     * ## 弹一次就够
     *
     * 点「去建立」或「以后再说」都会把标记落盘（`hasSeenHotspotGuide`），
     * 之后不再弹。连续弹会把用户逼成"闭眼点掉"，那还不如不弹。
     */
    val hotspotGuideVisible: Boolean = false,

    /**
     * 当前无线热点方案的**结论**（设置页「本次将使用」的值列）。
     *
     * 形如 `复用车机热点「CarHotspot」` / `自建热点「DBPlay」`。
     * 与 [hotspotHint] 分开的理由：`CarHotspotMode.AUTO` 下"到底用哪张网"
     * 才是用户要的答案，只给一句"自动判定"等于没答；理由留在 [hotspotHint]。
     *
     * 空串 = 有线链路，或探测失败（此时设置页整行不显示）。
     */
    val hotspotTarget: String = "",

    /**
     * 当前无线热点方案的**理由**（人类可读一句话）。
     *
     * 由 `CarHotspotResolver` 在**设置变化 / 会话启动**时算一次，
     * 而不是在渲染路径上算——它会做 `WifiManager` 的 binder 调用与网卡枚举。
     *
     * 之所以要把它显示出来：`CarHotspotMode.AUTO` 的决策（"复用车机热点"还是
     * "自建 DBPlay"）用户看不见，无线连不上时也就无从判断是哪一支出的问题。
     */
    val hotspotHint: String = "",

    /**
     * **复用车机热点**时，iPhone 该连哪张网、密码多少。空串 = 不适用。
     *
     * 只在复用那一支才有值：自建热点的凭据就是设置页上方那两个输入框，
     * 再说一遍是冗余；而复用时机热点凭据来自车机侧，用户在本 App 里看不到，
     * 无线连不上时最缺的就是这一句。
     */
    val hotspotPhoneHint: String = "",

    /**
     * 「复用车机热点」下用户手填的 SSID 与车机实际热点名不一致时的**警告**；
     * 空串 = 没这个问题（或不是复用模式）。
     *
     * 2026-10-06 加。单独一个字段而不是并进 [hotspotHint]：设置页要把这条
     * **标红**，而"标不标红"不该靠去 [hotspotHint] 里找 `⚠️` 字符来决定
     * ——那是把展示逻辑建在文案上。
     *
     * 为什么必须有这条：填错名字时上游 `ManualHotspotManager` 反而**不报错**
     * （它读不到匹配的配置就跳过全部校验），把用户填的字面量直接发给 iPhone，
     * 而那张网没人广播 ⇒ 静默卡在「无线通道就绪」。详见 `CarManualHotspotMatch`。
     */
    val hotspotWarning: String = "",

    /**
     * 车机自带热点开关的**当前**读数（三态）。
     *
     * 2026-10-08 加（车主口径：「设置页要能常驻看到车机热点状态」）。
     * 选「复用车机热点」时，这一行是用户判断"现在点连接会不会立刻失败"的**唯一**
     * 依据——车机侧的热点开关在本 App 里看不到，而它关着时连接必然失败
     * （见 `CarManualHotspotPrecheck`）。
     *
     * [CarHotspotEnabledState.UNKNOWN] = 读不到（厂商裁掉了隐藏 API）。
     * 它**不是**"关着"——连接时同样会放行，所以界面也必须这么表达。
     */
    val hotspotEnabledState: CarHotspotEnabledState = CarHotspotEnabledState.UNKNOWN,

    /**
     * 「复用车机热点」下，本机网卡上**当前真实存在**、可直接填入的地址候选
     * （PATCH-0102，2026-10-12）。
     *
     * 设置页据此在「本机 CarPlay 地址」输入框下方**列出可点的地址**，让用户点一下
     * 就填进去 —— 而不是让他对着一个空输入框猜。这条存在的直接原因：过去提示文案里
     * 写死的 `192.168.15.1` 是**外置 T-Box 网关、不在本机网卡上**，用户照填必然失败
     * （真车 `日志/3` 里 10 次尝试全被拦，见 `REF-pitfalls.md` §125）。
     *
     * 空表 = 读不到（或还没有简报）。**不要**把它显示成"没有可用地址"——
     * 两者的下一步动作完全不同。
     */
    val hotspotAddressCandidates: List<CarHotspotEndpointCandidate> = emptyList(),

    /**
     * 车机自带热点的**真实凭据**（设置页「一键填入」按钮用，2026-10-13）。
     *
     * 「复用车机热点」要求手填车机热点的 SSID / 密码，而这两个值用户在本 App
     * 里看不到、只能默写（真车日志 4：`Lynk&Co` ↔ `Lynk & Co` ⇒ 静默连不上）。
     * 探测链路本来就读得到它们，界面据此给一个「点一下就填对」的入口。
     *
     * null = 不是复用模式 / 没探测到车机热点（此时按钮不渲染）。
     * [CarNativeHotspotCredentials.passphrase] 为 null = 车机侧读不到密码，
     * 点击时**只填名称、不动密码框**。
     */
    val nativeHotspotCredentials: CarNativeHotspotCredentials? = null,

    /**
     * 开发者模式状态（设备号 / 采集上传进度 / 最近一次结果）。
     *
     * 只有 [unlocked] 为 true 时界面才显示开发者页；未解锁时这个字段
     * 仍然带着设备号等信息——它不渲染，只是省掉"解锁后再补一次状态"的时序问题。
     */
    val developer: CarDeveloperState = CarDeveloperState(),

    /**
     * 「关于」页版本号被连点的次数（0..6，达到 7 次即解锁）。
     *
     * 放在模型里而不是界面里的 `remember`：界面离开组合会丢计数，
     * 而"连点"的语义是**一段时间内累计**——控制器带超时重置，
     * 界面只负责把每一次点击报上来并显示"还差几次"。
     */
    val developerTapCount: Int = 0,

    /**
     * 在线更新状态（查询 / 下载 / 安装进度）。
     *
     * [CarUpdateState.dialogVisible] 为 true 时由 `CarPlayApp` 在根上弹出对话框——
     * 放在 App 层而不是「关于」页里，因为更新提示可能在用户已经离开关于页
     * （甚至停在主页）时才到达（自动检查是异步的）。
     */
    val update: CarUpdateState = CarUpdateState(),

    /**
     * 「ADB 独立热点」的实时状态（阶段 + 逐步原始输出）。
     *
     * 只在开发者页的自检区渲染。它同时被会话启动路径写入——也就是说
     * 无线连接失败后进开发者页，看到的**就是**刚才那次拉起失败在哪一步，
     * 不需要"再复现一次"。
     */
    val adbHotspot: CarAdbHotspotState = CarAdbHotspotState(),

    /** ADB 初始化历史记录与当前 5556 实测状态。 */
    val adbInitialization: CarAdbInitializationState = CarAdbInitializationState(),

    /**
     * 一次性「请界面跳到某个页面」的请求（控制器 → 界面）。
     *
     * 由 `CarPlayApp` 消费（`LaunchedEffect(request.key)`），消费后**必须**回调
     * [CarUiActions.consumeNavigationRequest] 清空，否则同一个 key 会在
     * 每次重组/回栈时重复生效，把用户锁在那个页面里出不来。
     *
     * 为什么是"一次性请求"而不是"当前页状态"，见 `CarNavigationRequest` 的 KDoc。
     */
    val navigationRequest: CarNavigationRequest? = null,

    /**
     * 「车机原生热点探测」的状态（结论 + 每条命令的原始输出）。
     *
     * 这是**首选方案的第一步**：先问车机现在有没有在跑的热点，有就复用、
     * 没有才走自建（[adbHotspot]）。只在开发者页渲染——它输出很长
     * （十几条 shell 命令 + 三次 `dumpsys`），属于排障信息。
     */
    val nativeHotspot: CarNativeHotspotProbeState = CarNativeHotspotProbeState(),

    /** 仪表地图投屏状态（CarPlay cluster stream）。 */
    val projectionState: CarProjectionState = CarProjectionState(),

    /**
     * 仪表上**两个图层各自的实时状态**（2026-10-23，B2）。
     *
     * `projectionState` 是会话级的（流 / 窗口 / 首帧），回答不了
     * “主题层此刻可见吗”“导航层是收起还是没画面”。本字段由控制器在
     * [CarUiActions] 对应的状态刷新里算一次，**只读**，不参与任何投影决策。
     *
     * 主题与导航分开的两个开关（`ClusterLayerSwitchMapping`）负责“用户要求什么”；
     * 本字段负责“现在实际是什么”，两者一起才能让界面不说谎。
     */
    val clusterLayers: CarClusterLayerStatus = CarClusterLayerStatus(),

    /**
     * HUD 导航转向提示推送的实时状态（2026-10-13，设置页「仪表导航」用）。
     *
     * ## 为什么要有它
     *
     * HUD 那条链路（iPhone → 车机 hudbridge 服务）在车上**完全无声**：
     * 没绑上外部服务、iPhone 一帧都没下发、车机拒收某一帧 —— 三种情况在手机上都只表现为
     * "仪表没有转向卡片"。设置页把 [CarHudStatus] 里那几个计数摊开，用户与现场
     * 一眼就能定位断在哪一段，不必先采诊断包。
     *
     * ## 只读回显，不含行为
     *
     * **开关**不在这个字段里 —— 它是 [settings] 的 `hudGuidanceEnabled`（要能拨、要落盘）。
     * 这里只是"现在实际是什么状态"的读数，来自 `CarSessionHost.hudStatus()`。
     *
     * 默认值 = "本车机没有 HUD 通道"（`supported = false`），界面据此显示一句
     * "本车机没有 HUD 通道"，而不是一片空白 —— 空白的歧义是"没数据"还是"不支持"。
     */
    val hud: CarHudStatus = CarHudStatus(),

    /** Current fresh iPhone route guidance; null means there is no usable route snapshot. */
    val navigation: CarNavigationSnapshot? = null,

    /**
     * 当前可用的仪表副屏候选（诊断 / 状态快照）。
     *
     * 2026-10-20 起设置页不再提供手动选屏；投屏目标由 `CarAppController` 固定优先
     * 使用 displayId=2，不在场时回退自动挑选。
     */
    val clusterCandidates: List<CarClusterCandidateSummary> = emptyList(),

    /**
     * `CarSessionSettings.clusterDisplayId` 的诊断留痕，不参与投屏目标决策。
     */
    val clusterDisplayId: Int = -1,

    /**
     * 仪表「原厂全屏地图模式」的只读快照（阶段 2，2026-10-18）。
     *
     * ## 为什么要有它
     *
     * 阶段 1 的抢屏/还原状态机本身是静默的：仪表停在原厂界面时，用户无法区分
     * 「灰度开关没开」「本机没有厂商接口」「还在等首帧」与「已经接管」。
     * 设置页据此显示「全屏模式」详情页（阶段 3 起：开关 + 当前状态 + 事件历史）。
     *
     * ## 只读
     *
     * 这里**没有**开关的动作接口：开关值来自 [settings] 的 `clusterModeGuardEnabled`，
     * 修改走 `CarUiActions.setClusterModeGuardEnabled`。本字段只回答"现在是什么状态"，
     * 不参与任何接管/还原决策。
     */
    val clusterFullMap: CarClusterFullMapUi = CarClusterFullMapUi(),

    /**
     * 安全区「画框」的坐标空间尺寸（px）—— 主屏上报给 iPhone 的那块画布。
     *
     * ## 为什么必须进 model
     *
     * `CarSessionSettings.safeArea` 存的是**绝对像素矩形**，坐标空间就是这块画布
     * （见 `CarPlayConfigBuilder`：`safeArea.scaledTo(canvasW, canvasH, scaledW, scaledH)`）。
     * 设置页要画那个可拖拽的框，就必须知道画布多大——否则屏幕上的框与真实坐标
     * 不成比例，用户拖出来的值会整体偏。
     *
     * ## 值从哪来 / 拿不到怎么办
     *
     * 来自 `CarAppController.canvas`（`onVideoCanvasSizeChanged` 缓存的上报尺寸）。
     * **为 0 表示还没上报过**（从未进过画面页）⇒ 设置页退化成"只读 + 提示先去连一次"，
     * 绝不拿一个猜的尺寸去写用户数据。
     *
     * ⚠️ 刻意用两个 `Int` 而不是 `car:api` 的 `CarVideoCanvas`：本类型要保持
     * **纯数据**（不引用 Display 体系），预览与单测才能直接构造它。
     */
    val safeAreaCanvasWidthPx: Int = 0,
    val safeAreaCanvasHeightPx: Int = 0,

    /**
     * 底栏（[com.dbtool.carplay.ui.components.CarDockBar]）要显示的应用位。
     *
     * 只在 `settings.dockBar` 打开时才非空：查 `PackageManager` + 把 `Drawable`
     * 转位图都有成本，关着的时候一次都不做（出厂就是关的）。
     */
    val dockApps: List<CarDockApp> = emptyList(),

    /** 底栏左（主驾）温度；null = 没有数据 ⇒ 组件那一块整块不渲染。 */
    val dockLeftTemperature: CarDockTemperature? = null,

    /** 底栏右（副驾）温度；null = 同上。 */
    val dockRightTemperature: CarDockTemperature? = null,

    /** 底栏媒体卡；null = 没有数据 ⇒ 不渲染（含它前面的分隔线）。 */
    val dockMedia: CarDockMedia? = null,

    /**
     * 底栏是否处于**编辑态**（应用槽置灰 + 可拖拽换位）。
     *
     * 由 [CarUiActions.onDockEnterEdit] / [CarUiActions.onDockExitEdit] 驱动；
     * 底栏关掉时恒为 false（编辑一个看不见的栏没有意义）。
     */
    val dockEditMode: Boolean = false,

    /** "选应用"面板的候选（全量可启动应用，**不含图标**，见 [CarDockAppChoice]）。 */
    val dockAppChoices: List<CarDockAppChoice> = emptyList(),

    /** "选应用"面板是否可见。 */
    val dockAppPickerVisible: Boolean = false,
)

/**
 * 仪表两个图层的实时状态快照（2026-10-23，B2）。
 *
 * 纯数据、只读。`themeRequested` / `navigationRequested` 是用户的意图
 * （由显示模式 + 投屏开关决定）；`themeVisible` / `navigationVisible` 是
 * 运行时结论（主题是否解析成功、导航层此刻有没有被自动收起）。
 */
data class CarClusterLayerStatus(
    val themeRequested: Boolean = false,
    /** 主题是否解析成功（文件存在且可用）。 */
    val themeAvailable: Boolean = false,
    /** 主题层是否真的在画：用户要求 + 主题可用 + 窗口已挂。 */
    val themeVisible: Boolean = false,
    val navigationRequested: Boolean = false,
    val navigationVisible: Boolean = false,
    val windowAttached: Boolean = false,
    val firstFrameShown: Boolean = false,
)

/**
 * 一块候选屏的 UI 摘要（不依赖 `car:api` 的 Display 类型，便于预览/单测）。
 */
data class CarClusterCandidateSummary(
    val displayId: Int,
    val label: String,
    val widthPixels: Int,
    val heightPixels: Int,
)

/**
 * 界面向上抛出的全部意图。
 *
 * **UI 不直接操作 Core**：界面只调用这里的回调，由
 * `:car:app` 的 ViewModel 把意图翻译成对
 * `CarSessionController` / `CarSettingsStore` 的调用。
 *
 * 所有方法都有默认空实现，这样预览、单元测试、以及尚未接入的页面
 * 都不需要写一堆桩代码。
 */
interface CarUiActions {
    fun start() {}

    fun stop() {}

    fun reconnect() {}

    /**
     * 提交设置（本地持久化 + 可选重连）。
     *
     * 语义与 upstream 的 "Save & Reconnect" 一致：设置页内部的修改只存在于
     * 界面草稿里，只有本方法被调用时才落盘。`reconnect = true` 表示
     * 立即用新配置重建 CarPlay 栈。
     */
    fun applySettings(settings: CarSessionSettings, reconnect: Boolean) {}


    fun clearPairings() {}

    fun resetSafeArea() {}

    /**
     * 重算一次热点简报（设置页「车机自带热点」那一行点击刷新）。
     *
     * 为什么需要一个显式动作：简报只在**保存设置 / 启动 / 重连**时算一次，
     * 而"看到显示关着 → 去车机设置里打开 → 回来看一眼"是最常见的操作顺序。
     * 没有这个动作，用户得退到首页再进设置页才能刷新，而车机上这一步很别扭。
     *
     * ⚠️ 它重算的是**整份**简报（含 [CarUiModel.hotspotEnabledState]），
     * 不只是开关读数——名字叫 `Briefing` 而不是 `Status` 就是为了不误导。
     * 实现方必须把它丢到后台线程，别在点击回调里直接读 `WifiManager`。
     */
    fun refreshHotspotBriefing() {}

    /**
     * 触发宿主弹出文件选择器（先选私钥，再选证书）。
     *
     * 拆成"触发"与 [importMfiIdentity] 两步，是因为文件选择器只能由 Activity 发起
     * （`ActivityResultContracts`），而 `:car:ui` 不认识 Activity。
     * 界面只负责表达"用户想导入"，由 `:car:app` 完成选文件并回填两个 Uri。
     */
    fun requestMfiIdentityImport() {}

    /**
     * 导入用户自定义的本地 MFi 配件身份。
     *
     * 两个 Uri 必须来自**同一份**配件身份（私钥与证书配对）。
     * 校验失败不会破坏现有身份，失败原因通过 [CarUiModel.mfiIdentity] 回传。
     */
    fun importMfiIdentity(identityUri: Uri, certificateUri: Uri) {}

    /** 清除用户导入的本地 MFi 身份，退回随包内置版本。 */
    fun clearMfiIdentity() {}

    // ---------------------------------------------------------------- AirPlay 图标

    /**
     * 触发宿主弹出图片选择器。
     *
     * 与 [requestMfiIdentityImport] 同理：文件选择器只能由 Activity 发起，
     * 界面只表达"用户想换图标"这个意图。
     */
    fun requestAirPlayIconImport() {}

    /**
     * 导入用户选中的图片作为 AirPlay OEM 图标。
     *
     * 实现侧负责**居中裁剪成正方形**（上游只接受正方形，非正方形会被静默丢弃）
     * 并在失败时保留原有图标。失败原因通过 [CarUiModel.airPlayIcon] 回传。
     */
    fun importAirPlayIcon(iconUri: Uri) {}

    /** 删除用户导入的图标，退回随包内置的默认图标。 */
    fun clearAirPlayIcon() {}

    fun exitApp() {}

    /**
     * 底栏「主页」：回到**车机桌面**。
     *
     * 不是"回到 DBPlay 主页"——底栏模仿的是系统那条栏，它的主页键就是回桌面。
     */
    fun onDockHomeClick() {}

    /** 底栏应用位：按稳定 key（当前实现是包名）启动对应应用。 */
    fun onDockAppClick(key: String) {}

    /**
     * 底栏媒体卡的播放/暂停。
     *
     * ⚠️ 实现方要按**当前状态**决定发 `play` 还是 `pause`，不要发 toggle ——
     * 实车上只发 toggle 是不生效的（见 `CarKeyHidMap.playPauseIndex` 的说明）。
     */
    fun onDockMediaToggle() {}

    /** 底栏媒体卡的下一曲。 */
    fun onDockMediaNext() {}
    fun onImmersiveMediaPrevious() {}

    /** 长按底栏 → 进编辑态（应用槽置灰、可拖拽换位）。 */
    fun onDockEnterEdit() {}

    /** 编辑态下长按底栏 → 退出编辑。 */
    fun onDockExitEdit() {}

    /**
     * 拖拽落位：**完整的新顺序**（应用 key 列表）。
     *
     * 实现方要把它存进 `dockAppOrder` 并重算应用行 —— 顺序是**用户配置**，
     * 不是界面态，重启后必须还在。
     */
    fun onDockReorder(order: List<String>) {}

    /** 打开"选应用"面板（首次打开时后台查一遍全量可启动应用）。 */
    fun onDockOpenAppPicker() {}

    /** "选应用"面板确认：把选中的应用（按面板里的顺序）落成 `dockAppOrder`。 */
    fun onDockAppPickerConfirm(keys: List<String>) {}

    /** 关掉"选应用"面板（不落盘）。 */
    fun onDockAppPickerDismiss() {}

    /**
     * 打开本应用设置页的 ADB 通道卡片，供无线建链失败时导航至 5556 初始化/修复入口。
     *
     * 本方法只导航，不自动初始化，也不启动热点；初始化必须由用户在卡片上明确触发。
     * 与通用设置索引导航保持分离，避免把用户留在需要自行查找的设置首页。
     */
    fun openDeveloperSettings() {}

    /**
     * 手动重连调试隧道（停掉当前连接并重开一轮）。
     * 用途：改了服务器地址/口令后不用重启 App；也是「卡住了」时最省事的一次自救。
     */
    fun restartDebugTunnel() {}

    /**
     * 界面**已经执行**了 [CarUiModel.navigationRequest] 这一次导航，请清空它。
     *
     * 必须调、且必须**在导航动作发出后立刻**调：不清空的话同一个请求
     * 会在下一次重组/返回主页时再生效一次——表现是"用户手动退出设置页，
     * 却被立刻弹回去"，用户被锁死在那个页面里。
     *
     * 由 `CarPlayApp` 的 `LaunchedEffect` 负责调用，业务代码不要手动调它。
     */
    fun consumeNavigationRequest() {}

    // ------------------------------------------------------------ 视频与触摸

    fun onVideoSurfaceAvailable(surface: Surface) {}

    fun onVideoSurfaceDestroyed() {}

    /**
     * 视频区域实测尺寸变化。
     *
     * 这个值会决定与 iPhone 协商的分辨率，因此必须在尺寸稳定后再上报，
     * 否则会反复触发重新握手。
     */
    fun onVideoCanvasSizeChanged(widthPixels: Int, heightPixels: Int) {}

    fun onTouch(contacts: List<CarTouchContact>) {}

    /** 申请缺失的运行时权限（VPN / 无线 / 麦克风 / 定位）。 */
    fun requestPermissions() {}

    // ------------------------------------------------------------ 开发者模式

    /**
     * 「关于」页的版本号被点了一下。
     *
     * 连点 7 次解锁开发者模式。计数与超时**在控制器里**：界面离开组合
     * 会丢掉 `remember`，而"连点"要跨重组累计。
     */
    fun onVersionTap() {}

    /**
     * 进入开发者页时刷新设备号与系统信息。
     *
     * 实现侧负责放到后台线程（读 `getprop` / `dumpsys` 会阻塞），
     * 结果通过 [CarUiModel.developer] 回传。
     */
    fun refreshDeveloperInfo() {}
    fun scanAudioOutput() {}

    /**
     * 采集车机日志并上传到远程诊断服务。
     *
     * 采集/打包/上传都在后台跑，阶段推进通过 [CarUiModel.developer] 回传；
     * 重复调用在上一趟没结束时会被忽略。
     * @param includeClientIpv4 本次明确同意将 Ecarx 当前客户端 IPv4 写入上传包，不记录 MAC。
     */
    fun collectAndUploadLogs(scope: CarLogScope, includeClientIpv4: Boolean) {}

    /** 用户主动请求对 AirPlay TCP 7000 做一次最长 90 秒的 root/tcpdump 握手包头采集。 */
    fun startAirPlayTcpCapture() {}

    /** 退出开发者模式（隐藏页随之关闭）。 */
    fun lockDeveloperMode() {}

    /**
     * 跑一次「ADB 独立热点自检」。
     *
     * 与真正的拉起（[CarUiModel.adbHotspot] 那条会话路径）的区别是：自检
     * **只读**——不写 `/data/softap.conf`、不 ifconfig、不起 hostapd / udhcpd。
     * 它只把每一步的**原始命令输出**摊开，用来回答"这台车机到底是哪一条挂的"。
     *
     * @param interfaceName 非空时跳过自动探测，直接用这个名字；一般传设置页
     *        填的 `adbHotspotInterface`（空串即自动探测）。
     */
    fun runAdbHotspotSelfCheck(interfaceName: String) {}

    /**
     * 跑一次「车机原生热点探测」（**只读**，经 adb shell）。
     *
     * 与 [runAdbHotspotSelfCheck] 的分工：
     * - 本方法回答「**车机现在有什么热点**」——首选方案的依据；
     * - 自检回答「**我们能不能自建一张热点**」——兜底方案的前提。
     *
     * 探测会跑十几条 shell 命令（含三次 `dumpsys`），实测数秒；
     * 实现侧负责放到后台线程，结果与逐条原始输出通过
     * [CarUiModel.nativeHotspot] 回传。
     */
    fun runNativeHotspotProbe() {}

    // 「启动性能优化 · 立即执行一次」的手动触发入口 `runStartupPerfCompile()`
    // 已于 2026-10-08 移除（车主口径：「移除设置-启动-立即执行一次性性能优化」）。
    // 开关本身保留 —— 自动执行路径在 `CarAppController.startSession()` 里直调
    // 私有实现，不需要走本契约。同批删掉了 `CarUiModel.perfCompileResult`
    // （全仓唯一消费者就是那颗按钮下面的结果行）。

    /**
     * 首次/修复时由用户明确触发 5556 初始化；不会启动热点。
     * 回调携带“曾经初始化、持久属性是否验证、当前通道实测状态”三个独立结果。
     */
    fun onInitializeAdb(onResult: (CarAdbInitializationState) -> Unit) {
        onResult(CarAdbInitializationState(detail = "当前界面未接入 ADB 5556 初始化。"))
    }

    /**
     * 「关闭 ADB」（2026-10-15，斌哥口径：「永久开启 ADB 旁新增关闭 ADB 按钮」）。
     *
     * 与 [onInitializeAdb] 是**同一件事的两面**：一个是开、一个是关，
     * 所以两者共用同一份回执类型 [CarAdbInitializationState]，界面不必学第二套状态。
     *
     * ## 关掉的是什么（说清楚，避免夸大）
     *
     * 关的是 **DBPlay 自己的 5556 通道**：落盘禁用标记、收回共享通道、
     * 从此不预热 / 不自愈 / 不巡检 / 不做启动性能优化。
     * **不写任何系统属性、不 `ctl.stop adbd`** —— 车机系统自带的 ADB
     * （厂商那条 `persist.adb.tcp.port=5555`）不受本开关影响，这一点在界面上如实说明。
     *
     * 关掉之后 5556 通道会断，依赖它的功能（自建热点、启动性能优化）当下用不了；
     * 恢复出口就是旁边那颗「永久开启 ADB（5556）」，**不需要 root 也不需要重启**。
     */
    fun onDisableAdb(onResult: (CarAdbInitializationState) -> Unit) {
        onResult(CarAdbInitializationState(detail = "当前界面未接入 ADB 关闭。"))
    }

    /** 旧版本 UI 专用入口，保留空实现以兼容预览；新版热点随会话启停。 */
    @Deprecated("自建热点由 ADB_HOSTAPD 会话生命周期管理")
    fun onStopAdbHotspot() {}

    /**
     * 首次启动引导与 ADB 失败页统一导航到连接设置中的 ADB 通道卡片；该入口只导航不执行。
     */
    fun openHotspotGuide() {}

    /** 「以后再说」的一次性首次启动引导回调。 */
    fun dismissHotspotGuide() {}

    /**
     * 用户手动请求对固定 5556 通道做有限重连与验活；不初始化、不改端口、不切 adbd root。
     */
    fun onHealAdb(onResult: (Boolean, String) -> Unit) {
        onResult(false, "当前界面实现未接入 ADB 自愈")
    }

    // ------------------------------------------------------------ 在线更新

    /**
     * 用户在「关于」页点了「检查更新」。
     *
     * 与启动时的自动检查共用一条链路，区别只在结果怎么反馈：
     * 手动检查无论成功失败都会把结论写在 [CarUiModel.update] 的那一行上。
     */
    fun onCheckUpdate() {}

    /** 开始下载已发现的新版本。 */
    fun onDownloadUpdate() {}

    /**
     * 安装已下载的 APK。
     *
     * 实现侧会拉起系统安装器（或静默提交），**必须在主线程调用**。
     */
    fun onInstallUpdate() {}

    /** 用户关掉更新对话框（强制更新时实现侧会忽略）。 */
    fun onDismissUpdate() {}

    /**
     * 用户切换了「关于」页的「后台自动更新」开关（2026-10-11）。
     *
     * 打开 = "发现新版本后自动下载，并在未连接手机时自动安装"。
     * 实现侧落盘并**立刻生效**（不必重启 App）：
     * 打开时若已有下载好的包，会自行进入"等空闲窗口"；关闭时退回"只提示不自动装"。
     *
     * ⚠️ 关闭再打开 = **解除熔断**（`silentBlocked` 的三种解除方式之一），
     * 这是车主在"自动装不上"时能在车上自救的手段。
     */
    fun onToggleSilentUpdate(enabled: Boolean) {}

    // ===== 仪表地图投屏 =====

    /**
     * 重新枚举仪表副屏候选（设置页「仪表导航」开页时与「重新检测」按钮调用）。
     *
     * 为什么需要单独一个动作：候选清单是 [CarUiModel.clusterCandidates] 的一部分，
     * 只在 model 重算时才更新，而副屏常常在**应用启动之后**才出现（车机延迟上电、
     * HDMI 后插）。不主动重算，用户就会一直看到一份过期的「未检测到副屏」。
     */
    fun refreshClusterDisplays() {}

    /** 开/关仪表地图投屏。 */
    fun setProjectionEnabled(enabled: Boolean) {}

    /**
     * 开/关「全屏模式守护」（阶段 3，2026-10-18）。
     *
     * 与 [setProjectionEnabled] 是两件事：投屏开关决定"要不要把画面送到仪表"，
     * 本开关只决定"送到仪表时是否主动把车机切到全屏地图模式、退出时是否还原"。
     * 关闭 = 保持阶段 1 之前的旧路径（建窗即抢屏，不主动还原）。
     */
    fun setClusterModeGuardEnabled(enabled: Boolean) {}

    /**
     * 设置页的「投到仪表 / 收起仪表」按钮。
     *
     * 显示时负责建窗并投屏；收起时拆除副屏窗口并把仪表还给车机原厂。
     * 这是用户手动控制投屏的入口；路线自动投屏走独立的导航状态链路。
     */
    fun setClusterVisible(visible: Boolean) {}
    /** Bounded gesture metadata only; never raw touch coordinates or trajectories. */
    fun reportProjectionGesture(message: String) {}
    fun onImmersiveSurfaceAvailable(surface: Surface?) {}
    /** Application-owned window memory survives video view disposal and external-app returns. */
    val immersiveNavigationWindowSelected: Boolean get() = true
    fun rememberImmersiveNavigationWindow(navigation: Boolean) {}

    /**
     * 只切换「iPhone 画不画」这一层，**不动副屏窗口**。
     *
     * 保留给窗口已经建立、只想临时隐藏或恢复画面的场景；完整收起投屏请用
     * [setClusterVisible]，这样会同时拆除窗口并把仪表还给车机原厂。
     */
    fun toggleProjectionUi() {}

    /** 切换仪表显示内容（地图/转向卡/整仪表）。 */
    fun setProjectionContent(url: String) {}

    /**
     * 保存并立即应用左右圆表布局（2026-10-21）。
     *
     * 实时生效 + 持久化：写 SharedPreferences 后立刻下发给 presenter，
     * 仪表屏一帧内更新；重启后由控制器读取并补应用。
     */
    fun setDialLayout(layout: com.dbtool.carplay.api.display.ClusterDialLayout) {}

    /**
     * 保存并立即应用自绘区域（2026-10-22）。
     *
     * 实时生效 + 持久化：写 SharedPreferences 后立刻下发给 presenter，
     * 仪表屏一帧内更新；重启后由控制器读取并补应用。
     */
    fun setDrawRegion(region: com.dbtool.carplay.api.display.ClusterDrawRegion) {}

    /**
     * 保存并立即应用仪表视频层本机变换（2026-10-22）。
     *
     * 只改 `TextureView` 的纹理矩阵，不改会话、不重连。
     */
    fun setVideoTransform(transform: com.dbtool.carplay.api.display.ClusterVideoTransform) {}

    /**
     * 立即切换仪表浅色/深色/跟随系统并落盘。
     *
     * 只替换现有主题 View 的 palette，不重连、不重建副屏 Presentation。
     */
    fun setClusterAppearanceMode(mode: com.dbtool.carplay.api.display.theme.ClusterAppearanceMode) {}

    /** 缩放仪表地图。 */
    fun zoomProjectionMap(zoomIn: Boolean) {}
}
