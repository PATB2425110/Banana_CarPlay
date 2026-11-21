package com.dbtool.carplay.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import com.dbtool.carplay.ui.CarStatusIndicator
import com.dbtool.carplay.ui.theme.CarIcons
import com.dbtool.carplay.ui.theme.CarMotion
import com.dbtool.carplay.ui.theme.CarTheme
import com.dbtool.carplay.ui.theme.LocalReduceMotion

/**
 * 连接状态主视觉。
 *
 * ## 为什么从"插画"改成"状态页"
 *
 * 旧版是一幅 756 行的自绘插画（手机 → 通道 → 应用坞 + 粒子/弹射/卡死三种动效）。
 * 它的问题不是画得不好，而是**信息效率为负**：
 *
 * - 插画里的"手机""通道""坞"是隐喻，用户需要先解码隐喻才能知道现在到底在等什么；
 * - 三种动效的差别（正常 / 卡死）要靠**看动画**才能分辨，而车机上没人会盯着看 4 秒；
 * - 主视觉占据了整屏，真正有用的"当前阶段"和"链路参数"反而没有位置放。
 *
 * 现在换成状态页，三段结构各回答一个问题：
 *
 * | 区块 | 回答的问题 |
 * | --- | --- |
 * | 状态枢纽（圆底 + 图标 + 进度环） | **现在是什么状态**（一瞥即可，不依赖动画） |
 * | 主标题 / 副标题 | 发生了什么、下一步会怎样 |
 * | 关键参数表 | 链路 / 分辨率 / 编码等细节 |
 *
 * 图标底色用语义色的浅底档（[com.dbtool.carplay.ui.theme.CarColors.successContainer]
 * 等）而不是描边——这是 Ant / Arco 一致的做法：**状态用"色块"表达，不用"线框"**。
 *
 * ## 「进行中」为什么不放在进度条上
 *
 * 旧版在图标下面摆一条 4dp 不定量进度条。问题是进度条与图标是两个互不相关的元素，
 * 眼睛要在两处之间来回跳。把"还在推进"画到图标**外围**之后，图标本身就成了指示器：
 * 一眼看完，不需要二次扫视。这是 iOS / watchOS 处理"进行中"的统一做法，
 * 也是这一版删掉进度条的原因——不是省事，是**同一个语义不该有两个指示器**。
 *
 * @param indicator 状态语义，唯一决定强调色，见 [indicatorColor]。
 * @param iconTint 图标色。传 null（默认）时跟随 [indicator] 的语义色；
 *   传具体颜色用于**品牌标志**——Apple 标志不该跟着状态变蓝变红，
 *   它应当像所有品牌标志一样保持单色（浅色底黑标、深色底白标，即 `textPrimary`）。
 *   此时状态语义仍由圆底与进度环表达，不会丢信息。
 * @param busy 是否处于"正在推进"状态。只有此时枢纽才转起来——
 *   失败态转圈会让用户以为它还在自己重试。
 * @param facts 关键参数表。为空则不占位。
 * @param actionText 主操作文案。与 [onAction] 同时非空时才渲染。
 *   状态页上的按钮**一律按主操作处理**（`Primary` + `Large`）：整页只有它一个可点的
 *   东西，用次级描边会让"出错了该怎么办"变成一个需要找的答案。
 * @param actionIcon 主操作图标，可空。
 * @param secondaryActionText 次要操作文案（失败页的「打开设置」）。与
 *   [onSecondaryAction] 同时非空时才渲染。
 *
 *   为什么要给失败页第二条路：现场最常见的失败原因是**配置不对**
 *   （例如认证方式选了远程认证却没填服务器地址），这时「重试」按多少次都一样，
 *   必须去设置里改。右上角那个齿轮虽然能进设置，但用户此刻正盯着
 *   「连接失败」四个字，指望他自己去角落找入口并不合理。
 * @param tertiaryActionText 第三条操作（失败页的「去打开 ADB 调试」）。
 *   只在 [tertiaryActionText] 与 [onTertiaryAction] 同时非空时渲染。
 *
 *   它和「打开设置」是**两条不同的路**：后者进的是**本应用**的设置页，
 *   前者进的是**车机系统**的设置页。自建热点失败在"ADB 没开"这一步时，
 *   用户要改的开关根本不在 DBPlay 里——把他送进我们自己的设置页毫无用处。
 *   这是两个并排的【次级】按钮，不抢主操作「重试」的位置。
 *
 *   ⚠️ 这里曾经还配有一块 `guidanceTitle` / `guidanceSteps` 的「怎么办」图文步骤
 *   （2026-10-06 按车主意见删除）——失败页本身已经够长，再压一块 4 步说明
 *   把版面撑得很挤，反而看不清按钮。快捷按钮就是那条指引的"一键版"。
 * @param extraContent 失败页最下方的**附加块**（当前只用于「立即建立热点 + ADB（永久）」）。
 *   与上面三条按钮的区别：它不是"另一个操作按钮"，而是一条**兜底重活**——
 *   就地起热点并把 ADB 打开（永久），成功后整个连接流程会自动继续，用户不需要再点别的。
 *   因为它比「重试」「打开设置」重一档，所以**独占一行**，并统一收窄到
 *   `contentMaxWidth * 0.42f`（见渲染处的注释）。
 */
@Composable
fun CarStatusView(
    title: String,
    subtitle: String,
    indicator: CarStatusIndicator,
    modifier: Modifier = Modifier,
    icon: ImageVector = CarIcons.Link,
    facts: List<CarFact> = emptyList(),
    busy: Boolean = false,
    iconTint: Color? = null,
    actionText: String? = null,
    actionIcon: ImageVector? = null,
    onAction: (() -> Unit)? = null,
    secondaryActionText: String? = null,
    secondaryActionIcon: ImageVector? = null,
    onSecondaryAction: (() -> Unit)? = null,
    tertiaryActionText: String? = null,
    tertiaryActionIcon: ImageVector? = null,
    onTertiaryAction: (() -> Unit)? = null,
    extraContent: (@Composable () -> Unit)? = null,
) {
    val colors = CarTheme.colors
    val dimens = CarTheme.dimens

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        StatusHub(
            icon = icon,
            accent = indicatorColor(indicator),
            iconTint = iconTint ?: indicatorColor(indicator),
            containerColor = indicatorContainerColor(indicator),
            busy = busy,
        )

        Spacer(Modifier.height(dimens.spaceXl))

        // 标题与副标题随阶段整体换掉：交叉淡入 + 从下方 1/3 行高滑入。
        //
        // 为什么要动：一次无线连接会在十来秒里连续走过
        // 「正在连接 iPhone → 正在连接车机 → 已连接」三段。纯文字替换会让人怀疑
        // "是不是界面刷新了"；淡入让"变的是状态"这件事被看见。
        // 只做位移与不透明度，**不做缩放**——文字缩放会掉清晰度，车机上很显眼。
        AnimatedContent(
            targetState = title to subtitle,
            transitionSpec = {
                (
                    fadeIn(
                        animationSpec = tween(
                            durationMillis = CarMotion.DurationMedium,
                            easing = CarMotion.EasingEnter,
                        ),
                    ) + slideInVertically(
                        animationSpec = tween(
                            durationMillis = CarMotion.DurationMedium,
                            easing = CarMotion.EasingEnter,
                        ),
                    ) { fullHeight -> fullHeight / 3 }
                    ).togetherWith(
                    fadeOut(animationSpec = tween(durationMillis = CarMotion.DurationFast)),
                )
            },
            label = "statusText",
        ) { (titleText, subtitleText) ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // 预留一行标题 + 一行副标题的高度：副标题可能为空，
                    // 不预留的话枢纽会随阶段上下跳动。
                    .heightIn(min = dimens.spaceHuge * 2f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = titleText,
                    style = CarTheme.type.displayLarge,
                    color = colors.textPrimary,
                    textAlign = TextAlign.Center,
                )

                if (subtitleText.isNotEmpty()) {
                    Spacer(Modifier.height(dimens.spaceS))
                    Text(
                        text = subtitleText,
                        style = CarTheme.type.bodyLarge,
                        color = colors.textSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(0.72f),
                    )
                }
            }
        }

        if (facts.isNotEmpty()) {
            Spacer(Modifier.height(dimens.spaceXl))
            CarFactsBand(
                facts = facts,
                centered = true,
                modifier = Modifier.widthIn(max = dimens.contentMaxWidth * 0.5f),
            )
        }

        if (onAction != null && actionText != null) {
            Spacer(Modifier.height(dimens.spaceXl))
            Row(
                // 两个按钮之间留 spaceL：spaceM(12dp) 比按钮自身的水平内边距
                // （16dp）还小，两个按钮会"粘"成一块，读不出是两个独立操作。
                horizontalArrangement = Arrangement.spacedBy(dimens.spaceL),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CarButton(
                    text = actionText,
                    onClick = onAction,
                    variant = CarButtonVariant.Primary,
                    size = CarButtonSize.Large,
                    icon = actionIcon,
                )
                // 次要操作**必须与主操作同尺寸**：两个按钮一高一矮会被读成
                // "一个是按钮、另一个是文字链接"，而它们都是这一屏的出路。
                if (onSecondaryAction != null && secondaryActionText != null) {
                    CarButton(
                        text = secondaryActionText,
                        onClick = onSecondaryAction,
                        variant = CarButtonVariant.Secondary,
                        size = CarButtonSize.Large,
                        icon = secondaryActionIcon,
                    )
                }
                // 第三条（去车机系统设置开 ADB）。同为次级样式——
                // 它不是"更重要的操作"，只是"另一条同样重要的路"。
                if (onTertiaryAction != null && tertiaryActionText != null) {
                    CarButton(
                        text = tertiaryActionText,
                        onClick = onTertiaryAction,
                        variant = CarButtonVariant.Secondary,
                        size = CarButtonSize.Large,
                        icon = tertiaryActionIcon,
                    )
                }
            }
        }

        // 失败页的「就地建立热点 + ADB」。
        //
        // ## 为什么不并进上面那排按钮（2026-10-08 真机反馈）
        //
        // 上面那排走 `Row` + `wrapContent`：宽度**贴合文案**。而这条是
        // `fillMaxWidth()` 的通栏按钮，两者叠在同一屏上，一条贴文案、一条
        // 铺满整宽，版式立刻失衡——车主原话是
        // 「**按钮尺寸过大，通栏铺满屏幕，页面比例不协调**」。
        //
        // ## 🔴 2026-10-08 二次修正：**收窄必须写在独立的里层 Box 上**
        //
        // 上一版写的是 `Modifier.fillMaxWidth().widthIn(max = X)`（同一个节点上），
        // 车主复测仍然**通栏铺满**——像素实测：按钮宽 1075px，而内容列只有
        // 1075px、旁边「重试」按钮只有 69px。即 `widthIn` 被**完全吃掉**。
        //
        // 根因（`javap` 反汇编 `foundation-layout` 的 `SizeNode.measure` 实证）：
        //
        // ```
        // targetConstraints = getTargetConstraints(density)   // widthIn → (min=0, max=X)
        // wrapped = incomingConstraints.constrain(target)     // ← 把 target 反向 coerce 进 incoming
        // ```
        //
        // `Constraints.constrain(other)` 是
        // `other.minWidth.coerceIn(this.minWidth, this.maxWidth)` 一类，
        // 而 `fillMaxWidth()` 已经把它传下来的约束**锁成 `min = max = 父宽`**
        // ⇒ 内层 `widthIn` 的 `max = X` 被 `coerceIn(父宽, 父宽)` 拉回**父宽**。
        // **同轴（宽度）上 `fillMaxWidth()` 之后写 `widthIn` 等于没写。**
        // （跨轴不受影响：`fillMaxWidth().heightIn(…)` 是安全的，因为宽度锁死
        //  不改变高度区间。本仓另有两处就是跨轴写法，无需改。）
        //
        // 正确写法 = **外层负责铺满、里层负责收窄**（`Box` 给子项的约束会把
        // `minWidth` 放松回 0，所以里层的 `widthIn` 这次真的生效）：
        //
        // ```
        // Box(Modifier.fillMaxWidth(), contentAlignment = Center) {
        //     Box(Modifier.widthIn(max = X)) { extraContent() }
        // }
        // ```
        //
        // ## 上限取多少
        //
        // `contentMaxWidth` 的 **0.22f**。这不是"某个写死的 dp"，而是取内容宽的
        // 一个固定比例 —— 主按钮群变宽变窄，这里自动跟着走。
        //
        // 为什么不直接等于「重试＋打开设置」那排的宽度：那排是 `wrapContent`，
        // 实测约 225dp，而「立即建立热点 + ADB（永久）」15 个字在 `titleSmall`
        // 下单行就要约 220dp，加上左右各 `spaceL` 内边距已超 260dp ——
        // **照那排的宽度写会把文案逼到省略号**。所以取"刚好放得下 + 略宽于那排"
        // 的 0.22f（本车实测约 310dp，约为那排的 1.35 倍），既读得出是同级的
        // 一颗按钮，又不抢主按钮的视觉主位。
        //
        // ⚠️ 历史：早先取 0.42f / 0.30f 两次都"调了但看不出来"—— 因为**根本没生效**
        // （见上面的 `constrain` 机制），与取值大小无关。
        if (extraContent != null) {
            Spacer(Modifier.height(dimens.spaceL))
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier.widthIn(max = dimens.contentMaxWidth * 0.22f),
                ) {
                    extraContent()
                }
            }
        }
    }
}

/**
 * 状态枢纽：圆底 + 图标，[busy] 时外围再叠一层进度环与两道扩散波。
 *
 * 四层结构（从下往上）：扩散波 → 圆底 → 进度环 → 图标。
 * 顺序很关键：扩散波必须在**圆底下面**，否则它会盖住圆底的语义色，
 * 状态就不能"一瞥分辨"了。
 *
 * @param accent 状态强档色：进度环与扩散波用它。
 * @param iconTint 图标色。品牌标志传单色，UI 图标传语义色——见 [CarStatusView] 的说明。
 * @param busy 只在 true 且系统未开启"减少动效"时才转。
 */
@Composable
private fun StatusHub(
    icon: ImageVector,
    accent: Color,
    iconTint: Color,
    containerColor: Color,
    busy: Boolean,
    modifier: Modifier = Modifier,
) {
    val dimens = CarTheme.dimens
    val animated = busy && !LocalReduceMotion.current

    val discSize = dimens.iconXl * 2.4f
    // 画布要放得下"最大扩散波"：半径会涨到进度环的 PulseMaxScale 倍，
    // 所以比圆底大出一圈 spaceXxl，否则波会被布局裁掉。
    val canvasSize = discSize + dimens.spaceXxl * 2f
    val stroke = dimens.strokeThick

    val spin: Float
    val pulse: Float
    val breath: Float
    if (animated) {
        val transition = rememberInfiniteTransition(label = "statusHub")
        spin = transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = CarMotion.DurationSpin,
                    easing = LinearEasing,
                ),
            ),
            label = "spin",
        ).value
        pulse = transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = CarMotion.DurationPulse,
                    easing = CarMotion.EasingStandard,
                ),
            ),
            label = "pulse",
        ).value
        breath = transition.animateFloat(
            initialValue = 1f,
            targetValue = CarMotion.BreathMaxScale,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = CarMotion.DurationBreath / 2,
                    easing = FastOutSlowInEasing,
                ),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "breath",
        ).value
    } else {
        // 不动时给一组中性值，绘制分支因此可以完全共用。
        spin = 0f
        pulse = 0f
        breath = 1f
    }

    Box(modifier = modifier.size(canvasSize), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val discRadius = discSize.toPx() / 2f
            val ringRadius = discRadius + dimens.spaceXs.toPx() + stroke.toPx() / 2f

            if (animated) {
                // 两道错开半个周期的扩散波：错开之后视觉上是连续不断的，
                // 同一时刻只有一道在"长大"，不会糊成一圈。
                PULSE_OFFSETS.forEach { offset ->
                    val progress = (pulse + offset) % 1f
                    drawCircle(
                        color = accent.copy(
                            alpha = CarMotion.PulseStartAlpha * (1f - progress),
                        ),
                        radius = ringRadius *
                            (1f + (CarMotion.PulseMaxScale - 1f) * progress),
                        center = center,
                        style = Stroke(width = stroke.toPx()),
                    )
                }
            }

            drawCircle(color = containerColor, radius = discRadius, center = center)

            if (animated) {
                drawArc(
                    color = accent,
                    // -90 让弧从 12 点方向起步，符合"表盘"的直觉。
                    startAngle = spin - 90f,
                    sweepAngle = CarMotion.ArcSweepDegrees,
                    useCenter = false,
                    topLeft = Offset(center.x - ringRadius, center.y - ringRadius),
                    size = Size(ringRadius * 2f, ringRadius * 2f),
                    style = Stroke(width = stroke.toPx(), cap = StrokeCap.Round),
                )
            }
        }

        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier
                .size(dimens.iconXl * 1.4f)
                .graphicsLayer {
                    // 呼吸只作用在图标上，不动圆底——圆底一动整块就"飘"了。
                    scaleX = breath
                    scaleY = breath
                },
        )
    }
}

/**
 * 两道扩散波的相位偏移。
 *
 * `0f` 与 `0.5f` = 相差半个周期。改成三道（`0f/0.33f/0.66f`）会变成
 * "三道波同时存在"，在 96dp 的圆底外显得拥挤；两道已经足够读出"在持续广播"。
 */
private val PULSE_OFFSETS = listOf(0f, 0.5f)
