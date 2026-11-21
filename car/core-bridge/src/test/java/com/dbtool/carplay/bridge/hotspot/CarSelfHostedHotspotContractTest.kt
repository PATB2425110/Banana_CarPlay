package com.dbtool.carplay.bridge.hotspot

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PATCH-0116 的契约测试：「**这次是给我们自己的 AP 做就绪判定**」这个事实
 * 必须从二开一路传到上游 `ManualHotspotManager`（2026-10-13）。
 *
 * ## 为什么这条必须钉住（日志 9，`1791897995606`）
 *
 * 设置页的 SSID 由**复用与自建共用**。用户先用「Lynk」复用车机热点，再切回
 * 「自建热点」时名字还是「Lynk」⇒ 自建那张 5 GHz AP 与车机系统里那份同名的
 * 2.4 GHz 配置**撞上** ⇒ 上游按"名字一致 = 真·复用"拿车机的配置校验我们自己的网 ⇒
 *
 * ```
 * Manual hotspot band 2.4 GHz does not match configured band 5 GHz   （21:24:22 / 26 / 30）
 * ```
 *
 * 刚起好的 hostapd 被就地拆掉。同一份日志里把名字换回 `DBPlay`（名字对不上 ⇒
 * 校验被跳过）同一条链立刻成功（21:25:28）。
 *
 * ## 钉死四件事
 *
 * 1. 上游 `ManualHotspotManager` 必须**有**这个开关，且自建时**根本不读**
 *    系统 `SoftApConfiguration`（不是"读了不校验"——信道/频段也不该借它的）；
 * 2. `CarPlayRuntimeConfig` 必须把它带在身上（默认 `false` = 保持复用语义）；
 * 3. `CarPlayController` 必须把它透传给 manager；
 * 4. `CarPlayConfigBuilder` 必须**按二开模式**推导它（复用 vs 自建在本层才分得清 ——
 *    两者在上游都映射成 `WirelessHotspotMode.MANUAL`）。
 *
 * 另外钉一条观测纪律：[CarHotspotClientWatcher] 的"没有客户端"结论
 * **必须**以"读数可用"为前提（读不到 ≠ 没有）。
 *
 * ⚠️ 读源码**先剥注释**：这些字面量在 KDoc 里必然出现（讲历史），不剥会假通过。
 */
class CarSelfHostedHotspotContractTest {

    private fun sourceOf(relative: String): String {
        val candidates = listOf(
            File(relative),
            File("../$relative"),
            File("../../$relative"),
            File("../../../$relative"),
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("找不到源码文件 $relative（从 ${File(".").absolutePath} 起找过 ${candidates.size} 个位置）")
        return file.readText()
    }

    private fun codeOf(relative: String): String =
        sourceOf(relative)
            .replace(Regex("(?s)/\\*.*?\\*/"), " ")
            .replace(Regex("(?m)//.*$"), " ")

    private val manager = codeOf(
        "../../shared/src/main/java/com/shilapi/xcertplay/network/ManualHotspotManager.kt",
    )
    private val runtimeConfig = codeOf(
        "../../shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayRuntimeConfig.kt",
    )
    private val controller = codeOf(
        "../../shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt",
    )
    private val builder = codeOf(
        "src/main/java/com/dbtool/carplay/bridge/CarPlayConfigBuilder.kt",
    )
    private val bridge = codeOf(
        "src/main/java/com/dbtool/carplay/bridge/CarPlaySessionBridge.kt",
    )
    private val watcher = codeOf(
        "src/main/java/com/dbtool/carplay/bridge/hotspot/CarHotspotClientWatcher.kt",
    )

    @Test
    fun `上游自建时根本不读系统 SoftApConfiguration`() {
        assertTrue(
            "ManualHotspotManager 必须新增 selfHostedAccessPoint 开关（二开自建时置 true）",
            manager.contains("selfHostedAccessPoint: Boolean = false"),
        )
        assertTrue(
            "读取系统配置必须被这个开关挡住：`if (selfHostedAccessPoint) { null } else { readApConfiguration()… }`。" +
                "只在 validate 里放宽是不够的 —— 信道/频段/加密也不该从**别人那张** AP 上借。",
            manager.contains("if (selfHostedAccessPoint)"),
        )
        assertTrue(
            "被挡住的那一支必须返回 null（而不是「读了但跳过校验」）",
            manager.contains("readApConfiguration()?.takeIf"),
        )
    }

    @Test
    fun `运行时配置带着这个开关且默认 false`() {
        assertTrue(
            "CarPlayRuntimeConfig 必须有 manualHotspotSelfHosted 字段",
            runtimeConfig.contains("val manualHotspotSelfHosted: Boolean = false"),
        )
    }

    @Test
    fun `控制器把开关透传给管理器`() {
        assertTrue(
            "CarPlayController 构造 ManualHotspotManager 时必须传 selfHostedAccessPoint",
            controller.contains("selfHostedAccessPoint = config.manualHotspotSelfHosted"),
        )
    }

    @Test
    fun `二开按热点模式推导开关而不是按名字猜`() {
        assertTrue(
            "CarPlayConfigBuilder 必须按 hotspot.mode 推导：只有「复用车机热点」是接管别人的 AP",
            builder.contains("manualHotspotSelfHosted = hotspot.mode != CarHotspotMode.MANUAL"),
        )
        assertTrue(
            "AUTO 在解析阶段已落到自建 ⇒ 不能只判 MANUAL 之外的两种模式漏掉 AUTO",
            builder.contains("hotspot.mode != CarHotspotMode.SAME_LAN"),
        )
        assertFalse(
            "不许按 SSID 是否与车机一致来推导（那正是这次踩坑的判据）",
            builder.contains("manualHotspotSelfHosted = hotspot.ssid"),
        )
    }

    @Test
    fun `客户端观测只在复用分支启动`() {
        val guard = bridge.indexOf("if (effectivePlan.reused) {")
        val start = bridge.indexOf("CarHotspotClientWatcher.start(")
        assertTrue("bridge 里找不到 reused 分支", guard >= 0)
        assertTrue("复用分支必须启动客户端观测器", start >= 0)
        assertTrue(
            "观测器必须在 `if (effectivePlan.reused)` 之内启动：" +
                "自建那张网的客户端我们自己清楚，观测它只是噪声",
            start > guard,
        )
    }

    @Test
    fun `读数不可用时不许下没有客户端的结论`() {
        assertTrue(
            "观测器必须把 `routeStatus != UNAVAILABLE` 当成「读得动」的判据" +
                "（否则本机读不到 SDK 时会被误报成「没有客户端」）",
            watcher.contains("RouteStatus.UNAVAILABLE"),
        )
        assertTrue(
            "采样必须有上限（无界 = 一次失败的复用留下永不停止的线程）",
            watcher.contains("MAX_SAMPLES"),
        )
    }
}
