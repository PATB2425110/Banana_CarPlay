package com.dbtool.carplay.api.perf

/**
 * 性能指标快照（2026-10-24）。
 *
 * ## 为什么每个字段都可空
 *
 * **不可用必须是显式的 `null`**，不能用 0 或 -1 冒充。规则引擎见到 `null`
 * 就直接判该规则「无法评估」，绝不猜。
 *
 * ## 明确不存在的指标
 *
 * 下面这些**没有数据源**，因此**不在本类里** —— 不许从别的字段推导：
 * - 视频 FPS（`updatedCallbackCount` 是 TextureView 渲染侧计数，不是解码帧率）
 * - 掉帧率（`VideoWorkQueue` 没有 dropped 计数）
 * - 连接分阶段耗时（没有打点）
 * - 解码耗时、Wi-Fi 信号强度、温度（无公开 API）
 */
data class PerfMetrics(
    /** 可用内存（字节）。来自 `ActivityManager.MemoryInfo.availMem`。 */
    val availMemBytes: Long? = null,
    /** 系统低内存标记。来自 `MemoryInfo.lowMemory`。 */
    val lowMemory: Boolean? = null,
    /** 可用存储（字节）。来自 `StatFs.availableBytes`。 */
    val freeStorageBytes: Long? = null,
    /** 电量百分比 0..100。 */
    val batteryLevel: Int? = null,
    /** 是否在充电。 */
    val charging: Boolean? = null,
    /** 是否车机（`CarPlatformInfo.isHeadUnit`）。 */
    val isHeadUnit: Boolean? = null,
    /** USB Host 能力是否未知（`CarUsbHostSupport.UNKNOWN`）。 */
    val usbHostUnknown: Boolean? = null,
    /**
     * 仪表视频帧回调计数是否已连续 ≥5s 未增长。
     *
     * 注意：**这只说明"渲染侧没收到新帧"**，不能推断 FPS，也不能断定是解码问题。
     */
    val frameCallbackStalled5s: Boolean? = null,
)

/** 建议的风险等级。 */
enum class PerfRisk { LOW, MEDIUM, HIGH }

/**
 * 一条**确定性规则**（2026-10-24）。
 *
 * 全部字段都是纯数据 + 一个纯函数 [appliesWhen]，可单测、可序列化展示，
 * 不包含任何 AI/启发式判断。
 */
data class PerfRule(
    /** 唯一 ID，如 `R1`。 */
    val id: String,
    /** 标题（用户可见）。 */
    val title: String,
    /** 说明：这条规则在解决什么。 */
    val description: String,
    /** 风险等级。 */
    val risk: PerfRisk,
    /** 建议修改的字段 → 目标值。必须全部在白名单内。 */
    val changes: Map<String, String>,
    /** 是否需要用户确认。 */
    val needsConfirm: Boolean,
    /** 是否需要重连。 */
    val needsReconnect: Boolean,
    /** 回滚方式说明（用户可见）。 */
    val rollbackHint: String,
    /**
     * 触发判定。
     *
     * @return 命中时返回**证据文本**（用于 UI 展示"为什么"）；未命中或指标不可用时返回 `null`。
     */
    val appliesWhen: (PerfMetrics) -> String?,
)

/**
 * 一条**评估结果**（规则命中后的建议）。
 */
data class PerfSuggestion(
    /** 规则 ID（合并后可能是 `R1+R2`）。 */
    val ruleId: String,
    val title: String,
    val description: String,
    val risk: PerfRisk,
    val changes: Map<String, String>,
    val needsConfirm: Boolean,
    val needsReconnect: Boolean,
    val rollbackHint: String,
    /** 合并了哪些规则的证据（每条形如 `R1: 可用内存 180MB < 300MB`）。 */
    val evidence: List<String>,
)

/**
 * 规则引擎（2026-10-24）。
 *
 * ## 冲突策略
 *
 * 多条规则改**同一字段**时：
 * 1. **相同目标值** → 合并成一条建议，[PerfSuggestion.ruleId] 用 `R1+R2`，证据合并；
 * 2. **不同目标值** → 按 [PerfRisk] 降级取最保守（LOW > MEDIUM > HIGH 的保守序：
 *    实际取 **HIGH 优先级最高**，因为它最保守），并在证据里标"冲突，取最高风险"；
 * 3. **需要重连与不需要重连混合** → 整条建议按"需要重连"处理（诚实提示）。
 *
 * ## 不支持的指标
 *
 * 规则里用到的指标为 `null` 时，该规则**静默不命中**（不报"未知"给用户），
 * 但 [evaluateDetailed] 会把它记进 `skipped` 供诊断页展示。
 */
object PerfRuleEngine {

    /** 内存阈值 300 MB。 */
    const val MIN_AVAIL_MEM_BYTES: Long = 300L * 1024 * 1024

    /** 存储阈值 500 MB。 */
    const val MIN_FREE_STORAGE_BYTES: Long = 500L * 1024 * 1024

    /** 低电量阈值。 */
    const val LOW_BATTERY_PERCENT: Int = 15

    /** R1：可用内存低于 300MB 或 lowMemory=true → 关调试日志。 */
    val R1: PerfRule = PerfRule(
        id = "R1",
        title = "内存紧张，建议关闭调试日志",
        description = "调试日志会持续占用内存与 IO。内存紧张时关掉可减少抖动。",
        risk = PerfRisk.MEDIUM,
        changes = mapOf("debugLogs" to "false"),
        needsConfirm = true,
        needsReconnect = false,
        rollbackHint = "可在「设置 → 高级设置 → 调试日志」重新打开",
        appliesWhen = { m ->
            val lowMem = m.lowMemory == true
            val mem = m.availMemBytes
            when {
                lowMem -> if (mem != null) {
                    "可用内存 ${mem / 1024 / 1024}MB，系统已标记 lowMemory"
                } else {
                    "系统已标记 lowMemory"
                }
                mem == null -> null // 指标不可用 ⇒ 无法评估
                mem < MIN_AVAIL_MEM_BYTES -> "可用内存 ${mem / 1024 / 1024}MB < ${MIN_AVAIL_MEM_BYTES / 1024 / 1024}MB"
                else -> null
            }
        },
    )

    /** R2：可用存储低于 500MB → 关调试日志。 */
    val R2: PerfRule = PerfRule(
        id = "R2",
        title = "存储紧张，建议关闭调试日志",
        description = "日志写盘会持续占用空间。存储紧张时关掉可避免写失败。",
        risk = PerfRisk.MEDIUM,
        changes = mapOf("debugLogs" to "false"),
        needsConfirm = true,
        needsReconnect = false,
        rollbackHint = "可在「设置 → 高级设置 → 调试日志」重新打开",
        appliesWhen = { m ->
            val free = m.freeStorageBytes ?: return@PerfRule null
            if (free < MIN_FREE_STORAGE_BYTES) {
                "可用存储 ${free / 1024 / 1024}MB < ${MIN_FREE_STORAGE_BYTES / 1024 / 1024}MB"
            } else {
                null
            }
        },
    )

    /** R3：低电量且未充电 → **实验性**建议降低视觉开销，不默认切外观。 */
    val R3: PerfRule = PerfRule(
        id = "R3",
        title = "低电量，可考虑降低视觉开销（实验性）",
        description = "仪表主题每 200ms 重绘一次。低电量且未充电时，减少自绘内容可省电。" +
            "这条是**建议**，不会自动切换外观模式。",
        risk = PerfRisk.LOW,
        changes = emptyMap(), // 故意不改任何字段：只提示
        needsConfirm = true,
        needsReconnect = false,
        rollbackHint = "不涉及配置修改，无需回滚",
        appliesWhen = { m ->
            val level = m.batteryLevel ?: return@PerfRule null
            val charging = m.charging ?: return@PerfRule null
            if (!charging && level < LOW_BATTERY_PERCENT) {
                "电量 ${level}% < ${LOW_BATTERY_PERCENT}% 且未充电"
            } else {
                null
            }
        },
    )

    /** R4：帧回调 5s 不增长 → **仅诊断**，不改配置。 */
    val R4: PerfRule = PerfRule(
        id = "R4",
        title = "仪表画面可能已冻结（诊断提示）",
        description = "仪表视频的纹理回调已连续 5 秒未增长。可能原因：手机停画、流卡住、或窗口已不可见。" +
            "这条**只提示诊断**，不会修改任何配置。",
        risk = PerfRisk.HIGH,
        changes = emptyMap(),
        needsConfirm = true,
        needsReconnect = false,
        rollbackHint = "不涉及配置修改，无需回滚",
        appliesWhen = { m ->
            if (m.frameCallbackStalled5s == true) {
                "纹理回调 5s 未增长（注意：不能由此推断 FPS 或解码故障）"
            } else {
                null
            }
        },
    )

    /** R5：非车机环境 → 推荐稳定优先，**不屏蔽**高级设置。 */
    val R5: PerfRule = PerfRule(
        id = "R5",
        title = "当前不是车机环境，建议用「稳定优先」",
        description = "非车机设备（手机/平板/模拟器）上，车机专属能力可能不可用。" +
            "推荐稳定优先模式，但不会隐藏任何设置项。",
        risk = PerfRisk.LOW,
        changes = emptyMap(),
        needsConfirm = true,
        needsReconnect = false,
        rollbackHint = "模式切换随时可改回",
        appliesWhen = { m ->
            if (m.isHeadUnit == false) "isHeadUnit=false" else null
        },
    )

    /** R6：USB Host 未知 → **不阻止连接**，仅提示。 */
    val R6: PerfRule = PerfRule(
        id = "R6",
        title = "USB Host 能力未知（不影响连接）",
        description = "探测结果未知不代表不支持。**不要**据此拒绝有线连接 —— " +
            "探测是文件系统启发式，误判会挡掉本来可用的车机。",
        risk = PerfRisk.LOW,
        changes = emptyMap(),
        needsConfirm = false,
        needsReconnect = false,
        rollbackHint = "不涉及配置修改，无需回滚",
        appliesWhen = { m ->
            if (m.usbHostUnknown == true) "usbHostSupport=UNKNOWN" else null
        },
    )

    /** 首批全部规则（顺序即展示顺序）。 */
    val ALL: List<PerfRule> = listOf(R1, R2, R3, R4, R5, R6)

    /**
     * 评估结果。
     *
     * @property suggestions 命中并合并后的建议（按风险降序）
     * @property skipped 因指标不可用而无法评估的规则 ID
     * @property notTriggered 指标可用但未达阈值的规则 ID
     */
    data class Result(
        val suggestions: List<PerfSuggestion>,
        val skipped: List<String>,
        val notTriggered: List<String>,
    )

    /** 简化入口：只要建议列表。 */
    fun evaluate(metrics: PerfMetrics): List<PerfSuggestion> = evaluateDetailed(metrics).suggestions

    /** 完整评估。 */
    fun evaluateDetailed(metrics: PerfMetrics): Result {
        val hits = mutableListOf<Pair<PerfRule, String>>()
        val skipped = mutableListOf<String>()
        val notTriggered = mutableListOf<String>()

        for (rule in ALL) {
            val evidence = rule.appliesWhen(metrics)
            if (evidence != null) {
                hits += rule to evidence
            } else if (!metricsAvailable(rule, metrics)) {
                skipped += rule.id
            } else {
                notTriggered += rule.id
            }
        }

        return Result(
            suggestions = merge(hits),
            skipped = skipped,
            notTriggered = notTriggered,
        )
    }

    /**
     * 该规则所需指标是否齐全。
     *
     * 逐条硬编码（元数据驱动也可以，但 6 条规则直接写更直白且不会漏）。
     */
    private fun metricsAvailable(rule: PerfRule, m: PerfMetrics): Boolean = when (rule.id) {
        "R1" -> m.availMemBytes != null || m.lowMemory != null
        "R2" -> m.freeStorageBytes != null
        "R3" -> m.batteryLevel != null || m.charging != null
        "R4" -> m.frameCallbackStalled5s != null
        "R5" -> m.isHeadUnit != null
        "R6" -> m.usbHostUnknown != null
        else -> false
    }

    /**
     * 合并冲突：同字段同值 → 并成一条；同字段不同值 → 取最高风险并在证据里标注。
     */
    private fun merge(hits: List<Pair<PerfRule, String>>): List<PerfSuggestion> {
        if (hits.isEmpty()) return emptyList()
        val riskOrder = listOf(PerfRisk.HIGH, PerfRisk.MEDIUM, PerfRisk.LOW)
        // 按"变更集合"分组：无变更的规则（R3/R4/R5/R6）各自独立
        val grouped = LinkedHashMap<String, MutableList<Pair<PerfRule, String>>>()
        for ((rule, evidence) in hits) {
            val signature = if (rule.changes.isEmpty()) {
                "id:${rule.id}"
            } else {
                rule.changes.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }
            }
            grouped.getOrPut(signature) { mutableListOf() } += rule to evidence
        }

        return grouped.values.map { group ->
            val rules = group.map { it.first }
            val evidences = group.map { "${it.first.id}: ${it.second}" }
            val highestRisk = riskOrder.first { r -> rules.any { it.risk == r } }
            val conflict = rules.size > 1 && rules.map { it.changes }.distinct().size > 1
            PerfSuggestion(
                ruleId = rules.joinToString("+") { it.id },
                title = if (rules.size == 1) rules[0].title else rules.map { it.title }.first(),
                description = rules.joinToString(" ") { it.description },
                risk = highestRisk,
                changes = rules.first().changes,
                needsConfirm = rules.any { it.needsConfirm },
                needsReconnect = rules.any { it.needsReconnect },
                rollbackHint = rules.first().rollbackHint,
                evidence = if (conflict) evidences + "冲突：多条规则改同一字段，取最高风险" else evidences,
            )
        }.sortedBy { suggestion ->
            riskOrder.indexOf(suggestion.risk)
        }
    }
}
