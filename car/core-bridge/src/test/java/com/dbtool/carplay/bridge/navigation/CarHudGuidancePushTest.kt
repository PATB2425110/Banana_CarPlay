package com.dbtool.carplay.bridge.navigation

import com.shilapi.xcertplay.iap2.message.CarNavigationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PATCH-0111：HUD 推送链路的两处现场修复 —— **中文路名映射** 与 **内容去重防闪烁**。
 *
 * ## 为什么这两条必须钉住
 *
 * 两条都只影响车机仪表，**在手机上完全看不出来**：
 *
 * - 路名映射错了 ⇒ 仪表一直显示英文占位符 `Next Road`（现场就是这样）；
 * - 去重写错方向 ⇒ 要么卡片继续闪（没去掉），要么**该更新的不更新**
 *   （车机提示卡住不刷新），后者比闪烁更难发现。
 *
 * 这里只测**纯逻辑**（[HudPushDeduper] + [hudCurrentRoad] / [hudNextRoad]），
 * 因为它们不含任何 Android 类型 ⇒ 能在 JVM 上跑；"转换层有没有真的调用它们"
 * 由 `HudGuidanceWiringContractTest` 静态读源码钉住。
 *
 * ## 现场补丁语义（刻意保留，不要"顺手修正"）
 *
 * - 上一次的值用 `null ⇒ 0` 的哨兵参与比较（`null` 与 `0` 等价）；
 * - **本次值为 `null` 时一律放行**（不参与去重）——空帧因此仍会推一次。
 *
 * 刻意偏离：
 * - 新会话开始要 [HudPushDeduper.reset]；
 * - 2026-10-19 起把转向/路名签名并入键（1.1.24 日志证明 0x5202 常晚于
 *   0x5201 到达，旧键会把真实转向丢掉）。
 */
class CarHudGuidancePushTest {

    // ── 内容去重 ────────────────────────────────────────────────────────────

    @Test
    fun `首帧一定推送`() {
        assertTrue(HudPushDeduper().accept(meters = 1_000, seconds = 60))
    }

    @Test
    fun `距离与秒数都没变时丢弃（同一毫秒推两次也不会闪）`() {
        val deduper = HudPushDeduper()

        assertTrue("第一帧该推", deduper.accept(meters = 1_000, seconds = 60))
        assertFalse("内容未变化必须丢弃", deduper.accept(meters = 1_000, seconds = 60))
        assertFalse("连推三次也只算一帧", deduper.accept(meters = 1_000, seconds = 60))
    }

    @Test
    fun `距离变了就推送`() {
        val deduper = HudPushDeduper()

        assertTrue(deduper.accept(meters = 1_000, seconds = 60))
        assertTrue("距离变了必须推", deduper.accept(meters = 999, seconds = 60))
    }

    @Test
    fun `秒数变了就推送`() {
        val deduper = HudPushDeduper()

        assertTrue(deduper.accept(meters = 1_000, seconds = 60))
        assertTrue("秒数变了必须推", deduper.accept(meters = 1_000, seconds = 59))
    }

    @Test
    fun `本次距离为 null 时放行（现场补丁语义：空帧仍会推一次）`() {
        val deduper = HudPushDeduper()

        assertTrue(deduper.accept(meters = 1_000, seconds = 60))
        assertTrue("null 一律放行", deduper.accept(meters = null, seconds = 60))
    }

    @Test
    fun `本次秒数为 null 时放行`() {
        val deduper = HudPushDeduper()

        assertTrue(deduper.accept(meters = 1_000, seconds = 60))
        assertTrue("null 一律放行", deduper.accept(meters = 1_000, seconds = null))
    }

    @Test
    fun `null 与 0 是不同内容但重复 null 不重发`() {
        val deduper = HudPushDeduper()

        assertTrue("首帧 meters=null 放行并记住 null", deduper.accept(meters = null, seconds = 60))
        assertFalse(deduper.accept(meters = null, seconds = 60))
        assertTrue("从未知距离变成零也是变化", deduper.accept(meters = 0, seconds = 60))
    }

    @Test
    fun `同一状态超过一秒二十秒及两分钟仍不补发`() {
        var clock = 0L
        val deduper = HudPushDeduper { clock }
        assertTrue(deduper.accept(1000, 60, "直行"))
        for (time in listOf(1000L, 20000L, 120000L, 3600000L)) {
            clock = time
            assertFalse(deduper.accept(1000, 60, "直行"))
        }
        assertTrue(deduper.accept(999, 60, "直行"))
    }

    @Test
    fun `缺距离时间的有效转向状态同样只发一次`() {
        val deduper = HudPushDeduper()
        assertTrue(deduper.accept(null, null, "右转"))
        repeat(10) { assertFalse(deduper.accept(null, null, "右转")) }
        assertTrue(deduper.accept(null, null, "左转"))
    }

    @Test
    fun `reset 后基线清空——与上一会话相同的首帧仍会推送`() {
        val deduper = HudPushDeduper()

        assertTrue(deduper.accept(meters = 4_242, seconds = 77))
        assertFalse("同一会话内该去重", deduper.accept(meters = 4_242, seconds = 77))

        deduper.reset()
        assertTrue(
            "新会话首帧必须推——否则「导航结束清空 → 重新导航」后车机一直空着",
            deduper.accept(meters = 4_242, seconds = 77),
        )
    }

    /**
     * 1.1.24 现场回归：`0x5201` 先到、`0x5202` 后到，二者之间距离/秒数不变。
     * 旧键会把后到的真实转向丢掉；新键必须在签名变化时放行。
     */
    @Test
    fun `同距离同秒数但转向或路名变化时必须推送`() {
        val deduper = HudPushDeduper()

        assertTrue(deduper.accept(meters = 800, seconds = 45, maneuverSignature = "CONTEXT/未知位置"))
        assertFalse(
            "转向与路名完全相同 ⇒ 仍然去重，保留防闪烁",
            deduper.accept(meters = 800, seconds = 45, maneuverSignature = "CONTEXT/未知位置"),
        )
        assertTrue(
            "0x5202 补上真实转向，即使距离/秒数没变也必须推",
            deduper.accept(meters = 800, seconds = 45, maneuverSignature = "RIGHT/进入无名道路"),
        )
        assertTrue(
            "距离变化仍照常推送",
            deduper.accept(meters = 799, seconds = 45, maneuverSignature = "RIGHT/进入无名道路"),
        )
    }

    @Test
    fun `HUD 去重签名包含转向类型与两行文案`() {
        val context = CarNavigationState(maneuverDescription = "未知位置", destination = "目的地")
        val right = context.copy(maneuverType = 2)
        val otherRoad = context.copy(maneuverDescription = "进入无名道路")
        val otherDestination = context.copy(destination = "另一个目的地")

        assertNotEquals(context.hudManeuverSignature(), right.hudManeuverSignature())
        assertNotEquals(context.hudManeuverSignature(), otherRoad.hudManeuverSignature())
        assertNotEquals(context.hudManeuverSignature(), otherDestination.hudManeuverSignature())
        assertEquals(
            context.hudManeuverSignature(),
            context.copy().hudManeuverSignature(),
        )
    }

    // ── 路名映射 ────────────────────────────────────────────────────────────

    @Test
    fun `第一行取 0x5202 的 maneuverDescription`() {
        val state = CarNavigationState(maneuverDescription = "八一西路")

        assertEquals("八一西路", state.hudCurrentRoad())
    }

    @Test
    fun `maneuverDescription 优先于 currentRoad`() {
        val state = CarNavigationState(currentRoad = "Next Road", maneuverDescription = "安仁大道")

        assertEquals("安仁大道", state.hudCurrentRoad())
    }

    @Test
    fun `currentRoad 有值时仍作为 fallback`() {
        val state = CarNavigationState(currentRoad = "某某路")

        assertEquals("某某路", state.hudCurrentRoad())
    }

    @Test
    fun `两路都为空时给 null（交给 hasContent 判空，不要编造路名）`() {
        assertNull(CarNavigationState().hudCurrentRoad())
    }

    @Test
    fun `第二行取目的地`() {
        val state = CarNavigationState(destination = "万达影城(万达广场店)")

        assertEquals("万达影城(万达广场店)", state.hudNextRoad())
    }

    @Test
    fun `destination 优先于 nextRoad，nextRoad 仍作 fallback`() {
        assertEquals(
            "未知位置",
            CarNavigationState(nextRoad = "After Road", destination = "未知位置").hudNextRoad(),
        )
        assertEquals(
            "After Road",
            CarNavigationState(nextRoad = "After Road").hudNextRoad(),
        )
    }
}
