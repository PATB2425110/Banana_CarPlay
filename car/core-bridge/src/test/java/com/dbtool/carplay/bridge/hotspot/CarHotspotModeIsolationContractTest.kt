package com.dbtool.carplay.bridge.hotspot

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 契约测试：**各热点模式彼此独立、互不污染；但 ADB 工具可被任何模式使用**
 * （2026-10-11，PATCH-0068，车主口径）。
 *
 * ## 三种模式各自会动什么
 *
 * | 模式 | 会在网卡/系统上留下什么 |
 * | --- | --- |
 * | `ADB_HOSTAPD` 自建 | **地址**（`ifconfig` 上去的网关）+ hostapd/udhcpd 进程 + 策略路由 |
 * | `MANUAL` 复用 | 什么都不建（只读车机那张 AP） |
 * | `SAME_LAN` | 什么都不建 |
 *
 * ⚠️ 2026-10-13（PATCH-0113）：原 `P2P_DIRECT`（Wi-Fi Direct 临时组网）一档已整体移除，
 * 上表随之从四种收窄到三种。
 *
 * ## 钉死的三条
 *
 * 1. **不得替别的模式起东西**：`ensureAdbHotspot` 只在 `ADB_HOSTAPD` 时才拉起我们的 AP；
 *    `ensureManualHotspotReady` 只在 `MANUAL` 时才做校验 ⇒ 复用/局域网不会
 *    "顺手"把自建热点拉起来。
 * 2. **切换时必须把上一个模式的地扫干净**：自建 → 任何（`stopSelfBuiltAp` + 用 ADB 工具兜底清理）；
 *    且有**落盘足迹**撑腰的 `releaseLeftoverAddress()`（进程被杀后留下的地址也认得出、清得掉）。
 * 3. **不得读取别人的残留当自己的输入**：复用分支要用 `CarAdbHotspotState.hostAddress`
 *    （实际配过的那个地址）把我方地址排除在外，**不得**用"现算的 planner 地址"。
 *
 * ## 为什么静态读源码
 *
 * 这条链要 `Context` + 真网卡 + 真 `adb` + 真车机热点，JVM 单测跑不了；而它必须被钉住。
 * 静态读法与本目录 [CarHotspotModeInitContractTest] / [HotspotReuseContractTest] 一致。
 */
class CarHotspotModeIsolationContractTest {

    private fun sourceOf(relative: String): String {
        val candidates = listOf(
            File(relative),
            File("../$relative"),
            File("../../$relative"),
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("找不到源码文件 $relative（从 ${File(".").absolutePath} 起找过 ${candidates.size} 个位置）")
        return file.readText()
    }

    private val bridgeSrc = sourceOf(
        "src/main/java/com/dbtool/carplay/bridge/CarPlaySessionBridge.kt",
    )
    private val modeInitSrc = sourceOf(
        "src/main/java/com/dbtool/carplay/bridge/hotspot/CarHotspotModeInit.kt",
    )
    private val managerSrc = sourceOf(
        "src/main/java/com/dbtool/carplay/bridge/hotspot/CarAdbHotspotManager.kt",
    )
    private val footprintSrc = sourceOf(
        "src/main/java/com/dbtool/carplay/bridge/hotspot/CarHotspotFootprintStore.kt",
    )
    private val resolverSrc = sourceOf(
        "src/main/java/com/dbtool/carplay/bridge/network/CarHotspotResolver.kt",
    )
    private val p2pSrc = sourceOf(
        "../../shared/src/main/java/com/shilapi/xcertplay/network/LegacyWifiP2pGroupManager.kt",
    )

    // ---------------------------------------------------- ① 不替别的模式起东西

    @Test
    fun `自建热点只在 ADB_HOSTAPD 模式下才会被拉起`() {
        val body = bridgeSrc.substringAfter("private fun ensureAdbHotspot(", "")
        assertTrue("ensureAdbHotspot 不见了", body.isNotEmpty())
        assertTrue(
            "必须在函数开头 `if (plan.mode != CarHotspotMode.ADB_HOSTAPD) return plan`：" +
                "否则切到「复用」或「局域网」时会顺手把我们自己的 AP 拉起来，两种模式在同一张网卡上打架。",
            body.substring(0, 400).contains("plan.mode != CarHotspotMode.ADB_HOSTAPD"),
        )
    }

    @Test
    fun `复用前置校验只在 MANUAL 模式下才跑`() {
        val body = bridgeSrc.substringAfter("private fun ensureManualHotspotReady(", "")
        assertTrue("ensureManualHotspotReady 不见了", body.isNotEmpty())
        assertTrue(
            "必须 `if (plan.mode != CarHotspotMode.MANUAL) return plan` —— 这条校验是复用专属的，",
            body.substringBefore("\n").contains("plan.mode != CarHotspotMode.MANUAL") ||
                body.substring(0, 400).contains("plan.mode != CarHotspotMode.MANUAL"),
        )
    }

    @Test
    fun `局域网单独成支，不会落进自建分支`() {
        assertTrue(
            "SAME_LAN 必须在 explicit() 里单独 return（它一张热点都不建）",
            resolverSrc.contains("if (settings.hotspotMode == CarHotspotMode.SAME_LAN)"),
        )
        // ⚠️ 2026-10-13（PATCH-0113）：原来这里还钉一条 `P2P_DIRECT` 同样单独成支，
        // 随该模式一并移除。
    }

    // ---------------------------------------------------- ② 切换时扫干净

    @Test
    fun `自建地址有落盘足迹，进程被杀后也认得出`() {
        assertTrue(
            "足迹存储必须存在（内存里的 activeNetwork 在进程被杀后就不存在了）",
            footprintSrc.contains("object CarHotspotFootprintStore"),
        )
        assertTrue(
            "自建开始建网时必须写足迹（网卡/地址/前缀长度）",
            managerSrc.contains("CarHotspotFootprintStore.save("),
        )
        assertTrue(
            "拆除后确认地址真的没了才清足迹",
            managerSrc.contains("CarHotspotFootprintStore.clear("),
        )
        assertTrue(
            "清理动作必须走 ADB 工具（ADB 是本工程共享设施，不属于任何单个模式）",
            managerSrc.contains("/system/bin/ip addr del") &&
                managerSrc.contains("override fun releaseLeftoverAddress("),
        )
    }

    @Test
    fun `任何一次模式切换都会顺手处理自建残留`() {
        assertTrue(
            "冷启动/非自建分支也必须调用 releaseLeftoverAddress()（那是「进程被杀留下」的唯一清理点）",
            modeInitSrc.contains("adbHotspot?.releaseLeftoverAddress()"),
        )
        assertTrue(
            "自建 → 别的：等不到释放时要用 ADB 工具兜底清一次",
            modeInitSrc.substringAfter("private fun stopSelfBuiltAp(", "")
                .contains("host.releaseLeftoverAddress()"),
        )
    }

    // ---------------------------------------------------- ③ 不读别人的残留

    @Test
    fun `复用分支：地址由用户填，残留只告警`() {
        assertTrue(
            "残留识别必须用实际配过的地址（CarAdbHotspotState.hostAddress），不能用现算的 planner 值",
            bridgeSrc.contains("adbHotspot?.state()?.hostAddress"),
        )
        // ⚠️ 2026-10-11（PATCH-0087）改判：残留**不再拦人**，只告警。
        //
        // 上一版（PATCH-0086）把"网上还躺着我方残留"当阻塞 —— 那时我们**自己猜**通告地址，
        // 残留会把 iPhone 引到错地址，拦得对。现在复用的地址**由用户填**（以它为准），
        // 残留不再改变我们通告什么 ⇒ 拦它就变成了无谓的挡路；但留着它是**有用的信号**
        // （说明自建热点没被拆干净），所以保留一条告警。
        assertTrue(
            "检出残留要留在诊断与界面日志里（只是不再阻塞）",
            bridgeSrc.contains("manual hotspot precheck warn: self residue"),
        )
        assertTrue(
            "残留那一段里不得再出现 throw —— 复用的阻塞项只剩「用户填的地址不在网上」",
            bridgeSrc.substringAfter("if (residue != null) {", "")
                .substringBefore("manual hotspot precheck warn")
                .contains("throw").not(),
        )
        // ⚠️ 2026-10-11（PATCH-0086）改判：候选池判据**现在是允许的**（作为兜底），
        // 因为车主给了车机固定网段 192.168.15.0/24，与我们的池（175~179 / 172.20 / 10.42）
        // 不相交 ⇒ 落在池里的一定是我们自建留下的。但仍必须是**兜底**：先认足迹地址。
        val residueBranch = bridgeSrc.substringAfter("val residue = (", "")
            .substringBefore("if (residue != null)")
        assertTrue(
            "主判据仍必须是「实际配过的那个地址」，池子只做足迹丢失后的兜底",
            // 2026-10-13：两个调用多了一个 `interfaces` 实参（预检共用同一份网卡快照，见
            // `ensureManualHotspotReady`），先认足迹地址、池子只做兜底的顺序没变，所以只匹配前缀。
            residueBranch.contains("addressPresentAnywhere(appContext, selfBuiltAddress") &&
                residueBranch.contains("selfPoolAddressAnywhere(appContext"),
        )
    }

    /**
     * 🔴 2026-10-11（PATCH-0087）：**复用的地址由用户填**，不是我们认。
     *
     * 车主口径：*"复用车机热点是要用户自己手动设置，而不是自动识别"*。
     *
     * 这条把两件事钉住：① 空地址在**连接前**就被拦下（文案由 `CarManualHotspotPrecheck`
     * 那个纯函数给，设置页与这里同源）；② 填了地址就**核它在不在网上**，
     * 不在就给出可执行的结论，而不是让 iPhone 白等 40 秒。
     *
     * 上一版（PATCH-0086）是"我们认网口、认不出就拦"，方向错了：两次认错
     * （`1791624952142` 通告了自建残留、`1791702691839` 把残留写进诊断）都是这么来的。
     *
     * ⚠️ 2026-10-12（PATCH-0099）：判据的**输入**从 `plan` 改成 `effectivePlan`
     * （= `plan`，或在 Ecarx 上被一条已验证客户端路由覆写 endpoint 后的副本）。
     * "以用户填的地址为准"这条口径没变 —— 变的只是 Ecarx 上多了一个**严格验证过**的
     * 覆写来源；两条路都不再"按网卡名猜"。所以下面钉的是同一件事的新写法，
     * 并补上 Ecarx 严格匹配**失败不回退**这一条。
     */
    @Test
    fun `复用的地址由用户填：空则拦下，填了则核对它在不在网上`() {
        assertTrue(
            "空地址必须在连接前拦下（判据与设置页同源：CarManualHotspotPrecheck.blocker）",
            bridgeSrc.contains("hostAddress = effectivePlan.manualHostAddress") &&
                // ⚠️ 2026-10-16：判据从 plan.reused 改成 mode == MANUAL。TCAM 档的
                // `reused` 同样是 true，但它的地址由 `TcamHotspotManager` 自动识别，
                // 照 `reused` 传会让预检误报「复用模式需要填地址」。
                bridgeSrc.contains("reuseRequested = effectivePlan.mode == CarHotspotMode.MANUAL"),
        )
        assertTrue(
            "填了地址就要核它在不在网上，不在就拦（可执行结论，而不是白等 40 秒）",
            bridgeSrc.contains("manual hotspot precheck blocked: host="),
        )
        assertTrue(
            "放行时要说清「用哪个地址、来自哪」：用户手填 vs Ecarx 已验证路由，两种来源都要标",
            bridgeSrc.contains("manual hotspot endpoint: source=") &&
                bridgeSrc.contains("\"user configuration\"") &&
                bridgeSrc.contains("\"Ecarx verified client route\""),
        )
        assertTrue(
            "只有「复用」这一支（plan.reused=true）才该被这条检查波及",
            bridgeSrc.contains("if (effectivePlan.reused && manualAddress != null)"),
        )
        // 🔴 PATCH-0099：Ecarx 覆写带上接口名时，核对必须**严格到那个接口**（名字 + UP +
        //    持有该 IPv4），失败就抛异常 —— **不得**退回"任意网卡上有没有这个地址"。
        //    否则一条过期路由会让 iPhone 拿到一个本机已经不承载的地址（现象又是 40 秒超时）。
        assertTrue(
            "Ecarx 覆写必须按接口名 + UP + 持有该地址三条件严格核对",
            bridgeSrc.contains("iface.name == manualInterface && iface.isUp &&") &&
                bridgeSrc.contains("iface.addresses.any { it.trim() == manualAddress }"),
        )
        // 把 `val onInterface = if (manualInterface != null) { … } else { … }` 的**两支**切开：
        // 严格支（Ecarx 覆写）不许有任何"任意网卡"兜底，用户支（手填地址）保持原行为。
        val onInterfaceExpr = bridgeSrc
            .substringAfter("val onInterface = if (manualInterface != null) {", "")
            .substringBefore("if (onInterface == null)")
        val strictBranch = onInterfaceExpr.substringBefore("} else {")
        val userBranch = onInterfaceExpr.substringAfter("} else {")
        assertTrue("拿不到 onInterface 的 if/else 两支，断言本身要立得住", onInterfaceExpr.isNotEmpty())
        assertTrue(
            "严格核对失败要当场拦下，不得回退成「任意网卡上找这个地址」",
            strictBranch.contains("addressPresentAnywhere").not(),
        )
        assertTrue(
            "没有接口名（用户手填路径）时才保持原行为：任意网卡上找这个地址",
            userBranch.contains("addressPresentAnywhere(appContext, manualAddress"),
        )
    }

    @Test
    fun `P2P 自己拆自己的组，并归还自己借的 Wi-Fi`() {
        val closeFn = p2pSrc.substringAfter("override fun close()", "")
        assertTrue("close() 不见了", closeFn.isNotEmpty())
        assertTrue(
            "P2P 组必须由它自己拆（removeOwnedGroup）—— 否则下次别家（含车机自己）建组会被残组挡",
            closeFn.substring(0, 900).contains("removeOwnedGroup("),
        )
        assertTrue(
            "自己打开的 Wi-Fi 站必须归还（restoreWifiStation）",
            closeFn.substring(0, 900).contains("restoreWifiStation()"),
        )
    }

    /**
     * 🔴 2026-10-11 车主现场：「点了关闭，手机上**还能搜到**自建热点」。
     *
     * 根因是拆除顺序：只有 `killall -s KILL` 时 hostapd 来不及走 nl80211 `stop_ap`，
     * 进程没了但**驱动仍停在 AP 模式**、beacon 照发。这条契约把正确顺序钉死。
     */
    @Test
    fun `停自建热点必须先体面停止再校验网卡模式`() {
        val tearDown = managerSrc.substringAfter("private fun tearDown()", "")
        assertTrue("tearDown 不见了", tearDown.isNotEmpty())

        val gracefulIdx = tearDown.indexOf("stopHostapdGracefully(")
        val forceIdx = tearDown.indexOf("killHostapdAndUdhcpd(")
        assertTrue(
            "必须先发 SIGTERM（hostapd 才会执行 nl80211 stop_ap、beacon 才停）",
            gracefulIdx >= 0,
        )
        assertTrue(
            "SIGKILL 只能作为兜底，且必须排在 SIGTERM 之后",
            forceIdx > gracefulIdx,
        )
        assertTrue(
            "最后必须校验网卡是否真的退出 AP 模式（否则『应用显示已关闭、手机还搜得到』）",
            tearDown.contains("ensureInterfaceNotAp("),
        )
        assertTrue(
            "网卡仍是 AP 时要强制复位接口（down/up 是唯一能把 SSID 从空中收回的动作）",
            managerSrc.substringAfter("private fun ensureInterfaceNotAp(", "")
                .contains("bounceInterface("),
        )
    }
}
