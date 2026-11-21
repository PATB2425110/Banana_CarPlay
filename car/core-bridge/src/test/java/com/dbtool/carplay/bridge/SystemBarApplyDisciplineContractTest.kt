package com.dbtool.carplay.bridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「改完设置之后按钮点不动，点一次重连就恢复」的窗口层契约（PATCH-0105，2026-10-12）。
 *
 * ## 现场（两份证据）
 *
 * 模拟器 21:12–21:24：主线程空闲在 `MessageQueue.nativePollOnce`、
 * `dumpsys gfxinfo` 累计帧数 40 秒不涨、无 ANR、窗口 `isVisible=true` 且有 surface
 * —— 也就是**触摸没送进进程**；而恢复的那一刻恰好是**一次会话（重）建立**。
 * 同一时刻的 `dumpsys window` 里留着一条铁证：
 *
 * ```
 * mControl=InsetsSourceControl type=ITYPE_IME mLeash=… animation-leash of insets_animation
 * mIsLeashReadyForDispatching=true mImeShowing=false mHasSurface=false
 * ```
 *
 * IME 自己已经没了（无 surface、不显示、高度 0），**insets 动画事务却还攥着 leash**
 * ⇒ 事务永远走不完 ⇒ 窗口收不到触摸。
 *
 * ## 成因（本测试要钉死的三条纪律）
 *
 * ① `applySystemBars` 原先走两条通道：`WindowInsetsControllerCompat.hide()/show()`
 *    （现代通道，API 30+ 就是**带 leash 的动画**）**再**裸写
 *    `decorView.systemUiVisibility`。设置页有文本框、改设置又触发自动保存与整页重组
 *    ⇒ "输入法正忙 + 我们又把标志位重写一遍"，两条通道互相打架。
 *    **API 30+ 必须只走 Compat 通道**（裸写只留给 API 28/29：吉利 CS11 实测拒绝
 *    Compat 请求，需要第二手，见 `FullscreenInsetContractTest`）。
 * ② **输入法在途时不写标志位**，整轮顺延一帧（一次性守卫，避免永远不应用）。
 * ③ 宿主与 UI 层各留一个**输入探针**，下一次复现不用再抓线程栈就能回答
 *    "触摸走到哪一层"。
 *
 * 这个测试**静态读源码**（`:car:app` 没有 test source set，见项目纪律 §127）。
 */
class SystemBarApplyDisciplineContractTest {

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

    /** 去掉块注释与行注释：断言源码里有没有某个字面量时必须走这个（项目纪律 §29）。 */
    private fun codeOf(relative: String): String =
        sourceOf(relative)
            .replace(Regex("""/\*[\s\S]*?\*/"""), "")
            .replace(Regex("""//[^\n]*"""), "")

    /**
     * 取函数体（花括号配平）。
     *
     * ⚠️ 必须配平而不是"到下一个 `}` 为止"：lambda / 字符串里的 `}` 会把函数体切断，
     * 于是"源码里明明有"的断言变成假红（项目纪律 §131）。
     */
    private fun functionBody(code: String, signature: String): String {
        val start = code.indexOf(signature)
        check(start >= 0) { "找不到 $signature" }
        val bodyOpen = code.indexOf('{', start)
        check(bodyOpen >= 0) { "$signature 没有函数体" }
        var depth = 0
        var i = bodyOpen
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

    private val activitySrc = "car/app/src/main/java/com/dbtool/carplay/app/MainActivity.kt"
    private val appSrc = "car/ui/src/main/java/com/dbtool/carplay/ui/navigation/CarPlayApp.kt"

    // ------------------------------------------------- 纪律 ①：裸写被 API 闸门包住

    @Test
    fun `旧 API 兜底必须被 API Q 闸门包住`() {
        val body = functionBody(codeOf(activitySrc), "private fun applySystemBars(")

        assertTrue(
            "兜底写入不见了 —— API 28/29 的车机（CS11）拒绝 Compat 请求时就没有第二手。" +
                "见 FullscreenInsetContractTest。",
            body.contains("systemUiVisibility ="),
        )
        val gate = body.indexOf("Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q")
        assertTrue(
            "API 闸门不见了 —— API 30+ 上系统栏由 WindowInsetsController 带 leash 动画驱动，" +
                "再裸写 systemUiVisibility 会绕过它的记账，正是窗口收不到触摸的成因。",
            gate >= 0,
        )
        val write = body.indexOf("systemUiVisibility =")
        assertTrue(
            "裸写落在 API 闸门**之前**（或闸门根本没包住它）—— 指令顺序是：先判 API，" +
                "再决定要不要裸写。",
            write > gate,
        )
    }

    // ------------------------------------------------- 纪律 ②：输入法在途时顺延

    @Test
    fun `输入法在途时整轮顺延而不是硬写`() {
        val body = functionBody(codeOf(activitySrc), "private fun applySystemBars(")

        assertTrue(
            "必须在写标志位之前判断「输入法是否可见」——" +
                "IME 事务在途时重写系统栏标志就是这次 insets 事务卡住的那条路。",
            body.contains("isVisible(WindowInsetsCompat.Type.ime())"),
        )
        assertTrue(
            "顺延必须走 `post` 到下一帧，不能就地 `Thread.sleep` / 忙等（会把主线程占住）。",
            body.contains("decorView.post { applySystemBars("),
        )
        assertTrue(
            "顺延必须有一次性守卫（systemBarsDeferredOnce）——" +
                "没有它，输入法一直开着就变成「标志位永远不应用」。",
            body.contains("systemBarsDeferredOnce"),
        )
        assertTrue(
            "顺延分支必须 `return`：顺手继续往下写就等于没顺延。",
            Regex("""if \(imeVisible && !systemBarsDeferredOnce\) \{[\s\S]*?return""")
                .containsMatchIn(body),
        )
    }

    // ------------------------------------------------- 纪律 ③：输入探针两侧就位

    @Test
    fun `宿主侧输入探针就位`() {
        val code = codeOf(activitySrc)
        assertTrue(
            "MainActivity 没有覆写 dispatchTouchEvent —— 就无从判断" +
                "『窗口层到底有没有收到这一下触摸』（本次现场的唯一分界点）。",
            code.contains("override fun dispatchTouchEvent("),
        )
        assertTrue(
            "探针必须只在 ACTION_DOWN 上记时（每根手指都打一行会把 logcat 冲掉）。",
            code.contains("MotionEvent.ACTION_DOWN"),
        )
        assertTrue(
            "缺少窗口层那条日志（输入诊断：窗口层收到按下）。",
            code.contains("输入诊断：窗口层收到按下"),
        )
        assertTrue(
            "缺少『窗口层收到、UI 层没收到』那条 W 级告警 —— 它才是下一次复现的判定行。",
            code.contains("窗口层收到按下，但 UI 层"),
        )
    }

    @Test
    fun `UI 侧探针只读不消费`() {
        val code = codeOf(appSrc)
        assertTrue(
            "CarPlayApp 缺少 onUiTouch 回调参数 —— 宿主无从知道触摸有没有走进 Compose 命中路径。",
            code.contains("onUiTouch: () -> Unit"),
        )
        assertTrue(
            "缺少探针修饰符 touchHealthProbe。",
            code.contains("touchHealthProbe"),
        )
        assertTrue(
            "探针必须走 Initial 阶段读事件（只读、不消费）——" +
                "一旦落到 Main 阶段并 consume，点击/拖拽/手势操作全都会跟着变。",
            code.contains("PointerEventPass.Initial"),
        )
        val probe = functionBody(code, "private fun Modifier.touchHealthProbe(")
        assertFalse(
            "探针里出现了 `consume()` —— 它只能观测，绝不能吃掉事件（那会改变触摸行为）。",
            probe.contains("consume("),
        )
        assertFalse(
            "探针里出现了 `detectTapGestures` —— 那是手势识别器，会与业务点击抢事件。",
            probe.contains("detectTapGestures"),
        )
    }
}
