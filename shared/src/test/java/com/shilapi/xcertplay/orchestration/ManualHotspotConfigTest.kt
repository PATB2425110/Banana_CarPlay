package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualHotspotConfigTest {
    @Test
    fun autoBandAcceptsBoth2GhzAnd5GhzChannels() {
        assertTrue(isManualHotspotChannelCompatible(ManualHotspotBand.AUTO, 6))
        assertTrue(isManualHotspotChannelCompatible(ManualHotspotBand.AUTO, 36))
    }

    @Test
    fun explicitBandRejectsChannelsFromTheOtherBand() {
        assertTrue(isManualHotspotChannelCompatible(ManualHotspotBand.GHZ_2_4, 11))
        assertFalse(isManualHotspotChannelCompatible(ManualHotspotBand.GHZ_2_4, 36))
        assertTrue(isManualHotspotChannelCompatible(ManualHotspotBand.GHZ_5, 149))
        assertFalse(isManualHotspotChannelCompatible(ManualHotspotBand.GHZ_5, 11))
    }

    // ---------------------------------------------------------------- 有线会话不吃热点设置
    //
    // 2026-10-08 移植 DiPlay PR #124 / #130。原文：
    // *"Do not require the manual hotspot name for a USB session — With 'Manual hotspot'
    //   selected for wireless but no hotspot name entered, connecting by cable crashed at
    //   start: the runtime config required manualHotspotSsid whatever the transport,
    //   although only a wireless session starts the hotspot."*
    //
    // 这几条用例钉住的就是那个不变量：**热点设置只约束无线会话**。

    @Test
    fun wiredSessionIgnoresAnUnfinishedManualHotspot() {
        // 设置页选了「复用车机热点」但热点名没填：**有线会话必须照样能起来**。
        val config = config(CarPlayTransport.WIRED, ssid = "")

        assertEquals(CarPlayTransport.WIRED, config.transport)
    }

    @Test
    fun wiredSessionIgnoresAnUnfinishedSameLanSelection() {
        // 同一条不变量，局域网连接那一支：地址/SSID 没填也不该挡住有线会话。
        val config = CarPlayRuntimeConfig(
            mfiTarget = MfiTarget.I2C,
            linuxI2cPath = "/dev/i2c-1",
            identification = identification(),
            transport = CarPlayTransport.WIRED,
            wirelessHotspotMode = WirelessHotspotMode.SAME_LAN,
        )

        assertEquals(CarPlayTransport.WIRED, config.transport)
    }

    @Test
    fun wirelessSessionStillRequiresTheManualHotspotName() {
        // 放宽只针对有线：无线侧校验一字未动，仍必须拦住。
        assertThrows(IllegalArgumentException::class.java) {
            config(CarPlayTransport.WIRELESS, ssid = "")
        }
    }

    @Test
    fun wirelessSessionAcceptsACompleteManualHotspot() {
        val config = config(CarPlayTransport.WIRELESS, ssid = "Car Wi-Fi", passphrase = "12345678")

        assertEquals("Car Wi-Fi", config.manualHotspotSsid)
    }

    @Test
    fun wirelessSessionStillRequiresTheSameLanAddress() {
        assertThrows(IllegalArgumentException::class.java) {
            CarPlayRuntimeConfig(
                mfiTarget = MfiTarget.I2C,
                linuxI2cPath = "/dev/i2c-1",
                identification = identification(),
                transport = CarPlayTransport.WIRELESS,
                wirelessHotspotMode = WirelessHotspotMode.SAME_LAN,
            )
        }
    }

    @Test
    fun legacyP2pDoesNotChangeTheExistingWifiP2pAndroidNineDowngrade() {
        assertEquals(
            WirelessHotspotMode.LOCAL_ONLY_HOTSPOT,
            effectiveWirelessHotspotMode(WirelessHotspotMode.WIFI_P2P, 28),
        )
        assertEquals(
            WirelessHotspotMode.WIFI_P2P_LEGACY,
            effectiveWirelessHotspotMode(WirelessHotspotMode.WIFI_P2P_LEGACY, 28),
        )
        assertEquals(
            WirelessHotspotMode.WIFI_P2P,
            effectiveWirelessHotspotMode(WirelessHotspotMode.WIFI_P2P, 29),
        )
    }

    @Test
    fun wirelessLegacyP2pDoesNotDependOnManualHotspotCredentials() {
        val config = CarPlayRuntimeConfig(
            mfiTarget = MfiTarget.I2C,
            linuxI2cPath = "/dev/i2c-1",
            identification = identification(),
            transport = CarPlayTransport.WIRELESS,
            wirelessHotspotMode = WirelessHotspotMode.WIFI_P2P_LEGACY,
            manualHotspotSsid = null,
            manualHotspotPassphrase = null,
        )

        assertEquals(WirelessHotspotMode.WIFI_P2P_LEGACY, config.wirelessHotspotMode)
    }

    // ------------------------------------------------- 用户手填的车机热点地址（PATCH-0087）
    //
    // 车主口径（verbatim）：*"复用车机热点是要用户自己手动设置，而不是自动识别"*。
    //
    // 上游这一层只校验**形态**（IPv4 字面量），"必填"由二开层把关 ——
    // 因为上游分不清"复用车机热点"与"P2P 组主被翻译成 MANUAL 形状"
    // （后者的地址来自组主的 GO）。这几条把那条分工钉住。

    @Test
    fun manualHotspotHostAddressIsAcceptedAndPassedThrough() {
        val config = CarPlayRuntimeConfig(
            mfiTarget = MfiTarget.I2C,
            linuxI2cPath = "/dev/i2c-1",
            identification = identification(),
            transport = CarPlayTransport.WIRELESS,
            wirelessHotspotMode = WirelessHotspotMode.MANUAL,
            manualHotspotSsid = "Lynk",
            manualHotspotPassphrase = "12345678",
            manualHotspotHostAddress = "192.168.15.1",
        )

        assertEquals("192.168.15.1", config.manualHotspotHostAddress)
    }

    @Test
    fun aNonIpv4ManualHotspotHostAddressIsRejected() {
        // 主机名 / IPv6 都会让"按地址找网卡"这一步永远失配 ⇒ 在构造配置时就拒掉，
        // 而不是拖到会话启动后抛一句看不懂的话。
        for (address in listOf("car-hotspot.local", "fe80::1", "192.168.15")) {
            assertThrows("'$address' 不该被接受", IllegalArgumentException::class.java) {
                CarPlayRuntimeConfig(
                    mfiTarget = MfiTarget.I2C,
                    linuxI2cPath = "/dev/i2c-1",
                    identification = identification(),
                    transport = CarPlayTransport.WIRELESS,
                    wirelessHotspotMode = WirelessHotspotMode.MANUAL,
                    manualHotspotSsid = "Lynk",
                    manualHotspotPassphrase = "12345678",
                    manualHotspotHostAddress = address,
                )
            }
        }
    }

    @Test
    fun verifiedEcarxInterfaceRequiresAndPassesPairedEndpoint() {
        val config = CarPlayRuntimeConfig(
            mfiTarget = MfiTarget.I2C,
            linuxI2cPath = "/dev/i2c-1",
            identification = identification(),
            transport = CarPlayTransport.WIRELESS,
            wirelessHotspotMode = WirelessHotspotMode.MANUAL,
            manualHotspotSsid = "Lynk",
            manualHotspotPassphrase = "12345678",
            manualHotspotHostAddress = "198.18.34.15",
            manualHotspotInterfaceName = "eth0.11",
        )
        assertEquals("198.18.34.15", config.manualHotspotHostAddress)
        assertEquals("eth0.11", config.manualHotspotInterfaceName)
    }

    @Test
    fun verifiedEcarxInterfaceRequiresValidAddressAndPlainInterfaceName() {
        assertThrows(IllegalArgumentException::class.java) {
            CarPlayRuntimeConfig(
                mfiTarget = MfiTarget.I2C,
                linuxI2cPath = "/dev/i2c-1",
                identification = identification(),
                transport = CarPlayTransport.WIRELESS,
                wirelessHotspotMode = WirelessHotspotMode.MANUAL,
                manualHotspotSsid = "Lynk",
                manualHotspotPassphrase = "12345678",
                manualHotspotInterfaceName = "eth0.11",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CarPlayRuntimeConfig(
                mfiTarget = MfiTarget.I2C,
                linuxI2cPath = "/dev/i2c-1",
                identification = identification(),
                transport = CarPlayTransport.WIRELESS,
                wirelessHotspotMode = WirelessHotspotMode.MANUAL,
                manualHotspotSsid = "Lynk",
                manualHotspotPassphrase = "12345678",
                manualHotspotHostAddress = "198.18.34.15",
                manualHotspotInterfaceName = "eth0.11;bad",
            )
        }
    }

    @Test
    fun aMissingManualHotspotHostAddressIsAllowedUpstream() {
        // 空 ⇒ 与改动前完全一致（manager 自己按网卡名打分挑）。
        // 这条同时护着 P2P 那条路：它被翻译成 MANUAL 形状但不带这个字段。
        val config = config(CarPlayTransport.WIRELESS, ssid = "Lynk", passphrase = "12345678")

        assertNull(config.manualHotspotHostAddress)
    }

    private fun config(
        transport: CarPlayTransport,
        ssid: String?,
        passphrase: String? = null,
    ): CarPlayRuntimeConfig = CarPlayRuntimeConfig(
        mfiTarget = MfiTarget.I2C,
        linuxI2cPath = "/dev/i2c-1",
        identification = identification(),
        transport = transport,
        wirelessHotspotMode = WirelessHotspotMode.MANUAL,
        manualHotspotSsid = ssid,
        manualHotspotPassphrase = passphrase,
    )

    private fun identification(): Iap2IdentificationConfig = Iap2IdentificationConfig(
        name = "test",
        modelIdentifier = "test",
        manufacturer = "test",
        serialNumber = "test",
        firmwareVersion = "1",
        hardwareVersion = "1",
        carPlayUsbInterfaceNumber = 3,
    )
}
