package com.prim.texfit.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "video_items")
data class VideoItemEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val exerciseId: String,
    val numExercise: String,
    val numFile: String,
    val fileName: String,
    val fileSizeRaw: Long,
    val note: String,
    val timings: String, // JSON serialized timings
    val customName: String,
    val isActive: Boolean,
    val isSizeHighlighted: Boolean,
    val sortOrder: Int = 0 
)

@Entity(tableName = "config_options")
data class ConfigOptionEntity(
    @PrimaryKey val id: String,
    val type: String, // "session" or "exercise"
    val name: String
)

@Entity(tableName = "global_settings")
data class GlobalSettingEntity(
    @PrimaryKey val key: String,
    val value: String
)
