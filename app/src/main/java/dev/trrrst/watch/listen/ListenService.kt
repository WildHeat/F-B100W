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
        val fastScan = intent?.getBooleanExtra(ListenScheduler.EXTRA_FAST_SCAN, false) ?: false
        // Keep the CPU awake for the window only, or timers stall while the screen is off.
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "trrrst:listen")
            .apply { setReferenceCounted(false); acquire((end - now).coerceAtLeast(0) + 120_000) }
        scope.launch {
            try {
                val limit = (end - System.currentTimeMillis()).coerceAtLeast(0) + 60_000
                val done = withTimeoutOrNull(limit) { ListenRun(this@ListenService, log).run(label, plannedStart, end, fastScan) }
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

/** One listening window: listen, log, try one read-only connection to the saved watch. */
private class ListenRun(private val ctx: Context, private val log: ListenLog) {
    private val repo = (ctx.applicationContext as TrrrstApp).repository
    private val ble = repo.ble
    private val clockFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private fun clock(ms: Long): String = clockFmt.format(Date(ms))
    private var probeResult = "no connection tried (the saved watch wasn't heard)"

    /** Set once a connection attempt has been made in this window (by either way of waiting). */
    @Volatile private var claimed = false
    /** The standing connection request is waiting for the watch right now. */
    @Volatile private var standingWaiting = false

    suspend fun run(label: String, plannedStart: Long, end: Long, fastScan: Boolean) = coroutineScope {
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
        val savedDev = saved?.let { ble.remoteDevice(it.address) }

        val tracker = AdvertTracker { clock(it) }
        var scanFallback: Job? = null
        var probe: Job? = null
        var nextProbeAt = 0L
        var heardWhileStanding = false
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

        // Way 1: scan in short bursts (5 s on, 3 s off), like izivkov's Smart Sync.
        val onResult: (android.bluetooth.le.ScanResult) -> Unit = { r ->
            val address = r.device.address
            val name = runCatching { r.device.name }.getOrNull() ?: r.scanRecord?.deviceName
            tracker.seen(
                System.currentTimeMillis(), address, name, r.rssi, r.isConnectable,
                r.scanRecord?.bytes?.let { advertHex(it) } ?: "",
            ).forEach(log::add)
            val t = System.currentTimeMillis()
            if (!claimed && saved != null && WatchMatcher.sameAddress(saved.address, address)) {
                if (standingWaiting) {
                    // Give the standing request a moment, so the log shows whether it works on its own.
                    if (!heardWhileStanding) {
                        heardWhileStanding = true
                        log.add("scan heard the watch; giving the standing request 2 s to connect first")
                    }
                    if (scanFallback?.isActive != true) scanFallback = launch {
                        delay(2_000)
                        // CONNECTING while the standing request waits = it's connecting right now.
                        val standingConnecting = standingWaiting && ble.state.value == ConnState.CONNECTING
                        if (!claimed && probe?.isActive != true && !standingConnecting) {
                            log.add("standing request didn't connect within 2 s; connecting from the scan")
                            probe = launch { if (!probeFromScan(r.device)) nextProbeAt = System.currentTimeMillis() + 5_000 }
                        }
                    }
                } else if (probe?.isActive != true && t >= nextProbeAt) {
                    // If the app was using the watch, try again on a later advert (at most every 5 s).
                    probe = launch { if (!probeFromScan(r.device)) nextProbeAt = System.currentTimeMillis() + 5_000 }
                }
            }
        }
        var lastScanProblem: String? = null
        val scanLoop = launch {
            while (System.currentTimeMillis() < end) {
                val stop = ble.listen(saved?.address, fast = fastScan, onResult = onResult, onFailed = { msg ->
                    if (msg != lastScanProblem) { log.add("couldn't listen: $msg"); lastScanProblem = msg }
                })
                if (stop != null) lastScanProblem = null
                try { delay(ScanBursts.ON_MS) } finally { stop?.invoke() }
                delay(ScanBursts.OFF_MS)
            }
        }
        log.add("listening two ways: scan in ${ScanBursts.ON_MS / 1000} s bursts with " +
            "${ScanBursts.OFF_MS / 1000} s pauses for name \"$WATCH_NAME\"" +
            (saved?.let { " or address ${it.address}" } ?: "") +
            (if (fastScan) " (fast scan)" else " (balanced scan, uses less battery)") +
            if (savedDev != null) ", plus a standing connection request to the saved watch" else "")

        // Way 2: a standing connection request; Android's Bluetooth chip connects by itself.
        val standing = launch { if (savedDev != null) standingLoop(savedDev, end) }

        try {
            var lastBeat = System.currentTimeMillis()
            while (System.currentTimeMillis() < end) {
                delay(1_000)
                val t = System.currentTimeMillis()
                tracker.tick(t).forEach(log::add)
                if (t - lastBeat >= 60_000) {
                    lastBeat = t
                    log.add("still listening: ${tracker.total} adverts so far, screen ${if (screenOn()) "on" else "off"}" +
                        if (standingWaiting) ", standing request waiting" else "")
                }
            }
        } finally {
            scanLoop.cancel()
            standing.cancel()
            scanFallback?.cancel()
            probe?.cancel()
            stateLog.cancel()
            tracker.finish()
            val bursts = tracker.summary()
            log.add("--- summary: ${tracker.total} adverts heard by the scan" +
                if (bursts.isEmpty()) "; the scan didn't hear the watch" else "")
            bursts.forEach { log.add("  $it") }
            log.add("  connection test: $probeResult")
            log.add("=== listening test over")
        }
    }

    private fun appIdle() = !repo.busy.value && ble.state.value in setOf(ConnState.DISCONNECTED, ConnState.ERROR)

    /** Keep a standing connection request open while the app isn't using the watch. If
     *  the app connects (or the scan does), the request steps aside; it's never left open
     *  after the window (the GATT client is closed: hard rule). */
    private suspend fun standingLoop(dev: BluetoothDevice, end: Long) {
        var waitingNoted = false
        while (!claimed && System.currentTimeMillis() < end) {
            if (!appIdle()) {
                if (!waitingNoted) { log.add("standing request: waiting until the app has finished with the watch"); waitingNoted = true }
                delay(5_000)
                continue
            }
            waitingNoted = false
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
                ok == true && !claimed -> {
                    claimed = true
                    log.add("connected by the standing request")
                    afterConnect(via = "the standing request", t0 = System.currentTimeMillis(), generation = generation)
                    return
                }
                ok == null -> {
                    ble.disconnectIfStill(generation)
                    repo.testOwnsConnection = false
                    log.add("standing request closed (listening window over)")
                    return
                }
                ble.connectGeneration != generation -> {
                    // A newer connect replaced ours: the scan's test connection, or the app.
                    if (!claimed) {
                        repo.testOwnsConnection = false
                        log.add("standing request stepped aside: the app connected to the watch")
                    }
                }
                else -> {
                    repo.testOwnsConnection = false
                    log.add("standing request ended without connecting (Android status ${ble.lastDisconnectStatus}); trying again in 5 s")
                    ble.disconnectIfStill(generation)
                    delay(5_000)
                }
            }
        }
    }

    /** Connect from a scan result. Returns false if it didn't try because the app was
     *  using the watch (the caller retries later). */
    private suspend fun probeFromScan(dev: BluetoothDevice): Boolean {
        if (claimed) return true
        val inUse = repo.busy.value || ble.state.value !in setOf(ConnState.DISCONNECTED, ConnState.ERROR)
        if (inUse) {
            val msg = "not tried yet: the app was using the watch (${ble.state.value.name.lowercase()})"
            if (msg != probeResult) log.add(msg)   // log once, not on every retry
            probeResult = msg
            return false
        }
        claimed = true
        var generation = -1
        try {
            repo.testOwnsConnection = true
            log.add("connecting to ${dev.address} from the scan (test: read only)")
            val t0 = System.currentTimeMillis()
            val ok = withTimeoutOrNull(30_000) { ble.connect(dev) { generation = it } }
            if (ok != true) {
                probeResult = if (ok == null) "connect from the scan timed out after 30 s"
                    else "connect from the scan failed after %.1f s".format((System.currentTimeMillis() - t0) / 1000.0)
                log.add(probeResult)
                ble.disconnectIfStill(generation)
                repo.testOwnsConnection = false
                return true
            }
            log.add("connected from the scan after %.1f s".format((System.currentTimeMillis() - t0) / 1000.0))
            afterConnect(via = "the scan", t0 = t0, generation = generation)
        } catch (e: CancellationException) {
            ble.disconnectIfStill(generation)
            repo.testOwnsConnection = false
            throw e
        }
        return true
    }

    /** Connected (by either way): note anything the watch sends by itself, read the
     *  connection reason, hang up. Nothing is written to the watch (apart from the
     *  standard Bluetooth "send me notifications" switch that every connect sets). */
    private suspend fun afterConnect(via: String, t0: Long, generation: Int) {
        fun secs() = "%.1f s".format((System.currentTimeMillis() - t0) / 1000.0)
        try {
            repo.exclusive {
                if (ble.state.value != ConnState.READY) {
                    probeResult = "connected via $via, but the watch had already hung up"
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
                    probeResult = "connected via $via, but the watch hung up after ${secs()} (it sent $heard message(s) by itself)"
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
                probeResult = "connected via $via; the watch sent $heard message(s) by itself; " +
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
    /** Advert bytes come padded with zeros to a fixed length; drop the padding. */
    private fun advertHex(b: ByteArray) = b.copyOf(b.indexOfLast { it != 0.toByte() } + 1).hex()
}
