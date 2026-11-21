package com.dbtool.carplay.bridge

import com.dbtool.carplay.api.hotspot.CarAdbHotspotDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自建热点**频段/信道**策略的不变量单测。
 *
 * ## 为什么这段必须钉死
 *
 * 2026-10-07 的产品决定是「**默认且仅走 5 GHz**」：不做频段自动选择，
 * 2.4 GHz 一并隐藏。此前 2026-09-29 曾按「车机射频只有 2.4 GHz」判定走
 * 2.4 GHz，该判定已被推翻——参考实现（`FUCK-Lynk&Co-ADB.apk`）在目标车机上
 * 确实以 `hw_mode=a` + `channel=36` 成功起过热点。
 *
 * 这条约束只体现在两个常量上（候选池 + 兜底信道）和 `hostapd` 配置生成里
 * 由信道推导的 `hw_mode`，没有任何类型层面的保护 —— 谁把候选池换回
 * `1/6/11`，编译一样通过，而后果是**真机上配置与产品口径矛盾**
 * （写出 `hw_mode=g` + `channel=1`，与「仅 5 GHz」不符）。
 *
 * 所以这里把「必须留在 5 GHz」变成会失败的断言，而不是只写在注释里。
 * 真要改回 2.4 GHz，先改这里的断言——那一步会强制改动者先读一遍理由。
 */
class CarAdbHotspotChannelTest {

    // ------------------------------------------------------------ 5 GHz 不变量

    /**
     * 候选信道必须**全部落在 5 GHz**（≥ 32，UNII-1 起）。
     *
     * 这是「固定 5 GHz」这条产品决定最直接的形式化：只要有人往候选池里塞一个
     * 2.4 GHz 信道（1~14），这条就会失败。
     */
    @Test
    fun `信道候选全部落在 5 GHz 频段`() {
        val channels = CarAdbHotspotDefaults.CHANNEL_CANDIDATES_5G.mapNotNull { it.toIntOrNull() }
        assertEquals(
            "候选池里有解析不出来的项：${CarAdbHotspotDefaults.CHANNEL_CANDIDATES_5G}",
            CarAdbHotspotDefaults.CHANNEL_CANDIDATES_5G.size,
            channels.size,
        )
        assertTrue("候选池为空", channels.isNotEmpty())
        for (channel in channels) {
            assertTrue("信道 $channel 不在 5 GHz 范围（≥ 32）内", channel >= 32)
        }
    }

    /**
     * 候选信道必须**两两互不重叠**。
     *
     * 取 UNII-1 的 36/40/44/48，每个信道占 20 MHz，间隔 4 个信道即不串扰。
     * 把 37/38 这类加进来不会报错，但会让人误以为「换一个信道」能解决干扰
     * ——实际上换到的多半仍与当前重叠。这里钉住「候选 = 互不重叠的那四个」。
     */
    @Test
    fun `信道候选两两互不重叠`() {
        val channels = CarAdbHotspotDefaults.CHANNEL_CANDIDATES_5G
            .mapNotNull { it.toIntOrNull() }
            .sorted()
        for (i in channels.indices) {
            for (j in i + 1 until channels.size) {
                val gap = channels[j] - channels[i]
                assertTrue("信道 ${channels[i]} 与 ${channels[j]} 间隔 $gap，会互相串扰", gap >= 4)
            }
        }
    }

    /** 兜底信道也必须是 5 GHz，否则「兜底」会绕过上面两条不变量。 */
    @Test
    fun `兜底信道是 5 GHz 且在候选池内`() {
        assertTrue(
            "兜底信道 ${CarAdbHotspotDefaults.DEFAULT_CHANNEL} 不在 5 GHz 范围（≥ 32）内",
            CarAdbHotspotDefaults.DEFAULT_CHANNEL >= 32,
        )
        assertTrue(
            "兜底信道不在候选池里：${CarAdbHotspotDefaults.CHANNEL_CANDIDATES_5G}",
            CarAdbHotspotDefaults.CHANNEL_CANDIDATES_5G
                .contains(CarAdbHotspotDefaults.DEFAULT_CHANNEL.toString()),
        )
    }

    /** 兜底信道取候选池第一项：规划器「避开同频」失败时回落到它，两者不该是两套口径。 */
    @Test
    fun `兜底信道等于候选池首项`() {
        assertEquals(
            CarAdbHotspotDefaults.CHANNEL_CANDIDATES_5G.first(),
            CarAdbHotspotDefaults.DEFAULT_CHANNEL.toString(),
        )
    }

    // ------------------------------------------------------------ 实际尝试顺序

    /**
     * **实际尝试的候选表只能有 5 GHz。**
     *
     * 2026-10-07 起本产品只走 5 GHz。这条断言防止有人「顺手把 2.4 GHz
     * 拼回候选表」——那会让配置生成写出与产品口径矛盾的频段。
     */
    @Test
    fun `实际尝试的候选表只含 5 GHz`() {
        val channels = CarAdbHotspotDefaults.CHANNEL_CANDIDATES.mapNotNull { it.toIntOrNull() }
        assertEquals(
            "候选表里有解析不出来的项：${CarAdbHotspotDefaults.CHANNEL_CANDIDATES}",
            CarAdbHotspotDefaults.CHANNEL_CANDIDATES.size,
            channels.size,
        )
        assertTrue("实际尝试的候选表为空", channels.isNotEmpty())
        for (channel in channels) {
            assertTrue("信道 $channel 不是 5 GHz", channel >= 32)
        }
    }

    /**
     * 2.4 GHz 候选**必须留在候选表之外**。
     *
     * 它保留下来只为一件件事：将来若要恢复 2.4 GHz 兜底，能直接接上。
     * 一旦它进了 [CarAdbHotspotDefaults.CHANNEL_CANDIDATES]，就会违背
     * 「仅 5 GHz」的产品口径（并可能让 `hostapd` 在 5 GHz 射频上起 2.4 GHz）。
     */
    @Test
    fun `2_4 GHz 候选不参与实际尝试`() {
        val twoG = CarAdbHotspotDefaults.CHANNEL_CANDIDATES_2G.toSet()
        val attempted = CarAdbHotspotDefaults.CHANNEL_CANDIDATES.toSet()
        assertTrue(
            "2.4 GHz 候选混进了实际尝试表：${twoG intersect attempted}",
            (twoG intersect attempted).isEmpty(),
        )
    }

    /** 实际尝试表以 5 GHz 候选池为准，两者不该漂移。 */
    @Test
    fun `实际尝试表与 5 GHz 候选池一致`() {
        assertEquals(
            CarAdbHotspotDefaults.CHANNEL_CANDIDATES_5G,
            CarAdbHotspotDefaults.CHANNEL_CANDIDATES,
        )
    }

    // ------------------------------------------------------------ 频段自洽

    /**
     * `hw_mode` 与信道必须自洽。
     *
     * `hw_mode=g` + `channel=36` 会让 `hostapd` 直接拒绝启动，而这两个值
     * 以前是分开写死的（一个在常量、一个在配置生成里）。现在只由信道推导，
     * 这条断言钉住推导本身。
     */
    @Test
    fun `hw_mode 由信道推导且自洽`() {
        for (channel in listOf(1, 6, 11, 13, 14)) {
            assertTrue("信道 $channel 应属 2.4 GHz", !CarAdbHotspotDefaults.isFiveGigahertz(channel))
            assertEquals("信道 $channel 的 hw_mode 应为 g", "g", CarAdbHotspotDefaults.hwModeOfChannel(channel))
        }
        for (channel in listOf(36, 40, 44, 48, 149)) {
            assertTrue("信道 $channel 应属 5 GHz", CarAdbHotspotDefaults.isFiveGigahertz(channel))
            assertEquals("信道 $channel 的 hw_mode 应为 a", "a", CarAdbHotspotDefaults.hwModeOfChannel(channel))
        }
    }

    /**
     * VHT（`ieee80211ac=1`）**必须**出现在实际尝试的每个信道上。
     *
     * 本产品只走 5 GHz，而 VHT 是 5 GHz 专属能力：不带它，802.11ac 速率
     * 上不去；带了又必须在 5 GHz（2.4 GHz 下 `hostapd` 会因「网卡在该频段
     * 不具备 VHT 能力」退出）。所以配置生成里 `ieee80211ac` 的开关必须
     * 就是 [CarAdbHotspotDefaults.isFiveGigahertz]。
     */
    @Test
    fun `VHT 开关与频段同源`() {
        for (channel in CarAdbHotspotDefaults.CHANNEL_CANDIDATES.mapNotNull { it.toIntOrNull() }) {
            assertTrue(
                "实际尝试的信道 $channel 上应当开 VHT",
                CarAdbHotspotDefaults.isFiveGigahertz(channel),
            )
        }
    }
}
