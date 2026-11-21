package com.dbtool.carplay.api.session

import com.dbtool.carplay.api.audio.CarAudioChannel
import com.dbtool.carplay.api.display.CarDisplay
import com.dbtool.carplay.api.media.CarVideoCodec

/**
 * CarPlay 会话的粗粒度阶段。
 *
 * 这是给 UI 用的**稳定**状态模型：upstream 的 `CarPlayStatus` 有 20+ 个细粒度取值，
 * 且随版本变化（例如新增 `WaitingForReenumeration`）。若 UI 直接依赖它，
 * 每次上游同步都要改 UI。
 *
 * 因此：Core 的细粒度状态 →（core-bridge 映射）→ 本枚举 → UI。
 * 上游新增状态时，只需要在 bridge 的映射函数里多一行，UI 不动。
 */
enum class CarSessionPhase {
    /** 尚未启动。 */
    IDLE,

    /** 正在准备 MFi 认证通道。 */
    PREPARING_MFI,

    /** MFi 协处理器未就绪，等待中。 */
    WAITING_FOR_MFI,

    /** 等待 iPhone 接入（有线）/ 等待无线握手。 */
    WAITING_FOR_DEVICE,

    /** 正在与 iPhone 建立控制通道（配对 / USB 枚举 / iAP2）。 */
    CONNECTING,

    /** AirPlay 会话已建立，正在收流。 */
    ACTIVE,

    /** 断线重连中。 */
    RECONNECTING,

    /** 失败（不可自动恢复，或重试已达上限）。 */
    FAILED,

    /** 已主动停止。 */
    STOPPED,
}

/**
 * UI 需要的全部会话状态快照。
 *
 * 不可变 + 全量快照，UI 可以做 diff 也可以直接整体渲染。
 */
data class CarSessionState(
    /** Frozen /info ALT ownership for this connection, not a newly saved draft. */
    val immersiveNavigationActive: Boolean = false,
    val phase: CarSessionPhase = CarSessionPhase.IDLE,
    /**
     * 面向用户的一行描述，可直接显示（如「等待 MFi 认证模块就绪」）。
     *
     * ⚠️ **必须是中文**。上游异常/状态文案是英文，不允许原样塞进来——
     * `:car:core-bridge` 里有 `CarErrorText` 负责翻译。
     */
    val detail: String = "",
    /** 已连接设备名（iPhone 名）。 */
    val deviceName: String? = null,
    /** 已连接设备型号。 */
    val deviceModel: String? = null,
    /**
     * 已连接设备的标识（iPhone 在 AirPlay `SETUP` 里给的 `deviceID`，是蓝牙 MAC）。
     *
     * 与 [deviceName] 一起在会话结束时清空——**这条信息必须"只描述当前连接"**，
     * 否则断开后界面上会留着一个已经不在的 iPhone，用户会以为它还连着。
     */
    val deviceId: String? = null,
    /** 当前协商/使用的显示。 */
    val display: CarDisplay? = null,
    /** 当前协商的 H.264 / HEVC。 */
    val videoCodec: CarVideoCodec? = null,
    /** 硬件解码还是软解。 */
    val softwareDecoder: Boolean = false,
    /** 当前活跃的音频通道。 */
    val activeAudioChannels: Set<CarAudioChannel> = emptySet(),
    /** 错误信息（phase == FAILED 时有意义）。 */
    val errorMessage: String? = null,
    /**
     * 自动重连调度器的正交状态（次数 / 下次延迟 / 是否耗尽）。
     *
     * ⚠️ **不是连接阶段**。连接阶段仍以 [phase] 为唯一真源；本字段只回答
     * 「重试调度器在做什么」。放在尾部并给默认值，旧 UI / 旧构造点不受影响。
     */
    val reconnect: CarReconnectStatus = CarReconnectStatus(),
) {
    val isActive: Boolean get() = phase == CarSessionPhase.ACTIVE
    val isBusy: Boolean
        get() = phase == CarSessionPhase.CONNECTING ||
            phase == CarSessionPhase.PREPARING_MFI ||
            phase == CarSessionPhase.RECONNECTING

    /**
     * 清空「当前连接的设备」三件套。
     *
     * 会话结束 / 停止 / 关闭时**必须**调：这三个字段描述的是**当前连接**，
     * 不清就会把上一台 iPhone 的名字留在设备页上，用户以为它还连着
     * （实测表现就是"连过一次之后再没连过，设备页却一直显示那台手机"）。
     *
     * 抽成方法而不是在三处各写一遍 `copy(deviceName = null, ...)`，
     * 是为了以后新增设备字段时不会漏清某一处。
     */
    fun withoutDevice(): CarSessionState =
        copy(deviceName = null, deviceModel = null, deviceId = null)
}

/**
 * 自动重连调度器在做什么（正交轴，不是连接阶段）。
 *
 * 取值刻意只有四个：
 * - [SCHEDULED] / [IN_PROGRESS] 都对应 [CarSessionPhase.RECONNECTING]；
 * - [EXHAUSTED] 必须对应 [CarSessionPhase.FAILED]（静默更新才会恢复）；
 * - [NONE] 可以出现在任意 phase。
 */
enum class CarReconnectState {
    /** 没有重连排期，也不在重建。 */
    NONE,

    /** 已排闹钟，等待下一次重连。 */
    SCHEDULED,

    /** 正在拆旧建新。 */
    IN_PROGRESS,

    /** 本轮自动重连已放弃（达到上限或被权限闸门拦下）。 */
    EXHAUSTED,
}

/**
 * 自动重连调度器的完整快照。
 *
 * @param attempt 已用次数（`SCHEDULED/IN_PROGRESS` 时为下一次的序号，1 起；
 *   `EXHAUSTED` 时为 [maxAttempts]）。
 * @param maxAttempts 本次断线事件允许的次数上限。
 * @param delayMillis 下一次重连前的等待；`EXHAUSTED` 时为 0。
 * @param lastError 最后一次失败原因，耗尽时作为首页副标题的兜底。
 */
data class CarReconnectStatus(
    val state: CarReconnectState = CarReconnectState.NONE,
    val attempt: Int = 0,
    val maxAttempts: Int = 0,
    val delayMillis: Long = 0L,
    val lastError: String? = null,
)

/**
 * 该重连状态要求桥接层落到的连接阶段（`null` = 不改动当前 phase）。
 *
 * ⚠️ **耗尽必须落 `FAILED`**：`RECONNECTING` 的 `allowsSilentInstall()` 是
 * `false`，停在它上面会让后台静默更新被永久阻断；`FAILED` 明确允许安装。
 */
fun CarReconnectState.appliedPhase(): CarSessionPhase? = when (this) {
    CarReconnectState.NONE -> null
    CarReconnectState.SCHEDULED,
    CarReconnectState.IN_PROGRESS,
    -> CarSessionPhase.RECONNECTING

    CarReconnectState.EXHAUSTED -> CarSessionPhase.FAILED
}

/**
 * 触摸点。坐标归一化到 [0,1]，与 upstream `AirPlayContact` 口径一致。
 *
 * 独立定义是为了让 UI / 方控模块不必依赖 upstream 的 `AirPlayContact`；
 * [down] 必须保留，否则无法表达"某个手指抬起"这种多点触控状态。
 */
data class CarTouchContact(
    val id: Int,
    val x: Double,
    val y: Double,
    val down: Boolean = true,
)
