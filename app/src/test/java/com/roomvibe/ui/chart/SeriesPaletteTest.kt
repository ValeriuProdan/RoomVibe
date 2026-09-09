package com.roomvibe.ui.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two identity schemes. Under BY_VALUE the line's colour is the reading, so
 * the stroke is all that says which device it is; under BY_DEVICE the hue says it
 * and the stroke only has to separate hues once they repeat.
 */
class SeriesPaletteTest {

    // ── Colour by value: the stroke carries identity ──────────────────────────

    @Test fun byValueLeavesTheColourToTheReading() {
        (0 until SERIES_STYLE_COUNT).forEach {
            assertNull("slot $it", seriesStyle(it, SeriesColoring.BY_VALUE).color)
        }
    }

    @Test fun theFirstDevicesGetDistinctDashPatternsAtOneWeight() {
        val styles = (0 until SERIES_PATTERN_COUNT).map { seriesStyle(it, SeriesColoring.BY_VALUE) }
        assertEquals(SERIES_PATTERN_COUNT, styles.map { it.dash }.toSet().size)
        assertEquals("the first patterns share one weight", 1, styles.map { it.width }.toSet().size)
    }

    /** Past the last pattern the patterns repeat, so the weight has to differ. */
    @Test fun repeatedPatternsAreSeparatedByTheStrokeWeight() {
        val first = seriesStyle(0, SeriesColoring.BY_VALUE)
        val wrapped = seriesStyle(SERIES_PATTERN_COUNT, SeriesColoring.BY_VALUE)
        assertEquals(first.dash, wrapped.dash)
        assertTrue("weights must differ", first.width != wrapped.width)
    }

    @Test fun everyByValueSlotUpToTheStyleCountIsUnique() {
        val styles = (0 until SERIES_STYLE_COUNT).map { seriesStyle(it, SeriesColoring.BY_VALUE) }
        assertEquals(SERIES_STYLE_COUNT, styles.toSet().size)
    }

    // ── Colour by device: the hue carries identity ────────────────────────────

    @Test fun theFirstEightDevicesGetDistinctSolidColours() {
        val styles = (0 until SERIES_HUE_COUNT).map { seriesStyle(it, SeriesColoring.BY_DEVICE) }
        assertEquals(SERIES_HUE_COUNT, styles.mapNotNull { it.color }.toSet().size)
        styles.forEach { assertNull("the first $SERIES_HUE_COUNT lines are solid", it.dash) }
    }

    /** Past eight the hue repeats, so the stroke pattern has to carry the difference. */
    @Test fun repeatedHuesAreSeparatedByTheStrokePattern() {
        val first = seriesStyle(0, SeriesColoring.BY_DEVICE)
        val ninth = seriesStyle(SERIES_HUE_COUNT, SeriesColoring.BY_DEVICE)
        assertNotNull(first.color)
        assertEquals(first.color, ninth.color)
        assertTrue("patterns must differ", first.dash != ninth.dash)
    }

    @Test fun everyByDeviceSlotUpToTheStyleCountIsUnique() {
        val styles = (0 until DEVICE_STYLE_COUNT).map { seriesStyle(it, SeriesColoring.BY_DEVICE) }
        assertEquals(DEVICE_STYLE_COUNT, styles.toSet().size)
    }

    // ── Both schemes ──────────────────────────────────────────────────────────

    @Test fun everyStrokeIsThickEnoughToSee() {
        for (coloring in SeriesColoring.values()) {
            (0 until SERIES_STYLE_COUNT).forEach {
                assertTrue("$coloring slot $it", seriesStyle(it, coloring).width >= 2f)
            }
        }
    }

    @Test fun slotsBeyondTheStyleCountStayValid() {
        assertEquals(
            seriesStyle(3, SeriesColoring.BY_VALUE),
            seriesStyle(SERIES_STYLE_COUNT + 3, SeriesColoring.BY_VALUE)
        )
        assertEquals(
            seriesStyle(3, SeriesColoring.BY_DEVICE),
            seriesStyle(DEVICE_STYLE_COUNT + 3, SeriesColoring.BY_DEVICE)
        )
    }

    @Test fun negativeSlotFallsBackToTheFirstStyle() {
        for (coloring in SeriesColoring.values()) {
            assertEquals("$coloring", seriesStyle(0, coloring), seriesStyle(-1, coloring))
        }
    }

    /** A device keeps its slot across a switch, so switching back restores its look. */
    @Test fun switchingSchemesIsReversibleForASlot() {
        for (slot in 0 until 20) {
            assertEquals(
                seriesStyle(slot, SeriesColoring.BY_VALUE),
                seriesStyle(slot, SeriesColoring.BY_VALUE)
            )
            assertTrue(
                "the schemes must actually differ at slot $slot",
                seriesStyle(slot, SeriesColoring.BY_VALUE) != seriesStyle(slot, SeriesColoring.BY_DEVICE)
            )
        }
    }
}
