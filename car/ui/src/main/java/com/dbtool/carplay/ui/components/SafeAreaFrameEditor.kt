package com.dbtool.carplay.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Text
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dbtool.carplay.api.settings.CarSettingsSafeArea
import com.dbtool.carplay.ui.theme.CarTheme
import kotlin.math.roundToInt

/**
 * 安全区「画框」编辑器（PATCH-0080，车主口径：按矩形模型 + 画框改）。
 *
 * ## 它替代了什么
 *
 * 之前是四个数字框（上 / 下 / 左 / 右内边距）。那个形态有两个毛病：
 *
 * 1. **用户要自己做算术**——想把"上方 72px 留给状态栏"表达出来，得知道面板多高、
 *    再算出下边该填多少；
 * 2. **它把"矩形数"当"内边距"用的那次事故就是这么来的**：外部直接把四个数塞进
 *    `/info` 的 `safeArea`，`top=1920`（比 1080 的面板还高）⇒ 高度为负 ⇒
 *    iPhone 直接拆会话。数字框对这种自相矛盾的值**没有任何约束**。
 *
 * 画框在**面板空间**里存矩形 `(x, y, w, h)`，出口一律走
 * [CarSettingsSafeArea.normalized] / `toInsets()` ⇒ 四边恒 ≥ 0 且自洽，
 * **结构上不可能发出负宽高**。
 *
 * ## 交互
 *
 * - 拖框内 → 整体移动；
 * - 拖四角 → 缩放（对角固定）；
 * - 松开即落值（每次拖动都 `onAreaChange`，由调用方写进 draft 草稿，不落盘）。
 *
 * ## 🔴 2026-10-11 修「画框不能实时拖动」（车主口径）
 *
 * 症状：手指拖着走，框只在原位附近来回弹、跟不上手指。
 *
 * 成因：拖动回调里用的是 `remember(area, …)` 算出来的那个矩形 —— 它是**手势开始那一刻**
 * 的快照，而 `pointerInput` 的 lambda 在 `area` 变化时**不会重启**。于是每个拖动事件都变成
 * "**最初的矩形** + **这一次的位移**"，下一事件又从最初那个矩形算起 ⇒ 框永远回到起点附近。
 *
 * 原版 `GeelyClusterSafeAreaEditorView` 的做法正相反：**矩形存在控件自己的字段里**，
 * `ACTION_MOVE` 时按 `(x - lastX, y - lastY)` 增量更新**当前值**、随后 `invalidate()` 并
 * 实时回调 `onAreaChanged`。这里照它办：拖动期间用 [liveRect] 当基准，**每次位移都累加到当前值上**。
 *
 * ## 坐标
 *
 * [canvasWidthPx] / [canvasHeightPx] 是**画布**尺寸（不是控件尺寸）——即
 * `CarSessionSettings.safeArea` 的坐标空间。控件按这个比例摆放预览，
 * 手势位移再按同一比例换算回面板 px。
 *
 * @param area 当前矩形（面板 px）
 * @param onAreaChange 拖动回调，参数**已归一化**（夹在画布内、尺寸合法）
 */
@Composable
fun SafeAreaFrameEditor(
    area: CarSettingsSafeArea,
    canvasWidthPx: Int,
    canvasHeightPx: Int,
    onAreaChange: (CarSettingsSafeArea) -> Unit,
    modifier: Modifier = Modifier,
) {
    require(canvasWidthPx > 0 && canvasHeightPx > 0) {
        "SafeAreaFrameEditor 需要正的画布尺寸（调用方负责空态）"
    }
    val colors = CarTheme.colors

    // 入口就归一化一次：外面存进来的值即使脏（比如历史数据），画出来的框也在屏内。
    val rect = remember(area, canvasWidthPx, canvasHeightPx) {
        area.normalized(canvasWidthPx, canvasHeightPx)
    }
    // 拖动期间以**这个**为基准累加位移（见类注释那条事故）；手抬起后交还给 `area`。
    var liveRect by remember(canvasWidthPx, canvasHeightPx) {
        mutableStateOf<CarSettingsSafeArea?>(null)
    }
    val shownRect = liveRect ?: rect
    // 外部（父组件归一化/draft 更新）传进来的最新值：拖动中途若被外部改写，下面按它对齐。
    val latestArea by rememberUpdatedState(area)
    var handle by remember { mutableStateOf<FrameHandle?>(null) }

    // 🔴 2026-10-11 车主口径：拖拽窗**按画布比例显示、并封顶高度**。
    //
    // 高度 = 可用宽度 / (canvasWidthPx / canvasHeightPx)，再夹到 [FRAME_MAX_HEIGHT_DP]。
    // 用 `BoxWithConstraints` 拿真实可用宽度：`fillMaxWidth` 的高度不能反过来依赖自身宽度，
    // 只能先量宽、再算高。
    //
    // ⚠️ 画布坐标空间**不变**（仍是 canvasWidthPx × canvasHeightPx）。显示尺寸与
    // 坐标空间的换算在拖动回调与绘制里各自按 sx/sy 独立完成，所以缩显后
    // 拖出来的框换算回真实 px 依然准确（见本文件 KDoc「坐标」一节）。
    val maxHeightDp = FRAME_MAX_HEIGHT_DP.dp
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val aspect = canvasWidthPx.toFloat() / canvasHeightPx.toFloat()
        val byAspect = maxWidth / aspect
        val frameHeight = minOf(byAspect, maxHeightDp)
        val density = androidx.compose.ui.platform.LocalDensity.current
        val previewWidthPx = with(density) { maxWidth.toPx() }
        val previewHeightPx = with(density) { frameHeight.toPx() }
        val sxPreview = previewWidthPx / canvasWidthPx
        val syPreview = previewHeightPx / canvasHeightPx
        val labelInsetPx = with(density) { 8.dp.toPx() }
        val labelGapPx = with(density) { 24.dp.toPx() }
        val labelTopInsetPx = with(density) { 4.dp.toPx() }
        val labelLeft = shownRect.x * sxPreview + labelInsetPx
        val labelTop = shownRect.y * syPreview + labelTopInsetPx
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(frameHeight),
        ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(canvasWidthPx, canvasHeightPx) {
                val touchRadiusPx = HANDLE_TOUCH_DP.dp.toPx()
                // 2026-10-24 手势仲裁（车主：安全区画框拖不动、页面也不滚）。
                //
                // 原来用 `detectDragGestures`：它只要越过 touchSlop 就会消费事件，
                // **不管 onDrag 里是否 consume** —— 所以手指落在画布空白处也会吃掉
                // 整段拖动，父级 verticalScroll 收不到，表现为"框没动、页面也不滚，
                // 像坏了"。另外命中测试是"第一个命中"而非"最近"，角点圆有一半画在
                // Canvas 外，边缘按压容易丢。
                //
                // 现在改成手动仲裁：**先命中检测，没命中直接放行**给父级滚动。
                // 命中后走 Initial 通道，在父级 verticalScroll 看到事件之前就消费。
                // 四角贴着画布边，晚消费会被整页滚动抢走，框看起来拖不动。
                awaitEachGesture {
                    val down = awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Initial,
                    )
                    val start = latestArea.normalized(canvasWidthPx, canvasHeightPx)
                    val hit = hitTest(
                        offset = down.position,
                        rect = start,
                        preview = size,
                        canvasWidthPx = canvasWidthPx,
                        canvasHeightPx = canvasHeightPx,
                        touchRadiusPx = touchRadiusPx,
                        insetPx = HANDLE_RADIUS_DP.dp.toPx(),
                    )
                    if (hit == null) return@awaitEachGesture // 未命中：不消费，父级滚
                    down.consume()
                    liveRect = start
                    handle = hit
                    com.dbtool.carplay.api.projection.ClusterLayerRuntime.setSafeAreaPreview(start)
                    var previous = down.position
                    try {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            // 用按下位置的差，不用 positionChange()：后者在 consume 之后是 0。
                            val delta = change.position - previous
                            previous = change.position
                            change.consume()
                            val sx = size.width.toFloat() / canvasWidthPx
                            val sy = size.height.toFloat() / canvasHeightPx
                            if (sx <= 0f || sy <= 0f) continue
                            val current = handle ?: continue
                            // 基准 = 拖动过程中的当前矩形（liveRect），不是手势开始时的快照。
                            val base = liveRect ?: latestArea.normalized(canvasWidthPx, canvasHeightPx)
                            val next = base.dragged(
                                handle = current,
                                dx = (delta.x / sx).roundToInt(),
                                dy = (delta.y / sy).roundToInt(),
                                canvasWidthPx = canvasWidthPx,
                                canvasHeightPx = canvasHeightPx,
                            )
                            liveRect = next
                            com.dbtool.carplay.api.projection.ClusterLayerRuntime.setSafeAreaPreview(next)
                            onAreaChange(next)
                        }
                    } finally {
                        handle = null
                        liveRect = null
                        com.dbtool.carplay.api.projection.ClusterLayerRuntime.setSafeAreaPreview(null)
                    }
                }
            },
    ) {
        val sx = size.width / canvasWidthPx
        val sy = size.height / canvasHeightPx
        val left = shownRect.x * sx
        val top = shownRect.y * sy
        val width = shownRect.width * sx
        val height = shownRect.height * sy

        // 面板底色：让用户看出"整块屏"的边界。
        drawRect(color = colors.surfaceVariant)

        // 预览示例：安全区内画一张简化地图/路线，拖动矩形时可直观看到
        // iPhone 内容将落在哪块区域。这里只是编辑器示意，不代表真实地图纹理。
        val mapLine = colors.primary.copy(alpha = 0.32f)
        val routeLine = colors.primary
        drawLine(color = mapLine, start = Offset(left, top + height * .28f),
            end = Offset(left + width, top + height * .72f), strokeWidth = 2f)
        drawLine(color = mapLine, start = Offset(left + width * .18f, top),
            end = Offset(left + width * .78f, top + height), strokeWidth = 2f)
        drawLine(color = routeLine, start = Offset(left + width * .08f, top + height * .82f),
            end = Offset(left + width * .42f, top + height * .55f), strokeWidth = 4f)
        drawLine(color = routeLine, start = Offset(left + width * .42f, top + height * .55f),
            end = Offset(left + width * .82f, top + height * .18f), strokeWidth = 4f)
        drawCircle(color = routeLine, radius = 5f,
            center = Offset(left + width * .08f, top + height * .82f))
        drawCircle(color = androidx.compose.ui.graphics.Color.White, radius = 5f,
            center = Offset(left + width * .82f, top + height * .18f))

        // 框外四块遮罩。分成四块而不是"整块 + 挖洞"：`DrawScope` 没有直接的
        // 挖洞 API，而四块矩形在这里是精确的（框恒在画布内，宽高恒 ≥ 1）。
        val scrim = colors.onSurface.copy(alpha = SCRIM_ALPHA)
        drawRect(color = scrim, size = Size(size.width, top))
        drawRect(
            color = scrim,
            topLeft = Offset(0f, top + height),
            size = Size(size.width, size.height - top - height),
        )
        drawRect(color = scrim, topLeft = Offset(0f, top), size = Size(left, height))
        drawRect(
            color = scrim,
            topLeft = Offset(left + width, top),
            size = Size(size.width - left - width, height),
        )

        // 框线
        drawRect(
            color = colors.primary,
            topLeft = Offset(left, top),
            size = Size(width, height),
            style = Stroke(width = FRAME_STROKE_DP.dp.toPx()),
        )

        // 左上角是 X/Y 的共同起点。
        drawCircle(
            color = colors.primary,
            radius = 6.dp.toPx(),
            center = Offset(left, top),
        )
        // 四角手柄画在框内，避免圆心落在画布边上、一半圆在控件外面点不到。
        val radius = HANDLE_RADIUS_DP.dp.toPx()
        listOf(
            FrameHandle.TOP_LEFT,
            FrameHandle.TOP_RIGHT,
            FrameHandle.BOTTOM_LEFT,
            FrameHandle.BOTTOM_RIGHT,
        ).forEach { corner ->
            drawCircle(
                color = colors.primary,
                radius = radius,
                center = handleCenter(left, top, left + width, top + height, corner, radius),
            )
        }
        }
        // XYWH 标签放在框的对应位置：X/Y 指向左上角，W 沿上边，H 沿左边。
        val labelModifier = Modifier
            .background(colors.primary.copy(alpha = 0.92f), CarTheme.shapes.small)
            .padding(horizontal = 5.dp, vertical = 2.dp)
        Text(
            text = "左上角 X=${shownRect.x}",
            color = androidx.compose.ui.graphics.Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.offset { IntOffset(labelLeft.roundToInt(), labelTop.roundToInt()) }.then(labelModifier),
        )
        Text(
            text = "左上角 Y=${shownRect.y}",
            color = androidx.compose.ui.graphics.Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.offset { IntOffset(labelLeft.roundToInt(), (labelTop + labelGapPx).roundToInt()) }.then(labelModifier),
        )
        Text(
            text = "↔ 宽 W=${shownRect.width}",
            color = androidx.compose.ui.graphics.Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.offset { IntOffset((shownRect.x * sxPreview + shownRect.width * sxPreview / 2f - labelGapPx).roundToInt(), labelTop.roundToInt()) }.then(labelModifier),
        )
        Text(
            text = "↕ 高 H=${shownRect.height}",
            color = androidx.compose.ui.graphics.Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.offset { IntOffset(labelLeft.roundToInt(), (shownRect.y * syPreview + shownRect.height * syPreview / 2f).roundToInt()) }.then(labelModifier),
        )
        }
    }
}

/** 拖动目标：框内 = 移动，四角 = 缩放（对角固定）。 */
private enum class FrameHandle { MOVE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

/** 角点圆心缩进框内，保证整圆和触摸区都在画布里。 */
private fun handleCenter(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    handle: FrameHandle,
    inset: Float,
): Offset {
    val ix = inset.coerceAtMost((right - left) / 2f)
    val iy = inset.coerceAtMost((bottom - top) / 2f)
    return when (handle) {
        FrameHandle.TOP_LEFT -> Offset(left + ix, top + iy)
        FrameHandle.TOP_RIGHT -> Offset(right - ix, top + iy)
        FrameHandle.BOTTOM_LEFT -> Offset(left + ix, bottom - iy)
        FrameHandle.BOTTOM_RIGHT -> Offset(right - ix, bottom - iy)
        FrameHandle.MOVE -> Offset((left + right) / 2f, (top + bottom) / 2f)
    }
}

/**
 * 命中测试。**角优先于框内**：角手柄与框内是重叠的，先判角才拖得动
 * （否则永远只会移动整框）。
 */
private fun hitTest(
    offset: Offset,
    rect: CarSettingsSafeArea,
    preview: IntSize,
    canvasWidthPx: Int,
    canvasHeightPx: Int,
    touchRadiusPx: Float,
    insetPx: Float,
): FrameHandle? {
    if (preview.width <= 0 || preview.height <= 0) return null
    val sx = preview.width.toFloat() / canvasWidthPx
    val sy = preview.height.toFloat() / canvasHeightPx
    val left = rect.x * sx
    val top = rect.y * sy
    val right = (rect.x + rect.width) * sx
    val bottom = (rect.y + rect.height) * sy
    val inset = insetPx

    // 命中点和画出来的圆是同一个位置：缩进框内，不落在画布外。
    val candidates = listOf(
        FrameHandle.TOP_LEFT,
        FrameHandle.TOP_RIGHT,
        FrameHandle.BOTTOM_LEFT,
        FrameHandle.BOTTOM_RIGHT,
    ).map { corner ->
        corner to handleCenter(left, top, right, bottom, corner, inset)
    }.mapNotNull { (h, p) ->
        val d2 = (offset.x - p.x) * (offset.x - p.x) + (offset.y - p.y) * (offset.y - p.y)
        if (d2 <= touchRadiusPx * touchRadiusPx) d2 to h else null
    }
    candidates.minByOrNull { it.first }?.let { return it.second }
    if (offset.x in left..right && offset.y in top..bottom) return FrameHandle.MOVE
    return null
}

/**
 * 按拖动目标改矩形，最后统一 [CarSettingsSafeArea.normalized] 兜底。
 *
 * 缩放时**不在这里挡"最小尺寸"**：`normalized` 已经用
 * `MIN_WIDTH_PIXELS / MIN_HEIGHT_PIXELS`（或画布本身更小就取画布）夹过一遍，
 * 两处都写反而容易不一致。
 */
private fun CarSettingsSafeArea.dragged(
    handle: FrameHandle,
    dx: Int,
    dy: Int,
    canvasWidthPx: Int,
    canvasHeightPx: Int,
): CarSettingsSafeArea {
    val moved = when (handle) {
        FrameHandle.MOVE -> copy(x = x + dx, y = y + dy)
        FrameHandle.TOP_LEFT -> copy(x = x + dx, y = y + dy, width = width - dx, height = height - dy)
        FrameHandle.TOP_RIGHT -> copy(y = y + dy, width = width + dx, height = height - dy)
        FrameHandle.BOTTOM_LEFT -> copy(x = x + dx, width = width - dx, height = height + dy)
        FrameHandle.BOTTOM_RIGHT -> copy(width = width + dx, height = height + dy)
    }
    return moved.normalized(canvasWidthPx, canvasHeightPx)
}

/** 框外遮罩的不透明度。够看出"外面不可用"，又不至于看不清面板轮廓。 */
private const val SCRIM_ALPHA = 0.55f

private const val FRAME_STROKE_DP = 4f

/** 角手柄半径（画出来的圆）。 */
/** 角点**看得见**的半径（车主：手柄要够大才抓得住）。 */
private const val HANDLE_RADIUS_DP = 15f

/**
 * 角手柄的**触摸**半径。刻意比画出来的圆大得多——车机上手指按不准，
 * 触摸目标要比视觉元素宽，否则"看得见但抓不住"。
 */
private const val HANDLE_TOUCH_DP = 34f

/**
 * 画框编辑区的**最大高度**（dp）——2026-10-11 车主口径「拖拽窗太大了，小一点」。
 *
 * ## 为什么改成"按比例 + 封顶"而不是写死 720
 *
 * 原来写死 `FRAME_HEIGHT_DP = 720`：车机密度 1.0 时它就是 720px。但本编辑器是
 * `fillMaxWidth()`，在 1920 宽的横屏上 ⇒ 编辑区高达 720dp，**几乎占满整屏**，
 * 下面的说明文字和「清除」按钮全被顶到屏幕外，用户得先滚一大段才看得到。
 *
 * 现在按**画布宽高比**算高度（1920:720 ≈ 2.67:1），再封顶到本值：
 *  - 宽屏（1920 内容宽）→ 高度 = 宽 / 2.67，仍可能很大，被 [FRAME_MAX_HEIGHT_DP] 截住；
 *  - 窄屏（如 600dp 内容宽）→ 高度 = 225dp，按比例自然缩小。
 *
 * ⚠️ 封顶**只影响编辑区在屏幕上的显示尺寸**，不改画布坐标空间 —— 拖动时
 * `sx = size.width / canvasWidthPx` / `sy = size.height / canvasHeightPx` 各自独立
 * 换算（见本文件第 129~130 行），所以封顶后**每个方向的比例仍然分别成立**，
 * 拖出来的框换算回真实 px 依然精确。视觉上略有纵横比偏差，换来的是
 * 编辑区不再吃掉整屏。
 */
private const val FRAME_MAX_HEIGHT_DP = 320f
