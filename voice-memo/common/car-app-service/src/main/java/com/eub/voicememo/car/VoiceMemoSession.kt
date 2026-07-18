package com.eub.voicememo.car

import android.content.Intent
import androidx.car.app.CarContext
import androidx.car.app.Session
import com.eub.voicememo.car.screen.MemoListScreen

class VoiceMemoSession(carContext: CarContext) : Session(carContext) {

    override fun onCreateScreen(intent: Intent): MemoListScreen {
        val app = carContext.applicationContext as VoiceMemoApplication
        return MemoListScreen(carContext, app.repository)
    }
}
