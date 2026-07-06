package com.thermolog.ui.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorRampTest {

    // ── Temperature: blue (cold) → green (comfort) → red (hot) ─────────────────

    @Test fun temp_cold_isBlueDominant() {
        val c = tempColor(2f)
        assertTrue("cold should be blue-dominant", c.blue > c.red)
    }

    @Test fun temp_comfortBand_isGreenDominant() {
        for (t in intArrayOf(20, 22, 25)) {
            val c = tempColor(t.toFloat())
            assertTrue("$t°C should be green-dominant", c.green > c.red && c.green > c.blue)
        }
    }

    @Test fun temp_hot_isRedDominant() {
        val c = tempColor(34f)
        assertTrue("hot should be red-dominant", c.red > c.green && c.red > c.blue)
    }

    @Test fun temp_clampsBelowAndAboveRange() {
        assertEquals(tempColor(0f), tempColor(-50f))
        assertEquals(tempColor(36f), tempColor(100f))
    }

    // ── Humidity: red (extremes) → green (comfortable) → red ───────────────────

    @Test fun humidity_ideal_isGreenDominant() {
        val c = humidColor(48f)
        assertTrue("~48% should be green-dominant", c.green > c.red && c.green > c.blue)
    }

    @Test fun humidity_extremes_areRedDominant() {
        val dry = humidColor(12f)
        val humid = humidColor(90f)
        assertTrue("too dry should be red-dominant", dry.red > dry.green)
        assertTrue("too humid should be red-dominant", humid.red > humid.green)
    }
}
