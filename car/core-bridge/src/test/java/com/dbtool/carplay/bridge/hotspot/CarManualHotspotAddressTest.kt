package com.dbtool.carplay.bridge.hotspot

import com.dbtool.carplay.api.settings.CarHotspotMode
import com.dbtool.carplay.api.settings.CarMfiTarget
import com.dbtool.carplay.api.settings.CarSessionSettings
import com.dbtool.carplay.bridge.CarPlayConfigBuilder
import com.dbtool.carplay.bridge.network.CarHotspotResolver
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「复用车机热点的地址由用户填」这条链的接线测试（PATCH-0087，2026-10-11）。
 *
 * ## 车主口径（verbatim）
 *
 * > 复用车机热点是要用户自己手动设置，而不是自动识别
 *
 * ## 为什么这条链要单独测
 *
 * 它是**跨四层**的：设置页（`manualHotspotHostAddress`）→ 方案（`CarHotspotPlan.manualHostAddress`）
 * → 运行配置（`CarPlayRuntimeConfig.manualHotspotHostAddress`）→
 * 上游 `ManualHotspotManager.hostAddressOverride`（按地址找网卡，不再按网卡名打分）。
 * 中间任何一层漏接，表现都是"设置页填了、却还是按网卡名猜"——
 * 而那正是 2026-10-11 两次认错（把自建残留 `192.168.176.1` 当成车机热点）的形态，
 * 在真车上要等 40 秒才看得出，所以必须在这里穷举。
 *
 * 判据全部在纯函数/数据类上（`withoutProbe` 不碰 `Context`），因此能在 JVM 上跑。
 */
class CarManualHotspotAddressTest {

    private fun reuseSettings(address: String) = CarSessionSettings(
        hotspotMode = CarHotspotMode.MANUAL,
        // 把 MFi 目标换成"远端"，免得 `runtimeConfig` 因为"本地环回服务还没起"而拒绝构造
        // （这条测试只关心热点那几项，与 MFi 通道无关）。
        mfiTarget = CarMfiTarget.REMOTE,
        remoteMfiServer = "https://mfi.example.invalid",
        manualHotspotSsid = "Lynk",
        manualHotspotPassphrase = "12345678",
        manualHotspotHostAddress = address,
    )

    @Test
    fun `复用方案带上用户填的地址`() {
        val plan = CarHotspotResolver.withoutProbe(reuseSettings("192.168.15.1"))

        assertEquals(CarHotspotMode.MANUAL, plan.mode)
        assertEquals(true, plan.reused)
        assertEquals("192.168.15.1", plan.manualHostAddress)
    }

    @Test
    fun `Ecarx验证的接口和本机endpoint一起透传给上游`() {
        val settings = reuseSettings("192.168.15.1")
        val plan = CarHotspotResolver.withoutProbe(settings).copy(
            manualHostAddress = "198.18.34.15",
            manualHostInterfaceName = "eth0.11",
        )
        val runtime = CarPlayConfigBuilder.runtimeConfig(settings, hotspotPlan = plan)

        assertEquals("198.18.34.15", runtime.manualHotspotHostAddress)
        assertEquals("eth0.11", runtime.manualHotspotInterfaceName)
    }

    /** 空白（含只敲了空格）等同于没填 —— 拦截文案由 `CarManualHotspotPrecheck` 给。 */
    @Test
    fun `没填或只敲空格时方案里是 null`() {
        for (address in listOf("", "   ")) {
            assertNull(
                "'$address' 必须被当成没填",
                CarHotspotResolver.withoutProbe(reuseSettings(address)).manualHostAddress,
            )
        }
    }

    /** 自建那一支**不读**这个字段（它的网段/网关由 `CarAdbHotspotPlanner` 选）。 */
    @Test
    fun `自建热点不读这个字段`() {
        val plan = CarHotspotResolver.withoutProbe(
            CarSessionSettings(
                hotspotMode = CarHotspotMode.ADB_HOSTAPD,
                manualHotspotHostAddress = "192.168.15.1",
            ),
        )

        assertNull("ADB 自建不该被「车机热点地址」影响", plan.manualHostAddress)
    }

    /** 只有「复用」那一支才把它交给上游 —— 其余形态一律不传。 */
    @Test
    fun `只有复用才把地址透传给上游`() {
        val reusePlan = CarHotspotResolver.withoutProbe(reuseSettings("192.168.15.1"))
        val reuseRuntime = CarPlayConfigBuilder.runtimeConfig(
            reuseSettings("192.168.15.1"),
            hotspotPlan = reusePlan,
        )
        assertEquals(WirelessHotspotMode.MANUAL, reuseRuntime.wirelessHotspotMode)
        assertEquals("192.168.15.1", reuseRuntime.manualHotspotHostAddress)

        val adbSettings = CarSessionSettings(
            hotspotMode = CarHotspotMode.ADB_HOSTAPD,
            mfiTarget = CarMfiTarget.REMOTE,
            remoteMfiServer = "https://mfi.example.invalid",
        )
        val adbRuntime = CarPlayConfigBuilder.runtimeConfig(
            adbSettings,
            hotspotPlan = CarHotspotResolver.withoutProbe(adbSettings),
        )
        assertNull("自建不该带这个字段", adbRuntime.manualHotspotHostAddress)
    }
}
