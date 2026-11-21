package com.dbtool.carplay.bridge.hotspot

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 契约测试：热点**已在运行**时必须被复用，不得重跑整套拉起。
 *
 * 背景（2026-10-05，实车日志 `1791168771616_eff7915a_d5ddb4312da1`）：
 * 斌哥反馈「保存设置之后重新连会在**无线通道就绪中**连接很久」。
 * 日志实锤：每次会话启动都完整重跑 18 步热点拉起，白等 5~13 秒
 * （`adb hotspot STARTING_DHCP` 那一段一次就 8.5 秒）。
 *
 * 根因是 `CarAdbHotspotManager.ensureStarted` 的复用守卫写成
 * `if (running.get() && snapshot.isRunning) return true`——`running` 只是
 * 「此刻有一次拉起在跑」的瞬时标志，`runBringUp` 返回后 `finally` 就清了它，
 * 于是守卫**恒为假**，热点明明在跑却每次重跑。
 *
 * 这个测试**静态读源码**（不反射、不启动 manager），把守卫的形态钉死：
 * 一定只按 `snapshot.isRunning` 短路，绝不再把 `running` 与进守卫。
 * 这样将来谁再改回去，测试立刻红。
 */
class HotspotReuseContractTest {

    private fun sourceOf(relative: String): String {
        // 单测 cwd 是模块目录（car/core-bridge），源码在同仓库的相对路径下。
        val candidates = listOf(
            File(relative),
            File("../$relative"),
            File("../../$relative"),
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("找不到源码文件 $relative（从 ${File(".").absolutePath} 起找过 ${candidates.size} 个位置）")
        return file.readText()
    }

    private val managerSrc =
        "src/main/java/com/dbtool/carplay/bridge/hotspot/CarAdbHotspotManager.kt"

    /** 截出 `ensureStarted` 的函数体（到下一个函数 `selfCheck` 之前）。 */
    private fun ensureStartedGuard(src: String): String {
        val body = src.substringAfter("override fun ensureStarted(", "")
        assertTrue("ensureStarted 不见了——签名或所在文件变了，本契约需同步更新", body.isNotEmpty())
        return body.substringBefore("override fun selfCheck")
    }

    @Test
    fun `复用守卫只看 snapshot isRunning`() {
        val guard = ensureStartedGuard(sourceOf(managerSrc))

        assertTrue(
            "复用守卫必须以 `if (snapshot.isRunning) return true` 短路；实际未找到。\n" +
                "这正是一处会导致「热点已运行仍重跑、白等十几秒」的回归点。",
            guard.contains("if (snapshot.isRunning) return true"),
        )
        assertFalse(
            "守卫里又出现了 `running.get() && snapshot.isRunning`：`running` 在拉起结束后即为 false，" +
                "该条件恒为假，会让热点复用彻底失效（2026-10-05 已修，勿回退）。",
            Regex("""if\s*\(\s*running\.get\(\)\s*&&\s*snapshot\.isRunning\s*\)""").containsMatchIn(guard),
        )
    }

    @Test
    fun `并发拉起互斥与等待仍在`() {
        val guard = ensureStartedGuard(sourceOf(managerSrc))

        assertTrue(
            "并发互斥丢失：两条线程同时 ifconfig/hostapd 会互相破坏，必须保留 compareAndSet。",
            guard.contains("compareAndSet(false, true)"),
        )
        assertTrue(
            "并发等待丢失：已在拉起中时应 waitForRunning() 等它，而不是并发再跑一遍。",
            guard.contains("waitForRunning()"),
        )
    }

    /**
     * 2026-10-05 二次修复：**跨进程复用**（实车日志
     * `1791179396666_cec15b13_d5ddb4312da1`）。
     *
     * 斌哥反馈「退出整个 DBPlay 后再进来，连接很慢」。日志实锤：进程被
     * 杀掉后重进（pid 11662 → 30801），热点其实还在跑（`hostapd` 进程在、
     * `runningSsid=DBPlay`），但内存 `snapshot` 已清零 ⇒ 只按 snapshot 判假的
     * 第一道守卫不命中 ⇒ 整条链重跑 ⇒ 白等 **17.4 秒**。
     *
     * 修法：内存判据之后，再补一道**读设备实况**的判据。
     * 本测试把这个顺序与两道判据的存在钉死。
     */
    @Test
    fun `内存判据未命中时按设备实况复用热点`() {
        val guard = ensureStartedGuard(sourceOf(managerSrc))

        assertTrue(
            "缺少跨进程复用入口 `reuseRunningHotspotIfPresent()`：进程被杀后热点仍在跑，" +
                "没有它就会重跑整条链、白等十余秒（2026-10-05 实车 17.4s）。",
            guard.contains("reuseRunningHotspotIfPresent()"),
        )
        // 顺序：内存快路径在前，设备实况垫后。反了就等于放弃了零成本的同进程复用。
        val memIdx = guard.indexOf("if (snapshot.isRunning) return true")
        val devIdx = guard.indexOf("reuseRunningHotspotIfPresent()")
        assertTrue(
            "两道判据的相对顺序被改了：内存判据（snapshot.isRunning）必须在前、" +
                "设备实况判据在后；实际 mem=$memIdx dev=$devIdx。",
            memIdx >= 0 && devIdx > memIdx,
        )
    }

    /**
     * 设备实况判据本身的形态：必须**两步都从设备读** ——
     * ① `pidof hostapd` 证明热点进程活着；
     * ② 网卡上出现我们的 host 地址，证明那是**我们建**的热点
     *    （不是车机原生热点、也不是别人的 AP）。
     */
    @Test
    fun `设备实况判据读 hostapd 进程与网卡地址`() {
        val src = sourceOf(managerSrc)
        val fn = src.substringAfter("private fun reuseRunningHotspotIfPresent(", "")
        assertTrue("reuseRunningHotspotIfPresent 不见了——本契约需同步更新", fn.isNotEmpty())
        val body = fn.substringBefore("\n    // ---")

        assertTrue(
            "必须用 `pidof hostapd` 判断热点进程是否活着。",
            body.contains("pidof hostapd"),
        )
        assertTrue(
            "必须用 `ifconfig <网卡>` 读实况地址，确认是我们建的那一个热点。",
            body.contains("ifconfig"),
        )
        assertTrue(
            "必须比对我们配置的 hostAddress（否则分不清我们建的热点与车机原生热点）。",
            body.contains("hostAddress"),
        )
    }
}
