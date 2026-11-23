package dev.trrrst.watch.listen

/** The listening scan's rhythm (PLAN Phase 3, Part 2b). Pure, unit-tested.
 *
 *  On 23 Nov a scan that ran for a whole window, started while the screen was off, stopped
 *  passing adverts to the app although the phone's Bluetooth chip received them (the
 *  watch's 00:30 call and a D press were both missed). izivkov's Smart Sync scans in
 *  short bursts on Android 11 and older (5 s on, 3 s off), so the listener does the same. */
object ScanBursts {
    const val ON_MS = 5_000L
    const val OFF_MS = 3_000L

    /** Android refuses (silently) a sixth scan start within 30 s from the same app. */
    const val ANDROID_LIMIT_STARTS = 5
    const val ANDROID_LIMIT_WINDOW_MS = 30_000L

    /** The most scan starts that can fall within any [windowMs] stretch. */
    fun maxStartsWithin(windowMs: Long, onMs: Long = ON_MS, offMs: Long = OFF_MS): Int {
        val period = onMs + offMs
        require(period > 0)
        // Starts at 0, period, 2*period, ...; a window of windowMs can hold floor(w/p)+1 of them.
        return (windowMs / period).toInt() + 1
    }
}
