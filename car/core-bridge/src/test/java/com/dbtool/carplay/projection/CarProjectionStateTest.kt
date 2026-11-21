package com.dbtool.carplay.projection

import com.dbtool.carplay.api.session.CarProjectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CarProjectionState] 状态机单测。
 *
 * 投屏失败有四种原因、现象都是「仪表上什么都没有」，所以「卡在哪一步」的判定
 * 必须逐条钉死，否则日志会误导排查方向。
 */
class CarProjectionStateTest {

    @Test
    fun `全 true 才算可见`() {
        val state = CarProjectionState(
            streamReady = true,
            attached = true,
            firstFrame = true,
            displayId = 1,
            shown = true,
        )
        assertTrue(state.visible)
        assertEquals("仪表导航投屏中", state.summary)
    }

    @Test
    fun `手机没建流时优先报这一步`() {
        val state = CarProjectionState(streamReady = false, attached = true, firstFrame = true)
        assertFalse(state.visible)
        assertEquals("等待 iPhone 建立仪表屏流", state.summary)
    }

    @Test
    fun `窗没建起来时报车机侧`() {
        val state = CarProjectionState(streamReady = true, attached = false)
        assertEquals("车机未能在仪表屏上创建投屏窗口", state.summary)
    }

    @Test
    fun `窗建好但无首帧时报等首帧`() {
        val state = CarProjectionState(streamReady = true, attached = true, firstFrame = false)
        assertEquals("已请求投屏，等待 iPhone 首帧", state.summary)
    }

    @Test
    fun `就绪但未显示时提示可上滑`() {
        val state = CarProjectionState(
            streamReady = true,
            attached = true,
            firstFrame = true,
            shown = false,
        )
        assertEquals("已就绪，当前未显示（可在设置页手动投屏）", state.summary)
    }

    @Test
    fun `默认构造的 summary 判在最前一步`() {
        assertEquals("等待 iPhone 建立仪表屏流", CarProjectionState().summary)
    }

    @Test
    fun `shown 为 false 时即使前三条都就绪也不可见`() {
        val state = CarProjectionState(streamReady = true, attached = true, firstFrame = true)
        assertFalse(state.visible)
    }

    @Test
    fun `displayId 默认 -1 表示未接管`() {
        assertEquals(-1, CarProjectionState().displayId)
    }
}
