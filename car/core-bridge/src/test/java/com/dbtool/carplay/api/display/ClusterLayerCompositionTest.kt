package com.dbtool.carplay.api.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClusterLayerCompositionTest {

    @Test
    fun `双层模式同时显示主题与视频并预留中区`() {
        val layout = ClusterLayerComposition.layoutFor(ClusterLayerMode.BOTH, 1920, 720)

        assertTrue(layout.themeVisible)
        assertTrue(layout.videoVisible)
        assertTrue(layout.themeCenterReserved)
    }

    @Test
    fun `双层模式按基准面板精确内缩`() {
        val inset = ClusterLayerComposition.layoutFor(ClusterLayerMode.BOTH, 1920, 720).videoInset

        assertEquals(VideoInset(0, 0, 0, 0), inset)  // 2026-10-22: 视频层改全屏，不再内缩
    }

    @Test
    fun `双层模式按比例缩放到一半面板`() {
        val inset = ClusterLayerComposition.layoutFor(ClusterLayerMode.BOTH, 960, 360).videoInset

        assertEquals(VideoInset(0, 0, 0, 0), inset)  // 同上
    }

    @Test
    fun `仅 CarPlay 隐藏主题且视频铺满`() {
        val layout = ClusterLayerComposition.layoutFor(
            ClusterLayerMode.CARPLAY_ONLY,
            1024,
            274,
        )

        assertFalse(layout.themeVisible)
        assertTrue(layout.videoVisible)
        assertFalse(layout.themeCenterReserved)
        assertEquals(VideoInset(0, 0, 0, 0), layout.videoInset)
    }

    @Test
    fun `仅主题隐藏视频视图但保留主题`() {
        val layout = ClusterLayerComposition.layoutFor(ClusterLayerMode.THEME_ONLY, 1024, 274)

        assertTrue(layout.themeVisible)
        assertFalse(layout.videoVisible)
        assertFalse(layout.themeCenterReserved)
        assertEquals(VideoInset(0, 0, 0, 0), layout.videoInset)
    }

    @Test
    fun `极细长面板的双层中区仍至少有一像素`() {
        val layout = ClusterLayerComposition.layoutFor(ClusterLayerMode.BOTH, 1, 1)
        val inset = layout.videoInset

        assertTrue(1 - inset.left - inset.right >= 1)
        assertTrue(1 - inset.top - inset.bottom >= 1)
    }

    @Test
    fun `零尺寸与负尺寸不会崩溃`() {
        for ((width, height) in listOf(0 to 0, -1 to 5, 5 to -1)) {
            val layout = ClusterLayerComposition.layoutFor(
                ClusterLayerMode.BOTH,
                width,
                height,
            )
            val inset = layout.videoInset
            assertTrue(inset.left >= 0 && inset.top >= 0)
            assertTrue(inset.right >= 0 && inset.bottom >= 0)
            assertTrue(maxOf(1, width) - inset.left - inset.right >= 1)
            assertTrue(maxOf(1, height) - inset.top - inset.bottom >= 1)
        }
    }

    @Test
    fun `超宽面板按比例换算上下与左右内缩`() {
        val inset = ClusterLayerComposition.layoutFor(ClusterLayerMode.BOTH, 2560, 1080).videoInset

        assertEquals(VideoInset(0, 0, 0, 0), inset)  // 同上
    }

    @Test
    fun `奇数面板尺寸不产生越界内缩`() {
        val inset = ClusterLayerComposition.layoutFor(ClusterLayerMode.BOTH, 1921, 721).videoInset

        assertTrue(1921 - inset.left - inset.right >= 1)
        assertTrue(721 - inset.top - inset.bottom >= 1)
    }

    // ---------------------------------------------------------- 2026-10-22 解耦（P0）
    //
    // 三态枚举只是兼容入口；主题与导航的可见性必须能分别表达。
    // 下面四条钉住“单关任一层，另一层与窗口状态不受影响”。

    @Test
    fun `三态模式映射到两个独立图层与解耦前逐项一致`() {
        val both = ClusterLayerComposition.layoutFor(ClusterLayerMode.BOTH, 1920, 720)
        val carPlay = ClusterLayerComposition.layoutFor(ClusterLayerMode.CARPLAY_ONLY, 1920, 720)
        val theme = ClusterLayerComposition.layoutFor(ClusterLayerMode.THEME_ONLY, 1920, 720)

        assertEquals(Triple(true, true, true), Triple(both.themeVisible, both.videoVisible, both.windowVisible))
        assertEquals(Triple(false, true, true), Triple(carPlay.themeVisible, carPlay.videoVisible, carPlay.windowVisible))
        assertEquals(Triple(true, false, true), Triple(theme.themeVisible, theme.videoVisible, theme.windowVisible))
    }

    @Test
    fun `主题关导航开时导航与窗口仍然需要显示`() {
        // 主题层关闭（CARPLAY_ONLY），导航层照常。
        val layout = ClusterLayerComposition.layoutFor(
            themeVisible = false,
            navigationVisible = true,
            panelWidth = 1920,
            panelHeight = 720,
        )

        assertFalse(layout.themeVisible)
        assertTrue(layout.videoVisible)
        assertTrue("导航还在，窗口不能隐藏", layout.windowVisible)
    }

    @Test
    fun `导航关主题开时主题与窗口仍然需要显示`() {
        // 导航层单独关闭（断流/用户关导航），主题层必须原样保留。
        val layout = ClusterLayerComposition.layoutFor(
            themeVisible = true,
            navigationVisible = false,
            panelWidth = 1920,
            panelHeight = 720,
        )

        assertTrue("导航断流不得影响主题", layout.themeVisible)
        assertFalse(layout.videoVisible)
        assertTrue("主题还在，窗口不能隐藏", layout.windowVisible)
    }

    @Test
    fun `两个图层都关时窗口才不需要显示`() {
        val layout = ClusterLayerComposition.layoutFor(
            themeVisible = false,
            navigationVisible = false,
            panelWidth = 1920,
            panelHeight = 720,
        )

        assertFalse(layout.themeVisible)
        assertFalse(layout.videoVisible)
        assertFalse(layout.windowVisible)
    }

    @Test
    fun `所有模式与常见面板都产生非负几何`() {
        val panels = listOf(
            1920 to 720,
            1024 to 274,
            800 to 480,
            3 to 2,
            0 to 1,
        )
        for (mode in ClusterLayerMode.entries) {
            for ((width, height) in panels) {
                val inset = ClusterLayerComposition.layoutFor(mode, width, height).videoInset
                val safeWidth = maxOf(1, width)
                val safeHeight = maxOf(1, height)
                assertTrue(inset.left >= 0 && inset.top >= 0)
                assertTrue(inset.right >= 0 && inset.bottom >= 0)
                assertTrue(safeWidth - inset.left - inset.right >= 1)
                assertTrue(safeHeight - inset.top - inset.bottom >= 1)
            }
        }
    }
}
