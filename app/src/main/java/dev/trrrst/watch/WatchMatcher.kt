package dev.trrrst.watch

/** The watch this phone remembers (PLAN Phase 2). [address] is the Bluetooth address
 *  the phone connected to; [mac] is the address the watch reports about itself in
 *  OP_BLE_FEATURES (so far always the same). A watch reset changes both. */
data class SavedWatch(val address: String, val name: String, val mac: String?)

/** "Is this my watch?" decisions. Pure, so they can be unit-tested. */
object WatchMatcher {

    /** After seeing a different F-B100W, keep looking this long for the saved one. */
    const val PREFER_SAVED_MS = 5_000L

    const val NOT_YOUR_WATCH =
        "Not connected: this isn't your saved watch. To switch watches, use Forget this watch in Settings."
    const val SAVED_RECONNECT =
        "Saved this watch. Hold C on the watch and press Connect to sync it."

    enum class ScanPick {
        /** Connect to it straight away. */
        CONNECT_NOW,
        /** Not the saved watch: use it only if the saved one doesn't show up. */
        BACKUP,
    }

    enum class Verdict {
        /** No watch remembered yet: remember this one, then sync. */
        SAVE_AND_SYNC,
        /** It's the saved watch: sync. */
        SYNC,
        /** A different watch: write nothing until the user says it's theirs. */
        ASK,
    }

    fun sameAddress(a: String, b: String): Boolean = a.trim().equals(b.trim(), ignoreCase = true)

    /** An F-B100W showed up during the search. [savedAddress] is null if none is saved. */
    fun onScanResult(savedAddress: String?, address: String): ScanPick =
        if (savedAddress == null || sameAddress(savedAddress, address)) ScanPick.CONNECT_NOW
        else ScanPick.BACKUP

    /** Connected to the watch at [address]. Decides before anything is written to it. */
    fun onConnected(saved: SavedWatch?, address: String): Verdict = when {
        saved == null -> Verdict.SAVE_AND_SYNC
        sameAddress(saved.address, address) -> Verdict.SYNC
        else -> Verdict.ASK
    }
}
