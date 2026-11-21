package com.dbtool.carplay.bridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「CarPlay 画面页只放画面、不放常驻 UI」回归契约。
 *
 * ## 这一条被反复翻过两次，所以必须用测试钉住
 *
 * **第一版**：画面页一个屏幕内入口都没有。画面播放时 `Box` 里只有 `TextureView`，
 * 而它无条件吃掉全部触摸 ⇒ 用户只能靠系统返回键，而本车机不一定把 BACK 派发给我们。
 * 于是加了右上角「返回主页」蓝色胶囊。
 *
 * **第二版（当前）**：车主实车体验后认为**那个按钮在画面上很碍事**——
 * 画面页的主角是 iPhone 的画面，右上角常驻一个品牌蓝实底胶囊像贴纸，故**移除**。
 *
 * ⇒ 结论：**画面分支里除了视频层，不允许再出现任何常驻 UI 组件。**
 * 这个测试把这条钉死，防止有人"出于好心"又把按钮加回来。
 *
 * ⚠️ **若实车再次出现"退不出去"**，先查车机面板键的 dispatch 链
 * （`CarInputSource.PANEL` / `CarKeyHidMap` / `super.dispatchKeyEvent`），
 * **不要直接把按钮加回来**——那会推翻车主已经明确表达过的界面偏好。
 * 真到了必须补屏幕入口的地步，也是先和车主确认，再来改这个测试。
 */
class CarPlayScreenExitContractTest {

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

    /** 去掉块注释与行注释——源码级断言必须先剥注释，否则会匹配到 KDoc 里的示例文字。 */
    private fun codeOf(relative: String): String =
        sourceOf(relative)
            .replace(Regex("""/\*[\s\S]*?\*/"""), "")
            .replace(Regex("""//[^\n]*"""), "")

    private val screenSrc =
        "car/ui/src/main/java/com/dbtool/carplay/ui/screens/CarPlayScreen.kt"

    /** 取出「画面正在播」那一支的源码（`if (session.showVideo)` … `} else {`）。 */
    private fun videoBranch(code: String): String {
        val branch = code.substringAfter("if (session.showVideo)", "")
        assertTrue("找不到 `if (session.showVideo)` 分支，测试本身失效了。", branch.isNotEmpty())
        val shown = branch.substringBefore("} else {")
        assertTrue("切不出画面分支，测试本身失效了。", shown.isNotEmpty())
        return shown
    }

    /**
     * 画面分支里**不允许**再有悬浮返回按钮。
     *
     * 断言的是**行为不存在**：曾被加入的 `CarPlayVideoExitButton` 必须已经删干净。
     */
    @Test
    fun `画面分支不允许出现悬浮返回按钮`() {
        val shown = videoBranch(codeOf(screenSrc))

        assertFalse(
            "画面分支里又出现了 CarPlayVideoExitButton。该按钮已于 2026-10-06 按车主要求移除" +
                "（画面页主角是 iPhone 的画面，常驻蓝色胶囊很碍事）。" +
                "若确需补屏幕内退出口，请先与车主确认，再一并更新本测试。",
            shown.contains("CarPlayVideoExitButton"),
        )
    }

    /**
     * `CarPlayVideoExitButton` 这个 composable 本身也必须彻底删掉。
     *
     * 光删调用点不够：留一个没人调的 `private` 函数，下一次有人看到它会以为
     * "这是设计的一部分"，于是重新接回去。**修法要连根拔。**
     */
    @Test
    fun `CarPlayVideoExitButton 函数本身也要删掉`() {
        val code = codeOf(screenSrc)

        assertFalse(
            "源码里还留着 `fun CarPlayVideoExitButton`。既然决定不要这个悬浮按钮，" +
                "函数定义也要删掉——否则下一个读到它的人会以为这是设计的一部分而重新接回去。",
            code.contains("fun CarPlayVideoExitButton"),
        )
    }

    /**
     * 画面分支里**只应有视频层**，没有其它常驻 UI。
     *
     * 判据用具体组件名而不是泛化的"有没有其他调用"——避免误伤
     * 未来可能合理加入的空状态（那在 `else` 分支，本就在画面分支之外）。
     */
    @Test
    fun `画面分支只保留视频层`() {
        val shown = videoBranch(codeOf(screenSrc))

        assertTrue(
            "画面分支里找不到 CarPlayVideoSurface —— 视频层没了。",
            shown.contains("CarPlayVideoSurface("),
        )
        // `CarButton` / `CarIconBadge` 这类常驻 UI 组件不应出现在画面分支里
        // （空状态里的按钮在 `else` 分支，不受影响）。
        listOf("CarButton(", "CarIconBadge(").forEach { widget ->
            assertFalse(
                "画面分支里出现了常驻 UI 组件 `$widget`。画面页播放状态下不允许叠加常驻控件，" +
                    "车主明确要求画面之上只有 iPhone 的画面本身。",
                shown.contains(widget),
            )
        }
    }
}
