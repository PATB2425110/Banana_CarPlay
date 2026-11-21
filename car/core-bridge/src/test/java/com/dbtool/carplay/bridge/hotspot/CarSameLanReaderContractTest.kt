package com.dbtool.carplay.bridge.hotspot

import java.net.Inet4Address
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `CarSameLanReader` 的两条**选地址判据**的契约测试（2026-10-08 加）。
 *
 * ## 背景（实车 `67b3c24af6b5` 日志实锤）
 *
 * 用户选「局域网连接」，车机侧并没有 Wi-Fi STA，于是 `readFromActiveNetwork`
 * 返回 null，代码落到**网卡枚举回落** `readFromInterfaces()`，它当时是：
 *
 * ```
 * .mapNotNull { ... }.firstOrNull()      // 纯枚举顺序，不区分网卡类型
 * ```
 *
 * 结果选中了有线口 `eth0.11`（`198.18.34.15`）——**RFC 2544 保留网段**。
 * 这个地址被通告给 iPhone，而 iPhone 走 Wi-Fi 物理上到不了，
 * 于是反复出现：
 *
 * ```
 * wireless attach timed out: iPhone did not open the AirPlay connection
 *   within 40000ms (attempt=1)
 * ```
 *
 * 蓝牙 / iAP2 / MFi 认证全过，唯独 AirPlay 数据通道永远不开，
 * 且**除这条超时外全链路无任何报错**。
 *
 * ⚠️ 这是 2026-10-03 那次（有线口 `eth0.3`，同一个 `198.18.34.15`）的同一类坑
 * —— 那次修的是 AUTO 复用分支，10-05 复用分支作废后防护一并丢掉，
 * 于是从「网卡枚举回落」这条路又漏了出来。
 *
 * 本测试把两条判据钉住，防止第三次漏出。
 */
class CarSameLanReaderContractTest {

    // ── ① RFC 2544 保留网段必须排除 ─────────────────────────────────────

    @Test
    fun `198_18 网段判为保留（含实测到的那一个地址）`() {
        for (text in listOf(
            "198.18.0.0",      // 网段起点
            "198.18.34.15",    // ← 实车 eth0.11 上拿到的那一个
            "198.18.255.255",  // 198.18/16 末尾
            "198.19.0.0",      // 198.19/16 起点
            "198.19.255.255",  // /15 末尾
        )) {
            assertTrue(
                "「$text」落在 RFC 2544 保留网段 198.18.0.0/15，必须判为保留",
                CarSameLanReader.isBenchmarkReserved(addr(text)),
            )
        }
    }

    @Test
    fun `198_18 之外的地址绝不误判`() {
        for (text in listOf(
            "198.17.255.255",  // 紧邻下界外侧
            "198.20.0.0",      // 紧邻上界外侧
            "192.168.1.100",   // 常见家用网段
            "10.0.0.5",
            "172.16.0.1",
            "169.254.1.1",     // link-local（另有 pickIpv4 挡）
            "1.1.1.1",
        )) {
            assertFalse(
                "「$text」不在 198.18.0.0/15 内，不能被当成保留网段排除",
                CarSameLanReader.isBenchmarkReserved(addr(text)),
            )
        }
    }

    // ── ② Wi-Fi STA 侧优先 ───────────────────────────────────────────────

    @Test
    fun `有 wlan 时优先选 wlan，即便 eth 排在前面`() {
        // 这就是实车那个顺序：eth0.11 先被枚举到。
        val picked = CarSameLanReader.pickSameLanCandidate(
            listOf(
                "eth0.11" to addr("198.18.34.15"),
                "wlan0" to addr("192.168.43.5"),
            ),
        )
        assertEquals(
            "必须跳过排在前面、iPhone 到不了的 eth0.11，选 Wi-Fi 侧的 wlan0",
            "wlan0" to addr("192.168.43.5"),
            picked,
        )
    }

    @Test
    fun `网卡名大小写不敏感`() {
        val picked = CarSameLanReader.pickSameLanCandidate(
            listOf("eth0" to addr("10.1.1.1"), "WLAN0" to addr("10.2.2.2")),
        )
        assertEquals("WLAN0" to addr("10.2.2.2"), picked)
    }

    @Test
    fun `没有 wlan 时退回第一个 —— 不误伤 Wi-Fi 侧不叫 wlan 的机型`() {
        // 个别 ROM 的 Wi-Fi 侧是 eth0.x（此时地址正常，iPhone 到得了），
        // 一刀切排除会把本来可用的配置判死。
        val picked = CarSameLanReader.pickSameLanCandidate(
            listOf("eth0.3" to addr("192.168.1.7")),
        )
        assertEquals("eth0.3" to addr("192.168.1.7"), picked)
    }

    @Test
    fun `候选为空时返回 null（如实报读不到，不编一个地址）`() {
        assertNull(CarSameLanReader.pickSameLanCandidate(emptyList()))
    }

    // ── 工具 ─────────────────────────────────────────────────────────────

    private fun addr(text: String): Inet4Address =
        InetAddress.getByName(text) as Inet4Address
}
