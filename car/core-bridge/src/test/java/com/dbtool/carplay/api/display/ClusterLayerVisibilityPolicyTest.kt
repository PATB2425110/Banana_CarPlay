package com.dbtool.carplay.api.display

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 仪表窗口去留判据（2026-10-22 解耦，P0）。
 *
 * 这些用例对应现场最容易回归的三条：
 * 1. 关导航时主题还在 ⇒ 窗口必须保留（不许 dismiss / 不许解绑 Surface）；
 * 2. 主题关时导航还在 ⇒ 窗口同样保留（原来的结构天然满足，锁住）；
 * 3. 两边都关 ⇒ 才允许拆窗，把仪表还给车机。
 */
class ClusterLayerVisibilityPolicyTest {

    @Test
    fun `双层模式下导航关闭时窗口必须留给主题`() {
        assertTrue(
            ClusterLayerVisibilityPolicy.shouldRetainWindowForTheme(
                mode = ClusterLayerMode.BOTH,
                themeAvailable = true,
                windowAttached = true,
            ),
        )
    }

    @Test
    fun `仅主题模式下导航关闭时窗口必须留给主题`() {
        assertTrue(
            ClusterLayerVisibilityPolicy.shouldRetainWindowForTheme(
                mode = ClusterLayerMode.THEME_ONLY,
                themeAvailable = true,
                windowAttached = true,
            ),
        )
    }

    @Test
    fun `仅CarPlay模式没有主题要留窗口`() {
        assertFalse(
            ClusterLayerVisibilityPolicy.shouldRetainWindowForTheme(
                mode = ClusterLayerMode.CARPLAY_ONLY,
                themeAvailable = true,
                windowAttached = true,
            ),
        )
    }

    @Test
    fun `主题解析失败时不为空窗占着仪表`() {
        assertFalse(
            ClusterLayerVisibilityPolicy.shouldRetainWindowForTheme(
                mode = ClusterLayerMode.BOTH,
                themeAvailable = false,
                windowAttached = true,
            ),
        )
    }

    @Test
    fun `窗口本来就没挂时无需保留`() {
        assertFalse(
            ClusterLayerVisibilityPolicy.shouldRetainWindowForTheme(
                mode = ClusterLayerMode.BOTH,
                themeAvailable = true,
                windowAttached = false,
            ),
        )
    }

    @Test
    fun `主题与导航都关时才拆窗`() {
        assertTrue(
            ClusterLayerVisibilityPolicy.shouldTearDownWindow(
                mode = ClusterLayerMode.CARPLAY_ONLY,
                navigationVisible = false,
                windowAttached = true,
            ),
        )
    }

    @Test
    fun `主题仍要求显示时不得拆窗`() {
        assertFalse(
            ClusterLayerVisibilityPolicy.shouldTearDownWindow(
                mode = ClusterLayerMode.THEME_ONLY,
                navigationVisible = false,
                windowAttached = true,
            ),
        )
        assertFalse(
            ClusterLayerVisibilityPolicy.shouldTearDownWindow(
                mode = ClusterLayerMode.BOTH,
                navigationVisible = false,
                windowAttached = true,
            ),
        )
    }

    @Test
    fun `导航层仍可见时不得拆窗`() {
        assertFalse(
            ClusterLayerVisibilityPolicy.shouldTearDownWindow(
                mode = ClusterLayerMode.CARPLAY_ONLY,
                navigationVisible = true,
                windowAttached = true,
            ),
        )
    }

    @Test
    fun `三态模式的两个独立维度映射正确`() {
        assertTrue(ClusterLayerMode.BOTH.showsTheme)
        assertTrue(ClusterLayerMode.BOTH.showsNavigation)
        assertTrue(ClusterLayerMode.THEME_ONLY.showsTheme)
        assertFalse(ClusterLayerMode.THEME_ONLY.showsNavigation)
        assertFalse(ClusterLayerMode.CARPLAY_ONLY.showsTheme)
        assertTrue(ClusterLayerMode.CARPLAY_ONLY.showsNavigation)
    }
}
