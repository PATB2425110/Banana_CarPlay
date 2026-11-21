package com.dbtool.carplay.bridge.hotspot

import android.content.Context
import com.dbtool.carplay.api.hotspot.CarAdbHotspotHost
import com.dbtool.carplay.api.hotspot.CarHotspotResidue
import com.dbtool.carplay.api.settings.CarHotspotMode

/**
 * 「热点模式切换」时的**初始化器**：按 `from → to` 做差异化清理与重采样。
 *
 * ## 为什么需要它
 *
 * 自建热点（[CarHotspotMode.ADB_HOSTAPD]）与复用类热点（[CarHotspotMode.MANUAL] /
 * [CarHotspotMode.TCAM_HOTSPOT]）里，**只有自建会往 `wlan0` 上配地址**。而 DBPlay 从
 * "自建"切到"复用"时
 * **从不清理自建留下的东西** —— 切换只走 `applySettings()` 的
 * `refreshHotspotHint() + reconnect()`，全程不碰热点实体。
 *
 * ## `from → to` 清理矩阵（2026-10-16 纳入 TCAM）
 *
 * | from → to | 要清什么 | 谁来做 |
 * | --- | --- | --- |
 * | `ADB_HOSTAPD → 任何`（含 `TCAM_HOTSPOT`） | 自建留下的网卡地址 + hostapd/udhcpd 占用者 | [stopSelfBuiltAp] |
 * | `TCAM_HOTSPOT → 任何` | **无地址可清**：TCAM 只读 `eth0.11`、从不配置地址；其 NetworkCallback 由 `CarPlaySessionBridge.closeTcamHotspot` 回收，不在本类 | 仅走下方通用残留清扫 |
 * | `SAME_LAN ↔ TCAM_HOTSPOT` | **无共用资源**：SAME_LAN 读 `wlan*` 的 STA 地址，TCAM 读 `eth0.11`，接口/地址/监听都不相交 | 无 |
 *
 * 因此本类**只对 `from == ADB_HOSTAPD` 做实体清理**，其余切换一律只做"检出并清扫
 * 自建残留"（[detectResidue] + `adbHotspot?.releaseLeftoverAddress()`）。TCAM 是
 * "复用"语义（与 MANUAL 同类），从不留下地址，不需要、也不允许为此新起清理机制。
 *
 * 后果（真车日志，设备 `e3de67df72a0`，2026-10-09 09:33；2026-10-11 08:57 复现）：
 *
 * ```
 * ① 先用「自建热点」      → wlan0 上被配了 192.168.176.1（我们 planner 的值）
 * ② 切到「复用车机热点」  → 那个地址还留着
 * ③ 复用分支把"网上碰到的地址"当成车机热点地址
 * ④ 把 SSID + 192.168.176.1 通告给 iPhone
 * ⑤ 车机热点在别的网段    ⇒ iPhone 到不了 ⇒ 40s attach timed out
 * ```
 *
 * ⇒ 切换模式时必须先"把上一个模式的地扫干净"，这就是本类的全部职责。
 *
 * ## ⚠️ 2026-10-11：残留判据必须用「**我们实际配上去的那个地址**」
 *
 * 上一版拿 `CarAdbHotspotPlanner.plan(...)` **这一刻重算出的**网关地址去网卡上找。
 * 而 planner 是**冲突规避**选网段的：上一轮自建占了 176，这一轮它就改选 175 ——
 * 于是判据永远在找一个"已经不在了"的地址：
 *
 * ```
 * 08:52 自建 → wlan0 = 192.168.175.1
 * 08:55 自建 → 175 被上一轮占着，改选 → wlan0 = 192.168.176.1
 * 08:57 切复用 → planner 算出 175 → ["原本无残留"]（网上躺着的是 176）
 *               → 复用分支读到 192.168.176.1 并通告给 iPhone
 * 08:58:17     wireless attach timed out: iPhone did not open the AirPlay connection（40000ms）
 * ```
 *
 * ⇒ 现在改用 **[selfBuiltAddresses]**（`CarAdbHotspotState.hostAddress`：运行时实际
 * `ifconfig` 上去的那个地址）做判据与等待对象。**等不到不是空转** —— 等的是真地址，
 * 所以 teardown 一删掉它就放行。
 *
 * ⚠️ 2026-10-11（PATCH-0086）起，**候选网段判据作为兜底**（见
 * [awaitResidueCleared] 的 `poolClear`）：原来不能按池判，是因为不知道车机在哪个网段、
 * 池里的 `192.168.175.0/24` 有可能正是车机自己；现在车主给了固定网段
 * `192.168.15.0/24`，与池不相交 ⇒ 按池判安全，且能覆盖"足迹丢失（进程被杀过）"那种残留。
 * 判据仍是"足迹地址 + 池"两条都要清（详见 [CarHotspotResidue] 类注释）。
 *
 * 同一次切换还会把「车机热点网口」的识别结论写进日志（[CarNetworkInterfaces.debugLine]）——
 * 复用能不能通全看它认得对不对，而这条信息过去在日志里根本没有。
 *
 * ## ⚠️ 它不是"把两种热点都起来"
 *
 * 两种模式在 `wlan0` 上**互斥**，同时准备只会加剧冲突。本类只在
 * **模式真的变了**（或首次启动）时跑，且**只做与 [from] 相关的清理**。
 *
 * ## 线程约定（与 [CarAdbHotspotHost] 一致）
 *
 * [onModeChanged] 里可能调 [CarAdbHotspotHost.stop]（非阻塞）并**轮询确认**，
 * 因此**只能在 worker 线程调用**。`stop()` 内部把活儿投到自己 worker，
 * 而这里的轮询是"读网卡状态"，很轻。
 *
 * ⚠️ 可见性是 **public**（不是 internal）：[Result] 要出现在
 * `CarAppServices.onHotspotModeChanged` 的返回类型上，而 Kotlin 不允许
 * public 函数暴露 internal 类型。本类只被 `:car:core-bridge` 内部与
 * 应用层经 `CarAppServices` 使用，public 只是为了让签名能成立。
 */
class CarHotspotModeInit(
    private val context: Context,
    private val adbHotspot: CarAdbHotspotHost?,
    private val diagnostics: (String) -> Unit,
) {

    /**
     * 一次模式切换做了什么。**纯数据，可单测**（本类本身的实体部分要 Context，
     * 但这份结论的构造与判读不需要）。
     */
    data class Result(
        /** 是否拆了自建热点（只有 `ADB_HOSTAPD → 别的` 才可能为 true）。 */
        val stoppedSelfBuiltAp: Boolean = false,
        /** 拆完是否**确认**网卡上的残留网关地址已消失。 */
        val residueCleared: Boolean = false,
        /** 切换前检出的残留地址（没有则为 null）。 */
        val leftoverAddress: String? = null,
        /** 给日志/界面的一句话，可为空。 */
        val note: String = "",
    )

    /**
     * 模式切换入口。
     *
     * @param from 切换前的模式；首次启动（无"前一个模式"）传 null
     * @param to 切换后的模式
     * @return 本次做了什么；调用方应当把 [Result.note] 写进诊断日志
     */
    fun onModeChanged(from: CarHotspotMode?, to: CarHotspotMode): Result {
        // 同模式 no-op：用户改个无关开关（分辨率、音量…）会一路调到 applySettings，
        // 若每次都"清一遍再拉起"，热点会被无谓地重启 ⇒ 连接被打断。
        if (from == to) return Result(note = "热点模式未变（$to），跳过初始化")

        // 顺手把「车机热点网口」认出来写进日志（PATCH-0068，2026-10-11）。
        //
        // 复用分支能不能通，全看这个识别对不对：真车日志 08:57 那次把**自建残留**的
        // `192.168.176.1` 当成了车机热点地址通告给 iPhone ⇒ 40 秒 attach 超时。
        // 这里只**记录结论**（不拦截，拦截在 `CarPlaySessionBridge.ensureManualHotspotReady`），
        // 并把它写进诊断包 —— 下次再看这类问题，一行就知道"认的是哪张网口"。
        diagnostics("Cluster-hotspot: ${CarNetworkInterfaces.debugLine(context, selfBuiltAddresses())}")

        // ⚠️ 核心分支：从**自建**切走 —— 必须先拆掉我们自己的 AP，
        // 否则残留网关地址会被复用分支误认成"车机热点"（见类注释那条链）。
        //
        // 反过来（复用 → 自建）不需要清理：复用从不 startSoftAp，
        // 它只读，网卡上没有属于我们的东西。这正是"清理动作与 from 强相关"。
        //
        // 2026-10-16 纳入 TCAM：`ADB_HOSTAPD → TCAM_HOTSPOT` 与其它目标一样走这里——
        // TCAM 复用在 `eth0.11` 上自动识别地址，若 wlan0 还躺着自建残留就会被误通告给
        // iPhone（到不了 AirPlay 7000），所以"先拆自建"对 TCAM 同样成立。
        if (from == CarHotspotMode.ADB_HOSTAPD) {
            return stopSelfBuiltAp(to)
        }

        // 其余切换（含冷启动 from == null）：只做一次"残留检出"，
        // **不擅自清理** —— 真的检出残留才需要动手，而动手是实体操作。
        //
        // `TCAM_HOTSPOT → 任何` / `SAME_LAN ↔ TCAM_HOTSPOT` 也落在这里：TCAM 只读
        // `eth0.11`、SAME_LAN 只读 `wlan*` 的 STA 地址，两者都不配置地址，也没有共用
        // 接口/监听 ⇒ 无需实体清理；下面只兜底清扫"自建残留"。
        val residue = detectResidue()

        // 🧹 2026-10-11（PATCH-0068）：**各模式必须互不污染**。
        //
        // 上面那条"不擅自清理"的纪律，代价是"进程被杀后留下的地址"没人管 ——
        // 而那种残留正是复用分支会当成车机热点地址的东西（真车 08:57 的 176.1）。
        // 现在：只要有**落盘足迹**证明那个地址是我们留的，就用 **ADB 工具**把它清掉
        // （ADB 是本工程的共享设施，不属于任何单个模式），并如实写进日志。
        val swept = runCatching { adbHotspot?.releaseLeftoverAddress() }.getOrNull()
        if (swept != null) diagnostics("Cluster-hotspot: $swept")

        return when {
            residue.detected -> Result(
                leftoverAddress = residue.leftoverAddress,
                note = residue.note.orEmpty() +
                    (swept?.let { "（本次已处理：$it）" } ?: "") +
                    "（本次切换自 ${from ?: "启动"}，未自动清理；" +
                    "若复用仍失败，请先「手动关闭热点」再连接）",
            )
            else -> Result(
                note = "热点模式切换：${from ?: "启动"} → $to（无残留）" +
                    (swept?.let { "；$it" } ?: ""),
            )
        }
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 拆掉自建热点，并**确认**网卡上的残留地址真的消失了。
     *
     * ## 为什么要"确认"而不是"发完命令就走"
     *
     * [CarAdbHotspotHost.stop] 的契约是"**非阻塞**——内部提交到 worker 线程"
     * （见其 KDoc：会话收尾路径上不能等它，否则返回上一页会卡十几秒）。
     * 而调用方**紧接着**就要 resolve 热点方案，会被残留地址误导 ——
     * 2026-10-11 08:57 的复现就是"stop() 已发出、复用已经在读网卡"：
     * `08:57:36.963 原本无残留` ⇒ `08:57:37.134 仍读到 192.168.176.1`。
     *
     * ⇒ 这里发完 `stop()` 后轮询网卡，直到**我方候选网段里的地址**全部消失
     * （或有超时）。**等不到不抛异常**：如实记进 [Result.residueCleared]，
     * 由调用方决定（与 `CarHotspotEnabledState.UNKNOWN` 一律放行同一条纪律：
     * 清理没确认成功，不该把用户的连接直接判死）。
     */
    private fun stopSelfBuiltAp(to: CarHotspotMode): Result {
        val before = detectResidue()
        // 我们实际配上去的那个地址（`CarAdbHotspotState.hostAddress`）。
        // **必须在 `host.stop()` 之前取**：tearDown 之后 activeNetwork 会被置空。
        val configuredAddress = selfBuiltAddresses().firstOrNull()
        // 抓成本地 val：`adbHotspot` 是构造参数（`private val`），Kotlin 不对它做智能转换。
        val host = adbHotspot
        if (host == null) {
            return Result(
                leftoverAddress = before.leftoverAddress,
                note = "热点模式切换：自建 → $to（无 ADB 热点宿主，未做清理）",
            )
        }

        diagnostics("Cluster-hotspot: 切换模式 自建 → $to，正在拆掉自建热点…")
        runCatching { host.stop() }.onFailure { error ->
            diagnostics("Cluster-hotspot: 拆自建热点抛异常：${error.message}")
        }

        val cleared = awaitResidueCleared(configuredAddress)
        // 兜底：等不到（teardown 没删掉 / 地址又被谁加回来）时，用 **ADB 工具**再清一次 ——
        // 各模式必须互不污染：留着它，复用就会把我们的地址当成车机热点通告出去。
        val swept = if (configuredAddress != null && !cleared) {
            runCatching { host.releaseLeftoverAddress() }.getOrNull()?.also {
                diagnostics("Cluster-hotspot: $it")
            }
        } else {
            null
        }
        val clearedAfterSweep = cleared || (configuredAddress != null &&
            CarNetworkInterfaces.addressPresentAnywhere(context, configuredAddress) == null)
        val note = when {
            !before.detected -> "热点模式切换：自建 → $to（原本无残留）"
            clearedAfterSweep ->
                "热点模式切换：自建 → $to，已拆掉自建热点并确认残留地址 " +
                    "${before.leftoverAddress} 已释放" + (swept?.let { "（$it）" } ?: "")
            else ->
                "⚠️ 热点模式切换：自建 → $to，已发拆除命令，但 ${RESIDUE_WAIT_MS}ms 内" +
                    "未确认残留地址 ${before.leftoverAddress} 消失（$swept）—— " +
                    "复用可能仍会把该地址通告给 iPhone（请重试，或用「手动关闭热点」）"
        }
        diagnostics("Cluster-hotspot: $note")
        return Result(
            stoppedSelfBuiltAp = true,
            residueCleared = clearedAfterSweep,
            leftoverAddress = before.leftoverAddress,
            note = note,
        )
    }

    /**
     * 轮询等待**我们自建时配上去的那个地址**消失。
     *
     * ⚠️ 2026-10-11 改判据：原来等的是"**现算的** planner 网关地址"，而 planner
     * 每轮会换网段（冲突规避）⇒ 它等的是一个"本来就不在"的地址，**立刻返回 true**，
     * 于是复用分支在自建地址还没被异步 teardown 删掉时就开跑，读到了残留地址
     * （真车日志 08:57:36.963 "原本无残留" → 08:57:37.134 仍读到 192.168.176.1）。
     *
     * 现在等的是**实际配过的那个地址**（[selfBuiltAddresses]，来自
     * `CarAdbHotspotState.hostAddress`）。
     *
     * @return true = 确认已消失（或**本来就没有**）；false = 超时仍在。
     */
    private fun awaitResidueCleared(configuredAddress: String?): Boolean {
        val deadline = System.nanoTime() + RESIDUE_WAIT_MS * 1_000_000L
        while (System.nanoTime() < deadline) {
            // 判据 =「我方候选网段里的地址一个都不剩」。它同时覆盖两种情况：
            // ① 落盘足迹记下的那个地址（`configuredAddress`，最准）；
            // ② 足迹丢失（进程被杀过）但网上还躺着候选网段里的地址。
            // 车主口径 2026-10-11：车机固定 192.168.15.0/24，与我们的池不相交 ⇒ 按池判不会误伤车机。
            val poolClear = CarNetworkInterfaces.selfPoolAddressAnywhere(context) == null
            val recordedClear = configuredAddress == null ||
                CarNetworkInterfaces.addressPresentAnywhere(context, configuredAddress) == null
            if (poolClear && recordedClear) return true
            runCatching { Thread.sleep(RESIDUE_POLL_MS) }
        }
        return CarNetworkInterfaces.selfPoolAddressAnywhere(context) == null
    }

    /**
     * 检出「自建残留」（见 [CarHotspotResidue]）。
     *
     * 🔴 2026-10-11 的关键修正：判据的 `plannedGateway` 必须是
     * **我们实际 `ifconfig` 上去的那个地址**（[selfBuiltAddresses]），
     * **不是**"当场重算的 planner 网关" —— planner 是冲突规避的，每轮会换网段，
     * 拿现算值去比对等于永远找不到真残留（详见 [CarHotspotResidue] 的类注释）。
     */
    private fun detectResidue(): CarHotspotResidue.Result {
        val configured = selfBuiltAddresses().firstOrNull().orEmpty()
        val iface = selfBuiltInterfaceName() ?: DEFAULT_INTERFACE
        val addresses = CarNetworkInterfaces.ipv4On(iface)
        if (configured.isNotEmpty()) {
            val exact = CarHotspotResidue.detect(iface, addresses, configured)
            if (exact.detected) return exact
        }
        // 没有足迹（进程被杀过）或足迹地址已不在：按"我方候选网段"判（更宽的兜底）。
        return CarHotspotResidue.detectWithinSubnets(iface, addresses)
    }

    /**
     * 我们自建热点**实际配上去**的地址（0 或 1 个）。
     *
     * 来源是 [CarAdbHotspotHost.state] 的 `hostAddress`（"实际配置到网卡上的地址，
     * **运行时选出来的**"）。拿不到（进程重启后没有记录）时返回空表 ⇒ 判据返回
     * [CarHotspotResidue.Result.NONE]，**不猜**。
     */
    private fun selfBuiltAddresses(): List<String> {
        val address = runCatching { adbHotspot?.state()?.hostAddress }.getOrNull()?.trim().orEmpty()
        return if (address.isEmpty()) emptyList() else listOf(address)
    }

    /** 我们自建热点实际用的网卡名（`CarAdbHotspotState.interfaceName`）。 */
    private fun selfBuiltInterfaceName(): String? =
        runCatching { adbHotspot?.state()?.interfaceName }.getOrNull()?.takeIf { it.isNotBlank() }

    private companion object {
        /** 与 `CarAdbHotspotPlanner` 的参考实现一致（本车 `wlan0`）。 */
        const val DEFAULT_INTERFACE = "wlan0"

        /** 等残留地址释放的总时长。`stop()` 是异步的，实测需要几百毫秒级。 */
        const val RESIDUE_WAIT_MS = 3_000L

        /** 轮询间隔。 */
        const val RESIDUE_POLL_MS = 100L
    }
}
