package com.roomvibe.ui.chart

import com.roomvibe.data.entity.Reading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class SeriesDataTest {

    private val hour = 3_600_000L

    /** A local wall-clock instant, so day bucketing is tested in the device's zone. */
    private fun at(day: Int, hourOfDay: Int): Long {
        val c = Calendar.getInstance()
        c.set(2026, Calendar.MARCH, day, hourOfDay, 0, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun reading(ms: Long, tMin: Float, tMax: Float) = Reading(
        sensorAddress = "AA:BB:CC:DD:EE:FF",
        timestampMs = ms,
        temperatureCelsius = (tMin + tMax) / 2f,
        humidityPercent = 50,
        tempMinC = tMin,
        tempMaxC = tMax,
        humMin = 40,
        humMax = 60
    )

    // ── Day buckets ──────────────────────────────────────────────────────────

    @Test fun dailyBucketCarriesTheWholeDaysMinAndMax() {
        val readings = listOf(
            reading(at(10, 1), 5f, 8f),
            reading(at(10, 9), 2f, 6f),
            reading(at(10, 15), 9f, 18f),
            reading(at(11, 3), 11f, 12f)
        )
        val visible = SeriesData(readings, Metric.TEMP)
            .visible(Lod.DAILY, at(9, 0), at(12, 0))

        assertEquals(2, visible.size)
        assertEquals(2f, visible[0].lo, 0.001f)   // coldest minimum of day 10
        assertEquals(18f, visible[0].hi, 0.001f)  // warmest maximum of day 10
        assertEquals(10f, visible[0].mid, 0.001f)
        assertEquals(11f, visible[1].lo, 0.001f)
        assertEquals(12f, visible[1].hi, 0.001f)
    }

    /** Midnight opens a new bucket rather than joining the previous day. */
    @Test fun aReadingAtMidnightStartsTheNextDay() {
        val readings = listOf(
            reading(at(10, 23), 1f, 2f),
            reading(at(11, 0), 30f, 31f)
        )
        val visible = SeriesData(readings, Metric.TEMP)
            .visible(Lod.DAILY, at(9, 0), at(12, 0))

        assertEquals(2, visible.size)
        assertEquals(2f, visible[0].hi, 0.001f)
        assertEquals(30f, visible[1].lo, 0.001f)
    }

    @Test fun bucketsUseTheRequestedMetric() {
        val readings = listOf(reading(at(10, 1), 5f, 8f).copy(humMin = 33, humMax = 77))
        val humidity = SeriesData(readings, Metric.HUMIDITY)
            .visible(Lod.DAILY, at(9, 0), at(12, 0))

        assertEquals(33f, humidity[0].lo, 0.001f)
        assertEquals(77f, humidity[0].hi, 0.001f)
    }

    // ── Hourly detail ────────────────────────────────────────────────────────

    @Test fun hourlyKeepsEveryReadingAsItsOwnPoint() {
        val readings = (0 until 6).map { reading(at(10, it), it.toFloat(), it + 1f) }
        val visible = SeriesData(readings, Metric.TEMP)
            .visible(Lod.HOURLY, at(10, 0), at(10, 5))

        assertEquals(6, visible.size)
        assertEquals(3f, visible[3].lo, 0.001f)
        assertEquals(4f, visible[3].hi, 0.001f)
    }

    /** Lines must reach both edges, so the slice keeps one point beyond each end. */
    @Test fun visibleKeepsOnePointOfOverscanOnEachSide() {
        val readings = (0 until 6).map { reading(at(10, it), it.toFloat(), it + 1f) }
        val visible = SeriesData(readings, Metric.TEMP)
            .visible(Lod.HOURLY, at(10, 2) + hour / 2, at(10, 3) + hour / 2)

        assertEquals(listOf(at(10, 2), at(10, 3), at(10, 4)), visible.map { it.tMs })
    }

    /**
     * Panned past the end of the history there is nothing to plot, but the last
     * bucket is still handed back — that is the same overscan rule, and it is what
     * lets a line run off the edge of the frame instead of stopping short of it.
     */
    @Test fun viewportBeyondTheDataKeepsOnlyTheNeighbouringBucket() {
        val readings = (0 until 4).map { reading(at(10 + it, 1), it.toFloat(), it + 1f) }
        val data = SeriesData(readings, Metric.TEMP)

        val past = data.visible(Lod.DAILY, at(20, 0), at(25, 0))
        assertEquals(1, past.size)
        assertEquals(3f, past[0].lo, 0.001f)   // the final day

        val before = data.visible(Lod.DAILY, at(1, 0), at(5, 0))
        assertEquals(1, before.size)
        assertEquals(0f, before[0].lo, 0.001f) // the first day
    }

    @Test fun emptyReadingsYieldNoPoints() {
        val data = SeriesData(emptyList(), Metric.TEMP)
        Lod.entries.forEach { assertTrue(data.visible(it, 0, Long.MAX_VALUE).isEmpty()) }
    }

    /** Bucketing must not depend on where the viewport happens to sit. */
    @Test fun bucketValuesAreTheSameWhicheverWindowYouLookThrough() {
        val readings = (0 until 72).map { reading(at(10, 0) + it * hour, it % 24f, it % 24f + 3f) }
        val data = SeriesData(readings, Metric.TEMP)

        val wide = data.visible(Lod.DAILY, at(9, 0), at(14, 0)).associateBy { it.bucketStart }
        val narrow = data.visible(Lod.DAILY, at(11, 0), at(11, 23)).associateBy { it.bucketStart }

        assertTrue(narrow.isNotEmpty())
        narrow.forEach { (bucket, point) ->
            assertEquals(wide[bucket]?.lo, point.lo)
            assertEquals(wide[bucket]?.hi, point.hi)
        }
    }
}
