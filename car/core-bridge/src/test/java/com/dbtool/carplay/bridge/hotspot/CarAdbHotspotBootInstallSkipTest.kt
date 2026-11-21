package com.dbtool.carplay.bridge.hotspot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「开机自启服务」这一步的结果记录契约。
 *
 * `/system` 挂不成 rw 时安装被**主动跳过**、没有改动任何系统文件，热点照开。
 * 这属于预期内的跳过：若记成失败，它每次都会成为 `firstFailure`，
 * 掩盖真正卡住的那一步（失败日志与自检页都按 `firstFailure` 定位）。
 *
 * `CarAdbHotspotManager` 需要 Context，本模块没有 Robolectric，所以这里读源码断言。
 */
class CarAdbHotspotBootInstallSkipTest {

    private val source: String = run {
        val rel = "src/main/java/com/dbtool/carplay/bridge/hotspot/CarAdbHotspotManager.kt"
        listOf(java.io.File(rel), java.io.File("car/core-bridge/$rel"), java.io.File("../core-bridge/$rel"))
            .firstOrNull { it.exists() }?.readText() ?: error("找不到 $rel")
    }

    private fun branchAfter(marker: String): String {
        val start = source.indexOf(marker)
        assertTrue("找不到分支 $marker", start >= 0)
        return source.substring(start, minOf(source.length, start + 400))
    }

    @Test
    fun `SYSTEM_NOT_RW 跳过按成功记录`() {
        val branch = branchAfter("output.contains(\"SYSTEM_NOT_RW\")")
        assertTrue("跳过安装应记为退出码 0", Regex("exitCode\\s*=\\s*0").containsMatchIn(branch.substringBefore("INSTALL_OK")))
        assertTrue("仍需保留 dm-verity 说明供排障", branch.contains("已跳过安装、未修改任何系统文件"))
    }

    @Test
    fun `未见 INSTALL_OK 仍按原退出码失败`() {
        val branch = branchAfter("安装未确认成功").let {
            val s = source.indexOf("else -> AdbShellRunner.CommandResult(")
            source.substring(s, minOf(source.length, s + 200))
        }
        assertEquals(true, branch.contains("exitCode = result.exitCode"))
    }
}
