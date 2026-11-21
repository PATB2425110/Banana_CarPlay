package io.dushu.car.hudprobe

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import java.util.Locale

/**
 * 高对比棋盘 + 递增计时。棋盘让缩放/裁剪错误肉眼可见，计时同时证明画面持续刷新。
 */
internal class ProbeCanvasView(
    context: Context,
    private val displayId: Int,
    private val boundsText: String,
    private val onFirstDraw: () -> Unit,
) : View(context) {
    private val startedAt = SystemClock.elapsedRealtime()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 40f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private var firstDrawReported = false

    init {
        setBackgroundColor(Color.BLACK)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val tile = 64
        val colors = intArrayOf(Color.MAGENTA, Color.BLACK, Color.CYAN, Color.BLACK)
        for (y in 0 until height step tile) {
            for (x in 0 until width step tile) {
                paint.color = colors[((x / tile) + (y / tile)) % colors.size]
                canvas.drawRect(
                    x.toFloat(),
                    y.toFloat(),
                    (x + tile).coerceAtMost(width).toFloat(),
                    (y + tile).coerceAtMost(height).toFloat(),
                    paint,
                )
            }
        }

        val panel = Rect(
            width / 20,
            height / 4,
            width - width / 20,
            height - height / 4,
        )
        paint.color = Color.argb(220, 0, 0, 0)
        canvas.drawRect(panel, paint)

        val elapsedSeconds = (SystemClock.elapsedRealtime() - startedAt) / 1000L
        val timer = String.format(
            Locale.US,
            "T+ %02d:%02d",
            elapsedSeconds / 60,
            elapsedSeconds % 60,
        )
        val centerY = panel.exactCenterY()
        canvas.drawText("DBPlay HUD PROBE", panel.left.toFloat() + 28f, centerY - 54f, textPaint)
        canvas.drawText(
            "display=$displayId bounds=$boundsText",
            panel.left.toFloat() + 28f,
            centerY,
            textPaint,
        )
        textPaint.textSize = 64f
        canvas.drawText(timer, panel.left.toFloat() + 28f, centerY + 82f, textPaint)
        textPaint.textSize = 40f

        if (!firstDrawReported) {
            firstDrawReported = true
            onFirstDraw()
        }
        postInvalidateOnAnimation()
    }
}
