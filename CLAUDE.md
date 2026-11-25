# CLAUDE.md

TRRRST Watch: a FOSS, fully offline Android companion app for the **Casio F-B100W** (module 3589), talking to it over BLE. This repo is a **personal fork** (`origin` = WildHeat/F-B100W) of `taxmeifyoucan/trrrst-watch`, licensed AGPL-3.0. No upstream PRs are planned. Don't shape changes around upstream style.

**Status (2026-11-23):** the app works on the user's Mi 8 (Android 10). Phases 0–2 are done and merged. Phase 3 is in progress: the listening test (PR #11) and Part 1, registering the app on the watch so D / time sync works (PR #12, confirmed on the watch), are merged. **Parts 2 and 2b are merged (PR #14):** alarm-clock alarms (0.2–1.3 s late), re-arm after restart, and a standing `autoConnect` request caught the watch's automatic calls at 12:30 and 18:30 (reason 3); scanning never worked with the screen off. The cleanup (scan removed, *Auto time sync* as an on/off switch writing `00 1E`, waiting for Bluetooth) is built on `feat/listen-cleanup` and **confirmed on the watch (2026-11-25)**, including a fix so a finished window is never restarted (`listenDoneUntil`); waiting for the user's commit (see PLAN Phase 3). Next after that: plan "Phase 3 proper" (send the time when the watch calls). Start from [HANDOFF-phase3-part2.md](HANDOFF-phase3-part2.md).** Watch quirks and Phase 3 results are recorded in PLAN.md.

The Mi 8 is reachable over adb at `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe` (not on PATH). Use `-s 52b2b2ca`, since an emulator is often running too. It's the user's personal phone, so only read logs and data for `dev.trrrst.watch` (`run-as dev.trrrst.watch cat shared_prefs/watch.xml` shows the remembered home city, the saved watch and the sync log). **The user installs builds (Android Studio ▶ Run) and commits themselves.** On MIUI, read crash traces with `adb shell dumpsys dropbox --print data_app_crash`, since they don't appear in logcat.

- **What we're building and why:** [TRRRST-Watch-Improvements.md](TRRRST-Watch-Improvements.md)
- **How, phase by phase, with status:** [PLAN.md](PLAN.md). Read it before starting feature work and update the checkboxes as you go.

## Hard rules

- **Never add the `INTERNET` permission** (or `ACCESS_NETWORK_STATE`), directly or through a dependency. After adding any library, check the merged manifest: `app/build/intermediates/merged_manifest/debug/.../AndroidManifest.xml`.
- **Don't add dependencies lightly.** No Google Play Services, Firebase, analytics or proprietary SDKs. Approved: Jetpack Glance (widget). Health Connect was considered and dropped.
- **Pairing (`createBond()`) only through the pairing test** (`listen/PairTest`, Settings → Background test), which the user approved on 2026-11-18 because the watch's time-sync request (D button, scheduled syncs) seems to need a paired phone. Don't pair anywhere else, and don't make normal connecting depend on it. Undo: unpair in the phone's Bluetooth settings + clear it on the watch (hold C 1 s, hold A 2 s, press C twice, hold B 2 s until CLR stops flashing).
- **Always `close()` a `BluetoothGatt` when it disconnects.** Leftover clients get revived by Android and break the next connection (see the PLAN Phase 0 bug record).
- **Don't change BLE byte layouts in `Protocol.kt` by guesswork.** Layouts were confirmed against captures. Anything new needs a captured hex fixture and a test in `ProtocolTest`. Bad writes can soft-brick the watch (see README). Never write to watch memory/firmware areas.
- **App info (`0x22`) is written only by `WatchRepository.registerAppIfNeeded`**: once, and only when the watch answers exactly `22 FF…FF 00` (unregistered/reset). The value `22 34 88 F4 E5 D5 AF C8 29 E0 6D 02` is from izivkov/gshock_api (`app_info_io.py`); user-approved 2026-11-21. Without it the watch ignores D. Never write other app-info values.
- **Watch writes are read-modify-write.** Read the current value, change only the managed bits, write it back (see `saveBasic`, `saveAlarms`, `BleSettings.with*`). Keep it that way.
- One feature per branch/PR, small changes. Features are only "done" after testing on the real watch, which Claude can't do, so say what still needs on-device verification.

## Build & test

Requires JDK 17 and Android SDK platform 35. On this Windows machine: there's no `gradlew.bat`, so run `./gradlew` from Git Bash. The default Java (and Android Studio's JBR) is 25, which Gradle 8.14 can't run on, so first `export JAVA_HOME="$USERPROFILE/.jdks/corretto-17.0.13" ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"`.

```sh
./gradlew testDebugUnitTest assembleDebug          # what CI runs
./gradlew testDebugUnitTest --tests '*ProtocolTest*'
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

CI: `.github/workflows/android.yml` (every push), `release.yml` (signs on `v*` tags; keystore comes from secrets, never commit `*.jks`).
Release: bump `versionCode`/`versionName` in `app/build.gradle.kts` and add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.

## Architecture

Single module `:app`, package `dev.trrrst.watch`, Kotlin + Jetpack Compose (Material 3), minSdk 26, target/compile 35. No DI framework, no database.

| File | Role |
| --- | --- |
| `ble/Protocol.kt` | Pure codec (no Android deps): opcodes, GATT UUIDs, encode/decode for time, DST, alarms, settings, profile, step records. Unit-tested. |
| `ble/BleManager.kt` | GATT connection, scan, notification queues. `read(op)` = write opcode to 0x2C, answer on 0x2D. `write()` → 0x2D. `receive(cat)` = bulk transfer over 0x23 (control) + 0x24 (data). `commit=false` peeks without consuming the watch's data. All ops serialized by a mutex. |
| `TrrrstApp.kt` | Application. Owns the app-scoped `BleManager`, `StepStore`, `SyncLogStore` and `WatchRepository`. |
| `WatchRepository.kt` | App-scoped core. The only consumer of `ble.found` (connect → `sync()`), operation mutex (`exclusive {}`) + `busy`, city tables, remembered home city, `writeHomeCityAndTime`, `sync(trigger)` → `SyncResult` (never throws). |
| `WatchViewModel.kt` | Screen state. Every button action goes through `op(label) { … }` → `repo.exclusive`. Calls `refreshAll()` (reads) when it sees `READY`. |
| `WatchMatcher.kt` | Pure "is this my watch?" logic (`SavedWatch`, scan preference, connect verdict). Unit-tested. |
| `HomeCity.kt`, `Sync.kt` | Pure logic: home-city choice, summer-time flag, `SyncResult`, plain-English `SyncMessages`. Unit-tested. |
| `data/StepStore.kt` | Step history as `filesDir/steps.json`, merged by date (`yyyy-MM-dd`). `changes` flow for screens. |
| `data/SyncLog.kt` | Last 10 `SyncRecord`s as JSON in prefs `watch`/`syncLog`. Pure `SyncLog` is unit-tested (test-only `org.json` dependency). |
| `listen/*.kt` | Phase 3 listening test: `ListenSchedule` + `LogTrim` (pure, tested), `ListenScheduler` (prefs, alarm-clock alarms: daily window = request code 0, "Test the wake-up" = 1; `ListenAlarmReceiver`, `BootReceiver` re-arms after restart/update), `ListenService` (foreground service; a standing `autoConnect` request per window, set while Bluetooth is on and the app is idle (`StandingRequest`, pure, tested); one read-only connect, logs the connection reason; no scanning: it never worked with the screen off), `ListenLog` (`files/listen.log`), `PhoneSetup` (pure, tested: setup advice) + `PhoneSettings` (battery optimisation status, opens Autostart/App info/battery pages), `PairTest` (the only place pairing is allowed). |
| `ui/*.kt` | Compose screens: Watch (home), Alarms, World, Settings, plus the history chart and theme. |
| `assets/cities_ht.json`, `cities_wt.json` | Home/world city tables (`no, name, tz, dst, rule`). |

Notes:
- Connecting: the watch only advertises briefly after the user holds the bottom-left **C** button, presses **D** (time-sync request), or at its automatic calls (about :30:30 past 00/06/12/18, only when `0x11` byte 12 = `00`). It gives up after about 6–10 s if nobody connects.
- Every fresh connection first checks it's the saved watch (`WatchRepository.isOurWatch`, a read only). A different watch triggers the "This isn't your saved watch" question, and nothing is written until the user accepts. Then it runs `WatchRepository.sync()`: home city + summer time, then the time, then a **committing** step download (user decision). `refreshAll()` only reads. The watch ignores a bare time write when it has no home city, and a third `1E` read in a row errors (see PLAN 1.2).
- DST and city changes use the official transaction sequences (`OP_DST_TRANSACTION` types 1/2/3). Keep the order.
- Target test phone: Xiaomi Mi 8, **Android 10 (API 29)**, MIUI. API-31+-only features (e.g. CompanionDeviceService presence) need a fallback that works on 29.

## Conventions

- Put new non-UI logic (stats, CSV, error text, zone matching, scheduling decisions) in plain Kotlin classes with JVM unit tests under `app/src/test`. There are no instrumented tests.
- User-facing text must be plain English with a next step. No raw hex or exception messages (PLAN #7).
- Small prefs live in `SharedPreferences`. Don't add Room/DataStore without a reason.
- Commit only when asked. Work on a feature branch, not `master`.
