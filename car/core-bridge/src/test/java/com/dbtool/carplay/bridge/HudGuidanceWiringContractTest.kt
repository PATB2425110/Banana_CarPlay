package com.dbtool.carplay.bridge

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PATCH-0111「HUD 导航推送移植」的接线契约。
 *
 * ## 为什么钉它
 *
 * 这次移植的三处改动**跨三个模块**，而且**每一处漏接都是静默失败**：
 *
 * | 改动 | 漏接后的表现 |
 * | --- | --- |
 * | `CarPlayNavigationHudClient` 换用 `hudCurrentRoad()` / `hudNextRoad()` | 仪表继续显示英文 `Next Road`（**手机上完全看不出来**） |
 * | `CarPlayNavigationHudClient` 接上 `deduper` | 卡片继续一闪一闪 |
 * | `GeelyHudBridge` 补 `onBindingDied` / `onNullBinding` | HUD 一次绑不上就永久不可用 |
 * | `Iap2IdentificationClient` 追加 RGD 组件宣告 | 桥绑得上但**收不到任何导航帧**（iPhone 不下发） |
 *
 * PATCH-0112（实车验证后的完善）又加了两条 —— 它们**不影响行为**，但漏了就没法排障：
 *
 * | 改动 | 漏接后的表现 |
 * | --- | --- |
 * | `GeelyHudBridge.dispatch()` 成功路径留痕 | 诊断包里 `GeelyHudAdapter` 一行都没有 ⇒ **无法区分**"推成功了"与"整条链路没跑" |
 * | 诊断包白名单显式含 HUD 标签 | 谁精简白名单就把 HUD 留痕整段滤掉，而"看不到"和"HUD 没跑"长得一样 |
 *
 * 这些行为在 JVM 上都跑不到（要真机 + 车机），所以按项目既有做法
 * （`NativeHotspotCredentialsWiringContractTest`、`FullscreenInsetContractTest` 同族）
 * **静态读源码**把接线钉住，断任一环对应断言即红。
 *
 * ⚠️ 断言一律先剥注释（§29）—— 这些文件的 KDoc 里必然出现同样的字面量。
 */
class HudGuidanceWiringContractTest {

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

    /** 去掉块注释与行注释再返回（项目纪律 §29）。 */
    private fun codeOf(relative: String): String =
        sourceOf(relative)
            .replace(Regex("""/\*[\s\S]*?\*/"""), "")
            .replace(Regex("""//[^\n]*"""), "")

    private val hudClient =
        "car/core-bridge/src/main/java/com/dbtool/carplay/bridge/navigation/CarPlayNavigationHudClient.kt"
    private val geelyBridge =
        "car/platform-geely/src/main/java/com/dbtool/carplay/geely/GeelyHudBridge.kt"
    private val identification =
        "shared/src/main/java/com/shilapi/xcertplay/transport/Iap2IdentificationClient.kt"
    private val packager =
        "car/core-bridge/src/main/java/com/dbtool/carplay/bridge/developer/CarDiagnosticPackager.kt"
    private val bridgeSrc =
        "car/core-bridge/src/main/java/com/dbtool/carplay/bridge/CarPlaySessionBridge.kt"

    @Test
    fun `HUD 客户端必须真的接上去重与中文路名映射`() {
        val code = codeOf(hudClient)

        assertTrue(
            "去重必须在 hasContent 之后、toGuidance 之前真的被调用（否则卡片继续闪）",
            // ⚠️ 2026-10-13（PATCH-0114）：这一支从单行 `return` 变成了块 ——
            // 丢帧要顺手计数（设置页的排障行要显示"去掉几帧"）。断言跟着改形状，
            // 但**判据一字未动**，下面的顺序断言继续钉"它确实在 toGuidance 之前"。
            code.contains("if (!deduper.accept(") &&
                code.contains("maneuverSignature = state.hudManeuverSignature()"),
        )
        assertTrue(
            "去重必须排在转换之前（顺序反了会让被丢弃的帧也走一遍转换，且计数含义失真）",
            code.indexOf("deduper.accept(") <
                code.indexOf("val guidance = state.toGuidance()"),
        )
        assertTrue(
            "新会话必须复位去重基线（否则重开导航后车机提示一直空着）",
            code.contains("deduper.reset()"),
        )
        assertTrue(
            "路名必须走 maneuverDescription 优先的映射（否则仪表显示英文 Next Road）",
            code.contains("currentRoad = hudCurrentRoad()"),
        )
        assertTrue(
            "第二行必须走目的地优先的映射",
            code.contains("nextRoad = hudNextRoad()"),
        )
        assertTrue(
            "映射函数本体：0x5202 的描述优先，0x5201 的 currentRoad 作 fallback",
            code.contains("maneuverDescription.cleanHudText() ?: nextRoad.cleanHudText() ?: currentRoad.cleanHudText()"),
        )
        assertTrue(
            "映射函数本体：目的地优先，nextRoad 作 fallback",
            code.contains("destination.cleanHudText() ?: nextRoad.cleanHudText()"),
        )
    }

    @Test
    fun `吉利 HUD 桥必须补齐绑定卡死的出口`() {
        val code = codeOf(geelyBridge)

        assertTrue(
            "必须实现 onBindingDied —— 现场实测该服务会走静默绑定失败，只回调这个",
            code.contains("override fun onBindingDied("),
        )
        assertTrue(
            "必须实现 onNullBinding",
            code.contains("override fun onNullBinding("),
        )
        assertTrue(
            "onServiceDisconnected 必须同时复位 binding（否则 bindIfNeeded 的幂等判据永远为真）",
            code.contains("binding.set(false)"),
        )
    }

    @Test
    fun `HUD 桥必须重放连接前的首帧并在断线后自动重连`() {
        val code = codeOf(geelyBridge)
        val rememberAt = code.indexOf("navigationState.offer(guidance, SystemClock.elapsedRealtime())")
        val ensureBoundAt = code.indexOf("if (!ensureBound()) return", rememberAt)

        assertTrue(
            "首帧必须先在绑定之前保存，否则 onServiceConnected 时已经丢了",
            rememberAt >= 0 && ensureBoundAt > rememberAt,
        )
        assertTrue(
            "onServiceConnected 必须重放待发帧，而不是只亮一下可用性",
            code.contains("flushUpdates(replay = true, force = true)"),
        )
        assertTrue(
            "没有待发帧时连接成功要发停止事务，清掉车机侧残留",
            code.contains("replay && batch.guidance == null"),
        )
        assertTrue(
            "onServiceDisconnected 在有有效导航时要主动退避重绑",
            code.contains("mainHandler.postDelayed(reconnectRunnable, HUD_RECONNECT_DELAY_MS)"),
        )
    }

    @Test
    fun `HUD 桥独立心跳刷新载荷时间且不续来源有效期`() {
        val code = codeOf(geelyBridge)
        assertFalse(code.contains("keepaliveRunnable"))
        assertFalse(code.contains("armKeepalive()"))
        assertTrue(code.contains("if (!force && lastAcceptedGuidance == content && (!laneCapabilityAvailable || lastSentLanes == guidance.lanes)) return"))
        assertTrue(code.contains("if (sendLanes(wireGuidance)) lastSentLanes = guidance.lanes"))
        assertTrue(code.contains("guidance.copy(updatedAtMs = 0L)"))
        assertTrue(code.contains("if (stopPublished) {"))
        assertTrue(code.contains("stopBarrier.acknowledge(stopToken)"))
        assertTrue(code.contains("HUD_HEARTBEAT_INTERVAL_MILLIS = 2_000L"))
        assertTrue(code.contains("guidance.copy(updatedAtMs = System.currentTimeMillis())"))
        assertFalse(code.contains("armExpiry()"))
        val tick = code.substringAfter("private val heartbeatRunnable").substringBefore("private fun flushUpdates")
        assertFalse("心跳不能伪造来源输入", tick.contains("navigationState.offer("))
        assertEqualsOne("只有心跳自身安排下一轮", tick, "postDelayed(this, HUD_HEARTBEAT_INTERVAL_MILLIS)")
    }

    private fun assertEqualsOne(message: String, code: String, token: String) {
        org.junit.Assert.assertEquals(message, 1, code.windowed(token.length).count { it == token })
    }

    @Test
    fun `配套桥身份必须在发导航前校验`() {
        val code = codeOf(geelyBridge)
        assertTrue(code.contains("isPairedLynkBridge(service)"))
        val capabilities = codeOf("car/platform-geely/src/main/java/com/dbtool/carplay/geely/HudBridgeCapabilities.kt")
        assertTrue(capabilities.contains("DBPLAY_LYNK_BRIDGE_V1"))
        assertTrue(code.contains("CODE_IDENTITY = 13"))
        assertTrue(code.contains("CODE_LANE_CAPABILITY = 14"))
        val connection = code.substringAfter("override fun onServiceConnected(").substringBefore("override fun onServiceDisconnected(")
        assertTrue("基础导航必须在可选探测之前启用", connection.indexOf("transact(CODE_ENABLE)") < connection.indexOf("readBridgeTag(service, CODE_LANE_CAPABILITY)"))
        assertTrue("基础首帧必须先重放", connection.indexOf("flushUpdates(replay = true, force = true)") < connection.indexOf("readBridgeTag(service, CODE_LANE_CAPABILITY)"))
        assertFalse("车道探测不得变成基础接口拒绝条件", connection.substringAfter("readBridgeTag(service, CODE_LANE_CAPABILITY)").contains("identityRejected ="))
        assertTrue(code.contains("HudBridgeCapabilities.fromTags(identity, null)"))
        assertTrue(code.contains("capabilities.navigation"))
        assertTrue(code.contains("laneCapabilityAvailable && lastSentLanes != guidance.lanes"))
        assertTrue("默认无车道，连接后仅能力匹配才开启", code.contains("private var laneCapabilityAvailable = false"))
        assertTrue(code.contains("if (token != bindingToken) return@post"))
        assertTrue(code.contains("batch.stop || stopBarrier.isPending || (replay && batch.guidance == null)"))
        assertTrue(code.contains("navigationState.takeForSend("))
        assertTrue(code.contains("lifecycleEpoch.isCurrent(epoch)"))
        assertTrue(code.contains("else \"领克踏板身份读取暂时失败，稍后重试\""))
    }

    @Test
    fun `传统帧必须走已测试字段编码器与领克归一化`() {
        val code = codeOf(geelyBridge)
        assertTrue(
            "发送必须使用已核对领克接收表的归一化结果",
            code.contains("LynkHudNormalizer.normalize(guidance)"),
        )
        assertTrue(
            "生产路径与字段顺序单测必须使用同一编码器",
            code.contains("LynkHudWireCodec.writeLegacy(parcelWriter(parcel), guidance, turnKind)"),
        )
        assertFalse(
            "不能在 writeLegacy 里用 maneuverType 现算，否则 null/0 会写成 0",
            code.contains("GeelyHudTurnMapping.turnKind(guidance.maneuverType ?: 0)"),
        )
    }

    @Test
    fun `吉利 HUD 桥的组件名与事务码跟随现场补丁（并钉住回退入口）`() {
        val code = codeOf(geelyBridge)

        assertTrue(
            "组件包名应为现场补丁值 com.baidu.naviauto",
            code.contains("private const val PACKAGE = \"com.baidu.naviauto\""),
        )
        assertFalse(
            "不应残留上游的 com.autonavi.amapauto（否则改动没生效）",
            code.contains("com.autonavi.amapauto"),
        )
        assertTrue(
            "清车道事务码应为现场补丁值 12",
            code.contains("private const val CODE_CLEAR_LANES = 12"),
        )
        assertTrue(
            "现代和传统字段必须委托经过16槽位/33槽位校验的编码器",
            code.contains("LynkHudWireCodec.writeModern(") && code.contains("LynkHudWireCodec.writeLegacy("),
        )
    }

    @Test
    fun `识别帧必须追加 RGD 组件宣告（否则 iPhone 根本不下发导航帧）`() {
        val code = codeOf(identification)

        assertTrue(
            "identificationInformation 必须把帧交给追加函数",
            code.contains("return frame.withRgdComponentDeclaration()"),
        )
        assertTrue(
            "追加实现 = payload 拼上 86 字节组件参数",
            code.contains("Iap2Frame(messageId, payload + RGD_COMPONENT_DECLARATION)"),
        )
        assertTrue(
            "组件表必须是 0x56 字节",
            code.contains("ByteArray(0x56)"),
        )
        assertTrue(
            "参数 id 必须是 0x1E（RGD 组件宣告）",
            code.contains("bytes[0x03] = 0x1e"),
        )
        assertTrue(
            "componentId 必须是 0x2A（42，与 0x5201/0x5202 的 COMPONENT_ID 一致）",
            code.contains("bytes[0x09] = 0x2a"),
        )
        assertTrue(
            "组件名必须逐字符一致",
            code.contains("private const val RGD_COMPONENT_NAME = \"RouteGuidanceDisplayComponent\""),
        )
        assertTrue(
            "组件名必须写在字节表 0x0E 处",
            code.contains("RGD_COMPONENT_NAME.encodeToByteArray().copyInto(bytes, 0x0e)"),
        )
    }

    // ── PATCH-0112：实车验证之后的"可排障性"（不影响行为，但漏了就没法断案） ──

    @Test
    fun `吉利 HUD 桥的成功发帧必须留痕（否则诊断包里分不出「没跑」与「推成功了」）`() {
        val code = codeOf(geelyBridge)

        assertTrue(
            "成功分支必须打日志 —— 车机侧是裸 Parcel、不给回执，只有发送端能记",
            code.contains("HUD 已受理本帧"),
        )
        assertTrue(
            "失败分支的留痕不能丢",
            code.contains("HUD 未受理本帧"),
        )
        assertTrue(
            "摘要必须单独抽成 summarize()，便于在测试与排障时对齐字段口径",
            code.contains("private fun summarize("),
        )
        assertTrue(
            "摘要里必须带上车机实际会看到的那几项：距离/剩余时间/转向语义/两行文案",
            code.contains("guidance.routeMeters") &&
                code.contains("guidance.routeSeconds") &&
                code.contains("turn.semantic") &&
                code.contains("guidance.currentRoad") &&
                code.contains("guidance.nextRoad"),
        )
        assertTrue(
            "成功/失败必须由 sendModern 的返回值分流，不能无条件打同一句",
            code.contains("val accepted = sendModern(wireGuidance, normalizedTurn, request)"),
        )
    }

    @Test
    fun `诊断包白名单必须留住 HUD 的留痕（否则等于没打日志）`() {
        // ⚠️ 这里用**原文**而不是 codeOf()：本文件里含 `/vendor/etc/init/*.rc` 这类字面量，
        //    里面有 `/*`，剥块注释的正则会从那里开始吞掉一大段（包括白名单）。
        val source = sourceOf(packager)

        assertTrue(
            "KEEP_TAGS 必须显式包含 HUD 发帧出口的标签（不能只靠关键字 hud 巧合命中）",
            source.contains("\"GeelyHudAdapter\""),
        )
        assertTrue(
            "KEEP_TAGS 必须包含导航转换层的标签",
            source.contains("\"CarPlay-Navigation\""),
        )
        assertTrue(
            "关键字白名单必须覆盖 hud（大小写不敏感那条路）",
            source.contains("\"hud\""),
        )
        assertTrue(
            "白名单必须**同时**切一刀 adb 通道的系统日志 —— " +
                "`logcat_filtered.txt` 只有本进程的行，车机侧（百度 hudbridge 服务）说了什么看不到",
            source.contains("logcat_system_filtered.txt"),
        )
    }

    // ── PATCH-0117（2026-10-13）：把"HUD 为什么不可用"变成可判定 ────────────

    /**
     * 日志 8/9 里 `HUD 桥可用性：false` 一次会话出现 **30+ 次、全是 false**，
     * 而"车机没装那个服务" / "装了但这次没绑上" / "绑上了但从不回调"
     * 这三种成因在包里**长得一模一样** —— 处置却完全不同。
     *
     * 三条断言各钉一环：目标探针（有没有那个包）、失败留痕（哪条路径失败的）、
     * 可用性去重（别用同值刷屏把信号淹掉）。
     */
    @Test
    fun `HUD 不可用时必须留下可判定的原因`() {
        val bridge = codeOf(geelyBridge)

        assertTrue(
            "GeelyHudBridge 必须有目标存在性探针（`getPackageInfo` + `resolveService`）：" +
                "`bindService` 返回 false 分不出'车机没装该服务'与'装了但没绑上'",
            bridge.contains("getPackageInfo(PACKAGE, 0)") &&
                bridge.contains("resolveService("),
        )
        assertTrue(
            "绑定未成功那条路径必须**留痕**（原先一个字都不打，包里只剩上层一句 false）",
            bridge.contains("HUD 桥绑定未成功"),
        )
        assertTrue(
            "可用性通知必须去重：dispatch 每帧都会重绑，不去重就是 30+ 行同值噪声",
            bridge.contains("lastNotifiedAvailability"),
        )
    }

    /**
     * 上层那句 `HUD 桥可用性：…` 必须**带上目标与最近一次失败**。
     *
     * 只写 true/false 等于把"三种成因"糊成一句 —— 那正是日志 8/9 卡住的地方。
     */
    @Test
    fun `可用性日志必须带上目标与最近失败原因`() {
        val bridge = codeOf(bridgeSrc)
        assertTrue(
            "可用性日志必须读 `status()` 取目标与 lastFailure 一起写进诊断包",
            bridge.contains("hudClient?.status()") && bridge.contains("status.lastFailure"),
        )
        assertTrue(
            "目标与失败原因必须出现在同一条日志里（`HUD 桥可用性：` 那一行）",
            // 普通字符串里的 `\$` 才是转义成字面美元符；原始字符串 `"""…"""` 里
            // `\$` 是「反斜杠 + 插值」⇒ 会报 Unresolved reference（已踩过）。
            bridge.contains("HUD 桥可用性：\$available\$detail"),
        )
    }
}
