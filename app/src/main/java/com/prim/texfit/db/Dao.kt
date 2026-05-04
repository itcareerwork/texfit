package com.prim.texfit.db

import androidx.room.*

@Dao
interface VideoItemDao {
    @Query("SELECT * FROM video_items")
    suspend fun getAll(): List<VideoItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<VideoItemEntity>)

    @Update
    suspend fun update(item: VideoItemEntity)

    @Query("DELETE FROM video_items WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM video_items")
    suspend fun deleteAll()

    @Transaction
    suspend fun replaceAll(items: List<VideoItemEntity>) {
        deleteAll()
        insertAll(items)
    }
}

@Dao
interface ConfigOptionDao {
    @Query("SELECT * FROM config_options WHERE type = :type")
    suspend fun getByType(type: String): List<ConfigOptionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(options: List<ConfigOptionEntity>)

    @Query("DELETE FROM config_options WHERE type = :type")
    suspend fun deleteByType(type: String)

    @Transaction
    suspend fun replaceByType(type: String, options: List<ConfigOptionEntity>) {
        deleteByType(type)
        insertAll(options)
    }
}

@Dao
interface GlobalSettingDao {
    @Query("SELECT value FROM global_settings WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun set(setting: GlobalSettingEntity)
}
