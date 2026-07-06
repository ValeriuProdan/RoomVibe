package com.thermolog.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.thermolog.data.entity.Reading
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

private const val HOUR = 3_600_000L
private const val DAY = 24 * HOUR

private const val PAD_L = 12f
private const val PAD_R = 52f
private const val PAD_T = 34f   // header band: title (left) + scrubber date (right)
private const val PAD_B = 26f

enum class Metric { TEMP, HUMIDITY }
enum class Lod { HOURLY, DAILY, MONTHLY }

fun lodFor(span: Long): Lod = when {
    span <= 6 * DAY -> Lod.HOURLY
    span <= 550 * DAY -> Lod.DAILY
    else -> Lod.MONTHLY
}

fun zoomLabel(span: Long): String = when (lodFor(span)) {
    Lod.HOURLY -> "Hourly"
    Lod.DAILY -> "Daily"
    Lod.MONTHLY -> "Monthly"
}

data class Viewport(val startMs: Long, val endMs: Long) {
    val span get() = (endMs - startMs).coerceAtLeast(1L)
}

private data class Point(
    val tMs: Long,
    val bucketStart: Long,
    val lo: Float,
    val hi: Float
) {
    val mid get() = (lo + hi) / 2f
}

/**
 * A single dark-themed metric chart (temperature OR humidity) in the Mi-Home style:
 * smooth line + gradient fill, dashed grid, right-side axis, min/max pills, and a
 * draggable scrubber. One-finger drag moves the scrubber; two fingers zoom & pan.
 */
@Composable
fun MetricChart(
    title: String,
    unit: String,
    metric: Metric,
    accent: Color,
    readings: List<Reading>,
    viewport: Viewport,
    scrubberMs: Long?,
    showTimeLabel: Boolean,
    onViewportChange: (Viewport) -> Unit,
    onScrub: (Long?) -> Unit,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    val gridColor = Color(0x1FFFFFFF)
    val axisLabel = TextStyle(fontSize = 10.sp, color = Color(0xFF9AA0A6))
    val titleColor = Color(0xFFE8EAED)

    if (readings.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("No data yet — tap Sync", color = Color(0xFF9AA0A6))
        }
        return
    }

    val dataMin = readings.first().timestampMs
    val dataMax = readings.last().timestampMs
    val minSpan = 3 * HOUR
    val maxSpan = ((dataMax - dataMin).coerceAtLeast(DAY) * 1.1).toLong()

    val vpState = rememberUpdatedState(viewport)
    val onVpState = rememberUpdatedState(onViewportChange)
    val onScrubState = rememberUpdatedState(onScrub)

    Canvas(
        modifier = modifier.pointerInput(readings.size) {
            awaitEachGesture {
                val first = awaitFirstDown(requireUnconsumed = false)
                // Lower ~40% of the plot area pans; upper part scrubs.
                val plotTop = PAD_T
                val plotBottom = size.height - PAD_B
                val panZoneTop = plotTop + (plotBottom - plotTop) * 0.8f
                val panMode = first.position.y > panZoneTop

                if (!panMode) scrubAt(first.position.x, size.width, vpState.value, onScrubState.value)
                var prevX = first.position.x
                var prevCentroid: Offset? = null
                var prevSpread = 0f
                while (true) {
                    val event = awaitPointerEvent()
                    val pts = event.changes.filter { it.pressed }
                    if (pts.isEmpty()) break
                    if (pts.size == 1) {
                        val x = pts[0].position.x
                        if (panMode) {
                            panBy(x - prevX, size.width, vpState.value, dataMin, dataMax, onVpState.value)
                        } else {
                            scrubAt(x, size.width, vpState.value, onScrubState.value)
                        }
                        prevX = x
                        pts[0].consume()
                        prevCentroid = null; prevSpread = 0f
                    } else {
                        val cx = pts.sumOf { it.position.x.toDouble() }.toFloat() / pts.size
                        val cy = pts.sumOf { it.position.y.toDouble() }.toFloat() / pts.size
                        val centroid = Offset(cx, cy)
                        val spread = pts.sumOf { (it.position - centroid).getDistance().toDouble() }
                            .toFloat() / pts.size
                        val pc = prevCentroid
                        if (pc != null && prevSpread > 0f) {
                            val zoom = if (spread > 0f) spread / prevSpread else 1f
                            applyTransform(centroid.x, centroid.x - pc.x, zoom, size.width,
                                vpState.value, minSpan, maxSpan, dataMin, dataMax, onVpState.value)
                        }
                        prevCentroid = centroid; prevSpread = spread
                        prevX = centroid.x
                        pts.forEach { it.consume() }
                    }
                }
            }
        }
    ) {
        val w = size.width - PAD_L - PAD_R
        val h = size.height - PAD_T - PAD_B
        val lod = lodFor(viewport.span)
        val points = buildPoints(readings, viewport.startMs, viewport.endMs, lod, metric)

        // Title (left of the header band)
        drawText(textMeasurer.measure(title, TextStyle(fontSize = 13.sp, color = titleColor,
            fontWeight = FontWeight.SemiBold)), topLeft = Offset(PAD_L, 4f))

        if (points.isEmpty()) {
            drawText(textMeasurer.measure("No data in range", axisLabel),
                topLeft = Offset(PAD_L, PAD_T + h / 2))
            return@Canvas
        }

        val lo = floor(points.minOf { it.lo } - 1f)
        val hi = ceil(points.maxOf { it.hi } + 1f)
        val range = (hi - lo).coerceAtLeast(1f)

        fun xOf(tMs: Long) = PAD_L + ((tMs - viewport.startMs).toDouble() / viewport.span * w).toFloat()
        fun yOf(v: Float) = PAD_T + h - (v - lo) / range * h

        // Dashed grid + right axis labels
        val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 8f))
        for (i in 0..4) {
            val frac = i / 4f
            val y = PAD_T + h - frac * h
            drawLine(gridColor, Offset(PAD_L, y), Offset(PAD_L + w, y), 1f, pathEffect = dash)
            val v = lo + frac * range
            val lbl = textMeasurer.measure("%.0f%s".format(v, unit), axisLabel)
            drawText(lbl, topLeft = Offset(PAD_L + w + 8f, y - lbl.size.height / 2))
        }

        // Build screen-space points for the primary (hi/mid) line
        val primary = points.map { Offset(xOf(it.tMs), yOf(if (lod == Lod.HOURLY) it.mid else it.hi)) }

        // Gradient fill under the primary line
        val fill = smoothPath(primary).apply {
            lineTo(primary.last().x, PAD_T + h)
            lineTo(primary.first().x, PAD_T + h)
            close()
        }
        drawPath(fill, Brush.verticalGradient(
            listOf(accent.copy(alpha = 0.35f), accent.copy(alpha = 0.02f)),
            startY = PAD_T, endY = PAD_T + h))

        // Primary line
        drawPath(smoothPath(primary), accent, style = Stroke(2.6f, cap = StrokeCap.Round, join = StrokeJoin.Round))

        // Min line (dashed) for daily/monthly
        if (lod != Lod.HOURLY) {
            val lows = points.map { Offset(xOf(it.tMs), yOf(it.lo)) }
            drawPath(smoothPath(lows), accent.copy(alpha = 0.6f),
                style = Stroke(2f, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = dash))
        }

        // Subtle hint marking the lower pan zone
        val panZoneTop = PAD_T + h * 0.8f
        drawLine(Color(0x10FFFFFF), Offset(PAD_L, panZoneTop), Offset(PAD_L + w, panZoneTop), 1f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 7f)))
        val hint = textMeasurer.measure("⇄ drag here to scroll through time",
            TextStyle(fontSize = 9.sp, color = Color(0x44FFFFFF)))
        drawText(hint, topLeft = Offset(PAD_L + (w - hint.size.width) / 2f, PAD_T + h - hint.size.height - 2f))

        // Min & max pills
        val maxP = points.maxByOrNull { it.hi }!!
        val minP = points.minByOrNull { it.lo }!!
        drawMarker(textMeasurer, xOf(maxP.tMs), yOf(maxP.hi), "%.1f".format(maxP.hi), accent, above = true)
        drawMarker(textMeasurer, xOf(minP.tMs), yOf(minP.lo), "%.1f".format(minP.lo), accent, above = false)

        // X-axis time labels
        val tfmt = timeAxisFormat(lod)
        val labelCount = (w / 90f).roundToInt().coerceIn(2, 6)
        for (i in 0..labelCount) {
            val tMs = viewport.startMs + viewport.span * i / labelCount
            val x = PAD_L + w * i / labelCount
            val lbl = textMeasurer.measure(tfmt.format(Date(tMs)), axisLabel)
            val tx = (x - lbl.size.width / 2f).coerceIn(0f, size.width - lbl.size.width)
            drawText(lbl, topLeft = Offset(tx, PAD_T + h + 6f))
        }

        // ── Scrubber ──────────────────────────────────────────────────────────
        val sel = scrubberMs
        if (sel != null && sel in viewport.startMs..viewport.endMs) {
            val near = points.minByOrNull { abs(it.tMs - sel) }
            if (near != null) {
                val x = xOf(near.tMs)
                drawLine(Color(0x88FFFFFF), Offset(x, PAD_T), Offset(x, PAD_T + h), 1.5f)

                if (lod == Lod.HOURLY) {
                    drawScrubDot(x, yOf(near.mid), accent)
                } else {
                    drawScrubDot(x, yOf(near.hi), accent)
                    drawScrubDot(x, yOf(near.lo), accent)
                }

                val valStr = if (lod == Lod.HOURLY) "%.1f%s".format(near.mid, unit)
                             else "%.1f–%.1f%s".format(near.lo, near.hi, unit)
                drawMarker(textMeasurer, x, PAD_T + 2f, valStr, accent, above = false, solidBg = true)

                if (showTimeLabel) {
                    val title2 = tooltipTitle(near.bucketStart, lod)
                    val m = textMeasurer.measure(title2, TextStyle(fontSize = 11.sp, color = Color(0xFFE8EAED)))
                    // Right-align to the plot edge (not the canvas), clear of the axis gutter
                    val plotRight = size.width - PAD_R
                    val tx = (plotRight - m.size.width).coerceAtLeast(PAD_L + 120f)
                    drawText(m, topLeft = Offset(tx, 4f))
                }
            }
        }
    }
}

// ── Gesture math ──────────────────────────────────────────────────────────

private fun scrubAt(x: Float, widthPx: Int, vp: Viewport, onScrub: (Long?) -> Unit) {
    val w = widthPx - PAD_L - PAD_R
    if (w <= 0) return
    val frac = ((x - PAD_L) / w).coerceIn(0f, 1f)
    onScrub(vp.startMs + (vp.span * frac).toLong())
}

private fun panBy(
    dxPx: Float, widthPx: Int, vp: Viewport, dataMin: Long, dataMax: Long,
    onChange: (Viewport) -> Unit
) {
    val span = vp.span
    val msPerPx = span.toDouble() / widthPx
    val panMs = (dxPx * msPerPx).toLong()
    var newStart = vp.startMs - panMs   // drag right → reveal older data
    var newEnd = vp.endMs - panMs
    val margin = span / 10
    if (newStart < dataMin - margin) { newStart = dataMin - margin; newEnd = newStart + span }
    if (newEnd > dataMax + margin) { newEnd = dataMax + margin; newStart = newEnd - span }
    onChange(Viewport(newStart, newEnd))
}

private fun applyTransform(
    centroidX: Float, panX: Float, zoom: Float, widthPx: Int,
    vp: Viewport, minSpan: Long, maxSpan: Long, dataMin: Long, dataMax: Long,
    onChange: (Viewport) -> Unit
) {
    val span = vp.span
    val msPerPx = span.toDouble() / widthPx
    val focalFrac = (centroidX / widthPx).coerceIn(0f, 1f)
    val focalMs = vp.startMs + (span * focalFrac).toLong()
    val newSpan = (span / zoom).toLong().coerceIn(minSpan, maxSpan)
    val panMs = (panX * msPerPx).toLong()
    var newStart = focalMs - (newSpan * focalFrac).toLong() - panMs
    var newEnd = newStart + newSpan
    val margin = newSpan / 10
    if (newStart < dataMin - margin) { newStart = dataMin - margin; newEnd = newStart + newSpan }
    if (newEnd > dataMax + margin) { newEnd = dataMax + margin; newStart = newEnd - newSpan }
    onChange(Viewport(newStart, newEnd))
}

// ── Drawing helpers ───────────────────────────────────────────────────────

/** Smooth curve through points using horizontal-tangent cubic segments. */
private fun smoothPath(pts: List<Offset>): Path {
    val path = Path()
    if (pts.isEmpty()) return path
    path.moveTo(pts[0].x, pts[0].y)
    for (i in 1 until pts.size) {
        val p0 = pts[i - 1]; val p1 = pts[i]
        val midX = (p0.x + p1.x) / 2f
        path.cubicTo(midX, p0.y, midX, p1.y, p1.x, p1.y)
    }
    return path
}

private fun DrawScope.drawScrubDot(x: Float, y: Float, accent: Color) {
    drawCircle(Color.White, 5f, Offset(x, y))
    drawCircle(accent, 3f, Offset(x, y))
}

private fun DrawScope.drawMarker(
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    cx: Float, cy: Float, text: String, accent: Color, above: Boolean, solidBg: Boolean = false
) {
    drawCircle(Color.White, 4f, Offset(cx, cy))
    val m = textMeasurer.measure(text, TextStyle(fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.SemiBold))
    val padX = 7f; val padY = 3f
    val boxW = m.size.width + padX * 2; val boxH = m.size.height + padY * 2
    val bx = (cx - boxW / 2).coerceIn(0f, size.width - boxW)
    // Keep the pill inside the plot band so it never overlaps the header title or axis labels
    val by = (if (above) cy - boxH - 8f else cy + 8f)
        .coerceIn(PAD_T, size.height - PAD_B - boxH)
    drawRoundRect(if (solidBg) accent else accent.copy(alpha = 0.92f),
        topLeft = Offset(bx, by), size = Size(boxW, boxH), cornerRadius = CornerRadius(boxH / 2, boxH / 2))
    drawText(m, topLeft = Offset(bx + padX, by + padY))
}

// ── Aggregation ─────────────────────────────────────────────────────────────

private fun buildPoints(readings: List<Reading>, startMs: Long, endMs: Long, lod: Lod, metric: Metric): List<Point> {
    var lo = lowerBound(readings, startMs) - 1
    if (lo < 0) lo = 0
    var hi = lowerBound(readings, endMs)
    if (hi < readings.size) hi++
    if (lo >= hi) return emptyList()
    val slice = readings.subList(lo, hi.coerceAtMost(readings.size))

    fun loOf(r: Reading) = if (metric == Metric.TEMP) r.tempMinC else r.humMin.toFloat()
    fun hiOf(r: Reading) = if (metric == Metric.TEMP) r.tempMaxC else r.humMax.toFloat()

    if (lod == Lod.HOURLY) {
        return slice.map { Point(it.timestampMs, it.timestampMs, loOf(it), hiOf(it)) }
    }

    val cal = Calendar.getInstance()
    fun bucketStart(ms: Long): Long {
        cal.timeInMillis = ms
        cal.set(Calendar.MILLISECOND, 0); cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MINUTE, 0); cal.set(Calendar.HOUR_OF_DAY, 0)
        if (lod == Lod.MONTHLY) cal.set(Calendar.DAY_OF_MONTH, 1)
        return cal.timeInMillis
    }

    val out = ArrayList<Point>()
    var curKey = Long.MIN_VALUE
    var mn = Float.MAX_VALUE; var mx = -Float.MAX_VALUE
    fun flush() {
        if (curKey != Long.MIN_VALUE) {
            val mid = if (lod == Lod.MONTHLY) curKey + 15 * DAY else curKey + 12 * HOUR
            out.add(Point(mid, curKey, mn, mx))
        }
    }
    for (r in slice) {
        val key = bucketStart(r.timestampMs)
        if (key != curKey) { flush(); curKey = key; mn = Float.MAX_VALUE; mx = -Float.MAX_VALUE }
        if (loOf(r) < mn) mn = loOf(r)
        if (hiOf(r) > mx) mx = hiOf(r)
    }
    flush()
    return out
}

private fun lowerBound(readings: List<Reading>, tMs: Long): Int {
    var lo = 0; var hi = readings.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (readings[mid].timestampMs < tMs) lo = mid + 1 else hi = mid
    }
    return lo
}

private fun timeAxisFormat(lod: Lod): SimpleDateFormat = when (lod) {
    Lod.HOURLY -> SimpleDateFormat("HH:mm", Locale.getDefault())
    Lod.DAILY -> SimpleDateFormat("d MMM", Locale.getDefault())
    Lod.MONTHLY -> SimpleDateFormat("MMM yy", Locale.getDefault())
}

private fun tooltipTitle(bucketStart: Long, lod: Lod): String {
    val fmt = when (lod) {
        Lod.HOURLY -> SimpleDateFormat("EEE d MMM, HH:00", Locale.getDefault())
        Lod.DAILY -> SimpleDateFormat("EEE d MMM yyyy", Locale.getDefault())
        Lod.MONTHLY -> SimpleDateFormat("MMMM yyyy", Locale.getDefault())
    }
    return fmt.format(Date(bucketStart))
}
