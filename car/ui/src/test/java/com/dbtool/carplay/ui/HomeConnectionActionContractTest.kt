package com.dbtool.carplay.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 首页连接动作矩阵的源码级契约（方案 §4.2 / §6.2）。
 *
 * Compose 的按钮渲染需要仪器化环境，这里沿用仓库既有的 `*ContractTest` 写法：
 * 读源码、剥掉注释后断言关键分支存在，防止后续改动把出口删掉。
 */
class HomeConnectionActionContractTest {

    private fun codeOf(relative: String): String {
        val candidates = listOf(
            File(relative),
            File("../$relative"),
            File("../../$relative"),
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("找不到源码文件 $relative（从 ${File(".").absolutePath} 起找过 ${candidates.size} 个位置）")
        return file.readText()
            .replace(Regex("""/\*[\s\S]*?\*/"""), "")
            .replace(Regex("""//[^\n]*"""), "")
    }

    private fun homeCode(): String =
        codeOf("src/main/java/com/dbtool/carplay/ui/screens/HomeScreen.kt")

    @Test
    fun `自动启动只在 IDLE 触发`() {
        val code = homeCode()
        assertTrue(
            "自动启动没有用 phase == IDLE 做闸门 —— STOPPED 会被再次自动启动，" +
                "用户点停止后 App 会自己连回去。",
            code.contains("session.phase == CarSessionPhase.IDLE"),
        )
        assertTrue(
            "自动启动的判据必须走 availableActions（状态机），不能自己猜相位。",
            code.contains("CarUiAction.START in session.availableActions"),
        )
    }

    @Test
    fun `STOPPED 渲染启动 CarPlay 按钮`() {
        val code = homeCode()
        assertTrue(
            "首页没有 STOPPED 的启动分支 —— 自动启动不再覆盖 STOPPED 后，" +
                "页面会没有任何可点按钮。",
            code.contains("CarSessionPhase.STOPPED"),
        )
        assertTrue(
            "缺少 startable 判据。",
            code.contains("startable"),
        )
        assertTrue(
            "主按钮文案不是「启动 CarPlay」。",
            code.contains("\"启动 CarPlay\""),
        )
        assertTrue(
            "「启动 CarPlay」没有接到 CarUiActions.start。",
            code.contains("actions::start"),
        )
    }

    @Test
    fun `ACTIVE 的停止连接必须二次确认`() {
        val code = homeCode()
        assertTrue(
            "ACTIVE 没有进入 stoppable 分支 —— 运行中没有断开入口。",
            code.contains("session.phase == CarSessionPhase.ACTIVE"),
        )
        assertTrue(
            "停止判据没有走 stoppable。",
            code.contains("stoppable"),
        )
        assertTrue(
            "ACTIVE 的停止没有弹出 CarAlertDialog。",
            code.contains("CarAlertDialog("),
        )
        assertTrue(
            "缺少 confirmStop 本地状态。",
            code.contains("confirmStop"),
        )

        val requestConfirm = code.indexOf("confirmStop = true")
        val actualStop = code.indexOf("actions.stop()")
        assertTrue(
            "找不到「弹出确认」的位置。",
            requestConfirm >= 0,
        )
        assertTrue(
            "找不到确认后的 actions.stop() 调用。",
            actualStop >= 0,
        )
        assertTrue(
            "确认框与 stop() 的顺序不对 —— 必须先弹确认（confirmStop = true），" +
                "确认回调里才调 actions.stop()。",
            actualStop > requestConfirm,
        )
    }

    @Test
    fun `画面页重新连接按钮受 availableActions 约束`() {
        val code = codeOf("src/main/java/com/dbtool/carplay/ui/screens/CarPlayScreen.kt")
        assertTrue(
            "空状态页的「重新连接」仍然无条件可点 —— 重连排期/重建中会被绕过退避。",
            code.contains("enabled = CarUiAction.RECONNECT in session.availableActions"),
        )
    }
}
