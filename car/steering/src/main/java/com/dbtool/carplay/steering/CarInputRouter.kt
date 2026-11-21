package com.dbtool.carplay.steering

import com.dbtool.carplay.api.input.CarInputEvent
import com.dbtool.carplay.api.input.CarInputListener
import com.dbtool.carplay.api.input.CarInputSource
import com.dbtool.carplay.api.input.CarKey
import com.dbtool.carplay.api.input.CarKeyAction
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 平台键码 → [CarKey] 的可替换映射表。
 *
 * 做成表而不是 `when` 表达式的原因：车机方控的键码是**部署相关**的。
 * 同一款车机不同批次、不同配置（有无音量旋钮、有无自定义键）键码都可能不同。
 * 硬编码意味着每换一台车就要改代码重新编译；
 * 配表意味着现场改配置即可。
 */
fun interface CarKeyMap {
    fun map(rawKeyCode: Int): CarKey?

    companion object {
        /**
         * 空表：表示"来源侧已经给出 [CarKey]，无需再映射"。
         * 车机专有按键服务通常属于这种情况。
         */
        val PASS_THROUGH: CarKeyMap = CarKeyMap { null }
    }
}

/**
 * 方控/按键事件的路由与仲裁。
 *
 * 处理三件事：
 * 1. 键码映射（[keyMap] 可替换，见 [CarKeyMap]）
 * 2. 抖动/连发抑制（每个键独立的静默窗口）
 * 3. 分发到多个 [CarInputListener]
 *
 * 线程安全：所有公开方法可从任意线程调用。
 */
class CarInputRouter(
    private var keyMap: CarKeyMap = CarKeyMap.PASS_THROUGH,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val listeners = CopyOnWriteArrayList<CarInputListener>()
    private val lastAcceptedAt = java.util.concurrent.ConcurrentHashMap<CarKey, Long>()

    @Volatile
    private var debounceMillis: Long = DEFAULT_DEBOUNCE_MILLIS

    fun attach(listener: CarInputListener) {
        listeners.addIfAbsent(listener)
    }

    fun detach(listener: CarInputListener) {
        listeners.remove(listener)
    }

    fun setKeyMap(map: CarKeyMap) {
        keyMap = map
    }

    /**
     * 设置按下事件的静默窗口。设为 0 关闭抑制。
     *
     * 默认 350ms（2026-10-03 由 120ms 上调）。
     *
     * 原值 120ms 的依据是「机械/电气抖动 50~100ms」——**这个假设在实车上不成立**。
     * 实车日志（devlog_2149, 21:47:37）显示：一次物理按压会被车机推**两条** DOWN，
     * 间隔 123~330ms。原因是 OneOS 对 `onKeyCodeEvent` 与 `onShortClick`
     * **两条回调都照发**（见 `EcarxCarInputAdapter.acceptByControlIndex` 的注释），
     * 而 120ms 窗口挡不住第二条。
     *
     * 后果：一次按压 → 转发两次 HID → CarPlay 收到两次 Play/Pause（切换语义）
     * → 用户看到「按暂停后音乐又自己播」「音乐老是断」。
     *
     * 350ms 的取值依据（28 次实测按压样本）：
     * - 重复上报间隔：123/145/194/223/234/236/264/277/300/311/323/330ms → 全部 < 350 ✅
     * - 真实按键间隔：3.0/4.5/7.0/12.2/20.8/53.5s → 全部 >> 350 ✅
     * - 模拟结果：120ms 挡 0 次；250ms 挡 7 次；**350ms 挡 14 次（正好每次按压留一条）**
     *
     * 350ms 以上（500ms/800ms/1500ms）结果与 350ms 相同，故取最小值。
     */
    fun setDebounceMillis(value: Long) {
        debounceMillis = value.coerceAtLeast(0)
    }

    /**
     * 交给来源侧调用：已知 [CarKey] 时直接进入仲裁。
     * @return true 表示事件已被消费（至少有一个 listener 返回 true）
     */
    fun route(key: CarKey, action: CarKeyAction, source: CarInputSource, rawKeyCode: Int? = null, repeatCount: Int = 0): Boolean {
        // 长按连发（repeatCount > 0）不是独立的一次按压，直接丢弃。
        // 与上游 DiPlay `CarPlayMediaKeys` 一致（`event.repeatCount == 0`）。
        if (repeatCount > 0) return false
        val now = clock()
        if (action == CarKeyAction.DOWN && isDebounced(key, now)) return false
        if (action == CarKeyAction.DOWN) lastAcceptedAt[key] = now
        val event = CarInputEvent(
            key = key,
            action = action,
            source = source,
            timestampMillis = now,
            repeatCount = repeatCount,
            rawKeyCode = rawKeyCode,
        )
        return dispatch(event)
    }

    /**
     * 交给来源侧调用：只有平台键码时，先用 [keyMap] 映射再仲裁。
     * @return true 表示事件已消费
     */
    fun routeRawKeyCode(
        rawKeyCode: Int,
        action: CarKeyAction,
        source: CarInputSource,
        repeatCount: Int = 0,
    ): Boolean {
        val key = keyMap.map(rawKeyCode) ?: return false
        return route(key, action, source, rawKeyCode = rawKeyCode, repeatCount = repeatCount)
    }

    /** 清空抖动状态。切换车机/重连后调用，避免旧状态压住第一次按键。 */
    fun reset() {
        lastAcceptedAt.clear()
    }

    private fun isDebounced(key: CarKey, now: Long): Boolean {
        val debounce = debounceMillis
        if (debounce <= 0) return false
        val previous = lastAcceptedAt[key] ?: return false
        return now - previous < debounce
    }

    private fun dispatch(event: CarInputEvent): Boolean {
        var consumed = false
        for (listener in listeners) {
            if (runCatching { listener.onInputEvent(event) }.getOrDefault(false)) consumed = true
        }
        return consumed
    }

    companion object {
        /**
         * 默认去抖窗口（2026-10-03 由 120ms 上调至 350ms）。
         * 依据见 [setDebounceMillis] 的注释：实车一次按压会被车机推两条 DOWN，
         * 间隔 123~330ms。
         */
        const val DEFAULT_DEBOUNCE_MILLIS = 350L
    }
}
