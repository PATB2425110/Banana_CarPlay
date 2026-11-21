package com.dbtool.carplay.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.dbtool.carplay.api.hotspot.CarAdbHotspotPhase
import com.dbtool.carplay.api.hotspot.CarAdbHotspotState
import com.dbtool.carplay.api.hotspot.CarAdbHotspotStep
import com.dbtool.carplay.ui.CarStatusIndicator
import com.dbtool.carplay.ui.theme.CarTheme

/**
 * ADB 独立热点的可观测状态渲染。
 *
 * **开发者页与设置→诊断页共用这一份。**
 *
 * 为什么不各写一遍：这两处的读者不同、但要看的东西完全一样——
 * 「ADB 到底通没通、卡在哪一步、那一步的原始输出是什么」。
 * 各写一遍必然漂移（一边加了字段另一边没加），而现场排查时最怕的
 * 就是"两页显示的结论不一致"。所以渲染只有这一处，
 * 两边只是**入口不同**：开发者页是解锁后的完整排障台，诊断页是随手可看的回显。
 */

/** ADB 热点阶段 → 中文名。穷尽式 `when`，不写 `else`：新增阶段时编译失败，强制显式决策。 */
fun CarAdbHotspotPhase.displayLabel(): String = when (this) {
    CarAdbHotspotPhase.IDLE -> "未运行"
    CarAdbHotspotPhase.CHECKING_BINARIES -> "正在检查二进制"
    CarAdbHotspotPhase.ENABLING_ADB -> "正在开通 ADB"
    CarAdbHotspotPhase.CONNECTING_ADB -> "正在连接 ADB"
    CarAdbHotspotPhase.PROBING_INTERFACE -> "正在探测网卡"
    CarAdbHotspotPhase.SELECTING_NETWORK -> "正在选择网卡与网段"
    CarAdbHotspotPhase.CONFIGURING -> "正在写入配置"
    CarAdbHotspotPhase.STARTING_HOSTAPD -> "正在启动 hostapd"
    CarAdbHotspotPhase.STARTING_DHCP -> "正在启动 DHCP"
    CarAdbHotspotPhase.RUNNING -> "热点运行中"
    CarAdbHotspotPhase.FAILED -> "未建立"
    CarAdbHotspotPhase.STOPPED -> "已停止"
}

/** ADB 热点阶段 → 状态语义色。映射只在这里一处。 */
fun CarAdbHotspotPhase.statusIndicator(): CarStatusIndicator = when (this) {
    CarAdbHotspotPhase.IDLE -> CarStatusIndicator.IDLE
    CarAdbHotspotPhase.RUNNING -> CarStatusIndicator.READY
    CarAdbHotspotPhase.FAILED -> CarStatusIndicator.ERROR
    // 自检跑完会把阶段放回 IDLE，所以"已停止"没有独立语义色。
    CarAdbHotspotPhase.STOPPED -> CarStatusIndicator.IDLE

    CarAdbHotspotPhase.CHECKING_BINARIES,
    CarAdbHotspotPhase.ENABLING_ADB,
    CarAdbHotspotPhase.CONNECTING_ADB,
    CarAdbHotspotPhase.PROBING_INTERFACE,
    CarAdbHotspotPhase.SELECTING_NETWORK,
    CarAdbHotspotPhase.CONFIGURING,
    CarAdbHotspotPhase.STARTING_HOSTAPD,
    CarAdbHotspotPhase.STARTING_DHCP,
    -> CarStatusIndicator.WORKING
}

/**
 * ADB 状态摘要行：阶段胶囊 + 网卡 / 地址。
 *
 * 网卡与地址只在探测出来之后才显示——`IDLE` 时它们是空串，
 * 显示空值会让人以为"探测到了但没值"。
 */
@Composable
fun CarAdbHotspotSummaryRow(state: CarAdbHotspotState, modifier: Modifier = Modifier) {
    val dimens = CarTheme.dimens
    val colors = CarTheme.colors
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimens.spaceM),
    ) {
        CarStatusPill(
            text = state.phase.displayLabel(),
            indicator = state.phase.statusIndicator(),
        )
        if (state.interfaceName.isNotEmpty()) {
            Text(
                text = "网卡 ${state.interfaceName}",
                style = CarTheme.type.bodySmall,
                color = colors.textTertiary,
            )
        }
        if (state.hostAddress.isNotEmpty()) {
            Text(
                text = state.hostAddress,
                style = CarTheme.type.bodySmall,
                color = colors.textTertiary,
            )
        }
    }
    if (state.message.isNotEmpty()) {
        Spacer(Modifier.height(dimens.spaceM))
        Text(
            text = state.message,
            style = CarTheme.type.bodyMedium,
            color = colors.textSecondary,
        )
    }
}

/**
 * 逐步结果卡片。
 *
 * 空列表时**不渲染任何东西**——没有步骤就说明自检还没跑过，
 * 摆一张空卡片只会让人以为"跑过了但没输出"。
 */
@Composable
fun CarAdbHotspotStepsCard(steps: List<CarAdbHotspotStep>, modifier: Modifier = Modifier) {
    if (steps.isEmpty()) return
    CarCard(modifier = modifier.fillMaxWidth()) {
        steps.forEachIndexed { position, step ->
            if (position > 0) CarRowDivider()
            CarAdbHotspotStepRow(step)
        }
    }
}

/**
 * ADB 热点自检里的一条步骤。
 *
 * 左侧圆点用 [CarStatusIndicator.READY] / [CarStatusIndicator.ERROR] 表达通过与否，
 * **不给它加文字**——"通过/失败"由颜色 + 右侧原始输出共同表达，再写一遍是冗余。
 */
@Composable
private fun CarAdbHotspotStepRow(step: CarAdbHotspotStep) {
    val dimens = CarTheme.dimens
    val colors = CarTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = dimens.spaceM),
        horizontalArrangement = Arrangement.spacedBy(dimens.spaceM),
    ) {
        CarStatusDot(
            indicator = if (step.ok) CarStatusIndicator.READY else CarStatusIndicator.ERROR,
            modifier = Modifier.padding(top = dimens.spaceXs),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = "${step.index}. ${step.title}",
                style = CarTheme.type.bodyMedium,
                color = colors.textPrimary,
            )
            Spacer(Modifier.height(dimens.spaceXs))
            Text(
                // 原始输出**不截断**：`ifconfig` / `ip rule show` 这类命令的关键信息
                // 常在最后一行，截断等于把最有用的一段扔掉。
                text = step.detail,
                style = CarTheme.type.bodySmall,
                color = colors.textSecondary,
            )
        }
    }
}
