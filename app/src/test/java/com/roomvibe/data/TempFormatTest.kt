package com.roomvibe.data

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

class TempFormatTest {

    @Before fun fixLocale() {
        // formatTemp uses the default locale's decimal separator; pin it for tests.
        Locale.setDefault(Locale.US)
    }

    @Test fun celsiusToF_knownPoints() {
        assertEquals(32f, celsiusToF(0f), 0.001f)
        assertEquals(212f, celsiusToF(100f), 0.001f)
        assertEquals(98.6f, celsiusToF(37f), 0.001f)
        assertEquals(-40f, celsiusToF(-40f), 0.001f) // the crossover point
    }

    @Test fun formatTemp_celsius() {
        assertEquals("20.0°C", formatTemp(20f, fahrenheit = false))
        assertEquals("-40.0°C", formatTemp(-40f, fahrenheit = false))
    }

    @Test fun formatTemp_fahrenheit_convertsAndSuffixes() {
        assertEquals("68.0°F", formatTemp(20f, fahrenheit = true))
        assertEquals("32.0°F", formatTemp(0f, fahrenheit = true))
    }

    @Test fun formatTemp_respectsDecimals() {
        assertEquals("24°C", formatTemp(23.6f, fahrenheit = false, decimals = 0))
        assertEquals("23.60°C", formatTemp(23.6f, fahrenheit = false, decimals = 2))
    }
}
