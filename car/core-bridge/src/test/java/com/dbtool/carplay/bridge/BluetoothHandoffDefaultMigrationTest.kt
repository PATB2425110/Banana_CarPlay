package com.dbtool.carplay.bridge

import com.dbtool.carplay.api.settings.CarBluetoothHandoffPolicy
import com.dbtool.carplay.api.settings.CarSessionSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「蓝牙释放默认档 `DISCONNECT_LINK` → `DISABLE_ADAPTER`」的契约（PATCH-0095，2026-10-11）。
 *
 * ## 背景
 *
 * iPhone 在 AirPlay 建好后会下发 `disableBluetooth`（带 `deviceID`）要求车机断开蓝牙。
 * 上一版为此加了 `DISCONNECT_LINK` 档（定向断开），但**本车实测没生效** ——
 * 实车日志里 `BluetoothDevice.disconnect()` 返回 null、复查仍处于连接态，
 * 代码自己都打出了"可把…改成「关闭蓝牙适配器」档"的提示。
 * 既然目标档是唯一实测有效的路径，就把它做成默认。
 *
 * ## 为什么必须配一次性迁移
 *
 * 老安装只要保存过一次设置，`bt_handoff_policy` 就被 `save()` 写成了
 * **旧出厂值** `DISCONNECT_LINK` ⇒ 只改 `load()` 的默认值对它们完全无效，
 * 它们会永远停在实测无效的那一档。
 *
 * ## 为什么是源码级断言
 *
 * [SharedPreferencesCarSettingsStore] 构造需要 `android.content.Context`
 * 与真实 `SharedPreferences`，纯 JVM 测试跑不起来。所以这里测两件能在 JVM 里
 * 确证的事：**默认值常量本身**（纯 Kotlin）+ **迁移的写法契约**（源码级）。
 */
class BluetoothHandoffDefaultMigrationTest {

    // ── 1. 出厂默认 ─────────────────────────────────────────────────────────

    /**
     * 2026-10-21 车主口径：**不能关车机蓝牙**（会断耳机/胎压/车钥匙）。
     * 默认回到 [CarBluetoothHandoffPolicy.OFF]；定向断链由
     * `DISCONNECT_LINK` 的新实现（特权 `app_process`）承担，但需用户显式选择。
     */
    @Test
    fun `新产品默认仅断开手机蓝牙连接`() {
        assertEquals(
            CarBluetoothHandoffPolicy.DISCONNECT_LINK,
            CarSessionSettings.default().bluetoothHandoffPolicy,
        )
    }

    /** 三个档都还在（可回退：`OFF` = 上游行为，`DISCONNECT_LINK` = 上一版行为）。 */
    @Test
    fun `三个档位都保留`() {
        assertEquals(3, CarBluetoothHandoffPolicy.entries.size)
        assertTrue(
            CarBluetoothHandoffPolicy.entries.containsAll(
                listOf(
                    CarBluetoothHandoffPolicy.OFF,
                    CarBluetoothHandoffPolicy.DISCONNECT_LINK,
                    CarBluetoothHandoffPolicy.DISABLE_ADAPTER,
                ),
            ),
        )
    }

    // ── 2. 迁移写法契约（源码级） ───────────────────────────────────────────

    /** 迁移必须在 `load()` 里被调用 —— 否则老安装永远不会被改写，本需求全部意义落空。 */
    @Test
    fun `迁移在 load 里注册`() {
        val loadBody = loadBody()
        assertTrue(
            "load() 里必须调用 migrateBluetoothHandoffToDisableAdapter(d)",
            loadBody.contains("migrateBluetoothHandoffToDisableAdapter(d)"),
        )
    }

    /**
     * ⚠️ 一次性迁移**各自带独立标记**，不能与其它迁移共用。
     *
     * 这条纪律在那个文件的 KDoc 里被反复强调（`migrateLegacyHotspotDefaults` 的注释
     * 就是踩过坑的产物）：共用标记 ⇒ 先跑的把标记置位 ⇒ 后跑的直接 return ⇒
     * **静默失效**，而且完全看不出来。
     */
    @Test
    fun `迁移标记独立且不与其它迁移重名`() {
        val source = codeOf()
        assertTrue(
            "缺标记 KEY_BT_HANDOFF_MIGRATED",
            source.contains("KEY_BT_HANDOFF_MIGRATED"),
        )
        val markers = Regex("""(KEY_[A-Z0-9_]*MIGRATED)\s*=\s*"([^"]+)"""")
            .findAll(source)
            .associate { it.groupValues[1] to it.groupValues[2] }
        assertTrue("应能解析出多个迁移标记：$markers", markers.size >= 3)
        assertEquals(
            "蓝牙迁移标记不能与其它迁移共用同一个字符串：$markers",
            markers.size,
            markers.values.toSet().size,
        )
    }

    /**
     * ⚠️ 判据**只认旧出厂值**，不能无条件改写。
     *
     * 用户可能已经选了 `OFF`（回到上游行为）或 `DISABLE_ADAPTER`；
     * 无条件覆盖会把他明确的选择抹掉。
     */
    @Test
    fun `迁移只改写恰好等于旧出厂值的持久化项`() {
        val body = functionBody("migrateBluetoothHandoffToDisableAdapter")
        assertTrue(
            "迁移必须比对 LEGACY_DEFAULT_BT_HANDOFF",
            body.contains("LEGACY_DEFAULT_BT_HANDOFF"),
        )
        assertTrue(
            "迁移必须读 KEY_BT_HANDOFF_POLICY 才知道当前值",
            body.contains("KEY_BT_HANDOFF_POLICY"),
        )
    }

    /**
     * ⚠️ 判据文本必须是**裸字符串**，不能用 `CarBluetoothHandoffPolicy.DISCONNECT_LINK.name`。
     *
     * 判据是"上一版出厂时**写进偏好里的那串文本**"，不是"这个枚举某个成员现在叫什么"。
     * 将来若重命名枚举成员，用 `.name` 会让判据悄悄失效（迁移永不命中 —— 又一次静默失效）。
     */
    @Test
    fun `迁移判据不依赖枚举成员名`() {
        val body = functionBody("migrateBluetoothHandoffToDisableAdapter")
        assertFalse(
            "判据不应写成 CarBluetoothHandoffPolicy.XXX.name（重命名后会静默失效）",
            body.contains("CarBluetoothHandoffPolicy."),
        )
    }

    /** 旧出厂值常量本身：必须是裸字符串 `DISCONNECT_LINK`。 */
    @Test
    fun `旧出厂值常量是裸字符串`() {
        assertTrue(
            "LEGACY_DEFAULT_BT_HANDOFF 应为 \"DISCONNECT_LINK\"",
            Regex("""LEGACY_DEFAULT_BT_HANDOFF\s*=\s*"DISCONNECT_LINK"""").containsMatchIn(codeOf()),
        )
    }

    /** 迁移结束后必须置位标记（否则每次 `load` 都会重跑）。 */
    @Test
    fun `迁移结束后置位标记`() {
        val body = functionBody("migrateBluetoothHandoffToDisableAdapter")
        assertTrue(
            "迁移必须在结尾置位自己的标记",
            body.contains("putBoolean(KEY_BT_HANDOFF_MIGRATED") && body.contains(".apply()"),
        )
    }

    /** 改写后的值取的是**新默认值**（`fallback.bluetoothHandoffPolicy.name`），不是硬编码的字符串。 */
    @Test
    fun `改写目标取新默认值而不是硬编码`() {
        val body = functionBody("migrateBluetoothHandoffToDisableAdapter")
        assertTrue(
            "改写目标应取 fallback.bluetoothHandoffPolicy.name",
            body.contains("fallback.bluetoothHandoffPolicy.name"),
        )
        assertFalse(
            "改写目标不该是硬编码的 DISABLE_ADAPTER 字符串（换默认值时会被漏掉）",
            Regex(""""DISABLE_ADAPTER"""").containsMatchIn(body),
        )
    }

    /**
     * 车机日志 1792037679717 证明：恢复 enable() 返回 true 后约 0.6 秒，新无线启动仍抛
     * "Bluetooth is not enabled"；因此不能把 binder 接受请求当成适配器已 ready。
     */
    @Test
    fun `新无线会话必须等待本应用恢复的蓝牙适配器到STATE_ON`() {
        val bridge = sourceOf(
            "car/core-bridge/src/main/java/com/dbtool/carplay/bridge/CarPlaySessionBridge.kt",
        ).replace(Regex("(?s)/\\*.*?\\*/"), " ").replace(Regex("(?m)//.*$"), " ")
        val startIndex = bridge.indexOf("private fun startInternal()")
        assertTrue("找不到 startInternal", startIndex >= 0)
        val startBody = bridge.substring(startIndex).substringBefore("private fun failStart")
        val barrier = startBody.indexOf("restoreBeforeWirelessStart()")
        val build = startBody.indexOf("buildSession()")
        assertTrue("蓝牙恢复屏障必须早于建会话/无线启动", barrier >= 0 && barrier < build)

        val releaser = sourceOf(
            "car/core-bridge/src/main/java/com/dbtool/carplay/bridge/bluetooth/CarBluetoothHandoffReleaser.kt",
        ).replace(Regex("(?s)/\\*.*?\\*/"), " ").replace(Regex("(?m)//.*$"), " ")
        val restore = releaser.substringAfter("private fun restoreAdapter()", "").substringBefore("private fun adapter()")
        assertTrue("恢复必须等到 BluetoothAdapter.STATE_ON", restore.contains("waitUntilAdapterOn(adapter"))
        assertTrue("STATE_ON 轮询实现必须存在", releaser.contains("private fun waitUntilAdapterOn("))
        val gate = releaser.substringAfter("fun restoreBeforeWirelessStart(", "").substringBefore("fun onSessionEnded(")
        assertTrue("新会话必须等待恢复 worker 完成", gate.contains("latch.await("))
        assertTrue("无用户关闭标记时不得擅自 enable", gate.contains("if (!prefs.adapterDisabledByUs) return true"))
    }

    // ── 测试工具 ────────────────────────────────────────────────────────────

    private fun loadBody(): String {
        val source = codeOf()
        val loadIndex = source.indexOf("override fun load()")
        assertTrue("找不到 load()", loadIndex >= 0)
        return source.substring(loadIndex).substringBefore("return CarSessionSettings(")
    }

    /** 取某个 `private fun <name>(...)` 的函数体（到下一个 `private fun` 或文件尾为止）。 */
    private fun functionBody(name: String): String {
        val source = codeOf()
        val start = source.indexOf("private fun $name(")
        assertTrue("找不到函数 $name", start >= 0)
        val rest = source.substring(start + 1)
        val next = rest.indexOf("\n    private fun ")
        return if (next >= 0) rest.substring(0, next) else rest
    }

    /**
     * 读 [SharedPreferencesCarSettingsStore].kt 并**剥掉注释**。
     *
     * 剥注释是必需的：这些 KDoc 为了讲清"为什么必须迁移"必然引用
     * `DISCONNECT_LINK` 等字面量，不剥掉会让断言把注释当代码。
     */
    private fun codeOf(): String = sourceOf(
        "car/core-bridge/src/main/java/com/dbtool/carplay/bridge/" +
            "SharedPreferencesCarSettingsStore.kt",
    )
        .replace(Regex("(?s)/\\*.*?\\*/"), " ")
        .replace(Regex("(?m)//.*$"), " ")

    private fun sourceOf(relative: String): String {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile
        }
        throw AssertionError("找不到源码：$relative")
    }
}
