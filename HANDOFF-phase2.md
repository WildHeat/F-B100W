# Handoff: Phase 2 (Remember your watch)

Self-contained brief for a new session. Read this, then [CLAUDE.md](CLAUDE.md) (project rules) and [PLAN.md](PLAN.md) (roadmap, status, and the full record of watch findings). You don't need any earlier chat history. Older handoffs ([phase 0](HANDOFF-phase0.md), [phase 1](HANDOFF-phase1.md)) are for reference only.

Last updated: 2026-11-17.

## Where things stand

**Phases 0 and 1 are done**, merged to `master` (PRs #1–#7). Everything below is confirmed on the user's Xiaomi Mi 8 (Android 10) with their Casio F-B100W. 29/29 unit tests pass, and there's no INTERNET permission. A build check enforces that (`check<Variant>NoNetworkPermissions` in `app/build.gradle.kts`).

**Don't start Phase 2 until the user asks.** When they do, branch from up-to-date `master`.

## How the app works now (after Phase 1)

| Piece | What it does |
| --- | --- |
| `TrrrstApp` (Application) | Owns one `BleManager`, `StepStore`, `SyncLogStore` and `WatchRepository` for the whole process. |
| `WatchRepository` | **Only** consumer of `ble.found`: connects, then runs `sync(MANUAL)` on every fresh connection. Owns the operation mutex (`exclusive {}`) and `busy`. Loads the city tables. Remembers the home city. |
| `sync(trigger)` | Needs an open connection. Sends home city + summer time, then the time (`writeHomeCityAndTime`), then downloads steps (**commit**) and saves them. Never throws: returns a `SyncResult` with plain-English errors (`SyncMessages`). Records every attempt in the sync log. |
| `WatchViewModel` | Screen state only. Calls `refreshAll()` (reads) whenever it sees `ConnState` become `READY`, so a reopened screen on a live connection fills in. All button actions go through `op()` → `repo.exclusive`. |
| SharedPreferences `watch` | `homeCity` (city `no`), `homeDstMode` (0 auto / 1 on / 2 off), `syncLog` (JSON, last 10). Put the remembered watch here too. |
| `BleManager` | Unchanged since Phase 0, except it now logs every watch answer (`TrrrstBLE: read xx -> …`). Keep the reconnect fix intact: close on disconnect, `closeGatt()` + `drainQueues()` before connect, ignore stale clients. |

## Watch facts learned (keep in mind)

- **The BLE address equals the MAC the watch reports** in `OP_BLE_FEATURES` (`read 10 -> 1003f822bc33d57f…`, bytes 1–6 reversed = `D5:33:BC:22:F8:03`). `decodeBleFeatures()` already returns it (`vm.mac`).
- The address **stays the same across reconnects, but changes after a watch reset** (`E5:33:8D:36:F8:03` → `D5:33:BC:22:F8:03`).
- The watch **only advertises for a short while after the user holds the bottom-left C button**. A direct `connectGatt` to a saved address won't connect while it isn't advertising. Keep the timeout short and keep the "hold C" instruction.
- The watch ends sessions itself (`status=19`). That's normal.
- **Home city:** the watch sometimes reports no city (`1D` home `fffe`, both `1E` slots `feff000000`). In every case seen, the time had been changed by hand on the watch beforehand. It keeps the city across a normal disconnect. While it has no city, it **ignores a bare time write**. Hence `writeHomeCityAndTime` on every sync.
- **`1E` read limit:** the two `1E` slots are handed out in turn. A third `1E` read with no write in between returns `ff811e`. `writeHomeCityAndTime` starts with a time write to reset that. Any new code that reads `1E` must respect this.
- Date order and Language have no effect on this watch (removed from the UI).
- The official Casio app can't run on the Mi 8, so there are **no reference captures**. New protocol behaviour is worked out by experiment, read-first.

## Rules (from the user, they matter)

1. **The Mi 8 is the user's personal phone.** Only touch `dev.trrrst.watch`. Read-only adb: `logcat -d`, `dumpsys`, `run-as dev.trrrst.watch cat …`. Never `logcat -c`.
2. **The user installs builds** (Android Studio ▶ Run) and **commits/merges themselves.** Build and test locally, then tell them exactly what to test. When they ask, offer a commit message. Don't install, commit or push unless asked.
3. **Never add the INTERNET permission.** The build check catches it. Still check every APK with `aapt`.
4. **Read before write on the watch.** New kinds of writes are tested one at a time, after the user agrees. Phase 2 should need **no new watch writes**.
5. Talk in plain English. The user isn't an Android developer. Keep chat summaries short. Explain test steps concretely (e.g. "hold C on the watch, press Connect", "let the watch disconnect on its own").
6. When the user says "check the logs", read the log and the app's prefs, compare them with what they expected, and report clearly.

## Phase 2 tasks (from PLAN.md)

Goal: the app only connects to *your* watch, and it reconnects faster.

1. **Save the watch** after the first successful connect: address + name (and the reported MAC) in prefs `watch`.
2. **Connect to the saved watch.** Keep it simple, and fall back safely:
   - First try the saved address. Either `adapter.getRemoteDevice(addr)` → `connect()` with a short timeout, or (simpler and more reliable while the watch only advertises after C) a **scan filtered by the saved address** (`ScanFilter.Builder().setDeviceAddress`).
   - If that address isn't seen, fall back to the current scan-by-name. Then **check `OP_BLE_FEATURES`** before syncing (the sync writes!). If it isn't the saved watch, ask the user (see below) instead of syncing.
3. **Settings:** show the remembered watch (name + address) and a **Forget this watch** button. Forgetting goes back to scan-by-name, and the next connected watch is saved.
4. **Unit-test the decision** in a plain class (e.g. `WatchMatcher`): no saved watch → accept any F-B100W and save it. Saved address matches → accept. Different address → treat it as a different watch (or as this watch after a reset, see the question below).
5. **No bonding / `createBond()`.** `BleManager.pair()` exists but must stay unused.

**Pitfalls**
- The sync runs automatically right after `connect()` (in `WatchRepository`'s `found` collector). Any "is this my watch?" check must happen **before** that sync, or a stranger's watch would get our time/city writes and lose its step data.
- A watch reset changes the address. Without care, the user would be locked out of their own watch after a reset. Forget + reconnect has to be easy, and the message has to say what to do.
- Keep `found` consumed only in the repository (the Phase 1.1 pitfall: no auto-reconnect when the screen reopens).
- Android 10: scanning still needs Location on. `ScanCheck` already handles that.

**Ask the user before building:**
- When a *different* F-B100W shows up (e.g. after a reset), should the app (a) ask "Is this your watch? Use it / Cancel", or (b) refuse until they press Forget? Recommend (a).

## Testing Phase 2 on the Mi 8

Unit tests + `assembleDebug` + no INTERNET, then ask the user to:
1. Forget (or fresh install) → hold C → Connect. The watch is saved and shown in Settings.
2. Let it disconnect, then hold C → Connect again. It connects, ideally faster. In the log, check the scan/connect used the saved address.
3. Regression: the time is corrected on connect (set it wrong by hand first), the steps download, and rotating the phone / reopening the app doesn't reconnect by itself.
4. Forget this watch → Connect: works by name again and saves it again.
5. Bluetooth off → Connect still shows the message.

Then read the log and prefs (below) and tick the PLAN.md boxes.

## Environment (this Windows PC)

- Use **Git Bash** (`Bash` tool). There's no `gradlew.bat`, so run `./gradlew`.
- **JDK:** the default `java` and Android Studio's JBR are Java 25, which Gradle 8.14 can't run on. Use:
  ```sh
  export JAVA_HOME="$USERPROFILE/.jdks/corretto-17.0.13"
  export ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"
  ```
- **adb** isn't on PATH: `ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"`. An emulator is often running, so always pass `-s 52b2b2ca` (the Mi 8). Wrap adb calls in `timeout 60`. If it says `waiting for device`, the phone is unplugged, so ask the user to plug it in. `unauthorized` → ask them to accept the USB-debugging prompt.
- If the first dependency download fails with Java DNS errors, retry.

```sh
cd "/c/Users/lowid/Desktop/Research/FB-100 app/F-B100W"
./gradlew testDebugUnitTest assembleDebug
"$ANDROID_HOME/build-tools/36.0.0/aapt.exe" dump permissions app/build/outputs/apk/debug/app-debug.apk | grep -iE "internet|network" || echo "no INTERNET"
```

### Reading what happened on the phone
- Check which build is installed: `"$ADB" -s 52b2b2ca shell dumpsys package dev.trrrst.watch | grep lastUpdateTime`.
- Log (save to the scratchpad, then grep):
  ```sh
  "$ADB" -s 52b2b2ca logcat -d -v time -b main,system > "<scratchpad>/run.log"
  ```
  Find the app's PIDs with `grep "Start proc [0-9]*:dev.trrrst.watch"`. Tags: `TrrrstBLE` (every write **and every watch answer** as hex, disconnects, scan/connect failures), `Trrrst` (op/sync failures with stack traces), `BluetoothGatt` (connect/MTU/discovery, `clientIf=` shows how many clients exist).
- App prefs (home city, sync log, later the saved watch): `"$ADB" -s 52b2b2ca shell run-as dev.trrrst.watch cat shared_prefs/watch.xml`.
- **Crash traces don't appear in logcat on MIUI:** `"$ADB" -s 52b2b2ca shell dumpsys dropbox --print data_app_crash | grep -A40 "dev.trrrst.watch"`. The old `SecurityException: Need BLUETOOTH permission` traces (v0.4, before 14:22 on 2026-11-17) are already fixed.
