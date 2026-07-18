package com.eub.voicememo.car.screen

import android.app.Application
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.RecognitionListener
import android.os.Bundle
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*
import androidx.car.app.model.Template
import com.eub.voicememo.data.db.VoiceMemoEntity
import com.eub.voicememo.data.repository.MemoRepository
import kotlinx.coroutines.*

class MemoListScreen(
    carContext: CarContext,
    private val repository: MemoRepository
) : Screen(carContext) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var memos: List<VoiceMemoEntity> = emptyList()
    private var isRecording = false
    private var isSaving = false

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

        if (memos.isEmpty()) {
            listBuilder.addItem(
                Row.Builder()
                    .setTitle("메모 없음")
                    .addText("음성 녹음 버튼을 눌러 메모를 남기세요")
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

        val recordAction = Action.Builder()
            .setTitle(if (isRecording) "녹음 중..." else "음성 메모")
            .setOnClickAction {
                startVoiceRecording()
            }
            .build()

        val header = Header.Builder()
            .setStartHeaderAction(Action.APP_ICON)
            .setTitle("음성 메모")
            .build()

        return ListTemplate.Builder()
            .setHeader(header)
            .setSingleList(listBuilder.build())
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(recordAction)
                    .build()
            )
            .build()
    }

    private fun startVoiceRecording() {
        if (isRecording || isSaving) return

        val speechRecognizer = SpeechRecognizer.createSpeechRecognizer(carContext)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                isRecording = true
                invalidate()
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                isRecording = false
            }

            override fun onError(error: Int) {
                isRecording = false
                invalidate()
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(
                    SpeechRecognizer.RESULTS_RECOGNITION
                )
                val recognizedText = matches?.firstOrNull() ?: ""
                if (recognizedText.isNotBlank()) {
                    saveMemo(recognizedText)
                }
                speechRecognizer.destroy()
                invalidate()
            }

            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        speechRecognizer.startListening(intent)
    }

    private fun saveMemo(text: String) {
        isSaving = true
        scope.launch {
            val memo = VoiceMemoEntity(
                title = text.take(30) + if (text.length > 30) "..." else "",
                content = text
            )
            repository.saveMemo(memo)
            isSaving = false
            invalidate()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
