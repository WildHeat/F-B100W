package dev.trrrst.watch.listen

/** Plain-English "Phone setup" advice for Settings → Background test (PLAN Phase 3,
 *  Part 2). Pure, unit-tested. MIUI (and other makers' battery savers) hold back or drop
 *  the listening alarms unless the app is allowed to run in the background. */
object PhoneSetup {
    /** What the app can see about the phone. [batteryOptimised] is null if unknown. */
    data class State(
        val batteryOptimised: Boolean?,
        val exactAlarms: Boolean,
        val isXiaomi: Boolean,
    )

    fun batteryLine(s: State): String = when (s.batteryOptimised) {
        true -> "Battery optimisation: on. Android may delay or skip the listening times."
        false -> "Battery optimisation: off. Good."
        null -> "Battery optimisation: couldn't check."
    }

    fun alarmLine(s: State): String =
        if (s.exactAlarms) "Alarms: on time (alarm-clock alarm; a small alarm icon may show in the status bar)."
        else "Alarms: this Android version doesn't let the app set exact alarms, so listening may start late."

    /** The steps to follow, in order. Autostart can't be read by the app, so it's always listed. */
    fun steps(s: State): List<String> = buildList {
        if (s.isXiaomi) {
            add("Autostart: tap \"Autostart\" below and switch it on for TRRRST Watch. Without it, MIUI blocks the alarms and the restart re-arm.")
            add("Battery saver: tap \"App info\", then Battery saver → No restrictions.")
        } else {
            add("Background: tap \"App info\" and allow the app to run in the background (the wording depends on the phone).")
        }
        if (s.batteryOptimised != false) {
            add("Battery optimisation: tap \"Battery optimisation\", find TRRRST Watch and choose \"Don't optimise\".")
        }
        if (s.isXiaomi) {
            add("Recents: open the recent apps list, press and hold TRRRST Watch and tap the lock, so cleaning up doesn't close it.")
        }
        add("Then use \"Test the wake-up\" to check it works.")
    }

    /** Xiaomi, Redmi and POCO phones run MIUI / HyperOS. */
    fun isXiaomi(manufacturer: String?, brand: String?): Boolean =
        listOf(manufacturer, brand).any { it != null && it.lowercase() in setOf("xiaomi", "redmi", "poco") }
}
