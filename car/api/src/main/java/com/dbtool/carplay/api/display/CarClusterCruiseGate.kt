package com.dbtool.carplay.api.display

/**
 * 仪表「何时该显示地图」的门控状态机（2026-10-11，从原版 `GeelyClusterModeState` 同步）。
 *
 * 它将导航路线活跃状态转成仪表显示/收起动作。路线情报暂时静默不等于用户结束导航，
 * 因此由 `CarPlayClusterRouteClient` 维护明确结束的静默超时，再把判定结果交给本门。
 *
 * 当前生产用途是路线自动投屏。`manualCruiseAllowed` 是兼容原版状态机的能力参数，
 * 但 DBPlay 当前接线未启用无导航手动巡航；用户需要手动投屏时使用设置页的显式按钮。
 *
 * ## 纯数据、纯函数
 *
 * 不碰 `Context` / `WindowManager` / `Surface` —— 判据可以在 JVM 上单测。
 * 真正的"显示/隐藏"由调用方落成 `AirPlaySession.setClusterUiShown(...)`
 * （`showUI` / `stopUI`，**保留 111 流不拆**），或落成窗口可见性。
 *
 * ## 与原版的差异
 *
 * 原版还有 `firstFrameReady` / `suppressedForRoute` 两个字段，服务于它的
 * "工厂全屏地图模式"与抢屏抑制。DBPlay 的抢屏抑制在
 * `GeelyClusterPresenter.suppressReopenUntil`，不重复放这里。
 */
class CarClusterCruiseGate(
    enabled: Boolean = true,
    displayAvailable: Boolean = false,
    manualCruiseAllowed: Boolean = false,
    routeActive: Boolean = false,
) {

    /** 投屏总开关（`CarSessionSettings.projectionEnabled`）。 */
    var enabled: Boolean = enabled
        private set

    /** 副屏此刻是否可选（选了屏且屏在）。 */
    var displayAvailable: Boolean = displayAvailable
        private set

    /** 用户是否允许"无导航时手动开启"（`CarSessionSettings.clusterManualCruise`）。 */
    var manualCruiseAllowed: Boolean = manualCruiseAllowed
        private set

    /** 导航是否进行中（来自 `Iap2RouteState.routeActive`）。 */
    var routeActive: Boolean = routeActive
        private set

    /** 用户是否手动开过地图（本轮会话内有效）。 */
    var manualCruise: Boolean = false
        private set

    /** 地图当前是否应当显示。 */
    var modeActive: Boolean = false
        private set

    /** 此刻该不该显示地图。调用方据此 `setClusterUiShown(uiEligible())`。 */
    fun uiEligible(): Boolean =
        enabled && (routeActive || manualCruise) && displayAvailable

    /** 此刻能不能手动开（原版用它决定四指手势要不要吃掉）。 */
    fun canManualOpen(): Boolean =
        enabled && (routeActive || manualCruiseAllowed) && displayAvailable && !modeActive

    /**
     * 路线状态变化。
     *
     * - 变 `true`：**自动开启**，并记住这次是"带导航"的开启（清掉手动标记，
     *   这样导航明确结束时能正常收屏）。
     * - 变 `false`：**不立即收屏** —— 见类注释"持续无导航不关闭"。
     *   真正的收屏由 [onRouteEndedExplicitly] 触发。
     */
    fun onRouteActive(active: Boolean): CarClusterModeAction {
        if (routeActive == active) return CarClusterModeAction.None
        routeActive = active
        if (!active) return CarClusterModeAction.None
        manualCruise = false
        return enter()
    }

    /**
     * iPhone **明确结束**导航（`Iap2RouteState` 的 pendingEnd 落地）。
     *
     * 只有这一条会"自动关闭"；持续无导航不走这里。
     */
    fun onRouteEndedExplicitly(): CarClusterModeAction {
        routeActive = false
        manualCruise = false
        return exit()
    }

    /** 「手动开」的入口（原版接的是四指左滑 / 方控菜单）。 */
    fun onManualOpen(): CarClusterModeAction {
        if (!canManualOpen()) return CarClusterModeAction.None
        manualCruise = true
        return enter()
    }

    /** 手动关闭（方控菜单）。手动关掉后即使有导航也不再自动开，等下一次路线开始。 */
    fun onManualClose(): CarClusterModeAction {
        manualCruise = false
        return exit()
    }

    /** 设置项「允许无导航时手动开启」变化。关掉时顺手收起"纯手动"的地图。 */
    fun onManualCruiseAllowed(allowed: Boolean): CarClusterModeAction {
        if (manualCruiseAllowed == allowed) return CarClusterModeAction.None
        manualCruiseAllowed = allowed
        if (!allowed && manualCruise && !routeActive) {
            manualCruise = false
            return exit()
        }
        return CarClusterModeAction.None
    }

    /** 投屏总开关变化。关掉一律收屏。 */
    fun onEnabled(value: Boolean): CarClusterModeAction {
        if (enabled == value) return CarClusterModeAction.None
        enabled = value
        if (!value) {
            manualCruise = false
            return exit()
        }
        return if (routeActive) enter() else CarClusterModeAction.None
    }

    /** 副屏可用性变化（选屏变了 / 屏拔了）。不可用一律收屏。 */
    fun onDisplayAvailable(value: Boolean): CarClusterModeAction {
        if (displayAvailable == value) return CarClusterModeAction.None
        displayAvailable = value
        if (!value) return exit()
        return if (routeActive || manualCruise) enter() else CarClusterModeAction.None
    }

    private fun enter(): CarClusterModeAction {
        if (modeActive) return CarClusterModeAction.None
        if (!uiEligible()) return CarClusterModeAction.None
        modeActive = true
        return CarClusterModeAction.Enter
    }

    private fun exit(): CarClusterModeAction {
        if (!modeActive) return CarClusterModeAction.None
        modeActive = false
        return CarClusterModeAction.Exit
    }
}

/** 门控输出的动作：调用方按它 decide 显/隐（都是幂等请求）。 */
enum class CarClusterModeAction { None, Enter, Exit }
