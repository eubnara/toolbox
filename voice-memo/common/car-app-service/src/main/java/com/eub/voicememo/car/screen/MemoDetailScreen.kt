package com.eub.voicememo.car.screen

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*
import com.eub.voicememo.data.db.VoiceMemoEntity
import com.eub.voicememo.data.repository.MemoRepository
import kotlinx.coroutines.*

class MemoDetailScreen(
    carContext: CarContext,
    private val repository: MemoRepository,
    private val memo: VoiceMemoEntity
) : Screen(carContext) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onGetTemplate(): Template {
        val header = Header.Builder()
            .setStartHeaderAction(Action.BACK)
            .setTitle("메모 상세")
            .build()

        return MessageTemplate.Builder(memo.content)
            .setHeader(header)
            .addAction(
                Action.Builder()
                    .setTitle("삭제")
                    .setOnClickAction {
                        deleteMemo()
                    }
                    .build()
            )
            .build()
    }

    private fun deleteMemo() {
        scope.launch {
            repository.deleteMemo(memo)
            withContext(Dispatchers.Main) {
                screenManager.pop()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
