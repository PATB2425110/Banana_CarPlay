package com.dbtool.carplay.ui

import com.dbtool.carplay.api.session.CarReconnectState
import com.dbtool.carplay.api.session.CarReconnectStatus
import com.dbtool.carplay.api.session.CarSessionPhase
import com.dbtool.carplay.api.session.CarSessionState
import com.dbtool.carplay.api.settings.CarSessionSettings
import com.dbtool.carplay.ui.components.CarFact

/** 界面指示器语义，UI 据此选颜色/图标，不需要理解协议阶段。 */
enum class CarStatusIndicator {
    IDLE,
    WORKING,
    READY,
    ERROR,
}

/** UI 可用的操作。 */
enum class CarUiAction {
    START,
    STOP,
    RECONNECT,
    OPEN_SETTINGS,

    /**
     * 定位到**本应用**设置页里的「立即建立热点 + ADB」入口。
     *
     * ## 2026-10-06 语义修正：从"去车机开发者选项"改成"去我们自己的热点+ADB"
     *
     * 这个 action 原先的语义是"打开**车机系统**的开发者选项 / ADB 调试页"
     * （`CarDeveloperSettingsLauncher` 逐级降级调 `Settings.ACTION_*`）。
     * **车主否掉了那个设计**，原话：**"去打开 adb 调试应该跳转到设置-连接-无线-立即建立热点+adb"**。
     *
     * 理由（也是这次修正的判据）：
     * - 车机 ADB 的开关**这个应用自己就会开**（`DeviceAdbEnabler` 直写
     *   `service.adb.tcp.port` / `persist.adb.tcp.port` + `ctl.stop/start adbd`，
     *   见 MEMORY 第 13/28 条）。把用户送去系统设置里翻那个开关，
     *   等于**放弃我们已经具备的能力**，还多绕一层 OneOS 的三四级设置金字塔；
     * - 本应用真正需要用户做的那一件事，是**点一下「立即建立热点 + ADB」**
     *   （斌哥口径：ADB 授权必须由用户手动点击建立，不自动开启）；
     * - 因此正确的落点是**我们自己的**设置页，且要直接落到那个按钮所在的
     *   「连接 → 热点服务」分组，让用户**不用再找**。
     *
     * ⚠️ 与 [OPEN_SETTINGS] 的区别仍然保留，别合并：那个进的是设置**索引页**
     * （用户自己找），这个进的是**已定位好的分区**（直达热点服务）。合并会让
     * "打开设置"这个通用出口也被强行塞进连接分区。
     */
    OPEN_DEVELOPER_SETTINGS,
}

/**
 * 首页/状态区所需的**纯展示**状态。
 *
 * 这一层存在的意义：把"技术状态"翻译成"界面语言"。
 * `CarSessionState.detail` 是给工程师看的（"Waiting for MFI coprocessor"），
 * [title]/[subtitle] 是给用户看的（"等待认证模块"）。
 * 重新设计 UI 时改的是这里和具体界面，不碰 Core。
 */
data class CarPlayScreenState(
    /** 原始协议阶段。界面用它做细粒度分支（例如选择插画动效状态）。 */
    val phase: CarSessionPhase,
    val title: String,
    val subtitle: String,
    val indicator: CarStatusIndicator,
    val availableActions: Set<CarUiAction>,
    /** 是否应显示视频层。 */
    val showVideo: Boolean,
    /** 是否允许用户触摸（转发到 iPhone）。 */
    val touchEnabled: Boolean,
    val errorMessage: String?,
    /** 已连接设备名（用于设备区展示）。 */
    val deviceName: String?,
    val deviceModel: String?,
    /** 已连接设备标识（蓝牙 MAC）。与 [deviceName] 同生共死，断开即清空。 */
    val deviceId: String?,
    /** 元信息行（链路、分辨率、编码等）。 */
    val facts: List<CarFact>,
    /** 面向工程师的原始阶段描述，放诊断区。 */
    val technicalDetail: String,

    /**
     * 这次失败**是不是"车机 ADB 没开 / 拿不到 root"这一族**。
     *
     * 界面据此在失败页摆出「去开 ADB 调试」按钮（点击后跳到本应用
     * 设置 → 连接 → 无线「立即建立热点 + ADB」，见 [CarUiAction.OPEN_DEVELOPER_SETTINGS]）。
     *
     * ⚠️ **判据写在数据里、不写在界面里**：界面拿不到原始错误结构，
     * 只能靠字面匹配（[NEED_ADB_MARKERS]），而"匹配什么字符串"是**数据层**的职责
     * ——改文案时只需改这里，界面不用跟着动。
     *
     * ⚠️ 这个标记**只影响"多给一条路"，不影响任何失败判定**：
     * 判错最坏是多显示一个按钮，不会把成功当失败、也不会掩盖真实原因。
     * 所以宁可漏判保守（只在明确的 ADB 文案上置位），也不要广撒网。
     */
    val needsDeveloperSettings: Boolean = false,

    /**
     * 自动重连调度器的正交快照（次数 / 下次延迟 / 是否耗尽）。
     *
     * ⚠️ **不是连接阶段**：连接阶段仍只看 [phase]。本字段只用于文案与按钮选择
     * （例如 `SCHEDULED` 显示"第 N/3 次，约 X 秒后重试"、`EXHAUSTED` 显示
     * "自动重连已停止"）。放在尾部并给默认值，不影响既有构造点。
     */
    val reconnect: CarReconnectStatus = CarReconnectStatus(),
) {
    val isActive: Boolean get() = indicator == CarStatusIndicator.READY
}

/**
 * 判定"车机 ADB 连不上 / 提不了权"的文案特征。
 *
 * 这些串都来自**我们自己写的**失败原因（`AdbShellSession` / `DeviceAdbEnabler` /
 * `CarPlaySessionBridge` 的 `CarHotspotUnavailableException`），不是上游英文文案，
 * 所以它们是稳定的；上游改文案不会影响这里。
 *
 * 覆盖的三条真实路径：
 * 1. `连不上本机 adb（车机 ADB 没开？）` —— 第 4 步 connect 失败；
 * 2. `提权探测` / `adb root` / `既不是 root 也没有 su` —— 第 5 步拿到 shell 但提不了权
 *    （**实车诊断包 `1791279270080` 走的就是这一支**：真实根因是 `requestAdbRoot`
 *    的验证环节自断连接，不是 ADB 没开）；
 * 3. `ADB 调试` —— `DeviceAdbEnabler` 降级提示里点名的那个开关。
 *
 * ⚠️ 匹配的是 `CarHotspotUnavailableException` 的**外层文案**，
 * 不是 `AdbShellSession` 的 step detail（后者只进诊断包，不进 `errorMessage`）。
 * 所以第 2 条实际命中靠的是外层那句
 * `自建热点未建立（第 5 步「提权探测（adb root → shell → root）」失败）…`。
 * **改 `CarPlaySessionBridge` 里那句失败文案时必须回来核对这里**，
 * 否则界面会静默丢掉「去开 ADB 调试」那个快捷按钮。
 */internal val NEED_ADB_MARKERS = listOf(
    "连不上本机 adb",
    "ADB 没开",
    "ADB 调试",
    "提权探测",
    "adb root",
    "既不是 root 也没有 su",
)

/**
 * [CarSessionState] → [CarPlayScreenState]。
 *
 * 无 Android 依赖、无副作用，可直接单元测试——这也是它放在 UI 模块里
 * 却不需要仪器化测试的原因。
 */
object CarUiStateMapper {

    fun project(
        state: CarSessionState,
        settings: CarSessionSettings? = null,
    ): CarPlayScreenState {
        val indicator = when (state.phase) {
            CarSessionPhase.IDLE, CarSessionPhase.STOPPED -> CarStatusIndicator.IDLE
            CarSessionPhase.ACTIVE -> CarStatusIndicator.READY
            CarSessionPhase.FAILED -> CarStatusIndicator.ERROR
            else -> CarStatusIndicator.WORKING
        }
        val failed = state.phase == CarSessionPhase.FAILED
        val errorText = state.errorMessage.orEmpty()
        val needsDevSettings = failed && NEED_ADB_MARKERS.any { errorText.contains(it) }
        val actions = buildSet {
            when (state.phase) {
                CarSessionPhase.IDLE, CarSessionPhase.STOPPED -> add(CarUiAction.START)
                CarSessionPhase.FAILED -> {
                    add(CarUiAction.RECONNECT)
                    add(CarUiAction.OPEN_SETTINGS)
                    // ADB 那一族失败时，第三条路才有意义——其余失败（配置错、
                    // 认证失败、iPhone 没连热点…）去开发者选项里什么也解决不了。
                    if (needsDevSettings) add(CarUiAction.OPEN_DEVELOPER_SETTINGS)
                }

                CarSessionPhase.ACTIVE -> {
                    add(CarUiAction.STOP)
                    add(CarUiAction.OPEN_SETTINGS)
                }

                else -> add(CarUiAction.STOP)
            }
        }
        return CarPlayScreenState(
            phase = state.phase,
            title = titleOf(state),
            subtitle = subtitleOf(state),
            indicator = indicator,
            availableActions = actions,
            showVideo = state.phase == CarSessionPhase.ACTIVE,
            touchEnabled = state.isActive,
            errorMessage = state.errorMessage,
            deviceName = state.deviceName,
            deviceModel = state.deviceModel,
            deviceId = state.deviceId,
            facts = factsOf(state, settings),
            technicalDetail = state.detail,
            needsDeveloperSettings = needsDevSettings,
            reconnect = state.reconnect,
        )
    }

    /**
     * 主标题 = **当前正在做的那一步**。
     *
     * ## 为什么不再把过程态统一成"正在连接"
     *
     * 旧版把「准备认证模块 / 等待认证模块 / 等待设备 / 连接中」四种内部阶段
     * 统一成一句"正在连接"，理由是"对用户没有区分意义、四套措辞会让页面看起来在跳字"。
     * **这个判断被现场否掉了**：无线连接要走十几秒，中间会依次经过
     * 热点 → 蓝牙 → 配对 → iAP2 → AirPlay 五段，用户盯着一个不动的"正在连接"
     * 完全不知道它卡在哪一段，也没法判断"我该去 iPhone 上做什么"。
     * 用户的原话是"应该先显示蓝牙连接状态信息，再说开始连接下一步"——
     * 他要的就是**把步骤报出来**，顺序上先看到蓝牙那一步。
     *
     * 现在直接用 [CarSessionState.detail]：它本来就是"面向用户的一行中文描述"，
     * 由 `CarPlayStatusMapper.detailOf` 逐状态给出（"正在连接蓝牙"、
     * "正在与 iPhone 配对"、"正在建立 iAP2 控制通道"…），
     * 所以这里不需要再维护第二套措辞——上游改文案时 UI 自动跟着变。
     *
     * 两条仍然成立的约束：
     * - **必须与 [CarStatusIndicator] 自洽**。过程态的指示器是
     *   [CarStatusIndicator.WORKING]（枢纽在转、主色蓝），而这里给的是
     *   "正在连接蓝牙"这种进行时，两者一致。⚠️ 不能给静止的"未启动"——
     *   那会让用户读到"它说没启动，可明明在转"。
     * - **[CarSessionState.detail] 必须是中文**。上游异常文案是英文，
     *   由 `CarErrorText` 在 bridge 层翻译；这里只做展示，不再兜第二层。
     *   `detail` 为空（理论上不该发生）时才回落到通用的"正在连接"。
     *
     * 同理 [CarSessionPhase.ACTIVE] 与 [CarSessionPhase.FAILED] 必须保留各自文案：
     * 投屏正在运行时显示"未启动"是**事实错误**，用户会以为没连上，然后去点"启动"。
     */
    private fun titleOf(state: CarSessionState): String {
        val device = state.deviceName
        return when (state.phase) {
            CarSessionPhase.ACTIVE ->
                if (device.isNullOrEmpty()) "CarPlay 运行中" else device

            CarSessionPhase.FAILED ->
                if (state.reconnect.state == CarReconnectState.EXHAUSTED) {
                    "自动重连已停止"
                } else {
                    "连接失败"
                }

            CarSessionPhase.RECONNECTING -> "正在重新连接"

            CarSessionPhase.STOPPED -> when {
                // 拔电暂停的 detail 是"已停止（熄火）"，也归入"已停止"这一档。
                state.detail.startsWith("已停止") -> "已停止"
                state.detail == "正在停止" -> "正在停止"
                state.detail == "已关闭" -> "已关闭"
                else -> "未启动"
            }

            CarSessionPhase.IDLE -> "未启动"

            // 其余全部是"正在建立连接"的过程态：标题报当前这一步。
            else -> state.detail.ifBlank { "正在连接" }
        }
    }

    /**
     * 副标题 = **这一整件事是为了什么**。
     *
     * 与 [titleOf] 分工：标题回答"现在这一步在做什么"，副标题回答"做这些是为了什么"。
     * 所以副标题在过程态里是**恒定**的一句，不跟着步骤跳——它承担的是
     * "所有步骤都属于同一件事"这个信息，标题负责其中的进度。
     *
     * 边界规则同 [titleOf]：必须与标题自洽，且 [CarSessionPhase.FAILED] /
     * [CarSessionPhase.ACTIVE] / 空闲态各有自己的文案。
     */
    private fun subtitleOf(state: CarSessionState): String = when (state.phase) {
        CarSessionPhase.ACTIVE -> "画面与音频由 iPhone 提供"

        CarSessionPhase.FAILED ->
            if (state.reconnect.state == CarReconnectState.EXHAUSTED) {
                // 重连次数上限是第一步的稳定口径（3 次），这里按方案原文
                // 给用户一个明确的"停手"结论，而不是继续转圈。
                "已尝试 3 次仍未连接，请检查手机或热点后重试"
            } else {
                state.errorMessage ?: state.detail
            }

        CarSessionPhase.RECONNECTING -> when (state.reconnect.state) {
            CarReconnectState.SCHEDULED -> {
                val attempt = state.reconnect.attempt.coerceAtLeast(1)
                val max = state.reconnect.maxAttempts.coerceAtLeast(attempt)
                // 2/4/8 秒退避 → 四舍五入到秒，避免出现"约 0 秒"。
                val seconds = ((state.reconnect.delayMillis + 999L) / 1000L).coerceAtLeast(0L)
                "第 $attempt/$max 次，约 $seconds 秒后重试"
            }

            CarReconnectState.IN_PROGRESS -> {
                val attempt = state.reconnect.attempt.coerceAtLeast(1)
                val max = state.reconnect.maxAttempts.coerceAtLeast(attempt)
                "正在重建连接（第 $attempt/$max 次）"
            }

            else -> "连接中断，正在恢复"
        }

        CarSessionPhase.IDLE -> "启动后自动连接 CarPlay"

        CarSessionPhase.STOPPED -> when {
            state.detail.startsWith("已停止") -> "点「启动 CarPlay」重新连接"
            state.detail == "正在停止" -> "正在关闭 CarPlay"
            state.detail == "已关闭" -> "应用已关闭"
            else -> "启动后自动连接 CarPlay"
        }

        CarSessionPhase.PREPARING_MFI -> "正在准备 MFi 认证模块"
        CarSessionPhase.WAITING_FOR_MFI -> "请确认 MFi 认证模块已连接"
        CarSessionPhase.WAITING_FOR_DEVICE -> "请按车机提示在 iPhone 上确认 CarPlay"
        CarSessionPhase.CONNECTING -> "正在完成 CarPlay 握手，请稍候"
    }

    private fun factsOf(
        state: CarSessionState,
        settings: CarSessionSettings?,
    ): List<CarFact> {
        val facts = mutableListOf<CarFact>()
        settings?.let {
            facts += CarFact("链路", if (it.isWireless) "无线" else "USB")
            // 未连接时把认证方式也列出来：它是"卡在等待认证模块"这类问题
            // 唯一能从界面上自查的信息。
            if (!state.isActive) {
                facts += CarFact("MFi 认证", it.mfiTarget.displayLabel())
            }
        }
        state.display?.let {
            facts += CarFact("分辨率", "${it.widthPixels} × ${it.heightPixels}")
        }
        state.videoCodec?.let {
            facts += CarFact("视频", if (state.softwareDecoder) "${it.name} 软解" else it.name)
        }
        if (state.activeAudioChannels.isNotEmpty()) {
            facts += CarFact("音频通道", state.activeAudioChannels.size.toString())
        }
        return facts
    }
}
