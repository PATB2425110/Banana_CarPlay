package com.dbtool.carplay.bridge

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * PATCH-0137 契约：冷启动必须有「进程启动 → onCreate 入口」这一段的分段耗时。
 *
 * 现场（VIN …368562 / 1.1.14）：`Slow dispatch took 9122ms ... BIND_APPLICATION`，
 * 而 `CarPlayApplication` 里原有的 `t0..t3` 只覆盖 181ms ⇒ 8.9 秒落在进入 onCreate 之前。
 * 没有这两行日志，下一份诊断包仍然只能说「启动慢」。
 */
class StartupStageTimingTest {

    @Test
    fun `attachBaseContext 打点存在`() {
        val src = stripComments(sourceOf(APP_FILE))
        val body = src.substringAfter("override fun attachBaseContext(")
            .substringBefore("override fun onCreate()")
        assertTrue("attachBaseContext 未打点", body.contains("SystemClock.elapsedRealtime()"))
        assertTrue("attach 前后都要记时间", body.count { it == '=' } >= 2)
    }

    @Test
    fun `进程启动段日志存在`() {
        val src = stripComments(sourceOf(APP_FILE))
        assertTrue("缺 启动分段 日志", src.contains("启动分段"))
        assertTrue(
            "必须以 Process.getStartElapsedRealtime() 为起点，否则量不到 onCreate 之前那段",
            src.contains("getStartElapsedRealtime"),
        )
        assertTrue("日志要能分别看出 attach 之前/本身/之后", src.contains("attachBaseContext 之前"))
    }

    private val APP_FILE = "car/app/src/main/java/com/dbtool/carplay/app/CarPlayApplication.kt"

    private fun stripComments(raw: String): String = raw
        .replace(Regex("(?s)/\\*.*?\\*/"), " ")
        .replace(Regex("(?m)//.*$"), " ")

    private fun sourceOf(relative: String): String {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile
        }
        throw AssertionError("找不到源文件：$relative")
    }
}
