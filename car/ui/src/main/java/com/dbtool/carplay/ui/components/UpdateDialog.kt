package com.dbtool.carplay.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.dbtool.carplay.api.update.CarUpdatePhase
import com.dbtool.carplay.api.update.CarUpdateState
import com.dbtool.carplay.ui.theme.CarTheme

/**
 * 在线更新对话框。
 *
 * 与 [CarAlertDialog] 分开写而不是复用它，因为它有两条它表达不了的东西：
 * 1. **进度条**——下载是个几十秒的过程，只给一句"正在下载"用户不知道还要等多久；
 * 2. **更新说明是一组条目**，不是一句话。
 *
 * ## 三条设计取向
 *
 * 1. **标题跟着阶段走**（[dialogTitle]）：`发现新版本` → `正在下载` →
 *    `安装包已就绪` → `正在安装`。标题是对话框里字号最大的一行，
 *    让它一直停在"发现新版本"等于浪费了这个位置——用户扫一眼就该知道
 *    "现在轮到谁了"。强制更新时始终是 `必须更新`（那才是当前最该知道的事）。
 * 2. **更新说明限高可滚**：一次发版可能有十几条 changelog，
 *    全铺出来会把按钮顶出屏幕（车机屏比手机矮）。这里给一个上限，
 *    超出部分滚动——**不截断**，因为截断会让"到底改了什么"变成猜谜。
 * 3. **进度给数字**：光有进度条看不出"还要多久"。右侧补一个百分比，
 *    下载中同时在 `message` 里给出已下载/总大小（由实现侧填）。
 *
 * ⚠️ **强制更新时没有关闭路径**（[CarUpdateState.forceUpdate]）：
 * 此时服务端已经判定当前版本不再受支持（协议栈缺陷、接口不兼容等），
 * 放一个"稍后再说"出去，等于让用户把车机停在一个连不上的版本上。
 * 所以 `dismissText = null`，并且点遮罩/返回键也不关。
 */
@Composable
fun CarUpdateDialog(
    state: CarUpdateState,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CarTheme.colors
    val dimens = CarTheme.dimens
    val confirmLabel = state.phase.confirmLabel()
    val busy = state.phase == CarUpdatePhase.DOWNLOADING || state.phase == CarUpdatePhase.INSTALLING

    AlertDialog(
        // 强制更新时把"点外面关掉"也堵上，否则上面的 dismissButton = null 形同虚设。
        onDismissRequest = { if (!state.forceUpdate) onDismiss() },
        modifier = modifier.widthIn(min = dimens.contentMaxWidth / 2),
        title = {
            Text(
                text = state.dialogTitle(),
                style = CarTheme.type.titleLarge,
                color = colors.textPrimary,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(dimens.spaceM),
            ) {
                // 版本对比：旧版本 → 新版本。有版本号时补在括号里，
                // 排障时"版本号比版本名更硬"（服务端是按 code 判的）。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(dimens.spaceS),
                ) {
                    Text(
                        text = state.currentVersionName.withCode(state.currentVersionCode)
                            .ifEmpty { "当前版本" },
                        style = CarTheme.type.bodyLarge,
                        color = colors.textTertiary,
                    )
                    Text(text = "→", style = CarTheme.type.bodyLarge, color = colors.textTertiary)
                    Text(
                        text = state.latestVersionName.withCode(state.latestVersionCode)
                            .ifEmpty { "新版本" },
                        style = CarTheme.type.titleSmall,
                        color = colors.primaryText,
                    )
                }

                // 定向灰度：说明"为什么这台机器收到了"。不给这句，
                // 用户会以为所有人都收到了同一个版本。
                if (state.isTargeted) {
                    Text(
                        text = "本次更新针对你的设备推送",
                        style = CarTheme.type.bodySmall,
                        color = colors.textTertiary,
                    )
                }

                if (state.releaseNotes.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(dimens.spaceXs)) {
                        Text(
                            text = "更新说明（${state.releaseNotes.size} 条）",
                            style = CarTheme.type.label,
                            color = colors.textSecondary,
                        )
                        // 限高 + 可滚：条目多时不把按钮顶出屏幕，也不截断内容。
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = dimens.rowMinHeight * 6)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(dimens.spaceXs),
                        ) {
                            for (note in state.releaseNotes) {
                                Text(
                                    text = "· $note",
                                    style = CarTheme.type.bodyMedium,
                                    color = colors.textSecondary,
                                )
                            }
                        }
                    }
                }

                if (state.phase == CarUpdatePhase.DOWNLOADING ||
                    state.phase == CarUpdatePhase.PENDING_INSTALL ||
                    state.phase == CarUpdatePhase.READY
                ) {
                    CarProgressBar(
                        progress = state.progress,
                        label = (state.progress * 100).toInt().coerceIn(0, 100).let { "$it%" },
                    )
                }

                if (state.message.isNotEmpty()) {
                    Text(
                        text = state.message,
                        style = CarTheme.type.bodyMedium,
                        color = if (state.phase == CarUpdatePhase.FAILED) {
                            colors.error
                        } else {
                            colors.textSecondary
                        },
                    )
                }

                // 说清楚"下载完还要再点一次"：否则用户会以为下载完就自动装好了，
                // 而系统安装界面弹出来时他并不知道那是这条链路的一部分。
                if (state.installNeedsUserConfirm && state.phase == CarUpdatePhase.READY) {
                    Text(
                        text = "接下来需要在系统安装界面再点一次「安装」。",
                        style = CarTheme.type.bodySmall,
                        color = colors.textTertiary,
                    )
                }

                // 失败时给一句可执行的下一步。只报"失败"用户会反复点同一个按钮；
                // 车机上最常见的两种失败都与网络有关，先往那上面引。
                if (state.phase == CarUpdatePhase.FAILED) {
                    Text(
                        text = "可先确认车机已联网，再点「重试」；若多次失败请稍后再试。",
                        style = CarTheme.type.bodySmall,
                        color = colors.textTertiary,
                    )
                }
            }
        },
        confirmButton = {
            if (confirmLabel != null) {
                CarButton(
                    text = confirmLabel,
                    onClick = {
                        // 包已下好（READY / PENDING_INSTALL）⇒ 直接装；否则先去下载。
                        if (state.phase == CarUpdatePhase.READY ||
                            state.phase == CarUpdatePhase.PENDING_INSTALL
                        ) {
                            onInstall()
                        } else {
                            onDownload()
                        }
                    },
                    enabled = !busy,
                )
            }
        },
        dismissButton = if (state.forceUpdate) {
            null
        } else {
            {
                CarButton(
                    text = if (state.phase == CarUpdatePhase.READY ||
                        state.phase == CarUpdatePhase.PENDING_INSTALL
                    ) {
                        "稍后安装"
                    } else {
                        "稍后再说"
                    },
                    onClick = onDismiss,
                    variant = CarButtonVariant.Ghost,
                )
            }
        },
        shape = CarTheme.shapes.extraLarge,
        containerColor = colors.surfaceElevated,
        titleContentColor = colors.textPrimary,
        textContentColor = colors.textSecondary,
    )
}

/**
 * 对话框标题。**强制更新优先于阶段**——"必须更新"是用户此刻最该知道的事，
 * 不能因为正在下载就被"正在下载"顶掉。
 *
 * 穷尽式 `when` 不写 `else`：新增阶段时编译失败，强制显式决策。
 */
private fun CarUpdateState.dialogTitle(): String {
    if (forceUpdate) return "必须更新"
    return when (phase) {
        CarUpdatePhase.AVAILABLE -> "发现新版本"
        CarUpdatePhase.DOWNLOADING -> "正在下载"
        // 静默路径下对话框本来就不显示（`dialogVisible` 恒 false），
        // 给一个中性的标题即可，不必为它编一套文案。
        CarUpdatePhase.PENDING_INSTALL -> "等待自动安装"
        CarUpdatePhase.READY -> "安装包已就绪"
        CarUpdatePhase.INSTALLING -> "正在安装"

        // 这几个阶段对话框本来就不显示（`dialogVisible` 为 false），
        // 真到了这里也退回到一个中性标题。
        CarUpdatePhase.IDLE,
        CarUpdatePhase.CHECKING,
        CarUpdatePhase.UP_TO_DATE,
        CarUpdatePhase.FAILED,
        -> "版本更新"
    }
}

/**
 * `0.10.3` → `0.10.3 (113)`。版本号为空时原样返回（不加空括号）。
 *
 * 版本名给人看、版本号给服务端看，两个都摆出来，
 * 用户截图报障时工程师不必再问"你的 code 是多少"。
 */
private fun String.withCode(code: Long): String =
    if (isBlank() || code <= 0L) this else "$this ($code)"

/**
 * 当前阶段主按钮的文案。null = 这个阶段没有可点的动作。
 *
 * 穷尽式 `when` 不写 `else`：新增阶段时编译失败，强制显式决策。
 */
private fun CarUpdatePhase.confirmLabel(): String? = when (this) {
    // 这几个阶段对话框本来就不显示（`dialogVisible` 为 false），
    // 真到了这里也不该给按钮。
    CarUpdatePhase.IDLE,
    CarUpdatePhase.CHECKING,
    CarUpdatePhase.UP_TO_DATE,
    -> null

    CarUpdatePhase.AVAILABLE -> "下载更新"
    CarUpdatePhase.DOWNLOADING -> "正在下载…"
    CarUpdatePhase.READY -> "立即安装"
    // 静默路径下对话框不显示；万一显示了（例如用户主动查看），
    // 包已经下好，给他一个「立即安装」总比一个没有按钮的框好。
    CarUpdatePhase.PENDING_INSTALL -> "立即安装"
    CarUpdatePhase.INSTALLING -> "正在安装…"

    // 失败一律回到"下载"入口：下载器发现同版本的包已经在了就直接跳到待安装，
    // 因此这里的"重试"对下载失败与安装失败都是对的。
    CarUpdatePhase.FAILED -> "重试"
}
