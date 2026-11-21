package com.dbtool.carplay.bridge

import com.dbtool.carplay.api.input.CarInputSource
import com.dbtool.carplay.api.input.CarKey
import com.dbtool.carplay.api.input.CarKeyAction

/**
 * `CarKey` → CarPlay HID 的映射表。
 *
 * ## 为什么需要这一层
 *
 * 方控适配器（`:car:platform-*`）把车机按键归一化成 [CarKey] 就结束了——它只负责
 * "收到按键"。要让 iPhone 真的响应，还必须把按键送进 CarPlay 的 HID 通道
 * （upstream `AirPlaySession.sendMedia` / `sendKnob` / `invokeSiri`）。
 * 那属于会话层的活，归本模块（`:car:core-bridge`）；本对象是它的纯映射部分，
 * **不引用任何 upstream 类型**，因此可以脱离 Android / Core 单独做单测。
 *
 * ## mediaReport 的 index 到底是什么
 *
 * upstream `AirPlayHid.mediaDescriptor` 没有一行注释说明 index 语义，但它本身
 * 就是一个 HID **Usage Array**，index 就是 usage 数组的下标：
 *
 * ```
 * 0x05,0x0c, 0x09,0x01, 0xa1,0x01,   // Usage Page(Consumer), Usage(Consumer Control), Collection(Application)
 * 0x15,0x00, 0x25,0x06,               // Logical Minimum 0, Logical Maximum 6  ← 下标上界
 * 0x0a,0x00,0x00,                     // [0] Usage 0x0000  Unassigned
 * 0x0a,0xb0,0x00,                     // [1] Usage 0x00B0  Play
 * 0x0a,0xb1,0x00,                     // [2] Usage 0x00B1  Pause
 * 0x0a,0xcd,0x00,                     // [3] Usage 0x00CD  Play/Pause
 * 0x0a,0xb5,0x00,                     // [4] Usage 0x00B5  Scan Next Track
 * 0x0a,0xb6,0x00,                     // [5] Usage 0x00B6  Scan Previous Track
 * 0x0a,0x9e,0x02,                     // [6] Usage 0x029E  Alternate Audio
 * 0x75,0x08, 0x95,0x01, 0x81,0x00,    // Report Size 8, Report Count 1, Input(Data,Array)
 * ```
 *
 * 于是 `AirPlayHid.mediaReport(index) = byteArrayOf(index.toByte())` 里的 index
 * 就是上表下标，`0` 表示"无按键"。upstream `AirPlaySession.sendMedia(index)` 会
 * **先发 `index` 再发 `0`**（按下 + 抬起），`sendKnob` 的 `momentary = true` 同理。
 *
 * ## 由此推出的两条硬约束
 *
 * 1. **只转发按下动作**。既然抬起由 upstream 自己补，把 `CarKeyAction.UP` 也转过去
 *    会让一次按键变成两次（例如"下一首"跳两首）。见 [shouldForward]。
 * 2. **音量不转发**。媒体描述符里没有音量 usage；CarPlay 的音量由车机功放自己管，
 *    硬塞一个不存在的 usage 只会污染 HID 流。见 [intentFor]。
 * 3. **HOME / BACK 只认方控来源**。车机自己的硬键 HOME/BACK 是用户"退出界面"的出口，
 *    转发并消费它们会把用户困在 CarPlay 画面里。见 [forwardsNavigationKeys]。
 *
 * ⚠️ 上面的结论来自**对描述符的解读**，不是真机抓包。iOS 是否按此语义接受这些
 * index，仍需一台真实 iPhone 验证；排查顺序见 `car/platform-ecarx/README.md`。
 */
internal object CarKeyHidMap {

    /** `AirPlayHid.mediaDescriptor` usage 数组下标。数值即"按下后发送的那个字节"。 */
    const val MEDIA_RELEASE = 0
    const val MEDIA_PLAY = 1
    const val MEDIA_PAUSE = 2
    const val MEDIA_PLAY_PAUSE = 3
    const val MEDIA_NEXT_TRACK = 4
    const val MEDIA_PREVIOUS_TRACK = 5
    const val MEDIA_ALTERNATE_AUDIO = 6

    /**
     * 「播放/暂停」键该发哪个 usage —— **要结合"现在在播吗"**。
     *
     * ## 为什么不能只发 `MEDIA_PLAY_PAUSE`（2026-09-29）
     *
     * 实车反馈：方控的上一首/下一首正常，**播放/暂停键完全没反应**。
     * 三个媒体键走的是同一套 HID 通道（`sendMedia`），既然 4/5 能到 iPhone，
     * 说明通道本身是通的；差别只在 usage：
     *
     * | index | usage | 实车表现 |
     * |---|---|---|
     * | 3 | `0x00CD` Play/Pause（toggle） | ❌ 没反应 |
     * | 4 | `0x00B5` Scan Next Track | ✅ 正常 |
     * | 5 | `0x00B6` Scan Previous Track | ✅ 正常 |
     *
     * 也就是说 **iOS 不理会"Play/Pause 切换"这个 usage**，只认成对的
     * `0xB0 Play` / `0xB1 Pause`（它们本来就在同一张描述符里，index 1/2）。
     *
     * ## 这正是 DBTOOL2.0 的做法（实车验证）
     *
     * DBTOOL2.0 的 `ThirdPartyPlayerBridge.sendPlayOrPauseCommand()` 从**不**发
     * "toggle"，而是先读当前 `PlaybackState`：
     *
     * ```java
     * boolean isPlaying = state != null && state.getState() == PlaybackState.STATE_PLAYING;
     * if (isPlaying) controller.getTransportControls().pause();
     * else           controller.getTransportControls().play();
     * ```
     *
     * 本方法把同一套判断搬到 HID 这一侧：**读状态 → 显式发 Play 或 Pause**。
     *
     * ## `isPlaying == null` 时为什么退回 toggle
     *
     * null = 还没连上媒体会话 / iPhone 没在提供媒体信息，此时我们**不知道**方向。
     * 猜一个方向（比如总是发 Play）会让"想暂停"变成"继续播"，比不动作更糟；
     * 所以退回 index 3 —— 与修改前**完全一致**的行为，不会让现状变差。
     *
     * @param isPlaying 当前是否在播放；null = 未知。
     */
    fun playPauseIndex(isPlaying: Boolean?): Int = when (isPlaying) {
        true -> MEDIA_PAUSE
        false -> MEDIA_PLAY
        null -> MEDIA_PLAY_PAUSE
    }

    /**
     * 这个动作是否应该转发给 CarPlay。
     *
     * **只转发 DOWN**：抬起由 upstream 的 `sendMedia` / `sendKnob(momentary = true)`
     * 自动补发；真机上车机方控有时只上报 DOWN（见 Ecarx 的 `onShortClick`），
     * 所以这里不能假设 UP 一定会来。
     *
     * `MULTIPLE` 是 `EcarxCarInputAdapter` 对"既不是 DOWN 也不是 UP"的兜底，
     * 它不代表一次新的按压。转发它等于凭空多发一次媒体键：一次物理按压若同时
     * 产生 DOWN 与 MULTIPLE，用户就会看到"跳两首"或"暂停又播放"。
     */
    fun shouldForward(action: CarKeyAction): Boolean =
        action == CarKeyAction.DOWN

    /** 一次按键对应的 HID 意图。 */
    sealed interface Intent {
        /** Consumer 页媒体键；[index] 取本对象的 `MEDIA_*` 常量。 */
        data class Media(val index: Int) : Intent

        /**
         * 唤起 Siri。
         *
         * 注意它**不走 HID**：upstream `AirPlaySession.invokeSiri()` 发的是
         * `requestSiri` 命令（siriAction 2 + 3）。放在同一个 Intent 里是因为
         * 对调用方而言"按了语音键"就是一件事，走哪条通道是实现细节。
         */
        data object Siri : Intent

        /** 旋钮 / 面板键。字段与 upstream `AirPlayKnobState` 同名同义。 */
        data class Knob(
            val select: Boolean = false,
            val home: Boolean = false,
            val back: Boolean = false,
        ) : Intent
    }

    /**
     * 把归一化按键映射成 HID 意图。
     *
     * 返回 null 有**两种不同**的原因，不要混为一谈：
     *
     * - **音量 / 静音**：HID 通道里没有音量 usage，音量归车机功放管。
     *   返回 null 意味着"不消费"，事件会交回车机/系统——这是期望行为。
     * - **MENU**：媒体描述符里找不到语义对得上的 usage。宁可什么都不做，
     *   也不要把 `select`（旋钮确认）借给它——那是在发明协议，
     *   将来真机上对不上时会变成一条极难定位的"按键串味"。
     *
     * @param isPlaying 只对 [CarKey.PLAY_PAUSE] 有意义：当前是否在播放。
     *        null = 未知，退回 `0xCD` toggle（见 [playPauseIndex]）。
     */
    fun intentFor(key: CarKey, isPlaying: Boolean? = null): Intent? = when (key) {
        CarKey.PLAY_PAUSE -> Intent.Media(playPauseIndex(isPlaying))
        CarKey.NEXT -> Intent.Media(MEDIA_NEXT_TRACK)
        CarKey.PREVIOUS -> Intent.Media(MEDIA_PREVIOUS_TRACK)

        // 描述符里没有 Stop usage；"停止播放"最接近的语义是 Pause（近似，非等价）。
        CarKey.STOP -> Intent.Media(MEDIA_PAUSE)

        CarKey.VOICE -> null // Vehicle assistant owns the steering-wheel voice key.
        CarKey.HOME -> Intent.Knob(home = true)
        CarKey.BACK -> Intent.Knob(back = true)

        CarKey.MENU,
        CarKey.VOLUME_UP,
        CarKey.VOLUME_DOWN,
        CarKey.MUTE,
        -> null
    }

    /**
     * 完整决策：这个事件要不要转发、转成什么。
     *
     * 调用方拿到的 null 一律按"不消费"处理（返回 false 给 `CarInputListener`），
     * 让事件继续流向车机/系统，避免"我们既不处理也不放行"。
     *
     * 与 [intentFor] 的分工：[intentFor] 是**纯映射**（这个键对应哪个 HID 意图），
     * 本函数是**策略**（这个来源的这个事件到底该不该转发）。
     *
     * @param isPlaying 只影响 [CarKey.PLAY_PAUSE]；null = 未知（见 [playPauseIndex]）。
     */
    fun planFor(
        key: CarKey,
        action: CarKeyAction,
        source: CarInputSource,
        isPlaying: Boolean? = null,
    ): Intent? =
        if (shouldForward(action) && forwardsNavigationKeys(key, source)) {
            intentFor(key, isPlaying)
        } else {
            null
        }

    /**
     * HOME / BACK 是否允许转发给 CarPlay。
     *
     * ## 为什么必须按来源区分（2026-09-28）
     *
     * 这两个键与媒体键有本质区别：**车机自己的硬键 HOME/BACK 是用户"离开当前界面"
     * 的出口**。基线平台靠 `MainActivity.dispatchKeyEvent` 把按键喂进来，它标注的
     * 来源是 [CarInputSource.PANEL]；如果这类事件也被转发并被消费
     * （`forwardToCarPlay` 返回 true ⇒ 事件不再流向 `super.dispatchKeyEvent`），
     * 用户就会**被困在 CarPlay 画面里**，没有任何硬键能退出。
     *
     * 而方向盘上的 home/back 按钮（[CarInputSource.STEERING_WHEEL]，Ecarx 那条路）
     * 语义不同：它**本来就是给 CarPlay 用的**，理应转发。
     *
     * ⇒ 只放行 [CarInputSource.STEERING_WHEEL]；PANEL / OTHER 一律不转发，
     * 事件继续流向系统，返回键仍然能退出界面。
     */
    private fun forwardsNavigationKeys(key: CarKey, source: CarInputSource): Boolean =
        when (key) {
            CarKey.HOME, CarKey.BACK -> source == CarInputSource.STEERING_WHEEL
            else -> true
        }
}
