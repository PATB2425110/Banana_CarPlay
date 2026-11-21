package com.dbtool.carplay.bridge.update

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 静默更新链路的**加固**回归测试（2026-10-15）。
 *
 * ## 背景
 *
 * 排查「静默更新到底能不能用」时，逐段读代码找出四类会导致**静默装不上、
 * 且不报错**的缺陷，并逐一修掉。本文件把每一处都钉住 —— 它们全都是
 * "错了也不会有任何东西变红"的类型：
 *
 * 1. **拿坏包去装** —— 本地缓存的 APK 从不校验身份/版本号。文件被截断、
 *    或服务端换过包（同名不同内容）时，会白跑一遍六级降级脚本，最后失败，
 *    还顺手烧掉一次熔断额度（上限只有 2 次）⇒ 一个**本来能装好**的版本
 *    被永久熔断。
 * 2. **装错包** —— 服务端把 `download_url` 配成别的 APK 时，会把别的应用
 *    交给安装器（或记成 DBPlay 的更新）。
 * 3. **下载连接泄漏** —— 非 2xx 提前 `return` 时不 `disconnect()`，
 *    连接池里堆着不释放的 socket。
 * 4. **HTTPS 降级** —— 从 https 跳到 http 的下载重定向被放行，中间人可换包。
 *
 * ## 为什么混用"纯函数断言"和"静态读源码"
 *
 * - [validateCarUpdateApkIdentity] 与 [isHttpsDowngrade] 是**纯函数**，
 *   直接跑真值即可（最可靠）。
 * - 但"**在哪个时机调用它**"是接线，没有运行时可测的接缝
 *   （`CarUpdateManager` 要 `Context` + 共享 adb 会话，本模块未配 Robolectric）。
 *   所以那一半用静态读源码断言，与 `SilentUpdateDefaultOnContractTest`
 *   同一套路；**断言字面量前必须先剥注释**（项目纪律），否则会匹配到本文件
 *   与生产代码注释里的同名字面量 ⇒ 假通过。
 */
class CarUpdateHardeningTest {

    // ---------------------------------------------------- 纯函数：APK 身份

    @Test
    fun `包名与版本号都对时校验通过`() {
        assertNull(
            validateCarUpdateApkIdentity(
                actual = CarUpdateApkIdentity("io.dushu.car", 202L),
                expectedPackageName = "io.dushu.car",
                expectedVersionCode = 202L,
            ),
        )
    }

    @Test
    fun `包名解析不出来要拦下`() {
        assertNotNull(
            "解不出包名说明 APK 损坏或不是合法 APK，绝不能交给安装器",
            validateCarUpdateApkIdentity(
                actual = CarUpdateApkIdentity(null, 202L),
                expectedPackageName = "io.dushu.car",
                expectedVersionCode = 202L,
            ),
        )
        assertNotNull(
            validateCarUpdateApkIdentity(
                actual = CarUpdateApkIdentity("   ", 202L),
                expectedPackageName = "io.dushu.car",
                expectedVersionCode = 202L,
            ),
        )
    }

    @Test
    fun `包名不匹配要拦下`() {
        val reason = validateCarUpdateApkIdentity(
            actual = CarUpdateApkIdentity("com.other.app", 202L),
            expectedPackageName = "io.dushu.car",
            expectedVersionCode = 202L,
        )
        assertNotNull("服务端配错下载地址时，装的是别的应用", reason)
        assertTrue("失败原因要能看出是包名问题，实际：$reason", reason!!.contains("包名"))
    }

    @Test
    fun `包内版本号非法要拦下`() {
        assertNotNull(
            validateCarUpdateApkIdentity(
                actual = CarUpdateApkIdentity("io.dushu.car", 0L),
                expectedPackageName = "io.dushu.car",
                expectedVersionCode = 202L,
            ),
        )
    }

    @Test
    fun `包内版本号与服务端不一致要拦下`() {
        val reason = validateCarUpdateApkIdentity(
            actual = CarUpdateApkIdentity("io.dushu.car", 201L),
            expectedPackageName = "io.dushu.car",
            expectedVersionCode = 202L,
        )
        assertNotNull(
            "服务端换过包 / 缓存串号时，装下去的不是我们以为的那个版本",
            reason,
        )
        assertTrue("失败原因要能看出是版本号问题，实际：$reason", reason!!.contains("版本号"))
    }

    // ------------------------------------------------ 纯函数：HTTPS 降级

    @Test
    fun `https 跳到 http 判为降级`() {
        assertTrue(
            "从 TLS 跳到明文 = 可被中间人换包，必须拒绝",
            isHttpsDowngrade(
                "https://p.sosun.cc/app.apk",
                "http://p.sosun.cc/app.apk",
            ),
        )
    }

    @Test
    fun `同为 https 不算降级`() {
        assertFalse(
            isHttpsDowngrade(
                "https://a.example.com/app.apk",
                "https://cdn.example.com/app.apk",
            ),
        )
    }

    @Test
    fun `http 起步不算降级`() {
        assertFalse(
            "本来就是明文（内网地址），谈不上降级 —— 不能误伤正常的 http 分发",
            isHttpsDowngrade(
                "http://192.168.1.10/app.apk",
                "http://192.168.1.11/app.apk",
            ),
        )
    }

    @Test
    fun `http 升到 https 不算降级`() {
        assertFalse(
            isHttpsDowngrade(
                "http://a.example.com/app.apk",
                "https://a.example.com/app.apk",
            ),
        )
    }

    @Test
    fun `解析不了的地址不判降级`() {
        assertFalse(
            "地址畸形时交给后面的协议校验去拒，别在这里抛",
            isHttpsDowngrade("https://a.example.com/app.apk", "::not a url::"),
        )
    }

    // -------------------------------------------------- 静态接线契约

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

    private val manager =
        "car/core-bridge/src/main/java/com/dbtool/carplay/bridge/update/CarUpdateManager.kt"
    private val installer =
        "car/core-bridge/src/main/java/com/dbtool/carplay/bridge/update/CarApkInstaller.kt"
    private val downloader =
        "car/core-bridge/src/main/java/com/dbtool/carplay/bridge/update/CarApkDownloader.kt"

    @Test
    fun `下载缓存命中前必须校验包`() {
        val code = codeOf(manager)
        assertTrue(
            "download() 命中本地缓存时必须先 validateUpdateApk —— " +
                "否则坏包会被当成'已下好'直接进待安装",
            code.contains("installer.validateUpdateApk(cached, versionCode)"),
        )
        assertTrue(
            "缓存包校验失败时必须删掉并继续下载（而不是留着它）",
            Regex("""校验失败[\s\S]{0,200}cached\.delete\(\)""").containsMatchIn(code),
        )
    }

    @Test
    fun `恢复与静默路径也必须校验包`() {
        val code = codeOf(manager)
        // restoreDownloaded() 与 notifyIdleWindow() 各有一处。
        val hits = Regex("""validateUpdateApk\(file, versionCode\)""").findAll(code).count()
        assertTrue(
            "restoreDownloaded / notifyIdleWindow / install 三处都要校验包，实际只找到 $hits 处",
            hits >= 3,
        )
    }

    @Test
    fun `静默路径的校验必须在熔断计数之前`() {
        val code = codeOf(manager)
        val validateIdx = code.indexOf("validateUpdateApk(file, versionCode)")
        val recordIdx = code.indexOf("prefs.recordSilentAttempt(versionCode)")
        assertTrue("找不到 recordSilentAttempt 调用点", recordIdx >= 0)
        assertTrue(
            "坏包是'包的问题'不是'通道的问题' —— 必须先校验再记熔断计数，" +
                "否则坏包会白烧一次熔断额度（上限只有 2 次）",
            validateIdx in 1 until recordIdx,
        )
    }

    @Test
    fun `安装器调用点必须带上目标版本号`() {
        val code = codeOf(manager)
        assertFalse(
            "installViaAdb(file) 不带版本号 = 没走校验，必须传 versionCode",
            Regex("""installViaAdb\(file\)""").containsMatchIn(code),
        )
        assertFalse(
            "installWithoutAdb(file) 不带版本号 = 没走校验，必须传 versionCode",
            Regex("""installWithoutAdb\(file\)""").containsMatchIn(code),
        )
        assertTrue(
            "三处 installViaAdb/installWithoutAdb 调用都要带 versionCode",
            Regex("""installViaAdb\(file, versionCode\)""").findAll(code).count() >= 2,
        )
    }

    @Test
    fun `两条静默安装入口内部也要自校验`() {
        val code = codeOf(installer)
        assertTrue(
            "installWithoutAdb 必须先校验再往下走",
            Regex("""fun installWithoutAdb\(file: File, expectedVersionCode: Long\)[\s\S]{0,400}validateUpdateApk\(file, expectedVersionCode\)""")
                .containsMatchIn(code),
        )
        assertTrue(
            "installViaAdb 必须先校验再 push（避免白跑六级降级）",
            Regex("""fun installViaAdb\(file: File, expectedVersionCode: Long\)[\s\S]{0,400}validateUpdateApk\(file, expectedVersionCode\)""")
                .containsMatchIn(code),
        )
    }

    @Test
    fun `下载连接与协议必须守住`() {
        val code = codeOf(downloader)
        assertTrue(
            "download() 必须用 finally 兜底 disconnect，否则非 2xx 提前返回会泄漏连接",
            Regex("""finally\s*\{[\s\S]{0,120}disconnect\(\)""").containsMatchIn(code),
        )
        assertTrue(
            "重定向到 http 时必须拒绝（HTTPS 降级防护）",
            code.contains("isHttpsDowngrade"),
        )
        assertTrue(
            "openOnce 必须校验协议只允许 http/https",
            Regex("""protocol\.equals\("http"[\s\S]{0,200}protocol\.equals\("https"""").containsMatchIn(code),
        )
        assertTrue(
            "改名必须原子（ATOMIC_MOVE），否则断网会留下半截 .apk",
            code.contains("ATOMIC_MOVE"),
        )
    }

    @Test
    fun `状态上报要能返回结果`() {
        val api =
            "car/core-bridge/src/main/java/com/dbtool/carplay/bridge/update/CarUpdateApi.kt"
        val code = codeOf(api)
        assertTrue(
            "reportStatus 必须返回结果对象，否则'上报失败'永远查不出来",
            code.contains("StatusReportResult"),
        )
        assertTrue(
            "reportStatus 也必须 finally disconnect",
            Regex("""fun reportStatus[\s\S]*?finally\s*\{[\s\S]{0,120}disconnect\(\)""").containsMatchIn(code),
        )
    }

    /** 一个健全性断言：常量上限本身没被这次改动顺手放大。 */
    @Test
    fun `熔断上限仍是 2`() {
        assertEquals(2, SILENT_MAX_ATTEMPTS)
    }
}
