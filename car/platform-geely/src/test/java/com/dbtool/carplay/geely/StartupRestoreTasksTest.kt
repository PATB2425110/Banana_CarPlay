package com.dbtool.carplay.geely

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 启动期恢复动作的提交语义（2026-10-20）。
 *
 * 关键只有一条：`submit` **不能等动作跑完**。冷启动 7.4 秒的
 * `BIND_APPLICATION` 里每一毫秒都在推迟首帧，恢复动作必须离开主线程。
 */
class StartupRestoreTasksTest {

    @Test
    fun `提交后立即返回且不在调用线程执行动作`() {
        val queued = mutableListOf<Runnable>()
        val logs = mutableListOf<String>()
        var ranOnCallingThread = false
        val tasks = StartupRestoreTasks(
            executor = Executor { queued += it },
            clock = { 0L },
            log = { logs += it },
        )

        val accepted = tasks.submit("蓝牙补恢复") {
            ranOnCallingThread = true
        }

        assertTrue("执行器应受理提交", accepted)
        assertFalse("submit 不该同步执行动作", ranOnCallingThread)
        assertEquals("动作应进队列等待后台执行", 1, queued.size)

        queued.single().run()

        assertTrue("后台执行时才真正跑动作", ranOnCallingThread)
        assertTrue(
            "动作结束必须落一行耗时",
            logs.any { it.startsWith("启动恢复：蓝牙补恢复=") && it.endsWith("ms") },
        )
    }

    @Test
    fun `动作结束后打出实际耗时`() {
        var now = 100L
        val logs = mutableListOf<String>()
        val tasks = StartupRestoreTasks(
            executor = Executor { it.run() },
            clock = {
                val value = now
                now += 25L
                value
            },
            log = { logs += it },
        )

        tasks.submit("仪表补还原") { }

        assertTrue(
            "应打出 仪表补还原=25ms，实际：$logs",
            logs.contains("启动恢复：仪表补还原=25ms"),
        )
    }

    @Test
    fun `动作抛异常只落日志不回传调用方`() {
        val logs = mutableListOf<String>()
        val tasks = StartupRestoreTasks(
            executor = Executor { it.run() },
            clock = { 0L },
            log = { logs += it },
        )

        val accepted = tasks.submit("蓝牙补恢复") { error("boom") }

        assertTrue(accepted)
        assertTrue(
            "失败必须留一行带标签的日志，实际：$logs",
            logs.any { it.startsWith("启动恢复：蓝牙补恢复 失败：boom") },
        )
    }

    @Test
    fun `执行器拒绝时不向启动路径抛异常`() {
        val logs = mutableListOf<String>()
        val tasks = StartupRestoreTasks(
            executor = Executor { throw RejectedExecutionException("closed") },
            clock = { 0L },
            log = { logs += it },
        )

        val accepted = tasks.submit("仪表补还原") { }

        assertFalse(accepted)
        assertTrue(
            "拒绝也要留日志，实际：$logs",
            logs.any { it.startsWith("启动恢复：仪表补还原 提交失败") },
        )
    }
}
