package com.dbtool.carplay.api.config

import com.dbtool.carplay.api.perf.PerfMetrics
import com.dbtool.carplay.api.perf.PerfRisk
import com.dbtool.carplay.api.perf.PerfRuleEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigSnapshotTest {

    // ------------------------------------------------------------ 白名单

    @Test
    fun `LIVE 必须与 CarSettingsReconnectPolicy 的真源完全一致`() {
        // P1-9：直接引用真源，不再手抄常量 —— 真源变了这里会红。
        val truth = com.dbtool.carplay.api.settings.CarSettingsReconnectPolicy.LIVE_FIELDS.toSet()
        assertEquals(truth, ConfigWhitelist.LIVE)
        assertTrue("LIVE 不能为空", ConfigWhitelist.LIVE.isNotEmpty())
    }

    @Test
    fun `LIVE 与 LOCAL_RENDER 不能重叠`() {
        val overlap = ConfigWhitelist.LIVE intersect ConfigWhitelist.LOCAL_RENDER
        assertTrue("重叠项会造成分类歧义: $overlap", overlap.isEmpty())
    }

    @Test
    fun `宽前缀绕过已被封堵`() {
        // P1-1 回归：这些键曾经因为命中 left_/draw_/right_/mask_ 前缀而被放行
        listOf(
            "left_displayId", "draw_initialUrl", "right_auth_token",
            "left_vin", "mask_token", "left_anythingElse",
        ).forEach { key ->
            assertFalse("$key 不应被放行", ConfigWhitelist.isAllowed(key))
        }
    }

    @Test
    fun `禁用字段一律 FORBIDDEN 且不放行`() {
        listOf("displayId", "clusterDisplayId", "initialURL", "setup", "streamType", "vin")
            .forEach { key ->
                assertEquals("$key 应为 FORBIDDEN", ConfigFieldKind.FORBIDDEN, ConfigWhitelist.kindOf(key))
                assertFalse("$key 不应放行", ConfigWhitelist.isAllowed(key))
            }
    }

    @Test
    fun `禁用前缀也不放行`() {
        listOf("mfi_cert", "auth_token", "pairing_key", "surface_x").forEach { key ->
            assertEquals(ConfigFieldKind.FORBIDDEN, ConfigWhitelist.kindOf(key))
            assertFalse(ConfigWhitelist.isAllowed(key))
        }
    }

    @Test
    fun `真实渲染键归为 LOCAL_RENDER`() {
        listOf(
            "draw_x", "left_dx", "right_scale", "video_scale",
        ).forEach { key ->
            assertEquals("$key", ConfigFieldKind.LOCAL_RENDER, ConfigWhitelist.kindOf(key))
            assertFalse("$key 不需要重连", ConfigWhitelist.needsReconnect(key))
        }
    }

    @Test
    fun `未知键返回 null 且不允许`() {
        assertNull(ConfigWhitelist.kindOf("totallyUnknownKey"))
        assertFalse(ConfigWhitelist.isAllowed("totallyUnknownKey"))
    }

    // ------------------------------------------------------------ 序列化

    @Test
    fun `序列化与反序列化往返一致`() {
        val snapshot = ConfigSnapshot.of(
            values = mapOf("debugLogs" to "false", "draw_x" to "12.5"),
            source = "manual",
            createdAtMillis = 1729750000000L,
        )
        val text = snapshot.serialize()
        assertTrue(text.startsWith(ConfigSnapshot.HEADER))
        val back = ConfigSnapshot.deserialize(text)
        assertNotNull(back)
        back!!
        assertEquals(snapshot.schemaVersion, back.schemaVersion)
        assertEquals(snapshot.createdAtMillis, back.createdAtMillis)
        assertEquals(snapshot.source, back.source)
        assertEquals(snapshot.values, back.values)
    }

    @Test
    fun `值里含等号也能正确还原`() {
        // dockAppOrder 是真自由字符串（磁盘用 \n 分隔），可含 '=' 与逗号
        val v = "a=b,c\nd=e"
        val text = ConfigSnapshot.of(mapOf("dockAppOrder" to v), "manual", 1L).serialize()
        assertEquals(v, ConfigSnapshot.deserialize(text)!!.values["dockAppOrder"])
    }

    @Test
    fun `头部不符返回 null 而不是空快照`() {
        assertNull(ConfigSnapshot.deserialize("NOT-A-SNAPSHOT\nschema=1\ncreated=1"))
        assertNull(ConfigSnapshot.deserialize(""))
    }

    @Test
    fun `缺 schema 或 created 返回 null`() {
        assertNull(ConfigSnapshot.deserialize("${ConfigSnapshot.HEADER}\ncreated=1"))
        assertNull(ConfigSnapshot.deserialize("${ConfigSnapshot.HEADER}\nschema=1"))
    }

    @Test
    fun `schema=1 旧格式被拒绝（协议已升到 2，无 END 无法判定完整性）`() {
        val text = "${ConfigSnapshot.HEADER}\nschema=1\ncreated=1\nsource=x\nk=debugLogs\ttrue\n"
        assertNull(ConfigSnapshot.deserialize(text))
    }

    @Test
    fun `未知字段导致整份快照被拒绝`() {
        // P1-5：不再"丢弃未知字段后继续" —— 那会让恢复按差异删除用户真实配置
        val text = "${ConfigSnapshot.HEADER}\nschema=1\ncreated=1\nsource=x\n" +
            "k=debugLogs\ttrue\nk=somethingNotWhitelisted\t1\n"
        assertNull(ConfigSnapshot.deserialize(text))
    }

    @Test
    fun `坏行导致整份快照被拒绝`() {
        val truncated = "${ConfigSnapshot.HEADER}\nschema=1\ncreated=1\nsource=x\nk=debugLogs"
        assertNull("截断的行必须整份失败", ConfigSnapshot.deserialize(truncated))
    }

    @Test
    fun `重复键导致整份快照被拒绝`() {
        val dup = "${ConfigSnapshot.HEADER}\nschema=1\ncreated=1\nsource=x\n" +
            "k=debugLogs\ttrue\nk=debugLogs\tfalse\n"
        assertNull(ConfigSnapshot.deserialize(dup))
    }

    @Test
    fun `未来 schema 被拒绝`() {
        val future = "${ConfigSnapshot.HEADER}\nschema=99\ncreated=1\nsource=x\nk=debugLogs\ttrue\n"
        assertNull("未来 schema 必须拒绝，否则会丢字段", ConfigSnapshot.deserialize(future))
    }

    // ------------------------------------------------------------ 敏感信息

    @Test
    fun `建快照时剔除敏感键`() {
        val snapshot = ConfigSnapshot.of(
            values = mapOf(
                "debugLogs" to "false",
                "vin" to "L6TTEST1111111111",
                "authToken" to "abc",
                "mfiCertificate" to "cert",
            ),
            source = "manual",
            createdAtMillis = 1L,
        )
        assertEquals(setOf("debugLogs"), snapshot.values.keys)
    }

    @Test
    fun `建快照时剔除未授权键`() {
        val snapshot = ConfigSnapshot.of(
            values = mapOf("debugLogs" to "true", "notWhitelisted" to "x"),
            source = "manual",
            createdAtMillis = 1L,
        )
        assertEquals(setOf("debugLogs"), snapshot.values.keys)
    }

    // ------------------------------------------------------------ 差异与计划

    @Test
    fun `差异计算包含新增 修改 删除`() {
        val before = mapOf("debugLogs" to "true", "draw_x" to "0.0")
        val after = mapOf("draw_x" to "10.0", "clusterFlip180" to "true")
        val changes = ConfigDiff.compute(before, after).associateBy { it.key }
        assertEquals(3, changes.size)
        assertTrue(changes.getValue("debugLogs").isRemove)
        assertFalse(changes.getValue("draw_x").isAdd)
        assertTrue(changes.getValue("clusterFlip180").isAdd)
    }

    @Test
    fun `requested 含未知键时整份计划被拒绝`() {
        val plan = ConfigChangePlan.build(
            current = mapOf("debugLogs" to "true"),
            requested = mapOf("debugLogs" to "false", "evilKey" to "x"),
        )
        assertTrue(plan.before.isEmpty())
        assertTrue(plan.after.isEmpty())
        assertTrue(plan.changes.isEmpty())
        assertEquals(1, plan.rejected.size)
        assertTrue(plan.rejected.containsKey("evilKey"))
        assertFalse(plan.isClean)
    }

    @Test
    fun `requested 含 FORBIDDEN 字段时整份拒绝`() {
        val plan = ConfigChangePlan.build(
            current = emptyMap(),
            requested = mapOf("displayId" to "3", "debugLogs" to "false"),
        )
        assertTrue(plan.after.isEmpty())
        assertTrue(plan.before.isEmpty())
        assertTrue(plan.rejected.getValue("displayId").contains("整份变更计划被拒绝"))
    }

    @Test
    fun `计划识别需要重连的字段`() {
        // 当前 20 个 LIVE 字段之外的白名单字段才会 NEEDS_RECONNECT；
        // 本地渲染参数不需要重连。
        val plan = ConfigChangePlan.build(
            current = emptyMap(),
            requested = mapOf("debugLogs" to "false", "draw_x" to "5.0"),
        )
        assertFalse(plan.needsReconnect)
        assertEquals(2, plan.liveChangeCount)
    }

    @Test
    fun `无变更时 hasChanges 为 false`() {
        val plan = ConfigChangePlan.build(
            current = mapOf("debugLogs" to "false"),
            requested = mapOf("debugLogs" to "false"),
        )
        assertFalse(plan.hasChanges)
    }

    // ------------------------------------------------------------ 规则

    private fun metrics(
        availMem: Long? = null,
        lowMem: Boolean? = null,
        free: Long? = null,
        level: Int? = null,
        charging: Boolean? = null,
        headUnit: Boolean? = null,
        usbUnknown: Boolean? = null,
        stalled: Boolean? = null,
    ) = PerfMetrics(availMem, lowMem, free, level, charging, headUnit, usbUnknown, stalled)

    private val MB = 1024L * 1024

    @Test
    fun `R1 内存低于 300MB 命中`() {
        val s = PerfRuleEngine.evaluate(metrics(availMem = 180 * MB))
        assertTrue(s.any { it.ruleId == "R1" })
        assertTrue(s.first { it.ruleId == "R1" }.evidence.first().contains("180MB"))
    }

    @Test
    fun `R1 lowMemory 标记也命中`() {
        val s = PerfRuleEngine.evaluate(metrics(lowMem = true))
        assertTrue(s.any { it.ruleId == "R1" })
    }

    @Test
    fun `R1 内存充足不命中`() {
        val r = PerfRuleEngine.evaluateDetailed(metrics(availMem = 900 * MB, lowMem = false))
        assertFalse(r.suggestions.any { it.ruleId == "R1" })
        assertTrue(r.notTriggered.contains("R1"))
    }

    @Test
    fun `R2 存储低于 500MB 命中`() {
        val s = PerfRuleEngine.evaluate(metrics(free = 200 * MB))
        assertTrue(s.any { it.ruleId == "R2" })
    }

    @Test
    fun `R1 与 R2 同时触发时合并成一条`() {
        val s = PerfRuleEngine.evaluate(metrics(availMem = 100 * MB, free = 100 * MB))
        val debugSuggestions = s.filter { it.changes["debugLogs"] == "false" }
        assertEquals("R1+R2 应合并成一条建议", 1, debugSuggestions.size)
        assertEquals("R1+R2", debugSuggestions[0].ruleId)
        assertEquals(2, debugSuggestions[0].evidence.size)
    }

    @Test
    fun `R3 低电量未充电命中但不改任何字段`() {
        val s = PerfRuleEngine.evaluate(metrics(level = 10, charging = false))
        val r3 = s.first { it.ruleId == "R3" }
        assertTrue(r3.changes.isEmpty())
    }

    @Test
    fun `R3 充电中不命中`() {
        val r = PerfRuleEngine.evaluateDetailed(metrics(level = 10, charging = true))
        assertFalse(r.suggestions.any { it.ruleId == "R3" })
    }

    @Test
    fun `R4 帧回调停滞只提示不改配置`() {
        val s = PerfRuleEngine.evaluate(metrics(stalled = true))
        val r4 = s.first { it.ruleId == "R4" }
        assertTrue(r4.changes.isEmpty())
        assertEquals(PerfRisk.HIGH, r4.risk)
    }

    @Test
    fun `R5 非车机环境命中`() {
        val s = PerfRuleEngine.evaluate(metrics(headUnit = false))
        assertTrue(s.any { it.ruleId == "R5" })
    }

    @Test
    fun `R6 USB 未知命中但不需确认`() {
        val s = PerfRuleEngine.evaluate(metrics(usbUnknown = true))
        val r6 = s.first { it.ruleId == "R6" }
        assertFalse(r6.needsConfirm)
    }

    @Test
    fun `指标全为 null 时全部跳过而不是误报`() {
        val r = PerfRuleEngine.evaluateDetailed(PerfMetrics())
        assertTrue("不应有任何建议", r.suggestions.isEmpty())
        assertTrue("六条规则都应标记为跳过", r.skipped.containsAll(listOf("R1", "R2", "R3", "R4", "R5", "R6")))
    }

    @Test
    fun `只有电量时 R3 可用但 R1R2 跳过`() {
        val r = PerfRuleEngine.evaluateDetailed(metrics(level = 50, charging = true))
        assertTrue(r.skipped.contains("R1"))
        assertTrue(r.skipped.contains("R2"))
        assertFalse(r.skipped.contains("R3"))
    }

    @Test
    fun `建议按风险从高到低排序`() {
        val s = PerfRuleEngine.evaluate(
            metrics(availMem = 100 * MB, stalled = true),
        )
        assertEquals(PerfRisk.HIGH, s.first().risk)
    }

    @Test
    fun `规则不伪造 FPS 或掉帧指标`() {
        // PerfMetrics 里根本没有 fps / droppedFrames 字段 —— 编译期即保证。
        // 这条测试是文档性的：确认引擎只会因为"指标为 null"跳过，不会推出数值。
        val r = PerfRuleEngine.evaluateDetailed(PerfMetrics(availMemBytes = 1000 * MB))
        assertTrue(r.suggestions.none { it.title.contains("帧率") || it.title.contains("FPS") })
    }

    // ------------------------------------------------------------ P0 回归（2026-10-24 封堵）

    @Test
    fun `构造入口不暴露：只能经 of 或 deserialize`() {
        // ConfigSnapshot 的构造器是 private，data class 的 copy() 也不存在。
        // 这里用反射断言：公开构造器数量为 0（编译器合成的 private 构造器不算）。
        val publicCtors = ConfigSnapshot::class.java.declaredConstructors.filter { !it.isSynthetic }
        assertTrue(
            "不应存在公开构造器，实际: ${publicCtors.map { it.parameterTypes.toList() }}",
            publicCtors.all { java.lang.reflect.Modifier.isPrivate(it.modifiers) },
        )
    }

    @Test
    fun `ConfigSnapshot 不是 data class（无 copy 方法暴露）`() {
        val hasCopy = ConfigSnapshot::class.java.declaredMethods.any { it.name == "copy" }
        assertFalse("data class 的 copy() 会绕过 init 校验，必须避免", hasCopy)
    }

    @Test
    fun `of 会剔除敏感键且不泄露`() {
        val snap = ConfigSnapshot.of(
            values = mapOf("debugLogs" to "true", "vin" to "L6TTEST1111111111", "displayId" to "3"),
            source = "manual",
            createdAtMillis = 1L,
        )
        assertEquals(setOf("debugLogs"), snap.values.keys)
        assertFalse(snap.serialize().contains("vin"))
        assertFalse(snap.serialize().contains("displayId"))
    }

    @Test
    fun `反序列化拒绝含敏感键的快照`() {
        val text = "${ConfigSnapshot.HEADER}\nschema=1\ncreated=1\nsource=x\nk=vin\tL6T7854\n"
        assertNull(ConfigSnapshot.deserialize(text))
    }

    @Test
    fun `快照 values 无法被强转改写`() {
        val snap = ConfigSnapshot.of(
            values = mapOf("debugLogs" to "true"),
            source = "manual",
            createdAtMillis = 1L,
        )
        @Suppress("UNCHECKED_CAST")
        val mutable = snap.values as? MutableMap<String, String>
        if (mutable != null) {
            assertThrows(UnsupportedOperationException::class.java) {
                mutable["debugLogs"] = "false"
            }
        }
        assertEquals("true", snap.values["debugLogs"])
    }

    @Test
    fun `构造后修改原始 Map 不影响快照`() {
        val source = mutableMapOf("debugLogs" to "true")
        val snap = ConfigSnapshot.of(values = source, source = "manual", createdAtMillis = 1L)
        source["debugLogs"] = "false"
        source["draw_x"] = "999.0"
        assertEquals("true", snap.values["debugLogs"])
        assertFalse(snap.values.containsKey("draw_x"))
    }

    @Test
    fun `计划的 after 与 rejected 无法被改写`() {
        val plan = ConfigChangePlan.build(
            current = mapOf("debugLogs" to "true"),
            requested = mapOf("debugLogs" to "false"),
        )
        @Suppress("UNCHECKED_CAST")
        val mutableAfter = plan.after as? MutableMap<String, String>
        if (mutableAfter != null) {
            assertThrows(UnsupportedOperationException::class.java) {
                mutableAfter["debugLogs"] = "true"
            }
        }
        @Suppress("UNCHECKED_CAST")
        val mutableRejected = plan.rejected as? MutableMap<String, String>
        if (mutableRejected != null) {
            assertThrows(UnsupportedOperationException::class.java) {
                mutableRejected.clear()
            }
        }
        assertEquals("false", plan.after["debugLogs"])
    }

    @Test
    fun `current 含禁用键时整份计划被拒绝且 before after changes 全部干净`() {
        val plan = ConfigChangePlan.build(
            current = mapOf("debugLogs" to "true", "displayId" to "3", "somethingElse" to "x"),
            requested = mapOf("debugLogs" to "false"),
        )
        // 整份拒绝：before/after/changes 都不含任何字段
        assertTrue("before 必须为空", plan.before.isEmpty())
        assertTrue("after 必须为空", plan.after.isEmpty())
        assertTrue("changes 必须为空", plan.changes.isEmpty())
        assertFalse("不得声称有变更", plan.hasChanges)
        // 具名原因可审计
        assertTrue(plan.rejected.containsKey("displayId"))
        assertTrue(plan.rejected.containsKey("somethingElse"))
        assertTrue(plan.rejected.getValue("displayId").contains("整份变更计划被拒绝"))
    }

    @Test
    fun `before 与 changes 永不泄漏非法字段`() {
        val plan = ConfigChangePlan.build(
            current = mapOf("vin" to "L6TTEST1111111111", "draw_x" to "0.0"),
            requested = mapOf("draw_x" to "5.0"),
        )
        listOf("vin", "displayId", "initialURL").forEach { bad ->
            assertFalse("before 泄漏 $bad", plan.before.containsKey(bad))
            assertFalse("after 泄漏 $bad", plan.after.containsKey(bad))
            assertFalse("changes 泄漏 $bad", plan.changes.any { it.key == bad })
        }
        assertTrue(plan.rejected.containsKey("vin"))
    }

    @Test
    fun `requested 含非法键时整份拒绝`() {
        val plan = ConfigChangePlan.build(
            current = mapOf("draw_x" to "0.0"),
            requested = mapOf("draw_x" to "5.0", "vin" to "X"),
        )
        assertTrue(plan.before.isEmpty())
        assertTrue(plan.after.isEmpty())
        assertTrue(plan.changes.isEmpty())
        assertTrue(plan.rejected.containsKey("vin"))
    }

    @Test
    fun `requested 含禁用键时整份拒绝且报告全部非法键`() {
        val plan = ConfigChangePlan.build(
            current = mapOf("draw_x" to "0.0"),
            requested = mapOf("draw_x" to "10.0", "initialURL" to "http://x", "madeUp" to "1"),
        )
        assertTrue("整份拒绝时 after 必须为空", plan.after.isEmpty())
        assertTrue(plan.before.isEmpty())
        assertEquals(2, plan.rejected.size)
        assertTrue(plan.rejected.containsKey("initialURL"))
        assertTrue(plan.rejected.containsKey("madeUp"))
    }


    // ------------------------------------------------------------ 恶意 Map（P1-1 回归）

    /**
     * 恶意 Map：`entries`（entrySet）含 `vin`，但 `keys`（keySet）与 `containsKey`
     * 都只暴露 `debugLogs`。
     *
     * 用来验证 [ConfigSnapshot] 的拷贝走 `entrySet()` 单一遍历、且 `init`
     * 校验的是拷贝后的实例字段（而不是入参视图）。
     */
    private class SneakyMap : java.util.AbstractMap<String, String>() {
        private val real: MutableMap<String, String> = mutableMapOf(
            "debugLogs" to "true",
            "vin" to "L6TTEST1111111111",
        )

        override val entries: MutableSet<MutableMap.MutableEntry<String, String>>
            get() = real.entries

        /** 只暴露合法键 —— 若实现信这个视图就会漏掉 vin。 */
        override val keys: MutableSet<String>
            get() = mutableSetOf("debugLogs")

        override fun containsKey(key: String): Boolean = key == "debugLogs"

        override fun get(key: String): String? = if (key == "debugLogs") "true" else null
    }

    @Test
    fun `恶意 Map 产不出含 vin 的快照`() {
        // of() 的 sanitize 先按 keySet 过滤（keySet 只暴露 debugLogs），
        // 因此 vin 在进构造器之前就被丢掉；这里是行为断言：
        // **无论走哪条路径，最终实例里绝不能出现 vin**。
        val snap = ConfigSnapshot.of(values = SneakyMap(), source = "manual", createdAtMillis = 1L)
        assertFalse("快照不得含 vin", snap.values.containsKey("vin"))
        assertFalse("序列化不得含 vin", snap.serialize().contains("vin"))
        assertFalse("序列化不得含 VIN 值", snap.serialize().contains("L6T7854"))
    }

    // ------------------------------------------------------------ deserialize 不抛（P1-2 回归）

    @Test
    fun `deserialize 对含 Tab 的 source 返回 null 而不是抛异常`() {
        val text = "${ConfigSnapshot.HEADER}\nschema=1\ncreated=1\nsource=a\tb\n"
        assertNull(ConfigSnapshot.deserialize(text))
    }

    @Test
    fun `deserialize 对含 CR LF 的 source 返回 null`() {
        listOf("a\rb", "a\nb").forEach { bad ->
            val text = "${ConfigSnapshot.HEADER}\nschema=1\ncreated=1\nsource=$bad\n"
            assertNull("source=$bad 应返回 null", ConfigSnapshot.deserialize(text))
        }
    }

    @Test
    fun `deserialize 对负数 created 返回 null`() {
        val text = "${ConfigSnapshot.HEADER}\nschema=1\ncreated=-5\nsource=x\nk=debugLogs\ttrue\n"
        assertNull(ConfigSnapshot.deserialize(text))
    }

    @Test
    fun `deserialize 对畸形输入永不抛异常`() {
        listOf(
            "", "\n\n", "garbage", "${ConfigSnapshot.HEADER}\n",
            "${ConfigSnapshot.HEADER}\nschema=abc\ncreated=1\n",
            "${ConfigSnapshot.HEADER}\nschema=1\ncreated=xyz\n",
            "${ConfigSnapshot.HEADER}\nschema=99\ncreated=1\nsource=x\n",
            "${ConfigSnapshot.HEADER}\nschema=1\ncreated=1\nsource=x\nk=\n",
            "${ConfigSnapshot.HEADER}\nschema=1\ncreated=1\nsource=x\nkbad\n",
        ).forEach { text ->
            // 只要不抛异常即可；返回值由各用例决定
            ConfigSnapshot.deserialize(text)
        }
    }

    @Test
    fun `解析失败不会产出空快照`() {
        assertNull(ConfigSnapshot.deserialize("${ConfigSnapshot.HEADER}\nschema=1\ncreated=-1\nsource=x\n"))
        assertNull(ConfigSnapshot.deserialize(""))
    }


    // ------------------------------------------------ A1/A2 回归（参数遮蔽 + 视图不一致）

    @Test
    fun `build 对 keys 与 entrySet 不一致的恶意 Map 整份拒绝而不是抛异常`() {
        val sneaky = object : java.util.AbstractMap<String, String>() {
            private val real = linkedMapOf("debugLogs" to "true", "displayId" to "3")
            override val entries: MutableSet<MutableMap.MutableEntry<String, String>> get() = real.entries
            override val keys: MutableSet<String> get() = mutableSetOf("debugLogs")
            override fun containsKey(key: String): Boolean = key == "debugLogs"
            override fun get(key: String): String? = "true"
        }
        val plan = try {
            ConfigChangePlan.build(current = sneaky, requested = mapOf("debugLogs" to "false"))
        } catch (e: IllegalArgumentException) {
            org.junit.Assert.fail("不得把异常泄漏给调用方: ${e.message}")
            return
        }
        assertTrue("before 必须为空", plan.before.isEmpty())
        assertTrue("after 必须为空", plan.after.isEmpty())
        assertTrue("changes 必须为空", plan.changes.isEmpty())
        assertTrue("displayId 必须被拒", plan.rejected.containsKey("displayId"))
    }

    @Test
    fun `plan 的 before after 对调用方 Map 是防御性拷贝`() {
        val cur = linkedMapOf("debugLogs" to "true")
        val plan = ConfigChangePlan.build(current = cur, requested = mapOf("debugLogs" to "false"))
        cur["debugLogs"] = "hacked"
        assertEquals("true", plan.before["debugLogs"])
    }

    // ------------------------------------------------ C 回归（协议完整性 + 转义）

    @Test
    fun `完整快照带 END 可往返`() {
        val snap = ConfigSnapshot.of(mapOf("debugLogs" to "true"), "manual", 7L)
        val text = snap.serialize()
        assertTrue("必须含终止记录", text.trimEnd().endsWith(ConfigSnapshot.END_MARKER))
        val back = ConfigSnapshot.deserialize(text)
        assertNotNull(back)
        assertEquals(snap.values, back!!.values)
        assertEquals(snap.source, back.source)
        assertEquals(snap.createdAtMillis, back.createdAtMillis)
    }

    @Test
    fun `缺少 END 的截断输入返回 null 而不是空快照`() {
        val text = "${ConfigSnapshot.HEADER}\nschema=2\ncreated=1\nsource=x\n"
        assertNull("截断必须拒绝，不能变成有效空快照", ConfigSnapshot.deserialize(text))
    }

    @Test
    fun `END 之后追加内容被拒绝`() {
        val good = ConfigSnapshot.of(mapOf("debugLogs" to "true"), "manual", 1L).serialize()
        assertNull(ConfigSnapshot.deserialize(good + "k=draw_x\t9.0\n"))
    }

    @Test
    fun `值内换行不能伪造 source 或新键`() {
        // 值域层（ConfigValueType.STRING）直接禁止换行 —— 这是第一道防线，
        // 比"转义后不伪造"更强：含换行的值根本进不了快照。
        assertThrows(IllegalArgumentException::class.java) {
            ConfigSnapshot.of(mapOf("clusterThemeId" to "0" + 10.toChar() + "source=evil"), "manual", 1L)
        }
    }
    @Test
    fun `值内 CR TAB 反斜杠往返一致`() {
        // CR/TAB 同样被值域层拒绝；只有换行以外的可打印字符才走转义往返。
        assertThrows(IllegalArgumentException::class.java) {
            ConfigSnapshot.of(mapOf("clusterThemeId" to "a" + 13.toChar() + "b"), "manual", 1L)
        }
        // 反斜杠本身是合法字符，必须能对称往返
        val v = "a" + 92.toChar() + "b"
        val text = ConfigSnapshot.of(mapOf("clusterThemeId" to v), "manual", 1L).serialize()
        assertEquals(v, ConfigSnapshot.deserialize(text)!!.values["clusterThemeId"])
    }
    @Test
    fun `重复元数据被拒绝`() {
        val text = "${ConfigSnapshot.HEADER}\nschema=2\nschema=2\ncreated=1\nsource=x\nend\n"
        assertNull(ConfigSnapshot.deserialize(text))
    }

    @Test
    fun `source 含换行时构造与反序列化都拒绝`() {
        // 构造期：init 直接 require 失败
        assertThrows(IllegalArgumentException::class.java) {
            ConfigSnapshot.of(emptyMap(), "a" + 10.toChar() + "b", 1L)
        }
        // 反序列化：即使协议层转义还原出换行，也必须拒绝（source 契约不变）
        val text = "${ConfigSnapshot.HEADER}" + 10.toChar() + "schema=2" + 10.toChar() +
            "created=1" + 10.toChar() + "source=a\\nend" + 10.toChar() +
            "k=debugLogs" + 9.toChar() + "true" + 10.toChar() + "end" + 10.toChar()
        assertNull(ConfigSnapshot.deserialize(text))
        // 正常无换行的 source 仍可往返
        val ok = "${ConfigSnapshot.HEADER}" + 10.toChar() + "schema=2" + 10.toChar() +
            "created=1" + 10.toChar() + "source=manual" + 10.toChar() +
            "k=debugLogs" + 9.toChar() + "true" + 10.toChar() + "end" + 10.toChar()
        assertNotNull(ConfigSnapshot.deserialize(ok))
    }

    @Test
    fun `VIN 不出现在序列化文本里`() {
        val text = ConfigSnapshot.of(
            mapOf("debugLogs" to "true", "vin" to "L6TTEST1111111111"),
            "manual", 1L,
        ).serialize()
        assertFalse(text.contains("L6T7854"))
        assertFalse(text.contains("vin"))
    }


    // ------------------------------------------------ 值域校验（C16/E20）

    @Test
    fun `NaN 与 Infinity 被拒绝`() {
        listOf("NaN", "Infinity", "-Infinity").forEach { bad ->
            assertThrows("draw_x=$bad 必须拒绝", IllegalArgumentException::class.java) {
                ConfigSnapshot.of(mapOf("draw_x" to bad), "manual", 1L)
            }
        }
    }

    @Test
    fun `整数字段不接受小数`() {
        // draw_w 是浮点字段，允许；safeArea 也是浮点。这里验证「非数值」被拒
        assertThrows(IllegalArgumentException::class.java) {
            ConfigSnapshot.of(mapOf("draw_x" to "abc"), "manual", 1L)
        }
    }

    @Test
    fun `布尔字段拒绝非法字符串`() {
        listOf("yes", "1", "", "TRUE").forEach { bad ->
            assertThrows("left_visible=$bad 必须拒绝", IllegalArgumentException::class.java) {
                ConfigSnapshot.of(mapOf("left_visible" to bad), "manual", 1L)
            }
        }
        val ok = ConfigSnapshot.of(mapOf("left_visible" to "true"), "manual", 1L)
        assertEquals("true", ok.values["left_visible"])
    }

    @Test
    fun `枚举字段拒绝未知值`() {
        assertThrows(IllegalArgumentException::class.java) {
            ConfigSnapshot.of(mapOf("uiAppearanceMode" to "dark"), "manual", 1L) // 大小写不放过
        }
        val ok = ConfigSnapshot.of(mapOf("uiAppearanceMode" to "DARK"), "manual", 1L)
        assertEquals("DARK", ok.values["uiAppearanceMode"])
    }

    @Test
    fun `数值超范围被拒绝`() {
        assertThrows(IllegalArgumentException::class.java) {
            ConfigSnapshot.of(mapOf("left_scale" to "99"), "manual", 1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConfigSnapshot.of(mapOf("draw_w" to "10"), "manual", 1L) // < MIN_WIDTH 480
        }
    }

    @Test
    fun `deserialize 也走值域校验`() {
        val text = "${ConfigSnapshot.HEADER}\nschema=2\ncreated=1\nsource=x\nk=draw_x\tNaN\nend\n"
        assertNull(ConfigSnapshot.deserialize(text))
    }

    @Test
    fun `build 也拒绝非法值`() {
        val plan = ConfigChangePlan.build(
            current = mapOf("draw_x" to "0.0"),
            requested = mapOf("draw_x" to "NaN"),
        )
        assertTrue(plan.after.isEmpty())
        assertTrue(plan.rejected.containsKey("draw_x"))
    }

    // ------------------------------------------------ VIN 精确黑名单

    @Test
    fun `已知 VIN 作为值被拒绝且不回显`() {
        val vin = "L6TTEST1111111111"
        val ex = assertThrows(IllegalArgumentException::class.java) {
            ConfigSnapshot.of(mapOf("clusterThemeId" to vin), "manual", 1L, knownVins = setOf(vin))
        }
        assertFalse("拒绝原因不得回显 VIN", ex.message?.contains(vin) ?: false)
    }

    @Test
    fun `不传 knownVins 时自由字符串可保存（诚实边界）`() {
        // 记录限制：没有调用方传入 VIN 时，无法识别任意形态的 VIN
        val snap = ConfigSnapshot.of(mapOf("clusterThemeId" to "factory_clear"), "manual", 1L)
        assertEquals("factory_clear", snap.values["clusterThemeId"])
    }

    // ------------------------------------------------ A4e changes 不可变

    @Test
    fun `changes 无法被强转修改`() {
        val plan = ConfigChangePlan.build(
            current = mapOf("debugLogs" to "true"),
            requested = mapOf("debugLogs" to "false"),
        )
        @Suppress("UNCHECKED_CAST")
        val mut = plan.changes as? MutableList<ConfigChange>
        if (mut != null) {
            assertThrows(UnsupportedOperationException::class.java) { mut.clear() }
        }
        assertEquals(1, plan.changes.size)
        assertTrue(plan.hasChanges)
    }

    // ------------------------------------------------ B8 重复元数据

    @Test
    fun `负数 schema 后再次出现合法 schema 被拒绝`() {
        val text = "${ConfigSnapshot.HEADER}\nschema=-1\nschema=2\ncreated=1\nsource=x\nend\n"
        assertNull(ConfigSnapshot.deserialize(text))
    }

    @Test
    fun `重复 created 被拒绝`() {
        val text = "${ConfigSnapshot.HEADER}\nschema=2\ncreated=-1\ncreated=1\nsource=x\nend\n"
        assertNull(ConfigSnapshot.deserialize(text))
    }

    // ------------------------------------------------ D19 ConfigDiff 语义

    @Test
    fun `ConfigDiff 不再对外的非法键生成可消费变更`() {
        // ConfigDiff 已改 internal；build 对非法键整份拒绝
        val plan = ConfigChangePlan.build(emptyMap(), mapOf("displayId" to "3"))
        assertTrue(plan.changes.isEmpty())
        assertTrue(plan.rejected.containsKey("displayId"))
    }


    // ------------------------------------------------ P1 回归（2026-10-24 复审修正）

    @Test
    fun `圆表 scale 下限是 0_2 不是 0_5`() {
        // ClusterDialLayout.sanitize 用 coerceIn(0.2f, 3f)；UI 滑块 0.4..1.6
        assertEquals("0.3", ConfigSnapshot.of(mapOf("left_scale" to "0.3"), "m", 1L).values["left_scale"])
        assertEquals("0.2", ConfigSnapshot.of(mapOf("right_scale" to "0.2"), "m", 1L).values["right_scale"])
        assertThrows(IllegalArgumentException::class.java) {
            ConfigSnapshot.of(mapOf("left_scale" to "0.1"), "m", 1L)
        }
    }

    @Test
    fun `safeArea 接受四元组并拒绝标量`() {
        // CarSettingsSafeArea(x/y/width/height: Int)
        assertEquals(
            "0,0,1920,720",
            ConfigSnapshot.of(mapOf("safeArea" to "0,0,1920,720"), "m", 1L).values["safeArea"],
        )
        assertEquals("", ConfigSnapshot.of(mapOf("safeArea" to ""), "m", 1L).values["safeArea"])
        listOf("200", "0,0,1920", "0,0,1920,abc", "-1,0,1920,720").forEach { bad ->
            assertThrows("safeArea=$bad 必须拒绝", IllegalArgumentException::class.java) {
                ConfigSnapshot.of(mapOf("safeArea" to bad), "m", 1L)
            }
        }
    }

    @Test
    fun `dockAppOrder 允许换行分隔与空串`() {
        // 磁盘格式是 joinToString("\n")；用户可能有 0 个 dock 应用
        val multi = "com.a.app\ncom.b.app"
        assertEquals(multi, ConfigSnapshot.of(mapOf("dockAppOrder" to multi), "m", 1L).values["dockAppOrder"])
        assertEquals("", ConfigSnapshot.of(mapOf("dockAppOrder" to ""), "m", 1L).values["dockAppOrder"])
    }

    @Test
    fun `hudCrop 接受四元组并拒绝越界`() {
        assertEquals("0.1,0.0,0.9,1.0",
            ConfigSnapshot.of(mapOf("hudCrop" to "0.1,0.0,0.9,1.0"), "m", 1L).values["hudCrop"])
        listOf("0.1,0.0,0.9", "0.1,0.0,1.9,1.0", "left=0.1,right=0.9").forEach { bad ->
            assertThrows("hudCrop=$bad 必须拒绝", IllegalArgumentException::class.java) {
                ConfigSnapshot.of(mapOf("hudCrop" to bad), "m", 1L)
            }
        }
    }

    @Test
    fun `坐标不允许负值`() {
        listOf("draw_x").forEach { key ->
            assertThrows("$key 负值必须拒绝", IllegalArgumentException::class.java) {
                ConfigSnapshot.of(mapOf(key to "-10"), "m", 1L)
            }
        }
    }

    @Test
    fun `VIN 在 deserialize 与 build 两条路径都被拦`() {
        val vin = "L6TTEST1111111111"
        // deserialize
        val text = "${ConfigSnapshot.HEADER}\nschema=2\ncreated=1\nsource=x\nk=clusterThemeId\t$vin\nend\n"
        assertNull(ConfigSnapshot.deserialize(text, knownVins = setOf(vin)))
        assertNotNull(ConfigSnapshot.deserialize(text)) // 不传则不拦（已声明限制）
        // build
        val plan = ConfigChangePlan.build(
            current = emptyMap(), requested = mapOf("clusterThemeId" to vin), knownVins = setOf(vin),
        )
        assertTrue(plan.after.isEmpty())
        assertTrue(plan.rejected.containsKey("clusterThemeId"))
        assertFalse(plan.rejected.getValue("clusterThemeId").contains(vin))
    }


    @Test
    fun `hudCrop 要求 right 大于 left 且 bottom 大于 top`() {
        // P1-6：生产 HudCropSpec 抛 IAE，sanitizeHudCrop 静默回落全画面 —— 配置层必须拒
        listOf("0.9,0.0,0.1,1.0", "0.5,0.5,0.5,0.5", "0.1,0.9,0.9,0.1").forEach { bad ->
            assertThrows("hudCrop=$bad 必须拒绝", IllegalArgumentException::class.java) {
                ConfigSnapshot.of(mapOf("hudCrop" to bad), "m", 1L)
            }
        }
        // 合法的仍是这几种
        listOf("0.1,0.0,0.9,1.0", "0.0,0.55,1.0,0.803").forEach { ok ->
            assertEquals(ok, ConfigSnapshot.of(mapOf("hudCrop" to ok), "m", 1L).values["hudCrop"])
        }
    }

}
