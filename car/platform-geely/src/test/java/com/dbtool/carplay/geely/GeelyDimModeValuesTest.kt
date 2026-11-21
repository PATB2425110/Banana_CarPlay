package com.dbtool.carplay.geely

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 仪表「抢屏」模式值回归测试（2026-10-09，白屏事故）。
 *
 * ## 为什么有这个测试
 *
 * 真车日志（`日志/1791510147443_.../logcat_app.txt`）实证：仪表白屏时我方调的是
 * ```
 * 09:40:51.276  DimMenuInteraction: switchNaviMode naviMode:1
 * 09:40:51.276  DimMenuInteraction: switchNaviMode    mode:NAVI_MODE_OFF
 * ```
 * 即 `1 = 关闭全屏导航`。而 PATCH-0046 初版把"进入"写成了 `1` ⇒ 想抢屏实际是
 * **主动让屏** ⇒ 仪表随即被原厂收回 ⇒ 白屏。
 *
 * ## 断言依据（不是猜的）
 *
 * 逐寄存器读原版 smali `GeelyDimModeController.smali`：
 * ```
 * enterFullNavi():  const/4 v0, 0x3    # = 3
 * exitFullNavi():   const/4 v0, 0x1    # = 1
 * ```
 * 本测试把这两个值钉死 —— 谁再改回去（或改成别的猜测值）立刻变红。
 *
 * 只测纯常量，不碰反射 / Android framework（本地单测跑得动）。
 */
class GeelyDimModeValuesTest {

    @Test
    fun `进入全屏导航 = 3（smali const 0x3）`() {
        assertEquals(
            "enterFullNavi 必须是 3（原版 smali const/4 v0, 0x3）",
            3,
            GeelyDimModeController.NAVI_MODE_FULL_MAP,
        )
    }

    @Test
    fun `退出全屏导航 = 1（smali const 0x1，也= 车机 NAVI_MODE_OFF）`() {
        assertEquals(
            "exitFullNavi 必须是 1（原版 smali const/4 v0, 0x1；车机标 NAVI_MODE_OFF）",
            1,
            GeelyDimModeController.NAVI_MODE_OFF,
        )
    }

    @Test
    fun `进入 与 退出 不能相等（等于 0 或相等的值都是本次事故的形态）`() {
        assertNotEquals(
            "两个值相等 ⇒ 抢屏与退出无法区分（PATCH-0046 的写法就是错值）",
            GeelyDimModeController.NAVI_MODE_FULL_MAP,
            GeelyDimModeController.NAVI_MODE_OFF,
        )
    }
}
