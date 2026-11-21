package com.dbtool.carplay.ui.screens

import android.graphics.SurfaceTexture
import android.view.MotionEvent
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import com.dbtool.carplay.api.display.ImmersiveNavigationPolicy
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import com.dbtool.carplay.ui.CarStatusIndicator
import com.dbtool.carplay.ui.CarTouchMapper
import com.dbtool.carplay.ui.ThreeFingerProjectionGesture
import com.dbtool.carplay.ui.CarUiAction
import com.dbtool.carplay.ui.CarUiActions
import com.dbtool.carplay.ui.CarUiModel
import com.dbtool.carplay.ui.components.CarButton
import com.dbtool.carplay.ui.components.CarButtonSize
import com.dbtool.carplay.ui.components.CarButtonVariant
import com.dbtool.carplay.ui.components.CarIconBadge
import com.dbtool.carplay.ui.components.indicatorColor
import com.dbtool.carplay.ui.components.indicatorContainerColor
import com.dbtool.carplay.ui.theme.CarIcons
import com.dbtool.carplay.ui.theme.CarTheme

/**
 * CarPlay 画面页。
 *
 * 设计原则：**这一页的主角是 iPhone 的画面，不是我们的 UI。**
 * 连接后不保留顶栏。默认只有视频层；用户开启的双玻璃卡片仅叠加在沉浸111上。
 *
 * ⚠️ **这里曾经挂过一个右上角「返回主页」按钮，已移除**（2026-10-06 车主决定）。
 * 它曾在 2026-10-06 被当成"必需项"加进来（理由是"本车机不把 BACK 派发给
 * 我们窗口，画面页会出不去"），但车主实车体验后认为**它在画面上很碍事**，故删。
 * 退出路径回到系统返回键；若实车再次出现"退不出去"，**别急着把按钮加回来**，
 * 先查面板键的 dispatch 链（细节见下方 `CarPlayScreen` 里 `session.showVideo` 分支的注释）。
 *
 * ⚠️ **这里曾经在左上角挂一个状态胶囊，上面写的是 `session.deviceName`。**
 * 已删除，两个理由：
 * 1. **那串字是 iPhone 的蓝牙名**（实测显示成"华为Mete 90"），
 *    在用户的 iPhone 上是可以随便改的昵称，摆在画面左上角既不是我们的
 *    品牌、也不是有用的信息，只是把别人的设备名贴在了我们的界面上；
 * 2. **连接状态应该只有一个出处**。状态本来就由主页的蓝牙提示承担
 *    （见 `HomeScreen` 的蓝牙连接提示），这里再飘一个"已连接"胶囊，
 *    等于同一件事有两个说法，断开时两边还可能不同步。
 *
 * 未连接时反过来：给一个明确的空状态，而不是让用户面对一块黑屏。
 */
@Composable
fun CarPlayScreen(
    model: CarUiModel,
    actions: CarUiActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    showDebugOverlay: Boolean = false,
) {
    val session = model.session
    val fpsCounter = remember { FpsCounter() }
    var fps by remember { androidx.compose.runtime.mutableStateOf(0f) }
    LaunchedEffect(model.settings.fpsOverlayEnabled, session.showVideo) {
        while (model.settings.fpsOverlayEnabled && session.showVideo) {
            delay(1000L)
            fps = fpsCounter.takePerSecond()
        }
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CarTheme.colors.videoBackground),
    ) {
        if (session.showVideo) {
            // ⚠️ **画面页不再有屏幕上的悬浮退出口**（2026-10-06 车主决定移除）。
            //
            // 历史：这里曾挂一个右上角「返回主页」蓝色胶囊（[CarPlayVideoExitButton]），
            // 起因是当时认定"本车机不把 BACK 作为 `KeyEvent` 派发给我们，画面页会出不去"。
            // 车主实测后反馈：**该按钮在画面上很碍事**（画面页的主角是 iPhone 的画面，
            // 一个常驻品牌蓝实底按钮压在视频右上角，观感像贴纸），故移除。
            //
            // 移除后画面页只剩 [CarPlayVideoSurface] 一个子节点。退出路径回到系统键：
            // - `CarPlayApp` 的 `BackHandler(enabled = stack.size > 1)` 正常路径下是启用的
            //   （栈 = [Home, CarPlay]），"按返回键弹回主页"本身成立；
            // - 车机面板返回键（`CarInputSource.PANEL`）被 `CarKeyHidMap` 刻意不放行，
            //   会走 `super.dispatchKeyEvent` 交给系统。
            // **若实车再次出现"退不出去"，不要直接把这个按钮加回来**——先确认车机面板键的
            // dispatch 链，再决定是补屏幕入口还是改用面板键放行（见 `REF-debug.md`）。
            CarPlayVideoSurface(actions = actions, fpsCounter = fpsCounter, projectionEnabled = model.settings.projectionEnabled,
                immersive = model.immersiveNavigationActive, model = model)
        } else {
            CarPlayEmptyState(model = model, actions = actions, onBack = onBack)
        }
        if (session.showVideo && model.settings.fpsOverlayEnabled) {
            FpsOverlay(fps, Modifier.align(Alignment.TopStart))
        }

        if (showDebugOverlay && model.logs.isNotEmpty()) {
            DebugLogOverlay(
                logs = model.logs,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(CarTheme.dimens.spaceM),
            )
        }
    }
}

/**
 * 视频输出层。
 *
 * 用 `TextureView` 而不是 `SurfaceView`：车机上视频区域会随分辨率/旋转/分屏
 * 动态改变尺寸，`SurfaceView` 的独立图层在尺寸变化时更容易出现闪黑边；
 * 上游也用 `TextureView`，保持一致避免引入新的适配问题。
 *
 * CarPlay HID 触摸描述符最多支持两个触点。超过两指时清空已上报触点并忽略
 * 当前整段超过 HID 能力的触摸序列，避免把三点触摸截成两点后误发给 iPhone；这只是输入能力保护，
 * 三指左右滑只在本地控制仪表投屏，整段触摸不会转发给 iPhone。
 */
@Composable
private fun CarPlayVideoSurface(actions: CarUiActions, fpsCounter: FpsCounter, projectionEnabled: Boolean, immersive: Boolean,
    model: CarUiModel) {
    val currentProjectionEnabled by rememberUpdatedState(projectionEnabled)
    val currentActions by rememberUpdatedState(actions)
    var waitingForTarget by remember(immersive) { androidx.compose.runtime.mutableStateOf(immersive) }
    var navigationVisible by remember(immersive) { androidx.compose.runtime.mutableStateOf(false) }
    var returnToMain by remember(immersive) { androidx.compose.runtime.mutableStateOf<(() -> Unit)?>(null) }
    // The inner handler takes precedence over the app navigation stack only on 111.
    BackHandler(enabled = immersive && navigationVisible) { returnToMain?.invoke() }
    LaunchedEffect(waitingForTarget) {
        if (waitingForTarget) {
            delay(5_000)
            currentActions.reportProjectionGesture("target=main_video result=first_frame_wait_timeout retained=current")
        }
    }
    Box(Modifier.fillMaxSize()) {
    // Rebuild surfaces when the connection-level mode changes; no cross-owner ALT surface reuse.
    androidx.compose.runtime.key(immersive) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            val policy = ImmersiveNavigationPolicy().apply {
                configure(immersive)
                if (immersive) requestNavigation(currentActions.immersiveNavigationWindowSelected)
            }
            val container = FrameLayout(context)
            val alt = TextureView(context).apply { isOpaque = false }
            lateinit var main: TextureView
            var front = ImmersiveNavigationPolicy.Screen.MAIN_110
            fun updateVisible() {
                waitingForTarget = policy.waiting()
                val target = policy.current()
                navigationVisible = target == ImmersiveNavigationPolicy.Screen.NAVIGATION_111
                if (target == front) return
                // Keep the pending TextureView attached and drawable underneath.
                // alpha=0/GONE can suppress its update callback on some devices,
                // preventing a first-frame-gated switch forever.
                if (target == ImmersiveNavigationPolicy.Screen.NAVIGATION_111) alt.bringToFront()
                else main.bringToFront()
                front = target
                if (immersive) currentActions.rememberImmersiveNavigationWindow(
                    target == ImmersiveNavigationPolicy.Screen.NAVIGATION_111)
            }
            returnToMain = {
                currentActions.onTouch(emptyList())
                policy.requestNavigation(false)
                updateVisible()
                currentActions.reportProjectionGesture("target=main_110 source=system_back waiting=${policy.waiting()}")
            }
            main = TextureView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                isOpaque = false
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(
                        surfaceTexture: SurfaceTexture,
                        width: Int,
                        height: Int,
                    ) {
                        actions.onVideoSurfaceAvailable(android.view.Surface(surfaceTexture))
                        if (width > 0 && height > 0) {
                            actions.onVideoCanvasSizeChanged(width, height)
                        }
                    }

                    override fun onSurfaceTextureSizeChanged(
                        surfaceTexture: SurfaceTexture,
                        width: Int,
                        height: Int,
                    ) {
                        if (width > 0 && height > 0) {
                            actions.onVideoCanvasSizeChanged(width, height)
                        }
                    }

                    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                        actions.onVideoSurfaceDestroyed()
                        return true
                    }

                    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {
                        fpsCounter.frame(); policy.onFirstFrame(110); updateVisible()
                    }
                }
                val projectionGesture = ThreeFingerProjectionGesture(80f * resources.displayMetrics.density)
                var lastGestureDiagnosticAt = Long.MIN_VALUE
                var lastGestureResultAt = Long.MIN_VALUE
                fun gestureDiagnostic(message: String, time: Long) {
                    val result = message.startsWith("phase=result")
                    val previous = if (result) lastGestureResultAt else lastGestureDiagnosticAt
                    if (previous == Long.MIN_VALUE || time - previous >= 500L) {
                        if (result) lastGestureResultAt = time else lastGestureDiagnosticAt = time
                        currentActions.reportProjectionGesture(message)
                    }
                }
                setOnTouchListener { view, event ->
                    val action = event.actionMasked
                    val gestureAction = when (action) {
                        MotionEvent.ACTION_DOWN -> ThreeFingerProjectionGesture.Action.DOWN
                        MotionEvent.ACTION_POINTER_DOWN -> ThreeFingerProjectionGesture.Action.POINTER_DOWN
                        MotionEvent.ACTION_POINTER_UP -> ThreeFingerProjectionGesture.Action.POINTER_UP
                        MotionEvent.ACTION_UP -> ThreeFingerProjectionGesture.Action.UP
                        MotionEvent.ACTION_CANCEL -> ThreeFingerProjectionGesture.Action.CANCEL
                        else -> ThreeFingerProjectionGesture.Action.MOVE
                    }
                    val gesture = projectionGesture.onEvent(gestureAction,
                        (0 until event.pointerCount).map { i -> ThreeFingerProjectionGesture.Point(event.getPointerId(i), event.getX(i), event.getY(i)) },
                        event.eventTime)
                    if (gesture.releasePhone) {
                        currentActions.onTouch(emptyList())
                        gestureDiagnostic("phase=three_pointer_start pointers=${event.pointerCount} enabled=$currentProjectionEnabled thresholdDp=80 timeoutMs=1500", event.eventTime)
                    }
                    if (gesture.consume) {
                        if (gesture.showProjection != null || gesture.reason != null) {
                            gestureDiagnostic("phase=result result=${gesture.reason} enabled=$currentProjectionEnabled dispatched=${currentProjectionEnabled && gesture.showProjection != null}", event.eventTime)
                        }
                        if (immersive) gesture.showProjection?.let {
                            currentActions.onTouch(emptyList())
                            policy.requestNavigation(it); updateVisible()
                            currentActions.reportProjectionGesture("target=main_${if (it) 111 else 110} waiting=${policy.waiting()}")
                        } else if (currentProjectionEnabled) gesture.showProjection?.let { currentActions.setClusterVisible(it) }
                        return@setOnTouchListener true
                    }
                    // 新一轮 DOWN 时清掉任何旧触点，避免 iPhone 侧残留按压状态。
                    if (action == MotionEvent.ACTION_DOWN) {
                        currentActions.onTouch(emptyList())
                    }
                    // ALT is display-only. Never send touches on its map to MAIN.
                    if (policy.current() == ImmersiveNavigationPolicy.Screen.MAIN_110) {
                        currentActions.onTouch(CarTouchMapper.contacts(event, view.width, view.height))
                    }
                    true
                }
            }
            container.addView(main, FrameLayout.LayoutParams(-1, -1))
            if (immersive) {
                var altSurface: android.view.Surface? = null
                alt.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                        altSurface = android.view.Surface(texture)
                        currentActions.onImmersiveSurfaceAvailable(altSurface)
                    }
                    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit
                    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                        currentActions.onImmersiveSurfaceAvailable(null)
                        altSurface?.release(); altSurface = null
                        return true
                    }
                    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
                        policy.onFirstFrame(111); updateVisible()
                    }
                }
                alt.setOnTouchListener { _, event -> main.dispatchTouchEvent(event) }
                container.addView(alt, 0, FrameLayout.LayoutParams(-1, -1))
            }
            container
        },
    )
    }
    if (immersive && navigationVisible && model.settings.immersiveGlassCardsEnabled) {
        ImmersiveGlassCards(model, actions)
    }
    if (waitingForTarget) Text(
        text = "等待目标视频首帧，保留当前画面",
        color = androidx.compose.ui.graphics.Color.White,
        modifier = Modifier.align(Alignment.BottomCenter).background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.65f)).padding(12.dp),
    )
    }
}

private class FpsCounter {
    private var frames = 0L
    private var last = 0L
    @Synchronized fun frame() { frames++ }
    @Synchronized fun takePerSecond(): Float {
        val value = frames - last
        last = frames
        return value.toFloat()
    }
}

/**
 * 未连接时的空状态。
 *
 * 这一页的底色是 `videoBackground`（近黑），所以图标圆底与文字都直接用
 * 语义色本身，而不是浅底档——浅色块压在近黑底上会像贴纸。
 */
@Composable
private fun CarPlayEmptyState(
    model: CarUiModel,
    actions: CarUiActions,
    onBack: () -> Unit,
) {
    val dimens = CarTheme.dimens
    val session = model.session
    val icon = when (session.indicator) {
        CarStatusIndicator.ERROR -> CarIcons.Error
        CarStatusIndicator.WORKING -> CarIcons.Refresh
        else -> CarIcons.Link
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(dimens.spaceXxl),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(dimens.spaceM),
        ) {
            CarIconBadge(
                icon = icon,
                tint = indicatorColor(session.indicator),
                container = indicatorContainerColor(session.indicator),
                size = dimens.iconXl * 1.6f,
            )
            Spacer(Modifier.height(dimens.spaceS))
            Text(
                text = session.title,
                style = CarTheme.type.displayMedium,
                color = CarTheme.colors.onVideoSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = session.subtitle,
                style = CarTheme.type.bodyLarge,
                color = CarTheme.colors.onVideoSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(0.6f),
            )
            Spacer(Modifier.height(dimens.spaceM))
            Row(horizontalArrangement = Arrangement.spacedBy(dimens.spaceM)) {
                // 判据取状态机：`RECONNECT` 只在 FAILED 时入集，重连排期/重建中
                // 不可点——否则用户点一下就会绕过退避、重置自动重连的次数。
                CarButton(
                    text = "重新连接",
                    onClick = actions::reconnect,
                    enabled = CarUiAction.RECONNECT in session.availableActions,
                    size = CarButtonSize.Large,
                    icon = CarIcons.Refresh,
                )
                CarButton(
                    text = "返回主页",
                    onClick = onBack,
                    variant = CarButtonVariant.Secondary,
                    size = CarButtonSize.Large,
                )
            }
        }
    }
}

/** 调试日志浮层。半透明表面 + 等宽字体，可滚动。 */
@Composable
private fun DebugLogOverlay(
    logs: List<String>,
    modifier: Modifier = Modifier,
) {
    val dimens = CarTheme.dimens
    val listState = rememberLazyListState()
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.scrollToItem(logs.lastIndex)
    }
    Box(
        modifier = modifier
            .fillMaxWidth(0.5f)
            .background(
                color = CarTheme.colors.overlaySurface,
                shape = CarTheme.shapes.medium,
            )
            .padding(dimens.spaceM),
    ) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
            itemsIndexed(logs) { _, line ->
                Text(
                    text = line,
                    style = CarTheme.type.mono,
                    color = CarTheme.colors.textSecondary,
                    modifier = Modifier.padding(vertical = dimens.spaceXs / 2),
                )
            }
        }
    }
}
