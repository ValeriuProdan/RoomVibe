package com.roomvibe.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

class LywsdProtocolTest {

    // ── isKnownThermometer ────────────────────────────────────────────────────

    @Test fun knownNames_areRecognised() {
        assertTrue(LywsdProtocol.isKnownThermometer("LYWSD03MMC"))
        assertTrue(LywsdProtocol.isKnownThermometer("LYWSD02"))
        assertTrue(LywsdProtocol.isKnownThermometer("ATC_A1B2C3"))
        assertTrue(LywsdProtocol.isKnownThermometer("MJ_HT_V1"))
        assertTrue(LywsdProtocol.isKnownThermometer("MIAOMIAOCE_ABC"))
    }

    @Test fun unknownNames_areRejected() {
        assertFalse(LywsdProtocol.isKnownThermometer("Galaxy Buds"))
        assertFalse(LywsdProtocol.isKnownThermometer(""))
        assertFalse(LywsdProtocol.isKnownThermometer(null))
        // prefix must be at the start
        assertFalse(LywsdProtocol.isKnownThermometer("My LYWSD03MMC"))
    }

    // ── parseRealtimeData (temp ×100) ─────────────────────────────────────────

    @Test fun realtime_parsesTempHumidityBattery() {
        // 23.50 °C, 45 %, 3000 mV
        val r = LywsdProtocol.parseRealtimeData(bytes(0x2E, 0x09, 0x2D, 0xB8, 0x0B))!!
        assertEquals(23.50f, r.temperatureCelsius, 0.001f)
        assertEquals(45, r.humidityPercent)
        assertEquals(3000, r.batteryMillivolts)
    }

    @Test fun realtime_handlesNegativeTemperature() {
        // -1.50 °C, 40 %  (int16 -150 = 0xFF6A little-endian)
        val r = LywsdProtocol.parseRealtimeData(bytes(0x6A, 0xFF, 0x28))!!
        assertEquals(-1.50f, r.temperatureCelsius, 0.001f)
        assertEquals(40, r.humidityPercent)
        assertEquals(0, r.batteryMillivolts) // no battery bytes present
    }

    @Test fun realtime_tooShort_returnsNull() {
        assertNull(LywsdProtocol.parseRealtimeData(bytes(0x01, 0x02)))
        assertNull(LywsdProtocol.parseRealtimeData(ByteArray(0)))
    }

    // ── parseHistoryRecord (14 bytes, temp ×10) ───────────────────────────────

    @Test fun history_parsesRealCapturedRecord() {
        // Captured from a real LYWSD03MMC:
        // fc 55 00 00 | d0 35 b9 04 | 31 00 | 4b | 29 00 | 47
        val rec = LywsdProtocol.parseHistoryRecord(
            bytes(0xFC, 0x55, 0x00, 0x00, 0xD0, 0x35, 0xB9, 0x04, 0x31, 0x00, 0x4B, 0x29, 0x00, 0x47)
        )!!
        assertEquals(22012L, rec.index)
        assertEquals(79246800L, rec.deviceTsSec) // 0x04B935D0, little-endian
        assertEquals(4.9f, rec.tempMaxC, 0.001f)
        assertEquals(75, rec.humMax)
        assertEquals(4.1f, rec.tempMinC, 0.001f)
        assertEquals(71, rec.humMin)
    }

    @Test fun history_handlesNegativeTemperature() {
        // idx 1, ts 0, maxT -5.0 (0xFFCE), maxH 80, minT -6.0 (0xFFC4), minH 82
        val rec = LywsdProtocol.parseHistoryRecord(
            bytes(0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xCE, 0xFF, 0x50, 0xC4, 0xFF, 0x52)
        )!!
        assertEquals(-5.0f, rec.tempMaxC, 0.001f)
        assertEquals(-6.0f, rec.tempMinC, 0.001f)
        assertEquals(80, rec.humMax)
        assertEquals(82, rec.humMin)
    }

    @Test fun history_tooShort_returnsNull() {
        assertNull(LywsdProtocol.parseHistoryRecord(bytes(0x01, 0x02, 0x03)))
    }

    // ── NUM_RECORDS (lifetime + stored) ───────────────────────────────────────

    @Test fun numRecords_parsesLifetimeAndStored() {
        // b9 64 00 00 | be 0e 00 00  → lifetime 25785, stored 3774
        val b = bytes(0xB9, 0x64, 0x00, 0x00, 0xBE, 0x0E, 0x00, 0x00)
        assertEquals(25785, LywsdProtocol.parseLifetimeCounter(b))
        assertEquals(3774, LywsdProtocol.parseStoredCount(b))
    }

    @Test fun numRecords_nullOrShort_returnZero() {
        assertEquals(0, LywsdProtocol.parseLifetimeCounter(null))
        assertEquals(0, LywsdProtocol.parseStoredCount(null))
        assertEquals(0, LywsdProtocol.parseStoredCount(bytes(0x01, 0x02, 0x03, 0x04))) // needs 8
    }

    // ── device clock ──────────────────────────────────────────────────────────

    @Test fun deviceTime_parsesUnsignedSeconds() {
        // 7b 7b 88 05 → 92_830_587
        assertEquals(92830587L, LywsdProtocol.parseDeviceTimeSec(bytes(0x7B, 0x7B, 0x88, 0x05)))
    }

    @Test fun deviceTime_handlesHighBit_asUnsigned() {
        // ff ff ff ff → 4_294_967_295, not -1
        assertEquals(4294967295L, LywsdProtocol.parseDeviceTimeSec(bytes(0xFF, 0xFF, 0xFF, 0xFF)))
    }

    @Test fun deviceTime_nullOrShort_returnNull() {
        assertNull(LywsdProtocol.parseDeviceTimeSec(null))
        assertNull(LywsdProtocol.parseDeviceTimeSec(bytes(0x01, 0x02)))
    }

    // ── uint32le round-trip ───────────────────────────────────────────────────

    @Test fun uint32le_roundTrips() {
        val enc = LywsdProtocol.uint32le(22011)
        assertEquals(4, enc.size)
        assertEquals(22011, LywsdProtocol.parseLifetimeCounter(enc))
    }
}
