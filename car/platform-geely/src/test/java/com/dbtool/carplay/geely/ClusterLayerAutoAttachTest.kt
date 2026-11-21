package com.dbtool.carplay.geely

import com.dbtool.carplay.api.display.ClusterLayerMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「图层模式 → 是否主动建窗」的判据（2026-10-20）。
 *
 * 现场（1.1.32）：`THEME_ONLY` 已应用、窗口 `attached=false`，但一直等到用户
 * 手动选副屏才建窗。这里把四条判据钉死，避免以后为了别的原因放宽建窗条件。
 */
class ClusterLayerAutoAttachTest {

    @Test
    fun `仅主题模式未挂载时主动建窗`() {
        assertTrue(
            ClusterLayerAutoAttach.shouldAttach(
                mode = ClusterLayerMode.THEME_ONLY,
                attached = false,
                displayId = 3,
                sessionActive = true,
            ),
        )
    }

    @Test
    fun `双层模式未挂载时主动建窗`() {
        assertTrue(
            ClusterLayerAutoAttach.shouldAttach(
                mode = ClusterLayerMode.BOTH,
                attached = false,
                displayId = 1,
                sessionActive = true,
            ),
        )
    }

    @Test
    fun `仅CarPlay模式不建窗`() {
        assertFalse(
            ClusterLayerAutoAttach.shouldAttach(
                mode = ClusterLayerMode.CARPLAY_ONLY,
                attached = false,
                displayId = 3,
                sessionActive = true,
            ),
        )
    }

    @Test
    fun `没有副屏候选时不建窗`() {
        assertFalse(
            ClusterLayerAutoAttach.shouldAttach(
                mode = ClusterLayerMode.THEME_ONLY,
                attached = false,
                displayId = -1,
                sessionActive = true,
            ),
        )
    }

    @Test
    fun `会话不可用时不建窗`() {
        assertFalse(
            ClusterLayerAutoAttach.shouldAttach(
                mode = ClusterLayerMode.THEME_ONLY,
                attached = false,
                displayId = 3,
                sessionActive = false,
            ),
        )
    }

    @Test
    fun `窗口已挂载时不重复建窗`() {
        assertFalse(
            ClusterLayerAutoAttach.shouldAttach(
                mode = ClusterLayerMode.BOTH,
                attached = true,
                displayId = 3,
                sessionActive = true,
            ),
        )
    }

    @Test
    fun `只有主题与双层模式要求主题层`() {
        assertTrue(ClusterLayerAutoAttach.requiresTheme(ClusterLayerMode.THEME_ONLY))
        assertTrue(ClusterLayerAutoAttach.requiresTheme(ClusterLayerMode.BOTH))
        assertFalse(ClusterLayerAutoAttach.requiresTheme(ClusterLayerMode.CARPLAY_ONLY))
    }
}
