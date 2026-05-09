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

    // ТОЧЕЧНЫЕ ОБНОВЛЕНИЯ для предотвращения Race Condition
    @Query("UPDATE video_items SET timings = :timings WHERE id = :id")
    suspend fun updateTimings(id: String, timings: String)

    @Query("UPDATE video_items SET isActive = :isActive WHERE id = :id")
    suspend fun updateIsActive(id: String, isActive: Boolean)

    @Query("UPDATE video_items SET sessionId = :sId, numExercise = :nE, isActive = :active WHERE id = :id")
    suspend fun updateSessionLink(id: String, sId: String, nE: String, active: Boolean)

    @Query("UPDATE video_items SET exerciseId = :eId, numFile = :nF, isActive = :active WHERE id = :id")
    suspend fun updateExerciseLink(id: String, eId: String, nF: String, active: Boolean)

    @Query("UPDATE video_items SET customName = :name WHERE id = :id")
    suspend fun updateCustomName(id: String, name: String)

    @Query("UPDATE video_items SET note = :note WHERE id = :id")
    suspend fun updateNote(id: String, note: String)

    @Query("UPDATE video_items SET isSizeHighlighted = :highlight WHERE id = :id")
    suspend fun updateSizeHighlight(id: String, highlight: Boolean)

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

    @Query("SELECT value FROM global_settings WHERE `key` = :key")
    fun getFlow(key: String): Flow<String?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun set(setting: GlobalSettingEntity)
}
