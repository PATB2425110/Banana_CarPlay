package com.dbtool.carplay.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.dbtool.carplay.api.hotspot.CarAdbChannelStatus
import com.dbtool.carplay.ui.CarUiActions
import com.dbtool.carplay.ui.theme.CarTheme

/**
 * 「永久开启 ADB（5556）」初始化/修复按钮，设置页与连接失败页共用。
 *
 * 按钮只调用 `CarUiActions.onInitializeAdb`，不会启动自建热点；只有用户显式点击才尝试
 * 配置瞬态/持久端口并验证当前 shell。持久属性未读回或通道不可用时，结果如实标为失败/未确认。
 *
 * ## 结果反馈
 *
 * 结果区**始终占位**（空串也渲染一行）：点击后内容突然出现会把下方内容顶下去，
 * 用户的手指还停在原位，容易误触到刚移上来的下一个按钮。
 *
 * ## 宽度（2026-10-08 版式修正 · 二次修订）
 *
 * 内部一律用 `fillMaxWidth()`，**宽度由调用方决定**：
 *
 * - **设置页**（`CarHotspotActionRow`）：放在 `Row` 里，外层 `Box(Modifier.weight(3f))`
 *   与"立即自愈 ADB"按 3:2 分配宽度 ⇒ 两个并排单元各占一份，天然不会通栏；
 * - **失败页**：交给 `CarStatusView` 的 `extraContent` 槽位，槽位收窄到
 *   `contentMaxWidth * 0.22f`。
 *
 * ⚠️ 早先的写法是组件自己 `fillMaxWidth()` 且**不受任何上限**，摆在
 * `CarStatusView` 后面时直接吃满整屏——车主原话「**按钮尺寸过大，通栏铺满屏幕，
 * 页面比例不协调**」。所以"收窄"这件事**必须由调用方在容器上做**，
 * 组件本身不写死任何绝对宽度。
 *
 * 🔴 **收窄容器必须与 `fillMaxWidth()` 分属两层**（2026-10-08 复测后坐实）：
 * `Modifier.fillMaxWidth().widthIn(max = X)` 写在**同一节点**上时 `widthIn`
 * **完全失效**——`SizeNode.measure` 里 `incomingConstraints.constrain(targetConstraints)`
 * 会把 `X` 反向 `coerceIn(父宽, 父宽)` 拉回父宽。正确写法是外层
 * `Box(Modifier.fillMaxWidth())` 负责铺满、里层 `Box(Modifier.widthIn(max = X))`
 * 负责收窄（`Box` 给子项下发的约束会把 `minWidth` 放松回 0）。详见
 * `CarStatusView` 的 `extraContent` 注释与 `REF-pitfalls.md` §64。
 *
 * @param actions 界面动作集。按钮只调 `onSetupHotspotAndAdb`，不碰别的。
 * @param modifier 布局修饰符。默认占满父容器可用宽度。
 * @param enabled 是否可点。**由调用方按 5556 的实测状态决定**（2026-10-15）：
 *   5556 已就绪时这颗按钮必须**置灰而不是消失** —— 消失了用户读不出
 *   "为什么这里没有按钮"，也读不出"下面是靠哪颗按钮关掉的"。
 *   失败页（`HomeScreen` 的 `extraContent`）不传，保持默认可用。
 * @param disabledHint 置灰时结果行要显示的原因。结果行本来就恒占位，
 *   所以置灰时把原因填进去而不是留空 —— 否则用户只看到一颗按不动的灰块，
 *   无从判断是"已经开好了"还是"坏了"。
 */
@Composable
fun CarHotspotAdbSetupButton(
    actions: CarUiActions,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    disabledHint: String = "",
) {
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    val dimens = CarTheme.dimens

    Column(
        modifier = modifier.fillMaxWidth().padding(dimens.spaceM),
        verticalArrangement = Arrangement.spacedBy(dimens.spaceS),
    ) {
        CarButton(
            // 2026-10-16：文案不再说"正在配置" —— 点下去的第一步是**检测**，
            // 5556 已经可用时这一步就到头了（直接复用，不换口、不重启 adbd）。
            // 写成"正在配置"会让"其实什么都没改"的那次点击显得像在动系统。
            text = if (busy) "正在检测并验证 5556…" else "永久开启 ADB（5556）",
            enabled = !busy && enabled,
            onClick = {
                busy = true
                result = null
                failed = false
                actions.onInitializeAdb { state ->
                    busy = false
                    result = state.detail
                    failed = state.channelStatus == CarAdbChannelStatus.UNAVAILABLE ||
                        state.channelStatus == CarAdbChannelStatus.PERSISTENCE_UNVERIFIED
                }
            },
            modifier = Modifier.fillMaxWidth(),
            variant = CarButtonVariant.Primary,
            size = CarButtonSize.Large,
        )
        // ⚠️ 结果区**始终占位**（空串也占一行），不能只在有结果时才出现——
        // 否则按钮点下去的一瞬间下面所有内容会往上跳一截，用户的手指还停在
        // 原来位置，接着点很可能点到刚移上来的下一个按钮。
        Text(
            text = result ?: if (enabled) "" else disabledHint,
            style = CarTheme.type.bodySmall,
            color = if (failed) {
                CarTheme.colors.error
            } else {
                CarTheme.colors.textSecondary
            },
        )
    }
}
