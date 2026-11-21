package com.dbtool.carplay.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.dbtool.carplay.api.developer.CarDebugTunnelState
import com.dbtool.carplay.api.developer.CarLogScope
import com.dbtool.carplay.api.developer.CarLogUploadPhase
import com.dbtool.carplay.api.hotspot.CarHotspotProbeEntry
import com.dbtool.carplay.api.hotspot.CarHotspotProbeSource
import com.dbtool.carplay.api.hotspot.CarNativeHotspotProbeState
import com.dbtool.carplay.api.settings.CarHotspotBand
import com.dbtool.carplay.ui.CarStatusIndicator
import com.dbtool.carplay.ui.CarUiActions
import com.dbtool.carplay.ui.CarUiModel
import com.dbtool.carplay.ui.components.CarAdbHotspotStepsCard
import com.dbtool.carplay.ui.components.CarAdbHotspotSummaryRow
import com.dbtool.carplay.ui.components.CarButton
import com.dbtool.carplay.ui.components.CarButtonSize
import com.dbtool.carplay.ui.components.CarButtonVariant
import com.dbtool.carplay.ui.components.CarCard
import com.dbtool.carplay.ui.components.CarProgressBar
import com.dbtool.carplay.ui.components.CarRowDivider
import com.dbtool.carplay.ui.components.CarScreen
import com.dbtool.carplay.ui.components.CarSettingChoice
import androidx.compose.ui.text.input.KeyboardType
import com.dbtool.carplay.ui.components.CarSettingSwitch
import com.dbtool.carplay.ui.components.CarSettingText
import com.dbtool.carplay.ui.components.CarSettingInfo
import com.dbtool.carplay.ui.components.CarStatusDot
import com.dbtool.carplay.ui.components.CarStatusPill
import com.dbtool.carplay.ui.components.SectionHeader
import com.dbtool.carplay.ui.theme.CarIcons
import com.dbtool.carplay.ui.theme.CarTheme

/**
 * 开发者模式（隐藏页）。
 *
 * **入口只有「关于」页的版本号连点 7 次**，所以这一页可以放心地把
 * 设备号、服务端地址、日志按钮全摊开——普通用户路径上到不了这里。
 *
 * 页面结构按"现场排障时的提问顺序"排：
 * 1. **现在在干什么**（阶段胶囊 + 进度 + 一句话）；
 * 2. **采什么**（采集范围，默认全量）；
 * 3. **这是哪台车**（设备编号 / 服务端 / 版本 / 系统）——
 *    工程师拿到日志后的第一件事就是在后台按设备号找这台车，
 *    所以这几个值必须在**点上传之前**就能看见、能抄下来；
 * 4. **上一次传到哪了**（日志编号 / 文件名 / 时间）；
 * 5. **车机原生热点探测**（**首选方案的第一步**）——
 *    先问车机现在有没有在跑的热点：有就复用它，没有才自建。
 *    它把每条探测命令（`ip addr` / `ip route` / `dumpsys wifi` / `dumpsys tethering`
 *    / `cat hostapd.conf` …）的原始输出摊开，用来判断"这台车机实际用的是哪套热点方案"。
 * 6. **ADB 独立热点（兜底）**（自检按钮 + 逐步原始输出）——
 *    无线连不上是现场最高频的问题，而它的失败点全在车机侧
 *    （网卡名 / `su` / hostapd 路径 / ABI），只有把命令输出摊开才定位得到。
 */
@Composable
fun DeveloperScreen(
    model: CarUiModel,
    actions: CarUiActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = CarTheme.dimens
    val colors = CarTheme.colors
    val developer = model.developer
    val hotspot = model.adbHotspot
    val probe = model.nativeHotspot

    // 采集范围与客户端 IP 选择都是本页临时草稿，不进设置存储：
    // 地址字段只在用户明确勾选本次上传时写入。
    var scope by remember { mutableStateOf(CarLogScope.FULL) }
    var includeClientIpv4 by remember { mutableStateOf(false) }

    // 设备号与系统信息要跑 getprop / dumpsys，只在进入页面时刷新一次。
    LaunchedEffect(Unit) { actions.refreshDeveloperInfo() }

    val busy = developer.phase.isBusy() || developer.airPlayTcpCaptureActive

    CarScreen(
        title = "开发者模式",
        onBack = onBack,
        modifier = modifier,
    ) {
        CarCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(dimens.spaceM),
            ) {
                CarStatusPill(
                    text = developer.phase.label(),
                    indicator = developer.phase.indicator(),
                )
                if (developer.lastUploadAt.isNotEmpty()) {
                    Text(
                        text = "最近上传 ${developer.lastUploadAt}",
                        style = CarTheme.type.bodySmall,
                        color = colors.textTertiary,
                    )
                }
            }
            if (developer.message.isNotEmpty()) {
                Spacer(Modifier.height(dimens.spaceM))
                Text(
                    text = developer.message,
                    style = CarTheme.type.bodyMedium,
                    color = colors.textSecondary,
                )
            }
            if (busy) {
                Spacer(Modifier.height(dimens.spaceM))
                // 进度条：只在"正在上传"时用真实百分比；采集/打包阶段没有可靠的进度，
                // 一律按 0 显示（此时阶段胶囊已经说明在推进，编一个百分比是骗人）。
                CarProgressBar(
                    progress = if (developer.phase == CarLogUploadPhase.UPLOADING) {
                        developer.progress
                    } else {
                        0f
                    },
                )
            }
        }

        SectionHeader(title = "音频输出扫描")
        CarCard(modifier = Modifier.fillMaxWidth()) {
            CarButton(
                text = if (developer.audioScanBusy) "正在扫描…" else "扫描并分析音频输出",
                onClick = actions::scanAudioOutput,
                enabled = !developer.audioScanBusy && !busy,
            )
            Text(
                text = developer.audioScanReport.ifEmpty {
                    "播放音乐后点击扫描。区分CarPlay输入、应用音轨与系统输出；无法确认的最终格式显示未知。仅本机读取，不自动上传。"
                },
                style = CarTheme.type.bodySmall,
                color = colors.textSecondary,
            )
        }

        SectionHeader(title = "采集范围")
        CarCard(modifier = Modifier.fillMaxWidth()) {
            CarSettingChoice(
                title = "采集内容",
                options = CarLogScope.entries,
                selected = scope,
                onSelect = { scope = it },
                description = scope.detail,
                enabled = !busy,
                label = { it.title },
            )
            CarRowDivider()
            CarSettingSwitch(
                title = "本次包包含网络端点 IPv4",
                checked = includeClientIpv4,
                onCheckedChange = { includeClientIpv4 = it },
                description = "包含 Ecarx 当前报告的热点客户端 IPv4，并保留 TCP 抓包两端 IP（非 DHCP 租约历史）；不记录 MAC。私网地址会上传，默认关闭。",
                enabled = !busy,
            )
        }

        SectionHeader(title = "AirPlay TCP 握手取证")
        CarCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "先启动采集，再在 90 秒内复现连接。只记录 TCP/7000 的 SYN/RST 包头和地址，不抓应用负载；需要已就绪的 root ADB 与 tcpdump，不会自动开启 ADB。结果随下一份诊断包上传。",
                style = CarTheme.type.bodySmall,
                color = colors.textSecondary,
            )
            Spacer(Modifier.height(dimens.spaceS))
            Text(
                text = developer.airPlayTcpCaptureStatus,
                style = CarTheme.type.bodyMedium,
                color = colors.textPrimary,
            )
            Spacer(Modifier.height(dimens.spaceS))
            CarButton(
                text = if (developer.airPlayTcpCaptureActive) "正在采集（最长 90 秒）…" else "开始 90 秒 TCP 抓包",
                onClick = actions::startAirPlayTcpCapture,
                modifier = Modifier.fillMaxWidth(),
                variant = CarButtonVariant.Secondary,
                // 2026-10-23 对齐规范：整行按钮统一 Large（68dp）。
                // 原来这颗是 Regular(56dp)，和下面「开始采集并上传」的 Large 差 12dp，
                // 同一屏里两颗整宽按钮高度不同，看起来就是没对齐。
                size = CarButtonSize.Large,
                enabled = !busy,
            )
        }

        Spacer(Modifier.height(dimens.spaceL))
        CarButton(
            text = if (busy) "正在处理…" else "开始采集并上传",
            onClick = { actions.collectAndUploadLogs(scope, includeClientIpv4) },
            modifier = Modifier.fillMaxWidth(),
            variant = CarButtonVariant.Primary,
            size = CarButtonSize.Large,
            icon = CarIcons.Log,
            enabled = !busy,
        )

        SectionHeader(title = "设备信息")
        CarCard(modifier = Modifier.fillMaxWidth()) {
            CarSettingInfo(
                title = "设备编号",
                value = developer.deviceId.ifBlank { "读取中…" },
            )
            CarRowDivider()
            CarSettingInfo(
                title = "诊断服务",
                value = developer.serverUrl.ifBlank { "未知" },
            )
            CarRowDivider()
            CarSettingInfo(
                title = "应用版本",
                value = developer.appVersionName.ifBlank { "未知" },
            )
            CarRowDivider()
            CarSettingInfo(
                title = "安卓版本",
                value = developer.androidVersion.ifBlank { "未知" },
            )
            CarRowDivider()
            CarSettingInfo(
                title = "系统版本",
                value = developer.systemVersion.ifBlank { "未知" },
            )
            CarRowDivider()
            CarSettingInfo(
                title = "设备型号",
                value = developer.deviceModel.ifBlank { "未知" },
            )
        }

        Spacer(Modifier.height(dimens.spaceM))
        CarButton(
            text = "重新读取设备信息",
            onClick = actions::refreshDeveloperInfo,
            modifier = Modifier.fillMaxWidth(),
            variant = CarButtonVariant.Secondary,
            icon = CarIcons.Refresh,
            enabled = !busy,
        )

        SectionHeader(title = "最近一次上传")
        CarCard(modifier = Modifier.fillMaxWidth()) {
            if (developer.lastLogId.isBlank() && developer.lastFileName.isBlank()) {
                Text(
                    text = "本机还没有成功上传过诊断包。",
                    style = CarTheme.type.bodyMedium,
                    color = colors.textSecondary,
                )
            } else {
                CarSettingInfo(
                    title = "日志编号",
                    value = developer.lastLogId.ifBlank { "（服务端未返回）" },
                )
                CarRowDivider()
                CarSettingInfo(
                    title = "文件名",
                    value = developer.lastFileName.ifBlank { "未知" },
                )
                CarRowDivider()
                CarSettingInfo(
                    title = "上传时间",
                    value = developer.lastUploadAt.ifBlank { "未知" },
                )
            }
        }

        SectionHeader(
            title = "车机原生热点探测",
        )
        CarCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(dimens.spaceM),
            ) {
                CarStatusPill(
                    text = probe.phaseLabel(),
                    indicator = probe.phaseIndicator(),
                )
                if (probe.info.source != CarHotspotProbeSource.NONE) {
                    Text(
                        text = probe.info.source.label(),
                        style = CarTheme.type.bodySmall,
                        color = colors.textTertiary,
                    )
                }
            }
            if (probe.summary.isNotEmpty()) {
                Spacer(Modifier.height(dimens.spaceM))
                Text(
                    text = probe.summary,
                    style = CarTheme.type.bodyMedium,
                    color = colors.textSecondary,
                )
            }
        }

        if (probe.finished) {
            Spacer(Modifier.height(dimens.spaceM))
            CarCard(modifier = Modifier.fillMaxWidth()) {
                CarSettingInfo(
                    title = "热点是否开启",
                    value = when {
                        probe.info.enabled -> "已开启"
                        probe.info.detected -> "未检测到开启状态"
                        else -> "未开启 / 未探测到"
                    },
                )
                CarRowDivider()
                CarSettingInfo(
                    title = "热点名称 (SSID)",
                    value = probe.info.ssid ?: "未读到",
                )
                CarRowDivider()
                CarSettingInfo(
                    title = "AP 网卡",
                    value = probe.info.interfaceName ?: "未读到",
                )
                CarRowDivider()
                CarSettingInfo(
                    title = "AP 地址 / 网段",
                    value = listOfNotNull(
                        probe.info.hostAddress?.let {
                            probe.info.prefixLength?.let { prefix -> "$it/$prefix" } ?: it
                        },
                        probe.info.subnet,
                    ).joinToString("  ").ifBlank { "未读到" },
                    description = probe.info.gateway?.let { "网关 $it" },
                )
                CarRowDivider()
                CarSettingInfo(
                    title = "DHCP 地址池",
                    value = listOfNotNull(probe.info.dhcpStart, probe.info.dhcpEnd)
                        .joinToString(" - ")
                        .ifBlank { "未读到" },
                )
                CarRowDivider()
                CarSettingInfo(
                    title = "频段 / 信道",
                    value = listOfNotNull(
                        probe.info.band.label().takeIf { probe.info.band != CarHotspotBand.AUTO },
                        probe.info.channel.takeIf { it > 0 }?.let { "信道 $it" },
                    ).joinToString("  ").ifBlank { "未读到" },
                )
                CarRowDivider()
                CarSettingInfo(
                    title = "DNS",
                    value = probe.info.dns.joinToString("  ").ifBlank { "未读到" },
                )
                CarRowDivider()
                CarSettingInfo(
                    title = "热点密码",
                    value = if (probe.info.passphrase != null) "已读到" else "未读到",
                    description = if (probe.info.passphrase != null) {
                        "复用时不需要用户再填密码。"
                    } else {
                        "Android 10 起密码多存为密文；明文只在 hostapd.conf / softap.conf 里，" +
                            "读它们需要 root。"
                    },
                )
            }
        }

        Spacer(Modifier.height(dimens.spaceM))
        CarButton(
            text = if (probe.isBusy) "探测中…" else "探测车机原生热点（ADB）",
            onClick = actions::runNativeHotspotProbe,
            modifier = Modifier.fillMaxWidth(),
            variant = CarButtonVariant.Primary,
            icon = CarIcons.Refresh,
            enabled = !probe.isBusy,
        )

        if (probe.entries.isNotEmpty()) {
            Spacer(Modifier.height(dimens.spaceM))
            CarCard(modifier = Modifier.fillMaxWidth()) {
                probe.entries.forEachIndexed { position, entry ->
                    if (position > 0) CarRowDivider()
                    ProbeEntryRow(entry)
                }
            }
        }

        SectionHeader(
            title = "ADB 独立热点（兜底）",
        )
        CarCard(modifier = Modifier.fillMaxWidth()) {
            CarAdbHotspotSummaryRow(hotspot)
        }

        Spacer(Modifier.height(dimens.spaceM))
        CarButton(
            text = if (hotspot.isBusy) "自检进行中…" else "运行 ADB 热点自检",
            onClick = { actions.runAdbHotspotSelfCheck(model.settings.adbHotspotInterface) },
            modifier = Modifier.fillMaxWidth(),
            variant = CarButtonVariant.Secondary,
            icon = CarIcons.Refresh,
            enabled = !hotspot.isBusy,
        )

        if (hotspot.steps.isNotEmpty()) {
            Spacer(Modifier.height(dimens.spaceM))
            CarAdbHotspotStepsCard(hotspot.steps)
        }

        Spacer(Modifier.height(dimens.spaceXl))

        // ============================================================
        // 调试反连隧道（2026-10-10 加，斌哥口径）
        // ============================================================
        //
        // 原文：「现在这样调试太麻烦了，我想让你直接在线终端调试，通过服务中转」
        //   「不要，你塞进 DBPlay 开发者模式里面」。
        //
        // 原理：车机自带 4G 在 NAT 后，无公网入口 ⇒ 只能**车机主动反连**。
        //   车机连公网跳板 :7000 → 跳板 relay 桥到 :5555 → 开发机 adb connect。
        //   开了之后改代码直接远程调，不用再出包/手装（一轮十几分钟）。
        //
        // 实现见 [com.dbtool.carplay.geely.lynk.LynkDebugTunnel]，
        // 由 `CarAppController` 在启动时按设置拉起。
        SectionHeader(
            title = "调试反连隧道",
        )
        CarCard(modifier = Modifier.fillMaxWidth()) {
            CarSettingSwitch(
                title = "开启调试隧道",
                checked = model.settings.debugTunnelEnabled,
                onCheckedChange = { actions.applySettings(model.settings.copy(debugTunnelEnabled = it), reconnect = false) },
            )
            CarRowDivider()
            // 状态行 + 「立即重连隧道」（2026-10-16）。
            //
            // 为什么必须有：此前这一张卡里只有开关和四个输入框 —— 开关拨上去之后
            // **连没连上、卡在哪一步（握手中？已连上？在退避？）、上次为什么失败**
            // 在界面上全都看不到，排障只能靠采一份诊断包再看日志。
            // 数据来源是 `CarDeveloperState.debugTunnel`（隧道只读快照，由控制器刷新），
            // 本页不做任何判断，只把状态与那句人话摊开。
            //
            // ⚠️ 这里**不显示服务器地址**：地址只以 `CarSensitiveText.hostLabel(...)`
            //    的运行时脱敏形态出现在日志里，界面这一行连脱敏值都不需要
            //    （输入框就在下面一格，重复一遍只是把敏感值多印一处）。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = dimens.spaceM, vertical = dimens.spaceS),
                verticalArrangement = Arrangement.spacedBy(dimens.spaceM),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(dimens.spaceM),
                ) {
                    CarStatusPill(
                        text = developer.debugTunnel.state.title,
                        indicator = developer.debugTunnel.state.indicator(),
                    )
                    Text(
                        text = developer.debugTunnel.detailLine(System.currentTimeMillis()),
                        style = CarTheme.type.bodySmall,
                        color = colors.textTertiary,
                    )
                }
                CarButton(
                    text = "立即重连隧道",
                    onClick = actions::restartDebugTunnel,
                    modifier = Modifier.fillMaxWidth(),
                    variant = CarButtonVariant.Secondary,
                    icon = CarIcons.Refresh,
                    // 开关关着时点它没有意义（`restartDebugTunnel` 内部也会因为
                    // `debugTunnelEnabled = false` 而起不来一轮），所以直接置灰，
                    // 而不是让用户点一下什么都没发生。
                    enabled = model.settings.debugTunnelEnabled,
                )
            }
            CarRowDivider()
            // ⚠️ 2026-10-11（PATCH-0076）：**服务器地址与连接口令都打码显示**
            //    （`isPassword = true` ⇒ `PasswordVisualTransformation`，与热点密码同一手法）。
            //    车主口径："这些都是明文，我需要你默认值隐藏，不能别人看见"。
            //    另外 `placeholder`（输入框为空时的灰字提示）**绝不能写真实值** ——
            //    原来那行提示填的就是真实跳板地址，等于把地址印在界面上给所有人看。
            //    ⚠️ 连带纪律：**本文件里不许出现真实地址/口令的字面量**（连注释也不行）——
            //    有 `DeveloperTunnelUiMaskingContractTest` 盯着这一条。
            CarSettingText(
                title = "服务器地址",
                value = model.settings.debugTunnelHost,
                onValueChange = { actions.applySettings(model.settings.copy(debugTunnelHost = it.trim()), reconnect = false) },
                placeholder = "公网跳板 IP 或域名",
                isPassword = true,
            )
            CarRowDivider()
            CarSettingText(
                title = "服务器端口",
                value = model.settings.debugTunnelPort.toString(),
                onValueChange = { raw ->
                    raw.filter { it.isDigit() }.take(5).toIntOrNull()
                        ?.let { actions.applySettings(model.settings.copy(debugTunnelPort = it), reconnect = false) }
                },
                placeholder = "7000",
                keyboardType = KeyboardType.Number,
            )
            CarRowDivider()
            CarSettingText(
                title = "本机 adbd 端口",
                value = model.settings.debugTunnelLocalAdbPort.toString(),
                onValueChange = { raw ->
                    raw.filter { it.isDigit() }.take(5).toIntOrNull()
                        ?.let { actions.applySettings(model.settings.copy(debugTunnelLocalAdbPort = it), reconnect = false) }
                },
                placeholder = "5556",
                keyboardType = KeyboardType.Number,
            )
            CarRowDivider()
            CarSettingText(
                title = "连接口令",
                value = model.settings.debugTunnelSecret,
                onValueChange = { actions.applySettings(model.settings.copy(debugTunnelSecret = it.trim()), reconnect = false) },
                placeholder = "与服务器一致的握手口令",
                isPassword = true,
            )
        }

        Spacer(Modifier.height(dimens.spaceXl))
        CarButton(
            text = "退出开发者模式",
            onClick = actions::lockDeveloperMode,
            modifier = Modifier.fillMaxWidth(),
            variant = CarButtonVariant.Danger,
        )
        Spacer(Modifier.height(dimens.spaceXxl))
    }
}

/** 阶段是否"正在推进"。推进中要禁掉按钮，避免用户重复点击。 */
private fun CarLogUploadPhase.isBusy(): Boolean = when (this) {
    CarLogUploadPhase.IDLE,
    CarLogUploadPhase.DONE,
    CarLogUploadPhase.FAILED,
    -> false

    CarLogUploadPhase.COLLECTING,
    CarLogUploadPhase.PACKAGING,
    CarLogUploadPhase.UPLOADING,
    -> true
}

/** 阶段的中文名。穷尽式 `when` 不写 `else`：新增阶段时编译失败，强制显式决策。 */
private fun CarLogUploadPhase.label(): String = when (this) {
    CarLogUploadPhase.IDLE -> "就绪"
    CarLogUploadPhase.COLLECTING -> "正在采集"
    CarLogUploadPhase.PACKAGING -> "正在打包"
    CarLogUploadPhase.UPLOADING -> "正在上传"
    CarLogUploadPhase.DONE -> "上传成功"
    CarLogUploadPhase.FAILED -> "上传失败"
}

/** 探测状态 → 胶囊文字。 */
private fun CarNativeHotspotProbeState.phaseLabel(): String = when {
    running -> "探测中"
    !finished -> "尚未探测"
    info.detected -> "探测到车机热点"
    // ⚠️ "没拿到 shell" 与 "拿到了、但确实没有热点" 必须分开报。
    // 前者只说明 ADB 没通（结论顶多来自本进程反射，而反射受厂商裁剪影响极大），
    // 后者才是"车机真的没有可用热点"。混在一起会把"ADB 不通"误读成
    // "车机没有热点"，进而错误地放弃首选方案去自建。
    !shellAvailable -> "未拿到 ADB shell"
    else -> "未探测到车机热点"
}

/** 探测状态 → 状态语义色。 */
private fun CarNativeHotspotProbeState.phaseIndicator(): CarStatusIndicator = when {
    running -> CarStatusIndicator.WORKING
    !finished -> CarStatusIndicator.IDLE
    info.detected -> CarStatusIndicator.READY
    // 首选方案（ADB 探测）没能执行 ⇒ 用 ERROR 让人一眼看到要去看下面的原始输出。
    !shellAvailable -> CarStatusIndicator.ERROR
    else -> CarStatusIndicator.IDLE
}

/** 探测来源 → 中文名。穷尽式 `when`，不写 `else`。 */
private fun CarHotspotProbeSource.label(): String = when (this) {
    CarHotspotProbeSource.ADB_SHELL -> "来源：ADB"
    CarHotspotProbeSource.LOCAL_REFLECTION -> "来源：本地反射"
    CarHotspotProbeSource.NONE -> "来源：无"
}

/** 频段 → 中文名。 */
private fun CarHotspotBand.label(): String = when (this) {
    CarHotspotBand.AUTO -> "自动"
    CarHotspotBand.GHZ_2_4 -> "2.4 GHz"
    CarHotspotBand.GHZ_5 -> "5 GHz"
}

/**
 * 隧道状态 → 状态点语义色。穷尽式 `when`，不写 `else`（新增状态时编译失败，强制显式决策）。
 *
 * `RETRYING` 用 `ERROR` 而不是 `WORKING`：它虽然"正在自动重连"，但对用户来说
 * 事实是**连接已经断了**，而且副文案里就挂着"上次失败：xxx"——标红才能让人看一眼
 * 就决定要不要改地址，而不是以为一切正常。
 */
private fun CarDebugTunnelState.indicator(): CarStatusIndicator = when (this) {
    CarDebugTunnelState.IDLE -> CarStatusIndicator.IDLE
    CarDebugTunnelState.CONNECTING -> CarStatusIndicator.WORKING
    CarDebugTunnelState.CONNECTED -> CarStatusIndicator.READY
    CarDebugTunnelState.RETRYING -> CarStatusIndicator.ERROR
}

/**
 * 一条探测记录。
 *
 * 与 ADB 自检的步骤行（`CarAdbHotspotStepsCard` 内部渲染）的区别：这里**把命令本身也显示出来**——
 * 探测的价值就在于"这条命令在车上返回了什么"，只给标题等于把最关键的一半藏起来。
 */
@Composable
private fun ProbeEntryRow(entry: CarHotspotProbeEntry) {
    val dimens = CarTheme.dimens
    val colors = CarTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = dimens.spaceM),
        horizontalArrangement = Arrangement.spacedBy(dimens.spaceM),
    ) {
        CarStatusDot(
            indicator = if (entry.ok) CarStatusIndicator.READY else CarStatusIndicator.ERROR,
            modifier = Modifier.padding(top = dimens.spaceXs),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = "${entry.index}. ${entry.title}",
                style = CarTheme.type.bodyMedium,
                color = colors.textPrimary,
            )
            if (entry.command.isNotEmpty()) {
                Spacer(Modifier.height(dimens.spaceXs))
                Text(
                    text = entry.command,
                    style = CarTheme.type.bodySmall,
                    color = colors.textTertiary,
                )
            }
            Spacer(Modifier.height(dimens.spaceXs))
            Text(
                // 原始输出**不截断**：`ip addr` / `dumpsys` 的关键信息常在最后一行，
                // 截断等于把最有用的一段扔掉。（超长命令在数据层已按上限裁剪并标注。）
                text = entry.output,
                style = CarTheme.type.bodySmall,
                color = colors.textSecondary,
            )
        }
    }
}

/** 阶段 → 状态语义色。映射只在这里一处，页面不自己挑颜色。 */
private fun CarLogUploadPhase.indicator(): CarStatusIndicator = when (this) {
    CarLogUploadPhase.IDLE -> CarStatusIndicator.IDLE
    CarLogUploadPhase.COLLECTING,
    CarLogUploadPhase.PACKAGING,
    CarLogUploadPhase.UPLOADING,
    -> CarStatusIndicator.WORKING

    CarLogUploadPhase.DONE -> CarStatusIndicator.READY
    CarLogUploadPhase.FAILED -> CarStatusIndicator.ERROR
}
