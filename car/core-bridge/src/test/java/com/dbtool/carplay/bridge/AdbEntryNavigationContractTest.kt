package com.dbtool.carplay.bridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「去开 ADB 调试」按钮落点的回归契约（2026-10-06 车主定案）。
 *
 * ## 车主原话
 *
 * **"去打开 adb 调试应该跳转到设置-连接-无线-立即建立热点+adb"**。
 *
 * ## 为什么必须改（判据，不只是照做）
 *
 * 旧实现调 `CarDeveloperSettingsLauncher.open(app)`，把用户送到**车机系统**的
 * 「开发者选项」。那是错的：
 *
 * 1. **能力在我们手里**：车机 ADB 由 `DeviceAdbEnabler` 直写系统属性开通
 *    （`service.adb.tcp.port` / `persist.adb.tcp.port` + `ctl.stop/start adbd`，
 *    见 MEMORY 第 13/28 条）。送用户去系统设置里翻那个开关 = 放着现成能力不用；
 * 2. **用户要做的动作只有一个**：点一下「立即建立热点 + ADB」。
 *    所以正确落点是**我们自己的**那个按钮，且要**直接定位**到它所在的
 *    「连接 → 热点服务」分组，而不是丢在设置索引页让用户自己找。
 *
 * ## 判据（静态读源码）
 *
 * ① 控制器侧：必须发 `CarNavigationTarget.SETTINGS_HOTSPOT`，不许再开系统 Intent；
 * ② UI 侧：必须把这个 target 映射到 `CarDestination.Settings(CarSettingsSection.CONNECTION)`；
 * ③ 契约里必须有「消费即清空」的通道 —— 否则用户手动退出设置页会被立刻弹回去。
 */
class AdbEntryNavigationContractTest {

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
     * 本次尤其危险：说明注释里**必然**写满了 `CarDeveloperSettingsLauncher`
     * 和"开发者选项"（解释"为什么不再这么做"），不剥离会匹配到注释 ⇒ 假通过。
     */
    private fun codeOf(relative: String): String =
        sourceOf(relative)
            .replace(Regex("""/\*[\s\S]*?\*/"""), "")
            .replace(Regex("""//[^\n]*"""), "")

    // 单测 cwd = car/core-bridge（模块目录）。仓库根在两级之上。
    private val controllerSrc =
        "car/app/src/main/java/com/dbtool/carplay/app/CarAppController.kt"

    private val appNavSrc =
        "car/ui/src/main/java/com/dbtool/carplay/ui/navigation/CarPlayApp.kt"

    private val contractSrc =
        "car/ui/src/main/java/com/dbtool/carplay/ui/CarUiContract.kt"

    private val requestSrc =
        "car/api/src/main/java/com/dbtool/carplay/api/navigation/CarNavigationRequest.kt"

    /**
     * ① 控制器必须**只发导航请求**，不再打开车机系统的开发者选项。
     *
     * 这是本次修改的核心：`openDeveloperSettings` 的实现体里
     * 不能再有 `CarDeveloperSettingsLauncher`。
     */
    @Test
    fun `openDeveloperSettings 不得再打开车机系统设置`() {
        val src = codeOf(controllerSrc)
        val body = src.substringAfter("override fun openDeveloperSettings() {", "")
        assertTrue("找不到 openDeveloperSettings 的实现体 —— 它被改名或删掉了。", body.isNotEmpty())
        val impl = body.substringBefore("\n    }")

        assertFalse(
            "`openDeveloperSettings` 又去调 `CarDeveloperSettingsLauncher` 了 —— " +
                "车主要求跳到**本应用**的「设置 → 连接 → 无线 → 立即建立热点 + ADB」。" +
                "车机 ADB 本来就由 `DeviceAdbEnabler` 自己开通，不该再把用户送去系统设置里找。",
            impl.contains("CarDeveloperSettingsLauncher"),
        )
        assertTrue(
            "必须发出 `SETTINGS_HOTSPOT` 导航请求。",
            impl.contains("CarNavigationTarget.SETTINGS_HOTSPOT"),
        )
    }

    /**
     * ② UI 必须把 `SETTINGS_HOTSPOT` 落到**连接分区**。
     *
     * 只跳到 `Settings()`（索引页）是不够的：车主说的是"设置-连接-无线-立即建立热点+adb"，
     * 用户不该再自己找一层。
     */
    @Test
    fun `SETTINGS_HOTSPOT 必须落到连接分区`() {
        val src = codeOf(appNavSrc)
        val block = src.substringAfter("CarNavigationTarget.SETTINGS_HOTSPOT ->", "")
        assertTrue("`CarPlayApp` 里没有处理 SETTINGS_HOTSPOT —— 请求发出去没人接。", block.isNotEmpty())
        val target = block.substringBefore("\n        }")

        assertTrue(
            "SETTINGS_HOTSPOT 必须映射到 `CarDestination.Settings(...)`。",
            target.contains("CarDestination.Settings("),
        )
        assertTrue(
            "必须带 `CarSettingsSection.CONNECTION` 焦点 —— 只进设置索引页等于让用户再找一层，" +
                "而车主明确要的是直达「连接 → 热点服务 → 立即建立热点 + ADB」。",
            target.contains("CarSettingsSection.CONNECTION"),
        )
    }

    /**
     * ③ 契约里必须有「消费即清空」的通道。
     *
     * 没有它的话，导航请求在 `CarUiModel` 里**常驻**：用户手动从设置页按返回，
     * 重组时 `LaunchedEffect` 的条件仍成立 ⇒ 立刻又被弹回去，用户被锁死。
     */
    @Test
    fun `导航请求必须是一次性的且能被清空`() {
        val contract = codeOf(contractSrc)
        assertTrue(
            "`CarUiActions` 里缺少 `consumeNavigationRequest()` —— " +
                "没有清空入口，导航请求会常驻，用户手动返回会被立刻弹回去。",
            contract.contains("fun consumeNavigationRequest()"),
        )
        assertTrue(
            "`CarUiModel` 里缺少 `navigationRequest` 字段 —— 控制器无法把请求交给界面。",
            contract.contains("val navigationRequest"),
        )

        val controller = codeOf(controllerSrc)
        assertTrue(
            "控制器必须实现 `consumeNavigationRequest()`。",
            controller.contains("override fun consumeNavigationRequest()"),
        )

        val nav = codeOf(appNavSrc)
        assertTrue(
            "`CarPlayApp` 消费完必须回调清空（`actions.consumeNavigationRequest()`），" +
                "否则同一个请求会在每次重组时重复生效。",
            nav.contains("actions.consumeNavigationRequest()"),
        )
    }

    /**
     * ④ 请求类型必须带自增 key。
     *
     * `LaunchedEffect(key)` 靠 key 变化重跑；key 不变时**连点两次只跳一次**
     * （第一次跳进设置页、用户返回主页、再点按钮 → 看着没反应）。
     *
     * key 的来源必须是**调用方传入**：若在 `CarNavigationRequest` 内部自增，
     * 每次构造都拿到同一个初值，等于没有 key。
     */
    @Test
    fun `导航请求的 key 必须由调用方自增`() {
        val src = codeOf(requestSrc)
        assertTrue("请求类型缺少 `key` 字段。", src.contains("val key: Long"))
        assertTrue(
            "必须提供 `fromTarget(target, key)` 工厂，且 key 由**调用方**传入。" +
                "若在本对象内部自增，每个请求都会拿到同一个初值 ⇒ 连点两次只跳一次。",
            src.contains("fun fromTarget("),
        )
        assertFalse(
            "`fromTarget` 的参数里不能有 key 的默认值（那样调用方会漏传、序号就退化成常量）。",
            Regex("""fun fromTarget\([^)]*key\s*:\s*Long\s*=""").containsMatchIn(src),
        )

        val controller = codeOf(controllerSrc)
        assertTrue(
            "控制器必须持有自增序号（`navigationRequestSeq`）。",
            controller.contains("navigationRequestSeq"),
        )
        assertTrue(
            "发请求时必须把序号自增后传进去（`++navigationRequestSeq`）。",
            controller.contains("++navigationRequestSeq"),
        )
    }
}
