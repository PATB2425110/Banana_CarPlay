package com.dbtool.carplay.bridge

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「本机可填的 CarPlay 地址候选」的接线契约（PATCH-0102，2026-10-12）。
 *
 * ## 为什么钉它
 *
 * 这条链**跨四个模块**：`:car:api`（纯判据）→ `:car:core-bridge`（读网卡、进简报）
 * → `:car:app`（进 model）→ `:car:ui`（渲染成可点行）。任何一环漏接，
 * 表现都是**界面照旧、构建全绿、只是候选永远不出现** —— 典型的静默失败
 * （与 `REF-pitfalls.md` §104 同族：策略写好了却没接上）。
 *
 * 而它要解决的恰恰是"用户不知道该填什么"：过去设置页只有一个空输入框，
 * 提示文案里写死的 `192.168.15.1` 又是外置 T-Box 网关、不在本机网卡上
 * ⇒ 用户照填必然被连接前的 precheck 拦下（真车 `日志/3`：10 次尝试全灭，见 §125）。
 *
 * ## 为什么静态读源码
 *
 * 四个模块里 `:car:app` 没有 test source set，`:car:ui` 的 Composable 也起不来
 * ⇒ 没有运行时可测的接缝。手法与 `ClusterGestureGateContractTest` 一致。
 *
 * ⚠️ 断言一律先剥注释（§29）—— 这几个文件的注释里必然出现同样的字面量。
 */
class HotspotEndpointCandidatesWiringContractTest {

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
    fun `core-bridge 真的去读了本机网卡并把候选放进简报`() {
        val code = codeOf(services)

        assertTrue(
            "简报里必须真的调一次判据去读网卡（否则候选永远是空的）",
            code.contains("CarHotspotEndpointCandidates.of("),
        )
        assertTrue(
            "读出来的结果必须填进 `endpointCandidates` 字段",
            code.contains("endpointCandidates = "),
        )
        assertTrue(
            "`CarHotspotBriefing` 必须有 `endpointCandidates` 字段（否则上面那行填不进去）",
            code.contains("val endpointCandidates: List<CarHotspotEndpointCandidate>"),
        )
    }

    @Test
    fun `app 层把候选从简报搬进 UI model`() {
        val code = codeOf(controller)

        assertTrue(
            "model 必须拿到候选；且来源是**那一份简报**（与 precheck 同一次网卡枚举）",
            code.contains("hotspotAddressCandidates = hotspotBriefing?.endpointCandidates"),
        )
    }

    @Test
    fun `UI model 声明了候选字段`() {
        val code = codeOf(contract)

        assertTrue(
            "`CarUiModel` 必须有 `hotspotAddressCandidates` 字段",
            code.contains("val hotspotAddressCandidates: List<CarHotspotEndpointCandidate>"),
        )
    }

    @Test
    fun `设置页把候选渲染成可点的行，点了就填进地址框`() {
        val code = codeOf(settingsScreen)

        assertTrue(
            "设置页必须消费候选字段",
            code.contains("model.hotspotAddressCandidates"),
        )
        assertTrue(
            "点一下必须真的写回「本机 CarPlay 地址」（只显示不能点 = 没解决问题）",
            code.contains("manualHotspotHostAddress = candidate.address"),
        )
    }
}
