package com.dbtool.carplay.bridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「顶栏内容必须有自己的一份**垂直留白**」的回归契约（2026-10-07 车主反馈）。
 *
 * ## 车主原话
 *
 * > 左滑进蓝牙设备页，设置按钮被顶栏遮挡
 * > 顶栏是 72px，你把设置按钮再往下移，应该能解决
 *
 * ## 先厘清一件事：车主说的"顶栏"是**车机的状态栏**
 *
 * 那 72px 是**车机 SystemUI 的独立窗口**（实机日志
 * `StatusBarWindowView{... 0,0-1920,72}`），不在我们进程里，
 * `insets.hide(statusBars())` 也被 ROM 拒绝 ⇒ 抢不掉。
 * 本应用走的是**避让**路线（车主 2026-10-06 定案 A，见
 * [FullscreenInsetContractTest]）：根节点 `CarPlayApp` 的 `windowInsetsPadding`
 * 已经把内容整体推到那条栏之下，画面协商 1920x1008。
 *
 * ⇒ **躲状态栏不是 `CarTopBar` 的职责**。谁要是想在这里给某个页面加
 * "避开状态栏"的分支，方向就错了，`FullscreenInsetContractTest` 也会拦。
 *
 * （2026-10-08 补充：车主给**画面页**开了一个 opt-in 的"沉浸"口子，
 * 见 `CarSessionSettings.immersiveVideo` —— 那仍然发生在 `CarPlayApp` 根节点，
 * 与 `CarTopBar` 无关，本文件的断言不受影响。出厂默认依旧是避让。）
 *
 * ## 那这次改的是什么
 *
 * 改的是**顶栏内容自己的垂直留白**：原来 `top` 和 `bottom` 都是 `spaceM`（12dp），
 * 内容贴着栏的下沿，读起来像"被压住了"。现在顶部换成
 * `CarDimensions.topBarInsetTop`，给顶栏一条自己的呼吸。
 *
 * ## 判据（静态读源码）
 *
 * 本项目 UI 层的既有契约测试（`FullscreenInsetContractTest` /
 * `CarPlayScreenExitContractTest` 等）都是**静态读源码、断言字面量**，
 * 因为它们要钉的是"有没有写对那一行"，而不是"像素画在哪"。
 * 本次同样如此：
 *
 * ① `CarTopBar` 的顶部内边距必须用 `topBarInsetTop`，不能再是 `spaceM`；
 * ② 该 token 必须真的定义在 `CarDimensions` 里、并随 `scale` 缩放；
 * ③ `CarTopBar` 里**不得**出现任何"按页面/按状态栏避让"的分支。
 *
 * ⚠️ 当时考虑过用 Robolectric + Compose ui-test **真的 measure 一次**、
 * 直接断言按钮坐标。放弃了：那要新引入 robolectric / androidx.test /
 * compose-ui-test 三组依赖，还要下载约 30MB 的 `android-all-instrumented` 镜像，
 * 而本机的 Robolectric 镜像抓取（`MavenArtifactFetcher`）在代理下稳定失败。
 * 为了一个 4px 的留白改动铺这套基建，**动作的分量远超改动的分量**。
 * 若将来真的要测布局坐标，记得先把镜像预置进 `~/.m2` 再引入依赖。
 */
class TopBarClearanceContractTest {

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
     * 本次尤其危险：说明注释里**必然**会写满 `spaceM`（解释"为什么不再用它"），
     * 不剥离就会匹配到注释 ⇒ 假通过。
     */
    private fun codeOf(relative: String): String =
        sourceOf(relative)
            .replace(Regex("""/\*[\s\S]*?\*/"""), "")
            .replace(Regex("""//[^\n]*"""), "")

    // 单测 cwd = car/core-bridge（模块目录）。仓库根在两级之上。
    private val scaffoldSrc =
        "car/ui/src/main/java/com/dbtool/carplay/ui/components/CarScaffold.kt"

    private val dimensSrc =
        "car/ui/src/main/java/com/dbtool/carplay/ui/theme/CarDimensions.kt"

    /**
     * ① `CarTopBar` 的顶部内边距必须是 `topBarInsetTop`，不能再和底部一样是 `spaceM`。
     *
     * 这是本次改动的核心。取 `CarTopBar` 里那一段 `padding(...)` 看两个值：
     * `top` 必须是新 token，`bottom` 保持 `spaceM`（**只动顶部**——
     * 底部那 12dp 同时承担"顶栏和内容区之间的分界"，不该一起变）。
     */
    @Test
    fun `顶栏顶部内边距必须用 topBarInsetTop`() {
        val src = codeOf(scaffoldSrc)
        val body = src.substringAfter("fun CarTopBar(", "")
        assertTrue("找不到 CarTopBar 的实现体——它被改名或删掉了。", body.isNotEmpty())

        // 从参数表之后取到下一个顶层函数（约等于整个函数体），再切出那段 padding。
        val padding = body.substringAfter(".padding(", "")
        assertTrue(
            "CarTopBar 里找不到 `.padding(` —— 顶栏的内边距被挪走或删掉了。" +
                "车主 2026-10-07 要求把设置按钮往下移，这条内边距正是落点。",
            padding.isNotEmpty(),
        )
        val paddingArgs = padding.substringBefore(")")

        assertTrue(
            "CarTopBar 的顶部内边距不是 `dimens.topBarInsetTop` —— " +
                "它要么被改回了 `spaceM`（内容贴住车机状态栏下沿，就是车主报的那个现象），" +
                "要么被写成了裸 dp 常量（那会丢掉随屏幕缩放的能力）。",
            paddingArgs.contains("top = dimens.topBarInsetTop"),
        )
        assertTrue(
            "CarTopBar 的底部内边距不再是 `dimens.spaceM` —— 本次只该动顶部。" +
                "底部那 12dp 还负责顶栏与内容区之间的分界，不该跟着变。",
            paddingArgs.contains("bottom = dimens.spaceM"),
        )
    }

    /**
     * ② token 必须真的定义在 `CarDimensions` 里，并且**随缩放变化**。
     *
     * 判据：token 的赋值必须走 `of(scale)` 里的那个局部 `d(...)` 换算。
     * 谁把它写成 `15.dp` 常量，在 1024x600（scale 0.9）的小屏车机上留白就不对了，
     * 这条会以"赋值不在 `d(` 里"失败。
     */
    @Test
    fun `topBarInsetTop 必须定义在 CarDimensions 并随缩放`() {
        val src = codeOf(dimensSrc)

        assertTrue(
            "`topBarInsetTop` 没有定义在 `CarDimensions` 里 —— " +
                "UI 层的尺寸一律只在该文件定义，界面里不许出现裸 dp。",
            src.contains("val topBarInsetTop: Dp"),
        )

        val assignment = src.substringAfter("topBarInsetTop =", "")
        assertTrue("找不到 `topBarInsetTop` 的赋值。", assignment.isNotEmpty())
        val value = assignment.substringBefore("\n").trim()

        assertTrue(
            "`topBarInsetTop` 的赋值 `$value` 没有走 `d(...)` 缩放换算 —— " +
                "写死 dp 会让小屏车机（1024x600，scale≈0.9）上的留白过大。" +
                "参照同文件里 `topBarHeight = d(64f)` 的写法。",
            value.startsWith("d("),
        )
    }

    /**
     * ③ `CarTopBar` 里**不得**出现任何"按页面 / 按状态栏"的避让分支。
     *
     * 这是与 [FullscreenInsetContractTest] 配套的防线：那条测试钉的是
     * `CarPlayApp` 根节点不许特殊化，这条钉的是顶栏**自己**也不许。
     * 两处都不许，才不会出现"设定里关了隐藏顶栏、但顶栏又自己避开一次"的双重偏移。
     */
    @Test
    fun `CarTopBar 不得自己按状态栏避让`() {
        val src = codeOf(scaffoldSrc)
        val body = src.substringAfter("fun CarTopBar(", "").substringBefore("\n@Composable")

        listOf(
            "windowInsetsPadding" to "顶栏自己避让 inset",
            "WindowInsets" to "顶栏直接碰 WindowInsets",
            "statusBars" to "顶栏直接判断状态栏",
            "hideTopBar" to "顶栏按设置分支",
        ).forEach { (needle, what) ->
            assertFalse(
                "`CarTopBar` 里出现了 `$needle`（$what）—— " +
                    "避让系统栏是 `CarPlayApp` 根节点 `windowInsetsPadding` 的职责" +
                    "（车主 2026-10-06 定案 A）。顶栏再避一次会变成双重偏移，" +
                    "画面页也会跟着缩水。",
                body.contains(needle),
            )
        }
    }
}
