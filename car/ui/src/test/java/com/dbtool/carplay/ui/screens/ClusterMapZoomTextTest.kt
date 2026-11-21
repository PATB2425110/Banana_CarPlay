package com.dbtool.carplay.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 仪表地图缩放入口的文案与可用性（设置页「投屏状态」分组）。
 *
 * 这里钉的是用户能看到的每一句话：按钮在投屏总闸关闭时必须置灰，
 * 说明文案要能让人知道"有这个能力 / 还差哪一步"。
 */
class ClusterMapZoomTextTest {

    @Test
    fun `投屏关闭时按钮不可用`() {
        assertFalse(clusterMapZoomEnabled(false))
    }

    @Test
    fun `投屏开启时按钮可用`() {
        assertTrue(clusterMapZoomEnabled(true))
    }

    @Test
    fun `投屏关闭时说明提示先开启`() {
        assertEquals("需先开启仪表投屏", clusterMapZoomHint(false))
    }

    @Test
    fun `投屏开启时说明指向页面内的缩放按钮`() {
        assertEquals(
            "使用下方放大 / 缩小按钮调整仪表地图",
            clusterMapZoomHint(true),
        )
    }

    @Test
    fun `步骤文案区分放大与缩小`() {
        assertEquals("已放大一步", clusterMapZoomStepText(true))
        assertEquals("已缩小一步", clusterMapZoomStepText(false))
    }

    @Test
    fun `所有用户文案不出现英文`() {
        val english = Regex("[A-Za-z]")
        listOf(
            clusterMapZoomHint(true),
            clusterMapZoomHint(false),
            clusterMapZoomStepText(true),
            clusterMapZoomStepText(false),
        ).forEach { text ->
            assertFalse("用户文案含英文：$text", english.containsMatchIn(text))
        }
    }
}
