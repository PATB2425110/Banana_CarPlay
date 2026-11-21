package com.dbtool.carplay.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.dbtool.carplay.api.developer.DEVELOPER_UNLOCK_TAP_COUNT
import com.dbtool.carplay.api.update.CarUpdatePhase
import com.dbtool.carplay.api.update.CarUpdateState
import com.dbtool.carplay.ui.CarUiModel
import com.dbtool.carplay.ui.components.CarButton
import com.dbtool.carplay.ui.components.CarButtonVariant
import com.dbtool.carplay.ui.components.CarCard
import com.dbtool.carplay.ui.components.CarIconBadge
import com.dbtool.carplay.ui.components.CarRowDivider
import com.dbtool.carplay.ui.components.CarScreen
import com.dbtool.carplay.ui.components.CarSettingAction
import com.dbtool.carplay.ui.components.CarSettingInfo
import com.dbtool.carplay.ui.components.CarSettingSwitch
import com.dbtool.carplay.ui.components.SectionHeader
import com.dbtool.carplay.ui.theme.CarIcons
import com.dbtool.carplay.ui.theme.CarTheme

/**
 * 关于页。
 *
 * 除了版本信息，这里刻意保留三块**通常被藏起来、但现场排障必须用到**的内容：
 * 平台身份、上游 Core 版本、许可与来源。
 * 车机项目出问题时，工程师问的第一句话往往就是"你装的是哪个版本、什么系统"。
 *
 * 顶部主卡是**作者卡**：应用名 + 抖音号 + 适配说明。
 * 这样一句话同时回答"这是谁做的东西"与"这台机器上能不能用"，
 * 比只写一行"作者 XX"更有用——用户拿到包的第一件事就是确认它适不适配自己的车机。
 *
 * 本页还承担**开发者模式的唯一入口**：连续点击「应用版本」的值
 * [DEVELOPER_UNLOCK_TAP_COUNT] 次即解锁（判定与超时在 `CarAppController`）。
 * 解锁后这里会多出一行进入开发者页，未解锁时它不存在——
 * 也就是说，这个入口在普通用户的界面上**完全不占位置**。
 */
@Composable
fun AboutScreen(
    model: CarUiModel,
    onExitApp: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onVersionTap: () -> Unit = {},
    onOpenDeveloper: () -> Unit = {},
    onCheckUpdate: () -> Unit = {},
    onToggleSilentUpdate: (Boolean) -> Unit = {},
) {
    val dimens = CarTheme.dimens
    val colors = CarTheme.colors
    val info = model.platformInfo
    val tapCount = model.developerTapCount
    val remaining = (DEVELOPER_UNLOCK_TAP_COUNT - tapCount).coerceAtLeast(0)

    CarScreen(
        title = "关于",
        onBack = onBack,
        modifier = modifier,
    ) {
        CarCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(dimens.spaceM),
            ) {
                CarIconBadge(
                    icon = CarIcons.Home,
                    tint = colors.primaryText,
                    container = colors.primaryContainer,
                )
                Text(
                    text = "Apple CarPlay",
                    style = CarTheme.type.displayMedium,
                    color = colors.textPrimary,
                )
            }
        }

        SectionHeader(title = "版本")
        CarCard(modifier = Modifier.fillMaxWidth()) {
            CarSettingInfo(
                title = "应用版本",
                value = model.appVersionName.ifBlank { "未知" },
                onClick = onVersionTap,
                description = if (!model.developer.unlocked && tapCount > 0) "再点击${remaining}次开启开发者模式" else null,
                // 连点计数 1..6 时给一句进度提示；0 次（还没点）与已解锁时都不显示，
                // 免得普通用户看到"再点 7 次…"这种莫名其妙的文案。
            )
            CarRowDivider()
            CarSettingInfo(
                title = "CarPlay 核心版本",
                value = model.coreVersionName.ifBlank { "未知" },
            )
            // 后台自动更新开关（2026-10-11）。
            //
            // ⚠️ 放「关于」页而不是设置页：更新状态刻意独立于会话设置
            // （`CarUpdatePrefs` 的类注释写明理由）—— 放进设置页会被
            // "恢复默认设置"一起重置掉，而"要不要让 App 自己装自己"
            // 不该被一个跟它无关的按钮改变。
            //
            // ⚠️ 默认**开**（2026-10-13 车主口径改；PATCH-0115）。改之前是"默认关"，
            // 理由（"唯一一个能让 App 把自己装死的功能"）没有消失，而是由
            // 「服务端 auto_update 那把锁 + 同版本 2 次失败即熔断 + 用户随时可关」
            // 三重保险接住。完整论证见 `CarUpdatePrefs.silentUpdateEnabled` 的 KDoc。
        }

        if (model.developer.unlocked) {
            SectionHeader(title = "开发者")
            CarCard(modifier = Modifier.fillMaxWidth()) {
                CarSettingAction(
                    title = "开发者模式",
                    value = "日志采集与上传",
                    onClick = onOpenDeveloper,
                )
            }
        }

        SectionHeader(title = "平台")
        CarCard(modifier = Modifier.fillMaxWidth()) {
            CarSettingInfo(
                title = "平台适配",
                value = info?.platformName ?: "未安装",
            )
            CarRowDivider()
            CarSettingInfo(
                title = "系统",
                value = info?.osVersion ?: "未知",
            )
            CarRowDivider()
            CarSettingInfo(
                title = "设备型号",
                value = info?.deviceModel ?: "未知",
            )
            val firmware = info?.firmwareVersion
            if (firmware != null) {
                CarRowDivider()
                CarSettingInfo(
                    title = "固件版本",
                    value = firmware,
                )
            }
        }

        SectionHeader(title = "许可与来源")
        CarCard(modifier = Modifier.fillMaxWidth()) {
            CarSettingInfo(
                title = "上游项目",
                value = "shilapi/xcertplay",
            )
            CarRowDivider()
            CarSettingInfo(
                title = "许可",
                value = "GPL-3.0",
            )
        }

        Spacer(Modifier.size(dimens.spaceXl))
        CarButton(
            text = "退出应用",
            onClick = onExitApp,
            variant = CarButtonVariant.Danger,
        )
        Spacer(Modifier.size(dimens.spaceXxl))
    }
}

/**
 * 「检查更新」那一行右侧的值。
 *
 * 值列只放**结论**（"已是最新" / "0.0.11"），原因放在 `description` 里——
 * 这与设置页「本次将使用」的处理方式一致：结论要一眼看到，理由要能查到。
 *
 * 有版本名时**带上版本号**（`0.10.3 (113)`）：服务端是按 code 判的，
 * 用户截图报障时工程师不必再问一句"你的 code 是多少"。
 *
 * 穷尽式 `when` 不写 `else`：新增阶段时编译失败，强制显式决策。
 */
private fun CarUpdateState.valueLabel(): String = when (phase) {
    CarUpdatePhase.IDLE -> "点击检查"
    CarUpdatePhase.CHECKING -> "检查中…"
    CarUpdatePhase.UP_TO_DATE -> "已是最新"
    CarUpdatePhase.AVAILABLE,
    CarUpdatePhase.DOWNLOADING,
    // 静默路径：包已下好、在等空闲窗口。值列仍显示版本号 ——
    // 用户此刻最该知道的是"哪个版本"，"等多久"在 description 里。
    CarUpdatePhase.PENDING_INSTALL,
    CarUpdatePhase.READY,
    CarUpdatePhase.INSTALLING,
    -> latestVersionName.withCode(latestVersionCode).ifEmpty { "有新版本" }

    CarUpdatePhase.FAILED -> "检查失败"
}

/**
 * 「检查更新」那一行的补充说明。null = 不显示（行高保持紧凑）。
 *
 * 下载中这一行**跟着进度走**：用户可能已经离开对话框，
 * 这一行是他唯一还能看到"还在下、下到哪了"的地方。
 */
private fun CarUpdateState.hintText(): String? = when (phase) {
    // 还没查过：说明这一行的作用，顺便给出上次检查时间。
    CarUpdatePhase.IDLE -> lastCheckedAt
        .takeIf { it.isNotEmpty() }
        ?.let { "上次检查：$it" }
        ?: "从远程服务器获取最新版本"

    CarUpdatePhase.CHECKING -> null

    CarUpdatePhase.UP_TO_DATE -> lastCheckedAt
        .takeIf { it.isNotEmpty() }
        ?.let { "上次检查：$it" }

    CarUpdatePhase.AVAILABLE -> message.ifEmpty { null }
    // 下载中优先显示百分比（比 message 里那句"正在下载 X%"更短、更直接）。
    CarUpdatePhase.DOWNLOADING -> "已下载 ${(progress * 100).toInt().coerceIn(0, 100)}%"
    // 静默路径：包已下好、在等空闲窗口。这句是**用户唯一的可见线索**——
    // 全程零弹窗，他只会在这里看到"为什么还没装上"。
    CarUpdatePhase.PENDING_INSTALL -> message.ifEmpty { "已下载，将在未连接手机时自动安装" }
    CarUpdatePhase.READY -> "安装包已下载完成，点「检查更新」回到安装提示"
    CarUpdatePhase.INSTALLING -> message.ifEmpty { null }

    // 失败必须给出原因：用户刚点过按钮，不能只回一句"失败"。
    CarUpdatePhase.FAILED -> message.ifEmpty { "请检查车机网络后重试" }
}

/** 正在查或正在下：此时「检查更新」这一行不可点。 */
private fun CarUpdateState.isBusy(): Boolean = when (phase) {
    CarUpdatePhase.CHECKING,
    CarUpdatePhase.DOWNLOADING,
    CarUpdatePhase.INSTALLING,
    -> true

    CarUpdatePhase.IDLE,
    CarUpdatePhase.UP_TO_DATE,
    CarUpdatePhase.AVAILABLE,
    // 等空闲窗口不是"忙"：用户此时点「检查更新」应当照常可点
    // （他想手动装的话，重新查一次会回到弹窗路径）。
    CarUpdatePhase.PENDING_INSTALL,
    CarUpdatePhase.READY,
    CarUpdatePhase.FAILED,
    -> false
}

/** `0.10.3` → `0.10.3 (113)`。版本名或版本号缺任一者时原样返回。 */
private fun String.withCode(code: Long): String =
    if (isBlank() || code <= 0L) this else "$this ($code)"
