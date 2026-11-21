package com.dbtool.carplay.geely

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * PATCH-0088 契约守卫：副屏画面 180° 翻转必须**串成一条链**。
 *
 * ## 背景（2026-10-11 真车）
 *
 * 车机（CS11 / `cs11_high`）的仪表副屏是**倒装**的 —— HDMI 屏塞进原仪表壳体，
 * 上下颠倒装。这种翻转是**物理**的：诊断包里 5 块屏的 `rotation` **全是 0**，
 * 系统与 iPhone 都不知道，只能在呈现层把画面转 180°。
 *
 * ## 为什么是源码级断言
 *
 * `Presentation` / `TextureView` 需要真 `Context` + 真 `Display`，纯 JVM 测试
 * 跑不起来（同 `GeelyClusterPresentationNoReattachTest` 的理由）。
 * 能在 JVM 里确证的是**写法契约**：
 *
 * 1. 呈现层按 `flip180` 决定旋转角，且转的是**内容根**（整面板）而不是 TextureView
 *    —— 只翻内容不动区域，对"整屏倒装"仍然错位；
 * 2. presenter 实现能力接口，且**就地**下发（不重建窗口、不重连）；
 * 3. 设置项已持久化（新键 + 读写两侧都有）。
 *
 * 真机上"画面到底转没转"仍要装车验证，这里只钉住"写法没跑偏"。
 */
class GeelyClusterFlip180ContractTest {

    /** 在模块根、`car/platform-geely` 相对目录、仓库根三处找文件（对齐既有测试）。 */
    private fun readSource(vararg relCandidates: String): String {
        val searched = mutableListOf<String>()
        for (rel in relCandidates) {
            val direct = Paths.get(rel)
            if (Files.exists(direct)) return String(Files.readAllBytes(direct))
            searched += direct.toAbsolutePath().toString()
            var dir: Path? = Paths.get(".").toAbsolutePath()
            while (dir != null) {
                val target = dir.resolve(rel)
                if (Files.exists(target)) return String(Files.readAllBytes(target))
                searched += target.toString()
                dir = dir.parent
            }
        }
        throw AssertionError("找不到源码文件，已尝试：${searched.joinToString("\n")}")
    }

    private fun presentationSource(): String = readSource(
        "src/main/java/com/dbtool/carplay/geely/GeelyClusterPresentation.kt",
        "car/platform-geely/src/main/java/com/dbtool/carplay/geely/GeelyClusterPresentation.kt",
    )

    private fun presenterSource(): String = readSource(
        "src/main/java/com/dbtool/carplay/geely/GeelyClusterPresenter.kt",
        "car/platform-geely/src/main/java/com/dbtool/carplay/geely/GeelyClusterPresenter.kt",
    )

    private fun storeSource(): String = readSource(
        "car/core-bridge/src/main/java/com/dbtool/carplay/bridge/SharedPreferencesCarSettingsStore.kt",
    )

    /**
     * 呈现层：按 `flip180` 决定角度，且**旋转可见内容块**（原地翻转）。
     *
     * 那块面板报 1920×1080、实际只显示 1920×720，内容就摆在 720 高的带子里。
     * 必须**在这条带子内部原地转** —— 去转整块 1080 高的根视图会把带子移到
     * `360..1080` 行，一半落到不显示的区里、画面被裁掉一半。
     */
    @Test
    fun `呈现层把 flip180 落成可见块的 180 度原地旋转`() {
        val src = presentationSource()
        assertTrue(
            "必须由 flip180 决定旋转角",
            src.contains("if (flip180) 180f else 0f"),
        )
        assertTrue(
            "必须旋转可见块（原地翻转），不能转整块面板根视图",
            src.contains("block.rotation = degrees"),
        )
        assertTrue(
            "占位文字同区域，要跟着转",
            src.contains("placeholder?.rotation = degrees"),
        )
        assertTrue(
            "必须提供就地切换入口",
            src.contains("fun setFlip180(enabled: Boolean)"),
        )
        assertTrue(
            "建窗时必须把上层灌入的值带进窗口",
            src.contains("private var flip180: Boolean = false"),
        )
    }

    /**
     * presenter：实现能力接口 + 就地下发 + 建窗带上当前值。
     *
     * "就地"是硬要求 —— 翻转不改变向 iPhone 的声明（尺寸/安全区/帧率都不动），
     * 若实现成"重建窗口"就会顺带重连 CarPlay，为转个画面把音乐掐断。
     */
    @Test
    fun `presenter 实现翻转能力并就地下发`() {
        val src = presenterSource()
        assertTrue("必须实现 CarClusterFlipControl", src.contains("CarClusterFlipControl"))
        assertTrue(
            "窗口已在时必须立刻应用（就地生效，不重建窗口）",
            src.contains("presentation?.setFlip180(enabled)"),
        )
        assertTrue(
            "建窗路径必须带上当前翻转值",
            src.contains("GeelyClusterPresentation(appContext, display, spec, flip180)"),
        )
    }

    /** 设置项必须真的持久化，否则重启就丢（选屏字段踩过这个坑，见 2026-10-08 注释）。 */
    @Test
    fun `翻转设置项已持久化`() {
        val src = storeSource()
        assertTrue("必须有新键", src.contains("\"cluster_flip_180\""))
        assertTrue("读取侧必须有它", src.contains("clusterFlip180 = prefs.getBoolean(KEY_CLUSTER_FLIP_180"))
        assertTrue("写入侧必须有它", src.contains(".putBoolean(KEY_CLUSTER_FLIP_180"))
    }
}
