package com.dbtool.carplay.bridge.hotspot

import com.dbtool.carplay.api.hotspot.CarHotspotProbeSource
import com.dbtool.carplay.api.hotspot.CarNativeHotspotInfo
import com.dbtool.carplay.api.hotspot.CarNativeHotspotMerge
import com.dbtool.carplay.api.settings.CarHotspotBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「ADB 侧 + 本地反射」两份探测结果怎么合。
 *
 * ## 为什么要专门钉它
 *
 * 这段逻辑原来埋在 `CarNativeHotspotProbe` 里（那个类要 `Context` 与 adb 会话，
 * 纯 JVM 单测构造不了 ⇒ **一直没有用例**），但它里面有两处优先级都是踩过坑才定的：
 *
 * 1. **`ssid` 存储配置优先于实际广播名**（第 3、4 条）。上游
 *    `ManualHotspotManager` 拿**存储配置**校验我们发出去的名字，把"实际广播名"
 *    盖上去会立刻被拒（实车原文
 *    `Manual hotspot SSID does not match the active local AP configuration: 'Lynk'`，
 *    用户当时填 `car` 就是这么被拒的）；只有两边都读不到存储配置时才退到实际名。
 * 2. **`source` 与 `credentialSource` 必须分开**（第 1、5 条）。实车上
 *    SSID/密码来自**本地反射**、网卡名与地址来自 **adb**，只打一个
 *    `source=ADB_SHELL` 会让读包的人以为凭据也来自 adb —— 而磁盘上那几份
 *    配置文件（`hostapd.conf` 模板、老 `softap.conf`、没有 SoftAp 段的
 *    `WifiConfigStore.xml`）**全是过期的**。
 */
class CarNativeHotspotMergeTest {

    /** 实车形态：adb 只给了网卡与地址，SSID/密码是本地反射给的。 */
    @Test
    fun `实车形态：凭据来自本地反射时，source 与 credentialSource 必须分开`() {
        val adb = CarNativeHotspotInfo(
            detected = true,
            enabled = true,
            interfaceName = "wlan0",
            hostAddress = "192.168.175.1",
            prefixLength = 24,
            source = CarHotspotProbeSource.ADB_SHELL,
        )
        val local = CarNativeHotspotInfo(
            detected = true,
            ssid = "Lynk",
            passphrase = "12345678",
            source = CarHotspotProbeSource.LOCAL_REFLECTION,
        )

        val merged = CarNativeHotspotMerge.merge(adb, local)

        assertEquals("Lynk", merged.ssid)
        assertEquals("12345678", merged.passphrase)
        assertEquals("wlan0", merged.interfaceName)
        // 整体来源仍是 adb（网卡、地址确实是 adb 读的）
        assertEquals(CarHotspotProbeSource.ADB_SHELL, merged.source)
        // 但凭据来源必须如实说是本地反射
        assertEquals(CarHotspotProbeSource.LOCAL_REFLECTION, merged.credentialSource)
        // 并且要给出一句提醒，否则读包的人会把磁盘上那份过期的配置当成热点名
        assertTrue(merged.credentialSourceNote!!.contains("别拿它当车机热点名"))
    }

    /** 凭据确实来自 adb 时不该多嘴。 */
    @Test
    fun `凭据来自 adb 时不多一句提醒`() {
        val adb = CarNativeHotspotInfo(
            detected = true,
            ssid = "Lynk",
            passphrase = "12345678",
            source = CarHotspotProbeSource.ADB_SHELL,
        )
        val local = CarNativeHotspotInfo(
            detected = true,
            ssid = "LYNK&CO",
            source = CarHotspotProbeSource.LOCAL_REFLECTION,
        )

        val merged = CarNativeHotspotMerge.merge(adb, local)

        assertEquals("Lynk", merged.ssid)
        assertEquals(CarHotspotProbeSource.ADB_SHELL, merged.credentialSource)
        assertNull(merged.credentialSourceNote)
    }

    /** 上游拿存储配置校验，所以"实际广播名"绝不能盖掉存储配置。 */
    @Test
    fun `ssid 取存储配置而不是实际广播名，并且判出不一致`() {
        val adb = CarNativeHotspotInfo(
            detected = true,
            runningSsid = "car",
            source = CarHotspotProbeSource.ADB_SHELL,
        )
        val local = CarNativeHotspotInfo(
            detected = true,
            ssid = "Lynk",
            source = CarHotspotProbeSource.LOCAL_REFLECTION,
        )

        val merged = CarNativeHotspotMerge.merge(adb, local)

        // 发出去的是存储配置名 —— 盖成 car 会被上游直接拒
        assertEquals("Lynk", merged.ssid)
        // 实际广播名单独留着，好让不一致被发现
        assertEquals("car", merged.runningSsid)
        assertTrue(merged.ssidMismatch)
        assertTrue(merged.ssidMismatchNote!!.contains("car"))
        assertTrue(merged.ssidMismatchNote!!.contains("Lynk"))
    }

    /** 两边都没有存储配置时才退到实际广播名（它至少是真实存在的网络）。 */
    @Test
    fun `存储配置两边都读不到时才退到实际广播名`() {
        val adb = CarNativeHotspotInfo(
            detected = true,
            source = CarHotspotProbeSource.ADB_SHELL,
        )
        val local = CarNativeHotspotInfo(
            detected = true,
            runningSsid = "car",
            source = CarHotspotProbeSource.LOCAL_REFLECTION,
        )

        val merged = CarNativeHotspotMerge.merge(adb, local)

        assertEquals("car", merged.ssid)
        // 退到实际名之后两边一致，不该再报不一致
        assertFalse(merged.ssidMismatch)
    }

    /** 两边都没探到 ⇒ 原样返回 adb 那份，不凭缺失造信息。 */
    @Test
    fun `两边都没探到时原样返回且不造信息`() {
        val adb = CarNativeHotspotInfo(source = CarHotspotProbeSource.NONE)
        val local = CarNativeHotspotInfo(
            ssid = "Lynk",
            passphrase = "12345678",
            source = CarHotspotProbeSource.LOCAL_REFLECTION,
        )

        val merged = CarNativeHotspotMerge.merge(adb, local)

        assertFalse(merged.detected)
        assertFalse(merged.enabled)
        assertNull(merged.ssid)
        assertNull(merged.passphrase)
        assertEquals(CarHotspotProbeSource.NONE, merged.source)
    }

    /** band / channel 只在 adb 没给出来时才用本地的（adb 的 0 / AUTO 等于"没读到"）。 */
    @Test
    fun `band 与 channel 的兜底顺序`() {
        val adb = CarNativeHotspotInfo(
            detected = true,
            band = CarHotspotBand.AUTO,
            channel = 0,
            source = CarHotspotProbeSource.ADB_SHELL,
        )
        val local = CarNativeHotspotInfo(
            detected = true,
            band = CarHotspotBand.GHZ_2_4,
            channel = 6,
            source = CarHotspotProbeSource.LOCAL_REFLECTION,
        )

        val merged = CarNativeHotspotMerge.merge(adb, local)

        assertEquals(CarHotspotBand.GHZ_2_4, merged.band)
        assertEquals(6, merged.channel)
    }

    /** adb 真给出了 band / channel 时以 adb 为准。 */
    @Test
    fun `adb 给出 band 与 channel 时不看本地`() {
        val adb = CarNativeHotspotInfo(
            detected = true,
            band = CarHotspotBand.GHZ_5,
            channel = 36,
            source = CarHotspotProbeSource.ADB_SHELL,
        )
        val local = CarNativeHotspotInfo(
            detected = true,
            band = CarHotspotBand.GHZ_2_4,
            channel = 6,
            source = CarHotspotProbeSource.LOCAL_REFLECTION,
        )

        val merged = CarNativeHotspotMerge.merge(adb, local)

        assertEquals(CarHotspotBand.GHZ_5, merged.band)
        assertEquals(36, merged.channel)
    }

    /** DNS 是两边的并集去重；本机出现过的地址则 adb 有就整份用 adb 的。 */
    @Test
    fun `DNS 取并集去重，已占用地址取非空的那份`() {
        val adb = CarNativeHotspotInfo(
            detected = true,
            dns = listOf("8.8.8.8"),
            occupiedAddresses = listOf("10.0.0.1"),
            source = CarHotspotProbeSource.ADB_SHELL,
        )
        val local = CarNativeHotspotInfo(
            detected = true,
            dns = listOf("8.8.8.8", "1.1.1.1"),
            occupiedAddresses = listOf("192.168.1.1"),
            source = CarHotspotProbeSource.LOCAL_REFLECTION,
        )

        val merged = CarNativeHotspotMerge.merge(adb, local)

        assertEquals(listOf("8.8.8.8", "1.1.1.1"), merged.dns)
        assertEquals(listOf("10.0.0.1"), merged.occupiedAddresses)
    }

    /** adb 侧没探到时，整体来源取本地反射。 */
    @Test
    fun `adb 没探到时整体来源取本地反射`() {
        val adb = CarNativeHotspotInfo(source = CarHotspotProbeSource.ADB_SHELL)
        val local = CarNativeHotspotInfo(
            detected = true,
            ssid = "Lynk",
            source = CarHotspotProbeSource.LOCAL_REFLECTION,
        )

        val merged = CarNativeHotspotMerge.merge(adb, local)

        assertEquals(CarHotspotProbeSource.LOCAL_REFLECTION, merged.source)
        assertEquals(CarHotspotProbeSource.LOCAL_REFLECTION, merged.credentialSource)
        assertNull(merged.credentialSourceNote)
    }
}
