package com.eub.voicememo.car

import android.app.Application
import com.eub.voicememo.data.db.VoiceMemoDatabase
import com.eub.voicememo.data.repository.MemoRepository

class VoiceMemoApplication : Application() {
    val database by lazy { VoiceMemoDatabase.getDatabase(this) }
    val repository by lazy { MemoRepository(database.voiceMemoDao()) }
}
