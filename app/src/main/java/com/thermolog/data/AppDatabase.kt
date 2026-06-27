package com.thermolog.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.thermolog.data.dao.ReadingDao
import com.thermolog.data.dao.SensorDao
import com.thermolog.data.entity.Reading
import com.thermolog.data.entity.Sensor

@Database(entities = [Sensor::class, Reading::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sensorDao(): SensorDao
    abstract fun readingDao(): ReadingDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "thermolog.db"
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}
