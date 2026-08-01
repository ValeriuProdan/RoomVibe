package com.roomvibe.ui.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pinch-to-zoom limits. Zooming all the way out is the easiest way to feed the
 * chart a degenerate viewport, so the transform has to stay inside its bounds no
 * matter what the gesture throws at it.
 */
class ViewportZoomTest {

    private val hour = 3_600_000L
    private val day = 24 * hour

    private val dataMin = 1_700_000_000_000L
    private val dataMax = dataMin + 30 * day
    private val minSpan = 3 * hour
    private val maxSpan = ((dataMax - dataMin) * 1.1).toLong()
    private val width = 1080

    private fun transform(vp: Viewport, zoom: Float, panX: Float = 0f): Viewport {
        var out = vp
        applyTransform(
            centroidX = width / 2f, panX = panX, zoom = zoom, widthPx = width,
            vp = vp, minSpan = minSpan, maxSpan = maxSpan,
            dataMin = dataMin, dataMax = dataMax
        ) { out = it }
        return out
    }

    private fun assertSane(vp: Viewport) {
        assertTrue("start must precede end", vp.endMs > vp.startMs)
        assertTrue("span under floor: ${vp.span}", vp.span >= minSpan)
        assertTrue("span over ceiling: ${vp.span}", vp.span <= maxSpan)
    }

    @Test fun zoomingOutRepeatedlySettlesAtTheCeiling() {
        var vp = Viewport(dataMax - 3 * day, dataMax)
        repeat(50) { vp = transform(vp, zoom = 0.5f); assertSane(vp) }
        assertEquals("should rest exactly at the widest span", maxSpan, vp.span)
    }

    @Test fun zoomingInRepeatedlySettlesAtTheFloor() {
        var vp = Viewport(dataMin, dataMax)
        repeat(50) { vp = transform(vp, zoom = 2f); assertSane(vp) }
        assertEquals(minSpan, vp.span)
    }

    /** A pinch that collapses to a point divides by zero — it must not escape. */
    @Test fun degenerateZoomFactorsStayInBounds() {
        val vp = Viewport(dataMax - 3 * day, dataMax)
        listOf(0f, Float.MIN_VALUE, 1e-30f, 1e30f, Float.MAX_VALUE).forEach { z ->
            assertSane(transform(vp, zoom = z))
        }
    }

    @Test fun fullyZoomedOutViewportStillCoversTheData() {
        var vp = Viewport(dataMax - 3 * day, dataMax)
        repeat(50) { vp = transform(vp, zoom = 0.5f) }
        assertTrue("data must remain visible", vp.startMs <= dataMax && vp.endMs >= dataMin)
    }

    /** Panning hard at full zoom-out must not walk the window off the data. */
    @Test fun panningAtFullZoomOutKeepsTheDataInFrame() {
        var vp = Viewport(dataMax - 3 * day, dataMax)
        repeat(50) { vp = transform(vp, zoom = 0.5f) }
        repeat(30) {
            vp = transform(vp, zoom = 1f, panX = 4000f)
            assertSane(vp)
            assertTrue(vp.startMs <= dataMax && vp.endMs >= dataMin)
        }
    }

    @Test fun aSingleFlatDayOfDataStillHasAValidRange() {
        val flatMax = dataMin + hour
        val ceiling = ((flatMax - dataMin).coerceAtLeast(day) * 1.1).toLong()
        var out = Viewport(dataMin, flatMax)
        applyTransform(540f, 0f, 0.01f, width, out, minSpan, ceiling, dataMin, flatMax) { out = it }
        assertTrue(out.endMs > out.startMs)
        assertTrue(out.span <= ceiling)
    }
}
