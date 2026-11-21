package com.dbtool.carplay.ui.screens

import com.dbtool.carplay.ui.CarClusterLayerStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图层状态文案与 B4 误报判据（2026-10-23）。
 *
 * 重点不是文案措辞，而是**两个图层能独立表达**：
 * 主题开着、导航收起时，界面不能再说「没有画面」。
 */
class ClusterLayerStatusTextTest {

    @Test
    fun `主题开导航收起时窗口保留但不报黑屏`() {
        val status = CarClusterLayerStatus(
            themeRequested = true,
            themeAvailable = true,
            themeVisible = true,
            navigationRequested = false,
            navigationVisible = false,
            windowAttached = true,
            firstFrameShown = false,
        )
        assertEquals("显示中", clusterThemeLayerText(status))
        assertEquals("已收起", clusterNavigationLayerText(status))
        assertEquals("已挂", clusterWindowText(status))
        assertEquals("仅仪表主题", clusterLayerCombinationText(status))
        assertFalse(
            "导航层收起时窗口还在是为主题保留的，不能报「窗上挂着，但没有画面」（B4）",
            clusterNavigationWarningVisible(
                status = status,
                windowAttached = true,
                firstFrameShown = false,
            ),
        )
    }

    @Test
    fun `导航要求显示但没首帧时才报黑屏`() {
        val status = CarClusterLayerStatus(
            themeRequested = false,
            themeVisible = false,
            navigationRequested = true,
            navigationVisible = true,
            windowAttached = true,
            firstFrameShown = false,
        )
        assertEquals("无画面（等待首帧）", clusterNavigationLayerText(status))
        assertTrue(
            "导航层要求显示、窗口已挂、首帧没到 —— 这才是真正的黑屏提示",
            clusterNavigationWarningVisible(
                status = status,
                windowAttached = true,
                firstFrameShown = false,
            ),
        )
    }

    @Test
    fun `导航层运行时被自动收起后不再报黑屏`() {
        val status = CarClusterLayerStatus(
            themeRequested = true,
            themeAvailable = true,
            themeVisible = true,
            navigationRequested = true,
            navigationVisible = false,
            windowAttached = true,
            firstFrameShown = false,
        )
        assertEquals("已收起", clusterNavigationLayerText(status))
        assertFalse(
            "导航层运行时已经收起（例如导航结束），不应再报黑屏",
            clusterNavigationWarningVisible(status, windowAttached = true, firstFrameShown = false),
        )
    }

    @Test
    fun `四象限组合文案`() {
        fun status(theme: Boolean, navigation: Boolean) = CarClusterLayerStatus(
            themeRequested = theme,
            themeAvailable = theme,
            themeVisible = theme,
            navigationRequested = navigation,
            navigationVisible = navigation,
            windowAttached = theme || navigation,
            firstFrameShown = navigation,
        )
        assertEquals("双层显示（主题 + 导航）", clusterLayerCombinationText(status(true, true)))
        assertEquals("仅仪表主题", clusterLayerCombinationText(status(true, false)))
        assertEquals("仅导航画面（CarPlay）", clusterLayerCombinationText(status(false, true)))
        assertEquals("两层都关闭", clusterLayerCombinationText(status(false, false)))
    }

    @Test
    fun `主题解析失败只影响主题自身的状态`() {
        val status = CarClusterLayerStatus(
            themeRequested = true,
            themeAvailable = false,
            themeVisible = false,
            navigationRequested = true,
            navigationVisible = true,
            windowAttached = true,
            firstFrameShown = true,
        )
        assertEquals("未显示（主题不可用）", clusterThemeLayerText(status))
        assertEquals("显示中", clusterNavigationLayerText(status))
    }

    @Test
    fun `主题可用但窗口未挂时不说显示中`() {
        val status = CarClusterLayerStatus(
            themeRequested = true,
            themeAvailable = true,
            themeVisible = false,
            windowAttached = false,
        )
        assertEquals("未显示（窗口未挂）", clusterThemeLayerText(status))
        assertEquals("未挂", clusterWindowText(status))
    }
}
