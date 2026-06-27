package com.thermolog.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sensors")
data class Sensor(
    @PrimaryKey val address: String,
    val name: String,
    val alias: String? = null,
    val lastSyncMs: Long = 0L,
    /** Highest history record index already downloaded; -1 means none yet. */
    val lastHistoryIndex: Int = -1
)
