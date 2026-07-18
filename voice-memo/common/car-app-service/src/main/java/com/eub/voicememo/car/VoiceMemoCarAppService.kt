package com.eub.voicememo.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.SessionInfo

class VoiceMemoCarAppService : CarAppService() {
    override fun createSession(sessionInfo: SessionInfo): Session {
        return VoiceMemoSession(this)
    }
}
