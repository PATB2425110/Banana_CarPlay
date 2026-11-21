package com.dbtool.carplay.geely.lynk

import com.dbtool.carplay.api.developer.CarDebugTunnelState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 隧道状态 → 界面契约的映射单测（2026-10-16）。
 *
 * ## 为什么这条翻译值得单独钉
 *
 * 它是**两种枚举之间唯一的那根线**（`LynkDebugTunnel.State` → `CarDebugTunnelState`）。
 * 翻译错一位（比如 `RETRYING` 落成 `CONNECTED`）不会有任何编译错误、
 * 也不会让隧道本身出问题 —— 现场表现只是"界面说已连接、实际连不上"，
 * 而这正是这次改造要消灭的那类误导。所以四个分支逐条断言，
 * 字段透传（失败原因 / 连上时刻 / 重连次数）也一并钉住：漏传一个字段
 * 同样只会让界面少说一句话，不会被别处发现。
 */
class LynkDebugTunnelStatusMappingTest {

    private fun status(
        state: LynkDebugTunnel.State,
        lastError: String? = null,
        lastConnectedAtMs: Long = 0L,
        reconnectCount: Int = 0,
    ) = LynkDebugTunnel.TunnelStatus(
        state = state,
        lastError = lastError,
        lastConnectedAtMs = lastConnectedAtMs,
        connectStartedAtMs = 0L,
        reconnectCount = reconnectCount,
    )

    @Test(timeout = 10_000)
    fun `四个状态逐条映射`() {
        assertEquals(
            CarDebugTunnelState.IDLE,
            status(LynkDebugTunnel.State.IDLE).toUiStatus().state,
        )
        assertEquals(
            CarDebugTunnelState.CONNECTING,
            status(LynkDebugTunnel.State.CONNECTING).toUiStatus().state,
        )
        assertEquals(
            CarDebugTunnelState.CONNECTED,
            status(LynkDebugTunnel.State.CONNECTED).toUiStatus().state,
        )
        assertEquals(
            CarDebugTunnelState.RETRYING,
            status(LynkDebugTunnel.State.RETRYING).toUiStatus().state,
        )
    }

    @Test(timeout = 10_000)
    fun `失败原因与连上时刻与重连次数原样透传`() {
        val ui = status(
            state = LynkDebugTunnel.State.RETRYING,
            lastError = "IOException: tunnel auth rejected",
            lastConnectedAtMs = 1_759_000_000_000L,
            reconnectCount = 4,
        ).toUiStatus()

        assertEquals("IOException: tunnel auth rejected", ui.lastError)
        assertEquals(1_759_000_000_000L, ui.lastConnectedAtMs)
        assertEquals(4, ui.reconnectCount)
    }

    @Test(timeout = 10_000)
    fun `没有失败记录时保持 null（界面按「没有」处理，别翻译成空串）`() {
        assertNull(status(LynkDebugTunnel.State.CONNECTED).toUiStatus().lastError)
    }
}
