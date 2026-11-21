package com.dbtool.carplay.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp

/**
 * DBPlay 圆角令牌。
 *
 * 刻度：2 / 4 / 8 / 12 / 16 / 胶囊。
 * **主力值是 [large]（12dp：56dp 控件 + 卡片）与 [extraLarge]（16dp：68dp 控件 + 弹窗）**
 * —— 圆角是组件层级的一部分，不是品牌装饰，所以刻意不做大（旧版 26dp 那种
 * "柔软 SaaS 风"会让整屏失去结构感）。
 *
 * ⚠️ **控件的圆角要随自身高度走**（约 20%）：56dp 用 [large]、68dp 用 [extraLarge]。
 * 两种尺寸共用一个 [medium] 会让大的那个看起来是直角方块 —— 已踩过。
 */
@Immutable
data class CarShapes(
    /** 状态点容器、进度条、极小标记。 */
    val extraSmall: RoundedCornerShape,
    /** 紧凑控件、Tag。 */
    val small: RoundedCornerShape,
    /** 小色块、图标预览底衬。 */
    val medium: RoundedCornerShape,
    /** 56dp 控件（按钮 Regular / 输入框 / 分段选择）、卡片、面板。 */
    val large: RoundedCornerShape,
    /** 68dp 控件（按钮 Large）、弹窗、大容器。 */
    val extraLarge: RoundedCornerShape,
    val pill: RoundedCornerShape,
) {
    companion object {
        fun of(dimens: CarDimensions): CarShapes = CarShapes(
            extraSmall = RoundedCornerShape(dimens.radiusXs),
            small = RoundedCornerShape(dimens.radiusS),
            medium = RoundedCornerShape(dimens.radiusM),
            large = RoundedCornerShape(dimens.radiusL),
            extraLarge = RoundedCornerShape(dimens.radiusXl),
            // 用固定的超大 dp 而不是 percent=50：percent 在扁长控件上会变成椭圆，
            // 而胶囊的语义是"两端半圆"，与控件长宽比无关。
            pill = RoundedCornerShape(dimens.radiusPill),
        )

        /** 供需要自定义半径的场景使用（例如状态点、进度条）。 */
        fun radius(value: Dp): RoundedCornerShape = RoundedCornerShape(value)
    }
}
