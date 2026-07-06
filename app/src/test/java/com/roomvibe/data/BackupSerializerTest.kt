package com.roomvibe.data

import com.roomvibe.data.entity.Reading
import com.roomvibe.data.entity.Sensor
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupSerializerTest {

    private val sensors = listOf(
        Sensor(address = "A4:C1:38:00:00:01", name = "LYWSD03MMC", alias = "Balcony",
            lastSyncMs = 1_700_000_000_000L, lastHistoryIndex = 42),
        Sensor(address = "A4:C1:38:00:00:02", name = "ATC_123", alias = null,
            lastSyncMs = 0L, lastHistoryIndex = -1)
    )

    // Readings from fromJson always have id = 0, so build inputs the same way.
    private val readings = listOf(
        Reading(id = 0, sensorAddress = "A4:C1:38:00:00:01", timestampMs = 1_700_000_000_000L,
            temperatureCelsius = 4.5f, humidityPercent = 73,
            tempMinC = 4.1f, tempMaxC = 4.9f, humMin = 71, humMax = 75, batteryPercent = 0),
        Reading(id = 0, sensorAddress = "A4:C1:38:00:00:02", timestampMs = 1_700_003_600_000L,
            temperatureCelsius = 22.5f, humidityPercent = 48,
            tempMinC = 22.0f, tempMaxC = 23.0f, humMin = 45, humMax = 50, batteryPercent = 90)
    )

    @Test fun roundTrip_preservesSensorsAndReadings() {
        val json = BackupSerializer.toJson(sensors, readings)
        val parsed = BackupSerializer.fromJson(json)
        assertEquals(sensors, parsed.sensors)
        assertEquals(readings, parsed.readings)
    }

    @Test fun restoredReadings_haveZeroId_forMerge() {
        val json = BackupSerializer.toJson(sensors, readings)
        val parsed = BackupSerializer.fromJson(json)
        assertTrue(parsed.readings.all { it.id == 0L })
    }

    @Test fun nullAlias_roundTripsAsNull() {
        val json = BackupSerializer.toJson(sensors, emptyList())
        val parsed = BackupSerializer.fromJson(json)
        assertEquals(null, parsed.sensors.first { it.address.endsWith("02") }.alias)
    }

    @Test fun emptyBackup_parsesToEmptyLists() {
        val parsed = BackupSerializer.fromJson(BackupSerializer.toJson(emptyList(), emptyList()))
        assertTrue(parsed.sensors.isEmpty())
        assertTrue(parsed.readings.isEmpty())
    }

    @Test fun backup_includesVersion() {
        val json = JSONObject(BackupSerializer.toJson(emptyList(), emptyList()))
        assertEquals(BackupSerializer.VERSION, json.getInt("version"))
    }
}
