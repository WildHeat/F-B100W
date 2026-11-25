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
import dev.trrrst.watch.ble.BleManager.ConnState
import dev.trrrst.watch.ble.Protocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
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
                // Plan the next window *after* this one, even if this run ended early
                // (e.g. Bluetooth off). Otherwise it restarts the same window every 5 s.
                ListenScheduler.schedule(this@ListenService, notBefore = end)
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

/** One listening window: a standing connection request to the saved watch, which
 *  Android's Bluetooth chip completes by itself the moment the watch advertises (PLAN
 *  Phase 3, Part 2b). One read-only connection per window. The scan was removed: with
 *  the screen off it never passed a single advert to the app on the Mi 8. */
private class ListenRun(private val ctx: Context, private val log: ListenLog) {
    private val repo = (ctx.applicationContext as TrrrstApp).repository
    private val ble = repo.ble
    private val clockFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private fun clock(ms: Long): String = clockFmt.format(Date(ms))
    private var probeResult = "the watch didn't call"
    /** Set once the one connection of this window has been made. */
    @Volatile private var claimed = false
    /** The standing connection request is waiting for the watch right now. */
    @Volatile private var standingWaiting = false

    suspend fun run(label: String, plannedStart: Long, end: Long) = coroutineScope {
        val now = System.currentTimeMillis()
        val saved = repo.savedWatch.value
        log.add("=== listening test: $label, listening until ${ListenScheduler.fmt(end)}")
        log.add("alarm planned for ${clock(plannedStart)}, came %.1f s late".format((now - plannedStart) / 1000.0))
        log.add(phoneState())
        log.add("saved watch: ${saved?.address ?: "none"}; " +
            "paired: ${saved?.let { PairTest.bondName(ble.bondState(it.address)) } ?: "-"}; " +
            "connection now: ${ble.state.value.name.lowercase()}")
        if (now >= end) {
            log.add("too late: the listening window is already over")
            return@coroutineScope
        }
        val dev = saved?.let { ble.remoteDevice(it.address) }
        if (dev == null) {
            log.add("no saved watch, nothing to wait for (connect once in the app first)")
            return@coroutineScope
        }
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
        val standing = launch { standingLoop(dev, end) }
        try {
            var lastBeat = System.currentTimeMillis()
            while (System.currentTimeMillis() < end && standing.isActive) {
                delay(1_000)
                val t = System.currentTimeMillis()
                if (t - lastBeat >= 60_000) {
                    lastBeat = t
                    log.add("still waiting: screen ${if (screenOn()) "on" else "off"}, " +
                        "Bluetooth ${onOff(bluetoothOn())}" + if (standingWaiting) ", standing request set" else "")
                }
            }
            standing.join()
        } finally {
            standing.cancel()
            stateLog.cancel()
            log.add("--- summary: $probeResult")
            log.add("=== listening test over")
        }
    }

    private fun bluetoothOn(): Boolean =
        runCatching { ctx.getSystemService(BluetoothManager::class.java).adapter?.isEnabled == true }.getOrDefault(false)

    private fun appIdle() = !repo.busy.value && ble.state.value in setOf(ConnState.DISCONNECTED, ConnState.ERROR)

    /** Keep a standing connection request open while Bluetooth is on and the app isn't
     *  using the watch. If the app connects, the request steps aside; it's never left
     *  open after the window (the GATT client is closed: hard rule). */
    private suspend fun standingLoop(dev: BluetoothDevice, end: Long) {
        var noted: StandingRequest.Step? = null
        while (true) {
            val step = StandingRequest.next(System.currentTimeMillis(), end, claimed, bluetoothOn(), appIdle())
            when (step) {
                StandingRequest.Step.STOP -> return
                StandingRequest.Step.WAIT_FOR_BLUETOOTH, StandingRequest.Step.WAIT_FOR_APP -> {
                    if (noted != step) {
                        log.add(if (step == StandingRequest.Step.WAIT_FOR_BLUETOOTH)
                            "Bluetooth is off: the watch can't reach the phone. Waiting for Bluetooth to come back on"
                        else "waiting until the app has finished with the watch")
                        noted = step
                    }
                    delay(StandingRequest.RECHECK_MS)
                    continue
                }
                StandingRequest.Step.SET -> noted = null
            }
            var generation = -1
            repo.testOwnsConnection = true
            standingWaiting = true
            log.add("standing connection request set for ${dev.address}")
            val ok = try {
                withTimeoutOrNull((end - System.currentTimeMillis()).coerceAtLeast(1)) {
                    ble.connect(dev, autoConnect = true) { generation = it }
                }
            } finally {
                standingWaiting = false
                // Cancelled (window over): close our client unless a newer connect owns it.
                if (!currentCoroutineContext().isActive) {
                    ble.disconnectIfStill(generation)
                    repo.testOwnsConnection = false
                }
            }
            when {
                ok == true -> {
                    claimed = true
                    log.add("connected by the standing request")
                    afterConnect(t0 = System.currentTimeMillis(), generation = generation)
                    return
                }
                ok == null -> {
                    ble.disconnectIfStill(generation)
                    repo.testOwnsConnection = false
                    log.add("standing request closed (listening window over)")
                    return
                }
                ble.connectGeneration != generation -> {
                    repo.testOwnsConnection = false
                    log.add("standing request stepped aside: the app connected to the watch")
                }
                else -> {
                    repo.testOwnsConnection = false
                    log.add("standing request ended without connecting (Android status ${ble.lastDisconnectStatus}); trying again")
                    ble.disconnectIfStill(generation)
                    delay(5_000)
                }
            }
        }
    }

    /** Connected: note anything the watch sends by itself, read the
     *  connection reason, hang up. Nothing is written to the watch (apart from the
     *  standard Bluetooth "send me notifications" switch that every connect sets). */
    private suspend fun afterConnect(t0: Long, generation: Int) {
        fun secs() = "%.1f s".format((System.currentTimeMillis() - t0) / 1000.0)
        try {
            repo.exclusive {
                if (ble.state.value != ConnState.READY) {
                    probeResult = "connected, but the watch had already hung up"
                    log.add(probeResult)
                    return@exclusive
                }
                // Read once at once and once after the wait: does waiting change the answer?
                try {
                    val f0 = ble.read(Protocol.OP_BLE_FEATURES)
                    log.add("read 10 at once -> ${f0.hex()} (reason: ${Protocol.connectReason(f0).label})")
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
                f?.let {
                    log.add("read 10 -> ${it.hex()} (watch reports ${Protocol.decodeBleFeatures(it).first}, " +
                        "reason: ${Protocol.connectReason(it).label})")
                }
                probeResult = "connected; the watch sent $heard message(s) by itself; " +
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
            // Always close the GATT client (hard rule), unless the app has since taken over.
            ble.disconnectIfStill(generation)
            log.add("disconnected (test only, nothing written)")
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
}
