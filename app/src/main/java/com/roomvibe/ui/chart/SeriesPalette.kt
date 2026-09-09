package com.roomvibe.ui.chart

import androidx.compose.ui.graphics.PathEffect

/**
 * How the compare chart tells one device's line from another.
 *
 * Colour here means what it means everywhere else in the app: the *value*. Both
 * charts run their readings through the comfort ramps in [ChartCore], so 22 °C is
 * the same green whether it is drawn on a single sensor's chart or next to three
 * other rooms. A line that changed hue because of which device it belonged to
 * would make the same temperature look like two different ones.
 *
 * That leaves identity with nowhere to live on the colour channel, so it moves to
 * the stroke: each device gets its own dash pattern, and past the last pattern the
 * patterns repeat at a different stroke weight. Pattern and weight survive the
 * value colouring — they read the same whether the line is deep blue or red — and
 * they carry into the legend swatch, the chips and the marker readout, so matching
 * a line to a device never depends on colour at all.
 *
 * Patterns are ordered most- to least-distinct, so the first few devices — the
 * common case — are the easiest pair to tell apart.
 */
private val SERIES_DASHES: List<List<Float>?> = listOf(
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
 * Stroke weights the patterns repeat at. Deliberately far apart — a 1 px
 * difference reads as an artefact, 1.5 px reads as a different line.
 */
private val SERIES_WIDTHS = listOf(SERIES_BASE_WIDTH, 5.6f, 2.2f)

/** How a single device's line is drawn: its dash pattern and its stroke weight. */
data class SeriesStyle(val dash: List<Float>?, val width: Float = SERIES_BASE_WIDTH) {
    val pathEffect: PathEffect?
        get() = dash?.let { PathEffect.dashPathEffect(it.toFloatArray()) }
}

/** Number of distinct dash patterns before they start repeating at another weight. */
const val SERIES_PATTERN_COUNT = 6

/** Total number of devices this palette keeps visually distinct. */
const val SERIES_STYLE_COUNT = SERIES_PATTERN_COUNT * 3

/**
 * The style for a device's slot. Slots are allocated per device and never
 * reshuffled, so a device keeps its stroke when others are added, renamed, or
 * hidden — the pattern follows the device, never its position in a list.
 */
fun seriesStyle(slot: Int): SeriesStyle {
    val s = if (slot < 0) 0 else slot
    val dash = SERIES_DASHES[s % SERIES_PATTERN_COUNT]
    val width = SERIES_WIDTHS[(s / SERIES_PATTERN_COUNT) % SERIES_WIDTHS.size]
    return SeriesStyle(dash, width)
}
