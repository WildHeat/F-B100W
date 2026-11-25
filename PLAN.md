# Implementation Plan

Engineering plan for the features in [TRRRST-Watch-Improvements.md](TRRRST-Watch-Improvements.md).
That file covers *what* to build and why. This file covers *how*, based on the code as it stands at v0.4 (`versionCode 4`).

Status legend: `[ ]` not started · `[~]` in progress · `[x]` done (tested on the real watch)

**Scope:** personal fork. No upstream pull requests. F-Droid compatibility is nice to have, not required. **Fully offline (no INTERNET permission) is still a hard rule.**

## Decisions made

| Topic | Decision |
| --- | --- |
| Upstream | Personal use only. No PRs to `taxmeifyoucan/trrrst-watch`. |
| CSV import conflict | When a day exists in both, **keep the higher step count** (and its distance). |
| Background sync default | **Off** until proven on the Mi 8. User turns it on in Settings. |
| Widget tech | **Jetpack Glance** (supports Android 6+, so it works on the Mi 8 on Android 10). |
| Health Connect | **Dropped.** No other fitness app needs the steps. CSV export/import only. |
| Bonding / pairing | **Don't rely on it.** Remember the watch by address + name. Revisit only once connecting works. |
| Official-app captures | **Not possible** (the Casio app isn't compatible with the Mi 8). Auto-sync behaviour is worked out by experiment. |

---

## Where the code stands now (and what that means for the plan)

| Finding in the code | Consequence |
| --- | --- |
| **Reported:** the GitHub v0.4 APK crashes as soon as **Connect** is pressed. It has never worked on the Mi 8. The watch then shows a plain `Err` (no code) but otherwise works normally. The watch has never been connected to any phone or app. | **Nothing else can be tested until this is fixed.** Phase 0 below. |
| The manifest declares only `BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT` (Android 12+) and `ACCESS_FINE_LOCATION`. It is **missing `BLUETOOTH` and `BLUETOOTH_ADMIN` (with `maxSdkVersion="30"`)**, which Android 11 and lower require for `startScan()`, `getName()` and `connectGatt()`. | **Confirmed cause of the crash** (2026-11-17, see the crash record in Phase 0). The watch's `Err` is just its connection attempt timing out. Upstream was likely only tested on Android 12+. |
| `BleManager` and `StepStore` are created **inside `WatchViewModel`** (`WatchViewModel.kt:23-24`) | Both die with the Activity, so background sync, the widget and the time-zone receiver can't reach them. They need to move to app scope (Phase 1). |
| `BleManager.scan()` accepts **any** device named `CASIO F-B100W` (`BleManager.kt:50-80`) | "Remember your watch" switches this to the saved address. `connect()` already takes a `BluetoothDevice`, so the change is small. |
| Watch auto-sync time is already decoded (`Protocol.BleSettings.autoSyncHour/Minute/Enabled`) | Background sync can wake just before that time instead of listening all day. |
| No Bluetooth captures of the official app are available | The auto-sync reconnect sequence has to be worked out by experiment (or by capturing one, see Open questions). |
| Errors reach the UI as raw text (`"watch error 0xFF for 0x09"`, `"$label failed: ${e.message}"`) | "Clearer feedback" needs typed errors + a plain-English mapping. |
| `StepStore` merges by date in `filesDir/steps.json` | CSV import reuses merge-by-date with a "max steps" rule. Stats can be pure functions over `history()`. |
| Cities (`assets/cities_*.json`) have no IANA zone IDs | Automatic time zone needs a hand-made zone → city map. |
| Only `ProtocolTest` exists | New logic goes in plain Kotlin classes with JVM unit tests. |

### Android 10 constraint (target phone: Mi 8)

`CompanionDeviceService` presence events are **Android 12+ only**. The Mi 8 runs Android 10 (API 29), so background sync uses:
an exact `AlarmManager` alarm just before the watch's auto-sync time → short **foreground service** → connect to the saved watch → sync → stop.
The alarm is rescheduled after the phone restarts. A one-time MIUI guide covers Autostart and "No restrictions" battery saver.
Android 10 also requires **Location to be switched on** (not just the permission) for Bluetooth scans to find anything.

---

## Phase 0: Make connecting work on the Mi 8 (blocker)

Setup:
- [x] USB debugging on. `adb devices` lists the phone (`MI_8`, `dipper`) as authorised.
- [ ] **Install via USB** may also be needed (MIUI blocks `adb install` without it).

### Crash record (2026-11-17)

| | |
| --- | --- |
| App | GitHub release v0.4 (`versionCode 4`), signed release APK |
| Phone | Xiaomi Mi 8 (`dipper`), Android 10, MIUI Global V12.0.3.0.QEAMIXM |
| What the user did | Held **C** on the watch, pressed **Connect** in the app |
| What happened | App closed instantly. Watch showed `Err` (no code) shortly after. Watch otherwise unaffected. |
| Reproducible | Yes: every attempt. Crash reports at 13:45:53, 13:46:23 and 14:22:20. |
| Watch history | Never connected to any phone or app (including the official Casio app) |

Stack trace (top frames):

```
java.lang.SecurityException: Need BLUETOOTH permission: Neither user 10677 nor current process has android.permission.BLUETOOTH.
    at android.bluetooth.IBluetooth$Stub$Proxy.getState(IBluetooth.java:1878)
    at android.bluetooth.BluetoothAdapter.getLeState(BluetoothAdapter.java:1046)
    at android.bluetooth.BluetoothAdapter.getLeAccess(BluetoothAdapter.java:1061)
    at android.bluetooth.BluetoothAdapter.getBluetoothLeScanner(BluetoothAdapter.java:820)
    at dev.trrrst.watch.ble.BleManager.scan(BleManager.kt:51)
    at dev.trrrst.watch.WatchViewModel.scanConnect(WatchViewModel.kt:128)
    at dev.trrrst.watch.ui.WatchHomeKt$WatchHome...(WatchHome.kt:61)   ← Connect button
```

Permissions on the installed app (`dumpsys package`): `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` (ignored on Android 10), `ACCESS_FINE_LOCATION` + `ACCESS_COARSE_LOCATION` (granted). **No `BLUETOOTH` or `BLUETOOTH_ADMIN`.**

**Root cause:** the manifest only declares the Android 12+ Bluetooth permissions. On Android 11 and lower, `BLUETOOTH` (and `BLUETOOTH_ADMIN` for scanning/connecting) are required. The first Bluetooth call throws, nothing catches it, and the app dies **before any Bluetooth traffic**. The app never talked to the watch.

How to get the trace: MIUI kept it out of logcat. Use `adb shell dumpsys dropbox --print data_app_crash` (read-only).

Steps:
- [x] **Confirm the cause** (2026-11-17). Crash report (`adb shell dumpsys dropbox --print data_app_crash`; MIUI kept the trace out of logcat): `SecurityException: Need BLUETOOTH permission` at `BluetoothAdapter.getBluetoothLeScanner()` ← `BleManager.scan(BleManager.kt:51)` ← Connect button. The app crashes **before any Bluetooth traffic**, so it never talked to the watch. The watch's `Err` is just its own connection attempt timing out.
- [x] **Fix:** added `BLUETOOTH` + `BLUETOOTH_ADMIN` with `maxSdkVersion="30"` to `AndroidManifest.xml` (branch `fix/android10-bluetooth-permissions`, merged to `master` in PR #1). Debug build passes 7/7 tests. `aapt dump permissions` shows both, and no INTERNET.
- [x] **Uninstalled the GitHub v0.4 release** from the Mi 8 (2026-11-17) and installed the debug build instead.
- [x] **First connection worked** (2026-11-17, 15:23). All reads and the user's writes (time, settings, BLE settings, timer, profile, home city, alarms) succeeded.
- [x] **Bug: second connect fails.** Fixed and confirmed by the user (reconnect after the watch ends the session works).
  - **Symptom:** after the watch ends a session (`onClientConnectionState status=19`, peer terminated), the next Connect fails with `read-req write failed` / `drsp start failed` / `watch write failed: 09…`. The watch keeps flashing CNCT.
  - **Cause:** `BleManager` never called `gatt.close()` when the *watch* disconnected. Android revived the old client (`clientIf=14`) alongside the new one (`clientIf=13`), both ran MTU/discovery/notify setup on the same link, and they clobbered each other's operations. Each retry added another ghost client.
  - **Fix** (`BleManager.kt`): close the client on `STATE_DISCONNECTED`, call `closeGatt()` + drain queues at the start of every `connect()`, ignore callbacks from stale clients, and fail the in-flight write at once on disconnect.
  - **Re-test:** connect → let the watch disconnect → connect again, **without** resetting the watch.
- [x] **Bug: Auto time sync switch flips back off.** Fixed and confirmed by the user: the switch stays on and survives reconnects.
  - **Cause:** a fresh watch stores no auto-sync time (`FF FF`). Turning the switch on sent hour 127 / minute 255 (`…7fff03`). The watch rejected it and read back as disabled.
  - **Fix:** default to 08:00 when the watch has no valid time. `BleSettings.withAutoSync` refuses invalid times. Picking a new time while the switch is on now saves it. Test `freshWatchHasNoAutoSyncTime` added.
- [x] **Date order and Language have no effect on the F-B100W** (confirmed by the user). The app wrote `0x13` bytes 4/5 correctly, but the display never changes. Both rows were removed from Settings. `Basic.encode()` still preserves their bytes.
- **Auto time sync only sets the watch's side.** At the set time the watch tries to connect, but nothing on the phone answers yet. That's Phase 3 (background sync).
- **Address observation:** the watch kept `E5:33:8D:36:F8:03` across reconnects, but had `D5:33:BC:22:F8:03` after the user reset it. So it's stable between connections but changes on a watch reset. This feeds into Phase 2.
- [x] **Safety net:** catch failures in scan/connect (`SecurityException`, Bluetooth off, adapter missing) so they show a message and disconnect instead of crashing. Confirmed by the user (2026-11-17): Bluetooth-off and Location-off messages show, no crash.
  - `BleManager.scan()` checks first (`ScanCheck`, unit-tested in `ScanCheckTest`), and catches `SecurityException`/`IllegalStateException` around `bluetoothLeScanner`, `startScan` and `stopScan`. Failures set `ConnState.ERROR` with a plain-English status line.
  - `BleManager.connect()` catches a failing `connectGatt`. `closeGatt()` no longer throws if Bluetooth was just switched off.
  - `WatchViewModel` wraps the automatic connect (which runs outside `op()`): on failure it logs, resets and shows "Couldn't connect to the watch…".
  - **Test:** press Connect with Bluetooth off, then with Location off. The app should stay open and say what to do. Then a normal connect should still work.
- [x] **Android 10 switch check:** before scanning, check that Bluetooth and Location are actually switched on (Location only on Android ≤ 11), with a clear message if not. Confirmed together with the safety net.
- [x] **First real connection:** all reads decode sensibly (2026-11-17).
- [x] Writes tried one at a time: Sync time, settings, BLE settings, timer, profile, home city, alarms all confirmed by the user.
- [x] **Step download (commit):** the home screen's **Download** button (`fetchSteps(peek=false)`) consumes the hourly data on the watch. Confirmed by the user (2026-11-17): downloads as expected, no errors in the log.

**Phase 0 done (2026-11-17).** Done when: Connect works repeatedly on the Mi 8 with no crash, the watch never shows `Err`, and Sync time + step download work.

## Phase 1: Groundwork (no visible features)

Brief, pitfalls and test steps: [HANDOFF-phase1.md](HANDOFF-phase1.md). Suggested order: 1.3 → 1.1 → 1.2 → 1.4.

**Phase 1 done (2026-11-17).** All four items confirmed on the Mi 8.

- [x] **1.1 App-scoped core.** `TrrrstApp : Application` owns one `BleManager` and one `StepStore`. A thin `WatchRepository` holds the shared sync logic.
  - Branch `feat/app-scoped-core`. `WatchRepository` is the only collector of `ble.found` (so a new ViewModel can't auto-reconnect) and owns the operation mutex + `busy` (shared with background sync later). `WatchViewModel` calls `refreshAll()` whenever it sees `ConnState` become `READY`, including a new ViewModel on an open connection. `BleManager` unchanged.
  - Confirmed on the Mi 8 (2026-11-17): connect → watch ended the session (status=19) → reconnect worked with one GATT client each time. Time sync, alarms and settings writes worked, and rotating the phone didn't reconnect or lose data. No errors or crashes in the log.
- [x] **1.2 Shared sync routine.** "connect → sync time → download steps → save → record result", used by both the UI and background sync.
  - **Decision (user, 2026-11-17):** on every fresh connection, sync the time and download steps automatically (commit, not peek).
  - Branch `feat/sync-routine`. `WatchRepository.sync(trigger)`: needs an open connection → time write → step download (commit) → `StepStore.ingest` → `SyncResult` (never throws; plain-English errors from `SyncMessages`, tested in `SyncMessagesTest`). Called after each successful `connect()`; a screen reopening on an open connection only re-reads. `StepStore.changes` lets the screen reload steps saved elsewhere. The 1.4 record is still to do.
  - Also fixed: since 1.1, a watch timeout inside `op()` was treated as cancellation and silently dropped; it's logged and shown again now.
  - **Watch finding (2026-11-17, read logging):** the watch **forgets its home and world city on every disconnect** (`1D` reads back `fefffe…`, both `1E` slots `feff000000`), and **ignores a bare time write (`09`)** while no home city is set. Writing `1D` + both `1E`, then `09` (the Save-home-city sequence) does set the time, and reads back correctly until disconnect.
  - **Fix:** every sync sends home city + summer time, then the time (`WatchRepository.writeHomeCityAndTime`, shared with Save home city). The city is remembered on the phone (prefs `watch`): the one last saved in the World tab, else the city whose `iana` matches the phone's zone (`HomeCity.choose`, tested). In auto mode the summer-time flag follows the phone. No city known → steps still download, and the user is told to pick a city. The World tab shows the remembered city when the watch reports none.
  - **Second finding (2026-11-17):** the watch hands out the two `1E` slots in turn. A third `1E` read with no write in between returns error `ff811e`. That broke the first version of the fix, because the connect refresh had already read both slots. Both successful saves had a time write between the reads, so `writeHomeCityAndTime` now starts with a (harmless) time write. If the time part still fails, the steps are downloaded anyway. The **Sync time** button uses the same sequence.
  - **Confirmed on the Mi 8 (2026-11-17, 21:34):** on connect the watch had no city; the sync sent time → London/auto → time, and the watch read back London and took the time. Steps were downloaded with no errors. Sync time (21:34:59, 21:35:07) worked the same way. The user confirmed the watch time was corrected.
  - `BleManager.read()` now also logs each watch answer (`TrrrstBLE`, `read xx -> …`). That's the log only, never the UI. Keep it for debugging.
  - Open: whether the watch switches to summer time by itself in March (it forgets the city, so probably not; the daily sync corrects it). The world city isn't remembered yet.
- [x] **1.3 No-INTERNET guard.** A build check that fails if `INTERNET` / `ACCESS_NETWORK_STATE` shows up in the merged manifest (catches libraries adding it).
  - Task `check<Variant>NoNetworkPermissions` in `app/build.gradle.kts` reads `SingleArtifact.MERGED_MANIFEST` and runs before `assemble<Variant>` and `check`, so CI's `assembleDebug` runs it too. Branch `build/no-internet-guard`.
  - Verified 2026-11-17: adding INTERNET to the manifest fails the build, and removing it passes again. Build-only, so no on-device test needed.
- [x] **1.4 Sync log.** `SyncRecord(time, trigger, ok, steps?, error?)`, last 10 kept. Used by background sync, the widget and the sync history list.
  - Branch `feat/sync-log`. `data/SyncLog.kt`: pure `SyncLog` (newest first, trim to 10, JSON round-trip, never throws on bad data; `SyncLogTest`, 6 tests) + `SyncLogStore` (SharedPreferences `watch`/`syncLog`, `records` StateFlow). `WatchRepository.sync()` records every attempt. Errors are the `SyncMessages` texts. Not shown in the UI yet.
  - Test-only dependency `org.json:json` (Android's org.json is a stub in JVM tests). Not in the app's runtime classpath.
  - Confirmed on the Mi 8 (2026-11-17, 21:49): `shared_prefs/watch.xml` holds `[{"time":1794952161090,"trigger":"MANUAL","ok":true,"steps":1058}]` after one connect.
  - **Watch observation, same session:** this time the watch still had London when it reconnected (`1D` read `…e776…`). In every earlier test where it had forgotten, the time had been **changed by hand on the watch** in between. So a manual time change probably clears the city, not the disconnect itself. Not proven. Either way, re-sending the city on every sync is harmless and stays.

## Phase 2: Remember your watch

Brief, pitfalls and test steps: [HANDOFF-phase2.md](HANDOFF-phase2.md). Known: the BLE address equals the MAC the watch reports in `OP_BLE_FEATURES`. It stays the same across reconnects but changes after a watch reset.

Branch `feat/remember-watch`, merged in PR #9 (2026-11-17). 37/37 tests, no INTERNET.

**Tested on the Mi 8 (2026-11-17, 22:18–22:25, log + prefs):** first connect saved `D5:33:BC:22:F8:03` and synced. Reconnect → `SYNC` without asking. Forget → saved again by name. The user then **reset the watch**: its address became `E5:33:5A:41:F8:03`. The scan waited 5 s for the saved one, then connected → `ASK` with **no writes** before the answer → *Use this watch* → synced (London + time, 0 steps after reset), and prefs now hold the new address. One GATT client throughout, no crash. In all 4 connects the reported MAC equalled the BLE address. Connecting after the 5 s wait took ~12 s (normally ~1.5 s), likely because the wait used part of the advertising window. **Still untested: Cancel in the dialog, and Connect with Bluetooth off.**

**Decision (user, 2026-11-17):** when a *different* F-B100W connects, **ask** ("This isn't your saved watch… Use this watch / Cancel"). Nothing is written to it until the user picks Use this watch.

- [x] After the first successful connect, save the watch's address and name. Prefs `watch`: `savedAddress`, `savedName`, `savedMac` (`WatchRepository.isOurWatch`).
- [x] **Find out if the address is stable:** stable across reconnects, changes on reset, and always equal to the MAC in `OP_BLE_FEATURES` (2026-11-17). The log line stays: every connect logs `Trrrst: connected to <address>, watch reports <mac>, saved <address> -> <verdict>`. Check this in the logs over a few days.
  - Implemented: **one scan by name** that prefers the saved address. The saved watch is taken at once. Another F-B100W is only used if the saved one hasn't shown up 5 s later (`WatchMatcher.PREFER_SAVED_MS`). Not two scans in a row, because the watch only advertises briefly after C. No `getRemoteDevice` direct connect: the watch has to be advertising anyway.
  - **Before the sync** (which writes), `isOurWatch` reads `OP_BLE_FEATURES` (read only) inside `exclusive {}` and decides. Different address → `pendingWatch` → dialog. Use this watch → save, then sync if still connected (else "Saved… hold C and press Connect"). Cancel / back → disconnect with "Not connected: this isn't your saved watch…".
- [x] **No bonding** (`createBond`). `BleManager.pair()` is still unused.
- [x] Settings: show the remembered watch + **Forget this watch** (with a confirmation). Visible without a connection.
- [x] Unit-test the "is this my watch?" decision (`WatchMatcher`, `WatchMatcherTest`, 8 tests). Build-only, no device needed.

## Phase 3: Automatic background sync

Brief, pitfalls and test steps: [HANDOFF-phase3.md](HANDOFF-phase3.md). Background sync must only sync the **saved** watch and never ask (no user to answer).

- [ ] `SyncService` foreground service (type `connectedDevice`) with a quiet notification channel.
- [ ] Permissions: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`, `SCHEDULE_EXACT_ALARM`. **No INTERNET.**
- [ ] `SyncScheduler`: exact alarm 1 min before the watch's auto-sync time. Rescheduled after each run, after reboot, and when auto-sync settings change.
- [ ] Service: connect to the saved watch → shared sync routine → notification "Watch synced at 08:00 · 6,412 steps" → stop. Hard timeout.
- [~] **No captures possible, so experiment:** first a logging-only version that records what the watch does when it reconnects at its auto-sync time. Only then add time + step syncing.
  - Built on branch `feat/background-sync-experiment` (2026-11-18), approved by the user incl. **one read-only test connection** per window. 53/53 tests, no INTERNET. **Not yet tested on the Mi 8.**
  - **Casio's manual** ([Linking with a Phone](https://support.casio.com/global/en/wat/manual/3589_en/UQIDSYrbwikdka.html), read 2026-11-18): the watch auto-corrects its time **4× a day at around 00:30, 06:30, 12:30, 18:30**, connecting by itself and hanging up when done. It only states an on/off switch (the aircraft/medical procedure), no settable time, so the meaning of the `0x11` "auto-sync time" bytes is unconfirmed. Auto correction **pauses for 24 h** after a manual time change on the watch (or a home/world swap). It needs "its paired phone" with the Casio app running (we don't pair, so the watch may never call us). **D** in Timekeeping Mode triggers an immediate correction (`TIME` flashes, then `OK` or `ERR`).
  - Settings → *Background test* switch (off by default). Listens from **10 min before to 20 min after** each of the manual's four times, plus the watch's own auto-sync time if it's on (prefs `watch`: `autoSyncOn`, `autoSyncHour`, `autoSyncMinute`; learned whenever the app reads `0x11`). Overlapping windows merge (`listen/ListenSchedule`, tested). Exact alarm (`setExactAndAllowWhileIdle`) → `ListenService` (foreground, type `connectedDevice`, quiet notification, wake lock for the window only) → *filtered* scan (name `CASIO F-B100W` or the saved address; `BleManager.listen`, which never touches `found`/`state`). **Listen now for 5 minutes** button for testing with **D**.
  - Logs (`AdvertTracker`, tested): first advert per address with name/RSSI/connectable/raw advert bytes, changes, burst lengths, a heartbeat every minute, phone state (screen, doze, battery saver, Bluetooth, Location, process age, alarm lateness).
  - First time the **saved** watch is heard (and the app isn't using the watch): connect inside `exclusive {}`, log anything the watch sends by itself for 15 s, read `0x10` only, disconnect (GATT always closed). Nothing written.
  - Log file: `filesDir/listen.log` (cap 200 KB), read with `run-as dev.trrrst.watch cat files/listen.log`; also logcat tag `TrrrstListen`. User said private/public doesn't matter for the test; **a real feature must keep logs private**.
  - **Results 2026-11-18 (02:20–02:42, unpaired watch):** holding **C** with only the listener running: the listener heard the watch (name + saved address, advert `02010603030418020a00 1109 "CASIO F-B100W"`, service UUID 0x1804) and connected in ~1.2 s. The watch sends **nothing by itself** after connecting. After the test hung up without syncing, the watch advertised ~20 s more, then showed `ERR`. **D does nothing at all** (no `TIME`, no advert), with Auto time sync off or on, so the time-sync request seems to need a paired phone (manual: "can connect with a phone that is paired with it"). The `0x10` answer's last bytes differed (`…26000000`/`…1e000000` when the app read at once, `…4c000400`/`…4e000400` when the test read after 15 s idle); cause unknown, so the test now reads at once and after the wait.
  - **Step 1b, pairing test (user approved 2026-11-18):** Settings → *Pair with this watch (test)* (`listen/PairTest`): while connected to the saved watch, reads `0x22`/`0x23` (log only), `createBond()`, logs bond-state changes, reads them again. Then test D with *Listen now*. If D still does nothing, the watch probably also needs Casio's own app registration (no captures), and background sync would stay "hold C". If the watch then calls but the scan hears nothing, it may use directed advertising: try a background `connectGatt(autoConnect = true)`.
  - **18–20 Nov:** every scheduled window was missed. The alarms arrived 1–16 h late, mostly only when the user opened the app (MIUI: no Autostart, battery optimisation on). So there's no data yet on whether the watch calls at its times. D still did nothing after bonding. App info `0x22` read `22ffffffffffffffffffff00` before and after bonding.
  - **Research (2026-11-21):** [izivkov/gshock_api](https://github.com/izivkov/gshock_api) (lists the F-B100W) treats app info `22 FF×10 00` as "watch reset / no app". Its Kotlin twin says this is "needed to re-enable button D… matching the official app". It answers with `22 34 88 F4 E5 D5 AF C8 29 E0 6D 02` (`app_info_io.py`; the Kotlin version uses a different value, `22 95 00…`). Connection reason = byte 8 of the `0x10` answer: 0 reset, 1 C, 2 find phone, 3 auto, 4 D (gshock_api `button_pressed_io.py`, Gadgetbridge `CasioConstants`). Neither gshock_api, GShockTimeServer nor Gadgetbridge bonds.
  - [x] **Part 1, register the app** (branch `feat/app-info`, user-approved 2026-11-21): `Protocol.appInfoNeedsRegister` / `APP_INFO_REGISTER` / `connectReason` (tested in `ProtocolTest`). `WatchRepository.registerAppIfNeeded()` runs first in `sync()`: read `0x22`, write only on the exact unregistered value, read back, log to `listen.log`. The reason is logged on every connect. 57/57 tests.
    - **Confirmed on the watch (2026-11-21 18:27):** the first connect read `22ffff…ff00` (reason byte 0 = reset), wrote `223488f4…6d02`, and it read back the same. The next connect (18:28) gave **reason 1 = C button** (byte 8 `00`→`01`), and "already set, nothing written". **D now flashes `TIME`** (seen by the user), so the app info was the missing piece. The D connection itself isn't in the log yet (the test's one connection per window was used up while the app was connecting). Fixed: the test connection retries later, and a window that ends early (e.g. Bluetooth off, which looped every 5 s at 18:26) no longer restarts itself.
    - **D confirmed (2026-11-21 18:36):** D → the watch advertised → the test connected in 0.9 s → `0x10` = `…7f04…`, **reason 4 = D button**. The watch sent nothing and **hung up by itself after ~7 s** (status 19); `TIME` then just disappears, no `OK`/`ERR`. So **the time must be sent within a few seconds** of a watch-initiated connection (the normal sync writes it ~1 s after ready). Still connected via a bonded phone, so "pairing not needed" is unproven.
  - [x] **Part 2, reliable wake-ups** (approved; branch `feat/reliable-wakeup`, built and **confirmed on the Mi 8 2026-11-21**). 66/66 tests, no INTERNET.
    - `ListenScheduler.setAlarm`: `setAlarmClock(AlarmClockInfo(at, open MainActivity))`; on API 31+ without `canScheduleExactAlarms()` (or a `SecurityException`) it falls back to `setAndAllowWhileIdle` and logs it. Shows a small alarm icon (user accepted).
    - `BootReceiver` (exported, `BOOT_COMPLETED` + `MY_PACKAGE_REPLACED`, new permission `RECEIVE_BOOT_COMPLETED`, user-approved): `schedule()` and logs "re-armed after phone restart/app update: next listening …". On MIUI it needs Autostart.
    - Settings → Background test → *Phone setup* box: battery optimisation status (`isIgnoringBatteryOptimizations`), exact-alarm status, numbered advice (`PhoneSetup`, pure, tested), buttons *Autostart* (MIUI `AutoStartManagementActivity`, falls back to app info), *App info*, *Battery optimisation* (`ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`; no `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`). Re-checked on resume. Autostart can't be read by the app.
    - *Test the wake-up*: `scheduleWakeTest`, own `PendingIntent` request code 1 (daily = 0), starts in 3 min, listens 5 min (`ListenSchedule.wakeTest`, tested), fast scan. Works with the test switch on or off.
    - Scan mode: `BleManager.listen(fast)`. Scheduled windows use `SCAN_MODE_BALANCED`, *Listen now* and the wake-up test `LOW_LATENCY` (intent extra `fastScan`). Logged with "listening for name …".
    - **Confirmed on the Mi 8 (2026-11-21):** after the setup box (Autostart on, No restrictions, battery optimisation off, locked in Recents), the wake-up test with the app swiped away and the screen off fired at 19:05:53, **0.7 s late** (process started 0 s before), vs 1–16 h late on 18–20 Nov. After a phone restart (≈19:15:55), `BootReceiver` logged "re-armed after phone restart: next listening 00:20 to 00:50" at 19:17:17 (MIUI delays boot broadcasts ~80 s), and `dumpsys alarm` showed the 00:20 alarm with a `showIntent` (alarm-clock). Not seen: the "re-armed after app update" line after the 18:59 install, probably because Autostart was still off then; harmless, since opening the app re-arms too.
  - [ ] **Part 3:** overnight check of on-time wake-ups, watch calls with reason "automatic", and battery use.
    - **Night 1 (21–22 Nov), balanced scan:** all five windows (00:20, 06:20, 07:50, 12:20, 18:20) started 0.2–1.3 s late with the screen mostly off; a restart at ~15:05 re-armed (15:06:37). **The watch was never heard** (0 adverts in every window). No manual time change on the watch for days. Battery: ~5 mAh for the 18:20 window (~0.5% of that discharge). Same evening, *Listen now* + D with the **phone locked** heard and connected in 0.9 s (reason 4), so a locked screen isn't the problem.
    - **Lead (2026-11-22):** gshock_api `time_adjustment_io.py` treats `0x11` byte 12 as a whole flag (`00` = time adjustment **on**, `80` = off) and byte 13 as "minutes after hour". Our `BleSettings` (inherited from upstream, never confirmed by a capture) reads byte 12 as hour + bit 7 = off. The watch stores `08 00` (app: "on at 08:00"), which gshock_api would read as **off**. Casio's times all end in :30, and our upstream fixture `…001e03` stores `00 1E`. Setting *Auto time sync* to **00:30** in the app writes exactly `00 1E` (an existing write, no code change).
    - **Night 2 (22–23 Nov), planned:** daily windows temporarily use the **fast** scan (`ListenScheduler.DAILY_FAST_SCAN = true`, set back afterwards), and the user sets *Auto time sync* to 00:30. If the watch calls, fix the meaning of the auto-sync setting in `Protocol`/UI (with a test); if not, both the scan and this setting are ruled out.
    - **Night 2 result (23 Nov 00:30): the 00:30 setting works.** The user saw `TIME` at ~00:30:30, then `Err` (nobody connected). logcat (`bt_stack`) shows the phone's chip **received** the watch's adverts at 00:30:30.69, but the app's scan (started at 00:20 with the screen off, fast mode) got **none**. A D press at 00:36 (phone locked, same scan) gave ~200 adverts in 7 s at the chip, 0 in the app; the watch showed `TIME` ~10 s, then `Err`. Earlier the same evening a scan **started with the screen on** heard D at once. So a long scan started while the screen is off doesn't deliver on this phone (cause not pinned down).
    - **Smart Sync (izivkov/GShockAPI, read 2026-11-23):** on Android < 12 it scans in a loop, 5 s on / 3 s off, `LOW_LATENCY` + `MATCH_MODE_AGGRESSIVE`; on 12+ CompanionDevice presence + a PendingIntent scan. On "device appeared" it connects with `autoConnect = true` (20 s timeout, 2 tries), reads app info and the button/reason (3 s each), then sets the time for an auto-time connection. Its comments: the watch calls "around 00:30, 06:30, 12:30 and 18:30" and disconnects after ~20 s. Gadgetbridge always connects from the phone with `autoConnect = false` ("connectGatt with true doesn't really work").
  - [ ] **Part 2b, two ways of waiting** (user-approved 2026-11-23, branch `feat/reliable-wakeup`, **built, waiting for the device test**). `ListenRun`: (1) scan in bursts (`ScanBursts`, 5 s on / 3 s off, ≤ 4 starts per 30 s, tested; `MATCH_MODE_AGGRESSIVE`); (2) a **standing connection request** `BleManager.connect(dev, autoConnect = true)` while the app is idle (state stays `DISCONNECTED` while it waits, so the screen and the app's Connect aren't affected; any newer `connect()` resumes it with false; `connectGeneration` + `disconnectIfStill()` so a late cleanup never closes someone else's connection). If the scan hears the watch while the request waits, it gives it 2 s, then connects from the scan. The log says which way connected. Still read-only (log only).
    - **Device test 2026-11-23 00:59 (wake-up test, window started with the screen off):** D pressed at 00:59:27 → the standing request, set at 00:59:30.15, **connected at 00:59:30.39 (0.24 s)**, reason 4 = D; the watch hung up after 6.4 s (no time sent). A second D at 01:00:02–10: the chip received ~170 adverts, **the burst scan 0** (one connection per window, so nothing connected → `Err`). So on the Mi 8 with the screen off, **only the standing request works**; the scan (bursts or not) gets nothing. Next: confirm with the watch's 06:30 auto call (reason 3), then drop the scan (or keep it only as a log) and decide "Phase 3 proper".
    - **Confirmed 2026-11-23:** the standing request caught the watch's automatic calls at **12:30:30** and **18:30:31** (reason 3 = automatic sync, connected < 1 s, the watch hung up after 7.0 / 6.4 s with no time sent). The 06:20 window missed because the phone's Bluetooth was off (the request was set anyway and sat idle). The scan heard 0 adverts in every window, even with the screen on. Battery: ~10 mAh in 19 h (0.26% of the phone's use), ~3 mAh per window.
  - [x] **Cleanup** (user-approved 2026-11-23, branch `feat/listen-cleanup`, **confirmed on the Mi 8 2026-11-25**, merged in PR #15):
    - Scan removed (`BleManager.listen`, `ScanBursts`, `AdvertTracker` + tests, `fastScan` extra, `DAILY_FAST_SCAN`). Each window only sets the standing request and ends after its one connection. No saved watch → "nothing to wait for".
    - Bluetooth off at a window: `StandingRequest.next()` (pure, tested) waits, re-checking every 10 s, and sets the request once Bluetooth is back. Phone setup box shows Bluetooth on/off.
    - *Auto time sync* is a plain switch. `0x11` byte 12 is a whole-byte flag (`00` on, `80` off, per gshock_api); on writes `00 1E`, off `80 1E` (only bytes 12/13 change; tests use the real values from the user's watch). The old `08 00` now reads as **off**. Listening windows are only the four :30 times; prefs `autoSyncHour/Minute` are removed. Settings warns when the test is on but the watch's auto time sync is off.
    - **Device test so far (23–25 Nov):** switch confirmed (`off` then `on` logged 23 Nov 22:57; the watch kept calling). Bluetooth-off waiting confirmed (23 Nov 23:01, 24 Nov 12:20, 25 Nov 06:20). Standing request caught 00:30, 06:30 (24 Nov), 00:30 and 12:30 (25 Nov); each time the watch hung up after about 7 s, sending nothing. **Bluetooth back on, then D: confirmed 25 Nov** (wake-up test 22:40, Bluetooth off → waiting; Bluetooth on → request set 22:42:43; D at 22:43:02 → connected, reason D button; watch hung up after 6.5 s).
    - **Bug found 25 Nov, fixed:** opening the app at 12:42 restarted the 12:30 window (already ended by the 12:30 call) until 12:50, so the wake-up test at 12:46 was ignored as "already listening". Fix: the end of each finished window is saved (prefs `listenDoneUntil`) and `ListenSchedule.planFrom()` (pure, tested) never plans inside it; a saved end more than a day ahead is ignored (clock set back). Installed 25 Nov 22:36; prefs show `listenDoneUntil` = 22:45:13 (test end) and the next window 00:20.
    - The watch didn't call at 18:30 on 24 or 25 Nov (request set, Bluetooth on); it did on 23 Nov. Watch this before acting on it.
    - Missing "re-armed after app update" line explained (25 Nov logcat): Android Studio ▶ Run force-stops the app right after installing (`Force stopping dev.trrrst.watch … from process`), and a force-stopped app gets no broadcasts, so `MY_PACKAGE_REPLACED` never reaches `BootReceiver`. Harmless: Studio then opens the app, which re-arms. A normal update (no force-stop) should still log it; not verified.
  - [ ] **Phase 3 proper: set the time when the watch calls** (planned 2026-11-25, **waiting for the user's approval and two choices**; branch `feat/background-time-sync`).
    - When the standing request connects to the saved watch (any reason: 3 automatic, 4 D, 1 C), read `0x10` for the log, then run `WatchRepository.sync(SyncTrigger.BACKGROUND)`: app-info check, home city + summer time + time (`writeHomeCityAndTime`). **No new kinds of writes**: the same ones every C connect has done since Phase 1. Never asks; no home city → nothing written, logged.
    - Deadline: the watch hangs up ~7 s after connecting. Estimated time written ~2–3 s after connect; log ms per step to see the real margin.
    - Keep `testOwnsConnection` set while it runs, so the screen's `refreshAll()` can't add `1E` reads (third-read error). One connection per window, as now. Result goes into the sync log as *background*.
    - **Choice 1, steps in the background:** A time only; **B time + non-clearing download (`commit = false`), timed, recommended**; C time + the normal clearing download (risk: the watch hangs up between clearing and our save, losing that data).
    - **Choice 2, telling the user:** **A log only for now, recommended** (notification comes with "make it the real feature"); B a quiet notification per call ("Watch time set at 06:30" / failure with a next step).
    - Unit tests for the new decision logic (which connections sync, what's logged).
    - Device test: D with the phone locked (wake-up test) → the watch shows `TIME` then **OK**, log shows "time written after X s"; overnight 00:30/06:30/12:30 calls set the time; compare the watch's seconds with the phone.
    - Later, separate branches: (1) clearing step download in the background if B shows it fits; (2) the real feature: Settings text, private log, notification, test UI tidied away; (3) pairing test without the bond (remove `PairTest` if the watch still calls); (4) watch whether the 18:30 skips continue once the watch actually gets its time.
  - New permissions: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE` (14+), `POST_NOTIFICATIONS` (13+), `WAKE_LOCK`, and from Part 2 `RECEIVE_BOOT_COMPLETED` (re-arm after restart/update). No background-location permission yet: add only if the log shows the locked-screen scan finds nothing.
- [ ] Settings toggle (**off by default**) + one-time MIUI guide.
- [ ] Check on the Mi 8: app swiped away, phone restarted, battery use overnight.

## Phase 4: Keep step history safe

- [ ] **CSV export/import** via the system file picker (no storage permission). Columns `date,steps,distance_m`.
- [ ] Pure `StepCsv` with tests: round-trip, bad rows skipped and counted, Excel quirks (CRLF line endings, BOM).
- [ ] Import merges by date, **keeping the higher step count**. Show a summary: "Imported 120 days, 8 updated, 2 rows skipped".
- [ ] Remind the user to export now and then (e.g. "Last export: 3 weeks ago" in Settings), since CSV is now the only backup.

## Phase 5: Clearer feedback

- [ ] `WatchError` types: BluetoothOff, LocationOff (Android 10 scan requirement), NotFound, ConnectionLost, Timeout, WatchRejected, PermissionMissing, Unknown.
- [ ] Plain-English message + next step for each. Test that none contain raw hex.
- [ ] "Last synced: today at 08:00" on the home screen. Last-10 sync history list.

## Phase 6: Better step stats

- [ ] Pure `StepStats`: weekly/monthly totals, averages, current & best streak, best day, best week (Mon–Sun weeks).
- [ ] Tests: gaps break streaks, today's partial day doesn't break a streak, month boundaries.
- [ ] UI: goal progress ring on the home screen + stats card. Step goal cached in prefs for offline/widget use.

## Phase 7: Home-screen widget (Jetpack Glance)

- [ ] Add `androidx.glance:glance-appwidget`. Check the merged manifest still has no INTERNET.
- [ ] Shows today's steps vs goal and last-sync time. Tap opens the app.
- [ ] Refreshed after every sync, manual or background.
- [ ] Check on the Mi 8 launcher. MIUI sometimes limits widget updates.

## Phase 8: Automatic time zone

- [ ] Listen for the phone's time-zone change. Apply the new home city on the next sync using the existing home-city transaction, with DST on auto.
- [ ] `ZoneMatcher`: hand-made map from time zone to watch city, with a fallback on UTC offset. Tests for ambiguous offsets.
- [ ] Settings toggle, off by default.

---

## Per-feature checklist

1. One feature per branch.
2. `./gradlew testDebugUnitTest assembleDebug` is green.
3. Merged manifest still has no INTERNET.
4. Install on the Mi 8 and test on the **real watch**. Tick the "Done when" boxes in the improvements doc.
5. Bump `versionCode`/`versionName` and add a fastlane changelog when making a release build.

## Open questions

1. ~~**Crash cause**~~: confirmed (missing `BLUETOOTH`/`BLUETOOTH_ADMIN`). See Phase 0 crash record.
2. ~~**Does the protocol work on this watch at all?**~~ Yes: reads and writes confirmed (2026-11-17). Step download confirmed too.
3. ~~**Address stability**~~: stable across reconnects, changes after a watch reset, and always equal to the reported MAC. Phase 2 handles the change (asks the user).
4. **Auto-sync reconnect sequence:** no captures possible. Phase 3 measures it.
