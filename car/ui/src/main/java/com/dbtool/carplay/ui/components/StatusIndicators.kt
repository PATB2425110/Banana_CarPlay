package com.dbtool.carplay.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.dbtool.carplay.ui.CarStatusIndicator
import com.dbtool.carplay.ui.theme.CarTheme

/** 一条键值信息，用于状态页/卡片底部的元信息区。 */
data class CarFact(val label: String, val value: String)

/**
 * 状态指示色的**唯一**映射点。
 *
 * UI 各处只传 [CarStatusIndicator] 语义，不自己挑颜色——
 * 否则"连接失败"在首页是红的、在设置页又是橙的这种不一致迟早出现。
 *
 * 返回的是**强档**色（图标、状态点、进度条），浅底档见 [indicatorContainerColor]。
 */
@Composable
fun indicatorColor(indicator: CarStatusIndicator): Color = when (indicator) {
    CarStatusIndicator.IDLE -> CarTheme.colors.textTertiary
    CarStatusIndicator.WORKING -> CarTheme.colors.primary
    CarStatusIndicator.READY -> CarTheme.colors.success
    CarStatusIndicator.ERROR -> CarTheme.colors.error
}

/** 状态语义的**浅底**档（状态胶囊、状态图标圆底）。 */
@Composable
fun indicatorContainerColor(indicator: CarStatusIndicator): Color = when (indicator) {
    CarStatusIndicator.IDLE -> CarTheme.colors.surfaceVariant
    CarStatusIndicator.WORKING -> CarTheme.colors.primaryContainer
    CarStatusIndicator.READY -> CarTheme.colors.successContainer
    CarStatusIndicator.ERROR -> CarTheme.colors.errorContainer
}

/** 画在 [indicatorContainerColor] 之上的文字色。 */
@Composable
fun indicatorOnContainerColor(indicator: CarStatusIndicator): Color = when (indicator) {
    CarStatusIndicator.IDLE -> CarTheme.colors.textSecondary
    CarStatusIndicator.WORKING -> CarTheme.colors.onPrimaryContainer
    CarStatusIndicator.READY -> CarTheme.colors.onSuccessContainer
    CarStatusIndicator.ERROR -> CarTheme.colors.onErrorContainer
}

/** 状态点。8dp 圆点，用于列表行与卡片标题前。 */
@Composable
fun CarStatusDot(
    indicator: CarStatusIndicator,
    modifier: Modifier = Modifier,
    size: Dp = CarTheme.dimens.spaceS,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(indicatorColor(indicator)),
    )
}

/**
 * 状态胶囊徽标（列表 / 视频页浮层）。
 *
 * 用**语义浅底 + 语义深字**，不用描边也不用中性底：
 * 描边在车机强光下几乎看不见，中性底则丢掉了"这是状态"的暗示。
 * 这是 Ant / TDesign 对 Tag 的一致处理。
 */
@Composable
fun CarStatusPill(
    text: String,
    indicator: CarStatusIndicator,
    modifier: Modifier = Modifier,
) {
    val dimens = CarTheme.dimens
    CarPanel(
        modifier = modifier,
        color = indicatorContainerColor(indicator),
        shape = CarTheme.shapes.pill,
    ) {
        Row(
            modifier = Modifier
                .height(dimens.spaceXxl)
                .padding(horizontal = dimens.spaceM),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimens.spaceS),
        ) {
            CarStatusDot(indicator)
            Text(
                text = text,
                style = CarTheme.type.bodyMedium,
                color = indicatorOnContainerColor(indicator),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 关键参数表：`label / value` 两列一组的流式排布。
 *
 * ⚠️ 旧版把它包在一个 `surfaceVariant` 色块里。现在**去掉了那层嵌套**：
 * 参数是卡片的附属信息，不是独立区块；多一层底色只会让卡片里出现
 * "块中块"，层级反而更乱（TDesign 明确说的"层级只选一种手段"）。
 *
 * @param centered 居中排布。状态页（整页居中）用 true，卡片内用 false。
 */
@Composable
fun CarFactsBand(
    facts: List<CarFact>,
    modifier: Modifier = Modifier,
    centered: Boolean = false,
) {
    val dimens = CarTheme.dimens
    FlowRow(
        modifier = modifier,
        horizontalArrangement = if (centered) {
            // 居中档（状态页）**每一列自带一条前导竖线**，所以这里只留半距；
            // 竖线两侧各 16dp，视觉间隔仍是 33dp 左右，与改动前相当。
            // 不这么做的话，`链路 / 无线` 与 `MFi 认证 / 远程认证` 两列会
            // 被读成"一行四个词"——它们之间只靠空白分隔，而空白是最弱的分隔手段。
            Arrangement.spacedBy(dimens.spaceL, Alignment.CenterHorizontally)
        } else {
            Arrangement.spacedBy(dimens.spaceXxl)
        },
        verticalArrangement = Arrangement.spacedBy(dimens.spaceM),
    ) {
        facts.forEachIndexed { index, fact ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (centered && index > 0) {
                    Box(
                        modifier = Modifier
                            .height(dimens.spaceXxl)
                            .width(dimens.strokeThin)
                            .background(CarTheme.colors.divider),
                    )
                    Spacer(Modifier.width(dimens.spaceL))
                }
                Column(
                    // 标签与取值之间用 spaceS 而不是 spaceXs：
                    // 4dp 在车机上看不出"这是两行"，标签会像取值的前缀。
                    verticalArrangement = Arrangement.spacedBy(dimens.spaceS),
                ) {
                    Text(
                        text = fact.label,
                        style = CarTheme.type.label,
                        color = CarTheme.colors.textTertiary,
                        maxLines = 1,
                    )
                    Text(
                        text = fact.value,
                        style = CarTheme.type.valueLarge,
                        color = CarTheme.colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** 分隔线。固定 1dp 高，调用方不需要再关心尺寸。 */
@Composable
fun CarRowDivider(
    modifier: Modifier = Modifier,
    inset: Dp = CarTheme.dimens.spaceM,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = inset)
            .height(CarTheme.dimens.strokeThin)
            .background(CarTheme.colors.divider),
    )
}
