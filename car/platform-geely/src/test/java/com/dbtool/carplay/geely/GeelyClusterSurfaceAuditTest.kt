package com.dbtool.carplay.geely

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PATCH-0069 回归守卫：对照 jadx 反编译原版（`GeelyCarPlay-0.3.2`）审计出的三处结构性缺失。
 *
 * 这些是**源码形态断言**（不是行为单测）——目的只有一个：后来人重构时若把这三处
 * 又删掉/改回去，CI 立刻红，别指望真车日志才发现。
 *
 * 依据（v0.3.2 jadx 实证）：
 * - `GeelyClusterPresentation$onCreate$3.onSurfaceTextureDestroyed` → `surface.release()`
 * - `GeelyClusterSurfaceOwner.onSurfaceAvailable/Gone` → `clearSurface(111,旧) + setSurface(111,新)`
 * - `GeelyClusterSurfaceOwner.attach$common` → `registerDisplayListener(...)`
 */
class GeelyClusterSurfaceAuditTest {

    private val geelyMain =
        File("src/main/java/com/dbtool/carplay/geely").absoluteFile

    private fun readCodeOnly(name: String): String {
        val text = File(geelyMain, name).readText()
        // 剥掉行注释与块注释：我们只想断言「真实代码形态」，
        // 源码里大段解释「原版怎么做的」的注释不该被当成违规（PATCH-0068 踩过）。
        val noBlock = text.replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
        return noBlock.lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
    }

    /** C：`onSurfaceTextureDestroyed` 必须显式释放包装 Surface（对齐原版 `it.release()`）。 */
    @Test
    fun `destroyed 回调必须显式 release 旧 Surface`() {
        val code = readCodeOnly("GeelyClusterPresentation.kt")
        val start = code.indexOf("onSurfaceTextureDestroyed")
        assertTrue("找不到 onSurfaceTextureDestroyed", start >= 0)
        val end = code.indexOf("override fun onSurfaceTextureUpdated", start)
        val body = code.substring(start, if (end > start) end else code.length)
        assertTrue(
            "onSurfaceTextureDestroyed 里必须显式释放旧 Surface（对齐原版 it.release()），" +
                "否则每次窗口销毁泄漏一条 Surface（PATCH-0069）",
            body.contains(".release()"),
        )
    }

    /** A：换 Surface 必须先作废旧通道（对齐原版 `clearSurface(111,旧) + setSurface(111,新)`）。 */
    @Test
    fun `publish 换 Surface 前必须先申报旧通道作废`() {
        val code = readCodeOnly("GeelyClusterPresentation.kt")
        val start = code.indexOf("private fun publish(")
        assertTrue("找不到 publish()", start >= 0)
        val end = code.indexOf("\n    private fun ", start + 10)
        val body = code.substring(start, if (end > start) end else code.length)
        // 先记旧值 → 作废旧通道（onSurface(null)）→ 再交新值。
        val idxPrevious = body.indexOf("val previous = surface")
        val idxClear = body.indexOf("onSurface(null)")
        val idxForward = body.lastIndexOf("onSurface(next)")
        assertTrue("publish() 必须记下 previous 才能申报旧通道作废", idxPrevious >= 0)
        assertTrue("publish() 必须显式 onSurface(null) 作废旧通道（对齐原版 clearSurface）", idxClear >= 0)
        assertTrue("旧通道作废必须发生在新通道转交之前", idxClear < idxForward)
    }

    /** E：必须注册 DisplayListener（对齐原版 `registerDisplayListener` + 热插拔重建）。 */
    @Test
    fun `Presenter 必须注册屏监听以感知热插拔`() {
        val code = readCodeOnly("GeelyClusterPresenter.kt")
        assertTrue(
            "GeelyClusterPresenter 必须 registerDisplayListener（对齐原版 " +
                "GeelyClusterSurfaceOwner.attach\$common），否则屏插拔不会自动重建窗口（PATCH-0069）",
            code.contains("registerDisplayListener"),
        )
        assertTrue(
            "注册的监听必须在 stop() 里摘掉，否则泄漏（PATCH-0069）",
            code.contains("unregisterDisplayListener"),
        )
        assertTrue(
            "registerDisplayListener 必须防重复注册（start 每次重建都会走）",
            code.contains("displayListenerRegistered"),
        )
    }

    /** D：无自绘叠层时必须把口径写死，避免后来人误判「漏了 setFullScreen」。 */
    @Test
    fun `无自绘叠层的口径必须显式记录`() {
        val code = readCodeOnly("GeelyClusterPresentation.kt")
        val raw = File(geelyMain, "GeelyClusterPresentation.kt").readText()
        assertTrue(
            "本版无 GeelySelfDrawOverlayView，必须在 KDoc 里写明「原版有、本版无」的口径，" +
                "否则后来人会以为 setVideoVisible 漏了 setFullScreen（审计项 D）",
            raw.contains("GeelySelfDrawOverlayView"),
        )
        // 代码里确实没有该对象的实例化（只是注释里提到）。
        assertTrue(
            "GeelySelfDrawOverlayView 不应被实例化（本版不带自绘叠层）",
            !code.contains("GeelySelfDrawOverlayView("),
        )
    }
}
