package com.dbtool.carplay.bridge.hotspot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress

/**
 * KX11/CS11 原厂网关“绕过网络绑定”的作用域单测（移植 DiPlay v0.2.16）。
 *
 * 现场依据：`docs/KX11_ROUTED_HOTSPOT.md` 第 6 条 —— 只有严格匹配的原厂网关
 * 才跳过 Android `bindProcessToNetwork`/`bindSocket`，其它候选保持原行为。
 * 这里把“严格匹配”与“确实接线”都钉死：
 * - 纯函数覆盖 KX11 / CS11 / 其它车型 / 其它系统 / 其它地址 / 其它接口；
 * - 源码契约确认 `TcamHotspotManager` 在 `bindProcess(network)` 之前调用了它，
 *   并打印了可检索的“KX11 原厂网关：绕过网络绑定”日志。
 */
class TcamFactoryGatewayBindBypassTest {

    private fun ip(text: String) = InetAddress.getByName(text)

    @Test
    fun `KX11 与 CS11 的 198点18 原厂网关绕过网络绑定`() {
        assertTrue(
            "CS11 + eth0.11 + 198.18.34.15 必须走 DiPlay 的绕过分支",
            TcamHotspotManager.shouldBypassProcessBind(
                28, "CS11", "cs11_high", "eth0.11", ip("198.18.34.15"),
            ),
        )
        assertTrue(
            "CX11 + eth0.11 + 198.18.34.15 必须绕过进程绑定",
            TcamHotspotManager.shouldBypassProcessBind(
                28, "CX11", "cx11_high", "eth0.11", ip("198.18.34.15"),
            ),
        )
        assertTrue(
            "KX11 原版行为不能被破坏",
            TcamHotspotManager.shouldBypassProcessBind(
                28, "KX11", "kx11_high", "eth0", ip("198.18.0.2"),
            ),
        )
        assertTrue(
            "198.19/16 仍在 198.18.0.0/15 内",
            TcamHotspotManager.shouldBypassProcessBind(
                28, "CS11", "cs11_high", "eth0", ip("198.19.1.2"),
            ),
        )
    }

    @Test
    fun `其它车型 系统 地址 接口一律保持原有绑定`() {
        val cases = listOf(
            "系统 29 不能绕过" to TcamHotspotManager.shouldBypassProcessBind(
                29, "CS11", "cs11_high", "eth0.11", ip("198.18.34.15"),
            ),
            "其它车型不能绕过" to TcamHotspotManager.shouldBypassProcessBind(
                28, "ABC", "abc", "eth0.11", ip("198.18.34.15"),
            ),
            "MODEL 对但 DEVICE 前缀不对不能绕过" to TcamHotspotManager.shouldBypassProcessBind(
                28, "CS11", "other", "eth0.11", ip("198.18.34.15"),
            ),
            "非 198.18/15 地址不能绕过" to TcamHotspotManager.shouldBypassProcessBind(
                28, "CS11", "cs11_high", "eth0.11", ip("192.168.15.1"),
            ),
            "非 eth 接口不能绕过" to TcamHotspotManager.shouldBypassProcessBind(
                28, "CS11", "cs11_high", "wlan0", ip("198.18.34.15"),
            ),
            "蜂窝接口不能绕过" to TcamHotspotManager.shouldBypassProcessBind(
                28, "CS11", "cs11_high", "rmnet0", ip("198.18.34.15"),
            ),
        )
        cases.forEach { (reason, actual) -> assertFalse(reason, actual) }
    }

    @Test
    fun `绑定之前先判定并在日志里明确写出绕过`() {
        val code = File(
            "src/main/java/com/dbtool/carplay/bridge/hotspot/TcamHotspotManager.kt",
        ).readText()
        val gate = code.indexOf("val factoryGatewayBypass = Kx11FactoryRoutePort.ENABLED && shouldBypassProcessBind(")
        val bind = code.indexOf("bindProcess(network)", gate.coerceAtLeast(0))
        assertTrue("找不到带总开关的严格判定调用", gate >= 0)
        assertTrue("bindProcess 必须在严格判定之后", bind > gate)
        assertTrue(
            "日志必须能直接检索到 DiPlay 的绕过口径",
            code.contains("KX11 原厂网关：绕过网络绑定"),
        )
    }
}
