package works.eub.voicememo.ui

import android.content.Intent
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.os.Bundle
import android.widget.Toast
import android.util.Log
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Checkbox
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import works.eub.voicememo.data.db.VoiceMemoEntity
import works.eub.voicememo.data.repository.MemoRepository
import kotlinx.coroutines.launch

private const val TAG = "VoiceMemo"

enum class RecordingState {
    IDLE, RECORDING, PAUSED
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceMemoScreen(
    repository: MemoRepository,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit
) {
    val memos by repository.getAllMemos().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    var recordingState by remember { mutableStateOf(RecordingState.IDLE) }
    var accumulatedText by remember { mutableStateOf("") }
    var partialText by remember { mutableStateOf("") }
    var lastActivityTime by remember { mutableStateOf(0L) }
    var editingMemo by remember { mutableStateOf<VoiceMemoEntity?>(null) }

    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }

    var pendingDeletes by remember { mutableStateOf<List<VoiceMemoEntity>>(emptyList()) }

    LaunchedEffect(pendingDeletes) {
        val toRestore = pendingDeletes
        if (toRestore.isNotEmpty()) {
            val job = launch {
                kotlinx.coroutines.delay(5000)
                snackbarHostState.currentSnackbarData?.dismiss()
            }
            val result = snackbarHostState.showSnackbar(
                message = "${toRestore.size}개 메모 삭제됨",
                actionLabel = "취소",
                duration = SnackbarDuration.Indefinite
            )
            job.cancel()
            if (result == SnackbarResult.ActionPerformed) {
                toRestore.forEach { memo ->
                    repository.saveMemo(memo)
                }
            }
            pendingDeletes = emptyList()
        }
    }

    val speechRecognizer = remember { mutableStateOf<SpeechRecognizer?>(null) }

    fun doStartListening() {
        speechRecognizer.value?.cancel()
        speechRecognizer.value?.destroy()
        speechRecognizer.value = null
        scope.launch {
            kotlinx.coroutines.delay(200)
            lastActivityTime = System.currentTimeMillis() + 5000

            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            if (recognizer == null) {
                Toast.makeText(context, "음성 인식을 사용할 수 없습니다", Toast.LENGTH_SHORT).show()
                return@launch
            }
            speechRecognizer.value = recognizer

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    Log.d(TAG, "Ready for speech")
                }
                override fun onBeginningOfSpeech() {
                    Log.d(TAG, "Speech started")
                    lastActivityTime = System.currentTimeMillis()
                }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    Log.d(TAG, "Speech ended")
                    recordingState = RecordingState.PAUSED
                }
                override fun onError(error: Int) {
                    Log.e(TAG, "Error: $error")
                    recordingState = RecordingState.PAUSED
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
                    partialText = ""
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults?.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION
                    )?.firstOrNull() ?: ""
                    partialText = partial
                    lastActivityTime = System.currentTimeMillis()
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 10_000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 10_000L)
            }
            recognizer.startListening(intent)
        }
    }

    fun doStopListening() {
        speechRecognizer.value?.stopListening()
    }

    fun cancelRecording() {
        speechRecognizer.value?.cancel()
        speechRecognizer.value?.destroy()
        speechRecognizer.value = null
        accumulatedText = ""
        partialText = ""
        recordingState = RecordingState.IDLE
    }

    fun saveRecording() {
        speechRecognizer.value?.cancel()
        speechRecognizer.value?.destroy()
        speechRecognizer.value = null
        val finalText = accumulatedText.trim()
        if (finalText.isNotBlank()) {
            scope.launch {
                val memo = VoiceMemoEntity(
                    title = finalText.take(30) + if (finalText.length > 30) "..." else "",
                    content = finalText
                )
                repository.saveMemo(memo)
            }
        }
        accumulatedText = ""
        partialText = ""
        recordingState = RecordingState.IDLE
    }

    DisposableEffect(Unit) {
        onDispose {
            speechRecognizer.value?.destroy()
            speechRecognizer.value = null
        }
    }

    LaunchedEffect(recordingState) {
        if (recordingState == RecordingState.RECORDING) {
            while (recordingState == RecordingState.RECORDING) {
                kotlinx.coroutines.delay(1000)
                if (recordingState == RecordingState.RECORDING &&
                    lastActivityTime > 0 &&
                    System.currentTimeMillis() - lastActivityTime > 5000
                ) {
                    doStopListening()
                    recordingState = RecordingState.PAUSED
                }
            }
        }
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    modifier = Modifier
                        .padding(16.dp)
                        .clickable { data.dismiss() }
                )
            }
        },
        topBar = {
            TopAppBar(
                title = {
                    if (selectionMode) {
                        Text("${selectedIds.size}개 선택")
                    } else {
                        Text("음성 메모")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                navigationIcon = {
                    if (selectionMode) {
                        IconButton(onClick = {
                            selectionMode = false
                            selectedIds = emptySet()
                        }) {
                            Text("✕", style = MaterialTheme.typography.titleLarge)
                        }
                    }
                },
                actions = {
                    if (selectionMode) {
                        TextButton(onClick = {
                            selectedIds = if (selectedIds.size == memos.size) {
                                emptySet()
                            } else {
                                memos.map { it.id }.toSet()
                            }
                        }) {
                            Text(if (selectedIds.size == memos.size) "전체 해제" else "전체 선택")
                        }
                        IconButton(
                            onClick = {
                                val toDelete = memos.filter { it.id in selectedIds }
                                pendingDeletes = toDelete
                                scope.launch {
                                    selectedIds.forEach { id ->
                                        repository.deleteMemoById(id)
                                    }
                                    selectedIds = emptySet()
                                    selectionMode = false
                                }
                            }
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "삭제")
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (recordingState != RecordingState.IDLE) {
                RecordingControlBar(
                    state = recordingState,
                    accumulatedText = accumulatedText,
                    partialText = partialText,
                    onPause = {
                        doStopListening()
                        recordingState = RecordingState.PAUSED
                    },
                    onResume = {
                        recordingState = RecordingState.RECORDING
                        doStartListening()
                    },
                    onSave = { saveRecording() },
                    onCancel = { cancelRecording() }
                )
            }
        },
        floatingActionButton = {
            if (recordingState == RecordingState.IDLE && !selectionMode) {
                FloatingActionButton(
                    onClick = {
                        if (!hasPermission) {
                            onRequestPermission()
                            return@FloatingActionButton
                        }
                        accumulatedText = ""
                        partialText = ""
                        recordingState = RecordingState.RECORDING
                        doStartListening()
                    },
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    Text("녹음")
                }
            }
        }
    ) { padding ->
        val contentPadding = if (recordingState != RecordingState.IDLE) {
            PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp)
        } else {
            PaddingValues(16.dp)
        }

        if (memos.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (!hasPermission) "마이크 권한이 필요합니다"
                    else "음성 메모가 없습니다\n하단 버튼을 눌러 녹음을 시작하세요",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = contentPadding
            ) {
                items(memos, key = { it.id }) { memo ->
                    SwipeToDeleteItem(
                        memo = memo,
                        isSelected = memo.id in selectedIds,
                        selectionMode = selectionMode,
                        onSelect = {
                            selectedIds = if (memo.id in selectedIds) {
                                selectedIds - memo.id
                            } else {
                                selectedIds + memo.id
                            }
                        },
                        onLongClick = {
                            if (!selectionMode) {
                                selectionMode = true
                                selectedIds = setOf(memo.id)
                            }
                        },
                        onClick = {
                            if (selectionMode) {
                                selectedIds = if (memo.id in selectedIds) {
                                    selectedIds - memo.id
                                } else {
                                    selectedIds + memo.id
                                }
                            } else {
                                editingMemo = memo
                            }
                        },
                        onDelete = {
                            pendingDeletes = listOf(memo)
                            scope.launch {
                                repository.deleteMemo(memo)
                            }
                        }
                    )
                }
            }
        }
    }

    editingMemo?.let { memo ->
        EditMemoDialog(
            memo = memo,
            onDismiss = { editingMemo = null },
            onSave = { newContent ->
                scope.launch {
                    val updated = memo.copy(
                        content = newContent,
                        title = newContent.take(30) + if (newContent.length > 30) "..." else ""
                    )
                    repository.updateMemo(updated)
                    editingMemo = null
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SwipeToDeleteItem(
    memo: VoiceMemoEntity,
    isSelected: Boolean,
    selectionMode: Boolean,
    onSelect: () -> Unit,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                true
            } else {
                false
            }
        },
        positionalThreshold = { it * 0.5f }
    )

    if (selectionMode || dismissState.currentValue == SwipeToDismissBoxValue.Settled) {
        SwipeToDismissBox(
            state = dismissState,
            enableDismissFromStartToEnd = false,
            enableDismissFromEndToStart = !selectionMode,
            backgroundContent = {
                val color by animateColorAsState(
                    if (dismissState.targetValue == SwipeToDismissBoxValue.EndToStart)
                        MaterialTheme.colorScheme.error
                    else Color.Transparent,
                    label = "bg"
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(color)
                        .padding(horizontal = 20.dp),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "삭제",
                        tint = Color.White
                    )
                }
            }
        ) {
            MemoItem(
                memo = memo,
                isSelected = isSelected,
                selectionMode = selectionMode,
                onClick = onClick,
                onLongClick = onLongClick
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MemoItem(
    memo: VoiceMemoEntity,
    isSelected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = null,
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = memo.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = memo.content,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun EditMemoDialog(
    memo: VoiceMemoEntity,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var text by remember { mutableStateOf(memo.content) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("메모 편집") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 150.dp),
                label = { Text("내용") }
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(text) },
                enabled = text.isNotBlank()
            ) {
                Text("저장")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("취소")
            }
        }
    )
}

@Composable
fun RecordingControlBar(
    state: RecordingState,
    accumulatedText: String,
    partialText: String,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    Surface(
        tonalElevation = 3.dp,
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            val displayText = buildString {
                if (accumulatedText.isNotBlank()) append(accumulatedText)
                if (partialText.isNotBlank()) {
                    if (isNotBlank()) append(" ")
                    append("[$partialText]")
                }
            }

            if (displayText.isNotBlank()) {
                Text(
                    text = displayText,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp, max = 100.dp)
                        .padding(bottom = 8.dp),
                    color = MaterialTheme.colorScheme.onSurface
                )
            } else {
                Text(
                    text = if (state == RecordingState.RECORDING) "듣고 있습니다..." else "일시 정지됨",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                OutlinedButton(onClick = onCancel) {
                    Text("취소")
                }
                Button(
                    onClick = if (state == RecordingState.RECORDING) onPause else onResume
                ) {
                    Text(if (state == RecordingState.RECORDING) "일시 정지" else "재개")
                }
                Button(
                    onClick = onSave,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text("저장")
                }
            }
        }
    }
}
