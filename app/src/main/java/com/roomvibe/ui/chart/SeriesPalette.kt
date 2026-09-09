package com.roomvibe.ui.chart

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect

/**
 * What a compare line's colour means — the two answers are genuinely different
 * tools, so the screen offers both rather than picking one.
 *
 * [BY_VALUE] colours every line with the comfort ramps in [ChartCore], the same
 * mapping the single-sensor charts use, so 22 °C is the same green wherever it is
 * drawn and "which of these rooms is cold" is answerable at a glance. That leaves
 * identity nowhere to live on the colour channel, so it moves to the stroke.
 *
 * [BY_DEVICE] gives each device one fixed hue, which is what you want when the
 * question is "which line is the balcony" rather than "how warm was it".
 */
enum class SeriesColoring { BY_VALUE, BY_DEVICE }

/**
 * Eight fixed hues in a fixed order, stepped for this app's dark chart surface
 * (#1B1D21). The order is the colour-blindness safety mechanism, not decoration:
 * on this surface every neighbouring pair clears ΔE 8.4 under protanopia and
 * deuteranopia and ΔE 19.3 under normal vision (OKLab ×100), every hue sits in the
 * dark lightness band (OKLCH L 0.48–0.67) above the chroma floor, and all eight
 * clear 3:1 contrast against the surface. Don't reorder or hand-tweak these
 * without re-validating the whole set.
 */
private val SERIES_HUES = listOf(
    Color(0xFF3987E5),  // 1 blue
    Color(0xFFD95926),  // 2 orange
    Color(0xFF199E70),  // 3 aqua
    Color(0xFFC98500),  // 4 yellow
    Color(0xFFD55181),  // 5 magenta
    Color(0xFF008300),  // 6 green
    Color(0xFF9085E9),  // 7 violet
    Color(0xFFE66767)   // 8 red
)

/**
 * Stroke patterns for [SeriesColoring.BY_DEVICE], where the hue is already doing
 * the work. Solid until the hues run out, then the pattern separates the repeats —
 * so slot 8 is "blue, long-dashed", not a second solid blue.
 */
private val DEVICE_DASHES: List<List<Float>?> = listOf(
    null,
    listOf(16f, 8f),
    listOf(3f, 6f)
)

/**
 * Stroke patterns for [SeriesColoring.BY_VALUE], where the pattern is the only
 * thing saying which device a line belongs to, so every device needs its own from
 * the first. Ordered most- to least-distinct, so the first few devices — the
 * common case — are the easiest pair to tell apart.
 */
private val VALUE_DASHES: List<List<Float>?> = listOf(
    null,                                 // 1 solid
    listOf(20f, 10f),                     // 2 long dash
    listOf(2.5f, 8f),                     // 3 dotted
    listOf(15f, 8f, 2.5f, 8f),            // 4 dash-dot
    listOf(7f, 7f),                       // 5 short dash
    listOf(26f, 8f, 2.5f, 8f, 2.5f, 8f)   // 6 long dash-dot-dot
)

/** The weight most devices' lines are drawn at. */
const val SERIES_BASE_WIDTH = 3.6f

/**
 * Stroke weights the [SeriesColoring.BY_VALUE] patterns repeat at. Deliberately
 * far apart — a 1 px difference reads as an artefact, 1.5 px as a different line.
 */
private val SERIES_WIDTHS = listOf(SERIES_BASE_WIDTH, 5.6f, 2.2f)

/**
 * How a single device's line is drawn.
 *
 * A null [color] means the line takes its colour from the reading, so callers
 * must fall back to the metric's ramp; a non-null one is the device's own hue and
 * is used as-is. Everything that has to match a line — the legend swatch, the
 * chips, the marker readout, the chart's end labels — branches on exactly that.
 */
data class SeriesStyle(
    val color: Color?,
    val dash: List<Float>?,
    val width: Float = SERIES_BASE_WIDTH
) {
    val pathEffect: PathEffect?
        get() = dash?.let { PathEffect.dashPathEffect(it.toFloatArray()) }
}

/** Distinct hues before [SeriesColoring.BY_DEVICE] starts pairing them with a pattern. */
const val SERIES_HUE_COUNT = 8

/** Distinct dash patterns before [SeriesColoring.BY_VALUE] repeats them at another weight. */
const val SERIES_PATTERN_COUNT = 6

/** How many devices each scheme keeps visually distinct. */
const val SERIES_STYLE_COUNT = SERIES_PATTERN_COUNT * 3
const val DEVICE_STYLE_COUNT = SERIES_HUE_COUNT * 3

/**
 * The style for a device's slot under [coloring].
 *
 * Slots are allocated per device and never reshuffled, so a device keeps its look
 * when others are added, renamed, or hidden — and keeps it across a switch of
 * scheme, since both schemes read the same slot. What that slot buys differs:
 * under [SeriesColoring.BY_DEVICE] it picks a hue (and, past the eighth device, a
 * pattern to separate the repeats); under [SeriesColoring.BY_VALUE] the colour is
 * the reading, so the slot picks a pattern and a stroke weight instead.
 */
fun seriesStyle(slot: Int, coloring: SeriesColoring): SeriesStyle {
    val s = if (slot < 0) 0 else slot
    return when (coloring) {
        SeriesColoring.BY_DEVICE -> SeriesStyle(
            color = SERIES_HUES[s % SERIES_HUE_COUNT],
            dash = DEVICE_DASHES[(s / SERIES_HUE_COUNT) % DEVICE_DASHES.size]
        )
        SeriesColoring.BY_VALUE -> SeriesStyle(
            color = null,
            dash = VALUE_DASHES[s % SERIES_PATTERN_COUNT],
            width = SERIES_WIDTHS[(s / SERIES_PATTERN_COUNT) % SERIES_WIDTHS.size]
        )
    }
}
