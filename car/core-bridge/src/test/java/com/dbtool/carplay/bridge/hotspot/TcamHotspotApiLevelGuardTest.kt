package com.dbtool.carplay.bridge.hotspot

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 契约测试：TCAM 复用的源码里**不得直调 API 30+ 的 `NetworkRequest` 清理方法**。
 *
 * ## 为什么要有它
 *
 * 本机 `minSdk = 28`（目标车机 Android 9 / SDK 28），而
 * `NetworkRequest.Builder#clearCapabilities()` / `#clearTransportTypes()` 是
 * **API 30（Android 11）** 才有的方法：
 * - `compileDebugKotlin` / 单测**都发现不了**（编译期不报错、JVM 测试不碰真机 framework）；
 * - 只有 `assembleRelease` 的 `lintVitalRelease` 会拦下（2026-10-16 实测：
 *   `TcamHotspotManager.kt:141 NewApi: Call requires API level 30 (current min is 28)`）；
 * - 真机上运行时必抛 `NoSuchMethodError`。
 *
 * 处置是**改代码**（新建的 Builder 本就没有任何 capability，清理行冗余），
 * 不是关闸门 / 建 baseline。本测试把结论钉死，防回归。
 */
class TcamHotspotApiLevelGuardTest {

    private val manager = codeOnly(
        sourceOf("car/core-bridge/src/main/java/com/dbtool/carplay/bridge/hotspot/TcamHotspotManager.kt"),
    )

    @Test
    fun `TCAM 复用源码不得直调 API 30 的 NetworkRequest 清理方法`() {
        listOf("clearCapabilities()", "clearTransportTypes()", "clearAllCapabilities()").forEach { api ->
            assertFalse(
                "TcamHotspotManager 出现 $api —— 它是 API 30（Android 11），" +
                    "minSdk=28 的车机上调用必 NoSuchMethodError",
                manager.contains(api),
            )
        }
    }

    @Test
    fun `NetworkRequest 只要求以太网传输且不清理 capability`() {
        val idx = manager.indexOf("NetworkRequest.Builder()")
        assertTrue("找不到 NetworkRequest.Builder() 使用点", idx >= 0)
        val snippet = manager.substring(idx, minOf(manager.length, idx + 200))
        assertTrue(
            "TCAM 复用必须只匹配以太网传输（TRANSPORT_ETHERNET）",
            snippet.contains("addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)"),
        )
        assertFalse(
            "新建的 Builder 没有任何 capability，不得再做清理调用",
            snippet.contains("clear"),
        )
    }

    /** 去掉 `//`、`/* */`、`*` 注释行——注释里可以提到禁用 API，不算直调。 */
    private fun codeOnly(source: String): String = source
        .lines()
        .filterNot { line ->
            val t = line.trim()
            t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
        }
        .joinToString("\n")

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
