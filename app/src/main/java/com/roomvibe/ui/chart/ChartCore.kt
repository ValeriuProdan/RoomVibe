package com.roomvibe.ui.chart

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.roomvibe.data.entity.Reading
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Pieces shared by every chart in the app: the time model (viewport + level of
 * detail), the aggregation that turns raw readings into plottable buckets, the
 * pan/zoom/scrub gesture handling, and the low-level drawing helpers.
 *
 * [MetricChart] draws one sensor; [CompareChart] draws several on one axis.
 */

internal const val MINUTE = 60_000L
internal const val HOUR = 3_600_000L
internal const val DAY = 24 * HOUR

internal const val PAD_L = 12f
internal const val PAD_R = 52f
internal const val PAD_T = 34f   // header band: title (left) + scrubber date (right)
internal const val PAD_B = 26f

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

/** Converts a stored Celsius value to the unit the user asked for. */
internal fun dispValue(v: Float, metric: Metric, fahrenheit: Boolean): Float =
    if (metric == Metric.TEMP && fahrenheit) v * 9f / 5f + 32f else v

// ── Value → colour ramps (single-sensor charts) ─────────────────────────────

// Temperature → colour ramp anchored to human thermal comfort (ASHRAE ~20–25 °C
// comfort zone): green through the comfortable band, blue when cold, red when hot.
private val TEMP_STOPS = listOf(
    0f to Color(0xFF1565C0),    // very cold — deep blue
    8f to Color(0xFF2196F3),    // cold — blue
    14f to Color(0xFF26C6DA),   // cool — cyan
    18f to Color(0xFF66BB6A),   // slightly cool — light green
    20f to Color(0xFF43A047),   // comfortable — green
    25f to Color(0xFF43A047),   // comfortable — green
    28f to Color(0xFFF4C020),   // warm — amber
    31f to Color(0xFFFF7A1A),   // hot — orange
    36f to Color(0xFFE53935)    // very hot — red
)

// Humidity → comfort ramp: green at the ideal (~48%), shading to red at both
// extremes (too dry and too humid).
private val HUMID_STOPS = listOf(
    15f to Color(0xFFE53935),   // too dry — red
    30f to Color(0xFFFB8C00),   // orange
    40f to Color(0xFF9CCC65),   // light green
    48f to Color(0xFF43A047),   // ideal — green
    56f to Color(0xFF9CCC65),   // light green
    68f to Color(0xFFFB8C00),   // orange
    82f to Color(0xFFE53935)    // too humid — red
)

private fun interpStops(value: Float, stops: List<Pair<Float, Color>>): Color {
    val first = stops.first()
    val last = stops.last()
    if (value <= first.first) return first.second
    if (value >= last.first) return last.second
    for (i in 0 until stops.size - 1) {
        val (v0, c0) = stops[i]
        val (v1, c1) = stops[i + 1]
        if (value in v0..v1) return lerp(c0, c1, (value - v0) / (v1 - v0))
    }
    return last.second
}

fun tempColor(celsius: Float): Color = interpStops(celsius, TEMP_STOPS)
fun humidColor(percent: Float): Color = interpStops(percent, HUMID_STOPS)

// ── Aggregation ─────────────────────────────────────────────────────────────

/** One plotted bucket: an hourly sample, or the min/max envelope of a day/month. */
data class SeriesPoint(
    val tMs: Long,
    val bucketStart: Long,
    val lo: Float,
    val hi: Float
) {
    val mid get() = (lo + hi) / 2f
}

/**
 * One sensor's readings, prepared for drawing at a given level of detail.
 *
 * Bucketing is viewport-independent, so it happens once here rather than on every
 * frame of a pan or pinch: at day/month zoom the whole history is bucketed up
 * front and [visible] only has to binary-search the result. Hourly detail stays
 * raw, because there the viewport is a few days at most and slicing the readings
 * costs less than bucketing every record ever recorded.
 *
 * Build these inside `remember(readings, lod, metric)` — never in a draw pass.
 */
class SeriesData(
    private val readings: List<Reading>,
    private val metric: Metric
) {
    // Each zoom level is bucketed at most once, and only if it is actually used —
    // so pinching across a zoom boundary doesn't re-aggregate the whole history.
    private val daily by lazy(LazyThreadSafetyMode.NONE) { bucketAll(readings, Lod.DAILY, metric) }
    private val monthly by lazy(LazyThreadSafetyMode.NONE) { bucketAll(readings, Lod.MONTHLY, metric) }

    /** The points overlapping the viewport, plus one either side so lines reach the edges. */
    fun visible(lod: Lod, startMs: Long, endMs: Long): List<SeriesPoint> {
        if (lod == Lod.HOURLY) {
            val (lo, hi) = sliceBounds(readings.size, startMs, endMs) { i -> readings[i].timestampMs }
            if (lo >= hi) return emptyList()
            return readings.subList(lo, hi).map {
                SeriesPoint(it.timestampMs, it.timestampMs, loOf(it, metric), hiOf(it, metric))
            }
        }
        val b = if (lod == Lod.MONTHLY) monthly else daily
        val (lo, hi) = sliceBounds(b.size, startMs, endMs) { i -> b[i].tMs }
        return if (lo >= hi) emptyList() else b.subList(lo, hi)
    }

    /**
     * The bucket describing [tMs] anywhere in this device's history, independent of
     * what is currently on screen — so a reading stays readable after the chart is
     * scrolled away from it.
     */
    fun bucketAt(tMs: Long, lod: Lod): SeriesPoint? {
        val tolerance = scrubToleranceMs(lod)
        return visible(lod, tMs - tolerance, tMs + tolerance).bucketAt(tMs, lod)
    }
}

@Composable
fun rememberSeriesData(readings: List<Reading>, metric: Metric): SeriesData =
    remember(readings, metric) { SeriesData(readings, metric) }

/**
 * How far the marker may sit from a bucket before that bucket stops describing it.
 * Day and month buckets are timestamped at their midpoint, so half a bucket means
 * "inside this bucket"; hourly points carry their reading's own timestamp, and
 * these sensors record once an hour.
 */
internal fun scrubToleranceMs(lod: Lod): Long = when (lod) {
    Lod.HOURLY -> HOUR
    Lod.DAILY -> DAY / 2
    Lod.MONTHLY -> 16 * DAY
}

/**
 * The bucket describing [tMs], or null when this device recorded nothing then.
 *
 * [SeriesData.visible] keeps one point beyond each edge of the viewport so lines
 * can run off the frame. That means the *nearest* point to a given time can be an
 * arbitrarily distant one for a device whose history doesn't reach that far back —
 * and reading a value off it would report a temperature the sensor never recorded.
 * Always come through here rather than taking the nearest point directly.
 */
internal fun List<SeriesPoint>.bucketAt(tMs: Long, lod: Lod): SeriesPoint? {
    val near = minByOrNull { abs(it.tMs - tMs) } ?: return null
    return if (abs(near.tMs - tMs) <= scrubToleranceMs(lod)) near else null
}

/**
 * True when this slice has something to show between [startMs] and [endMs] — either
 * a point inside the window, or a line entering one edge and leaving the other.
 *
 * A device whose data stops short of the window contributes a single far-off
 * overscan point; that draws nothing, but left unchecked it still stretches the
 * shared y-axis to fit a value that isn't on screen.
 */
internal fun List<SeriesPoint>.intersects(startMs: Long, endMs: Long): Boolean {
    if (isEmpty()) return false
    if (any { it.tMs in startMs..endMs }) return true
    return first().tMs < startMs && last().tMs > endMs
}

private fun loOf(r: Reading, metric: Metric) =
    if (metric == Metric.TEMP) r.tempMinC else r.humMin.toFloat()

private fun hiOf(r: Reading, metric: Metric) =
    if (metric == Metric.TEMP) r.tempMaxC else r.humMax.toFloat()

/**
 * Buckets an entire reading list into per-day or per-month min/max envelopes.
 *
 * Readings are in time order, so the calendar is only consulted when a bucket
 * actually ends — twice per bucket rather than once per reading, which is what
 * made panning stutter. `Calendar.add` (not arithmetic) keeps day boundaries on
 * local midnight across DST changes.
 */
private fun bucketAll(readings: List<Reading>, lod: Lod, metric: Metric): List<SeriesPoint> {
    if (readings.isEmpty()) return emptyList()

    val cal = Calendar.getInstance()
    fun startOfBucket(ms: Long): Long {
        cal.timeInMillis = ms
        cal.set(Calendar.MILLISECOND, 0); cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MINUTE, 0); cal.set(Calendar.HOUR_OF_DAY, 0)
        if (lod == Lod.MONTHLY) cal.set(Calendar.DAY_OF_MONTH, 1)
        return cal.timeInMillis
    }
    fun endOfBucket(start: Long): Long {
        cal.timeInMillis = start
        cal.add(if (lod == Lod.MONTHLY) Calendar.MONTH else Calendar.DAY_OF_MONTH, 1)
        return cal.timeInMillis
    }

    val out = ArrayList<SeriesPoint>()
    var start = startOfBucket(readings.first().timestampMs)
    var end = endOfBucket(start)
    var mn = Float.MAX_VALUE
    var mx = -Float.MAX_VALUE

    fun flush() {
        if (mn <= mx) {
            val mid = if (lod == Lod.MONTHLY) start + 15 * DAY else start + 12 * HOUR
            out.add(SeriesPoint(mid, start, mn, mx))
        }
    }

    for (r in readings) {
        if (r.timestampMs >= end) {
            flush()
            start = startOfBucket(r.timestampMs)
            end = endOfBucket(start)
            mn = Float.MAX_VALUE; mx = -Float.MAX_VALUE
        }
        val lo = loOf(r, metric); if (lo < mn) mn = lo
        val hi = hiOf(r, metric); if (hi > mx) mx = hi
    }
    flush()
    return out
}

/** Index range covering [startMs, endMs] with one item of overscan on each side. */
private inline fun sliceBounds(
    size: Int,
    startMs: Long,
    endMs: Long,
    timeAt: (Int) -> Long
): Pair<Int, Int> {
    if (size == 0) return 0 to 0
    val lo = (lowerBound(size, startMs, timeAt) - 1).coerceAtLeast(0)
    var hi = lowerBound(size, endMs, timeAt)
    if (hi < size) hi++
    return lo to hi.coerceAtMost(size)
}

private inline fun lowerBound(size: Int, tMs: Long, timeAt: (Int) -> Long): Int {
    var lo = 0; var hi = size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (timeAt(mid) < tMs) lo = mid + 1 else hi = mid
    }
    return lo
}

// ── Gestures ────────────────────────────────────────────────────────────────

/**
 * The shared chart gesture model: tap anywhere to place the marker, drag anywhere
 * to scroll through time, two fingers to zoom.
 *
 * Tapping and dragging share the whole plot, so the marker is placed on release
 * and only when the touch never travelled past the touch slop — otherwise every
 * pan would fling the marker to wherever the drag happened to start. Pointer
 * events are consumed once a drag begins, so an enclosing pager can't steal it
 * mid-gesture.
 */
@Composable
internal fun Modifier.chartGestures(
    resetKey: Any,
    viewport: Viewport,
    dataMin: Long,
    dataMax: Long,
    onViewportChange: (Viewport) -> Unit,
    onScrub: (Long?) -> Unit
): Modifier {
    val vpState = rememberUpdatedState(viewport)
    val onVpState = rememberUpdatedState(onViewportChange)
    val onScrubState = rememberUpdatedState(onScrub)
    val minSpan = 3 * HOUR
    val maxSpan = ((dataMax - dataMin).coerceAtLeast(DAY) * 1.1).toLong()

    return this.pointerInput(resetKey) {
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            val first = awaitFirstDown(requireUnconsumed = false)
            val downX = first.position.x
            var prevX = downX
            var dragging = false
            var pinched = false
            var prevCentroid: Offset? = null
            var prevSpread = 0f

            while (true) {
                val event = awaitPointerEvent()
                val pts = event.changes.filter { it.pressed }
                if (pts.isEmpty()) break
                if (pts.size == 1) {
                    val x = pts[0].position.x
                    // The slop distance is absorbed rather than applied, so the
                    // chart doesn't jump when a drag is recognised.
                    if (!dragging && abs(x - downX) > slop) dragging = true
                    if (dragging) {
                        panBy(x - prevX, size.width, vpState.value, dataMin, dataMax, onVpState.value)
                        pts[0].consume()
                    }
                    prevX = x
                    prevCentroid = null; prevSpread = 0f
                } else {
                    pinched = true
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

            // A touch that stayed put was a tap: place the marker where it landed.
            if (!dragging && !pinched) {
                scrubAt(downX, size.width, vpState.value, onScrubState.value)
            }
        }
    }
}

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

/** Pinch/pan step. Internal rather than private so the zoom limits can be tested. */
internal fun applyTransform(
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

// ── Drawing helpers ─────────────────────────────────────────────────────────

/**
 * Maps times and values to pixels.
 *
 * Deliberately a class with member functions rather than a pair of lambdas:
 * `(Long) -> Float` compiles to `Function1<Long, Float>`, which boxes both the
 * argument and the result on every call.
 */
internal class ChartScale(
    private val startMs: Long,
    private val spanMs: Long,
    private val plotLeft: Float,
    private val plotWidth: Float,
    private val valueLo: Float,
    private val valueRange: Float,
    private val plotTop: Float,
    private val plotHeight: Float
) {
    fun x(tMs: Long): Float =
        plotLeft + ((tMs - startMs).toDouble() / spanMs * plotWidth).toFloat()

    fun y(value: Float): Float =
        plotTop + plotHeight - (value - valueLo) / valueRange * plotHeight
}

/** Which of a bucket's three values a path follows. */
internal enum class Part { MID, HI, LO }

private fun SeriesPoint.value(part: Part): Float = when (part) {
    Part.MID -> mid
    Part.HI -> hi
    Part.LO -> lo
}

/**
 * Builds smooth chart paths without allocating.
 *
 * The obvious way to draw a series — map the points to a `List<Offset>` and hand
 * that to a spline helper — boxes every coordinate, because [Offset] is a value
 * class over a Long. A pan then rebuilds those lists sixty times a second for
 * every visible device, and the resulting garbage is what makes the chart stutter.
 * So this keeps primitive scratch buffers and a single Path, reused across frames.
 *
 * Hold one in `remember`. Every call returns the *same* Path instance, so draw it
 * before building the next one — safe because draw commands copy path data when
 * they are recorded.
 */
internal class ChartPaths {
    private var xs = FloatArray(0)
    private var ys = FloatArray(0)
    private var dx = FloatArray(0)
    private var slope = FloatArray(0)
    private var tangent = FloatArray(0)
    private val path = Path()

    private fun ensure(n: Int) {
        if (xs.size >= n) return
        val cap = maxOf(n, 64)
        xs = FloatArray(cap); ys = FloatArray(cap); dx = FloatArray(cap)
        slope = FloatArray(cap); tangent = FloatArray(cap)
    }

    private fun load(points: List<SeriesPoint>, scale: ChartScale, part: Part, reversed: Boolean) {
        val n = points.size
        ensure(n)
        for (i in 0 until n) {
            val p = points[if (reversed) n - 1 - i else i]
            xs[i] = scale.x(p.tMs)
            ys[i] = scale.y(p.value(part))
        }
    }

    /** A line through the loaded points. */
    fun line(points: List<SeriesPoint>, scale: ChartScale, part: Part): Path {
        path.reset()
        load(points, scale, part, reversed = false)
        appendSpline(points.size, moveTo = true)
        return path
    }

    /** The line, closed down to [baselineY] — the filled area under a series. */
    fun areaUnder(points: List<SeriesPoint>, scale: ChartScale, part: Part, baselineY: Float): Path {
        path.reset()
        load(points, scale, part, reversed = false)
        val n = points.size
        appendSpline(n, moveTo = true)
        if (n > 0) {
            path.lineTo(xs[n - 1], baselineY)
            path.lineTo(xs[0], baselineY)
            path.close()
        }
        return path
    }

    /**
     * The min–max envelope: the maxima left to right, then the minima back again,
     * closed. One path instead of three, so it can be filled and stroked as a unit.
     */
    fun envelope(points: List<SeriesPoint>, scale: ChartScale): Path {
        path.reset()
        val n = points.size
        load(points, scale, Part.HI, reversed = false)
        appendSpline(n, moveTo = true)
        load(points, scale, Part.LO, reversed = true)
        appendSpline(n, moveTo = false)
        path.close()
        return path
    }

    /**
     * Appends a monotone cubic Hermite spline (Fritsch–Carlson) through the
     * loaded points. Unlike a plain cubic it never overshoots or wiggles between
     * samples — where the data is monotone, so is the curve.
     */
    private fun appendSpline(n: Int, moveTo: Boolean) {
        if (n == 0) return
        if (moveTo) path.moveTo(xs[0], ys[0]) else path.lineTo(xs[0], ys[0])
        if (n == 1) return
        if (n == 2) { path.lineTo(xs[1], ys[1]); return }

        for (i in 0 until n - 1) {
            dx[i] = xs[i + 1] - xs[i]
            slope[i] = if (dx[i] != 0f) (ys[i + 1] - ys[i]) / dx[i] else 0f
        }

        // Initial tangents: average of neighbouring secants, flat at local extrema
        tangent[0] = slope[0]
        tangent[n - 1] = slope[n - 2]
        for (i in 1 until n - 1) {
            tangent[i] = if (slope[i - 1] * slope[i] <= 0f) 0f else (slope[i - 1] + slope[i]) / 2f
        }

        // Constrain tangents so each segment stays monotone (no overshoot)
        for (i in 0 until n - 1) {
            if (slope[i] == 0f) {
                tangent[i] = 0f; tangent[i + 1] = 0f
            } else {
                val a = tangent[i] / slope[i]
                val b = tangent[i + 1] / slope[i]
                val s = a * a + b * b
                if (s > 9f) {
                    val t = 3f / sqrt(s)
                    tangent[i] = t * a * slope[i]
                    tangent[i + 1] = t * b * slope[i]
                }
            }
        }

        // Emit each segment as a cubic Bézier from the Hermite tangents
        for (i in 0 until n - 1) {
            val d = dx[i]
            path.cubicTo(
                xs[i] + d / 3f, ys[i] + tangent[i] * d / 3f,
                xs[i + 1] - d / 3f, ys[i + 1] - tangent[i + 1] * d / 3f,
                xs[i + 1], ys[i + 1]
            )
        }
    }
}

internal fun DrawScope.drawScrubDot(x: Float, y: Float, accent: Color) {
    drawCircle(Color.White, 5f, Offset(x, y))
    drawCircle(accent, 3f, Offset(x, y))
}

internal fun DrawScope.drawMarker(
    textMeasurer: TextMeasurer,
    cx: Float, cy: Float, text: String, accent: Color, above: Boolean, solidBg: Boolean = false
) {
    drawCircle(Color.White, 4f, Offset(cx, cy))
    val m = textMeasurer.measure(text, TextStyle(fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.SemiBold))
    val padX = 7f; val padY = 3f
    val boxW = m.size.width + padX * 2; val boxH = m.size.height + padY * 2
    val bx = (cx - boxW / 2).coerceIn(0f, maxOf(0f, size.width - boxW))
    // Keep the pill inside the plot band so it never overlaps the header title or axis labels
    val by = (if (above) cy - boxH - 8f else cy + 8f)
        .coerceIn(PAD_T, maxOf(PAD_T, size.height - PAD_B - boxH))
    drawRoundRect(if (solidBg) accent else accent.copy(alpha = 0.92f),
        topLeft = Offset(bx, by), size = Size(boxW, boxH), cornerRadius = CornerRadius(boxH / 2, boxH / 2))
    drawText(m, topLeft = Offset(bx + padX, by + padY))
}

// ── Time formatting ─────────────────────────────────────────────────────────

/**
 * Axis and tooltip text, and the tick positions they sit at.
 *
 * Built once and held across frames: constructing a [SimpleDateFormat] parses its
 * pattern and loads locale data, which is far too expensive to do inside a draw
 * pass — and the charts were building two of them per frame.
 */
internal class TimeLabels(locale: Locale = Locale.getDefault()) {
    private val axisHourly = SimpleDateFormat("HH:mm", locale)
    private val axisDaily = SimpleDateFormat("d MMM", locale)
    private val axisMonthly = SimpleDateFormat("MMM yy", locale)
    private val tipHourly = SimpleDateFormat("EEE d MMM, HH:00", locale)
    private val tipDaily = SimpleDateFormat("EEE d MMM yyyy", locale)
    private val tipMonthly = SimpleDateFormat("MMMM yyyy", locale)

    private val scratchDate = Date()
    private val cal: Calendar = Calendar.getInstance()
    private val ticks = ArrayList<Long>(12)

    private fun format(fmt: SimpleDateFormat, tMs: Long): String {
        scratchDate.time = tMs
        return fmt.format(scratchDate)
    }

    fun axis(lod: Lod, tMs: Long): String = format(
        when (lod) {
            Lod.HOURLY -> axisHourly
            Lod.DAILY -> axisDaily
            Lod.MONTHLY -> axisMonthly
        },
        tMs
    )

    fun tooltip(lod: Lod, bucketStart: Long): String = format(
        when (lod) {
            Lod.HOURLY -> tipHourly
            Lod.DAILY -> tipDaily
            Lod.MONTHLY -> tipMonthly
        },
        bucketStart
    )

    /**
     * Tick times across the viewport, snapped to round clock boundaries — 12:00
     * rather than 13:38.
     *
     * As well as reading better, this is what keeps the text-layout cache warm:
     * arbitrary tick times produce a fresh set of unique strings on every frame of
     * a pan, and laying those out was the bulk of the chart's per-frame cost.
     * Snapped ticks repeat, so they are measured once and reused.
     *
     * The returned list is reused between calls — read it before calling again.
     */
    fun axisTicks(startMs: Long, endMs: Long, target: Int): List<Long> {
        ticks.clear()
        val span = endMs - startMs
        if (span <= 0 || target <= 0) return ticks
        val ideal = span / target

        if (ideal >= 26 * DAY) {
            val stepMonths = maxOf(1, ((ideal.toDouble() / (30.44 * DAY)) + 0.5).toInt())
            cal.timeInMillis = startMs
            cal.set(Calendar.MILLISECOND, 0); cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MINUTE, 0); cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.DAY_OF_MONTH, 1)
            while (cal.timeInMillis < startMs) cal.add(Calendar.MONTH, 1)
            while (cal.timeInMillis <= endMs && ticks.size < MAX_TICKS) {
                ticks.add(cal.timeInMillis)
                cal.add(Calendar.MONTH, stepMonths)
            }
            return ticks
        }

        val step = niceStep(ideal)
        // Anchor to local midnight so ticks land on round times in the user's own
        // zone. Re-anchoring each frame also stops a DST change drifting the whole
        // axis — any error stays inside the visible window.
        cal.timeInMillis = startMs
        cal.set(Calendar.MILLISECOND, 0); cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MINUTE, 0); cal.set(Calendar.HOUR_OF_DAY, 0)
        var t = cal.timeInMillis
        if (t < startMs) t += ((startMs - t) / step) * step
        while (t < startMs) t += step
        while (t <= endMs && ticks.size < MAX_TICKS) { ticks.add(t); t += step }
        return ticks
    }

    private fun niceStep(ideal: Long): Long {
        for (s in TICK_LADDER) if (s >= ideal) return s
        return TICK_LADDER.last()
    }

    private companion object {
        const val MAX_TICKS = 12
        val TICK_LADDER = longArrayOf(
            5 * MINUTE, 10 * MINUTE, 15 * MINUTE, 30 * MINUTE,
            HOUR, 2 * HOUR, 3 * HOUR, 6 * HOUR, 12 * HOUR,
            DAY, 2 * DAY, 7 * DAY, 14 * DAY
        )
    }
}

@Composable
internal fun rememberTimeLabels(): TimeLabels {
    val locale = Locale.getDefault()
    return remember(locale) { TimeLabels(locale) }
}
