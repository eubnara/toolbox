package works.eub.voicememo.car.screen

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import works.eub.voicememo.data.db.VoiceMemoEntity
import works.eub.voicememo.data.repository.MemoRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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
                    .setOnClickListener {
                        deleteMemo()
                    }
                    .build()
            )
            .build()
    }

    private fun deleteMemo() {
        scope.launch {
            repository.deleteMemo(memo)
            screenManager.pop()
        }
    }
}
