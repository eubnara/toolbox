package com.eub.voicememo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.eub.voicememo.data.db.VoiceMemoDatabase
import com.eub.voicememo.data.repository.MemoRepository
import com.eub.voicememo.ui.VoiceMemoScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val database = remember { VoiceMemoDatabase.getDatabase(applicationContext) }
                    val repository = remember { MemoRepository(database.voiceMemoDao()) }
                    VoiceMemoScreen(repository = repository)
                }
            }
        }
    }
}
