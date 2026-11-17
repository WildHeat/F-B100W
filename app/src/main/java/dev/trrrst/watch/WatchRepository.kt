package dev.trrrst.watch

import dev.trrrst.watch.ble.BleManager
import dev.trrrst.watch.ble.ScanCheck
import dev.trrrst.watch.data.StepStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** App-scoped coordinator for the watch connection (PLAN 1.1). Lives as long as the
 *  app process, so it is shared by the screen and, later, background sync. */
class WatchRepository(val ble: BleManager, val steps: StepStore) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val operationMutex = Mutex()
    private val _busy = MutableStateFlow(false)
    /** True while a watch operation runs, whoever started it. */
    val busy: StateFlow<Boolean> = _busy

    init {
        // The only consumer of scan results. It lives here (not in a ViewModel) so a
        // reopened screen can't pick up the last found device and reconnect by itself.
        scope.launch {
            ble.found.collectLatest { dev ->
                if (dev == null) return@collectLatest
                try {
                    ble.connect(dev)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("Trrrst", "connect failed", e)
                    ble.disconnect()
                    ble.fail(ScanCheck.CONNECT_FAILED)
                }
            }
        }
    }

    /** Run one complete watch operation (possibly many reads/writes) on its own.
     *  Operations queue in order, so multi-step changes never interleave. */
    suspend fun <T> exclusive(block: suspend () -> T): T =
        operationMutex.withLock {
            _busy.value = true
            try {
                block()
            } finally {
                _busy.value = false
            }
        }
}
