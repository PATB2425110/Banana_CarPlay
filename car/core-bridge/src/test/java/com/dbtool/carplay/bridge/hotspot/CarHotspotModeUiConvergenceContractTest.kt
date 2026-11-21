package com.dbtool.carplay.bridge.hotspot

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 契约测试：设置页热点模式选择器**移除 `MANUAL`、由 `TCAM_HOTSPOT` 取代**
 * （2026-10-16，车主定案）。
 *
 * ## 为什么静态读源码
 *
 * 选择器选项表在 Compose UI（`SettingsScreen`）里，`:car:core-bridge` 的 JVM 单测
 * 跑不了 Compose；但"选项表里不含 MANUAL"是本次 UI 收敛的**唯一硬判据**，
 * 与 [CarHotspotModeInitContractTest] / [HotspotReuseContractTest] 同用"静态读源码"钉死。
 */
class CarHotspotModeUiConvergenceContractTest {

    private val settingsScreen = sourceOf(
        "car/ui/src/main/java/com/dbtool/carplay/ui/screens/SettingsScreen.kt",
    )

    @Test
    fun `设置页热点模式选项不含 MANUAL`() {
        assertTrue(
            "找不到热点模式选择器的 filterNot 行",
            settingsScreen.contains("options = CarHotspotMode.entries.filterNot"),
        )
        val filter = settingsScreen
            .substringAfter("options = CarHotspotMode.entries.filterNot", "")
            .substringBefore("selected = draft.hotspotMode")

        assertTrue(
            "filterNot 谓词必须同时排除 AUTO 与 MANUAL（界面只剩自建/局域网/TCAM 三档）",
            filter.contains("CarHotspotMode.AUTO") && filter.contains("CarHotspotMode.MANUAL"),
        )
        assertFalse(
            "TCAM_HOTSPOT 不能被 filterNot 排除（它取代 MANUAL 成为唯一的复用档）",
            filter.contains("it == CarHotspotMode.TCAM_HOTSPOT"),
        )
        assertFalse(
            "ADB_HOSTAPD 不能被 filterNot 排除",
            filter.contains("it == CarHotspotMode.ADB_HOSTAPD"),
        )
        assertFalse(
            "SAME_LAN 不能被 filterNot 排除",
            filter.contains("it == CarHotspotMode.SAME_LAN"),
        )
    }

    private fun sourceOf(relative: String): String {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile
        }
        throw AssertionError("找不到源码：$relative")
    }
}
