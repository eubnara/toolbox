package com.eub.voicememo.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface VoiceMemoDao {
    @Query("SELECT * FROM voice_memos ORDER BY createdAt DESC")
    fun getAllMemos(): Flow<List<VoiceMemoEntity>>

    @Query("SELECT * FROM voice_memos WHERE id = :id")
    suspend fun getById(id: Long): VoiceMemoEntity?

    @Query("SELECT * FROM voice_memos WHERE syncedToTasks = 0")
    suspend fun getUnsyncedMemos(): List<VoiceMemoEntity>

    @Insert
    suspend fun insert(memo: VoiceMemoEntity): Long

    @Update
    suspend fun update(memo: VoiceMemoEntity)

    @Delete
    suspend fun delete(memo: VoiceMemoEntity)

    @Query("DELETE FROM voice_memos WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE voice_memos SET syncedToTasks = 1, tasksId = :tasksId WHERE id = :memoId")
    suspend fun markAsSynced(memoId: Long, tasksId: String)
}
