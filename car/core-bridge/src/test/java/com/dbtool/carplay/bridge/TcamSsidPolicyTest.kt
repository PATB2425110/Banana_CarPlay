package com.dbtool.carplay.bridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * PATCH-0136 接线契约：复用原厂热点**不许**用设置里保存的 SSID/密码去校验，
 * 且 attach 前要清掉自建残留。
 *
 * 现场（VIN 尾号 368562 / 1.1.14）：模式已是 TCAM，但 `manualHotspotSsid` 还是自建档的
 * `DBPlay` ⇒ `TcamHotspotManager` 抛 `Saved hotspot name differs from the active TCAM hotspot`，
 * 一次会话都没起；同一份快照里 `wlan0` 还挂着 `192.168.175.1`。
 *
 * 这两条一旦被改回去，表现都是**静默连不上**（日志只有一行 IOException），所以钉死。
 */
class TcamSsidPolicyTest {

    @Test
    fun `复用档方案不再声明 ssid`() {
        val body = stripComments(
            sourceOf("car/core-bridge/src/main/java/com/dbtool/carplay/bridge/network/CarHotspotResolver.kt"),
        ).substringAfter("private fun tcam(settings: CarSessionSettings)")
            // 2026-10-25：`geelyRouted()`（车机热点）插在 tcam() 之后、securityForSettings 之前，
            // 会被原来的区间一起吃进来 —— 而那一档**必须**声明用户手填的 ssid，
            // 与 PATCH-0136「TCAM 不声明名字」并不矛盾。区间收到 geelyRouted 之前。
            .substringBefore("private fun geelyRouted")

        assertTrue("tcam() 里 ssid 必须传 null（名字由车机现场决定）", body.contains("ssid = null"))
        assertFalse(
            "tcam() 里又出现了 manualHotspotSsid —— 切档后会拿自建档的名字去校验原厂热点",
            body.contains("manualHotspotSsid"),
        )
    }

    @Test
    fun `TCAM 管理器构造不传保存的 ssid`() {
        val body = stripComments(
            sourceOf("car/core-bridge/src/main/java/com/dbtool/carplay/bridge/CarPlaySessionBridge.kt"),
        ).substringAfter("private fun ensureTcamHotspotReady(")
            .substringBefore("val info = try {")

        assertTrue("savedSsid 必须为 null", body.contains("savedSsid = null"))
        assertFalse("不许再把 plan.ssid 传给管理器", body.contains("savedSsid = plan.ssid"))
    }

    @Test
    fun `attach 前清自建残留`() {
        val body = stripComments(
            sourceOf("car/core-bridge/src/main/java/com/dbtool/carplay/bridge/CarPlaySessionBridge.kt"),
        ).substringAfter("private fun ensureTcamHotspotReady(")
            .substringBefore("val info = try {")

        assertTrue(
            "缺自建残留清理：wlan0 上的 192.168.175.1 会被当成原厂热点地址通告出去",
            body.contains("releaseLeftoverAddress"),
        )
        assertTrue(
            "清理要根据「我们自己配过的地址」判断，不能无条件拆别人的热点",
            body.contains("adbHotspot?.state()?.hostAddress"),
        )
    }

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
