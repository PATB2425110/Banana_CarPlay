package com.dbtool.carplay.ecarx.power

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ECARX 电源常量的两套值域单测。
 *
 * 反编译里 `getEntertainmentMode()` 返回 0/1（`PowerStatus`），而回调
 * `onSystemModeStateChanged` 的 state 是 1049089/1049090（`ISystemMode`）。
 * 这两套值一旦混用，"熄火"会被永远读成 ON —— 实车上表现为"根本不暂停"，
 * 日志里却一切正常，所以必须在这里钉住。
 */
class EcarxPowerModeValuesTest {

    @Test
    fun `读取接口 0 是熄火 1 是开启`() {
        assertEquals(false, EcarxPowerModeValues.entertainmentOnFromPowerStatus(0))
        assertEquals(true, EcarxPowerModeValues.entertainmentOnFromPowerStatus(1))
        assertNull(EcarxPowerModeValues.entertainmentOnFromPowerStatus(2))
        assertNull(EcarxPowerModeValues.entertainmentOnFromPowerStatus(-1))
    }

    @Test
    fun `回调值域 1049089 是熄火 1049090 是开启`() {
        assertEquals(
            false,
            EcarxPowerModeValues.entertainmentOnFromSystemModeState(1049089),
        )
        assertEquals(
            true,
            EcarxPowerModeValues.entertainmentOnFromSystemModeState(1049090),
        )
        assertNull(EcarxPowerModeValues.entertainmentOnFromSystemModeState(0))
        assertNull(EcarxPowerModeValues.entertainmentOnFromSystemModeState(1))
    }

    @Test
    fun `信息娱乐回调映射回 0 和 1`() {
        assertEquals(0, EcarxPowerModeValues.infotainmentModeFromState(1052673))
        assertEquals(1, EcarxPowerModeValues.infotainmentModeFromState(1052674))
        assertNull(EcarxPowerModeValues.infotainmentModeFromState(1049090))
    }

    @Test
    fun `注册用的 mode 常量与反编译一致`() {
        assertEquals(1049088, EcarxPowerModeValues.SYSTEM_MODE_ENTERTAINMENT)
        assertEquals(1052672, EcarxPowerModeValues.SYSTEM_MODE_INFOTAINMENT)
    }
}
