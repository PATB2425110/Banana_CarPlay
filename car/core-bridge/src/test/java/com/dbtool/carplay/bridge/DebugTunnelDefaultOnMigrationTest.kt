package com.dbtool.carplay.bridge

import com.dbtool.carplay.api.settings.CarSessionSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「调试隧道开关默认 `false` → `true`」的契约（2026-10-19）。
 *
 * ## 口径
 *
 * 斌哥要求：默认开启，方便后台随时看设备在线直接远程调试。
 * 存量安装只要保存过一次设置，`debug_tunnel_enabled` 就被 `save()` 写成了旧默认 `false`
 * ⇒ 只改 data class 默认值对它们完全无效，必须配一次性迁移「强制开一次」。
 * 用户之后手动关掉，标记已置位，永久保持关闭。
 *
 * ## 为什么是源码级断言
 *
 * [SharedPreferencesCarSettingsStore] 构造需要 `Context` + 真 `SharedPreferences`，
 * 纯 JVM 跑不起来。所以这里钉两件能在 JVM 里确证的事：
 * **出厂默认值**（纯 Kotlin）+ **迁移的写法契约**（源码级，剥注释后正则）。
 */
class DebugTunnelDefaultOnMigrationTest {

    // ── 1. 出厂默认 ─────────────────────────────────────────────────────────

    @Test
    fun `出厂默认开启调试隧道`() {
        assertTrue(
            "debugTunnelEnabled 的出厂默认必须是 true",
            CarSessionSettings.default().debugTunnelEnabled,
        )
    }

    // ── 2. 迁移写法契约 ─────────────────────────────────────────────────────

    @Test
    fun `load 里调用了默认开启迁移`() {
        val load = loadBody()
        assertTrue(
            "load() 未调用 migrateDebugTunnelEnabledDefaultOn()",
            load.contains("migrateDebugTunnelEnabledDefaultOn()"),
        )
    }

    @Test
    fun `迁移标记是独立键且不等于被迁移的键`() {
        val code = codeOf()
        val markers = Regex("""(KEY_[A-Z0-9_]*MIGRATED)\s*=\s*"([^"]+)"""")
            .findAll(code)
            .associate { it.groupValues[1] to it.groupValues[2] }
        val enabledKey = Regex("""KEY_DEBUG_TUNNEL_ENABLED\s*=\s*"([^"]+)"""")
            .find(code)?.groupValues?.get(1)

        assertTrue("缺少标记常量 KEY_DEBUG_TUNNEL_ENABLED_MIGRATED", "KEY_DEBUG_TUNNEL_ENABLED_MIGRATED" in markers)
        assertTrue("缺少被迁移键 KEY_DEBUG_TUNNEL_ENABLED", enabledKey != null)
        assertNotEquals(
            "标记不能复用被迁移的键本身",
            enabledKey,
            markers["KEY_DEBUG_TUNNEL_ENABLED_MIGRATED"],
        )
        val dup = markers.values.groupBy { it }.filterValues { it.size > 1 }
        assertTrue("迁移标记出现重复字符串：$dup", dup.isEmpty())
    }

    @Test
    fun `迁移函数按铁律写`() {
        val body = functionBody("migrateDebugTunnelEnabledDefaultOn")
        assertTrue("缺少按标记短路", body.contains("if (prefs.getBoolean(KEY_DEBUG_TUNNEL_ENABLED_MIGRATED, false)) return"))
        assertTrue("缺少「键真的存在才写」判断", body.contains("prefs.contains(KEY_DEBUG_TUNNEL_ENABLED)"))
        assertTrue("没有写入 true", Regex("""putBoolean\(KEY_DEBUG_TUNNEL_ENABLED,\s*true\)""").containsMatchIn(body))
        assertTrue(
            "结尾没有置位标记",
            Regex("""putBoolean\(KEY_DEBUG_TUNNEL_ENABLED_MIGRATED,\s*true\)\.apply\(\)""").containsMatchIn(body),
        )
    }

    @Test
    fun `迁移不会在注释里假装实现`() {
        // 剥注释后的代码里必须真的存在函数体；防止只写了 KDoc 没写实现。
        val code = codeOf()
        assertFalse("函数声明在剥注释后消失了", code.contains("<!--"))
        assertTrue(code.contains("private fun migrateDebugTunnelEnabledDefaultOn()"))
    }

    // ── 工具 ────────────────────────────────────────────────────────────────

    private fun loadBody(): String {
        val code = codeOf()
        val start = code.indexOf("override fun load()")
        assertTrue("找不到 load()", start >= 0)
        val end = code.indexOf("return CarSessionSettings(", start)
        assertTrue("找不到 load() 的 return", end > start)
        return code.substring(start, end)
    }

    private fun functionBody(name: String): String {
        val code = codeOf()
        val start = code.indexOf("private fun $name(")
        assertTrue("找不到函数 $name", start >= 0)
        val rest = code.substring(start + 1)
        val next = rest.indexOf("\n    private fun ")
        return if (next >= 0) rest.substring(0, next) else rest
    }

    /** 读 store 源码并剥掉注释（KDoc 里会引用字面量，不剥会假通过）。 */
    private fun codeOf(): String = sourceOf(
        "car/core-bridge/src/main/java/com/dbtool/carplay/bridge/" +
            "SharedPreferencesCarSettingsStore.kt",
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
