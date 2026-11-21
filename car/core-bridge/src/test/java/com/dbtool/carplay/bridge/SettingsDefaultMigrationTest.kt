package com.dbtool.carplay.bridge

import com.dbtool.carplay.api.settings.CarHotspotMode
import com.dbtool.carplay.api.settings.CarSessionSettings
import com.dbtool.carplay.api.settings.CarTransportMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 设置默认值迁移契约（2026-10-06）。
 *
 * ## 背景
 *
 * 用户要求「**默认**这个配置，而不是**建议**别人这个配置」——即
 * 老安装也必须变成 `无线 CarPlay` + `参考物理长度 350mm`，不能只改
 * `CarSessionSettings` 的默认值（那对已持久化的老安装无效）。
 *
 * 于是新增两条一次性迁移：
 * - [migrateTransportToWireless]：`WIRED` → `WIRELESS`
 * - [migrateWidthPhysicalMmTo350]：`200` → `350`
 *
 * ## 为什么是源码级断言
 *
 * [SharedPreferencesCarSettingsStore] 构造需要 `android.content.Context`
 * 与真实 `SharedPreferences`，纯 JVM 测试跑不起来。
 * 所以这里测两件**能在 JVM 里确证**的事：
 *
 * 1. **默认值常量本身**（纯 Kotlin，可直接断言）；
 * 2. **迁移的写法契约**（源码级）——标记独立、判据只认旧出厂值。
 *
 * 真机上的"装上去到底变没变"仍要靠装车验证，源码断言只能钉住"写法没跑偏"。
 */
class SettingsDefaultMigrationTest {

    // ── 1. 默认值常量：出厂就应该是我要的那两个值 ──────────────────────────

    /**
     * 出厂默认必须是**无线**。
     *
     * 本工程的目标场景就是无线 CarPlay（自建热点 + 手机连上直接投屏），
     * 有线只是备选。装完打开就能连，才不需要用户先发现"要去设置里改连接方式"。
     */
    @Test
    fun `出厂默认连接方式是无线`() {
        assertEquals(CarTransportMode.WIRELESS, CarSessionSettings.default().transport)
    }

    /** 出厂默认参考物理长度必须是 350mm（横屏车机最宽边的更接近真值的一档）。 */
    @Test
    fun `出厂默认参考物理长度是 350mm`() {
        assertEquals(350, CarSessionSettings.DEFAULT_WIDTH_PHYSICAL_MM)
        assertEquals(350, CarSessionSettings.default().widthPhysicalMm)
    }

    // ── 2. 迁移写法契约（源码级） ──────────────────────────────────────────

    /**
     * 两条迁移都必须在 `load()` 里被调用。
     *
     * 少了任一条，老安装的持久化值就永远不会被改写 —— 这正是本需求的全部意义。
     */
    @Test
    fun `两条迁移都在 load 里注册`() {
        val source = codeOf()
        val loadIndex = source.indexOf("override fun load()")
        assertTrue("找不到 load()", loadIndex >= 0)
        // load() 的函数体到下一个 `return CarSessionSettings(` 为止。
        val loadBody = source.substring(loadIndex).substringBefore("return CarSessionSettings(")
        assertTrue(
            "load() 里必须调用 migrateTransportToWireless",
            loadBody.contains("migrateTransportToWireless(d)"),
        )
        assertTrue(
            "load() 里必须调用 migrateWidthPhysicalMmTo350",
            loadBody.contains("migrateWidthPhysicalMmTo350(d)"),
        )
    }

    /**
     * ⚠️ 一次性迁移**各自带独立标记**，不能共用。
     *
     * 这条纪律在 [SharedPreferencesCarSettingsStore] 的 KDoc 里被反复强调
     * （`migrateLegacyHotspotDefaults` 的注释就是踩过坑的产物）：
     * 两条迁移共用标记 ⇒ 先跑的那条把标记置位 ⇒ 后跑的那条直接 return ⇒
     * **静默失效**，而且完全看不出来。
     */
    @Test
    fun `两条迁移使用各自独立的标记`() {
        val source = codeOf()
        assertTrue(
            "transport 迁移缺标记 KEY_TRANSPORT_MIGRATED",
            source.contains("KEY_TRANSPORT_MIGRATED"),
        )
        assertTrue(
            "物理长度迁移缺标记 KEY_WIDTH_MM_MIGRATED",
            source.contains("KEY_WIDTH_MM_MIGRATED"),
        )
        // 两个标记常量必须是不同的字符串字面量。
        val values = Regex("""(KEY_TRANSPORT_MIGRATED|KEY_WIDTH_MM_MIGRATED)\s*=\s*"([^"]+)"""")
            .findAll(source)
            .associate { it.groupValues[1] to it.groupValues[2] }
        assertEquals("两个标记常量都应能被解析出来", 2, values.size)
        assertFalse(
            "两条迁移的标记字符串不能相同（否则先跑的会把后一条静默吃掉）：$values",
            values["KEY_TRANSPORT_MIGRATED"] == values["KEY_WIDTH_MM_MIGRATED"],
        )
    }

    /**
     * ⚠️ 判据**只认旧出厂值**，不能无条件改写。
     *
     * 用户可能已经手动把参考物理长度调成 250/300（那是他自己量过的真实值），
     * 无条件覆盖成 350 反而更差。所以迁移必须带 `if (current == 旧出厂值)` 条件。
     */
    @Test
    fun `迁移只改写恰好等于旧出厂值的持久化项`() {
        val source = codeOf()
        val transportFn = functionBody(source, "migrateTransportToWireless")
        assertTrue(
            "transport 迁移必须比对 LEGACY_DEFAULT_TRANSPORT",
            transportFn.contains("LEGACY_DEFAULT_TRANSPORT"),
        )
        val widthFn = functionBody(source, "migrateWidthPhysicalMmTo350")
        assertTrue(
            "物理长度迁移必须比对 LEGACY_DEFAULT_WIDTH_MM",
            widthFn.contains("LEGACY_DEFAULT_WIDTH_MM"),
        )
    }

    /** 旧出厂值常量本身：有线是 `WIRED`，物理长度是 `200`。 */
    @Test
    fun `旧出厂值常量与历史一致`() {
        val source = codeOf()
        assertTrue(
            "LEGACY_DEFAULT_TRANSPORT 应为 WIRED",
            Regex("""LEGACY_DEFAULT_TRANSPORT\s*=\s*"WIRED"""").containsMatchIn(source),
        )
        assertTrue(
            "LEGACY_DEFAULT_WIDTH_MM 应为 200",
            Regex("""LEGACY_DEFAULT_WIDTH_MM\s*=\s*200\b""").containsMatchIn(source),
        )
    }

    /**
     * ⚠️ 迁移的判据文本必须是**裸字符串**，不能用 `CarTransportMode.WIRED.name`。
     *
     * 判据是"上一版出厂时**写进偏好里的那串文本**"，不是"这个枚举某个成员
     * 现在叫什么"。将来若重命名枚举成员，用 `.name` 会让判据悄悄失效
     * （迁移永不命中 —— 又是一次静默失效）。
     */
    @Test
    fun `迁移判据不依赖枚举成员名`() {
        val source = codeOf()
        val transportFn = functionBody(source, "migrateTransportToWireless")
        assertFalse(
            "判据不应写成 CarTransportMode.WIRED.name（重命名后会静默失效）",
            transportFn.contains("CarTransportMode."),
        )
    }

    /** 迁移标记必须在写入后置位（否则每次 load 都会重跑，虽然结果幂等但语义不对）。 */
    @Test
    fun `迁移结束后置位标记`() {
        val source = codeOf()
        for (fn in listOf("migrateTransportToWireless", "migrateWidthPhysicalMmTo350")) {
            val body = functionBody(source, fn)
            assertTrue(
                "$fn 必须在结尾置位自己的迁移标记",
                body.contains("putBoolean(") && body.contains(".apply()"),
            )
        }
    }

    // ── 3. 「P2P_DIRECT 已移除」的默认值与迁移（PATCH-0113，2026-10-13） ──────

    /**
     * 出厂默认热点模式必须是**复用车机 TCAM 热点**（车主口径）。
     *
     * ⚠️ 2026-10-13 之前是 `P2P_DIRECT`（Wi-Fi Direct 临时组网）；那一档已整体移除，
     * 默认一度回到"复用车机热点"。2026-10-16 车主再拍板：删掉 `MANUAL`、让
     * `TCAM_HOTSPOT` 取代它——TCAM 自动识别 `eth0.11`，无需用户手填车机热点地址。
     */
    @Test
    fun `出厂默认热点模式是车机 GEELY_ROUTED 热点`() {
        assertEquals(CarHotspotMode.GEELY_ROUTED, CarSessionSettings.default().hotspotMode)
    }

    /** `P2P_DIRECT` 必须真的从枚举里消失（不是"留着但不显示"）。 */
    @Test
    fun `P2P_DIRECT 已不在热点模式枚举里`() {
        assertFalse(
            "CarHotspotMode 里不该再有 P2P_DIRECT：${CarHotspotMode.entries.map { it.name }}",
            CarHotspotMode.entries.any { it.name == "P2P_DIRECT" },
        )
    }

    /**
     * 老安装的 `hotspotMode = "P2P_DIRECT"` 必须被**一次性迁移**改写。
     *
     * 只改 `load()` 的默认值对老安装完全无效：它们早就把旧出厂值写进 prefs 了。
     * 不迁移的后果是 prefs 与默认值长期不一致，而且设置页会高亮一个
     * 已经不存在的档（`enumOf` 兜底成 MANUAL，界面却按旧值算）。
     */
    @Test
    fun `移除 P2P 档的迁移在 load 里注册`() {
        val source = codeOf()
        val loadIndex = source.indexOf("override fun load()")
        assertTrue("找不到 load()", loadIndex >= 0)
        val loadBody = source.substring(loadIndex).substringBefore("return CarSessionSettings(")
        assertTrue(
            "load() 里必须调用 migrateRemovedP2pHotspotMode",
            loadBody.contains("migrateRemovedP2pHotspotMode(d)"),
        )
    }

    /**
     * ⚠️ 判据必须是**裸字符串** `"P2P_DIRECT"`，且**带独立标记**。
     *
     * - 不能写 `CarHotspotMode.P2P_DIRECT.name`：那个枚举值本身已经删了，写成引用会编译不过；
     * - 不能与别的迁移共用标记：共用 ⇒ 先跑的那条把标记置位 ⇒ 这条直接 return ⇒ **静默失效**
     *   （与上面那条"各自独立标记"是同一个坑，见 [SharedPreferencesCarSettingsStore] 的 KDoc）。
     */
    @Test
    fun `移除 P2P 档的迁移用独立标记且判据是裸字符串`() {
        val source = codeOf()
        assertTrue(
            "缺标记 KEY_P2P_MODE_MIGRATED",
            source.contains("KEY_P2P_MODE_MIGRATED"),
        )
        val fn = functionBody(source, "migrateRemovedP2pHotspotMode")
        assertTrue(
            "判据必须比对 REMOVED_P2P_MODE_NAME",
            fn.contains("REMOVED_P2P_MODE_NAME"),
        )
        assertFalse(
            "判据里不该出现 CarHotspotMode.（枚举值已删，写了也编译不过）",
            fn.contains("CarHotspotMode."),
        )
        assertTrue(
            "REMOVED_P2P_MODE_NAME 必须是字面量 \"P2P_DIRECT\"",
            Regex("""REMOVED_P2P_MODE_NAME\s*=\s*"P2P_DIRECT"""").containsMatchIn(source),
        )
        assertTrue(
            "迁移结束后必须置位标记",
            fn.contains("putBoolean(") && fn.contains(".apply()"),
        )
    }

    // ── 4.5. 「MANUAL 从界面移除、由 TCAM 取代」的迁移与解析兜底（2026-10-16） ──

    /**
     * 老安装持久化里的 `hotspotMode = "MANUAL"` 必须迁移/等价到 `TCAM_HOTSPOT`。
     *
     * `MANUAL` 枚举值**保留**（不删），但界面不再提供这一档；老值若原样读回，
     * 会走"手填地址"那一支而**丢失 TCAM 语义**，且设置页模式按钮一个都不高亮。
     */
    @Test
    fun `MANUAL 存量迁移到 TCAM 在 load 里注册`() {
        val source = codeOf()
        val loadIndex = source.indexOf("override fun load()")
        assertTrue("找不到 load()", loadIndex >= 0)
        val loadBody = source.substring(loadIndex).substringBefore("return CarSessionSettings(")
        assertTrue(
            "load() 里必须调用 migrateManualHotspotModeToTcam",
            loadBody.contains("migrateManualHotspotModeToTcam(d)"),
        )
    }

    @Test
    fun `MANUAL 迁移用独立标记且判据是裸字符串`() {
        val source = codeOf()
        assertTrue(
            "缺标记 KEY_MANUAL_MODE_MIGRATED",
            source.contains("KEY_MANUAL_MODE_MIGRATED"),
        )
        val fn = functionBody(source, "migrateManualHotspotModeToTcam")
        assertTrue(
            "判据必须比对 LEGACY_MANUAL_MODE_NAME",
            fn.contains("LEGACY_MANUAL_MODE_NAME"),
        )
        assertTrue(
            "LEGACY_MANUAL_MODE_NAME 必须是字面量 \"MANUAL\"",
            Regex("""LEGACY_MANUAL_MODE_NAME\s*=\s*"MANUAL"""").containsMatchIn(source),
        )
        assertTrue(
            "迁移结束后必须置位标记",
            fn.contains("putBoolean(") && fn.contains(".apply()"),
        )
    }

    @Test
    fun `load 解析层把 MANUAL 等价到 TCAM`() {
        val source = codeOf()
        assertTrue(
            "解析/映射层必须把 MANUAL 等价到 TCAM_HOTSPOT（覆盖降级→升级后迁移标记已置位的残留值）",
            source.contains(
                "if (mode == CarHotspotMode.MANUAL) CarHotspotMode.TCAM_HOTSPOT else mode",
            ),
        )
    }

    @Test
    fun `已退役的导航自动投屏字段默认关闭`() {
        assertFalse(
            "导航自动投屏已取消，保留字段仅为兼容旧配置",
            CarSessionSettings.default().clusterAutoShowOnRoute,
        )
    }

    @Test
    fun `主页本地导航卡片开关默认显示以兼容现有行为`() {
        assertTrue(
            "新增的主页导航卡片开关默认开，不改变旧安装可见行为",
            CarSessionSettings.default().localNavigationCardEnabled,
        )
    }

    // ── 测试工具 ────────────────────────────────────────────────────────────

    /** 取某个 `private fun <name>(...)` 的函数体（到下一个 `private fun` 或文件尾为止）。 */
    private fun functionBody(source: String, name: String): String {
        val start = source.indexOf("private fun $name(")
        assertTrue("找不到函数 $name", start >= 0)
        val rest = source.substring(start + 1)
        val next = rest.indexOf("\n    private fun ")
        return if (next >= 0) rest.substring(0, next) else rest
    }

    /**
     * 读 [SharedPreferencesCarSettingsStore].kt 并**剥掉注释**。
     *
     * 剥注释是必需的：这些 KDoc 为了讲清"为什么必须迁移"必然引用
     * `WIRED` / `200` 等字面量，不剥掉会让断言把注释当代码（假通过或假失败）。
     */
    private fun codeOf(): String {
        val raw = sourceOf(
            "car/core-bridge/src/main/java/com/dbtool/carplay/bridge/" +
                "SharedPreferencesCarSettingsStore.kt",
        )
        return raw
            .replace(Regex("(?s)/\\*.*?\\*/"), " ")
            .replace(Regex("(?m)//.*$"), " ")
    }

    private fun sourceOf(relative: String): String {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile
        }
        throw AssertionError("找不到源码：$relative")
    }
}
