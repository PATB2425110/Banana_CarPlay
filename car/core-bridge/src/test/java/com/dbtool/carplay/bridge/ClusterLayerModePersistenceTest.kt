package com.dbtool.carplay.bridge

import com.dbtool.carplay.api.display.ClusterLayerMode
import com.dbtool.carplay.api.settings.CarSessionSettings
import com.dbtool.carplay.api.settings.ClusterLayerModeSetting
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图层模式的默认值、字符串协议与 SharedPreferences 接线契约。
 *
 * 存储类构造需要 Android Context + SharedPreferences，纯 JVM 测试跑不起来；
 * 这里用纯函数测值转换，再按项目既有做法静态钉住 load/save 两侧都接了键。
 */
class ClusterLayerModePersistenceTest {

    @Test
    fun `缺省值是仅 CarPlay 模式`() {
        ClusterLayerModeSetting.restore(null)

        // 2026-10-21 口径变更：默认改为「仅 CarPlay」，不再默认双层
        assertEquals(ClusterLayerMode.CARPLAY_ONLY, ClusterLayerMode.DEFAULT)
        assertEquals(ClusterLayerMode.CARPLAY_ONLY, CarSessionSettings.default().clusterLayerMode())
    }

    @Test
    fun `历史主题枚举可解析但不再启用主题图层`() {
        for (mode in ClusterLayerMode.entries) {
            assertEquals(mode, ClusterLayerMode.fromStorage(mode.name))
            ClusterLayerModeSetting.set(mode)
            assertEquals(ClusterLayerMode.CARPLAY_ONLY, ClusterLayerModeSetting.current())
        }
        ClusterLayerModeSetting.restore(null)
    }

    @Test
    fun `非法值与空值都回落仅 CarPlay 模式`() {
        assertEquals(ClusterLayerMode.CARPLAY_ONLY, ClusterLayerMode.fromStorage("BROKEN"))
        assertEquals(ClusterLayerMode.CARPLAY_ONLY, ClusterLayerMode.fromStorage(""))
        assertEquals(ClusterLayerMode.CARPLAY_ONLY, ClusterLayerMode.fromStorage(null))
    }

    @Test
    fun `存储实现读取键并恢复模式`() {
        val code = storeCode()
        assertTrue(
            "load() 必须读取 cluster_layer_mode 并调用 restore",
            code.contains("KEY_CLUSTER_LAYER_MODE") &&
                code.contains("ClusterLayerModeSetting.restore("),
        )
    }

    @Test
    fun `存储实现保存枚举名`() {
        val code = storeCode()
        assertTrue(
            "save() 必须把当前模式写成枚举名",
            code.contains(
                ".putString(KEY_CLUSTER_LAYER_MODE, settings.clusterLayerMode().name)",
            ),
        )
        assertTrue(
            "键名必须按任务书固定为 cluster_layer_mode",
            code.contains(
                "KEY_CLUSTER_LAYER_MODE = \"cluster_layer_mode\"",
            ),
        )
    }

    private fun storeCode(): String {
        val relative =
            "car/core-bridge/src/main/java/com/dbtool/carplay/bridge/SharedPreferencesCarSettingsStore.kt"
        val candidates = listOf(
            File(relative),
            File("../$relative"),
            File("../../$relative"),
            File("../../../$relative"),
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("找不到 $relative（从 ${File(".").absolutePath} 起找过）")
        return file.readText()
            .replace(Regex("(?s)/\\*.*?\\*/"), " ")
            .replace(Regex("(?m)//.*$"), " ")
    }
}
