package com.shilapi.xcertplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManualHotspotInterfaceSelectionTest {
    @Test
    fun `strict override allows verified primary eth interface only with exact address`() {
        val candidates = listOf(
            ManualHotspotInterfaceCandidate(
                name = "eth0.11",
                isUp = true,
                isLoopback = false,
                ipv4Addresses = listOf("198.18.34.15"),
                hardwareAddress = null,
            ),
        )

        assertEquals(
            "eth0.11",
            ManualHotspotInterfaceSelection.selectStrict(candidates, "eth0.11", "198.18.34.15")?.name,
        )
        assertNull(ManualHotspotInterfaceSelection.selectStrict(candidates, "eth0.11", "192.168.15.1"))
        assertNull(ManualHotspotInterfaceSelection.selectStrict(candidates, "eth0.3", "198.18.34.15"))
    }

    /**
     * 严格选择的语义：**接口名是调用方给的（来自已验证路由），所以名字本身就能消歧。**
     *
     * 同一个地址同时挂在两张网卡上**不是**歧义 —— 我们按名字取，不按地址猜。
     * 这条把该语义显式钉住，免得后人误以为"同地址多网卡"要拒绝（那会平白判死能用的配置）。
     */
    @Test
    fun `explicit interface name disambiguates an address that also appears elsewhere`() {
        val sharedAddress = listOf(
            ManualHotspotInterfaceCandidate("eth0.11", true, false, listOf("198.18.34.15"), null),
            ManualHotspotInterfaceCandidate("eth0.3", true, false, listOf("198.18.34.15"), null),
        )

        assertEquals(
            "eth0.11",
            ManualHotspotInterfaceSelection.selectStrict(sharedAddress, "eth0.11", "198.18.34.15")?.name,
        )
        assertEquals(
            "eth0.3",
            ManualHotspotInterfaceSelection.selectStrict(sharedAddress, "eth0.3", "198.18.34.15")?.name,
        )
    }

    /**
     * 该 fail-closed 的三种情况：接口 DOWN / 回环 / **目标地址不在指定接口上**
     * （哪怕另一张网卡恰好持有它 —— 那是跨接口兜底，正是要禁止的）。
     */
    @Test
    fun `strict override fails closed for down loopback or cross-interface address`() {
        val down = listOf(
            ManualHotspotInterfaceCandidate("eth0.11", false, false, listOf("198.18.34.15"), null),
        )
        val loopback = listOf(
            ManualHotspotInterfaceCandidate("eth0.11", true, true, listOf("198.18.34.15"), null),
        )
        // 指定接口在、地址不在它上面；另一张网卡持有该地址 ⇒ 必须拒绝，不得跨接口取。
        val crossInterface = listOf(
            ManualHotspotInterfaceCandidate("eth0.11", true, false, listOf("198.18.34.16"), null),
            ManualHotspotInterfaceCandidate("eth0.3", true, false, listOf("198.18.34.15"), null),
        )

        assertNull(ManualHotspotInterfaceSelection.selectStrict(down, "eth0.11", "198.18.34.15"))
        assertNull(ManualHotspotInterfaceSelection.selectStrict(loopback, "eth0.11", "198.18.34.15"))
        assertNull(ManualHotspotInterfaceSelection.selectStrict(crossInterface, "eth0.11", "198.18.34.15"))
    }
}
