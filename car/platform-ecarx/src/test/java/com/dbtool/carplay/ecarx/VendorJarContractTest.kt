package com.dbtool.carplay.ecarx

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * 厂商 jar 契约：**jar 必须与目标车 ROM 对得上**。
 *
 * 2026-10-20 的坑：编译期用的 `ecarx.adaptapi.jar` 里有 `wifiap.impl.Wifi6ApImpl`，
 * 而这台车的 ROM 里只有 `WifiApImp` ⇒ 运行时 `WifiAp.create()` 抛 ClassNotFoundException，
 * `vendor=null` 永远读不到。这条用例把「jar 里到底有哪些关键类」钉住，
 * 换 jar 时立刻能看出来。
 *
 * ⚠️ 这些 jar 是 **Android framework jar**：里面装的是 `classes.dex`，不是 `.class`，
 * 所以只能按 dex 里的类型描述符（`Lcom/…;`）检索，不能按 `.class` 文件名断言。
 */
class VendorJarContractTest {

    private fun jar(name: String): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val direct = File(dir, "libs/$name")
            if (direct.isFile) return direct
            val nested = File(dir, "car/platform-ecarx/libs/$name")
            if (nested.isFile) return nested
            dir = dir.parentFile
        }
        throw AssertionError("找不到 libs/$name")
    }

    /** 把所有 dex 字节拼起来，用于检索类型描述符。 */
    private fun dexBytes(name: String): ByteArray = ZipFile(jar(name)).use { z ->
        val chunks = z.entries().asSequence()
            .filter { it.name.endsWith(".dex") }
            .map { z.getInputStream(it).readBytes() }
            .toList()
        val total = chunks.sumOf { it.size }
        val out = ByteArray(total)
        var off = 0
        chunks.forEach { it.copyInto(out, off); off += it.size }
        out
    }

    private fun assertContains(bytes: ByteArray, descriptor: String) {
        val needle = descriptor.toByteArray(Charsets.UTF_8)
        var i = 0
        while (i + needle.size <= bytes.size) {
            var j = 0
            while (j < needle.size && bytes[i + j] == needle[j]) j++
            if (j == needle.size) return
            i++
        }
        throw AssertionError("dex 里找不到 $descriptor")
    }

    @Test
    fun `adaptapi jar 里有热点门面与实现类`() {
        val dex = dexBytes("ecarx.adaptapi.jar")
        listOf(
            "Lcom/ecarx/xui/adaptapi/wifiap/ExternalWifiAP;",
            "Lcom/ecarx/xui/adaptapi/wifiap/WifiAp;",
            "Lcom/ecarx/xui/adaptapi/wifiap/WifiApImp;",
        ).forEach { assertContains(dex, it) }
    }

    @Test
    fun `adaptapi jar 里有仪表互动接口`() {
        assertContains(
            dexBytes("ecarx.adaptapi.jar"),
            "Lcom/ecarx/xui/adaptapi/diminteraction/DimMenuInteraction;",
        )
    }

    @Test
    fun `jar 不是空的占位文件`() {
        listOf("ecarx.jar", "ecarx.car.jar", "ecarx.adaptapi.jar").forEach {
            assertTrue("$it 太小，可能是占位文件", jar(it).length() > 10_000)
        }
    }

    @Test
    fun `构建脚本用 compileOnly 引厂商 jar 并提供 simulateMode`() {
        var dir: File? = File(".").absoluteFile
        var script: File? = null
        while (dir != null) {
            val f = File(dir, "build.gradle.kts")
            if (f.isFile && f.readText().contains("ecarx.adaptapi.jar")) {
                script = f
                break
            }
            dir = dir.parentFile
        }
        assertTrue("找不到引用厂商 jar 的 build.gradle.kts", script != null)
        val text = script!!.readText()
        assertTrue("缺少 compileOnly(vendorJars)", text.contains("compileOnly(vendorJars)"))
        assertTrue("缺少 simulateMode 开关", text.contains("simulateMode"))
        assertTrue("simulateMode 分支里应改为 implementation", text.contains("implementation(vendorJars)"))
    }
}
