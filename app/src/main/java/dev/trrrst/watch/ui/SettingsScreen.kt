package dev.trrrst.watch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.trrrst.watch.WatchViewModel
import dev.trrrst.watch.ble.BleManager
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.trrrst.watch.listen.ListenScheduler
import dev.trrrst.watch.listen.PhoneSettings
import dev.trrrst.watch.listen.PhoneSetup
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val PERIODS = listOf(3, 5, 10, 60, 90, 120)

@Composable
fun SettingsScreen(vm: WatchViewModel) {
    val ctx = LocalContext.current
    val b = vm.basic
    val bs = vm.bleSettings
    val prof = vm.profile
    val timer = vm.timerRaw
    if (b == null || bs == null || prof == null || timer == null) {
        // The remembered watch can be seen and forgotten without a connection.
        Column(
            // Scrolls: the Background test card and Phone setup box don't fit on one screen.
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            YourWatchSection(vm)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            ListenTestSection(vm)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Connect to the watch to change its settings.")
        }
        return
    }


    var period by remember(bs) { mutableStateOf(bs.connectingPeriod) }
    var autoOn by remember(bs) { mutableStateOf(bs.autoSyncEnabled) }
    var goal by remember(prof) { mutableStateOf(prof.stepGoal.toString()) }
    var height by remember(prof) { mutableStateOf(prof.height.toString()) }
    var weight by remember(prof) { mutableStateOf(prof.weight.toString()) }
    var th by remember(timer) { mutableStateOf(timer[1].toInt()) }
    var tm by remember(timer) { mutableStateOf(timer[2].toInt()) }
    var ts by remember(timer) { mutableStateOf(timer[3].toInt()) }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(vertical = 10.dp)
    ) {
        item { YourWatchSection(vm) }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { ListenTestSection(vm) }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { Text("Connected watch info", style = MaterialTheme.typography.titleSmall) }
        item {
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("CASIO F-B100W", style = MaterialTheme.typography.bodyLarge)
                    Text("MAC ${vm.mac}", style = MaterialTheme.typography.bodySmall)
                    Text(vm.version, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { Text("Watch", style = MaterialTheme.typography.titleSmall) }
        item { SettingSwitch("24-hour display", b.h24) { vm.saveBasic { c -> c.copy(h24 = it) } } }
        item { SettingSwitch("Key sounds", b.buttonTone) { vm.saveBasic { c -> c.copy(buttonTone = it) } } }
        item { SettingSwitch("Power saving", b.powerSaving) { vm.saveBasic { c -> c.copy(powerSaving = it) } } }
        item { SettingSwitch("Long light (3 s)", b.lightLong) { vm.saveBasic { c -> c.copy(lightLong = it) } } }
        // Date order and Language were removed (2026-11-17): the F-B100W accepts the 0x13
        // bytes but its display doesn't change. Basic.encode() still preserves them.
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { Text("Find phone", style = MaterialTheme.typography.titleSmall) }
        item {
            SettingSwitch("Play sound on this phone", vm.finderSoundEnabled) { vm.finderSoundEnabled = it }
            ChoiceRow(
                "Sound", listOf("Alarm", "Notification", "Ringtone"),
                when (vm.finderSoundType) {
                    android.media.RingtoneManager.TYPE_NOTIFICATION -> 1
                    android.media.RingtoneManager.TYPE_RINGTONE -> 2
                    else -> 0
                }
            ) {
                vm.setFinderSound(
                    when (it) {
                        1 -> android.media.RingtoneManager.TYPE_NOTIFICATION
                        2 -> android.media.RingtoneManager.TYPE_RINGTONE
                        else -> android.media.RingtoneManager.TYPE_ALARM
                    }
                )
            }
        }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { Text("Connection", style = MaterialTheme.typography.titleSmall) }
        item {
            ChoiceRow("Connection time", PERIODS.map { "$it min" }, PERIODS.indexOf(period).coerceAtLeast(0)) {
                period = PERIODS[it]
                vm.saveConnPeriod(period)
            }
        }
        item {
            SettingSwitch("Auto time sync", autoOn) { autoOn = it; vm.saveAutoSync(it) }
            Text(
                "The watch asks this phone for the time at about 00:30, 06:30, 12:30 and 18:30.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { Text("Profile & goal", style = MaterialTheme.typography.titleSmall) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField("Goal", goal, Modifier.weight(1f)) { goal = it }
                NumField("Height", height, Modifier.weight(1f)) { height = it }
                NumField("Weight", weight, Modifier.weight(1f)) { weight = it }
            }
            Button(onClick = {
                vm.saveProfile(
                    goal.toIntOrNull() ?: 10000,
                    height.toIntOrNull() ?: 0,
                    weight.toIntOrNull() ?: 0
                )
            }, enabled = !vm.busy) { Text("Save profile") }
        }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { Text("Timer", style = MaterialTheme.typography.titleSmall) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField("H", th.toString(), Modifier.weight(1f)) { th = it.toIntOrNull() ?: 0 }
                NumField("M", tm.toString(), Modifier.weight(1f)) { tm = it.toIntOrNull() ?: 0 }
                NumField("S", ts.toString(), Modifier.weight(1f)) { ts = it.toIntOrNull() ?: 0 }
            }
            Button(onClick = { vm.saveTimer(th, tm, ts) }, enabled = !vm.busy) { Text("Save timer") }
        }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { Text("Appearance", style = MaterialTheme.typography.titleSmall) }
        item { AccentColorPicker(vm) }
    }
}

/** The remembered watch (PLAN Phase 2) and the Forget button. */
@Composable
private fun YourWatchSection(vm: WatchViewModel) {
    val saved = vm.savedWatch
    var confirm by remember { mutableStateOf(false) }
    Text("Your watch", style = MaterialTheme.typography.titleSmall)
    Card {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (saved == null) {
                Text("No watch saved yet. Connect once to save it.")
            } else {
                Text(saved.name, style = MaterialTheme.typography.bodyLarge)
                Text("Address ${saved.address}", style = MaterialTheme.typography.bodySmall)
                Text(
                    "The app only syncs with this watch. If you reset the watch or switch to " +
                        "another one, forget it here (or confirm the new watch when asked).",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = { confirm = true }) { Text("Forget this watch") }
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Forget this watch?") },
            text = { Text("The app will connect to the next F-B100W it finds and remember that one instead.") },
            confirmButton = {
                TextButton(onClick = { confirm = false; vm.forgetWatch() }) { Text("Forget") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

/** Switch for the background listening test (PLAN Phase 3, step 1). */
@Composable
private fun ListenTestSection(vm: WatchViewModel) {
    val st = vm.listenStatus
    Text("Background test", style = MaterialTheme.typography.titleSmall)
    Card {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Listen when the watch syncs", Modifier.weight(1f))
                Switch(checked = st.testOn, onCheckedChange = { vm.setListenTest(it) })
            }
            Text(
                "Test only. Around the watch's sync times (about 00:30, 06:30, 12:30 and 18:30), " +
                    "the phone waits 30 minutes for your watch to call, connects once to note why it " +
                    "called, and logs what happened. Nothing on the watch is changed, so the watch " +
                    "shows Err or gives up afterwards, which is harmless.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(listenStatusText(st, vm.savedWatch != null), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { vm.listenNow() }) { Text("Listen now for 5 minutes") }
            Text(
                "Then press D on the watch (in normal time mode, phone within 1 m) to make it sync now.",
                style = MaterialTheme.typography.bodySmall,
            )
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Text("Paired with this phone: ${vm.pairedText()}", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(
                onClick = { vm.pairTest() },
                enabled = vm.connState == BleManager.ConnState.READY && !vm.busy,
            ) { Text("Pair with this watch (test)") }
            Text(
                "Casio's manual says the watch only asks a paired phone for the time. Connect first, " +
                    "then pair; if Android asks, tap Pair. To undo: unpair the watch in the phone's " +
                    "Bluetooth settings, and on the watch hold C 1 s, hold A 2 s, press C twice, then " +
                    "hold B 2 s until CLR stops flashing.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (vm.pairMessage.isNotEmpty()) Text(vm.pairMessage, style = MaterialTheme.typography.bodyMedium)
        }
    }
    Spacer(Modifier.height(8.dp))
    PhoneSetupCard(vm)
}

/** Phone setup box (PLAN Phase 3, Part 2): what stops the listening alarms on this phone. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhoneSetupCard(vm: WatchViewModel) {
    val ctx = LocalContext.current
    // The user changes these in Android's settings and comes back: check again then.
    LifecycleResumeEffect(Unit) {
        vm.refreshPhoneSetup()
        onPauseOrDispose { }
    }
    val s = vm.phoneSetup
    Card {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Phone setup", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Phones save battery by holding back apps in the background, so the listening " +
                    "times can start hours late. To keep them on time:",
                style = MaterialTheme.typography.bodySmall,
            )
            PhoneSetup.steps(s).forEachIndexed { i, step ->
                Text("${i + 1}. $step", style = MaterialTheme.typography.bodySmall)
            }
            Text(PhoneSetup.bluetoothLine(s), style = MaterialTheme.typography.bodyMedium)
            Text(PhoneSetup.batteryLine(s), style = MaterialTheme.typography.bodyMedium)
            Text(PhoneSetup.alarmLine(s), style = MaterialTheme.typography.bodyMedium)
            if (s.isXiaomi) Text("Autostart: the app can't check this, please look yourself.",
                style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (s.isXiaomi) OutlinedButton(onClick = { PhoneSettings.openAutostart(ctx) }) { Text("Autostart") }
                OutlinedButton(onClick = { PhoneSettings.openAppInfo(ctx) }) { Text("App info") }
                OutlinedButton(onClick = { PhoneSettings.openBatteryOptimisation(ctx) }) { Text("Battery optimisation") }
            }
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            OutlinedButton(onClick = { vm.testWakeUp() }) { Text("Test the wake-up") }
            Text(
                "Sets a test alarm 3 minutes from now and listens for 5 minutes. Swipe the app away " +
                    "and lock the phone; the log then shows how late the alarm came. Works with the " +
                    "switch above on or off.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (vm.wakeTestMessage.isNotEmpty()) Text(vm.wakeTestMessage, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun listenStatusText(st: ListenScheduler.Status, hasSavedWatch: Boolean): String {
    val main = when {
        !st.testOn -> "Off."
        else -> st.next?.let {
            val day = when (it.start.toLocalDate()) {
                LocalDate.now() -> "today"
                LocalDate.now().plusDays(1) -> "tomorrow"
                else -> it.start.toLocalDate().toString()
            }
            val fmt = DateTimeFormatter.ofPattern("HH:mm")
            "Next listening: $day ${it.start.format(fmt)} to ${it.end.format(fmt)} (watch sync at ${it.label})."
        } ?: "On."
    }
    val autoOff = if (st.testOn && st.autoSyncOn == false)
        " The watch's Auto time sync is off, so it won't call: switch it on below (connect first)." else ""
    return if (hasSavedWatch) main + autoOff
    else "$main No watch saved yet: connect once so the test can connect to it."
}

@Composable
private fun AccentColorPicker(vm: WatchViewModel) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Accent color")
        Spacer(Modifier.weight(1f))
        AccentColors.forEachIndexed { index, color ->
            val selected = vm.accentIndex == index
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(color)
                    .border(
                        width = if (selected) 2.dp else 0.dp,
                        color = if (selected) Color.White else Color.Transparent,
                        shape = CircleShape
                    )
                    .clickable { vm.selectAccent(index) }
            )
        }
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label)
        Spacer(Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ChoiceRow(label: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label)
        Spacer(Modifier.weight(1f))
        var open by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { open = true }) {
                Text(options.getOrElse(selected) { "?" })
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEachIndexed { i, o ->
                    DropdownMenuItem(text = { Text(o) }, onClick = { onSelect(i); open = false })
                }
            }
        }
    }
}

@Composable
private fun NumField(label: String, value: String, mod: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = { onChange(it.filter(Char::isDigit).take(6)) },
        label = { Text(label) }, modifier = mod,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true
    )
}
