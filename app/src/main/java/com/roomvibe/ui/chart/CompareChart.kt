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
import androidx.compose.ui.graphics.SolidColor
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
private const val MAX_DIRECT_LABELS = 5

/** How the min line is set apart from its device's max line: thinner and fainter. */
private const val MIN_LINE_WEIGHT = 0.6f
private const val MIN_LINE_ALPHA = 0.6f

/**
 * Several devices' readings for one metric, on one shared axis.
 *
 * Two measures never share a chart here — the screen picks temperature *or*
 * humidity, so a difference in height always means a difference in the same unit.
 *
 * What colour means here is the caller's choice, carried in each series' style
 * (see [SeriesColoring]). Under BY_VALUE it is the metric's comfort ramp, exactly
 * as on the single-sensor charts, so a room at 22 °C is the same green on both
 * screens — and identity rides on the stroke instead: each device has its own dash
 * pattern and weight. Under BY_DEVICE the style carries a fixed hue and the line
 * is drawn in it.
 *
 * Either way identity is never carried by colour alone: the legend chips and the
 * marker readout repeat each device's stroke, up to [MAX_DIRECT_LABELS] lines name
 * themselves at their right-hand end, and the readout lists every device by name
 * beside its value.
 */
@Composable
fun CompareChart(
    unit: String,
    metric: Metric,
    series: List<CompareSeries>,
    viewport: Viewport,
    scrubberMs: Long?,
    rangeStyle: RangeStyle,
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

        // Which values each device draws, and whether that is one line or two.
        val (upperPart, lowerPart) = drawnParts(rangeStyle, lod)
        val twoLines = upperPart != lowerPart

        // How each device's line is painted: its own hue, or the value ramp — the
        // same mapping the single-sensor charts use, painted along the line so
        // every stretch shows the reading it was taken from.
        //
        // Built once per device per frame: a band is drawn twice with the line on
        // top, and rebuilding the gradient for each was three times the work for
        // no visible difference — the alpha varies, the colours don't.
        fun paintFor(s: CompareSeries, part: Part) =
            s.style.color?.let { SolidColor(it) }
                ?: valueBrush(s.points, scale, part, alpha = 1f) { v -> metricColor(metric, v) }

        val ramps = plotted.map { paintFor(it, upperPart) }

        // "Midpoint + area" shades the whole day behind the line: one closed path,
        // filled and then stroked, which stays readable even where bands overlap.
        // The other styles draw the range as lines instead, so no band.
        if (lod != Lod.HOURLY && rangeStyle == RangeStyle.MIDPOINT_AREA) {
            val fillAlpha = when (plotted.size) {
                1, 2 -> 0.20f
                3, 4 -> 0.15f
                else -> 0.10f
            }
            val edge = Stroke(1.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            plotted.forEachIndexed { i, s ->
                val band = paths.envelope(s.points, scale)
                drawPath(band, ramps[i], alpha = fillAlpha)
                drawPath(band, ramps[i], alpha = 0.55f, style = edge)
            }
        }

        // The lines themselves: the metric's colour ramp along the stroke, the
        // device's own dash pattern and weight across it.
        plotted.forEachIndexed { i, s ->
            drawPath(
                paths.line(s.points, scale, upperPart),
                ramps[i],
                style = Stroke(s.style.width, cap = StrokeCap.Round, join = StrokeJoin.Round,
                    pathEffect = s.style.pathEffect)
            )
        }

        // The min line, when there is one. The single-sensor chart dashes its min
        // line to tell it from the max — here the dash pattern is already spoken
        // for, it says *which device*, so a second pattern on top of that would
        // make both unreadable. So the min keeps its device's pattern and is drawn
        // thinner and fainter instead, always below its own max.
        if (twoLines) {
            for (s in plotted) {
                drawPath(
                    paths.line(s.points, scale, lowerPart),
                    paintFor(s, lowerPart),
                    alpha = MIN_LINE_ALPHA,
                    style = Stroke((s.style.width * MIN_LINE_WEIGHT).coerceAtLeast(1.6f),
                        cap = StrokeCap.Round, join = StrokeJoin.Round,
                        pathEffect = s.style.pathEffect)
                )
            }
        }

        // Direct labels: a few lines name themselves, so reading the chart doesn't
        // need a trip to the legend.
        if (plotted.size in 2..MAX_DIRECT_LABELS) {
            drawEndLabels(textMeasurer, plotted, metric, upperPart, lowerPart,
                plotRight = PAD_L + w,
                plotTop = PAD_T, plotBottom = PAD_T + h,
                xOf = { t -> xOf(t) }, yOf = { v -> yOf(v) })
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
            // Snap the crosshair to the nearest reading rather than leaving it
            // wherever the finger stopped, so it lands on a dot instead of
            // floating between two of them.
            var anchor: SeriesPoint? = null
            var closest = Long.MAX_VALUE
            for (s in plotted) {
                val near = s.points.bucketAt(sel, lod) ?: continue
                val gap = abs(near.tMs - sel)
                if (gap < closest) { closest = gap; anchor = near }
            }

            val lineX = xOf(anchor?.tMs ?: sel)
            drawLine(Color(0x88FFFFFF), Offset(lineX, PAD_T), Offset(lineX, PAD_T + h), 1.5f)

            // Each dot stays on its own device's reading. At day zoom every device
            // shares one bucket so they all land on the line; at hourly zoom they
            // genuinely aren't simultaneous — each sensor keeps its own hourly
            // clock — and pretending otherwise would misplace the value.
            for (s in plotted) {
                val near = s.points.bucketAt(sel, lod) ?: continue
                val up = near.value(upperPart)
                drawScrubDot(xOf(near.tMs), yOf(up), s.style.color ?: metricColor(metric, up))
                if (twoLines) {
                    val down = near.value(lowerPart)
                    drawScrubDot(xOf(near.tMs), yOf(down), s.style.color ?: metricColor(metric, down))
                }
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

/** Width of the stroke sample drawn beside a direct label, plus its gap. */
private const val END_SWATCH_W = 18f
private const val END_SWATCH_GAP = 5f

/** Breathing room between a name's plate and the right axis. */
private const val END_AXIS_GAP = 6f

/** Clearance kept between a name and any line, and between one name and the next. */
private const val END_LABEL_CLEARANCE = 4f

/**
 * Names each line, floated as close to its own right-hand end as it can get
 * without landing on top of any line.
 *
 * Every name shares the chart's right edge, so one column range covers them all.
 * Each drawn line's vertical span across that range is treated as occupied — the
 * whole min-to-max sweep, not the pixels it actually inks, so a name can never
 * land between two waves of the same line. What's left over is the free gaps, and
 * each name drops into the gap that puts it nearest the line it belongs to.
 *
 * A name that has nowhere clear to go is dropped rather than drawn over a line:
 * the chips below the chart already name every device, so a missing one costs
 * less than an unreadable one.
 *
 * Each name is preceded by a short sample of its own stroke, and sits in the
 * colour its line has there. Under value colouring that colour is the reading
 * rather than the device, so the stroke sample is what ties the name to a line.
 */
private fun DrawScope.drawEndLabels(
    textMeasurer: TextMeasurer,
    series: List<CompareSeries>,
    metric: Metric,
    upperPart: Part,
    lowerPart: Part,
    plotRight: Float,
    plotTop: Float,
    plotBottom: Float,
    xOf: (Long) -> Float,
    yOf: (Float) -> Float
) {
    data class EndLabel(
        val layout: TextLayoutResult,
        val style: SeriesStyle,
        val color: Color,
        val anchorY: Float
    )

    val lead = END_SWATCH_W + END_SWATCH_GAP

    val labels = series.mapNotNull { s ->
        val last = s.points.last()
        // Skip lines that end off-screen to the right — the label would float free.
        if (xOf(last.tMs) > plotRight + 4f) return@mapNotNull null
        val color = s.style.color ?: metricColor(metric, last.value(upperPart))
        val layout = textMeasurer.measure(
            s.label.take(14),
            TextStyle(fontSize = 10.sp, color = color, fontWeight = FontWeight.SemiBold)
        )
        EndLabel(layout, s.style, color, yOf(last.value(upperPart)))
    }.sortedBy { it.anchorY }
    if (labels.isEmpty()) return

    val x1 = plotRight - END_AXIS_GAP
    val x0 = (x1 - (labels.maxOf { it.layout.size.width } + lead)).coerceAtLeast(PAD_L)

    // What every drawn line occupies in that column range.
    val parts = if (upperPart == lowerPart) listOf(upperPart) else listOf(upperPart, lowerPart)
    val blocked = mutableListOf<ClosedFloatingPointRange<Float>>()
    for (s in series) for (part in parts) {
        var lo = Float.POSITIVE_INFINITY
        var hi = Float.NEGATIVE_INFINITY
        var ax = Float.NaN
        var ay = 0f
        for (pt in s.points) {
            val bx = xOf(pt.tMs)
            val by = yOf(pt.value(part))
            // Clip each segment to the column range, so a line that merely crosses
            // it counts for the stretch that shows rather than its whole length.
            if (!ax.isNaN() && maxOf(ax, bx) >= x0 && minOf(ax, bx) <= x1) {
                val run = bx - ax
                val segLo = minOf(ax, bx)
                val segHi = maxOf(ax, bx)
                val yAtC0 = if (run == 0f) by else ay + (by - ay) * ((x0.coerceIn(segLo, segHi) - ax) / run)
                val yAtC1 = if (run == 0f) by else ay + (by - ay) * ((x1.coerceIn(segLo, segHi) - ax) / run)
                lo = minOf(lo, yAtC0, yAtC1)
                hi = maxOf(hi, yAtC0, yAtC1)
            }
            ax = bx
            ay = by
        }
        if (lo <= hi) blocked += (lo - END_LABEL_CLEARANCE)..(hi + END_LABEL_CLEARANCE)
    }

    val gaps = freeGaps(blocked, plotTop, plotBottom).toMutableList()

    // Names keep the vertical order of the lines they belong to: the name of the
    // higher line stays higher. Where two lines run close together the eye pairs
    // name to line by position first, so a pair in the wrong order reads as a
    // straight mislabelling — worse than dropping one. `floor` walks down the plot
    // as each name is placed, which also keeps them off each other.
    var floor = plotTop
    for (l in labels) {
        val h = l.layout.size.height.toFloat()
        // Preferred spot: just above its own line's end, so the name reads as
        // belonging to the line beneath it.
        val want = l.anchorY - h - 6f

        // Two passes, because *which side* matters more than raw distance: a name
        // with its line directly beneath it is read as labelling that line, while
        // the same name a few pixels below sits under a line it doesn't belong to.
        // So take the nearest clear spot that sits above the line, and only settle
        // for below when nothing above fits.
        var bestGap = -1
        var bestY = 0f
        for (aboveOnly in booleanArrayOf(true, false)) {
            var bestDist = Float.MAX_VALUE
            gaps.forEachIndexed { i, g ->
                val lo = maxOf(g.start, floor)
                if (g.endInclusive - lo < h) return@forEachIndexed
                val y = want.coerceIn(lo, g.endInclusive - h)
                if (aboveOnly && y + h > l.anchorY) return@forEachIndexed
                val dist = kotlin.math.abs((y + h / 2f) - l.anchorY)
                if (dist < bestDist) {
                    bestDist = dist
                    bestGap = i
                    bestY = y
                }
            }
            if (bestGap >= 0) break
        }
        if (bestGap < 0) continue   // nowhere clear: leave this one to the chips

        val x = (x1 - l.layout.size.width).coerceAtLeast(PAD_L + lead)
        drawText(l.layout, topLeft = Offset(x, bestY))
        val cy = bestY + h / 2f
        drawLine(
            l.color,
            Offset(x - lead, cy), Offset(x - END_SWATCH_GAP, cy),
            strokeWidth = l.style.width,
            cap = StrokeCap.Round,
            pathEffect = l.style.pathEffect
        )
        floor = bestY + h + END_LABEL_CLEARANCE
    }

}
