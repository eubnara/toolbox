package com.eub.voicememo.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "voice_memos")
data class VoiceMemoEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val content: String,
    val audioFilePath: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val durationMs: Long = 0,
    val syncedToTasks: Boolean = false,
    val tasksId: String? = null
)
