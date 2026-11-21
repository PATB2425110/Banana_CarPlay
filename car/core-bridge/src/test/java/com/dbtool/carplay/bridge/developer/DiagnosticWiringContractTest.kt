package com.dbtool.carplay.bridge.developer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「诊断日志 / 蓝牙补恢复」在 App 侧的**接线契约**（PATCH-0095，2026-10-11）。
 *
 * ## 为什么是源码级断言
 *
 * 两处接线点分别落在 `car/app`（`CarPlayApplication` / `CarAppController`）——
 * 那是 **application 模块、没有 test source set**（见 `CarAppController` 里对
 * `CarVideoCanvas.CANVAS_ASPECT_TOLERANCE` 的同款说明）。所以只能读源码。
 *
 * ## 钉的是哪四件事
 *
 * 1. **应用日志随进程常驻**（`CarPlayApplication.onCreate` → `startAppLogging()`）：
 *    否则"空闲期发生的后台静默更新"永远采不到，而那正是本轮排查的缺口。
 * 2. **全系统日志跟会话启停**（`startSession()` 开 / `SessionEnded` 关）：
 *    它贵（`VehicleHal` 刷屏约 100KB/s，写 eMMC），不该常驻。
 * 3. **会话开始前就开日志**：会话启动那几秒正是最容易出问题的地方，
 *    晚一行就可能漏掉开头。
 * 4. **启动时补恢复蓝牙适配器**：`DISABLE_ADAPTER` 档关的是整个适配器，
 *    进程被杀这条路径不经过任何会话事件 ⇒ 不补恢复会留下"蓝牙一直关着"，
 *    连带车钥匙/胎压全断。
 *
 * ⚠️ 读之前**必须剥注释**：这些调用点的注释里大量引用方法名（解释"为什么"），
 * 不剥掉会让断言把注释当代码（假通过）。
 */
class DiagnosticWiringContractTest {

    // ── 1 & 4. App 启动路径 ─────────────────────────────────────────────────

    /** 应用日志（`--pid=本进程`）必须随进程常驻 —— 空闲期的静默更新只能靠它。 */
    @Test
    fun `启动时开应用日志`() {
        val body = applicationOnCreate()
        assertTrue(
            "CarPlayApplication.onCreate 必须调 services.startAppLogging()",
            body.contains("services.startAppLogging()"),
        )
    }

    /**
     * 启动时必须补一次蓝牙适配器恢复。
     *
     * 没有这一条，`DISABLE_ADAPTER` 档 + "会话中途进程被杀"就会留下
     * 蓝牙一直关着（车钥匙/胎压/耳机全断，用户不知道该去哪儿开回来）。
     */
    @Test
    fun `启动时补恢复蓝牙适配器`() {
        val body = applicationOnCreate()
        assertTrue(
            "CarPlayApplication.onCreate 必须调 services.restoreBluetoothAdapterIfLeftDisabled()",
            body.contains("services.restoreBluetoothAdapterIfLeftDisabled()"),
        )
    }

    /** 补恢复必须**先于**日志启动之后的收尾**、并且不能把启动搞崩**（`runCatching`）。 */
    @Test
    fun `启动路径的两处调用都兜异常`() {
        val body = applicationOnCreate()
        // 两处都应包在 runCatching 里：车机上"记录日志失败"绝不能变成"App 起不来"。
        val guarded = Regex("""runCatching \{ services\.(startAppLogging|restoreBluetoothAdapterIfLeftDisabled)\(\) \}""")
        assertTrue(
            "启动路径的两处调用都必须包 runCatching",
            guarded.findAll(body).count() == 2,
        )
    }

    // ── 2 & 3. 会话启停 ─────────────────────────────────────────────────────

    /** 会话启动时把**全系统**滚动日志也开起来（这一份贵，只覆盖会话）。 */
    @Test
    fun `会话启动时开全系统日志`() {
        val body = functionBody("startSession")
        assertTrue(
            "startSession() 必须调 services.startSessionLogging()",
            body.contains("services.startSessionLogging()"),
        )
    }

    /**
     * ⚠️ 开日志必须排在 `session.start()` **之前**。
     *
     * 会话启动路径（热点拉起 → adb 通道 → iPhone 握手）那几秒正是最容易
     * 出问题的地方，晚一行就可能漏掉整段开头。
     */
    @Test
    fun `开日志排在 session start 之前`() {
        val body = functionBody("startSession")
        val logging = body.indexOf("startSessionLogging()")
        val start = body.indexOf("session.start()")
        assertTrue("找不到 startSessionLogging()", logging >= 0)
        assertTrue("找不到 session.start()", start >= 0)
        assertTrue(
            "startSessionLogging() 必须在 session.start() 之前（否则漏掉会话开头）",
            logging < start,
        )
    }

    /** 会话结束收掉**全系统**那一份；应用日志继续（紧接着可能就是静默更新窗口）。 */
    @Test
    fun `会话结束时收全系统日志`() {
        val body = eventBranch("CarSessionEvent.SessionEnded ->")
        assertTrue(
            "SessionEnded 分支必须调 services.stopSessionLogging()",
            body.contains("services.stopSessionLogging()"),
        )
    }

    /**
     * ⚠️ 会话结束时**不能**调 `stopAllLogging()`。
     *
     * 那会把应用日志一起停掉 —— 而会话结束后紧接着就是后台静默更新的空闲窗口，
     * 那正是最需要日志的时候（本轮排查的缺口就在这里）。
     */
    @Test
    fun `会话结束不停应用日志`() {
        val body = eventBranch("CarSessionEvent.SessionEnded ->")
        assertFalse(
            "SessionEnded 分支不该调 stopAllLogging()（会把应用日志也停掉）",
            body.contains("stopAllLogging()"),
        )
    }

    // ── 记录器状态进诊断包 ──────────────────────────────────────────────────

    /** 诊断包要报"记录器在不在跑"，否则分不清"没起来过"与"起来了但刚 roll 过"。 */
    @Test
    fun `诊断包接入了记录器状态`() {
        val services = sourceOf(
            "car/core-bridge/src/main/java/com/dbtool/carplay/bridge/CarAppServices.kt",
        ).stripped()
        assertTrue(
            "CarAppServices 必须把 logcatRecorderStatus() 接给 CarDeveloperMode",
            services.contains("recorderStatusProvider = { logcatRecorderStatus() }"),
        )
    }

    // ── 测试工具 ────────────────────────────────────────────────────────────

    private fun applicationOnCreate(): String {
        val source = sourceOf(
            "car/app/src/main/java/com/dbtool/carplay/app/CarPlayApplication.kt",
        ).stripped()
        val start = source.indexOf("override fun onCreate()")
        assertTrue("找不到 CarPlayApplication.onCreate", start >= 0)
        return source.substring(start)
    }

    /** 取 `CarAppController` 里某个 `when` 分支的函数体。 */
    private fun eventBranch(marker: String): String {
        val source = sourceOf(
            "car/app/src/main/java/com/dbtool/carplay/app/CarAppController.kt",
        ).stripped()
        val start = source.indexOf(marker)
        assertTrue("找不到分支 $marker", start >= 0)
        val rest = source.substring(start + marker.length)
        val next = Regex("""\n {12}(is CarSessionEvent\.|CarSessionEvent\.)""").find(rest)
        return if (next != null) rest.substring(0, next.range.first) else rest
    }

    /** 取 `CarAppController` 里某个 `fun <name>(` 的函数体。 */
    private fun functionBody(name: String): String {
        val source = sourceOf(
            "car/app/src/main/java/com/dbtool/carplay/app/CarAppController.kt",
        ).stripped()
        val start = source.indexOf("fun $name(")
        assertTrue("找不到函数 $name", start >= 0)
        val rest = source.substring(start + 1)
        val next = Regex("""\n {4}(override )?(private )?fun """).find(rest)
        return if (next != null) rest.substring(0, next.range.first) else rest
    }

    /**
     * 剥掉注释。
     *
     * 剥注释是必需的：这些接线点的 KDoc 为了讲清"为什么必须常驻/必须先开"
     * 必然引用 `startAppLogging()` / `stopAllLogging()` 这些名字，
     * 不剥掉会让断言把注释当代码（假通过或假失败）。
     */
    private fun String.stripped(): String = this
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
