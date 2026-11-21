package com.dbtool.carplay.ecarx

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.dbtool.carplay.android.AndroidCarInputAdapter
import com.dbtool.carplay.api.input.CarInputListener
import com.dbtool.carplay.api.input.CarInputSource
import com.dbtool.carplay.api.input.CarKey
import com.dbtool.carplay.api.input.CarKeyAction

/**
 * Ecarx / 吉利 OneOS 车机的方控适配。
 *
 * 继承 [AndroidCarInputAdapter] 而不是重写一个：父类已经把
 * "`KeyEvent` → `CarKey` → 分发给 listener" 这段做好了，
 * 车机专有来源只需要把原始键码喂给它的
 * [AndroidCarInputAdapter.handleKeyCode] 出口。
 * 这里多出来的只有两件事：
 *
 * 1. **接上 OneOS KeyInput**（[OneOsKeyInputBridge]）——方控按键不走 `KeyEvent`，
 *    而是车机 ECU 通过 binder 推过来的 `swsm` 键码。
 * 2. **补一张 Ecarx 键码表**（[EcarxKeyCodes]）——那些 20 万量级的键码
 *    在父类的 `KEYCODE_*` 表里一个也命中不了。
 *
 * 抖动抑制不在这一层做：父类 `AndroidCarInputAdapter.dispatch` 已对 DOWN
 * 做 350ms 的按 key 静默窗口，本适配器经 `handleKeyCode` 自然继承。
 * 两边都做会互相掩盖问题。
 */
class EcarxCarInputAdapter(
    context: Context,
    private val clientId: String = OneOsKeyInputBridge.DEFAULT_CLIENT_ID,
) : AndroidCarInputAdapter() {

    private val appContext = context.applicationContext

    private val bridge = OneOsKeyInputBridge(
        context = appContext,
        clientId = clientId,
        keyCodes = EcarxKeyCodes.SUBSCRIBED,
        onRawKey = ::onRawKey,
    )

    // ------------------------------------------------------------- 生命周期

    override fun attach(listener: CarInputListener) {
        super.attach(listener)
        bridge.start()
    }

    override fun detach(listener: CarInputListener) {
        super.detach(listener)
        if (!hasListeners()) bridge.stop()
    }

    /**
     * 车机方控焦点是**排他**的：不注册就收不到按键。
     * 所以申请独占等价于把 KeyInput 桥接起来。
     */
    override fun requestExclusiveAccess(): Boolean {
        val granted = super.requestExclusiveAccess()
        bridge.start()
        return granted
    }

    override fun releaseExclusiveAccess() {
        super.releaseExclusiveAccess()
        if (!hasListeners()) bridge.stop()
    }

    /** 释放桥接线程。进程退出前调用；不调也只会留一个空闲线程。 */
    fun close() {
        bridge.close()
    }

    // --------------------------------------------------------------- 能力探测

    /**
     * 本车机是否具备 OneOS KeyInput。
     *
     * 探测的是 `OneOSApiService` 这个组件是否存在。非吉利车机上它会返回
     * false，UI 据此隐藏方控相关开关，而不是让用户对着一个永远不响的开关。
     *
     * 注意：这里**不能**用"bind 是否成功"来判断——`bindService` 是异步的，
     * 而且失败时只给一个 false，分不清"没有这个服务"和"被权限拦了"。
     */
    override fun isSupported(): Boolean = OneOsKeyInputBridge.isAvailable(appContext)

    /** 是否已成功注册到车机（诊断用）。 */
    fun isBridgeRegistered(): Boolean = bridge.isRegistered()

    /** 当前方控焦点控制器索引；`2` = 媒体页，`-999` = 查不到。 */
    fun currentControlIndex(): Int = bridge.currentControlIndex()

    // --------------------------------------------------------------- 键码映射

    override fun keyCodeToCarKey(keyCode: Int): CarKey? = when (keyCode) {
        EcarxKeyCodes.MEDIA_PLAY_PAUSE -> CarKey.PLAY_PAUSE
        EcarxKeyCodes.MEDIA_NEXT -> CarKey.NEXT
        EcarxKeyCodes.MEDIA_PREVIOUS -> CarKey.PREVIOUS
        EcarxKeyCodes.VOLUME_UP -> CarKey.VOLUME_UP
        EcarxKeyCodes.VOLUME_DOWN -> CarKey.VOLUME_DOWN
        // 2026-10-24：方控语音键 → CarKey.VOICE → CarKeyHidMap 映射到 Intent.Siri。
        // 麦克风上行本身已通（microphone uplink started codec=OPUS），只差这个键。
        EcarxKeyCodes.VOICE -> CarKey.VOICE
        // 车机上也可能同时存在走标准 KeyEvent 的按键（比如后装的蓝牙旋钮），
        // 所以落到父类的表而不是直接返回 null。
        else -> super.keyCodeToCarKey(keyCode)
    }

    // ------------------------------------------------------------- 事件入口

    private fun onRawKey(keyCode: Int, action: Int, repeatCount: Int) {
        if (!acceptByControlIndex(keyCode)) return
        val key = keyCodeToCarKey(keyCode)
        if (key == null) {
            Log.i(TAG, "未映射的方控键码 keyCode=$keyCode action=$action（需要补 EcarxKeyCodes）")
            return
        }
        val consumed = handleKeyCode(
            keyCode = keyCode,
            action = action.toCarKeyAction(),
            timestampMillis = SystemClock.uptimeMillis(),
            repeatCount = repeatCount,
            source = CarInputSource.STEERING_WHEEL,
        )
        Log.i(
            TAG,
            "方控 key=$key raw=$keyCode action=$action repeat=$repeatCount consumed=$consumed",
        )
    }

    /**
     * 门禁：方控焦点不在媒体控制器上时不消费按键。
     *
     * 车机把方控焦点放在不同控制器上（车信息页、空调页……），
     * 只有焦点在媒体页时按键才应该作用到 CarPlay。
     *
     * 查询失败（[OneOsKeyInputBridge.CONTROL_INDEX_UNKNOWN]）时**放行**：
     * binder 抖动比误触更常见，宁可按错了也别让方控整体失灵。
     * 这与 DBTOOL2.0 的处理一致。
     *
     * ## ⚠️ 这里**不**做去重，去抖在 `AndroidCarInputAdapter.dispatch`（350ms 窗口）
     *
     * `OneOsKeyInputBridge` 对 `onKeyCodeEvent` 与 `onShortClick` **两条回调都照发**，
     * 不判断它们是不是同一次按压 —— 与参考实现一致。**本层仍不去重。**
     *
     * ### 原注释的判断已实测推翻
     *
     * 旧注释写：「实车上一次按压只产生一条 DOWN……若两条都投递，
     * 那两个键会一次跳两首，用户早就报了。」
     *
     * **2026-10-03 实车日志证明这条观察是错的。** `devlog_2149`（21:47:37）原文：
     *
     * ```
     * 21:47:37.595  方控 key=PLAY_PAUSE raw=200085 action=0 repeat=0 consumed=true
     * 21:47:37.898  方控 key=PLAY_PAUSE raw=200085 action=1 repeat=0 consumed=false
     * 21:47:37.925  方控 key=PLAY_PAUSE raw=200085 action=0 repeat=0 consumed=true  ← 距上一条仅 27ms
     * ```
     *
     * 一次物理按压产生**两条 DOWN**（间隔 123~330ms），对应「按暂停后音乐又自己播」
     * 「音乐老是断」—— 正是旧注释预言、却被判为「用户早就该报了」的那个症状。
     *
     * 为什么「跳两首」没被早期发现：NEXT 的重复上报也会跳两首，但跳歌不易察觉
     * （用户以为是自己按重了）；而 PLAY_PAUSE 两次 = 暂停又播放，一眼可见。
     *
     * **结论**：去抖统一由 `AndroidCarInputAdapter.dispatch`（默认 350ms）负责，
     * 本适配器经 `handleKeyCode` 走同一个出口；本层不要另加判定 ——
     * 两边都做会互相掩盖问题。
     */
    private fun acceptByControlIndex(keyCode: Int): Boolean {
        // 语音键不走方控焦点门禁：焦点在哪个控制器上都应该能唤 Siri
        // （原逻辑要求焦点在媒体控制器上，语音键会被直接丢）。
        if (keyCode == EcarxKeyCodes.VOICE) return true
        val index = bridge.currentControlIndex()
        if (index == OneOsKeyInputBridge.CONTROL_INDEX_UNKNOWN) return true
        if (index == OneOsKeyInputBridge.CONTROL_INDEX_MEDIA) return true
        Log.i(TAG, "方控焦点不在媒体控制器(controlIndex=$index)，丢弃 keyCode=$keyCode")
        return false
    }

    private fun Int.toCarKeyAction(): CarKeyAction = when (this) {
        OneOsKeyInputBridge.ACTION_DOWN -> CarKeyAction.DOWN
        OneOsKeyInputBridge.ACTION_UP -> CarKeyAction.UP
        else -> CarKeyAction.MULTIPLE
    }

    private companion object {
        const val TAG = "Ecarx-Input"
    }
}
