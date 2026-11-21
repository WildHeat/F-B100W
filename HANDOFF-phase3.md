# Handoff: Phase 3 (Automatic background sync)

> **Superseded (2026-11-21):** use [HANDOFF-phase3-part2.md](HANDOFF-phase3-part2.md). This file is kept for reference only.

Self-contained brief for a new session. Read this, then [CLAUDE.md](CLAUDE.md) (project rules) and [PLAN.md](PLAN.md) (roadmap, status, and the full record of watch findings). You don't need any earlier chat history. Older handoffs ([phase 0](HANDOFF-phase0.md), [phase 1](HANDOFF-phase1.md), [phase 2](HANDOFF-phase2.md)) are for reference only.

Last updated: 2026-11-18.

## Where things stand

**Phases 0 and 1 are done** and merged to `master` (PRs #1–#8).

**Phase 2 (Remember your watch) is merged** to `master` (PR #9, 2026-11-17) and mostly confirmed. Branch Phase 3 from up-to-date `master`.
- Confirmed on the Mi 8, including a **real watch reset**: the first connect saves the watch, a reconnect syncs without asking, Forget saves the watch again, and a different address leads to the question. Nothing was written before the answer. *Use this watch* synced the watch and saved it.
- **Still untested:** *Cancel* in the "This isn't your saved watch" dialog, and Connect with Bluetooth off. Ask the user whether they've done these. To test Cancel without another reset, a fake `savedAddress` could be written into `shared_prefs/watch.xml`. That's a write, so **only with the user's OK**.
- 37/37 unit tests pass, and there's no INTERNET permission.

**Don't start Phase 3 until the user asks.** Then make a plan, get the user's approval, and only then build. The user asked for that order in Phase 2.

## How the app works now (after Phase 2)

| Piece | What it does |
| --- | --- |
| `TrrrstApp` (Application) | Owns one `BleManager`, `StepStore`, `SyncLogStore` and `WatchRepository` for the whole process. |
| `WatchRepository` | **Only** consumer of `ble.found`: `connect()` → `isOurWatch()` → `sync(MANUAL)`. Owns the operation mutex (`exclusive {}`) + `busy`, the city tables, the remembered home city, and the **saved watch** (`savedWatch`, `pendingWatch`, `useThisWatch`, `rejectWatch`, `forgetWatch`). |
| `isOurWatch(dev)` | Runs inside `exclusive {}` right after connecting, **before any write**. Reads `OP_BLE_FEATURES` (only for the log/record) and decides with `WatchMatcher.onConnected` on the Bluetooth address. Logs `Trrrst: connected to <addr>, watch reports <mac>, saved <addr> -> <verdict>`. |
| `WatchMatcher` (pure, tested) | `SavedWatch(address, name, mac)`. During the scan: `CONNECT_NOW` / `BACKUP`. After connecting: `SAVE_AND_SYNC` / `SYNC` / `ASK`. `PREFER_SAVED_MS = 5000`. |
| `BleManager.scan(savedAddress)` | One unfiltered scan, matched by name (`CASIO F-B100W`). Takes the saved address at once. Another F-B100W is a backup, used after 5 s. `connectedAddress`, `showStatus()` added. The reconnect fix is unchanged (close on disconnect, `closeGatt()` + `drainQueues()` before connect, ignore stale clients). |
| `sync(trigger)` | Needs an open connection. Home city + summer time, then the time (`writeHomeCityAndTime`), then a **committing** step download. Never throws. Records a `SyncRecord` in the sync log. `SyncTrigger.BACKGROUND` already exists, unused. |
| SharedPreferences `watch` | `homeCity`, `homeDstMode`, `syncLog` (JSON, last 10), `savedAddress`, `savedName`, `savedMac`. |
| UI | Not-your-watch dialog in `MainActivity` (app level). "Your watch" card + Forget in `SettingsScreen` (shown even when disconnected). |

## Watch facts learned (keep in mind)

- **Address:** the BLE address always equals the MAC the watch reports in `OP_BLE_FEATURES`. That held in all 4 connects on 2026-11-17. It stays the same across reconnects and **changes after a reset** (seen 3 times: `E5:33:8D:36:F8:03` → `D5:33:BC:22:F8:03` → `E5:33:5A:41:F8:03`).
- **Advertising:** the watch only advertises briefly after the user holds **C**. Normally the scan finds it in ~0.1 s and it's ready ~1.5 s later. When the app waited 5 s for the saved watch first, connecting took ~12 s, probably near the end of the advertising window.
- **Not yet known (the core question of Phase 3):** what the watch does at its **auto-sync time**. Does it advertise, for how long, under which name, and does it expect the phone to start anything? The official app can't run on the Mi 8, so **there are no captures**. It has to be measured.
- The auto-sync time is in `Protocol.BleSettings` (`autoSyncEnabled`, `autoSyncHour`, `autoSyncMinute`; `hasAutoSyncTime` is false on a fresh watch, which stores `FF FF`). The Settings switch defaults to 08:00.
- The watch ends sessions itself (`status=19`). That's normal.
- **Home city:** the watch sometimes reports no city, which seems to happen after the time is changed by hand. It ignores a bare time write while it has no city. Hence `writeHomeCityAndTime` on every sync.
- **`1E` read limit:** a third `1E` read with no write in between returns `ff811e`. `writeHomeCityAndTime` starts with a time write to reset it.
- Date order and Language have no effect on this watch.

## Rules (from the user, they matter)

1. **The Mi 8 is the user's personal phone.** Only touch `dev.trrrst.watch`. Read-only adb: `logcat -d`, `dumpsys`, `run-as dev.trrrst.watch cat …`. Never `logcat -c`. Any write (even to the app's own prefs) only with the user's OK.
2. **The user installs builds** (Android Studio ▶ Run) and **commits/merges themselves.** Build and test locally, then tell them exactly what to test. Offer a commit message when asked. Don't install, commit or push unless asked.
3. **Plan first:** explain the plan in plain English and wait for approval before implementing.
4. **Never add the INTERNET permission.** The build check catches it. Still check every APK with `aapt`.
5. **Read before write on the watch.** New kinds of writes are tested one at a time, after the user agrees.
6. Talk in plain English. The user isn't an Android developer. Keep chat summaries short. Explain test steps concretely (e.g. "hold C on the watch, press Connect", "let the watch disconnect on its own").
7. When the user says "check the logs", read the log and the app's prefs, compare them with what they expected, and report clearly (a table of time / what happened / result works well).

## Phase 3 tasks (from PLAN.md)

Goal: at the watch's daily auto-sync time, with the app closed, the phone syncs time + steps and shows a quiet notification ("Watch synced at 08:00 · 6,412 steps"). **Off by default**, switched on in Settings.

1. **Experiment first (logging only).** Before any automatic writes, build a version that wakes just before the watch's auto-sync time, scans for the saved address for a few minutes, and **only logs** what it sees: when the watch advertises, for how long, its name/address, and whether a connect works. No sync yet. Have the user leave it overnight, then read the log.
2. `SyncService`: a foreground service (type `connectedDevice`) with a quiet notification channel. Connect to the **saved** watch → `isOurWatch` → `sync(BACKGROUND)` → notification → stop. Hard timeout.
3. `SyncScheduler` (pure decision logic + unit tests): the next wake time ≈ 1 min before the watch's auto-sync time. Reschedule after each run, after reboot (`RECEIVE_BOOT_COMPLETED`), and when the auto-sync setting changes.
4. Settings toggle (off by default) + one-time MIUI guide (Autostart on, Battery saver → No restrictions).
5. Check on the Mi 8: app swiped away, phone restarted, battery use overnight.

**Pitfalls**
- **Background must never ask and never sync a stranger.** There's nobody to answer the dialog. Background sync only proceeds on `SYNC`. With no saved watch, or on `ASK`, it disconnects, writes nothing, and records a sync-log entry with a plain-English reason (e.g. "Open the app and connect once to choose your watch"). Right now the `found` collector always calls `sync(MANUAL)` and sets `pendingWatch`. The background path needs its own route (e.g. `connectAndSync(trigger)`), with the trigger passed through.
- **Keep `found` consumed only in `WatchRepository`** (the Phase 1.1 pitfall). Don't let the service and the screen both auto-connect. Share `exclusive {}` so the screen and the service never interleave.
- **Android 10 (API 29) on the Mi 8:** several permissions in PLAN only exist on newer Android. `SCHEDULE_EXACT_ALARM` is API 31+ (`setExactAndAllowWhileIdle` works without it on 29), `POST_NOTIFICATIONS` is API 33+, and `FOREGROUND_SERVICE_CONNECTED_DEVICE` is API 34+. Declare them for newer phones, but test on 29. `FOREGROUND_SERVICE` itself is needed.
- **Scanning in the background on Android 10:** to check on the device, not assumed. Location must be **switched on** (`ScanCheck` already covers it). Scans without a `ScanFilter` are generally paused while the screen is off, so the background scan should filter by the saved address. Whether `ACCESS_BACKGROUND_LOCATION` or a `location`-type foreground service is needed for results with the app closed has to be checked on the Mi 8. Don't add permissions beyond what's actually needed, and explain any new one to the user first.
- **MIUI** kills background apps. Expect the user to need Autostart + No restrictions. Without them, log and report what happened instead of guessing.
- **Battery:** no constant scanning. Wake briefly around the auto-sync time only.
- Close the `BluetoothGatt` on every disconnect (hard rule), including on timeout in the service.

## Environment (this Windows PC)

- Use **Git Bash** (`Bash` tool). There's no `gradlew.bat`, so run `./gradlew`.
- **JDK:** the default `java` and Android Studio's JBR are Java 25, which Gradle 8.14 can't run on. Use:
  ```sh
  export JAVA_HOME="$USERPROFILE/.jdks/corretto-17.0.13"
  export ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"
  ```
- **adb** isn't on PATH: `ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"`. An emulator is often running, so always pass `-s 52b2b2ca` (the Mi 8). Wrap adb calls in `timeout 60`.
  - `waiting for device`: the phone is unplugged, so ask the user to plug it in.
  - `offline`: `adb -s 52b2b2ca reconnect` once.
  - `unauthorized`: ask the user to unlock the phone and tap **Allow** on the USB-debugging prompt (seen 2026-11-17 after a reconnect).

```sh
cd "/c/Users/lowid/Desktop/Research/FB-100 app/F-B100W"
./gradlew testDebugUnitTest assembleDebug
"$ANDROID_HOME/build-tools/36.0.0/aapt.exe" dump permissions app/build/outputs/apk/debug/app-debug.apk | grep -iE "internet|network" || echo "no INTERNET"
```

### Reading what happened on the phone
- Installed build: `"$ADB" -s 52b2b2ca shell dumpsys package dev.trrrst.watch | grep lastUpdateTime`.
- Log (save to the scratchpad, then grep): `"$ADB" -s 52b2b2ca logcat -d -v time -b main,system > "<scratchpad>/run.log"`. App PIDs: `grep "Start proc [0-9]*:dev.trrrst.watch"`.
  - `TrrrstBLE`: scan start (with the saved address), found/backup decisions, every write and every watch answer as hex, disconnects.
  - `Trrrst`: the `connected to … -> VERDICT` line, user choice / reject / forget, op/sync failures with stack traces.
  - `BluetoothGatt`: `clientIf=` should be a single client per connection.
  - To check that nothing was written before a decision, grep `TrrrstBLE.*write` between the connect and the `->` line.
- Prefs (home city, sync log, saved watch): `"$ADB" -s 52b2b2ca shell run-as dev.trrrst.watch cat shared_prefs/watch.xml`.
- **Crash traces don't appear in logcat on MIUI:** `"$ADB" -s 52b2b2ca shell dumpsys dropbox --print data_app_crash | grep -B3 "Process: dev.trrrst.watch"`. The only ones so far are the old v0.4 ones (before 14:22 on 2026-11-17).
- Overnight tests: logcat's buffer may not reach back far enough. If the experiment needs a longer record, consider a small app-private log file (read with `run-as … cat`) and agree on it with the user first.
