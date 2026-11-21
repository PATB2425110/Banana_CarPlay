package com.dbtool.carplay.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 热点 summary 改写的单测（2026-10-06）。
 *
 * ## 为什么这段字符串处理值得钉死
 *
 * 它是"plan 说 `.176` / 实际 `.175`"这个实车矛盾的修复点。改错的后果分两种：
 * - **改多了**（把决策来源那半句也覆盖掉）⇒ 读日志的人再也看不出"为什么选了自建"；
 * - **改少了 / 改坏**（把 `?` 写进给人看的文案）⇒ 与不修一样糟，甚至更糟。
 *
 * 所以既钉"参数段被替换"，也钉"决策句原样保留"，还钉"拿不准就返回原串"。
 */
class HotspotSummaryRewriteTest {

    /** 实车原文（0.10.7 诊断包 `1791279270080` line 350）。 */
    private val original =
        "自建热点「DBPlay」：wlan0 / 192.168.176.0/24 / 网关 192.168.176.1 / 信道 1"

    @Test
    fun `用实际值改写参数段`() {
        val out = rewriteHotspotSummaryText(
            original = original,
            interfaceName = "wlan0",
            hostAddress = "192.168.175.1",
            subnet = "192.168.175.0/24",
            channel = 1,
        )
        assertEquals("自建热点「DBPlay」：wlan0 / 192.168.175.0/24 / 网关 192.168.175.1 / 信道 1", out)
    }

    /** 决策句必须原样保留——它讲的是"为什么"，与网络参数无关。 */
    @Test
    fun `决策句原样保留`() {
        val withReason =
            "未探测到车机热点，改用 DBPlay 自建热点「DBPlay」：wlan0 / 192.168.176.0/24 / 网关 192.168.176.1 / 信道 1"
        val out = rewriteHotspotSummaryText(
            original = withReason,
            interfaceName = "wlan0",
            hostAddress = "192.168.175.1",
            subnet = "192.168.175.0/24",
            channel = 1,
        )
        assertEquals(
            "未探测到车机热点，改用 DBPlay 自建热点「DBPlay」：" +
                "wlan0 / 192.168.175.0/24 / 网关 192.168.175.1 / 信道 1",
            out,
        )
    }

    /** 信道换了（manager 逐个候选试）：也要写进 summary。 */
    @Test
    fun `信道改写生效`() {
        val out = rewriteHotspotSummaryText(original, "wlan0", "192.168.175.1", "192.168.175.0/24", 6)
        assertEquals("自建热点「DBPlay」：wlan0 / 192.168.175.0/24 / 网关 192.168.175.1 / 信道 6", out)
    }

    // --------------------------------------------------------- 拿不准就原样返回

    /** 一个参数都没读到 ⇒ 原样返回（不能把 "?" 写进给人看的文案）。 */
    @Test
    fun `所有参数都为空时原样返回`() {
        assertEquals(
            original,
            rewriteHotspotSummaryText(original, null, null, null, null),
        )
    }

    @Test
    fun `所有参数都是空白串时原样返回`() {
        assertEquals(
            original,
            rewriteHotspotSummaryText(original, " ", "", "  ", null),
        )
    }

    /** 没有 `：` 锚点（措辞变了）⇒ 原样返回，绝不猜着改。 */
    @Test
    fun `没有冒号锚点时原样返回`() {
        val noColon = "自建热点「DBPlay」wlan0 192.168.176.0/24 信道 1"
        assertEquals(
            noColon,
            rewriteHotspotSummaryText(noColon, "wlan0", "192.168.175.1", "192.168.175.0/24", 1),
        )
    }

    /** 只拿到信道：也该改写，但主机名用占位 `?`（不丢信息，也明确"没读到"）。 */
    @Test
    fun `只拿到信道时主机名用占位`() {
        val out = rewriteHotspotSummaryText(original, null, null, null, 6)
        assertEquals("自建热点「DBPlay」：? / 信道 6", out)
    }

    /** 复用车机热点那一支的 summary 形态：同样只改参数尾段。 */
    @Test
    fun `复用分支的 summary 也能改写`() {
        val reuse = "复用车机热点「Lynk」（手动指定）：wlan0 / 192.168.176.0/24 / 网关 192.168.176.1 / 信道 1"
        val out = rewriteHotspotSummaryText(reuse, "wlan1", "192.168.180.1", "192.168.180.0/24", 11)
        assertEquals(
            "复用车机热点「Lynk」（手动指定）：wlan1 / 192.168.180.0/24 / 网关 192.168.180.1 / 信道 11",
            out,
        )
    }
}
