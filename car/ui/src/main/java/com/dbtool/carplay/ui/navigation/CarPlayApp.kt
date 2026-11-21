package com.dbtool.carplay.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import com.dbtool.carplay.api.navigation.CarNavigationTarget
import com.dbtool.carplay.ui.CarUiActions
import com.dbtool.carplay.ui.CarUiModel
import com.dbtool.carplay.ui.components.CarDockBar
import com.dbtool.carplay.ui.components.CarDockAppPickerDialog
import com.dbtool.carplay.ui.components.CarUpdateDialog
import com.dbtool.carplay.ui.screens.AboutScreen
import com.dbtool.carplay.ui.screens.CarPlayScreen
import com.dbtool.carplay.ui.screens.DeveloperScreen
import com.dbtool.carplay.ui.screens.HomeScreen
import com.dbtool.carplay.ui.screens.NavigationScreen
import com.dbtool.carplay.ui.screens.SettingsScreen
import com.dbtool.carplay.ui.theme.CarTheme

/**
 * 应用根组件：一个极简的页面栈。
 *
 * **导航模型：主页 + 二级页面。**
 *
 * 上一版用了左侧竖直导航栏，实测问题很明显：
 * - 车机横屏本来就把宽度让给内容才是对的，导航栏白占 100dp；
 * - 常驻导航栏会让界面看起来像"一个装了三四个 tab 的手机 App"，
 *   而不是"车机系统里的一个功能"。
 *
 * 现在改成：**主页 + 右上角设置入口 + 二级页面栈**。
 * 所有配置、设备、关于都从主页进、按返回键出，
 * 页面栈天然支持任意深度，不需要预设导航结构。
 *
 * CarPlay 画面页在连接后会自动进入**沉浸态**：
 * 去掉顶栏、画面之上**不放任何常驻 UI**（2026-10-06 起，此前挂过一个悬浮返回按钮，车主嫌碍事已删），
 * 让 iPhone 的画面成为主角。
 */
@Composable
fun CarPlayApp(
    model: CarUiModel,
    actions: CarUiActions,
    modifier: Modifier = Modifier,
    initialDestination: CarDestination = CarDestination.Home,
    onUiTouch: () -> Unit = {},
    /**
     * 内置仪表主题目录。由 `:car:app` 用 `ClusterThemeRepository` 读好传进来
     * （`:car:ui` 不依赖 `:car:core-bridge`）。
     */
    /** 云端主题同步状态（内置/云端数量、上次同步时间）。 */
    /** 主题 → 当前 VIN 的授权状态。 */
    /** 「获取授权」对话框状态（界面与宿主共用同一个持有者）。 */
    /** 授权动作回调（宿主负责后台网络）。 */
    /** 进入「仪表主题」页时触发云端同步（宿主负责放到后台线程）。 */
    /** 模块级主题授权；未知时按未激活处理。 */
    /** 模块授权是否仍在查询。 */
    /** 请求宿主补查模块授权（UI 不做网络判断）。 */
) {
    var stack by remember { mutableStateOf(listOf(initialDestination)) }
    val current = stack.last()

    fun push(destination: CarDestination) {
        stack = stack + destination
    }

    fun pop() {
        if (stack.size > 1) stack = stack.dropLast(1)
    }

    BackHandler(enabled = stack.size > 1) { pop() }

    // ---------------------------------------------------------------- 自动进入画面页
    //
    // 会话激活后自动跳进 CarPlay 画面页，**每次会话只跳一次**。
    //
    // ⚠️ 这段逻辑必须在 App 层，不能放进 `HomeScreen`。
    // `HomeScreen` 在 `when (current)` 里，导航离开时会**退出组合**，
    // 它内部的 `remember` 随之被丢弃；回到主页时守卫重新变成"未跳过"，
    // 于是刚连上时按返回键会被立刻弹回画面页——用户永远退不出去。
    //
    // 另外两个约束：
    // - 会话结束时必须**解除**标记，否则断开再连不会自动进入；
    // - 只在停留在主页时才跳。用户正在设置页里操作时被强行拽走很讨厌，
    //   而那时他显然知道自己要干什么。
    var autoEnterConsumed by remember { mutableStateOf(false) }
    LaunchedEffect(model.session.isActive) {
        if (!model.session.isActive) autoEnterConsumed = false
    }
    LaunchedEffect(model.session.isActive, current) {
        if (model.session.isActive && !autoEnterConsumed && current == CarDestination.Home) {
            autoEnterConsumed = true
            push(CarDestination.CarPlay)
        }
    }

    // ------------------------------------------------------------ 开发者模式收尾
    //
    // 开发者页是**隐藏页**：一旦上锁（点了"退出开发者模式"）就必须退出它，
    // 否则返回键还能回到一个本该不存在的页面。
    // 只在栈顶正好是它时才弹——别把用户正在看的设置页也一起弹掉。
    LaunchedEffect(model.developer.unlocked) {
        if (!model.developer.unlocked && stack.lastOrNull() == CarDestination.Developer) {
            stack = stack.dropLast(1)
        }
    }

    // ---------------------------------------------------- 控制器发来的导航请求
    //
    // 控制器（`:car:app`）不能直接调 `push(...)`——它依赖 UI，UI 不依赖它。
    // 于是走 `CarUiModel.navigationRequest` 这条**单向数据通道**：
    // 请求是**一次性事件**，这里消费一次后必须回调清空，
    // 否则用户手动从设置页按返回会被立刻弹回去，等于被锁在那个页面里。
    //
    // key 用 `LaunchedEffect` 的 key 承载：序号变一次就跳一次。
    LaunchedEffect(model.navigationRequest?.key) {
        val request = model.navigationRequest ?: return@LaunchedEffect
        push(
            when (request.target) {
                // 「设置 → 连接 → 无线（热点服务）→ 立即建立热点 + ADB」。
                // 直接带 focus 进连接分区，而不是丢在设置索引页让用户自己找。
                CarNavigationTarget.SETTINGS_HOTSPOT ->
                    CarDestination.Settings(CarSettingsSection.CONNECTION)
            },
        )
        // 消费即清空：不依赖"下一次请求会覆盖它"，因为清空才是幂等的。
        actions.consumeNavigationRequest()
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        // 根节点 inset 之后，系统栏那一条露出来的**就是这层 Surface 的颜色**。
        //
        // 画面页（**2026-10-09 车主定案：撤销 10-06 的深色顶栏**）：
        // 之前那 72px 自绘填的是应用主题色 `surface`（深色 `#1D1F23`），
        // 读起来像一条应用自己的顶栏。现在改为 `videoBackground`（近黑 `#0E0F12`）——
        // 与视频层同色 ⇒ 那 72px 与画面融为一体，不再有可见的横条。
        //
        // 为什么可以这么做：画面页已改由「安全区声明 + 画面延展到状态栏下」
        // （`CarSessionSettings.immersiveVideo`）铺满，CarPlay 自己会把交互 UI
        // 排到状态栏之下，不再需要靠一条主题色顶栏去"解释"那 72px。
        // 那 72px 实际露出的是**车机 SystemUI 的状态栏窗口**（不在本进程，抢不掉）。
        // 其余页面照旧用 `background`（页面底色）。
        color = if (current == CarDestination.CarPlay) {
            CarTheme.colors.videoBackground
        } else {
            CarTheme.colors.background
        },
    ) {
        // 顶部/底部要避让哪些 inset，取决于**用户对系统栏的意图**（画面页不再特殊化，
        // 与其它页面同一套判据——见下面的长注释）：
        //
        // ⚠️ **`windowInsetsPadding(safeDrawing)` 不能无条件加**（2026-10-05 实车修复）。
        // 旧实现假设"系统栏隐藏成功 ⇒ inset 归零 ⇒ 内容自然铺满"，这个假设在车机上是**错的**：
        // 隐藏系统栏的请求本来就只是"建议"，**系统可以拒绝**——吉利 CS11（Flyme Auto，
        // Android 9）就拒绝了。日志实证：
        //
        //     content=[0,72][0,0]            ← 窗口内容被切掉顶部 72px
        //     Visible insets changing to: Rect(0, 72 - 0, 0)   ← 系统恒报顶部 72px inset
        //     vsysui=LAYOUT_FULLSCREEN …     ← 我们请求了，系统没答应
        //
        // 于是"设置全屏了还是有顶栏"：隐藏请求落空 + 根节点无条件避让 72px = 用户看到的顶栏留白。
        //
        // 两条规则（按优先级）：
        // 1. **画面页与其它页面同一套判据**——用户开了隐藏哪条栏就不避让哪条（见下面的长注释）；
        // 2. IME（输入法）**任何情况下都避让**：设置页有文本框，edge-to-edge 下
        //    `adjustResize` 不再自动顶起内容，不避让键盘会直接盖住输入框。
        // 「用户要求隐藏的那几条栏」对应的 inset 集合；这些要被**排除**在避让之外。
        // 用 `statusBars()`/`navigationBars()` 这两个 WindowInsets 常量按需拼，
        // 避免依赖 `only(...)` 在本 compose 版本里的参数名。
        //
        // ⚠️ **默认仍是"避让状态栏"**（2026-10-06 车主定案 A，下文 2026-10-08 只是开了个口子）。
        //
        // 背景：那层状态栏是**车机 SystemUI 的独立窗口**，不在我们进程里，
        // `insets.hide(statusBars())` 请求会被 ROM 拒绝（本机已实证）⇒ **那 72px 永远抢不掉**。
        // 于是只有两个选项：
        //
        //   A. **避让**（出厂默认）：画面被砍 72px，协商分辨率 1920x1080 → **1920x1008**，带黑边；
        //      代价是画面缩水，好处是**画面完整可看、不被状态栏压住**。
        //   B. 顶格：画面 1920x1080 满幅，但**状态栏浮在画面上遮住顶部 72px**。
        //
        // 日志实证（设备 d5ddb4312da1）：
        //
        //     StatusBarWindowView{... 0,0-1920,72}        ← 状态栏根本没被隐藏（车机拒绝 hide 请求）
        //     video SPS ... negotiated=1920x1008          ← 避让后画布少了 72px
        //     ViewFactoryHolder{... 0,72-1920,1080}       ← 我们的 Compose 容器被下移了 72px
        //
        // 车主先选 B（画面顶格），实车看过后改回 **A（避让）**——状态栏压住画面顶部不可接受。
        // 2026-10-08 车主再次提出要沉浸，此时**多了一件 10-06 没有的工具**：安全区声明
        // （那时 `safeArea` 恒为 0、没有任何入口）。所以 B 现在以 **opt-in** 的形式回来，
        // 并且只对画面页开放（见下面的 `immersiveVideoActive`）——
        // 出厂默认仍然是 A，设置页/主页的判据也完全没变。
        // ------------------------------------------------------------------
        // 沉浸画面（2026-10-08 车主定案，推翻 2026-10-06 的"画面页不得特殊化"）。
        //
        // 诉求：**"我想要沉浸式体验，顶部有 72px 状态栏，我想把 CarPlay 的 UI 延展过去"**。
        //
        // 与 10-06 那次「顶格」尝试的唯一区别：这次**同时**声明安全区
        // （见 `CarSessionSettings.immersiveVideo` 与 `CarPlayConfigBuilder`）。
        // 光顶格 = CarPlay 自己的顶栏/按钮被状态栏盖住（10-06 就是因此被否），
        // 加上安全区 ⇒ iPhone 把交互 UI 排到那条栏之下，只有壁纸铺满。
        //
        // ⚠️ **只对画面页生效**。设置页/主页必须继续避让状态栏，否则顶栏按钮
        // （含右上角设置入口）会被车机状态栏压住点不到 —— 这正是 10-06 定案 A
        // 要保护的东西，本次没有推翻它，只是给画面页开了一个显式的口子。
        // ------------------------------------------------------------------
        // 「隐藏顶部栏」关掉时，状态栏还在。画面页必须铺到那一条下面，
        // 否则安全区的坐标系从状态栏下沿起算，怎么填像素都画不进状态栏。
        // 设置页和主页继续避让，避免顶栏按钮被盖住。
        val extendUnderStatusBar =
            current == CarDestination.CarPlay &&
                !model.settings.hideTopBar &&
                !model.settings.fullscreen
        val baseBarsToIgnore = when {
            // 2026-10-23 修：**全屏时只能排除导航栏，状态栏必须继续避让**。
            //
            // 旧写法把 statusBars 一起排除，理由是"全屏就该铺满"——但本机的状态栏是
            // 车机 SystemUI 的独立窗口（StatusBarWindowView 0,0-1920,72），
            // `insets.hide(statusBars())` 会被 ROM 拒绝（已实证多次）。
            // 于是排除避让之后：状态栏**还在**，Compose 却把顶栏放到了 y=0
            // ⇒ 「返回」「设置」被压在那 72px 底下，点不到。
            //
            // 这与 2026-10-06 定案 A 完全冲突（那次的结论就是"设置页/主页必须避让，
            // 否则右上角设置入口被压住"）。真正需要顶格铺满的只有画面页，
            // 那条口子由下面的 `immersiveVideoActive` 显式开，不在这里开口。
            model.settings.fullscreen -> WindowInsets.navigationBars
            // 2026-10-23 修（Claude 复核补充）：`hideTopBar && hideBottomBar`
            // 在 CarSessionSettings.kt:1214 里明确等价于「全屏」，MainActivity
            // 给系统传的窗口标志也完全相同。所以这里必须和 fullscreen 分支一致：
            // 只排除导航栏，状态栏继续避让 —— 否则用户从全屏退回这两个开关，
            // 顶栏按钮被 72px 状态栏压住的问题会原样复现。
            model.settings.hideTopBar && model.settings.hideBottomBar ->
                WindowInsets.navigationBars
            model.settings.hideTopBar -> WindowInsets.statusBars
            model.settings.hideBottomBar -> WindowInsets.navigationBars
            else -> WindowInsets(0, 0, 0, 0)
        }
        // 沉浸时额外把状态栏从避让集合里摘掉 ⇒ 画面页容器铺到 y=0
        // ⇒ 视频 View 实测高度 = 整屏 ⇒ 协商画布从 1920x1008 变成 1920x1080。
        val barsToIgnore = if (extendUnderStatusBar) {
            baseBarsToIgnore.union(WindowInsets.statusBars)
        } else {
            baseBarsToIgnore
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(
                    WindowInsets.safeDrawing
                        .exclude(barsToIgnore)
                        .union(WindowInsets.ime),
                )
                // 输入健康探针（PATCH-0105）：只**读**第一拍按下事件，证明"这一下触摸
                // 走进了 Compose 命中路径"。与宿主 `dispatchTouchEvent` 的埋点配对，
                // 用来回答"触摸卡在哪一层"，不参与任何交互判定。见下面对应的 KDoc。
                .touchHealthProbe(onUiTouch),
        ) {
            when (current) {
                CarDestination.Home -> HomeScreen(
                    model = model,
                    actions = actions,
                    onNavigate = ::push,
                )

                CarDestination.CarPlay -> CarPlayScreen(
                    model = model,
                    actions = actions,
                    onBack = ::pop,
                    showDebugOverlay = model.settings.debugLogs,
                )

                CarDestination.Navigation -> NavigationScreen(
                    model = model,
                    onBack = ::pop,
                )

                is CarDestination.Settings -> SettingsScreen(
                    model = model,
                    actions = actions,
                    onBack = ::pop,
                    onOpenAbout = { push(CarDestination.About) },
                    focus = current.focus,
                )

                CarDestination.About -> AboutScreen(
                    model = model,
                    onBack = ::pop,
                    onExitApp = actions::exitApp,
                    onVersionTap = actions::onVersionTap,
                    onOpenDeveloper = { push(CarDestination.Developer) },
                    onCheckUpdate = actions::onCheckUpdate,
                    onToggleSilentUpdate = actions::onToggleSilentUpdate,
                )

                CarDestination.Developer -> DeveloperScreen(
                    model = model,
                    actions = actions,
                    onBack = ::pop,
                )
            }

            // ------------------------------------------------------------ 自绘底栏
            //
            // 默认关（`CarSessionSettings.dockBar`，设置 → 车机配置 → 底栏）。
            //
            // **叠加**在内容之上、不占位：占位会改画面页协商出来的画布高度
            // （1920x1008 再减一条栏），那条链路牵动 safeArea 与
            // `FullscreenInsetContractTest`，代价远大于收益
            // （见 `DBPlay-底栏Dock-设计方案-20261011.md` §0-1）。
            //
            // 数据在 `CarUiModel.dockApps`；温度与媒体还没接数据源 ⇒ 组件自己
            // 整块不渲染（见 `CarDockBar` 的"数据缺失时的行为"）。
            if (model.settings.dockBar) {
                CarDockBar(
                    apps = model.dockApps,
                    leftTemperature = model.dockLeftTemperature,
                    rightTemperature = model.dockRightTemperature,
                    media = model.dockMedia,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = CarTheme.dimens.spaceM),
                    onHome = actions::onDockHomeClick,
                    // 编辑态下点应用槽 = 打开"选应用"面板（原 dock 的交互就是
                    // 长按进编辑 → 弹应用列表）；非编辑态才是启动该应用。
                    onAppClick = { app ->
                        if (model.dockEditMode) {
                            actions.onDockOpenAppPicker()
                        } else {
                            actions.onDockAppClick(app.key)
                        }
                    },
                    onMediaToggle = actions::onDockMediaToggle,
                    onMediaNext = actions::onDockMediaNext,
                    editMode = model.dockEditMode,
                    onLongPress = actions::onDockEnterEdit,
                    onExitEdit = actions::onDockExitEdit,
                    onReorder = actions::onDockReorder,
                )
            }

            // -------------------------------------------------------- 在线更新
            //
            // 对话框挂在**根上**而不是「关于」页里：自动检查是异步的，
            // 结果可能在用户已经离开关于页（甚至停在主页）时才到达——
            // 挂在关于页里就会出现"发现有新版本但什么都没发生"。


            // 底栏"选应用"面板（编辑态下点应用槽唤出）。
            if (model.dockAppPickerVisible) {
                CarDockAppPickerDialog(
                    choices = model.dockAppChoices,
                    // 没配过（`dockAppOrder` 为空）时预选"当前底栏正在显示的那几个"：
                    // 否则会出现"底栏明明有 5 个应用、面板却说已选 0 个"的矛盾读法
                    // （实拍确认过这个读法）。
                    selectedKeys = model.settings.dockAppOrder
                        .ifEmpty { model.dockApps.map { it.key } },
                    onConfirm = actions::onDockAppPickerConfirm,
                    onDismiss = actions::onDockAppPickerDismiss,
                )
            }
        }
    }
}

/**
 * 输入健康探针（PATCH-0105，**只观测、不消费**）。
 *
 * 在**第一拍按下**（`PointerEventType.Press`）时回调一次 [onTouch]。
 * 宿主 `MainActivity` 用它和 `dispatchTouchEvent` 的埋点配对，回答
 * "这一次触摸是卡在窗口层，还是已经走进了 Compose 命中路径"。
 *
 * 两条纪律（改这个函数前先读）：
 * 1. **绝不 `consume()`**：走 `PointerEventPass.Initial` 只读事件，触摸行为一点不变。
 *    一旦消费，点击/拖拽/多指操作全会跟着变。
 * 2. **它证明不了"画面页的视频层"**：画面页的 `AndroidView`（`TextureView`）自己
 *    吃事件、不走 Compose 的命中路径，所以画面页上只有窗口层那一行是可信的。
 *    本探针的用途是设置页/主页这类纯 Compose 页面。
 */
private fun Modifier.touchHealthProbe(onTouch: () -> Unit): Modifier =
    pointerInput(onTouch) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Press) onTouch()
            }
        }
    }
