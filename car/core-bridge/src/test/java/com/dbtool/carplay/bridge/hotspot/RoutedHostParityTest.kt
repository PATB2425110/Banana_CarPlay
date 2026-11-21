package com.dbtool.carplay.bridge.hotspot

import com.dbtool.carplay.api.hotspot.CarGeelyRoutedHost
import com.shilapi.xcertplay.network.GeelyRoutedHotspotManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetAddress

/**
 * 两份「车机热点」判据的**交叉一致性闸门**（2026-10-25）。
 *
 * ## 为什么会有两份
 *
 * - `:shared` 的 [GeelyRoutedHotspotManager] 是**上游基础层**，`:car:*` 依赖它，
 *   不能反向依赖 ⇒ 它必须自带一份判据；
 * - `:car:api` 的 [CarGeelyRoutedHost] 是**零依赖契约层**（`build.gradle.kts` 写明
 *   "Deliberately dependency-free"），供 UI / 设置页 / 单测使用 ⇒ 它也必须自带一份。
 *
 * 两份口径必须完全相同，否则会出现「设置页放行、manager 拒绝」或反之。
 *
 * ## 本测试的作用
 *
 * `:car:core-bridge` 同时依赖 `:car:api`（`api`）与 `:shared`（`implementation`），
 * 是唯一能同时看到两者的模块。这里用**同一组输入矩阵**跑两份实现并断言结果相等：
 * 任何人只改一边、忘了另一边，这个测试立刻变红。
 *
 * 它不测「行为是否正确」（那由各自模块的单测负责），只测「两份是否一致」。
 */
class RoutedHostParityTest {

    private fun addr(s: String): InetAddress = InetAddress.getByName(s)

    /** 地址判据的输入矩阵：接口名 × 地址。 */
    @Test
    fun `地址判据两份实现结果一致`() {
        val names = listOf(
            "eth0", "eth0.11", "eth1", "eth10", "eth", "ETH0", "wlan0", "lo", "",
        )
        val addrs = listOf(
            "198.18.34.15", "198.18.0.1", "198.19.0.1", "198.17.0.1",
            "192.168.1.9", "0.0.0.0", "255.255.255.255", "::1",
        )
        val mismatches = ArrayList<String>()
        for (name in names) {
            for (raw in addrs) {
                val a = addr(raw)
                val shared = GeelyRoutedHotspotManager.isVehicleAddress(name, a)
                val api = CarGeelyRoutedHost.isRoutedInterface(name) &&
                    CarGeelyRoutedHost.isRoutedAddress(a)
                if (shared != api) {
                    mismatches.add("iface='$name' addr=$raw shared=$shared api=$api")
                }
            }
        }
        assertEquals("地址判据出现漂移：\n" + mismatches.joinToString("\n"), 0, mismatches.size)
    }

    /** null 输入：两边都必须安全返回 false（不能 NPE）。 */
    @Test
    fun `null 输入两份实现都拒绝`() {
        assertEquals(
            GeelyRoutedHotspotManager.isVehicleAddress(null, null),
            CarGeelyRoutedHost.isRoutedInterface(null) && CarGeelyRoutedHost.isRoutedAddress(null),
        )
        assertEquals(false, GeelyRoutedHotspotManager.isVehicleAddress(null, null))
    }

    /** 选择排序：eth0 优先 + 接口名字典序 + 同接口保留枚举顺序。 */
    @Test
    fun `选择宿主两份实现结果一致`() {
        val cases = listOf(
            listOf("eth1" to addr("198.18.1.2"), "eth0" to addr("198.18.34.15")),
            listOf("eth2" to addr("198.18.1.2"), "eth10" to addr("198.18.1.3")),
            listOf("eth0" to addr("192.168.1.9"), "eth1" to addr("198.18.34.15")),
            listOf("wlan0" to addr("198.18.34.15")),
            listOf("eth0" to addr("198.18.34.20"), "eth0" to addr("198.18.34.15")),
            emptyList(),
        )
        for (case in cases) {
            val shared = GeelyRoutedHotspotManager.selectHost(case)
            val api = CarGeelyRoutedHost.selectHost(case)
            assertEquals(
                "选择结果漂移，输入=$case",
                shared?.first to shared?.second?.hostAddress,
                api?.first to api?.second?.hostAddress,
            )
        }
    }

    /** 机型门禁矩阵。 */
    @Test
    fun `机型门禁两份实现结果一致`() {
        val models = listOf(
            "CS11", "cs11", " CS11 ", "QUALCOMM CS11", "qualcomm cs11 ",
            "CX11", "QUALCOMM CX11",
            "KX11", "kx11", "KX11 ", "UNKNOWN", "", "CS12",
        )
        val devices = listOf("cs11_high", "cs11", "kx11", "KX11", "", "other")
        val sdks = listOf(27, 28, 29)
        val mismatches = ArrayList<String>()
        for (m in models) {
            for (d in devices) {
                for (s in sdks) {
                    val shared = GeelyRoutedHotspotManager.usesRoutedHotspot(m, d, s)
                    val api = CarGeelyRoutedHost.usesRoutedHotspot(m, d, s)
                    if (shared != api) {
                        mismatches.add("model='$m' device='$d' sdk=$s shared=$shared api=$api")
                    }
                }
            }
        }
        assertEquals("机型门禁出现漂移：\n" + mismatches.joinToString("\n"), 0, mismatches.size)
    }

    /** 命中计数也要一致（诊断日志里的 candidates=N）。 */
    @Test
    fun `候选计数两份实现结果一致`() {
        val case = listOf(
            "eth0" to addr("198.18.34.15"),
            "eth1" to addr("198.18.34.16"),
            "eth2" to addr("192.168.1.9"),
            "wlan0" to addr("198.18.34.17"),
        )
        assertEquals(
            CarGeelyRoutedHost.candidateCount(case),
            case.count { (n, a) -> GeelyRoutedHotspotManager.isVehicleAddress(n, a) },
        )
        assertEquals(2, CarGeelyRoutedHost.candidateCount(case))
    }

    /** 空输入两份都返回 null（不能崩）。 */
    @Test
    fun `空列表两份都返回 null`() {
        assertNull(GeelyRoutedHotspotManager.selectHost(emptyList()))
        assertNull(CarGeelyRoutedHost.selectHost(emptyList()))
    }
}
