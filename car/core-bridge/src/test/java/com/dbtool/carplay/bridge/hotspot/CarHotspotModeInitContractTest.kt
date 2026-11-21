package com.dbtool.carplay.bridge.hotspot

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 契约测试：**热点模式切换必须先把上一个模式的地扫干净**
 * （2026-10-09，PATCH-0051）。
 *
 * ## 钉死的是什么
 *
 * 两种模式共用同一张 `wlan0`，而 `CarHotspotMode.ADB_HOSTAPD`（自建）会在网卡上
 * 留下我们 planner 的网关地址（实车 `192.168.176.1`）。切到
 * `CarHotspotMode.MANUAL`（复用）时若不清理，复用分支的
 * `hotspotAlreadyRunningLocally()`（同样是找这个地址）就会**把残留认成车机热点**，
 * 把 `192.168.176.1` 通告给 iPhone ⇒ **40 秒 attach 超时**。
 *
 * 真车日志（设备 `e3de67df72a0`，2026-10-09 09:33）：
 * ```
 * hotspot plan: 复用车机热点「Lynk&Co」（手动指定）
 * wireless hotspot backend=Manual hotspot iface=wlan0 host=192.168.176.1
 * wireless attach timed out: iPhone did not open the AirPlay connection within 40000ms
 * ```
 *
 * ## 为什么静态读源码（§46）
 *
 * 这条链要 `Context` + 真网卡 + 真 `adb`，单测跑不了。而它**必须**被钉住：
 * 它是"机制正确"的唯一保证 —— 只靠代码里一句注释，下次重构就会被删掉。
 * 静态读法与本目录 [HotspotReuseContractTest] 一致。
 */
class CarHotspotModeInitContractTest {

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

    private val initSrc =
        "src/main/java/com/dbtool/carplay/bridge/hotspot/CarHotspotModeInit.kt"

    private val servicesSrc =
        "src/main/java/com/dbtool/carplay/bridge/CarAppServices.kt"

    private val controllerSrc =
        "../../car/app/src/main/java/com/dbtool/carplay/app/CarAppController.kt"

    private val bridgeSrc =
        "src/main/java/com/dbtool/carplay/bridge/CarPlaySessionBridge.kt"

    /** 截出 `onModeChanged` 的函数体（到下一个 `// ---` 分隔条之前）。 */
    private fun onModeChangedBody(src: String): String {
        val body = src.substringAfter("fun onModeChanged(", "")
        assertTrue("onModeChanged 不见了——签名或所在文件变了，本契约需同步更新", body.isNotEmpty())
        return body.substringBefore("// ------")
    }

    // ------------------------------------------------------------ 差异化清理

    @Test
    fun `切走自建时必须拆掉 AP 并等残留释放`() {
        val body = onModeChangedBody(sourceOf(initSrc))

        assertTrue(
            "`from == ADB_HOSTAPD` 必须走 stopSelfBuiltAp：这是「自建残留」的唯一清理点。",
            body.contains("from == CarHotspotMode.ADB_HOSTAPD") && body.contains("stopSelfBuiltAp("),
        )

        val stopFn = sourceOf(initSrc).substringAfter("private fun stopSelfBuiltAp(", "")
        assertTrue("stopSelfBuiltAp 不见了", stopFn.isNotEmpty())

        assertTrue(
            "必须先调 host.stop() 把自建热点拆掉，否则残留地址永远不会消失。",
            stopFn.contains("host.stop()") || stopFn.contains(".stop()"),
        )
        assertTrue(
            "stop() 是**非阻塞**契约 ⇒ 必须轮询确认残留地址真的没了（awaitResidueCleared），" +
                "否则调用方紧接着 resolve 时仍会被残留误导。",
            stopFn.contains("awaitResidueCleared(configuredAddress)"),
        )

        // 🔴 2026-10-11（PATCH-0068）：残留判据的"那个地址"必须是**我们实际配上去的那个**，
        // 不能是"当场重算的 planner 网关"。真车日志（08:57）就是后者导致的：
        // 判据报「原本无残留」，而网上躺着 192.168.176.1 → 复用把它通告给 iPhone →
        // 40 秒 attach timed out。planner 是冲突规避的，每轮会换网段，拿现算值比对
        // 等于永远找不到真残留。
        assertTrue(
            "残留判据/等待必须取自 CarAdbHotspotState.hostAddress（实际 ifconfig 上去的地址）：" +
                "现在 stopSelfBuiltAp 里应当同时出现 selfBuiltAddresses() 与 " +
                "adbHotspot?.state()?.hostAddress。",
            stopFn.contains("selfBuiltAddresses()") &&
                sourceOf(initSrc).contains("adbHotspot?.state()?.hostAddress"),
        )
        assertTrue(
            "残留判据不得回退成「现算的 planner 地址」（plannedGatewayAddress）—— " +
                "那正是 PATCH-0068 修掉的根因。",
            !sourceOf(initSrc).contains("plannedGatewayAddress"),
        )
    }

    @Test
    fun `轮询有超时而不是死等`() {
        val src = sourceOf(initSrc)
        val fn = src.substringAfter("private fun awaitResidueCleared(", "")
        assertTrue("awaitResidueCleared 不见了", fn.isNotEmpty())
        val body = fn.substringBefore("\n    /**")

        assertTrue(
            "轮询必须带截止时间（deadline），否则网卡一直不释放就会把 worker 永久占住。",
            body.contains("deadline") || body.contains("RESIDUE_WAIT_MS"),
        )
        assertTrue(
            "必须 sleep 一个轮询间隔，否则是忙等、白烧 CPU。",
            body.contains("Thread.sleep(") || body.contains("RESIDUE_POLL_MS"),
        )
    }

    @Test
    fun `清理没确认成功时不抛异常`() {
        val fn = sourceOf(initSrc).substringAfter("private fun stopSelfBuiltAp(", "")
        val body = fn.substringBefore("\n    /**")

        // 与 `CarHotspotEnabledState.UNKNOWN` 一律放行同一条纪律：
        // 清理没确认成功，不该把用户的连接直接判死。
        assertFalse(
            "stopSelfBuiltAp 里不得出现 `throw`：清理失败只该如实记进 Result，" +
                "由调用方决定，而不是把连接路径打断（见类注释与 UNKNOWN 的处置纪律）。",
            body.contains("throw "),
        )
        assertTrue(
            "拆热点本身必须被 runCatching 兜住：host.stop() 抛异常不该让整个切换链路炸掉。",
            body.contains("runCatching"),
        )
    }

    // ------------------------------------------------------------ 同模式 no-op

    @Test
    fun `同模式切换是 no-op`() {
        val body = onModeChangedBody(sourceOf(initSrc))

        assertTrue(
            "必须有 `if (from == to) return`：改分辨率/音量都会一路调到 applySettings，" +
                "若每次都「清一遍再拉起」，热点会被无谓重启 ⇒ 连接被打断。",
            body.contains("if (from == to) return"),
        )
    }

    @Test
    fun `只在切走自建时清理 其余切换只检出`() {
        val src = sourceOf(initSrc)
        val body = onModeChangedBody(src)

        // 复用 → 自建不需要清理：复用从不 startSoftAp，网卡上没有属于我们的东西。
        assertTrue(
            "除 ADB_HOSTAPD 以外的切换必须走「只检出、不自动清理」那条分支（detectResidue）。",
            body.contains("detectResidue()"),
        )
        // 只应有一处 stopSelfBuiltAp 调用点（就是 from == ADB_HOSTAPD 那支）。
        val stopCalls = Regex("""stopSelfBuiltAp\(""").findAll(body).count()
        assertTrue(
            "stopSelfBuiltAp 只允许在 `from == ADB_HOSTAPD` 一支里被调用；" +
                "实际在 onModeChanged 里出现 $stopCalls 次 —— 乱清理会拆掉一张正在用的网。",
            stopCalls == 1,
        )
    }

    // ------------------------------------------------------------ 判据同源

    @Test
    fun `残留判据与 planner 同源`() {
        val src = sourceOf(initSrc)

        assertTrue(
            "期望网关必须来自 CarAdbHotspotPlanner.plan（与真正会配上去的地址同源）；" +
                "自己再算一份就会永远检不出残留。",
            src.contains("CarAdbHotspotPlanner.plan("),
        )
        assertTrue(
            "判据本体必须复用 api 层的纯函数 CarHotspotResidue.detect。",
            src.contains("CarHotspotResidue.detect("),
        )
    }

    // ------------------------------------------------------------ 接线（三处）

    @Test
    fun `applySettings 检出模式变化并调用初始化`() {
        val src = sourceOf(controllerSrc)

        assertTrue(
            "applySettings 必须先取旧模式（this.settings.hotspotMode）再覆盖，否则永远检不出变化。",
            src.contains("val previousHotspotMode = this.settings.hotspotMode"),
        )
        assertTrue(
            "applySettings 必须比较新旧模式。",
            src.contains("val hotspotModeChanged = settings.hotspotMode != previousHotspotMode"),
        )
        assertTrue(
            "模式真的变了才允许调 onHotspotModeChanged —— 在 `if (hotspotModeChanged)` 之内。",
            src.contains("if (hotspotModeChanged)") && src.contains("services.onHotspotModeChanged("),
        )
        // 清理必须先完成，再由主线程回调触发重连；否则 async stop 与 buildSession
        // 会竞争，复用模式可能再次读到自建残留地址。
        val code = stripCommentLines(src)
        val initIdx = code.indexOf("services.onHotspotModeChanged(")
        val postIdx = code.indexOf("mainHandler.post {", initIdx)
        // 2026-10-20 重连链修复：直调 session.reconnect() 已收敛到唯一入口。
        val reconnectIdx = code.indexOf("requestReconnect(ReconnectSource.SETTINGS)", postIdx)
        assertTrue("必须找到模式清理调用、主线程回调和重连", initIdx >= 0 && postIdx > initIdx && reconnectIdx > postIdx)
        assertTrue(
            "模式清理任务必须用世代号丢弃过期结果",
            code.contains("hotspotModeChangeGeneration") && code.contains("cleanupGeneration != hotspotModeChangeGeneration"),
        )
    }

    @Test
    fun `初始化不在主线程同步执行`() {
        val src = sourceOf(controllerSrc)
        // 取到 onHotspotModeChanged 那段前后的窗口，确认它是被丢进 executor 的。
        // 同样用剥注释后的文本（理由见上一个测试）。
        val code = stripCommentLines(src)
        val initIdx = code.indexOf("services.onHotspotModeChanged(")
        assertTrue("找不到 onHotspotModeChanged 的调用点", initIdx > 0)
        val window = code.substring((initIdx - 400).coerceAtLeast(0), initIdx + 400)

        assertTrue(
            "onHotspotModeChanged 会 stop() + 最长 3 秒轮询确认，**绝不能**在主线程同步跑" +
                "（applySettings 在 UI 线程上，卡 3 秒等于界面假死）⇒ 必须丢到 worker。",
            window.contains("briefingExecutor.execute"),
        )
    }

    @Test
    fun `CarAppServices 暴露模式切换入口`() {
        val src = sourceOf(servicesSrc)

        assertTrue(
            "CarAppServices 必须有 onHotspotModeChanged 入口（应用层只依赖它，不碰 bridge 内部类型）。",
            src.contains("fun onHotspotModeChanged(") &&
                src.contains("CarHotspotModeInit("),
        )
        assertTrue(
            "入口要注入 adbHotspotManager 作为 CarAdbHotspotHost（清理动作的执行者）。",
            src.contains("adbHotspot = adbHotspotManager"),
        )
    }

    // ------------------------------------------------------------ 复用链路的落点

    @Test
    fun `复用链路不把自建残留当作车机热点`() {
        val src = sourceOf(bridgeSrc)
        // 先剥掉注释（行注释 `//` 与块注释 `/* */`）：`probeNativeHotspot()` 在
        // 注释与 KDoc 里被提及多次（那正是本项目文档习惯），只数**代码**里的调用。
        val code = stripComments(src)

        // 唯一调用 hotspotAlreadyRunningLocally() 的地方是 probeNativeHotspot()，
        // 而 probeNativeHotspot() 必须**不被调用** —— 否则残留会被通告。
        // 声明行 `private fun probeNativeHotspot(): …` 不算调用（后面跟的是 `:`）。
        val callSites = Regex("""probeNativeHotspot\(\)\s*[^\s:]""")
            .findAll(code)
            .count { !code.substring(0, it.range.first).trimEnd().endsWith("fun") }
        assertTrue(
            "probeNativeHotspot() 必须保持**不被调用**（它的首条判据 hotspotAlreadyRunningLocally() " +
                "会把自建残留认成车机热点）；实际发现 $callSites 处调用。" +
                "若确实要接回来，必须先给该判据加「当前不是复用模式」的闸门。",
            callSites == 0,
        )
        // 线上那条 resolve 必须仍然传 nativeInfo = null。
        assertTrue(
            "buildSession 里的 resolve 必须传 nativeInfo = null（复用不信任本地残留地址）。",
            code.contains("CarHotspotResolver.resolve(appContext, current, nativeInfo = null)"),
        )
    }

    // ------------------------------------------------------------ TCAM 纳入矩阵

    @Test
    fun `ADB_HOSTAPD 切到 TCAM_HOTSPOT 同样拆自建并清残留`() {
        val src = sourceOf(initSrc)
        val body = onModeChangedBody(src)

        assertTrue(
            "`from == ADB_HOSTAPD` 分支对 `to` 不做筛选 ⇒ `ADB_HOSTAPD → TCAM_HOTSPOT` " +
                "也必须走 stopSelfBuiltAp 把自建地址与占用者清干净（否则 TCAM 复用会把 " +
                "wlan0 残留当车机热点通告给 iPhone、到不了 AirPlay 7000）。",
            body.contains("from == CarHotspotMode.ADB_HOSTAPD") &&
                body.contains("return stopSelfBuiltAp(to)"),
        )
        assertTrue(
            "类注释必须把 TCAM 纳入 `from → to` 矩阵并点名 `ADB_HOSTAPD → 任何`（含 TCAM）。",
            src.contains("ADB_HOSTAPD → 任何") && src.contains("TCAM_HOTSPOT"),
        )
    }

    @Test
    fun `TCAM 切走无地址可清，只走通用残留清扫`() {
        val src = sourceOf(initSrc)

        assertTrue(
            "TCAM 只读 eth0.11、从不配置地址 ⇒ 不新增实体清理，也不得新起一套清理机制。",
            src.contains("TCAM_HOTSPOT → 任何") && src.contains("无地址可清"),
        )
        assertTrue(
            "SAME_LAN 与 TCAM 不共用接口/地址/监听，二者互切无需清理（结论要写进类注释）。",
            src.contains("SAME_LAN ↔ TCAM_HOTSPOT") && src.contains("无共用资源"),
        )
        // 仍只允许 from == ADB_HOSTAPD 一支调用 stopSelfBuiltAp。
        val stopCalls = Regex("""stopSelfBuiltAp\(""").findAll(onModeChangedBody(src)).count()
        assertTrue(
            "stopSelfBuiltAp 只允许在 `from == ADB_HOSTAPD` 一支里被调用；" +
                "实际在 onModeChanged 里出现 $stopCalls 次。",
            stopCalls == 1,
        )
    }

    /**
     * 去掉行注释与块注释（KDoc 也算块注释）。
     *
     * 只做够用的事：本测试只用它数"代码里有没有这处调用"，
     * 因此**不**处理字符串字面量里含 `//` 这种边缘情况——本项目源码里没有。
     */
    private fun stripComments(text: String): String {
        val noBlock = text.replace(Regex("""/\*[\s\S]*?\*/"""), " ")
        return noBlock.lineSequence()
            .joinToString("\n") { line -> line.substringBefore("//") }
    }

    /**
     * 只丢掉**整行注释**：逐行看，`trim()` 后以 `//`、`*`（KDoc 续行）、或"`/` + `*`"
     * 开头的行丢弃，其余行原样保留。
     *
     * （KDoc 里不敢直写那个两字符序列：Kotlin 的块注释可以嵌套，写进去会把整份 KDoc
     * 提前"开"一个内层注释，后面的源码全被吞掉 —— 这里踩过一次，记在这。）
     *
     * 【为什么不复用上面的 [stripComments]】这里要的是"逐行过滤"而不是"截断行尾"：
     * 本次定位的那两行都是**纯代码行**，只要把注释行剔掉，剩下的文本里它们的先后顺序
     * 就等于真实执行顺序；而行尾注释（`code // 说明`）保留下来也不影响 `indexOf`。
     * （用上面的 [stripComments] 会连"块注释里被引用的整段代码"一起截断，语义更绕。）
     */
    private fun stripCommentLines(text: String): String =
        text.lineSequence()
            .filterNot { line ->
                val trimmed = line.trim()
                trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")
            }
            .joinToString("\n")
}
