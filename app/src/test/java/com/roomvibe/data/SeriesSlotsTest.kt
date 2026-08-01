package com.roomvibe.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesSlotsTest {

    private val a = "AA:AA:AA:AA:AA:AA"
    private val b = "BB:BB:BB:BB:BB:BB"
    private val c = "CC:CC:CC:CC:CC:CC"

    @Test fun assignsLowestFreeSlotToEachNewDevice() {
        val slots = SeriesSlots.assign(emptyMap(), listOf(a, b, c))
        assertEquals(setOf(0, 1, 2), slots.values.toSet())
        assertEquals(3, slots.size)
    }

    @Test fun keepsExistingAssignmentsWhenADeviceIsAdded() {
        val first = SeriesSlots.assign(emptyMap(), listOf(a, b))
        val second = SeriesSlots.assign(first, listOf(a, b, c))
        assertEquals(first[a], second[a])
        assertEquals(first[b], second[b])
        assertEquals(2, second[c])
    }

    /** Renaming reorders the sensor list; colours must not follow. */
    @Test fun isIndependentOfTheOrderAddressesArePassedIn() {
        val forward = SeriesSlots.assign(emptyMap(), listOf(a, b, c))
        val reversed = SeriesSlots.assign(emptyMap(), listOf(c, b, a))
        assertEquals(forward, reversed)
    }

    @Test fun releasesTheSlotOfADeletedDevice() {
        val before = SeriesSlots.assign(emptyMap(), listOf(a, b, c))
        val afterDelete = SeriesSlots.assign(before, listOf(a, c))
        assertEquals(before[a], afterDelete[a])
        assertEquals(before[c], afterDelete[c])

        // b's slot is free again, so the next device takes it rather than slot 3.
        val d = "DD:DD:DD:DD:DD:DD"
        val afterAdd = SeriesSlots.assign(afterDelete, listOf(a, c, d))
        assertEquals(before[b], afterAdd[d])
    }

    @Test fun assignsDistinctSlotsWellPastTheEightHues() {
        val addresses = (0 until 30).map { "AA:BB:CC:DD:EE:%02X".format(it) }
        val slots = SeriesSlots.assign(emptyMap(), addresses)
        assertEquals(30, slots.size)
        assertEquals(30, slots.values.toSet().size)
    }

    @Test fun encodeDecodeRoundTrips() {
        val slots = SeriesSlots.assign(emptyMap(), listOf(a, b, c))
        assertEquals(slots, SeriesSlots.decode(SeriesSlots.encode(slots)))
    }

    @Test fun decodeToleratesEmptyAndMalformedInput() {
        assertTrue(SeriesSlots.decode(null).isEmpty())
        assertTrue(SeriesSlots.decode("").isEmpty())
        assertTrue(SeriesSlots.decode("garbage|=3|x=").isEmpty())
        assertEquals(mapOf(a to 7), SeriesSlots.decode("junk|$a=7"))
    }
}
