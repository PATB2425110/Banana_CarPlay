package com.dbtool.carplay.geely

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import com.dbtool.carplay.api.projection.ClusterLayerRuntime

/** Temporary real-instrument preview while the settings safe-area editor is open. */
internal class InstrumentSafeAreaPreviewView(context: Context) : View(context) {
    private val outside = Paint().apply { color = Color.argb(155, 0, 0, 0) }
    private val road = Paint().apply { color = Color.argb(210, 80, 170, 255); strokeWidth = 5f }
    private val route = Paint().apply { color = Color.WHITE; strokeWidth = 8f }
    private val border = Paint().apply { color = Color.YELLOW; style = Paint.Style.STROKE; strokeWidth = 4f }
    override fun onDraw(canvas: Canvas) {
        val area = ClusterLayerRuntime.safeAreaPreview() ?: run { visibility = INVISIBLE; return }
        visibility = VISIBLE
        val w = width.toFloat(); val h = height.toFloat()
        val left = area.x.coerceIn(0, 1920) / 1920f * w
        val top = area.y.coerceIn(0, 720) / 720f * h
        val right = (area.x + area.width).coerceIn(0, 1920) / 1920f * w
        val bottom = (area.y + area.height).coerceIn(0, 720) / 720f * h
        canvas.drawRect(0f, 0f, w, top, outside)
        canvas.drawRect(0f, bottom, w, h, outside)
        canvas.drawRect(0f, top, left, bottom, outside)
        canvas.drawRect(right, top, w, bottom, outside)
        canvas.drawLine(left + (right-left)*.1f, top + (bottom-top)*.8f, right-(right-left)*.1f, top+(bottom-top)*.2f, road)
        canvas.drawLine(left + (right-left)*.15f, top + (bottom-top)*.75f, left+(right-left)*.48f, top+(bottom-top)*.52f, route)
        canvas.drawLine(left+(right-left)*.48f, top+(bottom-top)*.52f, right-(right-left)*.15f, top+(bottom-top)*.18f, route)
        canvas.drawRect(left, top, right, bottom, border)
    }
}
