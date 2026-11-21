package com.dbtool.carplay.bridge.power

import com.dbtool.carplay.api.power.CarPowerSnapshot

/** 熄火宽限时长：熄火后 15 秒内回来就只暂停、不断开。 */
const val POWER_PAUSE_GRACE_MILLIS: Long = 15_000L

/** 策略给出的动作。 */
enum class PowerPauseAction {
    /** 什么都不做。 */
    NONE,

    /** 立即暂停播放（会话保留）。 */
    PAUSE_NOW,

    /** 宽限期结束仍在熄火状态：暂停并断开会话，只发一次。 */
    PAUSE_AND_STOP_SESSION,
}

/**
 * 「熄火自动暂停」的纯逻辑。
 *
 * 不依赖 Android：时间由调用方传入（`SystemClock.elapsedRealtime()`），
 * 这样整段判定可以在 JVM 单测里钉住，不必上车试。
 *
 * 规则：
 * - 娱乐模式变 OFF ⇒ 立刻 [PowerPauseAction.PAUSE_NOW]，并记下 `pendingSince`；
 * - 连续 OFF 不重复发 [PowerPauseAction.PAUSE_NOW]，也不刷新 `pendingSince`
 *   （否则持续熄灭时永远到不了点）；
 * - 15 秒内回到 ON ⇒ 清状态、取消断开；
 * - 到点仍 OFF ⇒ [PowerPauseAction.PAUSE_AND_STOP_SESSION]，且只发一次；
 * - `entertainmentOn == null`（读不到）⇒ [PowerPauseAction.NONE]，
 *   既不当成 ON 也不当成 OFF。
 *
 * 计时器由接线方按 [deadlineMs] 排；策略本身不持有线程或 Handler。
 */
class PowerPausePolicy(
    private val graceMillis: Long = POWER_PAUSE_GRACE_MILLIS,
) {
    init {
        require(graceMillis >= 0) { "graceMillis must be >= 0, was $graceMillis" }
    }

    /** 本次 OFF 的起始时刻；null = 当前没有待处理的熄火。 */
    var pendingSinceMs: Long? = null
        private set

    /** 断开动作是否已经发过（防止重复断会话）。 */
    var stopEmitted: Boolean = false
        private set

    /**
     * 喂入一帧电源状态。
     *
     * @param snapshot `null` 表示这次没有状态可读，等价于"不动作"。
     * @param nowMs 单调时钟（毫秒）。
     */
    fun onSnapshot(snapshot: CarPowerSnapshot?, nowMs: Long): PowerPauseAction {
        val entertainmentOn = snapshot?.entertainmentOn ?: return PowerPauseAction.NONE
        if (entertainmentOn) {
            pendingSinceMs = null
            stopEmitted = false
            return PowerPauseAction.NONE
        }
        // 已经在一次熄火事件里：不重复暂停，也不能把起点往后推。
        if (pendingSinceMs != null) return PowerPauseAction.NONE
        pendingSinceMs = nowMs
        stopEmitted = false
        return PowerPauseAction.PAUSE_NOW
    }

    /**
     * 定时器到点（或接线方主动查一次）。
     *
     * 只有"仍在熄火、未发过断开、且已满 [graceMillis]"才返回
     * [PowerPauseAction.PAUSE_AND_STOP_SESSION]；其余一律 [PowerPauseAction.NONE]。
     */
    fun onTimeout(nowMs: Long): PowerPauseAction {
        val since = pendingSinceMs ?: return PowerPauseAction.NONE
        if (stopEmitted) return PowerPauseAction.NONE
        if (nowMs - since < graceMillis) return PowerPauseAction.NONE
        stopEmitted = true
        return PowerPauseAction.PAUSE_AND_STOP_SESSION
    }

    /**
     * 下一次该在什么时刻检查；null = 不需要再排闹钟。
     *
     * 断开已发出后返回 null：那条会话已经在收尾，再排一次只会打空。
     */
    fun deadlineMs(): Long? {
        val since = pendingSinceMs ?: return null
        if (stopEmitted) return null
        return since + graceMillis
    }

    /** 清空全部状态（停止监听 / 关掉开关 / 会话重建时调用）。 */
    fun reset() {
        pendingSinceMs = null
        stopEmitted = false
    }
}
