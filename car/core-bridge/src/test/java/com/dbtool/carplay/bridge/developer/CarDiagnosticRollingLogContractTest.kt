package com.dbtool.carplay.bridge.developer

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 契约测试：诊断包必须把**整族**滚动日志各带一份回来（PATCH-0102，2026-10-12）。
 *
 * ## 为什么钉它（`REF-pitfalls.md` §124）
 *
 * 车机上滚动日志是 `xcertplay.log.3 / .2 / .1 / xcertplay.log` 四份，
 * 而采集清单里 `rollingLog` 的四条记录**过去全部把输出名写成 `logs/xcertplay.log`**
 * ⇒ 后一条覆盖前一条，包里**只剩最后一个（当前文件）**。
 *
 * 后果不是"少了几行"，而是**中间整段永久丢失**：
 * - `日志/1` 丢了 21:25:23–21:33:34（含仪表第一次挂载的完整过程）；
 * - `日志/3` 丢了 16:57–17:00:09（那一段只能从 `logcat.txt` 里拼回来）。
 *
 * 这类失败**跑起来完全正常** —— 包照样生成、清单照样有一行 `rollingLog:`，
 * 只有真去翻那一段时才发现在。所以必须钉住。
 *
 * ## 两条断言各管一半
 *
 * - 行为断言：`familyOf` 给的是**整族**，按修复后的规则落盘必须得到四个文件
 *   （旧规则只会剩一个）；
 * - 源码断言：采集侧的输出名**必须取 `source.name`**（源文件自己的名字，含序号），
 *   不许再取族名 `name`。
 *
 * ⚠️ 两条都要有：行为断言里的"修复后规则"是**测试侧的复刻**，
 * 实现若改回旧写法它照样绿；源码断言才是钉住实现的那一半。
 */
class CarDiagnosticRollingLogContractTest {

    // 单测 cwd = car/core-bridge（模块目录）。
    private val packagerSrc: String = run {
        val candidates = listOf(
            File("src/main/java/com/dbtool/carplay/bridge/developer/CarDiagnosticPackager.kt"),
            File("../src/main/java/com/dbtool/carplay/bridge/developer/CarDiagnosticPackager.kt"),
            File("../../src/main/java/com/dbtool/carplay/bridge/developer/CarDiagnosticPackager.kt"),
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("找不到 CarDiagnosticPackager.kt（从 ${File(".").absolutePath} 起找过 ${candidates.size} 个位置）")
        file.readText()
    }

    private fun tempDir(): File =
        Files.createTempDirectory("dbplay-rolling-log-contract").toFile().apply { deleteOnExit() }

    @Test
    fun `整族滚动日志各占一个输出名，中间那几段不再被覆盖`() {
        val src = tempDir()
        // 造出整族四份（模拟车机上的现状）。
        File(src, "xcertplay.log.3").writeText("oldest\n")
        File(src, "xcertplay.log.2").writeText("older\n")
        File(src, "xcertplay.log.1").writeText("recent\n")
        File(src, "xcertplay.log").writeText("current\n")

        val family = RollingLogFileWriter.familyOf(src, "xcertplay.log", maxFiles = 4)
        assertEquals("整族四份都要被 familyOf 找到", 4, family.size)

        // 修复后的规则：输出名 = 源文件自己的名字（含序号）。
        // 旧规则（一律取族名）在这里只会剩一个文件 —— 这正是 §124 的现场。
        val out = tempDir()
        family.forEach { source -> File(out, source.name).writeText(source.readText()) }

        assertEquals(
            "四段必须各占一个输出文件，不许互相覆盖",
            4,
            out.listFiles()?.size ?: 0,
        )
        assertEquals("oldest\n", File(out, "xcertplay.log.3").readText())
        assertEquals("older\n", File(out, "xcertplay.log.2").readText())
        assertEquals("recent\n", File(out, "xcertplay.log.1").readText())
        assertEquals("current\n", File(out, "xcertplay.log").readText())
    }

    @Test
    fun `采集侧的输出名必须取源文件自己的名字，不是族名`() {
        // 先剥注释（§29）：本文件与实现的注释里必然出现 `entryName` 这些字面量。
        val code = packagerSrc
            .replace(Regex("""/\*[\s\S]*?\*/"""), "")
            .replace(Regex("""//[^\n]*"""), "")
        // ⚠️ 只在**滚动日志采集那一段**里找 —— 同一个文件别处还有同名的局部变量
        //（归档那段也用 `entryName`）。直接取全文第一个，将来会错位到别的循环上。
        val region = code.substringAfter("for (name in ROLLING_LOG_NAMES)", "")
        assertTrue("没切到滚动日志采集段 —— 锚点是不是改了？", region.isNotEmpty())
        val line = region.lines().firstOrNull { it.contains("val entryName =") }
        assertNotNull("采集侧的 `val entryName =` 不见了 —— 锚点是不是改了？", line)

        val assignment = line!!.trim()
        assertTrue(
            "输出名必须带上源文件自己的名字（`source.name`，含 .1/.2/.3 序号），实际：$assignment",
            assignment.contains("source.name"),
        )
        // 旧写法 `… else name` 会让整族落进同一个输出名 —— 这条就是防它回退。
        assertFalse(
            "输出名不许再取族名 `name`（会互相覆盖，§124），实际：$assignment",
            Regex("""else\s+name\s*$""").containsMatchIn(assignment),
        )
    }
}
