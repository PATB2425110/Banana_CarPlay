package com.dbtool.carplay.bridge.hotspot

import com.dbtool.carplay.api.hotspot.CarNativeHotspotInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「这张车机热点该不该复用」判据的单测。
 *
 * ## 为什么这条判据必须钉死（2026-09-30 实车）
 *
 * 复用时发给 iPhone 的 SSID **只能**是存储配置名（上游 `ManualHotspotManager`
 * 会拿它跟 `getWifiApConfiguration()` 硬校验，传实际广播名会被直接拒绝）。
 * 而 iPhone 只能关联**实际在广播**的那个名字。
 *
 * ⇒ 当"存储配置名 ≠ 实际广播名"时，复用等于**把一个没人广播的名字发给 iPhone**：
 * 关联不上 → iAP2 无线隧道起不来 → 界面停在「无线通道就绪」，
 * **而整条链路上没有一句报错**。
 *
 * 实车数据（2026-09-30）：
 * - `getWifiApConfiguration()`（存储配置）→ `Lynk`
 * - `iw dev`（实际广播，`type AP`）→ `car`
 * - 结果：卡在「无线通道就绪」；封面能投（走蓝牙 iAP2），音视频上不来（走 AirPlay over Wi-Fi）
 *
 * 所以"能不能复用"不能只看"有没有读到 SSID"，必须把"广播名与配置名一致"也算进去。
 */
class CarNativeHotspotReuseTest {

    private fun info(
        detected: Boolean = true,
        ssid: String? = "Lynk",
        runningSsid: String? = null,
        interfaceName: String? = "wlan0",
        hostAddress: String? = "192.168.176.1",
    ) = CarNativeHotspotInfo(
        detected = detected,
        ssid = ssid,
        runningSsid = runningSsid,
        interfaceName = interfaceName,
        hostAddress = hostAddress,
    )

    /** 正常情况：广播名与配置名一致 ⇒ 可以复用。 */
    @Test
    fun `广播名与配置名一致时可以复用`() {
        val it = info(ssid = "Lynk", runningSsid = "Lynk")
        assertTrue(it.reusable)
        assertTrue(it.safeToReuse)
    }

    /**
     * 实车那一组：配置 `Lynk`、实际广播 `car`。
     *
     * `reusable` 仍为 true（信息够 attach），但 `safeToReuse` 必须为 false ——
     * 因为复用会把 `Lynk` 发给 iPhone，而它没有被广播。
     */
    @Test
    fun `广播名与配置名不一致时不能复用`() {
        val it = info(ssid = "Lynk", runningSsid = "car")
        assertTrue("信息本身够 attach，所以 reusable 应为 true", it.reusable)
        assertFalse("但复用注定失败：发给 iPhone 的名字没人广播", it.safeToReuse)
    }

    /** 读不到实际广播名时**不能**凭缺失判定不一致（那会把无害情况判成致命）。 */
    @Test
    fun `读不到实际广播名时仍可复用`() {
        val it = info(ssid = "Lynk", runningSsid = null)
        assertFalse(it.ssidMismatch)
        assertTrue(it.safeToReuse)
    }

    /** 空串与 null 同义（探测层"读不到就留空"，不填默认值）。 */
    @Test
    fun `实际广播名为空串时不算不一致`() {
        val it = info(ssid = "Lynk", runningSsid = "  ")
        assertFalse(it.ssidMismatch)
        assertTrue(it.safeToReuse)
    }

    /** 没探测到热点 ⇒ 谈不上复用（走自建）。 */
    @Test
    fun `没探测到热点时不能复用`() {
        val it = info(detected = false, ssid = null, runningSsid = null)
        assertFalse(it.reusable)
        assertFalse(it.safeToReuse)
    }

    /** 探测到了但没读到名称 ⇒ 不能复用（信息不够）。 */
    @Test
    fun `没读到配置名时不能复用`() {
        val it = info(ssid = null, runningSsid = "car")
        assertFalse(it.reusable)
        assertFalse(it.safeToReuse)
        assertFalse(it.actuallyRunning)
    }

    // ------------------------------------------------------------------
    // ⚠️ 2026-10-03 实车（CS11）补：`actuallyRunning` —— 光有名字不够，
    // 还得真有一张 AP 在跑（`iface` 与 `addr` 都非空）。
    //
    // 现场日志（设备 d5ddb4312da1，16:12）：
    //   `detected=true enabled=true ssid=Lynk iface=null addr=null channel=0`
    // 原判据把这一行当成了"可复用"，于是抓了有线口 eth0.3 通告给 iPhone。
    // ------------------------------------------------------------------

    /**
     * 实车那一行：名字读到了（Lynk），但**没有任何网卡承载它**。
     *
     * `reusable` 仍为 true（名字这条信息本身够 attach，不改数据类原语义），
     * 但 `actuallyRunning` 必须为 false —— 决策分支据此改走自建。
     */
    @Test
    fun `读到名字但没有网卡承载时不算真的在跑`() {
        val it = info(ssid = "Lynk", runningSsid = null, interfaceName = null, hostAddress = null)
        assertTrue("名字信息本身够 attach，reusable 保持原语义", it.reusable)
        assertFalse("但没有任何网卡承载它，热点实际没开", it.actuallyRunning)
    }

    /** 网卡名有、地址没有（或反之）同样不算"真的在跑"——两者缺一不可。 */
    @Test
    fun `网卡名与地址缺一则不算真的在跑`() {
        assertFalse(info(interfaceName = "wlan0", hostAddress = null).actuallyRunning)
        assertFalse(info(interfaceName = null, hostAddress = "192.168.176.1").actuallyRunning)
    }

    /** 正常情况：名字、网卡、地址俱全 ⇒ 真的在跑。 */
    @Test
    fun `名字网卡地址俱全时算真的在跑`() {
        val it = info(ssid = "Lynk", runningSsid = "Lynk")
        assertTrue(it.actuallyRunning)
    }
}
