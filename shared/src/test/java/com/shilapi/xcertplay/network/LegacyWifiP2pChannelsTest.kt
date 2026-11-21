package com.shilapi.xcertplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LegacyWifiP2pChannels] —— Android 9 建组信道计划（2026-10-11，对齐 DiPlay 的 legacy 路）。
 *
 * ## 为什么这几条必须钉死
 *
 * 真车（CS11，设备 `67b3c24af6b5`）旧实现是"同一个请求重试 4 次"，4 次全在 5ms 内回 BUSY
 * 就判死 —— 而 DiPlay 的口径是 **`BUSY` 只代表"这个信道被拒"**，必须继续走 2.4GHz 与
 * 系统默认重载。计划本身（顺序、去重、最后一步是"不指定信道"）就是那条修法的核心，
 * 所以在这里钉住。
 */
class LegacyWifiP2pChannelsTest {

    @Test
    fun `计划先 5GHz 非 DFS，再 2_4GHz，最后是系统默认重载`() {
        val plan = LegacyWifiP2pChannels.planChannels()
        assertEquals("顺序: ${plan}", 5180, plan[0])
        assertEquals(5745, plan[1])
        assertEquals(2437, plan[2])
        assertEquals(2412, plan[3])
        assertEquals(2462, plan[4])
        assertNull("最后一步必须是「不指定信道」= 系统默认重载", plan.last())
    }

    @Test
    fun `计划里不出现 DFS 信道，且不重复`() {
        val plan = LegacyWifiP2pChannels.planChannels()
        val channels = plan.filterNotNull()
        // 5GHz 只取 36/149（非 DFS 的常用两个）；52~144 是 DFS，车机未必允许。
        assertTrue("不该出现 DFS 信道：$channels", channels.none { it in 52..144 })
        assertEquals("不该有重复信道：$channels", channels.size, channels.toSet().size)
    }

    @Test
    fun `频率与信道号互转与 DiPlay 同口径`() {
        assertEquals(36, LegacyWifiP2pChannels.operatingChannelFor(5180))
        assertEquals(149, LegacyWifiP2pChannels.operatingChannelFor(5745))
        assertEquals(6, LegacyWifiP2pChannels.operatingChannelFor(2437))
        assertEquals(1, LegacyWifiP2pChannels.operatingChannelFor(2412))
        assertEquals(11, LegacyWifiP2pChannels.operatingChannelFor(2462))
        // 计划里的每个频率都必须映射得出信道（否则那一步会被跳过，等于白写）。
        LegacyWifiP2pChannels.planChannels().filterNotNull().forEach {
            assertTrue("$it MHz 必须能映射成信道", LegacyWifiP2pChannels.operatingChannelFor(it) != null)
        }
        assertNull("DFS / 未知频率映射不出来要返回 null，而不是猜一个", LegacyWifiP2pChannels.operatingChannelFor(5500))
    }

    @Test
    fun `监听信道固定 6（与 DiPlay 常量一致）`() {
        assertEquals(6, LegacyWifiP2pChannels.LISTEN_CHANNEL)
    }
}
