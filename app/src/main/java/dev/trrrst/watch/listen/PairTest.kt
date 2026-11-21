package dev.trrrst.watch.listen

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import dev.trrrst.watch.TrrrstApp
import dev.trrrst.watch.WatchMatcher
import dev.trrrst.watch.ble.BleManager
import dev.trrrst.watch.ble.Protocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** Pairing test (PLAN Phase 3, step 1b; approved by the user 2026-11-18). Casio's
 *  manual says the watch only asks a *paired* phone for the time, and D does nothing
 *  on our unpaired watch. This pairs the phone with the connected (saved) watch using
 *  ordinary Bluetooth pairing (`createBond`). Nothing is written to the watch's
 *  settings. Before and after, it reads the watch's app info (0x22) and name (0x23)
 *  for the log, to see whether pairing changes anything stored on the watch.
 *
 *  Undo: unpair in the phone's Bluetooth settings, and clear the pairing on the watch
 *  (manual: hold C 1 s, hold A 2 s, press C twice, hold B 2 s until CLR stops flashing). */
object PairTest {
    const val NOT_CONNECTED = "Connect to your watch first: hold C on the watch and press Connect."

    fun bondName(state: Int?): String = when (state) {
        BluetoothDevice.BOND_BONDED -> "yes"
        BluetoothDevice.BOND_BONDING -> "pairing"
        BluetoothDevice.BOND_NONE -> "no"
        else -> "unknown"
    }

    /** Pair with the connected watch. Returns a plain-English result for the screen. */
    suspend fun run(ctx: Context): String {
        val app = ctx.applicationContext as TrrrstApp
        val repo = app.repository
        val ble = repo.ble
        val log = ListenLog(app)
        val dev = ble.lastDevice
        val saved = repo.savedWatch.value
        if (dev == null || ble.state.value != BleManager.ConnState.READY) return NOT_CONNECTED
        if (saved == null || !WatchMatcher.sameAddress(saved.address, dev.address))
            return "This isn't your saved watch. Connect to your own watch first."
        return repo.exclusive {
            log.add("=== pairing test with ${dev.address}; paired before: ${bondName(dev.bondState)}")
            readInfo(ble, log, "before")
            if (dev.bondState == BluetoothDevice.BOND_BONDED) {
                log.add("already paired; nothing to do")
                return@exclusive "This phone is already paired with the watch."
            }
            val done = CompletableDeferred<Int>()
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    @Suppress("DEPRECATION")
                    val d = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                    if (!WatchMatcher.sameAddress(d.address, dev.address)) return
                    val now = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                    val before = i.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR)
                    val reason = i.getIntExtra("android.bluetooth.device.extra.REASON", -1)
                    log.add("paired: ${bondName(before)} -> ${bondName(now)}" +
                        if (reason >= 0) " (Android reason $reason)" else "")
                    if (now == BluetoothDevice.BOND_BONDED || now == BluetoothDevice.BOND_NONE) done.complete(now)
                }
            }
            ContextCompat.registerReceiver(
                app, receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                ContextCompat.RECEIVER_EXPORTED,
            )
            try {
                val started = try { dev.createBond() } catch (e: SecurityException) { false }
                log.add("asked Android to pair: ${if (started) "started" else "refused"}")
                if (!started) return@exclusive "Android couldn't start pairing. Turn Bluetooth off and on, connect again and retry."
                val result = withTimeoutOrNull(60_000) { done.await() }
                log.add("connection after pairing: ${ble.state.value.name.lowercase()}")
                when (result) {
                    BluetoothDevice.BOND_BONDED -> {
                        if (ble.state.value == BleManager.ConnState.READY) readInfo(ble, log, "after")
                        "Paired with the watch. Now try Listen now and press D on the watch."
                    }
                    null -> "Pairing didn't finish within a minute. Connect again and retry."
                    else -> "Pairing didn't work. Connect again and retry."
                }
            } finally {
                runCatching { app.unregisterReceiver(receiver) }
                log.add("=== pairing test over")
            }
        }
    }

    private suspend fun readInfo(ble: BleManager, log: ListenLog, whenText: String) {
        for (op in listOf(Protocol.OP_APP_INFO, Protocol.OP_WATCH_NAME)) {
            val name = "%02x".format(op)
            try {
                log.add("$whenText: read $name -> ${ble.read(op).joinToString("") { "%02x".format(it) }}")
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                log.add("$whenText: read $name failed: ${e.message}")
            }
        }
    }
}
