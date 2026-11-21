package com.dbtool.carplay.bridge

import com.dbtool.carplay.api.display.CarClusterSafeAreaInsets
import com.dbtool.carplay.api.display.CarClusterSafeAreaPolicy
import com.dbtool.carplay.api.display.CarClusterSpec
import com.dbtool.carplay.api.display.CarDisplay
import com.dbtool.carplay.api.settings.CarSessionSettings
import com.dbtool.carplay.api.settings.CarSettingsSafeArea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 仪表副屏「核心安全区」→ 报给 iPhone 的声明（2026-10-10）。
 *
 * ## 为什么必须钉住
 *
 * 副屏安全区是**唯一**能告诉 iPhone「这块面板的下半部是车机自己的仪表，
 * 别把导航画上去」的手段。少了这条声明，iPhone 认为整块面板都可用 ⇒ 导航铺满
 * ⇒ 压到油量/车速表（原版把这种现象叫「画面绘制到安全区外」）。
 *
 * 而这条声明**错了看不出来**：画面正常、协议正常、日志正常，只是位置不对。
 * 所以把翻译层的结果钉在测试里，而不是靠读源码。
 *
 * ## 链路
 *
 * 值来自「设置 → 仪表导航 → 核心安全区」（`CarSessionSettings.safeArea`），
 * 经 `CarClusterSafeAreaPolicy.resolve`（**"没画过框"的默认值就在那里**）、
 * 由 `CarAppController.resolveClusterSpec` 灌进 `CarClusterSpec.safeArea`
 * （此前这一项没带，见那里的注释），最后由 `CarPlayConfigBuilder.toAirPlayDisplay`
 * 翻译成上游 `AirPlayInsets`。
 *
 * 本文件钉三段：**翻译层**（spec 给什么就报什么，不给就一个字都不报）、
 * **默认策略有没有真的接上**（静态读源码——防止哪天把策略调用删了而测试全绿）、
 * 以及**两段串起来之后 iPhone 到底会收到什么**。
 *
 * `null` 与全 0 在 `AirPlayInfoPlist.areaDict` 里等价（都按整屏算），但语义必须
 * 分开——`null` =「这项不存在」，全 0 =「声明了一块和面板一样大的区域」。
 *
 * ## 本工程的副屏默认值（PATCH-0093，2026-10-11 车主口径）
 *
 * 用户没画过框时，副屏**不再"什么都不声明"**，而是回落到 `CarClusterSafeAreaPolicy`
 * 的默认核心区 **1920×720** ⇒ 1920×1080 面板上 insets `(top=0, bottom=360, left=0, right=0)`。
 * 车主原话：「仪表核心安全区改成 1920×720，现在的太大了」。
 * **只影响副屏**——主屏仍走 `CarPlayConfigBuilder` 的既有回退（沉浸避让顶部 72px）。
 *
 * ## 🔴 PATCH-0094（2026-10-11）：这个矩形**彻底**与主屏解耦
 *
 * 车主口径「仪表导航 的安全区只限副屏」。用户画过的框也不再喂给主屏
 * （此前它优先于沉浸避让 ⇒ 画"上方 720px"会把主屏 UI 一起挤到上方一条）。
 * 本文件有两条用例钉它：行为上的 `主屏不吃核心安全区那个矩形`，
 * 与源码上的 `翻译层不许再把核心安全区接到主屏`。
 *
 * ## 原版出厂默认（仅供人工对照，本工程没有照抄）
 *
 * 原版 `GeelyClusterSafeArea.DEFAULT` 是画布 1920×1080 上的核心区 `(x=0, y=0, w=1865, h=660)`
 * ⇒ insets `(top=0, bottom=420, left=0, right=55)`。
 * 没照抄的两个理由：`1865` 会让副屏左右各留一条谁也用不上的边（仪表没有左右饰板）；
 * `660` 比车主口径矮 60px。要按原版行为跑，就在设置页照着填这四个数。
 */
class ClusterSafeAreaContractTest {

    /** 中控主屏（1920×720，本车机的 CarPlay 主界面画布）。 */
    private val mainDisplay = CarDisplay(
        id = 0,
        widthPixels = 1920,
        heightPixels = 720,
        densityDpi = 160,
    )

    /** 仪表 HDMI2 面板（1920×1080，导航只占上半部，下半部留给车机仪表）。 */
    private val clusterPanel = CarClusterSpec(
        displayId = 3,
        widthPixels = 1920,
        heightPixels = 1080,
        densityDpi = 160,
    )

    private fun config(
        cluster: CarClusterSpec = clusterPanel,
        settings: CarSessionSettings = CarSessionSettings.default(),
        /**
         * 主屏安全区的**唯一**来源（PATCH-0094 起）：沉浸模式下实测的状态栏高度。
         * 默认 0 = 量不到 / 沉浸关 ⇒ 主屏不声明安全区。
         */
        immersiveSafeAreaTopPx: Int = 0,
    ) = CarPlayConfigBuilder.airPlayConfig(
        settings = settings,
        display = mainDisplay,
        widthPixels = 1920,
        heightPixels = 720,
        cluster = cluster,
        immersiveSafeAreaTopPx = immersiveSafeAreaTopPx,
    )

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

    /**
     * 去掉块注释与行注释再返回。
     *
     * **凡是"断言源码里有没有某个字面量"的用例都必须走这个**（项目纪律 §29）——
     * 说明性注释里**必然**会出现被断言的字面量（尤其"为什么不用 X"这种写法），
     * 不剥离就会匹配到注释 ⇒ 假通过。
     */
    private fun codeOf(relative: String): String =
        sourceOf(relative)
            .replace(Regex("""/\*[\s\S]*?\*/"""), "")
            .replace(Regex("""//[^\n]*"""), "")

    /**
     * 翻译层**不替 spec 补值**：`spec.safeArea` 是 `null` 就一个字都不报。
     *
     * ⚠️ 这条**不是**"用户没设过安全区时的结果"——那个场景由
     * `CarAppController` 先过一遍 `CarClusterSafeAreaPolicy`，结果见下面
     * `未设安全区时副屏默认声明 1920x720`。这里钉的是分工：
     * 默认值属于**策略层**，翻译层只做翻译，这样"默认值改了"不会连带改到
     * 别处（例如将来有平台自己带 `safeArea` 进来时不会被覆盖）。
     */
    @Test
    fun `翻译层不替 spec 补安全区`() {
        assertNull(config().cluster?.safeArea)
    }

    /**
     * 用户没画过框 ⇒ 副屏**默认声明 1920×720**（PATCH-0093）。
     *
     * 这是策略层与翻译层**串起来**之后 iPhone 真正收到的东西，
     * 也是本次改动要看的最终结果：`bottom = 360`（= 1080 − 720）把下方
     * 360px 的油量/车速表让出来。
     *
     * ⚠️ 只断 `!= null` 抓不住坏状态：铺满整屏是 `bottom = 0`、上面空一条是 `top > 0`。
     */
    @Test
    fun `未设安全区时副屏默认声明 1920x720`() {
        val cluster = clusterPanel.copy(
            safeArea = CarClusterSafeAreaPolicy.resolve(
                drawn = CarSettingsSafeArea(),
                mainCanvasWidthPixels = 1920,
                mainCanvasHeightPixels = 720,
                panelWidthPixels = clusterPanel.widthPixels,
                panelHeightPixels = clusterPanel.heightPixels,
            ),
        )
        val safe = config(cluster = cluster).cluster?.safeArea
        assertEquals(0, safe?.top)
        assertEquals(360, safe?.bottom)
        assertEquals(0, safe?.left)
        assertEquals(0, safe?.right)
        // 可用高度 = 720（不是整块面板的 1080）——这正是车主说的"现在太大了"。
        assertEquals(720, safe?.let { 1080 - it.top - it.bottom })
    }

    /**
     * 默认策略必须**真的接在** `CarAppController.resolveClusterSpec()` 上。
     *
     * 为什么静态读源码：`resolveClusterSpec` 在 `:car:app`（Android 应用模块，
     * 本工程没有它的 test source set），而"策略写好了但没接上"是本项目反复出现过的
     * 失败形态（副屏 `safeArea` 曾经整整一项没往 spec 里带，见那里的注释）。
     * 那种情况下所有单测都是绿的，只有真车上看得出。
     *
     * ⚠️ 按项目纪律 §29 先剥注释再断言——上面那段解释文字里就写着
     * `CarClusterSafeAreaPolicy`，不剥离会匹配到注释。
     */
    @Test
    fun `副屏默认策略必须接在 resolveClusterSpec 上`() {
        val src = codeOf("car/app/src/main/java/com/dbtool/carplay/app/CarAppController.kt")
        assertTrue(
            "`resolveClusterSpec` 里必须调用 `CarClusterSafeAreaPolicy.resolve(` —— " +
                "不调用的话副屏又回到\"什么都不声明\"，导航会铺满 1920×1080 面板压到仪表。",
            src.contains("CarClusterSafeAreaPolicy.resolve("),
        )
        assertTrue(
            "策略必须吃到用户画的核心区（`drawn = settings.safeArea`），" +
                "否则用户画的框对副屏完全失效。",
            src.contains("drawn = settings.safeArea"),
        )
        assertTrue(
            "策略必须拿到**副屏面板**尺寸（`panelWidthPixels` / `panelHeightPixels`）—— " +
                "默认区是按面板算的，给错尺寸会算出负数。",
            src.contains("panelWidthPixels = spec.widthPixels") &&
                src.contains("panelHeightPixels = streamHeight"),
        )
    }

    /** 设了就原样报四边——不合并、不换算、不回退成主屏的值。 */
    @Test
    fun `设了安全区就按四边原样报给 iPhone`() {
        val config = config(
            cluster = clusterPanel.copy(
                safeArea = CarClusterSafeAreaInsets(top = 0, bottom = 420, left = 0, right = 55),
            ),
        )
        assertEquals(0, config.cluster?.safeArea?.top)
        assertEquals(420, config.cluster?.safeArea?.bottom)
        assertEquals(0, config.cluster?.safeArea?.left)
        assertEquals(55, config.cluster?.safeArea?.right)
    }

    /**
     * 两块屏各报各的：主屏按沉浸模式避让顶部 72px，副屏避让下方 420px。
     *
     * ⚠️ PATCH-0094 起主屏的值**只能**来自 `immersiveSafeAreaTopPx`（沉浸实测值），
     * 所以这里显式传 72；同一份 `settings` 里那个矩形**只**喂副屏。
     * 串味的后果很具体：主屏安全区被套到副屏上 ⇒ 1920×1080 的面板被砍成
     * 720 高（导航只占上三分之一，其余全空）；反过来副屏的 420 被套到主屏上 ⇒
     * 中控底下空出一条谁也用不上的黑边。
     */
    @Test
    fun `副屏安全区与主屏安全区互不串味`() {
        val config = config(
            cluster = clusterPanel.copy(
                safeArea = CarClusterSafeAreaInsets(top = 0, bottom = 420, left = 0, right = 55),
            ),
            settings = CarSessionSettings.default().copy(
                immersiveVideo = true,
                // ⚠️ 这一份是「核心安全区」——PATCH-0094 起**只**喂副屏。
                safeArea = CarSettingsSafeArea(x = 0, y = 40, width = 1920, height = 1080 - 40),
            ),
            immersiveSafeAreaTopPx = 72,
        )
        assertEquals(72, config.main.safeArea?.top)
        assertEquals(0, config.main.safeArea?.bottom)
        assertEquals(0, config.cluster?.safeArea?.top)
        assertEquals(420, config.cluster?.safeArea?.bottom)
    }

    /**
     * 🔴 主屏**不吃**「核心安全区」那个矩形（PATCH-0094；车主口径
     * 「仪表导航 的安全区只限副屏」）。
     *
     * 判别性在于"矩形里那组数一个都不能出现"：改之前主屏会拿到 `top = 40`、
     * `bottom = 1080 − 40 − 720 = 320`。只要这里仍是 72/0/0/0，就说明主屏这条链
     * 确实没读它 —— 而 72 只可能来自显式传入的 `immersiveSafeAreaTopPx`。
     */
    @Test
    fun `主屏不吃核心安全区那个矩形`() {
        val config = config(
            settings = CarSessionSettings.default().copy(
                immersiveVideo = true,
                safeArea = CarSettingsSafeArea(x = 0, y = 40, width = 1920, height = 720),
            ),
            immersiveSafeAreaTopPx = 72,
        )
        assertEquals(72, config.main.safeArea?.top)
        assertEquals(0, config.main.safeArea?.bottom)
        assertEquals(0, config.main.safeArea?.left)
        assertEquals(0, config.main.safeArea?.right)
    }

    /**
     * 静态读源码：`CarPlayConfigBuilder` 里**不许**再出现 `settings.safeArea`。
     *
     * 为什么行为测试不够：上面那条只证明"这一组输入下主屏没吃到"。矩形恰好等于
     * 整屏、或 immersive 关掉时，把 `settings.safeArea` 接回主屏**不会**让行为测试变红
     * （真车 1.0.44 那次"画框悄悄顶掉 72px 避让"就是这么漏过去的）。
     * 所以按项目纪律 §29 剥注释后直接钉字面量。
     *
     * ⚠️ 负向断言用正则而不是 `contains("settings.safeArea")`：文件里**合法保留**着
     * `settings.safeAreaDrawOutside`（那是另一个开关，主屏副屏都吃），
     * 用 `contains` 会把它误判成违规。
     */
    @Test
    fun `翻译层不许再把核心安全区接到主屏`() {
        val src = codeOf("car/core-bridge/src/main/java/com/dbtool/carplay/bridge/CarPlayConfigBuilder.kt")
        val hit = Regex("""settings\.safeArea(?!DrawOutside)""").find(src)
        assertNull(
            "`CarPlayConfigBuilder` 里不该再引用 `settings.safeArea`（只允许 `settings.safeAreaDrawOutside`）" +
                "—— 那个矩形只作用于仪表副屏（PATCH-0094），主屏只剩沉浸避让一个来源。" +
                "命中的位置：${hit?.range?.let { src.substring(maxOf(0, it.first - 80), minOf(src.length, it.last + 80)) }}",
            hit,
        )
        // 反向确认找的是对的文件、剥注释没剥过头。
        assertTrue(
            "剥注释后必须仍能看到 `immersiveSafeAreaTopPx`（否则是剥过头或找错文件）。",
            src.contains("immersiveSafeAreaTopPx"),
        )
    }

    /**
     * 声明出来的可用高度按**各自画布**算：副屏 1080-0-420 = 660，
     * 正是原版核心区的高度。
     *
     * 这条盯的是"别拿画布高度当 inset"——副屏画布 1080、主屏画布 720，
     * 谁的高度算错了都还是一份"看着正常"的声明。
     */
    @Test
    fun `可用高度按各自画布算`() {
        val config = config(
            cluster = clusterPanel.copy(
                safeArea = CarClusterSafeAreaInsets(top = 0, bottom = 420, left = 0, right = 55),
            ),
        )
        val clusterSafe = config.cluster?.safeArea
        assertEquals(660, clusterSafe?.let { 1080 - it.top - it.bottom })
        assertEquals(1865, clusterSafe?.let { 1920 - it.left - it.right })
    }

    /** `drawUIOutsideSafeArea` 与安全区是**两个**独立的声明，一起透传。 */
    @Test
    fun `drawUIOutsideSafeArea 与安全区一起透传`() {
        val config = config(
            cluster = clusterPanel.copy(
                safeArea = CarClusterSafeAreaInsets(bottom = 420),
                safeAreaDrawOutside = false,
            ),
        )
        assertEquals(false, config.cluster?.safeAreaDrawOutside)
    }

    /**
     * 仪表**内容 URL** 必须出现在声明里（2026-10-11，车主「屏对但没内容」）。
     *
     * `AirPlayInfoPlist.displayEntry` 只在 `initialUrl != null` 时才写 `initialURL`；
     * 不写时 iPhone 照样建流、照样推帧 —— 推的是**黑帧**。所以这条断言盯的不是
     * "某个字段有没有值"，而是"iPhone 会不会有东西可画"。
     */
    @Test
    fun `副屏声明必须带 initialURL（否则 iPhone 只推黑帧）`() {
        val cluster = config().cluster
        assertEquals(
            "仪表默认内容 = 地图（与 /info 的 altScreenURLs 首项一致）",
            com.shilapi.xcertplay.airplay.CarPlayClusterDisplay.Content.MAP.url,
            cluster?.initialUrl,
        )
    }
}
