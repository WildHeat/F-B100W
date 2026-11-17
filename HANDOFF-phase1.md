# Handoff: Phase 1 (groundwork)

Self-contained brief for a new session. Read this, then [CLAUDE.md](CLAUDE.md) (project rules) and [PLAN.md](PLAN.md) (roadmap, status). You don't need any earlier chat history. The Phase 0 story (crash, reconnect bug, auto-sync bug) is in [HANDOFF-phase0.md](HANDOFF-phase0.md) and PLAN.md's Phase 0 section, for reference only.

Last updated: 2026-11-17.

## Where things stand

**Phase 0 is done.** The app works on the user's Xiaomi Mi 8 (Android 10) with their Casio F-B100W, and the user confirmed all of this on the real watch:
- connect, reconnect after the watch ends the session, Sync time
- all settings writes (settings, BLE settings, timer, profile, home city, alarms)
- step download (the committing one)
- Bluetooth-off and Location-off messages instead of crashes

All of it is merged to `master` (PRs #1 and #2). 14/14 unit tests pass. No INTERNET permission.

**Don't start Phase 1 until the user asks.** When they do, start each item on a new branch from up-to-date `master`.

Facts learned (keep in mind):
- The watch's BLE address **stays the same across reconnects but changes after a watch reset** (`E5:33:8D:36:F8:03` → `D5:33:BC:22:F8:03`). Matters for Phase 2.
- The watch ends sessions itself (`onClientConnectionState status=19`). That's normal.
- Auto time sync is set on the watch side only. Nothing on the phone answers it until Phase 3.
- The official Casio app can't run on the Mi 8, so there are **no reference captures**. New protocol behaviour is worked out by experiment, read-first.

## Rules (from the user, they matter)

1. **The Mi 8 is the user's personal phone.** Only touch the `dev.trrrst.watch` app: read its logs and crash reports. Read-only adb (`logcat -d`, `dumpsys`). Never `logcat -c`.
2. **The user installs builds** (Android Studio ▶ Run) and **commits/opens PRs themselves.** Build and test locally, then tell them exactly what to test. Don't install, commit or push unless asked.
3. **Never add the INTERNET permission.** Check every built APK.
4. **Read before write on the watch.** New kinds of writes are tested one at a time, after the user agrees. Phase 1 needs **no new watch writes**.
5. Talk in plain English. The user isn't an Android developer. Keep chat summaries short.

## Phase 1 tasks

The goal is groundwork with **no visible change**, so later phases (background sync, widget, time-zone receiver) can reach the Bluetooth connection and step data without the screen being open. Suggested order and one branch each:

### 1.3 No-INTERNET build check (do first: small, protects everything after)
- A Gradle task in `app/build.gradle.kts` that reads the **merged** manifest after `process<Variant>MainManifest` and fails if it contains `android.permission.INTERNET` or `ACCESS_NETWORK_STATE`.
- Hook it into `assemble`/`check` so CI (`.github/workflows/android.yml`) runs it too.
- Find the real merged-manifest path in `app/build/intermediates/` after a build. Don't hard-code a guessed one. Prefer the AGP variant API (`SingleArtifact.MERGED_MANIFEST`) if it's simple.
- Verify: temporarily add INTERNET to the manifest, check the build fails, then remove it.

### 1.1 App-scoped core
- New `TrrrstApp : Application` (register `android:name=".TrrrstApp"` in `AndroidManifest.xml`) owning one `BleManager` and one `StepStore`. `WatchViewModel` gets them from `(app as TrrrstApp)` instead of creating its own (`WatchViewModel.kt`, `val ble = BleManager(app)` / `val steps = StepStore(app)`).
- A thin `WatchRepository` (also app-scoped) for the shared sync logic (1.2).
- **Pitfall:** `BleManager.found` is a `StateFlow`, and `WatchViewModel.init` does `ble.found.collectLatest { connect(dev) }`. Once `BleManager` outlives the ViewModel, a new ViewModel (screen rotation, app reopened) would immediately get the old device again and **reconnect by itself**. Fix this: clear `found` after it's consumed, or switch it to a one-shot `SharedFlow`/`Channel`, or move the connect into the repository.
- **Pitfall:** the watch state (`mac`, `battery`, `alarms`, `basic`, …) lives in the ViewModel. If the connection survives but the ViewModel is new, the screen would show "connected" with empty data. Either move that state into the repository or call `refreshAll()` when a new ViewModel sees `ConnState.READY`. Keep it simple.
- Keep the `BleManager` internals unchanged (the reconnect fix depends on them: close on disconnect, `closeGatt()` + `drainQueues()` before connect, ignore stale clients).

### 1.2 Shared sync routine
- One function in `WatchRepository`: connect (or use the open connection) → sync time → download steps (commit) → save to `StepStore` → write a `SyncRecord` (1.4). It returns a result rather than throwing.
- Used later by background sync (Phase 3). For now the UI can keep its buttons. Only add a "Sync now" button if the user wants it, and ask first.
- **Ask the user:** should the automatic refresh on connect keep only *peeking* at steps (current behaviour, `fetchSteps(peek = true)`), with only explicit Download/Sync committing? The default is to keep peeking.

### 1.4 Sync log
- `data class SyncRecord(time, trigger /* manual|background */, ok, steps?, error?)`, last 10 kept, stored as JSON in `SharedPreferences` (no Room/DataStore).
- Put the trimming and JSON round-trip in a plain class with JVM unit tests (`app/src/test`). Error text must be plain English (no hex or exception messages).
- Not shown in the UI yet. Phase 5 adds the history list, and Phase 7 the widget.

### Testing each item
Unit tests + `assembleDebug` + no INTERNET, then ask the user to run this **regression check** on the Mi 8 (Phase 1 shouldn't change anything they can see):
1. Connect, let the watch disconnect, connect again.
2. Sync time, change one setting, Download steps.
3. **Rotate the phone / leave the app and come back** while connected and while disconnected. It must not reconnect by itself or show empty data (the 1.1 pitfall).
4. Bluetooth off → Connect still shows the message.

Then read the log (below) and tick the PLAN.md boxes.

## After Phase 1 (only when the user asks)

Feature order: Remember your watch (Phase 2) → Background sync (3) → CSV export/import (4) → Clearer feedback (5) → Stats (6) → Glance widget (7) → Automatic time zone (8). Health Connect was **dropped**. Details are in PLAN.md.

## Environment (this Windows PC)

- Use **Git Bash** (`Bash` tool). There's no `gradlew.bat`, so run `./gradlew`.
- **JDK:** the default `java` and Android Studio's JBR are Java 25, which Gradle 8.14 can't run on. Use:
  ```sh
  export JAVA_HOME="$USERPROFILE/.jdks/corretto-17.0.13"
  export ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"
  ```
- **adb** isn't on PATH: `ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"`. An emulator is often running, so always pass `-s 52b2b2ca` (the Mi 8). `offline` → `"$ADB" reconnect offline`. `unauthorized` → ask the user to accept the USB-debugging prompt. Hangs on `waiting for device` → phone unplugged, so ask the user.
- If the first dependency download fails with Java DNS errors, retry.

```sh
cd "/c/Users/lowid/Desktop/Research/FB-100 app/F-B100W"
./gradlew testDebugUnitTest assembleDebug
"$ANDROID_HOME/build-tools/36.0.0/aapt.exe" dump permissions app/build/outputs/apk/debug/app-debug.apk | grep -iE "internet|network" || echo "no INTERNET"
```

### Reading what happened on the phone
- The user tests first, then you read the log. The buffer keeps recent sessions:
  ```sh
  "$ADB" -s 52b2b2ca logcat -d -v time -b main,system > "<scratchpad>/run.log"
  ```
  Find the app's PIDs with `grep "Start proc [0-9]*:dev.trrrst.watch"`. Tags: `TrrrstBLE` (every write as hex, disconnects, scan/connect failures), `Trrrst` (op failures with stack traces), `BluetoothGatt` (connect/MTU/discovery, `clientIf=` shows how many clients exist).
- **Crash traces don't appear in logcat on MIUI:**
  ```sh
  "$ADB" -s 52b2b2ca shell dumpsys dropbox --print data_app_crash | grep -A40 "dev.trrrst.watch"
  ```
  Old `SecurityException: Need BLUETOOTH permission` crashes from 14:22 on 2026-11-17 and earlier are from v0.4 and already fixed.
