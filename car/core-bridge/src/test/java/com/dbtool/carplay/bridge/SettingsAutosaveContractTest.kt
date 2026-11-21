package com.dbtool.carplay.bridge

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置页**自动保存**的接线契约（PATCH-0103，2026-10-12 车主口径）。
 *
 * ## 这次改的是什么
 *
 * 设置页从"草稿 + 「保存并重连」"改成：**改完静置一小会儿就自动写盘**，
 * 底部常驻一行保存状态；那些**必须重连才生效**的改动另外提示 + 「重连生效」按钮
 * （重连会掐断正在投的屏，所以只由用户点）。
 *
 * ## 为什么必须钉在测试里
 *
 * 这套东西有四个**错了完全看不出来**的失败模式，界面与构建都可能是绿的：
 *
 * 1. **待重连提示永远不显示** —— `refreshModel()` 忘了填 `settingsPendingReconnect`，
 *    （漏接 `CarSettingsReconnectPolicy`）。用户改了画布尺寸，
 *    看到的是"已保存"，然后以为生效了。
 * 2. **提示永远不消失** —— 用户点了「重连生效」但 `reconnect()` 没推进生效基线。
 *    界面上永远挂着一句"有改动需重连生效"，很快用户就不再理它了
 *    （这就是"狼来了"式失效，比没有提示更糟）。
 * 3. **基线在系统自动重连里被顺手推进** —— 自动重连（会话断了自检重连、画布尺寸上报
 *    变化）也走 `session.reconnect()`。若在那些地方也推进基线，"待重连"提示会在
 *    用户毫无操作的情况下悄悄消失。
 * 4. **草稿在防抖窗口内丢失** —— 自动保存靠防抖，离开设置页时若没有兜底提交，
 *    最后那一下改动就没了，而用户以为"自动保存"早就替他存好了。
 *
 * 前四条分别落在 `:car:app` / `:car:ui` 的实现里，**没有运行时可测的接缝**
 * （`CarAppController` 依赖一大串 Android 对象），所以用静态读源码断言兜住。
 *
 * ⚠️ 断言一律走 [codeOf]（**先剥注释**）。本文件与实现里的注释**必然**出现这些字面量
 * （比如说明"以前有「保存并重连」按钮"），不剥离就会匹配到注释 ⇒ 假通过。
 */
class SettingsAutosaveContractTest {

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

    /** 去掉块注释与行注释再返回（项目纪律：断言源码字面量前必须剥注释）。 */
    private fun codeOf(relative: String): String =
        sourceOf(relative)
            .replace(Regex("""/\*[\s\S]*?\*/"""), "")
            .replace(Regex("""//[^\n]*"""), "")

    /** 从函数签名截到与之配平的右括号（含）。调用前必须先剥注释。 */
    private fun functionBody(code: String, signature: String): String {
        val start = code.indexOf(signature)
        assertTrue("找不到函数签名：$signature", start >= 0)
        // 先跳过**参数列表**：Kotlin 的参数默认值里可以有 lambda（`actions: … = {}`），
        // 从签名后直接找第一个 `{` 会截到那个空 lambda 体上，得到的"函数体"只有两个字符。
        val listStart = code.indexOf('(', start)
        assertTrue("$signature 没有参数列表", listStart >= 0)
        var depth = 0
        var i = listStart
        var listEnd = -1
        while (i < code.length) {
            val c = code[i]
            if (c == '(') depth++
            else if (c == ')') {
                depth--
                if (depth == 0) {
                    listEnd = i
                    break
                }
            }
            i++
        }
        assertTrue("$signature 的参数列表没闭合", listEnd >= 0)
        val bodyOpen = code.indexOf('{', listEnd)
        assertTrue("$signature 后面没有函数体", bodyOpen >= 0)
        depth = 0
        i = bodyOpen
        while (i < code.length) {
            val c = code[i]
            if (c == '{') depth++
            else if (c == '}') {
                depth--
                if (depth == 0) return code.substring(bodyOpen, i + 1)
            }
            i++
        }
        throw IllegalStateException("$signature 的函数体括号不平衡")
    }

    private val controller =
        "car/app/src/main/java/com/dbtool/carplay/app/CarAppController.kt"
    private val settingsScreen =
        "car/ui/src/main/java/com/dbtool/carplay/ui/screens/SettingsScreen.kt"
    private val scaffold =
        "car/ui/src/main/java/com/dbtool/carplay/ui/components/CarScaffold.kt"
    private val uiContract =
        "car/ui/src/main/java/com/dbtool/carplay/ui/CarUiContract.kt"

    // ---------------------------------------- 失败模式 1：待重连提示接不上

    @Test
    fun 模型字段存在() {
        val code = codeOf(uiContract)
        assertTrue("CarUiModel 缺 settingsPendingReconnect 字段", code.contains("settingsPendingReconnect"))
    }

    @Test
    fun refreshModel_把基线判定结果填进模型() {
        val body = functionBody(codeOf(controller), "private fun refreshModel()")
        assertTrue(
            "refreshModel 没有用 CarSettingsReconnectPolicy.requiresReconnect —— 待重连提示永远是假的",
            body.contains("CarSettingsReconnectPolicy"),
        )
        assertTrue(
            "requiresReconnect 的两个实参应是 (reconnectBaseline, settings)，否则基线与当前值对不上",
            body.contains("requiresReconnect(reconnectBaseline, settings)"),
        )
    }

    // ------------------------------------- 失败模式 2/3：基线只在正确的两处推进

    @Test
    fun startSession_推进生效基线() {
        assertTrue(
            "startSession 没把当前设置设为生效基线 —— 冷启动后一屏设置全被判成'待重连'",
            functionBody(codeOf(controller), "fun startSession()").contains("reconnectBaseline = settings"),
        )
    }

    @Test
    fun reconnect_用户手动重连时推进基线() {
        // 「重连生效」按钮 → CarUiActions.reconnect()。这里不推进，提示就永远不消失。
        val body = functionBody(codeOf(controller), "override fun reconnect()")
        assertTrue("reconnect() 没推进生效基线 —— 点完「重连生效」提示还在", body.contains("reconnectBaseline = settings"))
    }

    @Test
    fun applySettings_实际重连时才推进基线() {
        val body = functionBody(codeOf(controller), "override fun applySettings(")
        // 2026-10-20 重连链修复：重连入口收敛到 requestReconnect(source)。
        // 断言判据仍在（且走 SETTINGS 来源，不重置自动重连计数）。
        assertTrue(
            "applySettings 的重连判断不见了 —— 没有它，自动保存（reconnect=false）也会把基线推进，" +
                "待重连提示永远不会出现",
            body.contains("if (reconnect || clusterDisplayChanged)") &&
                body.contains("requestReconnect(ReconnectSource.SETTINGS)"),
        )
        assertTrue(
            "applySettings 没在重连后推进基线 —— 换仪表屏那种自动重连场景的提示会赖着不走",
            body.contains("if (reconnect || clusterDisplayChanged) reconnectBaseline = settings"),
        )
    }

    @Test
    fun 系统自动重连不推进基线() {
        // 会话断了自检重连、画布尺寸上报变化重连 —— 都是系统自发的，不是用户提交设置。
        // 在这几处也推进基线 ⇒ "待重连"提示会在用户毫无操作时悄悄消失。
        //
        // 办法：数全文件的赋值点。设计上只有 startSession / reconnect / applySettings
        // 三处（后两处分别有专门的函数体断言钉住），所以任何新增的赋值点都会让这里变红。
        val code = codeOf(controller)
        // applySettings 现在按模式切换分成两条执行路径：模式清理完成后的回调路径，
        // 以及无模式切换时的同步路径；两条路径各自推进一次，避免异步清理前误报已生效。
        assertEquals("reconnectBaseline 的赋值点数量变了", 4, countOf(code, "reconnectBaseline = settings"))
    }

    private fun countOf(code: String, needle: String): Int {
        var total = 0
        var index = code.indexOf(needle)
        while (index >= 0) {
            total++
            index = code.indexOf(needle, index + needle.length)
        }
        return total
    }

    // ---------------------------------------- 失败模式 4：防抖窗口内草稿丢失

    @Test
    fun 设置页自动保存走的是不重连的提交() {
        val code = codeOf(settingsScreen)
        val screen = functionBody(code, "fun SettingsScreen(")
        assertTrue(
            "SettingsScreen 没有把草稿交给 commitSettings —— 自动保存的提交路径换了",
            screen.contains("commitSettings(actions, draft)"),
        )
        val commit = functionBody(code, "private fun commitSettings(")
        assertTrue(
            "commitSettings 用的是带重连的提交 —— 每改一次设置就会掐断一次正在投的屏",
            commit.contains("applySettings(settings, reconnect = false)"),
        )
    }

    @Test
    fun 离开设置页时有兜底提交() {
        val body = functionBody(codeOf(settingsScreen), "fun SettingsScreen(")
        assertTrue("没有 DisposableEffect 兜底 —— 防抖窗口内返回会丢改动", body.contains("DisposableEffect(Unit)"))
        assertTrue("兜底里没有 onDispose", body.contains("onDispose"))
        assertTrue(
            "兜底读的不是最新草稿（应当用 rememberUpdatedState）—— 会永远提交首次组合时的旧值",
            body.contains("rememberUpdatedState(draft)"),
        )
    }

    @Test
    fun 自动保存有防抖窗口() {
        val code = codeOf(settingsScreen)
        assertTrue("没有防抖常量", code.contains("SETTINGS_AUTOSAVE_DEBOUNCE_MILLIS"))
        assertTrue("没有 delay 调用 —— 改动会逐帧写盘", code.contains("delay(SETTINGS_AUTOSAVE_DEBOUNCE_MILLIS)"))
    }

    @Test
    fun 底部状态行与重连按钮就位() {
        val code = codeOf(settingsScreen)
        assertTrue("没有保存状态行组件", code.contains("private fun SettingsSaveStatusBar("))
        assertTrue("顶栏没有「重连生效」", code.contains("text = \"重连生效\""))
        val statusBar = functionBody(code, "private fun SettingsSaveStatusBar(")
        assertFalse(
            "「重连生效」按钮还在底部状态行里",
            statusBar.contains("text = \"重连生效\""),
        )
        val screen = functionBody(code, "fun SettingsScreen(")
        assertTrue(
            "「重连生效」没有接到顶栏 actions",
            screen.contains("actions = reconnectAction"),
        )
        assertTrue("状态行没接 footer（不常驻底部就等于没有提示）", code.contains("footer = saveFooter"))
        // 「保存并重连」「放弃修改」按设计已撤掉：自动保存之后保存不需要用户动作，
        // 而已落盘的草稿也没有"放弃"的对象。
        assertFalse(
            "「保存并重连」按钮还在 —— 那会让用户以为不点就不会保存，与自动保存矛盾",
            code.contains("保存并重连"),
        )
        assertFalse(
            "「放弃修改」按钮还在 —— 草稿已落盘，放弃会丢弃用户以为『已经生效』的改动",
            code.contains("放弃修改"),
        )
    }

    @Test
    fun 页面骨架提供常驻底部插槽() {
        val code = codeOf(scaffold)
        // 参数在签名里（函数体是配平后的右括号起算），所以参数断言要落在签名片段上。
        val signature = code.substringAfter("fun CarScreen(").substringBefore(") {")
        assertTrue("CarScreen 没有 footer 参数", signature.contains("footer: (@Composable () -> Unit)? = null"))
        val body = functionBody(code, "fun CarScreen(")
        assertTrue("footer 没有被渲染出来", body.contains("footer != null"))
    }
}
