package com.dbtool.carplay.bridge

import com.dbtool.carplay.api.settings.CarSessionSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 调试反连隧道设置契约（2026-10-17 · PATCH-0130）。
 *
 * ## 背景（现场踩到的两个真问题）
 *
 * 1. **口令 11 位被跳板拒**：跳板 `relay.py` 弱校验模式 `MIN_SECRET_LEN = 12`，
 *    旧默认口令 `dbplay-2026` 恰好 11 位 ⇒ 拨号即被判「口令不匹配」。
 * 2. **隧道 5 个字段根本没过 SharedPreferences**：改完地址/口令，重启回默认；
 *    开关拨上去只是内存态，刷新模型后读回 `false`。
 *
 * ## 测法
 *
 * 与 [SettingsDefaultMigrationTest] 同口径：`SharedPreferencesCarSettingsStore`
 * 在 JVM 里跑不起来，所以只钉两件事 —— 默认值本身、以及源码里的写法契约
 * （键齐全 / load 读 / save 写 / 迁移有独立标记且只改旧默认值）。
 */
class DebugTunnelSettingsContractTest {

    @Test
    fun `出厂默认口令满足跳板最短长度要求`() {
        val secret = CarSessionSettings.default().debugTunnelSecret
        assertTrue(
            "默认口令只有 ${secret.length} 位，跳板弱校验要求 >= 12 位 —— 拨号必被拒",
            secret.length >= 12,
        )
    }

    @Test
    fun `出厂默认口令不再是上一版的 11 位值`() {
        assertFalse(
            "默认口令仍是旧的 11 位 dbplay-2026",
            CarSessionSettings.default().debugTunnelSecret == "dbplay-2026",
        )
    }

    @Test
    fun `五个隧道字段都有独立持久化键`() {
        val source = storeSource()
        val keys = listOf(
            "KEY_DEBUG_TUNNEL_ENABLED",
            "KEY_DEBUG_TUNNEL_HOST",
            "KEY_DEBUG_TUNNEL_PORT",
            "KEY_DEBUG_TUNNEL_LOCAL_ADB_PORT",
            "KEY_DEBUG_TUNNEL_SECRET",
        )
        keys.forEach { key ->
            assertTrue("存储层缺键常量 $key", source.contains("private const val $key"))
        }
    }

    @Test
    fun `load 读回五个隧道字段`() {
        val source = storeSource()
        listOf(
            "debugTunnelEnabled =",
            "debugTunnelHost =",
            "debugTunnelPort =",
            "debugTunnelLocalAdbPort =",
            "debugTunnelSecret =",
        ).forEach { field ->
            assertTrue("load() 里没有读回 $field —— 重启即丢，用户改的设置等于白设", source.contains(field))
        }
    }

    @Test
    fun `save 写全五个隧道字段`() {
        val source = storeSource()
        listOf(
            "putBoolean(KEY_DEBUG_TUNNEL_ENABLED",
            "putString(KEY_DEBUG_TUNNEL_HOST",
            "putInt(KEY_DEBUG_TUNNEL_PORT",
            "putInt(KEY_DEBUG_TUNNEL_LOCAL_ADB_PORT",
            "putString(KEY_DEBUG_TUNNEL_SECRET",
        ).forEach { call ->
            assertTrue("save() 里缺 $call —— 老安装永远写不进隧道配置", source.contains(call))
        }
    }

    @Test
    fun `口令迁移有独立标记且在 load 里被调用`() {
        val source = storeSource()
        assertTrue(
            "缺一次性标记 KEY_DEBUG_TUNNEL_SECRET_MIGRATED",
            source.contains("KEY_DEBUG_TUNNEL_SECRET_MIGRATED"),
        )
        assertTrue(
            "load() 没有调用 migrateDebugTunnelSecretToTwelveChars",
            source.contains("migrateDebugTunnelSecretToTwelveChars(d)"),
        )
        val body = source.substringAfter("private fun migrateDebugTunnelSecretToTwelveChars")
            .substringBefore("\n    private fun")
        assertTrue("迁移开头没有按标记短路", body.contains("KEY_DEBUG_TUNNEL_SECRET_MIGRATED, false)) return"))
        assertTrue(
            "判据必须是上一版写进 prefs 的旧默认口令（裸字符串）",
            body.contains("\"dbplay-2026\""),
        )
        assertTrue("迁移结尾要把标记置位", body.contains("KEY_DEBUG_TUNNEL_SECRET_MIGRATED, true"))
    }

    @Test
    fun `applySettings 拨开关后立即建链`() {
        val raw = sourceOf(
            "car/app/src/main/java/com/dbtool/carplay/app/CarAppController.kt",
        )
        val body = raw.substringAfter("override fun applySettings(")
            .substringBefore("\n    override fun clearPairings(")
        assertTrue(
            "applySettings 里没有比较 debugTunnelEnabled 的新旧值 —— 拨开关又变回「只落盘不拨号」",
            body.contains("settings.debugTunnelEnabled != previousDebugTunnelEnabled"),
        )
        assertTrue(
            "applySettings 里没有调用 startDebugTunnelIfNeeded —— 开关拨上去不会建链",
            body.contains("startDebugTunnelIfNeeded(settings)"),
        )
    }

    private fun storeSource(): String = stripComments(
        sourceOf(
            "car/core-bridge/src/main/java/com/dbtool/carplay/bridge/" +
                "SharedPreferencesCarSettingsStore.kt",
        ),
    )

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
