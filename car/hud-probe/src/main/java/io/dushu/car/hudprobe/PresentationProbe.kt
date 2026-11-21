package io.dushu.car.hudprobe

import android.app.Presentation
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.Gravity

internal class PresentationProbe(private val context: Context) {
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
        val presentation = ProbePresentation(
            displayContext = displayContext,
            displayId = displayId,
            x = x,
            y = y,
            width = width,
            height = height,
            onFirstDraw = onFirstDraw,
        )
        presentation.show()
        return Handle(presentation)
    }

    private class ProbePresentation(
        displayContext: Context,
        displayId: Int,
        private val x: Int,
        private val y: Int,
        private val width: Int,
        private val height: Int,
        onFirstDraw: () -> Unit,
    ) : Presentation(
        displayContext,
        displayContext.getSystemService(DisplayManager::class.java)
            ?.getDisplay(displayId)
            ?: error("display $displayId not found"),
    ) {
        private val probeView = ProbeCanvasView(
            context = displayContext,
            displayId = displayId,
            boundsText = "$x,$y ${width}x$height",
            onFirstDraw = onFirstDraw,
        )

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            setContentView(probeView)
        }

        override fun onStart() {
            super.onStart()
            window?.apply {
                setTitle(LAYER_TITLE)
                setLayout(width, height)
                setGravity(Gravity.TOP or Gravity.START)
                attributes = attributes.apply {
                    this.x = this@ProbePresentation.x
                    this.y = this@ProbePresentation.y
                }
            }
        }
    }

    internal class Handle(private val presentation: Presentation) {
        fun close() {
            runCatching { presentation.dismiss() }
        }
    }

    companion object {
        const val LAYER_TITLE = "DBPlay-HUD-PROBE-PRESENTATION"
    }
}
