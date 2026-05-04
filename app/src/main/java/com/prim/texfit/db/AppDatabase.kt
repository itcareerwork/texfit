package com.prim.texfit.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(entities = [VideoItemEntity::class, ConfigOptionEntity::class, GlobalSettingEntity::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun videoItemDao(): VideoItemDao
    abstract fun configOptionDao(): ConfigOptionDao
    abstract fun globalSettingDao(): GlobalSettingDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "texfit_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
