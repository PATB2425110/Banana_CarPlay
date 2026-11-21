package com.dbtool.carplay.bridge.hotspot

import com.dbtool.carplay.api.hotspot.CarAdbHotspotDefaults
import com.dbtool.carplay.api.settings.CarHotspotSecurity
import com.dbtool.carplay.api.settings.CarSessionSettings
import com.dbtool.carplay.bridge.network.CarHotspotResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「复用车机热点」(`CarHotspotMode.MANUAL`) 这条链的契约测试。
 *
 * ## 为什么要专门钉这条链
 *
 * `AUTO` 模式的探测判据已被 [CarNativeHotspotReuseTest] 覆盖得很密，
 * 但 `MANUAL`（设置页里用户**显式**选"复用车机热点"）走的是**另一条代码路径**
 * ——`CarHotspotResolver.explicit()` 的 `reuses` 分支，**此前一个测试都没有**。
 *
 * ## 这条链上已知的三个失败模式（都来自实车日志）
 *
 * 1. **SSID 必须照抄车机那一个**。上游 `ManualHotspotManager` 用存储配置名
 *    硬校验，填错的名字会让 iPhone 去关联一张不存在的网 ⇒ 卡在「无线通道就绪」，
 *    **全程无报错**。
 * 2. **上游会校验频段/信道/加密方式**（`validateApConfiguration`）——
 *    只在 SSID 恰好匹配时生效，三项任一不符直接抛 `IOException`。
 *    所以 MANUAL 下这三个值必须与车机实际一致，不能是"看着合理"的默认值。
 * 3. **加密方式必须由密码现推**（`securityForSettings`），不能照搬设置里那份 ——
 *    "设置选 OPEN + 密码非空"这种自相矛盾的组合送进上游必抛。
 *
 * ## 测试方式
 *
 * `resolve()` 需要一个真 `Context`（`explicit()` 会取 `applicationContext`），
 * JVM 单测里造不出来。所以这里用**源码级断言**（本仓既有模式：
 * `HotspotReuseContractTest` / `CarVideoSizeLimiterTest`）把契约形态钉死，
 * 防将来有人把这几个判据改回去。
 *
 * ⚠️ 读源码**必须先剥注释**——这几处的 KDoc 为了讲清历史必然引用
 * `manualHotspotSsid` / `validateApConfiguration` 等字面量（已踩过坑）。
 */
class HotspotManualReuseContractTest {

    private val resolverSrc =
        "src/main/java/com/dbtool/carplay/bridge/network/CarHotspotResolver.kt"

    /** 截出 `explicit()` 到下一个私有函数之前的函数体。 */
    private fun explicitBody(): String {
        val src = codeOf(resolverSrc)
        val body = src.substringAfter("private fun explicit(", "")
        assertTrue(
            "explicit() 不见了——签名或所在文件变了，本契约需同步更新",
            body.isNotEmpty(),
        )
        return body.substringBefore("private fun sameLan(")
    }

    /**
     * MANUAL 下 `reused` 必须为 true —— 它是"这次是 attach 车机已有的网，
     * 不是我们自己起"的唯一标志，设置页文案与诊断日志都读它。
     */
    @Test
    fun `MANUAL 模式下 reused 标志为真`() {
        val body = explicitBody()
        assertTrue(
            "explicit() 必须以 `settings.hotspotMode == CarHotspotMode.MANUAL` 推导 reuses",
            body.contains("CarHotspotMode.MANUAL"),
        )
        assertTrue(
            "reused 必须接在 reuses 上（写死 false 会让复用被误标成自建）",
            body.contains("reused = reuses"),
        )
    }

    /**
     * ⚠️ 加密方式**必须由密码现推**，不能直接用设置里那份。
     *
     * 上游 `ManualHotspotManager` 的 init 里有硬 `require`：
     * 「加密网络密码不能为空」「开放网络密码必须为空」。
     * 设置页允许用户先填密码再切到开放网络 ⇒ 直接透传会**必抛**。
     */
    @Test
    fun `加密方式由密码现推而不是照搬设置`() {
        val body = explicitBody()
        assertTrue(
            "security 必须走 securityForSettings(settings, …) 现推",
            body.contains("security = securityForSettings("),
        )
    }

    /**
     * ⚠️ MANUAL 下**不能**去算自建用的网卡/网段参数（`adbNetwork`）。
     *
     * `adbNetwork` 只对 `ADB_HOSTAPD`（自建）有意义。复用分支若也填上，
     * 等于把"我们打算自建的网段"混进"接管别人网络"的方案里，
     * 诊断日志会出现两套互相矛盾的网络参数。
     *
     * 判据：自建参数的求值条件里必须含 `selfBuilt`。
     */
    @Test
    fun `自建网络参数只在自建分支求值`() {
        val body = explicitBody()
        val idx = body.indexOf("CarAdbHotspotPlanner.plan(")
        assertTrue("找不到自建参数求值点", idx >= 0)
        val guard = body.substring(0, idx)
        assertTrue(
            "CarAdbHotspotPlanner.plan 必须在 `selfBuilt && context != null` 之下求值，" +
                "否则复用分支会混进自建网段参数",
            guard.contains("selfBuilt"),
        )
    }

    /**
     * 端口/热点常量自检：MANUAL 复用的目标端口是**厂商口**（我们只 attach），
     * 不该误用 DBPlay 自己的 `DEVICE_ADB_PORT` 去判断。
     *
     * 这条是防"把两个概念混起来"：`DEVICE_ADB_PORT`(5556) 是**自建热点时
     * 我们让 adbd 听的口**，与"车机热点的 SSID/密码"毫无关系。
     */
    @Test
    fun `复用分支不引用 DEVICE_ADB_PORT`() {
        val body = explicitBody()
        assertFalse(
            "explicit() 的复用分支不应出现 DEVICE_ADB_PORT（那是自建时切 adbd 的口）",
            body.contains("DEVICE_ADB_PORT"),
        )
    }

    /**
     * SSID 为空时的处置**按模式分开**（2026-10-13，PATCH-0116 **订正**）。
     *
     * ## 订正依据（实测证据，不是推测）
     *
     * 本用例原先写的是"必须留 `null` —— 留 null 让上游用自己的默认值，比我们编一个更安全"。
     * 那个前提**已被真车日志推翻**：上游 `CarPlayRuntimeConfig` 对
     * `transport == WIRELESS && mode == MANUAL` 有硬 `require`，ssid 为 `null` / 空白
     * ⇒ 直接抛 `IllegalArgumentException: manualHotspotSsid is required in manual hotspot mode`。
     * **上游没有任何"自己填默认名"的兜底。**
     *
     * 日志 9（`1791897995606`，2026-10-13 21:24:54 与 21:25:01 各一次）：
     * 设置页把热点名清空 ⇒ 计划显示「自建热点「系统随机」」 ⇒ 随即报上面那句 ⇒
     * 连接当场失败，而计划文案里**看不出**这个名字就是原因。
     *
     * ## 现在的判据
     *
     * - **自建**（`ADB_HOSTAPD`）：空 ⇒ 出厂默认名（与 `fallback()` 同一处置 ——
     *   我们自己也没有"让系统随机生成"这条路，那张 AP 是我们用 hostapd 起的）；
     * - **复用**（`MANUAL`）：仍留 `null`（名字该由车机那张网说了算，
     *   见 `reconcileReusedSsid`），这条判据**不动**。
     */
    @Test
    fun `空 SSID 自建兜底成出厂名而复用仍留 null`() {
        val body = explicitBody()
        assertTrue(
            "复用分支仍必须留 null：`settings.manualHotspotSsid.takeIf { it.isNotEmpty() }`",
            body.contains("takeIf { it.isNotEmpty() }"),
        )
        assertTrue(
            "自建分支必须兜底成 `CarAdbHotspotDefaults.SSID`：" +
                "留空会让上游 require 直接抛（日志 9 实证），" +
                "而计划文案会把它显示成「系统随机」，现场看不出原因",
            body.contains("ifBlank { CarAdbHotspotDefaults.SSID }"),
        )
    }

    // ── 纯数据契约（可直接实例化，不依赖 Context）─────────────────────────

    /**
     * `CarHotspotPlan.reused` 的语义：true = 接管车机已有的网。
     *
     * 这条由 `HotspotReuseContractTest` 覆盖 `CarAdbHotspotManager` 一侧，
     * 这里只做常量自检，确保两处判据用的是同一套枚举。
     */
    @Test
    fun `产品默认 SSID 不再依赖已移除的 ADB 自建配置`() {
        // 复用模式下用户不改 SSID 时，默认值应等于自建的默认名——
        // 这样"设置里没动过"的用户切模式时不会莫名其妙换名字。
        assertEquals(
            "Lynk&Co",
            CarSessionSettings.DEFAULT_HOTSPOT_SSID,
        )
    }

    /** 加密枚举与上游映射必须一一对应（漏一个会在 `when` 编译期报错，这里再钉一层）。 */
    @Test
    fun `加密枚举覆盖四种且无遗漏`() {
        val names = CarHotspotSecurity.entries.map { it.name }.toSet()
        assertEquals(
            setOf("OPEN", "WPA2", "WPA3_TRANSITION", "WPA3"),
            names,
        )
    }

    // ── 手填 SSID 比对（2026-10-06 加）──────────────────────────────────

    private val servicesSrc =
        "src/main/java/com/dbtool/carplay/bridge/CarAppServices.kt"

    /** 截出 `phoneHintOf` 的函数体（到下一个函数 `createSession` 之前）。 */
    private fun phoneHintBody(): String {
        val src = codeOf(servicesSrc)
        val body = src.substringAfter("private fun phoneHintOf(", "")
        assertTrue(
            "phoneHintOf 不见了——签名或所在文件变了，本契约需同步更新",
            body.isNotEmpty(),
        )
        return body.substringBefore("fun createSession(")
    }

    /**
     * ⚠️ 名字填错时**不能**再给"iPhone 请连「X」"。
     *
     * 那句提示在填错时是**谎话**：它让用户去连一个没人广播的网络。
     * 正确做法是先让用户把名字改对。
     */
    @Test
    fun `名字填错时不给请连X而是先让改名`() {
        val body = phoneHintBody()
        assertTrue(
            "phoneHintOf 必须先做 CarManualHotspotMatch 比对",
            body.contains("CarManualHotspotMatch.evaluate("),
        )
        val mismatchIdx = body.indexOf("if (mismatch)")
        assertTrue("必须有一个 mismatch 分支", mismatchIdx >= 0)
        // mismatch 分支内应当是"先改名"的文案，而不是"请连"。
        val mismatchBranch = body.substring(mismatchIdx, body.length)
        assertTrue(
            "mismatch 分支必须说「先把……改成车机实际的热点名」",
            mismatchBranch.contains("改成车机实际的热点名"),
        )
        assertTrue(
            "mismatch 分支**不能**再说「iPhone 请连」（那是误导）",
            !mismatchBranch.substringBefore("return when {").contains("iPhone 请连"),
        )
    }

    /**
     * 比对必须只读**设置里的手填值**与**探测结果**，不能凭空造一个名字。
     *
     * 这也是"改成用户手动输入即可"的落实：我们只比对、不改值。
     */
    @Test
    fun `比对只用设置值与探测值不做自动填充`() {
        val src = codeOf(servicesSrc)
        assertTrue(
            "manualSsidWarningOf 必须只在复用类档位下工作（MANUAL / TCAM_HOTSPOT）" +
                "—— 2026-10-16 起界面上的复用档是 TCAM_HOTSPOT",
            src.contains("if (!settings.hotspotMode.reusesCarNativeHotspot) return null"),
        )
        assertTrue(
            "必须用 settings.manualHotspotSsid 作为用户输入",
            src.contains("typedSsid = settings.manualHotspotSsid"),
        )
    }

    // ── 频段 / 信道：复用时不得透传「给自建用的」设置值（2026-10-08 加）─────

    /**
     * ⚠️ 复用分支的 `band` 必须是 `AUTO`，**不能**透传 `settings.manualHotspotBand`。
     *
     * ## 为什么（2026-10-08 实车日志，设备 67b3c24af6b5）
     *
     * 设置页那个 `manualHotspotBand`（出厂默认 `GHZ_5`）是给**自建热点**用的。
     * 复用时上游 `ManualHotspotManager.validateApConfiguration` 会拿它跟
     * **车机实际热点的频段**硬校验，车机热点跑在 2.4 GHz 时：
     *
     * 「Could not establish MANUAL hotspot: Manual hotspot band 2.4 GHz
     *   does not match configured band 5 GHz」→ wireless bring-up failed
     *
     * 于是选「复用车机热点」**每一次都失败** —— 这正是「复用车机热点无法使用」
     * 的根因。上游对 AUTO 有明确放行（`expectedBand != AUTO` 才校验频段），
     * 所以复用分支传 AUTO，改由车机热点自己的频段说了算。
     */
    @Test
    fun `复用分支的频段必须是 AUTO 而不是设置里的自建值`() {
        val body = explicitBody()
        assertTrue(
            "复用分支必须显式写 `reuses -> CarHotspotBand.AUTO`；" +
                "退回 `else -> settings.manualHotspotBand` 会让车机 2.4 GHz 热点" +
                "被设置里那个 5 GHz 硬校验挡掉",
            body.contains("reuses -> CarHotspotBand.AUTO"),
        )
    }

    /**
     * ⚠️ 同理：复用分支的信道必须为 `0`（不指定，随车机热点）。
     *
     * 上游对 `expectedChannel > 0` 的信道**同样硬校验**
     * （`Manual hotspot channel X does not match configured channel Y`）。
     */
    @Test
    fun `复用分支的信道为零而不是设置里的值`() {
        val body = explicitBody()
        assertTrue(
            "复用分支必须显式写 `reuses -> 0`（= 不指定）",
            body.contains("reuses -> 0"),
        )
    }

    // ── 测试工具（剥注释后读源码）──────────────────────────────────────────

    /**
     * 读源码并**剥掉注释**。
     *
     * ⚠️ 剥注释是**必需**的：本文件断言的 `manualHotspotSsid` / `selfBuilt` 等
     * 字面量在 KDoc 里大量出现，不剥会把注释当代码（假通过/假失败都可能）。
     */
    private fun codeOf(relative: String): String {
        val raw = sourceOf(relative)
        return raw
            .replace(Regex("(?s)/\\*.*?\\*/"), " ")
            .replace(Regex("(?m)//.*$"), " ")
    }

    private fun sourceOf(relative: String): String {
        val candidates = listOf(
            File(relative),
            File("../$relative"),
            File("../../$relative"),
        )
        return candidates.firstOrNull { it.exists() }?.readText()
            ?: error("找不到源码 $relative（从 ${File(".").absolutePath} 起找过 ${candidates.size} 个位置）")
    }
}
