package com.dbtool.carplay.ui.screens

import androidx.compose.ui.unit.dp
import com.dbtool.carplay.ui.navigation.CarSettingsSection
import com.dbtool.carplay.ui.theme.CarDimensions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置页双栏布局契约（2026-10-20）。
 *
 * 钉三件事：
 * 1. 断点按**可用内容宽**判定（900dp），不是窗口物理宽；
 * 2. 左栏分组覆盖全部 10 个分区，无重复、无遗漏；
 * 3. 窄屏索引页保留改造前的行序（回退路径行为不变）。
 */
class SettingsTwoPaneTest {
    @Test fun applicationSettingsTitleAndRailScaleMatchRequest() {
        assertEquals("设置", CarSettingsSection.IDENTITY.title)
        assertEquals("车机", CarSettingsSection.VEHICLE.title)
        assertEquals("投屏仪表", CarSettingsSection.CLUSTER.title)
        assertEquals(1.1f, SettingsLayoutPolicy.NAV_RAIL_SCALE, 0f)
    }

    @Test
    fun `可用内容宽取内容上限与窗口宽的较小值再减页边距`() {
        // 1920×1080 @160dpi，scale=1.25：内容上限 1120×1.25=1400，页边距 24×1.25=30。
        assertEquals(
            1340f,
            SettingsLayoutPolicy.availableContentWidth(1920.dp, 1400.dp, 30.dp).value,
            0.01f,
        )
        // 1024×600，scale=0.9：内容上限 1008，页边距 21.6。
        assertEquals(
            964.8f,
            SettingsLayoutPolicy.availableContentWidth(1024.dp, 1008.dp, 21.6.dp).value,
            0.01f,
        )
        // 800×480：窗口比内容上限窄，按窗口算 —— 756.8dp，应回退单栏。
        assertEquals(
            756.8f,
            SettingsLayoutPolicy.availableContentWidth(800.dp, 1008.dp, 21.6.dp).value,
            0.01f,
        )
    }

    @Test
    fun `900dp 是双栏断点`() {
        assertFalse(SettingsLayoutPolicy.isDualPane(899.9f.dp, 900.dp))
        assertTrue(SettingsLayoutPolicy.isDualPane(900.dp, 900.dp))
        assertTrue(SettingsLayoutPolicy.isDualPane(1000.dp, 900.dp))
        assertTrue(SettingsLayoutPolicy.isDualPane(1340.dp, 900.dp))
    }

    @Test
    fun `左栏分组覆盖全部十个分区且无重复`() {
        val sections = SETTINGS_NAV_GROUPS.flatMap { it.sections }
        assertEquals(CarSettingsSection.entries.toSet(), sections.toSet())
        assertEquals(CarSettingsSection.entries.size, sections.size)
    }

    @Test
    fun `左栏分组与定稿一致`() {
        assertEquals(3, SETTINGS_NAV_GROUPS.size)
        assertEquals(
            listOf(
                listOf(
                    CarSettingsSection.CONNECTION,
                    CarSettingsSection.DISPLAY,
                    CarSettingsSection.VEHICLE,
                ),
                listOf(
                    CarSettingsSection.CLUSTER,
                    CarSettingsSection.IMMERSIVE_NAVIGATION,
                    CarSettingsSection.HUD,
                ),
                listOf(
                    CarSettingsSection.IDENTITY,
                    CarSettingsSection.DIAGNOSTICS,
                ),
            ),
            SETTINGS_NAV_GROUPS.map { it.sections },
        )
    }

    @Test
    fun `窄屏索引保持改造前的行序`() {
        assertEquals(
            listOf(
                CarSettingsSection.CONNECTION,
                CarSettingsSection.DISPLAY,
                CarSettingsSection.CLUSTER,
                CarSettingsSection.IMMERSIVE_NAVIGATION,
                CarSettingsSection.HUD,
                CarSettingsSection.VEHICLE,
                CarSettingsSection.IDENTITY,
                CarSettingsSection.DIAGNOSTICS,
            ),
            SETTINGS_INDEX_ORDER,
        )
    }

    @Test
    fun `窄屏索引与左栏覆盖同一个分区集合`() {
        // 可达性不变量：两条路径都必须覆盖全部枚举值，且没有重复行。
        // 若以后新增分区只改一边，这里会失败（上一条只钉行序，不钉覆盖）。
        assertEquals(CarSettingsSection.entries.toSet(), SETTINGS_INDEX_ORDER.toSet())
        assertEquals(CarSettingsSection.entries.size, SETTINGS_INDEX_ORDER.size)
        assertEquals(
            SETTINGS_NAV_GROUPS.flatMap { it.sections }.toSet(),
            SETTINGS_INDEX_ORDER.toSet(),
        )
    }

    @Test
    fun `真实 token 在 1920 与 1024 上进入双栏 800 回退`() {
        // 断点判定必须接真实 CarDimensions × scale，而不是测试里另写一套数字。
        // 1920×1080@160dpi：短边 1080 → scale 夹到 1.25。
        // 期望值按「基准值 × scale」表达，别写成裸数字：2026-10-20 条目从 62dp 收到 58dp
        // （基准 46.4dp）时，写死 62 的断言直接挂了 —— 断言要说明换算关系，不是记住结果。
        val hd = CarDimensions.of(CarDimensions.scaleFor(1080f))
        val scale = hd.scale
        assertEquals(256f * scale, hd.settingsRailWidth.value, 0.01f)        // 基准 256dp → 320dp
        assertEquals(46.4f * scale, hd.settingsRailItemHeight.value, 0.01f)  // 基准 46.4dp → 58dp
        assertEquals(19.2f * scale, hd.settingsRailIconSize.value, 0.01f)    // 基准 19.2dp → 24dp
        assertEquals(9.6f * scale, hd.settingsRailItemRadius.value, 0.01f)   // 基准 9.6dp → 12dp
        val hdAvailable = SettingsLayoutPolicy.availableContentWidth(
            screenWidth = 1920.dp,
            contentMaxWidth = hd.contentMaxWidth,
            screenPaddingH = hd.screenPaddingH,
        )
        assertEquals(1340f, hdAvailable.value, 0.01f)
        assertTrue(SettingsLayoutPolicy.isDualPane(hdAvailable, hd.settingsDualPaneMinWidth))

        // 1024×600：短边 600 → 0.833 夹到 0.9，可用 964.8dp，仍双栏。
        val mid = CarDimensions.of(CarDimensions.scaleFor(600f))
        assertEquals(230.4f, mid.settingsRailWidth.value, 0.01f)
        val midAvailable = SettingsLayoutPolicy.availableContentWidth(
            screenWidth = 1024.dp,
            contentMaxWidth = mid.contentMaxWidth,
            screenPaddingH = mid.screenPaddingH,
        )
        assertEquals(964.8f, midAvailable.value, 0.01f)
        assertTrue(SettingsLayoutPolicy.isDualPane(midAvailable, mid.settingsDualPaneMinWidth))

        // 800×480：可用 756.8dp，回退单栏索引。
        val low = CarDimensions.of(CarDimensions.scaleFor(480f))
        val lowAvailable = SettingsLayoutPolicy.availableContentWidth(
            screenWidth = 800.dp,
            contentMaxWidth = low.contentMaxWidth,
            screenPaddingH = low.screenPaddingH,
        )
        assertEquals(756.8f, lowAvailable.value, 0.01f)
        assertFalse(SettingsLayoutPolicy.isDualPane(lowAvailable, low.settingsDualPaneMinWidth))
    }
}
