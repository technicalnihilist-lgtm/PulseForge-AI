package com.pulseforge.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [ScanEntity::class], version = 1, exportSchema = false)
abstract class PulseForgeDatabase : RoomDatabase() {

    abstract fun scanDao(): ScanDao

    companion object {
        @Volatile
        private var instance: PulseForgeDatabase? = null

        fun get(context: Context): PulseForgeDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    PulseForgeDatabase::class.java,
                    "pulseforge.db"
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}
