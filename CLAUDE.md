# CLAUDE.md

TRRRST Watch: a FOSS, fully offline Android companion app for the **Casio F-B100W** (module 3589), talking to it over BLE. This repo is a **personal fork** (`origin` = WildHeat/F-B100W) of `taxmeifyoucan/trrrst-watch`, licensed AGPL-3.0. No upstream PRs are planned. Don't shape changes around upstream style.

**Current blocker:** the v0.4 release crashes on the Mi 8 (Android 10) as soon as Connect is pressed, and has never worked there. The watch then shows `Err`. **Confirmed cause:** a `SecurityException` at `BleManager.scan()` because the manifest is missing `BLUETOOTH`/`BLUETOOTH_ADMIN` (`maxSdkVersion="30"`). The app never reaches the watch. Fix it first (PLAN Phase 0 has the full crash record). On MIUI, read crash traces with `adb shell dumpsys dropbox --print data_app_crash`, since they don't appear in logcat.

The Mi 8 is reachable over adb at `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe` (not on PATH). Ask before installing or uninstalling anything on the phone.

- **What we're building and why:** [TRRRST-Watch-Improvements.md](TRRRST-Watch-Improvements.md)
- **How, phase by phase, with status:** [PLAN.md](PLAN.md). Read it before starting feature work and update the checkboxes as you go.

## Hard rules

- **Never add the `INTERNET` permission** (or `ACCESS_NETWORK_STATE`), directly or through a dependency. After adding any library, check the merged manifest: `app/build/intermediates/merged_manifest/debug/.../AndroidManifest.xml`.
- **Don't add dependencies lightly.** No Google Play Services, Firebase, analytics or proprietary SDKs. Approved: Jetpack Glance (widget). Health Connect was considered and dropped.
- **Don't call `createBond()`/pairing** until Phase 0 shows it's safe. Pairing is linked to the watch `Err` state.
- **Don't change BLE byte layouts in `Protocol.kt` by guesswork.** Layouts were confirmed against captures. Anything new needs a captured hex fixture and a test in `ProtocolTest`. Bad writes can soft-brick the watch (see README). Never write to watch memory/firmware areas.
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
| `WatchViewModel.kt` | App logic. Every watch operation goes through `op(label) { … }` (operation mutex + busy flag + error logging). Currently **owns** `BleManager` and `StepStore`. PLAN Phase 0 moves them to app scope. |
| `data/StepStore.kt` | Step history as `filesDir/steps.json`, merged by date (`yyyy-MM-dd`). |
| `ui/*.kt` | Compose screens: Watch (home), Alarms, World, Settings, plus the history chart and theme. |
| `assets/cities_ht.json`, `cities_wt.json` | Home/world city tables (`no, name, tz, dst, rule`). |

Notes:
- Connecting: the watch only advertises briefly after the user holds the bottom-left **C** button.
- `refreshAll()` reads steps with `peek = true`. Only an explicit download commits (consumes hourly data on the watch).
- DST and city changes use the official transaction sequences (`OP_DST_TRANSACTION` types 1/2/3). Keep the order.
- Target test phone: Xiaomi Mi 8, **Android 10 (API 29)**, MIUI. API-31+-only features (e.g. CompanionDeviceService presence) need a fallback that works on 29.

## Conventions

- Put new non-UI logic (stats, CSV, error text, zone matching, scheduling decisions) in plain Kotlin classes with JVM unit tests under `app/src/test`. There are no instrumented tests.
- User-facing text must be plain English with a next step. No raw hex or exception messages (PLAN #7).
- Small prefs live in `SharedPreferences`. Don't add Room/DataStore without a reason.
- Commit only when asked. Work on a feature branch, not `master`.
