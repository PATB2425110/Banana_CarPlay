package com.dbtool.carplay.bridge.developer

import com.dbtool.carplay.api.developer.CarDiagnosticSettingsDump
import com.dbtool.carplay.api.settings.CarMfiTarget
import com.dbtool.carplay.api.settings.CarSessionSettings
import com.dbtool.carplay.api.settings.CarTransportMode
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `system/effective_settings.txt` 的渲染单测。
 *
 * ## 为什么这份清单值得单独钉
 *
 * 它有一个**在功能上完全无感**的失败模式：脱敏写漏了。
 *
 * `remoteMfiToken` 与 `manualHotspotPassphrase` 一旦被原样写进这个文件，
 * 就会随诊断包**上传到服务器** —— 而界面、日志、连接全都正常，没有任何迹象。
 * 这类"错了也不会有人发现"的地方，只能靠单测挡住，所以这里有**反向断言**。
 *
 * 同理，将来若有人图省事把实现改成 `CarSessionSettings.toString()`（data class 的
 * `toString()` 会把这两个字段原样打出来），本文件的第一条测试就会失败。
 */
class CarDiagnosticSettingsDumpTest {

    private val secretToken = "SECRET-MFI-TOKEN-0123456789"
    private val secretPassphrase = "VerySecretWifi"

    @Test
    fun `导航焦点开关默认开启且关闭时显式导出`() {
        assertTrue(CarDiagnosticSettingsDump.render(CarSessionSettings.default()).contains("navigationAudioFocusEnabled: true"))
        assertTrue(CarDiagnosticSettingsDump.render(CarSessionSettings.default().copy(navigationAudioFocusEnabled = false))
            .contains("navigationAudioFocusEnabled: false"))
    }

    private fun dumpWithSecrets(): String = CarDiagnosticSettingsDump.render(
        CarSessionSettings.default().copy(
            remoteMfiToken = secretToken,
            manualHotspotPassphrase = secretPassphrase,
        ),
    )

    // ------------------------------------------------------------ 脱敏（反向断言）

    @Test
    fun `🔴 反向断言：token 与热点密码的原值绝不能出现在输出里`() {
        val text = dumpWithSecrets()
        assertFalse("MFI token 泄漏进了诊断包文本", text.contains(secretToken))
        assertFalse("热点密码泄漏进了诊断包文本", text.contains(secretPassphrase))
    }

    @Test
    fun `🔴 反向断言：不能退化成 data class 的 toString`() {
        val text = dumpWithSecrets()
        // data class 的 toString 会以 `CarSessionSettings(` 开头把全部字段摊平。
        assertFalse("疑似改成了 toString()，敏感字段会跟着出去", text.contains("CarSessionSettings("))
    }

    @Test
    fun `脱敏不是空操作：仍报出「已设置」与长度`() {
        val text = dumpWithSecrets()
        assertTrue(text.contains("remoteMfiToken: (已设置，${secretToken.length} 字符)"))
        assertTrue(
            text.contains("manualHotspotPassphrase: ${secretPassphrase.length} 字符"),
        )
    }

    @Test
    fun `未设置时明确写「未设置」而不是空行`() {
        // ⚠️ 热点密码**出厂就有值**（`12345678`），所以这里必须显式清空才能测"未设置"。
        // （第一版测试就写错了这一条，被这条断言自己抓住。）
        val text = CarDiagnosticSettingsDump.render(
            CarSessionSettings.default().copy(manualHotspotPassphrase = ""),
        )
        assertTrue(text.contains("remoteMfiToken: (未设置)"))
        assertTrue(text.contains("manualHotspotPassphrase: (未设置)"))
        assertTrue(text.contains("remoteMfiServer: (未设置)"))
        assertTrue(text.contains("adbHotspotInterface: (未设置)"))
    }

    // ------------------------------------------------------------ 密码告警

    @Test
    fun `出厂密码被标成「出厂默认」且不告警`() {
        val text = CarDiagnosticSettingsDump.render(CarSessionSettings.default())
        val line = text.lineSequence().first { it.startsWith("manualHotspotPassphrase:") }
        assertTrue(line, line.contains("8 字符"))
        assertTrue(line, line.contains("出厂默认"))
        assertFalse("出厂密码是 8 位，不该告警", line.contains("⚠️"))
    }

    /**
     * ⚠️ 这条是这份文本里**唯一能提前发现**"iPhone 搜不到 SSID"的地方：
     * WPA2 要求 8~63 位，短于 8 位 hostapd 会直接拒绝启动，而日志里
     * 可能只有一句"热点已就绪"。
     */
    @Test
    fun `短于 8 位的密码必须告警`() {
        val text = CarDiagnosticSettingsDump.render(
            CarSessionSettings.default().copy(manualHotspotPassphrase = "1234567"),
        )
        val line = text.lineSequence().first { it.startsWith("manualHotspotPassphrase:") }
        assertTrue(line, line.contains("7 字符"))
        assertTrue(line, line.contains("WPA2 会失败"))
        assertFalse("不是出厂密码，不该标出厂默认", line.contains("出厂默认"))
    }

    @Test
    fun `非默认但够长的密码既不标默认也不告警`() {
        val text = CarDiagnosticSettingsDump.render(
            CarSessionSettings.default().copy(manualHotspotPassphrase = "abcdefgh"),
        )
        val line = text.lineSequence().first { it.startsWith("manualHotspotPassphrase:") }
        assertEquals("manualHotspotPassphrase: 8 字符", line)
    }

    // ------------------------------------------------------------ 内容完整性

    /**
     * 这份清单是**白名单**（见 `CarDiagnosticSettingsDump` 的类文档）。
     * 这条测试把"最该在的几项"钉住 —— 尤其是那两个**现场排查时最先要问的开关**。
     */
    @Test
    fun `两个现场开关与关键配置都在输出里`() {
        val text = CarDiagnosticSettingsDump.render(
            CarSessionSettings.default().copy(
                transport = CarTransportMode.WIRELESS,
                mfiTarget = CarMfiTarget.LOCAL_OFFLINE,
                forwardSteeringWheelToCarPlay = false,
                reportMediaCardToCar = false,
            ),
        )
        // 排查时最先要问的两项：是不是开着。关掉必须是显式的 false，不能"没写"。
        assertTrue(text.contains("forwardSteeringWheelToCarPlay: false"))
        assertTrue(text.contains("reportMediaCardToCar: false"))
        assertTrue(text.contains("transport: WIRELESS"))
        assertTrue(text.contains("isWireless: true"))
        assertTrue(text.contains("mfiTarget: LOCAL_OFFLINE"))
        // ⚠️ 2026-10-16：出厂默认已按车主口径改成 `TCAM_HOTSPOT`（复用车机 TCAM 热点），
        //    见 `CarSessionSettings.hotspotMode` 的历次默认值表。
        //    这里断言的是**默认值渲染出来的样子**，所以跟着改。
        assertTrue(text.contains("hotspotMode: GEELY_ROUTED"))
        assertTrue(text.contains("drivingSide: LEFT"))
        // ⚠️ 2026-10-11（PATCH-0080）：安全区从"四边内边距"改成"矩形"（x/y/w/h），
        //    诊断包里的这一行跟着换，断言同步。
        assertTrue(text.contains("safeArea: x="))
        assertTrue(text.contains("mainSafeArea: x="))
    }

    @Test
    fun `分区标题齐全，便于人眼定位`() {
        val text = CarDiagnosticSettingsDump.render(CarSessionSettings.default())
        for (section in listOf("[身份]", "[连接]", "[热点]", "[显示]", "[窗口]", "[音频]", "[功能开关]")) {
            assertTrue("缺少分区 $section", text.contains(section))
        }
    }

    /**
     * PATCH-0114（2026-10-13）：HUD 导航转向提示推送开关。
     *
     * 现场问「仪表上怎么没有转向卡片」时，这一行是**第一现场** ——
     * 它关着就一帧都不会推，而"关着" / "桥没绑上" / "iPhone 没下发"三者在仪表上
     * 长得一模一样，只有这份文件能区分。
     *
     * 这条同时钉两件事：字段进了白名单（人眼能看到），以及**默认值是开的**
     * （默认关会让"新装用户永远看不到这个能力"，那是回退，不是默认）。
     */
    @Test
    fun `HUD 导航推送开关在包里且默认为开`() {
        assertTrue(
            "HUD 推送开关必须在 effective_settings.txt 里：默认值应为 true",
            CarDiagnosticSettingsDump.render(CarSessionSettings.default())
                .contains("hudGuidanceEnabled: true"),
        )
        assertTrue(
            "关掉时也必须显式写 false（不能「没写」）：现场靠它区分「关着」与「链路坏了」",
            CarDiagnosticSettingsDump.render(
                CarSessionSettings.default().copy(hudGuidanceEnabled = false),
            ).contains("hudGuidanceEnabled: false"),
        )
    }

    /**
     * 调试反连隧道（2026-10-11 新增）：**开关与地址要能看见，密钥不能落原值**。
     *
     * 诊断包要上传到服务器，所以 `debugTunnelSecret` 与热点密码同级 —— 只报
     * "有没有 + 多长"。这条同时钉住两件事：字段进了白名单（现场能看到隧道开没开、
     * 连的哪台服务器），以及它**没有**被原样导出。
     */
    @Test
    fun `调试隧道：开关与地址可见，密钥只报长度`() {
        val text = CarDiagnosticSettingsDump.render(CarSessionSettings.default())
        assertTrue("缺少分区 [调试]", text.contains("[调试]"))
        assertTrue("隧道开关必须在包里：$text", text.contains("debugTunnelEnabled:"))
        assertTrue("隧道服务器地址必须在包里", text.contains("debugTunnelHost:"))
        assertTrue("隧道端口必须在包里", text.contains("debugTunnelPort:"))
        assertTrue("本地 adb 端口必须在包里", text.contains("debugTunnelLocalAdbPort:"))
        assertTrue("密钥字段名必须在包里", text.contains("debugTunnelSecret:"))
        val defaultSecret = CarSessionSettings.default().debugTunnelSecret
        assertTrue("默认密钥非空才有意义，测试本身要立得住", defaultSecret.isNotBlank())
        assertTrue(
            "密钥绝不能落原值（诊断包会上传）：$text",
            !text.contains(defaultSecret),
        )
        assertTrue("密钥应只报长度：$text", text.contains("debugTunnelSecret: (已设置"))

        // ⚠️ 2026-10-11（PATCH-0076）：**服务器地址同样不许落原值**。
        //    这份文件要上传服务器；"跳板 IP + 端口 + 口令"三者凑齐就等于把调试入口交出去。
        //    车主口径："这些都是明文，我需要你默认值隐藏，不能别人看见"。
        val defaultHost = CarSessionSettings.default().debugTunnelHost
        assertTrue("默认地址非空才有意义，测试本身要立得住", defaultHost.isNotBlank())
        assertTrue(
            "服务器地址绝不能落原值（诊断包会上传）：$text",
            !text.contains(defaultHost),
        )
        assertTrue("地址应只报长度：$text", text.contains("debugTunnelHost: (已设置"))
    }

    /**
     * 🔴 **白名单完整性**（反射）。
     *
     * `CarDiagnosticSettingsDump` 是**白名单、不是反射** —— 刻意如此，免得将来某个新的
     * 敏感字段被"自动全量导出"带上天。代价是"新增字段忘了加进清单"**不会有任何提示**，
     * 只会在现场排查时表现为"这个字段根本不在包里"，而且没人会想到是这个原因。
     *
     * 这条测试把那个代价找回来：**漏加就红**。新增设置字段时看到它失败，
     * 就知道该往哪加（加完再确认 `CarDiagnosticSettingsDumpTest` 里的脱敏断言仍然通过）。
     *
     * 说明：只检查**存储字段**（构造参数）。`displayScaleLabel` / `isWireless` 是计算属性、
     * 没有 backing field，本来就不在这个循环里；它们是否出现由上面那条内容完整性测试负责。
     */
    @Test
    fun `🔴 白名单完整性：每个存储字段都必须出现在输出里`() {
        val text = CarDiagnosticSettingsDump.render(CarSessionSettings.default())
        val missing = CarSessionSettings::class.java.declaredFields
            // 只取实例字段：跳过 companion，以及可能的合成静态字段（如 Compose 的 `$stable`）。
            .filterNot { Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .filterNot { text.contains(it) }
        assertTrue(
            "以下设置字段没有出现在 effective_settings.txt 里，现场排查会看不到它们：$missing",
            missing.isEmpty(),
        )
    }
}
