package com.thermolog.data.dao

import androidx.room.*
import com.thermolog.data.entity.Reading
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadingDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(readings: List<Reading>): List<Long>

    @Query(
        "SELECT * FROM readings WHERE sensorAddress = :address " +
        "AND timestampMs BETWEEN :fromMs AND :toMs ORDER BY timestampMs ASC"
    )
    fun getReadingsInRange(address: String, fromMs: Long, toMs: Long): Flow<List<Reading>>

    @Query("SELECT * FROM readings WHERE sensorAddress = :address ORDER BY timestampMs ASC")
    fun getAllForSensor(address: String): Flow<List<Reading>>

    @Query("SELECT * FROM readings")
    suspend fun getAllOnce(): List<Reading>

    @Query(
        "SELECT MIN(timestampMs) FROM readings WHERE sensorAddress = :address"
    )
    suspend fun getOldestTimestampMs(address: String): Long?

    @Query(
        "SELECT MAX(timestampMs) FROM readings WHERE sensorAddress = :address"
    )
    suspend fun getNewestTimestampMs(address: String): Long?

    @Query("SELECT COUNT(*) FROM readings WHERE sensorAddress = :address")
    suspend fun getCount(address: String): Int

    @Query("DELETE FROM readings WHERE sensorAddress = :address")
    suspend fun deleteAllForSensor(address: String)
}
