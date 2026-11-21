package com.dbtool.carplay.android

import android.os.Build
import android.view.WindowInsets

/**
 * 系统栏 inset 的**跨层快照**（2026-10-07，探测用）。
 *
 * ## 为什么需要这样一个持有器
 *
 * 画布尺寸对账（见 `CarDisplayProbe`）需要"顶部状态栏占多少、底部导航栏占多少"，
 * 而这两个值只有在**界面挂载之后**、从 `View.rootWindowInsets` 才拿得到。
 *
 * 但对账的调用点（`CarPlaySessionBridge` 生成协商尺寸时）可能早于界面挂载 ——
 * 会话重建和 Activity 生命周期并不同步。直接在 bridge 层持有 `Activity`
 * 会把 Core 依赖到 UI，破坏 `CarPlatform` 的依赖方向。
 *
 * 因此用一个进程级的**单向快照**：UI 层（`MainActivity.applySystemBars`）
 * 每次测量到就写进来，bridge 层读快照。写方唯一、读方无副作用。
 *
 * ## 刻意的设计取舍
 *
 * - **用 `@Volatile` 而不是锁**：只有一个写入者（主线程），且允许读到"稍微旧一点"
 *   的值 —— 对账日志差几十毫秒无意义。用锁反而在会话重建路径上引入不必要的争用。
 * - **不持久化**：进程重启后为全 0，此时对账行会如实显示"未测得"，这是诚实的行为，
 *   比沿用上次进程的数字更安全（避免拿旧屏的数去对新屏）。
 * - **不缓存到磁盘**：这是诊断数据，不是配置。
 *
 * ## 与 `MainActivity` 那行"系统栏回读"的关系
 *
 * 两者同源。`MainActivity` 打印的是**带语义的结论**（隐藏请求被接受还是被拒绝），
 * 这里提供的是**可供 bridge 计算的裸数字**。两行日志合起来才是完整证据链：
 *
 * ```
 * 画布对账｜面板=1920x1080｜窗口=1920x1008｜顶栏inset=0px 底栏inset=0px ...
 * 系统栏回读：请求 top=hide bottom=hide｜实测顶部 inset=72px｜结论=隐藏被车机拒绝
 * ```
 */
object CarWindowInsetsHolder {

    /** 一次测量到的系统栏快照（px）。 */
    data class Snapshot(
        /** 顶部（状态栏）inset，可见时的高度。 */
        val topPx: Int = 0,
        /** 底部（导航栏）inset，可见时的高度。 */
        val bottomPx: Int = 0,
        /** 底部栏"忽略可见性"时占的空间 —— >0 且 bottomPx==0 说明系统预留了这块地但当前隐藏。 */
        val bottomIgnoringVisibilityPx: Int = 0,
        /** 车机 SystemUI 状态栏窗口的实测高度（由 SystemUI 的窗口测量反推），0 表示取不到。 */
        val statusBarWindowHeightPx: Int = 0,
    ) {
        companion object {
            val EMPTY = Snapshot()
        }
    }

    @Volatile
    private var current: Snapshot = Snapshot.EMPTY

    /** UI 层每次拿到 insets 后调用。 */
    fun update(snapshot: Snapshot) {
        current = snapshot
    }

    /**
     * 从原始 `WindowInsets` 直接构造并写入，省得调用方自己拆字段。
     *
     * ⚠️ 2026-10-10：本工程**运行机是 Android 9（SDK 28）**，而
     * `WindowInsets.Type.*` / `getInsets(int)` / `getInsetsIgnoringVisibility(int)`
     * 都是 **API 30** 才有的接口。这里按 SDK 分支：
     *
     * - 30+：走新 API（语义最准，区分"可见"与"忽略可见性"）；
     * - 28/29：走同语义的废弃方法 `systemWindowInsetTop/Bottom`；"忽略可见性"
     *   在旧框架上**没有等价物** ⇒ 如实填 0（不猜），与 `MainActivity` 回读
     *   量不到时填 0 的口径一致。
     *
     * 不这样写就是定时炸弹：编译能过，旧机上**一调用就 `NoSuchMethodError`**
     * —— 与 2026-10-10 仪表窗口那次事故（`ViewTreeObserver` 用了 API 34 的监听器）
     * 同一族。现在模块的 lint 已把 NewApi 提到 fatal，这类调用会直接卡住出包。
     */
    fun updateFromInsets(insets: WindowInsets?, statusBarWindowHeightPx: Int = 0) {
        if (insets == null) {
            current = Snapshot.EMPTY
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val top = insets.getInsets(WindowInsets.Type.statusBars()).top
            val bottom = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            val bottomIgnoring =
                insets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()).bottom
            current = Snapshot(
                topPx = top,
                bottomPx = bottom,
                bottomIgnoringVisibilityPx = bottomIgnoring,
                statusBarWindowHeightPx = statusBarWindowHeightPx,
            )
            return
        }
        @Suppress("DEPRECATION")
        val legacyTop = insets.systemWindowInsetTop
        @Suppress("DEPRECATION")
        val legacyBottom = insets.systemWindowInsetBottom
        current = Snapshot(
            topPx = legacyTop,
            bottomPx = legacyBottom,
            bottomIgnoringVisibilityPx = 0,
            statusBarWindowHeightPx = statusBarWindowHeightPx,
        )
    }

    /** bridge 层读快照；从未写入过时返回全 0。 */
    fun snapshot(): Snapshot = current

    /** 仅用于测试。 */
    fun reset() {
        current = Snapshot.EMPTY
    }
}
