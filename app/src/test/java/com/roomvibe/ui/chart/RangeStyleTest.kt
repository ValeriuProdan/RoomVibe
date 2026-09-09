package com.roomvibe.ui.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Which bucket values each style draws. Markers, scrubber dots and end labels all
 * position themselves off this, so a wrong pair puts a dot where no line is.
 */
class RangeStyleTest {

    @Test fun maxOnlyDrawsOneLineAtTheMaximum() {
        val (upper, lower) = drawnParts(RangeStyle.MAX_ONLY, Lod.DAILY)
        assertEquals(Part.HI, upper)
        assertEquals("one line, so both ends are the same part", upper, lower)
    }

    @Test fun minMaxDrawsTwoLines() {
        val (upper, lower) = drawnParts(RangeStyle.MIN_MAX, Lod.DAILY)
        assertEquals(Part.HI, upper)
        assertEquals(Part.LO, lower)
        assertNotEquals(upper, lower)
    }

    @Test fun midpointAreaDrawsOneLineDownTheMiddle() {
        val (upper, lower) = drawnParts(RangeStyle.MIDPOINT_AREA, Lod.DAILY)
        assertEquals(Part.MID, upper)
        assertEquals(upper, lower)
    }

    /** An hourly point is one reading, so there is no range for a style to show. */
    @Test fun hourlyIgnoresTheStyle() {
        for (style in RangeStyle.values()) {
            assertEquals("$style", Part.MID to Part.MID, drawnParts(style, Lod.HOURLY))
        }
    }

    @Test fun everyStyleIsHandledAtEveryZoom() {
        for (style in RangeStyle.values()) {
            for (lod in Lod.values()) {
                val (upper, lower) = drawnParts(style, lod)
                // Whatever the pair, the upper line is never below the lower one.
                val ordering = listOf(Part.LO, Part.MID, Part.HI)
                assert(ordering.indexOf(upper) >= ordering.indexOf(lower)) {
                    "$style at $lod put $upper above $lower"
                }
            }
        }
    }

    @Test fun monthlyBehavesLikeDaily() {
        for (style in RangeStyle.values()) {
            assertEquals("$style", drawnParts(style, Lod.DAILY), drawnParts(style, Lod.MONTHLY))
        }
    }
}
