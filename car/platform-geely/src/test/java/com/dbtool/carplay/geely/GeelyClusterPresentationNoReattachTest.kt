package com.dbtool.carplay.geely

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths

/**
 * PATCH-0068 回归守卫：禁止在窗口 attach 之前重建 SurfaceTexture。
 *
 * ## 背景（真车日志 20:16:27 实证）
 *
 * `GeelyClusterPresentation` 一度在 `onStart` / `onViewAttachedToWindow` /
 * 窗口可见性变化三处调用 `reattachIfTextureLost()`，在窗口尚未真正 attach 时
 * `setSurfaceTexture(new SurfaceTexture(false))` 换掉刚建好的纹理。
 * 系统于是丢弃紧随其后的 `onSurfaceTextureAvailable` 回调 ⇒ `surface` 恒为
 * null ⇒ 解码器渲到别的 Surface，仪表永久黑。
 *
 * 原版（jadx 反编译 `GeelyClusterPresentation$onCreate$3`）不做任何重建，
 * 仅靠 `onSurfaceTextureAvailable` 一次交付 Surface；本工程对齐原版。
 *
 * 这条测试锁死源码形态：一旦有人把重建逻辑加回来，立刻红灯。
 */
class GeelyClusterPresentationNoReattachTest {

    private val sourcePath = Paths.get(
        "src/main/java/com/dbtool/carplay/geely/GeelyClusterPresentation.kt",
    )

    private fun readSource(): String {
        // 单测工作目录 = 模块根（car/platform-geely）。
        val candidates = listOf(
            sourcePath,
            Paths.get("car/platform-geely").resolve(sourcePath),
        )
        for (p in candidates) {
            if (Files.exists(p)) return String(Files.readAllBytes(p))
        }
        // 退而求其次：从仓库根相对定位。
        val repoRoot = Paths.get(".").toAbsolutePath()
        var dir: java.nio.file.Path? = repoRoot
        while (dir != null) {
            val target = dir.resolve("car/platform-geely").resolve(sourcePath)
            if (Files.exists(target)) return String(Files.readAllBytes(target))
            dir = dir.parent
        }
        error("找不到 GeelyClusterPresentation.kt，已尝试: $candidates")
    }

    /** 去掉 // 行注释与 /* */ 块注释，只留可执行代码，避免注释里的说明文字误伤断言。 */
    private fun readCodeOnly(): String {
        val raw = readSource()
        val noBlock = raw.replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
        return noBlock.lines().joinToString("\n") { line ->
            val i = line.indexOf("//")
            if (i >= 0) line.substring(0, i) else line
        }
    }

    @Test
    fun noSurfaceTextureRebuildFunctionExists() {
        val src = readCodeOnly()
        assertFalse(
            "禁止重新引入 reattachIfTextureLost：窗口 attach 前重建纹理" +
                "会吞掉 onSurfaceTextureAvailable，导致 surface 恒为 null（PATCH-0068）",
            src.contains("fun reattachIfTextureLost"),
        )
    }

    @Test
    fun noSetSurfaceTextureCallInPresentation() {
        val src = readCodeOnly()
        assertFalse(
            "禁止在 Presenter 内调用 setSurfaceTexture()：应由系统在窗口 attach 后" +
                "回调 onSurfaceTextureAvailable 交付 Surface（PATCH-0068）",
            src.contains("setSurfaceTexture("),
        )
    }

    @Test
    fun onStartDoesNotReattach() {
        val src = readCodeOnly()
        // 截取 onStart 方法体到下一个 override 之前，确认里面没有重建调用。
        val idx = src.indexOf("override fun onStart()")
        assertTrue("GeelyClusterPresentation 应保留 onStart 覆盖", idx >= 0)
        val next = src.indexOf("override fun", idx + 1)
        val body = src.substring(idx, if (next > idx) next else minOf(idx + 600, src.length))
        assertFalse(
            "onStart 内禁止触发纹理重建（PATCH-0068）",
            body.contains("reattachIfTextureLost"),
        )
    }

    @Test
    fun videoStartsTransparentUntilFirstFrame() {
        val src = readSource()
        assertTrue(
            "TextureView 初始 alpha 必须为 0f（对齐原版），首帧才由 setVideoVisible(true) 置 1f",
            src.contains("alpha = 0f"),
        )
    }
}
