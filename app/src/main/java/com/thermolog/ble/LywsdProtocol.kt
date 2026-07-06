package com.thermolog.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * BLE GATT protocol for the Xiaomi Mijia "comfort" thermometers (LYWSD02 and the
 * LYWSD03MMC units that expose the ebe0ccb0 service — confirmed via GATT Explorer).
 *
 * History readout:
 *   1. Read NUM_RECORDS (ebe0ccb9) → 8 bytes: (totalRecords u32, currentIdx u32) LE
 *   2. Enable notifications on HISTORY (ebe0ccbc)
 *   3. Write the start index (u32 LE) to RECORDS_IDX (ebe0ccba)
 *   4. Device streams one notification per stored hour until exhausted.
 *
 * History record = 14 bytes, little-endian:
 *   [0..3]  u32  index
 *   [4..7]  u32  Unix timestamp (seconds)
 *   [8..9]  i16  max temperature × 100
 *   [10]    u8   max humidity %
 *   [11..12]i16  min temperature × 100
 *   [13]    u8   min humidity %
 */
object LywsdProtocol {
    val SERVICE: UUID = UUID.fromString("ebe0ccb0-7a0a-4b0c-8a1a-6ff2997da3a6")

    /** Current temperature/humidity (notify). LYWSD03MMC: 5 bytes; LYWSD02: 3 bytes. */
    val DATA_CHAR: UUID = UUID.fromString("ebe0ccc1-7a0a-4b0c-8a1a-6ff2997da3a6")

    /** Device clock (read/write). Optional for reading existing history. */
    val TIME_CHAR: UUID = UUID.fromString("ebe0ccb7-7a0a-4b0c-8a1a-6ff2997da3a6")

    /** Number of stored history records (read): (total u32, current u32) LE. */
    val NUM_RECORDS_CHAR: UUID = UUID.fromString("ebe0ccb9-7a0a-4b0c-8a1a-6ff2997da3a6")

    /** Start index to begin streaming history from (write u32 LE). */
    val RECORDS_IDX_CHAR: UUID = UUID.fromString("ebe0ccba-7a0a-4b0c-8a1a-6ff2997da3a6")

    /** History record stream (notify), 14 bytes per record. */
    val HISTORY_CHAR: UUID = UUID.fromString("ebe0ccbc-7a0a-4b0c-8a1a-6ff2997da3a6")

    /** Standard BLE notification descriptor. */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    val KNOWN_NAME_PREFIXES = listOf("LYWSD03MMC", "LYWSD02", "ATC_", "MJ_HT_V1", "MIAOMIAOCE")

    /** True if this advertised name matches a known Xiaomi temperature/humidity sensor. */
    fun isKnownThermometer(name: String?): Boolean =
        name != null && KNOWN_NAME_PREFIXES.any { name.startsWith(it) }

    data class RealtimeReading(
        val temperatureCelsius: Float,
        val humidityPercent: Int,
        val batteryMillivolts: Int
    )

    data class HistoryRecord(
        val index: Long,
        /** Raw timestamp in the device's own clock epoch (seconds). Needs offset correction. */
        val deviceTsSec: Long,
        val tempMaxC: Float,
        val humMax: Int,
        val tempMinC: Float,
        val humMin: Int
    )

    fun parseRealtimeData(bytes: ByteArray): RealtimeReading? {
        if (bytes.size < 3) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val tempRaw = buf.short            // signed int16, ×100
        val humidity = buf.get().toInt() and 0xFF
        val mv = if (bytes.size >= 5) (buf.short.toInt() and 0xFFFF) else 0
        return RealtimeReading(tempRaw / 100f, humidity, mv)
    }

    fun parseHistoryRecord(bytes: ByteArray): HistoryRecord? {
        if (bytes.size < 14) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val idx = buf.int.toLong() and 0xFFFFFFFFL
        val tsSeconds = buf.int.toLong() and 0xFFFFFFFFL
        val maxTemp = buf.short / 10f          // history is ×10 (realtime is ×100)
        val maxHum = buf.get().toInt() and 0xFF
        val minTemp = buf.short / 10f
        val minHum = buf.get().toInt() and 0xFF
        return HistoryRecord(
            index = idx,
            deviceTsSec = tsSeconds,
            tempMaxC = maxTemp,
            humMax = maxHum,
            tempMinC = minTemp,
            humMin = minHum
        )
    }

    /**
     * NUM_RECORDS (ebe0ccb9), 8 bytes LE: (lifetimeCounter u32, storedCount u32).
     * The device is a ring buffer; storedCount is how many records are actually retained.
     */
    fun parseStoredCount(bytes: ByteArray?): Int {
        if (bytes == null || bytes.size < 8) return 0
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(4)
            .let { if (it < 0) 0 else it }
    }

    /** NUM_RECORDS first u32 LE: lifetime record counter (next index to be written). */
    fun parseLifetimeCounter(bytes: ByteArray?): Int {
        if (bytes == null || bytes.size < 4) return 0
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int.let { if (it < 0) 0 else it }
    }

    /** Device current clock, seconds in the device's own epoch (ebe0ccb7, u32 LE). */
    fun parseDeviceTimeSec(bytes: ByteArray?): Long? {
        if (bytes == null || bytes.size < 4) return null
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
    }

    /** 4-byte little-endian unsigned int, e.g. a history start index. */
    fun uint32le(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
}
