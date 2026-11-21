package com.dbtool.carplay.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.dbtool.carplay.api.session.CarReconnectState
import com.dbtool.carplay.api.session.CarSessionPhase
import com.dbtool.carplay.ui.CarStatusIndicator
import com.dbtool.carplay.ui.components.CarAlertDialog
import com.dbtool.carplay.ui.components.CarHotspotAdbSetupButton
import com.dbtool.carplay.ui.CarUiAction
import com.dbtool.carplay.ui.CarUiActions
import com.dbtool.carplay.ui.CarUiModel
import com.dbtool.carplay.ui.components.CarButton
import com.dbtool.carplay.ui.components.CarButtonSize
import com.dbtool.carplay.ui.components.CarButtonVariant
import com.dbtool.carplay.ui.components.CarIconButton
import com.dbtool.carplay.ui.components.CarPanel
import com.dbtool.carplay.ui.components.CarStatusView
import com.dbtool.carplay.ui.components.CarTopBar
import com.dbtool.carplay.ui.navigation.CarDestination
import com.dbtool.carplay.ui.theme.CarIcons
import com.dbtool.carplay.ui.theme.CarTheme

/**
 * 主页 = 连接状态页。
 *
 * 版式做过一次减法：**移除了页面底部的那一排按钮，以及左上角的标题文字**，
 * 只保留右上角的设置入口。理由：
 *
 * - 连接页的主行动是"自动连接"，不是"按一下再连"。页面上摆一个
 *   「启动 CarPlay」等于承认它不会自己启动——而副标题写的是
 *   "启动后自动连接 CarPlay"，两者互相打脸；
 * - 车机上的拇指可达区在屏幕下缘，但那正是空调/音量条常驻的位置，
 *   放主按钮容易被车机自己的 UI 压住；
 * - 标题文字与页面中央的大字（"未启动"）说的是同一件事，
 *   左上角再写一遍 "CarPlay / Android" 只是噪音。
 *
 * 移除按钮后必须补上**自动启动**（见下面的 `LaunchedEffect`），
 * 否则应用永远停在"未启动"——这是移除按钮时最容易漏掉的一步。
 *
 * ⚠️ **自动跳转画面页的逻辑不在这里**，已上移到
 * [com.dbtool.carplay.ui.navigation.CarPlayApp]。原因见那边的注释：
 * 放在本页的 `remember` 会随导航离开而被重置，导致"从画面页返回主页"
 * 被立刻弹回去、用户出不来。
 */
@Composable
fun HomeScreen(
    model: CarUiModel,
    actions: CarUiActions,
    onNavigate: (CarDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = CarTheme.dimens
    val session = model.session

    // 自动启动：页面底部不再有"启动"按钮，改为进入即启动。
    // 用 once 守卫而不是每次都调：失败后状态会回到可启动，
    // 没有守卫就会变成每次重组重试一次的死循环。
    //
    // ⚠️ 只在 `IDLE`（首次进入）自动启动。`STOPPED` 是**用户主动停止的结果**
    // （或熄火暂停后回到的页面），再自动起栈会变成"停不下来"：
    // 用户点停止 → 状态回 STOPPED → 页面重组又自动连回去。
    var autoStartIssued by remember { mutableStateOf(false) }
    LaunchedEffect(session.phase, session.availableActions) {
        if (!autoStartIssued &&
            session.phase == CarSessionPhase.IDLE &&
            CarUiAction.START in session.availableActions
        ) {
            autoStartIssued = true
            actions.start()
        }
    }

    // ACTIVE 的「停止连接」需要二次确认；其余过程态的停止保持一键（它们是
    // 用户自己走出来的唯一出口，不该再加一道确认）。
    var confirmStop by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        // 无标题栏：只有右上角的设置。
        // `showDivider = false`：本页顶栏没有标题、正文又是一块居中内容，
        // 分隔线两边并不存在"两个区域"，画出来只是一条横在留白上的线。
        CarTopBar(
            showDivider = false,
            actions = {
                CarIconButton(
                    icon = CarIcons.ImmersiveNavigation,
                    contentDescription = "进入沉浸导航111",
                    enabled = canEnterImmersiveNavigation(model),
                    onClick = {
                        // Explicit local window intent, not a protocol-mode change or instrument projection.
                        if (canEnterImmersiveNavigation(model)) {
                            actions.rememberImmersiveNavigationWindow(true)
                            onNavigate(CarDestination.CarPlay)
                        }
                    },
                )
                // DBPlay 本地导航卡独立于仪表副屏的 iPhone 导航卡片：由用户开关控制，
                // 且只在有新鲜路线信息时显示入口。
                if (model.settings.localNavigationCardEnabled && model.navigation != null) {
                    CarIconButton(
                        icon = CarIcons.Location,
                        contentDescription = "查看导航",
                        onClick = { onNavigate(CarDestination.Navigation) },
                    )
                }
                CarIconButton(
                    icon = CarIcons.Settings,
                    contentDescription = "设置",
                    onClick = { onNavigate(CarDestination.Settings()) },
                )
            },
        )

        // 主视觉区：**整页只有一条竖直中轴**。
        //
        // ⚠️ 这里曾经在顶栏下方挂过一条蓝牙链路状态条（胶囊 + "请先连接蓝牙：…"），
        // 位置换过三版（左对齐横条 → 主视觉下方 → 主视觉上方 → 内容区顶部居中），
        // **最后用户要求整条移除**，连同 `CarBluetoothMonitor` 一整套只读监视器一起删了。
        // 想恢复的话看 git 历史（0.0.27 及以前），别从零写。
        //
        // `BoxWithConstraints` + `verticalScroll` + `heightIn(min = maxHeight)` 这一组
        // 是**兜底**，不是版式：内容装得下时三者都不产生可见效果（Column 恰好等于
        // 视口高度、内容居中、没有可滚动区间）。只有视口矮到装不下时（手机横屏只有
        // 360dp 高就是这种情况），才把原本会被裁掉的部分变成可滚动——否则
        // "重试"按钮会被裁在屏幕外，用户连重试都点不到。车机屏幕不会触发。
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = dimens.screenPaddingH),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                // 只有**一套**主视觉：状态页。
                //
                // ⚠️ 曾经在 WORKING 阶段换成另一个"连接动画"组件（全屏纯白/纯黑底 +
                // 静止的 Apple 标志）。三个问题，任何一个都足以否掉它：
                //   1. 全屏色块比页底色更亮/更暗，边界是一道硬边，读起来像"页面没加载完"；
                //   2. 它是**静止**的——连接页最需要回答的"还在推进吗"反而没回答；
                //   3. 两套主视觉 = 两套版式、两套留白，改一处就得同步另一处。
                // 现在把"在推进"画进图标外围（见 CarStatusView 的 StatusHub），
                // 于是不需要第二个组件，页底也不再被盖住。
                // 连接失败后必须给一条**用户自己能走出来**的路。
                //
                // 这一屏除了右上角的设置入口没有任何可点元素，而设置入口解决不了链路问题——
                // 没有重试按钮时，用户唯一能做的是干等或者重启应用。
                //
                // 判据用 `availableActions` 而不是 `indicator == ERROR`：
                // 能不能重试是**状态机**的属性，不是界面的猜测。
                // 好处是"正在重连"时这个按钮会自己消失（那时状态机只提供 STOP），
                // 所以不需要额外写"防连点"逻辑。
                val retryable = CarUiAction.RECONNECT in session.availableActions

                // `STOPPED` 的出口：自动启动只在 `IDLE` 触发，用户主动停止后
                // 必须给一个「启动 CarPlay」按钮，否则页面回到"整页没有可点元素"。
                //
                // `IDLE && autoStartIssued` 覆盖**权限闸门拦下自动启动**的情况：
                // 此时 `actions.start()` 已经下发过，但状态仍停在 IDLE、`START`
                // 仍在集合里——不再给按钮用户就没法补授权后重试。
                val startable = CarUiAction.START in session.availableActions &&
                    (
                        session.phase == CarSessionPhase.STOPPED ||
                            (session.phase == CarSessionPhase.IDLE && autoStartIssued)
                        )

                // 失败页的**第二条出路**：去设置里改配置。
                //
                // 判据同样取状态机：`OPEN_SETTINGS` 在"失败"与"运行中"都可用，
                // 但这里只在**失败**时把它摆出来——运行中用户是在看画面，
                // 不需要被一个"打开设置"占住视线。
                val fixableInSettings =
                    retryable && CarUiAction.OPEN_SETTINGS in session.availableActions

                // ------------------------------------------------------------
                // 🔴 PATCH-0105（2026-10-12）：**进行中必须留一条出口**。
                //
                // 现场口径：「设置发生改动后必须点一次重连，其他按钮才能用」。
                // 其中一条可复现的路径就是这里：状态机在**过程态**（准备 MFi /
                // 等待设备 / 连接中 / 正在重连）只提供 `STOP`（见
                // `CarUiStateMapper.project` 的 `else ->`），而本页此前只渲染
                // `RECONNECT`（重试）与 `OPEN_SETTINGS`（打开设置）⇒
                // **过程态的主页上没有任何可点按钮**，只剩一个转圈的枢纽：
                // 用户要出来只能等、重启 App，或者（如现场）去点一次重连。
                //
                // 这里把状态机**本来就已经给出**的 `STOP` 摆出来（次要按钮，
                // 不抢主操作的位置），于是"没有出口"这个状态在结构上不可能再出现。
                //
                // ⚠️ 过程态一键停止；`ACTIVE` 也摆，但**必须二次确认**——用户
                // 正在投屏时误触一下就会断开车机画面，代价比过程态大得多。
                // `IDLE/STOPPED` 不摆（自动启动 / 「启动 CarPlay」接手）。
                val stoppable =
                    (
                        session.indicator == CarStatusIndicator.WORKING ||
                            session.phase == CarSessionPhase.ACTIVE
                        ) && CarUiAction.STOP in session.availableActions

                // 「第二条出路」的三种可能，**互斥**：
                // 失败页 → 打开设置（`fixableInSettings` 要求 `retryable`，只在 FAILED 成立）；
                // 进行中 / 运行中 → 停止连接；
                // 其余 → 没有第二条（`IDLE/STOPPED` 有自动启动或「启动 CarPlay」接手）。
                val stoppingActiveSession = stoppable && session.phase == CarSessionPhase.ACTIVE
                val secondaryActionText = when {
                    fixableInSettings -> "打开设置"
                    stoppable -> "停止连接"
                    else -> null
                }
                val secondaryActionIcon = when {
                    fixableInSettings -> CarIcons.Settings
                    stoppable -> CarIcons.Power
                    else -> null
                }
                val onSecondaryAction: (() -> Unit)? = when {
                    fixableInSettings -> ({ onNavigate(CarDestination.Settings()) })
                    stoppingActiveSession -> ({ confirmStop = true })
                    stoppable -> actions::stop
                    else -> null
                }

                // 主按钮：`STOPPED` 的「启动 CarPlay」/ 失败页的「重试」。
                // 自动重连耗尽时改词为「重新连接」——此时按下会**手动**重建，
                // 不再走已经停手的自动退避。
                val primaryActionText = when {
                    startable -> "启动 CarPlay"
                    retryable -> if (session.reconnect.state == CarReconnectState.EXHAUSTED) {
                        "重新连接"
                    } else {
                        "重试"
                    }
                    else -> null
                }
                val primaryActionIcon = when {
                    startable -> CarIcons.Play
                    retryable -> CarIcons.Refresh
                    else -> null
                }
                val onPrimaryAction: (() -> Unit)? = when {
                    startable -> actions::start
                    retryable -> actions::reconnect
                    else -> null
                }

                // 失败页的**第三条路**：去本应用里建立热点 + ADB。
                //
                // 判据取状态机（`OPEN_DEVELOPER_SETTINGS` 只在 ADB 那一族失败时入集，
                // 见 `CarUiStateMapper.project` 的 `needsDeveloperSettings`）。
                // 界面**不做**任何字面判断——"什么算 ADB 失败"是数据层的事。
                val needsAdb = CarUiAction.OPEN_DEVELOPER_SETTINGS in session.availableActions

                // 2026-10-23 D4：首页双栏化。
                // 主视觉（图标/状态/按钮）与全部业务逻辑一行未动，
                // 只是把 `session.facts`（链路、设备、认证）从主视觉里抽到右栏，
                // 左侧留白更干净、右侧信息更聚合。窄视口自动落回单栏。
                val statusBlock: @Composable (Boolean) -> Unit = { withFacts ->
                    CarStatusView(
                        title = session.title,
                    subtitle = session.subtitle,
                    indicator = session.indicator,
                    icon = statusIconOf(session.indicator),
                    // Apple 标志是**品牌标志**，保持单色：浅色底黑标、深色底白标
                    // （`textPrimary` 在浅色下是近黑、深色下是近白）。
                    // 品牌标志不跟着状态变色——状态由圆底与进度环承担。
                    // 其余状态图标（勾、叉、电源）是 UI 图标，跟随状态语义色更醒目。
                    iconTint = if (session.indicator == CarStatusIndicator.WORKING) {
                        CarTheme.colors.textPrimary
                    } else {
                        null
                    },
                        facts = if (withFacts) session.facts else emptyList(),
                    busy = session.indicator == CarStatusIndicator.WORKING,
                    actionText = primaryActionText,
                    actionIcon = primaryActionIcon,
                    onAction = onPrimaryAction,
                    secondaryActionText = secondaryActionText,
                    secondaryActionIcon = secondaryActionIcon,
                    onSecondaryAction = onSecondaryAction,
                    modifier = Modifier.fillMaxWidth(),
                    // ADB 那一族失败时，**就地**摆「立即建立热点 + ADB（永久）」。
                    //
                    // ## 2026-10-08（斌哥口径）——替换掉原来的「去开 ADB 调试」
                    //
                    // 车主原话：「**当出现「ADB 无法正常连接」的错误页面时，直接显示该
                    // 二级页面中的「立即建立热点 + ADB 永久」按钮，点击后执行相同的操作，
                    // 不需要另外新增一套功能。**」
                    //
                    // 旧做法是 `tertiaryActionText = "去开 ADB 调试"` → `openDeveloperSettings()`
                    // 导航到「设置 → 连接 → 热点服务」，用户到了还得自己找那颗按钮。
                    // 现在**就地可点**，少一次跳转。
                    //
                    // ⚠️ **复用同一份实现**：按钮就是
                    // [com.dbtool.carplay.ui.components.CarHotspotAdbSetupButton]，
                    // 与设置页那颗**完全是同一个 Composable**，都只调
                    // `CarUiActions.onInitializeAdb` ⇒ 设置页与失败页复用同一初始化动作。
                    //
                    // ⚠️ 只在 ADB 那一族失败时出现（`needsAdb`）：其余失败去建立热点/开 ADB
                    // 也解决不了，多一个按钮只会让人乱点。
                    //
                    // ## 2026-10-08 版式修正（斌哥：按钮"通栏铺满屏幕，比例不协调"）
                    //
                    // 这个按钮**必须交给 `CarStatusView` 的 `extraContent` 渲染**，
                    // 不能再像早先那样平铺在 `CarStatusView` 之后——直接平铺时
                    // `CarHotspotAdbSetupButton` 内部是 `fillMaxWidth()`，
                    // 会在这条 Column 里吃满整宽，与上面那排"贴合文案宽度"的
                    // 「重试 / 打开设置」形成刺眼反差。`extraContent` 槽位把它
                    // 统一收窄到 `contentMaxWidth * 0.22f`，版式才协调。
                    //
                    // ⚠️ 槽位里是**两层 Box**（外层铺满 + 里层 `widthIn`）——
                    // 收窄不能与 `fillMaxWidth()` 写在同一节点上，否则失效。见
                    // `CarStatusView` 的 `extraContent` 注释 / `REF-pitfalls.md` §64。
                    extraContent = null,
                    )
                }

                // 2026-10-24：移除右侧独立「连接详情」双栏卡片。
                // 原 D4 双栏把 `session.facts` 抽到右栏，视觉上多出一块空面板，
                // 连接失败页尤其显得右半边空荡。改为只走单栏：
                // 主视觉居中，`session.facts` 回归主视觉下方（D4 之前的行为）。
                statusBlock(true)

                if (session.isActive) {
                    Spacer(Modifier.size(dimens.spaceXl))
                    CarButton(
                        text = "返回 CarPlay",
                        onClick = { onNavigate(CarDestination.CarPlay) },
                        variant = CarButtonVariant.Primary,
                        size = CarButtonSize.Large,
                        icon = CarIcons.Play,
                    )
                }
            }
        }

        // 权限提示卡保留：它不是"按钮"，而是权限被拒后的恢复入口。
        // 去掉它会把用户堵死在"无线 CarPlay 需要定位权限但没地方给"的状态里。
        if (model.notice != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = dimens.screenPaddingH,
                        end = dimens.screenPaddingH,
                        bottom = dimens.spaceL,
                    ),
            ) {
                NoticeCard(text = model.notice, onAction = actions::requestPermissions)
            }
        }

        // `ACTIVE` 的「停止连接」二次确认：确认后才真正下发 stop，
        // 取消则什么也不做（车机颠簸环境下防误触）。
        if (confirmStop) {
            CarAlertDialog(
                title = "停止连接",
                message = "停止后将断开与 iPhone 的 CarPlay 连接。",
                confirmText = "停止连接",
                onConfirm = {
                    confirmStop = false
                    actions.stop()
                },
                onDismiss = { confirmStop = false },
                destructive = true,
            )
        }
    }
}

internal fun canEnterImmersiveNavigation(model: CarUiModel): Boolean =
    model.session.showVideo && model.immersiveNavigationActive && model.settings.immersiveNavigationEnabled

/**
 * 状态语义 → 主视觉图标。
 *
 * 图标与 [com.dbtool.carplay.ui.components.CarStatusView] 的圆底色是**成对**的：
 * 两者都由同一个 `indicator` 推出，所以不会出现"绿色底配叉号"这类自相矛盾的组合。
 *
 * WORKING 用 **Apple 标志**而不是通用的插头图标：这一屏在无线连接时会出现十来秒，
 * 用户此刻唯一需要确认的事就是"对面那台 iPhone 有没有被找到"。
 * 直接摆出对端的标志，比一个抽象插头少一次解码。
 * （Apple 自己"Works with Apple CarPlay"的用法就是这样：连接态直接用品牌标志。）
 */
private fun statusIconOf(indicator: CarStatusIndicator): ImageVector = when (indicator) {
    CarStatusIndicator.IDLE -> CarIcons.Power
    CarStatusIndicator.WORKING -> CarIcons.AppleLogo
    CarStatusIndicator.READY -> CarIcons.Success
    CarStatusIndicator.ERROR -> CarIcons.Error
}

/**
 * 提示卡（权限缺失等）。
 *
 * 用**警示色的浅底块**而不是描边或整块红底：
 * - 描边太弱，车机强光下看不见；
 * - 整块红底会读成"出大事了"，而缺权限只是"有一件事需要你处理"。
 * 浅底 + 深色文字是 Ant / TDesign 对 warning 的统一处理。
 */
@Composable
fun NoticeCard(
    text: String,
    modifier: Modifier = Modifier,
    onAction: (() -> Unit)? = null,
    actionText: String = "去授权",
) {
    val dimens = CarTheme.dimens
    val colors = CarTheme.colors
    CarPanel(
        modifier = modifier.fillMaxWidth(),
        color = colors.warningContainer,
        shape = CarTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(PaddingValues(dimens.spaceM)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimens.spaceM),
        ) {
            Icon(
                imageVector = CarIcons.Warning,
                contentDescription = null,
                modifier = Modifier.size(dimens.iconM),
                tint = colors.warning,
            )
            Text(
                text = text,
                style = CarTheme.type.bodyMedium,
                color = colors.onWarningContainer,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = dimens.spaceS),
            )
            if (onAction != null) {
                CarButton(
                    text = actionText,
                    onClick = onAction,
                    variant = CarButtonVariant.Secondary,
                )
            }
        }
    }
}
