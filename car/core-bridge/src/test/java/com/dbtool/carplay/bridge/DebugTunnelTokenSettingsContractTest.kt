package com.dbtool.carplay.bridge

import com.dbtool.carplay.api.settings.CarSessionSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 隧道 VIN 身份 token 的设置契约（2026-10-17，P1）。
 *
 * `SharedPreferencesCarSettingsStore` 依赖 Android SharedPreferences，JVM 单测里
 * 跑不起来，所以与既有 `DebugTunnelSettingsContractTest` 同口径：钉默认值和
 * 存储层源码契约。新键必须与老口令键分开，避免把 token 当口令覆盖。
 */
class DebugTunnelTokenSettingsContractTest {

    @Test
    fun `debugTunnelToken 默认空串`() {
        assertEquals("", CarSessionSettings.default().debugTunnelToken)
    }

    @Test
    fun `load 与 save 都使用独立的新键`() {
        val source = storeSource()
        assertTrue(
            "存储层缺键常量 KEY_DEBUG_TUNNEL_TOKEN",
            source.contains("private const val KEY_DEBUG_TUNNEL_TOKEN = \"debug_tunnel_token\""),
        )
        val load = source.substringAfter("override fun load(")
            .substringBefore("override fun save(")
        assertTrue(
            "load() 没有读回 debugTunnelToken",
            load.contains("debugTunnelToken = prefs.getString(KEY_DEBUG_TUNNEL_TOKEN"),
        )
        val save = source.substringAfter("override fun save(")
            .substringBefore("private fun migrateDebugTunnelSecretToTwelveChars")
        assertTrue(
            "save() 没有写入 debugTunnelToken",
            save.contains("putString(KEY_DEBUG_TUNNEL_TOKEN, settings.debugTunnelToken)"),
        )
    }

    @Test
    fun `token 键名与口令键名不同`() {
        val source = storeSource()
        assertTrue(
            "口令键必须是 debug_tunnel_secret",
            source.contains("KEY_DEBUG_TUNNEL_SECRET = \"debug_tunnel_secret\""),
        )
        assertFalse(
            "token 不能复用 debug_tunnel_secret",
            source.contains("KEY_DEBUG_TUNNEL_TOKEN = \"debug_tunnel_secret\""),
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
