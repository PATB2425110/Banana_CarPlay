package com.dbtool.carplay.bridge.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BtLinkReleaseCommand] 的命令形状契约（2026-10-21 方案 §5.4）。
 *
 * 这些断言钉住三件"错了就会在车上静默失败"的事：
 * 1. `su 0` 前缀之后第一个词仍然是 `/system/bin/sh`；
 * 2. `CLASSPATH=` 必须在 `app_process` **之前**（它是环境变量前缀）；
 * 3. 主类 → verb → mac 的顺序，以及单引号转义。
 */
class BtLinkReleaseCommandTest {

    private val apk = "/data/app/io.dushu.car/base.apk"
    private val mac = "1C:8E:2A:49:29:F6"

    @Test
    fun `无 root 前缀时以 system sh 开头`() {
        val command = BtLinkReleaseCommand.disconnect("", apk, mac)
        assertTrue(command.startsWith("/system/bin/sh -c '"))
        assertTrue(command.contains("CLASSPATH=$apk "))
    }

    @Test
    fun `root 前缀之后第一个词仍是 system sh`() {
        val command = BtLinkReleaseCommand.disconnect("su 0 ", apk, mac)
        assertEquals("su", command.substringBefore(' '))
        assertEquals("/system/bin/sh", command.split(' ')[2])
        assertTrue(command.contains("CLASSPATH=$apk /system/bin/app_process /system/bin "))
    }

    @Test
    fun `CLASSPATH 在 app_process 之前`() {
        val command = BtLinkReleaseCommand.disconnect("", apk, mac)
        val classpath = command.indexOf("CLASSPATH=")
        val appProcess = command.indexOf("/system/bin/app_process")
        assertTrue("CLASSPATH 必须先出现", classpath in 0 until appProcess)
    }

    @Test
    fun `主类 verb mac 顺序固定`() {
        val command = BtLinkReleaseCommand.disconnect("", apk, mac)
        val clazz = command.indexOf(BtLinkReleaseCommand.TOOL_CLASS)
        val verb = command.indexOf(" disconnect ")
        val address = command.indexOf(" $mac'")
        assertTrue(clazz in 0 until verb)
        assertTrue(verb in 0 until address)
    }

    @Test
    fun `probe 与 status 允许省略 mac`() {
        val probe = BtLinkReleaseCommand.probe("", apk, null)
        val status = BtLinkReleaseCommand.status("", apk, null)
        assertTrue(probe.endsWith(" probe'"))
        assertTrue(status.endsWith(" status'"))
        assertFalse(probe.contains("null"))
    }

    @Test
    fun `mac 与路径中的单引号被 POSIX 转义`() {
        val trickyApk = "/data/app/My App's/base.apk"
        val command = BtLinkReleaseCommand.disconnect("", trickyApk, mac)
        // ' → '\'' ；整个脚本仍被一对单引号包住。
        assertTrue(command.contains("My App'\\''s/base.apk"))
        assertTrue(command.endsWith(" $mac'"))
    }

    @Test
    fun `四种动作都带同样的工具类`() {
        listOf(
            BtLinkReleaseCommand.disconnect("", apk, mac),
            BtLinkReleaseCommand.forbid("", apk, mac),
            BtLinkReleaseCommand.allow("", apk, mac),
            BtLinkReleaseCommand.status("", apk, mac),
        ).forEach { command ->
            assertTrue(command.contains(BtLinkReleaseCommand.TOOL_CLASS))
        }
    }
}
