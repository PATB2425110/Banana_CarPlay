package com.dbtool.carplay.geely

import com.dbtool.carplay.api.navigation.CarHudGuidance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HUD 双目标探测 + 120 s 过期看门狗 + 待发帧重放/保活的纯逻辑单测。
 *
 * ## 为什么这两件事放在一个测试类
 *
 * 它们都只有纯逻辑、都只能在 JVM 上验证：目标探测写错的后果是"高德车机永远
 * 绑不上百度包"（现场只有一句"车机没有该服务"），看门狗写错的后果是"HUD 卡片
 * 永远挂着"或"导航途中被误清"。两者都在车上才看得见，用单测钉死最便宜。
 *
 * 真机行为（包到底装没装、服务能不能 bind）**不在本类覆盖范围** —— 那要上车。
 */
class HudTargetResolverTest {

    // 2026-10-19 起顺序改为「高德优先」（与 Fake Ultra 完全一致）
    private val amap = HudTargetResolver.CANDIDATES[0]
    private val baidu = HudTargetResolver.CANDIDATES[1]

    // ------------------------------------------------------------ 目标解析

    @Test
    fun `两个都可用时选高德（第一优先，与 Fake Ultra 一致）`() {
        val resolved = HudTargetResolver.resolve { true }
        assertEquals(amap, resolved)
    }

    @Test
    fun `只有百度可用时选百度（回退）`() {
        val resolved = HudTargetResolver.resolve { it == baidu }
        assertEquals(baidu, resolved)
    }

    @Test
    fun `两个都不可用时返回 null`() {
        assertNull(HudTargetResolver.resolve { false })
    }

    @Test
    fun `包未安装时探测抛异常视为不可用且不向上抛`() {
        // 模拟目标车没装百度包：`getPackageInfo` 抛 NameNotFoundException，
        // `HudTargets.resolverFor` 的 runCatching 会把它变成 false；这里直接
        // 让谓词抛，验证 HudTargetResolver 自己也不会把异常漏出去。
        val resolved = HudTargetResolver.resolve { target ->
            if (target == amap) error("NameNotFoundException") else true
        }
        assertEquals(baidu, resolved)
    }

    @Test
    fun `两个探测都抛异常时返回 null 而不是崩`() {
        val resolved = HudTargetResolver.resolve { error("PackageManager 被回收") }
        assertNull(resolved)
    }

    @Test
    fun `候选顺序固定为百度到高德且服务类名相同`() {
        assertEquals(2, HudTargetResolver.CANDIDATES.size)
        assertEquals("com.baidu.naviauto", baidu.packageName)
        assertEquals("com.autonavi.amapauto", amap.packageName)
        val service = "com.baidu.naviauto.hudbridge.CarPlayHudBridgeService"
        assertEquals(service, baidu.serviceName)
        assertEquals(service, amap.serviceName)
    }

    @Test
    fun `命中第一个后不再探测后面的候选`() {
        var calls = 0
        HudTargetResolver.resolve {
            calls += 1
            true
        }
        assertEquals("第一个候选可用就不该再问第二个", 1, calls)
    }

    @Test
    fun `flatName 是包名斜杠服务类名`() {
        assertEquals(
            "com.autonavi.amapauto/com.baidu.naviauto.hudbridge.CarPlayHudBridgeService",
            amap.flatName,
        )
    }

    // ------------------------------------------------------------ 看门狗

    @Test
    fun `过期窗口常量是 120 秒`() {
        assertEquals(120_000L, HUD_EXPIRY_MS)
    }

    @Test
    fun `看门狗在 120 秒内不触发`() {
        val watchdog = HudExpiryDeadline()
        watchdog.onFrame(1_000L)
        assertFalse(watchdog.claimExpiry(1_000L))
        assertFalse(watchdog.claimExpiry(1_000L + HUD_EXPIRY_MS - 1))
        assertFalse("未到点不该消费截止时间", watchdog.isExpired(1_000L + HUD_EXPIRY_MS - 1))
    }

    @Test
    fun `到点触发一次后不再重复触发`() {
        val watchdog = HudExpiryDeadline()
        watchdog.onFrame(1_000L)
        assertTrue(watchdog.claimExpiry(1_000L + HUD_EXPIRY_MS))
        assertFalse("同一个截止时刻只能清空一次", watchdog.claimExpiry(1_000L + HUD_EXPIRY_MS + 1))
    }

    @Test
    fun `新帧会把截止时间后移`() {
        val watchdog = HudExpiryDeadline()
        watchdog.onFrame(0L)
        watchdog.onFrame(100_000L)
        assertFalse("旧截止时刻已过，但新帧把它推后了", watchdog.claimExpiry(120_000L))
        assertTrue(watchdog.claimExpiry(100_000L + HUD_EXPIRY_MS))
    }

    @Test
    fun `取消后永远不再触发`() {
        val watchdog = HudExpiryDeadline()
        watchdog.onFrame(0L)
        watchdog.cancel()
        assertNull("取消后不该留着截止时间", watchdog.deadline)
        assertFalse(watchdog.claimExpiry(HUD_EXPIRY_MS * 10))
    }

    @Test
    fun `从未发帧时看门狗不触发`() {
        val watchdog = HudExpiryDeadline()
        assertFalse(watchdog.isExpired(Long.MAX_VALUE))
        assertFalse(watchdog.claimExpiry(Long.MAX_VALUE))
    }

    // ------------------------------------------------------------ 待发帧重放

    @Test
    fun `服务连接前收到的有效帧可以在有效期内重放`() {
        val pending = HudPendingGuidance()
        val guidance = guidance(updatedAtMs = 1_000L)
        pending.remember(guidance)

        assertEquals(guidance, pending.fresh(1_000L + HUD_EXPIRY_MS))
    }

    @Test
    fun `过期的待发帧不再重放并被清空`() {
        val pending = HudPendingGuidance()
        pending.remember(guidance(updatedAtMs = 1_000L))

        assertNull(pending.fresh(1_000L + HUD_EXPIRY_MS + 1))
        assertNull("过期帧取过一次后应被清掉", pending.fresh(Long.MAX_VALUE))
    }

    @Test
    fun `导航结束后清空待发帧`() {
        val pending = HudPendingGuidance()
        pending.remember(guidance(updatedAtMs = 1_000L))
        pending.clear()

        assertNull(pending.fresh(1_001L))
    }

    @Test
    fun `断线重连退避为正且短于过期窗口`() {
        assertTrue(HUD_RECONNECT_DELAY_MS > 0L)
        assertTrue(HUD_RECONNECT_DELAY_MS < HUD_EXPIRY_MS)
    }

    @Test
    fun `保活周期必须短于服务端三十秒超时`() {
        assertTrue(
            "HUD 服务端约 30.5 秒无帧就清空，保活必须留出余量",
            HUD_KEEPALIVE_MS < 30_000L,
        )
    }

    @Test
    fun `保活在周期内不重复、到点触发一次`() {
        val keepalive = HudKeepalive()
        keepalive.onFrame(1_000L)

        assertFalse(keepalive.isDue(1_000L + HUD_KEEPALIVE_MS - 1))
        assertTrue(keepalive.isDue(1_000L + HUD_KEEPALIVE_MS))
        keepalive.onFrame(1_000L + HUD_KEEPALIVE_MS)
        assertFalse("重发后应重新计时", keepalive.isDue(1_000L + HUD_KEEPALIVE_MS + 1))
        keepalive.cancel()
        assertFalse("取消后不该再触发", keepalive.isDue(Long.MAX_VALUE))
    }

    private fun guidance(updatedAtMs: Long) = CarHudGuidance(
        updatedAtMs = updatedAtMs,
        routeMeters = 120,
        routeSeconds = 10,
        segmentMeters = 20,
        maneuverType = 1,
        currentRoad = "测试路",
        nextRoad = "目的地",
        sessionId = 7L,
        revision = 3L,
    )
}
