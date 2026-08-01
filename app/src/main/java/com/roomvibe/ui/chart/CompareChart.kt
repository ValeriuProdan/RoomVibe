package com.roomvibe.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** One device's line on the compare chart. */
data class CompareSeries(
    val address: String,
    val label: String,
    val style: SeriesStyle,
    val points: List<SeriesPoint>
)

/** Below this many lines each one also gets its name drawn at its right-hand end. */
private const val MAX_DIRECT_LABELS = 4

/**
 * Several devices' readings for one metric, on one shared axis.
 *
 * Two measures never share a chart here — the screen picks temperature *or*
 * humidity, so a difference in height always means a difference in the same unit.
 * Identity is carried by colour (see [seriesStyle]) and never by colour alone:
 * the legend chips below the chart repeat every colour, up to four lines are
 * labelled directly at their right-hand end, and the scrubber readout names each
 * device beside its value.
 */
@Composable
fun CompareChart(
    unit: String,
    metric: Metric,
    series: List<CompareSeries>,
    viewport: Viewport,
    scrubberMs: Long?,
    dataMin: Long,
    dataMax: Long,
    fahrenheit: Boolean,
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

    if (series.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("Pick devices below to compare", color = Color(0xFF9AA0A6))
        }
        return
    }

    Canvas(
        modifier = modifier.chartGestures(
            // Keyed on which devices are plotted, NOT on their point counts: the
            // visible count changes as buckets scroll in, and that would restart
            // the gesture handler — cancelling the drag — halfway through a pan.
            resetKey = series.map { it.address },
            viewport = viewport,
            dataMin = dataMin,
            dataMax = dataMax,
            onViewportChange = onViewportChange,
            onScrub = onScrub
        )
    ) {
        val w = size.width - PAD_L - PAD_R
        val h = size.height - PAD_T - PAD_B
        if (w < 8f || h < 8f) return@Canvas   // degenerate size, e.g. mid-rotation

        val lod = lodFor(viewport.span)
        // Only devices that actually reach this window: one whose history stops
        // short of it would otherwise stretch the shared y-axis to fit a value
        // that is nowhere on screen.
        val plotted = series.filter { it.points.intersects(viewport.startMs, viewport.endMs) }
        if (plotted.isEmpty()) {
            drawText(textMeasurer.measure("No data in range", axisLabel),
                topLeft = Offset(PAD_L, PAD_T + h / 2))
            return@Canvas
        }

        fun disp(v: Float): Float = dispValue(v, metric, fahrenheit)

        // One shared y-scale across every device — that is the whole point of the
        // screen, so it spans all series' envelopes, not each line's own range.
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (s in plotted) for (p in s.points) {
            if (p.lo < lo) lo = p.lo
            if (p.hi > hi) hi = p.hi
        }
        lo = floor(lo - 1f); hi = ceil(hi + 1f)
        val range = (hi - lo).coerceAtLeast(1f)

        val scale = ChartScale(viewport.startMs, viewport.span, PAD_L, w, lo, range, PAD_T, h)
        fun xOf(tMs: Long) = scale.x(tMs)
        fun yOf(v: Float) = scale.y(v)

        // Dashed grid + right axis labels
        val gridDash = PathEffect.dashPathEffect(floatArrayOf(6f, 8f))
        for (i in 0..4) {
            val frac = i / 4f
            val y = PAD_T + h - frac * h
            drawLine(gridColor, Offset(PAD_L, y), Offset(PAD_L + w, y), 1f, pathEffect = gridDash)
            val lbl = textMeasurer.measure("%.0f%s".format(disp(lo + frac * range), unit), axisLabel)
            drawText(lbl, topLeft = Offset(PAD_L + w + 8f, y - lbl.size.height / 2))
        }

        // Zoomed out, one point per day would hide the whole story — a day that ran
        // 5→18 °C and one that sat at 11 °C all day average out the same. So each
        // day/month bucket draws its min–max envelope: one closed path, filled and
        // then stroked, which stays readable even where several bands overlap.
        if (lod != Lod.HOURLY) {
            val fillAlpha = when (plotted.size) {
                1, 2 -> 0.20f
                3, 4 -> 0.15f
                else -> 0.10f
            }
            val edge = Stroke(1.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            for (s in plotted) {
                val band = paths.envelope(s.points, scale)
                drawPath(band, s.style.color.copy(alpha = fillAlpha))
                drawPath(band, s.style.color.copy(alpha = 0.55f), style = edge)
            }
        }

        // The lines themselves, each in its device's colour
        for (s in plotted) {
            drawPath(
                paths.line(s.points, scale, Part.MID),
                s.style.color,
                style = Stroke(3.5f, cap = StrokeCap.Round, join = StrokeJoin.Round,
                    pathEffect = s.style.pathEffect)
            )
        }

        // Subtle hint marking the lower pan zone
        val panZoneTop = PAD_T + h * 0.8f
        drawLine(Color(0x10FFFFFF), Offset(PAD_L, panZoneTop), Offset(PAD_L + w, panZoneTop), 1f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 7f)))
        val hint = textMeasurer.measure("⇄ drag here to scroll through time",
            TextStyle(fontSize = 9.sp, color = Color(0x44FFFFFF)))
        drawText(hint, topLeft = Offset(PAD_L + (w - hint.size.width) / 2f, PAD_T + h - hint.size.height - 2f))

        // Direct labels: a few lines name themselves, so reading the chart doesn't
        // need a trip to the legend.
        if (plotted.size in 2..MAX_DIRECT_LABELS) {
            drawEndLabels(textMeasurer, plotted, plotRight = PAD_L + w, plotTop = PAD_T,
                plotBottom = PAD_T + h, xOf = { t -> xOf(t) }, yOf = { v -> yOf(v) })
        }

        // X-axis time labels, on round clock boundaries
        val target = (w / 90f).roundToInt().coerceIn(2, 6)
        for (tMs in labels.axisTicks(viewport.startMs, viewport.endMs, target)) {
            val lbl = textMeasurer.measure(labels.axis(lod, tMs), axisLabel)
            val tx = (xOf(tMs) - lbl.size.width / 2f).coerceIn(0f, size.width - lbl.size.width)
            drawText(lbl, topLeft = Offset(tx, PAD_T + h + 6f))
        }

        // ── Scrubber ──────────────────────────────────────────────────────────
        // The values themselves are listed under the chart, where they have room
        // to be named and sorted; here we only mark where each line was crossed.
        val sel = scrubberMs
        if (sel != null && sel in viewport.startMs..viewport.endMs) {
            drawLine(Color(0x88FFFFFF), Offset(xOf(sel), PAD_T), Offset(xOf(sel), PAD_T + h), 1.5f)
            // A device only gets a dot where it actually recorded something —
            // never on the far-off overscan point it may have been sliced with.
            var anchor: SeriesPoint? = null
            for (s in plotted) {
                val near = s.points.bucketAt(sel, lod) ?: continue
                drawScrubDot(xOf(near.tMs), yOf(near.mid), s.style.color)
                if (anchor == null) anchor = near
            }
            anchor?.let {
                val stamp = textMeasurer.measure(
                    labels.tooltip(lod, it.bucketStart),
                    TextStyle(fontSize = 11.sp, color = Color(0xFFE8EAED))
                )
                drawText(stamp, topLeft = Offset(
                    (PAD_L + w - stamp.size.width).coerceAtLeast(PAD_L), 4f))
            }
        }
    }
}

/**
 * Names each line at its right-hand end, nudging labels apart vertically when the
 * lines end close together so they never overlap.
 */
private fun DrawScope.drawEndLabels(
    textMeasurer: TextMeasurer,
    series: List<CompareSeries>,
    plotRight: Float,
    plotTop: Float,
    plotBottom: Float,
    xOf: (Long) -> Float,
    yOf: (Float) -> Float
) {
    data class EndLabel(val layout: TextLayoutResult, val color: Color, val anchorY: Float, var y: Float)

    val labels = series.mapNotNull { s ->
        val last = s.points.last()
        // Skip lines that end off-screen to the right — the label would float free.
        if (xOf(last.tMs) > plotRight + 4f) return@mapNotNull null
        val layout = textMeasurer.measure(
            s.label.take(14),
            TextStyle(fontSize = 10.sp, color = s.style.color, fontWeight = FontWeight.SemiBold)
        )
        val y = yOf(last.mid) - layout.size.height - 6f
        EndLabel(layout, s.style.color, y, y)
    }.sortedBy { it.anchorY }

    // Single downward pass is enough for a handful of labels: push each one below
    // the previous, then clamp the whole stack into the plot band.
    var minY = plotTop
    for (l in labels) {
        l.y = maxOf(l.y, minY)
        minY = l.y + l.layout.size.height + 2f
    }
    val overflow = minY - plotBottom
    if (overflow > 0f) labels.forEach { it.y = (it.y - overflow).coerceAtLeast(plotTop) }

    for (l in labels) {
        val x = (plotRight - l.layout.size.width).coerceAtLeast(PAD_L)
        drawText(l.layout, topLeft = Offset(x, l.y))
    }
}
