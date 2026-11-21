package com.dbtool.carplay.bridge

import com.dbtool.carplay.bridge.update.CarInstallScript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设备端静默安装脚本的生成与结果解析。
 *
 * ## 为什么要给"一段 sh 脚本"写单测
 *
 * 这段脚本跑在**车机上**，失败时的表现是"静默不干活"——没有编译期检查、
 * 没有异常、日志还得靠 adb 捞回来。所以**能在 JVM 里钉死的部分必须钉死**：
 *
 * 1. 六级降级**一级都不能少**（少一级 = 某一类车机永远装不上，且不报错）；
 * 2. 改了系统全局设置**必须恢复**（否则这台车机以后所有安装都异常）；
 * 3. 结果标记行的写法必须与 [CarInstallScript.parseSucceeded] 对得上。
 *
 * ⚠️ 测的是**脚本正文里的字符串**，不是"脚本真的能跑"——后者只能在真机上验。
 */
class CarInstallScriptTest {

    @Test
    fun buildReplacesUserPlaceholder() {
        val script = CarInstallScript.build(11)
        assertTrue("必须把占位符替换成真实用户 id", script.contains("U=11"))
        assertFalse("不能留下未替换的占位符", script.contains("__DBPLAY_USER__"))
    }

    /**
     * 六级降级一级都不能少。
     *
     * ⚠️ 断言的字符串要与脚本正文**逐字**对得上：这里故意写得"啰嗦"
     * （连 `-r`、`--user` 都带上），因为漏一个参数就是一次静默失败。
     */
    @Test
    fun buildCoversAllSixFallbackLevels() {
        val script = CarInstallScript.build(0)
        assertTrue("① 裸装", script.contains("pm install -r \"\$APK\""))
        assertTrue("② 多用户", script.contains("pm install -r --user \"\$U\""))
        assertTrue("③ 伪装安装来源", script.contains("-i com.android.vending"))
        assertTrue("⑤ OEM 白名单", script.contains("pm install -r -w --user"))
        assertTrue("④ 关安装校验器", script.contains("verifier_verify_adb_installs"))
        assertTrue("④ 关包校验开关", script.contains("package_verifier_enable"))
        assertTrue("⑥ Flyme 来源校验属性", script.contains("persist.flyme.appstore.verify"))
        assertTrue("⑥ su 兜底", script.contains("su 0 sh -c"))
    }

    /** 改了全局设置就必须恢复 —— 每个 key 至少出现两次（关一次、还原一次）。 */
    @Test
    fun buildAlwaysRestoresVerifierSettings() {
        val script = CarInstallScript.build(0)
        for (key in listOf("verifier_verify_adb_installs", "package_verifier_enable")) {
            val count = Regex(Regex.escape(key)).findAll(script).count()
            assertTrue("$key 只出现 $count 次：关掉后没恢复会让整机安装异常", count >= 2)
        }
    }

    @Test
    fun buildWritesBothResultMarkers() {
        val script = CarInstallScript.build(0)
        assertTrue("必须有成功标记", script.contains(CarInstallScript.RESULT_OK))
        assertTrue("必须有失败标记", script.contains(CarInstallScript.RESULT_FAIL))
    }

    // ---------------------------------------------------------------- 结果解析

    @Test
    fun parseSucceededOnlyOnExactMarkerLine() {
        assertTrue(
            "整行等于 RESULT=OK 才算成功",
            CarInstallScript.parseSucceeded("--- 1)\nSuccess\nRESULT=OK\n"),
        )
        assertFalse(
            "错误信息里引用 Success 不该被当成成功",
            CarInstallScript.parseSucceeded("Failure [INSTALL_FAILED_ALREADY_EXISTS] 非 Success\nRESULT=FAIL"),
        )
        assertFalse("没有标记行就不算成功", CarInstallScript.parseSucceeded("Success\n"))
    }

    /** 取**最后一个** `Failure[...]`：前面每一级降级都会失败一次，最后那条才是结论。 */
    @Test
    fun parseFailureReasonPrefersLastFailureCode() {
        val log = """
            --- 1) pm install -r
            Failure [INSTALL_FAILED_VERIFICATION_FAILURE]
            --- 5) OEM 白名单 -w
            Failure [INSTALL_FAILED_INTERNAL_ERROR]
            RESULT=FAIL
        """.trimIndent()
        assertEquals(
            "系统拒绝安装：INSTALL_FAILED_INTERNAL_ERROR",
            CarInstallScript.parseFailureReason(log),
        )
    }

    /** 没有错误码时退回最后一行有意义输出，但**不能**把 `RESULT=FAIL` 当原因。 */
    @Test
    fun parseFailureReasonFallsBackToLastMeaningfulLine() {
        val log = "=== BEGIN ===\n--- 1)\nabc 出错了\nRESULT=FAIL"
        assertEquals("abc 出错了", CarInstallScript.parseFailureReason(log))
    }

    @Test
    fun parseFailureReasonOnEmptyLog() {
        assertTrue(
            "空日志也要给一句人话，不能返回空串",
            CarInstallScript.parseFailureReason("").isNotBlank(),
        )
    }
}
