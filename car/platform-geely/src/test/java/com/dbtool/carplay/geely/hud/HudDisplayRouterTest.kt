package com.dbtool.carplay.geely.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HUD 选屏判据（2026-10-22）。
 *
 * ## 实车基准（2026-10-21，VIN `L6TTEST1111111114`，CS11）
 *
 * ```
 * Display 2: 1920×1080 @30Hz   com.geely.hud/HUDActivity 跑在这里  ← 陷阱
 * Display 3: 1024×274  @60Hz   flags=0xb（SECURE|PROTECTED|PRESENTATION）← HUD
 * ```
 *
 * 因此本测试的三条主线：
 * 1. **只认 1024×274 + FLAG_PRESENTATION**；
 * 2. 即便 1920×1080 那块屏有 `com.geely.hud`（现实如此），也**绝不能**选中它；
 * 3. 多块候选同时命中 ⇒ 返回空（HUD_AMBIGUOUS），不猜。
 */
class HudDisplayRouterTest {

    private val router = HudDisplayRouter()

    /** 实车 display 3：1024×274，flags=0xb（SECURE 0x1 | PROTECTED 0x2 | PRESENTATION 0x8）。 */
    private val hud = HudDisplayDescriptor(
        displayId = 3,
        widthPixels = 1024,
        heightPixels = 274,
        flags = 0xb,
        name = "HDMI",
    )

    /** 实车 display 2：1920×1080 @30Hz，`com.geely.hud` 的 Activity 跑在这里。 */
    private val instrument = HudDisplayDescriptor(
        displayId = 2,
        widthPixels = 1920,
        heightPixels = 1080,
        flags = 0x0,
        name = "DSI-2",
    )

    private val main = HudDisplayDescriptor(
        displayId = 0,
        widthPixels = 1920,
        heightPixels = 1080,
        flags = 0x0,
        name = "Built-in Screen",
    )

    @Test
    fun `实车三块屏里只选中 display 3`() {
        val route = router.route(listOf(main, instrument, hud))
        assertTrue("应命中 display 3，实际 $route", route is HudDisplayRoute.Found)
        assertEquals(3, (route as HudDisplayRoute.Found).display.displayId)
    }

    @Test
    fun `仪表屏绝不能因为 1920x1080 或组件名被选中`() {
        // 路由根本不看组件：这里用 name 模拟"仪表/HUD Activity 宿主"，结果仍是没找到。
        val route = router.route(listOf(main, instrument))
        assertEquals(HudDisplayRoute.Unavailable(HudDisplayRouter.HUD_NOT_FOUND), route)
    }

    @Test
    fun `没有 FLAG_PRESENTATION 的 1024x274 不认`() {
        val route = router.route(
            listOf(hud.copy(flags = 0x3)), // SECURE | PROTECTED，缺 PRESENTATION
        )
        assertEquals(HudDisplayRoute.Unavailable(HudDisplayRouter.HUD_NOT_FOUND), route)
    }

    @Test
    fun `尺寸不完全一致不认_1024x275 不是 HUD`() {
        val route = router.route(listOf(hud.copy(heightPixels = 275)))
        assertEquals(HudDisplayRoute.Unavailable(HudDisplayRouter.HUD_NOT_FOUND), route)
    }

    @Test
    fun `多块屏同时命中时必须返回空_不猜`() {
        val second = hud.copy(displayId = 5, name = "HDMI-2")
        val route = router.route(listOf(hud, second))
        assertTrue(
            "多候选时返回 Found 会把画面投到用户看不见的那块屏，而日志毫无异常",
            route is HudDisplayRoute.Unavailable,
        )
        assertEquals(
            HudDisplayRouter.HUD_AMBIGUOUS,
            (route as HudDisplayRoute.Unavailable).reason,
        )
    }

    @Test
    fun `空列表报 HUD_NOT_FOUND`() {
        assertEquals(
            HudDisplayRoute.Unavailable(HudDisplayRouter.HUD_NOT_FOUND),
            router.route(emptyList()),
        )
    }

    @Test
    fun `实车 display 3 的 flags 是 0xb_与判据常量一致`() {
        // 把实车读数钉在测试里：0xb & 0x8 == 0x8。
        assertEquals(0x8, HudDisplayRouter.FLAG_PRESENTATION)
        assertEquals(HudDisplayRouter.FLAG_PRESENTATION, hud.flags and HudDisplayRouter.FLAG_PRESENTATION)
    }

    @Test
    fun `命中判据同时要求宽高与 flags`() {
        val onlySize = hud.copy(flags = 0)
        val onlyFlags = hud.copy(widthPixels = 1920, heightPixels = 1080)
        assertEquals(
            HudDisplayRoute.Unavailable(HudDisplayRouter.HUD_NOT_FOUND),
            router.route(listOf(onlySize)),
        )
        assertEquals(
            HudDisplayRoute.Unavailable(HudDisplayRouter.HUD_NOT_FOUND),
            router.route(listOf(onlyFlags)),
        )
    }
}
