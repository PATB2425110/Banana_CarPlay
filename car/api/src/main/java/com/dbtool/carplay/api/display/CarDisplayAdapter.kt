package com.dbtool.carplay.api.display

/** 一块可用的物理/逻辑显示屏。 */
data class CarDisplay(
    /** 平台侧 display id，默认屏为 0（对应 Android `Display.DEFAULT_DISPLAY`）。 */
    val id: Int,
    val widthPixels: Int,
    val heightPixels: Int,
    val densityDpi: Int,
    /** 物理尺寸，车机需要它来向 iPhone 声明 CarPlay 显示物理大小。 */
    val widthPhysicalMm: Int? = null,
    val heightPhysicalMm: Int? = null,
    val refreshRateHz: Double? = null,
    /** 该屏是否为主屏（CarPlay 主界面）。 */
    val primary: Boolean = true,
)

/** 屏内安全区（px）。用于 iPhone 侧 viewArea / safeArea 声明。 */
data class CarSafeArea(
    val top: Int = 0,
    val bottom: Int = 0,
    val left: Int = 0,
    val right: Int = 0,
)

/**
 * 画布尺寸对账用的**实测快照**（2026-10-07）。
 *
 * ## 为什么需要它
 *
 * 0.10.10 车机日志里，报给 iPhone 的握手分辨率是 `1920x1008@60`，而面板是
 * 1920x1080 —— 差的 72px 是顶部状态栏。这个差值本身是**正常避让**，但它直接
 * 决定了 iPhone 编出来的画面尺寸，再被拉伸到面板上就会软化（用户看到的"模糊"）。
 *
 * 要判断"该按面板报还是按窗口报"，必须先分清三种情况，而它们在**只看窗口尺寸时
 * 无法区分**：
 *
 * | 情况 | 面板高 | 窗口高 | 栏 inset |
 * |---|---|---|---|
 * | 正常避让 | 1080 | 1008 | 顶 72 / 底 0 |
 * | 厂商在窗口层切 | 1080 | 1008 | 顶 0 / 底 0 （量不到）|
 * | 画面已顶格 | 1080 | 1080 | 有值但不生效 |
 *
 * 所以把三个数字**一次性**取出来放进同一条日志，避免分次采样串不上。
 */
data class CarDisplayProbe(
    /** 面板（物理屏）尺寸——权威值。 */
    val panelWidthPx: Int,
    val panelHeightPx: Int,
    /** 顶部状态栏在当前窗口里的 inset。0 表示量不到（车机未在窗口层声明）。 */
    val topInsetPx: Int,
    /** 底部导航栏的**可见** inset。 */
    val bottomInsetPx: Int,
    /** 底部导航栏**忽略可见性**时占的空间（>0 说明系统预留了这块地）。 */
    val bottomIgnoringVisibilityPx: Int,
    /** 车机 SystemUI 状态栏窗口的实测高度（从 SystemUI 窗口测量日志反推）。0 表示取不到。 */
    val statusBarWindowHeightPx: Int,
)

/**
 * 向 iPhone 声明的分辨率策略。
 *
 * upstream 用"缩放系数 0.3x~1.0x"表达（`CarPlayDisplayScale`），
 * 这里保留同样语义，并额外允许车机按自己的面板特性覆盖。
 */
data class CarResolutionPolicy(
    /** 0.3x ~ 1.0x，与 upstream `displayScaleTenths` 口径一致（如 10 表示 1.0x）。 */
    val scaleTenths: Int = DEFAULT_SCALE_TENTHS,
    /** 是否强制按面板原生分辨率声明，忽略缩放。 */
    val forcePanelResolution: Boolean = false,
    val fps: Int = DEFAULT_FPS,
) {
    companion object {
        const val DEFAULT_SCALE_TENTHS = 10
        const val DEFAULT_FPS = 60
    }
}

/**
 * 显示适配：车机有哪些屏、物理尺寸多少、安全区与分辨率策略。
 *
 * 边界：**不负责 CarPlay 画面渲染**。渲染仍由 Core 的
 * `MediaSink` + `AndroidMediaSink`(MediaCodec→Surface) 完成，
 * 本适配器只提供"往哪块屏、按什么规格声明"的决策输入。
 */
interface CarDisplayAdapter {
    /** 主显示信息；生成 AirPlayConfig 时使用。 */
    fun primaryDisplay(): CarDisplay

    /** 所有可用于 CarPlay 的显示（副驾屏/仪表屏等）。 */
    fun availableDisplays(): List<CarDisplay> = listOf(primaryDisplay())

    /** 该屏的安全区。车机 UI 有固定状态栏/装饰件时必须覆盖。 */
    fun safeArea(display: CarDisplay): CarSafeArea = CarSafeArea()

    /** 分辨率声明策略。 */
    fun resolutionPolicy(display: CarDisplay): CarResolutionPolicy = CarResolutionPolicy()

    /**
     * 车机是否需要"裁掉"顶部/底部栏（fullscreen 模式）。
     * 默认沿用 upstream 的 true。
     */
    fun hideSystemBars(): Boolean = true

    /**
     * 画布尺寸对账快照（2026-10-07，探测用）。
     *
     * 默认返回**全零面板**的空快照——非 Android 平台（或未实现的车机）不参与
     * 对账，调用方据此跳过打印即可，不必判 null。
     *
     * ⚠️ 实现里**不要**去 `Activity` 拿窗口：本方法可能在无窗口时被调（会话重建
     * 早于界面挂载）。取不到就如实返回 0，别抛。
     */
    fun probeCanvasMetrics(): CarDisplayProbe = CarDisplayProbe(0, 0, 0, 0, 0, 0)

    /** 显示配置发生变化（分辨率切换、屏插拔、旋转）。 */
    fun observeDisplayChanges(listener: (CarDisplay) -> Unit) {}

    fun removeDisplayListener(listener: (CarDisplay) -> Unit) {}
}
