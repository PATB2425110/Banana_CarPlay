package com.dbtool.carplay.bridge.hotspot

import com.dbtool.carplay.api.hotspot.CarNativeHotspotCredentials
import com.dbtool.carplay.api.hotspot.CarNativeHotspotInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 车机热点真实凭据的提取判据（2026-10-13，设置页「一键填入」用）。
 *
 * 判错一次的代价是"用户以为填对了"：上游拿填进去的 SSID 去校验配置，
 * 对不上就**跳过全部校验、静默失败**（见 `CarManualHotspotMatch` 的类注释）——
 * 所以"什么时候能一键填、填什么"必须被单测钉死，尤其：
 *
 * 1. 探测不到车机热点 ⇒ **不给**（凭缺失下结论会把用户引去填一个读不准的值）；
 * 2. 密码读不到（厂商置空 / 开放网络）⇒ 只填名称，`passphrase = null`，
 *    界面据此**不动密码框**。
 */
class CarNativeHotspotCredentialsTest {

    private fun info(
        detected: Boolean = true,
        ssid: String? = "Lynk & Co",
        passphrase: String? = "12345678",
    ) = CarNativeHotspotInfo(detected = detected, ssid = ssid, passphrase = passphrase)

    @Test
    fun `探测到车机热点时给出名称与密码`() {
        val credentials = CarNativeHotspotCredentials.of(info())
        assertEquals("Lynk & Co", credentials?.ssid)
        assertEquals("12345678", credentials?.passphrase)
    }

    @Test
    fun `探测不到时不给凭据`() {
        // 判不了就是不给：车机热点可能还没开，这时给任何"真实凭据"都是假的。
        assertNull(CarNativeHotspotCredentials.of(info(detected = false)))
    }

    @Test
    fun `没探测过时不给凭据`() {
        assertNull(CarNativeHotspotCredentials.of(null))
    }

    @Test
    fun `没读到热点名时不给凭据`() {
        assertNull(CarNativeHotspotCredentials.of(info(ssid = null)))
        assertNull(CarNativeHotspotCredentials.of(info(ssid = "   ")))
    }

    @Test
    fun `密码读不到时只给名称_密码归一为 null`() {
        // 厂商可能把 getPassphrase() 置空（或 WPA3-SAE 没有静态密码）。
        // 界面据此只填名称、不动密码框 —— 拿到 null 而不是空串是关键。
        val credentials = CarNativeHotspotCredentials.of(info(passphrase = null))
        assertEquals("Lynk & Co", credentials?.ssid)
        assertNull(credentials?.passphrase)

        assertNull(
            "空密码串也要归一为 null，不能拿空串清掉用户已填的密码",
            CarNativeHotspotCredentials.of(info(passphrase = "  "))?.passphrase,
        )
    }

    @Test
    fun `名称取存储配置名`() {
        // 填入的值要交给上游 `ManualHotspotManager` 校验，校验的正是存储配置名，
        // 不是 iw dev 读到的 runningSsid —— 两者在车机上可以不一致。
        val credentials = CarNativeHotspotCredentials.of(
            info(ssid = "Lynk", passphrase = "12345678"),
        )
        assertEquals("Lynk", credentials?.ssid)
    }
}
