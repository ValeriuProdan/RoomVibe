package com.roomvibe.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.roomvibe.data.entity.Reading
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Colour stops in the value-coloured line gradient. Past this, more is invisible. */
private const val MAX_GRADIENT_STOPS = 64

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
    showTitle: Boolean = true,
    colorByValue: Boolean = false,
    fahrenheit: Boolean = false,
    onViewportChange: (Viewport) -> Unit,
    onScrub: (Long?) -> Unit,
    modifier: Modifier = Modifier
) {
    // A pan redraws a dozen-plus labels per frame; the default 8-entry cache thrashes.
    val textMeasurer = rememberTextMeasurer(cacheSize = 48)
    // Scratch buffers and date formatters reused across frames.
    val paths = remember { ChartPaths() }
    val labels = rememberTimeLabels()
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

    // Bucketed once per zoom level, not once per frame — panning then costs a
    // binary search instead of a full re-aggregation of the visible history.
    val lod = lodFor(viewport.span)
    val seriesData = rememberSeriesData(readings, metric)

    Canvas(
        modifier = modifier.chartGestures(
            resetKey = readings.size,
            viewport = viewport,
            dataMin = dataMin,
            dataMax = dataMax,
            onViewportChange = onViewportChange,
            onScrub = onScrub
        )
    ) {
        val w = size.width - PAD_L - PAD_R
        val h = size.height - PAD_T - PAD_B
        // Guard against degenerate sizes (e.g. mid-rotation the canvas can be ~0)
        if (w < 8f || h < 8f) return@Canvas
        val points = seriesData.visible(lod, viewport.startMs, viewport.endMs)

        // Convert a stored value to the displayed number (°F when requested).
        // Colours still use the raw Celsius value, so the ramp is unit-independent.
        fun disp(v: Float): Float = if (fahrenheit) v * 9f / 5f + 32f else v

        // Title (left of the header band); hidden in landscape where the toggle names it
        if (showTitle) {
            drawText(textMeasurer.measure(title, TextStyle(fontSize = 13.sp, color = titleColor,
                fontWeight = FontWeight.SemiBold)), topLeft = Offset(PAD_L, 4f))
        }

        // Not just "no points": panned past the end of the history the slice still
        // carries one far-off overscan point, and the min/max pills would clamp it
        // on-screen as though it belonged to this window.
        if (!points.intersects(viewport.startMs, viewport.endMs)) {
            drawText(textMeasurer.measure("No data in range", axisLabel),
                topLeft = Offset(PAD_L, PAD_T + h / 2))
            return@Canvas
        }

        val lo = floor(points.minOf { it.lo } - 1f)
        val hi = ceil(points.maxOf { it.hi } + 1f)
        val range = (hi - lo).coerceAtLeast(1f)

        val scale = ChartScale(viewport.startMs, viewport.span, PAD_L, w, lo, range, PAD_T, h)
        fun xOf(tMs: Long) = scale.x(tMs)
        fun yOf(v: Float) = scale.y(v)

        // Dashed grid + right axis labels
        val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 8f))
        for (i in 0..4) {
            val frac = i / 4f
            val y = PAD_T + h - frac * h
            drawLine(gridColor, Offset(PAD_L, y), Offset(PAD_L + w, y), 1f, pathEffect = dash)
            val v = lo + frac * range
            val lbl = textMeasurer.measure("%.0f%s".format(disp(v), unit), axisLabel)
            drawText(lbl, topLeft = Offset(PAD_L + w + 8f, y - lbl.size.height / 2))
        }

        // Colour a value using the metric's ramp when colorByValue, else the accent
        fun colorAt(v: Float): Color = when {
            !colorByValue -> accent
            metric == Metric.TEMP -> tempColor(v)
            else -> humidColor(v)
        }

        // The primary line follows the midpoint at hourly detail, the daily maximum
        // when zoomed out (the minimum gets its own dashed line below).
        val primaryPart = if (lod == Lod.HOURLY) Part.MID else Part.HI
        fun valueAt(i: Int, part: Part): Float {
            val p = points[i]
            return when (part) { Part.MID -> p.mid; Part.HI -> p.hi; Part.LO -> p.lo }
        }

        // Horizontal gradient brush that colours a line by each point's value.
        // The shader is rebuilt every frame, so the stops are thinned to a fixed
        // budget — well past the point where more of them are visible.
        fun valueBrush(part: Part, alpha: Float): Brush {
            val firstX = xOf(points.first().tMs); val lastX = xOf(points.last().tMs)
            val gspan = lastX - firstX
            if (gspan <= 0f) return SolidColor(colorAt(valueAt(0, part)).copy(alpha = alpha))
            val stride = maxOf(1, points.size / MAX_GRADIENT_STOPS)
            var prev = -1f
            val stops = points.indices
                .filter { it % stride == 0 || it == points.lastIndex }
                .map { i ->
                    var f = ((xOf(points[i].tMs) - firstX) / gspan).coerceIn(0f, 1f)
                    if (f <= prev) f = (prev + 1e-4f).coerceAtMost(1f)
                    prev = f
                    f to colorAt(valueAt(i, part)).copy(alpha = alpha)
                }
            return Brush.linearGradient(colorStops = stops.toTypedArray(),
                start = Offset(firstX, 0f), end = Offset(lastX, 0f))
        }

        // Fill under the primary line (skipped for the value-coloured temperature line)
        if (!colorByValue) {
            drawPath(
                paths.areaUnder(points, scale, primaryPart, baselineY = PAD_T + h),
                Brush.verticalGradient(
                    listOf(accent.copy(alpha = 0.35f), accent.copy(alpha = 0.02f)),
                    startY = PAD_T, endY = PAD_T + h)
            )
        }

        // Primary line. Each path is drawn straight after it is built — the builder
        // hands back one reused Path, so never hold two at once.
        val lineStroke = Stroke(4.0f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        if (colorByValue) {
            drawPath(paths.line(points, scale, primaryPart), valueBrush(primaryPart, alpha = 1f), style = lineStroke)
        } else {
            drawPath(paths.line(points, scale, primaryPart), accent, style = lineStroke)
        }

        // Min line (dashed) for daily/monthly
        if (lod != Lod.HOURLY) {
            val minStroke = Stroke(3.0f, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = dash)
            if (colorByValue) {
                drawPath(paths.line(points, scale, Part.LO), valueBrush(Part.LO, alpha = 0.9f), style = minStroke)
            } else {
                drawPath(paths.line(points, scale, Part.LO), accent.copy(alpha = 0.6f), style = minStroke)
            }
        }

        // Subtle hint marking the lower pan zone
        val panZoneTop = PAD_T + h * 0.8f
        drawLine(Color(0x10FFFFFF), Offset(PAD_L, panZoneTop), Offset(PAD_L + w, panZoneTop), 1f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 7f)))
        val hint = textMeasurer.measure("⇄ drag here to scroll through time",
            TextStyle(fontSize = 9.sp, color = Color(0x44FFFFFF)))
        drawText(hint, topLeft = Offset(PAD_L + (w - hint.size.width) / 2f, PAD_T + h - hint.size.height - 2f))

        // Min & max pills — placed on the actual plotted line (midpoint in hourly,
        // the hi/lo envelope in daily/monthly)
        if (lod == Lod.HOURLY) {
            val maxP = points.maxByOrNull { it.mid }!!
            val minP = points.minByOrNull { it.mid }!!
            drawMarker(textMeasurer, xOf(maxP.tMs), yOf(maxP.mid), "%.1f".format(disp(maxP.mid)), colorAt(maxP.mid), above = true)
            drawMarker(textMeasurer, xOf(minP.tMs), yOf(minP.mid), "%.1f".format(disp(minP.mid)), colorAt(minP.mid), above = false)
        } else {
            val maxP = points.maxByOrNull { it.hi }!!
            val minP = points.minByOrNull { it.lo }!!
            drawMarker(textMeasurer, xOf(maxP.tMs), yOf(maxP.hi), "%.1f".format(disp(maxP.hi)), colorAt(maxP.hi), above = true)
            drawMarker(textMeasurer, xOf(minP.tMs), yOf(minP.lo), "%.1f".format(disp(minP.lo)), colorAt(minP.lo), above = false)
        }

        // X-axis time labels, on round clock boundaries
        val target = (w / 90f).roundToInt().coerceIn(2, 6)
        for (tMs in labels.axisTicks(viewport.startMs, viewport.endMs, target)) {
            val lbl = textMeasurer.measure(labels.axis(lod, tMs), axisLabel)
            val tx = (xOf(tMs) - lbl.size.width / 2f).coerceIn(0f, size.width - lbl.size.width)
            drawText(lbl, topLeft = Offset(tx, PAD_T + h + 6f))
        }

        // ── Scrubber ──────────────────────────────────────────────────────────
        val sel = scrubberMs
        if (sel != null && sel in viewport.startMs..viewport.endMs) {
            val near = points.bucketAt(sel, lod)
            if (near != null) {
                val x = xOf(near.tMs)
                drawLine(Color(0x88FFFFFF), Offset(x, PAD_T), Offset(x, PAD_T + h), 1.5f)

                if (lod == Lod.HOURLY) {
                    drawScrubDot(x, yOf(near.mid), colorAt(near.mid))
                } else {
                    drawScrubDot(x, yOf(near.hi), colorAt(near.hi))
                    drawScrubDot(x, yOf(near.lo), colorAt(near.lo))
                }

                val valStr = if (lod == Lod.HOURLY) "%.1f%s".format(disp(near.mid), unit)
                             else "%.1f–%.1f%s".format(disp(near.lo), disp(near.hi), unit)
                val pillColor = if (lod == Lod.HOURLY) colorAt(near.mid) else colorAt(near.hi)
                drawMarker(textMeasurer, x, PAD_T + 2f, valStr, pillColor, above = false, solidBg = true)

                if (showTimeLabel) {
                    val title2 = labels.tooltip(lod, near.bucketStart)
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
