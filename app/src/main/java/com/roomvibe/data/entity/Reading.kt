package com.roomvibe.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "readings",
    foreignKeys = [ForeignKey(
        entity = Sensor::class,
        parentColumns = ["address"],
        childColumns = ["sensorAddress"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["sensorAddress", "timestampMs"], unique = true)]
)
data class Reading(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sensorAddress: String,
    val timestampMs: Long,
    /** Representative value (midpoint of min/max for history, exact for realtime). */
    val temperatureCelsius: Float,
    val humidityPercent: Int,
    val tempMinC: Float,
    val tempMaxC: Float,
    val humMin: Int,
    val humMax: Int,
    val batteryPercent: Int = 0
)
