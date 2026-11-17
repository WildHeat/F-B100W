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

- [ ] **1.1 App-scoped core.** `TrrrstApp : Application` owns one `BleManager` and one `StepStore`. A thin `WatchRepository` holds the shared sync logic.
- [ ] **1.2 Shared sync routine.** "connect → sync time → download steps → save → record result", used by both the UI and background sync.
- [ ] **1.3 No-INTERNET guard.** A build check that fails if `INTERNET` / `ACCESS_NETWORK_STATE` shows up in the merged manifest (catches libraries adding it).
- [ ] **1.4 Sync log.** `SyncRecord(time, trigger, ok, steps?, error?)`, last 10 kept. Used by background sync, the widget and the sync history list.

## Phase 2: Remember your watch

- [ ] After the first successful connect, save the watch's address and name.
- [ ] **Find out if the address is stable:** log the address on each connect for a few days. The app also reads the watch's MAC from `OP_BLE_FEATURES`; compare the two.
  - Stable → connect directly with `getRemoteDevice(address)`, falling back to a scan filtered by that address.
  - Changing → scan filtered by name, then confirm it's *your* watch by reading `OP_BLE_FEATURES` before doing anything else.
- [ ] **No bonding** (`createBond`) unless Phase 0 shows it's safe.
- [ ] Settings: show the remembered watch + **Forget this watch**.
- [ ] Unit-test the "is this my watch?" decision.

## Phase 3: Automatic background sync

- [ ] `SyncService` foreground service (type `connectedDevice`) with a quiet notification channel.
- [ ] Permissions: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`, `SCHEDULE_EXACT_ALARM`. **No INTERNET.**
- [ ] `SyncScheduler`: exact alarm 1 min before the watch's auto-sync time. Rescheduled after each run, after reboot, and when auto-sync settings change.
- [ ] Service: connect to the saved watch → shared sync routine → notification "Watch synced at 08:00 · 6,412 steps" → stop. Hard timeout.
- [ ] **No captures possible, so experiment:** first a logging-only version that records what the watch does when it reconnects at its auto-sync time. Only then add time + step syncing.
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
3. **Address stability:** stable across reconnects, but changes after a watch reset (see Phase 0). Phase 2 still needs to handle the change.
4. **Auto-sync reconnect sequence:** no captures possible. Phase 3 measures it.
