package com.roomvibe.ui.chart

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect

/**
 * Colours for the compare chart, where a line's colour means "which device" —
 * identity, not value. (The single-sensor charts do the opposite: they colour by
 * value, using the comfort ramps in [ChartCore].)
 *
 * Eight fixed hues in a fixed order, stepped for this app's dark chart surface
 * (#1B1D21). The order is the colour-blindness safety mechanism, not decoration:
 * on this surface every neighbouring pair clears ΔE 8.4 under protanopia and
 * deuteranopia and ΔE 19.3 under normal vision (OKLab ×100), every hue sits in the
 * dark lightness band (OKLCH L 0.48–0.67) above the chroma floor, and all eight
 * clear 3:1 contrast against the surface. Don't reorder or hand-tweak these
 * without re-validating the whole set.
 *
 * Past eight devices the hues repeat, but the stroke pattern advances — so slot 8
 * is "blue, long-dashed", not a second solid blue. Hue × pattern keeps 24 devices
 * apart, and the pattern shows in the legend swatch as well as on the line.
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

/** Solid → long-dash → dotted. Applied in that order once the hues wrap around. */
private val SERIES_DASHES: List<List<Float>?> = listOf(
    null,
    listOf(16f, 8f),
    listOf(3f, 6f)
)

/** How a single device's line is drawn: its hue plus, past slot 8, a stroke pattern. */
data class SeriesStyle(val color: Color, val dash: List<Float>?) {
    val pathEffect: PathEffect?
        get() = dash?.let { PathEffect.dashPathEffect(it.toFloatArray()) }
}

/** Number of distinct hues before the palette starts pairing them with a pattern. */
const val SERIES_HUE_COUNT = 8

/** Total number of devices this palette keeps visually distinct. */
const val SERIES_STYLE_COUNT = SERIES_HUE_COUNT * 3

/**
 * The style for a device's colour slot. Slots are allocated per device and never
 * reshuffled, so a device keeps its colour when others are added, renamed, or
 * hidden — colour follows the device, never its position in a list.
 */
fun seriesStyle(slot: Int): SeriesStyle {
    val s = if (slot < 0) 0 else slot
    val hue = SERIES_HUES[s % SERIES_HUE_COUNT]
    val dash = SERIES_DASHES[(s / SERIES_HUE_COUNT) % SERIES_DASHES.size]
    return SeriesStyle(hue, dash)
}
