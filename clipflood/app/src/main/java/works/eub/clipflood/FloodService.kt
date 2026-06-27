package works.eub.clipflood

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class FloodService : Service() {

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CH_ID, "ClipCleaner",
                NotificationManager.IMPORTANCE_LOW).apply {
                description = "Background clipboard cleaning"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val count = intent?.getIntExtra("count", Scheduler.DEFAULT_COUNT) ?: Scheduler.DEFAULT_COUNT
        val notif = NotificationCompat.Builder(this, CH_ID)
            .setContentTitle("Cleaning clipboard...")
            .setContentText("Injecting $count clips")
            .setSmallIcon(android.R.drawable.ic_menu_delete)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        startForeground(NID, notif)

        Thread {
            try {
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                for (i in 0 until count) {
                    cm.setPrimaryClip(ClipData.newPlainText("c", ".$i"))
                    Thread.sleep(100)
                }
                cm.setPrimaryClip(
                    ClipData.newPlainText("c", ".")
                )
                notify("Cleaned $count clips")
            } catch (e: Exception) {
                notify("Failed: ${e.message}")
            } finally {
                Thread.sleep(1500)
                stopForeground(STOP_FOREGROUND_REMOVE)
                Scheduler.reschedule(this)
                stopSelf()
            }
        }.start()

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notify(text: String) {
        val n = NotificationCompat.Builder(this, CH_ID)
            .setContentTitle("Clipboard Cleaner")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_delete)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NID, n)
    }

    companion object {
        private const val CH_ID = "clip_clean"
        private const val NID = 1001
    }
}
