package com.dbtool.carplay.bridge.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「重连链修复」的**接线契约**（2026-10-20）。
 *
 * `car/app` 是 application 模块、没有 test source set，控制器本身跑不起来；
 * 纯判据已由 [CarReconnectChainTest] 钉住。这里只钉**代码形状**：
 *
 * 1. 控制器里除了唯一入口 `requestReconnect()`，不得再出现 `session.reconnect()` 直调；
 * 2. `SessionEnded` 分支只有 `REMOTE` 调 `scheduleAutoReconnect()`；
 * 3. 耗尽必须调用 `exhaustedReconnectStatus()`，且受 `stopRequestedByUser` 保护；
 * 4. 桥接层停止/重连前必须写 `expectedEndReasons`；
 * 5. 画布重连必须走 `requestReconnect(CANVAS)`。
 *
 * ⚠️ 断言前必须剥注释（项目纪律）：这些代码块的 KDoc 大量引用了函数名。
 */
class CarReconnectChainWiringTest {

    private val controller =
        "car/app/src/main/java/com/dbtool/carplay/app/CarAppController.kt"
    private val bridge =
        "car/core-bridge/src/main/java/com/dbtool/carplay/bridge/CarPlaySessionBridge.kt"

    // ── 单入口 ──────────────────────────────────────────────────────────────

    @Test
    fun `控制器里只有唯一入口直调 session reconnect`() {
        val code = codeOf(controller)
        val directCalls = Regex("""session\.reconnect\(\)""").findAll(code).count()
        assertEquals(
            "session.reconnect() 直调必须只剩 requestReconnect() 里那一处",
            1,
            directCalls,
        )
        val entry = functionBody(controller, "requestReconnect")
        assertTrue(
            "唯一入口必须真的调用 session.reconnect()",
            entry.contains("session.reconnect()"),
        )
    }

    @Test
    fun `唯一入口带 in-flight 守卫`() {
        val entry = functionBody(controller, "requestReconnect")
        assertTrue(
            "requestReconnect 必须先检查 reconnectInFlight",
            entry.contains("if (reconnectInFlight)"),
        )
        assertTrue(
            "requestReconnect 必须在放行后置位 reconnectInFlight",
            entry.contains("reconnectInFlight = true"),
        )
        assertTrue(
            "唯一入口要把当前设置交给会话",
            entry.contains("session.updateSettings(settings)"),
        )
    }

    @Test
    fun `重建未完成时不排新的自动重连`() {
        val schedule = functionBody(controller, "scheduleAutoReconnect")
        assertTrue(
            "排队前必须检查 reconnectInFlight（否则重建未完成时会叠加 generation）",
            schedule.contains("if (reconnectInFlight)"),
        )
    }

    @Test
    fun `画布重连走唯一入口且不重置计数`() {
        val canvas = functionBody(controller, "scheduleCanvasReconnect")
        assertTrue(
            "画布尺寸变化必须走 requestReconnect(ReconnectSource.CANVAS)",
            canvas.contains("requestReconnect(ReconnectSource.CANVAS)"),
        )
        assertFalse(
            "画布重连不能再直调 session.reconnect()",
            canvas.contains("session.reconnect()"),
        )
        assertFalse(
            "画布重连不是新的断线事件，不得重置自动重连计数",
            canvas.contains("resetAutoReconnect()"),
        )
    }

    // ── 事件来源分支 ────────────────────────────────────────────────────────

    @Test
    fun `SessionEnded 分支按来源分流且只有 REMOTE 排重连`() {
        val code = codeOf(controller)
        assertTrue(
            "SessionEnded 已改成 data class，when 分支必须用 is",
            code.contains("is CarSessionEvent.SessionEnded ->"),
        )
        val branch = eventBranch(controller, "CarSessionEvent.SessionEnded ->")
        assertTrue(
            "REMOTE 必须排自动重连",
            branch.contains("CarSessionEndReason.REMOTE -> scheduleAutoReconnect()"),
        )
        assertEquals(
            "整个分支只允许出现一次 scheduleAutoReconnect()（只能挂在 REMOTE 上）",
            1,
            Regex("""scheduleAutoReconnect\(\)""").findAll(branch).count(),
        )
        assertTrue(
            "用户停止必须撤销闹钟并复位计数",
            branch.contains("CarSessionEndReason.USER_STOP") &&
                branch.contains("resetAutoReconnect()"),
        )
        assertTrue(
            "熄火暂停必须与用户停止同样处理（不排自动重连）",
            branch.contains("CarSessionEndReason.POWER_PAUSE"),
        )
        assertTrue(
            "故意重连必须单独成一个分支（只撤闹钟、不重置计数）",
            branch.contains("CarSessionEndReason.RECONNECT"),
        )
    }

    // ── 耗尽态 ──────────────────────────────────────────────────────────────

    @Test
    fun `耗尽调用 EXHAUSTED 且受用户停止保护`() {
        val schedule = functionBody(controller, "scheduleAutoReconnect")
        assertTrue(
            "排期必须复用策略函数 shouldAutoReconnect",
            schedule.contains("shouldAutoReconnect("),
        )
        assertTrue(
            "退避必须复用策略函数 autoReconnectDelayMillis",
            schedule.contains("autoReconnectDelayMillis("),
        )
        assertTrue(
            "耗尽判据必须复用 shouldExhaustAutoReconnect",
            schedule.contains("shouldExhaustAutoReconnect("),
        )
        assertTrue(
            "耗尽必须落 exhaustedReconnectStatus",
            schedule.contains("exhaustedReconnectStatus("),
        )
        assertTrue(
            "耗尽必须受 stopRequestedByUser 保护（用户停止不算耗尽）",
            schedule.contains("stopRequestedByUser"),
        )
    }

    @Test
    fun `权限闸门拦下也落耗尽`() {
        val perform = functionBody(controller, "performAutoReconnect")
        assertTrue("自动重连仍必须过权限闸门", perform.contains("permissionGate"))
        assertTrue(
            "权限未就绪必须落 EXHAUSTED，不能停在 RECONNECTING",
            perform.contains("exhaustedReconnectStatus("),
        )
        assertTrue(
            "自动重连必须走唯一入口的 AUTO 来源",
            perform.contains("requestReconnect(ReconnectSource.AUTO)"),
        )
        assertFalse(
            "performAutoReconnect 里不得再直调 session.reconnect()",
            perform.contains("session.reconnect()"),
        )
    }

    @Test
    fun `复位会清空重试轴`() {
        val reset = functionBody(controller, "resetAutoReconnect")
        assertTrue(
            "resetAutoReconnect 必须把 reconnect 状态清回 NONE",
            reset.contains("session.updateReconnect(CarReconnectStatus())"),
        )
        assertTrue(
            "复位必须清零计数",
            reset.contains("autoReconnectAttempts = 0"),
        )
    }

    // ── 桥接层 ──────────────────────────────────────────────────────────────

    @Test
    fun `桥接层停止与重连都标记预期结束原因`() {
        val code = codeOf(bridge)
        assertTrue(
            "用户停止必须带 USER_STOP",
            code.contains("stopInternal(CarSessionEndReason.USER_STOP)"),
        )
        assertTrue(
            "熄火暂停必须带 POWER_PAUSE（不再复用无参 stop()）",
            code.contains("stopSession = { stopInternal(CarSessionEndReason.POWER_PAUSE) }"),
        )
        assertTrue(
            "reconnect() 拆旧会话前必须标 RECONNECT",
            code.contains("expectedEndReasons[it] = CarSessionEndReason.RECONNECT"),
        )
        assertTrue(
            "stopInternal 拆旧会话前必须标发起方原因",
            code.contains("expectedEndReasons[it] = reason"),
        )
        assertTrue(
            "onSessionEnded 必须读标记，缺省 REMOTE",
            code.contains(
                "expectedEndReasons.remove(session) ?: CarSessionEndReason.REMOTE",
            ),
        )
        assertTrue(
            "SessionEnded 事件必须带来源",
            code.contains("emit(CarSessionEvent.SessionEnded(reason))"),
        )
    }

    @Test
    fun `桥接层只有 REMOTE 才改 RECONNECTING`() {
        val ended = functionBody(bridge, "onSessionEnded")
        assertTrue(
            "onSessionEnded 必须按 reason 判断是否改 phase",
            ended.contains("if (reason == CarSessionEndReason.REMOTE)"),
        )
        assertTrue(
            "REMOTE 分支保留原来的 RECONNECTING + 会话已结束提示",
            ended.contains("CarSessionPhase.RECONNECTING") &&
                ended.contains("CarPlay 会话已结束"),
        )
    }

    @Test
    fun `updateReconnect 的耗尽映射到 FAILED`() {
        val update = functionBody(bridge, "updateReconnect")
        assertTrue(
            "updateReconnect 必须处理 EXHAUSTED",
            update.contains("CarReconnectState.EXHAUSTED"),
        )
        assertTrue(
            "EXHAUSTED 的 phase 必须来自 appliedPhase()（=FAILED）",
            update.contains("appliedPhase()"),
        )
        assertTrue(
            "耗尽必须清空设备与音频通道",
            update.contains("withoutDevice()") &&
                update.contains("activeAudioChannels = emptySet()"),
        )
    }

    // ── 测试工具 ────────────────────────────────────────────────────────────

    /** 取 `onEvent` 里某个 `when` 分支的函数体（到下一个同级分支或 `when` 结束为止）。 */
    private fun eventBranch(relative: String, marker: String): String {
        val source = codeOf(relative)
        val start = source.indexOf(marker)
        assertTrue("找不到分支 $marker", start >= 0)
        val rest = source.substring(start + marker.length)
        val next = Regex("""\n {12}(is CarSessionEvent\.|CarSessionEvent\.)""").find(rest)
        return if (next != null) rest.substring(0, next.range.first) else rest
    }

    /** 取某个 `fun <name>(` 的函数体（到下一个同级 `fun` 为止）。 */
    private fun functionBody(relative: String, name: String): String {
        val source = codeOf(relative)
        val start = source.indexOf("fun $name(")
        assertTrue("找不到函数 $name", start >= 0)
        val rest = source.substring(start + 1)
        val next = Regex("""\n {4}(override )?(private )?fun """).find(rest)
        return if (next != null) rest.substring(0, next.range.first) else rest
    }

    /** 读源码并剥掉注释（断言源码字面量前的项目纪律）。 */
    private fun codeOf(relative: String): String = sourceOf(relative)
        .replace(Regex("(?s)/\\*.*?\\*/"), " ")
        .replace(Regex("(?m)//.*$"), " ")

    private fun sourceOf(relative: String): String {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile
        }
        throw AssertionError("找不到源码：$relative")
    }
}
