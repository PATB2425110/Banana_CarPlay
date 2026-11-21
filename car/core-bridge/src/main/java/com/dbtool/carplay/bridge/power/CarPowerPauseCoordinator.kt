package com.dbtool.carplay.bridge.power

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.dbtool.carplay.api.power.CarPowerListener
import com.dbtool.carplay.api.power.CarPowerMode
import com.dbtool.carplay.api.power.CarPowerPauseSetting
import com.dbtool.carplay.api.power.CarPowerSnapshot

/**
 * 把「电源事件 → 策略 → 会话动作」接起来。
 *
 * 线程约定：
 * - 厂商回调可能在 Binder 线程；这里统一 `post` 到主线程再判定与动作，
 *   `CarPlaySessionBridge.stop()` / 媒体暂停都按主线程调用方的既有假设执行；
 * - 定时器与设置监听同样在主线程落地。
 *
 * 生命周期：
 * - [start]：会话启动时调用。开关关着就只挂监听、不注册厂商回调；
 * - [stop]：会话停止 / 实例关闭时调用，注销厂商回调与设置监听、清空策略；
 * - 开关在会话期间被拨动：打开即注册，关闭即注销并清状态。
 */
internal class CarPowerPauseCoordinator(
    private val powerMode: CarPowerMode,
    private val setting: CarPowerPauseSetting,
    private val pausePlayback: () -> Unit,
    private val stopSession: () -> Unit,
    private val log: (String) -> Unit = {},
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val handler: Handler = Handler(Looper.getMainLooper()),
) {
    private val policy = PowerPausePolicy()

    private var attached = false
    private var modeRunning = false
    private var settingHandle: AutoCloseable? = null
    private var announcedUnavailable = false

    private val powerListener = CarPowerListener { snapshot ->
        handler.post { handleSnapshot(snapshot) }
    }

    private val timeoutRunnable = Runnable {
        if (!modeRunning) return@Runnable
        when (policy.onTimeout(clock())) {
            PowerPauseAction.PAUSE_AND_STOP_SESSION -> {
                log("电源模式：熄火已满 ${POWER_PAUSE_GRACE_MILLIS / 1000} 秒，断开 CarPlay")
                stopSession()
            }
            else -> scheduleTimeout()
        }
    }

    /** 会话开始：挂设置监听，并按当前开关决定是否订阅电源事件。 */
    fun start() {
        if (attached) return
        attached = true
        settingHandle = setting.observe { enabled -> handler.post { onSettingChanged(enabled) } }
        onSettingChanged(setting.isEnabled())
    }

    /** 会话结束：注销全部监听并清空策略。 */
    fun stop() {
        if (!attached && !modeRunning) return
        attached = false
        settingHandle?.close()
        settingHandle = null
        stopMode()
    }

    private fun onSettingChanged(enabled: Boolean) {
        if (!attached) return
        if (enabled) {
            startMode()
        } else {
            log("电源模式：开关已关闭，停止监听")
            stopMode()
        }
    }

    private fun startMode() {
        if (modeRunning) return
        if (!powerMode.isAvailable()) {
            if (!announcedUnavailable) {
                announcedUnavailable = true
                log("电源模式：本车机没有可用的电源接口，熄火自动暂停不生效")
            }
            return
        }
        policy.reset()
        modeRunning = true
        powerMode.start(powerListener)
        log("电源模式：已开始监听娱乐模式")
        // 首帧：订阅之后立刻问一次当前值，避免"启动时就已熄火"要等到下一次变化。
        handler.post {
            if (!modeRunning) return@post
            powerMode.current()?.let(::handleSnapshot)
        }
    }

    private fun stopMode() {
        if (!modeRunning) {
            handler.removeCallbacks(timeoutRunnable)
            policy.reset()
            return
        }
        modeRunning = false
        handler.removeCallbacks(timeoutRunnable)
        powerMode.stop()
        policy.reset()
    }

    private fun handleSnapshot(snapshot: CarPowerSnapshot) {
        if (!modeRunning) return
        log("电源事件：source=vehicle entertainmentOn=${snapshot.entertainmentOn} " +
            "infotainmentMode=${snapshot.infotainmentMode} elapsedMs=${clock()}；不是独立车门状态")
        when (policy.onSnapshot(snapshot, clock())) {
            PowerPauseAction.PAUSE_NOW -> {
                log(
                    "电源模式：娱乐模式关闭（信息娱乐=${snapshot.infotainmentMode}），" +
                        "暂停播放，${POWER_PAUSE_GRACE_MILLIS / 1000} 秒内恢复则不断开",
                )
                pausePlayback()
                scheduleTimeout()
            }
            PowerPauseAction.PAUSE_AND_STOP_SESSION -> Unit
            PowerPauseAction.NONE -> {
                if (policy.deadlineMs() == null) {
                    handler.removeCallbacks(timeoutRunnable)
                }
            }
        }
    }

    private fun scheduleTimeout() {
        handler.removeCallbacks(timeoutRunnable)
        val deadline = policy.deadlineMs() ?: return
        handler.postDelayed(timeoutRunnable, (deadline - clock()).coerceAtLeast(0L))
    }
}
