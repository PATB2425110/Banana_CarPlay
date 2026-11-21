package com.dbtool.carplay.api.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自绘区域与视频画面变换的纯函数契约（2026-10-22）。
 *
 * 这两个参数都会落 SharedPreferences，用户也可能写出越界值；
 * 这里钉住「默认值 = 改造前行为」与「越界必被夹回画布内」两条底线。
 */
class ClusterDrawRegionTest {

    private fun assertFloat(expected: Float, actual: Float) =
        assertEquals(expected.toDouble(), actual.toDouble(), 1e-6)

    @Test
    fun `default region is full canvas and identity`() {
        val region = ClusterDrawRegion.DEFAULT
        assertTrue(region.isDefault)
        assertFloat(0f, region.x)
        assertFloat(0f, region.y)
        assertFloat(1920f, region.width)
        assertFloat(720f, region.height)
        assertFloat(1f, region.contentScale)
        assertFloat(0f, region.contentLeft)
        assertFloat(0f, region.contentTop)
    }

    @Test
    fun `sanitize clamps into canvas`() {
        val safe = ClusterDrawRegion.sanitize(
            ClusterDrawRegion(x = -100f, y = -50f, width = 5000f, height = 5000f),
        )
        assertFloat(0f, safe.x)
        assertFloat(0f, safe.y)
        assertFloat(ClusterDrawRegion.BASE_WIDTH, safe.width)
        assertFloat(ClusterDrawRegion.BASE_HEIGHT, safe.height)
        assertFloat(ClusterDrawRegion.BASE_WIDTH, safe.right)
        assertFloat(ClusterDrawRegion.BASE_HEIGHT, safe.bottom)
    }

    @Test
    fun `sanitize keeps minimum size`() {
        val safe = ClusterDrawRegion.sanitize(
            ClusterDrawRegion(x = 100f, y = 100f, width = 0f, height = 0f),
        )
        assertFloat(ClusterDrawRegion.MIN_WIDTH, safe.width)
        assertFloat(ClusterDrawRegion.MIN_HEIGHT, safe.height)
    }

    @Test
    fun `sanitize pushes region back inside right and bottom edges`() {
        val safe = ClusterDrawRegion.sanitize(
            ClusterDrawRegion(x = 1800f, y = 700f, width = 480f, height = 180f),
        )
        assertFloat(1920f - 480f, safe.x)
        assertFloat(720f - 180f, safe.y)
        assertFloat(1920f, safe.right)
        assertFloat(720f, safe.bottom)
    }

    @Test
    fun `content scale preserves aspect and centers inside region`() {
        // 与画布同比例（1920:720 = 8:3）的区域 ⇒ 内容顶格。
        val wide = ClusterDrawRegion(x = 0f, y = 0f, width = 960f, height = 360f)
        assertFloat(0.5f, wide.contentScale)
        assertFloat(0f, wide.contentLeft)
        assertFloat(0f, wide.contentTop)
        assertFalse(wide.isDefault)

        // 4:3 区域：宽度是瓶颈 ⇒ 内容左右顶格、上下居中留透明边。
        val tall = ClusterDrawRegion(x = 0f, y = 0f, width = 960f, height = 720f)
        assertFloat(0.5f, tall.contentScale)
        assertFloat(0f, tall.contentLeft)
        assertFloat(180f, tall.contentTop)
    }

    @Test
    fun `video transform default is identity`() {
        val transform = ClusterVideoTransform.DEFAULT
        assertTrue(transform.isDefault)
        assertFloat(1f, transform.scale)
        assertFloat(0f, transform.dx)
        assertFloat(0f, transform.dy)
    }

    @Test
    fun `video transform sanitize clamps scale and offsets`() {
        val tooSmall = ClusterVideoTransform.sanitize(
            ClusterVideoTransform(scale = 0.1f, dx = -5000f, dy = 5000f),
        )
        assertFloat(ClusterVideoTransform.MIN_SCALE, tooSmall.scale)
        assertFloat(-ClusterVideoTransform.MAX_OFFSET_X, tooSmall.dx)
        assertFloat(ClusterVideoTransform.MAX_OFFSET_Y, tooSmall.dy)

        val tooBig = ClusterVideoTransform.sanitize(ClusterVideoTransform(scale = 9f))
        assertFloat(ClusterVideoTransform.MAX_SCALE, tooBig.scale)
    }
}
