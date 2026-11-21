package dev.trrrst.watch.listen

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import dev.trrrst.watch.ble.Protocol
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Switches the listening test on and off and sets its alarms (PLAN Phase 3, step 1).
 *  It listens around the manual's four daily sync times, plus the watch's own
 *  auto-sync setting, which is kept on the phone (prefs `watch`) whenever the app
 *  reads the watch's BLE settings, because the app is closed when it matters. */
object ListenScheduler {
    private const val PREFS = "watch"
    private const val KEY_TEST = "listenTest"
    private const val KEY_AUTO_ON = "autoSyncOn"
    private const val KEY_HOUR = "autoSyncHour"
    private const val KEY_MINUTE = "autoSyncMinute"
    private const val KEY_NEXT = "listenNextStart"

    const val EXTRA_START = "start"
    const val EXTRA_SYNC_AT = "syncAt"
    const val EXTRA_END = "end"
    const val EXTRA_LABEL = "label"

    /** "Listen now": how long, for pressing D on the watch by hand. */
    const val LISTEN_NOW_MS = 5 * 60_000L

    /** [autoSyncOn] is null while the app hasn't read the watch's setting yet. */
    data class Status(
        val testOn: Boolean,
        val autoSyncOn: Boolean?,
        val hour: Int,
        val minute: Int,
        val next: ListenSchedule.Window?,
    )

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** [notBefore] (ms): plan from then instead of now, so a window that just ended
     *  (or ended early) isn't picked again. */
    fun status(ctx: Context, notBefore: Long = 0L): Status {
        val p = prefs(ctx)
        val testOn = p.getBoolean(KEY_TEST, false)
        val autoOn = if (p.contains(KEY_AUTO_ON)) p.getBoolean(KEY_AUTO_ON, false) else null
        val h = p.getInt(KEY_HOUR, 8)
        val m = p.getInt(KEY_MINUTE, 0)
        val from = maxOf(System.currentTimeMillis(), notBefore)
        val fromTime = Instant.ofEpochMilli(from).atZone(ZoneId.systemDefault())
        val next = if (testOn) ListenSchedule.next(fromTime, ListenSchedule.times(autoOn, h, m)) else null
        return Status(testOn, autoOn, h, m, next)
    }

    fun setTestOn(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_TEST, on).apply()
        ListenLog(ctx).add("listening test switched ${if (on) "on" else "off"}")
        schedule(ctx)
    }

    /** Keep the watch's auto-sync setting (just read from the watch) on the phone. */
    fun rememberAutoSync(ctx: Context, bs: Protocol.BleSettings) {
        val on = bs.autoSyncEnabled && bs.hasAutoSyncTime
        val h = if (bs.hasAutoSyncTime) bs.autoSyncHour else 8
        val m = if (bs.hasAutoSyncTime) bs.autoSyncMinute else 0
        val st = status(ctx)
        if (st.autoSyncOn == on && st.hour == h && st.minute == m) return
        prefs(ctx).edit().putBoolean(KEY_AUTO_ON, on).putInt(KEY_HOUR, h).putInt(KEY_MINUTE, m).apply()
        ListenLog(ctx).add("watch auto time sync: ${if (on) "on at %02d:%02d".format(h, m) else "off"}")
        schedule(ctx)
    }

    /** Set (or cancel) the alarm for the next listening window. Safe to call often. */
    fun schedule(ctx: Context, notBefore: Long = 0L): ListenSchedule.Window? {
        val app = ctx.applicationContext
        val am = app.getSystemService(AlarmManager::class.java)
        val p = prefs(app)
        val st = status(app, notBefore)
        val w = st.next
        if (w == null) {
            am.cancel(alarmIntent(app, null))
            if (p.contains(KEY_NEXT)) {
                p.edit().remove(KEY_NEXT).apply()
                ListenLog(app).add("no listening planned (test is off)")
            }
            return null
        }
        val at = w.start.toInstant().toEpochMilli()
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        val pi = alarmIntent(app, w)
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        if (p.getLong(KEY_NEXT, 0L) != at) {
            p.edit().putLong(KEY_NEXT, at).apply()
            ListenLog(app).add(
                "next listening: ${fmt(w.start)} to ${fmt(w.end)} (around the watch's sync at ${w.label})" +
                    if (exact) "" else " - inexact alarm, this Android version needs extra permission for exact ones"
            )
        }
        return w
    }

    private fun alarmIntent(ctx: Context, w: ListenSchedule.Window?): PendingIntent {
        val i = Intent(ctx, ListenAlarmReceiver::class.java).setAction("dev.trrrst.watch.LISTEN")
        if (w != null) {
            i.putExtra(EXTRA_START, w.start.toInstant().toEpochMilli())
            i.putExtra(EXTRA_SYNC_AT, w.syncAt.toInstant().toEpochMilli())
            i.putExtra(EXTRA_END, w.end.toInstant().toEpochMilli())
            i.putExtra(EXTRA_LABEL, "the watch's sync at ${w.label}")
        }
        return PendingIntent.getBroadcast(
            ctx, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Listen right away for [LISTEN_NOW_MS] (the user presses D on the watch). Started
     *  from the open app, so Android allows the foreground service. */
    fun listenNow(ctx: Context) {
        val now = System.currentTimeMillis()
        val i = Intent(ctx, ListenService::class.java)
            .putExtra(EXTRA_START, now)
            .putExtra(EXTRA_SYNC_AT, now)
            .putExtra(EXTRA_END, now + LISTEN_NOW_MS)
            .putExtra(EXTRA_LABEL, "Listen now (press D on the watch)")
        try {
            ContextCompat.startForegroundService(ctx, i)
        } catch (e: Exception) {
            ListenLog(ctx).add("couldn't start listening now: $e")
        }
    }

    private val FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private fun fmt(t: ZonedDateTime) = t.format(FMT)
    fun fmt(ms: Long): String = fmt(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
}

/** The daily alarm: starts the listening service. */
class ListenAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val svc = Intent(ctx, ListenService::class.java).putExtras(intent)
        try {
            ContextCompat.startForegroundService(ctx, svc)
        } catch (e: Exception) {
            ListenLog(ctx).add("alarm fired, but Android wouldn't start the listening service: $e")
            ListenScheduler.schedule(ctx)
        }
    }
}
