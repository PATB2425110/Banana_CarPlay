package io.dushu.car.hudprobe

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.view.Gravity
import android.view.WindowManager

internal class OverlayProbe(private val context: Context) {
    fun show(
        displayId: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        onFirstDraw: () -> Unit,
    ): Handle {
        require(width > 0 && height > 0) { "width and height must be > 0" }
        val display = context.getSystemService(DisplayManager::class.java)
            ?.getDisplay(displayId)
            ?: error("display $displayId not found")
        val displayContext = context.createDisplayContext(display)
        val windowManager = displayContext.getSystemService(WindowManager::class.java)
            ?: error("WindowManager unavailable for display $displayId")
        val view = ProbeCanvasView(
            context = displayContext,
            displayId = displayId,
            boundsText = "$x,$y ${width}x$height",
            onFirstDraw = onFirstDraw,
        )
        val params = WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
            title = LAYER_TITLE
        }
        windowManager.addView(view, params)
        return Handle(view, windowManager)
    }

    internal class Handle(
        private val view: ProbeCanvasView,
        private val windowManager: WindowManager,
    ) {
        fun close() {
            runCatching { windowManager.removeViewImmediate(view) }
        }
    }

    companion object {
        const val LAYER_TITLE = "DBPlay-HUD-PROBE-OVERLAY"
    }
}
