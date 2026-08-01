package com.roomvibe.ui.chart

import com.roomvibe.data.entity.Reading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Guards against reporting values a sensor never recorded.
 *
 * The slice deliberately keeps one point beyond each edge of the viewport so lines
 * can run off the frame — so for a device whose history stops short of the window,
 * the nearest point can be days away.
 */
class SeriesLookupTest {

    private val hour = 3_600_000L
    private val day = 24 * hour

    private fun at(dayOfMonth: Int, hourOfDay: Int = 0): Long {
        val c = Calendar.getInstance()
        c.set(2026, Calendar.MARCH, dayOfMonth, hourOfDay, 0, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun reading(ms: Long, t: Float) = Reading(
        sensorAddress = "AA:BB:CC:DD:EE:FF",
        timestampMs = ms,
        temperatureCelsius = t,
        humidityPercent = 50,
        tempMinC = t, tempMaxC = t, humMin = 40, humMax = 60
    )

    /** A device recording hourly on the 20th and 21st only. */
    private val recentOnly = (0 until 48).map { reading(at(20) + it * hour, 20f + it % 5) }

    // ── The reported bug ─────────────────────────────────────────────────────

    @Test fun aDeviceWithNoHistoryThereReportsNothing() {
        val data = SeriesData(recentOnly, Metric.TEMP)
        // Scrolled back a week, well before this device has any data at all
        val points = data.visible(Lod.HOURLY, at(13), at(14))

        assertTrue("the overscan point is still handed back", points.isNotEmpty())
        assertNull("but it must not be read as a value", points.bucketAt(at(13, 12), Lod.HOURLY))
        assertFalse("and the series must not scale the axis", points.intersects(at(13), at(14)))
    }

    @Test fun theSameHoldsWhenZoomedOutToDays() {
        val data = SeriesData(recentOnly, Metric.TEMP)
        val points = data.visible(Lod.DAILY, at(1), at(8))
        assertNull(points.bucketAt(at(4), Lod.DAILY))
        assertFalse(points.intersects(at(1), at(8)))
    }

    @Test fun scrollingPastTheEndIsAlsoEmpty() {
        val data = SeriesData(recentOnly, Metric.TEMP)
        val points = data.visible(Lod.HOURLY, at(27), at(28))
        assertNull(points.bucketAt(at(27, 12), Lod.HOURLY))
        assertFalse(points.intersects(at(27), at(28)))
    }

    // ── Still works where there IS data ──────────────────────────────────────

    @Test fun withinItsOwnHistoryTheValueIsReported() {
        val data = SeriesData(recentOnly, Metric.TEMP)
        val points = data.visible(Lod.HOURLY, at(20), at(21))
        assertTrue(points.intersects(at(20), at(21)))
        val bucket = points.bucketAt(at(20, 10), Lod.HOURLY)
        assertNotNull(bucket)
        assertEquals(at(20, 10), bucket!!.tMs)
    }

    @Test fun aMarkerBetweenTwoHourlyReadingsStillResolves() {
        val data = SeriesData(recentOnly, Metric.TEMP)
        val points = data.visible(Lod.HOURLY, at(20), at(21))
        assertNotNull(points.bucketAt(at(20, 10) + hour / 2, Lod.HOURLY))
    }

    @Test fun aDayBucketAnswersForAnyTimeInsideThatDay() {
        val data = SeriesData(recentOnly, Metric.TEMP)
        val points = data.visible(Lod.DAILY, at(19), at(23))
        listOf(at(20, 0), at(20, 6), at(20, 23)).forEach {
            assertNotNull("should resolve at $it", points.bucketAt(it, Lod.DAILY))
        }
    }

    /** A gap in the middle of a device's history must read as a gap, not as its edges. */
    @Test fun aGapInsideTheHistoryReportsNothing() {
        val split = (0 until 12).map { reading(at(20) + it * hour, 21f) } +
            (0 until 12).map { reading(at(25) + it * hour, 23f) }
        val points = SeriesData(split, Metric.TEMP).visible(Lod.HOURLY, at(19), at(26))
        assertNull("mid-gap must be blank", points.bucketAt(at(22, 12), Lod.HOURLY))
        assertNotNull(points.bucketAt(at(20, 5), Lod.HOURLY))
        assertNotNull(points.bucketAt(at(25, 5), Lod.HOURLY))
    }

    // ── intersects ───────────────────────────────────────────────────────────

    @Test fun aLineCrossingTheWholeWindowCounts() {
        // Two points, one either side: a line entering one edge and leaving the other
        val sparse = listOf(reading(at(10), 20f), reading(at(30), 25f))
        val points = SeriesData(sparse, Metric.TEMP).visible(Lod.HOURLY, at(19), at(21))
        assertEquals(2, points.size)
        assertTrue("a crossing line is visible even with no point inside",
            points.intersects(at(19), at(21)))
    }

    @Test fun anEmptySliceIntersectsNothing() {
        assertFalse(emptyList<SeriesPoint>().intersects(at(1), at(2)))
        assertNull(emptyList<SeriesPoint>().bucketAt(at(1), Lod.HOURLY))
    }
}
