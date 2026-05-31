package com.example.clipcleaner

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.TimePicker
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var floodBtn: Button

    private lateinit var scheduleCheck: CheckBox
    private lateinit var scheduleTime: TimePicker
    private lateinit var scheduleStatus: TextView

    private var running = false

    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        statusText = findViewById(R.id.status_text)
        progressBar = findViewById(R.id.progress_bar)
        floodBtn = findViewById(R.id.btn_flood)
        scheduleCheck = findViewById(R.id.schedule_check)
        scheduleTime = findViewById(R.id.schedule_time)
        scheduleStatus = findViewById(R.id.schedule_status)

        floodBtn.setOnClickListener { runFlood(50) }

        scheduleCheck.setOnCheckedChangeListener { _, on -> updateSchedule(on) }

        val h = Scheduler.hour(this)
        val m = Scheduler.minute(this)
        scheduleTime.hour = h
        scheduleTime.minute = m

        scheduleTime.setOnTimeChangedListener { _, _, _ -> updateSchedule(scheduleCheck.isChecked) }
        scheduleCheck.isChecked = Scheduler.enabled(this)
        updateScheduleStatus()
    }

    private fun runFlood(count: Int) {
        if (running) return
        running = true
        statusText.text = "Injecting $count clips..."
        progressBar.max = count
        progressBar.progress = 0

        Thread {
            try {
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                for (i in 0 until count) {
                    cm.setPrimaryClip(ClipData.newPlainText("c", ".$i"))
                    runOnUiThread { if (!isFinishing) progressBar.progress = i + 1 }
                }
                cm.setPrimaryClip(
                    ClipData.newPlainText("c", ".")
                )
                runOnUiThread { if (!isFinishing) statusText.text = "Done! $count clips injected." }
            } catch (e: Exception) {
                runOnUiThread { if (!isFinishing) statusText.text = "Error: ${e.message}" }
            } finally {
                running = false
            }
        }.start()
    }

    private fun updateSchedule(on: Boolean) {
        val h: Int; val m: Int
        @Suppress("DEPRECATION")
        fun TimePicker.h() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) hour else currentHour
        @Suppress("DEPRECATION")
        fun TimePicker.m() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) minute else currentMinute
        h = scheduleTime.h()
        m = scheduleTime.m()
        Scheduler.set(this, on, h, m, 50)
        updateScheduleStatus()
    }

    private fun updateScheduleStatus() {
        val next = Scheduler.nextRunTime(this)
        scheduleStatus.text = if (next != null) "Next run: $next" else ""
    }
}
