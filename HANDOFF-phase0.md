# Handoff: finish Phase 0, then start Phase 1

Self-contained brief for a new session. Read this, then [CLAUDE.md](CLAUDE.md) (project rules) and [PLAN.md](PLAN.md) (roadmap, bug records, status). You don't need any earlier chat history.

Last updated: 2026-11-17.

## Where things stand

The app **works on the user's Xiaomi Mi 8 (Android 10)** with their Casio F-B100W. All of this is on branch `fix/android10-bluetooth-permissions` (commit `36738d0` plus a follow-up). The user is opening a PR for it.

Fixed and **confirmed on the real watch by the user**:

| Issue | Cause | Fix |
| --- | --- | --- |
| Crash on Connect | Manifest lacked Android ≤11 permissions (`SecurityException: Need BLUETOOTH permission` in `BleManager.scan()`) | `BLUETOOTH` + `BLUETOOTH_ADMIN` with `maxSdkVersion="30"` |
| Second connect fails (`watch write failed`, watch keeps flashing CNCT) | `gatt.close()` never called when the *watch* disconnected; Android revived the old client next to the new one | `BleManager`: close on `STATE_DISCONNECTED`, `closeGatt()` + `drainQueues()` before every connect, ignore stale-client callbacks, fail in-flight write on disconnect |
| Auto time sync switch flips back off | Fresh watch stores `FF FF`; enabling sent hour 127 / minute 255 | Default 08:00 in the UI, `BleSettings.hasAutoSyncTime`, `withAutoSync` rejects invalid times, time-picker saves when already on. Test `freshWatchHasNoAutoSyncTime`. |
| Date order / Language do nothing | Watch accepts the `0x13` bytes 4/5 but its display never changes | Rows removed from Settings. `Basic.encode()` still preserves the bytes. |

User-confirmed working: connect, reconnect after the watch disconnects, Sync time, all settings writes, and settings surviving reconnects. 8/8 unit tests pass. The APK has no INTERNET permission.

Facts learned (keep in mind):
- The watch's BLE address **stays the same across reconnects but changes after a watch reset** (`E5:33:8D:36:F8:03` → `D5:33:BC:22:F8:03`). This matters for Phase 2 "Remember your watch".
- The watch ends sessions itself (`onClientConnectionState status=19`). That's normal.
- Auto time sync is set on the watch side only. At that time the watch tries to connect, but nothing on the phone answers until Phase 3 (background sync).
- The official Casio app can't run on the Mi 8, so there are **no reference captures**. New protocol behaviour has to be worked out by experiment, read-first.

## Rules (from the user, they matter)

1. **The Mi 8 is the user's personal phone.** Only touch the `dev.trrrst.watch` app: read its logs and crash reports. No system settings, no other apps. Use read-only adb commands (`logcat -d`, `dumpsys`). Never `logcat -c`.
2. **The user installs builds themselves** via Android Studio ▶ Run, and **commits/opens PRs themselves.** Build and test locally, then tell them what to test. Don't install, commit or push unless asked.
3. **Never add the INTERNET permission.** Check every built APK.
4. **Read before write on the watch.** New kinds of writes are tested one at a time, after the user agrees. A bad write can soft-brick the watch.
5. Don't start a new phase without being asked. Talk in plain English. The user isn't an Android developer.

## Environment (this Windows PC)

- Use **Git Bash** (`Bash` tool). There's no `gradlew.bat`, so run `./gradlew`.
- **JDK:** the default `java` and Android Studio's JBR are Java 25, which Gradle 8.14 can't run on (fails with just `25.0.4`). Use:
  ```sh
  export JAVA_HOME="$USERPROFILE/.jdks/corretto-17.0.13"
  export ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"
  ```
  In Android Studio the user has set **Settings → Build Tools → Gradle → Gradle JDK = corretto-17** for the same reason.
- **adb** isn't on PATH: `ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"`. An **emulator is often running too**, so always pass `-s 52b2b2ca` (the Mi 8). If the phone shows `offline`, `"$ADB" reconnect offline`. If it then shows `unauthorized`, ask the user to accept the USB-debugging prompt. If adb hangs on `waiting for device`, the phone is unplugged, so ask the user.
- The first dependency download once failed with Java DNS errors while `curl` worked. A plain retry fixed it.

Commands:
```sh
cd "/c/Users/lowid/Desktop/Research/FB-100 app/F-B100W"
./gradlew testDebugUnitTest assembleDebug
"$ANDROID_HOME/build-tools/36.0.0/aapt.exe" dump permissions app/build/outputs/apk/debug/app-debug.apk | grep -iE "internet|network" || echo "no INTERNET"
```

### Reading what happened on the phone
- The user tests first, then you read the log afterwards. **The phone's log buffer keeps recent sessions**, so there's no need to record live:
  ```sh
  "$ADB" -s 52b2b2ca logcat -d -v time -b main,system > "<scratchpad>/run.log"
  ```
  Find the app's PIDs with `grep "Start proc [0-9]*:dev.trrrst.watch"`. Useful tags: `TrrrstBLE` (every write, as hex), `Trrrst` (op failures with stack traces), `BluetoothGatt` (connect/MTU/discovery/`onClientConnectionState status=…`, `clientIf=` shows how many clients exist).
- **Crash traces don't appear in logcat on MIUI.** Read them with:
  ```sh
  "$ADB" -s 52b2b2ca shell dumpsys dropbox --print data_app_crash | grep -A40 "dev.trrrst.watch"
  ```

## What's left in Phase 0

1. **Step download (commit) not yet confirmed.** The home screen's **Download** button calls `fetchSteps(peek=false)`. This *consumes* the hourly data on the watch. Ask the user to try it once and compare with the watch's step count. Check the log for `drsp`/`steps` errors.
2. **Safety net, so Bluetooth problems show a message instead of crashing** (small code change):
   - `BleManager.scan()`: handle a missing adapter / Bluetooth off (`bluetoothAdapter?.isEnabled != true`) and `try/catch (SecurityException)` around `bluetoothLeScanner`/`startScan`. On failure: `ConnState.ERROR` + a plain-English `statusLine` ("Bluetooth is off. Turn it on and try again.").
   - On Android ≤ 30: if Location is **switched off** (`LocationManager.isLocationEnabled`, API 28+), show "Location is off. Android 10 needs it on to find the watch." instead of scanning.
   - `WatchViewModel` init: `ble.found.collectLatest { if (ble.connect(dev)) refreshAll() }` runs outside `op()`. Wrap it so a connect exception logs and resets state instead of crashing.
   - Test ideas for the user: press Connect with Bluetooth off, and with Location off. The app should stay open and explain what to do.
3. Tick the remaining Phase 0 boxes in `PLAN.md` and tell the user what to commit.

## After Phase 0 (only when the user asks)

**Phase 1 groundwork** in `PLAN.md`:
- move `BleManager`/`StepStore` to an app-scoped `TrrrstApp`
- one shared sync routine
- a build check that fails if INTERNET appears
- a `SyncRecord` log

Then the feature order is: Remember your watch → Background sync → CSV export/import → Clearer feedback → Stats → Glance widget → Automatic time zone. Health Connect was **dropped**.
