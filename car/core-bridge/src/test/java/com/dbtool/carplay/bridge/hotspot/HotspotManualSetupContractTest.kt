package com.dbtool.carplay.bridge.hotspot

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADB 初始化必须用户显式触发；开机不初始化 ADB、不恢复自建热点；热点按会话按需启动。
 */
class HotspotManualSetupContractTest {

    private fun readSource(relativeFromRepoRoot: String): String {
        // 单测 cwd 是模块目录（car/core-bridge），往上一跳到仓库根。
        val candidates = listOf(
            File("../../$relativeFromRepoRoot"),
            File("../$relativeFromRepoRoot"),
            File(relativeFromRepoRoot),
        )
        return candidates.firstOrNull { it.exists() }?.readText()
            ?: error("找不到源码文件：$relativeFromRepoRoot（cwd=${File(".").absolutePath}）")
    }

    @Test
    fun `App 启动路径不再自动预热热点或 ADB`() {
        val app = readSource(
            "car/app/src/main/java/com/dbtool/carplay/app/CarPlayApplication.kt",
        )
        assertFalse(
            "CarPlayApplication 不应再调用 prewarmHotspot()：按斌哥口径，热点由用户手动点建立",
            app.contains("prewarmHotspot()"),
        )
        assertFalse(
            "CarPlayApplication 不应再调用 prewarmAdb()：按斌哥口径，ADB 由用户手动点建立",
            app.contains("prewarmAdb()"),
        )
    }

    @Test
    fun `开机路径不再自动预热热点或 ADB`() {
        val boot = readSource(
            "car/app/src/main/java/com/dbtool/carplay/app/BootReceiver.kt",
        )
        assertFalse(
            "BootReceiver 不应再调用 prewarmHotspot()",
            boot.contains("prewarmHotspot()"),
        )
        assertFalse(
            "BootReceiver 不应再调用 prewarmAdb()",
            boot.contains("prewarmAdb()"),
        )
    }

    /** 新版只在 BOOT_COMPLETED 记录 ADB 快照；初始化/热点启动均由用户与会话显式触发。 */
    @Test
    fun `开机广播只读快照不得初始化ADB或恢复热点`() {
        val boot = readSource("car/app/src/main/java/com/dbtool/carplay/app/BootReceiver.kt")
            .replace(Regex("(?s)/\\*.*?\\*/"), " ")
            .replace(Regex("(?m)//.*$"), " ")
        assertTrue("仍需采集只读 5556 快照", boot.contains("adbPortSnapshot()"))
        assertFalse("开机不得调用旧 ADB+热点总初始化", boot.contains("setupHotspotAndAdbNow()"))
        assertFalse("开机不得调用 ADB 初始化", boot.contains("initializeAdbNow()"))
        assertFalse("开机不得启动自建热点", boot.contains("prewarmHotspot()"))
    }

    @Test
    fun `ADB初始化独立且不启动自建热点`() {
        val services = readSource("car/core-bridge/src/main/java/com/dbtool/carplay/bridge/CarAppServices.kt")
        val initialize = services.substringAfter("fun initializeAdbNow()", "").substringBefore("\n    }")
        assertTrue("必须存在用户触发的 5556 初始化入口", initialize.contains("adbSession.initializeDedicatedPort()"))
        assertFalse("初始化 ADB 不得启动自建热点", initialize.contains("adbHotspotManager.ensureStarted"))
        assertTrue("需记录用户曾执行初始化", initialize.contains("KEY_ADB_INIT_ATTEMPTED"))
        assertTrue("需记录初始化成功状态", initialize.contains("KEY_ADB_INIT_SUCCEEDED"))
        assertTrue("需记录持久属性实测状态", initialize.contains("KEY_ADB_PERSIST_VERIFIED"))
    }

    @Test
    fun `热点按ADB_HOSTAPD会话按需启动且总是停止`() {
        val session = readSource("car/core-bridge/src/main/java/com/dbtool/carplay/bridge/CarPlaySessionBridge.kt")
        assertTrue("仅 ADB_HOSTAPD 模式进入自建热点", session.contains("if (plan.mode != CarHotspotMode.ADB_HOSTAPD) return plan"))
        assertTrue("会话停止应始终收回自建热点", session.contains("if (settings.hotspotMode == CarHotspotMode.ADB_HOSTAPD) adbHotspot?.stop()"))
        val manager = readSource("car/core-bridge/src/main/java/com/dbtool/carplay/bridge/hotspot/CarAdbHotspotManager.kt")
        val configure = functionBody(manager, "private fun configure(")
        assertFalse("自建热点不安装开机 init 服务", configure.contains("installBootService("))
        assertFalse("自建热点启动过程不得反复改写 TCP ADB", configure.contains("ensureTcpAdb("))
    }

    /** Extract only the requested function body using balanced braces, not the next helper function. */
    private fun functionBody(source: String, signature: String): String {
        val code = source
            .replace(Regex("(?s)/\\*.*?\\*/"), " ")
            .replace(Regex("(?m)//.*$"), " ")
        val start = code.indexOf(signature)
        assertTrue("找不到函数：$signature", start >= 0)
        val open = code.indexOf('{', start)
        assertTrue("找不到函数体：$signature", open >= 0)
        var depth = 0
        for (index in open until code.length) {
            when (code[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(open + 1, index)
                }
            }
        }
        error("函数花括号未闭合：$signature")
    }
}
