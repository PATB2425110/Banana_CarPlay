package com.dbtool.carplay.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.dbtool.carplay.ui.theme.CarIcons
import com.dbtool.carplay.ui.theme.CarTheme
import java.util.Locale
import kotlin.math.roundToInt

/**
 * DBPlay 自己的底栏（Dock）。
 *
 * ## 它是什么
 *
 * 车机自带的那条底栏（`com.geely.dockbar`）默认被 DBPlay **隐藏**
 * （`CarSessionSettings.hideBottomBar` 出厂 true，可在设置里放开）。
 * 本组件是让 DBPlay **自己**在底部画一条同形态的栏：主页 / 两侧温度 /
 * 应用行 / 媒体卡。
 *
 * ## 尺寸从哪来（重要）
 *
 * 参考图是一张 950×166 的截图，**它的绝对缩放和屏幕密度不可知**
 * （既不是本机分辨率，也没法从截图反推 dp），所以全部尺寸都按
 * **相对应用图标**的比例定 —— 应用图标 = 32dp 作为锚点：
 *
 * | 元素 | 参考图实测 | 相对应用图标 | 本组件 |
 * | --- | --- | --- | --- |
 * | 面板高 | ≈52px | 2.1× | 72dp |
 * | 应用图标 | 25px | 1.0× | 32dp |
 * | 主页图标（图形） | 18px | 0.72× | 24dp |
 * | 箭头 / 播放 / 暂停 | 10–12px | ≈0.45× | 16dp |
 * | 应用图标视觉间距 | 24.5px | 0.98× | 32dp |
 * | 竖分隔线高 | 23px | 0.92× | 32dp |
 * | 温度数字（cap 高） | 14px | 0.56× | displayMedium（24sp） |
 * | 歌名（cap 高） | 8px | 0.32× | bodyLarge（15sp） |
 * | 专辑图 | 22px | 0.88× | 32dp |
 *
 * 面板底色取 [com.dbtool.carplay.ui.theme.CarColors.overlaySurface]：
 * 参考图里的面板是**半透**的（透得出后面的壁纸），而这个 token 的语义
 * 就是"浮在内容之上的一层"——盖在画面页的视频上、盖在页底上都成立。
 *
 * ## 数据缺失时的行为（有意设计）
 *
 * - [leftTemperature] / [rightTemperature] 为 null ⇒ **整块不渲染**（连分隔线一起）。
 *   不显示占位温度：一个假数字比没有数字糟得多。
 * - [media] 为 null ⇒ 不渲染媒体卡。
 * - 某个应用槽的 icon 为 null ⇒ 画一个淡底方块占位（用户可配但还没选时）。
 *
 * @param apps 应用行内容，从左往右。数量由调用方决定（参考图是 5 个）。
 */
@Composable
fun CarDockBar(
    apps: List<CarDockApp>,
    modifier: Modifier = Modifier,
    leftTemperature: CarDockTemperature? = null,
    rightTemperature: CarDockTemperature? = null,
    media: CarDockMedia? = null,
    onHome: () -> Unit = {},
    onTemperatureStep: (isLeft: Boolean, deltaCelsius: Float) -> Unit = { _, _ -> },
    onAppClick: (CarDockApp) -> Unit = {},
    onMediaToggle: () -> Unit = {},
    onMediaNext: () -> Unit = {},
    /** 编辑态：应用槽置灰、可拖拽换位。由调用方持有（见 `onLongPress`）。 */
    editMode: Boolean = false,
    /** 长按底栏（非编辑态）时回调 —— 调用方据此把 [editMode] 置 true。 */
    onLongPress: () -> Unit = {},
    /** 编辑态下长按底栏的回调 —— 调用方据此退出编辑。 */
    onExitEdit: () -> Unit = {},
    /**
     * 拖拽落位后回调**新的顺序**（应用 key 列表）。
     *
     * ⚠️ 传"完整顺序"而不是"从 a 移到 b"：调用方要拿它直接落库（`dockAppOrder`），
     * 相对位移的语义在"被拖的项中途被卸载"之类的边界上会算歪。
     */
    onReorder: (List<String>) -> Unit = {},
) {
    val dimens = CarTheme.dimens
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = dimens.dockBarMarginH)
            .height(dimens.dockBarHeight)
            .clip(CarTheme.shapes.medium)
            .background(CarTheme.colors.overlaySurface)
            .padding(horizontal = dimens.dockBarPaddingH),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimens.dockBarGap),
    ) {
        DockIconButton(
            icon = CarIcons.HomeSolid,
            contentDescription = "主页",
            glyphSize = dimens.dockHomeIcon,
            onClick = onHome,
        )

        DockDivider()

        if (leftTemperature != null) {
            DockTemperatureStepper(
                temperature = leftTemperature,
                contentDescriptionPrefix = "主驾温度",
            ) { delta -> onTemperatureStep(true, delta) }
            DockDivider()
        }

        // ⚠️ 应用行**不吃**富余宽度。
        //
        // 之前让它 `weight(1f)`，结果所有富余宽度都堆在它右边，中间空出一大块
        // （2026-10-11 车主截图圈出）。真机那条 dock 是靠**固定宽度的 smart bar**
        // 把中间吃掉的（`CsdDockView.SMART_BAR_WIDTH`）⇒ 这里改成让**媒体卡**
        // 吃满剩余宽度：标题与进度线跟着变长，歌名也不再被截断。
        //
        // 编辑态（2026-10-11）：置灰 + 拖动换位。
        // 非编辑态装"长按进编辑"，编辑态装"拖拽换位" —— **同一时刻只装一个**，
        // 否则两个手势检测器会互相抢（Compose 里多个 pointerInput 的优先级并不直观）。
        val density = LocalDensity.current
        val pitchPx = with(density) { (dimens.dockAppSlot + dimens.dockAppGap).toPx() }
        var dragFrom by remember { mutableStateOf(-1) }
        var dragPx by remember { mutableStateOf(0f) }
        val appsRowModifier = if (editMode) {
            Modifier.pointerInput(apps, pitchPx) {
                detectDragGestures(
                    onDragStart = { offset ->
                        dragFrom = (offset.x / pitchPx).toInt().coerceIn(0, (apps.size - 1).coerceAtLeast(0))
                        dragPx = 0f
                    },
                    onDrag = { _, amount -> dragPx += amount.x },
                    onDragEnd = {
                        // 落位按"拖过的槽位"四舍五入 —— 松手即生效，不做跟手预览
                        // （跟手要拖影 + 占位动画，是另一件事）。
                        val last = (apps.size - 1).coerceAtLeast(0)
                        val to = (dragFrom + (dragPx / pitchPx).roundToInt()).coerceIn(0, last)
                        if (dragFrom in apps.indices && to != dragFrom) {
                            onReorder(
                                apps.map { it.key }.toMutableList().apply { add(to, removeAt(dragFrom)) },
                            )
                        }
                        dragFrom = -1
                        dragPx = 0f
                    },
                    onDragCancel = { dragFrom = -1; dragPx = 0f },
                )
            }
        } else {
            Modifier
        }
        Row(
            modifier = appsRowModifier.alpha(if (editMode) 0.55f else 1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimens.dockAppGap),
        ) {
            apps.forEach { app ->
                DockAppSlot(
                    app = app,
                    onClick = { onAppClick(app) },
                    // ⚠️ 长按必须挂在**槽自己**身上（`combinedClickable`）。
                    // 之前挂在父行上用 `detectTapGestures`，结果子节点的 `clickable`
                    // 把 down/up 全吃掉了，父级**收不到长按**（实拍：长按后点槽
                    // 直接启动了应用，没进编辑态）。`combinedClickable` 天生按
                    // 按下时长分派单击/长按，不存在两套检测器抢事件的问题。
                    onLongPress = onLongPress,
                )
            }
        }

        if (media != null) {
            DockDivider()
            DockMediaCard(
                media = media,
                modifier = Modifier.weight(1f),
                onToggle = onMediaToggle,
                onNext = onMediaNext,
            )
        } else {
            // 没有媒体卡时（真实用户的默认状态）得留一个柔性空档，
            // 否则右侧的温度会贴到应用行后面，整条栏就散架了。
            Spacer(Modifier.weight(1f))
        }

        if (rightTemperature != null) {
            DockDivider()
            DockTemperatureStepper(
                temperature = rightTemperature,
                contentDescriptionPrefix = "副驾温度",
            ) { delta -> onTemperatureStep(false, delta) }
        }
    }
}

/** 底栏一侧的温度读数。 */
@Immutable
class CarDockTemperature(
    val celsius: Float,
    /** 点一次加减多少度。车机常见 0.5。 */
    val stepCelsius: Float = 0.5f,
)

/**
 * 底栏里的一个应用槽。
 *
 * [icon] 为 null 时画占位方块 —— 用户可配但还没选应用时就是这个状态。
 * [isFolder] 为 true 时画四宫格（参考图里有一个"文件夹"槽）。
 */
@Immutable
class CarDockApp(
    /** 稳定标识（包名或用户自定义 key），不要用下标。 */
    val key: String,
    val label: String,
    val icon: ImageBitmap? = null,
    val isFolder: Boolean = false,
)

/**
 * "选应用"面板里的一项。
 *
 * 与 [CarDockApp] 的区别：这里**不要图标**。面板是长列表（几十个应用），
 * 为了列出候选去解几十张位图既慢又占内存；真正进底栏的那几个才转位图。
 */
@Immutable
class CarDockAppChoice(
    val key: String,
    val label: String,
)

/** 底栏媒体卡的一份快照。 */
@Immutable
class CarDockMedia(
    val title: String,
    val isPlaying: Boolean,
    /** 0f..1f。 */
    val progress: Float,
    val artwork: ImageBitmap? = null,
)

@Composable
private fun DockTemperatureStepper(
    temperature: CarDockTemperature,
    contentDescriptionPrefix: String,
    onStep: (Float) -> Unit,
) {
    val dimens = CarTheme.dimens
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimens.spaceM),
    ) {
        DockIconButton(
            icon = CarIcons.ChevronLeft,
            contentDescription = "$contentDescriptionPrefix 降低",
            glyphSize = dimens.dockGlyphIcon,
            onClick = { onStep(-temperature.stepCelsius) },
        )
        // 数值大、单位小、单位贴数字顶部 —— 与参考图一致。
        // 整行 Bottom/Center 对齐会读成"23.5° 作为一个词居中"，
        // 而不是"一个读数"。
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = formatCelsius(temperature.celsius),
                style = CarTheme.type.titleLarge,
                color = CarTheme.colors.textPrimary,
                maxLines = 1,
            )
            Text(
                text = "°",
                style = CarTheme.type.bodyMedium,
                color = CarTheme.colors.textSecondary,
                maxLines = 1,
            )
        }
        DockIconButton(
            icon = CarIcons.ChevronRight,
            contentDescription = "$contentDescriptionPrefix 升高",
            glyphSize = dimens.dockGlyphIcon,
            onClick = { onStep(temperature.stepCelsius) },
        )
    }
}

@Composable
private fun DockMediaCard(
    media: CarDockMedia,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = CarTheme.dimens
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimens.spaceL),
    ) {
        Box(
            modifier = Modifier
                .size(dimens.dockMediaArt)
                .clip(CarTheme.shapes.small)
                .background(CarTheme.colors.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (media.artwork != null) {
                Image(
                    bitmap = media.artwork,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = CarIcons.Media,
                    contentDescription = null,
                    tint = CarTheme.colors.textSecondary,
                    modifier = Modifier.size(dimens.iconS),
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(dimens.spaceS),
        ) {
            Text(
                text = media.title,
                style = CarTheme.type.bodySmall,
                color = CarTheme.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(dimens.dockProgressHeight)
                    .clip(CircleShape)
                    .background(CarTheme.colors.outline),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(media.progress.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(CarTheme.colors.textPrimary),
                )
            }
        }

        DockIconButton(
            icon = if (media.isPlaying) CarIcons.Pause else CarIcons.Play,
            contentDescription = if (media.isPlaying) "暂停" else "播放",
            glyphSize = dimens.dockGlyphIcon,
            onClick = onToggle,
        )
        DockIconButton(
            icon = CarIcons.Next,
            contentDescription = "下一曲",
            glyphSize = dimens.dockGlyphIcon,
            onClick = onNext,
        )
    }
}

/**
 * 应用槽。
 *
 * 触摸目标是 [com.dbtool.carplay.ui.theme.CarDimensions.dockTouchTarget]（48dp），
 * 图形只有 [com.dbtool.carplay.ui.theme.CarDimensions.dockAppIcon]（32dp）——
 * 参考图里图标之间的视觉间距接近一个图标宽；把 32dp 的图标本身当触摸目标
 * 就低于车机的 56dp 下限，所以用"大触摸区 + 小图形"，两者的差额落在
 * 视觉间距里（见 [com.dbtool.carplay.ui.theme.CarDimensions.dockAppGap]）。
 */
@Composable
private fun DockAppSlot(
    app: CarDockApp,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    val dimens = CarTheme.dimens
    Box(
        modifier = Modifier
            .size(dimens.dockAppSlot)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onLongPress,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(dimens.dockAppIcon)
                .clip(CarTheme.shapes.large)
                .background(CarTheme.colors.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (app.isFolder) {
                Icon(
                    imageVector = CarIcons.Folder,
                    contentDescription = app.label,
                    tint = CarTheme.colors.textPrimary,
                    modifier = Modifier.size(dimens.iconM),
                )
            } else if (app.icon != null) {
                Image(
                    bitmap = app.icon,
                    contentDescription = app.label,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * 底栏图标按钮：**没有容器底色**（参考图里 ⌂ / ‹ / › / ⏸ / ⏭ 都是裸图形）。
 *
 * 不复用 `CarIconButton`：那个自带 `surfaceVariant` 圆底，是设置页的风格；
 * 底栏里一排圆底按钮会变成"一排悬浮按钮"，与参考图不符。
 * 触摸目标是 [com.dbtool.carplay.ui.theme.CarDimensions.dockTouchTarget]
 * （刻意小于页面用的 56dp，理由见该 token 的 KDoc）。
 */
@Composable
private fun DockIconButton(
    icon: ImageVector,
    contentDescription: String,
    glyphSize: Dp,
    onClick: () -> Unit,
) {
    val dimens = CarTheme.dimens
    Box(
        modifier = Modifier
            .size(dimens.dockTouchTarget)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = CarTheme.colors.textPrimary,
            modifier = Modifier.size(glyphSize),
        )
    }
}

/**
 * 竖分隔线。
 *
 * 颜色用 [com.dbtool.carplay.ui.theme.CarColors.outline] 而不是 `divider`：
 * `divider` 是 8% 白（给浅色页面用的"极淡"线），压在底栏的半透明深底上
 * 基本看不见；参考图里的分隔线是一眼能看到的实体线。
 */
@Composable
private fun DockDivider() {
    val dimens = CarTheme.dimens
    Box(
        modifier = Modifier
            .width(dimens.strokeThin)
            .height(dimens.dockDividerHeight)
            .background(CarTheme.colors.outline),
    )
}

/**
 * 温度文案。
 *
 * ⚠️ 必须显式用 [Locale.US]：车机语言是中文时 `String.format` 走默认 Locale，
 * 小数点在某些区域设置下会变成逗号（`23,5°`），而空调读数就是小数点。
 */
private fun formatCelsius(celsius: Float): String =
    String.format(Locale.US, "%.1f", celsius)
