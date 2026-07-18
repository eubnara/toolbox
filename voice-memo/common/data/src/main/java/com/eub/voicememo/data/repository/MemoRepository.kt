package com.eub.voicememo.data.repository

import com.eub.voicememo.data.db.VoiceMemoDao
import com.eub.voicememo.data.db.VoiceMemoEntity
import kotlinx.coroutines.flow.Flow

class MemoRepository(private val dao: VoiceMemoDao) {

    fun getAllMemos(): Flow<List<VoiceMemoEntity>> = dao.getAllMemos()

    suspend fun getMemo(id: Long): VoiceMemoEntity? = dao.getById(id)

    suspend fun saveMemo(memo: VoiceMemoEntity): Long = dao.insert(memo)

    suspend fun updateMemo(memo: VoiceMemoEntity) = dao.update(memo)

    suspend fun deleteMemo(memo: VoiceMemoEntity) = dao.delete(memo)

    suspend fun deleteMemoById(id: Long) = dao.deleteById(id)

    suspend fun getUnsyncedMemos(): List<VoiceMemoEntity> = dao.getUnsyncedMemos()

    suspend fun markAsSynced(memoId: Long, tasksId: String) = dao.markAsSynced(memoId, tasksId)
}
