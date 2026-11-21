package com.dbtool.carplay.bridge.hotspot

import com.dbtool.carplay.api.hotspot.CarHotspotApInterface
import com.dbtool.carplay.api.hotspot.CarManualHotspotMatch
import com.dbtool.carplay.api.settings.CarSessionSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 复用车机热点「启动速度 + 自动取密码」三处改动的测试（2026-10-13，日志 8）。
 *
 * 1. 复用预检只枚举一次网卡（原来 5 次，真车每次约 1 秒）；
 * 2. 用户没填 / 还是出厂默认密码时，改用车机热点自己的密码；
 * 3. `reconnect()` / `stop()` 不再为世代号抢 `lifecycleLock`（主线程曾被卡 3.4 秒）。
 *
 * 能在 JVM 上跑的走纯函数；需要 Context 的接线走"读源码"契约，且先剥注释（注释里必然出现同样的字面量）。
 */
class CarReuseStartupSpeedTest {

    private val default = CarSessionSettings.DEFAULT_HOTSPOT_PASSPHRASE

    // ── 密码判据 ────────────────────────────────────────────────────────────

    @Test
    fun `用户没填密码时改用车机密码`() {
        assertEquals("carSecret99", CarManualHotspotMatch.reconcilePassphrase("", "carSecret99"))
    }

    @Test
    fun `还是出厂默认密码时改用车机密码`() {
        assertEquals("carSecret99", CarManualHotspotMatch.reconcilePassphrase(default, "carSecret99"))
    }

    @Test
    fun `用户自己填了别的密码一律不动`() {
        assertNull(CarManualHotspotMatch.reconcilePassphrase("myOwnPass1", "carSecret99"))
    }

    @Test
    fun `读不到车机密码或长度不合 WPA 时不动`() {
        assertNull(CarManualHotspotMatch.reconcilePassphrase("", null))
        assertNull(CarManualHotspotMatch.reconcilePassphrase("", ""))
        assertNull(CarManualHotspotMatch.reconcilePassphrase("", "short"))
        assertNull(CarManualHotspotMatch.reconcilePassphrase(default, "x".repeat(64)))
    }

    @Test
    fun `与车机密码一致时不需要替换`() {
        assertNull(CarManualHotspotMatch.reconcilePassphrase("carSecret99", "carSecret99"))
        // 车机密码恰好就是出厂默认：同值，没有替换的必要
        assertNull(CarManualHotspotMatch.reconcilePassphrase(default, default))
    }

    // ── 用已有网卡快照判 AP 网卡，不再重新枚举 ──────────────────────────────

    private fun iface(name: String, vararg addresses: String, uplink: Boolean = false) =
        CarHotspotApInterface.Iface(name = name, addresses = addresses.toList(), isUp = true, isUplink = uplink)

    @Test
    fun `快照里 wlan 且有地址且不是上行的才算 AP 网卡`() {
        assertEquals(
            "wlan0",
            LocalSoftApReader.apInterfaceNameFrom(listOf(iface("eth0", "198.18.34.15"), iface("wlan0", "192.168.43.1"))),
        )
    }

    @Test
    fun `Ecarx 常态_只有有线口_找不到 AP 网卡`() {
        // 外置 T-Box 的 AP 不在本机网卡上；有线口再多地址也不能被当成 AP
        assertNull(
            LocalSoftApReader.apInterfaceNameFrom(
                listOf(iface("eth0", "198.18.34.15"), iface("eth0.11", "198.18.34.15", uplink = true), iface("wlan0")),
            ),
        )
    }

    @Test
    fun `上行网卡和 link-local 地址不算 AP`() {
        assertNull(LocalSoftApReader.apInterfaceNameFrom(listOf(iface("wlan0", "192.168.1.5", uplink = true))))
        assertNull(LocalSoftApReader.apInterfaceNameFrom(listOf(iface("wlan0", "169.254.3.4"))))
    }

    // ── 接线契约 ────────────────────────────────────────────────────────────

    private fun codeOf(relative: String): String {
        val file = listOf(java.io.File(relative), java.io.File("../$relative"), java.io.File("../../$relative"))
            .firstOrNull { it.exists() } ?: error("找不到 $relative")
        return file.readText()
            .replace(Regex("(?s)/\\*.*?\\*/"), " ")
            .replace(Regex("(?m)//.*$"), " ")
    }

    private val bridge = "src/main/java/com/dbtool/carplay/bridge/CarPlaySessionBridge.kt"

    private fun precheckBody(): String =
        codeOf(bridge).substringAfter("private fun ensureManualHotspotReady(", "")
            .substringBefore("private fun reconcileReusedSsid(")

    @Test
    fun `复用预检整段只枚举一次网卡`() {
        val body = precheckBody()
        assertTrue("找不到 ensureManualHotspotReady", body.isNotEmpty())
        assertEquals(
            "预检里 CarNetworkInterfaces.snapshot( 只允许出现一次（真车每次约 1 秒）",
            1,
            Regex("""CarNetworkInterfaces\.snapshot\(""").findAll(body).count(),
        )
        // 其余读网卡的判据都必须喂这份快照，而不是各自重新枚举
        assertTrue(body.contains("LocalSoftApReader.read(appContext, interfaces)"))
        assertTrue(body.contains("addressPresentAnywhere(appContext, selfBuiltAddress, interfaces)"))
        assertTrue(body.contains("selfPoolAddressAnywhere(appContext, interfaces)"))
        assertTrue(body.contains("addressPresentAnywhere(appContext, manualAddress, interfaces)"))
        assertTrue(body.contains("val currentInterfaces = interfaces"))
    }

    @Test
    fun `预检写出分段耗时日志`() {
        assertTrue(
            "下一个诊断包要能直接核对哪一段慢，而不是靠时间戳倒推",
            precheckBody().contains("manual hotspot precheck timing:"),
        )
    }

    @Test
    fun `密码替换只挂在复用分支_且不把密码写进日志`() {
        val code = codeOf(bridge)
        assertEquals(
            "reconcileReusedPassphrase 只允许从 reconcileReusedSsid 调用一次",
            1,
            Regex("""reconcileReusedPassphrase\(named""").findAll(code).count(),
        )
        val fn = code.substringAfter("private fun reconcileReusedPassphrase(", "")
            .substringBefore("private fun ensureAdbHotspot")
        assertTrue(fn.isNotEmpty())
        assertTrue("日志只能记长度", fn.contains("typedLength=") && fn.contains("storedLength="))
        assertTrue(
            "日志里不得插值明文密码",
            !Regex("""\$\{?replacement\}?(?!\.length)""").containsMatchIn(
                fn.substringAfter("diagnostics.log(").substringBefore("emit("),
            ),
        )
    }

    @Test
    fun `reconnect 和 stop 不再为世代号抢 lifecycleLock`() {
        val code = codeOf(bridge)
        assertTrue("世代号必须是原子量", code.contains("private val generation = AtomicInteger(0)"))
        val reconnect = code.substringAfter("override fun reconnect() {", "").substringBefore("/** 释放全部资源")
        assertTrue(
            "主线程入口只做原子自增，不得 synchronized(lifecycleLock)",
            reconnect.substringBefore("workExecutor.execute").contains("synchronized").not() &&
                reconnect.contains("generation.incrementAndGet()"),
        )
        val stop = code.substringAfter("override fun stop() {", "").substringBefore("nowPlayingDetach?.invoke()")
        assertTrue("stop 的世代号失效也不得抢锁", stop.contains("synchronized").not() && stop.contains("generation.incrementAndGet()"))
    }

    @Test
    fun `建栈途中被作废会放弃且不进连接失败`() {
        val code = codeOf(bridge)
        assertTrue(code.contains("if (generation.get() != buildToken) throw StaleBuildException()"))
        val catchStale = code.substringAfter("catch (_: StaleBuildException)", "").substringBefore("catch (error: CarHotspotUnavailableException)")
        assertTrue("作废不是错误，不得走 failStart", catchStale.isNotEmpty() && catchStale.contains("failStart").not())
    }
}
