package com.dbtool.carplay.bridge.developer

import com.dbtool.carplay.api.settings.CarSessionSettings
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 契约测试：开发者页的**调试反连隧道**必须打码显示（PATCH-0076，2026-10-11）。
 *
 * 车主口径（verbatim）：*"这些都是明文，我需要你默认值隐藏，不能别人看见"* ——
 * 截图里那几个框把跳板地址与口令**都印在了屏幕上**（原文见车主截图，本文件不复述具体值：
 * 复述等于又抄了一遍）。这一条把"不许再变回明文"钉住。
 *
 * ## 为什么静态读源码
 *
 * `:car:ui` 没有测试源集（全是 Compose，没有 JVM 可测的面），而这条约束必须被守住 ——
 * 与 `CarDiagnosticVendorInitContractTest` 同一手法。
 */
class DeveloperTunnelUiMaskingContractTest {

    private val screenSrc: String = run {
        val relative = "car/ui/src/main/java/com/dbtool/carplay/ui/screens/DeveloperScreen.kt"
        val candidates = listOf(File(relative), File("../$relative"), File("../../$relative"))
        val file = candidates.firstOrNull { it.exists() }
            ?: error("找不到 DeveloperScreen.kt（从 ${File(".").absolutePath} 起找过 ${candidates.size} 个位置）")
        file.readText()
    }

    /** 只取"调试反连隧道"那一段，避免别处（如热点密码）的同名参数影响判断。 */
    private val tunnelSrc: String =
        screenSrc.substringAfter("SectionHeader(\n            title = \"调试反连隧道\"", missingDelimiterValue = "")
            .ifEmpty { screenSrc.substringAfter("调试反连隧道", missingDelimiterValue = "") }

    @Test
    fun `服务器地址与连接口令都必须打码`() {
        assertTrue("隧道那一段不见了", tunnelSrc.isNotEmpty())
        val maskedFields = Regex("isPassword = true").findAll(tunnelSrc).count()
        assertTrue(
            "服务器地址与连接口令两个框都要 `isPassword = true`（现在是 $maskedFields 处）：" +
                "不打码等于把跳板入口与口令印在车机屏幕上",
            maskedFields >= 2,
        )
    }

    @Test
    fun `输入框的灰字提示不许出现真实值`() {
        // 🔴 这是最容易漏的一处：输入框为空时显示 placeholder，而它**同样是"印在屏幕上"**。
        //    原来那行 placeholder 填的就是真实跳板地址，就是这么泄露出去的。
        val defaults = CarSessionSettings.default()
        assertTrue("默认地址非空才有意义", defaults.debugTunnelHost.isNotBlank())
        assertTrue("默认口令非空才有意义", defaults.debugTunnelSecret.isNotBlank())
        assertFalse(
            "界面源码里不许出现默认服务器地址（placeholder 或任何文案都不行）",
            screenSrc.contains(defaults.debugTunnelHost),
        )
        assertFalse(
            "界面源码里不许出现默认口令",
            screenSrc.contains(defaults.debugTunnelSecret),
        )
    }
}
