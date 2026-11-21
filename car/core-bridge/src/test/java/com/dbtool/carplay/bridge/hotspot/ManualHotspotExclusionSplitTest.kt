package com.dbtool.carplay.bridge.hotspot

import java.io.File
import com.dbtool.carplay.api.hotspot.CarManualEndpointGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「用户填的地址」与「按名字猜 AP」两条路的网卡排除表**必须分开**（2026-10-12，日志 4 排查）。
 *
 * ## 为什么钉在测试里
 *
 * 上游 `ManualHotspotManager` 里原先只有一张 [排除表]，同时被两条路用：
 *
 * - `findInterfaceCarryingAddress()` —— **用户填了地址**，按地址找网卡；
 * - `isUsableInterface()` / `findLocalHotspotInterface()` —— 我们按名字**猜**哪张是 AP。
 *
 * 而 `eth` 是 2026-10-03（提交 `5f1c43e`，修「无线通道就绪中」卡死）为后者加的。
 * 日志 4 证明它把前者一起挡死了：本机承载 `198.18.34.15` 的网卡全是 `eth*`，
 * 于是预检说通过、上游每次返回 null、60 秒后 `local hotspot interface was not found`。
 *
 * ## 分工（本测试钉的就是这个语义）
 *
 * | 表 | 谁用 | 含 `eth`/`usb` |
 * | --- | --- | --- |
 * | `EXCLUDED_INTERFACE_PREFIXES` | 两条路的地基（回环/隧道/bond 等） | **不含** |
 * | `GUESS_EXCLUDED_INTERFACE_PREFIXES` | **只有"猜"的那条路** | 含 |
 *
 * ⚠️ 断言一律走 [codeOf]（**先剥注释**）：这些表的 KDoc 里**必然**出现 `eth`、`usb`
 * 这些字面量（解释为什么拆），不剥离就会匹配到注释 ⇒ 假通过。
 */
class ManualHotspotExclusionSplitTest {

    private fun sourceOf(relative: String): String {
        val candidates = listOf(
            File(relative),
            File("../$relative"),
            File("../../$relative"),
            File("../../../$relative"),
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("找不到源码文件 $relative（从 ${File(".").absolutePath} 起找过 ${candidates.size} 个位置）")
        return file.readText()
    }

    private fun codeOf(relative: String): String =
        sourceOf(relative)
            .replace(Regex("""/\*[\s\S]*?\*/"""), "")
            .replace(Regex("""//[^\n]*"""), "")

    private val manager =
        "shared/src/main/java/com/shilapi/xcertplay/network/ManualHotspotManager.kt"

    /** 常量声明块（`val X = listOf(` 到与之配平的 `)`）。 */
    private fun constantBody(code: String, name: String): String {
        val start = code.indexOf("val $name = listOf(")
        assertTrue("常量 $name 不见了 —— 拆分被回退了？", start >= 0)
        val open = code.indexOf('(', start)
        var depth = 0
        var i = open
        while (i < code.length) {
            val c = code[i]
            if (c == '(') depth++
            else if (c == ')') {
                depth--
                if (depth == 0) return code.substring(open, i)
            }
            i++
        }
        throw IllegalStateException("$name 的括号不平衡")
    }

    /**
     * 函数体（从签名后第一个 `{` 到与之配平的 `}`）。
     *
     * ⚠️ 不能用 `substringBefore("}")`：函数体里的 `.none { … }` 自带一对花括号，
     * 一刀切会把常量引用切在区间外 ⇒ 断言永远不成立（假红）。
     */
    private fun functionBody(code: String, signature: String): String {
        val start = code.indexOf(signature)
        assertTrue("找不到函数签名：$signature", start >= 0)
        val open = code.indexOf('{', start)
        assertTrue("$signature 后面没有函数体", open >= 0)
        var depth = 0
        var i = open
        while (i < code.length) {
            val c = code[i]
            if (c == '{') depth++
            else if (c == '}') {
                depth--
                if (depth == 0) return code.substring(open, i + 1)
            }
            i++
        }
        throw IllegalStateException("$signature 的函数体括号不平衡")
    }

    /** 某函数体里是否以 `TABLE.none { … }` 的形式引用了该表。 */
    private fun uses(code: String, name: String, signature: String): Boolean =
        functionBody(code, signature).contains("$name.none")

    @Test
    fun 通用排除表不再含有线口() {
        val body = constantBody(codeOf(manager), "EXCLUDED_INTERFACE_PREFIXES")
        // 🔴 这是本次修复的核心：一旦 `eth` 回到这张表，"用户填地址"那条路又死。
        assertFalse("EXCLUDED_INTERFACE_PREFIXES 又含有线口了", body.contains("\"eth\""))
        assertFalse("EXCLUDED_INTERFACE_PREFIXES 又含 usb 了", body.contains("\"usb\""))
        // 地基项一个都不能少（回环/隧道/bond…）。
        listOf("lo", "dummy", "rmnet", "tun", "ppp", "sit", "ip6", "bond").forEach { prefix ->
            assertTrue("通用排除表少了 $prefix", body.contains("\"$prefix\""))
        }
    }

    @Test
    fun 猜测专用排除表含有线口() {
        val body = constantBody(codeOf(manager), "GUESS_EXCLUDED_INTERFACE_PREFIXES")
        // 少了它就是重演 2026-10-03：没有 AP 在跑时按名字猜到 eth0.3。
        assertTrue("GUESS 排除表没有 eth", body.contains("\"eth\""))
        assertTrue("GUESS 排除表没有 usb", body.contains("\"usb\""))
    }

    @Test
    fun 猜测路径用的是猜测专用表() {
        val code = codeOf(manager)
        val guessRegion = "private fun isUsableInterface("
        assertTrue("isUsableInterface 不在用 GUESS 表 —— 按名字猜又会抓到有线口", uses(code, "GUESS_EXCLUDED_INTERFACE_PREFIXES", guessRegion))
    }

    @Test
    fun 用户填地址的路径不排有线口() {
        val code = codeOf(manager)
        val lookupRegion = "private fun findInterfaceCarryingAddress("
        assertTrue(
            "findInterfaceCarryingAddress 没在用通用排除表",
            uses(code, "EXCLUDED_INTERFACE_PREFIXES", lookupRegion),
        )
        assertFalse(
            "findInterfaceCarryingAddress 用回了 GUESS 表 —— 日志 4 那个 60 秒死等会复现",
            uses(code, "GUESS_EXCLUDED_INTERFACE_PREFIXES", lookupRegion),
        )
    }

    @Test
    fun 严格路径仍然不做任何前缀排除() {
        // Ecarx VERIFIED 走 `findInterfaceCarryingVerifiedAddress` → `selectStrict`，
        // 那条路本来就是"iface+IP 精确匹配"。若哪天它也被加上前缀排除，
        // 日志 4 里那 3 次成功的 `backend=…iface=eth0.11` 就没了。
        val code = codeOf(manager)
        val region = code.substringAfter("private fun findInterfaceCarryingVerifiedAddress(")
            .substringBefore("private fun isUsableInterface(")
        assertFalse("严格路径出现了前缀排除", region.contains("EXCLUDED_INTERFACE_PREFIXES"))
    }

    @Test
    fun 两侧的有线口前缀一致() {
        // `:car:api` 的闸门与 `:shared` 的猜测表若不一致，就会出现
        // "我们这边判 WIRED_ONLY 拦掉、上游却认得出来"或反之的自相矛盾。
        val guess = constantBody(codeOf(manager), "GUESS_EXCLUDED_INTERFACE_PREFIXES")
        CarManualEndpointGate.WIRED_INTERFACE_PREFIXES.forEach { prefix ->
            assertTrue("上游 GUESS 表少了 $prefix", guess.contains("\"$prefix\""))
        }
        assertEquals(2, CarManualEndpointGate.WIRED_INTERFACE_PREFIXES.size)
    }
}
