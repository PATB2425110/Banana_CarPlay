package com.dbtool.carplay.geely

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.view.View

/**
 * CarPlay 地图区的四边渐变融合遮罩（2026-10-23 重写）。
 *
 * ## 车主口径
 *
 * 「地图中心保持清晰，边缘自然融入仪表背景」；浅色模式用浅色主题背景色，
 * 深色模式用深色主题背景色。
 *
 * ## 为什么不再用 CLEAR
 *
 * 上一版用 `PorterDuff.CLEAR` 把四边擦成透明。问题：`clip` 下面就是车机原厂
 * 仪表/黑底，擦透明之后边缘露出来的是**下层真实内容**，不是主题背景色 ——
 * 浅色主题下会看到"白色表盘 + 透明黑洞 + 地图"三截。
 *
 * 现在改成**直接画主题背景色**的线性渐变（边缘不透明 → 内侧透明），
 * 地图边缘就像被背景"吃掉"，与主题背景同色自然衔接。
 *
 * ## 只作用于地图层
 *
 * 这个 View 只挂在 `clip`（视频容器）内部，`clip` 的矩形就是地图矩形，
 * 所以遮罩不可能画到圆盘/指针/刻度/文字上。
 */
internal class ClusterEdgeFadeView(context: Context) : View(context) {

    /** 羽化带宽（像素）。0 = 完全不画。 */
    var fadePx: Float = 0f

    /** 融合色 = 当前主题背景色（浅色/深色由调用方传入）。 */
    var fadeColor: Int = Color.BLACK

    /** 四边是否各自启用（默认全开）。 */
    var fadeLeft: Boolean = true
    var fadeTop: Boolean = true
    var fadeRight: Boolean = true
    var fadeBottom: Boolean = true

    private val paint = Paint().apply { isAntiAlias = true }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val f = fadePx
        if (f <= 0f) return

        val opaque = fadeColor or 0xFF000000.toInt()
        val clear = fadeColor and 0x00FFFFFF

        fun hGradient(fromX: Float, toX: Float) =
            LinearGradient(fromX, 0f, toX, 0f, opaque, clear, Shader.TileMode.CLAMP)

        fun vGradient(fromY: Float, toY: Float) =
            LinearGradient(0f, fromY, 0f, toY, opaque, clear, Shader.TileMode.CLAMP)

        // 左：从外缘不透明 → 向内 f 处透明
        if (fadeLeft && f > 0f) {
            paint.shader = hGradient(0f, f)
            canvas.drawRect(0f, 0f, f, h, paint)
        }
        // 右
        if (fadeRight && f > 0f) {
            paint.shader = hGradient(w, w - f)
            canvas.drawRect(w - f, 0f, w, h, paint)
        }
        // 上
        if (fadeTop && f > 0f) {
            paint.shader = vGradient(0f, f)
            canvas.drawRect(0f, 0f, w, f, paint)
        }
        // 下
        if (fadeBottom && f > 0f) {
            paint.shader = vGradient(h, h - f)
            canvas.drawRect(0f, h - f, w, h, paint)
        }
        paint.shader = null
    }
}
