package com.dbtool.carplay.bridge

import com.dbtool.carplay.api.session.CarSessionPhase
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.orchestration.CarPlayTransport

/**
 * upstream `CarPlayStatus`（20+ 个细粒度状态）→ 二开侧稳定的 [CarSessionPhase]。
 *
 * 这是"上游更新只改桥接层"的关键：upstream 新增/重命名状态时，
 * 只需要修改本文件的 `when`，UI 层完全不动。
 *
 * `when` 刻意写成穷尽式（不写 `else`）：upstream 一旦新增状态，
 * 这里会**编译失败**而不是静默走 else 分支。编译错误正好提醒我们显式决定新状态
 * 该映射到哪个阶段——这是刻意的，不要为了"以后不用改"而加 `else`。
 */
object CarPlayStatusMapper {

    fun phaseOf(status: CarPlayStatus): CarSessionPhase = when (status) {
        CarPlayStatus.DiscoveringMfi,
        CarPlayStatus.RequestingMfiPermission,
        CarPlayStatus.MfiReady,
        -> CarSessionPhase.PREPARING_MFI

        CarPlayStatus.WaitingForMfi -> CarSessionPhase.WAITING_FOR_MFI

        CarPlayStatus.StartingHotspot,
        is CarPlayStatus.HotspotReady,
        CarPlayStatus.WaitingForPairedIphone,
        CarPlayStatus.ConnectingBluetooth,
        CarPlayStatus.DiscoveringIphone,
        CarPlayStatus.WaitingForIphone,
        CarPlayStatus.RequestingIphonePermission,
        CarPlayStatus.WaitingForReenumeration,
        -> CarSessionPhase.WAITING_FOR_DEVICE

        CarPlayStatus.SelectingConfiguration,
        CarPlayStatus.OpeningDataPaths,
        CarPlayStatus.Pairing,
        CarPlayStatus.ConnectingControl,
        CarPlayStatus.AttachingNetwork,
        CarPlayStatus.RunningControl,
        CarPlayStatus.RunningWireless,
        -> CarSessionPhase.CONNECTING

        CarPlayStatus.WirelessActive -> CarSessionPhase.ACTIVE

        // upstream 在 ControlEnded 之后会调度一次全栈重连。
        CarPlayStatus.ControlEnded -> CarSessionPhase.RECONNECTING

        is CarPlayStatus.Failed -> CarSessionPhase.FAILED
    }

    /**
     * 面向用户的一行描述。
     *
     * ## 为什么不再与上游逐字一致
     *
     * 旧版这里刻意抄 upstream `CarPlayHostActivity` 私有 `CarPlayStatus.describe()`
     * 的英文原文，理由是"便于与上游日志/截图对照"。但 [detail] 并不只是内部字段：
     * 它是 `CarSessionState.detail`，会出现在
     * ① **首页过程态的大标题**（`CarUiStateMapper.titleOf`，2026-09-29 起——
     * 用户要求"先显示蓝牙连接状态、再说下一步"，于是每一步都报出来），
     * ② 首页「连接失败」的副标题兜底，③ 诊断区的当前阶段描述。
     * 结果是界面里混进英文——现场实测用户在失败页读到的是
     * `A server address must be configured for the remote MFi target`。
     *
     * 现在改为中文，**对照能力不丢**：上游自己会把它那一行原文打进日志，
     * 诊断页的日志列表里照样能逐字比对。界面语言的一致性优先。
     *
     * ⚠️ **同时要短**：既然①把它当大标题用，就不能写成整句——
     * 具体措辞规则见下面 `detailOf` 里的说明。
     *
     * 唯一保留原样的差异点：[CarPlayStatus.AttachingNetwork] 需要 [transport]
     * 才能给出正确措辞——upstream 用的是 Activity 的 `wirelessEnabled` 字段，
     * 我们改用会话配置，语义相同。
     */
    fun detailOf(status: CarPlayStatus, transport: CarPlayTransport): String = when (status) {
        // 措辞一律**短**：这一行现在会被 `CarUiStateMapper` 直接当**首页大标题**用
        // （30sp，一行就是一整块版面），"正在启动无线热点"这种长句摆上去太吵。
        // 统一压到 2~6 个字，且去掉"正在"前缀——标题上方那个转动的枢纽已经
        // 表达了"正在进行中"，文字再重复一次"正在"是冗余。
        // ⚠️ 参数（SSID / 信道 / 后端名）**不放这里**：它们是诊断信息，
        // 上游已经打进日志（`hotspot backend=… band=… channel=…`），
        // 设置页也有回显；塞进标题只会把它撑长。
        CarPlayStatus.DiscoveringMfi -> "准备认证"
        CarPlayStatus.WaitingForMfi -> "等待认证模块"
        CarPlayStatus.RequestingMfiPermission -> "请求 MFi 权限"
        CarPlayStatus.MfiReady -> "认证就绪"
        CarPlayStatus.StartingHotspot -> "启动热点"
        is CarPlayStatus.HotspotReady -> "热点就绪"
        CarPlayStatus.WaitingForPairedIphone -> "等待已配对 iPhone"
        CarPlayStatus.ConnectingBluetooth -> "连接蓝牙"
        CarPlayStatus.RunningWireless -> "无线通道就绪"
        CarPlayStatus.WirelessActive -> "无线已激活"
        CarPlayStatus.DiscoveringIphone -> "发现 iPhone"
        CarPlayStatus.WaitingForIphone -> "等待 iPhone 接入"
        CarPlayStatus.RequestingIphonePermission -> "请求 USB 权限"
        CarPlayStatus.WaitingForReenumeration -> "等待重新枚举"
        CarPlayStatus.SelectingConfiguration -> "协商配置"
        CarPlayStatus.OpeningDataPaths -> "打开数据通道"
        CarPlayStatus.Pairing -> "与 iPhone 配对"
        CarPlayStatus.ConnectingControl -> "建立控制通道"
        CarPlayStatus.AttachingNetwork ->
            if (transport == CarPlayTransport.WIRELESS) {
                "启动 AirPlay"
            } else {
                "接入网络"
            }

        CarPlayStatus.RunningControl -> "控制通道就绪"
        CarPlayStatus.ControlEnded -> "控制通道已结束"
        // 上游的 `status.message` 是英文，过一遍中文层再拼；认不出来时
        // `CarErrorText.of` 会给一句中文行动指引，**不会**漏出英文。
        //
        // 这一条**刻意不压短**：它只在失败态出现，而失败态的标题是"连接失败"、
        // 这一行走的是副标题——用户这时需要的是"为什么 + 怎么办"，压短反而没用。
        is CarPlayStatus.Failed ->
            "失败：${CarErrorText.of(status.message) ?: CarErrorText.FALLBACK}"
    }
}
