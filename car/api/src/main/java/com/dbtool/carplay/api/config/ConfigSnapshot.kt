package com.dbtool.carplay.api.config

/**
 * 一份**配置快照**（2026-10-24）。
 *
 * ## 设计约束
 *
 * - **只存白名单字段**，且全部是 `String` 表示 —— 快照本身不认识业务类型，
 *   序列化/反序列化不依赖任何 Android 或 JSON 库（`car:api` 零依赖）。
 * - **不含敏感信息**：VIN / 证书 / 私钥 / 令牌 / 配对密钥 一律不进 [values]；
 *   由 [sanitize] 在建快照时主动剔除。
 * - [schemaVersion] 用于兼容：读旧快照时缺字段走「当前值」，多出来的未知字段**丢弃并记录**。
 *
 * ## 序列化格式
 *
 * 自描述行格式（不用 JSON，避免引入依赖）：
 *
 * ```
 * DBPLAY-CFG-1
 * schema=1
 * created=1729750000000
 * source=manual
 * k=debugLogs\tfalse
 * k=video_persp_tlX\t0.0
 * ```
 *
 * 值里允许出现任意字符（含 `=`），只有 `\t` 分隔键值；键里禁止 `\t`/`\n`/`=`。
 */
class ConfigSnapshot private constructor(
    /** 当前快照 schema 版本。字段增删时 +1。 */
    val schemaVersion: Int,
    /** 创建时间（epoch millis）。 */
    val createdAtMillis: Long,
    /**
     * 快照来源，用于历史记录展示。
     *
     * 约定：`manual`（用户主动）、`rule:R1`（规则触发）、`mode:stable|balanced|performance`、
     * `pre-restore`（恢复前自动存的保护点）。
     */
    val source: String,
    values: Map<String, String>,
) {

    /**
     * 字段 → 字符串值。
     *
     * **防御性不可变副本**：构造时就 `LinkedHashMap` 拷贝 + `unmodifiableMap` 包裹。
     * 调用方即使强转成 `MutableMap` 也会抛 `UnsupportedOperationException`，
     * 无法通过外部持有的 Map 改写快照内容（P0-2）。
     */
    val values: Map<String, String> = java.util.Collections.unmodifiableMap(LinkedHashMap(values))

    init {
        // ★ P0-1：**所有**入参在这里做完整校验。构造器是 private，但 init 仍然兜底，
        //   防止将来有人加 internal 构造或反射绕过。
        require(schemaVersion in 0..CURRENT_SCHEMA_VERSION) {
            "schemaVersion 越界: $schemaVersion（允许 0..$CURRENT_SCHEMA_VERSION）"
        }
        require(createdAtMillis >= 0L) { "createdAtMillis 不能为负: $createdAtMillis" }
        require(source.none { it == '\n' || it == '\r' || it == '\t' }) {
            "source 不得含换行/Tab: ${source.take(32)}"
        }
        // ★ P1-1：校验 **this.values**（已完成单一遍历拷贝的实例字段），
        //   不再信任构造入参 Map 的 keySet()/entrySet() 视图 ——
        //   恶意 Map 可以让 entrySet 含 vin、keySet 只返回 debugLogs。
        require(this.values.keys.all { key ->
            ConfigWhitelist.isAllowed(key) && SENSITIVE_MARKERS.none { key.lowercase().contains(it) }
        }) { "快照含未授权或敏感字段: ${this.values.keys}" }
        // ★ 值域：deserialize 走的也是这里。校验对象是已拷贝的 this.values。
        this.values.forEach { (k, v) ->
            ConfigValueType.validate(k, v)?.let { reason ->
                throw IllegalArgumentException(reason)
            }
        }
    }

    /** 序列化为自描述文本。 */
    fun serialize(): String = buildString {
        appendLine(HEADER)
        appendLine("schema=$schemaVersion")
        appendLine("created=$createdAtMillis")
        appendLine("source=" + escape(source))
        values.toSortedMap().forEach { (k, v) ->
            append("k=").append(escape(k)).append('\t').appendLine(escape(v))
        }
        // ★ C1（2026-10-24 最终复审）：终止记录。
        //   没有它，文件在 `source=` 后被截断时会被解析成"有效的空快照"，
        //   恢复流程会把用户配置整体清空。必须见到 END 才算完整。
        appendLine(END_MARKER)
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 2
        const val HEADER: String = "DBPLAY-CFG-1"

        /** 完整快照的终止记录（★ C1）。缺它 = 截断，一律拒绝。 */
        const val END_MARKER: String = "end"

        /**
         * 值/键转义（★ C2）。
         *
         * 不转义时，值里的 `\n` 会被 `deserialize` 当成新的一行，可以伪造
         * `source=`、改写 `schema`/`created`、注入白名单键。协议是行式的，
         * 唯一可靠的办法是让换行不可能出现在**记录内部**。
         */
        private fun escape(raw: String): String = buildString(raw.length) {
            raw.forEach { c ->
                when (c) {
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(c)
                }
            }
        }

        /** 反转义。遇到未知转义或不完整转义返回 null（不静默吞）。 */
        private fun unescape(raw: String): String? {
            val out = StringBuilder(raw.length)
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                if (c != '\\') { out.append(c); i++; continue }
                if (i + 1 >= raw.length) return null // 悬空反斜杠
                when (raw[i + 1]) {
                    '\\' -> out.append('\\')
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    't' -> out.append('\t')
                    else -> return null // 未知转义
                }
                i += 2
            }
            return out.toString()
        }

        /** 快照里**永不出现**的键（双保险，[ConfigWhitelist.FORBIDDEN] 之外再兜一层）。 */
        private val SENSITIVE_MARKERS: List<String> = listOf(
            "vin", "token", "secret", "password", "passwd", "key", "cert", "signature", "pairing",
        )

        /**
         * 从**不可信输入**构造实例字段（★ P1-1）。
         *
         * ## 为什么不用 `LinkedHashMap(input)`
         *
         * `LinkedHashMap(Map)` 走 `entrySet()` 拷贝，而恶意 Map 可以让
         * `entrySet()` 里含 `vin`、`keySet()` 只返回 `debugLogs`。若 `init`
         * 再去校验入参的 `keySet()`，非法字段就跟着进了实例。
         *
         * 这里做**单一遍历**：`forEach`（= `entrySet()`）逐个 `put`，
         * 每个 key 当场校验；不合格直接抛，交给上层转成 `null`/拒绝。
         * 之后 `init` 再校验 `this.values` 做二次确认。
         *
         * @throws IllegalArgumentException 含未授权键或敏感键
         */
        private fun buildCanonicalValues(
            input: Map<String, String>,
            knownVins: Set<String> = emptySet(),
        ): Map<String, String> {
            val canonical = LinkedHashMap<String, String>(input.size.coerceAtLeast(0))
            input.forEach { (key, value) ->
                require(key.isNotEmpty()) { "快照键不得为空" }
                require(ConfigWhitelist.isAllowed(key)) { "快照含未授权字段: $key" }
                require(SENSITIVE_MARKERS.none { key.lowercase().contains(it) }) {
                    "快照含敏感字段: $key"
                }
                // 重复键不可能出现在 Map 语义里，但恶意实现可能重复遍历同一键；
                // 后者会被 require(isAllowed) 之外的逻辑放过，所以显式检测覆盖：
                require(!canonical.containsKey(key)) { "快照含重复键: $key" }
                // ★ 值域校验：唯一汇聚点，of()/deserialize()/构造器全部经过这里。
                ConfigValueType.validate(key, value)?.let { reason ->
                    throw IllegalArgumentException(reason)
                }
                // ★ VIN 精确黑名单（不做正则全局扫描 —— 17 位字母数字规则会误伤
                //   合法主题 id / 包名）。只在调用方**明确传入已知 VIN** 时生效。
                //
                //   诚实边界：自由字符串字段（clusterThemeId / dockAppOrder）在
                //   未传 knownVins 时**无法保证**拦住任意形态的 VIN。
                if (knownVins.isNotEmpty() && value in knownVins) {
                    // 原因里不回显 VIN 本身。
                    throw IllegalArgumentException("字段值命中已知敏感值: $key")
                }
                canonical[key] = value
            }
            return java.util.Collections.unmodifiableMap(canonical)
        }

        /** 从白名单字段里剔除敏感键。 */
        fun sanitize(values: Map<String, String>): Map<String, String> =
            values.filterKeys { key ->
                val lower = key.lowercase()
                SENSITIVE_MARKERS.none { lower.contains(it) } &&
                    ConfigWhitelist.isAllowed(key)
            }

        /** 建快照：自动剔除敏感与未授权字段。 */
        fun of(
            values: Map<String, String>,
            source: String,
            createdAtMillis: Long,
            knownVins: Set<String> = emptySet(),
        ): ConfigSnapshot = ConfigSnapshot(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            createdAtMillis = createdAtMillis,
            source = source,
            values = buildCanonicalValues(sanitize(values), knownVins),
        )

        /**
         * 反序列化。
         *
         * @return `null` = 无法解析（头部不符 / 关键行缺失）。**不要**静默返回空快照，
         *         否则恢复流程会把"读不出来"当成"配置全空"从而清空用户设置。
         */
        fun deserialize(text: String, knownVins: Set<String> = emptySet()): ConfigSnapshot? {
            val lines = text.lineSequence().toList()
            if (lines.isEmpty() || lines.first().trim() != HEADER) return null
            var schema = -1
            var created = -1L
            var source: String? = null
            // ★ B8：显式出现标志。曾经用 `schema >= 0` 当哨兵，
            //   首个值写 `schema=-1` 就绕过了重复检测。
            var sawSchema = false
            var sawCreated = false
            var sawSource = false
            var sawEnd = false
            val values = LinkedHashMap<String, String>()
            for (line in lines.drop(1)) {
                // ★ C1：END 之后只允许空行 —— 防止在末尾追加内容伪造"完整"。
                if (sawEnd) {
                    if (line.isNotBlank()) return null
                    continue
                }
                if (line.isBlank()) continue // 记录之间允许空行
                when {
                    line == END_MARKER -> sawEnd = true
                    line.startsWith("schema=") -> {
                        if (sawSchema) return null // 重复元数据 → 拒绝
                        sawSchema = true
                        schema = line.removePrefix("schema=").trim().toIntOrNull() ?: return null
                    }
                    line.startsWith("created=") -> {
                        if (sawCreated) return null
                        sawCreated = true
                        created = line.removePrefix("created=").trim().toLongOrNull() ?: return null
                    }
                    line.startsWith("source=") -> {
                        if (sawSource) return null
                        sawSource = true
                        val decoded = unescape(line.removePrefix("source=")) ?: return null
                        // 转义还原后若仍含控制字符，违反 source 契约 → 拒绝
                        if (decoded.any { it == '\n' || it == '\r' || it == '\t' }) return null
                        source = decoded
                    }
                    line.startsWith("k=") -> {
                        val body = line.removePrefix("k=")
                        val tab = body.indexOf('\t')
                        if (tab <= 0) return null
                        val k = unescape(body.substring(0, tab)) ?: return null
                        val v = unescape(body.substring(tab + 1)) ?: return null
                        if (!ConfigWhitelist.isAllowed(k)) return null
                        if (values.containsKey(k)) return null
                        values[k] = v
                    }
                    else -> return null
                }
            }
            // ★ C1：没见到终止记录 = 截断/不完整，一律拒绝。
            if (!sawEnd) return null
            if (!sawSchema || !sawCreated) return null
            if (schema < 0 || created < 0) return null
            // 旧 schema（<2）没有 END 约定，协议不兼容 —— 直接拒绝而不是猜完整性。
            if (schema < 2) return null
            // P1-6：比当前版本更新的 schema 一律拒绝 —— 未来版本可能有未知字段，
            // 按当前白名单解析会丢字段，随后"恢复"会按差异删掉用户的真实配置。
            if (schema > CURRENT_SCHEMA_VERSION) return null
            // ★ P1-2：构造校验失败必须返回 null，不能把 IAE 泄漏给调用方
            //   （恢复流程若没包 try/catch，会把车机端打崩）。
            //   只捕获 IllegalArgumentException —— 那是 require 的约定类型，
            //   不用 runCatching 吞 Throwable。
            return try {
                ConfigSnapshot(
                    schemaVersion = schema,
                    createdAtMillis = created,
                    source = source ?: return null,
                    // ★ P1-5：deserialize 也要走 knownVins，
                    //   否则从磁盘读回来的快照根本不检查 VIN。
                    values = buildCanonicalValues(values, knownVins),
                )
            } catch (e: IllegalArgumentException) {
                null
            }
        }
    }
}

/**
 * 单字段变更。
 *
 * @property kind 变更分类；`null` = 未授权（不应出现在已通过校验的 plan 里）
 */
data class ConfigChange(
    val key: String,
    val from: String?,
    val to: String?,
    val kind: ConfigFieldKind?,
) {
    /** 是否是新增（原来没有这个键）。 */
    val isAdd: Boolean get() = from == null && to != null

    /** 是否是删除。 */
    val isRemove: Boolean get() = from != null && to == null

    /** 是否需要重连。 */
    val needsReconnect: Boolean get() = kind == ConfigFieldKind.NEEDS_RECONNECT
}

/** 差异计算（纯函数）。 */
internal object ConfigDiff {

    /**
     * 对比两份配置，返回**按 key 排序**的差异列表。
     *
     * 只比较 [after] 里出现的键 + [before] 里有但 [after] 里没有的键
     * （即删除也算差异，不能悄悄丢配置）。
     */
    fun compute(before: Map<String, String>, after: Map<String, String>): List<ConfigChange> {
        val keys = (before.keys + after.keys).toSortedSet()
        return keys.mapNotNull { key ->
            val b = before[key]
            val a = after[key]
            if (b == a) return@mapNotNull null
            ConfigChange(key = key, from = b, to = a, kind = ConfigWhitelist.kindOf(key))
        }
    }
}

/**
 * 一次**配置变更计划**（用户确认前的草稿）。
 *
 * 构造即校验：未授权/禁用字段在这里就被挡掉，不会走到"应用"。
 */
class ConfigChangePlan private constructor(
    before: Map<String, String>,
    after: Map<String, String>,
    rejected: Map<String, String>,
) {
    /**
     * 变更前的完整配置（白名单内）。
     *
     * 防御性不可变副本（P0-2）。
     */
    val before: Map<String, String> =
        java.util.Collections.unmodifiableMap(LinkedHashMap(before))

    /**
     * 变更后的完整配置。
     *
     * **只含通过白名单的字段** —— `build()` 已把非法键剔到 [rejected]，
     * 且构造器 `init` 再校验一次（P0-3）。防御性不可变副本（P0-2）。
     */
    val after: Map<String, String> =
        java.util.Collections.unmodifiableMap(LinkedHashMap(after))

    /**
     * 被拒绝的键 → 拒绝原因。防御性不可变副本（P0-2）。
     */
    val rejected: Map<String, String> =
        java.util.Collections.unmodifiableMap(LinkedHashMap(rejected))

    /**
     * 差异列表（由 before/after 计算，键必然已通过白名单）。
     *
     * ★ A4e：`ConfigDiff.compute` 返回的是 `ArrayList`，Kotlin 的 `List`
     * 只是**只读接口**，强转 `MutableList` 后能 `add(...)` 改掉已验证的计划，
     * `hasChanges` 随之与事实不一致。这里包一层不可修改视图。
     */
    val changes: List<ConfigChange> = java.util.Collections.unmodifiableList(
        ConfigDiff.compute(this.before, this.after),
    )

    init {
        // ★ A1（2026-10-24 最终复审）: 必须用 `this.` 前缀读**实例字段**。
        //   直接写 `before.keys` / `after.keys` 时，Kotlin 解析到的是**构造参数**
        //   （同名遮蔽），而参数可能是恶意 Map 的视图；属性已经做过防御性拷贝，
        //   校验必须针对拷贝后的不可变集合，否则 keys/entrySet 视图不一致的 Map
        //   能把非法键带进来（已被 Claude 用反射 + 撒谎 Map 复现）。
        this.before.keys.forEach { key ->
            require(ConfigWhitelist.isAllowed(key)) { "before 含未授权字段: $key" }
        }
        this.after.keys.forEach { key ->
            require(ConfigWhitelist.isAllowed(key)) { "after 含未授权字段: $key" }
        }
        // changes 由 before/after 差异算出，两者都合法则 changes 的键必然合法；
        // 这里再显式断言一次，防止将来有人改成从别的来源构造 changes。
        this.changes.forEach { change ->
            require(ConfigWhitelist.isAllowed(change.key)) { "changes 含未授权字段: ${change.key}" }
        }
    }

    /** 是否有任何变更。 */
    val hasChanges: Boolean get() = changes.isNotEmpty()

    /** 是否所有请求的键都被接受。 */
    val isClean: Boolean get() = rejected.isEmpty()

    /** 是否有需要重连的变更 —— 有的话 UI 必须提示，不得说"已生效"。 */
    val needsReconnect: Boolean get() = changes.any { it.needsReconnect }

    /** 仅需热应用的变更数。 */
    val liveChangeCount: Int get() = changes.count { !it.needsReconnect }

    companion object {
        /**
         * 生成计划：把 [requested] 合并到 [current] 上，**双方都**逐键按白名单校验。
         *
         * ## current 的策略（★ P0-3）
         *
         * `current` 里若有非法/禁用键：**剔除出 [ConfigChangePlan.after] 并记入 rejected**，
         * 原因写明「current 含未授权字段，已剔除」。绝不静默携带进 after ——
         * 否则恢复执行器会把一个禁用键当成正常配置写回去。
         *
         * 为什么不整份拒绝：`current` 来自真实存储，可能因为旧版本或人为写入
         * 带有脏数据；整份拒绝会让用户永远无法做任何变更。剔除 + 报告更实用。
         */
        fun build(
            current: Map<String, String>,
            requested: Map<String, String>,
            knownVins: Set<String> = emptySet(),
        ): ConfigChangePlan {
            val rejected = LinkedHashMap<String, String>()

            // ★ P0-1：**先整体校验 current 与 requested**。任一方含非法键就
            //   整份拒绝 —— before / after / changes 全部为空，绝不"过滤后继续"。
            //   理由：过滤后的 before 是一个不存在的配置状态，拿它做快照或
            //   差异基线会误导恢复流程。
            // ★ A2（2026-10-24 最终复审）：不要用 `keys()` 预检、再用 entrySet 拷贝 ——
            //   恶意 Map 两个视图不一致时，预检通过、拷贝带进非法键，最后在构造器
            //   抛 IllegalArgumentException（不是"整份拒绝"，语义不符）。
            //   这里先做**单一 entrySet 遍历的防御性拷贝**，之后所有校验只看拷贝。

            val currentCopy = LinkedHashMap<String, String>()
            val requestedCopy = LinkedHashMap<String, String>()
            try {
                current.forEach { (k, v) -> currentCopy[k] = v }
                requested.forEach { (k, v) -> requestedCopy[k] = v }
            } catch (e: RuntimeException) {
                // 遍历本身抛异常 = 输入不可信，整份拒绝（不把异常泄漏出去）
                rejected["<input>"] = "配置输入遍历失败，整份变更计划被拒绝"
                return ConfigChangePlan(before = emptyMap(), after = emptyMap(), rejected = rejected)
            }

            val badCurrent = currentCopy.keys.filterNot { ConfigWhitelist.isAllowed(it) }
            val badRequested = requestedCopy.keys.filterNot { ConfigWhitelist.isAllowed(it) }
            if (badCurrent.isNotEmpty() || badRequested.isNotEmpty()) {
                // 同一个键两边都非法时保留**具体**原因（先 current 后 requested，
                // 后写不覆盖先写），避免"两个原因互相顶掉"。
                badCurrent.forEach { key ->
                    rejected.putIfAbsent(key, "current 含未授权/禁用字段，整份变更计划被拒绝")
                }
                badRequested.forEach { key ->
                    rejected.putIfAbsent(key, "requested 含未授权/禁用字段，整份变更计划被拒绝")
                }
                return ConfigChangePlan(
                    before = emptyMap(),
                    after = emptyMap(),
                    rejected = rejected,
                )
            }

            // ★ 值域校验：requested 的值此前**完全没校验**，`draw_x=NaN` 能直接进 after。
            //   与 snapshot 走同一个 ConfigValueType，保证三条入口一致。
            currentCopy.forEach { (k, v) ->
                ConfigValueType.validate(k, v)?.let { reason ->
                    rejected.putIfAbsent(k, "current 值非法 —— $reason")
                }
            }
            requestedCopy.forEach { (k, v) ->
                ConfigValueType.validate(k, v)?.let { reason ->
                    rejected.putIfAbsent(k, "requested 值非法 —— $reason")
                }
            }
            // ★ P1-5：VIN 在 build 路径上此前**完全没检查**（实测 after 里能带 VIN）。
            if (knownVins.isNotEmpty()) {
                currentCopy.forEach { (k, v) ->
                    if (v in knownVins) rejected.putIfAbsent(k, "current 值命中已知敏感值")
                }
                requestedCopy.forEach { (k, v) ->
                    if (v in knownVins) rejected.putIfAbsent(k, "requested 值命中已知敏感值")
                }
            }
            if (rejected.isNotEmpty()) {
                return ConfigChangePlan(before = emptyMap(), after = emptyMap(), rejected = rejected)
            }

            // 双方都已通过白名单 + 值域，可以安全合并（基于拷贝，不引用调用方 Map）。
            val after = LinkedHashMap<String, String>(currentCopy)
            after.putAll(requestedCopy)
            return ConfigChangePlan(
                before = currentCopy,
                after = after,
                rejected = rejected,
            )
        }
    }
}
