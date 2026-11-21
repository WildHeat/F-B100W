package dev.trrrst.watch

import android.bluetooth.BluetoothDevice
import android.content.Context
import dev.trrrst.watch.ble.BleManager
import dev.trrrst.watch.ble.Protocol
import dev.trrrst.watch.ble.ScanCheck
import dev.trrrst.watch.ble.WATCH_NAME
import dev.trrrst.watch.data.StepStore
import dev.trrrst.watch.data.SyncLogStore
import dev.trrrst.watch.data.SyncRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Date
import java.util.TimeZone

/** App-scoped coordinator for the watch connection (PLAN 1.1). Lives as long as the
 *  app process, so it is shared by the screen and, later, background sync. */
class WatchRepository(
    context: Context, val ble: BleManager, val steps: StepStore, val syncLog: SyncLogStore,
) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("watch", Context.MODE_PRIVATE)

    val homeCities: List<City> by lazy { loadCities("cities_ht.json") }
    val worldCities: List<City> by lazy { loadCities("cities_wt.json") }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val operationMutex = Mutex()
    /** True while the listening test owns the connection (PLAN Phase 3, step 1). The
     *  screen then leaves it alone instead of loading the watch state. */
    @Volatile var testOwnsConnection = false

    private val _busy = MutableStateFlow(false)
    /** True while a watch operation runs, whoever started it. */
    val busy: StateFlow<Boolean> = _busy

    private val _lastSync = MutableStateFlow<SyncResult?>(null)
    /** Result of the most recent [sync] in this app session. */
    val lastSync: StateFlow<SyncResult?> = _lastSync

    private val _savedWatch = MutableStateFlow(loadSavedWatch())
    /** The remembered watch (PLAN Phase 2), or null before the first connect / after Forget. */
    val savedWatch: StateFlow<SavedWatch?> = _savedWatch

    private val _pendingWatch = MutableStateFlow<SavedWatch?>(null)
    /** A connected watch that isn't the saved one, waiting for the user to say whether
     *  it's theirs. Nothing is written to it meanwhile. */
    val pendingWatch: StateFlow<SavedWatch?> = _pendingWatch

    init {
        // The only consumer of scan results. It lives here (not in a ViewModel) so a
        // reopened screen can't pick up the last found device and reconnect by itself.
        scope.launch {
            ble.found.collectLatest { dev ->
                if (dev == null) return@collectLatest
                try {
                    // Every fresh connection to *our* watch syncs time + downloads steps
                    // (user decision, PLAN 1.2). A screen reopening on an open connection
                    // does not. The check must come first: sync writes to the watch.
                    if (ble.connect(dev) && isOurWatch(dev)) sync(SyncTrigger.MANUAL)
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

    // ---- the remembered watch (PLAN Phase 2)

    private fun loadSavedWatch(): SavedWatch? {
        val address = prefs.getString("savedAddress", null) ?: return null
        return SavedWatch(
            address, prefs.getString("savedName", null) ?: WATCH_NAME, prefs.getString("savedMac", null),
        )
    }

    private fun rememberWatch(w: SavedWatch) {
        prefs.edit()
            .putString("savedAddress", w.address)
            .putString("savedName", w.name)
            .putString("savedMac", w.mac)
            .apply()
        _savedWatch.value = w
    }

    /** Forget the saved watch: the next F-B100W that connects is saved instead. */
    fun forgetWatch() {
        prefs.edit().remove("savedAddress").remove("savedName").remove("savedMac").apply()
        _savedWatch.value = null
        android.util.Log.d("Trrrst", "saved watch forgotten")
    }

    /** Runs right after connecting, before anything is written to the watch. Reads the
     *  address the watch reports (a read only), then decides. Returns true if it's ours
     *  (saving it when none was saved); false means the user is being asked. */
    private suspend fun isOurWatch(dev: BluetoothDevice): Boolean = exclusive {
        val address = dev.address
        val mac = try {
            Protocol.decodeBleFeatures(ble.read(Protocol.OP_BLE_FEATURES)).first
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            android.util.Log.w("Trrrst", "couldn't read the watch's own address", e)
            null   // the decision uses the Bluetooth address; this is for the record
        }
        val name = try { dev.name } catch (e: SecurityException) { null } ?: WATCH_NAME
        val saved = _savedWatch.value
        val verdict = WatchMatcher.onConnected(saved, address)
        // Logged on every connect to track whether the address stays the same (PLAN 2).
        android.util.Log.d("Trrrst",
            "connected to $address, watch reports $mac, saved ${saved?.address ?: "none"} -> $verdict")
        val here = SavedWatch(address, name, mac)
        when (verdict) {
            WatchMatcher.Verdict.SAVE_AND_SYNC -> { rememberWatch(here); true }
            WatchMatcher.Verdict.SYNC -> { if (here != saved) rememberWatch(here); true }
            WatchMatcher.Verdict.ASK -> { _pendingWatch.value = here; false }
        }
    }

    /** The user said the connected (different) watch is theirs: save it, then sync if
     *  it's still connected. The watch may have hung up while the question was open. */
    fun useThisWatch() {
        val w = _pendingWatch.value ?: return
        _pendingWatch.value = null
        rememberWatch(w)
        android.util.Log.d("Trrrst", "user chose watch ${w.address}")
        val connected = ble.state.value == BleManager.ConnState.READY &&
            ble.connectedAddress?.let { WatchMatcher.sameAddress(it, w.address) } == true
        if (connected) scope.launch { sync(SyncTrigger.MANUAL) }
        else ble.showStatus(WatchMatcher.SAVED_RECONNECT)
    }

    /** The user said the connected watch isn't theirs: disconnect without writing. */
    fun rejectWatch() {
        val w = _pendingWatch.value ?: return
        _pendingWatch.value = null
        android.util.Log.d("Trrrst", "user rejected watch ${w.address}")
        ble.disconnect()
        ble.fail(WatchMatcher.NOT_YOUR_WATCH)
    }

    private fun loadCities(name: String): List<City> {
        val arr = org.json.JSONArray(app.assets.open(name).bufferedReader().readText())
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            City(
                o.getInt("no"), o.getString("name"),
                (o.getDouble("tz") * 60).toInt(), (o.getDouble("dst") * 60).toInt(), o.getInt("rule"),
                o.optString("iana").ifEmpty { null },
            )
        }
    }

    // ---- home city, remembered on the phone (the watch forgets it on disconnect)

    /** The city each sync sends: the one saved in the World tab, else the phone's zone. */
    fun homeCity(): City? = HomeCity.choose(
        prefs.getInt("homeCity", -1).takeIf { it >= 0 },
        TimeZone.getDefault().id, homeCities,
    )

    fun homeDstMode(): Int = prefs.getInt("homeDstMode", DstMode.AUTO)

    fun rememberHomeCity(city: City, dstMode: Int) {
        prefs.edit().putInt("homeCity", city.no).putInt("homeDstMode", dstMode).apply()
    }

    /** Home city + summer time, then the time: the sequence the watch needs before it
     *  accepts a time write (initial-sync style: 1D + both 1E, then 09). Run inside
     *  [exclusive]. Returns what was written, for the screen. */
    suspend fun writeHomeCityAndTime(city: City, dstMode: Int): HomeCityWrite {
        // The watch hands out its two 1E slots in turn; a third 1E read with no write in
        // between gets error ff811e (seen 2026-11-17, right after the connect refresh had
        // read both). A write resets it, so start with a time write: harmless (the watch
        // ignores it without a city) and matches the sequence that worked on the watch.
        ble.write(Protocol.encodeTime())
        val st = Protocol.DstState.decode(ble.read(Protocol.OP_DST_STATE))
        // Read both slots before writing, preserving live world-city parameters.
        ble.read(Protocol.OP_DST_SETTING) // current home slot, replaced below
        val world = Protocol.DstSetting.decode(ble.read(Protocol.OP_DST_SETTING))
        st.homeCity = city.no
        st.homeAuto = dstMode == DstMode.AUTO
        st.homeDstOn = HomeCity.dstOn(dstMode, TimeZone.getDefault().inDaylightTime(Date()))
        ble.write(st.encode())
        val home = Protocol.DstSetting(0, city.no, city.tzMin, city.dstMin, city.rule)
        ble.write(home.encode())
        ble.write(world.encode())
        ble.write(Protocol.encodeTime())
        return HomeCityWrite(Protocol.DstState.decode(ble.read(Protocol.OP_DST_STATE)), home, world)
    }

    /** Set the watch time (with the remembered home city, which the watch needs).
     *  Run inside [exclusive]. Returns false if no home city is known. */
    suspend fun setTime(): Boolean {
        val city = homeCity() ?: return false
        writeHomeCityAndTime(city, homeDstMode())
        return true
    }

    data class HomeCityWrite(
        val state: Protocol.DstState, val home: Protocol.DstSetting, val world: Protocol.DstSetting,
    )

    /** The shared sync routine (PLAN 1.2): sync the watch's time, download today's
     *  steps (committing them on the watch) and save them. Needs an open connection;
     *  connecting is up to the caller for now (Phase 2/3 add connecting to the saved
     *  watch). Never throws: failures come back as a plain-English [SyncResult]. */
    suspend fun sync(trigger: SyncTrigger): SyncResult {
        val result = if (ble.state.value != BleManager.ConnState.READY) {
            SyncResult(false, error = SyncMessages.NOT_CONNECTED)
        } else try {
            exclusive {
                // The watch ignores a bare time write, so the time goes with the home city.
                // If that part fails, still download the steps.
                val city = homeCity()
                val timeError = if (city == null) SyncMessages.NO_HOME_CITY else try {
                    writeHomeCityAndTime(city, homeDstMode())
                    null
                } catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    android.util.Log.e("Trrrst", "sync: setting the time failed", e)
                    if (ble.state.value != BleManager.ConnState.READY) throw e
                    SyncMessages.TIME_NOT_SET
                }
                val raw = ble.receive(Protocol.CAT_EXERCISE, commit = true)
                val todaySteps = if (raw.isEmpty()) null else {
                    val rec = Protocol.decodeStepRecord(raw)
                    steps.ingest(rec)
                    rec.todaySteps
                }
                SyncResult(timeError == null, steps = todaySteps, error = timeError)
            }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()   // real cancellation: don't report
            android.util.Log.e("Trrrst", "sync failed", e)
            SyncResult(false, error = SyncMessages.forFailure(
                e, stillConnected = ble.state.value == BleManager.ConnState.READY))
        }
        _lastSync.value = result
        syncLog.add(SyncRecord(System.currentTimeMillis(), trigger, result.ok, result.steps, result.error))
        return result
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
