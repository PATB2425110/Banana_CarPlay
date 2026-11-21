package com.dbtool.carplay.bridge

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * PATCH-0133 / 0134 的接线契约（源码级）。
 *
 * 为什么用源码断言：`CarPlayConfigBuilder` 与 `CarPlaySessionBridge` 都要真实 Android 环境
 * 才跑得起来，纯 JVM 测不了行为。但「早停所需客户端数有没有接上」这件事，
 * 一旦被人删掉，表现是**静默退回跑满 3 轮**（实测 26.7s），日志里看不出来 —— 所以钉死。
 *
 * 三条不变量：
 * 1. 复用档（MANUAL 复用 + TCAM）都要拿到 `hotspotClientCountProvider`；
 * 2. TCAM 路径必须真的 `start()` 观测器，否则 provider 永远读到 null；
 * 3. 观测器只能是只读读数（不得引入 shell/ip 调用）。
 */
class HotspotClientCountWiringTest {

    @Test
    fun `只有实际自建AP跳过等待 原厂路由模式不得借配置校验开关跳过`() {
        val source = sourceOf("car/core-bridge/src/main/java/com/dbtool/carplay/bridge/CarPlayConfigBuilder.kt")
        assertTrue(source.contains("manualHotspotSkipClientSettle = hotspot.mode == CarHotspotMode.ADB_HOSTAPD"))
    }

    @Test
    fun `配置层为 MANUAL 复用与 TCAM 两档都提供客户端数`() {
        val src = stripComments(sourceOf(
            "car/core-bridge/src/main/java/com/dbtool/carplay/bridge/CarPlayConfigBuilder.kt",
        ))
        val block = src.substringAfter("hotspotClientCountProvider = if (")
            .substringBefore("else {")
        assertTrue(
            "MANUAL 复用分支丢了：$block",
            block.contains("CarHotspotMode.MANUAL && hotspot.reused"),
        )
        assertTrue(
            "TCAM 分支丢了 ⇒ TCAM 档的 RFCOMM 早停永远拿不到客户端数（会跑满 3 轮）",
            block.contains("CarHotspotMode.TCAM_HOTSPOT"),
        )
        assertTrue(
            "provider 必须只读观测器快照（recentEvidence），不许自己发命令",
            block.contains("CarHotspotClientWatcher.recentEvidence()"),
        )
    }

    @Test
    fun `TCAM 路径启动只读观测器`() {
        val src = stripComments(sourceOf(
            "car/core-bridge/src/main/java/com/dbtool/carplay/bridge/CarPlaySessionBridge.kt",
        ))
        val tcam = src.substringAfter("private fun ensureTcamHotspotReady(")
            .substringBefore("return plan.copy(")
        assertTrue(
            "ensureTcamHotspotReady 没有启动 CarHotspotClientWatcher ⇒ 客户端数永远是 null",
            tcam.contains("CarHotspotClientWatcher.start("),
        )
    }

    @Test
    fun `观测器不引入新的 shell 调用`() {
        val src = stripComments(sourceOf(
            "car/core-bridge/src/main/java/com/dbtool/carplay/bridge/hotspot/CarHotspotClientWatcher.kt",
        ))
        listOf("ProcessBuilder", "Runtime.getRuntime", "adb shell", "ip route").forEach { needle ->
            assertTrue(
                "观测器里出现了 $needle —— 早停必须是只读快照，不能新增调用",
                !src.contains(needle),
            )
        }
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
