package com.dbtool.carplay.geely

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import com.dbtool.carplay.api.media.CarNowPlaying
import com.dbtool.carplay.api.navigation.CarNavigationSnapshot
import kotlin.math.roundToInt

/** Transparent cards above the CarPlay map; deliberately independent of ClusterSkinView/theme. */
internal class ClusterIndependentCardsView(context: Context) : View(context) {
    var navigationEnabled = true
        set(value) { field = value; invalidate() }
    var musicEnabled = true
        set(value) { field = value; invalidate() }
    var navigation: CarNavigationSnapshot? = null
        set(value) { field = value; invalidate() }
    var nowPlaying: CarNowPlaying? = null
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    // Design coordinates in the independent 1920x720 overlay.
    var navigationX = 80f
    var navigationY = 80f
    var musicX = 80f
    var musicY = 500f

    private val panel = Paint().apply { color = Color.argb(190, 0, 0, 0) }
    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 24f; typeface = Typeface.DEFAULT_BOLD
    }
    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 20f }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val sx = width / 1920f
        val sy = height / 720f
        canvas.save(); canvas.scale(sx, sy)
        if (navigationEnabled) drawNavigation(canvas)
        if (musicEnabled) drawMusic(canvas)
        canvas.restore()
    }

    private fun drawNavigation(canvas: Canvas) {
        val n = navigation ?: return
        val lines = listOfNotNull(
            n.maneuverDescription?.takeIf(String::isNotBlank),
            n.segmentMeters?.let { "距转向 ${it}m" },
            n.routeMeters?.let { "剩余 ${it}m" },
            n.destination?.takeIf(String::isNotBlank),
        ).take(4)
        val cells = ClusterLaneGeometry.layout(n.lanes)
        if (lines.isEmpty() && cells.isEmpty()) return
        val h = ClusterLaneGeometry.panelHeight(lines.size, cells)
        canvas.drawRoundRect(navigationX, navigationY, navigationX + 430f, navigationY + h, 16f, 16f, panel)
        canvas.save()
        canvas.clipRect(navigationX + 18f, navigationY, navigationX + 412f, navigationY + h)
        lines.forEachIndexed { i, text -> canvas.drawText(text, navigationX + 18f, navigationY + 35f + i * 30f, if (i == 0) title else body) }
        canvas.restore()
        val laneTop = navigationY + ClusterLaneGeometry.textHeight(lines.size)
        cells.forEach { cell ->
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                strokeWidth = (3f * cell.scale).coerceAtLeast(1f)
                color = Color.LTGRAY
            }
            fun drawPath(points: List<ClusterLaneGeometry.Point>) {
                points.zipWithNext { a, b ->
                    canvas.drawLine(navigationX + a.x, laneTop + a.y, navigationX + b.x, laneTop + b.y, stroke)
                }
            }
            // A single incoming stem, followed by branch-local highlights (not a whole-cell tint).
            drawPath(cell.stem)
            cell.branches.sortedBy { it.recommended }.forEach { branch ->
                stroke.color = if (branch.recommended) Color.rgb(80, 220, 255) else Color.LTGRAY
                drawPath(branch.path)
                drawPath(branch.head)
            }
            if (cell.unknown) {
                val unknownPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.LTGRAY
                    textAlign = Paint.Align.CENTER
                    textSize = 18f * cell.scale
                }
                canvas.drawText("?", navigationX + (cell.left + cell.right) / 2f, laneTop + 80f, unknownPaint)
            }
        }
    }

    private fun drawMusic(canvas: Canvas) {
        val m = nowPlaying ?: return
        if (!m.hasContent) return
        val lyric = m.title.takeIf(String::isNotBlank) ?: return
        val right = musicX + ClusterMusicCardLayout.WIDTH
        canvas.drawRoundRect(musicX, musicY, right, musicY + ClusterMusicCardLayout.HEIGHT, 16f, 16f, panel)
        canvas.save()
        canvas.clipRect(musicX + ClusterMusicCardLayout.TEXT_PADDING, musicY,
            right - ClusterMusicCardLayout.TEXT_PADDING, musicY + ClusterMusicCardLayout.HEIGHT)
        canvas.drawText(musicCardEllipsize(lyric, ClusterMusicCardLayout.TEXT_WIDTH, title::measureText),
            musicX + ClusterMusicCardLayout.TEXT_PADDING, musicY + 31f, title)
        val artist = listOf(m.artist, m.album).filter { it.isNotBlank() }.joinToString(" · ")
        if (artist.isNotBlank()) canvas.drawText(
            musicCardEllipsize(artist, ClusterMusicCardLayout.TEXT_WIDTH, body::measureText),
            musicX + ClusterMusicCardLayout.TEXT_PADDING, musicY + 57f, body)
        canvas.restore()
    }
}
