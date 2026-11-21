package com.dbtool.carplay.geely

import android.content.Context
import com.dbtool.carplay.api.display.CarClusterPresenter
import com.dbtool.carplay.api.display.CarHudProjection
import com.dbtool.carplay.api.navigation.CarHudBridge
import com.dbtool.carplay.api.system.CarSystemAdapter
import com.dbtool.carplay.api.vehicle.CarVehicleStatusSource
import com.dbtool.carplay.ecarx.EcarxCarPlatform
import com.dbtool.carplay.geely.hud.GeelyHudProjection
import com.dbtool.carplay.geely.vehicle.GeelyFuelVehicleStatusSource

/**
 * 吉利车机平台适配（含仪表 HDMI2 副屏）。
 *
 * ## 为什么继承 [EcarxCarPlatform] 而不是 [com.dbtool.carplay.android.AndroidCarPlatform]
 *
 * 吉利的车机（本工程实测的 QUALCOMM CS11 / Flyme Auto / OneOS）**本身就是 eCarx 平台**：
 * 方控走 OneOS KeyInput binder，而不是标准 `KeyEvent`。仪表 HDMI2 是**在此之上**
 * 多出来的一块能力。
 *
 * 若本类直接继承 Android 基线，就会把 Ecarx 已经接好的 OneOS 方控丢掉——
 * 那是**功能倒退**，不是"只少了副屏"。因此这里继承 Ecarx，只覆写三处：
 *
 * | 覆写 | 原因 |
 * | --- | --- |
 * | `id` / `displayName` | 平台身份报 `geely`，诊断页不出现自相矛盾 |
 * | `system` | 同上（`platformId` 取自 system 的 `id`） |
 * | `cluster` | **本模块存在的唯一理由**：把视频画面送到仪表 HDMI2 |
 *
 * 其余七个域（音频 / 显示 / 媒体 / 输入 / 定位 / 麦克风 / 诊断）全部继承
 * Ecarx → Android 的既有实现，一行不重写。
 *
 * ## 装配优先级（重要）
 *
 * 本机可能同时满足 Ecarx 与 Geely 判据 ⇒ `CarPlayApplication.selectPlatform`
 * 必须**先判 Geely**（Geely 是 Ecarx 的超集）。若先判 Ecarx，副屏永远接不上。
 * 具体见 `CarPlayApplication.selectPlatform` 的注释。
 */
class GeelyCarPlatform(
    context: Context,
) : EcarxCarPlatform(context) {

    override val id: String = GeelyCarSystemAdapter.PLATFORM_ID

    override val displayName: String = "Geely / eCarx（仪表 HDMI2 副屏）"

    override val system: CarSystemAdapter by lazy { GeelyCarSystemAdapter(context) }

    /**
     * 仪表副屏呈现。**惰性构造**：只有真正要用副屏的路径才会碰
     * `DisplayManager` / `Presentation`，不在装平台时就建窗口。
     */
    override val cluster: CarClusterPresenter by lazy {
        GeelyClusterPresenter(context, diagnostics)
    }

    /**
     * 导航转向提示桥（百度 hudbridge）。
     *
     * **惰性构造**：绑服务是有副作用的（会在车机上拉起一个外部服务），
     * 不该在装平台时就做。只有上层真正开始订阅导航时才构造。
     */
    override val hud: CarHudBridge by lazy { GeelyHudBridge(context) }

    /**
     * HUD **地图画面**投屏（2026-10-22）。
     *
     * 与 [hud] 完全独立：这里画整幅 CarPlay 地图，走 1024×274 的 display 3。
     * 同样惰性构造 —— 与 [cluster] 一样，只有真正开启投屏才会碰到
     * `DisplayManager` / `Presentation`。
     */
    override val hudProjection: CarHudProjection by lazy {
        GeelyHudProjection(context) { message -> diagnostics.log(HUD_TAG, message) }
    }

    /** Read-only ICE status. Opening a subscription, not platform selection, starts OEM work. */
    override val vehicleStatus: CarVehicleStatusSource by lazy {
        GeelyFuelVehicleStatusSource(context) { message ->
            diagnostics.log("XCExt.vehicle-status", message)
        }
    }


    companion object {
        /** logcat 过滤前缀：HUD 投屏与仪表副屏分开（`XCExt.carplay-cluster`）。 */
        private const val HUD_TAG = "XCExt.hud-projection"

        /**
         * 本机是否是**可能带仪表 HDMI2 副屏的吉利车机**。
         *
         * ## 判据只问「能力」，不问「副屏此刻在不在」（2026-10-07 修正）
         *
         * 早先这里还额外要求「**当下真的存在**那块 1920x1080 非主屏」。那条判据被证明是
         * **错的**，会形成一个死锁：
         *
         * ```
         * 要配置副屏 → 得先有设置入口
         * 有设置入口 → 得先选中 GeelyCarPlatform
         * 选中 GeelyCarPlatform → 得先检测到副屏
         * 检测到副屏 → 得先接上 / 车机先上报
         * ```
         *
         * 结果就是**用户正准备接副屏、想去设置里配一下的时候，入口根本不存在**
         * （实车现象：设置页没有「仪表导航」这一行）。
         *
         * 根子在于把两个不同性质的事实塞进了一个布尔：
         *
         * | 事实 | 性质 | 该由谁回答 |
         * | --- | --- | --- |
         * | 本机是 eCarx 族、**可能**有 HDMI2 副屏 | **能力**（稳定） | 选平台（这里） |
         * | 这块屏**此刻在不在**、能不能挂窗口 | **状态**（易变） | 运行时的 [cluster] / 设置页状态行 |
         *
         * 能力稳定、状态易变：副屏会因 HDMI 线、车机上报时机、重启而忽有忽无，
         * 拿易变的去决定稳定的（平台身份），就会让"平台"跟着抖。**判据分离**之后：
         *
         * - 这里只判能力 ⇒ 只要是 eCarx 族就选中本平台，设置入口恒在；
         * - 副屏在不在 ⇒ 由 [GeelyClusterPresenter.isAvailable] 与设置页
         *   `GeelyClusterPresenter.isAvailable` 如实回答。
         *
         * ⚠️ 这么改**不会**误选平台：`isEcarx` 仍然要求车机是 eCarx 族
         * （[GeelyDisplaySelector.isEcarx]，判据来自反编译实证）。在真正的 eCarx
         * 车机上选中本平台是**正确**的——它继承 [EcarxCarPlatform]，OneOS 方控等
         * 既有能力一个不少；副屏只是"多出来的那块能力"，没有也不影响平台身份。
         *
         * 选平台顺序仍必须先判 Geely（见 `CarPlayApplication.selectPlatform`）：
         * 现在是「eCarx 族 ⇒ Geely」这个超集关系，比原先严格包含。
         */
        fun isAvailable(context: Context): Boolean =
            GeelyDisplaySelector.isEcarx(context.applicationContext)
    }
}
