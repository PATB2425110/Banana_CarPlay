package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * PATCH-0010 修正：`WirelessHotspotMode.SAME_LAN` 下 `CarPlayRuntimeConfig`
 * 对"通告给 iPhone 的 Wi-Fi 凭据"的校验。
 *
 * ## 为什么这些断言要单独立一份
 *
 * 该模式的失败**全程静默**：车机连着网、iPhone 蓝牙也通了、iAP2 也识别了，
 * 然后界面停在「无线通道就绪」直到 40 秒超时——现场只有 `0x5703` 里那一个
 * SSID 能说明问题（诊断包 `1aedc494`）。
 *
 * 所以这里把"SSID 必须能用"这条纪律钉在**配置构造**这一层：
 * 越早拦，越不用让用户去猜"为什么连不上"。
 *
 * ## ⚠️ 2026-10-08：这一整套只对**无线会话**成立
 *
 * 移植 DiPlay PR #124 / #130 后，`CarPlayRuntimeConfig` 里 SAME_LAN 那一块加了
 * `transport == WIRELESS` 门（DiPlay 原文：*"Only a wireless session starts the
 * hotspot; a USB session must not fail on unused settings."*）。
 *
 * 所以本文件的夹具**必须显式声明无线会话**：`hotspotPlan` 在
 * `CarPlaySessionBridge.buildSession()` 里是 `if (current.isWireless) … else null`，
 * 有线会话拿到的 `sameLanHostAddress` / `sameLanWifiSsid` **恒为 null**。
 * 若夹具继续用默认的 `WIRED`，下面每条 `assertThrows` 都会因为"根本没进这个分支"
 * 而失败——那是夹具失真，不是校验失效。
 *
 * "有线会话不该被这些设置挡住"这条不变量在 `ManualHotspotConfigTest` 里单独立了用例
 * （`wiredSessionIgnoresAnUnfinishedSameLanSelection`），本文件只负责无线侧。
 */
class SameLanRuntimeConfigTest {

    @Test
    fun `SAME_LAN 缺地址时直接拒绝构造`() {
        assertThrows(IllegalArgumentException::class.java) {
            config(sameLanHostAddress = null)
        }
    }

    @Test
    fun `SAME_LAN 地址不是 IPv4 时拒绝构造`() {
        assertThrows(IllegalArgumentException::class.java) {
            config(sameLanHostAddress = "fe80::1")
        }
        assertThrows(IllegalArgumentException::class.java) {
            config(sameLanHostAddress = "not-an-ip")
        }
    }

    @Test
    fun `SAME_LAN 缺 SSID 时拒绝构造 —— 空 SSID 会让无线 bring-up 崩溃`() {
        // ⚠️ 2026-10-07 真机回归（诊断包 1791345066541）：
        // 空 SSID 不是"握手可能失败"，而是**下游直接抛 require**：
        //   java.lang.IllegalArgumentException: Wireless SSID must not be blank
        //   at Iap2WirelessIdentification.<init>
        // 现场表现是"点启动 → 2 秒就断，界面无说明"。
        // ⇒ 必须在配置构造这一层就拦住，让报错发生在设置页，而不是会话启动后。
        assertThrows(IllegalArgumentException::class.java) {
            config(sameLanHostAddress = "192.168.1.5", sameLanWifiSsid = null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            config(sameLanHostAddress = "192.168.1.5", sameLanWifiSsid = "")
        }
    }

    @Test
    fun `SAME_LAN 必须有非空 SSID 才能构造`() {
        val config = config(
            sameLanHostAddress = "192.168.1.5",
            sameLanWifiSsid = "PhoneHotspot",
        )
        assertEquals("PhoneHotspot", config.sameLanWifiSsid)
    }

    @Test
    fun `SAME_LAN 的 SSID 原样保留，不做任何回落`() {
        val config = config(
            sameLanHostAddress = "192.168.1.5",
            sameLanWifiSsid = "我家WiFi",
            sameLanWifiPassphrase = "mypassword",
        )
        assertEquals("我家WiFi", config.sameLanWifiSsid)
        assertEquals("mypassword", config.sameLanWifiPassphrase)
    }

    @Test
    fun `SAME_LAN 的密码长度非法时拒绝构造`() {
        // 与手动热点同一条纪律：要么空（开放/未知），要么 8..63。
        assertThrows(IllegalArgumentException::class.java) {
            config(
                sameLanHostAddress = "192.168.1.5",
                sameLanWifiSsid = "SomeNet",
                sameLanWifiPassphrase = "short",
            )
        }
    }

    @Test
    fun `SAME_LAN 的 SSID 超过 32 字节时拒绝构造`() {
        assertThrows(IllegalArgumentException::class.java) {
            config(
                sameLanHostAddress = "192.168.1.5",
                sameLanWifiSsid = "x".repeat(33),
            )
        }
    }

    @Test
    fun `SAME_LAN 的 SSID 含 U+0000 时拒绝构造`() {
        assertThrows(IllegalArgumentException::class.java) {
            config(
                sameLanHostAddress = "192.168.1.5",
                sameLanWifiSsid = "bad\u0000ssid",
            )
        }
    }

    @Test
    fun `非 SAME_LAN 模式下不校验 SSID 与密码`() {
        // 其余模式的 hotspot 凭据由 manualHotspot* 那一组负责校验，
        // 这里传脏值也不该触发 SAME_LAN 的规则（分支隔离）。
        //
        // ⚠️ 必须显式声明 `WIRELESS`：默认是 `WIRED`，而 2026-10-08 起 SAME_LAN 那一块
        // 只对无线会话生效——用默认值的话，这条用例会**因为 transport 门而通过**，
        // 证明不了"分支隔离"这件事（那正是它存在的理由）。
        val config = CarPlayRuntimeConfig(
            mfiTarget = MfiTarget.REMOTE,
            remoteMfiServer = "https://mfi.example.test",
            remoteMfiToken = "secret",
            identification = identification(),
            transport = CarPlayTransport.WIRELESS,
            sameLanWifiSsid = "x".repeat(99),
            sameLanWifiPassphrase = "short",
        )
        assertEquals("x".repeat(99), config.sameLanWifiSsid)
    }

    private fun config(
        sameLanHostAddress: String? = "192.168.1.5",
        // 默认给一个合法 SSID：本模式下它是**必填**（空串会崩 bring-up），
        // 所以"只想测地址"的用例必须显式带一个，否则会被 SSID 的 require 拦下。
        sameLanWifiSsid: String? = "PhoneHotspot",
        sameLanWifiPassphrase: String? = null,
    ): CarPlayRuntimeConfig = CarPlayRuntimeConfig(
        mfiTarget = MfiTarget.REMOTE,
        remoteMfiServer = "https://mfi.example.test",
        remoteMfiToken = "secret",
        identification = identification(),
        // 本文件测的是无线会话的校验，所以显式声明（默认是 WIRED）。
        transport = CarPlayTransport.WIRELESS,
        wirelessHotspotMode = WirelessHotspotMode.SAME_LAN,
        sameLanHostAddress = sameLanHostAddress,
        sameLanWifiSsid = sameLanWifiSsid,
        sameLanWifiPassphrase = sameLanWifiPassphrase,
    )

    private fun identification() = Iap2IdentificationConfig(
        name = "test",
        modelIdentifier = "test",
        manufacturer = "test",
        serialNumber = "test",
        firmwareVersion = "1",
        hardwareVersion = "1",
        carPlayUsbInterfaceNumber = 3,
    )
}
