package com.dbtool.carplay.api.session

import com.dbtool.carplay.api.media.CarNowPlayingSource
import com.dbtool.carplay.api.navigation.CarHudStatus
import com.dbtool.carplay.api.navigation.CarNavigationSource

/**
 * 应用层需要的完整会话能力：状态/命令契约 + 视频输出宿主。
 *
 * 存在的理由：`:car:api` 把"会话控制"（[CarSessionController]）与
 * "视频输出"（[CarVideoSurfaceHost]）刻意拆成两个接口
 * （见 `CarVideoSurfaceHost` 的注释），但应用层从工厂拿到的是同一个对象。
 * 用一个组合接口表达"这两件事由同一个实例提供"，
 * 避免应用层写两个字段、两次赋值、两处可能不一致。
 *
 * 用组合而不是让 `CarSessionController : CarVideoSurfaceHost`：
 * 后者会让"只想订阅状态"的测试替身被迫实现 Surface 相关方法。
 */
interface CarSessionHost : CarSessionController, CarVideoSurfaceHost {

    /**
     * 请求对端补一个仪表副屏（ALT 111）关键帧。
     *
     * 平台层在「模式已回读到 3、但首帧迟迟不到」时半程催一次 I 帧；
     * 默认 false = 本实现不提供该能力（测试替身 / 未连接时也应是 false）。
     */
    fun requestClusterKeyFrame(): Boolean = false

    /**
     * 「当前正在播放」的数据源（2026-10-11）。
     *
     * 存在的理由：数据住在 `:car:core-bridge` 的 `CarPlayNowPlayingClient` 里，
     * 而底栏（`:car:ui`）要**读**它（歌名/封面/进度）也要**发令**它（⏸ / ⏭）。
     * 之前这条通道只往车机平台的媒体卡片推（`CarMediaAdapter.attachNowPlaying`），
     * 应用层自己的界面反而是看不到的 —— 于是底栏上的 ⏸ ⏭ 只能是死按钮。
     *
     * **默认 null**：不是所有实现都要暴露它（测试替身、还没接的平台），
     * 拿到 null 的调用方应当整块不渲染媒体卡，而不是显示空壳。
     *
     * 返回的对象**未连接时也是安全的**：`observe` 会给出 null，三条命令是空操作。
     */
    fun nowPlayingSource(): CarNowPlayingSource? = null

    /**
     * Current route guidance received from the connected iPhone.
     *
     * Unlike [hudStatus], this source is vehicle-platform neutral and does not
     * require a vehicle HUD service. The default null keeps older hosts and test
     * doubles source-compatible.
     */
    fun navigationSource(): CarNavigationSource? = null

    fun phonePowerSource(): CarPhonePowerSource? = null

    /**
     * HUD 导航转向提示推送的实时状态（2026-10-13，设置页「仪表导航」用）。
     *
     * ## 为什么挂在会话宿主上
     *
     * 桥侧的绑定状态住在平台层（`CarPlatform.hud`），而"iPhone 发了几帧、去掉几帧"
     * 只有会话内的 `CarPlayNavigationHudClient` 知道。两边合成的**唯一**位置就是会话桥
     * —— 界面（`:car:ui`）看不到平台，也不该看到。
     *
     * ## 默认实现 = "本车机没有 HUD 通道"
     *
     * 不是所有实现都要暴露它（测试替身、还没接的平台）。拿到默认值的界面应当显示
     * "本车机没有 HUD 通道"，而不是一片空白 —— 空白的歧义是"没数据"还是"不支持"。
     *
     * ## 实现要求
     *
     * 与 `CarHudBridge.status()` 同一口径：**只读、便宜、不阻塞、不抛**。
     * 它在渲染路径上被取（`CarAppController.refreshModel()`），不得触发绑定或 I/O。
     */
    fun hudStatus(): CarHudStatus = CarHudStatus()

    /**
     * 注册「导航启动自动投屏」的**显隐落点**（PATCH-0119，2026-10-14 车主口径）。
     *
     * ## 为什么是一个注册口，而不是让桥自己投
     *
     * 触发端（`CarPlayNavigationBridge` 的订阅 + 导航停超时）住在 `:car:core-bridge`，
     * 它只知道"该显示 / 该收起"。而**真正把画面投上去**的动作在应用层：
     * `CarUiActions.setClusterVisible`（= `CarAppController.showClusterProjection` /
     * `hideClusterProjection`）—— 那一条会建窗 + `seizeCluster()` 抢屏、收起时拆窗 +
     * `exitFullNavi()` 还屏。
     *
     * 让它归应用层是**刻意**的：车上有平台副屏窗口 owner（吉利）时，建窗的唯一入口
     * 必须只有一处，否则会重演"两套并行各建一块窗、后建的抢走 ALT(111) 轨"
     * （见 `CarClusterPresenter` 的定案）。所以桥只把请求递过去，动作仍由应用层做。
     *
     * ## 实现要求
     *
     * - **主线程**：桥在 iAP2 帧到达的线程上拿到请求，会先切到主线程再调 [sink]
     *   （建窗 `Presentation.show()` 与拆窗都要求主线程）；
     * - 幂等：同一个结论只会被请求一次（触发端自己去重），但实现仍应按"已经是这个状态
     *   就什么都不做"处理（重复请求不该重建窗口）。
     *
     * ## 默认实现 = 什么都不做
     *
     * 不是所有实现都要暴露它（测试替身、没有仪表能力的平台）。拿到默认实现的实现侧
     * 行为是"请求被忽略、只在日志里留痕"，而不是崩溃。
     *
     * @param sink `true` = 请求显示，`false` = 请求收起；传 `null` 注销（会话关闭时）。
     */
    fun setClusterVisibilitySink(sink: ((Boolean) -> Unit)?) {}
}
