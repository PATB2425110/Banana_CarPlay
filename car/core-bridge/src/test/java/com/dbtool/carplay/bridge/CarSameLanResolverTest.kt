package com.dbtool.carplay.bridge

import com.dbtool.carplay.api.settings.CarHotspotMode
import com.dbtool.carplay.api.settings.CarSessionSettings
import com.dbtool.carplay.bridge.network.CarHotspotResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「局域网连接」模式（PATCH-0010）的解析层单测。
 *
 * ## 为什么这些断言要钉死
 *
 * 这一支的失败**全部发生在车机上看起来正常的时候**：车机连着一张网、
 * 我们通告了一个地址、界面进入"等待连接"，然后永远停在等待。
 * 现场唯一的线索就是 plan 的 summary 与 targetLabel。
 * 所以这里重点卡三件事：
 *
 * 1. [CarHotspotMode.SAME_LAN] 必须落在**真正不建热点**的那一支
 *    （`mode` 不能被改写成 ADB_HOSTAPD / MANUAL）；
 * 2. 手填地址的合法性在**解析阶段**就被判掉，而不是拖到上游抛异常；
 * 3. 读不到地址时 summary 必须**说清原因**，而不是留一句"未知"。
 *
 * ⚠️ 这里用的是 `withoutProbe()`（没有 Context），所以"自动读取"那一路
 * 走的是"如实报告读不到"。真正的 `CarSameLanReader` 依赖 `ConnectivityManager`，
 * 属于 Android 框架调用，无法在 JVM 单测里覆盖——那部分靠设置页的
 * 「本次将使用」那一行在真机上看。
 */
class CarSameLanResolverTest {

    private fun settings(
        address: String = "",
        ssid: String = "DBPlay",
        passphrase: String = "12345678",
        wifiSsid: String = "",
        wifiPass: String = "",
    ) = CarSessionSettings(
        hotspotMode = CarHotspotMode.SAME_LAN,
        sameLanHostAddress = address,
        sameLanWifiSsid = wifiSsid,
        sameLanWifiPassphrase = wifiPass,
        manualHotspotSsid = ssid,
        manualHotspotPassphrase = passphrase,
    )

    @Test
    fun `模式必须保持 SAME_LAN，不许被改成自建`() {
        val plan = CarHotspotResolver.withoutProbe(settings(address = "192.168.1.5"))
        assertEquals(
            "这一支的全部意义就是不建热点，mode 被改写等于整条推出错",
            CarHotspotMode.SAME_LAN,
            plan.mode,
        )
        assertFalse("局域网连接不是复用别人的热点（那是 MANUAL）", plan.reused)
        // 不建热点 ⇒ 没有自建网络参数可谈。
        assertNull("SAME_LAN 不该带 adbNetwork，带了说明有人误加了自建逻辑", plan.adbNetwork)
    }

    @Test
    fun `手填合法 IPv4 时直接可用`() {
        val plan = CarHotspotResolver.withoutProbe(settings(address = "192.168.1.5"))
        assertTrue(plan.summary.contains("192.168.1.5"))
        assertEquals("局域网连接：192.168.1.5", plan.targetLabel)
        assertEquals("192.168.1.5", plan.sameLan?.hostAddress)
        assertTrue(plan.sameLan?.connected == true)
    }

    @Test
    fun `手填非 IPv4 时判为不可用并写明原因`() {
        val plan = CarHotspotResolver.withoutProbe(settings(address = "not-an-ip"))
        assertFalse(
            "非法地址必须在解析阶段就被判掉，否则要等上游抛异常才知道填错了",
            plan.sameLan?.connected == true,
        )
        assertNull(plan.sameLan?.hostAddress)
        assertTrue(
            "必须说清是哪个值不合法：${plan.summary}",
            plan.summary.contains("not-an-ip"),
        )
    }

    @Test
    fun `手填 IPv6 时判为不可用`() {
        // 我们自己的 AirPlay 监听与 mDNS 通告都按 IPv4 走，
        // 收一个 IPv6 进来属于"能配但必然连不上"，必须早拦。
        val plan = CarHotspotResolver.withoutProbe(settings(address = "fe80::1"))
        assertFalse(plan.sameLan?.connected == true)
        assertTrue(plan.summary.contains("fe80::1"))
    }

    @Test
    fun `手填回环地址时判为不可用`() {
        val plan = CarHotspotResolver.withoutProbe(settings(address = "127.0.0.1"))
        assertFalse(
            "回环地址在 iPhone 那一侧不可达，通告出去等于让它去敲一扇不存在的门",
            plan.sameLan?.connected == true,
        )
    }

    @Test
    fun `手填 link-local 地址时判为不可用`() {
        // 169.254.x.x = DHCP 失败时的自动地址，同样在 iPhone 侧不可达。
        val plan = CarHotspotResolver.withoutProbe(settings(address = "169.254.10.20"))
        assertFalse(plan.sameLan?.connected == true)
    }

    @Test
    fun `没有 Context 且未手填时如实报告读不到地址`() {
        val plan = CarHotspotResolver.withoutProbe(settings(address = ""))
        assertFalse(
            "读不到就该报读不到，不能编一个地址出来",
            plan.sameLan?.connected == true,
        )
        assertTrue(
            "summary 必须给出下一步（去连 Wi-Fi 或手填），而不是一句干巴巴的失败：${plan.summary}",
            plan.summary.contains("Wi-Fi") || plan.summary.contains("地址"),
        )
    }

    @Test
    fun `通告给 iPhone 的 SSID 必须是设置里填的真实 Wi-Fi，绝不能用热点名顶替`() {
        // ⚠️ 2026-10-07 真机回归（诊断包 1aedc494）：
        // 旧实现把这里填成热点名 DBPlay，iPhone 于是去关联一张 SAME_LAN 下
        // **根本不存在**的网 ⇒ 永远不切 Wi-Fi ⇒ 40s 后 wireless attach timed out
        // ⇒ 界面停在「无线通道就绪」。
        //
        // 这条断言就是为了钉死这个回归：用户填的「我家WiFi」必须原样出去。
        val plan = CarHotspotResolver.withoutProbe(
            settings(address = "10.0.0.7", wifiSsid = "我家WiFi", wifiPass = "mypassword"),
        )
        assertEquals("我家WiFi", plan.sameLanWifiSsid)
        assertEquals("mypassword", plan.sameLanWifiPassphrase)
        assertFalse(
            "热点名 DBPlay 绝不能出现在通告字段里",
            plan.sameLanWifiSsid == "DBPlay",
        )
    }

    @Test
    fun `未填真实 Wi-Fi 时解析层如实留空（由上游配置层拦下，不在这里编造）`() {
        // 没有 Context（读不到真实网络）且用户没填 ⇒ 解析层如实给 null。
        //
        // ⚠️ 分工：**解析层不负责"必须填"这条纪律**，它只如实报告读到什么；
        // "SSID 不许为空"由 `CarPlayRuntimeConfig` 的 `require` + 会话启动时报错承担
        // （那里能给出可展示的中文提示）。
        // 这里钉死的是**不许回落到热点名**：编一个错的网络名去关联，
        // 是 2026-10-07 上午那个"iPhone 找不到网"的 bug 成因。
        val plan = CarHotspotResolver.withoutProbe(settings(address = "10.0.0.7"))
        assertNull("读不到就该如实留空，绝不能拿 DBPlay 顶替", plan.sameLanWifiSsid)
        assertTrue(
            "summary 必须提示用户去填 Wi-Fi 名称：${plan.summary}",
            plan.summary.contains("Wi-Fi") || plan.summary.contains("SSID"),
        )
    }

    @Test
    fun `手填真实 Wi-Fi 后 summary 写明将用哪个 SSID 关联`() {
        val plan = CarHotspotResolver.withoutProbe(
            settings(address = "10.0.0.7", wifiSsid = "OfficeNet", wifiPass = "pass1234"),
        )
        assertTrue(
            "日志/设置页要能一眼看出通告的是哪张网：${plan.summary}",
            plan.summary.contains("OfficeNet"),
        )
    }

    @Test
    fun `信道固定为 0（自动），不猜路由器的信道`() {
        val plan = CarHotspotResolver.withoutProbe(settings(address = "10.0.0.7"))
        assertEquals(
            "路由器用哪个信道我们无从得知，填一个具体值只会让 iPhone 做无谓的重新关联",
            0,
            plan.channel,
        )
    }
}
