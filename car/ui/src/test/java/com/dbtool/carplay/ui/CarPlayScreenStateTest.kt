package com.dbtool.carplay.ui

import com.dbtool.carplay.api.session.CarReconnectState
import com.dbtool.carplay.api.session.CarReconnectStatus
import com.dbtool.carplay.api.session.CarSessionPhase
import com.dbtool.carplay.api.session.CarSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首页状态投影的文案 / 动作矩阵（方案 §4.2 / §4.3）。
 *
 * 这些断言只依赖纯函数 [CarUiStateMapper.project]，不需要 Compose / Android。
 */
class CarPlayScreenStateTest {

    private fun project(
        phase: CarSessionPhase,
        detail: String = "",
        errorMessage: String? = null,
        reconnect: CarReconnectStatus = CarReconnectStatus(),
    ): CarPlayScreenState = CarUiStateMapper.project(
        state = CarSessionState(
            phase = phase,
            detail = detail,
            errorMessage = errorMessage,
            reconnect = reconnect,
        ),
        settings = null,
    )

    @Test
    fun `STOPPED 按 detail 区分三态文案`() {
        val stopping = project(CarSessionPhase.STOPPED, detail = "正在停止")
        assertEquals("正在停止", stopping.title)
        assertEquals("正在关闭 CarPlay", stopping.subtitle)

        val stopped = project(CarSessionPhase.STOPPED, detail = "已停止")
        assertEquals("已停止", stopped.title)
        assertEquals("点「启动 CarPlay」重新连接", stopped.subtitle)

        val closed = project(CarSessionPhase.STOPPED, detail = "已关闭")
        assertEquals("已关闭", closed.title)
        assertEquals("应用已关闭", closed.subtitle)

        // 熄火暂停的 detail 是"已停止（熄火）"，同属"已停止"这一档。
        val powerPause = project(CarSessionPhase.STOPPED, detail = "已停止（熄火）")
        assertEquals("已停止", powerPause.title)
        assertEquals("点「启动 CarPlay」重新连接", powerPause.subtitle)
    }

    @Test
    fun `等待类阶段给出可执行的副标题`() {
        val preparing = project(CarSessionPhase.PREPARING_MFI, detail = "准备认证")
        assertEquals("准备认证", preparing.title)
        assertEquals("正在准备 MFi 认证模块", preparing.subtitle)

        val waitingMfi = project(CarSessionPhase.WAITING_FOR_MFI, detail = "等待认证模块")
        assertEquals("等待认证模块", waitingMfi.title)
        assertEquals("请确认 MFi 认证模块已连接", waitingMfi.subtitle)

        val waitingDevice = project(CarSessionPhase.WAITING_FOR_DEVICE, detail = "等待 iPhone 接入")
        assertEquals("等待 iPhone 接入", waitingDevice.title)
        assertEquals("请按车机提示在 iPhone 上确认 CarPlay", waitingDevice.subtitle)

        val connecting = project(CarSessionPhase.CONNECTING, detail = "与 iPhone 配对")
        assertEquals("与 iPhone 配对", connecting.title)
        assertEquals("正在完成 CarPlay 握手，请稍候", connecting.subtitle)
    }

    @Test
    fun `SCHEDULED 显示次数与倒计时且没有重试按钮`() {
        val projected = project(
            phase = CarSessionPhase.RECONNECTING,
            reconnect = CarReconnectStatus(
                state = CarReconnectState.SCHEDULED,
                attempt = 1,
                maxAttempts = 3,
                delayMillis = 2_000L,
            ),
        )
        assertEquals("正在重新连接", projected.title)
        assertEquals("第 1/3 次，约 2 秒后重试", projected.subtitle)
        assertEquals(setOf(CarUiAction.STOP), projected.availableActions)
        assertFalse(CarUiAction.RECONNECT in projected.availableActions)
    }

    @Test
    fun `IN_PROGRESS 显示正在重建且没有重试按钮`() {
        val projected = project(
            phase = CarSessionPhase.RECONNECTING,
            reconnect = CarReconnectStatus(
                state = CarReconnectState.IN_PROGRESS,
                attempt = 2,
                maxAttempts = 3,
            ),
        )
        assertEquals("正在重新连接", projected.title)
        assertEquals("正在重建连接（第 2/3 次）", projected.subtitle)
        assertEquals(setOf(CarUiAction.STOP), projected.availableActions)
        assertFalse(CarUiAction.RECONNECT in projected.availableActions)
    }

    @Test
    fun `EXHAUSTED 显示自动重连已停止并提供重新连接`() {
        val projected = project(
            phase = CarSessionPhase.FAILED,
            reconnect = CarReconnectStatus(
                state = CarReconnectState.EXHAUSTED,
                attempt = 3,
                maxAttempts = 3,
            ),
        )
        assertEquals("自动重连已停止", projected.title)
        assertEquals("已尝试 3 次仍未连接，请检查手机或热点后重试", projected.subtitle)
        assertTrue(CarUiAction.RECONNECT in projected.availableActions)
        assertTrue(CarUiAction.OPEN_SETTINGS in projected.availableActions)
        assertFalse(CarUiAction.STOP in projected.availableActions)
    }

    @Test
    fun `FAILED 且未耗尽沿用错误原因`() {
        val withError = project(
            phase = CarSessionPhase.FAILED,
            detail = "失败：上游原因",
            errorMessage = "连接失败原因",
        )
        assertEquals("连接失败", withError.title)
        assertEquals("连接失败原因", withError.subtitle)

        val withoutError = project(
            phase = CarSessionPhase.FAILED,
            detail = "失败：上游原因",
            errorMessage = null,
        )
        assertEquals("连接失败", withoutError.title)
        assertEquals("失败：上游原因", withoutError.subtitle)
    }

    @Test
    fun `reconnect 原样投影且不改变既有动作与视频开关`() {
        val status = CarReconnectStatus(
            state = CarReconnectState.SCHEDULED,
            attempt = 3,
            maxAttempts = 3,
            delayMillis = 8_000L,
        )
        val projected = project(
            phase = CarSessionPhase.RECONNECTING,
            reconnect = status,
        )
        assertEquals(status, projected.reconnect)
        assertEquals(setOf(CarUiAction.STOP), projected.availableActions)
        assertFalse(projected.showVideo)
        assertFalse(projected.touchEnabled)
    }

    @Test
    fun `STOPPED 与 ACTIVE 的动作矩阵保持不变`() {
        val stopped = project(CarSessionPhase.STOPPED, detail = "已停止")
        assertEquals(setOf(CarUiAction.START), stopped.availableActions)

        val active = project(CarSessionPhase.ACTIVE, detail = "已连接")
        assertEquals(
            setOf(CarUiAction.STOP, CarUiAction.OPEN_SETTINGS),
            active.availableActions,
        )
        assertTrue(active.showVideo)
        assertTrue(active.touchEnabled)
    }
}
