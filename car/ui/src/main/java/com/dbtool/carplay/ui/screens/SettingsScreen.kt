package com.dbtool.carplay.ui.screens

import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import com.dbtool.carplay.api.display.theme.ClusterAppearanceMode
import com.dbtool.carplay.api.airplay.CarAirPlayIconStatus
import com.dbtool.carplay.api.display.CarCustomDisplay
import com.dbtool.carplay.api.display.ClusterLayerSwitchMapping
import com.dbtool.carplay.api.display.ClusterVideoTransform
import com.dbtool.carplay.api.display.HudCropGeometry
import com.dbtool.carplay.api.display.HudCropPreset
import com.dbtool.carplay.api.display.HudCropSpec
import com.dbtool.carplay.api.display.HudScaleMode
import com.dbtool.carplay.api.display.approximatelyEquals
import com.dbtool.carplay.api.display.centerAt
import com.dbtool.carplay.api.display.centerX
import com.dbtool.carplay.api.display.centerY
import com.dbtool.carplay.api.display.scaledToWidth
import com.dbtool.carplay.api.display.widthFraction
import com.dbtool.carplay.api.hotspot.CarAdbHotspotDefaults
import com.dbtool.carplay.api.hotspot.CarAdbChannelStatus
import com.dbtool.carplay.api.hotspot.CarAdbInitializationState
import com.dbtool.carplay.api.hotspot.CarHotspotEnabledState
import com.dbtool.carplay.api.hotspot.CarHotspotNameRules
import com.dbtool.carplay.api.hotspot.CarManualHotspotPrecheck
import com.dbtool.carplay.api.navigation.CarHudStatus
import com.dbtool.carplay.api.settings.CarBluetoothHandoffPolicy
import com.dbtool.carplay.api.settings.CarDrivingSide
import com.dbtool.carplay.api.settings.CarHotspotBand
import com.dbtool.carplay.api.settings.CarHotspotMode
import com.dbtool.carplay.api.settings.reusesCarNativeHotspot
import com.dbtool.carplay.api.settings.CarHotspotSecurity
import com.dbtool.carplay.api.settings.CarMfiTarget
import com.dbtool.carplay.api.settings.CarPhysicalSizeBasis
import com.dbtool.carplay.api.settings.CarSessionSettings
import com.dbtool.carplay.api.settings.CarSettingsSafeArea
import com.dbtool.carplay.api.settings.CarTransportMode
import com.dbtool.carplay.api.settings.hasDefaultIdentity
import com.dbtool.carplay.api.settings.withDefaultIdentity
import com.dbtool.carplay.api.system.CarUsbHostSupport
import com.dbtool.carplay.ui.CarStatusIndicator
import com.dbtool.carplay.ui.CarUiActions
import com.dbtool.carplay.ui.CarUiModel
import com.dbtool.carplay.ui.displayLabel
import com.dbtool.carplay.ui.components.CarAlertDialog
import com.dbtool.carplay.ui.components.CarAdbHotspotStepsCard
import com.dbtool.carplay.ui.components.CarAdbHotspotSummaryRow
import com.dbtool.carplay.ui.components.CarButton
import com.dbtool.carplay.ui.components.CarButtonSize
import com.dbtool.carplay.ui.components.CarButtonVariant
import com.dbtool.carplay.ui.components.CarCard
import com.dbtool.carplay.ui.components.CarChoiceChip
import com.dbtool.carplay.ui.components.CarChoiceDialog
import com.dbtool.carplay.ui.components.CarDeviceCard
import com.dbtool.carplay.ui.components.CarNumberField
import com.dbtool.carplay.ui.components.CarRowDivider
import com.dbtool.carplay.ui.components.CarScreen
import com.dbtool.carplay.ui.components.CarSettingAction
import com.dbtool.carplay.ui.components.CarHotspotAdbSetupButton
import com.dbtool.carplay.ui.components.CarSettingChoice
import com.dbtool.carplay.ui.components.CarSettingInfo
import com.dbtool.carplay.ui.components.CarSettingNumberGroup
import com.dbtool.carplay.ui.components.CarSettingRow
import com.dbtool.carplay.ui.components.CarSettingSlider
import com.dbtool.carplay.ui.components.CarSettingSwitch
import com.dbtool.carplay.ui.components.CarSettingText
import com.dbtool.carplay.ui.components.SafeAreaFrameEditor
import com.dbtool.carplay.ui.CAR_CLUSTER_ALIGNMENT_LABELS
import com.dbtool.carplay.ui.carClusterAlignmentLabel
import com.dbtool.carplay.ui.components.SectionHeader
import com.dbtool.carplay.ui.navigation.CarSettingsSection
import com.dbtool.carplay.ui.theme.CarColors
import com.dbtool.carplay.ui.theme.CarIcons
import com.dbtool.carplay.ui.theme.CarTheme
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 设置页。
 *
 * ## 布局（2026-10-20 双栏改造）
 *
 * - 可用内容宽 ≥ 900dp：**左栏常驻分区导航 + 右栏当前分区内容**（主从双栏）。
 *   左栏只显示主标题，右栏保留原有 15 个分组的单列顺序；
 * - 可用内容宽 < 900dp：回退「索引页 + 分区详情页」两段式，行为与改造前一致；
 * - 「仪表主题」两种宽度下都走全宽独立页面（它自带 1000dp 双栏预览）。
 *
 * 双栏容器见 `SettingsTwoPane.kt`；分区清单与图标只定义一份，
 * 由左栏与窄屏索引页共用。
 *
 * ## 为什么不做"内容左右对切"
 *
 * 上游把 26 个分组塞进一条可滚动列表，在 1024x600 的车机上要滑七八屏才能
 * 找到"帧率"，驾驶中根本不可用。项目也试过把同一分区的控件拆成左右两列，
 * 真车反馈「列高不等、阅读顺序断裂、同族被拆开」。因此双栏只改外层容器，
 * 不拆分区内部的决策流。
 *
 * 首页的"音频"快捷入口通过 [CarDestination.Settings.focus] 直接打开对应分区，
 * 不复制任何设置项。
 */
@Composable
fun SettingsScreen(
    model: CarUiModel,
    actions: CarUiActions,
    onBack: () -> Unit,
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier,
    focus: CarSettingsSection? = null,
) {
    // 草稿态 —— **自动保存**（PATCH-0103，2026-10-12）。
    //
    // 早期版本要求点保存按钮才提交，理由是"用户随便点两下看看也会静默改掉配置"。
    // 但那个理由站不住：手动保存的真正代价是**用户以为改完了、其实没生效**，
    // 在车机上尤其糟（他改完就退出去了，下次进来发现又变回去了）。
    // 车主口径是"改完就该是改完了"，于是改成自动保存。
    //
    // 现在的分工：
    // - 每次改动只落在本地草稿 [draft]；静置 [SETTINGS_AUTOSAVE_DEBOUNCE_MILLIS]
    //   后**自动提交**（`reconnect = false`，不掐断正在投的屏）；
    // - 提交结果在底部**常驻**状态行显示（保存中 / 已保存 HH:mm:ss / 保存失败）；
    // - 那些**必须重连才生效**的改动（画布尺寸、音频通道号、热点身份…）由
    //   `model.settingsPendingReconnect` 标出，顶栏右侧给出「重连生效」按钮 ——
    //   重连会掐断当前投屏，所以**只由用户点**（车主口径：自动保存，重连仍手点）。
    //
    // 草稿与"已保存值"的对齐（PATCH-0105，2026-10-12 改）：
    //
    // ⚠️ 旧写法是 `remember(model.settings) { mutableStateOf(model.settings) }` ——
    // **每次自动保存成功都会把草稿状态整个丢掉重建**（`model.settings` 一变，`remember`
    // 的 key 就变）：本页随即整体重组一次、正在编辑的输入框被重新要焦点、输入法跟着
    // `startInput` 抖动（车机诊断包里就是成片的 `InputMethodManager:
    // onViewDetachedFromWindow`）。而 PATCH-0105 修的恰恰是"输入法事务在途时窗口标志
    // 被重写 ⇒ insets 事务卡住 ⇒ 窗口收不到触摸"，所以这里必须**少去打扰正在编辑的那一屏**。
    //
    // 现在的规则只有一条：**已保存值被"本页之外"改了才让草稿跟随**（例如「恢复默认设置」
    // 或别处改了设置）。本页自己的自动保存不会落进这个分支 —— 提交的就是草稿本身，
    // 保存值 == 草稿，那次赋值是**等值写入**，Compose 不会因此重组。
    var draft by remember { mutableStateOf(model.settings) }
    var lastSeenSaved by remember { mutableStateOf(model.settings) }
    if (model.settings != lastSeenSaved) {
        lastSeenSaved = model.settings
        draft = model.settings
    }
    var saveState by remember { mutableStateOf<SettingsSaveState>(SettingsSaveState.Idle) }
    var section by remember(focus) { mutableStateOf(focus) }
    // 仪表主题是**全宽独立页面**，不进入双栏右栏。进入前记下当前分区，
    // 返回时恢复它（双栏下不再是固定回索引页 —— 左栏一直显示着所有分区）。
    var sectionBeforeTheme by remember { mutableStateOf<CarSettingsSection?>(null) }

    // 进设置页时重算一次热点简报（2026-10-08 加）。
    //
    // 为什么必须在这里：`hotspotBriefing` 只在**保存设置 / 启动 / 重连**时算一次，
    // 而最常见的操作顺序是"在设置页看到热点关着 → 去车机设置里打开 → 回来再看一眼"。
    // 不刷新的话，用户看到的永远是进这一页**之前**那一刻的旧读数，于是这行状态
    // 反而比没有更糟（它看起来是实时的）。
    //
    // 成本可忽略：后台线程一次反射 + 一次粘性广播，不碰主线程（见
    // `CarAppController.refreshHotspotHint` 的 `briefingExecutor`）。
    LaunchedEffect(Unit) { actions.refreshHotspotBriefing() }

    fun update(next: CarSessionSettings) {
        draft = next
    }

    // 自动保存（PATCH-0103）：草稿与已保存值不一致时，静置一小段时间再提交。
    //
    // 为什么要防抖而不是"改一下就写一下"：数字框与滑条是**逐帧**回调的
    // （拖一次能触发几十次 update），而每次提交都会走
    // `CarAppController.applySettings` → `refreshModel()`，里面要重算停靠栏、
    // 当前播放、日志快照、显示候选… 在车机主线程上逐帧跑会肉眼可见地掉帧。
    // 静置窗口把"手停了才写盘"这件事变成默认行为。
    //
    // ⚠️ `model.settings` 也做 key：提交成功会让它变，effect 随之重启并立刻
    // 在下面那行短路返回 —— 否则会在保存完自己再触发一轮无谓的提交。
    LaunchedEffect(draft, model.settings) {
        if (draft == model.settings) return@LaunchedEffect
        saveState = SettingsSaveState.Saving
        delay(SETTINGS_AUTOSAVE_DEBOUNCE_MILLIS)
        saveState = commitSettings(actions, draft)
    }

    // 离开设置页时把还没落盘的草稿补交一次。
    //
    // ⚠️ 没有它就有**数据丢失**：在防抖窗口内直接返回，上面那个 LaunchedEffect
    // 会被取消，而新的一次不会再启动（页面已经销毁）—— 改动凭空消失，
    // 但用户以为"自动保存"已经替他存好了。
    //
    // ⚠️ 必须用 rememberUpdatedState 读**最新**草稿：`DisposableEffect(Unit)` 只
    // 注册一次，直接捕获 `draft` 会永远停在首次组合时那个值。
    val latestDraft by rememberUpdatedState(draft)
    val latestSaved by rememberUpdatedState(model.settings)
    DisposableEffect(Unit) {
        onDispose {
            val pending = latestDraft
            if (pending != latestSaved) commitSettings(actions, pending)
        }
    }

    val saveFooter: @Composable () -> Unit = {
        SettingsSaveStatusBar(
            saveState = saveState,
            pendingReconnect = model.settingsPendingReconnect,
        )
    }
    // 「重连生效」跟顶栏「设置」同一行，靠右。底部状态行只留保存提示。
    val reconnectAction: @Composable RowScope.() -> Unit = {
        if (model.settingsPendingReconnect) {
            CarButton(
                text = "重连生效",
                onClick = { actions.applySettings(draft, reconnect = true) },
                icon = CarIcons.Refresh,
            )
        }
    }

    val current = section

    // 分区入口统一走这里：仪表主题需要先判断模块授权，其余直接切分区。
    // 双栏与窄屏索引页共用同一套判定，避免两处各写一份授权分支。
    val openSection: (CarSettingsSection) -> Unit = { target ->
        section = target
    }

    // 双栏判定放在页面最外层：可用内容宽 = min(窗口宽, 内容上限) − 两侧页边距。
    // 900dp 是定稿断点（不随 scale 缩放），见 CarDimensions.settingsDualPaneMinWidth。
    // 110 physical pixels, not 110dp. Paint BEFORE padding so the reserved strip
    // uses the same day/night background instead of revealing black underneath.
    val settingsBottomInset = with(androidx.compose.ui.platform.LocalDensity.current) { 110.toDp() }
    BoxWithConstraints(modifier = modifier.fillMaxSize().background(CarTheme.colors.background).padding(bottom = settingsBottomInset)) {
        val dimens = CarTheme.dimens
        val availableContentWidth = SettingsLayoutPolicy.availableContentWidth(
            screenWidth = maxWidth,
            contentMaxWidth = dimens.contentMaxWidth,
            screenPaddingH = dimens.screenPaddingH,
        )
        val dualPane = SettingsLayoutPolicy.isDualPane(
            availableWidth = availableContentWidth,
            minWidth = dimens.settingsDualPaneMinWidth,
        )

        when {
            // 主题页自带 `CarScreen`：它要铺满整宽（双栏），还要在顶栏右侧显示"已选主题"。
            // 无论宽窄都走这个分支，不嵌入右栏（否则低于 ClusterThemeScreen 的 1000dp 阈值）。
            dualPane -> {
                // 双栏没有「索引页」这一层：section 为 null 时右栏默认落在日常第一项（连接）。
                // 刻意**不把默认值写回 section**：窄屏回退时 null 仍表示"该显示索引页"。
                val selected = current ?: SETTINGS_NAV_GROUPS.first().sections.first()
                SettingsTwoPane(
                    selected = selected,
                    summary = sectionSummary(
                        section = selected,
                        draft = draft,
                        model = model,
                        clusterThemeSummary = "",
                    ),
                    onSelectSection = openSection,
                    onOpenAbout = onOpenAbout,
                    onBack = onBack,
                    footer = saveFooter,
                    actions = reconnectAction,
                ) {
                    SettingsSectionContent(selected, draft, ::update, model, actions)
                }
            }

            current == null -> SettingsIndex(
                model = model,
                draft = draft,
                onOpenSection = openSection,
                onOpenAbout = onOpenAbout,
                onBack = onBack,
                footer = saveFooter,
                actions = reconnectAction,
                clusterThemeSummary = "",
            )

            else -> CarScreen(
                title = current.title,
                onBack = { section = null },
                footer = saveFooter,
                actions = reconnectAction,
            ) {
                SettingsSectionContent(current, draft, ::update, model, actions)
            }
        }
    }

    // 主题兑换弹窗：主题页内部会自己渲染（ClusterThemeScreen 已处理），其余分支在这里统一渲染。
    // 双栏下用户可能停在任意分区时从导航栏点「仪表主题」，所以不能只在索引分支里挂它。

}

/**
 * 设置页自动保存的状态（PATCH-0103）。
 *
 * ⚠️ 它是**界面状态**，不是持久化状态：进程重启后回到 [Idle]。
 * "是否还有改动在等重连"是另一件事，来自 `CarUiModel.settingsPendingReconnect`
 * （由 controller 用"已灌给会话的基线"比对得出），两者刻意分开 ——
 * 混成一个字段会让"保存成功"和"已经生效"被当成同一件事。
 */
private sealed interface SettingsSaveState {
    /** 还没改过任何东西。 */
    data object Idle : SettingsSaveState

    /** 草稿已变、正在防抖窗口内（或即将写盘）。 */
    data object Saving : SettingsSaveState

    /** 已写盘，[at] 是完成时刻（`HH:mm:ss`）。 */
    data class Saved(val at: String) : SettingsSaveState

    /** 写盘抛异常，[reason] 面向用户。 */
    data class Failed(val reason: String) : SettingsSaveState
}

/**
 * 防抖窗口：手停多久才写盘。
 *
 * 500ms 是权衡出来的 —— 再短（如 150ms）会把滑条拖动过程中的中间值也写进去，
 * 一次拖动就是十几次 `refreshModel()`；再长（如 1500ms）则用户改完立刻返回时
 * 多半要靠 [DisposableEffect] 的兜底补交，白白多走一条路径。
 */
private const val SETTINGS_AUTOSAVE_DEBOUNCE_MILLIS = 500L

/** 「全屏模式」详情页事件历史最多展示条数（状态机保留 30 条）。 */
private const val FULL_MAP_EVENT_HISTORY_LIMIT = 20

/** 提交草稿并把结果折算成界面状态。写盘失败**不能**让界面炸掉（自动保存没有"重试按钮"）。 */
private fun commitSettings(
    actions: CarUiActions,
    settings: CarSessionSettings,
): SettingsSaveState = runCatching {
    actions.applySettings(settings, reconnect = false)
}.fold(
    onSuccess = { SettingsSaveState.Saved(clockLabel()) },
    onFailure = { error ->
        val reason = error.message?.takeIf { it.isNotBlank() } ?: error::class.simpleName.orEmpty()
        SettingsSaveState.Failed(reason.ifBlank { "未知原因" })
    },
)

/** `HH:mm:ss` —— 给"已保存"那行用。秒级精度是为了让连续保存看得出确实又存了一次。 */
private fun clockLabel(): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

// ---------------------------------------------------------------------- 索引页

@Composable
private fun SettingsIndex(
    model: CarUiModel,
    draft: CarSessionSettings,
    clusterThemeSummary: String,
    onOpenSection: (CarSettingsSection) -> Unit,
    onOpenAbout: () -> Unit,
    onBack: () -> Unit,
    footer: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val dimens = CarTheme.dimens
    CarScreen(
        title = "设置",
        onBack = onBack,
        footer = footer,
        actions = actions,
        modifier = modifier,
    ) {
        // 索引页（窄屏回退路径）= 10 个分区 + 「关于」跳转条目。
        //
        // 行序来自 SETTINGS_INDEX_ORDER（与改造前逐行一致），图标来自
        // settingsSectionIcon —— 这两份数据与双栏左栏共用，避免标题/图标漂移。
        CarCard(modifier = Modifier.fillMaxWidth()) {
            SETTINGS_INDEX_ORDER.forEachIndexed { index, section ->
                if (index > 0) CarRowDivider()
                SectionEntry(
                    title = section.title,
                    summary = sectionSummary(section, draft, model, clusterThemeSummary),
                    icon = settingsSectionIcon(section),
                    onClick = { onOpenSection(section) },
                )
            }
            CarRowDivider()
            SectionEntry(
                title = "关于",
                summary = model.appVersionName.ifBlank { "版本与平台信息" },
                icon = CarIcons.Info,
                onClick = onOpenAbout,
            )
        }

        Spacer(Modifier.size(dimens.spaceXl))

        // 保存按钮与放弃按钮已随自动保存撤掉。需要用户点的「重连生效」
        // 在顶栏右侧，与「设置」同一行。
        Spacer(Modifier.size(dimens.spaceXxl))
    }
}

@Composable
private fun SectionEntry(
    title: String,
    summary: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    CarSettingAction(
        title = title,
        description = summary,
        icon = icon,
        value = null,
        onClick = onClick,
    )
}

/**
 * 当前分区的内容分发。
 *
 * 2026-10-20 双栏改造时从原详情页的 `when (current)` **原样抽出**：调用顺序、
 * 参数、各 `*Section` 内部逻辑都没动。双栏右栏与窄屏详情页调用的是同一个函数，
 * 因此两条路径不可能出现内容差异。
 *
 * 顺序即 [CarSettingsSection] 枚举顺序；主题页在调用方单独走全宽分支，
 * 这里只为穷尽枚举返回 `Unit`。
 */
@Composable
private fun SettingsSectionContent(
    current: CarSettingsSection,
    draft: CarSessionSettings,
    update: (CarSessionSettings) -> Unit,
    model: CarUiModel,
    actions: CarUiActions,
) {
    when (current) {
        CarSettingsSection.CONNECTION -> ConnectionSection(draft, update, model, actions)
        CarSettingsSection.DISPLAY -> DisplaySection(draft, update, model)
        CarSettingsSection.CLUSTER -> ClusterSection(draft, update, model, actions)
        CarSettingsSection.IMMERSIVE_NAVIGATION -> ImmersiveNavigationSettings(draft, update)
        CarSettingsSection.HUD -> HudSection(draft, update, model)
        CarSettingsSection.VEHICLE -> VehicleSection(draft, update, model, actions)
        CarSettingsSection.IDENTITY -> IdentitySection(draft, update, model, actions)
        CarSettingsSection.DIAGNOSTICS -> DiagnosticsSection(
            draft = draft,
            model = model,
            actions = actions,
            update = update,
            onResetSafeArea = actions::resetSafeArea,
            onClearPairings = actions::clearPairings,
        )
        // 主题页在调用方单独分支（自带 CarScreen），这里只为穷尽枚举。
    }
}

/**
 * 分区摘要（左栏不渲染，只用于**右栏页头**与**窄屏索引行**）。
 *
 * 与 [SETTINGS_INDEX_ORDER] 一样，摘要文案仍由本文件底部现有的 `summaryXxx`
 * 纯函数提供；这里只做 `section → summary` 的映射，不新增任何文案。
 */
private fun sectionSummary(
    section: CarSettingsSection,
    draft: CarSessionSettings,
    model: CarUiModel,
    clusterThemeSummary: String,
): String = when (section) {
    CarSettingsSection.CONNECTION -> summaryConnection(draft)
    CarSettingsSection.DISPLAY -> summaryDisplay(draft)
    CarSettingsSection.CLUSTER -> summaryCluster(draft)
    CarSettingsSection.IMMERSIVE_NAVIGATION -> if (draft.immersiveNavigationEnabled) "已开启 · 主屏111 · 与仪表投屏互斥" else "已关闭 · 主屏110"
    CarSettingsSection.HUD -> summaryHud(draft, model)
    CarSettingsSection.VEHICLE -> summaryVehicle(draft)
    CarSettingsSection.IDENTITY -> summaryIdentity(draft)
    CarSettingsSection.DIAGNOSTICS -> summaryDiagnostics(draft)
}

// ---------------------------------------------------------------- 全屏模式详情页

/**
 * 「全屏模式」详情页（阶段 3，2026-10-18）。
 *
 * 只做展示与开关，不改变接管/还原的判定：状态来自
 * `CarUiModel.clusterFullMap`，开关走 [CarUiActions.setClusterModeGuardEnabled]。
 */
@Composable
private fun ClusterFullMapSection(model: CarUiModel, actions: CarUiActions) {
    val ui = model.clusterFullMap
    val status = clusterFullMapDetail(ui)
    // 时间基准取一次「现在」：模型刷新（状态机推进 → refreshModel）会重组并重算相对时间。
    val nowMs = SystemClock.elapsedRealtime()
    val history = ui.events
        .mapNotNull { event ->
            clusterFullMapEventText(event)?.let { text ->
                text to clusterFullMapEventAgo(event, nowMs)
            }
        }
        .takeLast(FULL_MAP_EVENT_HISTORY_LIMIT)
        .reversed()

    SectionHeader(title = "开关")
    CarCard(modifier = Modifier.fillMaxWidth()) {
        CarSettingSwitch(
            title = "全屏模式守护",
            checked = ui.guardEnabled,
            description = if (ui.guardEnabled) {
                "接管后仪表整屏给 CarPlay，退出时自动还原"
            } else {
                "按旧方式投屏，不主动切换车机模式"
            },
            onCheckedChange = actions::setClusterModeGuardEnabled,
        )
    }

    Spacer(Modifier.size(CarTheme.dimens.spaceXl))

    SectionHeader(title = "当前状态")
    CarCard(modifier = Modifier.fillMaxWidth()) {
        CarSettingInfo(title = "全屏模式", value = status.first)
        if (status.second != null) {
            Text(
                text = status.second.orEmpty(),
                style = CarTheme.type.bodySmall,
                color = CarTheme.colors.textTertiary,
                modifier = Modifier.padding(
                    start = CarTheme.dimens.spaceM,
                    end = CarTheme.dimens.spaceM,
                    bottom = CarTheme.dimens.spaceS,
                ),
            )
        }
    }

    Spacer(Modifier.size(CarTheme.dimens.spaceXl))

    SectionHeader(title = "事件历史")
    CarCard(modifier = Modifier.fillMaxWidth()) {
        if (history.isEmpty()) {
            Text(
                text = "暂无记录",
                style = CarTheme.type.bodySmall,
                color = CarTheme.colors.textTertiary,
                modifier = Modifier.padding(
                    horizontal = CarTheme.dimens.spaceM,
                    vertical = CarTheme.dimens.spaceM,
                ),
            )
        } else {
            history.forEachIndexed { index, row ->
                if (index > 0) CarRowDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = CarTheme.dimens.spaceM,
                            vertical = CarTheme.dimens.spaceS,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(CarTheme.dimens.spaceM),
                ) {
                    Text(
                        text = row.first,
                        style = CarTheme.type.bodyMedium,
                        color = CarTheme.colors.textPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = row.second,
                        style = CarTheme.type.bodySmall,
                        color = CarTheme.colors.textTertiary,
                        maxLines = 1,
                    )
                }
            }
        }
    }

    Spacer(Modifier.size(CarTheme.dimens.spaceXxl))
}

// -------------------------------------------------------------------- 连接分区

@Composable
private fun ConnectionSection(
    draft: CarSessionSettings,
    update: (CarSessionSettings) -> Unit,
    model: CarUiModel,
    actions: CarUiActions,
) {
    SectionHeader(title = "连接")
    CarCard(modifier = Modifier.fillMaxWidth()) {
        CarSettingChoice(title = "连接方式", options = listOf(CarTransportMode.WIRED, CarTransportMode.WIRELESS),
            selected = draft.transport, onSelect = { update(draft.copy(transport = it)) },
            label = { if (it == CarTransportMode.WIRED) "USB 有线" else "无线 CarPlay" })
        CarSettingChoice(title = "认证方式", options = CarMfiTarget.entries.toList(), selected = draft.mfiTarget,
            onSelect = { update(draft.copy(mfiTarget = it)) }, label = { it.displayLabel() })
        if (draft.mfiTarget == CarMfiTarget.LOCAL_OFFLINE) {
            CarSettingInfo(title = "配件身份", value = model.mfiIdentity.sourceLabel.ifBlank { "未读取" })
            CarSettingAction(title = "导入自定义身份", value = "选择文件", onClick = actions::requestMfiIdentityImport)
        }
    }
    if (draft.isWireless) {
        LaunchedEffect(Unit) { actions.refreshHotspotBriefing() }
        SectionHeader(title = "车机热点", subtitle = "请先在原车系统开启热点；自动读取名称和密码，不使用ADB或局域网模式")
        CarCard(modifier = Modifier.fillMaxWidth()) {
            CarSettingInfo(title = "热点状态", value = hotspotStatusOf(draft, model))
            CarSettingText(title = "热点名称", value = draft.manualHotspotSsid,
                onValueChange = { update(draft.copy(manualHotspotSsid = it)) })
            CarSettingText(title = "热点密码", value = draft.manualHotspotPassphrase, isPassword = false,
                onValueChange = { update(draft.copy(manualHotspotPassphrase = it)) })
            CarSettingChoice(title = "加密方式", options = listOf(CarHotspotSecurity.OPEN, CarHotspotSecurity.WPA2),
                selected = draft.manualHotspotSecurity, onSelect = { update(draft.copy(manualHotspotSecurity = it)) },
                label = { if (it == CarHotspotSecurity.OPEN) "开放" else "WPA2" })
            CarSettingAction(title = "重新读取车机热点", value = null, onClick = actions::refreshHotspotBriefing,
                description = "读取失败保留现有值；不会把未知密码改成空密码。")
        }
    }
}

/** Historical layouts kept temporarily outside the reachable product navigation. */
@Composable
private fun LegacyConnectionSection(
    draft: CarSessionSettings,
    update: (CarSessionSettings) -> Unit,
    model: CarUiModel,
    actions: CarUiActions,
) {
    var showClearMfiIdentityDialog by remember { mutableStateOf(false) }

    SectionHeader(title = "链路")
    CarCard(modifier = Modifier.fillMaxWidth()) {
        CarSettingChoice(
            title = "连接方式",
            options = listOf(CarTransportMode.WIRED, CarTransportMode.WIRELESS),
            selected = draft.transport,
            onSelect = { update(draft.copy(transport = it)) },
            label = { if (it == CarTransportMode.WIRED) "USB 有线" else "无线 CarPlay" },
        )
        CarRowDivider()
        // 对应上游这一节里的 "Hotspot status"。它是只读回显，
        // 但价值很高：无线连不上时用户第一个要确认的就是"热点到底起没起来"，
        // 而界面上能看到这个状态的地方只有这里。
        CarSettingInfo(
            title = "热点状态",
            value = hotspotStatusOf(draft, model),
        )
        CarRowDivider()
        // 仅无线链路会走到这条命令（iPhone 建好 AirPlay 后才下发 disableBluetooth），
        // 但有线时也保留显示：设置项忽隐忽现比"用不上"更难理解。
        CarSettingChoice(
            title = "蓝牙握手处理",
            options = CarBluetoothHandoffPolicy.entries.toList(),
            selected = draft.bluetoothHandoffPolicy,
            onSelect = { update(draft.copy(bluetoothHandoffPolicy = it)) },
            label = { it.displayLabel() },
        )
    }

    SectionHeader(
        title = "MFi 认证",
    )
    CarCard(modifier = Modifier.fillMaxWidth()) {
        CarSettingChoice(
            title = "认证方式",
            options = CarMfiTarget.entries.toList(),
            selected = draft.mfiTarget,
            onSelect = { update(draft.copy(mfiTarget = it)) },
            label = { it.displayLabel() },
        )
        when (draft.mfiTarget) {
            CarMfiTarget.I2C -> {
                CarRowDivider()
                CarSettingText(
                    title = "I2C 设备路径",
                    value = draft.linuxI2cPath,
                    onValueChange = { update(draft.copy(linuxI2cPath = it)) },
                    placeholder = "/dev/i2c-1",
                )
            }

            CarMfiTarget.REMOTE -> {
                CarRowDivider()
                CarSettingText(
                    title = "远程认证服务地址",
                    value = draft.remoteMfiServer,
                    onValueChange = { update(draft.copy(remoteMfiServer = it)) },
                    placeholder = "https://example.com",
                )
                CarRowDivider()
                CarSettingText(
                    title = "访问令牌",
                    value = draft.remoteMfiToken,
                    onValueChange = { update(draft.copy(remoteMfiToken = it)) },
                    isPassword = true,
                )
            }

            CarMfiTarget.LOCAL_OFFLINE -> {
                val identity = model.mfiIdentity
                CarRowDivider()
                CarSettingInfo(
                    title = "配件身份",
                    value = identity.sourceLabel.ifEmpty { "缺失" },
                    description = identity.detail.ifEmpty {
                        "需要一份 Apple 配件身份（identity.pk8 + certificate.p7b）。"
                    },
                )
                if (identity.imported) {
                    CarRowDivider()
                    CarSettingAction(
                        title = "清除导入的身份",
                        value = null,
                        onClick = { showClearMfiIdentityDialog = true },
                    )
                }
                CarRowDivider()
                CarSettingAction(
                    title = "导入自定义身份",
                    value = "选择文件",
                    onClick = actions::requestMfiIdentityImport,
                    description = "依次选择 identity.pk8 与 certificate.p7b；两者必须来自同一份配件身份。",
                )
            }

            CarMfiTarget.USB_CH341 -> Unit
        }
    }

    if (showClearMfiIdentityDialog) {
        CarAlertDialog(
            title = "清除导入的身份？",
            message = "将删除本机保存的 identity.pk8 与 certificate.p7b，" +
                "改用随包内置的配件身份。若内置身份不可用，软件认证将无法完成。",
            confirmText = "清除",
            onConfirm = {
                showClearMfiIdentityDialog = false
                actions.clearMfiIdentity()
            },
            onDismiss = { showClearMfiIdentityDialog = false },
            destructive = true,
        )
    }

    if (draft.isWireless) {
        // ⚠️ 2026-10-06 重构：原来整块塞在一张卡里，一屏并列 5 个大控件 +
        // 3 个等宽大按钮，没有任何主次。现在按**功能**拆成三张卡：
        //   ① 热点身份（模式 / 凭据）—— 用户真正要填的东西
        //   ② 高级网络（网卡名）—— 默认折叠，排障才展开
        //   ③ 热点服务（两个开关 + 建立 / 自愈）—— 一次性动作
        // 「永久关闭热点」移出卡片、单独成区（见本段末尾），降低误触。
        val manualAttachment = draft.hotspotMode == CarHotspotMode.MANUAL
        // 复用类档位（手动复用 / TCAM 复用）共用的**身份类信息**：车机自带热点读数行、
        // 车机侧真实凭据的一键填入、名字填错警告。
        // MANUAL 档专属的（频段 / 信道、本机 CarPlay 地址）仍看 `manualAttachment`。
        val reuseAttachment = draft.hotspotMode.reusesCarNativeHotspot
        // PATCH-0010：局域网连接模式下，"热点身份"整块（SSID / 密码 / 加密）
        // **对用户没有意义**——网是别人的，凭据不由我们决定。
        // 显示它们只会让用户以为要填，填了也不起作用。
        val sameLan = draft.hotspotMode == CarHotspotMode.SAME_LAN

        SectionHeader(
            title = "热点身份",
        )
        // 车机自带热点关着时，**在设置页就把"连接必然失败"说清**（2026-10-08 加）。
        //
        // 文案直接复用连接前那道拦截（`CarManualHotspotPrecheck.blocker`）的**同一份**
        // ——两处各写一遍必然漂移，而现场最怕"设置页说能连、失败页说不能连"。
        // 它内部已经判了 `mode != MANUAL` 就返回 null，所以这里不必再加模式判断。
        val manualHotspotBlocker = CarManualHotspotPrecheck.blocker(
            mode = draft.hotspotMode,
            state = model.hotspotEnabledState,
            ssid = draft.manualHotspotSsid,
            // ⚠️ 2026-10-11（PATCH-0087）：复用的地址**由用户填**，没填就在这一页当场说清
            // （与连接前那道拦截同一份文案，见 `CarManualHotspotPrecheck`）。
            hostAddress = draft.manualHotspotHostAddress,
            reuseRequested = draft.hotspotMode == CarHotspotMode.MANUAL,
        )
        if (manualHotspotBlocker != null) {
            NoticeCard(text = manualHotspotBlocker)
        }
        // ⚠️ 2026-10-06 修（真机反馈）：**模式选择卡片必须无条件渲染**，
        // 不能被 `if (sameLan)` 分支吃掉。
        //
        // 旧结构把整张「热点模式」卡片塞在 `else`（非 SAME_LAN）分支里，
        // 于是用户一旦点了「局域网连接」，**连"热点模式"这排按钮本身都消失了**
        // ——剩下三张卡（本次将使用 / 本机地址 / 使用前提）里没有任何退出路径。
        // 用户的原话是"选择了局域网连接后，其他连接看不见了"：
        // 他以为选项被删了，其实是他把自己锁在了一个只有 SAME_LAN 的页面里。
        //
        // 选择器**永远在最上面**，用户随时能改回别的模式；下面再按当前模式
        // 渲染该模式专属的补充说明（SAME_LAN 的三张卡 / 其余模式的凭据卡）。
        CarCard(modifier = Modifier.fillMaxWidth()) {
            CarSettingChoice(
                title = "热点模式",
                // ⚠️ 2026-10-08（斌哥定案）：**「自动」按钮已从界面移除**。
                //
                // 理由是这一支早就不自动了：`CarHotspotResolver.resolve()` 自
                // 2026-10-05 起对 `AUTO` 也**直接自建、不做任何探测**（那条口径
                // 写死在解析器里）⇒ 界面上的「自动（优先复用车机热点）」是**假承诺**，
                // 用户选了它得到的其实是自建。留着按钮只会让人以为能自动复用。
                //
                // 枚举值 `CarHotspotMode.AUTO` **保留**：老安装的持久化配置里存着
                // 这个名字，删掉会让解析器的 `when` 漏掉一支（见
                // `SharedPreferencesCarSettingsStore` 的迁移逻辑）。它现在只是
                // **不可由界面选中的合法存量值**，解析时等价于自建。
                //
                // ⚠️ 2026-10-16（车主定案）：**「复用车机热点」按钮已从界面移除**，
                // 由「复用车机 TCAM 热点」取代（两个名字撞车，且车主只保留 TCAM 一档复用）。
                //
                // 枚举值 `CarHotspotMode.MANUAL` **保留**：老安装的持久化配置里存着
                // 这个名字，删掉会让解析器的 `when` 漏掉一支（见
                // `SharedPreferencesCarSettingsStore` 的迁移逻辑）。它现在只是
                // **不可由界面选中的合法存量值**，解析时等价于 TCAM_HOTSPOT。
                //
                // 与「自动」同一手法（filterNot）：界面只提供自建 / 局域网 / TCAM 复用三档。
                // 顺序仍然是刻意的：自建在前、局域网连接在后、TCAM 复用收尾。
                options = CarHotspotMode.entries.filterNot { it == CarHotspotMode.AUTO || it == CarHotspotMode.MANUAL },
                selected = draft.hotspotMode,
                onSelect = { update(draft.copy(hotspotMode = it)) },
                label = { hotspotModeLabel(it) },
            )
            // 车机自带热点的开关读数（2026-10-08 加，车主口径："要能常驻看到"）。
            //
            // 只在「复用车机热点」下显示：自建那一支那张网是**我们自己起的**
            // （车机开关关着才正常），局域网连接一张热点都不建 —— 对它们显示
            // "车机热点已关闭"只会误导。
            //
            // 这一行**可点**（`onClick` = 重读一次），所以不给右箭头：
            // `CarSettingInfo` 的只读外观 + 点击刷新，与开发者页隐藏入口同一手法
            // （见 `dbplay-feature-wiring` §二.3）。
            if (reuseAttachment) {
                CarRowDivider()
                CarSettingInfo(
                    title = "车机自带热点",
                    value = carHotspotEnabledLabel(model.hotspotEnabledState),
                    description = carHotspotEnabledNote(model.hotspotEnabledState),
                    onClick = actions::refreshHotspotBriefing,
                )
            }
        }
        if (sameLan) {
            CarCard(modifier = Modifier.fillMaxWidth()) {
                // 自动读取的结果（`CarSameLanReader` 已经读好放进 model）。
                // 这一行是排查"连不上"的第一现场：车机到底拿到什么地址、
                // 连的是哪个网，一眼可见。
                //
                // ⚠️ 与上面 else 分支同一条判据：简报是用**已保存配置**算的，
                // 草稿一切走它就过期（真机截图：这里写着"自建热点「DBPlay」"，
                // 而当前明明选的是局域网连接）。只在草稿与已保存一致时显示。
                if (model.hotspotHint.isNotEmpty() && draft.hotspotMode == model.settings.hotspotMode) {
                    CarSettingInfo(
                        title = "本次将使用",
                        value = model.hotspotTarget.ifEmpty { "自动读取本机地址" },
                        description = model.hotspotHint,
                    )
                    CarRowDivider()
                }
                CarSettingText(
                    title = "本机地址",
                    value = draft.sameLanHostAddress,
                    onValueChange = { update(draft.copy(sameLanHostAddress = it.trim())) },
                    placeholder = "留空自动读取当前网络",
                )
                CarRowDivider()
                // ⚠️ 2026-10-07 新增（实测修正）：这两个字段**不是可选项**。
                //
                // 诊断包 `1aedc494` 证明：iPhone 是**照着**我们下发的 Wi-Fi 名/密码
                // 去关联网络的，而不是"已经在网里就跳过"。旧版本这里下发的是本机
                // 热点名（`DBPlay`）——一个本模式下根本不存在的网络 ⇒ iPhone 找不到
                // ⇒ 卡在「无线通道就绪」。所以必须让用户能填"车机连的那张 Wi-Fi"。
                //
                // ⚠️ 诊断包 `1791345066541` 追加证实：**Wi-Fi 名称在这里是必填**。
                // 留空会让下游 `Iap2WirelessIdentification` 的 `require(ssid.isNotBlank())`
                // 抛异常，整个无线 bring-up 2 秒即失败（界面只表现为"启动后立刻断"）。
                // 所以提示文案必须明确"必填"，不能再说"留空自动读取"。
                //
                // 自动读取只拿得到名字（Android 自 10 起读不到已保存网络的明文密码），
                // 且个别 ROM 连名字都只给 `<unknown ssid>` ⇒ 手填是常见路径。
                CarSettingText(
                    title = "Wi-Fi 名称",
                    value = draft.sameLanWifiSsid,
                    onValueChange = { update(draft.copy(sameLanWifiSsid = it.trim())) },
                    placeholder = "必填：车机所连 Wi-Fi 的名字",
                    description = "必填。留空会导致无线连接启动失败。",
                )
                CarRowDivider()
                CarSettingText(
                    title = "Wi-Fi 密码",
                    value = draft.sameLanWifiPassphrase,
                    onValueChange = { update(draft.copy(sameLanWifiPassphrase = it.trim())) },
                    placeholder = "该网有密码时必填",
                )
                CarRowDivider()
                // ⚠️ 这三个前提**必须写出来**：它们全都在车机上不可观测，
                // 用户不知道就会反复重试一个注定失败的组合。尤其是
                // 路由器"客户端隔离"——开了之后同一张网内也互相不通，
                // 而车机侧看起来一切正常。
                CarSettingInfo(
                    title = "使用前提",
                    value = "同一张网",
                )
            }
        } else {
        CarCard(modifier = Modifier.fillMaxWidth()) {
            // 「本次将使用」= 已保存配置解析出来的结论。
            //
            // ⚠️ 只有在**草稿与已保存值一致**时才显示。
            // 判据不是"有没有值"，而是"这份简报描述的是不是用户现在看到的这一支"：
            // `hotspotBriefing` 由 `CarAppController` 用**已保存的 settings** 算（见
            // `refreshHotspotHint()` 只在 `applySettings` 后触发），草稿一改它就过期。
            // 旧版不看这一点，于是"点局域网连接 → 上面却写着自建热点「DBPlay」"
            // （真机截图实锤）——一句与当前选择矛盾的话，比没有这句话糟糕得多。
            if (model.hotspotHint.isNotEmpty() && draft.hotspotMode == model.settings.hotspotMode) {
                CarSettingInfo(
                    title = "本次将使用",
                    // 值列直接给结论。`AUTO` 下不能再写"自动判定"——用户要的是
                    // "到底用哪张网"，理由由 description 承担。
                    value = model.hotspotTarget.ifEmpty { hotspotModeLabel(draft.hotspotMode) },
                    // 复用车机热点时，"iPhone 该连哪张网、密码多少"排在最前面：
                    // 凭据在车机侧，用户在本 App 里看不到，这是最缺的一句。
                    // 自建那一支没有这一行（凭据就是上面两个输入框）。
                    description = listOfNotNull(
                        model.hotspotPhoneHint.takeIf { it.isNotEmpty() },
                        model.hotspotHint,
                    ).joinToString("\n"),
                )
                CarRowDivider()
            }
            CarSettingText(
                title = "热点名称 (SSID)",
                value = draft.manualHotspotSsid,
                onValueChange = { update(draft.copy(manualHotspotSsid = it)) },
                placeholder = if (reuseAttachment) "车机已开启的热点名" else "本机热点名",
                // ⚠️ 只保留**两条警告**：名字超长（`CarHotspotNameRules`，有单测）与
                // 复用模式下名字填错（`model.hotspotWarning`，链路完全静默、无任何报错）。
                // 两者都是"必须当场知道"的信息，其余解释性文案已按精简口径移除。
                description = listOfNotNull(
                    CarHotspotNameRules.problemOf(draft.manualHotspotSsid)?.let {
                        "⚠️ " + CarHotspotNameRules.describe(it, draft.manualHotspotSsid)
                    },
                    model.hotspotWarning.takeIf { it.isNotEmpty() },
                ).joinToString("\n"),
            )
            CarRowDivider()
            CarSettingText(
                title = "热点密码",
                value = draft.manualHotspotPassphrase,
                onValueChange = { update(draft.copy(manualHotspotPassphrase = it)) },
                isPassword = true,
                // 掩码栏位：不把出厂密码写出来，用户就没法从界面上知道默认值
                // （无线连不上时又要去翻代码）。属「必要信息」，不在精简范围内。
                description = "开放网络留空；加密网络需 8~63 位。" +
                    "出厂默认 ${CarSessionSettings.DEFAULT_HOTSPOT_PASSPHRASE}。",
            )
            // 一键填入车机真实热点名/密码（2026-10-13）。
            //
            // 复用模式要填的是**车机侧**的凭据，用户在本 App 里看不到、只能默写；
            // 默写错是静默失败（真车日志 4：`Lynk&Co` ↔ 真名 `Lynk & Co` ⇒
            // 通告了一张没人广播的网）。探测链路本来就读得到这两个值，
            // 让"填对"变成点一下。密码读不到时只填名称 —— 不动用户已填的密码框。
            // 只在复用模式下给：自建那一支这两个框命名的是我们自己的网，
            // 填入车机凭据反而是错的（与 `CarHotspotBriefing.nativeCredentials` 同源）。
            val nativeCredentials = model.nativeHotspotCredentials
            if (reuseAttachment && nativeCredentials != null) {
                CarRowDivider()
                CarSettingAction(
                    title = "填入车机热点「${nativeCredentials.ssid}」",
                    value = if (nativeCredentials.passphrase != null) {
                        "名称 + 密码"
                    } else {
                        "仅名称（密码读不到）"
                    },
                    onClick = {
                        update(
                            draft.copy(
                                manualHotspotSsid = nativeCredentials.ssid,
                                manualHotspotPassphrase = nativeCredentials.passphrase
                                    ?: draft.manualHotspotPassphrase,
                            ),
                        )
                    },
                )
            }
            // 频段 / 信道只对 MANUAL 有意义 —— 那是"接管一张已经存在的热点"，
            // 这两个值是那张网的实际值；其余分支由自建方案决定，暴露出来就是假开关。
            if (manualAttachment) {
                CarRowDivider()
                // 其他车型可手动指定本机 AirPlay endpoint；不能把外置 T-Box 上的 AP 网关
                // 误认为本机服务地址。Ecarx 路由验证成功时，会话使用验证到的本机 source address，
                // 不改写此设置。未验证到 route 时仍按下面的手动值和连接前检查处理。
                CarSettingText(
                    title = "本机 CarPlay 地址",
                    value = draft.manualHotspotHostAddress,
                    onValueChange = { update(draft.copy(manualHotspotHostAddress = it.trim())) },
                    placeholder = "本机 IPv4",
                    keyboardType = KeyboardType.Number,
                )
                // 🔴 PATCH-0102（2026-10-12）：把**本机现在真实存在的地址**列出来，让用户点选。
                //
                // 这条是给"用户不知道该填什么"兜底的。过去这里只有一个空输入框，
                // 而别处的提示文案写死了 `192.168.15.1` —— 那是**外置 T-Box 网关，
                // 根本不在本机网卡上**，用户照填必然被连接前那道 precheck 拦下
                // （真车 `日志/3`：10 次尝试全灭，见 `REF-pitfalls.md` §125）。
                //
                // 候选与连接时那道校验**同源**（同一份 `CarHotspotBriefing`、同一次网卡枚举）
                // ⇒ 从这儿点进去的地址，连接时一定过得了"在不在网卡上"这一关，
                // 不会再出现"页面让我填、连接说没有"的自相矛盾。
                model.hotspotAddressCandidates.forEachIndexed { index, candidate ->
                    CarRowDivider()
                    CarSettingAction(
                        title = "填为 ${candidate.address}",
                        value = candidate.interfaceName,
                        onClick = {
                            update(draft.copy(manualHotspotHostAddress = candidate.address))
                        },
                    )
                }
                CarRowDivider()
                CarSettingChoice(
                    title = "频段",
                    options = CarHotspotBand.entries.toList(),
                    selected = draft.manualHotspotBand,
                    onSelect = { update(draft.copy(manualHotspotBand = it)) },
                    label = {
                        when (it) {
                            CarHotspotBand.AUTO -> "自动"
                            CarHotspotBand.GHZ_2_4 -> "2.4 GHz"
                            CarHotspotBand.GHZ_5 -> "5 GHz"
                        }
                    },
                )
                CarRowDivider()
                CarSettingText(
                    title = "信道",
                    value = draft.manualHotspotChannel.toString(),
                    onValueChange = { raw ->
                        raw.filter { it.isDigit() }.take(3)
                            .toIntOrNull()
                            ?.let { update(draft.copy(manualHotspotChannel = it)) }
                    },
                    description = "0 表示自动。",
                    keyboardType = KeyboardType.Number,
                )
            }
            CarRowDivider()
            CarSettingChoice(
                title = "加密方式",
                options = CarHotspotSecurity.entries.toList(),
                selected = draft.manualHotspotSecurity,
                onSelect = { update(draft.copy(manualHotspotSecurity = it)) },
                label = {
                    when (it) {
                        CarHotspotSecurity.OPEN -> "开放"
                        CarHotspotSecurity.WPA2 -> "WPA2"
                        CarHotspotSecurity.WPA3_TRANSITION -> "WPA3 过渡"
                        CarHotspotSecurity.WPA3 -> "WPA3"
                    }
                },
            )
        }
        }

        if (draft.hotspotMode == CarHotspotMode.ADB_HOSTAPD) {
            CarSettingAdvancedInterface(
                value = draft.adbHotspotInterface,
                onValueChange = { update(draft.copy(adbHotspotInterface = it.trim())) },
            )
        }

        // ADB 初始化独立于热点模式：它只配置/验证全局 5556 通道，不启动 AP。
        SectionHeader(title = "ADB 通道")
        CarCard(modifier = Modifier.fillMaxWidth()) {
            CarSettingInfo(
                title = "初始化记录",
                // 2026-10-16：「已执行 / 曾尝试，未成功」这个二分会把**当前其实可用、
                // 只是持久属性没读回**的车渲染成"失败"。中间态单独给一句话，
                // 与旁边「当前 5556 状态」的措辞对齐（两边说的是同一件事）。
                value = when {
                    !model.adbInitialization.initializedByUser -> "尚未执行"
                    model.adbInitialization.initializationSucceeded -> "已执行"
                    model.adbInitialization.channelStatus == CarAdbChannelStatus.PERSISTENCE_UNVERIFIED ->
                        "已执行（持久性未确认）"
                    else -> "曾尝试，未成功"
                },
                description = if (model.adbInitialization.persistentPortVerified) {
                    "上次初始化时 persist 端口读回为 5556。"
                } else {
                    "该历史记录不代表当前 ADB 在线，也未确认重启后仍有效。"
                },
            )
            CarRowDivider()
            CarSettingInfo(
                title = "当前 5556 状态",
                // 关闭态**优先于**探测结果显示：用户自己关掉的，不能被渲染成
                // "5556 不可用" —— 那读起来像故障，会把他引去点「永久开启 ADB」，
                // 一点就把刚关掉的通道重新打开（2026-10-15）。
                value = if (model.adbInitialization.disabledByUser) {
                    "已关闭"
                } else {
                    adbChannelStatusLabel(model.adbInitialization.channelStatus)
                },
                description = model.adbInitialization.detail,
            )
            CarRowDivider()
            // 2026-10-15：旧版在 5556 已就绪时把整块按钮**藏起来**，既没有"关闭"的
            // 落点，用户也读不出为什么这里没按钮。现在恒显示、按状态置灰。
            CarAdbSetupRow(model = model, actions = actions)
        }

        // ============================================================
        // 「开发者调试」独立版块（2026-10-10，斌哥口径）
        // ============================================================
        //
        // 原文：「我想让你在 DBPlay 开着模式里面加一个，服务器就是公网 IP」。
        //
        // 解决：改一行代码要出包→传包→手装，一轮十几分钟。开了这个，
        // 车机 App 启动就**反连公网服务器**，开发者 adb 直连进来调，
        // 不用再出包（概念与流程见 `LynkDebugTunnel`）。
        //
        // ⚠️ 「开发者调试」曾有两次错误放置：
        //    ① 塞在 ConnectionSection 内、且被 if(ADB_HOSTAPD) 包住 ⇒ 看不到；
        //    ② 改成设置索引页独立入口 ⇒ 斌哥要求「塞进 DBPlay 开发者模式里面」。
        //    最终落地：`DeveloperScreen.kt` 的「调试反连隧道」版块，
        //    设置页这边**不再有入口**。
    }
}

/**
 * 「高级网络」折叠区：AP 网卡名（2026-10-06 新增）。
 *
 * ## 为什么折叠
 *
 * 这个字段**只在一件事上有用**：自动探测挑错了网卡。而探测失败是少数情况，
 * 绝大多数用户既不知道"wlan2"是什么，也不该被要求理解。
 * 常驻显示它，等于用一行满屏的术语稀释掉上面那些真正要填的字段。
 *
 * 默认收起、标注「高级」——需要的人会点开，不需要的人永远不用看到。
 * 这和"前置条件不满足时不要隐藏设置项"不冲突：那条规则针对的是
 * **依赖当前环境、隐藏后用户无从判断为什么变灰**的项；这里是纯排障入口，
 * 折叠它不会让任何用户"找不到本该能用的功能"。
 */
@Composable
private fun CarSettingAdvancedInterface(
    value: String,
    onValueChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val dimens = CarTheme.dimens

    SectionHeader(title = "高级网络")
    CarCard(modifier = Modifier.fillMaxWidth()) {
        CarSettingRow(
            title = "AP 网卡名",
            // 收起时把当前值显示出来，否则用户点了折叠项也不知道里面有没有改过。
            description = if (value.isBlank()) "留空自动探测" else "当前：$value",
            onClick = { expanded = !expanded },
            trailing = {
                Icon(
                    imageVector = if (expanded) CarIcons.ChevronUp else CarIcons.ChevronRight,
                    contentDescription = if (expanded) "收起" else "展开",
                    modifier = Modifier.size(dimens.iconS),
                    tint = CarTheme.colors.textTertiary,
                )
            },
        )
        if (expanded) {
            CarRowDivider()
            CarSettingText(
                title = "网卡名",
                value = value,
                onValueChange = onValueChange,
                placeholder = "留空自动探测",
                // 这张网卡叫什么完全由厂商决定（实测线索是 wlan2，
                // 也可能叫 ap0 / softap0），自动探测猜错时必须有个人工口，
                // 否则用户只能改代码。可填的候选名在开发者页自检里能看到。
            )
        }
    }
}

/**
 * ADB 5556 初始化/修复按钮，设置页与连接失败页共用。
 *
 * 按钮实现位于 [com.dbtool.carplay.ui.components.CarHotspotAdbSetupButton]，不会启动热点。
 *
 * ## 版式说明
 *
 * 旧版这行是一颗**通栏**大按钮，与下面的「立即自愈 ADB」「永久关闭热点」
 * 三条等宽大块竖着堆，一屏全是宽条，没有任何主次。
 *
 * 现在改为**主次分明的两列一行**（见 [CarHotspotActionRow]）：
 * 「立即建立热点 + ADB」占主位（宽权重 3），「立即自愈 ADB」退居次位（权重 2）。
 * 这两件事本来就是同一条链路的进 / 退两侧（建立 vs 修复），摆在一行才读得出关系。
 */
@Composable
private fun CarAdbManualSetupRow(actions: CarUiActions) {
    CarHotspotAdbSetupButton(actions = actions)
}

/**
 * 「永久开启 ADB（5556）」＋「关闭 ADB」并排一行（2026-10-15）。
 *
 * ## 出处（斌哥口径，verbatim）
 *
 *   「若 ADB 5556 已开启，按钮灰色不可按，同时永久开启 ADB 旁新增关闭 ADB 按钮
 *     并实现对应的功能」
 *
 * ## 两个刻意的行为变更
 *
 * ① **恒显示**：旧版在"5556 已就绪且持久属性已验证"时把整块按钮**藏起来**。
 *    藏了之后没有任何"关闭"的落点，用户也读不出"为什么这里没有按钮"。
 *    现在恒显示 + 按状态置灰。
 *
 * ② **两颗按钮的判据故意不同**（别图省事合成一个条件）：
 *    - 「永久开启」在已就绪时置灰（重复开启无意义），但**被关闭过时必须可点**
 *      —— 它是全 App 唯一的恢复出口，跟着一起灰就等于把用户锁死；
 *    - 「关闭」在已关闭时置灰；`UNKNOWN`（还没探过）时也置灰，
 *      避免在不知道当前状态的情况下盲点。
 *
 * ## 版式
 *
 * 权重 3:2：「永久开启 ADB（5556）」文案更长所以给主位 3，「关闭 ADB」给 2。
 * 两颗组件内部都是 `fillMaxWidth()`，宽度一律由这里决定（组件不写死绝对宽度，
 * 见 `HotspotAdbSetupButton` 的 KDoc）。
 */
@Composable
private fun CarAdbSetupRow(model: CarUiModel, actions: CarUiActions) {
    val dimens = CarTheme.dimens
    val adb = model.adbInitialization
    val ready = adb.channelStatus == CarAdbChannelStatus.READY

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(dimens.spaceS),
    ) {
        Box(modifier = Modifier.weight(3f)) {
            CarHotspotAdbSetupButton(
                actions = actions,
                enabled = !ready || adb.disabledByUser,
                // 2026-10-16（需求第 2 条）：确认 5556 已开启时把这颗按钮**置灰**，
                // 并把"已经开好了"这句话写进结果行 —— 只给一颗按不动的灰块，
                // 用户分不清是"已经好了"还是"坏了"。
                //
                // ⚠️ 置灰只在「可用 **且** 持久属性已读回」时发生（status == READY）。
                // 「当前可用但持久性未确认」**不置灰** —— 那颗按钮此时是唯一的
                // 补齐持久化入口，灰掉用户就永远等不到"重启后还能用"。
                disabledHint = "永久 ADB 已开启（5556 端口已开启），无需重复操作。",
            )
        }
        Box(modifier = Modifier.weight(2f)) {
            CarAdbDisableButton(
                actions = actions,
                enabled = !adb.disabledByUser &&
                    adb.channelStatus != CarAdbChannelStatus.UNKNOWN,
                disabledHint = if (adb.disabledByUser) "ADB 通道已关闭。" else "尚未检测 5556。",
            )
        }
    }
}

/**
 * 「关闭 ADB」（2026-10-15 新增）。
 *
 * ## 它到底关了什么（界面上必须说清，不能夸大）
 *
 * 关的是 **DBPlay 自己的 5556 通道**：落盘禁用标记、收回共享 adb 通道，
 * 此后不再预热 / 不再自愈 / 看门狗不再巡检 / 不跑启动性能优化。
 * **不写系统属性、不停 adbd** —— 车机系统自带的 ADB 端口不受本开关影响。
 * 这句限定由 `CarAdbInitializationState.detail` 带出来显示在结果行上：
 * 一颗写着"关闭 ADB"的按钮如果实际上没关掉系统 ADB 却不说，就是骗人。
 *
 * ## 为什么用 `Secondary` 而不是 `Danger`
 *
 * `Danger`（见下面「手动关闭热点」）表达"这一下不可逆"。这颗不是：5556 通道随时
 * 可以由旁边那颗「永久开启 ADB」重建，**不需要 root、不用重启 App**。
 * 所以按次操作渲染 —— 主位仍然是「永久开启 ADB」。
 *
 * ## 结果行
 *
 * 与建立 / 自愈两颗同规格：**恒占位**，点击后不留位置抖动（避免误触下方控件）。
 *
 * @param enabled 见调用处 `CarAdbSetupRow` 的判据说明。
 * @param disabledHint 置灰时结果行要显示的原因（不能留空，否则用户只看到一颗
 *   按不动的灰块，分不清是"已经关了"还是"坏了"）。
 */
@Composable
private fun CarAdbDisableButton(
    actions: CarUiActions,
    enabled: Boolean,
    disabledHint: String,
) {
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    val dimens = CarTheme.dimens

    Column(
        modifier = Modifier.fillMaxWidth().padding(dimens.spaceM),
        verticalArrangement = Arrangement.spacedBy(dimens.spaceS),
    ) {
        CarButton(
            text = if (busy) "正在关闭…" else "关闭 ADB",
            enabled = !busy && enabled,
            onClick = {
                busy = true
                result = null
                failed = false
                actions.onDisableAdb { state ->
                    busy = false
                    result = state.detail
                    // 判据用的是回执里的落盘标记，不是"没抛异常" —— 只有真的记上了才算关成，
                    // 这样"写不进 prefs"那条分支（我们自己的文案）会正确地红字提示。
                    failed = !state.disabledByUser
                }
            },
            modifier = Modifier.fillMaxWidth(),
            variant = CarButtonVariant.Secondary,
            // 与左侧 `Primary` 同档高度（`Large`）：`Row` 默认顶部对齐，
            // 两颗实心按钮高度差一档会变成两条参差的色块（同 `CarAdbHealRow` 的教训）。
            size = CarButtonSize.Large,
        )
        Text(
            text = result ?: if (enabled) "" else disabledHint,
            style = CarTheme.type.bodySmall,
            color = if (failed) CarTheme.colors.error else CarTheme.colors.textSecondary,
        )
    }
}

// 「启动性能优化 · 立即执行一次」按钮（原 `CarPerfCompileRow`）**已于 2026-10-08
// 按车主口径移除** —— 原话：「**移除设置-启动-立即执行一次性性能优化**」。
//
// ## 移除了什么、保留了什么
//
// 移除的只是"**我现在就要跑一次**"这个手动入口。**「启动性能优化」开关本身保留**，
// 它管的是"**每次启动自动执行** `pm compile -m speed -f`"（见下方 `StartupSection`
// 与 `CarAppController.startSession()`）——开关的语义没变，只是不再给一个
// "当场复跑一遍"的按钮。车主判据：开关已经覆盖了需求，多一颗按钮只是多一处要维护的版式。
//
// 随之清理的、**只服务这颗按钮**的那条链路：
//
// - `CarUiActions.runStartupPerfCompile()` —— 契约里删掉（全仓唯一调用方就是本组件）；
//   但 `CarAppController` 里那条**实现保留**，它现在只被"开机自动执行"调用；
// - `CarUiModel.perfCompileResult` —— 唯一消费者是下方那行结果文字，一并删掉。
//
// ⚠️ 用户可见的反馈**没有丢**：自动执行结束时 `CarAppController` 仍会弹一次
// `notice` 气泡（"启动性能优化完成 / 未完成：原因"），不会变成静默动作。

/**
 * ADB 热点服务的主次按钮行（2026-10-08，斌哥：「这些按钮排版优化」）。
 *
 * ## 为什么改成横向两列
 *
 * 旧版是三条**等宽通栏**大按钮竖着堆：「立即建立热点 + ADB」/「立即自愈 ADB」/
 * 「永久关闭热点」。两个问题：
 *
 * ① **没有主次**——建立是主动作（每屏只该有一个 Primary），自愈是修复性次动作
 *    （原 Ghost，2026-10-08 改为 Secondary），关闭是破坏性动作（Danger）。
 *    三条一样宽一样高，用户读不出"哪个是常用、哪个要慎点"。
 * ② **屏幕浪费**——车机是 1024~1280dp 的横屏，通栏按钮一行只放一个动作，
 *    要往下滑才看得全。
 *
 * 现在把**建立 + 自愈**并排成一行：它是同一条链路的进 / 退两面，天然成对；
 * 权重 3 : 2 让建立仍占主位（文案更长也需要更多宽度），自愈退居次位。
 * 「永久关闭热点」**不并进来**——它是破坏性动作，继续独立成区（见调用处注释）。
 *
 * ## 为什么用 `weight` 而不是固定 dp
 *
 * 车机宽度跨 1024~1280dp 多档，写死 dp 会在窄屏上挤压、宽屏上留白。
 * 权重让两个按钮**按比例**分配可用宽度，任何屏上都保持同一主次关系。
 *
 * 两个子组件都**自带结果行**（内部 `remember`），所以这里不需要透传
 * busy/result —— 它们各自占了并排单元的下半部分，点击后不会互相顶位。
 */
@Composable
private fun CarHotspotActionRow(actions: CarUiActions) {
    val dimens = CarTheme.dimens
    Row(
        modifier = Modifier.fillMaxWidth().padding(dimens.spaceM),
        horizontalArrangement = Arrangement.spacedBy(dimens.spaceM),
    ) {
        // 主位：建立（Primary）。宽度权重给多一点——它的文案"立即建立热点 + ADB（永久）"
        // 也比"立即自愈 ADB"长，权重 3:2 两边都不会被挤到省略号。
        Box(modifier = Modifier.weight(3f)) {
            CarHotspotAdbSetupButton(actions = actions)
        }
        // 次位：自愈（Secondary —— 有实底与描边的次按钮；2026-10-08 由 Ghost 改来，
        // 车主口径「立即治愈 ADB 这个也改成按钮形式」，见 `CarAdbHealRow` 的注释）。
        Box(modifier = Modifier.weight(2f)) {
            CarAdbHealRow(actions = actions)
        }
    }
}

/**
 * 「立即自愈 ADB」（2026-10-04）。
 *
 * 与下面「永久关闭热点」的区别：这是**修复性**动作，不是破坏性的。
 * 点一下会把 adb shell 通道重跑一遍（丢缓存 → 开通 ADB → 起 server →
 * 自连 → 提权），用来治 `offline` 或 adbd 被系统回收的情况——
 * 用户不必重启 App。
 *
 * 结果直接以一行小字回报（成功/失败+原因），因为用户点它时就是想立刻知道修没修好。
 *
 * ⚠️ 关掉「允许内置 ADB」时按钮仍可用：自愈会跳过"开通 ADB"那一步，
 * 但"起 server / 自连"这些本地动作照做 —— 车机 ADB 要是用户自己开着，
 * 这里同样能修。
 *
 * ## 🔴 2026-10-08：`Ghost` → `Secondary`（车主截图反馈「这个也改成按钮形式」）
 *
 * 原先是 `CarButtonVariant.Ghost`。查 `ActionButton.kt` 的实现：`Ghost` 映射到
 * Material3 的 **`TextButton`** —— 无底色、无描边，**渲染出来就是一行纯文字**
 * （只有 `primaryText` 颜色 + 圆角点击区）。它被摆在旁边那颗 `Primary` 实底按钮
 * 的左边，视觉上是一句"说明文字"而不是一个可点的控件 ⇒ 车主读不出"这里能点"。
 *
 * 现在改用 `Secondary`（`OutlinedButton` + `colors.surface` 实底 + 极淡描边），
 * 于是它成为一颗**看得出是按钮**的次操作按钮，同时仍然明显弱于左侧的
 * `Primary`（实底主色）—— 主次关系靠"填充色"区分，而不是靠"有没有形"。
 *
 * ⚠️ 这里**不改成 `Primary`**：同一屏只允许一个主操作，建立热点才是主操作；
 * 两颗实底主色按钮并排会让用户分不清该点哪个。
 *
 * 同批把尺寸从默认的 `Regular`（56dp）提到 `Large`（68dp），与左侧 `Primary` 齐平 ——
 * `Row` 默认顶部对齐，两颗**实心**按钮差 12dp 会变成两条参差的色块。
 */
@Composable
private fun CarAdbHealRow(actions: CarUiActions) {
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val dimens = CarTheme.dimens

    Column(
        modifier = Modifier.fillMaxWidth().padding(dimens.spaceM),
        verticalArrangement = Arrangement.spacedBy(dimens.spaceS),
    ) {
        CarButton(
            text = if (busy) "正在自愈 ADB…" else "立即自愈 ADB",
            enabled = !busy,
            onClick = {
                busy = true
                result = null
                actions.onHealAdb { ok, detail ->
                    busy = false
                    result = if (ok) "已恢复：adb shell 通道可用。" else "仍不可用：$detail"
                }
            },
            modifier = Modifier.fillMaxWidth(),
            variant = CarButtonVariant.Secondary,
            // 与左侧那颗 `Primary` 同档高度（`CarButtonSize.Large`）。
            //
            // `Row` 默认 `verticalAlignment = Top`，两颗按钮各自的高度决定底边位置。
            // 原先 Ghost 是"一行文字"，与 68dp 的实底按钮并排时高度差还能被忽略；
            // 改成有底色描边的实心按钮之后，56dp vs 68dp 会变成**两条参差的色块**，
            // 一眼看出没对齐。所以这里跟着取 `Large`。
            size = CarButtonSize.Large,
        )
        // 与上面的建立按钮同理：结果区始终占位，避免点击后内容跳动导致误触。
        Text(
            text = result.orEmpty(),
            style = CarTheme.type.bodySmall,
            color = if (result?.startsWith("仍不可用") == true) {
                CarTheme.colors.error
            } else {
                CarTheme.colors.textSecondary
            },
        )
    }
}

/**
 * 「手动关闭热点」（2026-10-04）。
 *
 * 斌哥口径：**"热点是永久开启，跟随车机自启动；关掉热点是永久关闭"**。
 * 所以这是一个**永久关闭**入口——等同于把上面「热点常开」关掉，
 * 但摆在手边、且有二次确认，不必去翻开关。
 *
 * 点了之后：本次会话切断、**下次开机也不再自启**，直到在设置页重新打开
 * 「热点常开」。
 *
 * 用 `Danger` 变体而不是 `Secondary`：这不是日常操作（日常是常开），
 * 点了会把无线 CarPlay 的通路断掉、且不会自己回来。变体差异就是提醒
 * "这一下是有代价的"。
 */
@Composable
private fun CarAdbHotspotManualStopRow(actions: CarUiActions) {
    var confirming by remember { mutableStateOf(false) }
    if (!confirming) {
        CarButton(
            text = "永久关闭热点",
            onClick = { confirming = true },
            modifier = Modifier.fillMaxWidth(),
            variant = CarButtonVariant.Secondary,
            icon = CarIcons.Close,
        )
    } else {
        // 两步确认：误点一下就断掉 CarPlay，代价与"点错一个开关"不成比例。
        NoticeCard(
            text = "将永久关闭热点：无线 CarPlay 会断开，且下次开机不再自动开启。确认关闭？",
        )
        Spacer(Modifier.size(CarTheme.dimens.spaceS))
        CarButton(
            text = "确认永久关闭",
            onClick = {
                actions.onStopAdbHotspot()
                confirming = false
            },
            modifier = Modifier.fillMaxWidth(),
            variant = CarButtonVariant.Danger,
            icon = CarIcons.Close,
        )
    }
}

// -------------------------------------------------------------------- 显示分区

@Composable
private fun DisplaySection(
    draft: CarSessionSettings,
    update: (CarSessionSettings) -> Unit,
    model: CarUiModel,
) {
    SectionHeader(
        title = "分辨率与画质",
    )
    CarCard(modifier = Modifier.fillMaxWidth()) {
        // 「分辨率缩放」和「自定义分辨率」已从设置页拿掉，固定 1.0x、不用自定义像素。
        CarSettingSlider(
            title = "帧率",
            value = draft.fps.toFloat(),
            valueRange = CarSessionSettings.MIN_FPS.toFloat()..CarSessionSettings.MAX_FPS.toFloat(),
            steps = (CarSessionSettings.MAX_FPS - CarSessionSettings.MIN_FPS) / CarSessionSettings.FPS_STEP - 1,
            valueLabel = "${draft.fps} fps",
            onValueChange = {
                val snapped = (it / CarSessionSettings.FPS_STEP).toInt() * CarSessionSettings.FPS_STEP
                update(draft.copy(fps = snapped.coerceIn(CarSessionSettings.MIN_FPS, CarSessionSettings.MAX_FPS)))
            },
        )
        CarRowDivider()
        CarSettingSlider(
                title = "参考物理长度",
                value = draft.widthPhysicalMm.toFloat(),
                valueRange = CarSessionSettings.MIN_WIDTH_PHYSICAL_MM.toFloat()..CarSessionSettings.MAX_WIDTH_PHYSICAL_MM.toFloat(),
                steps = (CarSessionSettings.MAX_WIDTH_PHYSICAL_MM - CarSessionSettings.MIN_WIDTH_PHYSICAL_MM) /
                    CarSessionSettings.WIDTH_PHYSICAL_MM_STEP - 1,
                valueLabel = "${draft.widthPhysicalMm} mm",
                onValueChange = {
                    val snapped = (it / CarSessionSettings.WIDTH_PHYSICAL_MM_STEP).toInt() *
                        CarSessionSettings.WIDTH_PHYSICAL_MM_STEP
                    update(
                        draft.copy(
                            widthPhysicalMm = snapped.coerceIn(
                                CarSessionSettings.MIN_WIDTH_PHYSICAL_MM,
                                CarSessionSettings.MAX_WIDTH_PHYSICAL_MM,
                            ),
                        ),
                    )
                },
            )
            CarRowDivider()
            CarSettingChoice(
                title = "物理尺寸基准",
                options = listOf(CarPhysicalSizeBasis.WIDEST_WIDTH, CarPhysicalSizeBasis.LONGEST_HEIGHT),
                selected = draft.physicalSizeBasis,
                onSelect = { update(draft.copy(physicalSizeBasis = it)) },
                label = {
                    if (it == CarPhysicalSizeBasis.WIDEST_WIDTH) "按底部长度" else "按顶部高度"
                },
            )
    }

    SectionHeader(title = "编码与解码")
    CarCard(modifier = Modifier.fillMaxWidth()) {
        CarSettingSwitch(
            title = "启用 HEVC (H.265)",
            checked = draft.hevc,
            onCheckedChange = { update(draft.copy(hevc = it)) },
        )
        CarRowDivider()
        CarSettingSwitch(
            title = "HEVC 使用软解",
            checked = draft.hevcSoftwareDecoder,
            onCheckedChange = { update(draft.copy(hevcSoftwareDecoder = it)) },
            enabled = draft.hevc,
        )
    }

    // 「画面延展到状态栏下」留在系统栏这张卡里：它决定画面怎么铺，
    // 不是安全区数值。主屏可拖的框在下面单独一节（`mainSafeArea`），
    // 仪表框仍在「仪表导航 → 导航安全区」，两块矩形互不影响。
    //
    // ⚠️ 「全屏」是这一节的上位开关：开着时「隐藏顶部栏 / 隐藏底部栏」被它接管
    // （置灰而不是隐藏 —— 隐藏会让用户以为设置丢了，置灰能看出"被谁接管了"）。
    SectionHeader(
        title = "系统栏与画面",
    )
    CarCard(modifier = Modifier.fillMaxWidth()) {
        CarSettingSwitch(
            title = "隐藏顶部栏",
            checked = draft.hideTopBar,
            onCheckedChange = { update(draft.copy(hideTopBar = it)) },
            enabled = !draft.fullscreen,
        )
        // 「画面延展到状态栏下」不再露出。字段保留，默认关。
        // ⚠️ 2026-10-11（PATCH-0096）：「画面绘制到安全区外」**从这里删掉了**。
        //
        // 它原来在本分区与「仪表导航 → 画面与安全区」**各渲染一次**，绑的是同一个
        // `CarSessionSettings.safeAreaDrawOutside`。同一个开关出现在两个分区，用户在
        // 这边改完、去那边发现也变了，只会以为见鬼 —— 车主口径「只留仪表导航」。
        //
        // 归属也本该在那边：它管的是"iPhone 允不允许把内容画到仪表安全区之外"，
        // 与同一张卡里的画框是同一件事的两面；而本分区管的是**主屏**的系统栏避让。
    }

    MainSafeAreaGroup(draft = draft, update = update, model = model)

}

/**
 * 自定义分辨率的三个输入框（宽 / 高 / DPI）。
 *
 * ## ⚠️ 为什么用本地文本状态，而不是直接绑 draft 的 Int
 *
 * 输入框必须有**编辑中间态**：用户要把 1920 改成 1280，得先删掉再打。
 * 若直接写 `value = draft.customDisplayWidthPx.toString()`，删空的那一刻
 * `toIntOrNull()` 返回 null ⇒ draft 不变 ⇒ 输入框**立刻弹回 1920**，
 * 用户根本删不动。所以这里用本地文本，能解析成数字时才写回 draft；
 * 解析不出来就只留文本（draft 保持上一个合法值）。
 *
 * `remember` 的 key 取 `customDisplayEnabled`：开关切换时重置文本，
 * 避免本地文本与 draft 长期漂移。
 */
@Composable
private fun DisplayCustomFields(
    draft: CarSessionSettings,
    update: (CarSessionSettings) -> Unit,
) {
    var widthText by remember(draft.customDisplayEnabled) {
        mutableStateOf(draft.customDisplayWidthPx.toString())
    }
    var heightText by remember(draft.customDisplayEnabled) {
        mutableStateOf(draft.customDisplayHeightPx.toString())
    }
    var dpiText by remember(draft.customDisplayEnabled) {
        mutableStateOf(draft.customDisplayDpi.toString())
    }
    CarSettingNumberGroup(
        title = "分辨率与密度",
        description = displayCustomHint(draft),
    ) {
        CarNumberField(
            label = "宽度 (px)",
            value = widthText,
            isError = CarCustomDisplay.problemOf(widthText.toIntOrNull()) != null,
            onValueChange = { text ->
                widthText = text
                text.toIntOrNull()?.let { update(draft.copy(customDisplayWidthPx = it)) }
            },
        )
        CarNumberField(
            label = "高度 (px)",
            value = heightText,
            isError = CarCustomDisplay.problemOf(heightText.toIntOrNull()) != null,
            onValueChange = { text ->
                heightText = text
                text.toIntOrNull()?.let { update(draft.copy(customDisplayHeightPx = it)) }
            },
        )
        CarNumberField(
            label = "DPI",
            value = dpiText,
            isError = CarCustomDisplay.dpiProblemOf(dpiText.toIntOrNull()) != null,
            onValueChange = { text ->
                dpiText = text
                text.toIntOrNull()?.let { update(draft.copy(customDisplayDpi = it)) }
            },
        )
    }
}

/**
 * 自定义分辨率那一组的副标题。
 *
 * 三个框各自有红边提示，但用户最想知道的是**合起来到底发出去什么**——
 * 尤其填了奇数（会被取偶）或 DPI 很低（物理尺寸会很大）的时候。
 * 这里把"实际会声明出去的像素与物理尺寸"直接算给用户看。
 */
private fun displayCustomHint(settings: CarSessionSettings): String {
    val width = settings.customDisplayWidthPx
    val height = settings.customDisplayHeightPx
    val dpi = settings.customDisplayDpi
    val resolved = CarCustomDisplay.resolve(width, height, dpi)
    val lines = mutableListOf<String>()
    CarCustomDisplay.problemOf(width)?.let {
        lines += "⚠️ " + CarCustomDisplay.describe(it, "宽度", width)
    }
    CarCustomDisplay.problemOf(height)?.let {
        lines += "⚠️ " + CarCustomDisplay.describe(it, "高度", height)
    }
    CarCustomDisplay.dpiProblemOf(dpi)?.let {
        lines += "⚠️ " + CarCustomDisplay.describeDpi(it, dpi)
    }
    if (resolved.widthPx != width || resolved.heightPx != height) {
        lines += "⚠️ 宽高必须是 2 的倍数，已取偶为 ${resolved.widthPx}x${resolved.heightPx}。"
    }
    lines += "将声明 ${resolved.widthPx}x${resolved.heightPx}，物理尺寸 " +
        "${resolved.widthMm}x${resolved.heightMm} mm（由 DPI 换算，iPhone 用它决定界面大小）。"
    lines += "若超过车机解码器上限，会自动压到上限以内（实际值见诊断包）。"
    return lines.joinToString("\n")
}

// -------------------------------------------------------------------- 音频分区

@Composable
private fun AudioSection(
    draft: CarSessionSettings,
    update: (CarSessionSettings) -> Unit,
    model: CarUiModel,
) {

    SectionHeader(title = "麦克风")
    CarCard(modifier = Modifier.fillMaxWidth()) {
        CarSettingSwitch(
            title = "使用车机麦克风",
            checked = draft.microphone,
            onCheckedChange = { update(draft.copy(microphone = it)) },
        )
    }

    SectionHeader(
        title = "音频通道（排查用）",
    )
    CarCard(modifier = Modifier.fillMaxWidth()) {
        CarSettingSwitch(
            title = "使用无损传输",
            checked = draft.higherQualityMedia,
            onCheckedChange = { update(draft.copy(higherQualityMedia = it)) },
            description = "实验性：请求102媒体流使用LPCM，不保证手机支持或音质提升。切换后需重连；无声或异常时关闭并重连，恢复AAC-LC。",
        )
        CarRowDivider()
        // 出厂 0 = 与没有这两个设置完全一致，所以「试错」是可回退的：
        // 试遍都不出声音，调回 0 即回到原始行为，不会留下一个更差的现场。
        CarSettingSwitch(
            title = "导航声道焦点",
            checked = draft.navigationAudioFocusEnabled,
            onCheckedChange = { update(draft.copy(navigationAudioFocusEnabled = it)) },
            description = "导航播报时通过按键或旋钮调节导航音量",
        )
        CarRowDivider()
        // 缓冲与流号治的是两件事：流号决定「声音进哪条总线」，缓冲决定
        // 「链路抖动时会不会断」。车机上这两个毛病常常同时出现。
        MediaAudioBufferRow(
            millis = draft.mediaAudioBufferMillis,
            onMillisChange = { update(draft.copy(mediaAudioBufferMillis = it)) },
        )
    }
    CurrentAudioStreamsGroup(model)
}

@Composable
private fun CurrentAudioStreamsGroup(model: CarUiModel) {
    SectionHeader(title = "当前音频流规格", subtitle = "只读：显示当前连接实际收流／麦克风上行规格，不是声明上限，也不代表DAC输出规格。")
    CarCard(modifier = Modifier.fillMaxWidth()) {
        val streams = model.activeAudioStreams
        val groups = listOf("音乐", "Siri", "导航／提示", "通话", "麦克风", "其他")
        groups.forEachIndexed { index, label ->
            if (index > 0) CarRowDivider()
            val matching = streams.filter { stream ->
                val route = when {
                    stream.microphone -> "麦克风"
                    stream.audioType.equals("media", true) -> "音乐"
                    stream.audioType.equals("speechrecognition", true) -> "Siri"
                    stream.audioType.lowercase() in listOf("telephony", "ringtone", "ring") -> "通话"
                    stream.audioType.lowercase() in listOf("default", "compatibility", "alert") -> "导航／提示"
                    else -> "其他"
                }
                route == label
            }
            CarSettingInfo(title = label, value = matching.takeIf { it.isNotEmpty() }
                ?.joinToString("\n") { it.description() } ?: "未协商／当前无活动流")
        }
    }
}

/**
 * 一条音频流选择行：滑杆 0~20，0 显示为「自动」。
 *
 * 用滑杆而不是下拉：车机上手指操作为主，滑杆的点击目标更大；而且 21 个档位
 * 用下拉要滚动，滑杆一眼看得到两端。
 */
@Composable
private fun AudioChannelRow(
    title: String,
    channel: Int,
    onChannelChange: (Int) -> Unit,
    hint: String? = null,
    range: IntRange = CarSessionSettings.AUDIO_CHANNEL_RANGE,
    lynk01Channel: Int? = null,
) {
    val lynk01 = lynk01Channel != null && channel == lynk01Channel
    CarSettingSlider(
        title = title,
        description = buildString {
            append(
                when {
                    channel == 0 -> "当前：自动（按系统 usage 路由，与出厂行为一致）。"
                    lynk01 -> "当前：领克01专用。"
                    else -> "当前：车机流号 $channel。"
                },
            )
            // 推荐值提示只对导航声道给（媒体流号各车机差异太大，不值得猜）。
            if (!hint.isNullOrBlank()) {
                append('\n')
                append(hint)
            }
        },
        value = channel.toFloat(),
        onValueChange = { onChannelChange(it.roundToInt()) },
        valueRange = range.first.toFloat()..range.last.toFloat(),
        steps = range.last - range.first - 1,
        valueLabel = when {
            channel == 0 -> "自动"
            lynk01 -> "领克01专用"
            else -> channel.toString()
        },
    )
}

/**
 * 媒体缓冲时长选择行：200～500毫秒，每50毫秒一档。
 *
 * 只作用于媒体流（音乐/播客）。通话、Siri、导航提示音保持小缓冲不动——
 * 它们要的是「及时」，加大缓冲只会更迟钝。
 *
 * 通过统一档位表保存与校验，默认仍为300毫秒。
 */
@Composable
private fun MediaAudioBufferRow(
    millis: Int,
    onMillisChange: (Int) -> Unit,
) {
    val presets = CarSessionSettings.AUDIO_BUFFER_PRESETS
    val index = presets.indexOf(millis).takeIf { it >= 0 } ?: 0
    CarSettingSlider(
        title = "媒体缓冲时长",
        value = index.toFloat(),
        onValueChange = { onMillisChange(presets[it.roundToInt().coerceIn(0, presets.lastIndex)]) },
        valueRange = 0f..(presets.lastIndex).toFloat(),
        steps = presets.size - 2,
        valueLabel = "${presets[index]} 毫秒",
    )
}

// -------------------------------------------------------------------- 输入分区

@Composable
private fun InputSection(
    draft: CarSessionSettings,
    update: (CarSessionSettings) -> Unit,
) {
    SectionHeader(
        title = "使用方向盘按键控制媒体",
    )
    CarCard(modifier = Modifier.fillMaxWidth()) {
        CarSettingSwitch(
            title = "转发方向盘按键",
            checked = draft.forwardSteeringWheelToCarPlay,
            onCheckedChange = { update(draft.copy(forwardSteeringWheelToCarPlay = it)) },
        )
    }
    // ⚠️ 2026-10-06 删除「不受本开关影响」整块（音量键 / 触摸屏）。
    //
    // 那两条是**反向说明**——"这里有些东西本应用不碰"。用户看到一张卡片、
    // 里面却没有可操作的控件，只会问"那它为什么要占一屏位置"。
    // 车机上屏幕和注意力都是稀缺的，不产生动作的内容就是噪音。
    //
    // 信息本身没丢，仍完整保留在文档里（`ARCHITECTURE.md` 音量键不转发也不消费、
    // `car/platform-ecarx/README.md` 音量键语义未定、触摸屏由上游管理）。
    // 真需要解释时去文档，不要在设置页里做科普。
}

// -------------------------------------------------------------------- 媒体分区

// -------------------------------------------------------------------- 熄火暂停

/**
 * 熄火自动暂停（2026-10-18）。
 *
 * 开关**不进** [CarSessionSettings]：它不参与与 iPhone 的握手，改完要立即生效，
 * 走"保存设置 → 重连"那条链反而会让用户以为开关没反应。所以这里直接读写
 * 设置文件里的 `power_pause_enabled`，并订阅它的变化（同一实例被别处改动时界面也能跟上）。
 */
// -------------------------------------------------------------------- 定位分区

@Composable
private fun LocationSection(
    draft: CarSessionSettings,
    update: (CarSessionSettings) -> Unit,
) {
    SectionHeader(
        title = "定位",
    )
    CarCard(modifier = Modifier.fillMaxWidth()) {
        CarSettingSwitch(
            title = "向 iPhone 上报位置",
            checked = draft.locationReporting,
            onCheckedChange = { update(draft.copy(locationReporting = it)) },
        )
    }
}

// ---------------------------------------------------------------- 车机配置分区

/**
 * 车机配置（2026-10-11 分区合并）。
 *
 * 收纳五个**车机侧功能开关**：音频 / 方向盘按键 / 媒体卡片 / 熄火暂停 / 位置共享。
 *
 * ## 为什么合并
 *
 * 这四个原本各占一个分区，而其中三个分区里只有一个开关（位置共享 1 个、
 * 媒体卡片 1 个、方向盘 1 个）—— 索引页上四条只值 4 个开关，用户要滚一屏
 * 才看得全。它们的共同点是"**本机对车机能力的能力开关**"，与「显示」
 * 「连接」这类有独立设置组的页面不是一回事，收成一页更好找。
 *
 * ## 为什么直接串四个 Section 而不重写
 *
 * 各节自己的 `SectionHeader` + `CarCard` 已经是完整的视觉单元，拼接后
 * 观感与原来在独立分区页里一致（节间距由 `SectionHeader` 的上边距承担）。
 * 重写只会引入新的排版问题，收益为零。
 */
@Composable
private fun VehicleSection(
    draft: CarSessionSettings,
    update: (CarSessionSettings) -> Unit,
    model: CarUiModel,
    actions: CarUiActions,
) {
    SectionHeader(title = "启动")
    CarCard(modifier = Modifier.fillMaxWidth()) {
        CarSettingSwitch(title = "开机自动启动", checked = draft.autoStartOnBoot,
            onCheckedChange = { update(draft.copy(autoStartOnBoot = it)) })
    }
    AudioSection(draft, update, model)
    Spacer(Modifier.size(CarTheme.dimens.spaceL))
    InputSection(draft, update)
    Spacer(Modifier.size(CarTheme.dimens.spaceL))
    LocationSection(draft, update)
    VehicleReadSafetySection(onReconnect = { actions.applySettings(draft, reconnect = true) })
}

// -------------------------------------------------------------------- 启动分区

@Composable
private fun StartupSection(
    draft: CarSessionSettings,
    update: (CarSessionSettings) -> Unit,
) {
    SectionHeader(
        title = "启动与后台",
    )
    CarCard(modifier = Modifier.fillMaxWidth()) {
        CarSettingSwitch(
            title = "开机自动启动",
            checked = draft.autoStartOnBoot,
            onCheckedChange = { update(draft.copy(autoStartOnBoot = it)) },
        )
    }

    // ⚠️ 2026-10-08（斌哥口径）：**启动性能优化独立成版块**。
    //
    // 原文：「**启动性能优化，在设置页面独立版块，不要放在设置-连接-热点服务版块**」。
    //
    // 旧位置在「连接 → 热点服务」卡内（与两个热点开关、建立 / 自愈按钮同卡）。
    // 那个位置有两处不妥：
    //   ① 归属错：`pm compile` 是**性能优化**，与热点/ADB 没有任何关系，
    //      用户要在"热点服务"里找性能开关，语义上先迷一次路；
    //   ② 版式土：热点服务卡当时并列 2 个开关 + 建立 + 自愈 + 性能执行按钮，
    //      一屏塞五六个等宽大控件，没有主次（与第 2 条"按钮排版优化"同源）。
    //
    // 现在搬到「启动」分区（标题「启动与后台」→ 与"启动后执行"天然同节），
    // **自成一个 `SectionHeader` + `CarCard`**：一个语义一块版面，与
    // 「危险操作」独立成区的做法一致。
    //
    // ⚠️ 2026-10-08：原本与开关同卡的「立即执行一次性能优化」按钮已按车主口径
    // 移除（原话「移除设置-启动-立即执行一次性性能优化」）。本卡现在只有这一个开关，
    // 所以**不再需要 `CarRowDivider()`**——它本来是为了分隔"开关行 / 按钮行"。
}

// --------------------------------------------------------------- 仪表导航分区

/**
 * 仪表导航（副屏投屏 + 仪表内容 + 画面与安全区）。
 *
 * ## 为什么单独一个分区（2026-10-10 补回）
 *
 * 仪表副屏是**会话级**能力：要在与 iPhone 建栈时就声明第二块屏
 * （CarPlay cluster stream 111），"投不投 / 投到哪块屏"必须握手前定下来。
 * 投屏能力（`CarSessionSettings.projectionEnabled`）在 `CarAppController` 与
 * 平台层都已接好，**但设置页一直没有入口** ——
 * `ClusterSection` 在上游是独立页面（原版是
 * `GeelyClusterSettingsActivity`「仪表地图与安全区域」），本工程迁移时丢了入口。
 * 后果：用户只能吃默认值（默认关闭）⇒ 仪表永远不投，而且无处可改。
 *
 * ## 投屏目标（2026-10-20 起固定）
 *
 * 吉利 OneOS 仪表面板固定为 displayId=2。手动选屏已移除，设置页只显示只读说明；
 * 固定屏不在场时由 `CarAppController` 回退自动挑选，避免影响其他车型。
 *
 * ## 本页管六件事
 *
 * 1. **投屏开关** —— `projectionEnabled`，打开立即投屏；未连接时等待会话就绪。
 *    投屏目标固定优先为仪表屏 displayId=2。
 * 2. **手动显隐** —— CarPlay画面三指左滑打开、右滑收起；导航自动显隐已退役。
 * 3. **投到哪块屏** —— 固定为仪表面板 displayId=2，只读展示，不提供手动选择；
 * 4. **让 iPhone 画什么** —— `clusterNavigationCard` / `clusterMapLayoutTrial` /
 *    `clusterAlignment`（仪表内容）；
 * 5. **画面怎么摆** —— `safeArea`（核心安全区）/ `safeAreaDrawOutside` /
 *    `clusterFlip180`（画面朝向）；
 * 6. **HUD 推不推** —— `hudGuidanceEnabled`（PATCH-0114，2026-10-13），
 *    开关 iPhone → 车机 hudbridge 服务那条**转向提示**通道。它**不接管画面**，
 *    与上面五件事全部无关（关掉 HUD 不影响投屏，反之亦然）。
 *
 * ## 布局：**单列垂直流**（PATCH-0097，2026-10-11 车主口径）
 *
 * 本页曾短暂排成左右两列（PATCH-0096），真车上用下来反而**更难读**：
 * 列高天然不等（左列候选 2~4 行、右列三个开关），眼睛找不到"下一项在哪"；
 * 阅读顺序被打断（"左看一半、跳回右上看一半"）；同族被列宽拆开。
 *
 * 现在改回**单列一条线**，按**车主的决策顺序**排，每块只回答一个问题：
 *
 * | # | 分组 | 回答的问题 | 生效方式 |
 * | --- | --- | --- | --- |
 * | 1 | 仪表信息 | 全屏模式与异常提示 | 只读 |
 * | 2 | 投屏与副屏 | 投不投、投到哪块屏 | 开关即时生效 |
 * | 3 | 仪表内容 | 让 iPhone 画什么 | 立即生效 |
 * | 4 | 显示时机 | 什么时候显示 | 立即生效 |
 * | 5 | 画面 | 画面朝向与安全区外绘制 | 立即生效 |
 * | 6 | 导航安全区 | 画面摆在哪块区域 | 立即生效 |
 * | 7 | HUD 导航推送 | 仪表上的转向提示推不推、通没通 | **立即生效**（PATCH-0114） |
 *
 * ⚠️ 「画面」与「导航安全区」**不合并**：前者是"窗口里的画面怎么摆"（朝向 /
 * 允不允许画到区外），后者是"可画区域是哪个矩形"，判据与出口完全不同
 * （前者进 `applySettings` 灌呈现层，后者进 `safeArea` 归一化下发 iPhone）。
 * 合并会重演 PATCH-0094 那次"改一个地方、另一个地方也跟着变"的困惑。
 *
 * 顺带修掉三件事：① **状态置顶**（原来叫「当前状态」、压在最大那张卡的最后一行，
 * 要滚到底才知道投上没有）；② **投屏目标只读展示**；③ **语义分组**
 * （"要重连的"与"立即生效的"拆开，用户能一眼看出改哪个要重连）。
 *
 * ## 生效时机
 *
 * 前两项都是会话级：改完点「重连生效」。投屏目标固定为 displayId=2，不再由
 * `clusterDisplayId` 决定，也不会因为旧配置值而把画面切到别的屏。
 *
 * ⚠️ **画面朝向（翻转）与仪表内容例外**：翻转只改窗口里的画面朝向，不参与向
 * iPhone 的声明（尺寸/安全区/帧率都不变）⇒ `applySettings` 直接灌给呈现层
 * **就地生效，不重连**；仪表内容三项由 `updateSettings` 重推 URL，同样不用重连。
 *
 * ⚠️ 两个对话框（`AlertDialog`）留在函数末尾：它们是**独立窗口**，
 * 挂在组合树的哪一层都不参与上面的单列布局。
 */
@Composable
private fun ClusterSection(
    draft: CarSessionSettings,
    update: (CarSessionSettings) -> Unit,
    model: CarUiModel,
    actions: CarUiActions,
) {
    // 仪表内容对齐的选择框（2026-10-11）。改动只落在草稿上，
    // 自动保存时提交 —— 但内容三项由 `CarPlaySessionBridge.updateSettings`
    // 立即重推 URL，不需要真的重连。
    var showAlignmentPicker by remember { mutableStateOf(false) }

    val dimens = CarTheme.dimens

    // ------------------------------------------------ 单列垂直流（PATCH-0097）
    //
    // ## 为什么放弃左右两列（2026-10-11 车主口径）
    //
    // PATCH-0096 把本页排成左右两列（左：投到哪 / 右：画什么），当时想的是"横屏
    // 1920×1080、单列留白太多"。真车上用下来，两列反而**更难读**：
    //
    // ① **列高天然不等**——左列的候选列表有 2~4 行，右列是三个开关，纵向错位后
    //    眼睛找不到"下一项在哪"；
    // ② **阅读顺序被打断**——单列的视线是"从上往下一条线"，两列要"左看一半、
    //    跳回右上看一半"，车主反馈"太乱"就是这里；
    // ③ **同族被拆开**——「显示时机」原本与「仪表内容」同属"让 iPhone 画什么"，
    //    却因为列宽限制挤进了右列，看起来像内容的一部分。
    //
    // 现在按**车主的决策顺序**排成一条线，每块只回答一个问题：
    //
    // | # | 分组 | 回答的问题 | 生效方式 |
    // | --- | --- | --- | --- |
    // | 1 | 仪表信息 | 全屏模式与异常提示 | 只读 |
    // | 2 | 投屏与副屏 | 投不投、投到哪块屏 | 开关即时生效 |
    // | 3 | 仪表内容 | 让 iPhone 画什么 | 立即生效 |
    // | 4 | 显示时机 | 什么时候显示 | 立即生效 |
    // | 5 | 画面 | 画面朝向与安全区外绘制 | 立即生效 |
    // | 6 | 导航安全区 | 画面摆在哪块区域 | 立即生效 |
    //
    // ⚠️ 2026-10-18：原第 7 块「HUD 导航推送」**已迁到独立的设置分区「HUD」**
    // （`CarSettingsSection.HUD`）。它本来就是另一条通道（iPhone → 车机自带地图服务），
    // 挤在投屏页最后会被当成投屏的一部分；迁走后本页只剩仪表副屏相关项。
    //
    // ⚠️ 「画面」与「导航安全区」**不合并**：前者是"窗口里的画面怎么摆"
    // （朝向 / 允不允许画到区外），后者是"可画区域是哪个矩形"，判据与出口完全不同
    // （前者进 `applySettings` 灌呈现层，后者进 `safeArea` 归一化下发 iPhone）。
    // 合并会重演 PATCH-0094 那次"改一个地方、另一个地方也跟着变"的困惑。
    // 2026-10-23 精简：原「图层状态（只读）」整块不再展示
    // （主题层/导航层/窗口挂载/首帧属于内部信息，普通车主不需要）。
    // ClusterStatusGroup 函数与数据源保留，改回展示只需取消下面注释。
    // ClusterStatusGroup(model = model)

    ClusterTargetGroup(draft = draft, update = update, actions = actions)

    IndependentInstrumentCardsGroup()

    ClusterContentGroup(
        draft = draft,
        update = update,
        onOpenAlignment = { showAlignmentPicker = true },
    )

    // 画面朝向 + 安全区外绘制：单列下不再需要整宽横排的特殊处理，
    // 直接作为独立一块排在「显示时机」与「导航安全区」之间。

    ClusterSafeAreaGroup(draft = draft, update = update, model = model)

    // 地图缩放跟在安全区画框后面（车主口径 2026-10-18）：都在回答"画面摆在哪"。

    // 2026-10-23：导航画面缩放（本机）从「仪表主题 → 圆表调节」整块搬到本页。
    // 它作用的是导航 TextureView，本来就属于导航；放在地图缩放旁边，
    // 用户能一眼分清“让 iPhone 缩”和“本机缩”。

    // 2026-10-23 精简：内部测试/调试项收进「高级设置」，默认折叠。
    // 底层字段（clusterMapLayoutTrial / safeAreaDrawOutside）与下发逻辑完全不变。

    // 仪表内容对齐：只改草稿，保存时提交。
    if (showAlignmentPicker) {
        CarChoiceDialog(
            title = "仪表内容对齐",
            message = "转向卡 / 导航信息在仪表上的对齐方式。只在「显示 iPhone 导航卡片」" +
                "或「仅地图布局控制（测试）」打开时才会发给 iPhone；" +
                "具体效果由手机侧导航应用决定。",
            options = CAR_CLUSTER_ALIGNMENT_LABELS.indices.toList(),
            selected = draft.clusterAlignment
                .coerceIn(0, CAR_CLUSTER_ALIGNMENT_LABELS.lastIndex),
            onSelect = { index ->
                update(draft.copy(clusterAlignment = index))
                showAlignmentPicker = false
            },
            onDismiss = { showAlignmentPicker = false },
            label = { index -> carClusterAlignmentLabel(index) },
        )
    }

}

/**
 * 仪表图层状态（只读，置顶）。
 *
 * 2026-10-23（B2/B4）：
 * - 原来的「显示模式」三态控件已拆成主题/导航两个开关，这里改为只读回显，
 *   让用户看见主题层、导航层与窗口各自的状态；
 * - 黑屏提示只在导航层**确实要求显示**时才出现。P0 之后“主题保留、导航收起”
 *   本来就会让窗口挂着且没有首帧，那是正常状态，不是故障。
 */
@Composable
private fun ClusterStatusGroup(model: CarUiModel) {
    val layers = model.clusterLayers
    val state = model.projectionState
    // 全屏模式守护的只读状态（阶段 2）：第一项是两列右值，第二项（失败原因）
    // 作为小字脚注 —— `CarSettingInfo` 没有"行下脚注"这个槽位。
    val fullMap = clusterFullMapDetail(model.clusterFullMap)
    SectionHeader(
        title = "图层状态（只读）",
        subtitle = "主题层与导航层各自独立；这里显示仪表窗口此刻的真实状态。",
    )
    CarCard(modifier = Modifier.fillMaxWidth()) {
        CarSettingInfo(
            title = "图层组合",
            value = clusterLayerCombinationText(layers),
            description = "由两个开关分别控制：上方管主题，下方管导航",
        )
        CarRowDivider()
        CarSettingInfo(
            title = "主题层",
            value = clusterThemeLayerText(layers),
        )
        CarRowDivider()
        CarSettingInfo(
            title = "导航层",
            value = clusterNavigationLayerText(layers),
            description = if (layers.themeVisible && !layers.navigationRequested) {
                "导航层已收起；仪表窗口保留给主题层。"
            } else {
                null
            },
        )
        CarRowDivider()
        CarSettingInfo(
            title = "仪表窗口",
            value = clusterWindowText(layers),
        )
    }

    SectionHeader(
        title = "仪表导航状态",
        subtitle = "本页以下各项只影响 iPhone 投到仪表的导航画面，不影响「仪表主题」的圆表与配色。",
    )
    CarCard(modifier = Modifier.fillMaxWidth()) {
        CarSettingInfo(
            title = "全屏模式",
            value = fullMap.first,
        )
        if (fullMap.second != null) {
            Text(
                text = fullMap.second.orEmpty(),
                style = CarTheme.type.bodySmall,
                color = CarTheme.colors.textTertiary,
                modifier = Modifier.padding(
                    start = CarTheme.dimens.spaceM,
                    end = CarTheme.dimens.spaceM,
                    bottom = CarTheme.dimens.spaceS,
                ),
            )
        }
        // ---------------------------------------------------------------- 黑屏提示
        //
        // 2026-10-10 现场：选了 displayId=2（1920×1080 的 HDMI 屏）后**仪表黑屏**。
        // 状态机那一行只会写「已请求投屏，等待 iPhone 首帧」——用户盯着黑屏，
        // 看不出"是还没连上"还是"手机侧没有出画面"。
        //
        // 这里把「窗口已挂上、但一直没有画面」这种**具体**状态单独摆出来。
        // 投屏目标已固定，不再引导用户换屏；只提示检查连接与手机侧画面情况。
        //
        // ⚠️ 2026-10-23（B4）：必须先问“导航层是不是真的要求显示”。
        // P0 之后「双层 + 关投屏」或「仅仪表主题」都会留下一个没有首帧的窗口，
        // 那是主题层正在使用的正常状态，绝不能再报成黑屏。
        if (clusterNavigationWarningVisible(
                status = layers,
                windowAttached = state.attached,
                firstFrameShown = state.firstFrame,
            )
        ) {
            CarRowDivider()
            CarSettingInfo(
                title = "窗上挂着，但没有画面",
                value = "等待手机画面",
                description = "导航层要求显示，窗口也已经挂到仪表屏上，可是这条视频流一直没出画面 ——" +
                    "请检查仪表 HDMI 连接、手机导航画面；仍无画面就到「诊断」采集日志。" +
                    "主题层不受影响，会继续显示。",
            )
        }
    }
}

/**
 * 投屏与副屏：保留两个用户开关，固定目标只读展示。
 *
 * 2026-10-20 起投屏固定优先使用仪表面板 displayId=2，手动候选列表、
 * 选定屏告警、重新检测和逐块试屏入口全部移除。
 */
@Composable
private fun ClusterTargetGroup(
    draft: CarSessionSettings,
    update: (CarSessionSettings) -> Unit,
    actions: CarUiActions,
) {
    // 2026-10-23：导航开关就是原来的「投屏到仪表」；显示状态由
    // “模式允许导航 + 投屏开关”共同决定，读法统一走四象限映射。
    val navigationVisible = ClusterLayerSwitchMapping.read(
        mode = draft.clusterLayerMode(),
        projectionEnabled = draft.projectionEnabled,
    ).navigationVisible

    SectionHeader(
        title = "投屏与副屏",
        subtitle = "只影响导航画面",
    )
    CarCard(modifier = Modifier.fillMaxWidth()) {
        CarSettingSwitch(
            title = "仪表盘投屏",
            enabled = !draft.immersiveNavigationEnabled,
            checked = navigationVisible,
            onCheckedChange = { enabled ->
                val previous = draft
                val previousMode = previous.clusterLayerMode()
                // 主题那一半在「仪表主题」页，这里只改导航这一半；
                // 两个开关通过四象限映射落回现有 mode + projectionEnabled。
                val target = ClusterLayerSwitchMapping.write(
                    themeVisible = previousMode.showsTheme,
                    navigationVisible = enabled,
                )
                // 先切模式：关闭时后面 hideClusterProjection() 的
                // “主题是否仍需窗口”判据必须看到新模式。
                previous.setClusterLayerMode(target.mode)
                val next = previous.copy(projectionEnabled = target.projectionEnabled)
                update(next)
                if (!enabled) {
                    actions.setClusterVisible(false)
                }
                runCatching {
                    actions.applySettings(next, reconnect = false)
                }.onSuccess {
                    if (enabled) actions.setClusterVisible(true)
                }.onFailure {
                    // 提交失败就把刚切的图层模式退回去。
                    previous.setClusterLayerMode(previousMode)
                    update(previous)
                }
            },
            description = "打开立即投屏，不再跟随导航开始或结束；CarPlay画面中三指左滑投屏、右滑收起。",
        )
        // 用户明确取消并隐藏导航自动投屏；旧持久化字段不再控制运行时。
        if (draft.projectionEnabled) {
            CarRowDivider()
            CarButton(text = "重新连接", onClick = { actions.applySettings(draft, reconnect = true) },
                variant = CarButtonVariant.Secondary, modifier = Modifier.fillMaxWidth())
        }
        // 2026-10-23 精简：投屏屏幕固定为「仪表屏 #2」，属技术信息，不再展示。
        // 固定 Display 2 的配置逻辑（ClusterTargetGroup 上游的 displayId 选择）一行未改。
    }
}

/**
 * 仪表内容（让 iPhone 画什么）。
 *
 * 原版 `GeelyClusterContent` 用三个字段决定副屏 URL：
 *   导航卡片 > 仅地图布局 > 纯地图（`when` 顺序即优先级）。
 * 车机侧只负责把 URL 告诉 iPhone，画成什么样由 iPhone 的导航应用决定。
 *
 * 这三项**都是"立即生效"**（`CarPlaySessionBridge.updateSettings` 重推 URL），
 * 与上面「投屏与副屏」那个会话级总闸不是一类，所以单独成组。
 */
@Composable
private fun ClusterContentGroup(
    draft: CarSessionSettings,
    update: (CarSessionSettings) -> Unit,
    onOpenAlignment: () -> Unit,
) {
    SectionHeader(
        title = "仪表内容",
    )
    CarCard(modifier = Modifier.fillMaxWidth()) {
        CarSettingSwitch(
            title = "仪表显示苹果原生导航信息卡片",
            checked = draft.clusterNavigationCard,
            onCheckedChange = { update(draft.copy(clusterNavigationCard = it)) },
        )
        // 2026-10-23 精简：「仅地图布局控制（测试）」移入页面底部「高级设置」折叠区。
        CarRowDivider()
        CarButton(
            text = "仪表内容对齐 · ${carClusterAlignmentLabel(draft.clusterAlignment)}",
            onClick = onOpenAlignment,
            variant = CarButtonVariant.Secondary,
            modifier = Modifier.fillMaxWidth(),
            // 与原版一致：两个内容开关都关时 URL 不带 `maneuverLayout`，对齐无从生效。
            enabled = draft.clusterNavigationCard || draft.clusterMapLayoutTrial,
        )
    }

}

@Composable
private fun ImmersiveNavigationSettings(draft: CarSessionSettings, update: (CarSessionSettings) -> Unit) {
    var showAlignmentPicker by remember { mutableStateOf(false) }
    SectionHeader(title = "沉浸式导航", subtitle = "主屏111 · 1920×1080 / 60fps声明 · 与仪表投屏互斥")
    CarCard(modifier = Modifier.fillMaxWidth()) {
        CarSettingSwitch(title = "沉浸式导航", checked = draft.immersiveNavigationEnabled,
            onCheckedChange = { update(draft.copy(immersiveNavigationEnabled = it)) },
            description = "保存后重连生效。开启后禁用仪表投屏及HUD视频输出；三指左滑显示111、右滑返回110。111不向手机转发触控。")
    }
    SectionHeader(title = "沉浸导航内容")
    ImmersiveGlassAppearanceSettings(draft, update)
    CarCard(modifier = Modifier.fillMaxWidth()) {
        CarSettingSwitch(
            title = "液态玻璃导航、手机状态与音乐卡片",
            checked = draft.immersiveGlassCardsEnabled,
            onCheckedChange = { update(draft.copy(immersiveGlassCardsEnabled = it)) },
            description = "仅在沉浸111画面显示。导航550×300、手机卡550×100、音乐550×252物理像素，左距60、顶距75、卡片间隔35；手机卡三等分居中显示信号图标、电池图标与百分比、电流A数。全部卡片需要至少797像素高度，小屏只显示完整放得下的上方卡片。三卡共用颜色与透明度，音乐与帆书上报同源，不更改苹果原生卡片或音频设置。",
        )
    }
    CarCard(modifier = Modifier.fillMaxWidth()) {
        CarSettingSwitch(title = "沉浸导航显示苹果原生卡片", checked = draft.immersiveNavigationCardEnabled,
            onCheckedChange = { update(draft.copy(immersiveNavigationCardEnabled = it)) },
            description = "只影响主屏111，不修改仪表投屏的同名设置。重连生效。")
        CarRowDivider()
        CarButton(text = "沉浸导航内容对齐 · ${carClusterAlignmentLabel(draft.immersiveNavigationAlignment)}",
            onClick = { showAlignmentPicker = true }, variant = CarButtonVariant.Secondary,
            modifier = Modifier.fillMaxWidth(),
            enabled = draft.immersiveNavigationCardEnabled || draft.immersiveNavigationMapLayout)
    }
    CarCard(modifier = Modifier.fillMaxWidth()) {
        CarSettingSwitch(title = "沉浸窗口仅地图布局", checked = draft.immersiveNavigationMapLayout,
            onCheckedChange = { update(draft.copy(immersiveNavigationMapLayout = it)) },
            description = "关闭原生卡片时，向iPhone请求带对齐参数的地图页面；不在车机侧裁剪，效果由手机导航应用决定。重连生效。")
        CarSettingSwitch(title = "沉浸窗口绘制到安全区外", checked = draft.immersiveNavigationDrawOutside,
            onCheckedChange = { update(draft.copy(immersiveNavigationDrawOutside = it)) })
        SafeAreaFrameEditor(area = draft.immersiveNavigationSafeArea,
            canvasWidthPx = 1920, canvasHeightPx = 1080,
            onAreaChange = { update(draft.copy(immersiveNavigationSafeArea = it)) })
        val area = draft.immersiveNavigationSafeArea
        var xText by remember(area) { mutableStateOf(if (area.isEmpty) "0" else area.x.toString()) }
        var yText by remember(area) { mutableStateOf(if (area.isEmpty) "72" else area.y.toString()) }
        var wText by remember(area) { mutableStateOf(if (area.isEmpty) "1920" else area.width.toString()) }
        var hText by remember(area) { mutableStateOf(if (area.isEmpty) "880" else area.height.toString()) }
        val numeric = immersiveSafeAreaFromText(xText, yText, wText, hText)
        CarSettingNumberGroup(title = "安全区数值", description = "沉浸窗口坐标：1920×1080 px；应用后重连生效，与仪表安全区独立。") {
            CarNumberField("左 X", xText, { xText = it.filter(Char::isDigit) })
            CarNumberField("上 Y", yText, { yText = it.filter(Char::isDigit) })
            CarNumberField("宽 W", wText, { wText = it.filter(Char::isDigit) })
            CarNumberField("高 H", hText, { hText = it.filter(Char::isDigit) })
        }
        CarButton(text = "应用沉浸安全区数值", enabled = numeric != null,
            onClick = { numeric?.let { update(draft.copy(immersiveNavigationSafeArea = it)) } },
            variant = CarButtonVariant.Secondary, modifier = Modifier.fillMaxWidth())
        CarButton(text = "恢复沉浸窗口默认安全区",
            onClick = { update(draft.copy(immersiveNavigationSafeArea = CarSettingsSafeArea(0, 72, 1920, 880))) })
    }
    if (showAlignmentPicker) {
        CarChoiceDialog(title = "沉浸导航内容对齐", message = "与仪表内容使用相同的协议方向；具体效果由手机导航应用决定，重连生效。",
            options = CAR_CLUSTER_ALIGNMENT_LABELS.indices.toList(),
            selected = draft.immersiveNavigationAlignment.coerceIn(0, CAR_CLUSTER_ALIGNMENT_LABELS.lastIndex),
            onSelect = { index -> update(draft.copy(immersiveNavigationAlignment = index)); showAlignmentPicker = false },
            onDismiss = { showAlignmentPicker = false }, label = { carClusterAlignmentLabel(it) })
    }
}

/**
 * 画面与安全区（画面怎么摆）。
 *
 * ## 为什么这三项同组
 *
 * ① **画面旋转 180°**（`clusterFlip180`）：副屏倒装（HDMI 屏装进原仪表壳体、
 *    上下颠倒）时把画面转回来。车机 `DisplayManager` 报的 rotation 恒为 0 ——
 *    倒装是物理的、系统不知道，只能在呈现层转。只改朝向、不改声明 ⇒ 勾上就生效。
 * ② **核心安全区**（`safeArea`）：面板上哪块区域能用。不声明的话 iPhone 会把
 *    导航铺满整块面板、压到车机自己的仪表饰板。
 * ③ **画面绘制到安全区外**（`safeAreaDrawOutside`）：iPhone 允不允许画到安全区
 *    之外。与 ② 是同一件事的两面，所以放在同一张卡里。
 *
 * ⚠️ 2026-10-11（PATCH-0094）：② 与 ③ **只作用于仪表副屏**（车主口径
 * 「仪表导航 的安全区只限副屏」）。主屏不读它们 —— 主屏只有"沉浸模式下
 * 自动避让状态栏"一个来源，见 `CarPlayConfigBuilder.airPlayConfig`。
 *
 * ⚠️ 2026-10-11（PATCH-0096）：③ 原来在「显示 → 系统栏与画面」里**又渲染了一次**
 * （绑的是同一个 `safeAreaDrawOutside`）。同一个开关出现在两个分区，用户改了
 * 这边那边也跟着变，会以为见鬼 —— 现在只留这里一处。
 */
@Composable
private fun ClusterFrameGroup(
    draft: CarSessionSettings,
    update: (CarSessionSettings) -> Unit,
    model: CarUiModel,
) {
    SectionHeader(
        title = "画面",
        subtitle = "只影响导航画面 · 朝向与是否允许画到安全区外。",
    )
    CarCard(modifier = Modifier.fillMaxWidth()) {
        CarSettingSwitch(
            title = "画面旋转 180°",
            checked = draft.clusterFlip180,
            onCheckedChange = { update(draft.copy(clusterFlip180 = it)) },
        )
        // 2026-10-23 精简：「画面绘制到安全区外」移入页面底部「高级设置」折叠区。
    }

}

/**
 * 导航安全区：**整宽单独一块**（2026-10-11 车主口径）。
 *
 * ## 为什么不放进两列布局
 *
 * 页面主体是左右两列（左：投到哪 / 右：画什么、怎么摆），列宽各占一半。
 * 画框留在列里就只有半屏宽，而它是最吃宽度的控件 —— 拖角手柄在两端，
 * 半宽下够不着、比例也看不出来。行外整宽后横屏左右都能拖。
 *
 * ⚠️ 只决定"放在哪、多宽"，`SafeAreaFrame` 内部的判据
 * （画布来源、矩形模型、"拿不到画布怎么办"）一律不动，见其 KDoc。
 */
@Composable
private fun ClusterSafeAreaGroup(
    draft: CarSessionSettings,
    update: (CarSessionSettings) -> Unit,
    model: CarUiModel,
) {
    SectionHeader(
        title = "导航安全区",
        subtitle = "只影响导航画面 · iPhone 把导航内容画在哪块区域。",
    )
    CarCard(modifier = Modifier.fillMaxWidth()) {
        SafeAreaFrame(
            area = draft.safeArea,
            onAreaChange = { update(draft.copy(safeArea = it)) },
            draft = draft,
            model = model,
            reportedDescription = "只影响仪表副屏，主屏不受影响。它只约束 iPhone 把仪表界面画在哪里，" +
                "不裁剪画面本身。可在「诊断」里重置。",
            referenceDescription = { canvasWidthPx, canvasHeightPx ->
                "还没连过 CarPlay，上面按参考画布 ${canvasWidthPx}×${canvasHeightPx} 预览；" +
                    "连上后会用真实画布，框按比例换算。只影响仪表副屏，主屏不受影响。"
            },
        )
    }
}

/**
 * 主屏安全区。四个数字手填，不拖框。
 *
 * 左、上是起点，宽、高是安全区总大小，单位都是像素。宽或高为 0 / 留空 = 不声明，
 * 主屏回落到「画面延展到状态栏下」。和仪表那份 `safeArea` 分开存。
 * `/info` 在握手时定，改完要重连。
 */
@Composable
private fun MainSafeAreaGroup(
    draft: CarSessionSettings,
    update: (CarSessionSettings) -> Unit,
    model: CarUiModel,
) {
    val area = draft.mainSafeArea
    var leftText by remember { mutableStateOf(if (area.isEmpty) "" else area.x.toString()) }
    var topText by remember { mutableStateOf(if (area.isEmpty) "" else area.y.toString()) }
    var widthText by remember { mutableStateOf(if (area.isEmpty) "" else area.width.toString()) }
    var heightText by remember { mutableStateOf(if (area.isEmpty) "" else area.height.toString()) }

    fun commit(
        leftRaw: String = leftText,
        topRaw: String = topText,
        widthRaw: String = widthText,
        heightRaw: String = heightText,
    ) {
        val left = leftRaw.toIntOrNull()
        val top = topRaw.toIntOrNull()
        val width = widthRaw.toIntOrNull()
        val height = heightRaw.toIntOrNull()
        if (left == null || top == null || width == null || height == null) return
        if (left < 0 || top < 0 || width < 0 || height < 0) return
        val next = if (width == 0 || height == 0) {
            CarSettingsSafeArea()
        } else {
            CarSettingsSafeArea(x = left, y = top, width = width, height = height)
        }
        if (next != draft.mainSafeArea) update(draft.copy(mainSafeArea = next))
    }

    val canvasWidthPx = model.safeAreaCanvasWidthPx
    val canvasHeightPx = model.safeAreaCanvasHeightPx
    val canvasKnown = canvasWidthPx > 0 && canvasHeightPx > 0
    val rightEdge = if (area.isEmpty) null else area.x + area.width
    val bottomEdge = if (area.isEmpty) null else area.y + area.height
    val overflows = canvasKnown && !area.isEmpty && (
        area.x + area.width > canvasWidthPx || area.y + area.height > canvasHeightPx
        )

    SectionHeader(
        title = "主屏安全区",
        subtitle = "只影响主屏 CarPlay。手填像素，不拖框。没填总大小时，仍按「画面延展到状态栏下」避让顶部。",
    )
    CarCard(modifier = Modifier.fillMaxWidth()) {
        CarSettingNumberGroup(
            title = "起点",
            description = "安全区左上角在画布上的像素位置。",
        ) {
            CarNumberField(
                label = "左 (px)",
                value = leftText,
                onValueChange = { text ->
                    leftText = text.filter { it.isDigit() }
                    commit(leftRaw = leftText)
                },
            )
            CarNumberField(
                label = "上 (px)",
                value = topText,
                onValueChange = { text ->
                    topText = text.filter { it.isDigit() }
                    commit(topRaw = topText)
                },
            )
        }
        CarSettingNumberGroup(
            title = "总大小",
            description = "安全区的宽和高，单位像素。填 0 或留空表示不声明。",
        ) {
            CarNumberField(
                label = "宽 (px)",
                value = widthText,
                isError = overflows,
                onValueChange = { text ->
                    widthText = text.filter { it.isDigit() }
                    commit(widthRaw = widthText)
                },
            )
            CarNumberField(
                label = "高 (px)",
                value = heightText,
                isError = overflows,
                onValueChange = { text ->
                    heightText = text.filter { it.isDigit() }
                    commit(heightRaw = heightText)
                },
            )
        }
        CarSettingInfo(
            title = "总大小",
            value = if (area.isEmpty) {
                "不声明（整屏，顶部仍看沉浸开关）"
            } else {
                "${area.width}×${area.height} px"
            },
            description = buildString {
                if (canvasKnown) {
                    append("当前主屏画布 ${canvasWidthPx}×${canvasHeightPx} px。")
                } else {
                    append("还没连过 CarPlay，先按你填的像素保存，连上后收到真实画布内。")
                }
                if (!area.isEmpty && rightEdge != null && bottomEdge != null) {
                    append(" 四边：左 ${area.x}、上 ${area.y}、右 $rightEdge、下 $bottomEdge。")
                }
                if (overflows) {
                    append(" 超出画布的部分会在发给 iPhone 时收到画布里面。")
                }
                append(" 只影响主屏，仪表导航卡片和仪表安全区不受影响。改完需要重连。")
            },
        )
        if (!area.isEmpty) {
            Spacer(Modifier.size(CarTheme.dimens.spaceS))
            CarButton(
                text = "清除（改回整屏可用）",
                onClick = {
                    leftText = ""
                    topText = ""
                    widthText = ""
                    heightText = ""
                    update(draft.copy(mainSafeArea = CarSettingsSafeArea()))
                },
                variant = CarButtonVariant.Secondary,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 安全区「画框」区（PATCH-0080，车主口径：按矩形模型 + 画框改）。
 *
 * ## 为什么需要画布尺寸
 *
 * 画框的坐标空间 = `safeArea` 的坐标空间 = 主屏上报给 iPhone 的画布
 * （换算在 `CarClusterSafeAreaPolicy.resolve`：先 `toInsets(画布)`、
 * 再 `scaledTo(画布 → 副屏面板)`；**不是**给主屏用的，PATCH-0094）。
 * 不知道它，拖出来的框与真实值就不成比例。值来自 `CarUiModel.safeAreaCanvasWidthPx`。
 *
 * ⚠️ 拿不到时（还没进过画面页 ⇒ 画布从未上报）**不画框、不写值**，只给一句
 * "先去连一次"的提示 —— 绝不拿一个猜的尺寸去写用户数据。这是 2026-10-11 那次
 * "safeArea 高度为负 ⇒ iPhone 拆会话"事故的直接教训：**宁可什么都不做，
 * 也不要产出看起来合法、实际自相矛盾的坐标**。
 *
 * ## 为什么不用数字框（原来那版）
 *
 * 四个数字框（上/下/左/右）要求用户自己做算术，而且它对"top 比面板还高"这类
 * 自相矛盾的值**没有任何约束**。画框存的是矩形，出口一律 `normalized()`
 * ⇒ 结构上不可能发出负宽高。
 */
@Composable
private fun SafeAreaFrame(
    area: CarSettingsSafeArea,
    onAreaChange: (CarSettingsSafeArea) -> Unit,
    draft: CarSessionSettings,
    model: CarUiModel,
    reportedDescription: String,
    referenceDescription: (canvasWidthPx: Int, canvasHeightPx: Int) -> String,
) {
    // 2026-10-11：**没连过也能设**。
    //
    // 原来这里要求 `safeAreaCanvasWidthPx > 0`（= 连过一次 CarPlay 才有），
    // 于是"没检测到设备"时安全区完全无法调整 —— 车主反馈"想先把框画好再上车"做不到。
    //
    // 原版（`GeelyClusterSafeAreaStore` / `SafeAreaEditorView`）存的是**面板空间矩形**，
    // 与有没有连上无关，因此可以离线编辑。这里照同一口径取**参考画布**：
    //   ① 已上报的画布（精确）；
    //   ② 用户自己声明的自定义分辨率（「显示与视频」里填的那两个数）；
    //   ③ 1920×720（副屏核心区默认口径，见 `CarClusterSafeAreaPolicy`）。
    // 画框坐标空间与 `CarPlayConfigBuilder` 的 `safeArea.scaledTo(...)` 同源，
    // 所以用参考画布画出来的框在真机上仍然按比例成立。
    // The instrument content canvas is not the Android HDMI mode. The latter reports
    // 1920x1080, but its lower 360px belongs to the factory HUD. Keep the editor in the
    // same fixed 1920x720 coordinate space used by the instrument presentation and plist.
    val usingReferenceFrame = true
    val canvasWidthPx = DEFAULT_SAFE_AREA_CANVAS_WIDTH_PX
    val canvasHeightPx = DEFAULT_SAFE_AREA_CANVAS_HEIGHT_PX
    if (canvasWidthPx <= 0 || canvasHeightPx <= 0) {
        CarSettingInfo(
            title = "还不能画框",
            value = "—",
            description = "参考画布尺寸不合法（自定义分辨率填成了 0 或负数）。" +
                "到「显示与视频」改一个正常值，或关掉自定义分辨率。",
        )
        return
    }

    val dimens = CarTheme.dimens

    // 未设置（w/h ≤ 0）= 整屏可用 ⇒ 画框显示成"整块面板"，
    // 用户把它拖小，就等于"声明只用这一块"。
    val shown = if (area.isEmpty) {
        CarSettingsSafeArea(x = 0, y = 0, width = canvasWidthPx, height = canvasHeightPx)
    } else {
        area
    }

    Text(
        text = "拖动方框移动；拖四角改大小。",
        style = CarTheme.type.bodySmall,
        color = CarTheme.colors.textTertiary,
        modifier = Modifier.padding(horizontal = dimens.spaceM, vertical = dimens.spaceS),
    )
    SafeAreaFrameEditor(
        area = shown,
        canvasWidthPx = canvasWidthPx,
        canvasHeightPx = canvasHeightPx,
        onAreaChange = onAreaChange,
        modifier = Modifier.padding(horizontal = dimens.spaceM, vertical = dimens.spaceS),
    )

    var leftText by remember(area) { mutableStateOf(if (area.isEmpty) "0" else area.x.toString()) }
    var topText by remember(area) { mutableStateOf(if (area.isEmpty) "0" else area.y.toString()) }
    var widthText by remember(area) { mutableStateOf(if (area.isEmpty) canvasWidthPx.toString() else area.width.toString()) }
    var heightText by remember(area) { mutableStateOf(if (area.isEmpty) canvasHeightPx.toString() else area.height.toString()) }
    fun commitNumeric(
        leftRaw: String = leftText,
        topRaw: String = topText,
        widthRaw: String = widthText,
        heightRaw: String = heightText,
    ) {
        val left = leftRaw.toIntOrNull() ?: return
        val top = topRaw.toIntOrNull() ?: return
        val width = widthRaw.toIntOrNull() ?: return
        val height = heightRaw.toIntOrNull() ?: return
        if (left < 0 || top < 0 || width <= 0 || height <= 0) return
        onAreaChange(CarSettingsSafeArea(left, top, width, height).normalized(canvasWidthPx, canvasHeightPx))
    }
    CarSettingNumberGroup(title = "安全区数值", description = "仪表内容坐标：1920×720 px；下方 HUD 区不参与计算。") {
        CarNumberField("左 X", leftText, { leftText = it.filter(Char::isDigit) })
        CarNumberField("上 Y", topText, { topText = it.filter(Char::isDigit) })
        CarNumberField("宽 W", widthText, { widthText = it.filter(Char::isDigit) })
        CarNumberField("高 H", heightText, { heightText = it.filter(Char::isDigit) })
    }
    CarButton(text = "应用安全区数值", onClick = { commitNumeric() },
        variant = CarButtonVariant.Secondary, modifier = Modifier.fillMaxWidth())

    Spacer(Modifier.size(dimens.spaceS))
    CarSettingInfo(
        title = "当前声明",
        value = if (area.isEmpty) {
            "整屏可用（不声明）"
        } else {
            "${area.width}×${area.height} px" +
                " @ (${area.x}, ${area.y})"
        },
        description = if (usingReferenceFrame) {
            referenceDescription(canvasWidthPx, canvasHeightPx)
        } else {
            reportedDescription
        },
    )

    // 只有"画过框"才给清除入口：未设置时它等于"整屏"，再放一个按钮是噪音。
    if (!area.isEmpty) {
        Spacer(Modifier.size(dimens.spaceS))
        CarButton(
            text = "清除（改回整屏可用）",
            onClick = { onAreaChange(CarSettingsSafeArea()) },
            variant = CarButtonVariant.Secondary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 地图缩放（2026-10-18）。
 *
 * 链路（`CarUiActions.zoomProjectionMap` → app → core-bridge → AirPlaySession）早已存在，
 * 这里只补用户能点到的入口。放在「导航安全区」画框**下面**：两者都在回答
 * "仪表上的画面怎么摆"，缩放是这件事的延续，不与总开关混在一组。
 *
 * 投屏总闸关着时两个按钮置灰 —— 没有仪表地图可缩放，点亮了也只会被忽略。
 */
@Composable
private fun ClusterMapZoomGroup(
    model: CarUiModel,
    actions: CarUiActions,
) {
    val enabled = clusterMapZoomEnabled(model.settings.projectionEnabled)
    SectionHeader(
        title = "地图缩放",
        subtitle = "只影响导航画面 · 缩放投在仪表上的地图；不影响主题，也不影响主屏。",
    )
    CarSettingRow(
        title = "放大 / 缩小",
        enabled = enabled,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(CarTheme.dimens.spaceS)) {
            CarChoiceChip(
                text = "放大",
                selected = false,
                onClick = { actions.zoomProjectionMap(true) },
                enabled = enabled,
            )
            CarChoiceChip(
                text = "缩小",
                selected = false,
                onClick = { actions.zoomProjectionMap(false) },
                enabled = enabled,
            )
        }
    }
    Text(
        text = clusterMapZoomHint(model.settings.projectionEnabled),
        style = CarTheme.type.bodySmall,
        color = CarTheme.colors.textTertiary,
        modifier = Modifier.padding(
            start = CarTheme.dimens.spaceM,
            end = CarTheme.dimens.spaceM,
            bottom = CarTheme.dimens.spaceS,
        ),
    )
}

/**
 * 导航画面缩放（本机）（2026-10-23 从「仪表主题 → 圆表调节」迁入）。
 *
 * 作用对象是仪表副屏的导航 `TextureView`（`GeelyClusterPresentation.applyVideoTransform`）：
 * 只缩放/平移已解码画面，不动主屏、不向 iPhone 发命令、不重连。
 *
 * 与「地图缩放」是两件事：那两个按钮让 iPhone 地图自己缩放，这一个只改本机显示矩阵。
 * 本地 state + `editing` 标志防止上层异步刷新在拖动过程中把旧值灌回来
 * （原来在主题页与圆表共用 `dialEditing`，迁走后必须独立）。
 */
@Composable
private fun ClusterVideoTransformGroup(model: CarUiModel, actions: CarUiActions) {
    val dimens = CarTheme.dimens
    val colors = CarTheme.colors
    var localTransform by remember { mutableStateOf(model.videoTransform) }
    var editing by remember { mutableStateOf(false) }
    LaunchedEffect(model.videoTransform) {
        if (!editing && localTransform != model.videoTransform) {
            localTransform = model.videoTransform
        }
    }

    SectionHeader(
        title = "导航画面缩放（本机）",
        subtitle = "只影响导航画面 · 缩放/平移仪表上的 CarPlay 画面，不影响主题圆表、主屏和手机。",
    )
    CarCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "范围 0.5×–3.0×。这是对已解码画面的本机变换，缩小不会增加地图内容；" +
                "要让手机地图本身缩放，请用上面的「地图缩放」。",
            style = CarTheme.type.bodySmall,
            color = colors.textTertiary,
            modifier = Modifier.padding(
                start = dimens.spaceM,
                end = dimens.spaceM,
                bottom = dimens.spaceS,
            ),
        )
        DialSlider(
            label = "缩放",
            value = localTransform.scale,
            range = ClusterVideoTransform.MIN_SCALE..ClusterVideoTransform.MAX_SCALE,
            onChange = {
                localTransform = ClusterVideoTransform.sanitize(localTransform.copy(scale = it))
                editing = true
                actions.setVideoTransform(localTransform)
            },
            colors = colors,
            format = { "×%.2f".format(it) },
            onValueChangeFinished = { editing = false },
        )
        DialSlider(
            label = "左右",
            value = localTransform.dx,
            range = -ClusterVideoTransform.MAX_OFFSET_X..ClusterVideoTransform.MAX_OFFSET_X,
            onChange = {
                localTransform = ClusterVideoTransform.sanitize(localTransform.copy(dx = it))
                editing = true
                actions.setVideoTransform(localTransform)
            },
            colors = colors,
            format = { "%.0f px".format(it) },
            onValueChangeFinished = { editing = false },
        )
        DialSlider(
            label = "上下",
            value = localTransform.dy,
            range = -ClusterVideoTransform.MAX_OFFSET_Y..ClusterVideoTransform.MAX_OFFSET_Y,
            onChange = {
                localTransform = ClusterVideoTransform.sanitize(localTransform.copy(dy = it))
                editing = true
                actions.setVideoTransform(localTransform)
            },
            colors = colors,
            format = { "%.0f px".format(it) },
            onValueChangeFinished = { editing = false },
        )
        CarRowDivider()
        CarButton(
            text = "恢复默认",
            onClick = {
                localTransform = ClusterVideoTransform.DEFAULT
                editing = false
                actions.setVideoTransform(ClusterVideoTransform.DEFAULT)
            },
            variant = CarButtonVariant.Secondary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// --------------------------------------------------------------- HUD 分区

/**
 * HUD 独立版块（2026-10-18）。
 *
 * ## 为什么从「仪表导航」里迁出来
 *
 * 那一页讲的全是**仪表副屏投屏**（向 iPhone 声明第二块屏 → 往那块屏开窗出画）。
 * HUD 是另一条通道：不接管任何画面，只把 iPhone 解出的转向提示推给车机自带的地图服务，
 * 由车机自己在抬头显示/仪表上画卡片。两者互不依赖 —— 关掉 HUD 不影响投屏，反之亦然。
 * 原先它挤在投屏页最后一块，用户要翻过整页才能拨到，也容易被当成投屏的一部分。
 *
 * ## 内容（自上而下）
 *
 * 1. 开关（沿用 `hudGuidanceEnabled`，默认开）；
 * 2. 只读状态卡：目标服务 / 连接 / 失败原因（有才显示）/ 计数 / 最近一条；
 * 3. 说明脚注。
 *
 * 所有文案出自 `HudStatusText.kt` 的纯函数（见那边的 KDoc），本文件只管排版。
 * 前置条件不满足（本机没有 HUD 通道）时开关**置灰而不隐藏** —— 项目纪律：
 * 前置条件不满足要常驻 + 置灰，并给出"为什么"。
 */
@Composable
private fun HudSection(
    draft: CarSessionSettings,
    update: (CarSessionSettings) -> Unit,
    model: CarUiModel,
) {
    SectionHeader(
        title = "导航提示",
        subtitle = "由车机自带的地图服务绘制，不接管任何画面。",
    )
    // 函数名沿用 `ClusterHudGroup`：它是这次迁移前就存在的实现，签名与调用方式未变，
    // 只是调用点从 ClusterSection 挪到了本页（见 SettingsScreen 的静态契约测试）。
    ClusterHudGroup(draft = draft, update = update, model = model)
    Spacer(Modifier.height(CarTheme.dimens.spaceM))
    // 2026-10-22：HUD **地图画面**投屏（与上面的转向提示是两条独立的链）。
    HudProjectionCard(draft = draft, update = update)
    Text(
        text = "导航提示由车机自带的地图服务显示；地图投屏由 DBPlay 自己把 CarPlay " +
            "画面裁剪后画到 1024×274 的 HUD 屏上。两者互不影响。",
        style = CarTheme.type.bodySmall,
        color = CarTheme.colors.textTertiary,
        modifier = Modifier.padding(
            start = CarTheme.dimens.spaceM,
            end = CarTheme.dimens.spaceM,
            top = CarTheme.dimens.spaceS,
        ),
    )
}

/**
 * HUD 地图投屏卡片（2026-10-22）。
 *
 * ## 为什么裁剪必须可调
 *
 * HUD 是 **1024×274 ≈ 3.74:1** 的超宽条；CarPlay 是 **1920×1080 ≈ 1.78:1**。
 * 直接缩放会把地图压扁一半以上（导航箭头会变成横向椭圆），所以必须先裁剪。
 * 「全宽」预设保留任务书给定值，「居中」预设给出严格 3.74:1 的无黑边裁剪；
 * 三个滑块再让用户按实车视角微调。
 *
 * ## 实时预览
 *
 * 上面是 16:9 源画面 + 裁剪框，下面是 1024×274 的效果条：
 * - 深色区域 = 被裁掉；
 * - 效果条里的色块 = 实际会显示的画面范围；`FIT` 时两侧留黑边，`FILL` 时铺满。
 *
 * 预览只是示意图，没有真实地图纹理（设置页不持有解码帧）；
 * 最终效果仍以 HUD 实车为准（`visibleOnHud` 只能肉眼确认）。
 */
@Composable
private fun HudProjectionCard(
    draft: CarSessionSettings,
    update: (CarSessionSettings) -> Unit,
) {
    val colors = CarTheme.colors
    CarCard(modifier = Modifier.fillMaxWidth()) {
        CarSettingSwitch(
            title = "HUD 地图投屏",
            checked = draft.hudProjectionEnabled,
            onCheckedChange = { update(draft.copy(hudProjectionEnabled = it)) },
            description = "把地图投到 HUD 屏" +
                "需要仪表副屏在位：HUD 与仪表共享同一条 111 流。",
        )
        if (!draft.hudProjectionEnabled) {
            Text(
                text = "打开后可调裁剪：HUD 是 3.74:1 的超宽条，CarPlay 是 1.78:1，" +
                    "不裁剪会严重压扁。",
                style = CarTheme.type.bodySmall,
                color = colors.textTertiary,
                modifier = Modifier.padding(
                    start = CarTheme.dimens.spaceM,
                    end = CarTheme.dimens.spaceM,
                    bottom = CarTheme.dimens.spaceM,
                ),
            )
            return@CarCard
        }

        CarRowDivider()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = CarTheme.dimens.spaceM, vertical = CarTheme.dimens.spaceS),
            verticalArrangement = Arrangement.spacedBy(CarTheme.dimens.spaceS),
        ) {
            Text(
                text = "取景范围",
                style = CarTheme.type.titleSmall,
                color = colors.textSecondary,
            )
            val activePreset = HudCropPreset.entries.firstOrNull {
                draft.hudCrop.approximatelyEquals(it.crop)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(CarTheme.dimens.spaceS)) {
                HudCropPreset.entries.forEach { preset ->
                    CarChoiceChip(
                        text = preset.displayName(),
                        selected = activePreset == preset,
                        onClick = { update(draft.copy(hudCrop = preset.crop)) },
                    )
                }
            }
            HudCropPreview(crop = draft.hudCrop, mode = draft.hudScaleMode)
            Text(
                text = hudCropSummary(draft.hudCrop, draft.hudScaleMode),
                style = CarTheme.type.bodySmall,
                color = colors.textTertiary,
            )
        }
        CarRowDivider()
        CarSettingSlider(
            title = "左右",
            value = draft.hudCrop.centerX,
            onValueChange = { update(draft.copy(hudCrop = draft.hudCrop.centerAt(it, draft.hudCrop.centerY))) },
            valueRange = 0f..1f,
            valueLabel = "%.2f".format(draft.hudCrop.centerX),
            description = "裁剪区水平中心（0 = 最左，1 = 最右）",
        )
        CarSettingSlider(
            title = "上下",
            value = draft.hudCrop.centerY,
            onValueChange = { update(draft.copy(hudCrop = draft.hudCrop.centerAt(draft.hudCrop.centerX, it))) },
            valueRange = 0f..1f,
            valueLabel = "%.2f".format(draft.hudCrop.centerY),
            description = "导航信息多在地图偏下；任务书默认 0.68",
        )
        CarSettingSlider(
            title = "大小",
            value = draft.hudCrop.widthFraction,
            onValueChange = { update(draft.copy(hudCrop = draft.hudCrop.scaledToWidth(it))) },
            valueRange = 0.05f..1f,
            valueLabel = "%.0f%%".format(draft.hudCrop.widthFraction * 100f),
            description = "数值越小 = 裁得越窄 = 画面放得越大",
        )
        CarRowDivider()
        Text(
            text = "宽高比不匹配时",
            style = CarTheme.type.titleSmall,
            color = colors.textSecondary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(CarTheme.dimens.spaceS)) {
            listOf(
                HudScaleMode.FILL to "铺满（裁边）",
                HudScaleMode.FIT to "完整（黑边）",
                HudScaleMode.STRETCH to "拉伸（会压扁）",
            ).forEach { (mode, label) ->
                CarChoiceChip(
                    text = label,
                    selected = draft.hudScaleMode == mode,
                    onClick = { update(draft.copy(hudScaleMode = mode)) },
                )
            }
        }
        Text(
            text = "默认「铺满」最适合超宽条屏；「完整」保证不丢画面，但左右会有黑边。",
            style = CarTheme.type.bodySmall,
            color = colors.textTertiary,
        )
    }
}

private fun HudCropPreset.displayName(): String = when (this) {
    HudCropPreset.FULL_WIDTH -> "全宽"
    HudCropPreset.CENTERED -> "居中"
}

/** 一行几何说明：裁剪宽度、自身宽高比、以及到 HUD 之后是否会有黑边。 */
internal fun hudCropSummary(crop: HudCropSpec, mode: HudScaleMode): String {
    val width = crop.rightFraction - crop.leftFraction
    val height = crop.bottomFraction - crop.topFraction
    val aspect = HudCropGeometry.HUD_ASPECT
    val cropAspect = (width * HudCropGeometry.SOURCE_WIDTH_PIXELS) /
        (height * HudCropGeometry.SOURCE_HEIGHT_PIXELS)
    val modeText = when (mode) {
        HudScaleMode.FILL -> "铺满（超出部分裁掉）"
        HudScaleMode.FIT -> if (cropAspect >= aspect) "完整（上下黑边）" else "完整（左右黑边）"
        HudScaleMode.STRETCH -> "拉伸"
    }
    return "裁剪宽 %.0f%% · 源宽高比 %.2f:1 · HUD %.2f:1 · %s"
        .format(width * 100f, cropAspect, aspect, modeText)
}

/**
 * 纯 Compose 裁剪预览：上图是 16:9 源画面与裁剪框，下图是 1024×274 效果条。
 *
 * 不引用任何平台层类型 —— 几何来自 `:car:api` 的 [HudCropGeometry]，
 * 与真正下发到 `TextureView.setTransform` 的是**同一份公式**。
 */
@Composable
private fun HudCropPreview(crop: HudCropSpec, mode: HudScaleMode) {
    val colors = CarTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(CarTheme.dimens.spaceXs)) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(
                    HudCropGeometry.SOURCE_WIDTH_PIXELS.toFloat() /
                        HudCropGeometry.SOURCE_HEIGHT_PIXELS,
                )
                .clip(CarTheme.shapes.medium),
        ) {
            // 源画面底色（示意）。
            drawRect(color = colors.surfaceVariant)
            // 地图纹理示意：几条斜线，方便看出裁剪后仍保留同一块内容。
            val grid = Color.White.copy(alpha = 0.12f)
            var x = -size.height
            while (x < size.width) {
                drawLine(
                    color = grid,
                    start = Offset(x, 0f),
                    end = Offset(x + size.height, size.height),
                    strokeWidth = 2f,
                )
                x += size.height / 4f
            }
            val left = crop.leftFraction * size.width
            val top = crop.topFraction * size.height
            val right = crop.rightFraction * size.width
            val bottom = crop.bottomFraction * size.height
            val shade = Color.Black.copy(alpha = 0.55f)
            // 四边遮罩（不用 BlendMode.Clear，省得被硬件层合成规则坑）。
            drawRect(shade, Offset(0f, 0f), Size(size.width, top))
            drawRect(shade, Offset(0f, bottom), Size(size.width, size.height - bottom))
            drawRect(shade, Offset(0f, top), Size(left, bottom - top))
            drawRect(shade, Offset(right, top), Size(size.width - right, bottom - top))
            drawRect(
                color = colors.primary,
                topLeft = Offset(left, top),
                size = Size(right - left, bottom - top),
                style = Stroke(width = 4f),
            )
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(
                    HudCropGeometry.HUD_WIDTH_PIXELS.toFloat() /
                        HudCropGeometry.HUD_HEIGHT_PIXELS,
                )
                .clip(CarTheme.shapes.small)
                .background(Color.Black),
        ) {
            val plan = HudCropGeometry.plan(
                sourceWidth = HudCropGeometry.SOURCE_WIDTH_PIXELS,
                sourceHeight = HudCropGeometry.SOURCE_HEIGHT_PIXELS,
                viewWidth = size.width.toInt().coerceAtLeast(1),
                viewHeight = size.height.toInt().coerceAtLeast(1),
                crop = crop,
                mode = mode,
            )
            val left = (size.width - plan.destinationWidth) / 2f
            val top = (size.height - plan.destinationHeight) / 2f
            drawRect(
                color = colors.primaryContainer,
                topLeft = Offset(left, top),
                size = Size(plan.destinationWidth, plan.destinationHeight),
            )
            // 画面中心线：直观看出裁剪是否居中。
            drawLine(
                color = colors.primary,
                start = Offset(size.width / 2f, 0f),
                end = Offset(size.width / 2f, size.height),
                strokeWidth = 2f,
            )
        }
    }
}

@Composable
private fun ClusterHudGroup(
    draft: CarSessionSettings,
    update: (CarSessionSettings) -> Unit,
    model: CarUiModel,
) {
    val hud = model.hud
    CarCard(modifier = Modifier.fillMaxWidth()) {
        CarSettingSwitch(
            title = "原车仪表卡片",
            checked = draft.hudGuidanceEnabled,
            onCheckedChange = { update(draft.copy(hudGuidanceEnabled = it)) },
            // 本车机没有 HUD 通道时置灰不隐藏（见 HudSection 的 KDoc）。
            // Preference remains editable before connecting a phone or resolving
            // the platform. Availability is a status, not permission to save it.
            enabled = true,
            description = "通过饺子踏板把转向、距离、道路和可用车道交给原车导航组件。",
        )
    }

    Spacer(Modifier.size(CarTheme.dimens.spaceM))

    SectionHeader(
        title = "原车仪表卡片状态",
        subtitle = "只读。显示 DBPLAY 输入与踏板受理状态；受理不代表原车已经绘制。",
    )
    CarCard(modifier = Modifier.fillMaxWidth()) {
        CarSettingInfo(
            title = "目标服务",
            value = hudTargetText(hud),
        )
        CarRowDivider()
        CarSettingInfo(
            title = "连接",
            value = hudConnectionText(hud),
            description = hudConnectionHint(hud),
        )
        // 失败行只在真失败过时出现：常态下多挂一行"没有失败"是纯噪声，
        // 而这一行存在的意义恰恰是"出问题时它必须在"。
        hudFailureText(hud)?.let { failure ->
            CarRowDivider()
            CarSettingInfo(
                title = "失败原因",
                value = "—",
                description = failure,
            )
        }
        CarRowDivider()
        CarSettingInfo(
            title = "推送计数",
            value = hudCountersText(hud),
            description = hudCounterHint(hud),
        )
        CarRowDivider()
        CarSettingInfo(
            title = "最近一条",
            value = hudLatestText(hud),
        )
    }
}

/** 连接那一行的小字：只说"现在是什么状态、要不要紧"，失败原因由单独一行承载。 */
private fun hudConnectionHint(hud: CarHudStatus): String? = when {
    !hud.supported -> "当前未报告原车仪表卡片接口；开关可保存，连接后请检查平台识别和踏板服务。"
    hud.available -> "已连上原车仪表卡片的踏板服务；最终显示仍取决于原车导航组件。"
    hud.connecting -> "正在连接车机的地图服务…"
    else -> "还没连上车机的地图服务。不影响 CarPlay 本身。"
}

/**
 * 计数那一行的小字：把三个数字读成一句结论（现场排障顺序）。
 *
 * 这里刻意直接读 `hud.receivedFrames` / `pushedFrames` / `droppedFrames`：
 * 三个数字单独摆着时用户不知道哪个算正常，而"收到但没受理"与"根本没收到"
 * 的处置完全不同。
 */
private fun hudCounterHint(hud: CarHudStatus): String? = when {
    !hud.supported -> null
    hud.receivedFrames == 0L -> "还没收到导航帧；当时没在导航时这是正常的。"
    hud.pushedFrames == 0L -> "已收到导航更新，桥接尚未受理；请检查连接状态及失败原因。"
    hud.droppedFrames > 0L -> "仅合并一秒内的重复更新；有效内容至少每秒重发，距离、路名、终点、车道变化立即推送。此计数不是视频掉帧。"
    else -> null
}

/**
 * 未连过 CarPlay 时画框用的**参考画布**（= 副屏核心区默认口径，与原版缺省一致）。
 *
 * 只在 `CarUiModel.safeAreaCanvasWidthPx/HeightPx` 还是 0（从未上报过）时启用；
 * 一旦真实画布上报，就以真实值为准。
 *
 * ⚠️ 2026-10-11（车主口径）：「拖动框画布改成 1920×720」。
 * 原为 1920×1080（整块仪表面板），但副屏核心区默认只有上方 720px
 * （见 `CarClusterSafeAreaPolicy.DEFAULT_HEIGHT_PIXELS`）—— 画框用 1080 会让用户
 * 拖出来的框**与真正发给 iPhone 的核心区不成比例**（框看着只有 2/3 高，实际已是整屏）。
 * 两处必须同源：画框空间 = `safeArea` 空间 = `CarClusterSafeAreaPolicy` 的默认空间。
 *
 * 数值刻意取副屏核心区口径，而不是面板物理尺寸 —— 用户在这个空间里画的框，
 * 换算到真机上正好落在核心区内，不需要自己减掉下方 360px 的油量/车速表。
 */
private const val DEFAULT_SAFE_AREA_CANVAS_WIDTH_PX = 1920
private const val DEFAULT_SAFE_AREA_CANVAS_HEIGHT_PX = 720

/**
 * 身份与外观。
 *
 * 这一节在上游位于 `Identity & appearance`，而**之前二开版完全没有暴露**——
 * 这四个值决定 iPhone 的 CarPlay 设备列表与 AirPlay 菜单里显示成什么，
 * 用户在车机上改不了就只能去改配置文件。
 */
@Composable
private fun IdentitySection(
    draft: CarSessionSettings,
    update: (CarSessionSettings) -> Unit,
    model: CarUiModel,
    actions: CarUiActions,
) {
    // 2026-10-23 设计系统重构：界面明暗改为用户可选（原先是恒浅色，
    // 因为车机 uiMode 不随原厂日夜切换，isSystemInDarkTheme() 恒 false）。
    SectionHeader(title = "界面外观")
    CarCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            listOf(
                ClusterAppearanceMode.DARK to "深色",
                ClusterAppearanceMode.LIGHT to "浅色",
                ClusterAppearanceMode.SYSTEM to "跟随系统",
            ).forEach { (mode, label) ->
                CarChoiceChip(
                    text = label,
                    selected = draft.uiAppearanceMode == mode,
                    onClick = { update(draft.copy(uiAppearanceMode = mode)) },
                )
            }
        }
        CarRowDivider()
        CarSettingInfo(
            title = "当前",
            value = when (draft.uiAppearanceMode) {
                ClusterAppearanceMode.DARK -> "深色"
                ClusterAppearanceMode.LIGHT -> "浅色"
                ClusterAppearanceMode.SYSTEM -> "跟随系统"
            },
            description = if (draft.uiAppearanceMode == ClusterAppearanceMode.SYSTEM) {
                "随Android系统明暗外观切换；原厂未更新系统外观时按系统当前值显示。"
            } else {
                null
            },
        )
    }

    SectionHeader(
        title = "AirPlay 图标",
    )
    CarCard(modifier = Modifier.fillMaxWidth()) {
        AirPlayIconPreviewRow(status = model.airPlayIcon)
        CarRowDivider()
        CarSettingAction(
            title = "选择图片",
            value = if (model.airPlayIcon.imported) "更换" else "选择文件",
            onClick = actions::requestAirPlayIconImport,
            // 上游只接受正方形，非正方形的图会被**静默丢弃**。
            // 二开版不让用户去学这条规范：实现侧直接居中裁剪。
        )
        if (model.airPlayIcon.imported) {
            CarRowDivider()
            CarSettingAction(
                title = "恢复默认图标",
                value = null,
                onClick = actions::clearAirPlayIcon,
            )
        }
    }

    SectionHeader(title = "驾驶侧")
    CarCard(modifier = Modifier.fillMaxWidth()) {
        CarSettingChoice(
            title = "驾驶侧",
            options = listOf(CarDrivingSide.LEFT, CarDrivingSide.RIGHT),
            selected = draft.drivingSide,
            onSelect = { update(draft.copy(drivingSide = it)) },
            label = { if (it == CarDrivingSide.LEFT) "左舵" else "右舵" },
        )
    }
}

/**
 * AirPlay 图标的预览行。
 *
 * 为什么不复用 `CarSettingInfo`：那一行右侧只能放文本，而这里要展示的是**图本身**——
 * 图标换没换、换得对不对，只有看图才知道。多写一个 `Row` 换来的是"所见即所得"。
 *
 * 底衬 `surfaceVariant` 是必需的：图标源图多半带透明通道，
 * 直接画在白色卡片上会看不出边界。
 */
@Composable
private fun AirPlayIconPreviewRow(status: CarAirPlayIconStatus) {
    val dimens = CarTheme.dimens
    val colors = CarTheme.colors
    val bitmap = remember(status.previewPng) {
        status.previewPng?.let { bytes ->
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = dimens.cardPadding, vertical = dimens.spaceM),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimens.spaceM),
    ) {
        Box(
            modifier = Modifier
                .size(dimens.iconXl * 2)
                .clip(CarTheme.shapes.medium)
                .background(colors.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "当前 AirPlay 图标",
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = CarIcons.Image,
                    contentDescription = null,
                    tint = colors.textDisabled,
                    modifier = Modifier.size(dimens.iconL),
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(dimens.spaceXs)) {
            Text(
                text = "当前图标",
                style = CarTheme.type.titleMedium,
                color = colors.textPrimary,
            )
            Text(
                text = status.sourceLabel.ifEmpty { "不可用" },
                style = CarTheme.type.bodyLarge,
                color = colors.textSecondary,
            )
            if (status.detail.isNotEmpty()) {
                Text(
                    text = status.detail,
                    style = CarTheme.type.bodySmall,
                    color = colors.textTertiary,
                )
            }
        }
    }
}

// -------------------------------------------------------------------- 诊断分区

/**
 * 诊断页「会话日志」显示的最大条数。
 *
 * 取 20 是**版面与信息量的折中**：环形缓冲本身有几百条，
 * 全铺出来在 1024x600 的车机上要滚十几屏，反而把上面的结论挤没了；
 * 而排障时真正有用的是**最近这一次连接过程**，20 条足够覆盖。
 * 需要完整日志时走开发者页的「采集并上传」。
 */
private const val LOG_TAIL_SIZE = 20

/**
 * 诊断。
 *
 * 对应上游的 `Diagnostics`：调试日志开关 + 一大片只读回显。
 * 二开版把原来散在「显示」「输入」里的只读信息（当前生效值、触摸与按键）
 * 全部收拢到这里——上游也是这个组织方式，
 * 排查时只需要看一页，不用在几个分区之间来回找。
 *
 * ## 排布顺序按"现场提问顺序"来，不按"上游字段顺序"
 *
 * 1. **现在卡在哪**（连接诊断）——进来的人十有八九是"连不上"，
 *    所以第一眼必须是会话状态与失败原因，不是版本号；
 * 2. **ADB 通没通、卡在第几步**（ADB 检测）——无线链路的失败点几乎全在车机侧，
 *    而 ADB 是唯一能把车机侧状态读出来的通道；
 * 3. **刚才发生了什么**（会话日志）——前两块给结论，这一块给过程；
 * 4. 之后才是版本 / 维护 / 硬件能力 / 当前生效值这些"背景信息"。
 *
 * ⚠️ 这三块里的数据**早就在模型里了**（[CarUiModel.session] / [CarUiModel.adbHotspot]
 * / [CarUiModel.logs]），但以前只渲染在开发者页或画面页浮层上——
 * 而画面页一旦没连上就是空状态，浮层反而看不到，那正是最需要日志的时候。
 * 诊断页要做的只是把它们**摆到用户找得到的地方**，不引入新的数据链路。
 */
@Composable
private fun DiagnosticsSection(
    draft: CarSessionSettings,
    model: CarUiModel,
    actions: CarUiActions,
    update: (CarSessionSettings) -> Unit,
    onResetSafeArea: () -> Unit,
    onClearPairings: () -> Unit,
) {
    // 「清除配对」的确认框状态。动作本身已从「维护」搬到下面的「设备」节
    // （原「设备」独立页的内容），确认框仍在本函数末尾统一渲染。
    var confirmClearPairings by remember { mutableStateOf(false) }

    // ------------------------------------------------------------ 连接诊断

    SectionHeader(
        title = "连接诊断",
    )
    // 失败原因顶到最上面：这就是需求里的「异常状态提示」。
    // 只有真的失败过（errorMessage 非空）才出现，正常情况下不占版面。
    model.session.errorMessage?.takeIf { it.isNotBlank() }?.let { message ->
        NoticeCard(text = message)
        Spacer(Modifier.size(CarTheme.dimens.spaceM))
    }
    CarCard(modifier = Modifier.fillMaxWidth()) {
        CarSettingInfo(
            title = "当前状态",
            value = model.session.title,
            description = model.session.subtitle,
        )
        CarRowDivider()
        CarSettingInfo(
            title = "协议阶段",
            value = model.session.technicalDetail.ifBlank { "—" },
        )
    }


    // ------------------------------------------------------------ 会话日志

    // 「诊断数据获取」的落点。取尾部若干条并**倒序**：
    // 日志是环形缓冲，越靠后越新，而排障时人只关心"刚刚发生了什么"。
    //
    // ⚠️ 这一块**不受**「调试日志」开关控制。那个开关管的是画面上的日志浮层
    // （驾驶中叠加文字会影响流畅度，所以默认关），而这里是静止的只读页，
    // 铺日志没有代价——而且"连不上"的时候恰恰最需要它，
    // 让开关把它也关掉等于把最有用的一页变成空白。
    val logTail = model.logs.takeLast(LOG_TAIL_SIZE).asReversed()
    SectionHeader(
        title = "会话日志",
    )
    CarCard(modifier = Modifier.fillMaxWidth()) {
        if (logTail.isEmpty()) {
            Text(
                text = "还没有日志。",
                style = CarTheme.type.bodyMedium,
                color = CarTheme.colors.textSecondary,
            )
        } else {
            logTail.forEachIndexed { position, line ->
                if (position > 0) CarRowDivider()
                Text(
                    text = line,
                    style = CarTheme.type.mono,
                    color = CarTheme.colors.textSecondary,
                    modifier = Modifier.padding(vertical = CarTheme.dimens.spaceXs),
                )
            }
        }
    }

    // ------------------------------------------------------------ 设备

    // 原「设备」独立页（`DevicesScreen`）2026-10-11 并入这里：它整页只回答
    // "现在连的是哪台 iPhone / 配对记录怎么清 / 认证走哪条路"，与诊断页同属
    // "排障与只读回显"，单独占一个导航入口性价比太低。
    SectionHeader(
        title = "设备",
    )
    val deviceSession = model.session
    if (deviceSession.deviceName.isNullOrEmpty()) {
        CarCard(modifier = Modifier.fillMaxWidth()) {
            CarSettingRow(
                title = "当前没有已连接的 iPhone",
            )
        }
    } else {
        CarDeviceCard(
            name = deviceSession.deviceName,
            model = deviceSession.deviceModel,
            transportLabel = if (model.settings.isWireless) "无线 CarPlay" else "USB",
            indicator = if (deviceSession.isActive) {
                CarStatusIndicator.READY
            } else {
                CarStatusIndicator.WORKING
            },
            connected = deviceSession.isActive,
        )
        // 卡片上只放得下「名称 · 型号 · 链路」，但排查时还要用另外几项。
        // 尤其**设备 ID**：同一台车接过多台 iPhone 时，只有它能说清"现在是哪一台"。
        // 这些值全部来自 AirPlay 握手里 iPhone 自己上报的 `AirPlayDeviceInfo`，
        // 所以断开后必须一起清空（否则会显示一台早已不在的设备）。
        CarCard(modifier = Modifier.fillMaxWidth()) {
            CarSettingInfo(
                title = "设备名称",
                value = deviceSession.deviceName,
            )
            CarRowDivider()
            CarSettingInfo(
                title = "设备 ID",
                value = deviceSession.deviceId ?: "本次未上报",
            )
            CarRowDivider()
            CarSettingInfo(
                title = "型号",
                value = deviceSession.deviceModel ?: "本次未上报",
            )
        }
    }
    CarCard(modifier = Modifier.fillMaxWidth()) {
        CarSettingRow(
            title = "已配对控制器",
            description = "已配对 ${model.pairedControllerCount} 台",
            trailing = {
                CarButton(
                    text = "清除配对",
                    onClick = { confirmClearPairings = true },
                    variant = CarButtonVariant.Secondary,
                    enabled = model.pairedControllerCount > 0,
                )
            },
        )
        CarRowDivider()
        CarSettingRow(
            title = "连接异常时先重连",
            trailing = {
                CarButton(
                    text = "重新连接",
                    onClick = actions::reconnect,
                    variant = CarButtonVariant.Ghost,
                )
            },
        )
        CarRowDivider()
        CarSettingInfo(
            title = "MFi 认证方式",
            value = model.settings.mfiTarget.displayLabel(),
        )
        CarRowDivider()
        CarSettingInfo(
            title = "连接链路",
            value = if (model.settings.isWireless) "无线" else "USB",
        )
    }

    // ------------------------------------------------------------ 日志与版本

    SectionHeader(title = "调试日志")
    CarCard(modifier = Modifier.fillMaxWidth()) {
        CarSettingSwitch(
            title = "调试日志",
            checked = draft.debugLogs,
            onCheckedChange = { update(draft.copy(debugLogs = it)) },
        )
        CarRowDivider()
        CarSettingSwitch(
            title = "FPS 显示",
            description = "画面左上角显示实时视频帧率（100px 小窗）",
            checked = draft.fpsOverlayEnabled,
            onCheckedChange = { update(draft.copy(fpsOverlayEnabled = it)) },
        )
    }

    SectionHeader(title = "维护")
    CarCard(modifier = Modifier.fillMaxWidth()) {
        // 「清除配对记录」2026-10-11 删除：与上面新增的「设备」节里的
        // 「清除配对」是同一个动作，同一页出现两次会让人以为是两件事。
        CarSettingRow(
            title = "重置安全区",
            onClick = onResetSafeArea,
            trailing = {
                CarButton(
                    text = "重置",
                    onClick = onResetSafeArea,
                    variant = CarButtonVariant.Secondary,
                )
            },
        )
    }

    // 有线链路的第一道门槛在硬件层，不在代码里：车机的 USB 口要能当 Host 用。
    // 之所以单开一个分区而不是并进「触摸与按键」，是因为它回答的是
    // "这台机器是什么"，而那一节回答的是"平台适配层给了什么"。
    SectionHeader(
        title = "有线连接",
    )
    CarCard(modifier = Modifier.fillMaxWidth()) {
        val support = model.platformInfo?.usbHostSupport ?: CarUsbHostSupport.UNKNOWN
        // 文案先算好再传进去：`when` 的分支体里放跨行字符串拼接，
        // Kotlin 会把续行的 `+` 当成新的分支条件解析（报 unaryPlus）。
        val label = when (support) {
            CarUsbHostSupport.SUPPORTED -> "可用"
            CarUsbHostSupport.UNSUPPORTED -> "不可用"
            CarUsbHostSupport.UNKNOWN -> "无法判定"
        }
        val hint = when (support) {
            CarUsbHostSupport.SUPPORTED ->
                "插上 iPhone 即可走有线链路。"
            CarUsbHostSupport.UNSUPPORTED ->
                "内核没有 USB 主机子系统，只能用无线 CarPlay。"
            CarUsbHostSupport.UNKNOWN ->
                "系统未开放判断所需的目录；不代表有线不可用，以插上 iPhone 后能否连上为准。"
        }
        CarSettingInfo(
            title = "USB 主机",
            value = label,
            description = hint,
        )
    }

    if (model.session.facts.isNotEmpty()) {
        SectionHeader(
            title = "当前生效值",
        )
        CarCard(modifier = Modifier.fillMaxWidth()) {
            for ((index, fact) in model.session.facts.withIndex()) {
                if (index > 0) CarRowDivider()
                CarSettingInfo(title = fact.label, value = fact.value)
            }
        }
    }

    if (confirmClearPairings) {
        CarAlertDialog(
            title = "清除全部配对记录？",
            message = "清除后，所有 iPhone 都需要重新与本机配对才能使用 CarPlay。" +
                "如果只是想修复连接问题，建议先试「重新连接」。",
            confirmText = "清除配对",
            destructive = true,
            onConfirm = {
                confirmClearPairings = false
                onClearPairings()
            },
            onDismiss = { confirmClearPairings = false },
        )
    }
}

/**
 * 设置页底部**常驻**的保存状态行（PATCH-0103，2026-10-12）。
 *
 * 取代了原先索引页/分区页底部那两颗保存/放弃按钮：
 * 自动保存之后，保存不再需要用户动作，而放弃在草稿已经落盘的前提下
 * 也没有可放弃的对象。剩下的两件事在这里合成一行 ——
 *
 * 1. **状态**：保存中 / 已保存 HH:mm:ss / 保存失败（带原因）。
 *    自动保存最怕的就是"悄无声息"：用户改完不知道到底存没存进去。
 * 2. **待重连**：有落在"必须重连才生效"那一侧的改动时，补一句提示。
 *    「重连生效」按钮在顶栏右侧，与标题同一行，不放在这里。
 *
 * ⚠️ 它是**常驻**的（走 `CarScreen(footer = ...)`），不随内容滚走 ——
 * 设置页的分区内容往往要滚一两屏，状态行若在末尾就等于没有提示。
 */
@Composable
private fun SettingsSaveStatusBar(
    saveState: SettingsSaveState,
    pendingReconnect: Boolean,
) {
    val dimens = CarTheme.dimens
    val colors = CarTheme.colors
    // 顺序即优先级：**失败 > 进行中 > 待重连 > 已保存**。
    // 尤其"保存失败"必须压过"待重连" —— 前者意味着改动根本没落盘，
    // 这时候提示"可以重连生效了"是在骗人。
    val (icon, text, tint) = when {
        saveState is SettingsSaveState.Failed ->
            Triple(CarIcons.Error, "保存失败：${saveState.reason}", colors.error)

        saveState is SettingsSaveState.Saving ->
            Triple(CarIcons.Refresh, "保存中…", colors.textSecondary)

        pendingReconnect -> Triple(
            CarIcons.Warning,
            buildString {
                append("已保存")
                (saveState as? SettingsSaveState.Saved)?.let { append(" ${it.at}") }
                append(" · 有改动需重连生效")
            },
            colors.warning,
        )

        saveState is SettingsSaveState.Saved ->
            Triple(CarIcons.Success, "已保存 ${saveState.at}", colors.success)

        else -> Triple(CarIcons.Check, "修改会自动保存", colors.textTertiary)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimens.spaceS),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(dimens.iconS),
        )
        Text(
            text = text,
            style = CarTheme.type.bodySmall,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}


private fun summaryConnection(s: CarSessionSettings): String =
    buildString {
        append(if (s.isWireless) "无线" else "USB")
        append(" · ")
        append(
            when (s.mfiTarget) {
                CarMfiTarget.USB_CH341 -> "CH341"
                CarMfiTarget.I2C -> "I2C"
                CarMfiTarget.REMOTE -> "远程认证"
                CarMfiTarget.LOCAL_OFFLINE -> "本机软件认证"
            },
        )
        if (s.isWireless) append(" · 热点：${hotspotModeLabel(s.hotspotMode)}")
        // 只在偏离默认档时补一句：默认档是常态，每次都在摘要里念一遍会把这一行
        // 撑长，反而看不到真正改过的东西。
        //
        // ⚠️ 2026-10-11（PATCH-0095）：**默认档换人了** —— 从 `DISCONNECT_LINK`
        // （断开本次手机）改成 `DISABLE_ADAPTER`（关闭蓝牙适配器，会话结束恢复）。
        // 所以这里比对的对象跟着换，否则摘要会**每次都念一遍默认档**，
        // 而 `CarSettingRow.description` 是 `maxLines=3` 静默截断（坑 §76）。
        if (s.isWireless && s.bluetoothHandoffPolicy != CarBluetoothHandoffPolicy.DISABLE_ADAPTER) {
            append(" · 蓝牙：${s.bluetoothHandoffPolicy.displayLabel()}")
        }
    }

/**
 * 热点模式的人类可读名称。
 *
 * ⚠️ 索引页摘要与分区页选项**必须共用这一个函数**：曾经摘要直接写
 * `s.hotspotMode.name`，界面上漏出枚举常量名这种机读字符串。
 *
 * ⚠️ 同理**不能出现 `LocalOnlyHotspot`**：那是 Android API 名，不是给人读的。
 * 2026-10-03 起该形态已整体移除（见 `CarHotspotMode`），只剩两条真正可用的形态：
 * 复用车机热点 / 自建热点，AUTO 在两者间调度。
 */
private fun adbChannelStatusLabel(status: CarAdbChannelStatus): String = when (status) {
    CarAdbChannelStatus.UNKNOWN -> "待检测"
    CarAdbChannelStatus.CHECKING -> "检测中"
    CarAdbChannelStatus.INITIALIZING -> "初始化中"
    CarAdbChannelStatus.READY -> "5556 可用"
    CarAdbChannelStatus.NOT_INITIALIZED -> "未初始化"
    CarAdbChannelStatus.UNAVAILABLE -> "5556 不可用"
    CarAdbChannelStatus.PERSISTENCE_UNVERIFIED -> "当前可用，持久性未确认"
}

private fun hotspotModeLabel(mode: CarHotspotMode): String = when (mode) {
    CarHotspotMode.AUTO -> "自动（优先复用车机热点）"
    // 2026-10-16 起 MANUAL 已不由界面提供（存量值专用）：老安装持久化里的
    // `"MANUAL"` 会在解析/迁移层等价到 TCAM_HOTSPOT，界面不会再出现这一档。
    CarHotspotMode.MANUAL -> "复用车机热点"
    // 不写 "hostapd"：那是进程名，用户读不懂。这一支与「复用车机热点」的区别
    // 才是要说的——它借车机 ADB 提权，自己起 hostapd，网段/信道/地址全可控。
    CarHotspotMode.ADB_HOSTAPD -> "自建热点（DBPlay）"
    // PATCH-0010。括号里那句是这一支**唯一需要用户理解的事**：
    // 它不建热点。否则用户会以为"选了它车机就会起一张网"，而实际要先自己连好。
    CarHotspotMode.SAME_LAN -> "局域网连接（不建热点）"
    // TCAM 复用：只复用车机原厂热点、绝不自己开，接入卡/地址自动识别。
    CarHotspotMode.TCAM_HOTSPOT -> "复用车机热点"
    // 2026-10-25 新增：与「复用车机热点」**并列的独立链路**。
    // 名字不写"路由"也不写"eth"——用户只需要知道它是"车机的热点"这一档；
    // 与上面那档的区别（手填凭据 vs 自动读取）写在选项下方的说明里。
    // ⚠️ 它同样**不建热点**，只是换个方式接管车机已有的网。
    CarHotspotMode.GEELY_ROUTED -> "车机热点"
}

/**
 * 「车机自带热点」那一行的**值列**文案（2026-10-08 加）。
 *
 * 穷尽式 `when`、**不写 `else`**：将来给 [CarHotspotEnabledState] 加值，
 * 编译器会在这里报错，逼着做一次显式决策（项目规则，见 `dbplay-feature-wiring` §一.5）。
 */
private fun carHotspotEnabledLabel(state: CarHotspotEnabledState): String = when (state) {
    CarHotspotEnabledState.ON -> "已开启"
    CarHotspotEnabledState.OFF -> "已关闭"
    CarHotspotEnabledState.UNKNOWN -> "读不到"
}

/**
 * 上面那一行的说明。
 *
 * ⚠️ [CarHotspotEnabledState.UNKNOWN] 的文案必须说清"**不影响连接**"：
 * 它与 `OFF` 在连接时的行为**完全相反**（`UNKNOWN` 放行、`OFF` 拦截，
 * 见 `CarManualHotspotPrecheck.blocker`）。界面若把它们混成一句"未知/关闭"，
 * 用户会去改一个本来就没问题的设置，而真正的问题在别处。
 */
private fun carHotspotEnabledNote(state: CarHotspotEnabledState): String = when (state) {
    CarHotspotEnabledState.ON ->
        "车机自带热点的开关。选「复用车机热点」时它必须开着；点这一行可重新读取。"
    CarHotspotEnabledState.OFF ->
        "关着时「复用车机热点」会立刻失败。去车机设置里打开后，点这一行重新读取。"
    CarHotspotEnabledState.UNKNOWN ->
        "这台车机读不到热点开关（厂商隐藏了接口）。不影响连接，按放行处理。"
}

private fun summaryDisplay(s: CarSessionSettings): String {
    val video = "${s.fps}fps · ${if (s.hevc) "HEVC" else "H.264"}"
    return video
}

/**
 * 车机配置摘要（2026-10-11 分区合并）。
 *
 * 原来「音频 / 方向盘与按键 / 媒体卡片 / 定位」各有一条 `summaryXxx`，
 * 合并后索引页只剩一行位置 —— 全列出来会撑爆 `CarSettingRow` 的
 * description（`maxLines = 3`，§76 静默截断）。
 *
 * 所以改成**只报"开着的项"**：一项没开时一行说清"全部关闭"，
 * 开了一两项就直接列名字，信息密度最高。
 */
private fun summaryVehicle(s: CarSessionSettings): String {
    val on = buildList {
        if (s.advancedAudioChannelMapping) add("通道映射")
        if (s.microphone) add("麦克风")
        if (s.forwardSteeringWheelToCarPlay) add("方控")
        if (s.reportMediaCardToCar) add("媒体卡片")
        if (s.locationReporting) add("定位")
    }
    return if (on.isEmpty()) "音频 · 方控 · 媒体卡片 · 定位 · 全部关闭"
    else "已开启：" + on.joinToString("、")
}

private fun summaryStartup(s: CarSessionSettings): String =
    "开机自启${if (s.autoStartOnBoot) "开" else "关"} · 进入即连接"

private fun summaryIdentity(s: CarSessionSettings): String =
    "${s.deviceName.ifBlank { "未命名" }} · " +
        if (s.drivingSide == CarDrivingSide.LEFT) "左舵" else "右舵"

private fun summaryWindow(s: CarSessionSettings): String = when {
    s.fullscreen -> "全屏 · 隐藏两条系统栏"
    s.hideTopBar && s.hideBottomBar -> "隐藏顶部栏 · 隐藏底部栏"
    s.hideTopBar -> "隐藏顶部栏"
    s.hideBottomBar -> "隐藏底部栏"
    else -> "保留系统栏"
}

/**
 * 仪表导航摘要。
 *
 * 2026-10-23：导航是否显示按两个独立开关的口径读（模式允许导航 + 投屏开关），
 * 与页面里的「在仪表上显示导航画面」保持一致；不再单看 `projectionEnabled`。
 */
private fun summaryCluster(s: CarSessionSettings): String {
    val navigationVisible = ClusterLayerSwitchMapping.read(
        mode = s.clusterLayerMode(),
        projectionEnabled = s.projectionEnabled,
    ).navigationVisible
    return when {
        !navigationVisible -> "导航画面 · 已收起 · 固定仪表屏 #2"
        else -> "导航画面 · 已开启 · 固定仪表屏 #2 · 三指左滑投屏／右滑收起"
    }
}

/**
 * 索引页「HUD」那一行的摘要。
 *
 * 只报"开关 + 连接"两件事：目标包名与计数在详情页里，索引页一行放不下，
 * 而用户在这一层只需要知道"开着没、通没通"。
 */
private fun summaryHud(s: CarSessionSettings, model: CarUiModel): String {
    if (!s.hudGuidanceEnabled) return "原车仪表卡片 · 已关闭"
    val hud = model.hud
    return "原车仪表卡片 · 已开启 · " + when {
        !hud.supported -> "本机不支持"
        hud.available -> "已连接"
        hud.connecting -> "绑定中…"
        else -> "未连接"
    }
}

/**
 * 索引页「仪表主题」那一行的摘要。
 *
 * ⚠️ 这里**不读 `clusterSkinEnabled`**：那是不参与副屏绘制的历史字段。
 * 2026-10-23 起主题显隐由主题页自己的「在仪表上显示主题」开关控制，
 * 对应判据是 `ClusterLayerMode.showsTheme`。
 */

private fun summaryDiagnostics(s: CarSessionSettings): String =
    "调试日志${if (s.debugLogs) "开" else "关"} · 安全区与配对维护"

/**
 * 热点状态回显。
 *
 * 与上游 `Hotspot status` 同样的口径：只报"起来没有"，不报信道/客户端数——
 * 那些属于诊断回显，放在「诊断」分区里看。
 */
private fun hotspotStatusOf(draft: CarSessionSettings, model: CarUiModel): String = when {
    !draft.isWireless -> "未使用"
    model.session.isActive -> "已连接"
    model.session.indicator == CarStatusIndicator.WORKING -> "启动中"
    else -> "未启动"
}

/**
 * 赞助页（2026-10-22）。
 *
 * 车主口径：在设置里加一个赞助入口。页面只有三块：一句说明、一张赞赏码、
 * 一行扫码提示。图片放在 `assets/sponsor/qr.jpg`，换码只需替换这张图。
 */
@Composable
private fun SponsorSection() {
    val context = LocalContext.current
    val bitmap = remember {
        runCatching {
            context.assets.open("sponsor/qr.jpg").use { BitmapFactory.decodeStream(it) }
        }.getOrNull()
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "赞助 Token",
            style = CarTheme.type.bodyLarge,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "DBPlay 是个人作品，长期维护要烧 Token。觉得好用可以赞助一点，帮作者把 Token 续上。",
            style = CarTheme.type.bodyMedium,
            color = CarTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "赞赏码",
                // 2026-10-22 车主口径：赞赏码做成圆角。
                modifier = Modifier
                    .height(320.dp)
                    .widthIn(max = 320.dp)
                    .clip(RoundedCornerShape(20.dp)),
                contentScale = ContentScale.Fit,
            )
        } else {
            Text("赞赏码加载失败（assets/sponsor/qr.jpg 缺失）", color = CarTheme.colors.error)
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = "用微信扫码即可，金额随意",
            style = CarTheme.type.bodySmall,
            color = CarTheme.colors.textSecondary,
        )
    }
}
