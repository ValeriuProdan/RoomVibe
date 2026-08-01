package com.roomvibe.ui.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesPaletteTest {

    @Test fun theFirstEightDevicesGetDistinctSolidColours() {
        val styles = (0 until SERIES_HUE_COUNT).map { seriesStyle(it) }
        assertEquals(SERIES_HUE_COUNT, styles.map { it.color }.toSet().size)
        styles.forEach { assertNull("first ${SERIES_HUE_COUNT} lines are solid", it.dash) }
    }

    /** Past eight the hue repeats, so the stroke pattern has to carry the difference. */
    @Test fun repeatedHuesAreSeparatedByTheStrokePattern() {
        val first = seriesStyle(0)
        val ninth = seriesStyle(SERIES_HUE_COUNT)
        assertEquals(first.color, ninth.color)
        assertNotEquals(first.dash, ninth.dash)
    }

    @Test fun everySlotUpToTheStyleCountIsUnique() {
        val styles = (0 until SERIES_STYLE_COUNT).map { seriesStyle(it) }
        assertEquals(SERIES_STYLE_COUNT, styles.toSet().size)
    }

    @Test fun slotsBeyondTheStyleCountStayValid() {
        val far = seriesStyle(SERIES_STYLE_COUNT + 3)
        assertTrue(far.color.alpha > 0f)
        assertEquals(seriesStyle(3), far)
    }

    @Test fun negativeSlotFallsBackToTheFirstStyle() {
        assertEquals(seriesStyle(0), seriesStyle(-1))
    }
}
