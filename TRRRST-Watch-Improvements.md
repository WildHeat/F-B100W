# TRRRST Watch: Improvement Plan

Planned improvements to **TRRRST Watch**, the open-source Android app for the **Casio F-B100W** watch.

- **App source:** https://github.com/taxmeifyoucan/trrrst-watch
- **Version reviewed:** v0.4.0 (security review passed: no internet permission, Bluetooth only, data stays on the phone)
- **Target phone:** Xiaomi Mi 8 (Android 10). The app supports Android 8.0 and newer.

---

## Summary

| #   | Feature                                                | Priority | Why it matters                                        |
| --- | ------------------------------------------------------ | -------- | ----------------------------------------------------- |
| 1   | Automatic background sync                              | High     | The watch syncs every day without you opening the app |
| 2   | Remember your watch                                    | High     | Faster, safer connecting. Needed for #1               |
| 3   | Keep step history safe (CSV export and Health Connect) | High     | History survives uninstalls and phone changes         |
| 4   | Better step stats                                      | Medium   | Weekly/monthly totals, streaks, personal bests        |
| 5   | Home-screen widget                                     | Medium   | See today's steps without opening the app             |
| 6   | Automatic time zone                                    | Medium   | Watch follows you when you travel                     |
| 7   | Clearer feedback                                       | Medium   | Plain-English messages and "last synced" time         |

**Recommended order:** 2 → 1 → 3 → 7 → 4 → 5 → 6

Features 1 and 2 belong together, and 2 must come first. Feature 3 protects your data early. The rest can be added in any order.

---

## 1. Automatic background sync

### The problem

The app only talks to the watch while it is open on screen. The watch has an **auto time sync** setting that reconnects once a day at a chosen time, but nothing on the phone is listening when that happens. In practice, the setting does nothing.

### What we'll build

A small background service that:

- waits for the watch to reconnect at its daily auto-sync time
- **syncs the time** automatically
- **downloads the day's steps** automatically
- shows a quiet notification, e.g. "Watch synced at 08:00 · 6,412 steps"

### How it should work

- Uses an Android **companion device / foreground service** so the system doesn't kill it.
- Only runs briefly when the watch connects, to save battery.
- Can be switched on or off in Settings.

### Xiaomi-specific note

MIUI aggressively stops background apps. The app should show a one-time guide explaining how to:

- turn on **Autostart** for TRRRST Watch
- set **Battery saver** to **No restrictions** for the app

### Done when

- [ ] The watch syncs time and steps at its set time, with the app closed
- [ ] It still works after the phone restarts
- [ ] Battery use stays low (no constant scanning)

---

## 2. Remember your watch

### The problem

Every time it connects, the app searches for _any_ device named "CASIO F-B100W". This is slower than needed, could connect to someone else's watch nearby, and can't support background sync.

### What we'll build

- On first connection, **save the watch's Bluetooth address**.
- Afterwards, **connect directly** to that watch only.
- Add a **"Forget this watch"** button in Settings, for switching watches.

### Done when

- [ ] After the first pairing, the app only connects to your watch
- [ ] Reconnecting is noticeably faster
- [ ] "Forget this watch" clears it and allows pairing a new one

---

## 3. Keep step history safe

### The problem

Step history is saved in one private file inside the app. **If you uninstall the app or change phones, the history is lost.** Steps also can't be seen in any other fitness app.

### What we'll build

**a) Export to CSV**

- An **Export** button that saves all step history as a `.csv` file.
- Opens in Excel, Google Sheets, etc.
- Columns: `date, steps, distance_m`

**b) Import from CSV**

- Load a previously exported file back in, e.g. on a new phone.

**c) Health Connect integration**

- Send daily steps and distance to **Health Connect**, Google's on-phone health data hub.
- Steps then appear in Google Fit and other fitness apps.
- Works **without internet**. Data stays on the phone.
- On the Mi 8 (Android 10), Health Connect is installed as a separate app from the Play Store.
- Optional and off by default. The user grants permission.

### Done when

- [ ] Export creates a valid CSV with all days
- [ ] Import restores history without creating duplicate days
- [ ] With Health Connect on, new steps appear there after each sync

---

## 4. Better step stats

### What we'll build

- **Weekly and monthly totals** and **daily averages**
- **Goal streaks**, e.g. "5 days in a row over your goal"
- **Personal bests**: best day, best week
- **Goal progress ring** on the home screen (uses the step goal already set on the watch)

### Done when

- [ ] Stats are correct against the stored daily history
- [ ] Streaks reset correctly when a day misses the goal

---

## 5. Home-screen widget

### What we'll build

A small widget for the phone's home screen showing:

- **today's steps** and progress towards the goal
- **when the watch last synced**
- tap to open the app

### Done when

- [ ] The widget updates after every sync (including background syncs from feature 1)

---

## 6. Automatic time zone

### The problem

When you travel to another time zone, the watch's home city must be changed by hand.

### What we'll build

- Detect when the **phone's time zone changes**.
- Automatically set the watch's **home city** to the matching city from the built-in list.
- Keep summer time (DST) in automatic mode.
- Optional, with a setting to turn it off.

### Done when

- [ ] Changing the phone's time zone updates the watch on the next sync
- [ ] The correct time shows on the watch after travel

---

## 7. Clearer feedback

### The problem

Errors are shown as raw technical messages (e.g. "watch error 0xFF for 0x09"), and there's no way to see when the watch last synced.

### What we'll build

- **"Last synced: today at 08:00"** on the home screen.
- **Plain-English errors** with a suggested fix, for example:
  - "Watch not found. Hold the **C** button on the watch for 3 seconds."
  - "Bluetooth is off. Turn it on and try again."
  - "Connection lost. Bring the watch closer and try again."
- A small **sync history** list (last 10 syncs, success or failure).

### Done when

- [ ] No raw hex errors are shown to the user
- [ ] Every error tells the user what to do next

---

## Before we start

### Tools needed

1. **Android Studio** (free): https://developer.android.com/studio
2. On the Mi 8, turn on **Developer options**: Settings → About phone → tap **MIUI version** 7 times
3. Turn on **USB debugging** (and **Install via USB** on Xiaomi) in Developer options
4. A USB cable to connect the phone to the PC

### Where the changes go (for reference)

| Area                 | File                                                   |
| -------------------- | ------------------------------------------------------ |
| Bluetooth connection | `app/src/main/java/dev/trrrst/watch/ble/BleManager.kt` |
| App logic            | `app/src/main/java/dev/trrrst/watch/WatchViewModel.kt` |
| Step storage         | `app/src/main/java/dev/trrrst/watch/data/StepStore.kt` |
| Screens              | `app/src/main/java/dev/trrrst/watch/ui/`               |
| Permissions          | `app/src/main/AndroidManifest.xml`                     |

### Important rules

- **Never add internet permission.** Keeping the app offline is what makes it trustworthy.
- **Test each feature on the real watch** before moving to the next.
- **Keep changes small**, one feature at a time.

### License

The app is licensed under **AGPL-3.0**:

- We can change it freely for personal use.
- If the modified app is **shared with anyone**, its source code must be shared too, under the same license.
- Recommended: offer the improvements back to the original developer as **pull requests**. Everyone benefits, and you keep receiving their updates.
