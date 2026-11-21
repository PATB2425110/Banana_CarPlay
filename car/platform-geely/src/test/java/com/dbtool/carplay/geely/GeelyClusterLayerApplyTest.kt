package com.dbtool.carplay.geely

import android.view.View
import com.dbtool.carplay.api.display.ClusterLayerMode
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 平台层（实车真正建窗的 `GeelyClusterPresentation`）三模式分层回归。
 *
 * 这些是纯逻辑单测：视图几何与可见性先算成 [ClusterLayerViewPlan]，
 * 再由 [applyClusterLayerPlan] 下发。窗口/Surface 生命周期不在接口里，
 * 从类型上保证切模式不会顺手拆流。
 */
class GeelyClusterLayerApplyTest {

    private val presentationSource = File(
        "src/main/java/com/dbtool/carplay/geely/GeelyClusterPresentation.kt",
    )

    private fun readPresentationCode(): String {
        val file = sequenceOf(
            presentationSource,
            File("car/platform-geely").resolve(presentationSource),
        ).firstOrNull { it.exists() } ?: error("找不到 GeelyClusterPresentation.kt")
        val raw = file.readText()
        val noBlock = raw.replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
        return noBlock.lines().joinToString("\n") { line ->
            val i = line.indexOf("//")
            if (i >= 0) line.substring(0, i) else line
        }
    }

    private fun refreshLayersBody(): String {
        val code = readPresentationCode()
        val start = code.indexOf("private fun refreshLayers(")
        assertTrue("找不到 refreshLayers()", start >= 0)
        val end = code.indexOf("private val vitalsTick", start)
        assertTrue("找不到 vitalsTick 作为 refreshLayers() 的结束界限", end > start)
        return code.substring(start, end)
    }

    /** 截取从 [signature] 到 [nextSignature] 之间的源码（用于锁住“某方法不许做什么”）。 */
    private fun methodBody(signature: String, nextSignature: String): String {
        val code = readPresentationCode()
        val start = code.indexOf(signature)
        assertTrue("找不到 $signature", start >= 0)
        val end = code.indexOf(nextSignature, start + signature.length)
        assertTrue("找不到 $nextSignature 作为 $signature 的结束界限", end > start)
        return code.substring(start, end)
    }

    @Test
    fun bothModeShowsThemeAndInsetsVideoBySafeArea() {
        val plan = clusterLayerViewPlan(
            mode = ClusterLayerMode.BOTH,
            panelWidth = 1920,
            visibleHeight = 720,
            themeAvailable = true,
        )

        assertTrue("BOTH 必须显示主题层", plan.themeVisible)
        assertTrue("BOTH 必须保留视频层", plan.clipVisible)
        assertTrue("BOTH 应让皮肤给视频让出中间区", plan.centerReserved)
        // 2026-10-22 车主口径：CarPlay 视频层改全屏 1920x720，不再按安全区内缩。
        // 原厂左右表是 DIM 固件层，自己会盖住两侧。
        assertEquals(0, plan.clipLeft)
        assertEquals(0, plan.clipTop)
        assertEquals(1920, plan.clipWidth)
        assertEquals(720, plan.clipHeight)
    }

    @Test
    fun carplayOnlyFillsPanelAndHidesTheme() {
        val plan = clusterLayerViewPlan(
            mode = ClusterLayerMode.CARPLAY_ONLY,
            panelWidth = 1920,
            visibleHeight = 720,
            themeAvailable = true,
        )

        assertFalse("CARPLAY_ONLY 不显示主题层", plan.themeVisible)
        assertTrue("CARPLAY_ONLY 必须显示视频层", plan.clipVisible)
        assertEquals(0, plan.clipLeft)
        assertEquals(0, plan.clipTop)
        assertEquals(1920, plan.clipWidth)
        assertEquals(720, plan.clipHeight)
    }

    @Test
    fun themeOnlyKeepsVideoInvisibleButNotGone() {
        val plan = clusterLayerViewPlan(
            mode = ClusterLayerMode.THEME_ONLY,
            panelWidth = 1920,
            visibleHeight = 720,
            themeAvailable = true,
        )

        assertTrue("THEME_ONLY 必须显示主题层", plan.themeVisible)
        assertFalse("THEME_ONLY 视频层不可见", plan.clipVisible)
        assertEquals(View.INVISIBLE, clipVisibilityFor(plan.clipVisible))
        assertNotEquals(
            "视频层禁止用 GONE：会脱离布局并可能销毁 Surface",
            View.GONE,
            clipVisibilityFor(plan.clipVisible),
        )
    }

    @Test
    fun missingThemeHidesThemeLayerButKeepsMode() {
        val plan = clusterLayerViewPlan(
            mode = ClusterLayerMode.THEME_ONLY,
            panelWidth = 1920,
            visibleHeight = 720,
            themeAvailable = false,
        )

        assertEquals(ClusterLayerMode.THEME_ONLY, plan.mode)
        assertFalse("主题为空/皮肤未注入 ⇒ themeVisible=false", plan.themeVisible)
        assertFalse("没有主题就无需给皮肤预留中间区", plan.centerReserved)
        assertFalse(plan.clipVisible)
    }

    // ---------------------------------------------------------- 2026-10-22 解耦（P0）
    //
    // 三态模式之外再叠一个“导航层运行时开关”。四象限必须各自独立：
    // 关导航不得隐藏主题；关主题不得隐藏导航；两边都关才允许隐藏窗口。

    @Test
    fun bothOnShowsThemeAndNavigation() {
        val plan = clusterLayerViewPlan(
            mode = ClusterLayerMode.BOTH,
            panelWidth = 1920,
            visibleHeight = 720,
            themeAvailable = true,
            navigationVisible = true,
        )

        assertTrue(plan.themeVisible)
        assertTrue(plan.clipVisible)
        assertTrue("两层都开 ⇒ 窗口可见", plan.windowVisible)
    }

    @Test
    fun themeOnNavigationOffKeepsThemeAndWindow() {
        // 导航断流 / 用户关导航：只应影响 clip，主题与窗口不受影响。
        val plan = clusterLayerViewPlan(
            mode = ClusterLayerMode.BOTH,
            panelWidth = 1920,
            visibleHeight = 720,
            themeAvailable = true,
            navigationVisible = false,
        )

        assertTrue("导航关闭不得隐藏主题", plan.themeVisible)
        assertFalse("导航层应隐藏", plan.clipVisible)
        assertTrue("主题仍在画 ⇒ 窗口必须保留", plan.windowVisible)
        assertEquals(View.INVISIBLE, clipVisibilityFor(plan.clipVisible))
    }

    @Test
    fun navigationOnThemeOffKeepsNavigationAndWindow() {
        // 关主题（CARPLAY_ONLY）：导航与视频流绑定不受影响。
        val plan = clusterLayerViewPlan(
            mode = ClusterLayerMode.CARPLAY_ONLY,
            panelWidth = 1920,
            visibleHeight = 720,
            themeAvailable = true,
            navigationVisible = true,
        )

        assertFalse(plan.themeVisible)
        assertTrue("关主题不得隐藏导航", plan.clipVisible)
        assertTrue(plan.windowVisible)
    }

    @Test
    fun bothOffOnlyThenWindowIsHidden() {
        val plan = clusterLayerViewPlan(
            mode = ClusterLayerMode.CARPLAY_ONLY,
            panelWidth = 1920,
            visibleHeight = 720,
            themeAvailable = true,
            navigationVisible = false,
        )

        assertFalse(plan.themeVisible)
        assertFalse(plan.clipVisible)
        assertFalse("两层都关才允许隐藏窗口", plan.windowVisible)
    }

    @Test
    fun defaultNavigationVisibleKeepsLegacyPlan() {
        // 不传新参数时，结果必须与解耦前逐项一致（默认值等价）。
        val legacy = clusterLayerViewPlan(
            mode = ClusterLayerMode.BOTH,
            panelWidth = 1920,
            visibleHeight = 720,
            themeAvailable = true,
        )
        val explicit = clusterLayerViewPlan(
            mode = ClusterLayerMode.BOTH,
            panelWidth = 1920,
            visibleHeight = 720,
            themeAvailable = true,
            navigationVisible = true,
        )

        assertEquals(legacy, explicit)
    }

    @Test
    fun applyingThemeOnlyNeverDetachesOrReleasesStream() {
        val plan = clusterLayerViewPlan(
            mode = ClusterLayerMode.THEME_ONLY,
            panelWidth = 1920,
            visibleHeight = 720,
            themeAvailable = true,
        )
        val target = RecordingLayerTarget()

        applyClusterLayerPlan(plan, target)

        assertEquals(true, target.themeVisible)
        assertEquals(false, target.videoVisible)
        assertEquals(0, target.detachCalls)
        assertEquals(0, target.surfaceReleaseCalls)
        assertEquals(1920, target.bounds?.width)
        assertEquals(720, target.bounds?.height)
    }

    @Test
    fun applyingLayerPlanSourceHasNoWindowLifecycleOrNaviModeCalls() {
        val body = refreshLayersBody()
        assertFalse("切模式不得 detach 窗口", body.contains("detach("))
        assertFalse("切模式不得 dismiss 窗口", body.contains("dismiss("))
        assertFalse("切模式不得解绑 Surface", body.contains("publish(null)"))
        assertFalse("切模式不得改 switchNaviMode", body.contains("switchNaviMode"))
        assertFalse("切模式不得调 stop()", body.contains("stop()"))
        assertFalse(
            "视频层必须走 INVISIBLE，禁止 GONE",
            body.contains("clip.visibility = View.GONE"),
        )
    }

    @Test
    fun navigationVisibilitySourceHasNoWindowOrSurfaceLifecycle() {
        // P0 契约：单独开关导航层只改视图，不许碰窗口/Surface/会话。
        val body = methodBody(
            "fun applyNavigationVisible(",
            "private fun refreshLayers(",
        )
        assertFalse("不得 detach 窗口", body.contains("detach("))
        assertFalse("不得 dismiss 窗口", body.contains("dismiss("))
        assertFalse("不得解绑 Surface", body.contains("publish(null)"))
        assertFalse("不得直接回调 onSurface(null)", body.contains("onSurface(null)"))
        assertFalse("不得改写整窗 alpha", body.contains("setWindowVisible("))
        assertFalse("不得 stop 会话/窗口", body.contains("stop()"))
    }

    @Test
    fun videoVisibilityNoLongerDrivesWindowAlpha() {
        // P0 契约：导航首帧/视频层显隐只影响视频层，窗口 alpha 由两层并集重算。
        val body = methodBody(
            "fun setVideoVisible(",
            "fun applyDialLayout(",
        )
        assertFalse(
            "setVideoVisible 不得单边 setWindowVisible（C2）",
            body.contains("setWindowVisible("),
        )
        assertTrue(
            "必须改为按两层并集重算窗口",
            body.contains("updateWindowVisibility()"),
        )
    }

    @Test
    fun layerRefreshRecomputesWindowFromBothLayers() {
        val body = refreshLayersBody()
        assertTrue(
            "refreshLayers 必须调用 updateWindowVisibility()（主题 ∨ 导航）",
            body.contains("updateWindowVisibility()"),
        )
    }

    @Test
    fun modeSetBeforeAttachIsAppliedOnceWindowExists() {
        val pending = PendingClusterLayerMode()
        pending.set(ClusterLayerMode.THEME_ONLY)

        var appliedBeforeAttach: ClusterLayerMode? = null
        pending.applyTo(null)
        assertNull("窗口未建立时只暂存，不应用", appliedBeforeAttach)

        pending.applyTo { mode -> appliedBeforeAttach = mode }
        assertEquals(
            "attach 前设置的模式必须在建窗后补应用",
            ClusterLayerMode.THEME_ONLY,
            appliedBeforeAttach,
        )
    }

    private class RecordingLayerTarget : ClusterLayerTarget {
        var themeVisible: Boolean? = null
        var videoVisible: Boolean? = null
        var bounds: Bounds? = null
        var centerReserved: Boolean? = null

        /** 故意不在 [ClusterLayerTarget] 接口里：生产代码无从调用。 */
        var detachCalls = 0
        var surfaceReleaseCalls = 0

        override fun setThemeVisible(visible: Boolean) {
            themeVisible = visible
        }

        override fun setVideoVisible(visible: Boolean) {
            videoVisible = visible
        }

        override fun setVideoBounds(width: Int, height: Int, left: Int, top: Int) {
            bounds = Bounds(width, height, left, top)
        }

        override fun setCenterReserved(reserved: Boolean) {
            centerReserved = reserved
        }

        fun detach() {
            detachCalls += 1
        }

        fun releaseSurface() {
            surfaceReleaseCalls += 1
        }
    }

    private data class Bounds(
        val width: Int,
        val height: Int,
        val left: Int,
        val top: Int,
    )
}
