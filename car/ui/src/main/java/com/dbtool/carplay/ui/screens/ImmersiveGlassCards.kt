package com.dbtool.carplay.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.dbtool.carplay.ui.CarUiActions
import com.dbtool.carplay.ui.CarUiModel
import com.dbtool.carplay.ui.cluster.ClusterManeuverKind
import com.dbtool.carplay.ui.cluster.clusterManeuverKindOf

/** App-local overlay. No video readback, extra Surface, network, phone commands or timers. */
@Composable
internal fun ImmersiveGlassCards(model: CarUiModel, actions: CarUiActions) {
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val context = LocalContext.current
    val icons = remember(context) { OsnIconPainter(context) }
    val navigation = glassNavigationText(model.navigation)
    val music = model.immersiveNowPlaying
    val artwork = music?.artworkBytes
    // Decode at most a small cover, only when image content changes, not on progress updates.
    val bitmap = remember(artwork?.contentHashCode(), artwork?.size) {
        runCatching {
            artwork?.let { bytes ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@let null
                var sample = 1
                while (bounds.outWidth / sample > 320 || bounds.outHeight / sample > 320) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                    BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }.getOrNull()
    }
    Box(Modifier.fillMaxSize().onSizeChanged { viewport = it }) {
        if (ImmersiveGlassCardLayout.canFitNavigation(viewport.width, viewport.height)) {
            val left = ImmersiveGlassCardLayout.LEFT
            val top = ImmersiveGlassCardLayout.top()
            val width = with(density) { 550.toDp() }
            val height = with(density) { 300.toDp() }
            Canvas(Modifier.offset { IntOffset(left, top) }.size(width, height)
                .semantics { contentDescription = "导航信息，手机信号与电池电量" }) {
                drawIntoCanvas { canvas ->
                    val c = canvas.nativeCanvas
                    glass(c, model.settings.immersiveGlassColor, model.settings.immersiveGlassTransparency)
                    val active = model.navigation?.routeActive == true
                    if (!active) {
                        drawNavigationWaiting(c)
                    } else {
                        val kind = if (model.navigation?.guidanceState in 3..5) null
                            else clusterManeuverKindOf(model.navigation?.maneuverType)
                        val roadBottom = text(c, navigation.road,
                            ImmersiveGlassCardLayout.INSTRUCTION_LEFT, ImmersiveGlassCardLayout.ROAD_BASELINE,
                            ImmersiveGlassCardLayout.ROAD_TEXT_SIZE, ImmersiveGlassCardLayout.ROAD_MAX_WIDTH)
                        osnManeuverIcon(kind)?.let {
                            icons.drawBottomAligned(c, it, ImmersiveGlassCardLayout.ARROW_LEFT, roadBottom)
                        }
                        val batteryTextWidth = phoneStatusPaint(Paint.Align.LEFT, Color.WHITE)
                            .measureText(glassPhoneBatteryText(model.phonePowerLevel))
                        distanceText(c, navigation.distance, GlassPhoneStatusLayout.distanceMaxWidth(batteryTextWidth))
                        drawLanes(c, glassLanes(model.navigation))
                        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x30ffffff }
                        c.drawLine(32f, 225f, 518f, 225f, p)
                        text(c, navigation.remaining, 32f, 270f, 24f, 320f)
                        text(c, navigation.arrival, 385f, 270f, 25f, 140f)
                    }
                    drawPhoneStatus(c, model)
                }
            }
            // The phone inset belongs to navigation even when the music card cannot fit.
            // Never scale or overlap the two card containers.
            if (!ImmersiveGlassCardLayout.canFit(viewport.width, viewport.height)) return@Box
            val musicTop = ImmersiveGlassCardLayout.musicTop()
            Box(Modifier.offset { IntOffset(left, musicTop) }
                .size(width, with(density) { ImmersiveGlassCardLayout.MUSIC_HEIGHT.toDp() })) {
                Canvas(Modifier.fillMaxSize()) {
                    drawIntoCanvas { canvas ->
                        val c = canvas.nativeCanvas
                        glass(c, model.settings.immersiveGlassColor, model.settings.immersiveGlassTransparency,
                            ImmersiveGlassCardLayout.MUSIC_HEIGHT.toFloat())
                        val cover = RectF(
                            ImmersiveGlassCardLayout.COVER_LEFT, ImmersiveGlassCardLayout.COVER_TOP,
                            ImmersiveGlassCardLayout.COVER_LEFT + ImmersiveGlassCardLayout.COVER_SIZE,
                            ImmersiveGlassCardLayout.COVER_TOP + ImmersiveGlassCardLayout.COVER_SIZE,
                        )
                        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x30ffffff }
                        c.drawRoundRect(cover, 12f, 12f, p)
                        if (bitmap != null) {
                            val save = c.save()
                            c.clipPath(Path().apply { addRoundRect(cover, 12f, 12f, Path.Direction.CW) })
                            c.drawBitmap(bitmap, null, cover, p.apply { alpha = 255 })
                            c.restoreToCount(save)
                        } else text(c, "♪", 68f, 101f, 46f, 50f)
                        text(c, music?.title?.takeIf { it.isNotBlank() } ?: "暂无音乐",
                            ImmersiveGlassCardLayout.MUSIC_TEXT_LEFT, 76f, 29f, 360f)
                        text(c, listOfNotNull(music?.artist?.takeIf(String::isNotBlank),
                            music?.album?.takeIf(String::isNotBlank)).joinToString(" / "),
                            ImmersiveGlassCardLayout.MUSIC_TEXT_LEFT, 112f, 21f, 360f, 190)
                        p.color = 0x28ffffff
                        c.drawRoundRect(RectF(32f, ImmersiveGlassCardLayout.PROGRESS_TOP,
                            518f, ImmersiveGlassCardLayout.PROGRESS_BOTTOM), 3f, 3f, p)
                        val progress = if (music != null && music.durationMillis > 0)
                            (music.positionMillis.toDouble() / music.durationMillis).coerceIn(0.0, 1.0).toFloat() else 0f
                        p.color = Color.WHITE
                        c.drawRoundRect(RectF(32f, ImmersiveGlassCardLayout.PROGRESS_TOP,
                            32f + 486f * progress, ImmersiveGlassCardLayout.PROGRESS_BOTTOM), 3f, 3f, p)
                        val triangleY = ImmersiveGlassCardLayout.MEDIA_ICON_TOP + 13f
                        triangle(c, 127f, triangleY, false, p); triangle(c, 143f, triangleY, false, p)
                        triangle(c, 407f, triangleY, true, p); triangle(c, 423f, triangleY, true, p)
                        if (music?.isPlaying == true) {
                            c.drawRoundRect(RectF(264f, ImmersiveGlassCardLayout.MEDIA_ICON_TOP,
                                272f, ImmersiveGlassCardLayout.MEDIA_ICON_BOTTOM), 2f, 2f, p)
                            c.drawRoundRect(RectF(280f, ImmersiveGlassCardLayout.MEDIA_ICON_TOP,
                                288f, ImmersiveGlassCardLayout.MEDIA_ICON_BOTTOM), 2f, 2f, p)
                        } else triangle(c, 275f, triangleY, true, p)
                    }
                }
                // Only explicit media buttons handle input; card backgrounds never send phone touches.
                listOf(
                    Triple(136, "上一首", actions::onImmersiveMediaPrevious),
                    Triple(275, if (music?.isPlaying == true) "暂停" else "播放", actions::onDockMediaToggle),
                    Triple(414, "下一首", actions::onDockMediaNext),
                ).forEach { (x, label, click) ->
                    Box(Modifier.offset { IntOffset(ImmersiveGlassCardLayout.mediaHitLeft(x),
                        ImmersiveGlassCardLayout.mediaHitTop()) }
                        .size(with(density) { ImmersiveGlassCardLayout.MEDIA_HIT_SIZE.toDp() },
                            with(density) { ImmersiveGlassCardLayout.MEDIA_HIT_SIZE.toDp() })
                        .semantics { contentDescription = label }
                        .clickable(enabled = music?.hasContent == true, onClick = click))
                }
            }
        }
    }
}

/** Read-only phone telemetry: no local battery/radio fallback and no click surface. */
private fun drawPhoneStatus(c: Canvas, model: CarUiModel) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val signalSave = c.save()
    c.translate(GlassPhoneStatusLayout.SIGNAL_LEFT, GlassPhoneStatusLayout.SIGNAL_TOP)
    c.scale(GlassPhoneStatusLayout.SIGNAL_SCALE, GlassPhoneStatusLayout.SIGNAL_SCALE)
    glassPhoneSignalBars(model.phoneSignalStrength).forEachIndexed { index, active ->
        paint.color = if (active) Color.WHITE else 0xff666666.toInt()
        val x = index * GlassPhoneStatusLayout.SIGNAL_STEP
        val height = 10f + index * 8f
        val bottom = GlassPhoneStatusLayout.SIGNAL_BOTTOM
        c.drawRoundRect(RectF(x, bottom - height,
            x + GlassPhoneStatusLayout.SIGNAL_BAR_WIDTH, bottom), 3f, 3f, paint)
    }
    c.restoreToCount(signalSave)
    val batteryText = glassPhoneBatteryText(model.phonePowerLevel)
    val batteryColor = if (glassPhoneBatteryFill(model.phonePowerLevel) == null) 0xff888888.toInt()
        else glassPhonePowerColor(model.phonePowerLevel, model.phoneChargingState)
    val batteryTextWidth = phoneStatusPaint(Paint.Align.LEFT, batteryColor).measureText(batteryText)
    val batterySave = c.save()
    c.translate(GlassPhoneStatusLayout.batteryLeft(batteryTextWidth), GlassPhoneStatusLayout.BATTERY_CENTER_Y)
    c.scale(GlassPhoneStatusLayout.BATTERY_SCALE, GlassPhoneStatusLayout.BATTERY_SCALE)
    drawPhoneBattery(c, model.phonePowerLevel, model.phoneChargingState, 0f)
    phoneStatusText(c, batteryText,
        GlassPhoneStatusLayout.BATTERY_WIDTH + GlassPhoneStatusLayout.BATTERY_TERMINAL_EXTENT +
            GlassPhoneStatusLayout.BATTERY_VALUE_GAP, Paint.Align.LEFT, batteryColor)
    c.restoreToCount(batterySave)
}

private fun drawPhoneBattery(c: Canvas, level: Int?, chargingState: Int?, left: Float) {
    val fill = glassPhoneBatteryFill(level)
    val color = if (fill == null) 0xff888888.toInt() else glassPhonePowerColor(level, chargingState)
    val right = left + GlassPhoneStatusLayout.BATTERY_WIDTH
    val top = GlassPhoneStatusLayout.CENTER_Y - GlassPhoneStatusLayout.BATTERY_HEIGHT / 2f
    val bottom = top + GlassPhoneStatusLayout.BATTERY_HEIGHT
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }
    c.drawRoundRect(RectF(left, top, right, bottom), 5f, 5f, paint)
    paint.style = Paint.Style.FILL
    c.drawRoundRect(RectF(right + 1f, top + 9f, right + 6f, bottom - 9f), 2f, 2f, paint)
    if (fill != null && fill > 0f) {
        c.drawRoundRect(RectF(left + 4f, top + 4f,
            left + 4f + (GlassPhoneStatusLayout.BATTERY_WIDTH - 8f) * fill, bottom - 4f), 2f, 2f, paint)
    }
    if (chargingState == 1) {
        // Charging is an icon, not another text label. Dark outline keeps it
        // readable on a partially filled battery and translucent backgrounds.
        val x = (left + right) / 2f
        val y = GlassPhoneStatusLayout.CENTER_Y
        val bolt = Path().apply {
            moveTo(x + 3f, y - 10f); lineTo(x - 6f, y + 1f)
            lineTo(x, y + 1f); lineTo(x - 3f, y + 10f)
            lineTo(x + 6f, y - 1f); lineTo(x, y - 1f); close()
        }
        paint.color = 0xff20252b.toInt()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.strokeJoin = Paint.Join.ROUND
        c.drawPath(bolt, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        c.drawPath(bolt, paint)
    }
}

/** Empty state has no stale guidance, status labels, separators or placeholder values. */
private fun drawNavigationWaiting(c: Canvas) {
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 29f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    val metrics = p.fontMetrics
    val baseline = 150f - (metrics.ascent + metrics.descent) / 2f
    c.drawText("等待导航信息", 275f, baseline, p)
}

private fun phoneStatusText(c: Canvas, value: String, x: Float, align: Paint.Align, textColor: Int) {
    val paint = phoneStatusPaint(align, textColor)
    val metrics = paint.fontMetrics
    val baseline = GlassPhoneStatusLayout.CENTER_Y - (metrics.ascent + metrics.descent) / 2f
    c.drawText(value, x, baseline, paint)
}

private fun phoneStatusPaint(align: Paint.Align, textColor: Int) =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textSize = GlassPhoneStatusLayout.VALUE_TEXT_SIZE
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = align
    }

/** Fixed row below the road: never shifts when lane data is absent or arrives late. */
private fun drawLanes(c: Canvas, lanes: List<GlassLane>) {
    if (lanes.isEmpty()) {
        return
    }
    val slot = 326f / lanes.size
    val scale = minOf(1f, (slot - 2f) / 42f)
    lanes.forEachIndexed { index, lane ->
        val save = c.save()
        c.translate(188f + (index + 0.5f) * slot, 190f)
        c.scale(scale, scale)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4.5f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val recommended = lane.highlight != null && lane.highlight in lane.bearings
        if (lane.bearings.isNotEmpty()) {
            paint.color = if (recommended) Color.WHITE else 0xff888888.toInt()
            c.drawLine(0f, 22f, 0f, 4f, paint)
        }
        lane.bearings.sortedBy { it == lane.highlight }.forEach { bearing ->
            glassLaneBranch(bearing)?.let { branch ->
                paint.color = if (bearing == lane.highlight) Color.WHITE else 0xff888888.toInt()
                c.drawPath(Path().apply {
                    moveTo(branch.start.x, branch.start.y)
                    cubicTo(branch.control1.x, branch.control1.y, branch.control2.x,
                        branch.control2.y, branch.tip.x, branch.tip.y)
                }, paint)
                val d = branch.heading
                c.drawPath(Path().apply {
                    moveTo(branch.tip.x - d.x * 7f - d.y * 5f, branch.tip.y - d.y * 7f + d.x * 5f)
                    lineTo(branch.tip.x, branch.tip.y)
                    lineTo(branch.tip.x - d.x * 7f + d.y * 5f, branch.tip.y - d.y * 7f - d.x * 5f)
                }, paint)
            }
        }
        // Preserve spacing for empty/unknown lanes without drawing placeholder information.
        c.restoreToCount(save)
    }
}

private fun glass(c: Canvas, rgb: Int, transparency: Int, height: Float = 300f) {
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.shader = LinearGradient(0f, 0f, 440f, 300f,
        glassBackgroundColors(rgb, transparency), null, Shader.TileMode.CLAMP)
    c.drawRoundRect(RectF(1f, 1f, 549f, height - 1f), 37f, 37f, p)
    p.shader = null; p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f; p.color = 0x60ffffff
    c.drawRoundRect(RectF(2f, 2f, 548f, height - 2f), 36f, 36f, p)
    p.color = 0x18303a44; p.strokeWidth = 3f
    c.drawRoundRect(RectF(5f, 5f, 545f, height - 5f), 33f, 33f, p)
}

private fun text(c: Canvas, value: String, x: Float, y: Float, size: Float, max: Float, alpha: Int = 255): Float {
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; this.alpha = alpha; textSize = size
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    var result = value
    if (p.measureText(result) > max) {
        val end = p.breakText(result, true, (max - p.measureText("…")).coerceAtLeast(0f), null)
        result = result.take(end) + "…"
    }
    val ink = Rect()
    p.getTextBounds(result, 0, result.length, ink)
    c.drawText(result, x, y, p)
    return y + ink.bottom
}

/** Reserve the upper-right inset without truncating the supplied distance or changing its value. */
private fun distanceText(c: Canvas, value: String, maxWidth: Float) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = ImmersiveGlassCardLayout.DISTANCE_TEXT_SIZE
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    paint.textSize = ImmersiveGlassCardLayout.distanceTextSize(paint.measureText(value), maxWidth)
    c.drawText(value, ImmersiveGlassCardLayout.INSTRUCTION_LEFT, ImmersiveGlassCardLayout.DISTANCE_BASELINE, paint)
}

private fun centered(c: Canvas, value: String, y: Float, size: Float, max: Float, alpha: Int = 255) {
    val p = Paint().apply { textSize = size; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    val width = p.measureText(value).coerceAtMost(max)
    text(c, value, (550f - width) / 2, y, size, max, alpha)
}

private fun triangle(c: Canvas, x: Float, y: Float, right: Boolean, p: Paint) {
    val sign = if (right) 1 else -1
    c.drawPath(Path().apply {
        moveTo(x + sign * 10, y); lineTo(x - sign * 10, y - 13)
        lineTo(x - sign * 10, y + 13); close()
    }, p)
}

private class OsnIconPainter(private val context: Context) {
    private data class Icon(val bitmap: Bitmap, val inkBottom: Int)
    private val icons = HashMap<Int, Icon>()

    fun drawBottomAligned(c: Canvas, resource: Int, left: Float, roadInkBottom: Float) {
        val icon = icons.getOrPut(resource) {
            val size = ImmersiveGlassCardLayout.ARROW_SIZE
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val drawable = requireNotNull(context.getDrawable(resource)).mutate()
            drawable.setTint(Color.WHITE)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(Canvas(bitmap))
            // The vector viewport contains transparent padding. Measure once per icon, not per
            // frame, so the visible arrow/arrival glyph meets the road's actual glyph bottom.
            val pixels = IntArray(size * size)
            bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
            val lastRow = (size - 1 downTo 0).firstOrNull { y ->
                (0 until size).any { x -> (pixels[y * size + x] ushr 24) != 0 }
            }
            Icon(bitmap, lastRow?.plus(1) ?: size)
        }
        c.drawBitmap(icon.bitmap, left,
            ImmersiveGlassCardLayout.arrowTop(roadInkBottom, icon.inkBottom), null)
    }
}
