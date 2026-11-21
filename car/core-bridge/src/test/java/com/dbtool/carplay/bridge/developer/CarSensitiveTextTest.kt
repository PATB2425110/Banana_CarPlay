package com.dbtool.carplay.bridge.developer

import com.dbtool.carplay.api.developer.CarSensitiveText
import com.dbtool.carplay.api.settings.CarSessionSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 敏感值脱敏口径（PATCH-0076，2026-10-11）。
 *
 * 车主口径（verbatim）：*"这些都是明文，我需要你默认值隐藏，不能别人看见"* ——
 * 指的是调试反连隧道的**服务器地址**与**连接口令**。它们会出现在三个地方
 * （界面上、应用日志里、以及**要上传**的诊断包里），所以口径收在 [CarSensitiveText] 一处，
 * 这里把那条口径钉死。
 */
class CarSensitiveTextTest {

    @Test
    fun `设置了就只报"已设置 N 字符"，绝不回原值`() {
        // 刻意用**样例值**而不是真实口令：真实值不该出现在任何源码里（连测试也不行）。
        val label = CarSensitiveText.label("sample-secret-9")
        assertFalse("不能回原值", label.contains("sample"))
        assertFalse("不能回原值", label.contains("secret"))
        assertTrue("要能分辨'填了'：$label", label.contains("已设置"))
        assertTrue("要能分辨长度：$label", label.contains("15"))
    }

    @Test
    fun `没填就报未设置 —— 现场要能一眼区分"没填"与"填错"`() {
        assertEquals(CarSensitiveText.NOT_SET, CarSensitiveText.label(""))
        assertEquals(CarSensitiveText.NOT_SET, CarSensitiveText.label("   "))
    }

    @Test
    fun `服务器地址与口令同一条口径`() {
        val host = CarSessionSettings.default().debugTunnelHost
        assertTrue("默认地址非空才有意义", host.isNotBlank())
        val label = CarSensitiveText.hostLabel(host)
        assertFalse("地址不能回原值：$label", label.contains(host))
        assertTrue("地址也要能分辨'填了'：$label", label.contains("已设置"))
    }

    @Test
    fun `默认口令本身也不能从长度反推出来 —— 口径是"只报有没有 + 多长"，长度是允许的`() {
        // 这条是**刻意记录取舍**：长度会暴露"是不是还停在出厂默认"（默认口令长度已知），
        // 但足以区分现场状态，且不足以还原原值。真要连长度都不报，改这里一处即可
        //（界面/日志/诊断包共用同一实现）。
        val label = CarSensitiveText.label(CarSessionSettings.default().debugTunnelSecret)
        assertEquals("(已设置，${CarSessionSettings.default().debugTunnelSecret.length} 字符)", label)
    }
}
