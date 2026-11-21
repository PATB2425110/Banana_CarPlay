package com.dbtool.carplay.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing

/**
 * DBPlay 动效令牌。
 *
 * ## 为什么动效也要抽令牌
 *
 * 动效的"手感"由**时长 + 缓动**决定，而不是由某个组件的代码决定。
 * 时长散落在各组件里，最后必然出现"这个淡入 200ms、那个 250ms、还有个 320ms"
 * 的随机差异——和当年那套 14/20/28/40 的间距是同一个毛病，
 * 只是这次肉眼更难指出哪里不对，只觉得"整体有点飘"。
 *
 * 所以和颜色/尺寸/字号一样：**这里定义，别处只引用**。
 *
 * ## 取值依据（`DBPlay-UI-DESIGN.md` §7）
 *
 * - **时长压在 140~320ms**。车机上的动画只负责"解释状态变化"，不是表演。
 *   超过 320ms 会让"切换设置项"这类高频操作显得迟钝；
 *   低于 120ms 又来不及被看见，等于没做。
 * - **缓动一律先快后慢**（`CubicBezier(0.4, 0, 0.2, 1)` 同族，即 Material 的
 *   `FastOutSlowIn`）：起步快 = 响应感，收尾慢 = 不突兀。这是 iOS 与 Material
 *   的共识区间，也是"看起来贵"和"看起来卡"的分界。
 * - **循环动画只有一个用途**：告诉用户"还在等"。周期 ≥ 1.2s——
 *   低于 1s 会读成焦躁，车机上尤其明显。
 *
 * ## 为什么不用 Spring
 *
 * Compose 的 `spring` 很诱人，但弹簧动画的**结束时间不确定**（取决于阻尼与初速度）。
 * 在"状态每几秒就变一次"的连接页上，两段弹簧会互相追尾。
 * 这里全部用显式时长的补间，行为可预测——车机 UI 更需要"稳"而不是"弹"。
 */
object CarMotion {

    // ------------------------------------------------------------------ 时长

    /** 状态切换：颜色、文字、不透明度的变化。 */
    const val DurationFast = 140

    /** 元素入场与位移（文字换行、卡片出现）。 */
    const val DurationMedium = 220

    /** 较大的结构变化（页面切换、面板展开）。 */
    const val DurationSlow = 320

    // -------------------------------------------------------------- 循环动画

    /** 不定量进度环转一圈。 */
    const val DurationSpin = 1300

    /** 一圈"扩散波"从出现到消失。 */
    const val DurationPulse = 2200

    /** 图标呼吸一个来回。 */
    const val DurationBreath = 2200

    // -------------------------------------------------------------- 循环参数

    /** 进度环的弧长（度）。90° 是"看得出在转"的下限。 */
    const val ArcSweepDegrees = 90f

    /** 扩散波最多放大到进度环半径的多少倍。 */
    const val PulseMaxScale = 1.35f

    /** 扩散波的起始不透明度。再高就会盖过圆底的语义色。 */
    const val PulseStartAlpha = 0.28f

    /** 图标呼吸的最大缩放。压在 3% 以内，否则会读成"在抖"。 */
    const val BreathMaxScale = 1.03f

    // ------------------------------------------------------------------ 缓动

    /** 标准缓动：先快后慢。绝大多数过渡用它。 */
    val EasingStandard: Easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

    /**
     * 入场缓动：起步更缓、收尾更长。
     *
     * 用于"从无到有"的元素（文字换行、卡片出现）——它需要一点加速过程才不突兀，
     * 而 [EasingStandard] 的瞬时起步在"从零开始"的场景下会显得跳。
     */
    val EasingEnter: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
}
