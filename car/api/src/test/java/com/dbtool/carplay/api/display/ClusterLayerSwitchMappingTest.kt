package com.dbtool.carplay.api.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 两个独立图层开关 ↔ 现有持久化模型的双向映射（2026-10-23）。
 *
 * 这组用例钉住四件事：
 * 1. 四象限写入一定落到任务书规定的 mode + projectionEnabled；
 * 2. 老配置（三种 mode × 投屏开关）读取后与 P0 的运行时结论一致；
 * 3. 四象限写入后再读回来不变（UI 不会自己漂移）；
 * 4. `THEME_ONLY + projectionEnabled=true` 这类历史脏组合读取为“导航收起”，
 *    并在第一次写入时归一化，避免拨主题开关时把导航意外打开。
 */
class ClusterLayerSwitchMappingTest {

    private fun assertTarget(
        theme: Boolean,
        navigation: Boolean,
        mode: ClusterLayerMode,
        projection: Boolean,
    ) {
        val target = ClusterLayerSwitchMapping.write(theme, navigation)
        assertEquals("mode（主题=$theme 导航=$navigation）", mode, target.mode)
        assertEquals(
            "projectionEnabled（主题=$theme 导航=$navigation）",
            projection,
            target.projectionEnabled,
        )
    }

    @Test
    fun `四象限写入落到任务书规定的组合`() {
        assertTarget(false, false, ClusterLayerMode.CARPLAY_ONLY, false)
        assertTarget(false, true, ClusterLayerMode.CARPLAY_ONLY, true)
        assertTarget(true, false, ClusterLayerMode.THEME_ONLY, false)
        assertTarget(true, true, ClusterLayerMode.BOTH, true)
    }

    @Test
    fun `老配置读取与 P0 运行时一致`() {
        // 双层 + 投屏开 = 主题与导航都显示。
        assertEquals(
            ClusterLayerSwitches(themeVisible = true, navigationVisible = true),
            ClusterLayerSwitchMapping.read(ClusterLayerMode.BOTH, projectionEnabled = true),
        )
        // 双层 + 投屏关 = P0 的“主题保留、导航收起”。
        assertEquals(
            ClusterLayerSwitches(themeVisible = true, navigationVisible = false),
            ClusterLayerSwitchMapping.read(ClusterLayerMode.BOTH, projectionEnabled = false),
        )
        // 仅导航。
        assertEquals(
            ClusterLayerSwitches(themeVisible = false, navigationVisible = true),
            ClusterLayerSwitchMapping.read(ClusterLayerMode.CARPLAY_ONLY, projectionEnabled = true),
        )
        assertEquals(
            ClusterLayerSwitches(themeVisible = false, navigationVisible = false),
            ClusterLayerSwitchMapping.read(ClusterLayerMode.CARPLAY_ONLY, projectionEnabled = false),
        )
        // 仅主题：模式本身就不允许导航，投屏开关无论开关都读成导航收起。
        for (projection in listOf(true, false)) {
            assertEquals(
                "THEME_ONLY + projectionEnabled=$projection",
                ClusterLayerSwitches(themeVisible = true, navigationVisible = false),
                ClusterLayerSwitchMapping.read(ClusterLayerMode.THEME_ONLY, projection),
            )
        }
    }

    @Test
    fun `四象限写入后再读回保持不变`() {
        for (theme in listOf(true, false)) {
            for (navigation in listOf(true, false)) {
                val target = ClusterLayerSwitchMapping.write(theme, navigation)
                val readBack = ClusterLayerSwitchMapping.read(target.mode, target.projectionEnabled)
                assertEquals("主题开关回读（$theme/$navigation）", theme, readBack.themeVisible)
                assertEquals(
                    "导航开关回读（$theme/$navigation）",
                    navigation,
                    readBack.navigationVisible,
                )
            }
        }
    }

    @Test
    fun `历史脏组合在写入时归一化`() {
        // 老版本允许 THEME_ONLY + projectionEnabled=true（模式不允许导航但总开关开着）。
        val legacy = ClusterLayerSwitchMapping.read(
            ClusterLayerMode.THEME_ONLY,
            projectionEnabled = true,
        )
        assertTrue("主题仍在", legacy.themeVisible)
        assertFalse("导航不应因总开关开着就冒出来", legacy.navigationVisible)

        val normalized = ClusterLayerSwitchMapping.write(
            legacy.themeVisible,
            legacy.navigationVisible,
        )
        assertEquals(ClusterLayerMode.THEME_ONLY, normalized.mode)
        assertFalse("归一化后投屏总开关跟着收起", normalized.projectionEnabled)
    }

    @Test
    fun `缺省配置读取为两层都关`() {
        val switches = ClusterLayerSwitchMapping.read(
            ClusterLayerMode.DEFAULT,
            projectionEnabled = false,
        )
        assertFalse(switches.themeVisible)
        assertFalse(switches.navigationVisible)
    }
}
