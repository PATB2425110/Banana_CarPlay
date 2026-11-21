package com.dbtool.carplay.geely.lynk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 调试隧道握手帧的字节级回归测试（2026-10-17，VIN 车队化）。
 *
 * 跳板 v7 依赖两个字段定位车辆，但老车机/跳板 v6 只认旧帧。
 * 所以这里同时钉住两态：
 * - 有 VIN：新帧必须含 `vin=` 与 `app=`，且以 `\n` 结尾；
 * - 无 VIN：必须与旧版逐字节一致，连空格数量都不许多。
 */
class LynkDebugTunnelHandshakeTest {

    private val secret = "selftest-secret-1234"

    @Test
    fun `有VIN时帧含vin与app且以换行结尾`() {
        val frame = LynkDebugTunnel.buildHandshake(
            secret = secret,
            vin = "L6TTEST1111111115",
            appVersion = "1.1.15",
        )
        assertTrue("帧应含 vin=：$frame", frame.contains("vin=L6TTEST1111111115"))
        assertTrue("帧应含 app=：$frame", frame.contains("app=1.1.15"))
        assertTrue("帧必须以 \\n 结尾：$frame", frame.endsWith("\n"))
        assertEquals(
            "新帧字段顺序/分隔必须稳定",
            "DBPLAY-TUNNEL $secret vin=L6TTEST1111111115 app=1.1.15\n",
            frame,
        )
    }

    @Test
    fun `无VIN时帧与旧帧逐字节一致`() {
        val oldFrame = "DBPLAY-TUNNEL $secret\n"
        assertEquals(oldFrame, LynkDebugTunnel.buildHandshake(secret, null, "1.1.15"))
        assertEquals(oldFrame, LynkDebugTunnel.buildHandshake(secret, "", ""))
        assertEquals(oldFrame, LynkDebugTunnel.buildHandshake(secret, "   ", "1.1.15"))
    }

    @Test
    fun `有VIN且有token时追加tok且字段顺序为vin-app-tok`() {
        val token = "0123456789abcdef01234567"
        val frame = LynkDebugTunnel.buildHandshake(
            secret = secret,
            vin = "L6TTEST1111111115",
            appVersion = "1.1.15",
            token = token,
        )
        assertEquals(
            "有 token 的新帧形态必须稳定",
            "DBPLAY-TUNNEL $secret vin=L6TTEST1111111115 app=1.1.15 tok=$token\n",
            frame,
        )
        assertTrue(
            "字段顺序必须为 vin -> app -> tok",
            frame.indexOf("vin=") < frame.indexOf("app=") &&
                frame.indexOf("app=") < frame.indexOf("tok="),
        )
    }

    @Test
    fun `有VIN但token为空时仍是无tok形态`() {
        val expected = "DBPLAY-TUNNEL $secret vin=L6TTEST1111111115 app=1.1.15\n"
        assertEquals(
            expected,
            LynkDebugTunnel.buildHandshake(secret, "L6TTEST1111111115", "1.1.15", ""),
        )
        assertEquals(
            expected,
            LynkDebugTunnel.buildHandshake(secret, "L6TTEST1111111115", "1.1.15", "   "),
        )
    }

    @Test
    fun `无VIN时即使有token也不发tok`() {
        val oldFrame = "DBPLAY-TUNNEL $secret\n"
        assertEquals(
            oldFrame,
            LynkDebugTunnel.buildHandshake(secret, null, "1.1.15", "0123456789abcdef01234567"),
        )
    }
}
