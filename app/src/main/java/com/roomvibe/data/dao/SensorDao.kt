package com.roomvibe.data.dao

import androidx.room.*
import com.roomvibe.data.entity.Sensor
import kotlinx.coroutines.flow.Flow

@Dao
interface SensorDao {
    @Query("SELECT * FROM sensors ORDER BY alias, name")
    fun getAllFlow(): Flow<List<Sensor>>

    @Query("SELECT * FROM sensors")
    suspend fun getAllOnce(): List<Sensor>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(sensors: List<Sensor>): List<Long>

    @Query("SELECT * FROM sensors WHERE address = :address")
    suspend fun getByAddress(address: String): Sensor?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(sensor: Sensor)

    @Delete
    suspend fun delete(sensor: Sensor)

    @Query("UPDATE sensors SET alias = :alias WHERE address = :address")
    suspend fun updateAlias(address: String, alias: String?)

    @Query("UPDATE sensors SET lastSyncMs = :ms WHERE address = :address")
    suspend fun updateLastSync(address: String, ms: Long)

    @Query("UPDATE sensors SET lastHistoryIndex = :index WHERE address = :address")
    suspend fun updateLastHistoryIndex(address: String, index: Int)
}
