package com.roomvibe.ui.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale

class TimeLabelsTest {

    private val labels = TimeLabels(Locale.UK)
    private val hour = 3_600_000L
    private val day = 24 * hour

    private fun at(year: Int, month: Int, dayOfMonth: Int, hourOfDay: Int = 0): Long {
        val c = Calendar.getInstance()
        c.set(year, month, dayOfMonth, hourOfDay, 0, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun assertWellFormed(ticks: List<Long>, startMs: Long, endMs: Long) {
        assertTrue("must not run away: ${ticks.size}", ticks.size <= 12)
        ticks.forEach {
            assertTrue("tick $it before start", it >= startMs)
            assertTrue("tick $it after end", it <= endMs)
        }
        ticks.zipWithNext { a, b -> assertTrue("ticks must ascend", b > a) }
    }

    @Test fun hourlySpanSnapsToRoundClockTimes() {
        val start = at(2026, Calendar.MARCH, 10, 1) + 17 * 60_000L   // 01:17, deliberately ragged
        val end = start + 12 * hour
        val ticks = labels.axisTicks(start, end, 6).toList()

        assertWellFormed(ticks, start, end)
        assertTrue(ticks.isNotEmpty())
        // Every tick lands on a whole number of minutes past local midnight
        val midnight = at(2026, Calendar.MARCH, 10)
        ticks.forEach { assertEquals(0L, (it - midnight) % (15 * 60_000L)) }
    }

    @Test fun theTightestZoomStillProducesTicks() {
        val start = at(2026, Calendar.MARCH, 10, 9)
        val ticks = labels.axisTicks(start, start + 3 * hour, 6).toList()
        assertWellFormed(ticks, start, start + 3 * hour)
        assertTrue("a 3h window should be labelled", ticks.size >= 2)
    }

    @Test fun multiDaySpanUsesDayScaleSteps() {
        val start = at(2026, Calendar.MARCH, 1)
        val end = at(2026, Calendar.MARCH, 31)
        val ticks = labels.axisTicks(start, end, 6).toList()
        assertWellFormed(ticks, start, end)
        assertTrue(ticks.size >= 3)
    }

    /** Fully zoomed out over years, the month branch takes over. */
    @Test fun multiYearSpanFallsBackToWholeMonths() {
        val start = at(2023, Calendar.JANUARY, 1)
        val end = at(2026, Calendar.JUNE, 1)
        val ticks = labels.axisTicks(start, end, 6).toList()

        assertWellFormed(ticks, start, end)
        assertTrue(ticks.isNotEmpty())
        val cal = Calendar.getInstance()
        ticks.forEach {
            cal.timeInMillis = it
            assertEquals("month ticks start on the 1st", 1, cal.get(Calendar.DAY_OF_MONTH))
            assertEquals("at midnight", 0, cal.get(Calendar.HOUR_OF_DAY))
        }
    }

    @Test fun anExtremeSpanStillTerminates() {
        val start = at(1990, Calendar.JANUARY, 1)
        val end = at(2090, Calendar.JANUARY, 1)
        assertWellFormed(labels.axisTicks(start, end, 6).toList(), start, end)
    }

    @Test fun degenerateRangesYieldNoTicks() {
        val t = at(2026, Calendar.MARCH, 10)
        assertTrue(labels.axisTicks(t, t, 6).isEmpty())
        assertTrue(labels.axisTicks(t, t - day, 6).isEmpty())
        assertTrue(labels.axisTicks(t, t + day, 0).isEmpty())
    }

    /** The tick list is reused between calls, so callers must not hold onto it. */
    @Test fun successiveCallsReplacePreviousTicks() {
        val start = at(2026, Calendar.MARCH, 10)
        labels.axisTicks(start, start + 30 * day, 6)
        val second = labels.axisTicks(start, start + 6 * hour, 6)
        assertWellFormed(second.toList(), start, start + 6 * hour)
    }

    @Test fun axisAndTooltipTextRenderForEveryZoomLevel() {
        val t = at(2026, Calendar.MARCH, 10, 14)
        Lod.entries.forEach {
            assertTrue(labels.axis(it, t).isNotBlank())
            assertTrue(labels.tooltip(it, t).isNotBlank())
        }
    }
}
