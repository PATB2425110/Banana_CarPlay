package com.dbtool.carplay.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CarErrorText] 的单测。
 *
 * ## 为什么这段值得钉死
 *
 * 它是**首页「连接失败」副标题的唯一出处**（经 `CarPlayStatusMapper.detailOf`
 * 与 `CarPlaySessionBridge.startInternal`）。翻错的后果不是崩溃，而是
 * "用户读到了一句话，但那句话帮不上忙"——这种问题在真机上很难被发现，
 * 因为开发机上根本走不到这些分支。
 *
 * ## 重点盯的是"包装层"（2026-09-29 实车暴露）
 *
 * 上游 `CarPlayController.startWirelessHotspot` 会把内层失败原因再包一层：
 *
 * ```
 * Could not establish LOCAL_ONLY_HOTSPOT hotspot: Timed out after 60000ms waiting for LocalOnlyHotspot
 * ```
 *
 * 不剥壳的话，表里那十几条热点内层文案**一条都匹配不上**，全都落到通用兜底
 * ——实车表现就是"无线连不上，但界面只说了一句'连接未能建立'"，
 * 而真正的原因（这台车机的系统热点接口根本不回调）被吃掉了。
 */
class CarErrorTextTest {

    // ------------------------------------------------------------ 空值语义

    @Test
    fun `null 与空白返回 null 而不是兜底`() {
        // 调用方靠 null 保留"没有错误信息"的语义，拿兜底顶替会让首页
        // 在"根本没失败"的时候也显示一句"连接未能建立"。
        assertNull(CarErrorText.of(null))
        assertNull(CarErrorText.of(""))
        assertNull(CarErrorText.of("   "))
    }

    // ------------------------------------------------------------ 未包装（原行为）

    @Test
    fun `精确匹配仍然生效`() {
        assertEquals(
            "本机没有蓝牙适配器",
            CarErrorText.of("Bluetooth adapter is unavailable"),
        )
    }

    @Test
    fun `前缀匹配保留参数`() {
        assertEquals(
            "无法与 iPhone 建立蓝牙连接（AA:BB:CC:DD:EE:FF）",
            CarErrorText.of("Could not connect RFCOMM to AA:BB:CC:DD:EE:FF"),
        )
    }

    @Test
    fun `认不出来时给中文行动指引而不是英文原文`() {
        assertEquals(CarErrorText.FALLBACK, CarErrorText.of("some brand new upstream text"))
    }

    // ------------------------------------------------------------ 我们自己的中文文案（放行）

    @Test
    fun `无线 attach 超时的中文失败原文放行_不被兜底吃掉`() {
        // `:shared` 的 `armWirelessAttachWatchdog` 在重试耗尽后 `fail()` 的原文。
        // 这句含 SSID 与「忽略此网络」指引，是用户此刻最需要的信息；
        // 修复前它落到 FALLBACK，副标题只剩一句「连接未能建立」。
        val raw = "无线 CarPlay 未连上：等待 iPhone 建立连接超时（已尝试 3 次）。" +
            "iPhone 没有连上这台车机的热点。" +
            "请确认手机已打开 Wi-Fi、并连上「Lynk」；" +
            "若手机里还存着这张热点的旧密码，请先「忽略此网络」再重连。"
        assertEquals(raw, CarErrorText.of(raw))
    }

    // ------------------------------------------------------------ 包装层：剥壳

    @Test
    fun `包装层被剥掉_内层精确条目能命中`() {
        // 这一条在修复前是**匹配不上**的（整串带了包装前缀）⇒ 只能落到兜底。
        assertEquals(
            "没有配置要接管的热点名称",
            CarErrorText.of("Could not establish MANUAL hotspot: Manual hotspot SSID is not configured"),
        )
        assertEquals(
            "系统没有给出可用的热点名称",
            CarErrorText.of(
                "Could not establish LOCAL_ONLY_HOTSPOT hotspot: " +
                    "LocalOnlyHotspot did not report a usable SSID",
            ),
        )
    }

    @Test
    fun `包装层被剥掉_内层前缀条目能命中并保留参数`() {
        assertEquals(
            "系统返回了不支持的热点加密方式（7）",
            CarErrorText.of(
                "Could not establish LOCAL_ONLY_HOTSPOT hotspot: " +
                    "Unsupported LocalOnlyHotspot security type: 7",
            ),
        )
    }

    // ------------------------------------------------------------ 包装层：LOH 两条专用文案

    @Test
    fun `系统热点超时给出可执行的中文原因`() {
        // 实车原文（Android 9 车机：LOH 接口从不回调，白等 60 秒）。
        val text = CarErrorText.of(
            "Could not establish LOCAL_ONLY_HOTSPOT hotspot: " +
                "Timed out after 60000ms waiting for LocalOnlyHotspot",
        )
        assertTrue("应指出是系统热点超时：$text", text!!.contains("系统热点"))
        assertTrue("应指出车机不支持：$text", text.contains("不会回调"))
        assertTrue("应给出替代方案：$text", text.contains("ADB 独立热点"))
    }

    @Test
    fun `系统热点残留请求给出可执行的中文原因`() {
        // 实车原文：上一次超时留下的系统请求没有释放 ⇒ 本进程内后续每次都失败。
        val text = CarErrorText.of(
            "Could not establish LOCAL_ONLY_HOTSPOT hotspot: " +
                "Caller already has an active LocalOnlyHotspot request",
        )
        assertTrue("应指出是残留请求：$text", text!!.contains("残留"))
        assertTrue("应指出本进程内不可用：$text", text.contains("本进程"))
    }

    @Test
    fun `超时那条不会被蓝牙的超时前缀抢走`() {
        // "Timed out after " 是蓝牙与 LOH 共有的前缀，只有带上包装层才能区分。
        // 这里正面确认 LOH 那条走的是热点文案，而不是蓝牙那条。
        val text = CarErrorText.of(
            "Could not establish LOCAL_ONLY_HOTSPOT hotspot: " +
                "Timed out after 60000ms waiting for LocalOnlyHotspot",
        )
        assertTrue("不应被当成蓝牙超时：$text", !text!!.contains("蓝牙"))
    }

    // ------------------------------------------------------------ PATCH-0064：Wi-Fi Direct

    @Test
    fun `Wi-Fi Direct 上电失败给出可执行的中文原因`() {
        // 真车日志（诊断包 1791546694008）里车机 Wi-Fi 平时就是关的，而 Wi-Fi Direct 走
        // Android 框架 ⇒ 现在由 DBPlay 自己打开。文案必须反映这一点，不能再让用户
        // "先去打开 Wi-Fi"（车机上根本没有那个入口）。
        val rejected = CarErrorText.of(
            "Could not establish WIFI_P2P_LEGACY hotspot: " +
                "Wi-Fi Direct could not turn on the head unit's Wi-Fi station",
        )
        assertTrue("应指出是打不开车机 Wi-Fi：$rejected", rejected!!.contains("无法打开车机 Wi-Fi"))

        val timedOut = CarErrorText.of(
            "Could not establish WIFI_P2P_LEGACY hotspot: " +
                "Wi-Fi Direct did not see the head unit's Wi-Fi turn on within 20000ms (state=2)",
        )
        assertTrue("应指出是上电超时：$timedOut", timedOut!!.contains("超时"))
        assertTrue("不应被当成蓝牙超时：$timedOut", !timedOut.contains("蓝牙"))

        val retried = CarErrorText.of(
            "Could not establish WIFI_P2P_LEGACY hotspot: " +
                "Wi-Fi P2P createGroup failed after 3 attempts: Wi-Fi P2P is busy",
        )
        assertTrue("应指出是建组连续失败：$retried", retried!!.contains("建组连续失败"))
    }

    @Test
    fun `Wi-Fi Direct 兜底不再要求用户自己去开 Wi-Fi`() {
        val text = CarErrorText.of("Could not establish WIFI_P2P_LEGACY hotspot: 某个新原因")
        assertTrue("应指出是 Wi-Fi Direct：$text", text!!.contains("Wi-Fi Direct"))
        assertTrue(
            "兜底应说明 DBPlay 已经尝试自动打开车机 Wi-Fi（否则用户会去做一件做不到的事）：$text",
            text.contains("自动打开"),
        )
    }

    // ------------------------------------------------------------ PATCH-0067：P2P 两条可行动失败

    /**
     * 「建组连续失败」这句原本把三种完全不同的原因揉在一起（缺固件特性 / 定位没开 /
     * 被别的应用占着），用户看完不知道该做什么。现在前两种有各自的独立文案。
     *
     * ⚠️ 这两条必须走**同一层包装**（`Could not establish … hotspot: <内层>`），
     * 因为内层文案是在 manager 里构造、由上游包一层之后再交给 `CarErrorText` 的。
     */
    @Test
    fun `Wi-Fi Direct 缺固件特性时指向改用别的热点方式`() {
        val text = CarErrorText.of(
            "Could not establish WIFI_P2P_LEGACY hotspot: " +
                "Wi-Fi Direct is not supported by this head unit",
        )
        assertTrue("应指出车机不支持：$text", text!!.contains("没有 Wi-Fi Direct 能力"))
        assertTrue("应给出可行动方向：$text", text.contains("自建热点"))
    }

    @Test
    fun `Wi-Fi Direct 定位关着时指向车机设置`() {
        val text = CarErrorText.of(
            "Could not establish WIFI_P2P_LEGACY hotspot: " +
                "Wi-Fi Direct needs the head unit's location switch to be on",
        )
        assertTrue("应指出定位开关：$text", text!!.contains("定位开关"))
        assertTrue("应说明去处：$text", text.contains("车机设置"))
    }

    /**
     * 真车日志 `1791626784246`：三条前提**全部满足**、框架仍一直回 BUSY ×3。
     * 这句必须与"被别的应用占用"区分开 —— 后者会让用户去查一个查不出结果的方向。
     */
    @Test
    fun `Wi-Fi Direct 前提全满足却仍忙时指向换热点方式`() {
        val text = CarErrorText.of(
            "Could not establish WIFI_P2P_LEGACY hotspot: " +
                "Wi-Fi Direct is not usable on this head unit",
        )
        assertTrue("应指出是服务不可用：$text", text!!.contains("Wi-Fi Direct 服务不可用"))
        assertTrue("应给出可行动方向：$text", text.contains("自建热点"))
        assertTrue("不应把用户引向'被别的应用占用'：$text", !text.contains("占用"))
    }

    /**
     * 2026-10-11 真车：P2P 网卡 `p2p0` 一直 DOWN（框架的 P2P 没起来）。
     * 车主口径：「**P2P 在 DBPlay 无法临时组网**」——这条文案要直接给出可行动结论，
     * 而不是让用户反复重试。
     */
    @Test
    fun `Wi-Fi Direct 没在运行时直接指向换热点方式`() {
        val text = CarErrorText.of(
            "Could not establish WIFI_P2P_LEGACY hotspot: " +
                "Wi-Fi Direct is not running on this head unit",
        )
        assertTrue("应指出 P2P 没在跑：$text", text!!.contains("Wi-Fi Direct 服务没有在运行"))
        assertTrue("应指出可核对的旁证：$text", text.contains("p2p0"))
        assertTrue("应给出可行动方向：$text", text.contains("自建热点"))
    }

    // ------------------------------------------------------------ 包装层：兜底

    @Test
    fun `包装层内层也认不出来时按模式给兜底`() {
        val loh = CarErrorText.of("Could not establish LOCAL_ONLY_HOTSPOT hotspot: 某个新原因")
        assertTrue("应指出是系统热点：$loh", loh!!.contains("系统热点"))

        val manual = CarErrorText.of("Could not establish MANUAL hotspot: 某个新原因")
        assertTrue("应指出是接管车机热点：$manual", manual!!.contains("无法接管车机自带热点"))

        val p2p = CarErrorText.of("Could not establish WIFI_P2P_LEGACY hotspot: Wi-Fi P2P is busy")
        assertTrue("应指出是 Wi-Fi Direct 临时组网：$p2p", p2p!!.contains("Wi-Fi Direct"))

        // 内层原文**不许**附在括号里漏到界面上（本文件的既定原则：
        // 宁可少一句精确原因，也不要漏出英文）。内层原文在日志里能查到。
        val englishInner = CarErrorText.of(
            "Could not establish MANUAL hotspot: some brand new upstream reason",
        )
        assertTrue(
            "不应漏出英文原文：$englishInner",
            !englishInner!!.contains("brand new upstream reason"),
        )
    }

    @Test
    fun `包装层内层带换行也能剥掉`() {
        // 上游内层原因里可能带换行，正则必须用 DOT_MATCHES_ALL；
        // 否则 `.` 不匹配换行 ⇒ 整条落不到剥壳分支，只能吃通用兜底。
        val text = CarErrorText.of(
            "Could not establish LOCAL_ONLY_HOTSPOT hotspot: 第一行原因\n第二行细节",
        )
        assertTrue("应仍按系统热点兜底：$text", text!!.contains("系统热点"))
    }

    @Test
    fun `认不出的模式名退回通用兜底`() {
        assertEquals(
            CarErrorText.FALLBACK,
            CarErrorText.of("Could not establish SOMETHING_NEW hotspot: 未知原因"),
        )
    }
}
