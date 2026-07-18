package works.eub.voicememo.car.screen

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import works.eub.voicememo.data.db.VoiceMemoEntity
import works.eub.voicememo.data.repository.MemoRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val TAG = "VoiceMemoCar"

class MemoListScreen(
    carContext: CarContext,
    private val repository: MemoRepository
) : Screen(carContext) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var memos: List<VoiceMemoEntity> = emptyList()
    private var isRecording = false
    private var isPaused = false
    private var isSaving = false
    private var accumulatedText = ""
    private var speechRecognizer: SpeechRecognizer? = null

    init {
        scope.launch {
            repository.getAllMemos().collect { memoList ->
                memos = memoList
                invalidate()
            }
        }
    }

    override fun onGetTemplate(): Template {
        val listBuilder = ItemList.Builder()

        if (isRecording || isPaused) {
            listBuilder.addItem(
                Row.Builder()
                    .setTitle(if (isPaused) "일시 정지됨" else "녹음 중...")
                    .addText(accumulatedText.ifBlank { "듣고 있습니다..." })
                    .build()
            )
            if (isPaused) {
                listBuilder.addItem(
                    Row.Builder()
                        .setTitle("재개")
                        .setOnClickListener { resumeRecording() }
                        .build()
                )
                listBuilder.addItem(
                    Row.Builder()
                        .setTitle("취소")
                        .setOnClickListener { cancelRecording() }
                        .build()
                )
            }
        }

        if (memos.isEmpty() && !isRecording && !isPaused) {
            listBuilder.addItem(
                Row.Builder()
                    .setTitle("메모 없음")
                    .addText("하단 버튼을 눌러 녹음을 시작하세요")
                    .build()
            )
        } else {
            memos.forEach { memo ->
                listBuilder.addItem(
                    Row.Builder()
                        .setTitle(memo.title)
                        .addText(memo.content)
                        .setOnClickListener {
                            screenManager.push(
                                MemoDetailScreen(carContext, repository, memo)
                            )
                        }
                        .build()
                )
            }
        }

        return ListTemplate.Builder()
            .setTitle("음성 메모")
            .setSingleList(listBuilder.build())
            .setActionStrip(buildActionStrip())
            .build()
    }

    private fun buildActionStrip(): ActionStrip {
        val action = when {
            isRecording -> {
                Action.Builder()
                    .setTitle("일시정지")
                    .setOnClickListener { pauseRecording() }
                    .build()
            }
            isPaused -> {
                Action.Builder()
                    .setTitle("저장")
                    .setOnClickListener { saveRecording() }
                    .build()
            }
            else -> {
                Action.Builder()
                    .setTitle("녹음")
                    .setOnClickListener { startRecording() }
                    .build()
            }
        }

        return ActionStrip.Builder()
            .addAction(action)
            .build()
    }

    private fun startRecording() {
        if (isRecording || isSaving) return

        if (carContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "RECORD_AUDIO permission not granted")
            return
        }

        accumulatedText = ""
        doStartListening()
    }

    private fun doStartListening() {
        speechRecognizer?.cancel()
        speechRecognizer?.destroy()

        val recognizer = SpeechRecognizer.createSpeechRecognizer(carContext) ?: run {
            Log.e(TAG, "SpeechRecognizer not available")
            return
        }
        speechRecognizer = recognizer

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "Ready for speech")
                isRecording = true
                isPaused = false
                invalidate()
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "Speech started")
            }

            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                Log.d(TAG, "Speech ended")
                isRecording = false
                isPaused = true
                invalidate()
            }

            override fun onError(error: Int) {
                Log.e(TAG, "Speech error: $error")
                isRecording = false
                isPaused = true
                invalidate()
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(
                    SpeechRecognizer.RESULTS_RECOGNITION
                )
                val text = matches?.firstOrNull() ?: ""
                Log.d(TAG, "Recognized: $text")
                if (text.isNotBlank()) {
                    accumulatedText = if (accumulatedText.isEmpty()) text
                    else "$accumulatedText $text"
                }
                isRecording = false
                isPaused = true
                invalidate()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults?.getStringArrayList(
                    SpeechRecognizer.RESULTS_RECOGNITION
                )?.firstOrNull() ?: ""
                if (partial.isNotBlank()) {
                    val displayText = if (accumulatedText.isEmpty()) partial
                    else "$accumulatedText $partial"
                    Log.d(TAG, "Partial: $displayText")
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

        recognizer.startListening(intent)
    }

    private fun pauseRecording() {
        speechRecognizer?.stopListening()
        isRecording = false
        isPaused = true
        invalidate()
    }

    private fun resumeRecording() {
        doStartListening()
    }

    private fun saveRecording() {
        speechRecognizer?.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null

        val finalText = accumulatedText.trim()
        if (finalText.isNotBlank()) {
            isSaving = true
            scope.launch {
                val memo = VoiceMemoEntity(
                    title = finalText.take(30) + if (finalText.length > 30) "..." else "",
                    content = finalText
                )
                repository.saveMemo(memo)
                isSaving = false
                isRecording = false
                isPaused = false
                accumulatedText = ""
                invalidate()
            }
        } else {
            isRecording = false
            isPaused = false
            accumulatedText = ""
            invalidate()
        }
    }

    private fun cancelRecording() {
        speechRecognizer?.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
        isRecording = false
        isPaused = false
        accumulatedText = ""
        invalidate()
    }

}
