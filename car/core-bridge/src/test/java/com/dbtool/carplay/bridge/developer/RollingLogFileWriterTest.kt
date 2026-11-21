package com.dbtool.carplay.bridge.developer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 滚动日志写入器契约（PATCH-0095）。
 *
 * ## 为什么值得单独钉
 *
 * 这份文件的全部意义就是"**出事前后那一段不能丢**"。而"写满之后怎么办"
 * 恰好是那种**写错了也看不出来**的地方 —— 上游 `SessionLogFile` 就是
 * `outputStream()` 从头覆盖，跑起来一切正常，只有真去翻日志时才发现
 * 断连前的内容已经被抹掉了。
 *
 * 所以这里直接断言**内容层面**的事实：写满滚动之后，最早的行在 `.1` 里、
 * 最新的行在 `name` 里，而不是被覆盖掉。
 */
class RollingLogFileWriterTest {

    private fun tempDir(): File =
        Files.createTempDirectory("dbplay-rolling-log-test").toFile().apply { deleteOnExit() }

    @Test
    fun `写满后滚动，旧内容进 点1 而不是被覆盖`() {
        val dir = tempDir()
        // 每行 9 字节（"LNNNNNNN\n"），上限 40 字节 ⇒ 每 4 行滚一次。
        val writer = RollingLogFileWriter(dir, "xcertplay.log", maxFileBytes = 40L, maxFiles = 4)
        repeat(12) { writer.appendLine("L%07d".format(it)) }
        writer.close()

        val current = File(dir, "xcertplay.log")
        assertTrue("当前文件必须存在", current.isFile)
        assertTrue(
            "写满后必须产生 .1（而不是像上游那样从头覆盖）",
            File(dir, "xcertplay.log.1").isFile,
        )

        // 🔴 这是本条测试的全部意义：**最早那几行必须还在**。
        // 上游 `SessionLogFile` 的写法在这里会返回 false —— 它会把文件
        // 从头覆盖，只剩最后几行。
        val family = RollingLogFileWriter.familyOf(dir, "xcertplay.log", maxFiles = 4)
        val allText = family.joinToString("\n") { it.readText() }
        assertTrue(
            "最早的行必须被保留（滚动而不是覆盖），实际内容：$allText",
            allText.contains("L0000000"),
        )

        // 最新的行必须在**当前**文件里（而不是被滚走了）。
        assertTrue(
            "最新的行必须在当前文件里，实际：${current.readText()}",
            current.readText().contains("L0000011"),
        )
    }

    @Test
    fun `保留份数有上限，最老的会被丢弃`() {
        val dir = tempDir()
        val writer = RollingLogFileWriter(dir, "log.txt", maxFileBytes = 20L, maxFiles = 3)
        repeat(30) { writer.appendLine("row%05d".format(it)) }
        writer.close()

        assertTrue(File(dir, "log.txt").isFile)
        assertTrue(File(dir, "log.txt.1").isFile)
        assertTrue(File(dir, "log.txt.2").isFile)
        // maxFiles = 3 ⇒ 只能有 当前 + .1 + .2，不该出现 .3。
        assertFalse("超出上限的 .3 必须被丢掉", File(dir, "log.txt.3").isFile)
    }

    /**
     * `roll()` 是"归档上一进程的尾巴"。
     *
     * `CarLogcatRecorder.start()` 每次都会先调它 —— 否则上一次运行（或上一次会话）
     * 留下的内容会被新一行直接盖掉，而那一段往往正是要查的。
     */
    @Test
    fun `roll 把已有内容归档成 点1 并从头开始`() {
        val dir = tempDir()
        val writer = RollingLogFileWriter(dir, "xcertplay.log", maxFileBytes = 1L shl 20, maxFiles = 4)
        writer.appendLine("上一进程的最后一行")
        writer.close()

        val second = RollingLogFileWriter(dir, "xcertplay.log", maxFileBytes = 1L shl 20, maxFiles = 4)
        second.roll()
        second.appendLine("本次的第一行")
        second.close()

        assertTrue(
            "上一进程的尾巴必须被归档到 .1",
            File(dir, "xcertplay.log.1").readText().contains("上一进程的最后一行"),
        )
        val current = File(dir, "xcertplay.log").readText()
        assertTrue("当前文件应是本次内容", current.contains("本次的第一行"))
        assertFalse("当前文件不该还留着上一进程的内容", current.contains("上一进程的最后一行"))
    }

    /** `familyOf` 必须**最老在前** —— 诊断包按这个顺序拼接才读得通。 */
    @Test
    fun `familyOf 按最老在前返回全部存在的文件`() {
        val dir = tempDir()
        val writer = RollingLogFileWriter(dir, "log.txt", maxFileBytes = 20L, maxFiles = 3)
        repeat(30) { writer.appendLine("row%05d".format(it)) }
        writer.close()

        val family = RollingLogFileWriter.familyOf(dir, "log.txt", maxFiles = 3)
        assertEquals(listOf("log.txt.2", "log.txt.1", "log.txt"), family.map { it.name })
    }

    @Test
    fun `文件不存在时 familyOf 返回空表`() {
        val dir = tempDir()
        assertTrue(RollingLogFileWriter.familyOf(dir, "nope.log", maxFiles = 4).isEmpty())
    }

    /** 行数 / 滚动次数要如实统计（诊断包清单靠它判断"记录器到底有没有在工作"）。 */
    @Test
    fun `统计行数与滚动次数`() {
        val dir = tempDir()
        val writer = RollingLogFileWriter(dir, "log.txt", maxFileBytes = 20L, maxFiles = 4)
        repeat(10) { writer.appendLine("row%05d".format(it)) }
        assertEquals(10L, writer.lineCount())
        assertTrue("写满必然滚过", writer.rotationCount() >= 1)
        writer.close()
    }
}
