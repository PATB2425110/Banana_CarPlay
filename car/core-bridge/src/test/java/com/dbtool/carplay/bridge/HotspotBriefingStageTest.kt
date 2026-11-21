package com.dbtool.carplay.bridge

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * PATCH-0135（L1-5）接线契约：热点简报的「探测段」必须按模式裁剪，且必须留分段耗时。
 *
 * 为什么钉：实测同一套代码上，简报单次成本 CS11 为 2–3.5s、EX11 约 4s；
 * 自建档里那份原厂探测结果最终被丢弃，却要先花几秒去拿。一旦有人把
 * `if (reusesCarNativeHotspot)` 去掉，成本会静默回到原样，日志也看不出来。
 */
class HotspotBriefingStageTest {

    @Test
    fun `自建档不读原厂探测缓存`() {
        val body = briefingBody()
        val probeBlock = body.substringAfter("val nativeInfo =")
            .substringBefore("val afterProbe")
        assertTrue(
            "nativeInfo 必须先判模式：$probeBlock",
            probeBlock.contains("readsAppleCarPlayHotspotCredentials"),
        )
        assertTrue(
            "自建档分支应直接给 null，不触发 cachedInfo（几秒级 ADB 探测）",
            probeBlock.contains("else") && probeBlock.contains("null"),
        )
    }

    @Test
    fun `简报留分段耗时日志`() {
        val body = briefingBody()
        assertTrue(
            "缺分段耗时日志：下次诊断包又只能看到「简报慢」，不知慢在哪段",
            body.contains("热点简报分段") && body.contains("probe=") && body.contains("resolve="),
        )
    }

    private fun briefingBody(): String {
        val src = stripComments(
            sourceOf("car/core-bridge/src/main/java/com/dbtool/carplay/bridge/CarAppServices.kt"),
        )
        return src.substringAfter("fun hotspotBriefing(settings: CarSessionSettings)")
            .substringBefore("private fun manualSsidWarningOf(")
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
