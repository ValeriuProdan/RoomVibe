package com.roomvibe.ui.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lines are coloured by their reading, so the stroke is the only thing that says
 * which device a line belongs to. These tests pin that down.
 */
class SeriesPaletteTest {

    @Test fun theFirstDevicesGetDistinctDashPatternsAtOneWeight() {
        val styles = (0 until SERIES_PATTERN_COUNT).map { seriesStyle(it) }
        assertEquals(SERIES_PATTERN_COUNT, styles.map { it.dash }.toSet().size)
        assertEquals("the first patterns share one weight", 1, styles.map { it.width }.toSet().size)
    }

    @Test fun theFirstDeviceIsASolidLine() {
        assertNull(seriesStyle(0).dash)
    }

    /** Past the last pattern the patterns repeat, so the weight has to differ. */
    @Test fun repeatedPatternsAreSeparatedByTheStrokeWeight() {
        val first = seriesStyle(0)
        val wrapped = seriesStyle(SERIES_PATTERN_COUNT)
        assertEquals(first.dash, wrapped.dash)
        assertTrue("weights must differ", first.width != wrapped.width)
    }

    @Test fun everySlotUpToTheStyleCountIsUnique() {
        val styles = (0 until SERIES_STYLE_COUNT).map { seriesStyle(it) }
        assertEquals(SERIES_STYLE_COUNT, styles.toSet().size)
    }

    @Test fun everyStrokeIsThickEnoughToSee() {
        (0 until SERIES_STYLE_COUNT).forEach { assertTrue(seriesStyle(it).width >= 2f) }
    }

    @Test fun slotsBeyondTheStyleCountStayValid() {
        assertEquals(seriesStyle(3), seriesStyle(SERIES_STYLE_COUNT + 3))
    }

    @Test fun negativeSlotFallsBackToTheFirstStyle() {
        assertEquals(seriesStyle(0), seriesStyle(-1))
    }
}
