package com.dbtool.carplay.ecarx

import android.content.Context
import com.dbtool.carplay.android.AndroidCarPlatform
import com.dbtool.carplay.api.input.CarInputAdapter
import com.dbtool.carplay.api.media.CarMediaAdapter
import com.dbtool.carplay.api.system.CarSystemAdapter
import com.dbtool.carplay.ecarx.media.EcarxCarMediaAdapter

/**
 * Ecarx / 吉利 OneOS 车机平台适配。
 *
 * 只覆写两个域：
 *
 * | 域 | 覆写原因 |
 * | --- | --- |
 * | `input` | 方控按键不走 `KeyEvent`，要接车机 ECU 的 OneOS KeyInput binder |
 * | `system` | 平台身份要报 `ecarx` 而不是 `android` |
 *
 * 其余六个域（音频 / 显示 / 媒体 / 麦克风 / 定位 / 诊断）沿用
 * [AndroidCarPlatform] 的默认实现——Ecarx 车机在这几项上与标准 Android
 * 没有已验证的差异，凭猜测写实现只会制造没人敢删的死代码。
 *
 * 用法（产品 App）：
 * ```kotlin
 * if (EcarxCarPlatform.isAvailable(context)) {
 *     CarPlatformRegistry.install(EcarxCarPlatform(context))
 * } else {
 *     CarPlatformRegistry.install(AndroidCarPlatform(context))
 * }
 * ```
 *
 * ## 为什么是 `open`（2026-10-07）
 *
 * 吉利车机**本身就是 eCarx**（方控走 OneOS KeyInput binder），只是在此之上
 * 多出「仪表 HDMI2 副屏」等厂商专有能力。`car/platform-geely` 的
 * `GeelyCarPlatform` 因此继承本类、只覆写差异项，从而**自动继承**这里已经接好的
 * OneOS 方控 —— 若改成继承 Android 基线，那些接口会全部丢失。
 * 因此本类必须是 `open`，与 `AndroidCarPlatform` 的"可继承骨架"定位一致。
 */
open class EcarxCarPlatform(
    context: Context,
    private val clientId: String = OneOsKeyInputBridge.DEFAULT_CLIENT_ID,
) : AndroidCarPlatform(context) {

    override val id: String = EcarxCarSystemAdapter.PLATFORM_ID

    override val displayName: String = "Ecarx / 吉利 OneOS"

    override val input: CarInputAdapter by lazy { EcarxCarInputAdapter(context, clientId) }

    override val system: CarSystemAdapter by lazy { EcarxCarSystemAdapter(context) }

    /**
     * 媒体卡片 / 仪表盘封面。
     *
     * 车机只显示它认识的媒体源，而 CarPlay 的画面是"投上去的"——
     * 系统不知道 iPhone 在放什么，仪表盘就一直空着。这里换成 Ecarx 实现：
     * **伪装成帆书**走它的广播通道上报曲目，封面用本地 HTTP 服务喂 URL。
     *
     * 非吉利车机上 [EcarxCarMediaAdapter] 会自己探测不到接收者并静默跳过，
     * 所以即便平台判定错了也不会产生副作用。
     */
    override val media: CarMediaAdapter by lazy { EcarxCarMediaAdapter(context) }

    companion object {
        /**
         * 本机是否是 Ecarx / OneOS 车机。
         *
         * 判据是"车机上有没有 OneOS 的服务总线"，而不是读 `Build.MANUFACTURER`
         * 之类的字符串：厂商名会被刷机包、白牌车机改掉，而
         * `com.geely.service.oneosapi` 这个组件在就在、不在就不在。
         */
        fun isAvailable(context: Context): Boolean =
            OneOsKeyInputBridge.isAvailable(context.applicationContext)
    }
}
