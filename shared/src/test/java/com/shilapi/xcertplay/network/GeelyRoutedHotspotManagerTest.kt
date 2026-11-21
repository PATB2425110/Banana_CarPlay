package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress

/**
 * [GeelyRoutedHotspotManager] 回归（2026-10-25）。
 *
 * 判据与被测行为逐条对齐参考 APK
 * `GeelyCarPlay-Settings-Refined-debug.apk`（SHA-256 `4af7f7649b198bc7…`）的
 * `GeelyRoutedHotspotManager` / `GeelyRoutedHostReadiness` / `GeelyRoutedHotspotManagerKt`。
 *
 * 全部用注入的 `sample` / `nowMillis` / `pause` 跑纯 JVM，不碰真实网卡与时钟。
 */
class GeelyRoutedHotspotManagerTest {

    private fun addr(s: String): InetAddress = InetAddress.getByName(s)

    /** 假时钟：每次调用前进 [stepMs]，便于在不睡觉的情况下把预算耗光。 */
    private class FakeClock(private var t: Long = 0L, private val stepMs: Long = 0L) {
        fun now(): Long = t.also { t += stepMs }
    }

    private fun manager(
        candidates: List<Pair<String, InetAddress>>,
        clock: () -> Long = { 0L },
        pause: (Long) -> Unit = { },
        diagnostics: MutableList<String>? = null,
    ) = GeelyRoutedHotspotManager(
        ssid = "DBPlay",
        passphrase = "12345678",
        security = Iap2WirelessSecurity.WPA_WPA2,
        onDiagnostic = { diagnostics?.add(it) },
        nowMillis = clock,
        pause = pause,
        sample = { candidates },
    )

    // ---------------------------------------------------------------- 正常路径

    @Test
    fun `找到 198_18 地址后返回与参考包逐字段一致的结构`() {
        val diag = mutableListOf<String>()
        val info = manager(listOf("eth0.11" to addr("198.18.34.15")), diagnostics = diag).start(60_000L)

        assertEquals("DBPlay", info.ssid)
        assertEquals("12345678", info.passphrase)
        assertEquals(Iap2WirelessSecurity.WPA_WPA2, info.security)
        // ★ 这四条是与参考包一致的核心：不声明信道/频段/BSSID
        assertEquals("channel 必须是 0", 0, info.channel)
        assertNull("frequencyMHz 必须是 null", info.frequencyMHz)
        assertNull("bssid 必须是 null（不能是网卡 MAC）", info.bssid)
        assertEquals("Car hotspot", info.bandLabel)
        assertEquals(WirelessHotspotBackend.GEELY_ROUTED_HOTSPOT, info.backend)
        assertEquals("eth0.11", info.interfaceName)
        assertEquals("198.18.34.15", info.hostAddress?.hostAddress)

        assertTrue("应有诊断日志", diag.any { it.contains("Car routed hotspot host=198.18.34.15") })
        assertTrue("诊断里应带 interface", diag.any { it.contains("interface=eth0.11") })
    }

    @Test
    fun `eth0 优先于其它 eth 接口`() {
        val info = manager(
            listOf("eth1" to addr("198.18.1.2"), "eth0" to addr("198.18.34.15")),
        ).start(60_000L)
        assertEquals("eth0", info.interfaceName)
        assertEquals("198.18.34.15", info.hostAddress?.hostAddress)
    }

    @Test
    fun `非 eth 接口即使地址匹配也被忽略`() {
        val clock = FakeClock(stepMs = 1_000L)
        val err = runCatching {
            manager(listOf("wlan0" to addr("198.18.34.15")), clock = clock::now).start(3_000L)
        }.exceptionOrNull()
        assertTrue("wlan0 上的 198.18 不算", err is IOException)
    }

    @Test
    fun `198_19 不被接受`() {
        val clock = FakeClock(stepMs = 1_000L)
        val err = runCatching {
            manager(listOf("eth0" to addr("198.19.0.1")), clock = clock::now).start(3_000L)
        }.exceptionOrNull()
        assertTrue(err is IOException)
    }

    // ---------------------------------------------------------------- 超时 / 取消

    @Test
    fun `预算耗尽抛带毫秒数的 IOException`() {
        // 每次读时钟前进 1000ms，timeout 3000ms ⇒ 必然超时
        val clock = FakeClock(stepMs = 1_000L)
        val err = runCatching {
            manager(emptyList(), clock = clock::now).start(3_000L)
        }.exceptionOrNull()
        assertTrue("必须是 IOException", err is IOException)
        assertTrue(
            "文案要带 timeoutMillis 与 ms，与参考包一致：${err?.message}",
            err!!.message!!.contains("after 3000ms"),
        )
    }

    @Test
    fun `timeoutMillis 小于等于 0 抛 IllegalArgumentException`() {
        val err = runCatching { manager(emptyList()).start(0L) }.exceptionOrNull()
        assertTrue("参考包 require(timeoutMillis > 0)", err is IllegalArgumentException)
    }

    @Test
    fun `close 之后的等待立刻取消`() {
        val err = runCatching {
            // 时钟不动，pause 时 close 掉自己 → 下一轮 while 检查应抛 cancelled
            lateinit var mgr: GeelyRoutedHotspotManager
            mgr = GeelyRoutedHotspotManager(
                ssid = "DBPlay",
                passphrase = "12345678",
                security = Iap2WirelessSecurity.WPA_WPA2,
                nowMillis = { 0L },
                pause = { mgr.close() },
                sample = { emptyList() },
            )
            mgr.start(60_000L)
        }.exceptionOrNull()
        assertTrue("close 后应抛 IOException", err is IOException)
        assertTrue(
            "文案应为 cancelled：${err?.message}",
            err!!.message!!.contains("cancelled"),
        )
    }

    // ---------------------------------------------------------------- 判据

    @Test
    fun `地址判据与参考包逐字节一致`() {
        assertTrue(GeelyRoutedHotspotManager.isVehicleAddress("eth0", addr("198.18.34.15")))
        assertTrue(GeelyRoutedHotspotManager.isVehicleAddress("eth9", addr("198.18.0.1")))
        assertFalse(GeelyRoutedHotspotManager.isVehicleAddress("eth0", addr("198.19.0.1")))
        assertFalse(GeelyRoutedHotspotManager.isVehicleAddress("wlan0", addr("198.18.34.15")))
        assertFalse(GeelyRoutedHotspotManager.isVehicleAddress("ETH0", addr("198.18.34.15")))
        assertFalse(GeelyRoutedHotspotManager.isVehicleAddress("eth0", addr("::1")))
    }

    @Test
    fun `同接口多地址保留枚举顺序`() {
        val a = GeelyRoutedHotspotManager.selectHost(
            listOf("eth0" to addr("198.18.34.20"), "eth0" to addr("198.18.34.15")),
        )
        assertEquals("198.18.34.20", a!!.second.hostAddress)
        val b = GeelyRoutedHotspotManager.selectHost(
            listOf("eth0" to addr("198.18.34.15"), "eth0" to addr("198.18.34.20")),
        )
        assertEquals("198.18.34.15", b!!.second.hostAddress)
    }

    @Test
    fun `设备门禁逐字对齐参考包`() {
        // SDK 28 硬门槛
        assertTrue(GeelyRoutedHotspotManager.usesRoutedHotspot("CS11", "cs11_high", 28))
        assertTrue(GeelyRoutedHotspotManager.usesRoutedHotspot("KX11", "kx11", 28))
        assertTrue(GeelyRoutedHotspotManager.usesRoutedHotspot("QUALCOMM CS11", "x", 28))
        assertTrue(GeelyRoutedHotspotManager.usesRoutedHotspot("CX11", "cx11_high", 28))
        assertTrue("CS11 不看 device", GeelyRoutedHotspotManager.usesRoutedHotspot(" CS11 ", "", 28))
        assertFalse(GeelyRoutedHotspotManager.usesRoutedHotspot("CS11", "cs11", 29))
        assertFalse(GeelyRoutedHotspotManager.usesRoutedHotspot("CS11", "cs11", 27))
        // KX11 那支才看 device
        assertFalse(GeelyRoutedHotspotManager.usesRoutedHotspot("KX11", "cs11", 28))
        // 不存在 "device 以 cs11 开头即为真" 的兜底
        assertFalse(GeelyRoutedHotspotManager.usesRoutedHotspot("UNKNOWN", "cs11xyz", 28))
        assertFalse(GeelyRoutedHotspotManager.usesRoutedHotspot(null, "cs11", 28))
    }

    @Test
    fun `backend 标签可读`() {
        assertEquals("Geely routed car hotspot", WirelessHotspotBackend.GEELY_ROUTED_HOTSPOT.label)
        assertNotNull(
            com.shilapi.xcertplay.orchestration.WirelessHotspotMode.valueOf("GEELY_ROUTED"),
        )
    }

    // ------------------------------------------------ Claude 第二轮指出的缺口（G1）

    @Test
    fun `超时优先于命中 - 第一轮就有合法地址但已超预算仍抛超时`() {
        // 参考包 await 顺序：采样 → 判取消 → 判超时 → 判命中
        // 所以即使 sample 一上来就给出 eth0/198.18，超预算也必须先抛 timeout。
        val clock = FakeClock(stepMs = 5_000L) // started=0, 第二读=5000 ⇒ remaining<=0
        val err = runCatching {
            manager(listOf("eth0" to addr("198.18.34.15")), clock = clock::now).start(1_000L)
        }.exceptionOrNull()
        assertTrue("必须抛 IOException，而不是返回命中结果：${err}", err is IOException)
        assertTrue(
            "必须是超时文案而不是别的：${err?.message}",
            err!!.message!!.contains("unavailable after 1000ms"),
        )
    }

    @Test
    fun `start 之前就 close - 一次都不采样且抛 cancelled`() {
        var sampled = 0
        val mgr = GeelyRoutedHotspotManager(
            ssid = "DBPlay",
            passphrase = "12345678",
            security = Iap2WirelessSecurity.WPA_WPA2,
            nowMillis = { 0L },
            pause = { },
            sample = { sampled++; emptyList() },
        )
        mgr.close()
        val err = runCatching { mgr.start(60_000L) }.exceptionOrNull()
        assertTrue("应抛 IOException", err is IOException)
        assertTrue("文案应为 cancelled：${err?.message}", err!!.message!!.contains("cancelled"))
        assertEquals("close 之后 while 条件直接为假，不该采样", 0, sampled)
    }

    @Test
    fun `采样之后立刻 close - 第二次检查也要生效`() {
        // 参考包在 sample() 之后**再判一次** cancelled
        lateinit var mgr: GeelyRoutedHotspotManager
        var first = true
        mgr = GeelyRoutedHotspotManager(
            ssid = "DBPlay",
            passphrase = "12345678",
            security = Iap2WirelessSecurity.WPA_WPA2,
            nowMillis = { 0L },
            pause = { },
            sample = {
                if (first) { first = false; mgr.close() }
                emptyList()
            },
        )
        val err = runCatching { mgr.start(60_000L) }.exceptionOrNull()
        assertTrue("采样后判取消应抛 IOException", err is IOException)
        assertTrue("文案应为 cancelled：${err?.message}", err!!.message!!.contains("cancelled"))
    }

    @Test
    fun `诊断里的 candidates 计数等于命中那一轮的匹配数`() {
        val diag = mutableListOf<String>()
        manager(
            listOf(
                "eth0" to addr("198.18.34.15"),
                "eth1" to addr("198.18.34.16"),
                "eth2" to addr("192.168.1.9"),   // 不匹配
                "wlan0" to addr("198.18.34.17"), // 前缀不符
            ),
            diagnostics = diag,
        ).start(60_000L)
        assertTrue(
            "candidates 应为 2（eth0+eth1），实际日志：$diag",
            diag.any { it.contains("candidates=2") },
        )
    }

    @Test
    fun `pause 永远不超过 250 且始终为正`() {
        val pauses = mutableListOf<Long>()
        // 时钟固定不动 ⇒ 每轮 remaining 恒为 1000，pause 应恒为 min(250, 1000)=250
        var n = 0
        val mgr = GeelyRoutedHotspotManager(
            ssid = "DBPlay",
            passphrase = "12345678",
            security = Iap2WirelessSecurity.WPA_WPA2,
            nowMillis = { 0L },
            pause = { ms -> pauses.add(ms); n++; if (n >= 3) throw IOException("stop") },
            sample = { emptyList() },
        )
        runCatching { mgr.start(1_000L) }
        assertTrue("应有 pause 调用", pauses.isNotEmpty())
        assertTrue("pause 必须 > 0：$pauses", pauses.all { it > 0 })
        assertTrue("pause 必须 <= 250：$pauses", pauses.all { it <= 250L })
    }

    @Test
    fun `close 能立即唤醒正在等待的 start - 真线程`() {
        val mgr = GeelyRoutedHotspotManager(
            ssid = "DBPlay",
            passphrase = "12345678",
            security = Iap2WirelessSecurity.WPA_WPA2,
            // 这里**不注入 pause**，用真实的 defaultPause（waitLock.wait）
            nowMillis = { 0L },
            sample = { emptyList() },
        )
        val result = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val done = java.util.concurrent.CountDownLatch(1)
        val worker = Thread {
            result.set(runCatching { mgr.start(60_000L) }.exceptionOrNull())
            done.countDown()
        }
        worker.start()
        Thread.sleep(80)          // 让它进入 wait
        val t0 = System.nanoTime()
        mgr.close()
        val woke = done.await(2, java.util.concurrent.TimeUnit.SECONDS)
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000
        assertTrue("close 后 2 秒内必须返回（否则 notifyAll 是死代码）", woke)
        assertTrue("耗时 ${elapsedMs}ms 应远小于单次 250ms 轮询，说明是被唤醒的", elapsedMs < 250)
        assertTrue("应抛 cancelled：${result.get()}", result.get() is IOException)
    }

    @Test
    fun `sample 抛 IOException 时原样传播 - 不被当成超时`() {
        val mgr = GeelyRoutedHotspotManager(
            ssid = "DBPlay",
            passphrase = "12345678",
            security = Iap2WirelessSecurity.WPA_WPA2,
            nowMillis = { 0L },
            pause = { },
            sample = { throw IOException("Could not inspect vehicle network interfaces") },
        )
        val err = runCatching { mgr.start(60_000L) }.exceptionOrNull()
        assertTrue(err is IOException)
        assertTrue(
            "必须原样透出网络枚举失败文案，不能被吞成超时：${err?.message}",
            err!!.message!!.contains("Could not inspect vehicle network interfaces"),
        )
    }

    @Test
    fun `eth0 存在但地址不匹配时选 eth1`() {
        val info = manager(
            listOf("eth0" to addr("192.168.1.9"), "eth1" to addr("198.18.34.15")),
        ).start(60_000L)
        assertEquals("eth0 优先只在命中集合内生效", "eth1", info.interfaceName)
    }

    @Test
    fun `真实 IPv6 地址不算车机地址`() {
        // ⚠️ 不能用 "::ffff:198.18.34.15" 来测：JVM 会把 IPv4-mapped 规范化成
        // Inet4Address，于是 isVehicleAddress 返回 true —— 那与参考包行为**一致**
        // （参考包也是 instanceof Inet4Address）。这里改用真正的 16 字节 Inet6Address。
        val v6 = InetAddress.getByAddress(
            ByteArray(16).also { it[15] = 1 }, // ::1
        )
        assertTrue("前提：这是 Inet6Address", v6 is java.net.Inet6Address)
        assertFalse(GeelyRoutedHotspotManager.isVehicleAddress("eth0", v6))

        // 顺带把 mapped 的行为也锁住：它会被规范化成 Inet4Address，因此算命中，
        // 这与参考包一致，不是 bug。
        val mapped = InetAddress.getByName("::ffff:198.18.34.15")
        assertTrue(
            "mapped IPv6 应被 JVM 规范化成 Inet4Address（与参考包一致）",
            mapped is java.net.Inet4Address,
        )
    }

    @Test
    fun `timeoutMillis 为负数抛 IllegalArgumentException`() {
        val err = runCatching { manager(emptyList()).start(-1L) }.exceptionOrNull()
        assertTrue(err is IllegalArgumentException)
    }
}
