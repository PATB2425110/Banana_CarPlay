package com.dbtool.carplay.android

import android.view.KeyEvent
import com.dbtool.carplay.api.input.CarInputAdapter
import com.dbtool.carplay.api.input.CarInputEvent
import com.dbtool.carplay.api.input.CarInputListener
import com.dbtool.carplay.api.input.CarInputSource
import com.dbtool.carplay.api.input.CarKey
import com.dbtool.carplay.api.input.CarKeyAction
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 默认方控/按键适配：把 Android `KeyEvent` 媒体键翻译成 [CarInputEvent]。
 *
 * ## 接入方式（⚠️ 漏了这一步，整个平台的按键就是死的）
 *
 * 本适配器**不自己注册接收器**——它没有 Activity，拿不到 `dispatchKeyEvent`。
 * 必须由宿主 Activity 主动把按键转进来。本工程的实际接线是：
 *
 * ```
 * MainActivity.dispatchKeyEvent(KeyEvent)
 *        ↓  controller.handlePlatformKeyEvent(event)
 * CarAppController            （拿到 platform.input 并转发）
 *        ↓  (platform.input as? AndroidCarInputAdapter)?.handleKeyEvent(event)
 * 本适配器 → keyCodeToCarKey → dispatch → CarPlaySessionBridge → CarPlay HID
 * ```
 *
 * ⚠️ **历史坑**：这条链曾经断在第一环——`CarPlaySessionBridge` 老老实实把
 * listener 挂到了 `platform.input`，但**没有任何人往适配器里喂事件**，
 * `MainActivity` 也没覆写 `dispatchKeyEvent`。结果：listener 挂得好好的，
 * 按键一个都进不来，表现为"方控完全没反应"，而且日志里连一条错误都没有。
 * 车机专有按键服务（Ecarx OneOS）不走这条路，所以只有基线平台会中招。
 *
 * 车机适配点：Flyme / OSN 常把方控事件从专有服务（或 CAN 总线）抛出，
 * 而不是走 `KeyEvent`。这类车机继承本类，覆写 [handleKeyCode] 或直接
 * 在 [attach] 时改为注册自己的监听，再调用同一个 [dispatch] 出口即可。
 *
 * ## 去抖：这里才是产线唯一的去抖点（2026-10-18）
 *
 * 实车一次物理按压会被车机推**两条 DOWN**（间隔 123~330ms，见
 * `EcarxCarInputAdapter.acceptByControlIndex` 的日志与
 * `CarInputRouter.setDebounceMillis` 的 28 次样本）。两条都进入
 * `CarPlaySessionBridge.forwardToCarPlay` 就会发两次 HID：NEXT 跳两首，
 * PLAY_PAUSE 暂停后又自己播放。因此 [dispatch] 对 DOWN 做 350ms 的按 key
 * 静默窗口。
 *
 * `:car:steering` 的 `CarInputRouter` 是同一套规则的参考实现与单测对象，
 * **但产线里从未被实例化**。保留它是为了让去抖规则继续有独立单测覆盖，
 * 也避免两套实现互相掩盖问题；平台适配器不要依赖 steering 模块。
 */
open class AndroidCarInputAdapter(
    private val clock: () -> Long = System::currentTimeMillis,
    debounceMillis: Long = DEFAULT_DEBOUNCE_MILLIS,
) : CarInputAdapter {

    private val listeners = CopyOnWriteArrayList<CarInputListener>()

    /** 每个键最后一次被接受的 DOWN 时刻（时钟取自 [clock]）。 */
    private val lastDownAt = ConcurrentHashMap<CarKey, Long>()

    @Volatile
    private var debounceMillis: Long = debounceMillis.coerceAtLeast(0)

    @Volatile
    private var exclusive = false

    override fun attach(listener: CarInputListener) {
        val added = listeners.addIfAbsent(listener)
        // 重新接上监听（重连/切车机）后第一条按键不能被上一轮的窗口挡掉。
        if (added && listeners.size == 1) reset()
    }

    override fun detach(listener: CarInputListener) {
        listeners.remove(listener)
        if (listeners.isEmpty()) reset()
    }

    /**
     * 是否还有订阅者。
     *
     * 给需要 bind 系统服务的车机适配器用（如 Ecarx 的 OneOS KeyInput）：
     * 最后一个 listener 走了就该把桥接停掉，否则一个没人听的服务会一直占着 binder。
     */
    protected fun hasListeners(): Boolean = listeners.isNotEmpty()

    override fun requestExclusiveAccess(): Boolean {
        exclusive = true
        return true
    }

    override fun releaseExclusiveAccess() {
        exclusive = false
    }

    /** 是否已向车机申请独占方控（仅用于诊断）。 */
    fun hasExclusiveAccess(): Boolean = exclusive

    /**
     * 处理一个 Android 按键事件。
     *
     * ⚠️ **这是基线 Android 平台唯一的按键入口，必须由宿主 Activity 主动调用。**
     * 适配器自己没有 Activity，拿不到 `dispatchKeyEvent`；没人调用它，
     * 该平台的方控/媒体键就是**完全不通**的（见 [dispatch] 上方的接线说明）。
     *
     * @return true 表示事件已消费，调用方不应继续分发。
     */
    fun handleKeyEvent(event: KeyEvent): Boolean {
        val key = keyCodeToCarKey(event.keyCode) ?: return false
        val action = when (event.action) {
            KeyEvent.ACTION_DOWN -> CarKeyAction.DOWN
            KeyEvent.ACTION_UP -> CarKeyAction.UP
            else -> return false
        }
        return dispatch(
            CarInputEvent(
                key = key,
                action = action,
                source = CarInputSource.PANEL,
                timestampMillis = event.eventTime,
                repeatCount = if (event.repeatCount > 0) event.repeatCount else 0,
                rawKeyCode = event.keyCode,
            ),
        )
    }

    /**
     * 直接按平台键码分发（供车机专有按键服务调用，避免构造 `KeyEvent`）。
     */
    open fun handleKeyCode(
        keyCode: Int,
        action: CarKeyAction,
        timestampMillis: Long,
        repeatCount: Int = 0,
        source: CarInputSource = CarInputSource.PANEL,
    ): Boolean {
        val key = keyCodeToCarKey(keyCode) ?: return false
        return dispatch(
            CarInputEvent(
                key = key,
                action = action,
                source = source,
                timestampMillis = timestampMillis,
                repeatCount = repeatCount,
                rawKeyCode = keyCode,
            ),
        )
    }

    /**
     * 设置按下事件的静默窗口。设为 0 关闭抑制。
     *
     * 默认 350ms：实车重复上报间隔 123~330ms（28 次样本全部小于 350ms），
     * 而真实按键间隔最短 2.8s，因此这个窗口只挡重复上报，不误伤连按。
     */
    fun setDebounceMillis(value: Long) {
        debounceMillis = value.coerceAtLeast(0)
    }

    /**
     * 清空抖动状态。重连 / 切换车机后调用，避免旧状态压住第一条按键。
     */
    fun reset() {
        lastDownAt.clear()
    }

    /**
     * 统一出口：任一监听返回 true 即视为消费。
     *
     * ## 去抖规则（产线唯一生效点）
     *
     * - `DOWN` 且 `repeatCount > 0`：长按连发不是新的一次按压，直接丢弃；
     * - `DOWN` 距同键上一次被接受的 DOWN 小于 [debounceMillis]：丢弃；
     * - `UP` / `MULTIPLE`：不做判断，保持原样分发。
     *
     * 被丢弃的 DOWN 仍返回 `true`。对上层（Activity / 系统）表现为"已消费"，
     * 否则第二条会漏回系统，被别的 App 再执行一次媒体键。
     */
    protected open fun dispatch(event: CarInputEvent): Boolean {
        if (event.action == CarKeyAction.DOWN) {
            if (event.repeatCount > 0) return true
            val now = clock()
            val previous = lastDownAt[event.key]
            val window = debounceMillis
            if (previous != null && window > 0 && now - previous < window) return true
            lastDownAt[event.key] = now
        }
        var consumed = false
        for (listener in listeners) {
            if (listener.onInputEvent(event)) consumed = true
        }
        return consumed
    }

    protected open fun keyCodeToCarKey(keyCode: Int): CarKey? = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND,
        KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD, KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD,
        -> CarKey.PREVIOUS

        KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
        KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD, KeyEvent.KEYCODE_MEDIA_STEP_FORWARD,
        -> CarKey.NEXT

        KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK,
        -> CarKey.PLAY_PAUSE

        KeyEvent.KEYCODE_MEDIA_STOP -> CarKey.STOP

        KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_SEARCH -> CarKey.VOICE

        KeyEvent.KEYCODE_HOME -> CarKey.HOME
        KeyEvent.KEYCODE_BACK -> CarKey.BACK
        KeyEvent.KEYCODE_MENU -> CarKey.MENU
        KeyEvent.KEYCODE_VOLUME_UP -> CarKey.VOLUME_UP
        KeyEvent.KEYCODE_VOLUME_DOWN -> CarKey.VOLUME_DOWN
        KeyEvent.KEYCODE_VOLUME_MUTE -> CarKey.MUTE
        else -> null
    }

    companion object {
        /**
         * 默认去抖窗口：一次物理按压可能被车机推两条 DOWN，间隔 123~330ms。
         * 350ms 来自 28 次实车按压样本，见类 KDoc。
         */
        const val DEFAULT_DEBOUNCE_MILLIS = 350L
    }
}
