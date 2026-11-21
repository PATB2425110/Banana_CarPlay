package com.dbtool.carplay.bridge.hotspot

import com.dbtool.carplay.api.hotspot.CarManualHotspotMatch
import com.dbtool.carplay.api.hotspot.CarNativeHotspotInfo
import com.dbtool.carplay.api.settings.CarSessionSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「复用车机热点」下**手填 SSID 与车机热点名比对**判据的单测（2026-10-06 加）。
 *
 * ## 这条判据存在的理由
 *
 * 上游 `ManualHotspotManager.start()` 有个反直觉的短路：
 *
 * ```kotlin
 * val apConfiguration = readApConfiguration()?.takeIf { it.ssid == expectedSsid }
 * validateApConfiguration(apConfiguration)
 * ```
 *
 * **名字对**时它会去校验信道/频段/加密（不符就抛）；**名字错**时那个
 * `takeIf` 让 `apConfiguration` 变成 null ⇒ **校验全跳过** ⇒ 只靠"找到一张
 * 像 AP 的网卡"就返回成功，把用户填的字面量原样发给 iPhone。
 *
 * 而那张网**没有人广播** ⇒ iPhone 永远关联不上 ⇒ 卡在「无线通道就绪」，
 * **全链路无一句报错**。所以"填错了"这件事必须由**我们自己**在设置页说出来。
 *
 * ## 判据的两条铁律（都有对应测试）
 *
 * 1. **判不准就别说话**：没探测到车机热点 / 没读到名字 ⇒ 返回 null。
 *    车机热点完全可能只是还没开，此时任何"不匹配"都是假警报。
 * 2. **一致时才放行**：不只是"都非空"，而是**字面完全相等**（区分大小写，
 *    Wi-Fi SSID 本身就是区分大小写的）。
 */
class CarManualHotspotMatchTest {

    private fun info(
        detected: Boolean = true,
        ssid: String? = "Lynk",
    ) = CarNativeHotspotInfo(
        detected = detected,
        ssid = ssid,
        interfaceName = "wlan0",
        hostAddress = "192.168.176.1",
    )

    // ── 正向：一致 ────────────────────────────────────────────────────────

    @Test
    fun `名字一致时不告警`() {
        val r = CarManualHotspotMatch.evaluate(typedSsid = "Lynk", nativeInfo = info())
        assertTrue(r.matches)
        assertNull("一致时必须没有告警文案", r.note)
    }

    /** 用户输入带首尾空格（手机输入法常见）不应被判成不一致。 */
    @Test
    fun `首尾空格会被裁掉再比对`() {
        val r = CarManualHotspotMatch.evaluate(typedSsid = "  Lynk  ", nativeInfo = info())
        assertTrue("首尾空格不该造成不匹配", r.matches)
        assertNull(r.note)
    }

    // ── 负向：不一致（核心场景）──────────────────────────────────────────

    /**
     * **这就是默认配置会踩的坑**：DBPlay 的 MANUAL 默认 SSID 是 `DBPlay`，
     * 而车机热点通常叫别的（实车是 `Lynk`）。
     */
    @Test
    fun `默认 DBPlay 与车机 Lynk 不一致时必须告警`() {
        val r = CarManualHotspotMatch.evaluate(typedSsid = "DBPlay", nativeInfo = info())
        assertFalse("必须判成不匹配", r.matches)
        val note = r.note
        assertTrue("必须给出告警文案", note != null)
        assertTrue("文案要点出用户填的值：$note", note!!.contains("DBPlay"))
        assertTrue("文案要点出车机真实的值（用户要照着改）：$note", note.contains("Lynk"))
    }

    /** 大小写不同也算不一致 —— Wi-Fi SSID 区分大小写。 */
    @Test
    fun `大小写不同算不一致`() {
        val r = CarManualHotspotMatch.evaluate(typedSsid = "lynk", nativeInfo = info())
        assertFalse("SSID 区分大小写，小写 lynk ≠ Lynk", r.matches)
    }

    /** 差一个字符也要报 —— 这正是"静默失败"最典型的起因。 */
    @Test
    fun `差一个字符也要报`() {
        val r = CarManualHotspotMatch.evaluate(typedSsid = "Lyn", nativeInfo = info())
        assertFalse(r.matches)
        assertTrue(r.note!!.contains("Lynk"))
    }

    // ── 判不准就别说话 ──────────────────────────────────────────────────

    /**
     * 没探测到车机热点 ⇒ **不告警**。
     *
     * 车机热点可能只是还没开；此时报"不匹配"会把用户赶去改一个本来正确的名字
     * ——本仓反复踩过"自造判据比参考更严 ⇒ 把无害情况判成致命"。
     */
    @Test
    fun `没探测到热点时不告警`() {
        val r = CarManualHotspotMatch.evaluate(
            typedSsid = "DBPlay",
            nativeInfo = info(detected = false, ssid = null),
        )
        assertTrue("判不了就不说话", r.matches)
        assertNull(r.note)
    }

    /** 探测到了但名字没读到 ⇒ 同样不敢下结论。 */
    @Test
    fun `探测到但没读到名字时不告警`() {
        assertNull(CarManualHotspotMatch.evaluate("DBPlay", info(ssid = null)).note)
        assertNull(CarManualHotspotMatch.evaluate("DBPlay", info(ssid = "  ")).note)
    }

    /** 从没探测过（null）⇒ 不告警。 */
    @Test
    fun `没有探测结果时不告警`() {
        val r = CarManualHotspotMatch.evaluate(typedSsid = "DBPlay", nativeInfo = null)
        assertTrue(r.matches)
        assertNull(r.note)
    }

    /** 用户还没填 ⇒ 这一条由输入框自己的空值校验负责，不在这里重复告警。 */
    @Test
    fun `用户未填时不告警`() {
        assertNull(CarManualHotspotMatch.evaluate("", info()).note)
        assertNull(CarManualHotspotMatch.evaluate("   ", info()).note)
    }

    // ── 诊断用的一行 ────────────────────────────────────────────────────

    @Test
    fun `诊断行同时给出双方的值`() {
        val line = CarManualHotspotMatch.debugLine("DBPlay", info())
        assertTrue("要含判定结果：$line", line.contains("matches=false"))
        assertTrue("要含用户填的值：$line", line.contains("DBPlay"))
        assertTrue("要含车机的值：$line", line.contains("Lynk"))
    }

    @Test
    fun `诊断行在没探测到时写明未探测到`() {
        val line = CarManualHotspotMatch.debugLine("DBPlay", null)
        assertTrue("要说明没探测到：$line", line.contains("未探测到"))
    }

    /** 常量自检：默认 SSID 确实不是车机热点常用的名字（这是坑的根源）。 */
    @Test
    fun `新产品默认出厂 SSID 是 LynkCo`() {
        assertEquals(
            "Lynk&Co",
            com.dbtool.carplay.api.settings.CarSessionSettings.DEFAULT_HOTSPOT_SSID,
        )
    }

    // ── 连接前核对（2026-10-13，日志 4：Lynk&Co ↔ Lynk & Co）────────────────

    /** 日志 4 现场：设置填 `Lynk&Co`，车机真名 `Lynk & Co`。 */
    @Test
    fun `只差空格_本次改用车机真名`() {
        val r = CarManualHotspotMatch.reconcile(typedSsid = "Lynk&Co", storedSsid = "Lynk & Co")
        assertTrue("应判成改用真名：$r", r is CarManualHotspotMatch.Reconcile.UseStored)
        r as CarManualHotspotMatch.Reconcile.UseStored
        assertEquals("Lynk & Co", r.ssid)
        assertTrue("提示里要有双方的值：${r.note}", r.note.contains("Lynk&Co") && r.note.contains("Lynk & Co"))
    }

    @Test
    fun `只差大小写_本次改用车机真名`() {
        val r = CarManualHotspotMatch.reconcile("lynk & co", "Lynk & Co")
        assertEquals("Lynk & Co", (r as CarManualHotspotMatch.Reconcile.UseStored).ssid)
    }

    @Test
    fun `首尾空格不算差异_保持不动`() {
        assertEquals(
            CarManualHotspotMatch.Reconcile.Keep,
            CarManualHotspotMatch.reconcile("  Lynk & Co ", "Lynk & Co"),
        )
    }

    @Test
    fun `完全一致_保持不动`() {
        assertEquals(
            CarManualHotspotMatch.Reconcile.Keep,
            CarManualHotspotMatch.reconcile("Lynk & Co", "Lynk & Co"),
        )
    }

    /** 实质不同的名字绝不替用户改：存储名未必等于实际广播名，读到的也可能是残留。 */
    @Test
    fun `实质不同_只告警不改`() {
        val r = CarManualHotspotMatch.reconcile("My Car WiFi", "Lynk & Co")
        assertTrue("应只告警：$r", r is CarManualHotspotMatch.Reconcile.Mismatch)
        assertTrue((r as CarManualHotspotMatch.Reconcile.Mismatch).note.contains("Lynk & Co"))
    }

    /**
     * 出厂默认名 `DBPlay` 是自建热点的占位名，不是用户对车机热点的选择（日志 5：复用模式下
     * SSID 仍是 DBPlay）。车机存储名已知且不同 ⇒ 本次改用真名，设置不动。
     */
    @Test
    fun `出厂默认名且车机名已知_本次改用车机真名`() {
        val r = CarManualHotspotMatch.reconcile(
            com.dbtool.carplay.api.settings.CarSessionSettings.DEFAULT_HOTSPOT_SSID,
            "Lynk & Co",
        )
        assertTrue("应改用真名：$r", r is CarManualHotspotMatch.Reconcile.UseStored)
        assertEquals("Lynk & Co", (r as CarManualHotspotMatch.Reconcile.UseStored).ssid)
    }

    /** 车机热点本来就叫 DBPlay（或读不到车机名）时，默认名不是问题，不动。 */
    @Test
    fun `出厂默认名但车机名一致或未知_保持不动`() {
        val ssid = com.dbtool.carplay.api.settings.CarSessionSettings.DEFAULT_HOTSPOT_SSID
        assertEquals(CarManualHotspotMatch.Reconcile.Keep, CarManualHotspotMatch.reconcile(ssid, ssid))
        assertEquals(CarManualHotspotMatch.Reconcile.Keep, CarManualHotspotMatch.reconcile(ssid, null))
    }

    @Test
    fun `实质不同_差一个字符也不当成空格差异`() {
        assertTrue(
            CarManualHotspotMatch.reconcile("Lynk&Co1", "Lynk & Co") is
                CarManualHotspotMatch.Reconcile.Mismatch,
        )
    }

    @Test
    fun `读不到车机名或用户没填_保持不动`() {
        assertEquals(CarManualHotspotMatch.Reconcile.Keep, CarManualHotspotMatch.reconcile("Lynk&Co", null))
        assertEquals(CarManualHotspotMatch.Reconcile.Keep, CarManualHotspotMatch.reconcile("Lynk&Co", "  "))
        assertEquals(CarManualHotspotMatch.Reconcile.Keep, CarManualHotspotMatch.reconcile("", "Lynk & Co"))
        assertEquals(CarManualHotspotMatch.Reconcile.Keep, CarManualHotspotMatch.reconcile("   ", "Lynk & Co"))
    }

    @Test
    fun `设置页告警在只差空格时点破差在哪`() {
        val note = CarManualHotspotMatch.evaluate("Lynk&Co", info(ssid = "Lynk & Co")).note
        assertTrue("要点破空格/大小写：$note", note!!.contains("空格"))
        val plain = CarManualHotspotMatch.evaluate("DBPlay", info(ssid = "Lynk")).note
        assertFalse("实质不同时不该说是空格问题：$plain", plain!!.contains("只差空格"))
    }

    @Test
    fun `evaluateStored 与 evaluate 同一判据`() {
        assertEquals(
            CarManualHotspotMatch.evaluate("DBPlay", info()),
            CarManualHotspotMatch.evaluateStored("DBPlay", "Lynk"),
        )
        // 未探测到（detected=false）与"没读到存储名"都不告警。
        assertTrue(CarManualHotspotMatch.evaluateStored("DBPlay", null).matches)
        assertTrue(CarManualHotspotMatch.evaluate("DBPlay", info(detected = false)).matches)
    }

    // ── 接线契约：只在复用分支生效，不波及自建/P2P/局域网 ───────────────────

    private fun bridgeCode(): String {
        val rel = "src/main/java/com/dbtool/carplay/bridge/CarPlaySessionBridge.kt"
        val file = listOf(java.io.File(rel), java.io.File("../$rel"), java.io.File("../../$rel"))
            .firstOrNull { it.exists() } ?: error("找不到 $rel")
        return file.readText()
            .replace(Regex("(?s)/\\*.*?\\*/"), " ")
            .replace(Regex("(?m)//.*$"), " ")
    }

    @Test
    fun `热点名核对只挂在复用分支上`() {
        val code = bridgeCode()
        // 2026-10-13（PATCH-0116）：这个分支里现在**多了**一行只读的客户端观测器启动，
        // 所以不能再要求 `reconcileReusedSsid(` 紧跟在 `{` 后面。改成"截出这一支的函数体"
        // 再逐条断言 —— 既保住"只在复用下调用"的契约，又不把无关的先后顺序钉死。
        val branch = Regex("""if \(effectivePlan\.reused\) \{([\s\S]*?)\} else \{""")
            .find(code)?.groupValues?.get(1).orEmpty()
        assertTrue(
            "找不到 `if (effectivePlan.reused) { … } else {` 结构 —— 这段接线被改写了，本契约需同步更新",
            branch.isNotEmpty(),
        )
        assertTrue(
            "复用分支必须调用 reconcileReusedSsid(effectivePlan…)",
            branch.contains("reconcileReusedSsid(effectivePlan"),
        )
        assertTrue(
            "复用分支必须启动只读的客户端观测器（PATCH-0116：车机侧有没有客户端关联上来，" +
                "是「没关联上」与「关联上了到不了 endpoint」之间唯一的分水岭）",
            branch.contains("CarHotspotClientWatcher.start("),
        )
        assertEquals(
            "reconcileReusedSsid 只允许有这一个调用点（外加定义），不得被其它热点模式调用",
            1,
            Regex("""reconcileReusedSsid\(effectivePlan""").findAll(code).count(),
        )
    }

    @Test
    fun `设置页提示里的密码插值带花括号`() {
        val rel = "src/main/java/com/dbtool/carplay/bridge/CarAppServices.kt"
        val file = listOf(java.io.File(rel), java.io.File("../$rel"), java.io.File("../../$rel"))
            .firstOrNull { it.exists() } ?: error("找不到 $rel")
        val code = file.readText()
            .replace(Regex("(?s)/\\*.*?\\*/"), " ")
            .replace(Regex("(?m)^\\s*//.*$"), " ")
        assertTrue(
            "`\$plan.passphrase` 只插值 plan 本身（把整个 CarHotspotPlan 打到设置页上），必须写 \${plan.passphrase}",
            code.contains("\${plan.passphrase}") && !code.contains("\$plan.passphrase"),
        )
    }

    // ── 「读到的车机密码」如何写进日志（PATCH-0116，2026-10-13）──────────────

    /**
     * 复用失败时日志里只有一句 `passphrase is DBPlay factory default (reuse mode)`，
     * 而它同时覆盖"读不到密码"（通告的值是**错的**）与"密码本来就是它"
     * （通告的值是**对的**）。2026-10-13 现场（日志 9）就卡在这个二义性上：
     * 无法决定下一步是修密码读取、还是去查网络路径。
     *
     * ⇒ 三档文案必须**两两不同**，且都不含明文密码。
     */
    @Test
    fun `车机密码读数三档可区分`() {
        val unreadable = CarManualHotspotMatch.describeCarPassphraseRead(null)
        val blank = CarManualHotspotMatch.describeCarPassphraseRead("")
        val badLength = CarManualHotspotMatch.describeCarPassphraseRead("1234567")
        val factory = CarManualHotspotMatch.describeCarPassphraseRead(
            CarSessionSettings.DEFAULT_HOTSPOT_PASSPHRASE,
        )
        val other = CarManualHotspotMatch.describeCarPassphraseRead("s3cret-pass")

        assertTrue("读不到必须说 unreadable：$unreadable", unreadable.contains("unreadable"))
        assertTrue("空串与读不到同档：$blank", blank.contains("unreadable"))
        assertTrue("长度不在 8~63 必须说 unusable：$badLength", badLength.contains("unusable"))
        assertTrue(
            "读到且等于出厂默认值必须明确说与车机一致（这就是「连不上则与密码无关」那条判据）：$factory",
            factory.contains("factoryDefault"),
        )
        assertTrue("读到但不等于出厂默认值必须说没有替换：$other", other.contains("没有"))
        assertTrue(
            "三档必须两两不同，否则日志里又看不出来是哪一种",
            unreadable != factory && badLength != factory && badLength != unreadable,
        )
        assertFalse("明文密码不得进入日志文案", other.contains("s3cret-pass"))
    }
}
