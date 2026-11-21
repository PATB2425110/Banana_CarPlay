package com.dbtool.carplay.api.display

import android.view.Surface

/**
 * 仪表盘（副屏）呈现契约。
 *
 * ## 它解决什么
 *
 * 上游 AirPlay 协议层**本来就支持第二块屏**：
 * `AirPlayInfoPlist` 里 `STREAM_TYPE_MAIN_SCREEN = 110` / `STREAM_TYPE_ALT_SCREEN = 111`，
 * 且 `AirPlayConfig` 有 `cluster: AirPlayDisplayConfig?` 字段——`/info` 里只要
 * `cluster` 非空，就会向 iPhone 声明第二块屏，iPhone 随即开一条 111 的独立视频流。
 *
 * 但"**把这条流显示到车机仪表盘那块非主屏上**"属于厂商差异：每家的屏 id、
 * 分辨率、呈现方式（`Presentation` / 硬件视频层 / 车机专有 API）都不同。
 * 本接口把这段差异收敛到 `:car:platform-*`，Core 与 UI 完全不感知。
 *
 * ## 与 [CarDisplayAdapter] 的分工
 *
 * - [CarDisplayAdapter] 回答"**这块屏按什么规格向 iPhone 声明**"（尺寸 / 安全区 / 帧率）。
 * - 本接口回答"**这块屏上有个 Surface，谁来承载它**"（创建 / 销毁 / 尺寸）。
 *
 * 两者刻意分开：声明是纯数据、可单测；呈现必然要碰 `Context` / `WindowManager` /
 * `DisplayManager`，且不同车机差异极大。
 *
 * ## 线程与生命周期
 *
 * - [start] / [stop] 会在主线程调用（车机 `Presentation` 必须在主线程建）。
 * - [start] 之后 [onSurfaceAvailable] 会在主线程回调，**可能多次**（重建 Presentation）。
 * - [stop] 之后不得再回调；实现需保证幂等。
 *
 * 实现必须容忍"屏不在 / 用户没开副屏功能"：此时 [start] 什么都不做，
 * 不回调即可（**不要**抛异常——副屏只是锦上添花，不该拖垮主会话）。
 */
interface CarClusterPresenter {

    /** 本车机是否提供可用的仪表副屏。false 时上层应禁用副屏设置项。 */
    fun isAvailable(): Boolean

    /**
     * 列出当前所有**可选**的副屏候选（供设置页下拉，让用户自己挑）。
     *
     * ## 为什么需要它（2026-10-08）
     *
     * 之前由实现**自动挑**一块（判据「非主屏 + 恰好 1920×1080 + 唯一命中」），
     * 隐含假设「车上那块仪表屏有且只有一块」。现场推翻：车上有多块非主屏，
     * 自动挑抓错了 ⇒ 用户看到「主屏上出现副屏画面」。改为**列出全部候选，
     * 用户选一块**（选中值存 `CarSessionSettings.clusterDisplayId`）。
     *
     * 默认空实现：非副屏平台 / 未接入时返回空表，UI 显示「未检测到副屏」。
     */
    fun listCandidates(): List<CarClusterCandidate> = emptyList()

    /**
     * 当前这块副屏的声明规格；无可用副屏时为 null。
     *
     * ## 为什么需要它
     *
     * 上游 AirPlay `/info` 要在**建栈之前**就拿到副屏尺寸（否则不声明第二块屏），
     * 而"哪块屏、多宽多高"是平台才知道的事。让上层自己查 `DisplayManager`
     * 会与 [start] 内部的选择逻辑各查各的，出现"声明了 A 屏、窗口却挂在 B 屏"。
     *
     * 因此由实现统一给出规格：`resolveClusterSpec()` 用它填 `/info`，
     * [start] 则保证挂在**同一块屏**上。
     *
     * 实现须便宜（每次建栈会被调用）且不抛。
     */
    fun currentSpecOrNull(): CarClusterSpec?

    /**
     * 开始呈现。装配副屏 UI 并把它的视频 Surface 通过 [onSurfaceAvailable] 回调出去。
     *
     * @param spec 该屏向 iPhone 声明的规格（宽高是**面板**尺寸，不是画布尺寸）。
     * @param onSurfaceAvailable Surface 就绪时回调；传 null 表示 Surface 已销毁。
     */
    fun start(spec: CarClusterSpec, onSurfaceAvailable: (Surface?) -> Unit)

    /** 结束呈现，释放副屏窗口。幂等。 */
    fun stop()

    /** 副屏规格变化（屏插拔 / 分辨率切换）。实现可重建窗口，也可忽略。 */
    fun onSpecChanged(spec: CarClusterSpec) {}

    /**
     * 副屏窗口此刻是否**挂在屏上**（有一块窗口存在）。
     *
     * ## 为什么要有它（2026-10-08）
     *
     * 反编译原版只有一个窗口 owner（`GeelyClusterSurfaceOwner`），所以"窗口在不在"
     * 只有一处能回答。DBPlay 一度有两套（本接口的实现 + `:car:core-bridge` 的
     * `ClusterProjectionController`），上层要报"投屏状态"时不知道问谁，于是两套各报
     * 各的，还各自往 ALT(111) 轨塞 Surface。
     *
     * 定案：**有本实现（`platform.cluster != null`）时，窗口归本接口独占**，
     * core-bridge 不再建窗。上层据此读窗口状态。
     *
     * 默认 false —— 不提供窗口能力的实现不必关心。
     */
    fun isWindowAttached(): Boolean = false

    /**
     * 副屏窗口是否**已出画**（收到第一帧）。
     *
     * 默认 false。与 [isWindowAttached] 同源：只有窗口 owner 才知道首帧何时到。
     */
    fun isRendering(): Boolean = false

    /** Explicit user open, including when a window already exists but OEM mode is off. */
    fun onManualProjectionOpen() {}

    /**
     * 应用仪表图层模式（三模式分层）。默认空实现，老实现不受影响。
     *
     * 契约：**不拆窗、不断流**。模式变化只改窗口内图层的可见性与几何，
     * 不得触发 attach / detach、解绑 Surface 或重连。
     * 窗口尚未建立时，实现应记住该值，并在建窗后立即应用一次。
     */
    fun applyClusterLayerMode(mode: ClusterLayerMode) {}

    /**
     * 单独打开/关闭**导航视频层**（2026-10-22 解耦，P0）。
     *
     * 与 [applyClusterLayerMode] 的区别：模式决定“主题与导航谁该出现”，
     * 本方法只表达“导航这一刻要不要显示”。主题层完全不受影响。
     *
     * 契约与 [applyClusterLayerMode] 相同：**不拆窗、不断流、不解绑 Surface**；
     * 导航层关闭时用 `INVISIBLE` 保留视图与 BufferQueue，重新打开时下一帧即可恢复。
     * 窗口尚未建立时实现应记住该值，建窗后补应用。默认空实现。
     */
    fun applyNavigationVisible(visible: Boolean) {}

    /**
     * 应用左右圆表的自定义布局（2026-10-21）。
     *
     * 与 [applyClusterLayerMode] 同性质：**不得**触发 attach / detach、解绑 Surface 或重连；
     * 只改变已有绘制实例的变换参数，一帧内生效。
     *
     * 窗口尚未建立时，实现应记住该值，并在建窗后立即应用一次。
     * 默认空实现：不支持自定义表盘布局的平台不受影响。
     */
    fun applyDialLayout(layout: ClusterDialLayout) {}

    /**
     * 应用 DBPlay 自绘层的绘制区域（2026-10-22）。
     *
     * 契约与 [applyDialLayout] 相同：**不得**触发 attach / detach、解绑 Surface
     * 或重连；只改已有皮肤 View 的绘制参数，一帧内生效。
     * 窗口尚未建立时，实现应记住该值，并在建窗后立即应用一次。
     * 默认空实现：不支持自绘层的平台不受影响。
     */
    fun applyDrawRegion(region: ClusterDrawRegion) {}

    /**
     * 应用仪表视频层的本机画面缩放/平移（2026-10-22）。
     *
     * 只改呈现层 `TextureView` 的纹理矩阵，**不向 iPhone 发命令、不解绑 Surface、
     * 不改视频缓冲区尺寸**。窗口尚未建立时，实现应记住该值并在建窗后补应用。
     * 默认空实现：不支持本机画面变换的平台不受影响。
     */
    fun applyVideoTransform(transform: ClusterVideoTransform) {}

}

/**
 * 「用户选屏」能力契约（2026-10-08）。
 *
 * ## 为什么要单独一个接口，而不是塞进 [CarClusterPresenter]
 *
 * 不是所有副屏实现都支持"多屏可挑"——有的平台就一块固定副屏。单独的接口让
 * 支持的实现去实现它、上层用 `as?` 探测，避免往主契约里加"大多数实现都空转"
 * 的方法。与 Kotlin 里 `Comparable` 这类可选能力接口同思路。
 */
interface CarClusterDisplaySelection {
    /**
     * 设置用户选中的仪表 displayId（-1 = 未选）。
     *
     * 上层从 `CarSessionSettings.clusterDisplayId` 读出后灌入，变更时重新调用。
     */
    fun setRequestedDisplayId(id: Int)
}

/**
 * 「副屏画面 180° 翻转」能力契约（2026-10-11）。
 *
 * ## 为什么需要
 *
 * 副屏常常是**倒装**的：一块 HDMI 屏塞进原仪表壳体，上下颠倒装。
 * 这种翻转是**物理**的 —— 车机 `DisplayManager` 报的 `rotation` **恒为 0**，
 * 系统与 iPhone 都不知道，只能在**呈现层**把画面转 180°。
 *
 * 真车实证（CS11 / `cs11_high`，2026-10-11 诊断包
 * `1791702691839_f70d0ce5`）：`system/environment.txt` 里 5 块屏
 * （内置 + 3 块 HDMI + 虚拟屏）**旋转全为 0**，而现场仪表画面是倒的。
 *
 * ## 为什么单独一个接口，而不是塞进 [CarClusterPresenter]
 *
 * 同 [CarClusterDisplaySelection]：不是所有平台都倒装，用 `as?` 探测，
 * 避免往主契约里加"大多数实现都空转"的方法。
 *
 * ## 语义
 *
 * - 只改**窗口里的画面朝向**，**不改**报给 iPhone 的声明
 *   （尺寸 / 安全区 / 帧率一律不变）⇒ 切换**不需要重连**，实现应立刻
 *   应用到**已存在**的窗口上。
 * - 实现必须幂等：同一值重复设置不产生副作用。
 */
interface CarClusterFlipControl {
    /**
     * 是否把副屏画面旋转 180°（false = 原样，默认）。
     *
     * 实现应立即生效：窗口已在时直接改视图变换，不要等到下次 `start()`。
     */
    fun setFlip180(enabled: Boolean)
}

/**
 * 副屏声明规格。
 *
 * 与 [CarDisplay] 同形但不复用，理由同 `PhysicalSizeMm`：`car:api` 里
 * "屏"的语义有两处（主屏声明 / 副屏声明），刻意各自成型，避免把
 * "主屏才是 primary" 这类假设带进副屏路径。
 */
data class CarClusterSpec(
    /** 车机侧 display id。**必须是非主屏**——主屏由 CarPlay 主界面占用。 */
    val displayId: Int,
    /** 申报给 iPhone 的像素尺寸（= 面板尺寸）。 */
    val widthPixels: Int,
    val heightPixels: Int,
    val densityDpi: Int,
    val widthPhysicalMm: Int? = null,
    val heightPhysicalMm: Int? = null,
    /** 申报帧率。 */
    val fps: Int = CarResolutionPolicy.DEFAULT_FPS,
    /**
     * 画面在面板内的实际显示高度（px）。
     *
     * ## 为什么副屏要单独有这个字段
     *
     * 上游 AirPlay 的 "cluster" 屏语义与主屏不同：它常常**只用面板的一部分**。
     * 例如吉利车机的仪表 HDMI2 面板是 1920x1080，但视频画面只铺满上方 720px，
     * 下方 360px 留给车机自己的仪表（油量/车速等）并保持透明。
     *
     * - 0 或 >= [heightPixels] ⇒ 视为铺满，等同主屏行为；
     * - 否则由实现把视频层按此高度摆放、其余区域透明。
     *
     * 传 0 更安全：不清楚车机布局时不要凭空猜一个高度。
     */
    val visibleHeightPixels: Int = 0,

    /**
     * 报给 iPhone 的副屏安全区 insets（px，基于本屏面板尺寸）。
     *
     * ## 为什么需要
     *
     * 吉利仪表面板 1920×1080，但导航内容只该出现在上方核心区（默认 1865×660），
     * 下方留给车机自己的油量/车速表。**不报安全区时** iPhone 认为整块面板都可用，
     * 会把导航铺满、画到被饰板/仪表遮挡的位置——即"画面绘制到安全区外"。
     *
     * 原版 `GeelyClusterSafeArea.toInsets()` 干的就是这件事。这里用中性类型表达，
     * 避免 `:car:api` 依赖 `:shared`。
     *
     * `null` ⇒ 不声明（整屏），等同修复前行为。
     */
    val safeArea: CarClusterSafeAreaInsets? = null,

    /**
     * 允许 iPhone 把 UI 画到安全区外（`drawUIOutsideSafeArea`）。
     *
     * `null` ⇒ 用上游默认（`true`）。取值来自用户设置
     * 「显示与视频 → 画面绘制到安全区外」，**副屏与主屏共用同一个开关**。
     */
    val safeAreaDrawOutside: Boolean? = null,
)

/**
 * 副屏安全区（px insets，源坐标 = 该屏面板尺寸）。
 *
 * `:car:api` 的中性表达：不引用 `AirPlayInsets`，由 `:car:core-bridge`
 * 在生成 plist 时翻译成上游类型。
 */
data class CarClusterSafeAreaInsets(
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
) {
    /**
     * 把这四边**夹进面板尺寸**里，保证它们描述的是一个"真的在屏内"的区域。
     *
     * ## 🔴 为什么必须有这一道（2026-10-11 真车日志证实）
     *
     * `/info` 的 `safeArea` 是**由 insets 现算**的（上游 `AirPlayInfoPlist.areaDict`）：
     * `heightPixels = 面板高 - top - bottom`。所以只要 `top + bottom > 面板高`，
     * 报给 iPhone 的 `safeArea.heightPixels` 就是**负数**。
     *
     * 真车实测（CX11，app 1.0.37）：两块屏都报了
     * `safeArea={width 1912, height **-1560**, originX 1, originY 1920}` ——
     * 反解出来正是 `top=1920`（**比 1080 的面板还高**）、`bottom=720`、`left=1`、`right=7`。
     * 而 iPhone 拿到这种自相矛盾的 safeArea 会**直接拆会话**（`iAP tunnel peer EOF` + 空 TEARDOWN）。
     *
     * ## 原版怎么保证不会这样（`GeelyClusterSafeArea`）
     *
     * 原版的"画框"存的是**矩形** `(x, y, w, h)`，并且**先归一化再转 insets**：
     * `normalized()` 把 `w/h` 夹进画布、再把 `x/y` 夹进 `0..canvas-w/h`；`toInsets()` 才做
     * `top=y / bottom=(H-y)-h / left=x / right=(W-x)-w` ⇒ 四边**恒 ≥ 0** 且自洽。
     * 我们这边少了那一步归一化（外部直接把四个数当 insets 塞进来），于是把"矩形数"当"内边距"用。
     *
     * ⚠️ 这一道是**兜底防线**（保证发出去的 safeArea 永远合法），不是"数据源已经对了"的证明：
     * 数据源（谁产出了 `top=1920`）仍要按原版模型改成"矩形 → normalized → toInsets"。
     */
    fun clampedTo(panelWidthPixels: Int, panelHeightPixels: Int): CarClusterSafeAreaInsets {
        val w = panelWidthPixels.coerceAtLeast(1)
        val h = panelHeightPixels.coerceAtLeast(1)
        val l = left.coerceIn(0, w - 1)
        val t = top.coerceIn(0, h - 1)
        return CarClusterSafeAreaInsets(
            left = l,
            top = t,
            // 右边剩下的空间最多到 `w-1-l`：这样 `left+right ≤ w-1` ⇒ 宽度恒 ≥ 1。
            right = right.coerceIn(0, w - 1 - l),
            bottom = bottom.coerceIn(0, h - 1 - t),
        )
    }
}

/**
 * 一块可选的仪表副屏候选（供设置页下拉展示）。
 *
 * 纯数据，不含 `Display`/`Context`，便于 UI 层与单测使用。
 */
data class CarClusterCandidate(
    /** 车机侧 display id —— 用户选中后存进 `CarSessionSettings.clusterDisplayId`。 */
    val displayId: Int,
    /** 展示用标签，如 `副屏 · displayId=1 · 1920×1080`。 */
    val label: String,
    val widthPixels: Int,
    val heightPixels: Int,
)
