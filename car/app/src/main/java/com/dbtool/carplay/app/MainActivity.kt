package com.dbtool.carplay.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.dbtool.carplay.api.settings.CarTransportMode
import com.dbtool.carplay.ui.CarUiModel
import com.dbtool.carplay.ui.navigation.CarPlayApp
import com.dbtool.carplay.ui.theme.CarPlayTheme
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.launch

/**
 * 宿主 Activity。
 *
 * 职责刻意收窄为四件事，**不含任何 CarPlay 业务逻辑**：
 * 1. 权限申请（VPN / 无线 / 麦克风 / 定位）——这是唯一必须在 Activity 层做的事，
 *    因为 Android 的权限对话框只能由 Activity 发起；
 * 2. 系统栏控制（隐藏状态栏/导航栏）——车机上必须做，否则画面会被系统栏挤压；
 * 3. 把 [CarPlayApp] 挂载到 Compose；
 * 4. **把 `dispatchKeyEvent` 转给方控通路**——走 `KeyEvent` 的车机，
 *    这里是唯一的按键入口，漏了它该平台方控就是死的（见 [dispatchKeyEvent]）。
 *
 * 所有会话、设置、状态逻辑都在 [CarAppController] 里，
 * 因此 Activity 重建（车机切页、系统回收）不会影响 CarPlay 会话。
 */
class MainActivity : ComponentActivity() {

    private lateinit var controller: CarAppController

    // ------------------------------------------------------------ 仪表主题（P1.5）
    //
    // 主题列表是**界面状态**：内置主题启动时读一次 assets；云端主题在进入
    // 「仪表主题」页时后台刷新，结果回来后更新这里。网络与磁盘都不在主线程。
    // Transitional declarations while removing theme call sites; no startup wiring.

    // ------------------------------------------------------- 主题授权（输入码 / 扫码）
    //
    // 全部网络调用都在 `dbplay-cluster-theme` 线程上跑（与云端目录同步同一个
    // 单线程队列），主线程只负责把结果写进 Compose 状态。

    /** 当前扫码会话；只在主线程读写。 */

    /**
     * 会话代次：重开对话框 / 刷新二维码 / 关闭对话框时 +1，
     * 迟到的后台回调按代次丢弃，避免把上一次的二维码/状态贴到新会话上。
     */

    /** 与界面层同一套轮询状态机；wait→bound / expired / 超时判定只有一份实现。 */


    private var vpnGranted = false

    /** 首帧耗时只打一次（见 [onCreate] 里的 `OnPreDrawListener`）。 */
    private var firstFrameLogged = false
    private var permissionRequestInFlight = false

    // ---------------------------------------------------------------- 输入健康探针
    //
    // PATCH-0105（2026-10-12）加，**只用于诊断，不改变任何交互行为**。
    //
    // 起因：现场出现过「窗口看得见、按钮点不动」——点一次「重连」（= 会话重建 +
    // 窗口重排）之后立刻恢复。当时的证据链是"三层都正常，但触摸没进来"：
    // 主线程空闲在 `MessageQueue.nativePollOnce`、`gfxinfo` 累计帧数几分钟不涨、
    // 没有 ANR、窗口 `isVisible=true` 且有 surface。
    //
    // 这条探针把"触摸到底走到哪一层"变成日志里的两行：
    // - `输入诊断：窗口层收到按下` —— [dispatchTouchEvent] 被调用（Activity 窗口收到了）；
    // - `输入诊断：UI 层收到按下`   —— Compose 命中路径收到了（见 [CarPlayApp] 的 `onUiTouch`）。
    //
    // 于是下一次复现不用再抓 66 份线程栈：
    // - 两行都有 ⇒ 事件到了 UI 层，问题在命中测试/控件本身；
    // - 只有第一行 ⇒ **窗口层收到了、Compose 没收到**（层级/命中测试）；
    // - 一行都没有 ⇒ 触摸根本没送到进程（窗口/输入法/系统），即本次现场那一类。
    private var lastWindowTouchDownAt = 0L
    private var threePointerWindowSeen = false
    private var lastThreePointerWindowLogAt = Long.MIN_VALUE
    private val uiTouchSeen = AtomicBoolean(false)

    private val inputProbeHandler = Handler(Looper.getMainLooper())

    /**
     * 系统栏标志位"整轮延后一帧"的一次性守卫（PATCH-0105）。
     *
     * 见 [applySystemBars] 开头的说明：输入法事务在途时本轮不写标志位、推迟一帧再来。
     * 守卫只允许顺延**一次**，避免"输入法一直开着 ⇒ 标志位永远不应用"。
     */
    private var systemBarsDeferredOnce = false

    /**
     * MFi 身份导入的中间态：已经选好的私钥 Uri，等证书 Uri 到位后一起提交。
     *
     * 为什么要两步：私钥与证书必须配对，一次只让用户选一个文件
     * 反而更容易让他把两份不同的身份混着选；分两步可以在第二步取消时
     * 干净地放弃整个导入，而不是留下半套身份。
     */
    private var pendingMfiIdentityUri: Uri? = null

    private val mfiIdentityLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) {
            pendingMfiIdentityUri = null
            controller.setNotice("未选择私钥文件，已取消导入本地 MFi 身份。")
            return@registerForActivityResult
        }
        pendingMfiIdentityUri = uri
        launchMfiCertificatePicker()
    }

    private val mfiCertificateLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val identityUri = pendingMfiIdentityUri
        pendingMfiIdentityUri = null
        if (uri == null || identityUri == null) {
            controller.setNotice("未选择证书文件，已取消导入本地 MFi 身份。")
            return@registerForActivityResult
        }
        controller.importMfiIdentity(identityUri, uri)
    }

    private val runtimePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        permissionRequestInFlight = false
        val denied = result.filterValues { granted -> !granted }.keys
        if (denied.isNotEmpty()) {
            controller.setNotice("以下权限被拒绝，相关功能不可用：${denied.joinToString("、")}")
        } else {
            controller.setNotice(null)
        }
        proceedToStart()
    }

    /**
     * AirPlay OEM 图标选择器。
     *
     * 只筛图片类型（见 [AIRPLAY_ICON_MIME_TYPES]）：与 MFi 身份导入不同，这里选的就是图片，MIME 是标准的，
     * 限定类型能让车机文件管理器把非图片直接灰掉，减少误选。
     * 真正的把关（能不能解码、是不是正方形）在 `CarAirPlayIconStore` 里——
     * 非正方形会**居中裁剪**而不是报错，用户不该为了一个图标去学图像规范。
     */
    private val airPlayIconLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) {
            controller.setNotice("未选择图片，已取消更换 AirPlay 图标。")
            return@registerForActivityResult
        }
        controller.importAirPlayIcon(uri)
    }

    private val vpnConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        vpnGranted = result.resultCode == Activity.RESULT_OK
        if (!vpnGranted) {
            controller.setNotice("未授予 VPN 权限。有线 CarPlay 需要它来建立数据通道。")
        } else {
            controller.setNotice(null)
        }
        controller.startSession()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BlockGate.interceptIfBlocked(this)) return
        enableEdgeToEdge()

        // 首帧耗时（诊断用）。
        //
        // 为什么要单独量：实车 3 份包里**稳定**出现
        // `Choreographer: Skipped 46~47 frames!` ⇒ 首帧约 0.78~0.9 s，
        // 而 `MainThreadMonitor: Slow dispatch` 只说"慢了"，不说"慢在哪一段"
        // （它报的 `m=110` 是冷启动整条消息，见 `REF-debug.md` §F.8）。
        // 这里在**布局完成、绘制之前**打一个点，配合 `CarPlaySessionBridge` 那条
        // "媒体接线耗时"，就能把首帧拆成「组合 + 布局」与「主线程上被塞进来的其它工作」。
        //
        // 用 `OnPreDrawListener` 而不是 `Choreographer.postFrameCallback`：
        // 后者在帧**开始**时回调（组合都还没跑），量不到我们要的那一段。
        val createdAt = SystemClock.uptimeMillis()
        window.decorView.viewTreeObserver.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (firstFrameLogged) return true
                    firstFrameLogged = true
                    window.decorView.viewTreeObserver.removeOnPreDrawListener(this)
                    Log.i(
                        TAG,
                        "首帧：onCreate 起 ${SystemClock.uptimeMillis() - createdAt}ms（布局完成、绘制前）",
                    )
                    return true
                }
            },
        )

        val application = application as CarPlayApplication
        controller = application.controller

        // 注入宿主能力，避免控制器反向依赖 Activity。
        controller.permissionGate = { requestPrerequisitesIfNeeded() }
        controller.permissionRequestAction = { requestPrerequisitesIfNeeded() }
        controller.mfiIdentityImportRequest = { launchMfiIdentityPicker() }
        controller.airPlayIconImportRequest = { launchAirPlayIconPicker() }
        controller.exitAction = { finishAndExit() }

        // 仪表主题：唯一内置主题先给首屏；云端缓存与网络刷新仍在
        // `dbplay-cluster-theme` 线程上做，`onCreate` 不发网络请求。
        // `:car:ui` 不依赖 `:car:core-bridge` ⇒ 由宿主读好、以纯数据下传。

        setContent {
            val model: CarUiModel by controller.model

            // 2026-10-23 设计系统重构：界面明暗由设置项 `uiAppearanceMode` 决定
            //（深色 / 浅色 / 跟随系统），不再写死。
            //
            // 历史教训：原来传 `isSystemInDarkTheme()`，但本机车机 uiMode 不随原厂
            // 日夜切换、恒为 false ⇒ `CarDarkColors` 那套 token 一次都没生效过，
            // 整个 App 永远是浅色。现在改由用户显式选择，`SYSTEM` 才去读系统。
            val uiAppearance = model.settings.uiAppearanceMode
            val uiDark = uiAppearance.resolveDark(isSystemInDarkTheme())
            CarPlayTheme(darkTheme = uiDark) {

                // 设置里改了"全屏 / 隐藏顶栏 / 隐藏底栏"后立刻生效，不需要重连。
                LaunchedEffect(
                    model.settings.fullscreen,
                    model.settings.hideTopBar,
                    model.settings.hideBottomBar,
                ) {
                    applySystemBars(
                        hideTopBar = model.settings.hideTopBar || model.settings.fullscreen,
                        hideBottomBar = model.settings.hideBottomBar || model.settings.fullscreen,
                    )
                }
                // `onUiTouch` 只做输入健康探针的埋点（PATCH-0105，诊断用）：
                // 它证明"这一下触摸确实走进了 Compose 的命中路径"。
                CarPlayApp(
                    model = model,
                    actions = controller,
                    onUiTouch = {
                        uiTouchSeen.set(true)
                        Log.i(TAG, "输入诊断：UI 层收到按下")
                    },
                )
            }
        }

        // 与上面 `OnPreDrawListener` 配对，把首帧拆成「等帧」与「帧内干活」两段。
        // `postFrameCallback` 挂在 CALLBACK_ANIMATION 上，在同一帧里**早于**
        // CALLBACK_TRAVERSAL ⇒ 它回调的时刻 ≈ 这一帧的开始；`onPreDraw` 则是
        // 布局完成后、绘制前。两个点相减就能判定那 300 ms 的归属：
        //   等帧占大头 ⇒ 主线程在等 VSYNC / 合成器，不在我们的代码里；
        //   帧内占大头 ⇒ 组合 + measure/layout + 会话接线。
        val onCreateDoneAt = SystemClock.uptimeMillis()
        Choreographer.getInstance().postFrameCallback {
            Log.i(
                TAG,
                "首帧回调：onCreate 起 ${SystemClock.uptimeMillis() - createdAt}ms" +
                    "（onCreate 结束起 ${SystemClock.uptimeMillis() - onCreateDoneAt}ms）",
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::controller.isInitialized) return
        val settings = controller.model.value.settings
        // 全屏（沉浸模式）是两条栏开关的上位：打开时强制都隐藏。
        applySystemBars(
            hideTopBar = settings.hideTopBar || settings.fullscreen,
            hideBottomBar = settings.hideBottomBar || settings.fullscreen,
        )
    }

    // ------------------------------------------------------------- 方控 / 按键

    /**
     * 把系统按键转给 CarPlay HID 通道（方控媒体键）。
     *
     * ⚠️ **这是基线 Android 平台唯一的按键入口。** 走 `KeyEvent` 的车机
     * （Android 基线、后装蓝牙旋钮）拿到按键的位置只有这里——适配器自己没有
     * Activity，拿不到 `dispatchKeyEvent`。曾经漏了这一环，导致该平台方控全死：
     * listener 挂上了、代码全绿、日志里一条错都不报，就是按键没反应。
     *
     * 用 `dispatchKeyEvent` 而不是 `onKeyDown`：前者在 Compose 的焦点/返回
     * 处理**之前**拿到事件，媒体键不会先被界面吃掉。
     *
     * 返回值交给控制器决定，只有 CarPlay 真的转发了才返回 true。
     * 一律返回 false 的按键：音量 / 静音 / MENU（见 `CarKeyHidMap.intentFor`），
     * 以及 **HOME / BACK**——本机硬键的来源标注是 `CarInputSource.PANEL`，
     * 而 `CarKeyHidMap` 只放行**方控来源**的 HOME/BACK。事件继续流向系统，
     * 所以音量仍由车机功放管，返回键仍能退出 CarPlay 界面。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!::controller.isInitialized) return super.dispatchKeyEvent(event)
        if (controller.handlePlatformKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    // ------------------------------------------------------------- 触摸健康探针

    /**
     * 输入健康探针（PATCH-0105，**诊断用，不改变任何行为**）。
     *
     * 只在一段触摸的**第一拍**（`ACTION_DOWN`）记时并起一个短定时器：
     * 若 [`CarPlayApp`] 的 `onUiTouch` 在 [INPUT_PROBE_TIMEOUT_MILLIS] 内没有把这个标记
     * 置位，说明**窗口层收到了触摸、Compose 没收到** —— 那是层级/命中测试的问题；
     * 而连这一行都不出现，就是"触摸根本没送进进程"（本次现场那一类，
     * 常见于窗口标志/输入法 insets 事务出问题）。
     *
     * 顺带做一次**猜测性自愈**：同一时刻请求一次 insets 重算
     * （`requestApplyInsets` 只重发 insets 通知，不改任何标志位，幂等且无副作用）。
     * 现场恢复点恰好是一次会话重建带来的窗口重排，这一步就是想在不重建会话的前提下
     * 把那次重排补上；修不好也无害。
     *
     * ⚠️ 不要把它当"业务逻辑"用：探针只回答"到了哪一层"，不参与任何判定。
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN) threePointerWindowSeen = false
        if (!threePointerWindowSeen && event.pointerCount >= 3) {
            threePointerWindowSeen = true
            if (lastThreePointerWindowLogAt == Long.MIN_VALUE || event.eventTime - lastThreePointerWindowLogAt >= 500L) {
                lastThreePointerWindowLogAt = event.eventTime
                Log.i(TAG, "输入诊断：window_multi_touch pointers=${event.pointerCount} action=$action；仅观察，不消费")
            }
        }
        if (action == MotionEvent.ACTION_DOWN) {
            lastWindowTouchDownAt = SystemClock.uptimeMillis()
            uiTouchSeen.set(false)
            Log.i(TAG, "输入诊断：窗口层收到按下")
            inputProbeHandler.postDelayed(
                {
                    if (!uiTouchSeen.get()) {
                        Log.w(
                            TAG,
                            "输入诊断：窗口层收到按下，但 UI 层 ${INPUT_PROBE_TIMEOUT_MILLIS}ms 内没收到" +
                                "（窗口/命中测试层；已请求一次 insets 重算）",
                        )
                        runCatching { ViewCompat.requestApplyInsets(window.decorView) }
                    }
                },
                INPUT_PROBE_TIMEOUT_MILLIS,
            )
        }
        return super.dispatchTouchEvent(event)
    }

    // ------------------------------------------------------- MFi 身份导入流程

    /**
     * 弹出文件选择器，让用户选 `identity.pk8`。
     *
     * MIME 类型放开通配（见 [MFI_IDENTITY_MIME_TYPES]）：`.pk8` / `.p7b`
     * 都不是标准 MIME，限定成 `application/octet-stream` 会在不少车机
     * 文件管理器里把文件全灰掉。
     * 真正的把关在 `CarMfiAccessoryKey` 的载入校验里——选错文件只会得到
     * 一条明确的中文提示，不会损坏现有身份。
     */
    private fun launchMfiIdentityPicker() {
        pendingMfiIdentityUri = null
        mfiIdentityLauncher.launch(MFI_IDENTITY_MIME_TYPES)
    }

    private fun launchMfiCertificatePicker() {
        mfiCertificateLauncher.launch(MFI_IDENTITY_MIME_TYPES)
    }

    // ----------------------------------------------------- AirPlay 图标选择流程

    private fun launchAirPlayIconPicker() {
        airPlayIconLauncher.launch(AIRPLAY_ICON_MIME_TYPES)
    }

    // ------------------------------------------------------------------ 权限流程

    /**
     * @return true 表示权限已就绪、可以立刻启动会话；
     *         false 表示已发起申请，稍后由回调继续。
     */
    private fun requestPrerequisitesIfNeeded(): Boolean {
        if (permissionRequestInFlight) return false

        val missing = missingRuntimePermissions()
        if (missing.isNotEmpty()) {
            permissionRequestInFlight = true
            runtimePermissionLauncher.launch(missing.toTypedArray())
            return false
        }
        return maybeRequestVpnConsent()
    }

    /** 运行权限齐备后，检查 VPN 授权。 */
    private fun proceedToStart() {
        if (maybeRequestVpnConsent()) {
            controller.startSession()
        }
    }

    /**
     * @return true 表示 VPN 已就绪（无需申请或已授权），可以继续；
     *         false 表示已弹出授权界面，稍后由回调继续。
     */
    private fun maybeRequestVpnConsent(): Boolean {
        val settings = controller.model.value.settings
        // 只有有线链路才依赖 VpnService（承载 IPv6 over NCM）。
        if (settings.transport != CarTransportMode.WIRED) return true
        if (vpnGranted) return true
        val intent: Intent? = (application as CarPlayApplication).services.vpnConsentIntent()
        if (intent == null) {
            vpnGranted = true
            return true
        }
        vpnConsentLauncher.launch(intent)
        return false
    }

    private fun missingRuntimePermissions(): List<String> {
        val settings = controller.model.value.settings
        val wanted = mutableListOf<String>()
        if (settings.microphone) wanted += Manifest.permission.RECORD_AUDIO
        if (settings.locationReporting) {
            wanted += Manifest.permission.ACCESS_FINE_LOCATION
            wanted += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (settings.isWireless) wanted += wirelessPermissions()
        return wanted.distinct().filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }
    }

    /** 与上游 `requiredWirelessPermissions()` 保持同一分级口径。 */
    private fun wirelessPermissions(): List<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.NEARBY_WIFI_DEVICES,
        )

        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )

        else -> listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    }

    // ---------------------------------------------------------------- 系统栏

    /**
     * 隐藏/显示系统栏。
     *
     * 车机上这一步不是"锦上添花"：状态栏和导航栏会直接挤压 CarPlay 画面区域，
     * 导致实际可用高度比协商出来的分辨率小，画面被拉伸。
     * 用 `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` 保证用户仍能从边缘划出系统栏。
     *
     * ## ⚠️ 为什么这里**恒定** `setDecorFitsSystemWindows(window, false)`
     *
     * 旧实现写的是 `setDecorFitsSystemWindows(window, !(hideTopBar && hideBottomBar))`：
     * 只要两条栏不是同时隐藏，就让系统替我们做 inset。这带来一个**只有车机才会踩到**的坑——
     *
     * 车机的状态栏常常是**厂商钉死的**：`insets.hide(statusBars())` 不报错、也不生效
     * （Android 的隐藏请求本来就只是"建议"，系统可以拒绝）。
     * 此时窗口仍然把状态栏那块地方让了出来，而我们的 Compose 层**完全不知道**
     * 有 inset 这回事（`grep WindowInsets car/ui/` 旧版零命中）——
     * 于是顶栏连同右上角的设置按钮被压在状态栏底下，用户点不到。
     * 这就是"开启隐藏顶部栏后按钮被遮挡"的完整成因。
     *
     * 现在改成**恒定 edge-to-edge**（`false`），inset 全部交给 Compose 处理
     * （`CarPlayApp` 根节点上的 `windowInsetsPadding(safeDrawing)`）。这样：
     * - 系统栏**隐藏成功** ⇒ inset 归零 ⇒ 内容铺满，就是用户要的"隐藏"效果；
     * - 系统栏**隐藏失败**（车机钉死）⇒ inset 非零 ⇒ 内容自动避开，
     *   按钮永远点得到——**不再依赖隐藏请求是否被系统接受**。
     *
     * 顺带解决双内边距：如果保留 `true`，系统已经让出的空间会与 Compose 的
     * `safeDrawing` 叠加，出现"状态栏高度的两倍"留白。
     *
     * `enableEdgeToEdge()`（API 29+ 把系统栏设为透明）与这里的 `false` 是配套的，
     * 两者必须同时成立，否则透明栏下面会露出未绘制区域。
     */
    private fun applySystemBars(hideTopBar: Boolean, hideBottomBar: Boolean) {
        // ------------------------------------------------------------------
        // 🔴 2026-10-12（PATCH-0105）：**输入法事务在途时整轮延后一帧**。
        //
        // 现场：「改完设置 ⇒ 别的按钮点不动 ⇒ 点一次重连就恢复」。
        // 三层证据都指向"触摸没送进进程"而不是"App 卡住"：
        //   `dumpsys gfxinfo` 累计帧数几分钟不涨、主线程空闲在
        //   `MessageQueue.nativePollOnce`、无 ANR，窗口 `isVisible=true` 且有 surface。
        // 而同一时刻的 `dumpsys window` 里留着一条铁证：IME 的 insets 控制**还攥着
        // 一条 `animation-leash of insets_animation`**，可 IME 自己已经
        // `mHasSurface=false` / `mImeShowing=false` / `mImeHeight=0`
        // ⇒ **insets 动画事务没走完**。
        //
        // 本函数原先同时走两条通道：`WindowInsetsControllerCompat.hide()/show()`
        // （现代通道，API 30+ 就是**带 leash 的动画**）**再**裸写
        // `decorView.systemUiVisibility`。而设置页有文本框、改设置又会触发自动保存与
        // 整页重组 ⇒ "输入法正忙 + 我们又把两条栏的标志重写一遍"，两条通道互相打架，
        // 事务就可能永远走不完。
        //
        // 纪律（本节的前三条）：
        // 1. **输入法可见时不写任何标志位**，整轮推迟一帧再看（`post`）。
        //    用一次性守卫 [systemBarsDeferredOnce]，避免"输入法一直开着 ⇒ 永远不应用"；
        // 2. **API 30+ 只走 Compat 通道**，不再裸写 `systemUiVisibility`
        //    （API 30+ 上它只是兼容层，写它等于绕过 insets 记账）；
        // 3. 裸写只留给 **API 28/29**（吉利 CS11 实证拒绝 Compat 请求，需要第二手，
        //    见 `FullscreenInsetContractTest`）。
        // 见 `SystemBarApplyDisciplineContractTest`（静态读源码，钉住这三条）。
        val decorView = window.decorView
        val imeVisible = ViewCompat.getRootWindowInsets(decorView)
            ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        if (imeVisible && !systemBarsDeferredOnce) {
            systemBarsDeferredOnce = true
            Log.i(TAG, "系统栏：输入法在途，本轮标志位延后一帧再写")
            decorView.post { applySystemBars(hideTopBar, hideBottomBar) }
            return
        }
        systemBarsDeferredOnce = false

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insets = WindowCompat.getInsetsController(window, decorView)
        insets.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        // ------------------------------------------------------------------
        // 状态栏图标配色（2026-10-06）。
        //
        // 场景：车机状态栏**大概率压在任何页面之上都会显示**（厂商钉死、拒绝 hide 请求），
        // 而本应用整体是深色的（画面页的视频、设置页的深色主题）。
        // 车机默认可能按"浅色背景"给深色图标，压在深色背景上会看不见。
        // 这里统一请求**浅色图标**（`isAppearanceLightStatusBars = false`）——
        // 在深色背景上对比度最高，也是绝大多数车机 SystemUI 的默认观感。
        // 它不改变状态栏是否显示，只保证"若它确实显示，也看得清"。
        insets.isAppearanceLightStatusBars = false
        insets.isAppearanceLightNavigationBars = false

        if (hideTopBar) {
            insets.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            insets.show(WindowInsetsCompat.Type.statusBars())
        }
        if (hideBottomBar) {
            insets.hide(WindowInsetsCompat.Type.navigationBars())
        } else {
            insets.show(WindowInsetsCompat.Type.navigationBars())
        }

        // ------------------------------------------------------------------
        // ⚠️ 旧 API 兜底（2026-10-05 实车修复）。
        //
        // 上面的 `insets.hide(...)` 是**请求**，系统可以拒绝。吉利 CS11
        // （Flyme Auto，Android 9）就拒绝了——日志实证 `vsysui=` 里始终带着
        // `LAYOUT_FULLSCREEN` 却没有 `HIDE_NAVIGATION` 生效，且窗口内容被切掉
        // 顶部 72px（`content=[0,72][0,0]` / `Visible insets = Rect(0, 72 - 0, 0)`）。
        //
        // 在 API 30 以下，`WindowInsetsControllerCompat` 内部本来就走
        // `SYSTEM_UI_FLAG_*`；但部分车机 ROM 对 **Compat 包装过的 setSystemUiVisibility**
        // 不认，只认裸标志位。这里再显式设一遍：不起作用也无害，起作用就赚到。
        //
        // 🔴 2026-10-12（PATCH-0105）：**这条兜底只留给 API 28/29**。
        // API 30+ 上系统栏状态由 `WindowInsetsController` 带着 leash 动画驱动，
        // 再裸写一次 `systemUiVisibility` 等于绕过它的记账 —— 现场那次
        // "IME 的 insets 事务永远走不完 ⇒ 窗口收不到触摸"就发生在这条路上。
        // 车机（CS11 / Android 9 = API 28）仍然保留这条兜底，能力一点没少。
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            val legacyFlags = decorView.systemUiVisibility
            var nextFlags = legacyFlags or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            if (hideTopBar) {
                nextFlags = nextFlags or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            }
            if (hideBottomBar) {
                nextFlags = nextFlags or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            }
            if (nextFlags != legacyFlags) {
                Log.i(TAG, "系统栏兜底 setSystemUiVisibility 0x${Integer.toHexString(nextFlags)}")
                decorView.systemUiVisibility = nextFlags
            }
        }

        // ------------------------------------------------------------------
        // 结果回读（2026-10-06，诊断用）。
        //
        // 为什么要打这条：车机"拒绝 hide 请求"是本项目的**已知事实**，但此前
        // 只能从 SystemUI 窗口的测量日志（`StatusBarWindowView 0,0-1920,72`）
        // 间接推断。这里在**设置后**立刻回读我们进程能看到的事实——
        // 窗口内容是否仍被顶部 inset 切掉、旧标志位实际是什么——
        // 让下一份诊断包能一次性判定"这次隐藏到底成没成"，不必再翻 SystemUI 日志。
        //
        // 注意：这只是**观测**，不改变任何行为；且 `window.decorView` 的
        // inset 要下一帧才刷新，所以用 `post` 推迟一帧读。
        val wantedTop = hideTopBar
        val wantedBottom = hideBottomBar
        window.decorView.post {
            val rootInsets = androidx.core.view.ViewCompat.getRootWindowInsets(window.decorView)
            val topInset = rootInsets?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: -1
            // ------------------------------------------------------------------
            // ⚠️ 2026-10-07 新增：**底栏也要回读**。
            //
            // 旧的回读只量了顶部 inset，于是"底栏看不见"这件事在诊断包里
            // 完全没有数据 —— 只能靠猜。实车反馈（0.10.10）明确指出
            // "底栏无显示，关闭隐藏底栏也没用"，而现有日志无法证伪任何一方。
            //
            // 这里补上底部 inset 与底部可见 inset。两者的区别很关键：
            // - `navigationBars().bottom` 是**系统告诉我们"这里有一条栏"**；
            // - `getInsetsIgnoringVisibility(navigationBars()).bottom` 是
            //   "即便这条栏当前隐藏，它占位的空间是多少"（>0 说明系统预留了）。
            // 配合 `tappableElement`（当前可点区域）能区分三种情况：
            //   ① 我们请求隐藏、系统拒绝   → visibleBottom > 0 且 vsysui 不带 HIDE_NAVIGATION
            //   ② 我们请求隐藏、系统接受   → visibleBottom == 0
            //   ③ 车机把空调条画在**我们自己窗口之外**（厂商 overlay）
            //      → 两个 bottom 都是 0，但屏幕上确实看得见 → 只能靠肉眼确认
            // 第 ③ 种是程序**无论如何都盖不住**的，日志量到 0 反而是正确结果；
            // 这次把它显式打出来，避免下次再把"设置没生效"和"栏不归我们管"混为一谈。
            val bottomInset =
                rootInsets?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: -1
            val bottomIgnoringVisibility = rootInsets
                ?.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars())
                ?.bottom ?: -1
            // ------------------------------------------------------------------
            // 2026-10-07：把这份实测写进跨层快照，供 bridge 层做画布对账。
            // 为什么不在 bridge 层直接量：那边拿不到可靠的 rootWindowInsets，
            // 且会在无窗口时机被调。写快照是单向的，不影响本方法任何行为。
            // ------------------------------------------------------------------
            runCatching {
                com.dbtool.carplay.android.CarWindowInsetsHolder.update(
                    com.dbtool.carplay.android.CarWindowInsetsHolder.Snapshot(
                        topPx = if (topInset > 0) topInset else 0,
                        bottomPx = if (bottomInset > 0) bottomInset else 0,
                        bottomIgnoringVisibilityPx =
                            if (bottomIgnoringVisibility > 0) bottomIgnoringVisibility else 0,
                    ),
                )
            }
            @Suppress("DEPRECATION")
            val flagsNow = window.decorView.systemUiVisibility
            Log.i(
                TAG,
                "系统栏回读：请求 top=${if (wantedTop) "hide" else "show"} " +
                    "bottom=${if (wantedBottom) "hide" else "show"}｜" +
                    "实测顶部 inset=${topInset}px｜" +
                    "实测底部 inset=${bottomInset}px（忽略可见性=${bottomIgnoringVisibility}px）｜" +
                    "vsysui=0x${Integer.toHexString(flagsNow)}" +
                    "（FULLSCREEN=${flagsNow and View.SYSTEM_UI_FLAG_FULLSCREEN != 0}" +
                    ", HIDE_NAV=${flagsNow and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION != 0}）" +
                    "｜结论=顶部${if (wantedTop && topInset > 0) "隐藏被车机拒绝" else "与请求一致"}" +
                    "、底部${if (wantedBottom && bottomInset > 0) "隐藏被车机拒绝" else "与请求一致"}",
            )
        }
    }

    /**
     * 当前安装包版本名。
     *
     * 不用 `BuildConfig.VERSION_NAME`：本模块没有开 `buildConfig` 生成
     * （AGP 8 默认关闭），而版本号本来就要从 `PackageManager` 读回，
     * 与「关于」页同一口径。
     */
    @Suppress("DEPRECATION")
    private fun appVersionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    // ------------------------------------------------------------------ 退出

    /** 彻底退出：车机场景下用户点"退出应用"期待的是进程结束，而不是回到桌面后还被拉起。 */
    private fun finishAndExit() {
        finishAndRemoveTask()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    private companion object {
        /** logcat 标签。与 `CarPlayApplication` 的 `carplay-startup` 同属启动诊断。 */
        const val TAG = "carplay-startup"

        /**
         * 进主题页时强制刷新云端目录的最小间隔：60 秒。
         *
         * 目录缓存 TTL 是 24 小时（`ClusterThemeCloudStore.TTL_MILLIS`），
         * 但授权态不能等那么久 —— 后台切换付费总开关后必须尽快跟上。
         * 60 秒是"够快"与"不把服务器打爆"之间的折中：目录只有几 KB，
         * 且 sha256 没变的主题文件不会重下。
         */

        /** 见 [launchMfiIdentityPicker] 的说明：非标准后缀，只能放开 MIME。 */
        val MFI_IDENTITY_MIME_TYPES = arrayOf("*/*")

        /** AirPlay 图标就是普通图片，限定类型可以让文件管理器把非图片灰掉。 */
        val AIRPLAY_ICON_MIME_TYPES = arrayOf("image/*")

        /**
         * 输入探针的判定窗口（PATCH-0105）。
         *
         * 800ms：正常的一拍 `ACTION_DOWN` 到 Compose 命中路径是**同一帧内**（<16ms），
         * 这里留足 50 倍余量，只用来区分"没送到"与"送到了"，不用于任何性能判断。
         */
        const val INPUT_PROBE_TIMEOUT_MILLIS = 800L
    }
}

/**
 * 主题页的界面快照。
 *
 * 列表与同步状态一起替换：分开写会出现"列表已换、底部还写着上一次时间"
 * 的中间帧。
 */

/**
 * 仪表主题模块入口门禁：模块已授权（云端 catalog 的 `licensed`），
 * **或**工程模式构建（[BuildConfig.ENGINEERING_MODE]）。
 *
 * 抽成纯函数是为了单测钉死真值表——它决定「点仪表主题入口是进主题页
 * 还是弹授权窗」，回归代价是整条授权链路。
 *
 * 工程版的来历（2026-10-19 车主要求）：模拟器没有服务端授权、也读不到
 * VIN，入口只会弹授权窗、主题页根本打不开。**刻意只做构建期开关**
 * （`-Pdbplay.engineering=true`）：运行时开关等于给最终用户留一个
 * "自己关掉付费授权"的口子，DBP 授权码体系会直接失效。
 */

/**
 * 主题链路测试用的占位 VIN。
 *
 * 约束：**恰好 17 位、且不含 I/O/Q**，否则过不了
 * `ThemeAuthorizationClient.normalizeVinForRequest` 的 `^[A-HJ-NPR-Z0-9]{17}$`
 * （GB 16735 禁用字母），顶上去也会被客户端自己挡掉——那这个测试开关就白加了。
 * 由 `ThemeVinFreeTest` 钉住这两条，改字面量先过那个测试。
 */

/**
 * 主题链路给界面/请求用的 VIN 判定（纯函数，单测入口）。
 *
 * 与 [themeModuleGateOpen] 同一套路：拆出来是为了钉真值表，也为了让
 * `openThemeRedeem` / `refreshThemeEntitlement` 两处共用同一套语义（散落写迟早漏一处）。
 *
 * - [cached] 非空（非空白）⇒ 原样返回，测试开关不介入（真 VIN 永远优先）；
 * - [cached] 空/空白、[vinFree] 为 false（普通出包）⇒ 返回 null，
 *   调用方据此走「未识别到 VIN」的原路径；
 * - [cached] 空/空白、[vinFree] 为 true ⇒ 返回 [THEME_VIN_PLACEHOLDER]，
 *   并回调 [log] 一行（现场别把占位值当成真 VIN 排查）。
 */
