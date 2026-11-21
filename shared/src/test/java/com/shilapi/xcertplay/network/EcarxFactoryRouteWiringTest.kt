package com.shilapi.xcertplay.network

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 原厂网关 fallback 的接线契约（移植 DiPlay v0.2.16 / docs/KX11_ROUTED_HOTSPOT.md）。
 *
 * 行为本身由 [ManualHotspotReadinessTest] 覆盖；这里只钉两件“接线错了就静默失效”的事：
 * 1. 探测必须发生在读 Android SoftApConfiguration **之前**（否则 AP=false/配置不一致
 *    会在进入原厂分支前就把连接判死，DiPlay 明确要求“Android AP=false 不拒绝该网关”）；
 * 2. 只有 `factoryRoute` 且 `kx11Source` 严格匹配的候选才允许返回，
 *    避免把普通 wlan0 / 平台 SoftAP 候选误当成原厂网关。
 */
class EcarxFactoryRouteWiringTest {

    private val source =
        "src/main/java/com/shilapi/xcertplay/network/ManualHotspotManager.kt"

    @Test
    fun `原厂网关探测在读取 Android AP 配置之前`() {
        val code = File(source).readText()
        val probe = code.indexOf("resolveEcarxFactoryHotspot(timeoutMillis)")
        val readConfig = code.indexOf("val apConfiguration = if (selfHostedAccessPoint)")
        assertTrue("找不到原厂网关探测调用", probe >= 0)
        assertTrue("找不到 Android AP 配置读取点", readConfig >= 0)
        assertTrue(
            "原厂网关必须在读 Android SoftApConfiguration 之前判定，否则 AP=false 会提前判死",
            probe < readConfig,
        )
    }

    @Test
    fun `只接受 factoryRoute 且 kx11Source 严格匹配的候选`() {
        val code = File(source).readText()
        val body = code.substringAfter("private fun resolveEcarxFactoryHotspot(")
            .substringBefore("private fun factoryHotspotInfo(")
        assertTrue("必须检查 selection.factoryRoute", body.contains("selection.factoryRoute"))
        assertTrue("必须再次用 kx11Source 核接口与源地址", body.contains("EcarxClientRoute.kx11Source("))
        assertTrue("必须先用 isEcarxPlatform 做平台闸门", body.contains("EcarxClientRoute.isEcarxPlatform("))
        assertTrue(
            "自建与调用方显式指定地址必须先排除",
            body.contains("selfHostedAccessPoint || hostAddressOverride != null"),
        )
        assertTrue("必须接总开关，支持一键回滚", body.contains("Kx11FactoryRoutePort.ENABLED"))
    }

    @Test
    fun `AirPlay 监听地址接总开关且保留 bind双冒号 回滚路径`() {
        val code = File(
            "src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt",
        ).readText()
        val idx = code.indexOf("val airPlayBindAddress = if (Kx11FactoryRoutePort.ENABLED)")
        assertTrue("找不到带总开关的 bindAddress 接线", idx >= 0)
        assertTrue(
            "总开关关闭时必须回到 1.1.42 的 bind ::",
            code.substring(idx, (idx + 400).coerceAtMost(code.length))
                .contains("InetAddress.getByName(\"::\")"),
        )
        assertTrue(
            "监听必须使用这个地址，而不是再次写死 ::",
            code.contains("bindAddress = airPlayBindAddress"),
        )
    }

    @Test
    fun `原厂分支跳过 Android AP 配置校验且 channel 为 0 无 BSSID`() {
        val code = File(source).readText()
        val body = code.substringAfter("private fun factoryHotspotInfo(")
            .substringBefore("private fun interfaceScore(")
        assertTrue("channel 必须为 0（未知信道不猜）", body.contains("channel = 0"))
        assertTrue("不能声明 Wi-Fi BSSID", body.contains("bssid = null"))
        assertTrue(
            "必须写清跳过 Android SoftApConfiguration 校验的依据",
            body.contains("docs/KX11_ROUTED_HOTSPOT.md"),
        )
    }
}
