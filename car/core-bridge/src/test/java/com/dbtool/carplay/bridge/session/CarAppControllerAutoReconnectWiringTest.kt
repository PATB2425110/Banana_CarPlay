package com.dbtool.carplay.bridge.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「自动重连」在 `CarAppController` 里的**接线契约**（PATCH-0095，2026-10-11）。
 *
 * ## 为什么是源码级断言
 *
 * `car/app` 是 **application 模块、没有 test source set**（见 `CarAppController`
 * 里对 `CarVideoCanvas.CANVAS_ASPECT_TOLERANCE` 的同款说明），
 * 所以"控制器有没有真的按策略排闹钟"在 JVM 里跑不起来，只能读源码。
 *
 * 判据与退避节奏本身有单测（[CarSessionAutoReconnectPolicyTest]）；这里只钉
 * **接线点**：事件分支有没有调、用户停止有没有被区分、计数有没有在正确的时机归零。
 *
 * ⚠️ 读之前**必须剥注释**（项目纪律）：这些代码块的注释里大量引用了
 * `scheduleAutoReconnect()` / `stopRequestedByUser` 这些名字（解释"为什么"），
 * 不剥掉就会让断言把注释当代码（假通过）。
 */
class CarAppControllerAutoReconnectWiringTest {

    // ── 事件分支 ────────────────────────────────────────────────────────────

    /** 会话结束必须排一次自动重连 —— 这正是本次修复的全部内容。 */
    @Test
    fun `SessionEnded 分支排自动重连`() {
        val body = eventBranch("CarSessionEvent.SessionEnded ->")
        assertTrue(
            "SessionEnded 分支必须调用 scheduleAutoReconnect()",
            body.contains("scheduleAutoReconnect()"),
        )
    }

    /**
     * 会话结束必须**撤掉"稳定计时"**。
     *
     * 不撤的话：会话只活了 5 秒就掉，那个 30 秒的归零闹钟照样会到点 ⇒
     * 重连计数被清零 ⇒ 退避永远停在第一次（无限循环）。
     */
    @Test
    fun `SessionEnded 分支撤销稳定计时`() {
        val body = eventBranch("CarSessionEvent.SessionEnded ->")
        assertTrue(
            "SessionEnded 分支必须 removeCallbacks(autoReconnectReset)",
            body.contains("removeCallbacks(autoReconnectReset)"),
        )
    }

    /**
     * 会话建立要重排"稳定计时"，但**不能直接把计数清零**。
     *
     * `SessionStarted` 在"连上就掉"的坏会话上也会触发；直接归零会让退避
     * 永远停在第一次 —— 那等于没有退避。
     */
    @Test
    fun `SessionStarted 分支重排稳定计时而不是直接归零`() {
        val body = eventBranch("CarSessionEvent.SessionStarted ->")
        assertTrue(
            "SessionStarted 分支必须重排 autoReconnectReset",
            body.contains("postDelayed(autoReconnectReset, AUTO_RECONNECT_STABLE_RESET_MILLIS)"),
        )
        assertFalse(
            "SessionStarted 分支不能直接把 autoReconnectAttempts 归零（退避会失效）",
            body.contains("autoReconnectAttempts = 0"),
        )
    }

    // ── 用户 vs 远端 ────────────────────────────────────────────────────────

    /**
     * ⚠️ `stop()`（用户明确要求停止）必须置位 `stopRequestedByUser`。
     *
     * 这是**唯一**能区分"用户点的停止"与"远端断开"的手段：两者在事件流里
     * 都是 `SessionEnded`（`CarPlaySessionBridge.stop()` 与 `onSessionEnded()`
     * 都 emit 同一个事件）。
     */
    @Test
    fun `用户停止时置位标记`() {
        val body = functionBody("stop")
        assertTrue(
            "stop() 必须置位 stopRequestedByUser",
            body.contains("stopRequestedByUser = true"),
        )
    }

    /** 用户主动开始 / 重连 ⇒ 标记复位、计数重新开始。 */
    @Test
    fun `用户开始与重连时复位`() {
        for (fn in listOf("startSession", "reconnect")) {
            val body = functionBody(fn)
            assertTrue(
                "$fn 必须把 stopRequestedByUser 复位",
                body.contains("stopRequestedByUser = false"),
            )
            assertTrue("$fn 必须复位自动重连状态", body.contains("resetAutoReconnect()"))
        }
    }

    // ── 重连动作本身 ────────────────────────────────────────────────────────

    /**
     * ⚠️ 自动重连必须走**权限闸门**。
     *
     * 失败原因里有一类就是"权限没给"（无线要定位、麦克风要录音）。
     * 绕过闸门直接重连 ⇒ 必然再失败一次，而闸门会把申请流程拉起来。
     * 用户点「重连」那条路（`reconnect()`）本来就是这么做的，自动那条不能例外。
     */
    @Test
    fun `自动重连走权限闸门`() {
        val body = functionBody("performAutoReconnect")
        assertTrue(
            "performAutoReconnect 必须检查 permissionGate",
            body.contains("permissionGate"),
        )
        // 2026-10-20 重连链修复：所有重连入口收敛到 requestReconnect(source)，
        // 自动重连必须是 AUTO 来源（不能借用户重连的语义重置计数）。
        assertTrue(
            "自动重连必须走唯一入口 requestReconnect(ReconnectSource.AUTO)",
            body.contains("requestReconnect(ReconnectSource.AUTO)"),
        )
    }

    /** 闸门拦下时**不再排下一次**：决定权已经交回用户。 */
    @Test
    fun `闸门拦下时不续排`() {
        val body = functionBody("performAutoReconnect")
        assertFalse(
            "performAutoReconnect 里不该再排闹钟（否则权限没给时会无限重排）",
            body.contains("postDelayed"),
        )
    }

    /** 判断与退避**必须复用策略模块**，不能在控制器里再写一遍 `&&` 链。 */
    @Test
    fun `排闹钟复用策略函数`() {
        val body = functionBody("scheduleAutoReconnect")
        assertTrue(body.contains("shouldAutoReconnect("))
        assertTrue(body.contains("autoReconnectDelayMillis("))
    }

    /** 用满次数要**说出来**（日志），否则用户只看到"断了就再也没反应"。 */
    @Test
    fun `用满次数时留下日志`() {
        val body = functionBody("scheduleAutoReconnect")
        assertTrue(
            "达到上限时必须 appendLog，让用户知道该手动点连接",
            body.contains("appendLog("),
        )
    }

    // ── 测试工具 ────────────────────────────────────────────────────────────

    /** 取 `onEvent` 里某个 `when` 分支的函数体（到下一个同级分支或 `when` 结束为止）。 */
    private fun eventBranch(marker: String): String {
        val source = codeOf()
        val start = source.indexOf(marker)
        assertTrue("找不到分支 $marker", start >= 0)
        val rest = source.substring(start + marker.length)
        // 下一个同级分支（缩进 12 空格 + `is `/`CarSessionEvent.`）即本分支结束。
        val next = Regex("""\n {12}(is CarSessionEvent\.|CarSessionEvent\.)""").find(rest)
        return if (next != null) rest.substring(0, next.range.first) else rest
    }

    /** 取某个 `override fun <name>(` 或 `fun <name>(` 的函数体（到下一个同级 `fun` 为止）。 */
    private fun functionBody(name: String): String {
        val source = codeOf()
        val start = source.indexOf("fun $name(")
        assertTrue("找不到函数 $name", start >= 0)
        val rest = source.substring(start + 1)
        val next = Regex("""\n {4}(override )?(private )?fun """).find(rest)
        return if (next != null) rest.substring(0, next.range.first) else rest
    }

    /**
     * 读 `CarAppController.kt` 并**剥掉注释**。
     *
     * 剥注释是必需的：这些代码块的 KDoc 为了讲清"为什么要区分用户停止"
     * 必然引用 `scheduleAutoReconnect` / `stopRequestedByUser` 这些名字，
     * 不剥掉会让断言把注释当代码（假通过或假失败）。
     */
    private fun codeOf(): String = sourceOf(
        "car/app/src/main/java/com/dbtool/carplay/app/CarAppController.kt",
    )
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
