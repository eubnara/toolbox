package works.eub.voicememo.car

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session
import works.eub.voicememo.car.screen.MemoListScreen

class VoiceMemoSession : Session() {

    override fun onCreateScreen(intent: Intent): Screen {
        val app = carContext.applicationContext as VoiceMemoApplication
        return MemoListScreen(carContext, app.repository)
    }
}
