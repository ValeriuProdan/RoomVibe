package com.roomvibe.ui

import com.roomvibe.ui.chart.RangeStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cycle behind the toggle button. One button stands in for a three-item menu,
 * so the only way back to a style you overshot is to keep tapping — which has to
 * actually arrive.
 */
class RangeStyleToggleTest {

    @Test fun cyclesLeastToMostDetail() {
        assertEquals(RangeStyle.MIN_MAX, RangeStyle.MAX_ONLY.next())
        assertEquals(RangeStyle.MIDPOINT_AREA, RangeStyle.MIN_MAX.next())
    }

    @Test fun wrapsRoundToTheStart() {
        assertEquals(RangeStyle.MAX_ONLY, RangeStyle.MIDPOINT_AREA.next())
    }

    @Test fun everyStyleIsReachableAndReturnsHome() {
        RangeStyle.values().forEach { start ->
            var style = start
            val seen = mutableSetOf(style)
            repeat(RangeStyle.values().size - 1) {
                style = style.next()
                seen += style
            }
            assertEquals("cycling from $start reaches every style", RangeStyle.values().toSet(), seen)
            assertEquals("one more tap comes home", start, style.next())
        }
    }

    @Test fun everyStyleHasItsOwnLabel() {
        val labels = RangeStyle.values().map { it.label }
        assertEquals("labels are distinct", labels.size, labels.toSet().size)
        assertTrue("no style is left unlabelled", labels.none { it.isBlank() })
    }
}
