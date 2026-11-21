package dev.trrrst.watch.listen

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.location.LocationManagerCompat
import dev.trrrst.watch.MainActivity
import dev.trrrst.watch.TrrrstApp
import dev.trrrst.watch.WatchMatcher
import dev.trrrst.watch.ble.BleManager.ConnState
import dev.trrrst.watch.ble.Protocol
import dev.trrrst.watch.ble.WATCH_NAME
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Listening test (PLAN Phase 3, step 1). Started by [ListenAlarmReceiver] just before
 *  the watch's auto-sync time. Runs as a foreground service (quiet notification) so
 *  Android keeps it alive, listens until the window ends, then plans the next day. */
class ListenService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var log: ListenLog
    private var started = false
    private var finished = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        log = ListenLog(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android requires the notification at once after startForegroundService.
        if (!goForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (started) {
            log.add("asked to listen (alarm or Listen now) while already listening; ignored")
            return START_NOT_STICKY
        }
        started = true
        val now = System.currentTimeMillis()
        val plannedStart = intent?.getLongExtra(ListenScheduler.EXTRA_START, now) ?: now
        val syncAt = intent?.getLongExtra(ListenScheduler.EXTRA_SYNC_AT, now + 120_000) ?: (now + 120_000)
        val end = intent?.getLongExtra(ListenScheduler.EXTRA_END, syncAt + 1_200_000) ?: (syncAt + 1_200_000)
        val label = intent?.getStringExtra(ListenScheduler.EXTRA_LABEL) ?: "the watch's sync"
        // Keep the CPU awake for the window only, or timers stall while the screen is off.
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "trrrst:listen")
            .apply { setReferenceCounted(false); acquire((end - now).coerceAtLeast(0) + 120_000) }
        scope.launch {
            try {
                val limit = (end - System.currentTimeMillis()).coerceAtLeast(0) + 60_000
                val done = withTimeoutOrNull(limit) { ListenRun(this@ListenService, log).run(label, plannedStart, end) }
                if (done == null) log.add("hard time limit reached; stopped")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("TrrrstListen", "listening test failed", e)
                log.add("listening test failed: $e")
            } finally {
                finished = true
                ListenScheduler.schedule(this@ListenService)
                ServiceCompat.stopForeground(this@ListenService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (started && !finished) log.add("listening service was stopped before the test finished")
        scope.cancel()
        runCatching { wakeLock?.release() }
        super.onDestroy()
    }

    private fun goForeground(): Boolean {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Watch listening test", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while the phone listens for the watch at its auto-sync time."
                setShowBadge(false)
            }
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Listening for your watch")
            .setContentText("Test: noting what the watch does when it syncs.")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .build()
        return try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, n,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
            )
            true
        } catch (e: Exception) {
            log.add("couldn't show the listening notification, so Android won't let the test run: $e")
            false
        }
    }

    private companion object {
        const val CHANNEL = "listen_test"
        const val NOTIFICATION_ID = 31
    }
}

/** One listening window: listen, log, try one read-only connection to the saved watch. */
private class ListenRun(private val ctx: Context, private val log: ListenLog) {
    private val repo = (ctx.applicationContext as TrrrstApp).repository
    private val ble = repo.ble
    private val clockFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private fun clock(ms: Long): String = clockFmt.format(Date(ms))
    private var probeResult = "no connection tried (the saved watch wasn't heard)"

    suspend fun run(label: String, plannedStart: Long, end: Long) = coroutineScope {
        val now = System.currentTimeMillis()
        val saved = repo.savedWatch.value
        log.add("=== listening test: $label, listening until ${ListenScheduler.fmt(end)}")
        log.add("alarm planned for ${clock(plannedStart)}, came %.1f s late".format((now - plannedStart) / 1000.0))
        log.add(phoneState())
        log.add("saved watch: ${saved?.address ?: "none (connect once in the app first)"}; " +
            "paired: ${saved?.let { PairTest.bondName(ble.bondState(it.address)) } ?: "-"}; " +
            "connection now: ${ble.state.value.name.lowercase()}")
        if (now >= end) {
            log.add("too late: the listening window is already over")
            return@coroutineScope
        }

        val tracker = AdvertTracker { clock(it) }
        var probe: Job? = null
        val stateLog = launch {
            var prev = ble.state.value
            ble.state.collect {
                if (it != prev) {
                    val status = ble.lastDisconnectStatus
                    log.add("connection: ${it.name.lowercase()}" +
                        if (it == ConnState.DISCONNECTED) {
                            if (status != null) " (Android status $status${if (status == 19) " = the watch hung up" else ""})" else ""
                        } else "")
                    prev = it
                }
            }
        }
        val stop = ble.listen(
            saved?.address,
            onResult = { r ->
                val address = r.device.address
                val name = runCatching { r.device.name }.getOrNull() ?: r.scanRecord?.deviceName
                tracker.seen(
                    System.currentTimeMillis(), address, name, r.rssi, r.isConnectable,
                    r.scanRecord?.bytes?.let { advertHex(it) } ?: "",
                ).forEach(log::add)
                if (probe == null && saved != null && WatchMatcher.sameAddress(saved.address, address)) {
                    probe = launch { probe(r.device) }
                }
            },
            onFailed = { log.add("couldn't listen: $it") },
        )
        if (stop == null) {
            stateLog.cancel()
            return@coroutineScope
        }
        log.add("listening for name \"$WATCH_NAME\"" + (saved?.let { " or address ${it.address}" } ?: ""))
        try {
            var lastBeat = System.currentTimeMillis()
            while (System.currentTimeMillis() < end) {
                delay(1_000)
                val t = System.currentTimeMillis()
                tracker.tick(t).forEach(log::add)
                if (t - lastBeat >= 60_000) {
                    lastBeat = t
                    log.add("still listening: ${tracker.total} adverts so far, screen ${if (screenOn()) "on" else "off"}")
                }
            }
        } finally {
            stop()
            probe?.cancel()
            stateLog.cancel()
            tracker.finish()
            val bursts = tracker.summary()
            log.add("--- summary: ${tracker.total} adverts heard" + if (bursts.isEmpty()) "; the watch wasn't heard at all" else "")
            bursts.forEach { log.add("  $it") }
            log.add("  connection test: $probeResult")
            log.add("=== listening test over")
        }
    }

    /** Connect to the saved watch, note anything it sends by itself, do one read, hang
     *  up. Nothing is written to the watch (apart from the standard Bluetooth
     *  "send me notifications" switch that every connect sets). */
    private suspend fun probe(dev: BluetoothDevice) {
        val inUse = repo.busy.value || ble.state.value !in setOf(ConnState.DISCONNECTED, ConnState.ERROR)
        if (inUse) {
            probeResult = "not tried: the app was using the watch (${ble.state.value.name.lowercase()})"
            log.add(probeResult)
            return
        }
        var attempted = false
        try {
            repo.exclusive {
                log.add("connecting to ${dev.address} (test: read only)")
                attempted = true
                repo.testOwnsConnection = true
                val t0 = System.currentTimeMillis()
                fun secs() = "%.1f s".format((System.currentTimeMillis() - t0) / 1000.0)
                val ok = withTimeoutOrNull(30_000) { ble.connect(dev) }
                if (ok != true) {
                    probeResult = if (ok == null) "connect timed out after 30 s" else "connect failed after ${secs()}"
                    log.add(probeResult)
                    return@exclusive
                }
                log.add("connected and ready after ${secs()}")
                // Read once at once and once after the wait: does waiting change the answer?
                try {
                    log.add("read 10 at once -> ${ble.read(Protocol.OP_BLE_FEATURES).hex()}")
                } catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    log.add("read 10 at once failed: ${e.message}")
                }
                log.add("waiting 15 s to see whether the watch sends anything by itself")
                var heard = 0
                coroutineScope {
                    val listener = launch {
                        ble.notifications.collect { (uuid, v) ->
                            heard++
                            log.add("watch sent by itself on ${short(uuid)}: ${v.hex()}")
                        }
                    }
                    withTimeoutOrNull(15_000) { ble.state.first { it != ConnState.READY } }
                    listener.cancel()
                }
                if (ble.state.value != ConnState.READY) {
                    probeResult = "connected, but the watch hung up after ${secs()} (it sent $heard message(s) by itself)"
                    log.add(probeResult)
                    return@exclusive
                }
                val f = try {
                    ble.read(Protocol.OP_BLE_FEATURES)
                } catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    log.add("read 10 failed: ${e.message}")
                    null
                }
                f?.let { log.add("read 10 -> ${it.hex()} (watch reports ${Protocol.decodeBleFeatures(it).first})") }
                probeResult = "connected after the scan in ${secs()}; the watch sent $heard message(s) by itself; " +
                    "the read ${if (f != null) "worked" else "failed"}"
                log.add(probeResult)
            }
        } catch (e: CancellationException) {
            probeResult += " (interrupted when listening ended)"
            throw e
        } catch (e: Exception) {
            probeResult = "connection test failed: $e"
            log.add(probeResult)
        } finally {
            // Always close the GATT client (hard rule), also on timeout or failure.
            if (attempted) {
                ble.disconnect()
                log.add("disconnected (test only, nothing written)")
            }
            repo.testOwnsConnection = false
        }
    }

    private fun screenOn() = ctx.getSystemService(PowerManager::class.java).isInteractive

    private fun phoneState(): String {
        val pm = ctx.getSystemService(PowerManager::class.java)
        val bt = runCatching { ctx.getSystemService(BluetoothManager::class.java).adapter?.isEnabled }.getOrNull()
        val loc = runCatching {
            LocationManagerCompat.isLocationEnabled(ctx.getSystemService(LocationManager::class.java))
        }.getOrNull()
        val age = (SystemClock.elapsedRealtime() - TrrrstApp.startedAt) / 1000
        return "phone: screen ${if (pm.isInteractive) "on" else "off"}, doze ${pm.isDeviceIdleMode}, " +
            "battery saver ${pm.isPowerSaveMode}, " +
            "battery optimisation ${if (pm.isIgnoringBatteryOptimizations(ctx.packageName)) "off" else "on"} for the app, " +
            "Bluetooth ${onOff(bt)}, Location ${onOff(loc)}, app process started $age s ago, Android ${Build.VERSION.SDK_INT}"
    }

    private fun onOff(b: Boolean?) = when (b) { true -> "on"; false -> "off"; null -> "unknown" }
    private fun short(uuid: UUID) = uuid.toString().substring(4, 8)
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    /** Advert bytes come padded with zeros to a fixed length; drop the padding. */
    private fun advertHex(b: ByteArray) = b.copyOf(b.indexOfLast { it != 0.toByte() } + 1).hex()
}
