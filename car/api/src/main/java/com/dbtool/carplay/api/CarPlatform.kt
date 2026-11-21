package com.dbtool.carplay.api

import com.dbtool.carplay.api.audio.CarAudioAdapter
import com.dbtool.carplay.api.diagnostics.CarDiagnosticsAdapter
import com.dbtool.carplay.api.display.CarClusterPresenter
import com.dbtool.carplay.api.display.CarDisplayAdapter
import com.dbtool.carplay.api.display.CarHudProjection
import com.dbtool.carplay.api.input.CarInputAdapter
import com.dbtool.carplay.api.media.CarMediaAdapter
import com.dbtool.carplay.api.microphone.CarMicrophoneAdapter
import com.dbtool.carplay.api.navigation.CarHudBridge
import com.dbtool.carplay.api.navigation.CarNavigationAdapter
import com.dbtool.carplay.api.system.CarSystemAdapter

/**
 * 一个车机平台的适配聚合。
 *
 * 依赖方向（唯一允许的方向）：
 *
 * ```
 * CarPlay Core (upstream :shared)
 *        ↓  (只通过构造函数注入，Core 不认识本接口)
 * CarAdapter = CarPlatform 实现
 *        ↓
 * 具体车机系统（Android / Flyme / OSN / Ecarx ...）
 * ```
 *
 * 约定：
 * - 上层（UI、服务、产品 App）只依赖 [CarPlatform] 及其子接口，不直接依赖任何具体车机实现。
 * - 新增车机只需要新增一个 [CarPlatform] 实现，不改动 CarPlay Core。
 * - 本接口刻意保持最小：每个子适配器只暴露"车机确实需要知道的东西"，
 *   不为抽象而抽象。
 */
interface CarPlatform {
    /** 稳定标识，例如 `android` / `flyme` / `osn` / `ecarx`。用于日志与配置选择。 */
    val id: String

    /** 人类可读名称，用于 UI 与诊断。 */
    val displayName: String

    val audio: CarAudioAdapter
    val display: CarDisplayAdapter
    val media: CarMediaAdapter
    val input: CarInputAdapter
    val system: CarSystemAdapter
    val navigation: CarNavigationAdapter
    val microphone: CarMicrophoneAdapter
    val diagnostics: CarDiagnosticsAdapter

    /**
     * 仪表盘（副屏）呈现。
     *
     * 默认 **null** = 本车机没有已验证的副屏能力。做成可空而不是"返回一个
     * 空实现"，是因为副屏是**增值能力**：null 让上层一眼看出"没有"，不必
     * 反复试 `isAvailable()`；也让绝大多数平台实现**一行都不用写**。
     *
     * 需要副屏的车机（如吉利 eCarx 的 HDMI2 仪表）覆写此属性。
     */
    val cluster: CarClusterPresenter? get() = null

    /**
     * 导航转向提示桥（HUD）。
     *
     * 默认 **null** = 本车机没有已验证的 HUD 提示通道。同样是增值能力，
     * 理由与 [cluster] 一致。需要它的车机（如吉利 eCarx 的百度 hudbridge）覆写。
     */
    val hud: CarHudBridge? get() = null

    /**
     * HUD **地图画面**投屏能力（2026-10-22）。
     *
     * 与 [hud]（转向提示桥）是两条不同的链：本项把整幅 CarPlay 地图画面画到
     * 一块 1024×274 的 HUD 屏上，需要平台层有 `Presentation` 窗口实现。
     *
     * 默认 **null** = 本车机没有已验证的 HUD 投屏能力。理由与 [cluster] 相同：
     * 增值能力，没实现的平台一行都不用写。
     */
    val hudProjection: CarHudProjection? get() = null

    /** Optional, read-only vehicle telemetry; absent on tablets and ordinary Android platforms. */
    val vehicleStatus: com.dbtool.carplay.api.vehicle.CarVehicleStatusSource? get() = null

}

/**
 * 进程级平台安装点。
 *
 * 产品 App 在 `Application.onCreate` 中安装一次即可；后续所有扩展模块通过
 * [current] 获取，避免把具体车机实现硬编码进调用方。
 *
 * 这里使用显式注册而不是反射/ServiceLoader：车机环境常有 SELinux 与类加载器限制，
 * 显式注册更容易排查，也不会在启动阶段引入不可控的类加载。
 */
object CarPlatformRegistry {
    @Volatile
    private var installed: CarPlatform? = null

    /** 安装平台实现。重复安装会被拒绝，避免多入口互相覆盖。 */
    @Synchronized
    fun install(platform: CarPlatform) {
        val existing = installed
        check(existing == null || existing === platform) {
            "A CarPlatform is already installed: ${existing?.id}. " +
                "Reset it explicitly before installing another one."
        }
        installed = platform
    }

    /** 已安装则返回 true。 */
    fun isInstalled(): Boolean = installed != null

    fun currentOrNull(): CarPlatform? = installed

    /** 未安装时抛出，用于明确要求平台能力的调用点。 */
    fun current(): CarPlatform = installed
        ?: error("No CarPlatform installed. Call CarPlatformRegistry.install() in Application.onCreate.")

    /** 仅用于测试与本地诊断。 */
    @Synchronized
    fun reset() {
        installed = null
    }
}
