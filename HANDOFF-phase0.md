# Handoff: Phase 0, first working connection on the Mi 8

Self-contained brief for a new session. Read this, then [CLAUDE.md](CLAUDE.md) (project rules) and Phase 0 of [PLAN.md](PLAN.md) (crash record). You don't need any earlier chat history.

## Goal

Get TRRRST Watch connecting to the user's Casio F-B100W from their Xiaomi Mi 8 (Android 10) **without crashing**. First reading from the watch, then (one at a time) writing to it. Phase 0 is done when Connect works every time, the watch never shows `Err`, and **Sync time** + **step download** work.

## Background (what happened so far, 2026-11-17)

- The GitHub release v0.4 crashed the moment **Connect** was pressed. It has **never** worked on this phone, and the watch has **never** been connected to any app (the official Casio app doesn't run on the Mi 8).
- After each crash the watch showed `Err` (no code). The watch is otherwise fine. `Err` was just the watch's own connection attempt timing out.
- **Confirmed cause:** `SecurityException: Need BLUETOOTH permission` at `BleManager.scan()` (`BleManager.kt:51`). The manifest only had Android 12+ Bluetooth permissions. The app died **before any Bluetooth traffic**, so the BLE protocol code has never run against this watch.
- **Done already:**
  - Branch `fix/android10-bluetooth-permissions` created from `master`.
  - `app/src/main/AndroidManifest.xml`: added `BLUETOOTH` and `BLUETOOTH_ADMIN`, both `maxSdkVersion="30"`. **Not committed.** The user hasn't asked for a commit, so ask before committing.
  - `./gradlew testDebugUnitTest assembleDebug` passes (7/7 `ProtocolTest`). APK at `app/build/outputs/apk/debug/app-debug.apk`. `aapt dump permissions` confirms both new permissions and no INTERNET.
  - **v0.4 has been uninstalled** from the phone. **Nothing is installed now.**
- Untracked files in the repo root: `CLAUDE.md`, `PLAN.md`, `TRRRST-Watch-Improvements.md` (the feature wishlist), and this file. Don't delete them.

## Rules (from the user, they matter)

1. **The Mi 8 is the user's personal phone.** Only touch the `dev.trrrst.watch` app on it: install/uninstall it, read logs and crash reports. No system settings, no other apps. If something else needs changing, ask the user to do it.
2. **Never add the INTERNET permission.** Check the APK after every build (command below).
3. **Read before write on the watch.** The first connection only reads. Writes come one at a time, after the user agrees. A bad write can soft-brick the watch (the upstream author had to open one up).
4. **Ask before** installing anything, committing, or doing writes. Don't start feature work (Phases 1+) without being asked.
5. Talk to the user in plain English. They aren't an Android developer.

## Environment quirks (this Windows PC)

- **Use Git Bash** (`Bash` tool). There's **no `gradlew.bat`**; run `./gradlew`.
- **JDK:** the default `java` and Android Studio's JBR are **Java 25, which Gradle 8.14 can't run on** (fails with just `25.0.4`). Use the installed JDK 17:
  ```sh
  export JAVA_HOME="$USERPROFILE/.jdks/corretto-17.0.13"
  export ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"
  ```
- **adb** isn't on PATH: `ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"`. The phone shows up as `MI_8` / `dipper`, USB debugging and "Install via USB" are on.
- **Network:** the first dependency download failed with Java DNS errors (`Could not GET … repo.maven.apache.org`) while `curl` worked. A plain retry fixed it. Dependencies are now cached.
- A clean build takes ~7 minutes. Use `timeout: 600000` and `--no-daemon` (or leave the daemon running for faster rebuilds).
- **MIUI keeps crash stack traces out of logcat.** Read them with:
  ```sh
  "$ADB" shell dumpsys dropbox --print data_app_crash | grep -A40 "dev.trrrst.watch"
  ```
  Don't clear logcat (`logcat -c`). Use `-T 1` to only record new lines.

Useful commands:
```sh
cd "/c/Users/lowid/Desktop/Research/FB-100 app/F-B100W"
./gradlew --no-daemon testDebugUnitTest assembleDebug
"$ANDROID_HOME/build-tools/36.0.0/aapt.exe" dump permissions app/build/outputs/apk/debug/app-debug.apk   # must NOT list INTERNET
"$ADB" install -r app/build/outputs/apk/debug/app-debug.apk
# record logs while the user tests (background, 3 min):
timeout 180 "$ADB" logcat -v time -T 1 -b main,system,crash > "<scratchpad>/run.log"
```
Then search the log for `TrrrstBLE|Trrrst|AndroidRuntime|BluetoothGatt|bt_btif|dev.trrrst`.

## Steps

### 1. Install and first connect (reads only)
1. Ask the user before installing. Then `adb install -r` the debug APK.
2. Tell the user: open the app → grant the Location/Bluetooth permission prompt → make sure Bluetooth **and** Location are on → hold **C** on the watch → press **Connect** once. Start the logcat recording first.
3. What Connect does: `scan()` (looks for name `CASIO F-B100W`) → `connect()` (GATT, MTU 512, discover services, enable notifications on 0x2D) → `refreshAll()`.
   - `refreshAll()` only **reads** opcodes 0x10, 0x20, 0x28, 0x15, 0x16, 0x13, 0x11, 0x2D, 0x18, 0x1D, 0x1E×2.
   - Then it peeks steps (`receive(CAT_EXERCISE, commit=false)`). The peek sends the "abort" command (3), so the step data stays on the watch.
   - **No writes happen on connect.**
4. Check the result with the user: battery %, firmware version, alarms, settings, steps on the home screen. Do they match the watch? Note anything that looks wrong.
5. If it crashes or fails, get the dropbox trace and logcat, find the failing step (scan / connect / MTU / services / notify / which read), and fix it. Possible Android 10 issues:
   - a scan that never finds the watch (watch only advertises briefly after holding C)
   - `requestMtu(512)` not supported
   - a `GATT 133` connect error, which is common on Xiaomi. Retry once, or set `autoConnect=false` (already set).

### 2. Safety net (code change, small)
Stop Bluetooth failures from crashing the app:
- `BleManager.scan()`: wrap `bluetoothLeScanner`/`startScan` in `try/catch (SecurityException)`. Handle adapter missing or Bluetooth off (`bluetoothAdapter?.isEnabled != true`). On failure: set `ConnState.ERROR` + a plain-English `statusLine` ("Bluetooth is off. Turn it on and try again.").
- On Android ≤ 30, check that Location is **switched on** (`LocationManager.isLocationEnabled`, API 28+) before scanning. Show "Location is off. Android 10 needs it on to find the watch." if not.
- `WatchViewModel`'s `ble.found.collectLatest { ble.connect(dev) … }` runs outside `op()`. Wrap it so a connect exception logs and resets state instead of crashing.
- Don't touch `Protocol.kt` byte layouts.
- Rebuild, re-check permissions, reinstall, re-test.

### 3. Writes, one at a time (ask the user before each)
1. **Sync time** (Watch tab). Writes opcode 0x09 with reason 1. Check the watch shows the right time.
2. **Download steps** (commit=true). This *consumes* the hourly data on the watch. Check the numbers match the watch.
3. Stop there. Settings/alarm/city writes are outside Phase 0. Just note whether the user wants them tested.

### 4. Wrap up
- Update `PLAN.md` Phase 0 checkboxes and the Open questions (does the protocol work on this watch? anything odd?). Record any new crash or `Err` the same way as the existing crash record.
- Note the watch's Bluetooth address shown when connecting (`device.address` in logs, and the MAC shown in the app from opcode 0x10). Phase 2 needs to know whether it stays the same between connections.
- Ask the user whether to commit the branch. Commit message ends with the attribution line from the system reminder, if present.
- Report in plain English: what works, what doesn't, what's next (Phase 1 in `PLAN.md`).
