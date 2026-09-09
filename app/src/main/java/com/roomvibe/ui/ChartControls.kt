package com.roomvibe.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AreaChart
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.StackedLineChart
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.roomvibe.ui.chart.RangeStyle

/** How each style reads in a menu or a description, least to most detail. */
val RangeStyle.label: String
    get() = when (this) {
        RangeStyle.MAX_ONLY -> "Max only"
        RangeStyle.MIN_MAX -> "Min & max"
        RangeStyle.MIDPOINT_AREA -> "Midpoint + range"
    }

/**
 * One glyph per style, each drawing the shape the chart itself will draw: a single
 * trend line, a pair of lines, and a line over a filled band.
 */
val RangeStyle.icon: ImageVector
    get() = when (this) {
        RangeStyle.MAX_ONLY -> Icons.AutoMirrored.Filled.ShowChart
        RangeStyle.MIN_MAX -> Icons.Default.StackedLineChart
        RangeStyle.MIDPOINT_AREA -> Icons.Default.AreaChart
    }

/**
 * The next style a tap should bring, wrapping at the end.
 *
 * Three states on one button, so the order is the declaration order — least to
 * most detail — and cycling it far enough always comes back to where it started.
 */
fun RangeStyle.next(): RangeStyle =
    RangeStyle.values()[(ordinal + 1) % RangeStyle.values().size]

/**
 * Cycles what a zoomed-out bucket draws, alongside the compare screen's colouring
 * toggle and built the same way: the icon shows the style *in force*, so a glance
 * answers "why is there a band behind this line" rather than advertising a state
 * the chart isn't in.
 *
 * Unlike a two-state toggle the icon alone can't imply what a tap does, so the
 * description carries both — what is drawn now, and what the tap will draw.
 */
@Composable
fun RangeStyleToggle(style: RangeStyle, onCycle: () -> Unit, tint: Color) {
    IconButton(onClick = onCycle) {
        Icon(
            style.icon,
            "Zoomed out, showing ${style.label.lowercase()}. Tap for ${style.next().label.lowercase()}.",
            tint = tint
        )
    }
}
