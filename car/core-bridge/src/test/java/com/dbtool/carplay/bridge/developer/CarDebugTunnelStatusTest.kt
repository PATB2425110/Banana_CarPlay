package com.dbtool.carplay.bridge.developer

import com.dbtool.carplay.api.developer.CarDebugTunnelState
import com.dbtool.carplay.api.developer.CarDebugTunnelStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 调试反连隧道状态行的文案单测（2026-10-16）。
 *
 * ## 为什么这两条纯函数值得钉
 *
 * `detailLine` 是**界面唯一那句"现在到底怎么回事"**：开关拨上去之后，用户拿到的
 * 全部信息就是这一行字。它同时也是 `:car:ui` 里唯一无法在 JVM 上测到的部分
 * （`car:ui` 没有测试源集，见 `DeveloperTunnelUiMaskingContractTest` 的说明），
 * 所以四态语气与 `lastError` 的有无必须在这一层被测死。
 *
 * ## 为什么必须显式传 `nowMs`
 *
 * "已连上，保持 N 分钟"若内部取系统时间，就**测不出边界**（只能断言"包含'保持'"
 * 这种没营养的东西）。把它做成入参 ⇒ 纯函数，`humanElapsed` 的分档也能钉死：
 * `0` / `59_000` / `60_000` / `3_599_000` / `3_600_000` 这五个值正好压在
 * 四道分界线上（秒→分→时各两处），是最容易写反的地方。
 */
class CarDebugTunnelStatusTest {

    // ------------------------------------------------------------ detailLine 四态

    @Test
    fun `IDLE 没失败过_只说未开启`() {
        assertEquals("未开启隧道", CarDebugTunnelStatus().detailLine(1_000L))
    }

    @Test
    fun `IDLE 有失败记录_要带上原因`() {
        val status = CarDebugTunnelStatus(lastError = "ConnectException: refused")
        assertEquals(
            "未开启；上次失败：ConnectException: refused",
            status.detailLine(1_000L),
        )
    }

    @Test
    fun `lastError 是空白串_按没有失败处理`() {
        // 隧道实现里 lastError 的类型是 String?，但契约层不能假定"有值就一定有意义"：
        // 空白串同样是"没有"。两条分支都要走 IDLE 的干净版本。
        assertEquals(
            "未开启隧道",
            CarDebugTunnelStatus(lastError = "   ").detailLine(1_000L),
        )
        assertEquals(
            "连接断开，已重连 2 次",
            CarDebugTunnelStatus(
                state = CarDebugTunnelState.RETRYING,
                lastError = "",
                reconnectCount = 2,
            ).detailLine(1_000L),
        )
    }

    @Test
    fun `CONNECTING 只说正在反连`() {
        val status = CarDebugTunnelStatus(state = CarDebugTunnelState.CONNECTING)
        assertEquals("正在反连服务器…", status.detailLine(1_000L))
    }

    @Test
    fun `CONNECTED 有时间戳_报保持了多久`() {
        val status = CarDebugTunnelStatus(
            state = CarDebugTunnelState.CONNECTED,
            lastConnectedAtMs = 10_000L,
        )
        assertEquals("已连上，保持 刚刚", status.detailLine(10_000L))
        assertEquals("已连上，保持 5 秒", status.detailLine(15_000L))
    }

    @Test
    fun `CONNECTED 但没有时间戳_只说已连上`() {
        // 状态与时刻是两个字段，理论上有"已连上但时刻还是 0"的窗口；
        // 这时绝不能拿 0 去算出一个"1970 年"的荒谬时长。
        val status = CarDebugTunnelStatus(state = CarDebugTunnelState.CONNECTED)
        assertEquals("已连上", status.detailLine(1_000L))
    }

    @Test
    fun `RETRYING 报重连次数_有失败原因就附上`() {
        val bare = CarDebugTunnelStatus(
            state = CarDebugTunnelState.RETRYING,
            reconnectCount = 3,
        )
        assertEquals("连接断开，已重连 3 次", bare.detailLine(1_000L))

        val withError = bare.copy(lastError = "IOException: tunnel auth rejected")
        assertEquals(
            "连接断开，已重连 3 次；上次失败：IOException: tunnel auth rejected",
            withError.detailLine(1_000L),
        )
    }

    // ------------------------------------------------------------ humanElapsed 边界

    @Test
    fun `humanElapsed 压在四道分界线上`() {
        assertEquals("刚刚", CarDebugTunnelStatus.humanElapsed(0L))
        assertEquals("刚刚", CarDebugTunnelStatus.humanElapsed(999L))
        assertEquals("59 秒", CarDebugTunnelStatus.humanElapsed(59_000L))
        assertEquals("1 分钟", CarDebugTunnelStatus.humanElapsed(60_000L))
        assertEquals("59 分钟", CarDebugTunnelStatus.humanElapsed(3_599_000L))
        assertEquals("1 小时", CarDebugTunnelStatus.humanElapsed(3_600_000L))
    }

    @Test
    fun `humanElapsed 再往上是天`() {
        assertEquals("23 小时", CarDebugTunnelStatus.humanElapsed(86_399_000L))
        assertEquals("1 天", CarDebugTunnelStatus.humanElapsed(86_400_000L))
        assertEquals("3 天", CarDebugTunnelStatus.humanElapsed(3L * 86_400_000L))
    }
}
