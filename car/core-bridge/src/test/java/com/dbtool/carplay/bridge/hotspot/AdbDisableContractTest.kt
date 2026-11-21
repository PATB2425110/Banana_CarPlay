package com.dbtool.carplay.bridge.hotspot

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「关闭 ADB」契约测试（2026-10-15）。
 *
 * ## 为什么需要它
 *
 * 这颗按钮的**全部价值**在于"关掉之后它不会自己回来"。而工程里恰好有两条
 * 会主动把 5556 修回来的自动化路径 —— ADB 专用口看门狗（每 30 秒巡检一次，
 * [CarAdbHotspotDefaults.ADB_WATCHDOG_PERIOD_MILLIS]）与设置页复用探测
 * （[CarAdbHotspotDefaults] 同域的 `checkAdbChannel`）。只要有一处漏了禁用门，
 * 用户的体感就是"**按了关闭，过一会儿又自己开了**"，而这在真机上极难复现。
 *
 * ⇒ 这里把"门必须开在哪几处"钉成断言。与 `HotspotManualSetupContractTest`
 * 同一套写法（读源码文本 + 按函数体定位），目的不是测行为，而是**挡住后人把门拆掉**。
 *
 * ## 与另一条红线的关系
 *
 * 本类**不碰** `HotspotManualSetupContractTest` / `DeviceAdbEnablerPlatformGateTest` /
 * `CarAdbHotspotAutoStartContractTest`。三个都必须继续通过。
 */
class AdbDisableContractTest {

    private fun readSource(relativeFromRepoRoot: String): String {
        // 单测 cwd 是模块目录（car/core-bridge），往上一跳到仓库根。
        val candidates = listOf(
            File("../../$relativeFromRepoRoot"),
            File("../$relativeFromRepoRoot"),
            File(relativeFromRepoRoot),
        )
        return candidates.firstOrNull { it.exists() }?.readText()
            ?: error("找不到源码文件：$relativeFromRepoRoot（cwd=${File(".").absolutePath}）")
    }

    /** 去掉注释后再做断言：注释里出现某个字面量不该被判成"实现了"。 */
    private fun stripComments(source: String): String = source
        .replace(Regex("(?s)/\\*.*?\\*/"), " ")
        .replace(Regex("(?m)//.*$"), " ")

    /** 用花括号配平取出函数体，避免把下一个函数的代码算进来。 */
    private fun functionBody(source: String, signature: String): String {
        val code = stripComments(source)
        val start = code.indexOf(signature)
        assertTrue("找不到函数：$signature", start >= 0)
        val open = code.indexOf('{', start)
        assertTrue("找不到函数体：$signature", open >= 0)
        var depth = 0
        for (index in open until code.length) {
            when (code[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(open + 1, index)
                }
            }
        }
        error("函数花括号未闭合：$signature")
    }

    private fun services(): String =
        readSource("car/core-bridge/src/main/java/com/dbtool/carplay/bridge/CarAppServices.kt")

    @Test
    fun `关闭标记必须落盘且只在显式开启时清除`() {
        val source = services()
        assertTrue(
            "关闭标记必须有独立的 prefs key",
            source.contains("KEY_ADB_USER_DISABLED"),
        )

        val disable = functionBody(source, "fun disableAdbNow()")
        assertTrue(
            "关闭必须同步落盘（commit 同步写；apply 是异步的，界面回执会读不到刚落的状态）",
            disable.contains(".commit()"),
        )
        assertTrue("关闭要写的就是那条禁用标记", disable.contains("KEY_ADB_USER_DISABLED"))

        val initialize = functionBody(source, "fun initializeAdbNow()")
        assertTrue(
            "显式「永久开启 ADB」必须清掉禁用标记，否则用户点了恢复却仍然被门挡住",
            initialize.contains("putBoolean(KEY_ADB_USER_DISABLED, false)"),
        )
    }

    @Test
    fun `关闭后所有会主动写 5556 的自动化路径都要被门挡住`() {
        val source = services()

        val watchdog = source
            .substringAfter("private val adbWatchdog = CarAdbWatchdog(", "")
            .substringBefore("\n    )")
        assertTrue(
            "看门狗 enable 门必须并入禁用标记：不堵这一处，关闭后 30 秒就会被修回来",
            watchdog.contains("KEY_ADB_USER_DISABLED"),
        )

        assertTrue(
            "预热要跳过：通道是用户关的，ensure 只会必然失败并写下一条像故障的日志",
            functionBody(source, "fun prewarmAdb()").contains("isAdbDisabledByUser()"),
        )
        assertTrue(
            "自愈要跳过：自愈的定义是\"修回可用\"，与用户\"要它不可用\"的口径正相反",
            functionBody(source, "fun healAdbNow()").contains("isAdbDisabledByUser()"),
        )
        assertTrue(
            "启动性能优化要跳过：pm compile 要 root，而 root 得先有 shell，必然失败",
            functionBody(source, "fun runStartupPerfCompile(").contains("isAdbDisabledByUser()"),
        )
        assertTrue(
            "关闭态下不得实连探测，否则会产出一条误导性的\"5556 当前不可用\"把用户引去点开启",
            functionBody(source, "fun checkAdbChannel()").contains("isAdbDisabledByUser()"),
        )
    }

    /** 这条是本需求的安全底线：关闭动作**只动 App 自己**，不碰系统。 */
    @Test
    fun `关闭不得写系统属性或停止 adbd`() {
        val disable = functionBody(services(), "fun disableAdbNow()")
        listOf("ctl.stop", "ctl.start", "setprop", "SystemPropertiesBridge", "initializeDedicatedPort")
            .forEach { forbidden ->
                assertFalse(
                    "关闭 ADB 不得出现 `$forbidden`：本动作必须是可逆的 App 侧动作，" +
                        "`ctl.stop adbd` / 写 persist 属不可逆方向（能否起来由 init 与厂商组件决定），" +
                        "要等实车验证之后另行决定",
                    disable.contains(forbidden),
                )
            }
        assertTrue(
            "关闭要收回共享通道（releaseAll 的 KDoc 里那种\"用户点关闭即放开\"的口径）",
            disable.contains("releaseAll()"),
        )
    }

    @Test
    fun `界面在 5556 就绪时置灰而不是隐藏按钮`() {
        val settings = readSource("car/ui/src/main/java/com/dbtool/carplay/ui/screens/SettingsScreen.kt")
        assertTrue("设置页必须有 ADB 开/关这一行", settings.contains("CarAdbSetupRow("))
        assertTrue("关闭按钮必须接到 onDisableAdb 动作", settings.contains("actions.onDisableAdb"))
        assertFalse(
            "旧版在 READY 时把整块按钮藏起来 —— 藏了就没有\"关闭\"的落点，也不能再藏",
            stripComments(settings).contains("channelStatus != CarAdbChannelStatus.READY ||"),
        )
        assertTrue(
            "用户主动关闭的状态要在状态行上如实显示，不能被压成\"5556 不可用\"",
            settings.contains("adbInitialization.disabledByUser"),
        )

        val button = readSource("car/ui/src/main/java/com/dbtool/carplay/ui/components/HotspotAdbSetupButton.kt")
        assertTrue("开启按钮必须支持外部决定可点性", button.contains("enabled: Boolean = true"))
        assertTrue(
            "置灰时必须给出原因（否则用户只看到一颗按不动的灰块）",
            button.contains("disabledHint"),
        )

        val contract = readSource("car/ui/src/main/java/com/dbtool/carplay/ui/CarUiContract.kt")
        assertTrue("契约里必须有 onDisableAdb 且默认实现非空", contract.contains("fun onDisableAdb("))
    }

    @Test
    fun `状态模型用独立字段表达用户口径`() {
        val api = readSource("car/api/src/main/java/com/dbtool/carplay/api/hotspot/CarAdbHotspotContract.kt")
        assertTrue(
            "disabledByUser 必须与 channelStatus 分开：前者是用户口径（重启仍在），后者是一次实测结果",
            api.contains("val disabledByUser: Boolean = false"),
        )
    }
}
