package com.dbtool.carplay.bridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「设置全屏/隐藏顶栏后仍然有顶栏」的回归契约（2026-10-05 实车修复）。
 *
 * ## 故障现场
 *
 * 吉利 CS11（Flyme Auto，Android 9）实车日志：
 *
 * ```
 * content=[0,72][0,0]                              ← 窗口内容被切掉顶部 72px
 * Visible insets changing to: Rect(0, 72 - 0, 0)   ← 系统恒报顶部 72px inset
 * vsysui=LAYOUT_FULLSCREEN …                       ← 我们请求了隐藏，系统没答应
 * ```
 *
 * ## 成因
 *
 * 两条叠加：
 * 1. 系统栏隐藏请求只是**建议**，车机 ROM 拒绝 —— inset 恒为 72px；
 * 2. `CarPlayApp` 根节点**无条件** `windowInsetsPadding(safeDrawing)`，于是
 *    永远自动避开这 72px ⇒ 用户看到的"顶栏"。
 *
 * ## 修法
 *
 * ① 根节点改成**按设置条件化**：用户开了全屏/隐藏顶栏就不避让顶部；IME 永远避让。
 * ② `MainActivity.applySystemBars` 加 `SYSTEM_UI_FLAG_*` 旧 API 兜底，绕开
 *    部分车机 ROM 对 Compat 包装调用不认的问题。
 *
 * 这个测试**静态读源码**，把两条形态钉死。
 */
class FullscreenInsetContractTest {

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

    /**
     * 去掉块注释与行注释。
     *
     * **凡是"断言源码里有没有某个字面量"的用例都必须走这个**（项目纪律 §29）——
     * 说明性注释里**必然**会出现被断言的字面量（尤其"为什么不用 X"这种写法），
     * 不剥离就会匹配到注释 ⇒ 假失败。
     */
    private fun codeOf(relative: String): String =
        sourceOf(relative)
            .replace(Regex("""/\*[\s\S]*?\*/"""), "")
            .replace(Regex("""//[^\n]*"""), "")

    // 单测 cwd = car/core-bridge（模块目录）。仓库根在两级之上。
    private val appSrc =
        "car/ui/src/main/java/com/dbtool/carplay/ui/navigation/CarPlayApp.kt"

    private val activitySrc =
        "car/app/src/main/java/com/dbtool/carplay/app/MainActivity.kt"

    /**
     * 根节点**不能无条件**避让 `safeDrawing`。
     *
     * 无条件避让是原始 bug 的成因：车机拒绝隐藏状态栏时 inset 恒在，内容永远缩进 72px。
     * 必须能看到「按设置决定是否排除某条栏」的代码。
     */
    @Test
    fun `根节点 inset 必须按设置条件化`() {
        val src = sourceOf(appSrc)

        assertTrue(
            "必须出现 `barsToIgnore` —— 用户要求隐藏的那几条栏要排除在避让之外。",
            src.contains("barsToIgnore"),
        )
        assertTrue(
            "必须用 `exclude(barsToIgnore)` 把要隐藏的栏从 safeDrawing 里摘掉。",
            src.contains(".exclude(barsToIgnore)"),
        )
        assertTrue(
            "IME（输入法）必须无条件并回来：设置页有文本框，避让丢了键盘会盖住输入框。",
            src.contains(".union(WindowInsets.ime)"),
        )
        assertFalse(
            "旧写法 `windowInsetsPadding(WindowInsets.safeDrawing)` 已回归——" +
                "无条件避让 = 车机拒绝隐藏时永远留一条顶栏。",
            Regex("""windowInsetsPadding\(\s*WindowInsets\.safeDrawing\s*\)""").containsMatchIn(src),
        )
    }

    /** `barsToIgnore` 必须真的读取设置，不能写死。 */
    @Test
    fun `barsToIgnore 必须由设置驱动`() {
        val src = sourceOf(appSrc)
        // ⚠️ 2026-10-08：判定块改名成 `baseBarsToIgnore`（沉浸那一支在它之后
        // 叠加 `statusBars`，见下面的 `画面页顶格必须由沉浸设置驱动…`）。
        // 这里要断的是"那三个开关真的参与了判定"，所以取整个判定块到 `Box(` 为止。
        val block = src.substringAfter("val baseBarsToIgnore =", "")
        assertTrue("baseBarsToIgnore 不见了（判定块被改名或删掉了）", block.isNotEmpty())
        val body = block.substringBefore("\n        Box(")

        assertTrue(
            "全屏开关必须参与判定（`fullscreen`）。",
            body.contains("settings.fullscreen"),
        )
        assertTrue(
            "隐藏顶栏开关必须参与判定（`hideTopBar`）。",
            body.contains("settings.hideTopBar"),
        )
        assertTrue(
            "隐藏底栏开关必须参与判定（`hideBottomBar`）。",
            body.contains("settings.hideBottomBar"),
        )
    }

    /**
     * **画面页顶格必须由「沉浸」设置驱动，且只对画面页生效**（2026-10-08 车主定案）。
     *
     * ## 背景（同一件事翻了两次）
     *
     * 那层状态栏是**车机 SystemUI 的独立窗口**（`StatusBarWindowView{... 0,0-1920,72}`），
     * 不在我们进程里，`insets.hide(statusBars())` 请求会被 ROM 拒绝（本机已实证）
     * ⇒ **那 72px 永远抢不掉**。于是只有两个选项：
     *
     * - **A 避让**：画面砍 72px，协商分辨率 1920x1080 → **1920x1008**，带黑边；
     * - **B 顶格**：画面 1920x1080 满幅，但**状态栏浮在画面上遮住顶部 72px**。
     *
     * 车主 2026-10-06 先选 B，实车看过后**改回 A**——状态栏压住画面顶部不可接受。
     * 2026-10-08 车主再次提出要沉浸（"我想把 CarPlay 的 UI 延展过去"）。这次能成，
     * 是因为多了 10-06 没有的工具：**安全区声明**（那时 `safeArea` 恒为 0、没有任何
     * 输入入口）——顶格 **+** 声明 `safeArea.top = 状态栏实测高度`
     * ⇒ iPhone 把交互 UI 排到那条栏之下，只有壁纸铺满。
     *
     * ## 本次契约（三条都要成立，缺哪条都会退回一个已知的坏状态）
     *
     * ① **沉浸开关必须参与判定**（`settings.immersiveVideo`）。
     *    缺 ⇒ 顶格没有出口，功能等于没做。
     * ② **顶格只能对画面页生效**（`current == CarDestination.CarPlay`）。
     *    缺 ⇒ 设置页/主页也顶格，顶栏按钮（含右上角设置入口）被车机状态栏压住点不到
     *    ——这正是 10-06 定案 A 要保护的东西，本次没有推翻它。
     * ③ **原有避让逻辑与 IME 无条件避让都还在**。
     *    缺 ⇒ 为了顶格把避让整个删掉，会连"用户明确要求隐藏的栏"也不避让。
     *
     * ⚠️ 2026-10-09 起出厂默认改为「顶格」（`immersiveVideo = true`，见
     * `CarSessionSettings.immersiveVideo`）——本测试钉的仍旧是"写法"，不是默认值。
     */
    @Test
    fun `画面页顶格必须由沉浸设置驱动且只对画面页生效`() {
        val src = sourceOf(appSrc)

        assertTrue(
            "`barsToIgnore` 的判定里必须出现 `settings.hideTopBar` —— " +
                "隐藏顶部栏关掉时，画面页要铺进状态栏。",
            src.contains("settings.hideTopBar") && src.contains("extendUnderStatusBar"),
        )
        assertTrue(
            "沉浸顶格必须与 `current == CarDestination.CarPlay` 绑定 —— " +
                "否则设置页/主页也会顶格，顶栏按钮会被车机状态栏压住点不到。",
            src.contains("current == CarDestination.CarPlay"),
        )
        assertTrue(
            "必须仍用 `exclude(barsToIgnore)` 从 safeDrawing 里摘掉要隐藏的栏。",
            src.contains(".exclude(barsToIgnore)"),
        )
        assertTrue(
            "IME（输入法）必须无条件并回来：设置页有文本框，避让丢了键盘会盖住输入框。",
            src.contains(".union(WindowInsets.ime)"),
        )
        assertFalse(
            "旧写法 `windowInsetsPadding(WindowInsets.safeDrawing)` 已回归——" +
                "无条件避让 = 车机拒绝隐藏时永远留一条顶栏。",
            Regex("""windowInsetsPadding\(\s*WindowInsets\.safeDrawing\s*\)""").containsMatchIn(src),
        )
    }

    /**
     * `MainActivity` 必须有旧 API 兜底。
     *
     * `insets.hide(...)` 是建议，车机可以拒绝；`SYSTEM_UI_FLAG_*` 裸标志位是
     * 绕开「Compat 包装调用不被认」的最后一道。
     */
    @Test
    fun `系统栏隐藏必须有旧 API 兜底`() {
        val src = sourceOf(activitySrc)

        assertTrue(
            "缺少 `setSystemUiVisibility` 兜底——车机拒绝 Compat 请求时就没有第二手。",
            src.contains("systemUiVisibility"),
        )
        assertTrue(
            "兜底必须带 `SYSTEM_UI_FLAG_FULLSCREEN`（隐藏状态栏的裸标志位）。",
            src.contains("SYSTEM_UI_FLAG_FULLSCREEN"),
        )
        assertTrue(
            "兜底必须带 `SYSTEM_UI_FLAG_IMMERSIVE_STICKY`，否则用户从边缘划出后不会自动收起。",
            src.contains("SYSTEM_UI_FLAG_IMMERSIVE_STICKY"),
        )
        assertTrue(
            "兜底必须带 `SYSTEM_UI_FLAG_HIDE_NAVIGATION`（隐藏导航栏）。",
            src.contains("SYSTEM_UI_FLAG_HIDE_NAVIGATION"),
        )
    }

}
