package dev.trrrst.watch.ble

/**
 * Decides whether a Bluetooth scan can start, and if not, what to tell the user.
 * Pure logic (no Android calls) so it can be unit-tested; [BleManager] gathers the facts.
 */
object ScanCheck {

    const val NO_BLUETOOTH = "This phone has no Bluetooth, so it can't talk to the watch."
    const val BLUETOOTH_OFF = "Bluetooth is off. Turn it on and try again."
    const val LOCATION_OFF = "Location is off. Android 10 needs it on to find the watch. Turn it on and try again."
    const val NOT_ALLOWED = "The app isn't allowed to use Bluetooth. Allow Bluetooth and Location for TRRRST Watch in the phone's app settings, then try again."
    const val CONNECT_FAILED = "Couldn't connect to the watch. Hold the C button on the watch and press Connect again."

    /**
     * @param sdkInt Android API level. Up to Android 11 (API 30), scans only find
     *   devices while Location is switched on; Android 12+ doesn't need it.
     * @return null if the scan can start, otherwise a plain-English message.
     */
    fun problem(
        hasAdapter: Boolean,
        bluetoothOn: Boolean,
        locationOn: Boolean,
        sdkInt: Int,
    ): String? = when {
        !hasAdapter -> NO_BLUETOOTH
        !bluetoothOn -> BLUETOOTH_OFF
        sdkInt <= 30 && !locationOn -> LOCATION_OFF
        else -> null
    }
}
