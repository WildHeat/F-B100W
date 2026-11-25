# Handoff: Phase 3, Part 2 (Reliable wake-ups) and Part 3 (Overnight check)

Self-contained brief for a new session. Read this, then [CLAUDE.md](CLAUDE.md) (project rules) and the Phase 3 section of [PLAN.md](PLAN.md) (full record of results and sources). You don't need earlier chat history. [HANDOFF-phase3.md](HANDOFF-phase3.md) and the older handoffs are reference only.

Last updated: 2026-11-21.

## Where things stand

- Phases 0–2 are done and merged. Phase 3 so far:
  - **PR #11** `feat/background-sync-experiment`: the **listening test**. Settings → *Background test*: switch, *Listen now for 5 minutes*, *Pair with this watch (test)*. Code is in `listen/`.
  - **PR #12** `feat/app-info`: **Part 1, register the app on the watch**. Confirmed on the watch. 57/57 tests, no INTERNET.
- **The user approved Part 2 and Part 3 (below).** Start Part 2 on a new branch `feat/reliable-wakeup` from up-to-date `master`. The plan is approved, so build it, then tell the user exactly what to test.
- **The user chose "log only"** for watch-initiated connections in the background. Don't sync or write in the background until the user approves "Phase 3 proper" (see the end).

## Key findings so far (2026-11-17 to 21)

1. **App info (`0x22`) was the missing piece, not pairing.** A reset or never-registered watch answers `22 FF FF FF FF FF FF FF FF FF FF 00` and ignores **D** (its "give me the time" button).
   - `WatchRepository.registerAppIfNeeded()` (first step of `sync()`) writes `22 34 88 F4 E5 D5 AF C8 29 E0 6D 02`, but only on that exact answer. The value is from [izivkov/gshock_api](https://github.com/izivkov/gshock_api) `app_info_io.py`.
   - Confirmed 2026-11-21 18:27: the write read back correctly.
2. **Connection reason = byte 8 of the `0x10` answer:** 0 reset (no app), 1 C button, 2 find phone, 3 automatic sync, 4 D button (`Protocol.connectReason`).
   - Before registration it was always 0. After registration, C gave 1 and D gave 4.
3. **D now works.** D → the watch advertises → the phone connected in 0.9 s, reason 4.
   - **The watch sends nothing and hangs up after about 7 s** (status 19) if no time arrives. `TIME` then just disappears, with no `OK`/`ERR`.
   - So a real background sync must write the time within a few seconds. The normal sync does it about 1 s after ready.
4. **Scheduled syncs:** the manual says the watch auto-corrects about 4× a day, at around **00:30, 06:30, 12:30 and 18:30**.
   - It needs auto time correction enabled. The app's *Auto time sync* switch (`0x11` byte 12 bit 7) is on.
   - It pauses for 24 h after a manual time change on the watch.
   - **Not yet observed:** no listening window has run at those times yet (see 5).
5. **MIUI blocked every wake-up.** On 18–20 Nov the alarms (`setExactAndAllowWhileIdle`) arrived **1–16 h late**, mostly only when the user opened the app ("app process started 0 s ago"). The log showed "battery optimisation on"; Autostart is off. **This is what Part 2 fixes.**
6. **Pairing:** the phone has been bonded since 18 Nov 03:02 (`PairTest`, user-approved). gshock_api, GShockTimeServer and Gadgetbridge don't bond, so it's probably unnecessary. That's unproven until tested without the bond. Leave it as is for now.
7. **Advert:** name `CASIO F-B100W`, connectable, data `02010603030418020a00 1109 "CASIO F-B100W"` (service UUID 0x1804). It's the same for C and D. It's always the BLE address `E5:33:5A:41:F8:03`, which is also the saved watch (it changes after a watch reset).

## How the listening test works now (`app/src/main/java/dev/trrrst/watch/listen/`)

| File | Role |
| --- | --- |
| `ListenSchedule` (pure, tested) | `MANUAL_TIMES` 00:30/06:30/12:30/18:30, plus the watch's `0x11` time if on. Window = 10 min before to 20 min after. Overlaps merge. `next(now, times)` returns the next window not yet ended; if it's already started, the start is now. |
| `ListenScheduler` | Prefs `watch`: `listenTest`, `autoSyncOn/Hour/Minute`, `listenNextStart`. `schedule(ctx, notBefore)` sets **one** alarm (`PendingIntent` request code 0 → `ListenAlarmReceiver`) with the extras `start`/`syncAt`/`end`/`label`. `setTestOn`, `rememberAutoSync` (called whenever the app reads `0x11`), `listenNow` (5 min). `WatchViewModel.init` re-arms on every app start. |
| `ListenAlarmReceiver` | Starts `ListenService` with `startForegroundService`. |
| `ListenService` | Foreground service (type `connectedDevice`, quiet notification, wake lock for the window). Ignores a second start while running. In `finally`: `schedule(this, notBefore = end)`, so an early end (e.g. Bluetooth off) doesn't loop. `ListenRun` uses `BleManager.listen()`: a filtered scan (name or saved address, `SCAN_MODE_LOW_LATENCY`) that never touches `found`/`state`. `AdvertTracker` logs bursts. **One read-only test connection** per window to the saved watch, inside `repo.exclusive`. It retries every 5 s if the app is busy. It reads `0x10` at once (logs the reason), waits 15 s logging anything the watch sends, reads again, then disconnects. It sets `repo.testOwnsConnection`, so the screen doesn't `refreshAll()`. |
| `ListenLog` | `files/listen.log` (cap 200 KB) and logcat `TrrrstListen`. The app's normal connects also log there ("app connected … reason …", "app info: …"). |
| `PairTest` | Settings button: `createBond()` with the connected saved watch, logs bond changes, reads `0x22`/`0x23`. The only place pairing is allowed (CLAUDE.md). |
| `AdvertTracker`, `LogTrim` | Pure, tested. |

Permissions now: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `POST_NOTIFICATIONS`, `WAKE_LOCK` (plus the Bluetooth/location ones). No INTERNET.

## Part 2: reliable wake-ups (approved, to build)

Branch `feat/reliable-wakeup`. It doesn't touch the watch.

1. **Alarm-clock alarm:** in `ListenScheduler.schedule`, use `AlarmManager.setAlarmClock(AlarmClockInfo(at, showIntent), pi)`, where `showIntent` opens `MainActivity`.
   - On API 31+ without `canScheduleExactAlarms()`, fall back to `setAndAllowWhileIdle` and log it.
   - Tell the user a small alarm icon may appear in the status bar (they accepted this).
2. **Re-arm after reboot and update:** a new `BootReceiver` (exported, for `BOOT_COMPLETED` + `MY_PACKAGE_REPLACED`) calls `ListenScheduler.schedule()` and logs "re-armed after restart/update".
   - New permission `RECEIVE_BOOT_COMPLETED` (user-approved).
   - On MIUI it only works with Autostart on.
3. **"Phone setup" box** in Settings → Background test:
   - Battery optimisation on/off via `PowerManager.isIgnoringBatteryOptimizations`. The button opens `Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` (no extra permission; **don't** add `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` without asking).
   - Autostart button: try the explicit MIUI activity `com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity` in try/catch. Fall back to `ACTION_APPLICATION_DETAILS_SETTINGS` for our package.
   - Plain-English guide: Autostart on, Battery saver → No restrictions, lock the app in Recents.
   - Re-check the status when the screen resumes.
4. **"Test the wake-up" button:** a separate alarm (request code **1**, so the daily alarm isn't replaced). It starts 3 min ahead with a 5-min window, labelled "wake-up test", and also uses the alarm-clock alarm. The log already records lateness ("alarm planned for …, came X s late") and process age.
5. **Lower-power scan:** add a scan-mode parameter to `BleManager.listen()`. Scheduled windows use `SCAN_MODE_BALANCED`. *Listen now* and the wake-up test use `LOW_LATENCY`. Pass it via an intent extra.
6. **Tests and docs:**
   - Unit tests for any new pure logic (e.g. the test-window computation, setup advice text).
   - `./gradlew testDebugUnitTest assembleDebug`, aapt check for no INTERNET.
   - Update PLAN.md (Phase 3 Part 2 checkbox and results) and the CLAUDE.md architecture row.
7. **Optional, small:** the test connection's 15 s wait is longer than the watch's ~7 s patience. Keep it (it's log only), but don't read anything into "hung up after ~7 s".

**Part 2 device test (tell the user):**
1. Install, and use the setup box: Autostart on, No restrictions, battery optimisation off.
2. *Test the wake-up* → swipe the app away from Recents → lock the phone → wait about 10 min → "check the logs". Goal: less than 1 min late.
3. Restart the phone → check the log shows it re-armed and the next window is planned.

## Part 3: overnight check (approved, after Part 2 works)

- **Before bed:** the test switch is on, *Auto time sync* is on, Bluetooth and Location are on, and **no manual time change on the watch** (24 h pause rule).
- **In the morning, read `listen.log`, prefs and logcat:**
  - Did the windows at about 00:20 and 06:20 start on time?
  - Did the watch advertise at about 00:30/06:30, and with **reason 3 = automatic sync**?
  - How long did it advertise and wait?
- **Battery (app-only, read-only):** `dumpsys batterystats dev.trrrst.watch`, or see what MIUI's battery page shows for the app.
- **Expected with log only:** the watch shows `TIME`, then gives up after about 7 s. Harmless: holding C still syncs normally.

## After that: separate decisions (plan first, ask)

- **Phase 3 proper** (planned 2026-11-25 in PLAN.md Phase 3, waiting for the user's approval): when the watch calls (reason 3, or 4 for D), run the normal sync (home city + time, then steps) for the saved watch only, never asking. It must write the time within about 7 s. Then a quiet notification ("Watch synced at 06:30 · 6,412 steps"). Then decide whether the listening windows become the real feature: logs private, Settings text, removing the test UI.
- **Pairing:** test without the bond (unpair on the phone, and run the watch's clear-pairing steps). Note that the watch clear resets app info to `FF`, and the app re-registers on the next connect. If the watch is fine without the bond, remove `PairTest` and restore the "no pairing" rule.
- **Still untested from Phase 2:** *Cancel* in the "This isn't your saved watch" dialog, and Connect with Bluetooth off.

## Rules (from the user; they matter)

1. **The Mi 8 is the user's personal phone.** Only touch `dev.trrrst.watch`, read-only adb (`logcat -d`, `dumpsys` for our package, `run-as dev.trrrst.watch cat …`). Never `logcat -c`. Any write only with the user's OK.
2. **The user installs builds** (Android Studio ▶ Run) and **commits/merges themselves.** Build and test locally, then give exact test steps. Write commit/PR messages when asked, but don't commit, push or install.
3. **Plan first** for anything not yet approved. Explain in plain English and wait for approval.
4. **Never add INTERNET.** Check every APK with aapt.
5. **Read before write on the watch.** New kinds of writes only with approval, one at a time.
6. **Plain English, short summaries.** The user isn't an Android developer. Give concrete test steps ("hold C on the watch, press Connect"). For "check the logs", give a table of time / what happened / result.
7. **The watch's buttons:** C = hold to connect (bottom-left). **D** = the time-sync request (short press in normal time mode). B = light. A = hold for settings. The manual's text doesn't give positions; don't guess them.

## Environment (this Windows PC)

- Git Bash (`Bash` tool), `./gradlew` (no `gradlew.bat`).
  ```sh
  export JAVA_HOME="$USERPROFILE/.jdks/corretto-17.0.13" ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"
  ./gradlew testDebugUnitTest assembleDebug
  "$ANDROID_HOME/build-tools/36.0.0/aapt.exe" dump permissions app/build/outputs/apk/debug/app-debug.apk | grep -iE "internet|network" || echo "no INTERNET"
  ```
- adb: `ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"`, always `-s 52b2b2ca`, wrapped in `timeout 60`.
  - Installed build: `dumpsys package dev.trrrst.watch | grep lastUpdateTime`.
  - Test log: `run-as dev.trrrst.watch cat files/listen.log`. Prefs: `… cat shared_prefs/watch.xml`.
  - Logcat (save to the scratchpad, then grep): `logcat -d -v time -b main,system`. Tags: `TrrrstListen`, `TrrrstBLE` (every read answer/write as hex), `Trrrst`, `BluetoothGatt`.
  - MIUI crash traces: `dumpsys dropbox --print data_app_crash`.
- Bash heredocs with Python containing `'` can break the shell. Prefer the Edit tool for code edits.
