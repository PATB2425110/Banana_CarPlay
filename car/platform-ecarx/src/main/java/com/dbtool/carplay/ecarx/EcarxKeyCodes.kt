package com.dbtool.carplay.ecarx

/**
 * 吉利 / 领克 ECarX 车机的方控键码。
 *
 * 这些**不是** Android `KeyEvent.KEYCODE_*`，而是车机 ECU 自己的 `swsm`
 * 键码空间（10 万量级），由 OneOS KeyInput 服务原样抛出。所以不能指望
 * `AndroidCarInputAdapter` 里那张 `KeyEvent` 映射表命中它们，必须单独成表。
 *
 * 值来源：DBTOOL2.0 `OneOSClient.OneOSKeyInputClient` 的 `R_MEDIA_*` 常量，
 * 该工程在吉利车机上实测有效。
 *
 * 为什么做成常量对象而不是散落的 `when`：
 * 车机方控键码是**部署相关**的——同款车机不同批次、不同配置（有无音量旋钮、
 * 有无自定义键）键码都可能不同。集中在一处便于现场核对与替换，
 * 也与 `:car:steering` 的 `CarKeyMap`「配表而不是硬编码」的取向一致。
 */
internal object EcarxKeyCodes {

    const val MEDIA_PLAY_PAUSE = 200085
    const val MEDIA_NEXT = 200087
    const val MEDIA_PREVIOUS = 200088
    const val VOLUME_UP = 200024
    const val VOLUME_DOWN = 200025

    /**
     * 方向盘麦克风 / 语音键（2026-10-24 实车采集）。
     *
     * 本车（CS11）方控语音键上报 `swsm keyevent keycode:200231`，
     * 对应 `KEYCODE_R_VOICE_ASSIST`。日志实证：
     * `ECarXCarHardKeyService: swsm keyevent keycode:200231 action:0/1`
     *
     * 此前 SUBSCRIBED 只有 5 个媒体键，这个键永远到不了 DBPlay，
     * 被 `com.geely.inputservice` 直接路由给车机自带语音（DuerOS）。
     */
    const val VOICE = 200231

    /**
     * 注册给车机的关注列表。
     *
     * 只订阅确实会映射成 [com.dbtool.carplay.api.input.CarKey] 的键。
     * 不要图省事传空数组去"全都要"：那会把音量旋钮的每一次微调、
     * 甚至车机内部按键都推过来，既浪费 binder 往返，也让诊断日志不可读。
     */
    val SUBSCRIBED: IntArray = intArrayOf(
        MEDIA_PLAY_PAUSE,
        MEDIA_NEXT,
        MEDIA_PREVIOUS,
        VOLUME_UP,
        VOLUME_DOWN,
        VOICE,
    )
}
