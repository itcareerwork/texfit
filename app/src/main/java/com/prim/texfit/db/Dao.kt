package com.prim.texfit.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface VideoItemDao {
    @Query("SELECT * FROM video_items ORDER BY sortOrder ASC")
    fun getAllFlow(): Flow<List<VideoItemEntity>>

    @Query("SELECT * FROM video_items ORDER BY sortOrder ASC")
    suspend fun getAll(): List<VideoItemEntity>

    @Query("SELECT * FROM video_items WHERE id = :id")
    suspend fun getById(id: String): VideoItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<VideoItemEntity>)

    @Update
    suspend fun update(item: VideoItemEntity)

    @Query("DELETE FROM video_items WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM video_items")
    suspend fun deleteAll()

    @Query("UPDATE video_items SET sessionId = '', numExercise = '', isActive = 0 WHERE sessionId = :sessionId")
    suspend fun clearSessionReferences(sessionId: String)

    @Query("UPDATE video_items SET exerciseId = '', numFile = '', isActive = 0 WHERE exerciseId = :exerciseId")
    suspend fun clearExerciseReferences(exerciseId: String)

    @Transaction
    suspend fun replaceAll(items: List<VideoItemEntity>) {
        deleteAll()
        insertAll(items)
    }
}

@Dao
interface ConfigOptionDao {
    @Query("SELECT * FROM config_options WHERE type = :type ORDER BY name ASC")
    fun getByTypeFlow(type: String): Flow<List<ConfigOptionEntity>>

    @Query("SELECT * FROM config_options WHERE type = :type ORDER BY name ASC")
    suspend fun getByType(type: String): List<ConfigOptionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(options: List<ConfigOptionEntity>)

    @Query("DELETE FROM config_options WHERE id = :id")
    suspend fun deleteById(id: String)

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
