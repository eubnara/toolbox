package works.eub.clipflood

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

object Scheduler {

    private const val PREFS = "schedule"
    private const val KEY_ON = "enabled"
    private const val KEY_H = "hour"
    private const val KEY_M = "min"
    private const val KEY_COUNT = "count"
    const val DEFAULT_COUNT = 70

    fun enabled(ctx: Context) = prefs(ctx).getBoolean(KEY_ON, false)
    fun hour(ctx: Context) = prefs(ctx).getInt(KEY_H, 2)
    fun minute(ctx: Context) = prefs(ctx).getInt(KEY_M, 0)
    fun count(ctx: Context) = prefs(ctx).getInt(KEY_COUNT, DEFAULT_COUNT)

    fun setCount(ctx: Context, count: Int) {
        prefs(ctx).edit().putInt(KEY_COUNT, count).apply()
    }

    fun set(ctx: Context, on: Boolean, h: Int, m: Int, c: Int) {
        prefs(ctx).edit().apply {
            putBoolean(KEY_ON, on)
            putInt(KEY_H, h)
            putInt(KEY_M, m)
            putInt(KEY_COUNT, c)
            apply()
        }
        if (on) scheduleNext(ctx, h, m) else cancel(ctx)
    }

    fun nextRunTime(ctx: Context): String? {
        if (!enabled(ctx)) return null
        val h = hour(ctx); val m = minute(ctx)
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, h)
            set(Calendar.MINUTE, m)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (before(Calendar.getInstance())) add(Calendar.DAY_OF_MONTH, 1)
        }
        val fmt = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
        val label = if (cal.get(Calendar.DAY_OF_YEAR) == Calendar.getInstance().get(Calendar.DAY_OF_YEAR)) "Today" else "Tomorrow"
        return "$label ${fmt.format(cal.time)}"
    }

    fun scheduleNext(ctx: Context, h: Int, m: Int) {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, h)
            set(Calendar.MINUTE, m)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (before(Calendar.getInstance())) add(Calendar.DAY_OF_MONTH, 1)
        }
        val alarm = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pending(ctx)
        alarm.setAlarmClock(AlarmManager.AlarmClockInfo(cal.timeInMillis, null), pi)
    }

    fun cancel(ctx: Context) {
        val alarm = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.cancel(pending(ctx))
    }

    fun reschedule(ctx: Context) {
        if (enabled(ctx)) scheduleNext(ctx, hour(ctx), minute(ctx))
    }

    private fun pending(ctx: Context): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java)
        return PendingIntent.getBroadcast(ctx, 0, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val i = Intent(ctx, FloodService::class.java).apply {
            putExtra("count", Scheduler.count(ctx))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(i)
        } else {
            ctx.startService(i)
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        if (i.action == Intent.ACTION_BOOT_COMPLETED) Scheduler.reschedule(ctx)
    }
}
