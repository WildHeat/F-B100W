package dev.trrrst.watch

import android.app.Application
import android.os.SystemClock
import dev.trrrst.watch.ble.BleManager
import dev.trrrst.watch.data.StepStore
import dev.trrrst.watch.data.SyncLogStore

/** Owns the parts that must outlive the screen: one Bluetooth connection, one step
 *  store, and the repository that coordinates them (PLAN 1.1). Background sync, the
 *  widget and the time-zone receiver reach them through here. */
class TrrrstApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startedAt = SystemClock.elapsedRealtime()
    }

    val ble by lazy { BleManager(this) }
    val steps by lazy { StepStore(this) }
    val syncLog by lazy { SyncLogStore(this) }
    val repository by lazy { WatchRepository(this, ble, steps, syncLog) }

    companion object {
        /** When this app process started (elapsedRealtime), for the listening test's log. */
        @Volatile var startedAt = 0L
            private set
    }
}
