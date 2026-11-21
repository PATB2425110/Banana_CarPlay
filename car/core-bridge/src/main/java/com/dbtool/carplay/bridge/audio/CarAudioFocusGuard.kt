package com.dbtool.carplay.bridge.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import com.dbtool.carplay.bridge.CarPlayMediaSink

/**
 * 音频焦点守卫（0.8.5）。
 *
 * ## 为什么需要它
 *
 * 现场现象：CarPlay 播歌时音乐**频繁中断**，日志里看到音频流（type=102）
 * 被对端反复 `TEARDOWN` → 几秒后又重建。而视频流（type=110）**一次都没被拆**。
 *
 * 排查结论（车机侧取证）：
 * - 车机上装着第三方车机音乐（`com.luna.music.car`）与歌词播报，
 *   每次切歌都会写它的播放队列库、并触发车机 SmartBar 的 `MediaMusicPlayState` 状态机；
 * - 车机自带的媒体状态机一被搅动，**音响焦点就被反复抢走/归还**；
 * - **本 App 此前从不申请音频焦点**（全仓 `requestAudioFocus` 为零），
 *   于是「谁在放声音」这件事对系统而言一直悬空：
 *   别的播放器一申请就拿到，iPhone 那头察觉到音频路由被夺，就把音频流拆掉重来。
 *
 * 原厂 CarPlay 的做法（对照 DiPlay 的吉利适配文档）与此一致：
 * 由一个 sink 独占媒体焦点，**暂停期间也不释放**，焦点真的丢了就静音自己、
 * 会话结束时才释放。
 *
 * ## 本实现的边界
 *
     * - 通过 sink 的媒体专用增益接口控制本地 AudioTrack；shared 提供窄接口。
     * - LOSS/LOSS_TRANSIENT 立即把媒体音轨增益设为0；GAIN恢复1；DUCK为0.5，
     *   不覆盖已有LOSS。系统总音量、通话/导航音轨与路由配置不改。
 * - 车机上取不到 `AudioManager`（例如单测、纯逻辑环境）时整体退化为空操作，
 *   不抛异常——守卫属于「锦上添花」，绝不能反过来让主链路起不来。
 *
 * ## 为什么暂停不释放焦点（关键）
 *
 * 播放暂停时若把焦点交还，系统会认为「本 App 已退出放音」，此时任何别的
 * 播放器都能顺手拿走焦点；等用户再按播放，就要**重新抢**，中间这段时间
 * 音频路由是断的，表现就是「切完歌后不自动续播」。
 * 因此暂停时保持 `AUDIOFOCUS_GAIN`，只在会话真正结束时 `abandon`。
 */
class CarAudioFocusGuard(
    /**
     * 上下文。**允许为 null**：拿不到 AudioManager 时整体退化为空操作，
     * 让守卫在单测与无音频服务的车机上都不会把主链路带崩。
     */
    private val context: Context?,
    /** 被守护的音频输出槽位；焦点丢失/恢复时对它做静音与恢复。 */
    private val sink: CarPlayMediaSink,
    /** 诊断日志出口，默认走 android.util.Log。 */
    private val log: (String) -> Unit = {},
    navigationAudioFocusEnabled: Boolean = true,
) {

    private val audioManager: AudioManager? =
        runCatching { context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }.getOrNull()

    /** 当前是否持有焦点。用于幂等：重复申请/重复释放都不做事。 */
    @Volatile
    var holdsFocus: Boolean = false
        private set

    /** App-local media gain only, not a system-volume snapshot. */
    @Volatile
    private var mediaGain: Float = 1f

    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                // 焦点彻底回到我们手里：恢复正常音量。
                restoreVolume(reason = "gain")
            }

            AudioManager.AUDIOFOCUS_LOSS -> {
                // 永久丢失（别的播放器抢走了）。**不 abandon**：
                // 车机可能在一次抖动里发出 LOSS 又马上把焦点还回来，
                // 主动 abandon 会让「暂停期间保住焦点」这条策略失效。
                mute(reason = "loss")
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // 暂时丢失（导航播报、来电、Siri）。静音，等 GAIN 回来再恢复。
                mute(reason = "loss_transient")
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // 允许压低音量继续播：这里只压到一半，不静音，避免把音乐彻底掐掉。
                duck()
            }

            else -> log("audio focus unknown change=$change")
        }
    }

    private val focusRequest: AudioFocusRequest? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                // 关键：交付模式必须同时声明「独占（换焦点时对方必须停）」与
                // 「暂停可 Duck」。只声明 gain 在部分车机 ROM 上会被系统降级处理。
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener(listener)
                .build()
        } else {
            null
        }

    private fun navigationAttributes(): AudioAttributes {
        var usage = AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
        var content = AudioAttributes.CONTENT_TYPE_SPEECH
        runCatching {
            val policyType = Class.forName("com.ecarx.xui.adaptapi.policy.Policy")
            val policy = policyType.getMethod("create", Context::class.java).invoke(null, context)
            val attributes = policyType.getMethod("getAudioAttributes").invoke(policy)
            val api = Class.forName("com.ecarx.xui.adaptapi.policy.IAudioAttributes")
            usage = api.getMethod("getAudioAtrributesUsage", String::class.java)
                .invoke(attributes, "NAVIGATION_GUIDANCE") as Int
            content = api.getMethod("getAudioAtrributesContentType", String::class.java)
                .invoke(attributes, "NAVI_GUIDANCE") as Int
        }.onFailure { log("navigation audio policy unavailable; using Android navigation attributes: ${it.javaClass.simpleName}") }
        log("navigation audio focus attributes usage=$usage contentType=$content")
        return AudioAttributes.Builder().setUsage(usage).setContentType(content).build()
    }

    private val navigationListener = AudioManager.OnAudioFocusChangeListener { change ->
        log("navigation audio focus change=$change")
    }

    private val navigationFocusRequest: AudioFocusRequest? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(navigationAttributes())
                .setAcceptsDelayedFocusGain(false)
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener(navigationListener)
                .build()
        } else null
    }

    @Volatile
    private var holdsNavigationFocus = false

    private val navigationState = NavigationAudioFocusState(
        enabled = navigationAudioFocusEnabled,
        acquire = ::acquireNavigationFocus,
        release = ::releaseNavigationFocus,
    )

    /** Speech remains tracked while disabled so re-enabling during an announcement restores focus. */
    fun setNavigationSpeechActive(active: Boolean) = navigationState.setSpeechActive(active)

    fun setNavigationAudioFocusEnabled(enabled: Boolean) = navigationState.setEnabled(enabled)

    /** Match the factory TTS lifecycle so steering-wheel volume targets navigation while speaking. */
    private fun acquireNavigationFocus(): Boolean {
        val am = audioManager ?: return false
        if (holdsNavigationFocus) return true
        val request = navigationFocusRequest
        val result = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && request != null) {
                am.requestAudioFocus(request)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    navigationListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
                )
            }
        }.getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        holdsNavigationFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        log("navigation audio focus acquire granted=$holdsNavigationFocus result=$result")
        return holdsNavigationFocus
    }

    private fun releaseNavigationFocus() {
        val am = audioManager ?: return
        if (!holdsNavigationFocus) return
        val request = navigationFocusRequest
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && request != null) {
                am.abandonAudioFocusRequest(request)
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(navigationListener)
            }
        }
        holdsNavigationFocus = false
        log("navigation audio focus released")
    }

    /** 申请媒体焦点。幂等：已持有则直接返回 true。 */
    fun acquire(): Boolean {
        val am = audioManager ?: run {
            log("audio focus skipped: no AudioManager")
            return false
        }
        if (holdsFocus) return true
        val granted = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
                am.requestAudioFocus(focusRequest)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    listener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN,
                )
            }
        }.getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)

        holdsFocus = granted == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        log("audio focus acquire granted=$holdsFocus result=$granted")
        if (holdsFocus) restoreVolume(reason = "acquire")
        return holdsFocus
    }

    /**
     * 释放媒体焦点。只在**会话结束**时调用。
     *
     * 刻意不暴露「暂停时释放」的入口：暂停保持焦点是本类的核心策略，
     * 见类注释「为什么暂停不释放焦点」。
     */
    fun release() {
        navigationState.close()
        val am = audioManager ?: return
        if (!holdsFocus) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
                am.abandonAudioFocusRequest(focusRequest)
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(listener)
            }
        }
        holdsFocus = false
        restoreVolume(reason = "release")
        log("audio focus released")
    }

    // ------------------------------------------------------------ 静音与恢复

    private fun mute(reason: String) {
        applyMediaGain(0f, "mute", reason)
    }

    private fun duck() {
        // A DUCK event must not unmute an outstanding LOSS.
        if (mediaGain != 0f) applyMediaGain(0.5f, "duck", "can_duck")
    }

    private fun restoreVolume(reason: String) {
        applyMediaGain(1f, "restore", reason)
    }

    @Synchronized private fun applyMediaGain(gain: Float, action: String, reason: String) {
        mediaGain = gain
        val applied = runCatching { sink.setMediaOutputGain(gain) }.getOrDefault(false)
        log("audio focus $action reason=$reason gain=$gain localApplied=$applied")
    }

    /** 供诊断面板读取的当前状态（不参与逻辑）。 */
    fun describe(): String = "holdsFocus=$holdsFocus muted=${mediaGain == 0f}"
}
