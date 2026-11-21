package com.dbtool.carplay.api.session

import com.dbtool.carplay.api.input.CarInputEvent

/**
 * 会话结束的**来源**。
 *
 * `SessionEnded` 以前是个没有参数的单例事件：用户点停止、熄火自动暂停、
 * 远端断线、桥接层为了重建而主动拆旧会话，在事件流里长得一模一样。
 * 自动重连只能对真正的意外断开（[REMOTE]）生效，否则会出现
 * 「用户点了停止，2 秒后 App 自己又连回去」以及
 * 「熄火停止 → 自动重连 → 15 秒后再次熄火停止」的回路。
 *
 * 由桥接层在**发起方**留标记（预期结束原因），`onSessionEnded` 读标记后填进来；
 * 只有 [REMOTE] 是缺省值（没有标记的结束回调）。
 */
enum class CarSessionEndReason {
    /** 远端 / 上游真实结束（iPhone 拔线、关 Wi-Fi、切走）。唯一允许自动重连的来源。 */
    REMOTE,

    /** 用户点「停止连接」，或控制器主动 stop（含拉黑）。 */
    USER_STOP,

    /** 熄火自动暂停（电源协调器 15 秒宽限到点）。 */
    POWER_PAUSE,

    /** 桥接层为了重建会话而主动拆掉旧会话（用户/设置/画布/自动重连都走这里）。 */
    RECONNECT,
}

/**
 * 会话事件流（一次性通知，与 [CarSessionState] 快照互补）。
 *
 * UI 用它做动画、Toast、日志，而不是把它们塞进状态里反复重放。
 */
sealed interface CarSessionEvent {
    data object AudioStreamsChanged : CarSessionEvent
    data class PhaseChanged(
        val phase: CarSessionPhase,
        val detail: String,
    ) : CarSessionEvent

    data class DeviceConnected(
        val name: String,
        val model: String?,
    ) : CarSessionEvent

    data object SessionStarted : CarSessionEvent

    /** 会话结束。[reason] 决定控制器是否允许排自动重连（只有 [CarSessionEndReason.REMOTE] 可以）。 */
    data class SessionEnded(val reason: CarSessionEndReason) : CarSessionEvent

    data class TransportError(val message: String) : CarSessionEvent

    data class LogLine(val message: String) : CarSessionEvent

    /** 方控/硬键事件（已归一化）。UI 可据此显示 OSD。 */
    data class InputReceived(val event: CarInputEvent) : CarSessionEvent
}

/** 会话状态与事件监听。 */
interface CarSessionListener {
    fun onStateChanged(state: CarSessionState) {}
    fun onEvent(event: CarSessionEvent) {}
}

/**
 * UI / 服务层唯一允许依赖的 CarPlay 入口。
 *
 * 依赖方向：
 *
 * ```
 * CarPlay Core (upstream)
 *        ↓  由 :car:core-bridge 实现本接口
 * CarSessionController        ← 本接口，位于 :car:api
 *        ↓
 * UI (:car:ui) / 服务 / 方控
 * ```
 *
 * 关键约束：**UI 不允许直接持有 upstream 的 `CarPlayController` /
 * `AndroidMediaSink` / `AirPlaySession`**。UI 只能通过本接口观察状态、下发命令。
 * 这样重做整个车机 UI 时不需要触碰 CarPlay 协议核心。
 *
 * 线程约定：所有回调在实现方约定的单一线程（通常主线程）投递；
 * 状态读取 [state] 必须随时安全。
 */
interface CarSessionController {
    fun activeAudioStreams(): List<com.dbtool.carplay.api.audio.CarAudioStreamStatus> = emptyList()
    /** 当前状态快照。 */
    val state: CarSessionState

    /**
     * 更新会话设置。
     *
     * **不会立即生效**：在下一次 [start] / [reconnect] 时应用。
     * 这与 upstream "保存并重连"的语义一致——中途换配置会导致
     * 与 iPhone 的既有握手失效，必须整栈重建。
     */
    fun updateSettings(settings: com.dbtool.carplay.api.settings.CarSessionSettings)

    /**
     * 注册监听。注册时会立即回调一次当前状态，避免 UI 出现"首帧空白"。
     * 实现必须去重，重复注册同一 listener 只能收到一次回调。
     */
    fun addListener(listener: CarSessionListener)

    fun removeListener(listener: CarSessionListener)

    /** 启动会话。可重复调用（幂等）。 */
    fun start()

    /** 停止会话并释放 Core 侧资源。可重复调用（幂等）。 */
    fun stop()

    /** 触发一次全栈重连（等价于 upstream 的完整重新握手）。 */
    fun reconnect()

    /**
     * 更新**自动重连调度器**的正交状态（次数 / 下次延迟 / 是否耗尽）。
     *
     * 这不是“第三套连接阶段”：连接阶段仍以 [CarSessionState.phase] 为唯一真源。
     * 实现方按 [CarReconnectStatus.state] 同步必要的 phase/detail（见
     * [CarReconnectState.appliedPhase]），UI 只读。
     *
     * 默认空实现：没有重连调度的实现（测试替身 / 其他平台）不必被迫实现它。
     */
    fun updateReconnect(status: CarReconnectStatus) {}

    /**
     * 下发触摸事件。返回 false 表示当前没有活跃会话，UI 可忽略。
     * @param contacts 空列表表示"抬起所有手指"。
     */
    fun sendTouch(contacts: List<CarTouchContact>): Boolean

    /** Core 侧是否仍然持有一个活跃的 AirPlay 附着（用于"复用后台会话"判断）。 */
    fun hasActiveAttachment(): Boolean

    // ===== 仪表副屏投屏（CarPlay cluster stream 111）=====

    /**
     * 当前投屏状态（快照）。UI 直接读，无需自己拼。
     *
     * 各字段含义见 [CarProjectionState]；[CarProjectionState.summary] 已把
     * 「卡在哪一步」写成一句中文。
     */
    val projectionState: CarProjectionState

    /**
     * 开/关仪表投屏。
     *
     * @return true 表示本次真的建立了车机侧窗口。false 的常见原因：
     *         ① iPhone 还没建第二路屏流；② 没找到仪表副屏；③ 固件不放行。
     *         具体原因见 [projectionState]。
     */
    fun setProjectionEnabled(enabled: Boolean): Boolean

    /** 切换仪表显示的内容（地图 / 转向卡 / 整仪表盘）。 */
    fun setProjectionContent(url: String): Boolean

    /** 缩放仪表地图一步。 */
    fun zoomProjectionMap(zoomIn: Boolean): Boolean

    /**
     * 只切换「iPhone 画不画」这一层，**不动副屏窗口**。返回切换后是否显示。
     *
     * 保留给窗口已经建好、只想临时藏一下画面的场景；完整收起投屏还需拆除副屏窗口。
     */
    fun toggleProjectionUi(): Boolean
}
