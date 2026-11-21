package com.dbtool.carplay.bridge

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「一键填入车机真实热点名/密码」的接线契约（2026-10-13）。
 *
 * ## 为什么钉它
 *
 * 这条链**跨四个模块**：`:car:api`（`CarNativeHotspotCredentials.of` 纯判据）→
 * `:car:core-bridge`（进 `CarHotspotBriefing`）→ `:car:app`（进 UI model）→
 * `:car:ui`（设置页渲染成可点行）。任何一环漏接，表现都是
 * **界面照旧、构建全绿、只是按钮永远不出现** —— 与
 * `HotspotEndpointCandidatesWiringContractTest` 同族的静默失败。
 *
 * 而它要解决的正是真车日志 4 的默写错误：`Lynk&Co` ↔ 真名 `Lynk & Co`
 * （空格之差 ⇒ 通告一张没人广播的网，iPhone 静默连不上）。
 *
 * ⚠️ 断言一律先剥注释（§29）—— 这些文件的注释里必然出现同样的字面量。
 */
class NativeHotspotCredentialsWiringContractTest {

    // 单测 cwd = car/core-bridge（模块目录）。仓库根在两级之上。
    private fun sourceOf(relative: String): String {
        val candidates = listOf(
            File(relative),
            File("../$relative"),
            File("../../$relative"),
            File("../../../$relative"),
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("找不到源码文件 $relative（从 ${File(".").absolutePath} 起找过 ${candidates.size} 个位置）")
        return file.readText()
    }

    /** 去掉块注释与行注释再返回（项目纪律 §29）。 */
    private fun codeOf(relative: String): String =
        sourceOf(relative)
            .replace(Regex("""/\*[\s\S]*?\*/"""), "")
            .replace(Regex("""//[^\n]*"""), "")

    private val services =
        "car/core-bridge/src/main/java/com/dbtool/carplay/bridge/CarAppServices.kt"
    private val controller =
        "car/app/src/main/java/com/dbtool/carplay/app/CarAppController.kt"
    private val contract = "car/ui/src/main/java/com/dbtool/carplay/ui/CarUiContract.kt"
    private val settingsScreen =
        "car/ui/src/main/java/com/dbtool/carplay/ui/screens/SettingsScreen.kt"

    @Test
    fun `core-bridge 把真实凭据放进简报_且只在复用模式下给`() {
        val code = codeOf(services)

        assertTrue(
            "简报必须真的调判据读凭据（否则按钮永远是空的）",
            code.contains("CarNativeHotspotCredentials.of("),
        )
        assertTrue(
            "读出来的结果必须填进 `nativeCredentials` 字段",
            code.contains("nativeCredentials = "),
        )
        assertTrue(
            "`CarHotspotBriefing` 必须有 `nativeCredentials` 字段",
            code.contains("val nativeCredentials: CarNativeHotspotCredentials?"),
        )
        assertTrue(
            "必须门控在**复用类档位**（`reusesCarNativeHotspot` = MANUAL / TCAM_HOTSPOT）：" +
                "2026-10-16 起界面上的复用档是 TCAM_HOTSPOT，只认 MANUAL 会让按钮永远不出现；" +
                "自建模式下那两框命名的是我们自己的网，填入车机凭据是错的",
            code.contains("settings.hotspotMode.reusesCarNativeHotspot"),
        )
    }

    @Test
    fun `app 层把凭据从简报搬进 UI model`() {
        val code = codeOf(controller)

        assertTrue(
            "model 必须拿到凭据；且来源是**那一份简报**（与候选/警告同源）",
            code.contains("nativeHotspotCredentials = hotspotBriefing?.nativeCredentials"),
        )
    }

    @Test
    fun `UI model 声明了凭据字段`() {
        val code = codeOf(contract)

        assertTrue(
            "`CarUiModel` 必须有 `nativeHotspotCredentials` 字段",
            code.contains("val nativeHotspotCredentials: CarNativeHotspotCredentials?"),
        )
    }

    @Test
    fun `设置页渲染成可点的行_点了真的写回名称与密码`() {
        val code = codeOf(settingsScreen)

        assertTrue(
            "设置页必须消费凭据字段",
            code.contains("model.nativeHotspotCredentials"),
        )
        assertTrue(
            "点一下必须真的写回 SSID（只显示不能点 = 没解决问题）",
            code.contains("manualHotspotSsid = nativeCredentials.ssid"),
        )
        assertTrue(
            "点一下必须真的写回密码",
            code.contains("manualHotspotPassphrase = nativeCredentials.passphrase"),
        )
        assertTrue(
            "密码读不到时必须兜底成不动密码框（`?: draft.manualHotspotPassphrase`），" +
                "不能拿 null 清掉用户已填的密码",
            code.contains("?: draft.manualHotspotPassphrase"),
        )
    }
}
