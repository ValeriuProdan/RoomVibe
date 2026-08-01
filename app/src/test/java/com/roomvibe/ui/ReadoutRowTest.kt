package com.roomvibe.ui

import androidx.compose.ui.graphics.Color
import com.roomvibe.ui.chart.SeriesPoint
import com.roomvibe.ui.chart.SeriesStyle
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The marker readout's ordering. The point of keeping no-data devices in the list
 * is that rows don't jump as the marker crosses the start of a device's history,
 * so the order has to be predictable.
 */
class ReadoutRowTest {

    private val style = SeriesStyle(Color(0xFF3987E5), null)

    private fun row(label: String, value: Float?) = ReadoutRow(
        label = label,
        style = style,
        bucket = value?.let { SeriesPoint(tMs = 0L, bucketStart = 0L, lo = it, hi = it) }
    )

    private fun labels(vararg rows: ReadoutRow) = rows.toList().rankedForReadout().map { it.label }

    @Test fun measuredDevicesRankWarmestFirst() {
        assertEquals(
            listOf("hot", "mild", "cold"),
            labels(row("mild", 21f), row("cold", 12f), row("hot", 30f))
        )
    }

    @Test fun devicesWithoutDataSinkToTheBottom() {
        assertEquals(
            listOf("warm", "cool", "absent"),
            labels(row("absent", null), row("cool", 18f), row("warm", 25f))
        )
    }

    /** Below the measured rows, the input order (palette order) is preserved. */
    @Test fun theOrderOfNoDataDevicesIsStable() {
        assertEquals(
            listOf("measured", "first", "second", "third"),
            labels(row("measured", 20f), row("first", null), row("second", null), row("third", null))
        )
    }

    /** The whole reason for the placeholder: one device losing data must not
     *  reorder the devices that still have it. */
    @Test fun losingOneDevicesDataLeavesTheOthersInPlace() {
        val withData = labels(row("a", 30f), row("b", 20f), row("c", 10f))
        val bWentQuiet = rowsAfterBStopsRecording()
        assertEquals(listOf("a", "b", "c"), withData)
        assertEquals("a and c must not swap", listOf("a", "c", "b"), bWentQuiet)
        // a stays first and c keeps its position relative to a
        assertEquals("a", bWentQuiet.first())
    }

    private fun rowsAfterBStopsRecording() =
        labels(row("a", 30f), row("b", null), row("c", 10f))

    @Test fun allDevicesWithoutDataKeepTheirIncomingOrder() {
        assertEquals(
            listOf("x", "y", "z"),
            labels(row("x", null), row("y", null), row("z", null))
        )
    }

    @Test fun negativeTemperaturesStillRankCorrectly() {
        assertEquals(
            listOf("inside", "freezing", "outside"),
            labels(row("outside", -12f), row("inside", 21f), row("freezing", -0.5f))
        )
    }

    @Test fun anEmptyReadoutStaysEmpty() {
        assertEquals(emptyList<ReadoutRow>(), emptyList<ReadoutRow>().rankedForReadout())
    }
}
