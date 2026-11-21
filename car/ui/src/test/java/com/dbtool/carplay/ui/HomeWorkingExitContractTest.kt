package com.dbtool.carplay.ui

import com.dbtool.carplay.api.session.CarSessionPhase
import com.dbtool.carplay.api.session.CarSessionState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 主页「过程态必须留一条出口」的契约（PATCH-0105，2026-10-12）。
 *
 * ## 现场
 *
 * 车主口径：「设置发生改动后必须点一次重连，其他按钮才能被正常点击或触发」。
 * 其中一条可复现的路径就是主页：
 *
 * - 状态机（[CarUiStateMapper.project]）在**过程态**只提供 `STOP`；
 * - 而 [HomeScreen] 此前只渲染 `RECONNECT`（重试）与 `OPEN_SETTINGS`（打开设置），
 *   两者在过程态都**不成立** ⇒ **整页没有任何可点按钮**，只剩一个转圈的枢纽。
 *
 * 于是"要出来只能等 / 重启 App / 去点一次重连"——正是车主描述的那件事。
 *
 * ## 本测试钉两层
 *
 * 1. **纯逻辑**：任何非终态相位都必须提供一个可用的退出动作（`STOP`）——
 *    这是"没有出口"在结构上不可能出现的前提；
 * 2. **静态读源码**：主页真的把 `STOP` 摆成了次要按钮（判据是 `availableActions`，
 *    不是字面判断相位）。
 */
class HomeWorkingExitContractTest {

    /** 过程态（既不是"没启动"也不是"已连上/失败/已停止"）。 */
    private val workingPhases = listOf(
        CarSessionPhase.PREPARING_MFI,
        CarSessionPhase.WAITING_FOR_MFI,
        CarSessionPhase.WAITING_FOR_DEVICE,
        CarSessionPhase.CONNECTING,
        CarSessionPhase.RECONNECTING,
    )

    @Test
    fun `过程态必须给出停止动作`() {
        val state = CarSessionState()
        workingPhases.forEach { phase ->
            val projected = CarUiStateMapper.project(
                state = state.copy(phase = phase),
                settings = null,
            )
            assertTrue(
                "相位 $phase 没有任何可用动作 —— 用户被关在一个只能等的页面里" +
                    "（现场表现：整页没按钮，只能去点一次重连）。",
                CarUiAction.STOP in projected.availableActions,
            )
            assertTrue(
                "相位 $phase 的指示器应当是 WORKING（界面据此摆放次要按钮）。",
                projected.indicator == CarStatusIndicator.WORKING,
            )
        }
    }

    @Test
    fun `终态不能混进过程态的判据`() {
        // 反向：把"已连上"也算成过程态就会给正常状态塞一个容易误触的断开按钮。
        val active = CarUiStateMapper.project(
            state = CarSessionState(phase = CarSessionPhase.ACTIVE),
            settings = null,
        )
        assertFalse(
            "ACTIVE 被判成了过程态 —— 主页会给正在投屏的用户多摆一个断开按钮。",
            active.indicator == CarStatusIndicator.WORKING,
        )
    }

    // ------------------------------------------------------------ 静态读源码

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

    @Test
    fun `主页把停止动作摆出来`() {
        val code = codeOf("src/main/java/com/dbtool/carplay/ui/screens/HomeScreen.kt")

        assertTrue(
            "主页没有引用 STOP 动作 —— 过程态又没有出口了（本条就是现场那个 bug 的回归护栏）。",
            code.contains("CarUiAction.STOP in session.availableActions"),
        )
        assertTrue(
            "主页缺少「停止连接」这条次要出路。",
            code.contains("停止连接"),
        )
        assertTrue(
            "「停止连接」没有接到动作上（必须调到 CarUiActions.stop）。",
            code.contains("actions::stop"),
        )
        assertTrue(
            "判据必须走状态机（stoppable / availableActions），不能自己猜相位。",
            code.contains("stoppable"),
        )
    }
}
